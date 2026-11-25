package org.chromatex.lab.data

import org.chromatex.lab.BuildConfig
import org.json.JSONObject

object Catalog {
    /** Kayıt kodu → ekranda görünen Türkçe. Meta’da her zaman code yazılır. */
    data class Choice(val code: String, val title: String)

    private val PARTI_NO_REGEX = Regex("^[A-Za-z]\\d{7}-\\d{2}$")

    fun normalizePartiNo(raw: String): String = raw.trim().uppercase()

    /** Boş serbest; doluysa X9999999-99 (Hub validators.py ile birebir). */
    fun validatePartiNo(raw: String): String? {
        val text = normalizePartiNo(raw)
        if (text.isEmpty()) return null
        return if (PARTI_NO_REGEX.matches(text)) null
        else "Parti no şablonu: X9999999-99 (örn. P1234567-01, T1234567-01)"
    }

    @Volatile
    var fabricStateChoices: List<Choice> = listOf(
        Choice("ham", "Ham"),
        Choice("boyali", "Boyalı"),
        Choice("yari_islem", "Yarı işlem"),
    )
        private set

    @Volatile
    var labelChoices: List<Choice> = listOf(
        Choice("normal_kumas", "Normal kumaş"),
        Choice("hata_var", "Hata var"),
        Choice("delik", "Delik"),
        Choice("yag_lekesi", "Yağ lekesi"),
        Choice("kirik_igne", "İğne kırık"),
        Choice("kacik_ilmek", "Kaçık ilmek"),
        Choice("diger", "Diğer"),
        Choice("belirsiz", "Belirsiz"),
    )
        private set

    @Volatile
    var fabricTypeChoices: List<Choice> = listOf(
        Choice("Suprem", "Süprem"),
        Choice("Lycra suprem", "Likra süprem"),
        Choice("Ribana", "Ribana"),
        Choice("Lycra ribana", "Likra ribana"),
        Choice("Interlok", "Interlok"),
        Choice("Pike", "Pike"),
        Choice("2 iplik", "2 iplik"),
        Choice("3 iplik / Futter", "3 iplik / Futter"),
        Choice("Vanize", "Vanize"),
        Choice("Kaskorse", "Kaskorse"),
        Choice("Jakar", "Jakar"),
        Choice("Diger", "Diğer"),
    )
        private set

    @Volatile
    var fabricColorChoices: List<Choice> = listOf(
        Choice("Ekru", "Ekru"),
        Choice("Beyaz", "Beyaz"),
        Choice("Kirik beyaz", "Kırık beyaz"),
        Choice("Siyah", "Siyah"),
        Choice("Antrasit", "Antrasit"),
        Choice("Gri", "Gri"),
        Choice("Lacivert", "Lacivert"),
        Choice("Kirmizi", "Kırmızı"),
        Choice("Bordo", "Bordo"),
        Choice("Yesil", "Yeşil"),
        Choice("Mavi", "Mavi"),
        Choice("Bej", "Bej"),
        Choice("Diger", "Diğer"),
    )
        private set

    fun updateFromHubCatalog(itemsObj: JSONObject) {
        fun parseKind(kind: String): List<Choice>? {
            val arr = itemsObj.optJSONArray(kind) ?: return null
            val out = ArrayList<Choice>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optInt("active", 1) == 0) continue
                val code = obj.optString("value").trim()
                if (code.isEmpty()) continue
                val title = obj.optString("display_name").trim().ifEmpty { code }
                out.add(Choice(code, title))
            }
            return out.takeIf { it.isNotEmpty() }
        }
        parseKind("fabric_state")?.let { fabricStateChoices = it }
        parseKind("label")?.let { labelChoices = it }
        parseKind("fabric_type")?.let { fabricTypeChoices = it }
        parseKind("fabric_color")?.let { fabricColorChoices = it }
    }

    val fabricStates: List<String> get() = fabricStateChoices.map { it.code }
    val labels: List<String> get() = labelChoices.map { it.code }
    val fabricTypes: List<String> get() = fabricTypeChoices.map { it.code }
    val fabricColors: List<String> get() = fabricColorChoices.map { it.code }

    fun titleOf(choices: List<Choice>, code: String): String =
        choices.firstOrNull { it.code == code }?.title ?: code

    fun codeAt(choices: List<Choice>, index: Int): String =
        choices.getOrNull(index)?.code ?: choices.first().code

    fun indexOfCode(choices: List<Choice>, code: String): Int =
        choices.indexOfFirst { it.code == code }.coerceAtLeast(0)

    val APP_VERSION: String get() = BuildConfig.VERSION_NAME
    val APP_VERSION_CODE: Int get() = BuildConfig.VERSION_CODE
    const val CALIB_BATCH_EXPECTED = "Target_v2_Final_2026-11-17"
}
