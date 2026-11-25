package org.chromatex.lab.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import org.chromatex.lab.data.CameraOptics
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PostCaptureResult(
    val pass: Boolean,
    val reason: String,
    val fullWidth: Int,
    val fullHeight: Int,
    val ringOk: Boolean = false,
    val patchesInFrame: Boolean = false,
    val ringLeft: Double = -1.0,
    val ringRight: Double = -1.0,
    val ringTop: Double = -1.0,
    val ringBottom: Double = -1.0,
    val pxPerMm: Double = 0.0,
    val holeSidePx: Int = 0,
    val meanEdgeStep: Double = 0.0,
    val minEdgeStep: Double = 0.0,
    val whiteRingMean: Double = 0.0,
    val whiteClipRatio: Double = 0.0,
    val pureLumaMean: Double = 0.0,
    val pureStd: Double = 0.0,
    val glareRatio: Double = 0.0,
)

/**
 * onImageSaved sonrasında diske yazılan gerçek JPEG'i iki ölçekte (~20-35 ms) doğrular:
 * - Ölçek-Lo (~560..800 kısa kenar, 2x kutu ortalama): 3-bant imzalı (Beyaz 6mm -> Siyah 8mm -> Dış Beyaz 3mm)
 *   halka kilidi — Zebra/desenli kumaşa, beyaz kumaş->yeşil halkaya ve v1 kartlara takılmaz.
 * - Ölçek-Hi (~1120..1600 kısa kenar): gerçek optik odak ve hareket bulanıklığı uçurum ölçümü (2-px cliff).
 */
object PostCaptureVerifier {

    fun verify(photoFile: File): PostCaptureResult {
        if (!photoFile.exists() || photoFile.length() < 4096L) {
            return PostCaptureResult(false, "DOSYA_BOS", 0, 0)
        }
        val exifRot = try {
            when (
                ExifInterface(photoFile.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) {
            return PostCaptureResult(false, "JPEG_OKUNAMADI", 0, 0)
        }
        val fullW = if (exifRot == 90 || exifRot == 270) rawH else rawW
        val fullH = if (exifRot == 90 || exifRot == 270) rawW else rawH
        val fullShort = min(fullW, fullH)

        // Ölçek-Hi: kısa kenar ~1000..1600 px (8MP Lenovo & 12MP Xiaomi -> sampleHi=2)
        var sampleHi = 1
        while (fullShort / (sampleHi * 2) >= 950) {
            sampleHi *= 2
        }
        if (sampleHi < 2 && fullShort >= 1800) sampleHi = 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleHi
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(photoFile.absolutePath, opts)
            ?: return PostCaptureResult(false, "JPEG_DECODE_HATA", fullW, fullH)

        val bmp = if (exifRot != 0) {
            try {
                val m = Matrix().apply { postRotate(exifRot.toFloat()) }
                val r = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, false)
                if (r !== decoded) decoded.recycle()
                r
            } catch (_: Exception) {
                decoded
            }
        } else {
            decoded
        }

        val wHi = bmp.width
        val hHi = bmp.height
        val lumaHi = IntArray(wHi * hHi)
        try {
            val rowBuf = IntArray(wHi)
            for (yy in 0 until hHi) {
                bmp.getPixels(rowBuf, 0, wHi, 0, yy, wHi, 1)
                val base = yy * wHi
                for (xx in 0 until wHi) {
                    val px = rowBuf[xx]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    lumaHi[base + xx] = (77 * r + 150 * g + 29 * b) shr 8
                }
            }
        } finally {
            bmp.recycle()
        }

        // Ölçek-Lo: 2x2 kutu ortalama (~563x816 Lenovo, ~768x1024 Xiaomi)
        val w = (wHi / 2).coerceAtLeast(64)
        val h = (hHi / 2).coerceAtLeast(64)
        val luma = IntArray(w * h)
        for (yy in 0 until h) {
            val r0 = (2 * yy) * wHi
            val r1 = (2 * yy + 1).coerceAtMost(hHi - 1) * wHi
            val base = yy * w
            for (xx in 0 until w) {
                val c0 = 2 * xx
                val c1 = (2 * xx + 1).coerceAtMost(wHi - 1)
                luma[base + xx] = (lumaHi[r0 + c0] + lumaHi[r0 + c1] + lumaHi[r1 + c0] + lumaHi[r1 + c1]) shr 2
            }
        }

        fun yAt(x: Int, y: Int): Int {
            val xx = x.coerceIn(0, w - 1)
            val yy = y.coerceIn(0, h - 1)
            return luma[yy * w + xx]
        }

        fun yHiAt(x: Int, y: Int): Int {
            val xx = x.coerceIn(0, wHi - 1)
            val yy = y.coerceIn(0, hHi - 1)
            return lumaHi[yy * wHi + xx]
        }

        val shortPx = min(w, h)
        val holeSide = (shortPx * CameraOptics.HOLE_FILL_FRAC).toInt().coerceAtLeast(48)
        val nomMm = (holeSide / CameraOptics.HOLE_MM).coerceAtLeast(1.0)
        val cx = w / 2
        val cy = h / 2
        val spanHalf = (holeSide * 0.35).toInt()
        val startOff = (holeSide * 0.32).toInt().coerceAtLeast(24)
        val maxOffX = (w / 2) - 4
        val maxOffY = (h / 2) - 4
        val rayStep = (shortPx / 180).coerceIn(2, 5)

        data class SideHit(val edgeLo: Double, val stepGradHi: Double)

        fun scanSide(alongIsY: Boolean, sign: Int): SideHit? {
            val cAlong = if (alongIsY) cy else cx
            val maxOff = if (alongIsY) maxOffX else maxOffY
            val pts = ArrayList<Int>(96)
            var total = 0
            var along = cAlong - spanHalf
            val alongEnd = cAlong + spanHalf
            while (along <= alongEnd) {
                total++
                fun at(o: Int): Int = if (alongIsY) {
                    yAt(cx + sign * o, along)
                } else {
                    yAt(along, cy + sign * o)
                }
                var bestRayScore = -1e9
                var bestCoord = Int.MIN_VALUE
                var off = startOff
                while (off < maxOff - 6) {
                    val lmm = (off / 61.0).coerceAtLeast(1.0)
                    val outerCheck = off + (10.8 * lmm).toInt().coerceAtLeast(6)
                    if (outerCheck >= maxOff) break

                    val wNear = (
                        at(off - (1.5 * lmm).toInt().coerceAtLeast(1)) +
                            at(off - (3.8 * lmm).toInt().coerceAtLeast(2))
                        ) * 0.5
                    val bNear = at(off + (2.5 * lmm).toInt().coerceAtLeast(2)).toDouble()
                    val bMid = at(off + (4.5 * lmm).toInt().coerceAtLeast(3)).toDouble()
                    val bFar = at(off + (6.5 * lmm).toInt().coerceAtLeast(4)).toDouble()

                    if (wNear >= 95.0 && (wNear - bMid) >= 35.0) {
                        val bCore = (bNear + bMid + bFar) / 3.0
                        val darkOk =
                            (bCore < 118.0 && bFar < 125.0 && (wNear - bCore) >= 38.0) ||
                                (wNear >= 175.0 && bCore < 160.0 && bFar < 165.0 && (wNear - bCore) >= 60.0)
                        if (darkOk) {
                            val oW1 = at(off + (9.4 * lmm).toInt().coerceAtLeast(5)).toDouble()
                            val oW2 = at(outerCheck).toDouble()
                            val oWhite = max(oW1, oW2)
                            if (oWhite >= 100.0 && (oWhite - bCore) >= 30.0) {
                                val score = (min(wNear, oWhite) - bCore) - 0.6 * bCore
                                if (score > bestRayScore) {
                                    bestRayScore = score
                                    bestCoord = if (alongIsY) cx + sign * off else cy + sign * off
                                }
                            }
                        }
                    }
                    off++
                }
                if (bestCoord != Int.MIN_VALUE) {
                    pts.add(bestCoord)
                }
                along += rayStep
            }
            val cov = pts.size.toDouble() / total.coerceAtLeast(1)
            if (pts.size < 6 || cov < 0.48) return null
            val sortedPts = pts.sorted()
            val med = sortedPts[sortedPts.size / 2]
            val tol = max(5.0, 2.4 * nomMm)
            val inliers = sortedPts.filter { abs(it - med) <= tol }
            val inlierCov = inliers.size.toDouble() / total.coerceAtLeast(1)
            if (inliers.size < 6 || inlierCov < 0.42) return null
            val edgeLo = inliers[inliers.size / 2].toDouble()

            // Ölçek-Hi üzerinde gerçek 2-px optik kenar keskinliği
            val edgeHi = (edgeLo * 2.0).roundToInt()
            val aHi0 = ((cAlong - spanHalf * 0.7) * 2.0).toInt()
            val aHi1 = ((cAlong + spanHalf * 0.7) * 2.0).toInt()
            val grads = ArrayList<Double>(64)
            var a = aHi0
            while (a <= aHi1) {
                var best = 0
                for (d in -12..12) {
                    val s2 = if (alongIsY) {
                        abs(yHiAt(edgeHi + d + 1, a) - yHiAt(edgeHi + d - 1, a))
                    } else {
                        abs(yHiAt(a, edgeHi + d + 1) - yHiAt(a, edgeHi + d - 1))
                    }
                    if (s2 > best) best = s2
                }
                grads.add(best.toDouble())
                a += 6
            }
            val sortedGrads = grads.sorted()
            val medGrad = if (sortedGrads.isEmpty()) 0.0 else sortedGrads[sortedGrads.size / 2]
            return SideHit(edgeLo = edgeLo, stepGradHi = medGrad)
        }

        val left = scanSide(alongIsY = true, sign = -1)
        val right = scanSide(alongIsY = true, sign = +1)
        val top = scanSide(alongIsY = false, sign = -1)
        val bottom = scanSide(alongIsY = false, sign = +1)

        if (left == null || right == null || top == null || bottom == null) {
            return PostCaptureResult(
                pass = false,
                reason = "HAREKET_VEYA_HALKA_KAYBI",
                fullWidth = fullW,
                fullHeight = fullH,
            )
        }

        val spanX = right.edgeLo - left.edgeLo
        val spanY = bottom.edgeLo - top.edgeLo
        if (spanX < holeSide * 0.55 || spanY < holeSide * 0.55 ||
            abs(spanX - spanY) > max(spanX, spanY) * 0.22
        ) {
            return PostCaptureResult(
                pass = false,
                reason = "HALKA_SEKIL_BOZUK",
                fullWidth = fullW,
                fullHeight = fullH,
            )
        }

        val ppmLo = 0.5 * (spanX + spanY) / 122.0
        val fullScale = fullShort.toDouble() / shortPx.toDouble()
        val ppmFull = ppmLo * fullScale
        val holeFullPx = (ppmFull * CameraOptics.HOLE_MM).toInt()

        val fullCardW = spanX * (CameraOptics.FULL_TARGET_MM / 122.0)
        val fullCardH = spanY * (CameraOptics.FULL_TARGET_MM / 122.0)
        val mcx = 0.5 * (left.edgeLo + right.edgeLo)
        val mcy = 0.5 * (top.edgeLo + bottom.edgeLo)
        val patchesInFrame = (mcx - fullCardW * 0.5 >= 0.02 * w) &&
            (mcx + fullCardW * 0.5 <= 0.98 * w) &&
            (mcy - fullCardH * 0.5 >= 0.02 * h) &&
            (mcy + fullCardH * 0.5 <= 0.98 * h)

        val meanEdgeStep = 0.25 * (left.stepGradHi + right.stepGradHi + top.stepGradHi + bottom.stepGradHi)
        val minEdgeStep = min(min(left.stepGradHi, right.stepGradHi), min(top.stepGradHi, bottom.stepGradHi))

        // 6 mm beyaz referans halkası ortalaması + clipping kontrolü
        val insetW = (1.0 * ppmLo).toInt().coerceAtLeast(1)
        val whiteDepth = (CameraOptics.RING_WHITE_MM * ppmLo).toInt().coerceAtLeast(insetW + 2)
        val xPad = (spanX * 0.15).toInt()
        val yPad = (spanY * 0.15).toInt()
        var wSum = 0.0
        var wN = 0
        var wClip = 0
        fun sampleWhiteRect(ax: Int, ay: Int, bx: Int, by: Int) {
            var yy = min(ay, by).coerceAtLeast(0)
            val yy1 = max(ay, by).coerceAtMost(h)
            val xx0 = min(ax, bx).coerceAtLeast(0)
            val xx1 = max(ax, bx).coerceAtMost(w)
            while (yy < yy1) {
                var xx = xx0
                while (xx < xx1) {
                    val v = yAt(xx, yy)
                    wSum += v
                    wN++
                    if (v >= 248) wClip++
                    xx += 2
                }
                yy += 2
            }
        }
        sampleWhiteRect((left.edgeLo + xPad).toInt(), (top.edgeLo + insetW).toInt(), (right.edgeLo - xPad).toInt(), (top.edgeLo + whiteDepth - insetW).toInt())
        sampleWhiteRect((left.edgeLo + xPad).toInt(), (bottom.edgeLo - (whiteDepth - insetW)).toInt(), (right.edgeLo - xPad).toInt(), (bottom.edgeLo - insetW).toInt())
        sampleWhiteRect((left.edgeLo + insetW).toInt(), (top.edgeLo + yPad).toInt(), (left.edgeLo + whiteDepth - insetW).toInt(), (bottom.edgeLo - yPad).toInt())
        sampleWhiteRect((right.edgeLo - (whiteDepth - insetW)).toInt(), (top.edgeLo + yPad).toInt(), (right.edgeLo - insetW).toInt(), (bottom.edgeLo - yPad).toInt())
        val whiteMean = if (wN == 0) 0.0 else wSum / wN
        val whiteClipRatio = if (wN == 0) 0.0 else wClip.toDouble() / wN

        // Gerçek 92x92mm kumaş içi istatistiği (15 mm inset, Ölçek-Hi üzerinden)
        val padHi = (15.0 * ppmLo * 2.0).toInt().coerceAtLeast(12)
        val gx0 = ((left.edgeLo * 2.0).toInt() + padHi).coerceIn(0, wHi - 2)
        val gx1 = ((right.edgeLo * 2.0).toInt() - padHi).coerceIn(gx0 + 2, wHi)
        val gy0 = ((top.edgeLo * 2.0).toInt() + padHi).coerceIn(0, hHi - 2)
        val gy1 = ((bottom.edgeLo * 2.0).toInt() - padHi).coerceIn(gy0 + 2, hHi)
        var fSum = 0.0
        var fSq = 0.0
        var fGlare = 0
        var fN = 0
        run {
            var yy = gy0
            while (yy < gy1) {
                var xx = gx0
                while (xx < gx1) {
                    val v = yHiAt(xx, yy).toDouble()
                    fSum += v
                    fSq += v * v
                    if (v > 235.0) fGlare++
                    fN++
                    xx += 4
                }
                yy += 4
            }
        }
        val pureMean = if (fN == 0) 0.0 else fSum / fN
        val pureStd = if (fN == 0) 0.0 else sqrt(((fSq / fN) - pureMean * pureMean).coerceAtLeast(0.0))
        val glareRatio = if (fN == 0) 0.0 else fGlare.toDouble() / fN

        val reason = when {
            ppmFull < 5.8 || fullShort < 1800 -> "KAMERA_COZUNURLUGU_YETERSIZ"
            !patchesInFrame -> "YAMALAR_KADRAJ_DISI"
            whiteClipRatio > 0.35 || whiteMean > 246.0 -> "BEYAZ_KART_PATLADI"
            meanEdgeStep < 58.0 || minEdgeStep < 44.0 -> "ODAK_VEYA_HAREKET_YUMUSAK"
            else -> "OK"
        }

        return PostCaptureResult(
            pass = reason == "OK",
            reason = reason,
            fullWidth = fullW,
            fullHeight = fullH,
            ringOk = true,
            patchesInFrame = patchesInFrame,
            ringLeft = (left.edgeLo / w).coerceIn(0.0, 1.0),
            ringRight = (right.edgeLo / w).coerceIn(0.0, 1.0),
            ringTop = (top.edgeLo / h).coerceIn(0.0, 1.0),
            ringBottom = (bottom.edgeLo / h).coerceIn(0.0, 1.0),
            pxPerMm = ppmFull,
            holeSidePx = holeFullPx,
            meanEdgeStep = meanEdgeStep,
            minEdgeStep = minEdgeStep,
            whiteRingMean = whiteMean,
            whiteClipRatio = whiteClipRatio,
            pureLumaMean = pureMean,
            pureStd = pureStd,
            glareRatio = glareRatio,
        )
    }
}
