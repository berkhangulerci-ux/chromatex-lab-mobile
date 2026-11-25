package org.chromatex.lab.data

import android.content.Context

object SessionPrefs {
    private const val NAME = "chromatex_lab"
    private val RETIRED_URLS = setOf(
        "http://192.168.1.10:8787",
        "http://192.168.16.106:8787",
    )

    fun serverUrl(context: Context): String =
        prefs(context).getString("server_url", "")
            ?.trim()
            ?.takeUnless { it in RETIRED_URLS }
            ?: ""

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString("server_url", url.trim().trimEnd('/')).apply()
    }

    fun operator(context: Context): String =
        prefs(context).getString("operator", "").orEmpty()

    fun setOperator(context: Context, value: String) {
        prefs(context).edit().putString("operator", value.trim()).apply()
    }

    fun batchId(context: Context): String =
        prefs(context).getString("batch_id", Catalog.CALIB_BATCH_EXPECTED).orEmpty()

    fun setBatchId(context: Context, value: String) {
        prefs(context).edit().putString("batch_id", value.trim()).apply()
    }

    fun fabricState(context: Context): String =
        prefs(context).getString("fabric_state", "ham").orEmpty()

    fun setFabricState(context: Context, value: String) {
        prefs(context).edit().putString("fabric_state", value).apply()
    }

    fun label(context: Context): String =
        prefs(context).getString("label", "normal_kumas").orEmpty()

    fun setLabel(context: Context, value: String) {
        prefs(context).edit().putString("label", value).apply()
    }

    fun fabricType(context: Context): String =
        prefs(context).getString("fabric_type", "Suprem").orEmpty()

    fun setFabricType(context: Context, value: String) {
        prefs(context).edit().putString("fabric_type", value).apply()
    }

    fun fabricColor(context: Context): String =
        prefs(context).getString("fabric_color", "Ekru").orEmpty()

    fun setFabricColor(context: Context, value: String) {
        prefs(context).edit().putString("fabric_color", value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
