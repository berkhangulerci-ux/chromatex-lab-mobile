import Foundation

enum Catalog {
    struct Choice: Identifiable, Hashable {
        let code: String
        let title: String
        var id: String { code }
    }

    static let fabricStateChoices: [Choice] = [
        .init(code: "ham", title: "Ham"),
        .init(code: "boyali", title: "Boyalı"),
        .init(code: "yari_islem", title: "Yarı işlem"),
    ]

    static let labelChoices: [Choice] = [
        .init(code: "normal_kumas", title: "Normal kumaş"),
        .init(code: "hata_var", title: "Hata var"),
        .init(code: "delik", title: "Delik"),
        .init(code: "yag_lekesi", title: "Yağ lekesi"),
        .init(code: "kirik_igne", title: "İğne kırık"),
        .init(code: "kacik_ilmek", title: "Kaçık ilmek"),
        .init(code: "diger", title: "Diğer"),
        .init(code: "belirsiz", title: "Belirsiz"),
    ]

    static let fabricTypeChoices: [Choice] = [
        .init(code: "Suprem", title: "Süprem"),
        .init(code: "Lycra suprem", title: "Likra süprem"),
        .init(code: "Ribana", title: "Ribana"),
        .init(code: "Lycra ribana", title: "Likra ribana"),
        .init(code: "Interlok", title: "Interlok"),
        .init(code: "Pike", title: "Pike"),
        .init(code: "2 iplik", title: "2 iplik"),
        .init(code: "3 iplik / Futter", title: "3 iplik / Futter"),
        .init(code: "Vanize", title: "Vanize"),
        .init(code: "Kaskorse", title: "Kaskorse"),
        .init(code: "Jakar", title: "Jakar"),
        .init(code: "Diger", title: "Diğer"),
    ]

    static let fabricColorChoices: [Choice] = [
        .init(code: "Ekru", title: "Ekru"),
        .init(code: "Beyaz", title: "Beyaz"),
        .init(code: "Kirik beyaz", title: "Kırık beyaz"),
        .init(code: "Siyah", title: "Siyah"),
        .init(code: "Antrasit", title: "Antrasit"),
        .init(code: "Gri", title: "Gri"),
        .init(code: "Lacivert", title: "Lacivert"),
        .init(code: "Kirmizi", title: "Kırmızı"),
        .init(code: "Bordo", title: "Bordo"),
        .init(code: "Yesil", title: "Yeşil"),
        .init(code: "Mavi", title: "Mavi"),
        .init(code: "Bej", title: "Bej"),
        .init(code: "Diger", title: "Diğer"),
    ]

    static let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.2.11"
    static let appVersionCode = Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") ?? 14
    static let defaultBatchId = "Target_v2_Final_2026-11-17"
    /// Fallback before the camera reports FOV. Live value comes from `CameraOptics`.
    static var holeFillFrac: Double { CameraOptics.compositionFraction }
    static let holeMm: Double = 100
    static let clientType = "ios"

    /// Hole must sit on the yellow square. ±6% — a 9% miss is not OK.
    enum Framing {
        static let scaleMin = 0.94
        static let scaleMax = 1.06
        static let hardFillMin = 0.92
        static let hardFillMax = 1.10
        static let alignTolFrac = 0.08
        /// Soft skew coach (~26px @480).
        static let skewFrac = 0.055
        /// Hard skew (~21.6px @480) — rejects hub skew≈23, allows ~3–15 good shots.
        static let hardSkewFrac = 0.045
        /// Top/bottom paper ratio. Torch falloff sits ~0.20–0.35; only a real hand shadow blocks.
        static let paperImbalanceMax = 0.42
        static let minPaperEdges = 2
        static let minFabricSharp = 4.5
        static let minTextSharp = 5.5
        static let minBorderScore = 8.0
        static let needConsecutiveOk = 2
        static let coachStickySec = 0.55
        static let autoHoldSec = 0.70
    }

    enum Purpose: String, CaseIterable, Identifiable {
        case sceneCapture = "scene_capture"
        var id: String { rawValue }
        var title: String { "Numune" }
    }

    static func title(of choices: [Choice], code: String) -> String {
        choices.first(where: { $0.code == code })?.title ?? code
    }
}

/// Yellow square = 100 mm hole, sized from the active camera’s field of view.
///
/// Physical card is 220 mm. It is framed on the short side with 4% margin each edge
/// (`cardFill` = 0.92) → hole / short side = 100/220 × 0.92 ≈ 0.418 on a 24 mm-equiv
/// main camera. Every other lens keeps that same working distance and changes the
/// fraction with its own short-side FOV (crop, zoom, iPhone 14 Pro vs 17).
enum CameraOptics {
    static let holeMm = 100.0
    static let cardMm = 220.0
    static let edgeMargin = 0.04
    /// Short-side FOV of a 4:3 ~24 mm-equiv main camera.
    static let referenceShortFovDeg = 57.0

    static var cardFill: Double { 1 - 2 * edgeMargin }

    static var compositionFraction: Double { holeMm / (cardMm / cardFill) }

    static var referenceDistanceMm: Double {
        let half = tan(referenceShortFovDeg * .pi / 180 / 2)
        return (cardMm / cardFill / 2) / half
    }

    static func windowFraction(
        horizontalFovDeg: Double,
        formatWidth: Int,
        formatHeight: Int,
        zoom: Double = 1
    ) -> Double {
        guard let halfShort = shortHalfTan(
            horizontalFovDeg: horizontalFovDeg,
            formatWidth: formatWidth,
            formatHeight: formatHeight,
            zoom: zoom
        ) else { return compositionFraction }
        let sceneShortMm = 2 * referenceDistanceMm * halfShort
        let fraction = holeMm / sceneShortMm
        return min(0.56, max(0.28, fraction))
    }

    static func shortHalfTan(
        horizontalFovDeg: Double,
        formatWidth: Int,
        formatHeight: Int,
        zoom: Double
    ) -> Double? {
        guard horizontalFovDeg > 5, formatWidth > 16, formatHeight > 16 else { return nil }
        let z = max(zoom, 0.5)
        let hfov = (horizontalFovDeg * .pi / 180) / z
        let halfH = tan(hfov / 2)
        let halfV = halfH * Double(formatHeight) / Double(formatWidth)
        let halfShort = formatWidth <= formatHeight ? halfH : halfV
        guard halfShort.isFinite, halfShort > 0.02 else { return nil }
        return halfShort
    }
}
