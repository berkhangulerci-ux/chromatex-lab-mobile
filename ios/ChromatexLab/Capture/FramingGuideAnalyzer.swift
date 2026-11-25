import Foundation

struct FrameGuide {
    var message: String
    var ok: Bool
    var luma: Double
    var edgeLuma: Double = 0
    var centerStd: Double = 0
    var sharpness: Double = 0
    var skewPx: Double = 0
    var coachKey: CoachKey = .none
    var arrow: String = ""
    var geometryMode: String = "window_priority"
    var paperEdgesVisible: Int = 0
    var colorRingScore: Double = 0
    var windowFraction: Double = 0.80
    var holeSidePx: Int = 0
    var pxPerMm: Double = 0
    var rawSharpness: Double = 0
    var rawSkewPx: Double = 0
    var borderScore: Double = 0
    var consecutiveOk: Int = 0
    var qualityOk: Bool = false
    var analysisShortPx: Int = 0
    var cornersSeated: Bool = false
    var textSharpness: Double = 0
    var lightEma: Double = 0
    var skewDir: String = ""
    var blackRingScore: Double = 0
    var greenRingScore: Double = 0
    var fillRatio: Double = 0
    var ringLeft: Double = -1
    var ringRight: Double = -1
    var ringTop: Double = -1
    var ringBottom: Double = -1
    var glareRatio: Double = 0
}

/// Port of Android FramingGuideAnalyzer — luma plane analysis for v2 target rings.
final class FramingGuideAnalyzer {
    var windowFraction: Double = Catalog.holeFillFrac

    private var emaStd = 0.0
    private var emaSharp = 0.0
    private var emaTextSharp = 0.0
    private var emaLight = 0.0
    private var emaSkew = 0.0
    private var emaBorder = 0.0
    private var emaPaper = 0.0
    private var emaPitch = 0.0
    private var emaRoll = 0.0
    private var emaReady = false
    private let alpha = 0.35
    private var consecutiveOk = 0
    private var stickySkewKey: CoachKey = .skew

    private var minFabricSharpRaw: Double { Catalog.Framing.minFabricSharp }
    private var minFabricSharpEma: Double { Catalog.Framing.minFabricSharp }
    private var minTextSharpRaw: Double { Catalog.Framing.minTextSharp }
    private var minTextSharpEma: Double { Catalog.Framing.minTextSharp }
    private let minStd = 4.0
    private var needConsecutiveOk: Int { Catalog.Framing.needConsecutiveOk }

    private struct SideLock { let edge: Double; let low: Double; let high: Double }
    private struct RingMeasure {
        let ok: Bool
        let left, right, top, bottom, spanX, spanY: Double
        let skewLeft, skewRight, skewTop, skewBot, pitch, roll: Double
        static let none = RingMeasure(ok: false, left: 0, right: 0, top: 0, bottom: 0, spanX: 0, spanY: 0, skewLeft: 0, skewRight: 0, skewTop: 0, skewBot: 0, pitch: 0, roll: 0)
    }

    private func ema(_ prev: Double, _ x: Double) -> Double {
        emaReady ? alpha * x + (1 - alpha) * prev : x
    }

    /// Analyze grayscale luma bytes (row-major, width*height).
    func analyze(luma: [UInt8], width w: Int, height h: Int, rotationDegrees: Int = 0) -> FrameGuide {
        precondition(luma.count >= w * h)
        let shortPxEarly = min(w, h)
        let step = max(3, min(8, shortPxEarly / 96))
        let windowFraction = min(max(self.windowFraction, 0.28), 0.56)

        func sample(_ x: Int, _ y: Int) -> Int {
            let xx = max(0, min(w - 1, x))
            let yy = max(0, min(h - 1, y))
            return Int(luma[yy * w + xx])
        }

        func regionMean(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> (Double, Double) {
            var sum = 0.0, sumSq = 0.0, n = 0
            var y = max(0, y0)
            let yy1 = min(h, y1)
            let xx0 = max(0, x0)
            let xx1 = min(w, x1)
            while y < yy1 {
                var x = xx0
                while x < xx1 {
                    let v = Double(sample(x, y))
                    sum += v; sumSq += v * v; n += 1
                    x += step
                }
                y += step
            }
            if n == 0 { return (0, 0) }
            let mean = sum / Double(n)
            return (mean, (sumSq / Double(n)) - mean * mean)
        }

        func regionSharpness(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> Double {
            var sum = 0.0, n = 0
            let off = step
            var y = max(off, y0 + off)
            let yy1 = min(h - off, y1 - off)
            let xx0 = max(off, x0 + off)
            let xx1 = min(w - off, x1 - off)
            while y < yy1 {
                var x = xx0
                while x < xx1 {
                    sum += Double(abs(sample(x + off, y) - sample(x - off, y)) + abs(sample(x, y + off) - sample(x, y - off)))
                    n += 1
                    x += step
                }
                y += step
            }
            return n == 0 ? 0 : sum / Double(n)
        }

        func holeEdgeY(_ x: Int, _ yFrom: Int, _ yTo: Int, outwardDown: Bool) -> Int {
            var bestY = -1, bestScore = 0
            var y = max(2, yFrom)
            let lim = min(h - 3, yTo)
            while y < lim {
                let g = abs(sample(x, y + 2) - sample(x, y - 2))
                let outer = outwardDown ? sample(x, y + 3) : sample(x, y - 3)
                let inner = outwardDown ? sample(x, y - 3) : sample(x, y + 3)
                let darkBias = outer < inner - 12 ? 28 : 0
                let score = g + darkBias
                if score > bestScore { bestScore = score; bestY = y }
                y += 2
            }
            return bestScore >= 12 ? bestY : -1
        }

        func holeEdgeX(_ y: Int, _ xFrom: Int, _ xTo: Int, outwardRight: Bool) -> Int {
            var bestX = -1, bestScore = 0
            var x = max(2, xFrom)
            let lim = min(w - 3, xTo)
            while x < lim {
                let g = abs(sample(x + 2, y) - sample(x - 2, y))
                let outer = outwardRight ? sample(x + 3, y) : sample(x - 3, y)
                let inner = outwardRight ? sample(x - 3, y) : sample(x + 3, y)
                let darkBias = outer < inner - 12 ? 28 : 0
                let score = g + darkBias
                if score > bestScore { bestScore = score; bestX = x }
                x += 2
            }
            return bestScore >= 12 ? bestX : -1
        }

        let shortPx = min(w, h)
        let holeSide = max(48, Int(Double(shortPx) * windowFraction))
        let holeSidePx = holeSide
        let pxPerMm = Double(holeSide) / Catalog.holeMm
        let mx0 = max(0, (w - holeSide) / 2)
        let my0 = max(0, (h - holeSide) / 2)
        let mx1 = min(w, mx0 + holeSide)
        let my1 = min(h, my0 + holeSide)
        let inset = max(4, Int(Double(holeSide) * 0.12))
        let mm = Double(holeSide) / Catalog.holeMm
        let greenPx = max(2, Int(5.0 * mm))
        let whitePx = max(3, Int(6.0 * mm))
        let blackPx = max(3, Int(8.0 * mm))
        let search = max(20, greenPx + whitePx + blackPx + Int(Double(holeSide) * 0.06))
        let band = max(10, greenPx + whitePx + blackPx)

        let (cMean, cVar) = regionMean(mx0 + inset, my0 + inset, mx1 - inset, my1 - inset)
        let cStd = sqrt(max(0, cVar))
        let fabricSharp = regionSharpness(mx0 + inset, my0 + inset, mx1 - inset, my1 - inset)

        var brightN = 0, brightTot = 0, glareN = 0, glareTot = 0
        var y = my0 + inset
        while y < my1 - inset {
            var x = mx0 + inset
            while x < mx1 - inset {
                brightTot += 1
                if sample(x, y) > 190 { brightN += 1 }
                x += step
            }
            y += step
        }
        y = my0
        while y < my1 {
            var x = mx0
            while x < mx1 {
                glareTot += 1
                if sample(x, y) > 235 { glareN += 1 }
                x += step
            }
            y += step
        }
        let glareRatio = glareTot == 0 ? 0.0 : Double(glareN) / Double(glareTot)

        let g1 = greenPx
        let (gTop, gTopVar) = regionMean(mx0, max(0, my0 - g1), mx1, max(0, my0))
        let (gBot, gBotVar) = regionMean(mx0, min(h, my1), mx1, min(h, my1 + g1))
        let (gLeft, gLeftVar) = regionMean(max(0, mx0 - g1), my0, max(0, mx0), my1)
        let (gRight, gRightVar) = regionMean(min(w, mx1), my0, min(w, mx1 + g1), my1)
        let greenMean = (gTop + gBot + gLeft + gRight) / 4.0

        let bStart = greenPx + whitePx
        let bEnd = bStart + blackPx
        let bTop = regionMean(mx0, max(0, my0 - bEnd), mx1, max(0, my0 - bStart)).0
        let bBot = regionMean(mx0, min(h, my1 + bStart), mx1, min(h, my1 + bEnd)).0
        let bLeft = regionMean(max(0, mx0 - bEnd), my0, max(0, mx0 - bStart), my1).0
        let bRight = regionMean(min(w, mx1 + bStart), my0, min(w, mx1 + bEnd), my1).0
        let blackMean = (bTop + bBot + bLeft + bRight) / 4.0
        let blackRingScore = (min(120.0, max(0.0, 120.0 - blackMean)) / 120.0) * 40.0
            + (min(100.0, max(0.0, cMean - blackMean)) / 100.0) * 60.0
        let greenRingScore = sqrt(max(0, (gTopVar + gBotVar + gLeftVar + gRightVar) / 4.0))
            + (min(80.0, max(0.0, greenMean - blackMean)) / 80.0) * 20.0

        let textTop = regionSharpness(mx0, max(0, my0 - bEnd), mx1, my0)
        let textBot = regionSharpness(mx0, my1, mx1, min(h, my1 + bEnd))
        let textLeft = regionSharpness(max(0, mx0 - bEnd), my0, mx0, my1)
        let textRight = regionSharpness(mx1, my0, min(w, mx1 + bEnd), my1)
        let textSharp = (textTop + textBot + textLeft + textRight) / 4.0
        let ringMean = blackMean * 0.55 + greenMean * 0.45
        let lightRef = cMean * 0.55 + ringMean * 0.45

        let edgeBand = max(6, Int(Double(shortPx) * 0.08))
        let (tMean, _) = regionMean(mx0, 0, mx1, edgeBand)
        let (boMean, _) = regionMean(mx0, h - edgeBand, mx1, h)
        let (lMean, _) = regionMean(0, my0, edgeBand, my1)
        let (rMean, _) = regionMean(w - edgeBand, my0, w, my1)
        let edgeVals = [tMean, boMean, lMean, rMean]
        let paperEdges = edgeVals.filter { $0 > 140 }.count
        let edgeMean = paperEdges >= 2 ? edgeVals.filter { $0 > 140 }.reduce(0, +) / Double(paperEdges) : ringMean

        // measureBlackRing
        func measureBlackRing() -> RingMeasure {
            let cx = w / 2, cy = h / 2
            func sideFromCenter(alongIsY: Bool, dir: Int) -> SideLock? {
                var points: [(Int, Int)] = []
                let spanHalf = Int(Double(holeSide) * 0.35)
                let alongStart = (alongIsY ? cy : cx) - spanHalf
                let alongEnd = (alongIsY ? cy : cx) + spanHalf
                let startOffset = Int(Double(holeSide) * 0.38)
                let maxOffset = ((alongIsY ? w : h) / 2) - 6
                var along = alongStart
                while along <= alongEnd {
                    func atOffset(_ off: Int) -> Int {
                        let pos = (alongIsY ? cx : cy) + (off * dir)
                        let x = alongIsY ? pos : along
                        let y = alongIsY ? along : pos
                        return sample(x, y)
                    }
                    var off = startOffset
                    while off < maxOffset - 6 {
                        let innerWhite = (atOffset(off - 3) + atOffset(off - 2) + atOffset(off - 1)) / 3
                        let outerBlack = (atOffset(off + 1) + atOffset(off + 2) + atOffset(off + 3)) / 3
                        if innerWhite >= outerBlack + 28 && outerBlack < 115 {
                            var solid = true
                            for k in 1...4 {
                                if atOffset(off + k) > outerBlack + 18 { solid = false; break }
                            }
                            if solid {
                                let edgeImg = (alongIsY ? cx : cy) + (off * dir)
                                points.append((along, edgeImg))
                                break
                            }
                        }
                        off += 2
                    }
                    along += 4
                }
                if points.count < 6 { return nil }
                points.sort { $0.1 < $1.1 }
                var bestCount = 0, bestA = 0, bestB = 0, k = 0
                let tol = 25
                for a in points.indices {
                    if k < a { k = a }
                    while k + 1 < points.count && points[k + 1].1 - points[a].1 <= tol { k += 1 }
                    let cnt = k - a + 1
                    if cnt > bestCount { bestCount = cnt; bestA = a; bestB = k }
                }
                if bestCount < 6 { return nil }
                let cluster = Array(points[bestA...bestB])
                let edges = cluster.map { $0.1 }.sorted()
                let edgeMed = Double(edges[edges.count / 2])
                let alongSorted = cluster.sorted { $0.0 < $1.0 }
                let third = max(1, alongSorted.count / 3)
                let lowPts = alongSorted.prefix(third).map { $0.1 }.sorted()
                let highPts = alongSorted.suffix(third).map { $0.1 }.sorted()
                return SideLock(edge: edgeMed, low: Double(lowPts[lowPts.count / 2]), high: Double(highPts[highPts.count / 2]))
            }
            guard let left = sideFromCenter(alongIsY: true, dir: -1),
                  let right = sideFromCenter(alongIsY: true, dir: 1),
                  let top = sideFromCenter(alongIsY: false, dir: -1),
                  let bottom = sideFromCenter(alongIsY: false, dir: 1) else {
                return .none
            }
            let spanX = right.edge - left.edge
            let spanY = bottom.edge - top.edge
            if spanX < 36 || spanY < 36 { return .none }
            let ratio = spanX / spanY
            if ratio < 0.75 || ratio > 1.33 { return .none }
            return RingMeasure(
                ok: true, left: left.edge, right: right.edge, top: top.edge, bottom: bottom.edge,
                spanX: spanX, spanY: spanY,
                skewLeft: abs(left.high - left.low), skewRight: abs(right.high - right.low),
                skewTop: abs(top.high - top.low), skewBot: abs(bottom.high - bottom.low),
                pitch: (right.low - left.low) - (right.high - left.high),
                roll: (bottom.low - top.low) - (bottom.high - top.high)
            )
        }

        let ring = measureBlackRing()
        let rawSkew = max(max(ring.skewTop, ring.skewBot), max(ring.skewLeft, ring.skewRight))
        emaPitch = ema(emaPitch, ring.ok ? ring.pitch : 0)
        emaRoll = ema(emaRoll, ring.ok ? ring.roll : 0)

        func skewTriple(_ key: CoachKey) -> (String, CoachKey, String) {
            switch key {
            case .skewUp: return ("EGIK UST YAKIN — ustu kaldir", key, "↑")
            case .skewDown: return ("EGIK ALT YAKIN — ustu yaklastir", key, "↓")
            case .skewLeft: return ("EGIK SOL YAKIN — saga egin", key, "←")
            case .skewRight: return ("EGIK SAG YAKIN — sola egin", key, "→")
            default: return ("YAMUK/EGIK — duz bakin", .skew, "↺")
            }
        }
        func proposeSkewKeyBuffer() -> CoachKey {
            let ap = abs(emaPitch), ar = abs(emaRoll), minDir = 8.0
            if ap >= ar * 1.25 && ap >= minDir { return emaPitch > 0 ? .skewUp : .skewDown }
            if ar > ap * 1.25 && ar >= minDir { return emaRoll > 0 ? .skewLeft : .skewRight }
            return .skew
        }
        func skewToScreen(_ key: CoachKey) -> CoachKey {
            if key == .skew { return key }
            let cw: [CoachKey] = [.skewUp, .skewRight, .skewDown, .skewLeft]
            guard let idx = cw.firstIndex(of: key) else { return key }
            let turns = ((rotationDegrees / 90) % 4 + 4) % 4
            return cw[(idx + turns) % 4]
        }
        let proposed = skewToScreen(proposeSkewKeyBuffer())
        if proposed == .skew {
            // keep sticky
        } else if stickySkewKey == .skew {
            stickySkewKey = proposed
        } else if proposed == stickySkewKey {
            stickySkewKey = proposed
        } else if (stickySkewKey == .skewUp && proposed == .skewDown) || (stickySkewKey == .skewDown && proposed == .skewUp) {
            if abs(emaPitch) >= 12 { stickySkewKey = proposed }
        } else if (stickySkewKey == .skewLeft && proposed == .skewRight) || (stickySkewKey == .skewRight && proposed == .skewLeft) {
            if abs(emaRoll) >= 12 { stickySkewKey = proposed }
        } else {
            stickySkewKey = proposed
        }
        let (skewMsg, skewKey, skewArrow) = skewTriple(stickySkewKey)
        let skewDirLabel: String = {
            switch skewKey {
            case .skewUp: return "UST"
            case .skewDown: return "ALT"
            case .skewLeft: return "SOL"
            case .skewRight: return "SAG"
            default: return rawSkew > 0 ? "?" : ""
            }
        }()

        var borderSum = 0.0, borderN = 0
        func addBorderY(_ x: Int, _ yNom: Int, outwardDown: Bool) {
            let yy = holeEdgeY(x, yNom - search, yNom + search, outwardDown: outwardDown)
            if yy >= 0 { borderSum += Double(abs(sample(x, yy + 2) - sample(x, yy - 2))); borderN += 1 }
        }
        func addBorderX(_ y: Int, _ xNom: Int, outwardRight: Bool) {
            let xx = holeEdgeX(y, xNom - search, xNom + search, outwardRight: outwardRight)
            if xx >= 0 { borderSum += Double(abs(sample(xx + 2, y) - sample(xx - 2, y))); borderN += 1 }
        }
        addBorderY(mx0 + Int(Double(holeSide) * 0.35), my0, outwardDown: false)
        addBorderY(mx0 + Int(Double(holeSide) * 0.65), my0, outwardDown: false)
        addBorderY(mx0 + Int(Double(holeSide) * 0.35), my1, outwardDown: true)
        addBorderY(mx0 + Int(Double(holeSide) * 0.65), my1, outwardDown: true)
        addBorderX(my0 + Int(Double(holeSide) * 0.35), mx0, outwardRight: false)
        addBorderX(my0 + Int(Double(holeSide) * 0.65), mx0, outwardRight: false)
        addBorderX(my0 + Int(Double(holeSide) * 0.35), mx1, outwardRight: true)
        addBorderX(my0 + Int(Double(holeSide) * 0.65), mx1, outwardRight: true)
        let borderScore = borderN == 0 ? 0.0 : borderSum / Double(borderN)

        let ycx = Double(mx0 + mx1) / 2.0
        let ycy = Double(my0 + my1) / 2.0
        let innerSpanMm = 122.0
        let holeMeasX = ring.ok ? ring.spanX * (Catalog.holeMm / innerSpanMm) : -1.0
        let holeMeasY = ring.ok ? ring.spanY * (Catalog.holeMm / innerSpanMm) : -1.0
        let holeMeas = ring.ok ? (holeMeasX + holeMeasY) / 2.0 : -1.0
        let measCx = ring.ok ? (ring.left + ring.right) / 2.0 : ycx
        let measCy = ring.ok ? (ring.top + ring.bottom) / 2.0 : ycy
        let dx = measCx - ycx
        let dy = measCy - ycy
        let scale = holeMeas > 0 ? holeMeas / Double(holeSide) : -1.0
        let alignTol = Double(holeSide) * Catalog.Framing.alignTolFrac
        let ringFound = ring.ok
        let scaleMin = Catalog.Framing.scaleMin
        let scaleMax = Catalog.Framing.scaleMax

        // 18-yama Bounding Box Guard: siyah ic kenar (122mm) → tam kart (220mm)
        let fullTarget = 220.0
        let fullTargetW = ring.ok ? ring.spanX * (fullTarget / innerSpanMm) : -1.0
        let fullTargetH = ring.ok ? ring.spanY * (fullTarget / innerSpanMm) : -1.0
        let cardLeft = measCx - fullTargetW / 2.0
        let cardRight = measCx + fullTargetW / 2.0
        let cardTop = measCy - fullTargetH / 2.0
        let cardBot = measCy + fullTargetH / 2.0
        let marginX = 0.03 * Double(w)
        let marginY = 0.03 * Double(h)
        let overflowLeft = ring.ok && cardLeft < marginX
        let overflowRight = ring.ok && cardRight > Double(w) - marginX
        let overflowTop = ring.ok && cardTop < marginY
        let overflowBot = ring.ok && cardBot > Double(h) - marginY
        let patchesInFrame = ring.ok && !overflowLeft && !overflowRight && !overflowTop && !overflowBot
        let ringAligned = ringFound && scale >= scaleMin && scale <= scaleMax
            && abs(dx) <= alignTol && abs(dy) <= alignTol && patchesInFrame
        let cornersSeated = ringAligned
        let measuredPpm = ring.ok ? ((ring.spanX + ring.spanY) / 2.0) / innerSpanMm : 0.0

        func rectMean(_ ax: Int, _ ay: Int, _ bx: Int, _ by: Int) -> (Double, Bool) {
            let x0 = min(ax, bx), x1 = max(ax, bx), y0 = min(ay, by), y1 = max(ay, by)
            let fullyInside = x0 >= 0 && y0 >= 0 && x1 <= w && y1 <= h && (x1 - x0) >= 4 && (y1 - y0) >= 2
            return (regionMean(x0, y0, x1, y1).0, fullyInside)
        }

        var topPaperMean = 0.0, botPaperMean = 0.0
        var topPaperInside = false, botPaperInside = false
        if ring.ok && measuredPpm > 0 {
            let paper0 = max(2, Int((8.0 + 2.0) * measuredPpm))
            let paper1 = max(paper0 + 2, Int((8.0 + 10.0) * measuredPpm))
            let xPadP = Int(ring.spanX * 0.22)
            let px0 = Int(ring.left) + xPadP
            let px1 = Int(ring.right) - xPadP
            let topPaper = rectMean(px0, Int(ring.top) - paper1, px1, Int(ring.top) - paper0)
            let botPaper = rectMean(px0, Int(ring.bottom) + paper0, px1, Int(ring.bottom) + paper1)
            topPaperMean = topPaper.0; botPaperMean = botPaper.0
            topPaperInside = topPaper.1; botPaperInside = botPaper.1
        }
        let paperImbalance = abs(topPaperMean - botPaperMean) / max(max(topPaperMean, botPaperMean), 1.0)
        emaPaper = ema(emaPaper, paperImbalance)
        // Hole brighter than the white ring is normal with the torch. Bottom-light
        // only when the lower paper is actually blown out.
        let bottomLit = glareRatio > 0.02
            || (topPaperInside && botPaperInside && botPaperMean > topPaperMean + 50 && botPaperMean > 220)
        let seated = scale >= scaleMin && scale <= scaleMax
            && rawSkew <= Double(shortPx) * Catalog.Framing.skewFrac
        let shadowLimit = seated ? 0.50 : Catalog.Framing.paperImbalanceMax
        let shadowBlocked = ring.ok && topPaperInside && botPaperInside
            && paperImbalance > shadowLimit
            && emaPaper > shadowLimit
        let ringLeftN = ring.ok ? min(1, max(0, ring.left / Double(w))) : -1.0
        let ringRightN = ring.ok ? min(1, max(0, ring.right / Double(w))) : -1.0
        let ringTopN = ring.ok ? min(1, max(0, ring.top / Double(h))) : -1.0
        let ringBottomN = ring.ok ? min(1, max(0, ring.bottom / Double(h))) : -1.0

        emaStd = ema(emaStd, cStd)
        emaSharp = ema(emaSharp, fabricSharp)
        emaTextSharp = ema(emaTextSharp, textSharp)
        emaLight = ema(emaLight, lightRef)
        emaSkew = ema(emaSkew, rawSkew)
        emaBorder = ema(emaBorder, borderScore)
        emaReady = true

        let geometryMode: String = {
            if ringAligned { return "v2_hole_measured" }
            if ringFound { return "v2_ring_off" }
            return "no_hole"
        }()
        let skewLimit = Double(shortPx) * Catalog.Framing.skewFrac

        func guide(_ msg: String, _ qualityOk: Bool, _ key: CoachKey, _ arrow: String) -> FrameGuide {
            if qualityOk { consecutiveOk += 1 } else { consecutiveOk = 0 }
            let fullyOk = qualityOk && consecutiveOk >= needConsecutiveOk
            return FrameGuide(
                message: msg, ok: fullyOk, luma: cMean, edgeLuma: edgeMean, centerStd: emaStd,
                sharpness: emaSharp, skewPx: emaSkew,
                coachKey: qualityOk ? .okHold : key, arrow: arrow, geometryMode: geometryMode,
                paperEdgesVisible: paperEdges, colorRingScore: greenRingScore, windowFraction: windowFraction,
                holeSidePx: holeMeas > 0 ? Int(holeMeas) : holeSidePx,
                pxPerMm: measuredPpm > 0 ? measuredPpm : pxPerMm,
                rawSharpness: fabricSharp, rawSkewPx: rawSkew, borderScore: borderScore,
                consecutiveOk: consecutiveOk, qualityOk: qualityOk, analysisShortPx: shortPx,
                cornersSeated: cornersSeated, textSharpness: textSharp, lightEma: emaLight,
                skewDir: skewDirLabel, blackRingScore: blackRingScore, greenRingScore: greenRingScore,
                fillRatio: scale > 0 ? scale : 0, ringLeft: ringLeftN, ringRight: ringRightN,
                ringTop: ringTopN, ringBottom: ringBottomN, glareRatio: glareRatio
            )
        }

        let fabricSharpOk = fabricSharp >= minFabricSharpRaw && emaSharp >= minFabricSharpEma
        let ringSharpOk = textSharp >= minTextSharpRaw && emaTextSharp >= minTextSharpEma

        if emaLight < 55 { return guide("ISIK YETERSIZ", false, .dark, "!") }
        if bottomLit { return guide("ALTTAN ISIK VAR — ustten aydinlatma kullanin", false, .bright, "!") }
        if emaLight > 242 && cStd < 5 { return guide("ISIK FAZLA", false, .bright, "!") }
        if !ringFound { return guide("SIYAH HALKA — 4 kenarda beyaz serit gorunmeli", false, .noPaper, "!") }
        if shadowBlocked { return guide("GOLGE VAR — elinizi ve golgeyi cekin", false, .uneven, "!") }
        if (overflowLeft && overflowRight) || (overflowTop && overflowBot) {
            return guide("UZAKLAS — yamalar kadraj disinda", false, .far, "↙")
        }
        if overflowLeft && (!overflowRight || abs(cardLeft - marginX) >= abs(cardRight - (Double(w) - marginX))) {
            return guide("YAMALAR SOLDA — sola kay", false, .moveLeft, "←")
        }
        if overflowRight { return guide("YAMALAR SAGDA — saga kay", false, .moveRight, "→") }
        if overflowTop && (!overflowBot || abs(cardTop - marginY) >= abs(cardBot - (Double(h) - marginY))) {
            return guide("YAMALAR USTTE — yukari kay", false, .moveUp, "↑")
        }
        if overflowBot { return guide("YAMALAR ALTTA — asagi kay", false, .moveDown, "↓") }
        if scale > 0.01 && scale < scaleMin { return guide("YAKLAS — olculen delik sari kareden kucuk", false, .close, "↗") }
        if scale > scaleMax { return guide("UZAKLAS — olculen delik sari kareden buyuk", false, .far, "↙") }
        if abs(dx) > alignTol && abs(dx) >= abs(dy) && dx > 0 { return guide("DELIK SAGDA — saga kay", false, .moveRight, "→") }
        if abs(dx) > alignTol && abs(dx) >= abs(dy) { return guide("DELIK SOLDA — sola kay", false, .moveLeft, "←") }
        if abs(dy) > alignTol && dy > 0 { return guide("DELIK ASAGIDA — asagi kay", false, .moveDown, "↓") }
        if abs(dy) > alignTol { return guide("DELIK YUKARIDA — yukari kay", false, .moveUp, "↑") }
        if rawSkew > skewLimit && emaSkew > skewLimit * 0.85 { return guide(skewMsg, false, skewKey, skewArrow) }
        if cStd < minStd && emaStd < minStd * 0.85 { return guide("DOKU ZAYIF — yaklasin", false, .texture, "↗") }
        if !ringSharpOk { return guide("KENAR NETLESIN — sabit tutun", false, .blur, "◎") }
        if !fabricSharpOk { return guide("SARI DELIK NETLESIN — sabit tutun", false, .blur, "◎") }
        let hold = consecutiveOk + 1
        if hold < needConsecutiveOk {
            return guide("OK delik net sabit \(hold)/\(needConsecutiveOk)", true, .okHold, "●")
        }
        return guide("OK delik net", true, .okHold, "●")
    }

    static func isHardQualityOk(_ g: FrameGuide, minRawSharp: Double? = nil) -> Bool {
        hardFailReason(g, minRawSharp: minRawSharp) == nil
    }

    /// Human-readable hard-gate failure for coach (nil = pass).
    static func hardFailReason(_ g: FrameGuide, minRawSharp: Double? = nil) -> String? {
        let minSharp = minRawSharp ?? Catalog.Framing.minFabricSharp
        if !g.cornersSeated { return "DELIK HIZALA — sari kareye oturtun" }
        if g.paperEdgesVisible < Catalog.Framing.minPaperEdges { return "KAGIT KENARI — 4 kenar gorunsun" }
        if g.fillRatio > 0
            && (g.fillRatio < Catalog.Framing.hardFillMin || g.fillRatio > Catalog.Framing.hardFillMax) {
            return g.fillRatio < Catalog.Framing.hardFillMin
                ? "YAKLAS — delik kucuk"
                : "UZAKLAS — delik buyuk"
        }
        if g.textSharpness > 0 && g.textSharpness < Catalog.Framing.minTextSharp {
            return "KENAR NETLESIN — sabit tutun"
        }
        if g.rawSharpness > 0 && g.rawSharpness < minSharp {
            return "DELIK NETLESIN — sabit tutun"
        }
        if g.borderScore > 0 && g.borderScore < Catalog.Framing.minBorderScore {
            return "HALKA NETLESIN — sabit tutun"
        }
        if g.analysisShortPx > 0 {
            let lim = Double(g.analysisShortPx) * Catalog.Framing.hardSkewFrac
            let skew = max(g.rawSkewPx, g.skewPx)
            if skew > lim { return "EGIK — telefonu duz tutun" }
        }
        return nil
    }

    /// Scale of measured fabric hole vs yellow overlay hole (same math as analyze).
    static func holeScale(
        ringSpanX: Double,
        ringSpanY: Double,
        shortPx: Int,
        windowFraction: Double = Catalog.holeFillFrac
    ) -> Double {
        let holeSide = max(48.0, Double(shortPx) * min(max(windowFraction, 0.28), 0.56))
        let holeMeas = ((ringSpanX + ringSpanY) / 2.0) * (Catalog.holeMm / 122.0)
        guard holeSide > 0, holeMeas > 0 else { return -1 }
        return holeMeas / holeSide
    }
}
