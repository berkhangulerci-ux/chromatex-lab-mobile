package org.chromatex.lab.data

import android.content.Context
import org.json.JSONObject
import java.io.File

data class CalibInfo(
    val present: Boolean,
    val batchId: String?,
    val patchCount: Int,
    val measuredAt: String?,
    val matchesExpectedPaper: Boolean,
    val expectedBatch: String,
    val message: String,
)

object CalibrationStatus {

    fun load(context: Context): CalibInfo {
        val expected = SessionPrefs.batchId(context).ifBlank { Catalog.CALIB_BATCH_EXPECTED }
        val candidates = listOf(
            File(SceneRecordStore.calibDir(context), "patches_measured.json"),
            File(SceneRecordStore.calibDir(context), "active_calibration.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: return CalibInfo(
                present = false,
                batchId = null,
                patchCount = 0,
                measuredAt = null,
                matchesExpectedPaper = false,
                expectedBatch = expected,
                message = "Yüklü kalibrasyon yok — A4 batch ölçümünü tablete yükleyin",
            )

        return try {
            val raw = JSONObject(file.readText())
            val batch = raw.optJSONObject("batch") ?: raw
            val batchId = batch.optString("batch_id").ifBlank { raw.optString("batch_id") }
            val patches = batch.optJSONObject("patches") ?: raw.optJSONObject("patches")
            val count = patches?.length() ?: 0
            val measuredAt = batch.optString("measured_at").ifBlank { null }
            val match = batchId == expected || batchId == Catalog.CALIB_BATCH_EXPECTED
            CalibInfo(
                present = count > 0,
                batchId = batchId,
                patchCount = count,
                measuredAt = measuredAt,
                matchesExpectedPaper = match,
                expectedBatch = expected,
                message = when {
                    count == 0 -> "Kalibrasyon dosyası boş"
                    match -> "A4 kağıt batch ile uyumlu ($batchId)"
                    else -> "UYARI: yüklü batch ($batchId) beklenen A4 ($expected) ile farklı"
                },
            )
        } catch (e: Exception) {
            CalibInfo(
                false, null, 0, null, false, expected,
                "Kalibrasyon okunamadı: ${e.message}",
            )
        }
    }

    fun deviceCalibratedRecently(context: Context): Boolean {
        return SceneRecordStore.listGallery(context).any {
            it.json.optJSONObject("capture")?.optString("purpose") == "device_calibration"
        }
    }
}
