package org.chromatex.lab.ui

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.chromatex.lab.data.CameraOptics
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class FrameGuide(
    val message: String,
    val ok: Boolean,
    val luma: Double,
    val edgeLuma: Double = 0.0,
    val centerStd: Double = 0.0,
    val sharpness: Double = 0.0,
    val skewPx: Double = 0.0,
    val coachKey: CoachKey = CoachKey.NONE,
    val arrow: String = "",
    val geometryMode: String = "window_priority",
    val paperEdgesVisible: Int = 0,
    val colorRingScore: Double = 0.0,
    val windowFraction: Double = 0.80,
    val holeSidePx: Int = 0,
    val pxPerMm: Double = 0.0,
    /** Ham (EMA’sız) — yazı netliği (etiket bantları) */
    val rawSharpness: Double = 0.0,
    val rawSkewPx: Double = 0.0,
    val borderScore: Double = 0.0,
    val consecutiveOk: Int = 0,
    val qualityOk: Boolean = false,
    val analysisShortPx: Int = 0,
    /** Renk köşeleri dış kareye oturdu mu (toleranslı) */
    val cornersSeated: Boolean = false,
    val textSharpness: Double = 0.0,
    /** Delik+halka lightRef EMA — pozlama politikası için (luma=cMean ayrı) */
    val lightEma: Double = 0.0,
    /** Yönlü eğim: UST|ALT|SOL|SAG|? (su terazisi) */
    val skewDir: String = "",
    /** v2: siyah halka skoru (yüksek = beyaz→siyah kenar kilitli) */
    val blackRingScore: Double = 0.0,
    /** v2: yeşil halka skoru */
    val greenRingScore: Double = 0.0,
    /** Ölçülen doluluk (her iki eksen ortası); uzak sahte kenar reddi */
    val fillRatio: Double = 0.0,
    /** Siyah halka iç kenarları — analiz çözünürlüğüne normalize 0..1; yoksa -1 */
    val ringLeft: Double = -1.0,
    val ringRight: Double = -1.0,
    val ringTop: Double = -1.0,
    val ringBottom: Double = -1.0,
    /** Kumaş deliğindeki specular parlama oranı (luma > 235) */
    val glareRatio: Double = 0.0,
    /** 6 mm beyaz referans halka ortalaması (kalibrasyonlu incident pozlama için; yoksa -1) */
    val whiteRingLuma: Double = -1.0,
    /** 6 mm beyaz referans halkada patlayan piksel oranı (luma >= 246) */
    val whiteRingClipRatio: Double = 0.0,
    /** Siyah-beyaz halka geçiş uçurumu (2-px adım kontrastı) */
    val ringEdgeStep: Double = 0.0,
)

/**
 * Sarı kare = 100 mm delik.
 * v2 FINAL kartında halkalar içten dışa: Yeşil 5mm -> Beyaz 6mm -> Siyah 8mm.
 * Siyah halkanın beyaz şeride bakan iç kenarları arası mesafe = 100 + 10 + 12 = 122 mm.
 */
private class SideLock(
    val edge: Double,
    val low: Double,
    val high: Double,
)

private class RingMeasure(
    val ok: Boolean,
    val left: Double,
    val right: Double,
    val top: Double,
    val bottom: Double,
    val spanX: Double,
    val spanY: Double,
    val skewLeft: Double,
    val skewRight: Double,
    val skewTop: Double,
    val skewBot: Double,
    val pitch: Double,
    val roll: Double,
)

class FramingGuideAnalyzer(
    private val onGuide: (FrameGuide) -> Unit,
) : ImageAnalysis.Analyzer {

    val windowFractionRef = AtomicReference(CameraOptics.HOLE_FILL_FRAC)

    private var emaStd = 0.0
    private var emaSharp = 0.0
    private var emaTextSharp = 0.0
    private var emaLight = 0.0
    private var emaSkew = 0.0
    private var emaBorder = 0.0
    private var emaPitch = 0.0
    private var emaRoll = 0.0
    private var emaReady = false
    private val alpha = 0.35
    private var consecutiveOk = 0
    /** Yönlü EGİK sticky — sol/sağ her kare flip olmasın */
    private var stickySkewKey: CoachKey = CoachKey.SKEW

    /** İnce süprem tabanı. Kapı ağırlığı halka kenarı (ringSharpOk ≥ 8). */
    private val minFabricSharpRaw = 3.8
    private val minFabricSharpEma = 3.8
    private val minTextSharpRaw = 8.0
    private val minTextSharpEma = 8.0
    private val minStd = 4.0
    private val minBorder = 12.0
    private val needConsecutiveOk = 3

    private fun ema(prev: Double, x: Double): Double =
        if (!emaReady) x else alpha * x + (1.0 - alpha) * prev

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val plane = image.planes.firstOrNull() ?: run {
            image.close()
            return
        }
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val rotationDegrees = image.imageInfo.rotationDegrees
        val shortPxEarly = minOf(w, h)
        /** 640×480 ↔ 1280×960 aynı keskinlik ölçeği. */
        val step = (shortPxEarly / 96).coerceIn(3, 8)
        val windowFraction = windowFractionRef.get().toDouble()
            .coerceIn(0.35, 0.44)

        fun sample(x: Int, y: Int): Int {
            val xx = x.coerceIn(0, w - 1)
            val yy = y.coerceIn(0, h - 1)
            val index = yy * rowStride + xx * pixelStride
            if (index !in 0 until buffer.capacity()) return 0
            return buffer.get(index).toInt() and 0xFF
        }

        fun regionMean(x0: Int, y0: Int, x1: Int, y1: Int): Pair<Double, Double> {
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            var y = y0.coerceAtLeast(0)
            val yy1 = y1.coerceAtMost(h)
            val xx0 = x0.coerceAtLeast(0)
            val xx1 = x1.coerceAtMost(w)
            while (y < yy1) {
                var x = xx0
                while (x < xx1) {
                    val v = sample(x, y).toDouble()
                    sum += v
                    sumSq += v * v
                    n++
                    x += step
                }
                y += step
            }
            if (n == 0) return 0.0 to 0.0
            val mean = sum / n
            return mean to ((sumSq / n) - mean * mean)
        }

        fun regionSharpness(x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var sum = 0.0
            var n = 0
            val off = step
            var y = (y0 + off).coerceAtLeast(off)
            val yy1 = (y1 - off).coerceAtMost(h - off)
            val xx0 = (x0 + off).coerceAtLeast(off)
            val xx1 = (x1 - off).coerceAtMost(w - off)
            while (y < yy1) {
                var x = xx0
                while (x < xx1) {
                    sum += abs(sample(x + off, y) - sample(x - off, y)).toDouble() +
                        abs(sample(x, y + off) - sample(x, y - off)).toDouble()
                    n++
                    x += step
                }
                y += step
            }
            return if (n == 0) 0.0 else sum / n
        }

        fun holeEdgeY(x: Int, yFrom: Int, yTo: Int, outwardDown: Boolean): Int {
            var bestY = -1
            var bestScore = 0
            var y = yFrom.coerceAtLeast(2)
            val lim = yTo.coerceAtMost(h - 3)
            while (y < lim) {
                val g = abs(sample(x, y + 2) - sample(x, y - 2))
                val outer = if (outwardDown) sample(x, y + 3) else sample(x, y - 3)
                val inner = if (outwardDown) sample(x, y - 3) else sample(x, y + 3)
                val darkBias = if (outer < inner - 12) 28 else 0
                val score = g + darkBias
                if (score > bestScore) {
                    bestScore = score
                    bestY = y
                }
                y += 2
            }
            return if (bestScore >= 12) bestY else -1
        }

        fun holeEdgeX(y: Int, xFrom: Int, xTo: Int, outwardRight: Boolean): Int {
            var bestX = -1
            var bestScore = 0
            var x = xFrom.coerceAtLeast(2)
            val lim = xTo.coerceAtMost(w - 3)
            while (x < lim) {
                val g = abs(sample(x + 2, y) - sample(x - 2, y))
                val outer = if (outwardRight) sample(x + 3, y) else sample(x - 3, y)
                val inner = if (outwardRight) sample(x - 3, y) else sample(x + 3, y)
                val darkBias = if (outer < inner - 12) 28 else 0
                val score = g + darkBias
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                }
                x += 2
            }
            return if (bestScore >= 12) bestX else -1
        }

        val shortPx = minOf(w, h)
        val holeSide = (shortPx * windowFraction).toInt().coerceAtLeast(48)
        val holeSidePx = holeSide
        val pxPerMm = holeSide / CameraOptics.HOLE_MM

        val mx0 = ((w - holeSide) / 2).coerceAtLeast(0)
        val my0 = ((h - holeSide) / 2).coerceAtLeast(0)
        val mx1 = (mx0 + holeSide).coerceAtMost(w)
        val my1 = (my0 + holeSide).coerceAtMost(h)

        val inset = (holeSide * 0.12).toInt().coerceAtLeast(4)
        // v2 FINAL halka mm->px (icten disa): yesil 5mm | beyaz 6mm | siyah 8mm
        val mm = holeSide / CameraOptics.HOLE_MM
        val greenPx = (5.0 * mm).toInt().coerceAtLeast(2)
        val whitePx = (6.0 * mm).toInt().coerceAtLeast(3)
        val blackPx = (8.0 * mm).toInt().coerceAtLeast(3)
        val search = (greenPx + whitePx + blackPx + (holeSide * 0.06).toInt()).coerceAtLeast(20)
        val band = (greenPx + whitePx + blackPx).coerceAtLeast(10)
        val cornerBox = (band * 0.9).toInt().coerceAtLeast(8)

        val (cMean, cVar) = regionMean(mx0 + inset, my0 + inset, mx1 - inset, my1 - inset)
        val cStd = sqrt(cVar.coerceAtLeast(0.0))
        val fabricSharp = regionSharpness(mx0 + inset, my0 + inset, mx1 - inset, my1 - inset)

        // v2 FINAL sirasina gore halka bolgeleri: delik -> yesil(5mm) -> beyaz(6mm) -> siyah(8mm)
        val g0 = 0
        val g1 = greenPx
        val (gTop, gTopVar) = regionMean(mx0, (my0 - g1).coerceAtLeast(0), mx1, (my0 - g0).coerceAtLeast(0))
        val (gBot, gBotVar) = regionMean(mx0, (my1 + g0).coerceAtMost(h), mx1, (my1 + g1).coerceAtMost(h))
        val (gLeft, gLeftVar) = regionMean((mx0 - g1).coerceAtLeast(0), my0, (mx0 - g0).coerceAtLeast(0), my1)
        val (gRight, gRightVar) = regionMean((mx1 + g0).coerceAtMost(w), my0, (mx1 + g1).coerceAtMost(w), my1)
        val greenMean = listOf(gTop, gBot, gLeft, gRight).average()

        val bStart = greenPx + whitePx
        val bEnd = bStart + blackPx
        val bTop = regionMean(mx0, (my0 - bEnd).coerceAtLeast(0), mx1, (my0 - bStart).coerceAtLeast(0)).first
        val bBot = regionMean(mx0, (my1 + bStart).coerceAtMost(h), mx1, (my1 + bEnd).coerceAtMost(h)).first
        val bLeft = regionMean((mx0 - bEnd).coerceAtLeast(0), my0, (mx0 - bStart).coerceAtLeast(0), my1).first
        val bRight = regionMean((mx1 + bStart).coerceAtMost(w), my0, (mx1 + bEnd).coerceAtMost(w), my1).first
        val blackMean = listOf(bTop, bBot, bLeft, bRight).average()

        val blackRingScore =
            ((120.0 - blackMean).coerceIn(0.0, 120.0) / 120.0) * 40.0 +
                ((cMean - blackMean).coerceIn(0.0, 100.0) / 100.0) * 60.0
        val blackRingOk = blackMean < 110.0 && (cMean - blackMean) > 18.0 && blackRingScore >= 20.0

        val greenRingScore = sqrt(
            listOf(gTopVar, gBotVar, gLeftVar, gRightVar).average().coerceAtLeast(0.0),
        ) + ((greenMean - blackMean).coerceIn(0.0, 80.0) / 80.0) * 20.0

        val textTop = regionSharpness(mx0, (my0 - bEnd).coerceAtLeast(0), mx1, my0)
        val textBot = regionSharpness(mx0, my1, mx1, (my1 + bEnd).coerceAtMost(h))
        val textLeft = regionSharpness((mx0 - bEnd).coerceAtLeast(0), my0, mx0, my1)
        val textRight = regionSharpness(mx1, my0, (mx1 + bEnd).coerceAtMost(w), my1)
        val textSharp = listOf(textTop, textBot, textLeft, textRight).average()

        val ringMean = blackMean * 0.55 + greenMean * 0.45
        val colorRingScore = greenRingScore
        val lightRef = cMean * 0.55 + ringMean * 0.45

        val edgeBand = (shortPx * 0.08).toInt().coerceAtLeast(6)
        val (tMean, _) = regionMean(mx0, 0, mx1, edgeBand)
        val (bMean, _) = regionMean(mx0, h - edgeBand, mx1, h)
        val (lMean, _) = regionMean(0, my0, edgeBand, my1)
        val (rMean, _) = regionMean(w - edgeBand, my0, w, my1)
        val paperEdges = listOf(tMean, bMean, lMean, rMean).count { it > 140 }
        val edgeMean = if (paperEdges >= 2) {
            listOf(tMean, bMean, lMean, rMean).filter { it > 140 }.average()
        } else {
            ringMean
        }

        val topYL = holeEdgeY((mx0 + holeSide * 0.22).toInt(), my0 - search, my0 + search, outwardDown = false)
        val topYR = holeEdgeY((mx0 + holeSide * 0.78).toInt(), my0 - search, my0 + search, outwardDown = false)
        val botYL = holeEdgeY((mx0 + holeSide * 0.22).toInt(), my1 - search, my1 + search, outwardDown = true)
        val botYR = holeEdgeY((mx0 + holeSide * 0.78).toInt(), my1 - search, my1 + search, outwardDown = true)
        val leftXT = holeEdgeX((my0 + holeSide * 0.22).toInt(), mx0 - search, mx0 + search, outwardRight = false)
        val leftXB = holeEdgeX((my0 + holeSide * 0.78).toInt(), mx0 - search, mx0 + search, outwardRight = false)
        val rightXT = holeEdgeX((my0 + holeSide * 0.22).toInt(), mx1 - search, mx1 + search, outwardRight = true)
        val rightXB = holeEdgeX((my0 + holeSide * 0.78).toInt(), mx1 - search, mx1 + search, outwardRight = true)

        // 3-BANTLI IMZALI TARAMA (Ic Beyaz 6mm -> Siyah Halka 8mm -> Dis Beyaz A4 Kagit 3mm):
        // Zebra/baskili kumas cizgilerine, beyaz kumas->yesil halka gecisine, dis A4 kenarina
        // ve kesikli v1 renk yamalarina takilmadan yalnizca gercek 61mm v2 siyah halkaya kilitlenir.
        fun measureBlackRing(): RingMeasure {
            val none = RingMeasure(
                false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            )
            val cx = w / 2
            val cy = h / 2
            val nomMm = (holeSide / CameraOptics.HOLE_MM).coerceAtLeast(1.0)
            val rayStep = (shortPx / 180).coerceIn(2, 4)

            fun sideFromCenter(alongIsY: Boolean, dir: Int): SideLock? {
                val points = ArrayList<IntArray>(96)
                val spanHalf = (holeSide * 0.35).toInt()
                val alongStart = (if (alongIsY) cy else cx) - spanHalf
                val alongEnd = (if (alongIsY) cy else cx) + spanHalf

                // Uzak kart (scale ~0.55) dahil yakalamak icin 0.32'den basla; 3-bant skoru ic deseni eler.
                val startOffset = (holeSide * 0.32).toInt().coerceAtLeast(24)
                val maxOffset = ((if (alongIsY) w else h) / 2) - 4

                var along = alongStart
                var totalRays = 0
                while (along <= alongEnd) {
                    totalRays++
                    fun atOffset(off: Int): Int {
                        val pos = (if (alongIsY) cx else cy) + (off * dir)
                        val x = if (alongIsY) pos else along
                        val y = if (alongIsY) along else pos
                        return sample(x, y)
                    }

                    var bestRayScore = -1e9
                    var bestEdgeImg = -1
                    var off = startOffset
                    while (off < maxOffset - 6) {
                        // Eger off = 61mm siyah ic kenar ise, bu isin uzerindeki yerel 1mm olcegi = off / 61.0 px
                        val lmm = (off / 61.0).coerceAtLeast(1.0)
                        val outerCheck = off + (10.8 * lmm).toInt().coerceAtLeast(6)
                        if (outerCheck >= maxOffset) break

                        val w1 = atOffset(off - (1.5 * lmm).toInt().coerceAtLeast(1))
                        val w2 = atOffset(off - (3.8 * lmm).toInt().coerceAtLeast(2))
                        val wNear = (w1 + w2) * 0.5

                        val bNear = atOffset(off + (2.5 * lmm).toInt().coerceAtLeast(2)).toDouble()
                        val bMid = atOffset(off + (4.5 * lmm).toInt().coerceAtLeast(3)).toDouble()
                        val bFar = atOffset(off + (6.5 * lmm).toInt().coerceAtLeast(4)).toDouble()

                        if (wNear >= 95.0 && (wNear - bMid) >= 35.0) {
                            // 8mm siyah halka +2.5mm, +4.5mm ve +6.5mm'nin TAMAMINDA koyu kalmali (5mm yesil halkayi eler)
                            val bCore = (bNear + bMid + bFar) / 3.0
                            val darkOk =
                                (bCore < 118.0 && bFar < 125.0 && (wNear - bCore) >= 38.0) ||
                                    (wNear >= 175.0 && bCore < 160.0 && bFar < 165.0 && (wNear - bCore) >= 60.0)
                            if (darkOk) {
                                // 69..72mm (siyah halka disindaki 3mm yamasiz beyaz A4 kanali) tekrar beyaza cikmali
                                val oW1 = atOffset(off + (9.4 * lmm).toInt().coerceAtLeast(5)).toDouble()
                                val oW2 = atOffset(outerCheck).toDouble()
                                val oWhite = max(oW1, oW2)
                                if (oWhite >= 100.0 && (oWhite - bCore) >= 30.0) {
                                    // Gercek derin siyah (bCore ~40-75) + cift tarafli parlak beyaz en yuksek puani alir
                                    val score = (minOf(wNear, oWhite) - bCore) - 0.6 * bCore
                                    if (score > bestRayScore) {
                                        bestRayScore = score
                                        bestEdgeImg = (if (alongIsY) cx else cy) + (off * dir)
                                    }
                                }
                            }
                        }
                        off++
                    }
                    if (bestEdgeImg >= 0) {
                        points.add(intArrayOf(along, bestEdgeImg))
                    }
                    along += rayStep
                }

                val hitRatio = points.size.toDouble() / totalRays.coerceAtLeast(1)
                if (points.size < 6 || hitRatio < 0.48) return null
                points.sortBy { it[1] }

                val medAll = points[points.size / 2][1]
                val tol = max(5, (2.4 * nomMm).toInt())
                val cluster = points.filter { abs(it[1] - medAll) <= tol }
                val coverage = cluster.size.toDouble() / totalRays.coerceAtLeast(1)
                if (cluster.size < 6 || coverage < 0.42) return null

                val edges = cluster.map { it[1] }.sorted()
                val edgeMed = edges[edges.size / 2].toDouble()
                val alongSorted = cluster.sortedBy { it[0] }
                val third = (alongSorted.size / 3).coerceAtLeast(1)
                val lowPts = alongSorted.take(third).map { it[1] }.sorted()
                val highPts = alongSorted.takeLast(third).map { it[1] }.sorted()

                return SideLock(
                    edge = edgeMed,
                    low = lowPts[lowPts.size / 2].toDouble(),
                    high = highPts[highPts.size / 2].toDouble(),
                )
            }

            val left = sideFromCenter(alongIsY = true, dir = -1) ?: return none
            val right = sideFromCenter(alongIsY = true, dir = 1) ?: return none
            val top = sideFromCenter(alongIsY = false, dir = -1) ?: return none
            val bottom = sideFromCenter(alongIsY = false, dir = 1) ?: return none

            val spanX = right.edge - left.edge
            val spanY = bottom.edge - top.edge
            if (spanX < holeSide * 0.55 || spanY < holeSide * 0.55) return none
            if (abs(spanX - spanY) > max(spanX, spanY) * 0.22) return none

            return RingMeasure(
                ok = true,
                left = left.edge,
                right = right.edge,
                top = top.edge,
                bottom = bottom.edge,
                spanX = spanX,
                spanY = spanY,
                skewLeft = abs(left.high - left.low),
                skewRight = abs(right.high - right.low),
                skewTop = abs(top.high - top.low),
                skewBot = abs(bottom.high - bottom.low),
                pitch = (right.low - left.low) - (right.high - left.high),
                roll = (bottom.low - top.low) - (bottom.high - top.high),
            )
        }
        val ring = measureBlackRing()

        val skewTop = ring.skewTop
        val skewBot = ring.skewBot
        val skewLeft = ring.skewLeft
        val skewRight = ring.skewRight
        val rawSkew = max(max(skewTop, skewBot), max(skewLeft, skewRight))

        val pitchRaw = if (ring.ok) ring.pitch else 0.0
        val rollRaw = if (ring.ok) ring.roll else 0.0
        emaPitch = ema(emaPitch, pitchRaw)
        emaRoll = ema(emaRoll, rollRaw)

        fun skewTriple(key: CoachKey): Triple<String, CoachKey, String> = when (key) {
            CoachKey.SKEW_UP ->
                Triple("EGIK UST YAKIN — tabletin ustunu kaldir", key, "↑")
            CoachKey.SKEW_DOWN ->
                Triple("EGIK ALT YAKIN — tabletin ustunu yaklastir", key, "↓")
            CoachKey.SKEW_LEFT ->
                Triple("EGIK SOL YAKIN — saga egin / sola kaldir", key, "←")
            CoachKey.SKEW_RIGHT ->
                Triple("EGIK SAG YAKIN — sola egin / saga kaldir", key, "→")
            else ->
                Triple("YAMUK/EGIK — duz bakin", CoachKey.SKEW, "↺")
        }

        fun proposeSkewKeyBuffer(): CoachKey {
            val ap = abs(emaPitch)
            val ar = abs(emaRoll)
            val minDir = 8.0
            return when {
                ap >= ar * 1.25 && ap >= minDir && emaPitch > 0 -> CoachKey.SKEW_UP
                ap >= ar * 1.25 && ap >= minDir && emaPitch < 0 -> CoachKey.SKEW_DOWN
                ar > ap * 1.25 && ar >= minDir && emaRoll > 0 -> CoachKey.SKEW_LEFT
                ar > ap * 1.25 && ar >= minDir && emaRoll < 0 -> CoachKey.SKEW_RIGHT
                else -> CoachKey.SKEW
            }
        }

        /** Buffer → ekran: rotationDegrees kadar CW döndür. */
        fun skewToScreen(key: CoachKey): CoachKey {
            if (key == CoachKey.SKEW) return key
            val cw = listOf(
                CoachKey.SKEW_UP,
                CoachKey.SKEW_RIGHT,
                CoachKey.SKEW_DOWN,
                CoachKey.SKEW_LEFT,
            )
            val idx = cw.indexOf(key)
            if (idx < 0) return key
            val turns = ((rotationDegrees / 90) % 4 + 4) % 4
            return cw[(idx + turns) % 4]
        }

        val proposed = skewToScreen(proposeSkewKeyBuffer())
        stickySkewKey = when {
            proposed == CoachKey.SKEW -> stickySkewKey
            stickySkewKey == CoachKey.SKEW -> proposed
            proposed == stickySkewKey -> proposed
            (stickySkewKey == CoachKey.SKEW_UP && proposed == CoachKey.SKEW_DOWN) ||
                (stickySkewKey == CoachKey.SKEW_DOWN && proposed == CoachKey.SKEW_UP) ->
                if (abs(emaPitch) >= 12.0) proposed else stickySkewKey
            (stickySkewKey == CoachKey.SKEW_LEFT && proposed == CoachKey.SKEW_RIGHT) ||
                (stickySkewKey == CoachKey.SKEW_RIGHT && proposed == CoachKey.SKEW_LEFT) ->
                if (abs(emaRoll) >= 12.0) proposed else stickySkewKey
            else -> proposed
        }
        val (skewMsg, skewKey, skewArrow) = skewTriple(stickySkewKey)
        val skewDirLabel = when (skewKey) {
            CoachKey.SKEW_UP -> "UST"
            CoachKey.SKEW_DOWN -> "ALT"
            CoachKey.SKEW_LEFT -> "SOL"
            CoachKey.SKEW_RIGHT -> "SAG"
            else -> if (rawSkew > 0) "?" else ""
        }

        var borderSum = 0.0
        var borderN = 0
        fun addBorderY(x: Int, yNom: Int, outwardDown: Boolean) {
            val yy = holeEdgeY(x, yNom - search, yNom + search, outwardDown)
            if (yy >= 0) {
                borderSum += abs(sample(x, yy + 2) - sample(x, yy - 2)).toDouble()
                borderN++
            }
        }
        fun addBorderX(y: Int, xNom: Int, outwardRight: Boolean) {
            val xx = holeEdgeX(y, xNom - search, xNom + search, outwardRight)
            if (xx >= 0) {
                borderSum += abs(sample(xx + 2, y) - sample(xx - 2, y)).toDouble()
                borderN++
            }
        }
        addBorderY((mx0 + holeSide * 0.35).toInt(), my0, outwardDown = false)
        addBorderY((mx0 + holeSide * 0.65).toInt(), my0, outwardDown = false)
        addBorderY((mx0 + holeSide * 0.35).toInt(), my1, outwardDown = true)
        addBorderY((mx0 + holeSide * 0.65).toInt(), my1, outwardDown = true)
        addBorderX((my0 + holeSide * 0.35).toInt(), mx0, outwardRight = false)
        addBorderX((my0 + holeSide * 0.65).toInt(), mx0, outwardRight = false)
        addBorderX((my0 + holeSide * 0.35).toInt(), mx1, outwardRight = true)
        addBorderX((my0 + holeSide * 0.65).toInt(), mx1, outwardRight = true)
        val borderScore = if (borderN == 0) 0.0 else borderSum / borderN

        val leftX = when {
            leftXT >= 0 && leftXB >= 0 -> (leftXT + leftXB) / 2.0
            leftXT >= 0 -> leftXT.toDouble()
            leftXB >= 0 -> leftXB.toDouble()
            else -> -1.0
        }
        val rightX = when {
            rightXT >= 0 && rightXB >= 0 -> (rightXT + rightXB) / 2.0
            rightXT >= 0 -> rightXT.toDouble()
            rightXB >= 0 -> rightXB.toDouble()
            else -> -1.0
        }
        val topY = when {
            topYL >= 0 && topYR >= 0 -> (topYL + topYR) / 2.0
            topYL >= 0 -> topYL.toDouble()
            topYR >= 0 -> topYR.toDouble()
            else -> -1.0
        }
        val botY = when {
            botYL >= 0 && botYR >= 0 -> (botYL + botYR) / 2.0
            botYL >= 0 -> botYL.toDouble()
            botYR >= 0 -> botYR.toDouble()
            else -> -1.0
        }
        val measW = if (leftX >= 0 && rightX >= 0) abs(rightX - leftX) else -1.0
        val measH = if (topY >= 0 && botY >= 0) abs(botY - topY) else -1.0
        val fillW = if (measW > 0) measW / holeSide else -1.0
        val fillH = if (measH > 0) measH / holeSide else -1.0
        val fillRatioPre = when {
            fillW > 0 && fillH > 0 -> (fillW + fillH) / 2.0
            fillW > 0 -> fillW
            fillH > 0 -> fillH
            else -> -1.0
        }
        val fillRatio = fillRatioPre

        // v2 FINAL: Delik (100mm) + Yesil (5+5=10mm) + Beyaz (6+6=12mm) = 122.0 mm siyah ic kenar
        val ycx = (mx0 + mx1) / 2.0
        val ycy = (my0 + my1) / 2.0
        val innerSpanMm = 122.0
        val holeMeasX = if (ring.ok) ring.spanX * (CameraOptics.HOLE_MM / innerSpanMm) else -1.0
        val holeMeasY = if (ring.ok) ring.spanY * (CameraOptics.HOLE_MM / innerSpanMm) else -1.0
        val holeMeas = if (ring.ok) (holeMeasX + holeMeasY) / 2.0 else -1.0
        val measCx = if (ring.ok) (ring.left + ring.right) / 2.0 else ycx
        val measCy = if (ring.ok) (ring.top + ring.bottom) / 2.0 else ycy
        val dx = measCx - ycx
        val dy = measCy - ycy
        val scale = if (holeMeas > 0.0) holeMeas / holeSide else -1.0
        val alignTol = holeSide * 0.08
        val ringFound = ring.ok

        // 18-yama Bounding Box Guard: siyah ic kenar (122mm) → tam kart (220mm)
        val fullTargetW = if (ring.ok) {
            ring.spanX * (CameraOptics.FULL_TARGET_MM / innerSpanMm)
        } else {
            -1.0
        }
        val fullTargetH = if (ring.ok) {
            ring.spanY * (CameraOptics.FULL_TARGET_MM / innerSpanMm)
        } else {
            -1.0
        }
        val cardLeft = measCx - fullTargetW / 2.0
        val cardRight = measCx + fullTargetW / 2.0
        val cardTop = measCy - fullTargetH / 2.0
        val cardBot = measCy + fullTargetH / 2.0
        // Sağ/sol R-G-B-Y ve C-M-O-Fb yamaları kenara yapışmasın.
        val marginX = 0.03 * w
        val marginY = 0.03 * h
        val overflowLeft = ring.ok && cardLeft < marginX
        val overflowRight = ring.ok && cardRight > w - marginX
        val overflowTop = ring.ok && cardTop < marginY
        val overflowBot = ring.ok && cardBot > h - marginY
        val patchesInFrame = ring.ok &&
            !overflowLeft && !overflowRight && !overflowTop && !overflowBot

        // Buffer yönünü ekran yönüne çevir (rotationDegrees CW).
        val rotTurns = ((rotationDegrees / 90) % 4 + 4) % 4
        val (screenDx, screenDy) = when (rotTurns) {
            1 -> -dy to dx
            2 -> -dx to -dy
            3 -> dy to -dx
            else -> dx to dy
        }
        fun moveToScreen(key: CoachKey): CoachKey {
            val cw = listOf(
                CoachKey.MOVE_UP,
                CoachKey.MOVE_RIGHT,
                CoachKey.MOVE_DOWN,
                CoachKey.MOVE_LEFT,
            )
            val idx = cw.indexOf(key)
            return if (idx < 0) key else cw[(idx + rotTurns) % 4]
        }

        // 0.82..1.15 — 16:9'de YAKLAS/UZAKLAS savaşını azalt
        val ringAligned = ringFound &&
            scale in 0.82..1.15 &&
            abs(dx) <= alignTol &&
            abs(dy) <= alignTol &&
            patchesInFrame
        val cornersSeated = ringAligned
        val measuredPpm = if (ring.ok) ((ring.spanX + ring.spanY) / 2.0) / innerSpanMm else 0.0

        fun rectMean(ax: Int, ay: Int, bx: Int, by: Int): Pair<Double, Boolean> {
            val x0 = minOf(ax, bx)
            val x1 = maxOf(ax, bx)
            val y0 = minOf(ay, by)
            val y1 = maxOf(ay, by)
            val fullyInside = x0 >= 0 && y0 >= 0 && x1 <= w && y1 <= h &&
                (x1 - x0) >= 4 && (y1 - y0) >= 2
            return regionMean(x0, y0, x1, y1).first to fullyInside
        }

        // Parlama, kumaş parlaklığı, doku (cStd) ve kumaş netliği YALNIZCA gerçek 100mm kumaş deliğinin
        // içinden ölçülür (yeşil/beyaz/siyah halka kenarı asla kumaş metriğine sızmaz).
        val gx0: Int
        val gy0: Int
        val gx1: Int
        val gy1: Int
        if (ring.ok && measuredPpm > 0.0) {
            // Siyah iç kenar (122mm) → 6mm beyaz + 5mm yeşil + 4mm güvenlik = 15mm içeri (92x92mm saf kumaş)
            val pad = (15.0 * measuredPpm).toInt().coerceAtLeast(8)
            gx0 = (ring.left + pad).toInt().coerceIn(0, w - 2)
            gy0 = (ring.top + pad).toInt().coerceIn(0, h - 2)
            gx1 = (ring.right - pad).toInt().coerceIn(gx0 + 2, w)
            gy1 = (ring.bottom - pad).toInt().coerceIn(gy0 + 2, h)
        } else {
            gx0 = (mx0 + inset).coerceIn(0, w - 2)
            gy0 = (my0 + inset).coerceIn(0, h - 2)
            gx1 = (mx1 - inset).coerceIn(gx0 + 2, w)
            gy1 = (my1 - inset).coerceIn(gy0 + 2, h)
        }
        val (pureMean, pureVar) = if (ring.ok && measuredPpm > 0.0) {
            regionMean(gx0, gy0, gx1, gy1)
        } else {
            cMean to cVar
        }
        val pureStd = sqrt(pureVar.coerceAtLeast(0.0))
        val pureFabricSharp = if (ring.ok && measuredPpm > 0.0) {
            regionSharpness(gx0, gy0, gx1, gy1)
        } else {
            fabricSharp
        }

        // Halka kenar keskinliğini sabit mx0..mx1 yerine ölçülen gerçek halka koordinatlarında ölç.
        val effectiveTextSharp: Double
        val ringEdgeStep: Double
        if (ring.ok && measuredPpm > 0.0) {
            val rBand = (8.0 * measuredPpm).toInt().coerceAtLeast(4)
            val xIn0 = (ring.left + ring.spanX * 0.18).toInt().coerceIn(0, w - 2)
            val xIn1 = (ring.right - ring.spanX * 0.18).toInt().coerceIn(xIn0 + 2, w)
            val yIn0 = (ring.top + ring.spanY * 0.18).toInt().coerceIn(0, h - 2)
            val yIn1 = (ring.bottom - ring.spanY * 0.18).toInt().coerceIn(yIn0 + 2, h)
            val rtSharp = regionSharpness(xIn0, (ring.top - rBand).toInt(), xIn1, (ring.top + rBand).toInt())
            val rbSharp = regionSharpness(xIn0, (ring.bottom - rBand).toInt(), xIn1, (ring.bottom + rBand).toInt())
            val rlSharp = regionSharpness((ring.left - rBand).toInt(), yIn0, (ring.left + rBand).toInt(), yIn1)
            val rrSharp = regionSharpness((ring.right - rBand).toInt(), yIn0, (ring.right + rBand).toInt(), yIn1)
            effectiveTextSharp = listOf(rtSharp, rbSharp, rlSharp, rrSharp).average()

            fun sideCliff(alongIsY: Boolean, edgePos: Double): Double {
                val a0 = if (alongIsY) yIn0 else xIn0
                val a1 = if (alongIsY) yIn1 else xIn1
                val eInt = edgePos.toInt()
                var sumBest = 0.0
                var cnt = 0
                var a = a0
                val aStep = step.coerceAtLeast(4)
                while (a < a1) {
                    var best = 0
                    for (d in -3..3) {
                        val s = if (alongIsY) {
                            abs(sample(eInt + d + 1, a) - sample(eInt + d - 1, a))
                        } else {
                            abs(sample(a, eInt + d + 1) - sample(a, eInt + d - 1))
                        }
                        if (s > best) best = s
                    }
                    sumBest += best.toDouble()
                    cnt++
                    a += aStep
                }
                return if (cnt == 0) 0.0 else sumBest / cnt
            }
            ringEdgeStep = listOf(
                sideCliff(alongIsY = false, edgePos = ring.top),
                sideCliff(alongIsY = false, edgePos = ring.bottom),
                sideCliff(alongIsY = true, edgePos = ring.left),
                sideCliff(alongIsY = true, edgePos = ring.right),
            ).average()
        } else {
            effectiveTextSharp = textSharp
            ringEdgeStep = 0.0
        }

        var glareN = 0
        var glareTot = 0
        run {
            var y = gy0
            while (y < gy1) {
                var x = gx0
                while (x < gx1) {
                    glareTot++
                    if (sample(x, y) > 235) glareN++
                    x += step
                }
                y += step
            }
        }
        val glareRatio = if (glareTot == 0) 0.0 else glareN.toDouble() / glareTot

        val whiteSamples = ArrayList<Double>(4)
        var wTopMean = 0.0
        var wBotMean = 0.0
        var wLeftMean = 0.0
        var wRightMean = 0.0
        var wTopIn = false
        var wBotIn = false
        var wLeftIn = false
        var wRightIn = false
        var whiteClipN = 0
        var whiteClipTot = 0
        if (ring.ok && measuredPpm > 0.0) {
            val insetW = (1.0 * measuredPpm).toInt().coerceAtLeast(1)
            val whiteDepth = (CameraOptics.RING_WHITE_MM * measuredPpm).toInt().coerceAtLeast(insetW + 2)
            val xPad = (ring.spanX * 0.15).toInt()
            val yPad = (ring.spanY * 0.15).toInt()
            val x0 = (ring.left + xPad).toInt()
            val x1 = (ring.right - xPad).toInt()
            val y0 = (ring.top + yPad).toInt()
            val y1 = (ring.bottom - yPad).toInt()
            // Siyah iç kenar → merkeze doğru 6 mm saf beyaz referans halka (yama/cetvele değmez).
            val ty0 = (ring.top + insetW).toInt(); val ty1 = (ring.top + whiteDepth - insetW).toInt()
            val by0 = (ring.bottom - (whiteDepth - insetW)).toInt(); val by1 = (ring.bottom - insetW).toInt()
            val lx0 = (ring.left + insetW).toInt(); val lx1 = (ring.left + whiteDepth - insetW).toInt()
            val rx0 = (ring.right - (whiteDepth - insetW)).toInt(); val rx1 = (ring.right - insetW).toInt()
            val wt = rectMean(x0, ty0, x1, ty1)
            val wb = rectMean(x0, by0, x1, by1)
            val wl = rectMean(lx0, y0, lx1, y1)
            val wr = rectMean(rx0, y0, rx1, y1)
            wTopMean = wt.first; wTopIn = wt.second
            wBotMean = wb.first; wBotIn = wb.second
            wLeftMean = wl.first; wLeftIn = wl.second
            wRightMean = wr.first; wRightIn = wr.second
            for ((mean, inside) in listOf(wt, wb, wl, wr)) {
                if (inside && mean > 0.0) whiteSamples.add(mean)
            }
            fun countClip(ax: Int, ay: Int, bx: Int, by: Int) {
                var yy = minOf(ay, by).coerceAtLeast(0)
                val yyEnd = maxOf(ay, by).coerceAtMost(h)
                val xxStart = minOf(ax, bx).coerceAtLeast(0)
                val xxEnd = maxOf(ax, bx).coerceAtMost(w)
                while (yy < yyEnd) {
                    var xx = xxStart
                    while (xx < xxEnd) {
                        whiteClipTot++
                        if (sample(xx, yy) >= 250) whiteClipN++
                        xx += 2
                    }
                    yy += 2
                }
            }
            if (wTopIn) countClip(x0, ty0, x1, ty1)
            if (wBotIn) countClip(x0, by0, x1, by1)
            if (wLeftIn) countClip(lx0, y0, lx1, y1)
            if (wRightIn) countClip(rx0, y0, rx1, y1)
        }
        val whiteRingMean = if (whiteSamples.isEmpty()) pureMean else whiteSamples.average()
        val whiteRingClipRatio = if (whiteClipTot == 0) 0.0 else whiteClipN.toDouble() / whiteClipTot
        val bottomLit = ringAligned && ((pureMean - whiteRingMean > 30.0) || glareRatio > 0.025)
        // 4 taraflı (hem sol-sağ hem üst-alt) beyaz halka dengesizliği — ekran yönünden bağımsız gölge yakalar.
        val imbY = if (wTopIn && wBotIn) {
            abs(wTopMean - wBotMean) / max(wTopMean, wBotMean).coerceAtLeast(1.0)
        } else 0.0
        val imbX = if (wLeftIn && wRightIn) {
            abs(wLeftMean - wRightMean) / max(wLeftMean, wRightMean).coerceAtLeast(1.0)
        } else 0.0
        val paperImbalance = max(imbY, imbX)
        val shadowBlocked = ringAligned && (wTopIn && wBotIn || wLeftIn && wRightIn) && paperImbalance > 0.18

        // Normalize halka koordinatlarını JPEG (ekran) oryantasyonuna CW döndürerek kaydet.
        val bL = if (ring.ok) (ring.left / w).coerceIn(0.0, 1.0) else -1.0
        val bR = if (ring.ok) (ring.right / w).coerceIn(0.0, 1.0) else -1.0
        val bT = if (ring.ok) (ring.top / h).coerceIn(0.0, 1.0) else -1.0
        val bB = if (ring.ok) (ring.bottom / h).coerceIn(0.0, 1.0) else -1.0
        val (ringLeftN, ringRightN, ringTopN, ringBottomN) = if (!ring.ok) {
            doubleArrayOf(-1.0, -1.0, -1.0, -1.0)
        } else when (rotTurns) {
            1 -> doubleArrayOf(1.0 - bB, 1.0 - bT, bL, bR)
            2 -> doubleArrayOf(1.0 - bR, 1.0 - bL, 1.0 - bB, 1.0 - bT)
            3 -> doubleArrayOf(bT, bB, 1.0 - bR, 1.0 - bL)
            else -> doubleArrayOf(bL, bR, bT, bB)
        }

        val effectiveLightRef = if (ring.ok && whiteSamples.isNotEmpty()) whiteRingMean else lightRef
        emaStd = ema(emaStd, pureStd)
        emaSharp = ema(emaSharp, pureFabricSharp)
        emaTextSharp = ema(emaTextSharp, effectiveTextSharp)
        emaLight = ema(emaLight, effectiveLightRef)
        emaSkew = ema(emaSkew, rawSkew)
        emaBorder = ema(emaBorder, borderScore)
        emaReady = true

        val geometryMode = when {
            ringAligned -> "v2_hole_measured"
            ringFound -> "v2_ring_off"
            else -> "no_hole"
        }

        val skewLimit = shortPx * 0.08

        fun guide(
            msg: String,
            qualityOk: Boolean,
            key: CoachKey,
            arrow: String,
        ): FrameGuide {
            if (qualityOk) consecutiveOk++ else consecutiveOk = 0
            val fullyOk = qualityOk && consecutiveOk >= needConsecutiveOk
            return FrameGuide(
                message = msg,
                ok = fullyOk,
                luma = pureMean,
                edgeLuma = edgeMean,
                centerStd = emaStd,
                sharpness = emaSharp,
                skewPx = emaSkew,
                coachKey = when {
                    !qualityOk -> key
                    else -> CoachKey.OK_HOLD
                },
                arrow = arrow,
                geometryMode = geometryMode,
                paperEdgesVisible = paperEdges,
                colorRingScore = colorRingScore,
                windowFraction = windowFraction,
                holeSidePx = if (holeMeas > 0) holeMeas.toInt() else holeSidePx,
                pxPerMm = if (measuredPpm > 0) measuredPpm else pxPerMm,
                rawSharpness = pureFabricSharp,
                rawSkewPx = rawSkew,
                borderScore = borderScore,
                consecutiveOk = consecutiveOk,
                qualityOk = qualityOk,
                analysisShortPx = shortPx,
                cornersSeated = cornersSeated,
                textSharpness = effectiveTextSharp,
                lightEma = emaLight,
                skewDir = skewDirLabel,
                blackRingScore = blackRingScore,
                greenRingScore = greenRingScore,
                fillRatio = if (scale > 0) scale else fillRatio,
                ringLeft = ringLeftN,
                ringRight = ringRightN,
                ringTop = ringTopN,
                ringBottom = ringBottomN,
                glareRatio = glareRatio,
                whiteRingLuma = if (ring.ok && whiteSamples.isNotEmpty()) whiteRingMean else -1.0,
                whiteRingClipRatio = whiteRingClipRatio,
                ringEdgeStep = ringEdgeStep,
            )
        }

        // 640x480 önizlemede (1.8 px/mm) ince koyu/siyah süprem ilmekleri (~0.35mm) Nyquist sınırının
        // altında kaldığı için saf kumaş std/grad ~0.4..1.2'ye inebilir; halka kilitliyken optik odak
        // aynı düzlemdeki kalibrasyon halkası (ringSharpOk + ringEdgeStep) ile kesin garanti edilir.
        val effMinStd = if (ringAligned) {
            if (pureMean < 75.0) 0.35 else 1.8
        } else {
            minStd
        }
        val effMinFabSharp = if (ringAligned) {
            if (pureMean < 75.0) 0.45 else 1.8
        } else {
            minFabricSharpRaw
        }
        val fabricSharpOk = pureFabricSharp >= effMinFabSharp && emaSharp >= effMinFabSharp * 0.85
        val ringSharpOk = effectiveTextSharp >= minTextSharpRaw &&
            emaTextSharp >= minTextSharpEma &&
            (!ring.ok || ringEdgeStep >= 32.0)

        fun overflowGuide(bufDir: CoachKey): FrameGuide = when (moveToScreen(bufDir)) {
            CoachKey.MOVE_LEFT -> guide("YAMALAR SOLDA — sola kay", false, CoachKey.MOVE_LEFT, "←")
            CoachKey.MOVE_RIGHT -> guide("YAMALAR SAGDA — saga kay", false, CoachKey.MOVE_RIGHT, "→")
            CoachKey.MOVE_UP -> guide("YAMALAR USTTE — yukari kay", false, CoachKey.MOVE_UP, "↑")
            else -> guide("YAMALAR ALTTA — asagi kay", false, CoachKey.MOVE_DOWN, "↓")
        }

        // ÖNCELİK SIRASI: 1) Zifiri karanlık / Halka yok -> 2) Mesafe & Kadraj (YAKLAS/UZAKLAS/KAYDIR/EGIK)
        // -> 3) Hizalandıktan sonra Işık / Gölge / Parlama / Doku / Netlik.
        val result = when {
            !ring.ok && emaLight < 45 ->
                guide("ISIK YETERSIZ", false, CoachKey.DARK, "!")

            !ring.ok && emaLight > 242 && pureStd < 5 ->
                guide("ISIK FAZLA", false, CoachKey.BRIGHT, "!")

            !ringFound ->
                guide("SIYAH HALKA — 4 kenarda beyaz serit gorunmeli", false, CoachKey.NO_PAPER, "!")

            // 18 yama kadraj dışı — çekim kesin engel
            (overflowLeft && overflowRight) || (overflowTop && overflowBot) ->
                guide("UZAKLAS — yamalar kadraj disinda", false, CoachKey.FAR, "↙")

            overflowLeft && (!overflowRight || abs(cardLeft - marginX) >= abs(cardRight - (w - marginX))) ->
                overflowGuide(CoachKey.MOVE_LEFT)

            overflowRight ->
                overflowGuide(CoachKey.MOVE_RIGHT)

            overflowTop && (!overflowBot || abs(cardTop - marginY) >= abs(cardBot - (h - marginY))) ->
                overflowGuide(CoachKey.MOVE_UP)

            overflowBot ->
                overflowGuide(CoachKey.MOVE_DOWN)

            scale in 0.01..0.82 ->
                guide("YAKLAS — olculen delik sari kareden kucuk", false, CoachKey.CLOSE, "↗")

            scale > 1.15 ->
                guide("UZAKLAS — olculen delik sari kareden buyuk", false, CoachKey.FAR, "↙")

            abs(screenDx) > alignTol && abs(screenDx) >= abs(screenDy) && screenDx > 0 ->
                guide("DELIK SAGDA — saga kay", false, CoachKey.MOVE_RIGHT, "→")

            abs(screenDx) > alignTol && abs(screenDx) >= abs(screenDy) ->
                guide("DELIK SOLDA — sola kay", false, CoachKey.MOVE_LEFT, "←")

            abs(screenDy) > alignTol && screenDy > 0 ->
                guide("DELIK ASAGIDA — asagi kay", false, CoachKey.MOVE_DOWN, "↓")

            abs(screenDy) > alignTol ->
                guide("DELIK YUKARIDA — yukari kay", false, CoachKey.MOVE_UP, "↑")

            rawSkew > skewLimit && emaSkew > skewLimit * 0.85 ->
                guide(skewMsg, false, skewKey, skewArrow)

            whiteSamples.isNotEmpty() && whiteRingMean < 120.0 ->
                guide("ISIK YETERSIZ — ust aydinlatmayi artirin", false, CoachKey.DARK, "!")

            whiteRingMean > 246.0 || whiteRingClipRatio > 0.28 ->
                guide("BEYAZ KART PATLIYOR — poz kisiliyor / flasi azaltin", false, CoachKey.BRIGHT, "!")

            bottomLit ->
                guide("ALTTAN ISIK VAR — ustten aydinlatma kullanin", false, CoachKey.BRIGHT, "!")

            shadowBlocked ->
                guide("GOLGE VAR — elinizi ve golgeyi cekin", false, CoachKey.UNEVEN, "!")

            pureStd < effMinStd && emaStd < effMinStd * 0.85 ->
                guide("DOKU ZAYIF — yaklasin / netlestirin", false, CoachKey.TEXTURE, "↗")

            !ringSharpOk ->
                guide("KENAR NETLESIN — sabit tutun", false, CoachKey.BLUR, "◎")

            !fabricSharpOk ->
                guide("SARI DELIK NETLESIN — sabit tutun", false, CoachKey.BLUR, "◎")

            else -> {
                val hold = consecutiveOk + 1
                if (hold < needConsecutiveOk) {
                    guide("OK delik net sabit $hold/$needConsecutiveOk", true, CoachKey.OK_HOLD, "●")
                } else {
                    guide("OK delik net", true, CoachKey.OK_HOLD, "●")
                }
            }
        }

        onGuide(result)
        image.close()
    }
}
