package org.chromatex.lab.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class CoachKey {
    NONE,
    NO_PAPER,
    DARK,
    BRIGHT,
    UNEVEN,
    MOVE_DOWN,
    MOVE_UP,
    MOVE_RIGHT,
    MOVE_LEFT,
    FAR,
    SKEW,
    SKEW_UP,
    SKEW_DOWN,
    SKEW_LEFT,
    SKEW_RIGHT,
    CLOSE,
    TEXTURE,
    BLUR,
    EXPOSURE_ADJUSTING,
    OK_HOLD,
    CAPTURE,
}

/**
 * Ekrana bakmadan hizalama: TTS + bip + titreşim.
 * Aynı durum tekrar tekrar konuşulmaz (debounce).
 */
class CaptureCoach(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private val ttsReady = AtomicBoolean(false)
    private var tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 88)
    } catch (_: Exception) {
        null
    }

    private var lastKey: CoachKey = CoachKey.NONE
    private var lastSpeakAt = 0L
    private val minSpeakGapMs = 1600L

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tr = Locale("tr", "TR")
                val r = tts?.setLanguage(tr)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                tts?.setSpeechRate(1.05f)
                ttsReady.set(true)
            }
        }
    }

    fun onGuide(key: CoachKey, holdLeftMs: Long = 0L) {
        when (key) {
            CoachKey.OK_HOLD -> {
                // Geri sayımda her ~0.35s kısa bip
                if (holdLeftMs in 1..autoHoldWindow) {
                    val step = (holdLeftMs / 350L).toInt()
                    if (step != lastOkTick) {
                        lastOkTick = step
                        beep(ToneGenerator.TONE_PROP_ACK)
                        if (step == 1) vibrate(40)
                    }
                } else if (holdLeftMs > autoHoldWindow) {
                }
                // İlk OK'ye girişte konuş
                maybeSpeak(key, "Tamam. Sabit tutun.")
            }
            CoachKey.CAPTURE -> {
                lastKey = key
                // TTS yok — çekim sonrası shutdown ana thread’i kilitleyebiliyor
                beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
                vibrate(80)
            }
            else -> {
                lastOkTick = -1
                maybeSpeak(key, phrase(key))
                if (key != lastKey) {
                    when (key) {
                        CoachKey.MOVE_UP, CoachKey.MOVE_DOWN,
                        CoachKey.MOVE_LEFT, CoachKey.MOVE_RIGHT,
                        CoachKey.SKEW, CoachKey.SKEW_UP, CoachKey.SKEW_DOWN,
                        CoachKey.SKEW_LEFT, CoachKey.SKEW_RIGHT,
                        CoachKey.CLOSE, CoachKey.FAR,
                        -> {
                            beep(ToneGenerator.TONE_PROP_NACK)
                            vibrate(55)
                        }
                        CoachKey.DARK, CoachKey.BRIGHT, CoachKey.UNEVEN,
                        CoachKey.NO_PAPER, CoachKey.TEXTURE, CoachKey.BLUR,
                        CoachKey.EXPOSURE_ADJUSTING,
                        -> {
                            beep(ToneGenerator.TONE_CDMA_PIP)
                            vibrate(35)
                        }
                        else -> Unit
                    }
                }
                lastKey = key
            }
        }
    }

    private var lastOkTick = -1
    /** CaptureActivity.autoHoldMs (650 ms) ile aynı — bip dead-zone olmasın */
    private val autoHoldWindow = 650L

    private fun phrase(key: CoachKey): String = when (key) {
        CoachKey.NO_PAPER -> "Siyah halka. Dört kenarda beyaz şerit görünmeli."
        CoachKey.DARK -> "Işık yetersiz. LED veya telefon ışığını yaklaştırın."
        CoachKey.BRIGHT -> "Alttan ışık var. Üstten aydınlatma kullanın."
        CoachKey.UNEVEN -> "Gölge var. Elinizi ve gölgeyi çekin."
        CoachKey.MOVE_DOWN -> "Aşağı."
        CoachKey.MOVE_UP -> "Yukarı."
        CoachKey.MOVE_RIGHT -> "Sağa."
        CoachKey.MOVE_LEFT -> "Sola."
        CoachKey.FAR -> "Uzaklaşın."
        CoachKey.SKEW -> "Eğik. Düz tutun."
        CoachKey.SKEW_UP -> "Üst yakın. Tabletin üstünü kaldırın."
        CoachKey.SKEW_DOWN -> "Alt yakın. Tabletin üstünü yaklaştırın."
        CoachKey.SKEW_LEFT -> "Sol yakın. Sağa eğerek düzeltin."
        CoachKey.SKEW_RIGHT -> "Sağ yakın. Sola eğerek düzeltin."
        CoachKey.CLOSE -> "Yaklaşın. Delik merkeze, maksimum piksel."
        CoachKey.TEXTURE -> "Yaklaşın. Dokuyu doldurun."
        CoachKey.BLUR -> "Odak merkezde bekleyin."
        CoachKey.EXPOSURE_ADJUSTING -> "Poz ayarlanıyor. Sabit tutun."
        CoachKey.OK_HOLD -> "Tamam. Sabit tutun."
        CoachKey.CAPTURE -> "Çekildi"
        CoachKey.NONE -> ""
    }

    private fun maybeSpeak(key: CoachKey, text: String) {
        if (text.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (key == lastKey && now - lastSpeakAt < minSpeakGapMs) return
        lastSpeakAt = now
        lastKey = key
        speakNow(text)
    }

    private fun speakNow(text: String) {
        if (!ttsReady.get()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach_${SystemClock.uptimeMillis()}")
    }

    private fun beep(toneType: Int) {
        try {
            tone?.startTone(toneType, 120)
        } catch (_: Exception) {
        }
    }

    private fun vibrate(ms: Long) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                val vm = appContext.getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }

    /** Konuşmayı kes — shutdown yok (UI kilidi önleme). */
    fun quiet() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun release() {
        quiet()
        val engine = tts
        tts = null
        // shutdown ayrı thread — bazı cihazlarda (MediaTek) main’de ANR
        if (engine != null) {
            Thread({
                try {
                    engine.shutdown()
                } catch (_: Exception) {
                }
            }, "tts-shutdown").start()
        }
        try {
            tone?.release()
        } catch (_: Exception) {
        }
        tone = null
    }
}
