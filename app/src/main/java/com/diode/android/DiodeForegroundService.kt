package com.diode.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import mobile.Mobile

/**
 * Diode 前台服務 - 確保在背景穩定運行，不被 Low Memory Killer 終止。
 * 呼叫 Go Mobile 編譯的 diode_client 邏輯，提供 SOCKS5 代理。
 */
class DiodeForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "DiodeServiceChannel"
        private const val NOTIFICATION_ID = 101
        // 本機 Diode SOCKS 監聽此 port，與 Bind 的 8080 分開，讓 Bind 可轉發到遠端
        private const val DEFAULT_SOCKS_PORT = 9080

        const val EXTRA_RPC_ADDRS = "rpc_addrs"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_PRIVATE_KEY = "private_key"

        fun start(context: Context, rpcAddrs: String = "", socksPort: Int = DEFAULT_SOCKS_PORT, privateKey: String = "") {
            val intent = Intent(context, DiodeForegroundService::class.java).apply {
                putExtra(EXTRA_RPC_ADDRS, rpcAddrs)
                putExtra(EXTRA_SOCKS_PORT, socksPort)
                putExtra(EXTRA_PRIVATE_KEY, privateKey)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DiodeForegroundService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        createNotificationChannel()

        val rpcAddrs = intent?.getStringExtra(EXTRA_RPC_ADDRS) ?: ""
        val socksPort = intent?.getIntExtra(EXTRA_SOCKS_PORT, DEFAULT_SOCKS_PORT) ?: DEFAULT_SOCKS_PORT
        val privateKey = intent?.getStringExtra(EXTRA_PRIVATE_KEY) ?: ""
        val dataDir = filesDir.absolutePath
        Log.i(TAG, "Diode service start rpc=$rpcAddrs port=$socksPort")

        val notification = createNotification(socksPort)
        startForeground(NOTIFICATION_ID, notification)

        Thread {
            try {
                // 每次重啟前：先清除 binding，再停止 node，確保乾淨狀態
                val clearResult = Mobile.clearBinds()
                Log.i(TAG, "clearBinds on restart: $clearResult")
                Mobile.stopDiode()
                Thread.sleep(1500)
                val portInfo = "$socksPort:socks"
                val status = Mobile.startDiodeSimple(rpcAddrs, portInfo, privateKey, dataDir)
                Log.i(TAG, "Diode started: $status")
            } catch (e: Exception) {
                Log.e(TAG, "Diode start failed", e)
            }
        }.start()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy clearing binds and stopping Diode")
        try {
            Mobile.clearBinds()
            Mobile.stopDiode()
            Log.i(TAG, "Diode stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Diode", e)
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "代理網路服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SOCKS5 代理服務通道"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(socksPort: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("代理網路運行中")
            .setContentText("SOCKS5 代理已啟動於 127.0.0.1:$socksPort")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

private const val TAG = "Diode"
