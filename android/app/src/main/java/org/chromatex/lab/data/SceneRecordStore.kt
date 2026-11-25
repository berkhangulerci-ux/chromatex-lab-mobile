package org.chromatex.lab.data

import android.content.Context
import android.os.Build
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class FabricSession(
    val fabricState: String,
    val label: String,
    val fabricType: String,
    val fabricColor: String,
    val orguKodu: String?,
    val sipNo: String?,
    val isemriNo: String?,
    val makinaNo: String?,
    val partiNo: String?,
)

data class GalleryItem(
    val recordFile: File,
    val json: JSONObject,
    val imageFile: File?,
    val synced: Boolean,
)

object SceneRecordStore {

    /** NaN/Inf Android JSONObject’te geçersiz JSON üretir → Hub “meta/JSON” hatası. */
    fun putFinite(obj: JSONObject, key: String, value: Double?): JSONObject {
        if (value == null || !value.isFinite()) {
            obj.put(key, JSONObject.NULL)
        } else {
            obj.put(key, value)
        }
        return obj
    }

    fun putFinite(obj: JSONObject, key: String, value: Float?): JSONObject {
        if (value == null || !value.isFinite()) {
            obj.put(key, JSONObject.NULL)
        } else {
            obj.put(key, value.toDouble())
        }
        return obj
    }

    /**
     * Master arşiv ≠ train set.
     * true yalnızca framed_ok + exposure_ok ve scene/device_calibration purpose.
     * FORCE / zayıf kalite kaydı saklanır ama train_eligible=false.
     */
    fun isTrainEligible(purpose: String, meta: CaptureMeta): Boolean {
        if (!meta.framedOk) return false
        if (meta.exposureOk != true) return false
        return purpose == "scene_capture" || purpose == "device_calibration"
    }

    private fun root(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "ChromatexLab")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun imagesDir(context: Context) =
        File(root(context), "images").also { if (!it.exists()) it.mkdirs() }

    fun recordsDir(context: Context) =
        File(root(context), "records").also { if (!it.exists()) it.mkdirs() }

    fun pendingDir(context: Context) =
        File(root(context), "pending_upload").also { if (!it.exists()) it.mkdirs() }

    fun uploadedDir(context: Context) =
        File(root(context), "uploaded").also { if (!it.exists()) it.mkdirs() }

    fun calibDir(context: Context) =
        File(root(context), "calibration").also { if (!it.exists()) it.mkdirs() }

    fun newImageFile(context: Context, purpose: String): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(imagesDir(context), "${purpose}_$ts.jpg")
    }

    fun listPending(context: Context): List<File> =
        pendingDir(context).listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun listGallery(context: Context): List<GalleryItem> {
        val records = recordsDir(context).listFiles { f -> f.extension == "json" }.orEmpty()
        return records.mapNotNull { file ->
            try {
                val json = JSONObject(file.readText())
                val name = json.optJSONObject("capture")?.optString("image_file").orEmpty()
                val image = if (name.isNotBlank()) File(imagesDir(context), name) else null
                val synced = json.optBoolean("uploaded", false) ||
                    File(uploadedDir(context), file.name).exists()
                GalleryItem(file, json, image?.takeIf { it.exists() }, synced)
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.recordFile.lastModified() }
    }

    fun saveRecord(
        context: Context,
        operator: String,
        batchId: String,
        fabric: FabricSession?,
        purpose: String,
        imageFile: File,
        torchOn: Boolean,
        torchAvailable: Boolean = true,
        meta: CaptureMeta,
    ): File {
        val id = UUID.randomUUID().toString()
        val json = JSONObject()
            .put("record_id", id)
            .put(
                "created_at",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()),
            )
            .put("operator", operator)
            .put("device_id", DeviceId.get(context))
            .put("calibration_batch_id", batchId)
            .put("fabric_window_mm", 100)
            .put("train_eligible", isTrainEligible(purpose, meta))
            .put("uploaded", false)
            .put(
                "capture",
                JSONObject()
                    .put("client_type", "android")
                    .put(
                        "device",
                        JSONObject()
                            .put("device_id", DeviceId.get(context))
                            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                            .put("android_version", Build.VERSION.RELEASE)
                            .put("app_version", Catalog.APP_VERSION),
                    )
                    .put(
                        "camera",
                        JSONObject()
                            .put("camera_id", meta.cameraId)
                            .put("resolution_w", meta.resolutionW)
                            .put("resolution_h", meta.resolutionH)
                            .put("iso", meta.iso)
                            .put("exposure_ns", meta.exposureNs)
                            .also {
                                putFinite(it, "focal_length_mm", meta.focalLengthMm)
                                putFinite(it, "sensor_width_mm", meta.sensorWidthMm)
                                putFinite(it, "sensor_height_mm", meta.sensorHeightMm)
                            },
                    )
                    .put(
                        "light",
                        JSONObject()
                            .put("torch_on", torchOn)
                            .put("torch_available", torchAvailable)
                            .also {
                                putFinite(it, "luma_mean", meta.lumaMean)
                                putFinite(it, "edge_luma", meta.edgeLuma)
                                putFinite(it, "center_std", meta.centerStd)
                                putFinite(it, "sharpness", meta.sharpness)
                                putFinite(it, "skew_px", meta.skewPx)
                                putFinite(it, "measured_luma", meta.measuredLuma)
                                putFinite(it, "glare_ratio", meta.glareRatio)
                            }
                            .put("exposure_policy_state", meta.exposurePolicyState)
                            .put("target_luma_band", meta.targetLumaBand)
                            .put("exposure_ok", meta.exposureOk)
                            .put("light_guidance", meta.lightGuidance),
                    )
                    .put("guidance_at_capture", meta.guidance)
                    .put("framed_ok", meta.framedOk)
                    .put("auto_captured", meta.autoCaptured)
                    .put(
                        "focus_region",
                        meta.focusRegion ?: "ring_high_contrast_border",
                    )
                    .put(
                        "geometry",
                        JSONObject()
                            .put("mode", meta.geometryMode)
                            .put("fabric_window_mm", 100)
                            .put("hole_side_px", meta.holeSidePx)
                            .put("paper_edges_visible", meta.paperEdgesVisible)
                            .also {
                                putFinite(it, "window_fraction", meta.windowFraction)
                                putFinite(it, "px_per_mm", meta.pxPerMm)
                                putFinite(it, "color_ring_score", meta.colorRingScore)
                                putFinite(it, "ring_left", meta.ringLeft)
                                putFinite(it, "ring_right", meta.ringRight)
                                putFinite(it, "ring_top", meta.ringTop)
                                putFinite(it, "ring_bottom", meta.ringBottom)
                            }
                            .put(
                                "note",
                                "Full v2 target with all 18 patches visible inside frame",
                            ),
                    )
                    .put("image_file", imageFile.name)
                    .put("purpose", purpose),
            )

        if (fabric != null) {
            json.put("fabric_state", fabric.fabricState)
            json.put("label", fabric.label)
            json.put("fabric_type", fabric.fabricType)
            json.put("fabric_color", fabric.fabricColor)
            json.put(
                "fabric_codes",
                JSONObject()
                    .put("orgu_kodu", fabric.orguKodu)
                    .put("sip_no", fabric.sipNo)
                    .put("isemri_no", fabric.isemriNo)
                    .put("makina_no", fabric.makinaNo)
                    .put("parti_no", fabric.partiNo),
            )
        }

        val out = File(recordsDir(context), "$id.json")
        out.writeText(json.toString(2))
        val pending = JSONObject(json.toString()).put("local_image_path", imageFile.absolutePath)
        File(pendingDir(context), "$id.json").writeText(pending.toString(2))
        return out
    }

    fun markUploaded(context: Context, pendingFile: File) {
        val id = pendingFile.nameWithoutExtension
        val record = File(recordsDir(context), pendingFile.name)
        if (record.exists()) {
            val json = JSONObject(record.readText())
            json.put("uploaded", true)
            json.put("uploaded_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            record.writeText(json.toString(2))
            File(uploadedDir(context), pendingFile.name).writeText(json.toString(2))
        }
        pendingFile.delete()
    }

    fun deleteRecord(context: Context, item: GalleryItem) {
        item.imageFile?.delete()
        item.recordFile.delete()
        File(pendingDir(context), item.recordFile.name).delete()
        File(uploadedDir(context), item.recordFile.name).delete()
    }

    fun updateRecordJson(context: Context, item: GalleryItem, mutator: (JSONObject) -> Unit) {
        val json = JSONObject(item.recordFile.readText())
        mutator(json)
        item.recordFile.writeText(json.toString(2))
        val pending = File(pendingDir(context), item.recordFile.name)
        if (pending.exists()) {
            val p = JSONObject(pending.readText())
            mutator(p)
            pending.writeText(p.toString(2))
        }
    }

    fun deleteUploaded(context: Context): Int {
        var n = 0
        listGallery(context).filter { it.synced }.forEach {
            deleteRecord(context, it)
            n++
        }
        return n
    }

    fun clearAll(context: Context): Int {
        var n = 0
        listGallery(context).forEach {
            deleteRecord(context, it)
            n++
        }
        return n
    }

    data class Stats(
        val total: Int,
        val pending: Int,
        val uploaded: Int,
        val calibShots: Int,
        val sceneShots: Int,
        val freeMb: Long,
        val totalMb: Long,
    )

    fun stats(context: Context): Stats {
        val gallery = listGallery(context)
        val root = root(context)
        val stat = StatFs(root.absolutePath)
        val free = stat.availableBytes / (1024 * 1024)
        val total = stat.totalBytes / (1024 * 1024)
        return Stats(
            total = gallery.size,
            pending = gallery.count { !it.synced },
            uploaded = gallery.count { it.synced },
            calibShots = gallery.count {
                it.json.optJSONObject("capture")?.optString("purpose") == "device_calibration"
            },
            sceneShots = gallery.count {
                it.json.optJSONObject("capture")?.optString("purpose") == "scene_capture"
            },
            freeMb = free,
            totalMb = total,
        )
    }
}
