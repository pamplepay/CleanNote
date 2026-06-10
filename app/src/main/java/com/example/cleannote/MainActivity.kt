package com.example.cleannote

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cleannote.ui.theme.CleanNoteTheme
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// 디버그 모드 설정 (true: 파라미터 확인 팝업 표시, false: 즉시 실행)
private const val IS_DEBUG = false

// 결제사 / 화면 크기는 AppConfig.kt 에서 빌드 전에 설정합니다.
// → BUILD_PAYMENT_PROVIDER, BUILD_SCREEN_SIZE

class MainActivity : ComponentActivity() {
    private lateinit var paymentLauncher: ActivityResultLauncher<Intent>
    private lateinit var barcodeLauncher: ActivityResultLauncher<Intent>
    private var mainWebView: WebView? = null
    private var isDeviceInitialized = false
    private var isWebViewLoaded = false       // WebView 최초 로드 완료 여부
    private var isPendingBarcodeScan = false  // 바코드 스캔 진행 중 여부

    // 설정 관련 상수
    private val PREFS_NAME = "cleanpos_config"
    private val KEY_HOST = "server_host"
    private val KEY_PORT = "server_port"
    private val KEY_CURRENT_USER = "current_user"   // 현재 로그인 사용자명
    // KEY_PROVIDER / DEFAULT_PROVIDER 는 빌드 타임 상수(BUILD_PAYMENT_PROVIDER)로 대체됨

    private val DEFAULT_HOST = "cleannote.oilnote.co.kr"
    private val DEFAULT_PORT = ""              // 도메인(https)은 기본 포트 사용 → 생략
    private val OLD_DEFAULT_HOST = "139.150.82.48"  // 구 기본 IP (도메인으로 자동 전환용)

    // 가맹점 정보 상수
    private val KEY_MERCHANT_NAME = "merchant_name"
    private val KEY_MERCHANT_ADDRESS = "merchant_address"
    private val DEFAULT_MERCHANT_NAME = ""
    private val DEFAULT_MERCHANT_ADDRESS = ""

    // 영수증 하단 문구 (웹에서 setReceiptFooter()로 설정)
    private val KEY_RECEIPT_FOOTER = "receipt_footer"
    private val DEFAULT_RECEIPT_FOOTER = "세차노트를 이용해 주셔서 감사합니다."

    // 영수증 자동출력 (웹에서 setReceiptAutoPrint()로 설정, 기본 ON)
    private val KEY_RECEIPT_AUTO_PRINT = "receipt_auto_print"
    private val DEFAULT_RECEIPT_AUTO_PRINT = true

    // ── 사용자별 설정 키 생성 ──────────────────────────────────
    // 로그인한 사용자명을 prefix로 붙여 사용자마다 독립적인 설정을 저장합니다.
    // 예) 사용자 "1111" → "1111_server_host", "1111_server_port"
    // 사용자가 없으면 기기 공통 키(server_host)를 그대로 사용합니다.
    private fun userKey(baseKey: String): String {
        val user = getCurrentUser()
        return if (user.isNotEmpty()) "${user}_$baseKey" else baseKey
    }

    fun getCurrentUser(): String {
        return (getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_USER, "") ?: "").trim()
    }

    // 웹 로그인 완료 후 호출 → 사용자별 서버 설정으로 전환 + WebView 재접속
    fun setCurrentUser(username: String) {
        val cleanUser = username.trim()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CURRENT_USER, cleanUser)
            .apply()
        Log.d("CleanNoteLog", "[USER] 현재 사용자: '$cleanUser'")
        // 해당 사용자 전용 서버 URL로 WebView 재접속
        val url = getServerUrl()
        sendLogToWeb("USER_SWITCH", "사용자: '$cleanUser' → 서버: $url")
        runOnUiThread { mainWebView?.loadUrl(url) }
    }

    private fun sendLogToWeb(tag: String, message: String) {
        val fullLog = "[$tag] $message"
        Log.d("CleanNoteLog", fullLog)
        runOnUiThread {
            val escapedLog = fullLog.replace("'", "\\'").replace("\n", " ")
            // 웹에 함수가 없는 경우 JS 에러 방지
            mainWebView?.evaluateJavascript(
                "if(typeof window.onPaymentLog === 'function') window.onPaymentLog('$escapedLog')",
                null
            )
        }
    }

    // 결과 팝업 (인쇄 선택 기능 포함)
    private fun showPaymentResultDialog(
        title: String,
        message: String,
        showPrintOption: Boolean = false,
        onPrintAction: (() -> Unit)? = null
    ) {
        // 영수증 자동출력 ON + 인쇄 가능한 결과면 → 팝업 없이 즉시 인쇄
        if (showPrintOption && onPrintAction != null && getReceiptAutoPrint()) {
            Log.d("CleanNoteLog", "[RECEIPT_AUTO_PRINT] 자동출력 ON → 팝업 생략, 즉시 인쇄")
            runOnUiThread { onPrintAction() }
            return
        }
        runOnUiThread {
            val builder = AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)

            if (showPrintOption && onPrintAction != null) {
                builder.setPositiveButton("인쇄") { dialog, _ ->
                    onPrintAction()
                    dialog.dismiss()
                }
                builder.setNegativeButton("닫기") { dialog, _ ->
                    dialog.dismiss()
                }
            } else {
                builder.setPositiveButton("확인") { dialog, _ ->
                    dialog.dismiss()
                }
            }
            builder.show()
        }
    }

    private fun showIntentDebugDialog(title: String, intent: Intent, onConfirm: () -> Unit) {
        val sb = StringBuilder()
        sb.append("Action: ${intent.action}\n")
        sb.append("Type: ${intent.type}\n\n")
        val extras = intent.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                sb.append("$key: ${extras.get(key)}\n")
            }
        }
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("전송 파라미터:\n\n$sb")
                .setPositiveButton("실행") { _, _ -> onConfirm() }
                .setNegativeButton("취소", null)
                .setCancelable(false)
                .show()
        }
    }

    // 앱 설치 여부 확인
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // 안전한 Intent 실행 (ActivityNotFoundException 방어)
    private fun safeLaunchIntent(intent: Intent, appLabel: String = "결제 앱") {
        try {
            paymentLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            val pkg = intent.component?.packageName ?: intent.action ?: "알 수 없음"
            sendLogToWeb("ERROR", "App not found: $pkg")
            showPaymentResultDialog(
                "$appLabel 오류",
                "${appLabel}을 찾을 수 없습니다.\n앱이 설치되어 있는지 확인해 주세요.\n\n패키지: $pkg"
            )
        } catch (e: Exception) {
            sendLogToWeb("ERROR", "Launch Failed: ${e.message}")
            showPaymentResultDialog("실행 오류", "앱 실행 중 오류가 발생했습니다.\n${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        paymentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                // 응답 포맷으로 결제사 판별: NICE는 NVCATRETURNCODE extra를 포함
                if (data.hasExtra("NVCATRETURNCODE") || data.hasExtra("NVCATRECVDATA")) {
                    // A002 바코드 응답 분기 (type1="A0", type2="02")
                    val recvRaw = data.getStringExtra("NVCATRECVDATA") ?: ""
                    val isBarcode = try {
                        val j = org.json.JSONObject(recvRaw)
                        j.optString("type1") == "A0" && j.optString("type2") == "02"
                    } catch (e: Exception) { false }

                    if (isBarcode) {
                        handleBarcodeScanResult(data)
                    } else {
                        handleNiceResponse(data)
                    }
                } else {
                    handleKiccResponse(data)
                }
            } else {
                // 바코드 스캔 대기 중이었다면 취소 콜백 전달
                if (isPendingBarcodeScan) {
                    isPendingBarcodeScan = false
                    Log.d("CleanNoteLog", "[BARCODE] 스캔 취소, resultCode=${result.resultCode}")
                    runOnUiThread {
                        mainWebView?.evaluateJavascript("window.onBarcodeResult(null)", null)
                    }
                } else {
                    showPaymentResultDialog("작업 취소", "요청이 취소되었습니다.")
                }
            }
        }

        // barcodeLauncher는 paymentLauncher 백업용으로 유지
        barcodeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            isPendingBarcodeScan = false
            if (result.resultCode == RESULT_OK && data != null) {
                handleBarcodeScanResult(data)
            } else {
                Log.d("CleanNoteLog", "[BARCODE] 스캔 취소 또는 실패, resultCode=${result.resultCode}")
                runOnUiThread {
                    mainWebView?.evaluateJavascript("window.onBarcodeResult(null)", null)
                }
            }
        }

        checkStoragePermission()

        enableEdgeToEdge()
        // getServerUrl()을 setContent 바깥에서 1회만 평가 → Compose 재구성 시 반복 호출 방지
        val initialUrl = getServerUrl()
        Log.d("CleanNoteLog", "[SERVER_URL] 초기 접속: $initialUrl")
        setContent {
            CleanNoteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        url = initialUrl,
                        modifier = Modifier.padding(innerPadding),
                        onWebViewCreated = {
                            mainWebView = it
                            if (!isDeviceInitialized) initPaymentDevice()
                            isWebViewLoaded = false
                        },
                        onPageReady = {
                            // 페이지 로드 완료 후 빌드 고정값을 웹으로 전달
                            sendBuildConfigToWeb()
                        }
                    )
                }
            }
        }
    }

    // ---------- KICC 응답 처리 ----------
    private fun handleKiccResponse(data: Intent) {
        val resultCode = (data.getStringExtra("RESULT_CODE") ?: data.extras?.get("RESULT_CODE")?.toString() ?: "").trim()
        val resultMsg = (data.getStringExtra("RESULT_MSG") ?: data.extras?.get("RESULT_MSG")?.toString() ?: "").trim()
        val tranType = (data.getStringExtra("TRAN_TYPE") ?: data.extras?.get("TRAN_TYPE")?.toString() ?: "").trim()

        if (tranType == "F1") {
            if (resultCode == "0000") {
                isDeviceInitialized = true
                sendLogToWeb("INIT_SUCCESS", "장치 초기화 성공")
            } else {
                sendLogToWeb("INIT_FAILED", "초기화 실패: $resultMsg ($resultCode)")
            }
            return
        }

        if (tranType == "F5") {
            sendLogToWeb("PRINT_RESULT", "Code: $resultCode, Msg: $resultMsg")
            return
        }

        val approvalNum = (data.getStringExtra("APPROVAL_NUM") ?: "").trim()
        val approvalDate = (data.getStringExtra("APPROVAL_DATE") ?: "").trim()
        val cardName = (data.getStringExtra("CARD_NAME") ?: "").trim()
        val cardNum = (data.getStringExtra("CARD_NUM") ?: "").trim()
        val totalAmount = (data.getStringExtra("TOTAL_AMOUNT") ?: "").trim()

        val isSuccess = resultCode == "0000"
        val status = if (isSuccess) "success" else "fail"
        val resultJson = """{ "status": "$status", "resultCode": "$resultCode", "resultMsg": "$resultMsg", "approvalNum": "$approvalNum", "approvalDate": "$approvalDate", "cardName": "$cardName", "cardNum": "$cardNum", "totalAmount": "$totalAmount" }"""

        mainWebView?.evaluateJavascript("window.onPaymentResult($resultJson)", null)

        if (isSuccess) {
            if (tranType == "D4" || tranType == "A9") {
                mainWebView?.evaluateJavascript("window.onCardCancelResult('0000', '취소성공')", null)
                showPaymentResultDialog(
                    title = "취소 성공",
                    message = "금액: ${totalAmount}원\n승인번호: $approvalNum\n카드: $cardName\n\n취소 영수증을 인쇄하시겠습니까?",
                    showPrintOption = true,
                    onPrintAction = {
                        printKiccCancelReceipt(totalAmount, cardName, cardNum, approvalNum, approvalDate)
                    }
                )
            } else if (tranType == "B1") {
                showPaymentResultDialog(
                    title = "현금영수증 발행 성공",
                    message = "금액: ${totalAmount}원\n승인번호: $approvalNum\n\n영수증을 인쇄하시겠습니까?",
                    showPrintOption = true,
                    onPrintAction = {
                        printKiccReceipt(totalAmount, "현금영수증", "", approvalNum, approvalDate)
                    }
                )
            } else {
                mainWebView?.evaluateJavascript("onCardApproveResult('$approvalNum', '$approvalDate', '$cardName')", null)
                showPaymentResultDialog(
                    title = "결제 성공",
                    message = "금액: ${totalAmount}원\n승인번호: $approvalNum\n카드: $cardName\n\n영수증을 인쇄하시겠습니까?",
                    showPrintOption = true,
                    onPrintAction = {
                        printKiccReceipt(totalAmount, cardName, cardNum, approvalNum, approvalDate)
                    }
                )
            }
        } else {
            val errorTitle = when (tranType) {
                "D4", "A9" -> "취소 실패"
                "B1" -> "현금영수증 실패"
                else -> "결제 실패"
            }
            showPaymentResultDialog(errorTitle, "사유: $resultMsg\n오류코드: $resultCode")
        }
    }

    // ---------- NICE 응답 처리 ----------
    private fun handleNiceResponse(data: Intent) {
        val nvcatReturnCode = data.extras?.getInt("NVCATRETURNCODE", 99) ?: 99
        val nvcatRecvData = data.getStringExtra("NVCATRECVDATA") ?: ""

        if (nvcatRecvData.isEmpty()) {
            showPaymentResultDialog("응답 없음", "결제 앱에서 응답을 받지 못했습니다.\nNVCATRETURNCODE: $nvcatReturnCode")
            return
        }

        try {
            val recvJson = JSONObject(nvcatRecvData)
            val type1 = recvJson.optString("type1", "")
            val type2 = recvJson.optString("type2", "")
            val respData = recvJson.optJSONObject("data") ?: JSONObject()

            val resultCode = respData.optString("RA63", "").trim()
            val resultMsg = respData.optString("RA71", "").trim()
            val approvalNum = respData.optString("RA70", "").trim()
            val approvalDate = respData.optString("RA69", "").trim()
            val cardName = respData.optString("RA66", "").trim()
            val cardNum = respData.optString("RA64", "").trim()
            val totalAmount = respData.optString("RA04", "").trim()

            val transType = "$type1$type2"

            // 인쇄 요청 응답 (A001)
            if (transType == "A001") {
                sendLogToWeb("PRINT_RESULT", "Code: $resultCode, Msg: $resultMsg")
                return
            }

            val isSuccess = resultCode == "0000"
            val status = if (isSuccess) "success" else "fail"
            val resultJson = """{ "status": "$status", "resultCode": "$resultCode", "resultMsg": "$resultMsg", "approvalNum": "$approvalNum", "approvalDate": "$approvalDate", "cardName": "$cardName", "cardNum": "$cardNum", "totalAmount": "$totalAmount" }"""

            mainWebView?.evaluateJavascript("window.onPaymentResult($resultJson)", null)

            if (isSuccess) {
                when (transType) {
                    "CC50" -> {
                        mainWebView?.evaluateJavascript("window.onCardCancelResult('0000', '취소성공')", null)
                        showPaymentResultDialog(
                            title = "취소 성공",
                            message = "금액: ${totalAmount}원\n승인번호: $approvalNum\n카드: $cardName\n\n취소 영수증을 인쇄하시겠습니까?",
                            showPrintOption = true,
                            onPrintAction = {
                                printNiceCancelReceipt(totalAmount, cardName, cardNum, approvalNum, approvalDate)
                            }
                        )
                    }
                    "RA10" -> {
                        showPaymentResultDialog(
                            title = "현금영수증 발행 성공",
                            message = "금액: ${totalAmount}원\n승인번호: $approvalNum\n\n영수증을 인쇄하시겠습니까?",
                            showPrintOption = true,
                            onPrintAction = {
                                printNiceReceipt(totalAmount, "현금영수증", "", approvalNum, approvalDate)
                            }
                        )
                    }
                    else -> {
                        mainWebView?.evaluateJavascript("onCardApproveResult('$approvalNum', '$approvalDate', '$cardName')", null)
                        showPaymentResultDialog(
                            title = "결제 성공",
                            message = "금액: ${totalAmount}원\n승인번호: $approvalNum\n카드: $cardName\n\n영수증을 인쇄하시겠습니까?",
                            showPrintOption = true,
                            onPrintAction = {
                                printNiceReceipt(totalAmount, cardName, cardNum, approvalNum, approvalDate)
                            }
                        )
                    }
                }
            } else {
                val errorTitle = when (transType) {
                    "CC50" -> "취소 실패"
                    "RA10" -> "현금영수증 실패"
                    else -> "결제 실패"
                }
                showPaymentResultDialog(errorTitle, "사유: $resultMsg\n오류코드: $resultCode")
            }
        } catch (e: Exception) {
            sendLogToWeb("ERROR", "Response parse failed: ${e.message}")
            showPaymentResultDialog("응답 파싱 오류", "응답 데이터 파싱에 실패했습니다.\n${e.message}")
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 1001)
            }
        }
    }

    // ===================================================================
    // 결제 진입점 (provider 플래그에 따라 KICC / NICE 경로로 분기)
    // ===================================================================
    fun startPaymentApp(amount: String, datano: String, paymentType: String, installment: String) {
        val provider = getPaymentProvider()
        sendLogToWeb("PROVIDER", "현재 결제사: $provider | 결제유형: $paymentType | 금액: $amount")
        when (provider) {
            PROVIDER_NICE -> {
                when (paymentType) {
                    "D4" -> startNiceCancel(amount, datano, installment)
                    "A9" -> startNiceSerialCancel(amount, datano, installment)
                    "B1" -> startNiceCashReceipt(amount, datano)
                    else -> startNicePayment(amount, datano, paymentType, installment)
                }
            }
            else -> { // PROVIDER_KICC (기본값)
                when (paymentType) {
                    "D4" -> startKiccCancel(amount, datano, installment)
                    "A9" -> startKiccSerialCancel(amount, datano, installment)
                    "B1" -> startKiccCashReceipt(amount, datano)
                    else -> startKiccPayment(amount, datano, paymentType, installment)
                }
            }
        }
    }

    // ===================================================================
    // 영수증 인쇄 공용 진입점 (provider에 따라 분기)
    // ===================================================================
    fun printReceipt(amount: String, cardName: String, cardNum: String, approvalNum: String, approvalDate: String) {
        when (getPaymentProvider()) {
            PROVIDER_NICE -> printNiceReceipt(amount, cardName, cardNum, approvalNum, approvalDate)
            else -> printKiccReceipt(amount, cardName, cardNum, approvalNum, approvalDate)
        }
    }

    fun printCancelReceipt(amount: String, cardName: String, cardNum: String, approvalNum: String, approvalDate: String) {
        when (getPaymentProvider()) {
            PROVIDER_NICE -> printNiceCancelReceipt(amount, cardName, cardNum, approvalNum, approvalDate)
            else -> printKiccCancelReceipt(amount, cardName, cardNum, approvalNum, approvalDate)
        }
    }

    // ===================================================================
    // 장치 초기화 진입점 (KICC만 F1 전문 전송, NICE는 no-op)
    // ===================================================================
    fun initPaymentDevice() {
        val provider = getPaymentProvider()
        if (provider == PROVIDER_NICE) {
            isDeviceInitialized = true
            return
        }
        // KICC: F1 장치구동 초기화 전문
        if (!isAppInstalled("kr.co.kicc.aproject")) {
            sendLogToWeb("ERROR", "KICC 앱이 설치되어 있지 않습니다.")
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
            putExtra("TRAN_NO", createDefaultDataNo())
            putExtra("TRAN_TYPE", "F1")
            putExtra("PACKAGE_NAME", packageName)
        }
        safeLaunchIntent(intent, "KICC 결제 앱")
    }

    // ===================================================================
    // KICC 결제 요청 함수들
    // ===================================================================
    private fun startKiccPayment(amount: String, datano: String, paymentType: String, installment: String) {
        if (!isAppInstalled("kr.co.kicc.aproject")) {
            showPaymentResultDialog("KICC 결제 앱 오류", "KICC 결제 앱을 찾을 수 없습니다.\n앱이 설치되어 있는지 확인해 주세요.\n\n패키지: kr.co.kicc.aproject")
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
            putExtra("TRAN_NO", if (datano.isEmpty()) createDefaultDataNo() else datano)
            putExtra("TRAN_TYPE", when(paymentType) { "8" -> "D1"; "0", "0R" -> "D2"; else -> paymentType })
            val kiccInstall = if (installment.length == 1) "0$installment" else if (installment.isEmpty()) "00" else installment
            putExtra("TOTAL_AMOUNT", amount)
            putExtra("INSTALLMENT", kiccInstall)
            putExtra("PACKAGE_NAME", packageName)
        }
        if (IS_DEBUG) showIntentDebugDialog("KICC 승인", intent) { safeLaunchIntent(intent, "KICC 결제 앱") }
        else safeLaunchIntent(intent, "KICC 결제 앱")
    }

    private fun startKiccCashReceipt(amount: String, datano: String) {
        if (!isAppInstalled("kr.co.kicc.aproject")) {
            showPaymentResultDialog("KICC 결제 앱 오류", "KICC 결제 앱을 찾을 수 없습니다.\n앱이 설치되어 있는지 확인해 주세요.\n\n패키지: kr.co.kicc.aproject")
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
            putExtra("TRAN_NO", if (datano.isEmpty()) createDefaultDataNo() else datano)
            putExtra("TRAN_TYPE", "B1")
            putExtra("TOTAL_AMOUNT", amount)
            putExtra("INSTALLMENT", "00")
            putExtra("PACKAGE_NAME", packageName)
        }
        if (IS_DEBUG) showIntentDebugDialog("KICC 현금영수증", intent) { safeLaunchIntent(intent, "KICC 결제 앱") }
        else safeLaunchIntent(intent, "KICC 결제 앱")
    }

    private fun startKiccCancel(amount: String, datano: String, installment: String) {
        if (!isAppInstalled("kr.co.kicc.aproject")) {
            showPaymentResultDialog("KICC 결제 앱 오류", "KICC 결제 앱을 찾을 수 없습니다.\n앱이 설치되어 있는지 확인해 주세요.\n\n패키지: kr.co.kicc.aproject")
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
            putExtra("TRAN_NO", if (datano.isEmpty()) createDefaultDataNo() else datano)
            putExtra("TRAN_TYPE", "D4")
            putExtra("TOTAL_AMOUNT", amount)
            val parts = installment.split("|")
            if (parts.size >= 3) {
                putExtra("INSTALLMENT", "00")
                putExtra("APPROVAL_NO", parts[1].trim())
                val date = parts[2].trim().let { if (it.length == 8) it.substring(2) else it }
                putExtra("APPROVAL_DATE", date)
            }
        }
        if (IS_DEBUG) showIntentDebugDialog("KICC 취소", intent) { safeLaunchIntent(intent, "KICC 결제 앱") }
        else safeLaunchIntent(intent, "KICC 결제 앱")
    }

    private fun startKiccSerialCancel(amount: String, datano: String, installment: String) {
        if (!isAppInstalled("kr.co.kicc.aproject")) {
            showPaymentResultDialog("KICC 결제 앱 오류", "KICC 결제 앱을 찾을 수 없습니다.\n앱이 설치되어 있는지 확인해 주세요.\n\n패키지: kr.co.kicc.aproject")
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
            putExtra("TRAN_NO", if (datano.isEmpty()) createDefaultDataNo() else datano)
            putExtra("TRAN_TYPE", "A9")
            putExtra("TOTAL_AMOUNT", amount)
            val parts = installment.split("|")
            if (parts.size >= 3) {
                putExtra("INSTALLMENT", "00")
                putExtra("APPROVAL_NO", parts[1].trim())
                val date = parts[2].trim().let { if (it.length == 8) it.substring(2) else it }
                putExtra("APPROVAL_DATE", date)
            }
            putExtra("OPTION_FIELD", "A9#$datano")
            putExtra("PACKAGE_NAME", packageName)
        }
        if (IS_DEBUG) showIntentDebugDialog("KICC 일련번호 취소", intent) { safeLaunchIntent(intent, "KICC 결제 앱") }
        else safeLaunchIntent(intent, "KICC 결제 앱")
    }

    // ===================================================================
    // KICC 영수증 인쇄 (KICC 프린터 명령 포맷 + 파일 경로 전달)
    // ===================================================================
    fun printKiccReceipt(amount: String, cardName: String, cardNum: String, approvalNum: String, approvalDate: String) {
        thread {
            try {
                val CRLF = "\r\n"
                val merchantName = getMerchantName()
                val merchantAddress = getMerchantAddress()
                val receiptFooter = getReceiptFooter()
                val sb = StringBuilder().apply {
                    append("C").append(CRLF)
                    if (cardName == "현금영수증") append("T220   [ 현 금 영 수 증 ]").append(CRLF)
                    else append("T220      [ 영 수 증 ]").append(CRLF)
                    append("L24").append(CRLF)
                    if (merchantName.isNotEmpty()) append("T110가맹점명 : ${merchantName}").append(CRLF)
                    if (merchantAddress.isNotEmpty()) append("T110주    소 : ${merchantAddress}").append(CRLF)
                    if (merchantName.isNotEmpty() || merchantAddress.isNotEmpty()) append("T110--------------------------------").append(CRLF)
                    append("T110금    액 : ${amount}원").append(CRLF)
                    if (cardName != "현금영수증") {
                        append("T110카 드 명 : ${cardName}").append(CRLF)
                        append("T110카드번호 : ${cardNum}").append(CRLF)
                    }
                    append("T110승인번호 : ${approvalNum}").append(CRLF)
                    append("T110승인일시 : ${approvalDate}").append(CRLF)
                    append("T110--------------------------------").append(CRLF)
                    append("T110  ${receiptFooter}").append(CRLF)
                    append("L120").append(CRLF)
                    append("PCF").append(CRLF)
                }
                val file = File(Environment.getExternalStorageDirectory(), "print_kicc.txt")
                if (file.exists()) file.delete()
                file.writeBytes(sb.toString().toByteArray(charset("EUC-KR")))
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
                        putExtra("TRAN_NO", createDefaultDataNo())
                        putExtra("TRAN_TYPE", "F5")
                        putExtra("PRINT_DATA", file.absolutePath)
                        putExtra("PACKAGE_NAME", packageName)
                    }
                    safeLaunchIntent(intent, "KICC 결제 앱")
                }
            } catch (e: Exception) { sendLogToWeb("ERROR", "Print Failed: ${e.message}") }
        }
    }

    fun printKiccCancelReceipt(amount: String, cardName: String, cardNum: String, approvalNum: String, approvalDate: String) {
        thread {
            try {
                val CRLF = "\r\n"
                val merchantName = getMerchantName()
                val merchantAddress = getMerchantAddress()
                val receiptFooter = getReceiptFooter()
                val sb = StringBuilder().apply {
                    append("C").append(CRLF)
                    append("T220   [ 취 소 영 수 증 ]").append(CRLF)
                    append("L24").append(CRLF)
                    if (merchantName.isNotEmpty()) append("T110가맹점명 : ${merchantName}").append(CRLF)
                    if (merchantAddress.isNotEmpty()) append("T110주    소 : ${merchantAddress}").append(CRLF)
                    if (merchantName.isNotEmpty() || merchantAddress.isNotEmpty()) append("T110--------------------------------").append(CRLF)
                    append("T110취소금액 : ${amount}원").append(CRLF)
                    if (cardName.isNotEmpty()) {
                        append("T110카 드 명 : ${cardName}").append(CRLF)
                        append("T110카드번호 : ${cardNum}").append(CRLF)
                    }
                    append("T110승인번호 : ${approvalNum}").append(CRLF)
                    append("T110취소일시 : ${approvalDate}").append(CRLF)
                    append("T110--------------------------------").append(CRLF)
                    append("T110  ${receiptFooter}").append(CRLF)
                    append("L120").append(CRLF)
                    append("PCF").append(CRLF)
                }
                val file = File(Environment.getExternalStorageDirectory(), "print_kicc.txt")
                if (file.exists()) file.delete()
                file.writeBytes(sb.toString().toByteArray(charset("EUC-KR")))
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName("kr.co.kicc.aproject", "kr.co.kicc.aproject.callpopup.CallPopup")
                        putExtra("TRAN_NO", createDefaultDataNo())
                        putExtra("TRAN_TYPE", "F5")
                        putExtra("PRINT_DATA", file.absolutePath)
                        putExtra("PACKAGE_NAME", packageName)
                    }
                    safeLaunchIntent(intent, "KICC 결제 앱")
                }
            } catch (e: Exception) { sendLogToWeb("ERROR", "CancelPrint Failed: ${e.message}") }
        }
    }

    // ===================================================================
    // NICE 공통 유틸 (JSON 전문 구성 + Intent 실행)
    // ===================================================================
    private fun buildNvcatJson(type1: String, type2: String, data: JSONObject): String {
        return JSONObject().apply {
            put("type1", type1)
            put("type2", type2)
            put("type3", "")
            put("data", data)
        }.toString()
    }

    private fun launchNvcat(action: String, jsonData: String, title: String) {
        val intent = Intent().apply {
            setAction(action)
            putExtra("NEWNVCATSENDDATA", jsonData)
            type = "text/plain"
        }
        if (IS_DEBUG) showIntentDebugDialog(title, intent) { safeLaunchIntent(intent, "NICE 결제 앱") }
        else safeLaunchIntent(intent, "NICE 결제 앱")
    }

    // ===================================================================
    // NICE 결제 요청 함수들
    // ===================================================================
    private fun startNicePayment(amount: String, datano: String, paymentType: String, installment: String) {
        val niceInstall = if (installment.length == 1) "0$installment" else if (installment.isEmpty()) "00" else installment
        val data = JSONObject().apply {
            put("SA04", amount)
            put("SA05", niceInstall)
        }
        val json = buildNvcatJson("CA", "10", data)
        launchNvcat("NICEVCAT", json, "NICE 승인")
    }

    private fun startNiceCashReceipt(amount: String, datano: String) {
        val data = JSONObject().apply {
            put("SA04", amount)
            put("SA32", "01")
        }
        val json = buildNvcatJson("RA", "10", data)
        launchNvcat("NICEVCAT", json, "NICE 현금영수증")
    }

    private fun startNiceCancel(amount: String, datano: String, installment: String) {
        val parts = installment.split("|")
        val data = JSONObject().apply {
            put("SA04", amount)
            put("SA05", "00")
            if (parts.size >= 3) {
                put("SA24", parts[1].trim())
                val date = parts[2].trim().let { if (it.length == 8) it.substring(2) else it }
                put("SA25", date)
            }
        }
        val json = buildNvcatJson("CC", "50", data)
        launchNvcat("NICEVCAT", json, "NICE 취소")
    }

    private fun startNiceSerialCancel(amount: String, datano: String, installment: String) {
        val parts = installment.split("|")
        val data = JSONObject().apply {
            put("SA04", amount)
            put("SA05", "00")
            put("SA10", datano)
            if (parts.size >= 3) {
                put("SA24", parts[1].trim())
                val date = parts[2].trim().let { if (it.length == 8) it.substring(2) else it }
                put("SA25", date)
            }
        }
        val json = buildNvcatJson("CC", "50", data)
        launchNvcat("NICEVCAT", json, "NICE 일련번호 취소")
    }

    // ===================================================================
    // NICE 영수증 인쇄 (ESC/POS 포맷 + SZ100 문자열 직접 전달)
    // ===================================================================
    fun printNiceReceipt(amount: String, cardName: String, cardNum: String, approvalNum: String, approvalDate: String) {
        thread {
            try {
                val merchantName = getMerchantName()
                val merchantAddress = getMerchantAddress()
                val receiptFooter = getReceiptFooter()
                val ESC = "\u001b"
                val printData = StringBuilder().apply {
                    append("${ESC}@")
                    append("${ESC}!\u0010")
                    if (cardName == "현금영수증") append("        [ 현 금 영 수 증 ]")
                    else append("           [ 영 수 증 ]")
                    append("${ESC}!\u0000")
                    append("\n\n")
                    if (merchantName.isNotEmpty()) append("가맹점명 : ${merchantName}\n")
                    if (merchantAddress.isNotEmpty()) append("주    소 : ${merchantAddress}\n")
                    if (merchantName.isNotEmpty() || merchantAddress.isNotEmpty()) append("------------------------------------------\n")
                    append("금    액 : ${amount}원\n")
                    if (cardName != "현금영수증") {
                        append("카 드 명 : ${cardName}\n")
                        append("카드번호 : ${cardNum}\n")
                    }
                    append("승인번호 : ${approvalNum}\n")
                    append("승인일시 : ${approvalDate}\n")
                    append("------------------------------------------\n")
                    append("  ${receiptFooter}\n\n\n\n")
                    append("${ESC}i")
                }.toString()

                val data = JSONObject().apply {
                    put("SZ100", printData)
                }
                val json = buildNvcatJson("A0", "01", data)
                runOnUiThread { launchNvcat("NICEVCAT", json, "NICE 인쇄") }
            } catch (e: Exception) {
                sendLogToWeb("ERROR", "Print Failed: ${e.message}")
            }
        }
    }

    fun printNiceCancelReceipt(amount: String, cardName: String, cardNum: String, approvalNum: String, approvalDate: String) {
        thread {
            try {
                val merchantName = getMerchantName()
                val merchantAddress = getMerchantAddress()
                val receiptFooter = getReceiptFooter()
                val ESC = "\u001b"
                val printData = StringBuilder().apply {
                    append("${ESC}@")
                    append("${ESC}!\u0010")
                    append("        [ 취 소 영 수 증 ]")
                    append("${ESC}!\u0000")
                    append("\n\n")
                    if (merchantName.isNotEmpty()) append("가맹점명 : ${merchantName}\n")
                    if (merchantAddress.isNotEmpty()) append("주    소 : ${merchantAddress}\n")
                    if (merchantName.isNotEmpty() || merchantAddress.isNotEmpty()) append("------------------------------------------\n")
                    append("취소금액 : ${amount}원\n")
                    if (cardName.isNotEmpty()) {
                        append("카 드 명 : ${cardName}\n")
                        append("카드번호 : ${cardNum}\n")
                    }
                    append("승인번호 : ${approvalNum}\n")
                    append("취소일시 : ${approvalDate}\n")
                    append("------------------------------------------\n")
                    append("  ${receiptFooter}\n\n\n\n")
                    append("${ESC}i")
                }.toString()

                val data = JSONObject().apply {
                    put("SZ100", printData)
                }
                val json = buildNvcatJson("A0", "01", data)
                runOnUiThread { launchNvcat("NICEVCAT", json, "NICE 취소 인쇄") }
            } catch (e: Exception) {
                sendLogToWeb("ERROR", "CancelPrint Failed: ${e.message}")
            }
        }
    }

    // ===================================================================
    // 공통 유틸
    // ===================================================================
    private fun createDefaultDataNo() = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())

    private fun getMerchantName(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MERCHANT_NAME, DEFAULT_MERCHANT_NAME) ?: DEFAULT_MERCHANT_NAME
    }

    private fun getMerchantAddress(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MERCHANT_ADDRESS, DEFAULT_MERCHANT_ADDRESS) ?: DEFAULT_MERCHANT_ADDRESS
    }

    fun saveMerchantConfig(name: String, address: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldName = prefs.getString(KEY_MERCHANT_NAME, DEFAULT_MERCHANT_NAME) ?: DEFAULT_MERCHANT_NAME
        val oldAddress = prefs.getString(KEY_MERCHANT_ADDRESS, DEFAULT_MERCHANT_ADDRESS) ?: DEFAULT_MERCHANT_ADDRESS
        // 값이 기존과 동일하면 재저장/토스트 생략 (웹이 화면 전환마다 호출해도 중복 토스트 방지)
        if (oldName == name && oldAddress == address) {
            Log.d("CleanNoteLog", "[MERCHANT] 변경 없음 → 저장/토스트 생략")
            return
        }
        prefs.edit().apply {
            putString(KEY_MERCHANT_NAME, name)
            putString(KEY_MERCHANT_ADDRESS, address)
            apply()
        }
        Toast.makeText(this, "가맹점 정보가 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }

    // 영수증 하단 문구 조회 (미설정 시 기본 문구 반환)
    fun getReceiptFooter(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RECEIPT_FOOTER, DEFAULT_RECEIPT_FOOTER)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_RECEIPT_FOOTER
    }

    // 웹에서 Android.setReceiptFooter("문구") 로 호출하여 영수증 하단 문구를 저장
    fun saveReceiptFooter(footer: String) {
        val text = footer.trim()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldRaw = prefs.getString(KEY_RECEIPT_FOOTER, null)
        // 값이 기존과 동일하면 재저장/토스트 생략 (페이지 로드마다 호출돼도 중복 토스트 방지)
        if (oldRaw == text) {
            Log.d("CleanNoteLog", "[RECEIPT_FOOTER] 변경 없음 → 저장/토스트 생략")
            return
        }
        prefs.edit().putString(KEY_RECEIPT_FOOTER, text).apply()
        Log.d("CleanNoteLog", "[RECEIPT_FOOTER] 저장: '$text'")
        Toast.makeText(this, "영수증 문구가 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }

    // 영수증 자동출력 설정 조회 (미설정 시 기본 ON)
    fun getReceiptAutoPrint(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RECEIPT_AUTO_PRINT, DEFAULT_RECEIPT_AUTO_PRINT)
    }

    // 웹에서 Android.setReceiptAutoPrint(true/false) 로 자동출력 ON/OFF 저장 (토스트 없이 조용히 저장)
    fun saveReceiptAutoPrint(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RECEIPT_AUTO_PRINT, enabled)
            .apply()
        Log.d("CleanNoteLog", "[RECEIPT_AUTO_PRINT] 저장: $enabled")
    }

    // 설치된 앱 버전 조회 (versionName + versionCode) — 세팅 화면 표시용
    fun getAppVersion(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val vName = pInfo.versionName ?: "?"
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pInfo.longVersionCode
            else
                @Suppress("DEPRECATION") pInfo.versionCode.toLong()
            "$vName ($vCode)"
        } catch (e: Exception) {
            "?"
        }
    }

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 1순위: 사용자별 설정 / 2순위: 기기 공통 설정 / 3순위: 하드코딩 기본값
        val rawHost = prefs.getString(userKey(KEY_HOST), null)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_HOST, DEFAULT_HOST)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_HOST
        // 이전에 http:// 가 포함된 채로 저장된 값을 방어 처리
        var host = rawHost.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .trim()
            .ifBlank { DEFAULT_HOST }
        var port = prefs.getString(userKey(KEY_PORT), null)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_PORT, DEFAULT_PORT)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PORT

        // 구 기본 IP(139.150.82.48)로 저장된 기기는 새 도메인으로 자동 전환
        if (host == OLD_DEFAULT_HOST) {
            host = DEFAULT_HOST
            port = ""
        }

        // 프로토콜 자동 결정: 순수 IPv4는 http(하위호환), 도메인은 https
        val isIp = host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
        val scheme = if (isIp) "http" else "https"
        // 기본 포트(https 443 / http 80)는 생략, 그 외만 ":port" 부착
        val portPart = when {
            port.isBlank() -> ""
            scheme == "https" && port == "443" -> ""
            scheme == "http" && port == "80" -> ""
            else -> ":$port"
        }
        return "$scheme://$host$portPart/"
    }

    // 결제사는 AppConfig.kt 의 BUILD_PAYMENT_PROVIDER 값으로 빌드 시 고정됩니다.
    // 런타임에 변경되지 않으므로 SharedPreferences를 읽지 않습니다.
    private fun getPaymentProvider(): String {
        Log.d("CleanNoteLog", "[PROVIDER] 빌드 고정값: $BUILD_PAYMENT_PROVIDER")
        return BUILD_PAYMENT_PROVIDER
    }

    // 페이지 로드 완료 후 빌드 고정값을 웹으로 전달
    // 웹에서 window.onAppConfig(provider, screenSize) 를 구현하면 설정값을 수신합니다.
    fun sendBuildConfigToWeb() {
        runOnUiThread {
            val js = "if(typeof window.onAppConfig === 'function') window.onAppConfig('$BUILD_PAYMENT_PROVIDER', '$BUILD_SCREEN_SIZE');"
            mainWebView?.evaluateJavascript(js, null)
            Log.d("CleanNoteLog", "[BUILD_CONFIG] 웹 전달: VAN=$BUILD_PAYMENT_PROVIDER, 화면크기=${BUILD_SCREEN_SIZE}인치")
        }
    }

    // ── 공통: host/port 문자열을 정리하여 저장 ──────────────────────────
    // 저장만 수행. WebView 이동 없음.
    // 페이지 자동 초기화 호출에서도 안전하게 사용 가능.
    fun saveServerConfig(host: String, port: String, provider: String = "") {
        val cleanHost = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .trim()

        if (cleanHost.isBlank()) {
            sendLogToWeb("ERROR", "setServerConfig 무시: host가 비어있음 (현재 설정 유지)")
            runOnUiThread {
                Toast.makeText(this, "서버 주소가 비어 있어 설정을 저장하지 않았습니다.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val finalPort = if (port.isBlank()) DEFAULT_PORT else port.trim()
        val currentUser = getCurrentUser()

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(userKey(KEY_HOST), cleanHost)
            putString(userKey(KEY_PORT), finalPort)
            apply()
        }

        val newUrl = getServerUrl()   // 저장된 값 기준으로 실제 접속 URL(스킴/포트 반영) 구성
        val userLabel = if (currentUser.isNotEmpty()) "사용자: '$currentUser'" else "기기 공통"
        sendLogToWeb("CONFIG_SAVED", "$userLabel → 서버 저장: $newUrl")
        Log.d("CleanNoteLog", "[CONFIG_SAVED] $userLabel, URL: $newUrl")
    }

    // ── 사용자가 명시적으로 "앱에 설정 전송" 버튼을 눌렀을 때 호출 ──────
    // 저장 + WebView를 새 서버 주소로 즉시 재접속.
    // 웹에서: Android.applyServerConfig(host, port, provider)
    fun applyServerConfig(host: String, port: String, provider: String = "") {
        saveServerConfig(host, port, provider)   // 먼저 저장
        val newUrl = getServerUrl()              // 저장 후 URL 읽기
        val currentUser = getCurrentUser()
        val userLabel = if (currentUser.isNotEmpty()) "사용자: '$currentUser'" else "기기 공통"

        sendLogToWeb("SERVER_APPLY", "$userLabel → 접속: $newUrl")
        Log.d("CleanNoteLog", "[SERVER_APPLY] $userLabel → $newUrl")
        runOnUiThread {
            Toast.makeText(this, "[$userLabel] 서버 변경 → $newUrl", Toast.LENGTH_SHORT).show()
            mainWebView?.loadUrl(newUrl)
        }
    }

    // ===================================================================
    // NICE VCAT 바코드 스캔 (세차권 바코드 읽기 — A002 전문)
    // ===================================================================
    fun requestNvcatBarcode() {
        val json = buildNvcatJson("A0", "02", JSONObject())
        val intent = Intent().apply {
            action = "NICEVCAT"
            putExtra("NEWNVCATSENDDATA", json)
            type = "text/plain"
        }
        Log.d("CleanNoteLog", "[BARCODE] 바코드 스캔 요청: $json")
        try {
            isPendingBarcodeScan = true
            barcodeLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            isPendingBarcodeScan = false
            sendLogToWeb("ERROR", "NICE VCAT 앱을 찾을 수 없습니다.")
            showPaymentResultDialog("바코드 스캔 오류", "NICE VCAT 앱을 찾을 수 없습니다.\n앱이 설치되어 있는지 확인해 주세요.")
        } catch (e: Exception) {
            isPendingBarcodeScan = false
            sendLogToWeb("ERROR", "바코드 스캔 실행 오류: ${e.message}")
            showPaymentResultDialog("바코드 스캔 오류", "앱 실행 중 오류가 발생했습니다.\n${e.message}")
        }
    }

    private fun handleBarcodeScanResult(data: Intent) {
        // NICE VCAT 응답 extra: NVCATRECVDATA (String JSON), NVCATRETURNCODE (int)
        val recvData = (data.getStringExtra("NVCATRECVDATA")
            ?: data.getStringExtra("nvcatRecvData") ?: "").trim()
        val returnCode = data.extras?.getInt("NVCATRETURNCODE", -1)
            ?: data.getStringExtra("nvcatReturnCode")?.toIntOrNull() ?: -1

        Log.d("CleanNoteLog", "[BARCODE] returnCode=$returnCode recvData=$recvData")

        if (recvData.isEmpty()) {
            sendLogToWeb("BARCODE_ERROR", "바코드 응답 없음 (returnCode=$returnCode)")
            runOnUiThread {
                mainWebView?.evaluateJavascript("window.onBarcodeResult(null)", null)
            }
            return
        }

        try {
            val recvJson = JSONObject(recvData)
            val type1 = recvJson.optString("type1", "")
            val type2 = recvJson.optString("type2", "")
            val respData = recvJson.optJSONObject("data") ?: JSONObject()
            val resultCode = respData.optString("RA63", "").trim()

            Log.d("CleanNoteLog", "[BARCODE] type=$type1$type2 resultCode=$resultCode")

            if (resultCode == "0000") {
                // A002 응답의 고객정보 필드(RZ101): 바코드/휴대폰번호 등 스캔 데이터
                val barcodeValue = respData.optString("RZ101", "").trim()
                Log.d("CleanNoteLog", "[BARCODE] 스캔 성공: $barcodeValue")
                if (barcodeValue.isEmpty()) {
                    sendLogToWeb("BARCODE_ERROR", "RZ101 비어있음 | data: $respData")
                    runOnUiThread {
                        mainWebView?.evaluateJavascript("window.onBarcodeResult(null)", null)
                    }
                    return
                }
                sendLogToWeb("BARCODE_SUCCESS", "바코드: $barcodeValue")
                val escapedValue = barcodeValue
                    .replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", "\\n").replace("\r", "\\r")
                runOnUiThread {
                    mainWebView?.evaluateJavascript(
                        "window.onBarcodeResult('$escapedValue')", null
                    )
                }
            } else {
                // PV01 = 취소, 그 외 = 실패
                val resultMsg = respData.optString("RA71", "바코드 스캔 실패").trim()
                val isCancelled = resultCode.startsWith("PV") || resultMsg.contains("취소")
                Log.d("CleanNoteLog", "[BARCODE] ${if (isCancelled) "취소" else "실패"}: $resultMsg ($resultCode)")
                sendLogToWeb("BARCODE_CANCEL", "${if (isCancelled) "취소" else "실패"}: $resultMsg ($resultCode)")
                runOnUiThread {
                    mainWebView?.evaluateJavascript("window.onBarcodeResult(null)", null)
                }
            }
        } catch (e: Exception) {
            sendLogToWeb("BARCODE_ERROR", "응답 파싱 오류: ${e.message}")
            Log.d("CleanNoteLog", "[BARCODE] 파싱 오류: ${e.message}")
            runOnUiThread {
                mainWebView?.evaluateJavascript("window.onBarcodeResult(null)", null)
            }
        }
    }
}

class WebAppInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun processPayment(amount: String, datano: String, paymentType: String, installment: String) {
        activity.runOnUiThread { activity.startPaymentApp(amount, datano, paymentType, installment) }
    }

    // 서버 설정 저장만 수행 (WebView 이동 없음)
    // 페이지 자동 초기화 시 안전하게 사용 가능
    // 사용 예: Android.setServerConfig(host, port, provider)
    @JavascriptInterface
    fun setServerConfig(host: String, port: String, provider: String) {
        activity.saveServerConfig(host, port, provider)
    }

    // 서버 설정 저장 + 즉시 새 서버로 WebView 이동
    // "앱에 설정 전송" 버튼 클릭 시 호출
    // 사용 예: Android.applyServerConfig(host, port, provider)
    @JavascriptInterface
    fun applyServerConfig(host: String, port: String, provider: String) {
        activity.applyServerConfig(host, port, provider)
    }

    @JavascriptInterface
    fun setMerchantConfig(name: String, address: String) {
        activity.runOnUiThread { activity.saveMerchantConfig(name, address) }
    }

    @JavascriptInterface
    fun initDevice() { activity.runOnUiThread { activity.initPaymentDevice() } }

    @JavascriptInterface
    fun printReceipt(amount: String, cardName: String, cardNum: String, approvalNum: String, approvalDate: String) {
        activity.printReceipt(amount, cardName, cardNum, approvalNum, approvalDate)
    }

    // 웹 로그인 완료 후 호출 → 사용자별 서버 설정으로 전환
    // 사용 예: Android.setCurrentUser("1111");
    @JavascriptInterface
    fun setCurrentUser(username: String) {
        activity.setCurrentUser(username)
    }

    // 현재 로그인된 사용자명 조회 (동기)
    // 사용 예: const user = Android.getCurrentUser();
    @JavascriptInterface
    fun getCurrentUser(): String = activity.getCurrentUser()

    // 웹에서 빌드 고정값 조회 (동기 호출용)
    // 사용 예: const provider = Android.getPaymentProvider();
    @JavascriptInterface
    fun getPaymentProvider(): String = BUILD_PAYMENT_PROVIDER

    // 사용 예: const size = Android.getScreenSize();
    @JavascriptInterface
    fun getScreenSize(): String = BUILD_SCREEN_SIZE

    // 영수증 하단 문구 저장
    // 사용 예: Android.setReceiptFooter("감사합니다. 또 방문해 주세요.");
    @JavascriptInterface
    fun setReceiptFooter(footer: String) {
        activity.runOnUiThread { activity.saveReceiptFooter(footer) }
    }

    // 영수증 하단 문구 조회 (동기)
    // 사용 예: const footer = Android.getReceiptFooter();
    @JavascriptInterface
    fun getReceiptFooter(): String = activity.getReceiptFooter()

    // 영수증 자동출력 ON/OFF 저장 (웹 세팅 토글 / 페이지 로드 동기화)
    // 사용 예: Android.setReceiptAutoPrint(true);
    @JavascriptInterface
    fun setReceiptAutoPrint(enabled: Boolean) {
        activity.saveReceiptAutoPrint(enabled)
    }

    // 영수증 자동출력 현재값 조회 (동기)
    // 사용 예: const auto = Android.getReceiptAutoPrint();
    @JavascriptInterface
    fun getReceiptAutoPrint(): Boolean = activity.getReceiptAutoPrint()

    // 설치된 앱 버전 조회 (동기) — 세팅 화면 표시용
    // 사용 예: const v = Android.getAppVersion();  // 예) "0.1 (18)"
    @JavascriptInterface
    fun getAppVersion(): String = activity.getAppVersion()

    // NICE VCAT 바코드 스캔 요청 (세차권 바코드 읽기 — A002 전문)
    // 성공 시 window.onBarcodeResult(barcode) 로 바코드 문자열(RZ101)을 전달합니다.
    // 스캔 실패/취소/빈값 시 window.onBarcodeResult(null) 호출됩니다.
    // 사용 예: Android.startBarcodeScan();
    @JavascriptInterface
    fun startBarcodeScan() {
        activity.runOnUiThread { activity.requestNvcatBarcode() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    url: String,
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView) -> Unit,
    onPageReady: (() -> Unit)? = null
) {
    var webViewInstance: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler(enabled = canGoBack) { webViewInstance?.goBack() }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        canGoBack = view?.canGoBack() ?: false
                        onPageReady?.invoke()   // 페이지 로드 완료 → 빌드 설정값 전달
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(WebAppInterface(ctx as MainActivity), "Android")
                loadUrl(url)
                onWebViewCreated(this)
                webViewInstance = this
            }
        },
        update = { webViewInstance = it }
    )
}