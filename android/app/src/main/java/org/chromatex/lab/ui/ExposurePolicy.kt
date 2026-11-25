package org.chromatex.lab.ui

import org.chromatex.lab.data.CameraCapabilities
import kotlin.math.abs

/**
 * Ortam-duyarlı pozlama — net/px için ISO+exp hard limit.
 *
 * Tercih: luma ~120–180, ISO≤160, exp≤20ms.
 * Kabul (OK): luma 100–200, ISO≤200, exp≤20ms — dar bantta avlanmayı keser.
 * Hard: ISO>400 veya exp>40ms → LIGHT_NEED / engel.
 *
 * Settle: limitler soft içindeyse ~1.2s ayardan sonra OK say (2 dk “Poz ayarlaniyor” yok).
 */
enum class ExposurePolicyState {
    LIGHT_NEED,
    EXPOSURE_ADJUSTING,
    EXPOSURE_OK,
    LIGHT_TOO_MUCH,
    EXPOSURE_UNSTABLE,
}

data class ExposureLimits(
    val lumaMinHard: Double = 55.0,
    /** Ayar hedefi */
    val lumaTargetLo: Double = 120.0,
    val lumaTargetHi: Double = 180.0,
    /** Auto-shoot kabul bandı (hedeften geniş — avlanmayı kes) */
    val lumaAcceptLo: Double = 100.0,
    val lumaAcceptHi: Double = 200.0,
    val lumaTooMuch: Double = 242.0,
    val isoPrefer: Int = 160,
    val isoSoft: Int = 200,
    val isoHard: Int = 400,
    val expPreferNs: Long = 20_000_000L,
    val expHardNs: Long = 40_000_000L,
) {
    fun withDevice(caps: CameraCapabilities?): ExposureLimits {
        if (caps == null) return this
        val isoUpper = caps.isoRange?.upper ?: isoHard
        val isoLower = caps.isoRange?.lower ?: 50
        val expUpper = caps.exposureNsRange?.upper ?: expHardNs
        val expLower = caps.exposureNsRange?.lower ?: 1_000_000L
        return copy(
            isoPrefer = isoPrefer.coerceIn(isoLower, isoUpper),
            isoSoft = isoSoft.coerceIn(isoLower, isoUpper),
            isoHard = isoHard.coerceIn(isoLower, isoUpper),
            expPreferNs = expPreferNs.coerceIn(expLower, expUpper),
            expHardNs = expHardNs.coerceIn(expLower, expUpper),
        )
    }
}

data class ExposureDecision(
    val state: ExposurePolicyState,
    val exposureOk: Boolean,
    val guidance: String,
    val coachKey: CoachKey,
    val wantIso: Int? = null,
    val wantExpNs: Long? = null,
    val evDelta: Int = 0,
    val measuredLuma: Double = 0.0,
    val targetLumaBand: String = "100-200",
)

class ExposurePolicyController(
    private var limits: ExposureLimits = ExposureLimits(),
) {
    var lastState: ExposurePolicyState = ExposurePolicyState.EXPOSURE_ADJUSTING
        private set
    var lastGuidance: String = ""
        private set
    var lastDecision: ExposureDecision? = null
        private set

    private var flipCount = 0
    private var lastAdjustDir = 0
    private var lastApplyElapsed = 0L
    private var adjustingSinceElapsed = -1L
    private val minApplyGapMs = 450L
    /** Soft limit içinde bu kadar ayardan sonra OK — sonsuz av yok */
    private val settleMs = 1200L

    fun updateLimits(caps: CameraCapabilities?) {
        limits = ExposureLimits().withDevice(caps)
    }

    fun limits(): ExposureLimits = limits

    fun evaluate(
        luma: Double,
        iso: Int?,
        expNs: Long?,
        isoPresets: List<Int>,
        expPresetsNs: List<Long>,
        supportsManual: Boolean,
        nowElapsed: Long,
        apply: Boolean,
        whiteRingLuma: Double = -1.0,
        whiteRingClipRatio: Double = 0.0,
    ): ExposureDecision {
        val L = limits
        val isoVal = iso ?: L.isoPrefer
        val expVal = expNs ?: L.expPreferNs
        val hardIso = iso != null && iso > L.isoHard
        val hardExp = expNs != null && expNs > L.expHardNs
        val softIso = iso != null && iso > L.isoSoft
        val overPreferIso = iso != null && iso > L.isoPrefer
        val overPreferExp = expNs != null && expNs > L.expPreferNs
        val meteringReady = iso != null && expNs != null
        val hasWhiteRef = whiteRingLuma > 10.0
        val whiteClipped = hasWhiteRef && (whiteRingLuma > 244.0 || whiteRingClipRatio > 0.22)
        val band = if (hasWhiteRef) "white:145-244" else "${L.lumaAcceptLo.toInt()}-${L.lumaAcceptHi.toInt()}"
        val reportedLuma = if (hasWhiteRef) whiteRingLuma else luma

        // Auto-AE tabletlerde (supportsManual=false, örn. Lenovo TB-X306F) siyah kumaş merkezdeyken
        // stok kamera AE 29.99ms ve ISO ~205-280 seçebiliyor; bunları kilitlememek için esnek soft sınır:
        val limitsSoftOk = if (hasWhiteRef && !supportsManual) {
            iso != null && expNs != null && iso <= 320 && expNs <= 34_000_000L
        } else if (hasWhiteRef) {
            iso != null && expNs != null && iso <= 250 && expNs <= 25_000_000L
        } else {
            iso != null &&
                expNs != null &&
                iso <= L.isoSoft &&
                expNs <= L.expPreferNs
        }
        val lumaAcceptable = if (hasWhiteRef) {
            whiteRingLuma in 155.0..244.0 && !whiteClipped
        } else {
            luma in L.lumaAcceptLo..L.lumaAcceptHi
        }
        val lumaUsable = if (hasWhiteRef) {
            whiteRingLuma in 125.0..244.0 && !whiteClipped
        } else {
            luma >= L.lumaMinHard && luma <= L.lumaTooMuch
        }

        fun decide(
            state: ExposurePolicyState,
            msg: String,
            key: CoachKey,
            wantIso: Int? = null,
            wantExp: Long? = null,
            evDelta: Int = 0,
            ok: Boolean = state == ExposurePolicyState.EXPOSURE_OK,
        ): ExposureDecision {
            val dir = when {
                wantIso != null && iso != null -> wantIso.compareTo(iso).coerceIn(-1, 1)
                wantExp != null && expNs != null -> wantExp.compareTo(expNs).coerceIn(-1, 1)
                evDelta != 0 -> evDelta.coerceIn(-1, 1)
                else -> 0
            }
            var st = state
            if (dir != 0 && lastAdjustDir != 0 && dir == -lastAdjustDir) {
                flipCount++
            } else if (dir != 0) {
                flipCount = (flipCount - 1).coerceAtLeast(0)
            }
            if (dir != 0) lastAdjustDir = dir

            // Zıt ayar avı + soft limit OK → UNSTABLE yerine OK (çekime izin; patlama yoksa)
            if (flipCount >= 3 && limitsSoftOk && lumaUsable && !whiteClipped &&
                st == ExposurePolicyState.EXPOSURE_ADJUSTING
            ) {
                st = ExposurePolicyState.EXPOSURE_OK
                flipCount = 0
                lastAdjustDir = 0
                adjustingSinceElapsed = -1L
                val settled = ExposureDecision(
                    state = st,
                    exposureOk = true,
                    guidance = "Poz OK",
                    coachKey = CoachKey.NONE,
                    measuredLuma = reportedLuma,
                    targetLumaBand = band,
                )
                lastState = settled.state
                lastGuidance = settled.guidance
                lastDecision = settled
                return settled
            }
            if (flipCount >= 4 && st == ExposurePolicyState.EXPOSURE_ADJUSTING) {
                st = ExposurePolicyState.EXPOSURE_UNSTABLE
            }

            val canApply = apply && (nowElapsed - lastApplyElapsed >= minApplyGapMs)
            // OK / settle sonrası yeni ayar isteme
            val blockApply = st == ExposurePolicyState.EXPOSURE_OK
            val out = ExposureDecision(
                state = st,
                exposureOk = (ok || st == ExposurePolicyState.EXPOSURE_OK) && !hardIso && !hardExp && !whiteClipped,
                guidance = msg,
                coachKey = key,
                wantIso = if (canApply && !blockApply) wantIso else null,
                wantExpNs = if (canApply && !blockApply) wantExp else null,
                evDelta = if (canApply && !blockApply) evDelta else 0,
                measuredLuma = reportedLuma,
                targetLumaBand = band,
            )
            if (canApply && !blockApply && (wantIso != null || wantExp != null || evDelta != 0)) {
                lastApplyElapsed = nowElapsed
            }
            if (st == ExposurePolicyState.EXPOSURE_ADJUSTING ||
                st == ExposurePolicyState.EXPOSURE_UNSTABLE
            ) {
                if (adjustingSinceElapsed < 0L) adjustingSinceElapsed = nowElapsed
            } else if (st == ExposurePolicyState.EXPOSURE_OK) {
                adjustingSinceElapsed = -1L
            }
            lastState = out.state
            lastGuidance = out.guidance
            lastDecision = out
            return out
        }

        // --- Beyaz referans halkası clipping kapısı (Torch / aşırı ışıkta yamaların patlamasını önler) ---
        if (meteringReady && whiteClipped) {
            // Önce poz süresini kıs (hareket bulanıklığını da keser), 8ms altında ISO düşür.
            val minExpFloor = expPresetsNs.firstOrNull() ?: 500_000L
            val minIsoFloor = isoPresets.firstOrNull() ?: 50
            val nextExp = if (expVal > 8_000_000L) stepDownLong(expPresetsNs, expVal) else expVal
            val nextIso = if (expVal <= 8_000_000L || nextExp == expVal) {
                stepDown(isoPresets, isoVal)
            } else {
                isoVal
            }
            val atFloor = isoVal <= minIsoFloor && expVal <= minExpFloor
            if (atFloor) {
                adjustingSinceElapsed = -1L
                return decide(
                    ExposurePolicyState.LIGHT_TOO_MUCH,
                    "BEYAZ KART PATLIYOR — flasi kapatin veya isigi uzaklastirin",
                    CoachKey.BRIGHT,
                    evDelta = if (!supportsManual) -1 else 0,
                    ok = false,
                )
            }
            return decide(
                ExposurePolicyState.EXPOSURE_ADJUSTING,
                "Poz kisiliyor (beyaz referans koruma) — sabit tutun",
                CoachKey.EXPOSURE_ADJUSTING,
                wantIso = if (supportsManual && nextIso != isoVal) nextIso else null,
                wantExp = if (supportsManual && nextExp != expVal) nextExp else null,
                evDelta = if (!supportsManual) -1 else 0,
                ok = false,
            )
        }

        // --- Settle: soft limit + kullanılabilir luma → yeterince ayarlandıysa OK ---
        if (limitsSoftOk && lumaUsable && !whiteClipped && adjustingSinceElapsed >= 0L &&
            nowElapsed - adjustingSinceElapsed >= settleMs
        ) {
            flipCount = 0
            lastAdjustDir = 0
            return decide(
                ExposurePolicyState.EXPOSURE_OK,
                "Poz OK",
                CoachKey.NONE,
                ok = true,
            )
        }

        // --- Beyaz referans halkası kilitliyken TAM KAPSAMLI kalibrasyonlu pozlama ---
        // ÖNEMLİ: Bu bloktan ASLA alttaki eski (saf kumaş luma < 55) kontrollerine düşülmez;
        // aksi halde siyah kumaş (luma ~20-40) karanlık oda sanılıp LIGHT_NEED ile kilitlenir.
        if (meteringReady && hasWhiteRef) {
            if (hardIso || hardExp) {
                if (!supportsManual && whiteRingLuma >= 195.0) {
                    // Auto-AE tablet siyah kumaşı aydınlatmak için 40ms'ye çıktıysa EV düşürerek hızlandır
                    return decide(
                        ExposurePolicyState.EXPOSURE_ADJUSTING,
                        "Poz hizlandiriliyor (titreme onleme) — sabit tutun",
                        CoachKey.EXPOSURE_ADJUSTING,
                        evDelta = -1,
                        ok = false,
                    )
                }
                if (supportsManual) {
                    return decide(
                        ExposurePolicyState.EXPOSURE_ADJUSTING,
                        "Poz ayarlaniyor — sabit tutun",
                        CoachKey.EXPOSURE_ADJUSTING,
                        wantIso = if (hardIso) nearest(isoPresets, L.isoSoft) else null,
                        wantExp = if (hardExp) nearestLong(expPresetsNs, L.expPreferNs) else null,
                        ok = false,
                    )
                }
                adjustingSinceElapsed = -1L
                return decide(
                    ExposurePolicyState.LIGHT_NEED,
                    "ISIK YETERSIZ — ust aydinlatmayi artirin",
                    CoachKey.DARK,
                    ok = false,
                )
            }

            // Koyu/siyah kumaşta manuel sensörlü cihazda beyaz halkada güvenli pay varsa (<195) pozu tercih tavanına çıkar
            if (supportsManual && luma < 55.0 && whiteRingLuma in 155.0..195.0 &&
                (isoVal < L.isoPrefer || expVal < L.expPreferNs)
            ) {
                return decide(
                    ExposurePolicyState.EXPOSURE_ADJUSTING,
                    "Koyu kumas sinyali optimize ediliyor — sabit tutun",
                    CoachKey.EXPOSURE_ADJUSTING,
                    wantIso = if (isoVal < L.isoPrefer) stepUp(isoPresets, isoVal, L.isoPrefer) else null,
                    wantExp = if (expVal < L.expPreferNs) stepUpLong(expPresetsNs, expVal, L.expPreferNs) else null,
                )
            }

            if (limitsSoftOk && whiteRingLuma in 145.0..244.0) {
                flipCount = 0
                lastAdjustDir = 0
                return decide(
                    ExposurePolicyState.EXPOSURE_OK,
                    "Poz OK",
                    CoachKey.NONE,
                    ok = true,
                )
            }

            if (whiteRingLuma < 145.0) {
                val maxSoftIso = if (supportsManual) 250 else 320
                if (isoVal >= maxSoftIso && expVal >= L.expPreferNs) {
                    if (whiteRingLuma >= 125.0) {
                        return decide(
                            ExposurePolicyState.EXPOSURE_OK,
                            "Poz OK",
                            CoachKey.NONE,
                            ok = true,
                        )
                    }
                    adjustingSinceElapsed = -1L
                    return decide(
                        ExposurePolicyState.LIGHT_NEED,
                        "ISIK YETERSIZ — ust aydinlatmayi yaklastirin",
                        CoachKey.DARK,
                        ok = false,
                    )
                }
                return decide(
                    ExposurePolicyState.EXPOSURE_ADJUSTING,
                    "Poz ayarlaniyor — sabit tutun",
                    CoachKey.EXPOSURE_ADJUSTING,
                    wantIso = if (supportsManual && isoVal < maxSoftIso) {
                        stepUp(isoPresets, isoVal, maxSoftIso)
                    } else null,
                    wantExp = if (supportsManual && expVal < L.expPreferNs) {
                        stepUpLong(expPresetsNs, expVal, L.expPreferNs)
                    } else null,
                    evDelta = if (!supportsManual) +1 else 0,
                )
            }

            // Beyaz halka 145..244 aralığında ama ISO/exp soft sınırın biraz üstündeyse (örn. 260..380 veya 30..38ms):
            // Manuel cihazda yumuşakça düşür, düşemiyorsa veya auto-AE ise 1.2s settle sonrası çekime izin ver.
            return decide(
                ExposurePolicyState.EXPOSURE_ADJUSTING,
                "Poz dengeleniyor — sabit tutun",
                CoachKey.EXPOSURE_ADJUSTING,
                wantIso = if (supportsManual && isoVal > 250) nearest(isoPresets, L.isoSoft) else null,
                wantExp = if (supportsManual && expVal > 25_000_000L) nearestLong(expPresetsNs, L.expPreferNs) else null,
                evDelta = if (!supportsManual && whiteRingLuma > 210.0) -1 else 0,
            )
        }

        // --- Hard ISO ---
        if (hardIso) {
            adjustingSinceElapsed = -1L
            return decide(
                ExposurePolicyState.LIGHT_NEED,
                "ISIK YETERSIZ — LED/telefon isigini yaklastirin (ISO yuksek)",
                CoachKey.DARK,
                wantIso = if (supportsManual) nearest(isoPresets, isoVal.coerceAtMost(L.isoHard)) else null,
                wantExp = if (supportsManual && hardExp) {
                    nearestLong(expPresetsNs, L.expPreferNs)
                } else {
                    null
                },
                ok = false,
            )
        }

        if (hardExp && luma < L.lumaAcceptLo) {
            adjustingSinceElapsed = -1L
            return decide(
                ExposurePolicyState.LIGHT_NEED,
                "ISIK YETERSIZ — LED/telefon isigini yaklastirin (ISO yuksek)",
                CoachKey.DARK,
                wantExp = if (supportsManual) nearestLong(expPresetsNs, L.expPreferNs) else null,
                ok = false,
            )
        }

        if (hardExp && luma >= L.lumaAcceptLo) {
            return decide(
                ExposurePolicyState.EXPOSURE_ADJUSTING,
                "Poz ayarlaniyor — sabit tutun",
                CoachKey.EXPOSURE_ADJUSTING,
                wantExp = if (supportsManual) nearestLong(expPresetsNs, L.expPreferNs) else null,
                evDelta = if (!supportsManual) -1 else 0,
            )
        }

        // --- Metering yok ---
        if (!meteringReady) {
            return decide(
                ExposurePolicyState.EXPOSURE_ADJUSTING,
                "Poz ayarlaniyor — sabit tutun",
                CoachKey.EXPOSURE_ADJUSTING,
                wantIso = if (supportsManual) nearest(isoPresets, L.isoPrefer) else null,
                wantExp = if (supportsManual) nearestLong(expPresetsNs, L.expPreferNs) else null,
            )
        }

        // --- Kabul bandı + soft limit → OK (mikro trim yok) ---
        if (limitsSoftOk && lumaAcceptable) {
            flipCount = 0
            lastAdjustDir = 0
            return decide(
                ExposurePolicyState.EXPOSURE_OK,
                "Poz OK",
                CoachKey.NONE,
                ok = true,
            )
        }

        // --- Çok karanlık ---
        if (luma < L.lumaMinHard) {
            if (isoVal >= L.isoSoft) {
                adjustingSinceElapsed = -1L
                return decide(
                    ExposurePolicyState.LIGHT_NEED,
                    "ISIK YETERSIZ — LED/telefon isigini yaklastirin (ISO yuksek)",
                    CoachKey.DARK,
                    ok = false,
                )
            }
            return decide(
                ExposurePolicyState.EXPOSURE_ADJUSTING,
                "Poz ayarlaniyor — sabit tutun",
                CoachKey.EXPOSURE_ADJUSTING,
                wantIso = if (supportsManual) stepUp(isoPresets, isoVal, L.isoPrefer) else null,
                wantExp = if (supportsManual) stepUpLong(expPresetsNs, expVal, L.expPreferNs) else null,
                evDelta = if (!supportsManual) +1 else 0,
            )
        }

        // --- Yanık ---
        if (luma > L.lumaTooMuch) {
            val nextIso = stepDown(isoPresets, isoVal)
            val nextExp = stepDownLong(expPresetsNs, expVal)
            val atFloor =
                isoVal <= (isoPresets.firstOrNull() ?: 50) &&
                    expVal <= (expPresetsNs.firstOrNull() ?: 500_000L)
            if (atFloor) {
                adjustingSinceElapsed = -1L
                return decide(
                    ExposurePolicyState.LIGHT_TOO_MUCH,
                    "ISIK FAZLA — isigi uzaklastirin/kisin",
                    CoachKey.BRIGHT,
                    evDelta = if (!supportsManual) -1 else 0,
                    ok = false,
                )
            }
            return decide(
                ExposurePolicyState.EXPOSURE_ADJUSTING,
                "Poz ayarlaniyor — sabit tutun",
                CoachKey.EXPOSURE_ADJUSTING,
                wantIso = if (supportsManual) nextIso else null,
                wantExp = if (supportsManual) nextExp else null,
                evDelta = if (!supportsManual) -1 else 0,
            )
        }

        // --- Soft ISO aşımı: düşürmeyi dene; luma kabuldeyse settle OK ---
        if (softIso) {
            if (lumaAcceptable) {
                // Luma iyi ama ISO yüksek → ışık iste (daha fazla düşürmek luma bozar)
                adjustingSinceElapsed = -1L
                return decide(
                    ExposurePolicyState.LIGHT_NEED,
                    "ISIK YETERSIZ — LED/telefon isigini yaklastirin (ISO yuksek)",
                    CoachKey.DARK,
                    wantIso = if (supportsManual) nearest(isoPresets, L.isoSoft) else null,
                    ok = false,
                )
            }
            return decide(
                ExposurePolicyState.EXPOSURE_ADJUSTING,
                "Poz ayarlaniyor — sabit tutun",
                CoachKey.EXPOSURE_ADJUSTING,
                wantIso = if (supportsManual) nearest(isoPresets, L.isoPrefer) else null,
                wantExp = if (supportsManual && overPreferExp) {
                    nearestLong(expPresetsNs, L.expPreferNs)
                } else {
                    null
                },
                evDelta = if (!supportsManual) -1 else 0,
            )
        }

        // --- Exp prefer üstü ---
        if (overPreferExp) {
            return decide(
                ExposurePolicyState.EXPOSURE_ADJUSTING,
                "Poz ayarlaniyor — sabit tutun",
                CoachKey.EXPOSURE_ADJUSTING,
                wantExp = if (supportsManual) nearestLong(expPresetsNs, L.expPreferNs) else null,
                evDelta = if (!supportsManual) -1 else 0,
            )
        }

        // --- Luma hedef dışı ama kabul dışı → tek adım ayar ---
        if (luma > L.lumaTargetHi) {
            return decide(
                ExposurePolicyState.EXPOSURE_ADJUSTING,
                "Poz ayarlaniyor — sabit tutun",
                CoachKey.EXPOSURE_ADJUSTING,
                wantIso = if (supportsManual) stepDown(isoPresets, isoVal) else null,
                wantExp = if (supportsManual) stepDownLong(expPresetsNs, expVal) else null,
                evDelta = if (!supportsManual) -1 else 0,
            )
        }

        if (luma < L.lumaTargetLo) {
            if (isoVal >= L.isoPrefer) {
                // Tercih tavanı: kabul bandındaysa OK, değilse ışık
                if (lumaAcceptable && limitsSoftOk) {
                    return decide(
                        ExposurePolicyState.EXPOSURE_OK,
                        "Poz OK",
                        CoachKey.NONE,
                        ok = true,
                    )
                }
                adjustingSinceElapsed = -1L
                return decide(
                    ExposurePolicyState.LIGHT_NEED,
                    "ISIK YETERSIZ — LED/telefon isigini yaklastirin (ISO yuksek)",
                    CoachKey.DARK,
                    ok = false,
                )
            }
            return decide(
                ExposurePolicyState.EXPOSURE_ADJUSTING,
                "Poz ayarlaniyor — sabit tutun",
                CoachKey.EXPOSURE_ADJUSTING,
                wantIso = if (supportsManual) stepUp(isoPresets, isoVal, L.isoPrefer) else null,
                wantExp = if (supportsManual) stepUpLong(expPresetsNs, expVal, L.expPreferNs) else null,
                evDelta = if (!supportsManual) +1 else 0,
            )
        }

        // Hedef bantta, soft OK → OK
        if (limitsSoftOk) {
            flipCount = 0
            lastAdjustDir = 0
            return decide(
                ExposurePolicyState.EXPOSURE_OK,
                "Poz OK",
                CoachKey.NONE,
                ok = true,
            )
        }

        return decide(
            ExposurePolicyState.EXPOSURE_ADJUSTING,
            "Poz ayarlaniyor — sabit tutun",
            CoachKey.EXPOSURE_ADJUSTING,
        )
    }

    companion object {
        fun nearest(presets: List<Int>, want: Int): Int? {
            if (presets.isEmpty()) return null
            return presets.minByOrNull { abs(it - want) }
        }

        fun nearestLong(presets: List<Long>, want: Long): Long? {
            if (presets.isEmpty()) return null
            return presets.minByOrNull { abs(it - want) }
        }

        fun stepUp(presets: List<Int>, current: Int, cap: Int): Int? {
            if (presets.isEmpty()) return null
            val next = presets.firstOrNull { it > current } ?: presets.last()
            return next.coerceAtMost(cap).let { t -> nearest(presets, t) }
        }

        fun stepDown(presets: List<Int>, current: Int): Int? {
            if (presets.isEmpty()) return null
            return presets.lastOrNull { it < current } ?: presets.first()
        }

        fun stepUpLong(presets: List<Long>, current: Long, cap: Long): Long? {
            if (presets.isEmpty()) return null
            val next = presets.firstOrNull { it > current } ?: presets.last()
            return next.coerceAtMost(cap).let { t -> nearestLong(presets, t) }
        }

        fun stepDownLong(presets: List<Long>, current: Long): Long? {
            if (presets.isEmpty()) return null
            return presets.lastOrNull { it < current } ?: presets.first()
        }
    }
}
