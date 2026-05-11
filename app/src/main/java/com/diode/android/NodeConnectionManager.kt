package com.diode.android

import android.util.Log
import mobile.Mobile
import java.util.Timer
import java.util.TimerTask

/**
 * 管理節點連線流程：取得節點列表 → 依序嘗試 connect + bind → keepalive 循環。
 * 使用 singleton 確保連線狀態在 Activity 轉場時不會遺失。
 */
class NodeConnectionManager private constructor() {

    companion object {
        private const val TAG = "NodeConn"
        private const val KEEPALIVE_INTERVAL = 25_000L // 25 秒
        private val BIND_PORT = BuildConfig.WEBVIEW_PROXY_PORT
        private const val REMOTE_SOCKS_PORT = 1080

        /** Singleton instance */
        val instance: NodeConnectionManager by lazy { NodeConnectionManager() }
    }

    private val api = DiodeApiClient()
    private var currentNode: NodeInfo? = null
    private var sessionId: String? = null
    private var keepaliveTimer: Timer? = null

    /**
     * 主方法：取得節點列表，依序嘗試連線。
     * @param onStatus UI 狀態回調（在呼叫線程執行）
     * @return 成功連線的 NodeInfo，或 null（全部失敗）
     */
    fun connectToNode(onStatus: (String) -> Unit): NodeInfo? {
        onStatus("正在取得節點列表...")
        val nodes = api.getNodes()
        if (nodes.isEmpty()) {
            Log.w(TAG, "No nodes available from API")
            onStatus("無可用節點")
            return null
        }
        Log.i(TAG, "Got ${nodes.size} nodes, trying in order...")

        for (node in nodes) {
            onStatus("嘗試連線 ${node.regionDisplayName}...")
            Log.i(TAG, "Trying node ${node.nodeId} (${node.region}, conns=${node.activeConnections})")

            val sid = api.generateSessionId()
            val connected = api.connect(node.nodeId, sid)
            if (!connected) {
                Log.w(TAG, "API connect failed for node ${node.nodeId}")
                continue
            }

            // 設定 Bind：本機 port → 遠端 client_address SOCKS port
            val bindStr = "$BIND_PORT:${node.clientAddress}:$REMOTE_SOCKS_PORT:tcp"
            Log.i(TAG, "setBinds: $bindStr")
            val bindResult = Mobile.setBinds(bindStr)
            Log.i(TAG, "setBinds result: $bindResult")

            if (bindResult.startsWith("Success")) {
                currentNode = node
                sessionId = sid
                Log.i(TAG, "Connected to node ${node.nodeId} (${node.regionDisplayName})")
                onStatus("使用隱秘通道，經由地點(${node.regionDisplayName})")
                return node
            }

            // Bind 失敗，清理並嘗試下一個
            Log.w(TAG, "Bind failed for node ${node.nodeId}: $bindResult")
            Mobile.clearBinds()
            api.disconnect(sid)
        }

        onStatus("所有節點連線失敗")
        return null
    }

    /** 啟動 keepalive 定時器，每 25 秒呼叫一次，同時回報流量 */
    fun startKeepalive() {
        val sid = sessionId ?: return
        stopKeepalive()
        keepaliveTimer = Timer("keepalive", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        // 取得自上次以來的流量統計（原子性取得並歸零）
                        val bytesUp = Mobile.getTrafficAndReset()
                        val bytesDown = Mobile.getLastBytesDown()
                        val result = api.keepalive(sid, bytesUp, bytesDown)
                        Log.d(TAG, "keepalive: ok=${result.ok} warning=${result.warning} up=$bytesUp down=$bytesDown")
                        if (result.warning) {
                            Log.w(TAG, "Usage warning: traffic exceeds limit")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "keepalive error: ${e.message}")
                    }
                }
            }, KEEPALIVE_INTERVAL, KEEPALIVE_INTERVAL)
        }
        Log.i(TAG, "Keepalive timer started (interval=${KEEPALIVE_INTERVAL}ms)")
    }

    /** 停止 keepalive 定時器 */
    fun stopKeepalive() {
        keepaliveTimer?.cancel()
        keepaliveTimer = null
    }

    /** 斷開目前連線並通知後台 */
    fun disconnectCurrent() {
        stopKeepalive()
        val sid = sessionId ?: return
        try {
            Mobile.clearBinds()
        } catch (e: Exception) {
            Log.e(TAG, "clearBinds error: ${e.message}")
        }
        try {
            api.disconnect(sid)
            Log.i(TAG, "Disconnected session $sid")
        } catch (e: Exception) {
            Log.e(TAG, "disconnect error: ${e.message}")
        }
        currentNode = null
        sessionId = null
    }

    /** 重置本地狀態（不呼叫 API），用於 service 重啟時清除舊連線 */
    fun reset() {
        stopKeepalive()
        currentNode = null
        sessionId = null
        Log.i(TAG, "State reset")
    }

    /** 取得目前連線的節點 */
    fun getCurrentNode(): NodeInfo? = currentNode
}
