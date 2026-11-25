package org.chromatex.lab.data

import android.util.SizeF
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.min

/**
 * Overlay tek kare = 100mm kumaş deliği.
 *
 * Evrensel oran (cihaz/FOV tahmini yok):
 * - Tam kart (cetvel + 18 yama) = 220 mm
 * - Delik = 100 mm → delik, kısa kenarın %38’i
 *   (kart kısa kenarın ~%84’ünü doldurur; %3 margin guard analyzer’da).
 */
data class CameraOptics(
    val focalMm: Float,
    val sensorWmm: Float,
    val sensorHmm: Float,
    val captureW: Int,
    val captureH: Int,
) {
    val hfovDeg: Double get() = 2.0 * atan((sensorWmm / (2.0 * focalMm)).toDouble()) * 180.0 / PI
    val vfovDeg: Double get() = 2.0 * atan((sensorHmm / (2.0 * focalMm)).toDouble()) * 180.0 / PI

    val shortPx: Int get() = min(captureW, captureH)

    /** Sabit fiziksel oran — modele göre ölçeklenmez. */
    fun recommendedWindowFraction(): Float = HOLE_FILL_FRAC

    fun holeSidePx(windowFraction: Float = recommendedWindowFraction()): Int =
        (shortPx * windowFraction).toInt()

    fun pxPerMm(windowFraction: Float = recommendedWindowFraction()): Double =
        holeSidePx(windowFraction) / HOLE_MM

    fun summary(windowFraction: Float = recommendedWindowFraction()): String {
        val px = holeSidePx(windowFraction)
        return "f=%.2fmm FOV≈%.0f×%.0f° hole≈%dpx (%.1f px/mm) frac=%.2f".format(
            focalMm, hfovDeg, vfovDeg, px, px / HOLE_MM, windowFraction,
        )
    }

    companion object {
        const val HOLE_MM = 100.0
        /**
         * Siyah halka dış kenar: 100 + 2*(5 yeşil + 6 beyaz + 8 siyah) = 138 mm.
         * measureBlackRing iç kenarı 122 mm ölçer; bounding guard
         * span’ı 138 mm referansına göre 220 mm tam karta ölçekler.
         */
        const val BLACK_RING_OUTER_MM = 138.0
        /** Cetvel + 18 kalibrasyon yaması dahil gerçek dış genişlik. */
        const val FULL_TARGET_MM = 220.0
        const val RING_GREEN_MM = 5.0
        const val RING_WHITE_MM = 6.0
        const val RING_BLACK_MM = 8.0
        const val RING_OUTER_MM = BLACK_RING_OUTER_MM
        const val TARGET_CARD_MM = FULL_TARGET_MM
        const val COLOR_BLOCK_MM = FULL_TARGET_MM

        /**
         * 100/220 * 0.836 ≈ 0.38 — delik kısa kenarın %38’i (izin: 0.35..0.44).
         * Tam kart ~%84; sağ/sol R-G-B-Y ve C-M-O-Fb yamaları kadrajda kalır.
         * Analyzer ayrıca %3 margin guard uygular.
         */
        const val HOLE_FILL_FRAC = 0.38f

        fun from(
            focalMm: Float?,
            sensor: SizeF?,
            captureW: Int?,
            captureH: Int?,
        ): CameraOptics? {
            if (focalMm == null || focalMm <= 0f) return null
            if (sensor == null || sensor.width <= 0f || sensor.height <= 0f) return null
            val w = captureW ?: return null
            val h = captureH ?: return null
            if (w < 64 || h < 64) return null
            return CameraOptics(focalMm, sensor.width, sensor.height, w, h)
        }

        /** Sabit delik oranı. İzin verilen aralık 0.35f..0.44f; değer 0.38f. */
        fun fallbackWindowFraction(): Float = HOLE_FILL_FRAC
    }
}
