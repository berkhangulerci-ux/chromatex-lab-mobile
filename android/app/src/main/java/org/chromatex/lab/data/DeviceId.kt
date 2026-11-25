package org.chromatex.lab.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

object DeviceId {
    private const val KEY = "device_uuid"

    @SuppressLint("HardwareIds")
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("chromatex_lab", Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrBlank()) return existing

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val id = when {
            !androidId.isNullOrBlank() && androidId != "9774d56d682e549c" ->
                "and-$androidId"
            else -> "uuid-${UUID.randomUUID()}"
        }
        prefs.edit().putString(KEY, id).apply()
        return id
    }

    fun devicePayload(context: Context): Map<String, Any?> = mapOf(
        "device_id" to get(context),
        "client_type" to "android",
        "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "android_version" to Build.VERSION.RELEASE,
        "app_version" to Catalog.APP_VERSION,
        "app_version_code" to Catalog.APP_VERSION_CODE,
        "operator" to SessionPrefs.operator(context),
        "batch_id" to SessionPrefs.batchId(context),
    )
}
