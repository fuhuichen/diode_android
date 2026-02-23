package com.diode.android

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 在 Application 啟動時預載 OpenSSL .so 庫（libssl.so.1.1, libcrypto.so.1.1），
 * 因為 Android Gradle 不打包 versioned .so 檔（如 .so.1.1），需從 assets 解壓後 System.load。
 */
class DiodeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        loadOpenSslLibs()
    }

    private fun loadOpenSslLibs() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return
        val assetPath = "openssl/$abi"
        try {
            if (abi !in (assets.list("openssl") ?: emptyArray())) return

            val libDir = File(cacheDir, "openssl").apply { mkdirs() }
            val libSsl = File(libDir, "libssl.so.1.1")
            val libCrypto = File(libDir, "libcrypto.so.1.1")

            if (!libSsl.exists() || !libCrypto.exists()) {
                assets.open("$assetPath/libssl.so.1.1").use { it.copyTo(FileOutputStream(libSsl)) }
                assets.open("$assetPath/libcrypto.so.1.1").use { it.copyTo(FileOutputStream(libCrypto)) }
            }

            System.load(libCrypto.absolutePath)
            System.load(libSsl.absolutePath)
            Log.i(TAG, "Diode OpenSSL libs loaded from $libDir")
        } catch (e: Exception) {
            Log.e(TAG, "Diode Failed to load OpenSSL libs", e)
        }
    }

    companion object {
        private const val TAG = "Diode"
    }
}
