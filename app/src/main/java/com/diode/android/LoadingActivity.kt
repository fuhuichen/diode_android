package com.diode.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.diode.android.databinding.ActivityLoadingBinding
import mobile.Mobile

/**
 * Loading Activity：啟動 Diode、等待連線、setBinds，完成後進入 WebViewActivity。
 */
class LoadingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoadingBinding
    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var done = false

    companion object {
        private const val TAG = "Diode"
        /** 本機 Diode SOCKS 監聽 port（與 Bind 分開，避免搶 8080） */
        private const val DIODE_SOCKS_PORT = 9080
        /** WebView 使用的 proxy port = Bind 本機 port，流量經 Bind 轉發到遠端 SOCKS */
        private const val WEBVIEW_PROXY_PORT = 8080
        private const val DEFAULT_BINDS = "8080:0x7203d627adcddf308cd951a4182f19e083a7d39a:1080:tcp"
        private const val FALLBACK_BINDS = "8080:0x2262934e4509e2060d25586ad1c35e1abf26e69a:1080:tcp"
        private const val POLL_DELAY = 1500L
        private const val POLL_INTERVAL = 2000L
        private const val TIMEOUT_MS = 45000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoadingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        binding.tvStatus.text = "正在連線代理..."
        Toast.makeText(applicationContext, "正在連線代理...", Toast.LENGTH_SHORT).show()

        DiodeForegroundService.start(this, rpcAddrs = "", socksPort = DIODE_SOCKS_PORT, privateKey = "")
        Log.i(TAG, "LoadingActivity: start Diode, waiting for ready...")

        pollRunnable = object : Runnable {
            override fun run() {
                if (done) return
                val address = Mobile.getAddress()
                val error = Mobile.getLastError()
                when {
                    error.isNotEmpty() -> {
                        Log.w(TAG, "Diode init error: $error")
                        binding.tvStatus.text = "連線錯誤: ${error.take(40)}..."
                        Toast.makeText(applicationContext, "連線錯誤: ${error.take(30)}...", Toast.LENGTH_SHORT).show()
                        handler.postDelayed(this, POLL_INTERVAL)
                    }
                    address.isNotEmpty() -> {
                        binding.tvStatus.text = "代理已連線"
                        Toast.makeText(applicationContext, "代理已連線", Toast.LENGTH_SHORT).show()
                        onDiodeReady()
                    }
                    else -> {
                        handler.postDelayed(this, POLL_INTERVAL)
                    }
                }
            }
        }
        handler.postDelayed(pollRunnable!!, POLL_DELAY)
        handler.postDelayed({
            if (!done) {
                Log.w(TAG, "LoadingActivity: timeout, entering WebView anyway")
                onDiodeReady()
            }
        }, TIMEOUT_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        pollRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
            }
        }
    }

    private fun onDiodeReady() {
        if (done) return
        done = true
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null

        binding.tvStatus.text = "設定 Bind..."
        val bindResult = Mobile.setBinds(DEFAULT_BINDS)
        Log.i(TAG, "setBinds (default) result: $bindResult")

        if (bindResult.startsWith("Success")) {
            binding.tvStatus.text = "Bind 已設定"
            Toast.makeText(applicationContext, "Bind 已設定", Toast.LENGTH_SHORT).show()
            // Bind 完成後延遲 5 秒再進入 WebView
            handler.postDelayed({ proceedToWebView() }, 5000L)
        } else {
            Log.w(TAG, "Default bind failed, trying fallback...")
            binding.tvStatus.text = "預設 Bind 失敗，切換備用地址..."
            Toast.makeText(applicationContext, "預設地址失敗，使用備用地址", Toast.LENGTH_LONG).show()
            Mobile.clearBinds()
            val fallbackResult = Mobile.setBinds(FALLBACK_BINDS)
            Log.i(TAG, "setBinds (fallback) result: $fallbackResult")
            binding.tvStatus.text = if (fallbackResult.startsWith("Success")) "Bind 已設定（備用）" else "Bind 失敗: $fallbackResult"
            Toast.makeText(applicationContext, if (fallbackResult.startsWith("Success")) "已使用備用地址連線" else "備用地址也失敗: $fallbackResult", Toast.LENGTH_LONG).show()
            // Bind 完成後延遲 5 秒再進入 WebView
            handler.postDelayed({ proceedToWebView() }, 5000L)
        }
    }

    private fun proceedToWebView() {
        // 在尚未建立任何 WebView 前先設定代理；由主線程延遲啟動 WebView，避免 BAL（背景啟動 Activity 被擋）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            binding.tvStatus.text = "設定代理..."
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("socks5://127.0.0.1:$WEBVIEW_PROXY_PORT")
                .build()
            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                {
                    runOnUiThread {
                        binding.tvStatus.text = "代理已設定"
                        Toast.makeText(applicationContext, "代理 127.0.0.1:$WEBVIEW_PROXY_PORT 已設定（經 Bind 到遠端）", Toast.LENGTH_SHORT).show()
                    }
                },
                {
                    runOnUiThread {
                        Log.e(TAG, "LoadingActivity: proxy override failed")
                        Toast.makeText(applicationContext, "代理設定失敗，仍將開啟瀏覽器", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
        // 一律由主線程延遲啟動 WebView（此時 Activity 仍在前景），避免從 callback 啟動被 BAL 擋下
        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, WebViewActivity::class.java).apply {
                    putExtra(WebViewActivity.EXTRA_SOCKS_PORT, WEBVIEW_PROXY_PORT)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
                finish()
            }
        }, 600)
    }
}
