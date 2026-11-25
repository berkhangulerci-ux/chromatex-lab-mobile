package org.chromatex.lab.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
    val hasUpdate: Boolean,
)

/** Kiosk edition: full OTA APK download + install. */
object UpdateClient {

    fun check(serverUrl: String, currentCode: Int): UpdateInfo? {
        val base = serverUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        return try {
            val conn = (URL("$base/app/version").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val code = json.optInt("version_code", 0)
            val name = json.optString("version_name", "?")
            val notes = json.optString("notes", "")
            val path = json.optString("download_path", "/app/download")
            UpdateInfo(
                versionCode = code,
                versionName = name,
                notes = notes,
                downloadUrl = "$base$path",
                hasUpdate = code > currentCode,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun registerDevice(serverUrl: String, payload: Map<String, Any?>): Boolean {
        return try {
            val base = serverUrl.trim().trimEnd('/')
            if (!base.startsWith("http://") && !base.startsWith("https://")) return false
            val body = JSONObject(payload).toString()
            val conn = (URL("$base/devices/register").openConnection() as HttpURLConnection).apply {
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
        val out = File(context.cacheDir, "chromatex-lab-update.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 120000
        }
        conn.inputStream.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        conn.disconnect()
        return out
    }

    fun installApk(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // FileProvider already used
            }
        }
        context.startActivity(intent)
    }
}
