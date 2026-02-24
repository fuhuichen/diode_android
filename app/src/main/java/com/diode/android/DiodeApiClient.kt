package com.diode.android

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class NodeInfo(
    val nodeId: String,
    val region: String,
    val clientAddress: String,
    val activeConnections: Int
) {
    /** 地區顯示名稱 */
    val regionDisplayName: String
        get() = REGION_NAMES[region] ?: region

    companion object {
        private val REGION_NAMES = mapOf(
            "ap-east-1" to "香港",
            "ap-southeast-1" to "新加坡",
            "ap-northeast-1" to "東京",
            "us-east-1" to "美東",
            "us-west-1" to "美西",
            "eu-west-1" to "歐洲"
        )
    }
}

/**
 * HTTP client for Diode backend API.
 * Uses java.net.HttpURLConnection to avoid extra dependencies.
 */
class DiodeApiClient {

    companion object {
        private const val TAG = "DiodeApi"
        private const val BASE_URL = "http://13.213.186.48/diode"
        private val API_KEY = BuildConfig.API_KEY
        private val API_SECRET = BuildConfig.API_SECRET
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 15_000
    }

    /** POST /api/v1/nodes — 取得可用節點列表（已按 active_connections 升序排序） */
    fun getNodes(): List<NodeInfo> {
        val body = JSONObject()
        val response = post("/api/v1/nodes", body) ?: return emptyList()
        val nodes = response.optJSONArray("nodes") ?: return emptyList()
        val result = mutableListOf<NodeInfo>()
        for (i in 0 until nodes.length()) {
            val n = nodes.getJSONObject(i)
            result.add(
                NodeInfo(
                    nodeId = n.getString("node_id"),
                    region = n.getString("region"),
                    clientAddress = n.getString("client_address"),
                    activeConnections = n.optInt("active_connections", 0)
                )
            )
        }
        return result
    }

    /** POST /api/v1/connect — 註冊連線 */
    fun connect(nodeId: String, sessionId: String): Boolean {
        val body = JSONObject().apply {
            put("node_id", nodeId)
            put("session_id", sessionId)
        }
        val response = post("/api/v1/connect", body)
        return response != null
    }

    /** POST /api/v1/keepalive */
    fun keepalive(sessionId: String): Boolean {
        val body = JSONObject().apply {
            put("session_id", sessionId)
        }
        val response = post("/api/v1/keepalive", body)
        return response != null
    }

    /** POST /api/v1/disconnect */
    fun disconnect(sessionId: String): Boolean {
        val body = JSONObject().apply {
            put("session_id", sessionId)
        }
        val response = post("/api/v1/disconnect", body)
        return response != null
    }

    /** 產生唯一 session ID */
    fun generateSessionId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * 執行 POST 請求，回傳 JSON response 或 null（失敗時）。
     */
    private fun post(path: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL$path")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-API-Key", API_KEY)
            conn.setRequestProperty("X-API-Secret", API_SECRET)
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                Log.d(TAG, "$path → $code: $text")
                return JSONObject(text)
            } else {
                val errorText = try {
                    BufferedReader(InputStreamReader(conn.errorStream, Charsets.UTF_8)).use { it.readText() }
                } catch (_: Exception) { "" }
                Log.w(TAG, "$path → $code: $errorText")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "$path failed: ${e.message}", e)
            return null
        } finally {
            conn?.disconnect()
        }
    }
}
