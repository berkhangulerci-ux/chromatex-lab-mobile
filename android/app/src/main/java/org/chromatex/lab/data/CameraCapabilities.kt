package org.chromatex.lab.data

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cihazdan bağımsız kamera yetenekleri — tablet/telefon fark etmez.
 * Değerler CameraCharacteristics’ten runtime okunur; sabit MP/ISO yok.
 */
data class CameraCapabilities(
    val cameraId: String,
    val jpegSizes: List<Size>,
    val preferredCaptureSize: Size?,
    val isoRange: Range<Int>?,
    val exposureNsRange: Range<Long>?,
    val supportsManualSensor: Boolean,
    val supportsAeCompensation: Boolean,
    val aeCompensationRange: Range<Int>?,
    val aeCompensationStep: Float?,
    val hasFlash: Boolean,
    val maxDigitalZoom: Float,
    val hardwareLevelLabel: String,
    val supportsEdgeModeOff: Boolean = false,
    val supportsNoiseReductionMinimal: Boolean = false,
    val supportsAwbLock: Boolean = false,
    val supportsAeLock: Boolean = false,
    val focalLengthMm: Float? = null,
) {
    val maxMegapixels: Double
        get() {
            val s = preferredCaptureSize
                ?: jpegSizes.maxByOrNull { it.width.toLong() * it.height }
                ?: return 0.0
            return s.width.toLong() * s.height / 1_000_000.0
        }

    /** FULL / LEVEL_3: manuel ISO/exp + ISP ince ayar güvenli. */
    val isFullOrLevel3: Boolean
        get() = hardwareLevelLabel == "FULL" || hardwareLevelLabel == "LEVEL_3"

    /** Manuel sensör + FULL/LEVEL_3 — LIMITED/LEGACY’de zorlama. */
    val allowManualIsoExp: Boolean
        get() = supportsManualSensor && isFullOrLevel3

    /** EDGE_OFF / NR_MINIMAL yalnız FULL/LEVEL_3 ve destekleniyorsa. */
    val allowStillIspTuning: Boolean
        get() = isFullOrLevel3 && (supportsEdgeModeOff || supportsNoiseReductionMinimal)

    fun summary(): String {
        val sz = preferredCaptureSize
        val mp = if (sz != null) {
            "%.1fMP %dx%d".format(maxMegapixels, sz.width, sz.height)
        } else {
            "MP?"
        }
        val man = when {
            allowManualIsoExp -> "manuelAE"
            supportsAeCompensation -> "EV"
            else -> "autoAE"
        }
        val f = focalLengthMm?.let { " f=%.1fmm".format(it) }.orEmpty()
        return "$mp · $man · HAL=$hardwareLevelLabel$f"
    }

    fun isoPresets(): List<Int> {
        val r = isoRange ?: return emptyList()
        val min = r.lower.coerceAtLeast(50)
        val max = r.upper
        val candidates = listOf(
            50, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
        )
        val inRange = candidates.filter { it in min..max }
        return when {
            inRange.isNotEmpty() -> (listOf(min) + inRange + listOf(max)).distinct().sorted()
            else -> listOf(min, max).distinct()
        }
    }

    fun exposurePresetsNs(): List<Long> {
        val r = exposureNsRange ?: return emptyList()
        val candidatesMs = listOf(
            0.5, 1.0, 2.0, 4.0, 8.0, 10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 66.0, 100.0,
        )
        val fromMs = candidatesMs.map { (it * 1_000_000.0).toLong() }
            .filter { it in r.lower..r.upper }
        return (listOf(r.lower) + fromMs + listOf(r.upper)).distinct().sorted()
    }

    fun formatExposure(ns: Long): String {
        val ms = ns / 1_000_000.0
        return if (ms < 1.0) {
            val denom = (1000.0 / ms.coerceAtLeast(0.001)).roundToInt().coerceAtLeast(1)
            "1/${denom}s"
        } else {
            "%.0fms".format(ms)
        }
    }

    companion object {
        /** Ana lens odak bandı — makro / ultra-geniş elenir. */
        const val MAIN_FOCAL_MIN_MM = 2.2f
        const val MAIN_FOCAL_MAX_MM = 6.0f

        fun from(chars: CameraCharacteristics, cameraId: String): CameraCapabilities {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpeg = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
                .sortedByDescending { it.width.toLong() * it.height }

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val manual =
                caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

            val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val aeStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val zoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val level = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "?"
            }

            val preferred = pickPreferredJpeg(jpeg, chars)
            val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            val nrModes = chars.get(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            )
            val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

            return CameraCapabilities(
                cameraId = cameraId,
                jpegSizes = jpeg,
                preferredCaptureSize = preferred,
                isoRange = iso,
                exposureNsRange = exp,
                supportsManualSensor = manual && iso != null && exp != null,
                supportsAeCompensation = aeRange != null && aeRange.lower != aeRange.upper,
                aeCompensationRange = aeRange,
                aeCompensationStep = aeStep?.let {
                    it.numerator.toFloat() / it.denominator.toFloat()
                },
                hasFlash = flash,
                maxDigitalZoom = zoom,
                hardwareLevelLabel = level,
                supportsEdgeModeOff =
                    edgeModes?.contains(CameraMetadata.EDGE_MODE_OFF) == true,
                supportsNoiseReductionMinimal =
                    nrModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) == true,
                supportsAwbLock =
                    chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
                supportsAeLock =
                    chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
                focalLengthMm = focals?.firstOrNull(),
            )
        }

        fun hasUsableAutofocus(chars: CameraCharacteristics): Boolean {
            val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
            return modes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ||
                modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        fun isMainBackFocal(focalMm: Float): Boolean =
            focalMm in MAIN_FOCAL_MIN_MM..MAIN_FOCAL_MAX_MM

        fun pickPreferredJpeg(
            jpeg: List<Size>,
            chars: CameraCharacteristics,
        ): Size? {
            if (jpeg.isEmpty()) return null
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val sensorAspect = if (active != null && active.height() > 0) {
                active.width().toDouble() / active.height()
            } else {
                4.0 / 3.0
            }
            val maxPx = jpeg.maxOf { it.width.toLong() * it.height }
            val top = jpeg.filter { it.width.toLong() * it.height >= (maxPx * 0.98).toLong() }
            return top.minByOrNull { s ->
                val a = s.width.toDouble() / s.height.coerceAtLeast(1)
                abs(a - sensorAspect)
            } ?: jpeg.first()
        }
    }
}
