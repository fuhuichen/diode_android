package com.diode.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.diode.android.databinding.ActivityWebviewBinding

/**
 * WebView 頁面：由 LoadingActivity 在 bind 完成後啟動，設定代理並載入網頁。
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding

    companion object {
        private const val TAG = "Diode"
        const val EXTRA_SOCKS_PORT = "socks_port"
        private const val DEFAULT_SOCKS_PORT = 8080
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        supportActionBar?.hide()
        val port = intent.getIntExtra(EXTRA_SOCKS_PORT, DEFAULT_SOCKS_PORT)
        setupWebView(port)
        setupUrlBarIfPresent()
        // 橫向使用 layout-land：全螢幕無 URL 列，etUrl/btnGo 為 null
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(port: Int) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            android.util.Log.i(TAG, "Diode WebView proxy socks5://127.0.0.1:$port")
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("socks5://127.0.0.1:$port")
                .build()

            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                { runOnUiThread {
                    android.util.Log.i(TAG, "Diode WebView proxy override success")
                    Toast.makeText(applicationContext, "代理 127.0.0.1:$port 已設定", Toast.LENGTH_SHORT).show()
                    // 延遲 200ms 再載入，確保代理完全生效
                    binding.webView.postDelayed(
                        { binding.webView.loadUrl(BuildConfig.DEFAULT_URL) },
                        200
                    )
                } },
                { runOnUiThread {
                    android.util.Log.e(TAG, "Diode WebView proxy override failed")
                    Toast.makeText(applicationContext, "代理設定失敗", Toast.LENGTH_SHORT).show()
                    binding.webView.loadUrl(BuildConfig.DEFAULT_URL)
                } }
            )
        } else {
            Toast.makeText(applicationContext, "此裝置不支援 WebView 代理設定", Toast.LENGTH_LONG).show()
            binding.webView.loadUrl(BuildConfig.DEFAULT_URL)
        }

        // Cookie 持久化
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = DiodeWebViewClient()
            webChromeClient = DiodeWebChromeClient()
        }
    }

    private fun setupUrlBarIfPresent() {
        val etUrl = binding.root.findViewById<android.widget.EditText>(R.id.etUrl) ?: return
        val btnGo = binding.root.findViewById<android.view.View>(R.id.btnGo) ?: return
        etUrl.setText(BuildConfig.DEFAULT_URL)
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl()
                true
            } else false
        }
        btnGo.setOnClickListener { loadUrl() }
    }

    private fun loadUrl() {
        val etUrl = binding.root.findViewById<android.widget.EditText>(R.id.etUrl) ?: return
        var url = etUrl.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        binding.webView.loadUrl(url)
    }

    private inner class DiodeWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            binding.progressBar.visibility = android.view.View.VISIBLE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            binding.progressBar.visibility = android.view.View.GONE
            url?.let { binding.root.findViewById<android.widget.EditText>(R.id.etUrl)?.setText(it) }
            CookieManager.getInstance().flush()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean = false
    }

    private inner class DiodeWebChromeClient : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            supportActionBar?.title = title ?: getString(R.string.app_name)
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    @Deprecated("Deprecated in API 33")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
