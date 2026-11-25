package org.chromatex.lab.data

import android.content.Context
import java.io.File

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
    val hasUpdate: Boolean,
)

/**
 * Play Store edition: device register only.
 * APK download/install are no-ops in the Play edition.
 */
object UpdateClient {

    fun check(serverUrl: String, currentCode: Int): UpdateInfo? = null

    fun registerDevice(serverUrl: String, payload: Map<String, Any?>): Boolean {
        return try {
            val base = serverUrl.trim().trimEnd('/')
            if (!base.startsWith("http://") && !base.startsWith("https://")) return false
            val body = org.json.JSONObject(payload).toString()
            val conn = (java.net.URL("$base/devices/register").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    fun downloadApk(context: Context, url: String): File {
        throw UnsupportedOperationException("OTA install is disabled in the Play Store edition")
    }

    fun installApk(context: Context, apk: File) {
        // no-op
    }
}
