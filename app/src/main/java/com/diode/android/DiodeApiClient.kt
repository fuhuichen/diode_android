package com.diode.android

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import mobile.Mobile
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

data class NodeInfo(
    val nodeId: String,
    val region: String,
    val clientAddress: String,
    val activeConnections: Int
) {
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

data class KeepaliveResult(val ok: Boolean, val warning: Boolean)

/**
 * HTTP client for Diode backend API.
 * Authentication uses HMAC-SHA256 (computed in native Go code; secret never exposed in Java/Kotlin).
 */
class DiodeApiClient {

    companion object {
        private const val TAG = "DiodeApi"
        private const val BASE_URL = "https://diode.sofa-partner.com/diode"
        private val API_KEY = BuildConfig.API_KEY
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 15_000

        /** 自身 APK 簽章證書 SHA-256（lowercase hex）— 後端 v1 模式驗證必填 */
        @Volatile private var apkSignatureHex: String? = null

        private fun computeApkSignatureHex(): String {
            return try {
                val ctx = DiodeApplication.appContext
                val pm = ctx.packageManager
                val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    val si = info.signingInfo
                    when {
                        si == null -> emptyArray()
                        si.hasMultipleSigners() -> si.apkContentsSigners
                        else -> si.signingCertificateHistory
                    }
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
                }
                if (signatures.isEmpty()) return ""
                val md = MessageDigest.getInstance("SHA-256")
                md.digest(signatures[0].toByteArray()).joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.e(TAG, "computeApkSignatureHex failed", e)
                ""
            }
        }

        fun apkSignature(): String {
            apkSignatureHex?.let { return it }
            val hex = computeApkSignatureHex()
            apkSignatureHex = hex
            return hex
        }
    }

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

    fun connect(nodeId: String, sessionId: String): Boolean {
        val body = JSONObject().apply {
            put("node_id", nodeId)
            put("session_id", sessionId)
        }
        return post("/api/v1/connect", body) != null
    }

    fun keepalive(sessionId: String, bytesUp: Long = 0, bytesDown: Long = 0): KeepaliveResult {
        val body = JSONObject().apply {
            put("session_id", sessionId)
            put("bytes_up", bytesUp)
            put("bytes_down", bytesDown)
        }
        val response = post("/api/v1/keepalive", body)
            ?: return KeepaliveResult(ok = false, warning = false)
        val warning = response.optBoolean("warning", false)
        return KeepaliveResult(ok = true, warning = warning)
    }

    fun disconnect(sessionId: String): Boolean {
        val body = JSONObject().apply {
            put("session_id", sessionId)
        }
        return post("/api/v1/disconnect", body) != null
    }

    fun sendClientLog(payload: JSONObject): Boolean {
        return post("/api/v1/clientlog", payload) != null
    }

    fun generateSessionId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun post(path: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL$path")
            conn = url.openConnection() as HttpURLConnection
            val bodyStr = body.toString()
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val apkSig = apkSignature()
            val signature = Mobile.signRequest(API_KEY, "POST", path, timestamp, bodyStr, apkSig)

            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-API-Key", API_KEY)
            conn.setRequestProperty("X-Timestamp", timestamp)
            conn.setRequestProperty("X-Signature", signature)
            if (apkSig.isNotEmpty()) {
                conn.setRequestProperty("X-App-Signature", apkSig)
            }
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(bodyStr)
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
