import AudioToolbox
import AVFoundation
import UIKit

/// Screen-free alignment: TTS + beep + haptic. Debounced like Android CaptureCoach.
@MainActor
final class CaptureCoach {
    private let synthesizer = AVSpeechSynthesizer()
    private var lastKey: CoachKey = .none
    private var lastSpeakAt: TimeInterval = 0
    private let minSpeakGap: TimeInterval = 1.6
    private var lastOkTick = -1
    private let autoHoldWindow: TimeInterval = Catalog.Framing.autoHoldSec

    func onGuide(_ key: CoachKey, holdLeftMs: Int64 = 0) {
        switch key {
        case .okHold:
            if holdLeftMs > 0 && holdLeftMs <= Int64(autoHoldWindow * 1000) {
                let step = Int(holdLeftMs / 350)
                if step != lastOkTick {
                    lastOkTick = step
                    beep()
                    if step == 1 { haptic(.light) }
                }
            }
            maybeSpeak(key, "Tamam. Sabit tutun.")
        case .capture:
            lastKey = key
            beep(strong: true)
            haptic(.medium)
        default:
            lastOkTick = -1
            maybeSpeak(key, phrase(key))
            if key != lastKey {
                switch key {
                case .moveUp, .moveDown, .moveLeft, .moveRight,
                     .skew, .skewUp, .skewDown, .skewLeft, .skewRight,
                     .close, .far:
                    beep()
                    haptic(.medium)
                case .dark, .bright, .uneven, .noPaper, .texture, .blur, .exposureAdjusting:
                    beep()
                    haptic(.light)
                default:
                    break
                }
            }
            lastKey = key
        }
    }

    func quiet() {
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
    }

    func release() {
        quiet()
    }

    private func phrase(_ key: CoachKey) -> String {
        switch key {
        case .noPaper: return "Siyah halka. Dört kenarda beyaz şerit görünmeli."
        case .dark: return "Işık yetersiz. LED veya telefon ışığını yaklaştırın."
        case .bright: return "Alttan ışık var. Üstten aydınlatma kullanın."
        case .uneven: return "Gölge var. Elinizi ve gölgeyi çekin."
        case .moveDown: return "Aşağı."
        case .moveUp: return "Yukarı."
        case .moveRight: return "Sağa."
        case .moveLeft: return "Sola."
        case .far: return "Uzaklaşın."
        case .skew: return "Eğik. Düz tutun."
        case .skewUp: return "Üst yakın. Telefonun üstünü kaldırın."
        case .skewDown: return "Alt yakın. Telefonun üstünü yaklaştırın."
        case .skewLeft: return "Sol yakın. Sağa eğerek düzeltin."
        case .skewRight: return "Sağ yakın. Sola eğerek düzeltin."
        case .close: return "Yaklaşın. Delik merkeze, maksimum piksel."
        case .texture: return "Yaklaşın. Dokuyu doldurun."
        case .blur: return "Odak merkezde bekleyin."
        case .exposureAdjusting: return "Poz ayarlanıyor. Sabit tutun."
        case .okHold: return "Tamam. Sabit tutun."
        case .capture: return "Çekildi"
        case .none: return ""
        }
    }

    private func maybeSpeak(_ key: CoachKey, _ text: String) {
        guard !text.isEmpty else { return }
        let now = ProcessInfo.processInfo.systemUptime
        if key == lastKey && now - lastSpeakAt < minSpeakGap { return }
        lastSpeakAt = now
        lastKey = key
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "tr-TR")
            ?? AVSpeechSynthesisVoice(language: "en-US")
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 1.05
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
        synthesizer.speak(u)
    }

    private func beep(strong: Bool = false) {
        AudioServicesPlaySystemSound(strong ? 1117 : 1104)
    }

    private func haptic(_ style: UIImpactFeedbackGenerator.FeedbackStyle) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }
}
