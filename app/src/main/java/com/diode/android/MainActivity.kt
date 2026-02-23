package com.diode.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.diode.android.databinding.ActivityMainBinding
import mobile.Mobile

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var statusCheckRunnable: Runnable? = null
    private var isDiodeRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        setupClickListeners()
        updateStatusUI(DiodeStatus.STOPPED)
    }

    override fun onResume() {
        super.onResume()
        // 恢復時檢查狀態
        checkDiodeStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatusCheck()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION
                )
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnStart.setOnClickListener { startDiode() }
        binding.btnStop.setOnClickListener { stopDiode() }
        binding.btnOpenWebView.setOnClickListener { openWebView() }
        binding.btnBatteryOptimization.setOnClickListener { requestBatteryOptimizationExemption() }
        binding.btnSetBinds.setOnClickListener { setBinds() }
        binding.btnClearBinds.setOnClickListener { clearBinds() }
    }

    private fun startDiode() {
        val rpcAddrs = binding.etRpcAddrs.text.toString().trim()
        val socksPort = binding.etSocksPort.text.toString().toIntOrNull() ?: 8080
        val privateKey = binding.etPrivateKey.text.toString().trim()
        Log.i(TAG, "startDiode rpc=$rpcAddrs port=$socksPort")

        updateStatusUI(DiodeStatus.STARTING)
        DiodeForegroundService.start(this, rpcAddrs, socksPort, privateKey)
        
        // 開始定期檢查狀態
        startStatusCheck()
    }

    private fun stopDiode() {
        Log.i(TAG, "stopDiode")
        stopStatusCheck()
        DiodeForegroundService.stop(this)
        isDiodeRunning = false
        updateStatusUI(DiodeStatus.STOPPED)
        Toast.makeText(this, "Diode 服務已停止", Toast.LENGTH_SHORT).show()
    }

    private fun startStatusCheck() {
        stopStatusCheck()
        statusCheckRunnable = object : Runnable {
            override fun run() {
                checkDiodeStatus()
                // 只有尚未連線成功時才繼續輪詢，避免每 3 秒重複檢查
                if (!isDiodeRunning) {
                    handler.postDelayed(this, STATUS_CHECK_INTERVAL)
                }
            }
        }
        handler.postDelayed(statusCheckRunnable!!, STATUS_CHECK_DELAY)
    }

    private fun stopStatusCheck() {
        statusCheckRunnable?.let { handler.removeCallbacks(it) }
        statusCheckRunnable = null
    }

    private fun checkDiodeStatus() {
        try {
            val error = Mobile.getLastError()
            val address = Mobile.getAddress()
            
            when {
                error.isNotEmpty() -> {
                    Log.w(TAG, "checkDiodeStatus error=$error")
                    isDiodeRunning = false
                    updateStatusUI(DiodeStatus.ERROR, error)
                }
                address.isNotEmpty() -> {
                    Log.i(TAG, "checkDiodeStatus running address=$address")
                    isDiodeRunning = true
                    updateStatusUI(DiodeStatus.RUNNING, "已連線: ${address.take(10)}...")
                    stopStatusCheck() // 成功後停止檢查
                }
                else -> {
                    Log.d(TAG, "checkDiodeStatus starting...")
                    updateStatusUI(DiodeStatus.STARTING)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkDiodeStatus exception", e)
            updateStatusUI(DiodeStatus.STOPPED)
        }
    }

    private fun updateStatusUI(status: DiodeStatus, message: String? = null) {
        val (indicatorRes, statusText, enableWebView) = when (status) {
            DiodeStatus.STOPPED -> Triple(
                R.drawable.status_indicator_stopped,
                "Diode 未啟動",
                false
            )
            DiodeStatus.STARTING -> Triple(
                R.drawable.status_indicator_starting,
                "Diode 啟動中...",
                false
            )
            DiodeStatus.RUNNING -> Triple(
                R.drawable.status_indicator_running,
                message ?: "Diode 運行中",
                true
            )
            DiodeStatus.ERROR -> Triple(
                R.drawable.status_indicator_error,
                "錯誤: ${message?.take(50) ?: "未知"}",
                false
            )
        }

        binding.statusIndicator.setBackgroundResource(indicatorRes)
        binding.tvStatus.text = statusText
        binding.btnOpenWebView.isEnabled = enableWebView
        
        if (status == DiodeStatus.RUNNING) {
            Toast.makeText(this, "Diode 已成功啟動！", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWebView() {
        if (!isDiodeRunning) {
            Log.w(TAG, "openWebView rejected: Diode not running")
            Toast.makeText(this, "請先啟動 Diode", Toast.LENGTH_SHORT).show()
            return
        }
        // 使用 bind port 8080 透過遠端節點上網
        val port = 8080
        Log.i(TAG, "openWebView port=$port")
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_SOCKS_PORT, port)
        }
        startActivity(intent)
    }

    private fun setBinds() {
        val bindsStr = binding.etBinds.text.toString().trim()
        if (bindsStr.isEmpty()) {
            Toast.makeText(this, "請輸入綁定配置", Toast.LENGTH_SHORT).show()
            return
        }
        Log.i(TAG, "setBinds: $bindsStr")
        Thread {
            val result = Mobile.setBinds(bindsStr)
            runOnUiThread {
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                Log.i(TAG, "setBinds result: $result")
            }
        }.start()
    }

    private fun clearBinds() {
        Log.i(TAG, "clearBinds")
        Thread {
            val result = Mobile.clearBinds()
            runOnUiThread {
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                Log.i(TAG, "clearBinds result: $result")
            }
        }.start()
    }

    enum class DiodeStatus {
        STOPPED, STARTING, RUNNING, ERROR
    }

    /**
     * 引導用戶將 App 加入電池最佳化白名單，避免 Doze 模式限制網路。
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = android.content.Intent().apply {
                    action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = android.net.Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                    Toast.makeText(this, "請允許忽略電池最佳化", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "無法開啟設定: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "已在電池最佳化白名單中", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "此裝置不需要此設定", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "通知權限已授予", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val TAG = "Diode"
        private const val REQUEST_NOTIFICATION = 1001
        private const val STATUS_CHECK_DELAY = 2000L      // 啟動後延遲 2 秒開始檢查
        private const val STATUS_CHECK_INTERVAL = 3000L   // 每 3 秒檢查一次
    }
}
