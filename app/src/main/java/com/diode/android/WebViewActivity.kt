package com.diode.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
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
 * 支援多分頁（Multi-Tab）：target="_blank" / window.open 會建立新分頁。
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding

    private data class TabInfo(val webView: WebView, var title: String = "New Tab")
    private val tabs = mutableListOf<TabInfo>()
    private var activeTabIndex = -1

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
        setupProxy(port)
        setupUrlBarIfPresent()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }
    }

    // ── Tab management ──────────────────────────────────────────────────

    private fun currentWebView(): WebView? {
        return if (activeTabIndex in tabs.indices) tabs[activeTabIndex].webView else null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            webViewClient = DiodeWebViewClient()
            webChromeClient = DiodeWebChromeClient()
        }
    }

    private fun createTab(url: String? = null, resultMsg: Message? = null) {
        val webView = WebView(this)
        configureWebView(webView)

        val tab = TabInfo(webView)
        tabs.add(tab)
        binding.webViewContainer.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        switchToTab(tabs.size - 1)

        if (resultMsg != null) {
            val transport = resultMsg.obj as? WebView.WebViewTransport
            transport?.webView = webView
            resultMsg.sendToTarget()
        } else if (url != null) {
            webView.loadUrl(url)
        }
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        for (i in tabs.indices) {
            tabs[i].webView.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        refreshTabBar()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        binding.webViewContainer.removeView(tab.webView)
        tab.webView.destroy()

        if (tabs.isEmpty()) {
            finish()
            return
        }

        val newIndex = when {
            index < activeTabIndex -> activeTabIndex - 1
            index == activeTabIndex -> index.coerceAtMost(tabs.size - 1)
            else -> activeTabIndex
        }
        switchToTab(newIndex)
    }

    private fun refreshTabBar() {
        val tabBar = binding.tabBar
        val tabBarScroll = binding.tabBarScroll

        tabBar.removeAllViews()

        if (tabs.size <= 1) {
            tabBarScroll.visibility = View.GONE
            return
        }
        tabBarScroll.visibility = View.VISIBLE

        for (i in tabs.indices) {
            val tab = tabs[i]
            val isActive = (i == activeTabIndex)

            val tabButton = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                if (isActive) {
                    background = ColorDrawable(Color.parseColor("#555555"))
                }
                setOnClickListener { switchToTab(i) }
            }

            val titleView = TextView(this).apply {
                val displayTitle = if (tab.title.length > 12) tab.title.take(12) + "..." else tab.title
                text = displayTitle
                setTextColor(Color.WHITE)
                textSize = 13f
                if (isActive) setTypeface(null, Typeface.BOLD)
            }
            tabButton.addView(titleView)

            val closeView = TextView(this).apply {
                text = "\u2715"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 14f
                setPadding(dp(8), 0, dp(4), 0)
                setOnClickListener { closeTab(i) }
            }
            tabButton.addView(closeView)

            tabBar.addView(tabButton)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // ── Proxy setup ─────────────────────────────────────────────────────

    private fun setupProxy(port: Int) {
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
                    binding.webViewContainer.postDelayed(
                        { createTab(BuildConfig.DEFAULT_URL) },
                        200
                    )
                } },
                { runOnUiThread {
                    android.util.Log.e(TAG, "Diode WebView proxy override failed")
                    Toast.makeText(applicationContext, "代理設定失敗", Toast.LENGTH_SHORT).show()
                    createTab(BuildConfig.DEFAULT_URL)
                } }
            )
        } else {
            Toast.makeText(applicationContext, "此裝置不支援 WebView 代理設定", Toast.LENGTH_LONG).show()
            createTab(BuildConfig.DEFAULT_URL)
        }
    }

    // ── URL bar ─────────────────────────────────────────────────────────

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
        currentWebView()?.loadUrl(url)
    }

    // ── WebView clients ─────────────────────────────────────────────────

    private inner class DiodeWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (view == currentWebView()) {
                binding.progressBar.bringToFront()
                binding.progressBar.visibility = View.VISIBLE
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (view == currentWebView()) {
                binding.progressBar.visibility = View.GONE
                url?.let { binding.root.findViewById<android.widget.EditText>(R.id.etUrl)?.setText(it) }
            }
            CookieManager.getInstance().flush()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean = false
    }

    private inner class DiodeWebChromeClient : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            val tabIndex = tabs.indexOfFirst { it.webView == view }
            if (tabIndex >= 0) {
                tabs[tabIndex].title = title ?: "New Tab"
                refreshTabBar()
            }
            if (view == currentWebView()) {
                supportActionBar?.title = title ?: getString(R.string.app_name)
            }
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            if (resultMsg == null) return false
            createTab(resultMsg = resultMsg)
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            val tabIndex = tabs.indexOfFirst { it.webView == window }
            if (tabIndex >= 0) {
                closeTab(tabIndex)
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        for (tab in tabs) {
            binding.webViewContainer.removeView(tab.webView)
            tab.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    @Deprecated("Deprecated in API 33")
    override fun onBackPressed() {
        val webView = currentWebView()
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else if (tabs.size > 1) {
            closeTab(activeTabIndex)
        } else {
            super.onBackPressed()
        }
    }
}
