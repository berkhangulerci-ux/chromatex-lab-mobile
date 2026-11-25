import Foundation

enum ExposurePolicyState: String {
    case lightNeed = "LIGHT_NEED"
    case exposureAdjusting = "EXPOSURE_ADJUSTING"
    case exposureOk = "EXPOSURE_OK"
    case lightTooMuch = "LIGHT_TOO_MUCH"
    case exposureUnstable = "EXPOSURE_UNSTABLE"
}

struct ExposureLimits {
    var lumaMinHard: Double = 55
    var lumaTargetLo: Double = 120
    var lumaTargetHi: Double = 180
    var lumaAcceptLo: Double = 100
    var lumaAcceptHi: Double = 200
    var lumaTooMuch: Double = 242
    var isoPrefer: Int = 160
    var isoSoft: Int = 200
    var isoHard: Int = 400
    var expPreferNs: Int64 = 20_000_000
    var expHardNs: Int64 = 40_000_000

    mutating func clamp(isoMin: Int, isoMax: Int, expMin: Int64, expMax: Int64) {
        isoPrefer = min(max(isoPrefer, isoMin), isoMax)
        isoSoft = min(max(isoSoft, isoMin), isoMax)
        isoHard = min(max(isoHard, isoMin), isoMax)
        expPreferNs = min(max(expPreferNs, expMin), expMax)
        expHardNs = min(max(expHardNs, expMin), expMax)
    }
}

struct ExposureDecision {
    var state: ExposurePolicyState
    var exposureOk: Bool
    var guidance: String
    var coachKey: CoachKey
    var wantIso: Int?
    var wantExpNs: Int64?
    var evDelta: Int = 0
    var measuredLuma: Double = 0
    var targetLumaBand: String = "100-200"
}

final class ExposurePolicyController {
    private(set) var limits = ExposureLimits()
    private(set) var lastState: ExposurePolicyState = .exposureAdjusting
    private(set) var lastGuidance = ""
    private(set) var lastDecision: ExposureDecision?

    private var flipCount = 0
    private var lastAdjustDir = 0
    private var lastApplyElapsed: Int64 = 0
    private var adjustingSinceElapsed: Int64 = -1
    private let minApplyGapMs: Int64 = 450
    private let settleMs: Int64 = 1200

    func updateDeviceLimits(isoMin: Int, isoMax: Int, expMinNs: Int64, expMaxNs: Int64) {
        limits.clamp(isoMin: isoMin, isoMax: isoMax, expMin: expMinNs, expMax: expMaxNs)
    }

    func evaluate(
        luma: Double,
        iso: Int?,
        expNs: Int64?,
        isoPresets: [Int],
        expPresetsNs: [Int64],
        supportsManual: Bool,
        nowElapsed: Int64,
        apply: Bool
    ) -> ExposureDecision {
        let L = limits
        let isoVal = iso ?? L.isoPrefer
        let expVal = expNs ?? L.expPreferNs
        let hardIso = iso.map { $0 > L.isoHard } ?? false
        let hardExp = expNs.map { $0 > L.expHardNs } ?? false
        let softIso = iso.map { $0 > L.isoSoft } ?? false
        let overPreferExp = expNs.map { $0 > L.expPreferNs } ?? false
        let meteringReady = iso != nil && expNs != nil
        let band = "\(Int(L.lumaAcceptLo))-\(Int(L.lumaAcceptHi))"

        let limitsSoftOk = iso.map { $0 <= L.isoSoft } ?? false
            && expNs.map { $0 <= L.expPreferNs } ?? false
        let lumaAcceptable = luma >= L.lumaAcceptLo && luma <= L.lumaAcceptHi
        let lumaUsable = luma >= L.lumaMinHard && luma <= L.lumaTooMuch

        func decide(
            _ state: ExposurePolicyState,
            _ msg: String,
            _ key: CoachKey,
            wantIso: Int? = nil,
            wantExp: Int64? = nil,
            evDelta: Int = 0,
            ok: Bool? = nil
        ) -> ExposureDecision {
            let dir: Int = {
                if let wantIso, let iso { return (wantIso > iso ? 1 : wantIso < iso ? -1 : 0) }
                if let wantExp, let expNs { return (wantExp > expNs ? 1 : wantExp < expNs ? -1 : 0) }
                return evDelta == 0 ? 0 : (evDelta > 0 ? 1 : -1)
            }()
            var st = state
            if dir != 0 && lastAdjustDir != 0 && dir == -lastAdjustDir {
                flipCount += 1
            } else if dir != 0 {
                flipCount = max(flipCount - 1, 0)
            }
            if dir != 0 { lastAdjustDir = dir }

            if flipCount >= 3 && limitsSoftOk && lumaUsable && st == .exposureAdjusting {
                st = .exposureOk
                flipCount = 0
                lastAdjustDir = 0
                adjustingSinceElapsed = -1
                let settled = ExposureDecision(
                    state: st, exposureOk: true, guidance: "Poz OK", coachKey: .none,
                    measuredLuma: luma, targetLumaBand: band
                )
                lastState = settled.state
                lastGuidance = settled.guidance
                lastDecision = settled
                return settled
            }
            if flipCount >= 4 && st == .exposureAdjusting {
                st = .exposureUnstable
            }

            let canApply = apply && (nowElapsed - lastApplyElapsed >= minApplyGapMs)
            let blockApply = st == .exposureOk
            let exposureOk = (ok ?? (st == .exposureOk)) && !hardIso && !hardExp
            let out = ExposureDecision(
                state: st,
                exposureOk: exposureOk,
                guidance: msg,
                coachKey: key,
                wantIso: canApply && !blockApply ? wantIso : nil,
                wantExpNs: canApply && !blockApply ? wantExp : nil,
                evDelta: canApply && !blockApply ? evDelta : 0,
                measuredLuma: luma,
                targetLumaBand: band
            )
            if canApply && !blockApply && (wantIso != nil || wantExp != nil || evDelta != 0) {
                lastApplyElapsed = nowElapsed
            }
            if st == .exposureAdjusting || st == .exposureUnstable {
                if adjustingSinceElapsed < 0 { adjustingSinceElapsed = nowElapsed }
            } else if st == .exposureOk {
                adjustingSinceElapsed = -1
            }
            lastState = out.state
            lastGuidance = out.guidance
            lastDecision = out
            return out
        }

        if limitsSoftOk && lumaUsable && adjustingSinceElapsed >= 0
            && nowElapsed - adjustingSinceElapsed >= settleMs
        {
            flipCount = 0
            lastAdjustDir = 0
            return decide(.exposureOk, "Poz OK", .none, ok: true)
        }

        if hardIso {
            adjustingSinceElapsed = -1
            return decide(
                .lightNeed,
                "ISIK YETERSIZ — LED/telefon isigini yaklastirin (ISO yuksek)",
                .dark,
                wantIso: supportsManual ? Self.nearest(isoPresets, min(isoVal, L.isoHard)) : nil,
                wantExp: supportsManual && hardExp ? Self.nearestLong(expPresetsNs, L.expPreferNs) : nil,
                ok: false
            )
        }

        if hardExp && luma < L.lumaAcceptLo {
            adjustingSinceElapsed = -1
            return decide(
                .lightNeed,
                "ISIK YETERSIZ — LED/telefon isigini yaklastirin (ISO yuksek)",
                .dark,
                wantExp: supportsManual ? Self.nearestLong(expPresetsNs, L.expPreferNs) : nil,
                ok: false
            )
        }

        if hardExp && luma >= L.lumaAcceptLo {
            return decide(
                .exposureAdjusting, "Poz ayarlaniyor — sabit tutun", .exposureAdjusting,
                wantExp: supportsManual ? Self.nearestLong(expPresetsNs, L.expPreferNs) : nil,
                evDelta: supportsManual ? 0 : -1
            )
        }

        if !meteringReady {
            return decide(
                .exposureAdjusting, "Poz ayarlaniyor — sabit tutun", .exposureAdjusting,
                wantIso: supportsManual ? Self.nearest(isoPresets, L.isoPrefer) : nil,
                wantExp: supportsManual ? Self.nearestLong(expPresetsNs, L.expPreferNs) : nil
            )
        }

        if limitsSoftOk && lumaAcceptable {
            flipCount = 0
            lastAdjustDir = 0
            return decide(.exposureOk, "Poz OK", .none, ok: true)
        }

        if luma < L.lumaMinHard {
            if isoVal >= L.isoSoft {
                adjustingSinceElapsed = -1
                return decide(
                    .lightNeed,
                    "ISIK YETERSIZ — LED/telefon isigini yaklastirin (ISO yuksek)",
                    .dark, ok: false
                )
            }
            return decide(
                .exposureAdjusting, "Poz ayarlaniyor — sabit tutun", .exposureAdjusting,
                wantIso: supportsManual ? Self.stepUp(isoPresets, isoVal, L.isoPrefer) : nil,
                wantExp: supportsManual ? Self.stepUpLong(expPresetsNs, expVal, L.expPreferNs) : nil,
                evDelta: supportsManual ? 0 : 1
            )
        }

        if luma > L.lumaTooMuch {
            let nextIso = Self.stepDown(isoPresets, isoVal)
            let nextExp = Self.stepDownLong(expPresetsNs, expVal)
            let atFloor = isoVal <= (isoPresets.first ?? 50)
                && expVal <= (expPresetsNs.first ?? 500_000)
            if atFloor {
                adjustingSinceElapsed = -1
                return decide(
                    .lightTooMuch, "ISIK FAZLA — isigi uzaklastirin/kisin", .bright,
                    evDelta: supportsManual ? 0 : -1, ok: false
                )
            }
            return decide(
                .exposureAdjusting, "Poz ayarlaniyor — sabit tutun", .exposureAdjusting,
                wantIso: supportsManual ? nextIso : nil,
                wantExp: supportsManual ? nextExp : nil,
                evDelta: supportsManual ? 0 : -1
            )
        }

        if softIso {
            if lumaAcceptable {
                adjustingSinceElapsed = -1
                return decide(
                    .lightNeed,
                    "ISIK YETERSIZ — LED/telefon isigini yaklastirin (ISO yuksek)",
                    .dark,
                    wantIso: supportsManual ? Self.nearest(isoPresets, L.isoSoft) : nil,
                    ok: false
                )
            }
            return decide(
                .exposureAdjusting, "Poz ayarlaniyor — sabit tutun", .exposureAdjusting,
                wantIso: supportsManual ? Self.nearest(isoPresets, L.isoPrefer) : nil,
                wantExp: supportsManual && overPreferExp ? Self.nearestLong(expPresetsNs, L.expPreferNs) : nil,
                evDelta: supportsManual ? 0 : -1
            )
        }

        if overPreferExp {
            return decide(
                .exposureAdjusting, "Poz ayarlaniyor — sabit tutun", .exposureAdjusting,
                wantExp: supportsManual ? Self.nearestLong(expPresetsNs, L.expPreferNs) : nil,
                evDelta: supportsManual ? 0 : -1
            )
        }

        if luma > L.lumaTargetHi {
            return decide(
                .exposureAdjusting, "Poz ayarlaniyor — sabit tutun", .exposureAdjusting,
                wantIso: supportsManual ? Self.stepDown(isoPresets, isoVal) : nil,
                wantExp: supportsManual ? Self.stepDownLong(expPresetsNs, expVal) : nil,
                evDelta: supportsManual ? 0 : -1
            )
        }

        if luma < L.lumaTargetLo {
            if isoVal >= L.isoPrefer {
                if lumaAcceptable && limitsSoftOk {
                    return decide(.exposureOk, "Poz OK", .none, ok: true)
                }
                adjustingSinceElapsed = -1
                return decide(
                    .lightNeed,
                    "ISIK YETERSIZ — LED/telefon isigini yaklastirin (ISO yuksek)",
                    .dark, ok: false
                )
            }
            return decide(
                .exposureAdjusting, "Poz ayarlaniyor — sabit tutun", .exposureAdjusting,
                wantIso: supportsManual ? Self.stepUp(isoPresets, isoVal, L.isoPrefer) : nil,
                wantExp: supportsManual ? Self.stepUpLong(expPresetsNs, expVal, L.expPreferNs) : nil,
                evDelta: supportsManual ? 0 : 1
            )
        }

        if limitsSoftOk {
            flipCount = 0
            lastAdjustDir = 0
            return decide(.exposureOk, "Poz OK", .none, ok: true)
        }

        return decide(.exposureAdjusting, "Poz ayarlaniyor — sabit tutun", .exposureAdjusting)
    }

    static func nearest(_ presets: [Int], _ want: Int) -> Int? {
        presets.min(by: { abs($0 - want) < abs($1 - want) })
    }

    static func nearestLong(_ presets: [Int64], _ want: Int64) -> Int64? {
        presets.min(by: { abs($0 - want) < abs($1 - want) })
    }

    static func stepUp(_ presets: [Int], _ current: Int, _ cap: Int) -> Int? {
        guard !presets.isEmpty else { return nil }
        let next = presets.first(where: { $0 > current }) ?? presets.last!
        return nearest(presets, min(next, cap))
    }

    static func stepDown(_ presets: [Int], _ current: Int) -> Int? {
        guard !presets.isEmpty else { return nil }
        return presets.last(where: { $0 < current }) ?? presets.first
    }

    static func stepUpLong(_ presets: [Int64], _ current: Int64, _ cap: Int64) -> Int64? {
        guard !presets.isEmpty else { return nil }
        let next = presets.first(where: { $0 > current }) ?? presets.last!
        return nearestLong(presets, min(next, cap))
    }

    static func stepDownLong(_ presets: [Int64], _ current: Int64) -> Int64? {
        guard !presets.isEmpty else { return nil }
        return presets.last(where: { $0 < current }) ?? presets.first
    }
}
