package org.chromatex.lab.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object UploadClient {

    private const val TAG = "UploadClient"

    data class Result(val ok: Int, val fail: Int, val message: String)

    fun uploadPending(context: Context, serverUrl: String): Result {
        val base = serverUrl.trim().trimEnd('/')
        if (base.isBlank()) return Result(0, 0, "Sunucu adresi boş")
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return Result(0, 0, "Sunucu adresi http:// veya https:// ile başlamalı")
        }

        var ok = 0
        var fail = 0
        val errors = mutableListOf<String>()

        for (pending in SceneRecordStore.listPending(context)) {
            try {
                val raw = pending.readText(Charsets.UTF_8)
                val body = JSONObject(raw)
                val imagePath = body.optString("local_image_path")
                val image = File(imagePath)
                if (!image.exists()) {
                    fail++
                    errors += "${pending.name}: görüntü yok"
                    continue
                }
                // Hub’a sadece meta gider; cihaz yolu / NaN temizlenir
                body.remove("local_image_path")
                sanitizeJson(body)
                val metaText = body.toString()
                // Round-trip: Android NaN sızdıysa burada yakala
                try {
                    JSONObject(metaText)
                } catch (e: Exception) {
                    throw IllegalStateException("meta JSON bozuk (NaN/Inf?): ${e.message}")
                }
                postMultipart("$base/upload", metaText, image)
                SceneRecordStore.markUploaded(context, pending)
                ok++
            } catch (e: Exception) {
                fail++
                Log.w(TAG, "upload failed ${pending.name}", e)
                val short = e.message?.take(160).orEmpty()
                errors += "${pending.name}: $short"
            }
        }
        val msg = if (errors.isEmpty()) "Tamam" else errors.take(3).joinToString(" | ")
        return Result(ok, fail, msg)
    }

    fun ping(serverUrl: String): Boolean {
        return try {
            val base = serverUrl.trim().trimEnd('/')
            if (!base.startsWith("http://") && !base.startsWith("https://")) return false
            val conn = (URL("$base/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /** Hub'dan aktif katalog (/catalog) ve aktif kalibrasyon batch (/calibration/active) çek. */
    fun syncFromHub(context: Context, serverUrl: String): Boolean {
        val base = serverUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return false
        var anyOk = false
        try {
            val conn = (URL("$base/catalog").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText())
                json.optJSONObject("items")?.let { Catalog.updateFromHubCatalog(it) }
                anyOk = true
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        try {
            val conn = (URL("$base/calibration/active").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText())
                val activeId = json.optString("batch_id").trim()
                if (activeId.isNotEmpty()) {
                    SessionPrefs.setBatchId(context, activeId)
                }
                anyOk = true
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        return anyOk
    }

    /** NaN/Inf → null; iç içe JSONObject/JSONArray dolaş. */
    private fun sanitizeJson(node: Any?) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                for (key in keys) {
                    val v = node.opt(key)
                    when (v) {
                        is Double -> {
                            if (!v.isFinite()) node.put(key, JSONObject.NULL)
                        }
                        is Float -> {
                            if (!v.isFinite()) node.put(key, JSONObject.NULL)
                        }
                        is JSONObject, is JSONArray -> sanitizeJson(v)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val v = node.opt(i)
                    when (v) {
                        is Double -> {
                            if (!v.isFinite()) node.put(i, JSONObject.NULL)
                        }
                        is Float -> {
                            if (!v.isFinite()) node.put(i, JSONObject.NULL)
                        }
                        is JSONObject, is JSONArray -> sanitizeJson(v)
                    }
                }
            }
        }
    }

    private fun postMultipart(url: String, recordJson: String, image: File) {
        val boundary = "----ChromatexBoundary${System.currentTimeMillis()}"
        val utf8 = StandardCharsets.UTF_8
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 120000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }

        BufferedOutputStream(conn.outputStream).use { out ->
            fun writeAscii(s: String) {
                out.write(s.toByteArray(utf8))
            }

            fun writeField(name: String, value: String) {
                writeAscii("--$boundary\r\n")
                writeAscii("Content-Disposition: form-data; name=\"$name\"\r\n")
                writeAscii("Content-Type: application/json; charset=utf-8\r\n\r\n")
                out.write(value.toByteArray(utf8))
                writeAscii("\r\n")
            }

            writeField("record_json", recordJson)
            writeAscii("--$boundary\r\n")
            writeAscii(
                "Content-Disposition: form-data; name=\"image\"; filename=\"${image.name}\"\r\n",
            )
            writeAscii("Content-Type: image/jpeg\r\n\r\n")
            image.inputStream().use { it.copyTo(out) }
            writeAscii("\r\n--$boundary--\r\n")
            out.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = (conn.errorStream ?: conn.inputStream)
                ?.bufferedReader(utf8)
                ?.readText()
                .orEmpty()
                .take(300)
            throw IllegalStateException("HTTP $code $err")
        }
        conn.disconnect()
    }
}
