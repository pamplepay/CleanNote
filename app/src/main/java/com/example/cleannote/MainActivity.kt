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
private const val IS_DEBUG = true

// 결제사 / 화면 크기는 AppConfig.kt 에서 빌드 전에 설정합니다.
// → BUILD_PAYMENT_PROVIDER, BUILD_SCREEN_SIZE

class MainActivity : ComponentActivity() {
    private lateinit var paymentLauncher: ActivityResultLauncher<Intent>
    private var mainWebView: WebView? = null
    private var isDeviceInitialized = false

    // 설정 관련 상수
    private val PREFS_NAME = "cleanpos_config"
    private val KEY_HOST = "server_host"
    private val KEY_PORT = "server_port"
    // KEY_PROVIDER / DEFAULT_PROVIDER 는 빌드 타임 상수(BUILD_PAYMENT_PROVIDER)로 대체됨

    private val DEFAULT_HOST = "139.150.82.48"
    private val DEFAULT_PORT = "8023"

    // 가맹점 정보 상수
    private val KEY_MERCHANT_NAME = "merchant_name"
    private val KEY_MERCHANT_ADDRESS = "merchant_address"
    private val DEFAULT_MERCHANT_NAME = ""
    private val DEFAULT_MERCHANT_ADDRESS = ""

    private fun sendLogToWeb(tag: String, message: String) {
        val fullLog = "[$tag] $message"
        Log.d("CleanNoteLog", fullLog)
        runOnUiThread {
            val escapedLog = fullLog.replace("'", "\\'").replace("\n", " ")
            mainWebView?.evaluateJavascript("window.onPaymentLog('$escapedLog')", null)
        }
    }

    // 결과 팝업 (인쇄 선택 기능 포함)
    private fun showPaymentResultDialog(
        title: String,
        message: String,
        showPrintOption: Boolean = false,
        onPrintAction: (() -> Unit)? = null
    ) {
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
                    handleNiceResponse(data)
                } else {
                    handleKiccResponse(data)
                }
            } else {
                showPaymentResultDialog("작업 취소", "요청이 취소되었습니다.")
            }
        }

        checkStoragePermission()

        enableEdgeToEdge()
        setContent {
            CleanNoteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        url = getServerUrl(),
                        modifier = Modifier.padding(innerPadding),
                        onWebViewCreated = {
                            mainWebView = it
                            if (!isDeviceInitialized) initPaymentDevice()
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
                    append("T110  세차노트를 이용해 주셔서 감사합니다.").append(CRLF)
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
                    append("T110  세차노트를 이용해 주셔서 감사합니다.").append(CRLF)
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
                    append("  세차노트를 이용해 주셔서 감사합니다.\n\n\n\n")
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
                    append("  세차노트를 이용해 주셔서 감사합니다.\n\n\n\n")
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
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_MERCHANT_NAME, name)
            putString(KEY_MERCHANT_ADDRESS, address)
            apply()
        }
        Toast.makeText(this, "가맹점 정보가 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, DEFAULT_HOST)?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST
        val port = prefs.getString(KEY_PORT, DEFAULT_PORT)?.takeIf { it.isNotBlank() } ?: DEFAULT_PORT
        val url = "http://$host:$port/"
        Log.d("CleanNoteLog", "[SERVER_URL] 접속 URL: $url")
        return url
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

    // provider 파라미터는 빌드 타임 상수(BUILD_PAYMENT_PROVIDER)로 고정되므로 무시됩니다.
    // 웹 설정 화면에서 VAN 선택/화면 크기 선택 항목은 더 이상 사용하지 않습니다.
    fun saveServerConfig(host: String, port: String, provider: String = "") {
        // 빈 host가 저장되면 앱 실행 시 흰 화면이 발생하므로 방어 처리
        if (host.isBlank()) {
            sendLogToWeb("ERROR", "setServerConfig 무시: host가 비어있음 (현재 설정 유지)")
            Toast.makeText(this, "서버 주소가 비어 있어 설정을 저장하지 않았습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val finalPort = if (port.isBlank()) DEFAULT_PORT else port
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_HOST, host.trim())
            putString(KEY_PORT, finalPort.trim())
            // VAN은 빌드 타임 고정값 사용 - SharedPreferences에 저장하지 않음
            apply()
        }
        sendLogToWeb("CONFIG_SAVED", "host='${host.trim()}' port='${finalPort.trim()}' | VAN 고정값: $BUILD_PAYMENT_PROVIDER | 화면크기: ${BUILD_SCREEN_SIZE}인치")
        Toast.makeText(this, "설정 저장 완료 (VAN: $BUILD_PAYMENT_PROVIDER)", Toast.LENGTH_SHORT).show()
    }
}

class WebAppInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun processPayment(amount: String, datano: String, paymentType: String, installment: String) {
        activity.runOnUiThread { activity.startPaymentApp(amount, datano, paymentType, installment) }
    }

    // provider 파라미터는 하위 호환성을 위해 유지하지만 실제로는 BUILD_PAYMENT_PROVIDER 사용
    @JavascriptInterface
    fun setServerConfig(host: String, port: String, provider: String) {
        activity.runOnUiThread { activity.saveServerConfig(host, port, provider) }
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

    // 웹에서 빌드 고정값 조회 (동기 호출용)
    // 사용 예: const provider = Android.getPaymentProvider();
    @JavascriptInterface
    fun getPaymentProvider(): String = BUILD_PAYMENT_PROVIDER

    // 사용 예: const size = Android.getScreenSize();
    @JavascriptInterface
    fun getScreenSize(): String = BUILD_SCREEN_SIZE
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
