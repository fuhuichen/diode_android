package com.diode.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.diode.android.databinding.ActivityLoadingBinding
import mobile.Mobile
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.TimeZone

/**
 * Loading Activity：啟動 Diode、等待連線、setBinds，完成後進入 WebViewActivity。
 *
 * 弱網處理：
 *   - 每次嘗試最多等 ATTEMPT_TIMEOUT_MS（顯示經過秒數）
 *   - 失敗時提供「重新連線」按鈕，最多重試 MAX_RETRIES 次
 *   - 全部失敗後改顯示「送出 log」按鈕，把 logcat 上傳給後台診斷
 */
class LoadingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoadingBinding
    private val handler = Handler(Looper.getMainLooper())

    private val nodeManager = NodeConnectionManager.instance
    private val api = DiodeApiClient()

    private var attemptCount = 0
    private var attemptDone = false
    private var elapsedSec = 0
    private var attemptStartMs = 0L
    private var lastError: String = ""

    private var pollRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    private var tickRunnable: Runnable? = null

    companion object {
        private const val TAG = "Diode"
        private val DIODE_SOCKS_PORT = BuildConfig.DIODE_SOCKS_PORT
        private val WEBVIEW_PROXY_PORT = BuildConfig.WEBVIEW_PROXY_PORT
        private const val POLL_DELAY = 1500L
        private const val POLL_INTERVAL = 2000L
        /** 單次嘗試最長等待 30 秒（弱網下大約足夠 Diode 完成握手 + setBinds） */
        private const val ATTEMPT_TIMEOUT_MS = 30_000L
        /** 總嘗試次數：第 1 次自動，之後可手動重試最多 2 次 */
        private const val MAX_ATTEMPTS = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoadingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()

        binding.btnRetry.setOnClickListener { startAttempt() }
        binding.btnSendLog.setOnClickListener { sendLogToBackend() }

        startAttempt()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelHandlers()
    }

    private fun cancelHandlers() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        timeoutRunnable = null
        tickRunnable = null
    }

    private fun startAttempt() {
        if (attemptCount >= MAX_ATTEMPTS) {
            showFinalFailure("已達最大重試次數")
            return
        }
        attemptCount++
        attemptDone = false
        elapsedSec = 0
        attemptStartMs = System.currentTimeMillis()
        cancelHandlers()

        binding.btnRetry.visibility = View.GONE
        binding.btnSendLog.visibility = View.GONE
        binding.tvHint.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = if (attemptCount == 1) "隱私保護中" else "重新連線中 (第 $attemptCount/$MAX_ATTEMPTS 次)"
        binding.tvElapsed.text = "已等候 0 秒"

        // 偵錯：允許用 `adb shell setprop debug.diode.peer as1.prenet.diode.io:41046` 指定單一 peer
        val peerOverride = try {
            @Suppress("UNCHECKED_CAST", "PrivateApi")
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "debug.diode.peer", "") as String
        } catch (_: Exception) { "" }
        Log.i(TAG, "Attempt #$attemptCount start, peer=${peerOverride.ifEmpty { "(default 6 peers)" }}")
        // 重啟 Diode 服務，確保乾淨狀態（service 內會 reset + clearBinds + stopDiode + startDiode）
        DiodeForegroundService.start(this, rpcAddrs = peerOverride, socksPort = DIODE_SOCKS_PORT, privateKey = "")

        // 經過秒數計時器（每秒更新一次）
        tickRunnable = object : Runnable {
            override fun run() {
                if (attemptDone) return
                elapsedSec = ((System.currentTimeMillis() - attemptStartMs) / 1000L).toInt()
                binding.tvElapsed.text = "已等候 $elapsedSec 秒（弱網會較久）"
                handler.postDelayed(this, 1000L)
            }
        }
        handler.postDelayed(tickRunnable!!, 1000L)

        // 輪詢 Diode 是否就緒
        pollRunnable = object : Runnable {
            override fun run() {
                if (attemptDone) return
                val address = Mobile.getAddress() ?: ""
                val error = Mobile.getLastError() ?: ""
                when {
                    address.isNotEmpty() -> {
                        onDiodeReady()
                    }
                    else -> {
                        if (error.isNotEmpty()) {
                            lastError = error
                            Log.w(TAG, "Diode init error during attempt #$attemptCount: $error")
                        }
                        handler.postDelayed(this, POLL_INTERVAL)
                    }
                }
            }
        }
        handler.postDelayed(pollRunnable!!, POLL_DELAY)

        // 整體 timeout
        timeoutRunnable = Runnable {
            if (!attemptDone) {
                lastError = if (lastError.isNotEmpty()) "timeout (${ATTEMPT_TIMEOUT_MS / 1000}s): $lastError"
                            else "timeout：${ATTEMPT_TIMEOUT_MS / 1000} 秒內未就緒"
                onAttemptFailed("連線逾時")
            }
        }
        handler.postDelayed(timeoutRunnable!!, ATTEMPT_TIMEOUT_MS)
    }

    private fun onDiodeReady() {
        if (attemptDone) return
        // 取消輪詢與 timeout（接下來換 connectToNode 自己控制）
        pollRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        timeoutRunnable = null

        binding.tvStatus.text = "尋找最佳節點..."

        Thread {
            val node = nodeManager.connectToNode { status ->
                runOnUiThread {
                    if (!attemptDone) binding.tvStatus.text = status
                }
            }

            runOnUiThread {
                if (attemptDone) return@runOnUiThread
                if (node != null) {
                    attemptDone = true
                    cancelHandlers()
                    Log.i(TAG, "Connected to node ${node.regionDisplayName}")
                    nodeManager.startKeepalive()
                    proceedToWebView()
                } else {
                    lastError = "no node available or all bind failed"
                    onAttemptFailed("找不到可用節點")
                }
            }
        }.start()
    }

    private fun onAttemptFailed(reason: String) {
        if (attemptDone) return
        attemptDone = true
        cancelHandlers()
        binding.progressBar.visibility = View.GONE

        Log.w(TAG, "Attempt #$attemptCount failed: $reason ($lastError)")

        if (attemptCount < MAX_ATTEMPTS) {
            binding.tvStatus.text = "連線失敗：$reason"
            binding.tvHint.text = "已嘗試 $attemptCount/$MAX_ATTEMPTS 次，請按下方按鈕重試"
            binding.tvHint.visibility = View.VISIBLE
            binding.btnRetry.visibility = View.VISIBLE
        } else {
            showFinalFailure(reason)
        }
    }

    private fun showFinalFailure(reason: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvStatus.text = "多次連線失敗：$reason"
        binding.tvHint.text = "請按下方按鈕送出診斷 log 給客服協助"
        binding.tvHint.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.GONE
        binding.btnSendLog.visibility = View.VISIBLE
        binding.btnSendLog.isEnabled = true
    }

    private fun sendLogToBackend() {
        binding.btnSendLog.isEnabled = false
        binding.tvStatus.text = "正在送出 log..."

        Thread {
            val log = collectLogcat()
            val payload = buildLogPayload(log)
            val ok = try {
                api.sendClientLog(payload)
            } catch (e: Exception) {
                Log.e(TAG, "sendClientLog threw", e)
                false
            }

            runOnUiThread {
                if (ok) {
                    binding.tvStatus.text = "已送出，感謝回報"
                    binding.tvHint.text = "客服會在收到後跟進，您可稍後再試一次"
                    binding.btnSendLog.visibility = View.GONE
                    binding.btnRetry.visibility = View.VISIBLE
                    attemptCount = 0  // 允許重新開始嘗試
                    Toast.makeText(this, "log 已送出", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvStatus.text = "送出失敗，請檢查網路後再試"
                    binding.btnSendLog.isEnabled = true
                }
            }
        }.start()
    }

    private fun buildLogPayload(logText: String): JSONObject {
        val abi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else Build.CPU_ABI ?: ""
        var versionCode = 0
        try {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") pkgInfo.versionCode
            }
        } catch (_: Exception) {}

        return JSONObject().apply {
            put("device_brand", Build.BRAND ?: "")
            put("device_model", Build.MODEL ?: "")
            put("manufacturer", Build.MANUFACTURER ?: "")
            put("device", Build.DEVICE ?: "")
            put("hardware", Build.HARDWARE ?: "")
            put("fingerprint", Build.FINGERPRINT ?: "")
            put("abi", abi)
            put("os_version", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            put("sdk_int", Build.VERSION.SDK_INT)
            put("locale", Locale.getDefault().toString())
            put("timezone", TimeZone.getDefault().id)
            put("network_type", detectNetworkType())
            put("package_name", packageName)
            put("app_version", BuildConfig.VERSION_NAME)
            put("version_code", versionCode)
            put("flavor", BuildConfig.FLAVOR)
            put("attempt_count", attemptCount)
            put("last_error", lastError)
            put("log_text", logText)
        }
    }

    private fun detectNetworkType(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(net) ?: return "unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        } catch (e: Exception) {
            "error:${e.message}"
        }
    }

    /** 讀取自己進程的 logcat，最後 500 行（包含 Diode/DiodeApi/NodeConn tag） */
    private fun collectLogcat(): String {
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "500", "-v", "time")
            )
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                r.forEachLine { line ->
                    // 過濾感興趣的 tag
                    if (line.contains("Diode") || line.contains("NodeConn") ||
                        line.contains("DiodeApi") || line.contains("AndroidRuntime") ||
                        line.contains("System.err")) {
                        sb.append(line).append('\n')
                    }
                }
            }
            if (sb.isEmpty()) "(no diode-tagged logs captured)" else sb.toString()
        } catch (e: Exception) {
            "logcat collection failed: ${e.message}"
        }
    }

    private fun proceedToWebView() {
        if (isFinishing || isDestroyed) return

        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("socks5://127.0.0.1:$WEBVIEW_PROXY_PORT")
                .build()
            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                { Log.i(TAG, "Proxy set on 127.0.0.1:$WEBVIEW_PROXY_PORT") },
                { Log.e(TAG, "Proxy override failed") }
            )
        }

        startActivity(Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_SOCKS_PORT, WEBVIEW_PROXY_PORT)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
        finish()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
            }
        }
    }
}
