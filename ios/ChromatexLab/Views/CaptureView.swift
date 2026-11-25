import SwiftUI
import UIKit

struct CaptureView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: SceneRecordStore

    let operatorName: String
    let batchId: String
    let fabric: FabricSession
    let purpose: String

    @StateObject private var camera = CameraController()
    @State private var coach = CaptureCoach()
    @State private var guideText = "SARI kare = 100mm delik — siyah+yeşil halkayı hizalayın"
    @State private var guideOK = false
    @State private var capturing = false
    @State private var shotsThisSession = 0
    @State private var lastShotSummary = ""
    @State private var focusPulse = false
    @State private var okSince: TimeInterval = -1
    @State private var stickyCoachKey: CoachKey = .none
    @State private var stickyCoachAt: TimeInterval = -1
    @State private var stickyGuideText = ""
    @State private var latchedKey: CoachKey = .none
    @State private var latchedText = ""
    @State private var pendingKey: CoachKey = .none
    @State private var pendingCount = 0

    private var autoHoldMs: TimeInterval { Catalog.Framing.autoHoldSec }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            // Hole overlay lives inside PreviewUIView on the letterboxed video rect.
            CameraPreviewView(session: camera.session, holeFraction: camera.holeFraction) { point in
                camera.focus(atNormalized: point)
                withAnimation(.easeOut(duration: 0.25)) { focusPulse = true }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    withAnimation { focusPulse = false }
                }
            }
            .ignoresSafeArea()

            if focusPulse {
                Circle()
                    .stroke(AppTheme.accent, lineWidth: 2)
                    .frame(width: 72, height: 72)
                    .transition(.opacity)
            }

            VStack(spacing: 0) {
                Text(guideText)
                    .font(.system(.headline, design: .rounded).weight(.semibold))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity)
                    .background(guideOK ? AppTheme.ok : AppTheme.bad)

                HStack {
                    Text(liveMetaLine)
                        .font(.system(.caption, design: .monospaced).weight(.semibold))
                        .foregroundStyle(AppTheme.accent)
                    Spacer()
                    Text("çekim \(shotsThisSession)")
                        .font(.system(.caption, design: .rounded).weight(.bold))
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(AppTheme.chrome)

                VStack(spacing: 8) {
                    HStack(spacing: 10) {
                        Button(action: closeCapture) {
                            Image(systemName: "xmark")
                                .font(.body.weight(.semibold))
                                .frame(width: 44, height: 44)
                                .background(.ultraThinMaterial, in: Circle())
                        }
                        .accessibilityLabel("Kapat")
                        Spacer()
                        Button { camera.toggleAeMode() } label: {
                            Text(camera.autoPolicy ? "Oto" : "Manuel")
                                .font(.subheadline.weight(.semibold))
                                .frame(minWidth: 72, minHeight: 44)
                                .background(.ultraThinMaterial, in: Capsule())
                        }
                        if camera.torchAvailable {
                            Button { camera.toggleTorch() } label: {
                                Image(systemName: camera.torchOn ? "flashlight.on.fill" : "flashlight.off.fill")
                                    .font(.body.weight(.semibold))
                                    .frame(width: 44, height: 44)
                                    .background(.ultraThinMaterial, in: Circle())
                            }
                            .accessibilityLabel(camera.torchOn ? "Işık açık" : "Işık kapalı")
                        }
                    }
                    .padding(.horizontal, 12)
                    .foregroundStyle(.white)
                    if !camera.autoPolicy {
                        HStack(spacing: 8) {
                            chromeButton(camera.isoLabel, active: true) { camera.cycleIso() }
                            chromeButton(camera.expLabel, active: true) { camera.cycleExposure() }
                        }
                    }

                    if camera.torchAvailable, camera.torchOn {
                        HStack(spacing: 10) {
                            Image(systemName: "light.min")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(.white.opacity(0.85))
                            Slider(
                                value: Binding(
                                    get: { Double(camera.torchLevel) },
                                    set: { camera.setTorchLevel(Float($0)) }
                                ),
                                in: 0.1...1.0,
                                step: 0.05
                            )
                            .tint(AppTheme.accent)
                            Text(camera.torchLevelLabel)
                                .font(.system(.caption, design: .rounded).weight(.bold).monospacedDigit())
                                .foregroundStyle(AppTheme.accent)
                                .frame(width: 40, alignment: .trailing)
                            Image(systemName: "light.max")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(.white.opacity(0.85))
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .padding(.horizontal, 12)
                    }
                }
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity)
                .background(AppTheme.chrome.opacity(0.94))

                Spacer()

                Text(sessionBanner)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(8)
                    .frame(maxWidth: .infinity)
                    .background(AppTheme.primary.opacity(0.85))

                if !lastShotSummary.isEmpty {
                    Text(lastShotSummary)
                        .font(.footnote)
                        .foregroundStyle(.white)
                        .padding(8)
                        .frame(maxWidth: .infinity)
                        .background(Color.black.opacity(0.55))
                }

                HStack {
                    Spacer()
                    Button {
                        Task { await shoot(auto: false, force: true) }
                    } label: {
                        ZStack {
                            Circle().fill(.white).frame(width: 76, height: 76)
                            Circle().stroke(.gray.opacity(0.5), lineWidth: 3).frame(width: 66, height: 66)
                            if capturing || camera.isConfiguring {
                                ProgressView().tint(.black)
                            }
                        }
                    }
                    .disabled(capturing || camera.isConfiguring || !camera.isRunning || camera.authorizationDenied)
                    .accessibilityLabel("Manuel çekim (force)")
                    .opacity(guideOK ? 1 : 0.9)
                    Spacer()
                }
                .padding(.vertical, 18)
                .frame(maxWidth: .infinity)
                .background(AppTheme.chrome.opacity(0.96))
            }
        }
        .statusBarHidden(true)
        .task {
            UIApplication.shared.isIdleTimerDisabled = true
            guideText = "Kamera açılıyor…"
            guideOK = false
            await camera.requestAccessAndConfigure()
            if camera.authorizationDenied {
                guideText = "Kamera izni gerekli — Ayarlar → ChromaTex Lab"
                guideOK = false
            } else if let err = camera.errorMessage {
                guideText = err
                guideOK = false
            } else if camera.isRunning {
                guideText = "SARI kare = 100mm delik — siyah+yeşil halkayı hizalayın"
                guideOK = false
                camera.focus()
            } else {
                guideText = "Kamera hazır değil — tekrar deneyin"
                guideOK = false
            }
        }
        .onChange(of: camera.errorMessage) { _, newValue in
            if let newValue, !newValue.isEmpty {
                guideText = newValue
                guideOK = false
            }
        }
        // Drive hold/auto-shoot off every analyzed frame (message-only never re-fired on "OK delik net").
        .onChange(of: camera.lastFrameAt) { _, _ in
            guard !capturing else { return }
            onGuideTick()
        }
        .onChange(of: camera.latestExposure?.state) { _, _ in
            guard !capturing else { return }
            onGuideTick()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            coach.release()
            camera.stop()
        }
    }

    private var sessionBanner: String {
        "\(fabric.fabricState) · \(fabric.label) · \(fabric.fabricType) · \(fabric.fabricColor)"
    }

    private var liveMetaLine: String {
        let iso = camera.liveISO.map { "ISO \($0)" } ?? "ISO —"
        let exp = camera.liveExposureMs.map { String(format: "%.1f ms", $0) } ?? "— ms"
        let luma: String = {
            if let m = camera.latestExposure { return String(format: "L%.0f", m.measuredLuma) }
            return "L—"
        }()
        let optics = camera.opticsLabel.isEmpty ? "" : "  ·  \(camera.opticsLabel)"
        return "\(camera.liveResolution)  ·  \(iso)  ·  \(exp)  ·  \(luma)\(optics)"
    }

    private func chromeButton(
        _ title: String,
        active: Bool,
        accent: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(.subheadline, design: .rounded).weight(.bold))
                .foregroundStyle(accent && active ? AppTheme.primary : .white)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(
                    (accent && active ? AppTheme.accent : Color.white.opacity(active ? 0.22 : 0.14)),
                    in: Capsule()
                )
                .overlay(
                    Capsule()
                        .stroke(Color.white.opacity(active ? 0.55 : 0.28), lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }

    private func closeCapture() {
        coach.release()
        camera.stop()
        dismiss()
    }

    private func isExposureOkForShoot() -> Bool {
        guard let d = camera.latestExposure else { return !camera.autoPolicy }
        if !d.exposureOk { return false }
        return camera.autoPolicy ? d.state == .exposureOk : true
    }

    private func coachPriority(_ key: CoachKey) -> Int {
        switch key {
        case .dark, .bright, .noPaper: return 3
        case .uneven, .blur, .exposureAdjusting: return 2
        case .close, .far, .moveUp, .moveDown, .moveLeft, .moveRight,
             .skew, .skewUp, .skewDown, .skewLeft, .skewRight, .texture:
            return 1
        case .okHold, .capture: return 0
        case .none: return 0
        }
    }

    /// Speak a new phrase only after the same hint wins several frames.
    /// Otherwise the analyzer walks the checklist and TTS reads every line.
    private func presentCoach(text: String, key: CoachKey, force: Bool = false) {
        if key == latchedKey {
            pendingKey = key
            pendingCount = 0
            latchedText = text
            stickyGuideText = text
            stickyCoachKey = key
            guideText = text
            return
        }
        if key == pendingKey {
            pendingCount += 1
        } else {
            pendingKey = key
            pendingCount = 1
        }
        let ready = force && coachPriority(key) >= 3 || pendingCount >= 6
        guard ready else {
            if !latchedText.isEmpty { guideText = latchedText }
            return
        }
        latchedKey = key
        latchedText = text
        pendingCount = 0
        stickyCoachKey = key
        stickyCoachAt = ProcessInfo.processInfo.systemUptime
        stickyGuideText = text
        guideText = text
        if key != .none { coach.onGuide(key) }
    }

    private func onGuideTick() {
        guard let guide = camera.latestGuide else { return }
        let expDec = camera.latestExposure
        let exposureBlocks = !isExposureOkForShoot()
        let hardOk = FramingGuideAnalyzer.isHardQualityOk(guide)
        // Green only when soft consecutive OK + hard; hold may continue through brief hard flicker.
        let showGreen = guide.ok && hardOk && !exposureBlocks
        guideOK = showGreen

        if exposureBlocks,
           let expDec,
           expDec.state == .lightNeed || expDec.state == .lightTooMuch {
            okSince = -1
            camera.setAwbLock(false)
            guideOK = false
            presentCoach(text: "!  \(expDec.guidance)", key: expDec.coachKey, force: true)
            return
        }

        // Soft not ready → reset hold (YAKLAS / GOLGE / skew coach).
        if !guide.ok {
            okSince = -1
            camera.setAwbLock(false)
            let pozHint = (exposureBlocks && expDec?.state == .exposureAdjusting) ? " · poz…" : ""
            presentCoach(text: "\(guide.arrow)  \(guide.message)\(pozHint)", key: guide.coachKey)
            return
        }

        if exposureBlocks {
            okSince = -1
            camera.setAwbLock(false)
            presentCoach(
                text: "◎  \(expDec?.guidance ?? "Poz") — sabit tutun",
                key: expDec?.coachKey ?? .exposureAdjusting,
                force: true
            )
            return
        }

        // Do not start a new hold until hard passes once; mid-hold flicker does not reset.
        if !hardOk && okSince < 0 {
            let why = FramingGuideAnalyzer.hardFailReason(guide) ?? "KALITE DUSUK"
            presentCoach(text: "!  \(why) (manuel cekebilirsiniz)", key: .blur)
            return
        }

        let now = ProcessInfo.processInfo.systemUptime
        if okSince < 0 {
            okSince = now
            camera.setAwbLock(true)
        }
        let held = now - okSince
        let left = max(0, autoHoldMs - held)
        let leftMs = Int64(left * 1000)
        coach.onGuide(.okHold, holdLeftMs: leftMs)
        stickyCoachKey = .okHold
        stickyCoachAt = now
        if left > 0 {
            guideText = String(
                format: "●  SABIT  %.1fs  net=%.0f egim=%.0f",
                left, guide.rawSharpness, guide.rawSkewPx
            )
            stickyGuideText = guideText
            return
        }

        if !FramingGuideAnalyzer.isHardQualityOk(guide) || !isExposureOkForShoot() {
            okSince = -1
            camera.setAwbLock(false)
            if !isExposureOkForShoot() {
                presentCoach(
                    text: "!  \(expDec?.guidance ?? "Poz")",
                    key: expDec?.coachKey ?? .exposureAdjusting,
                    force: true
                )
            } else {
                let why = FramingGuideAnalyzer.hardFailReason(guide) ?? "EGIK/KALITE"
                presentCoach(text: "!  \(why) — duz tutun", key: .blur, force: true)
            }
            return
        }

        guideText = "●  CEKILIYOR…"
        stickyGuideText = guideText
        Task { await shoot(auto: true, force: false) }
    }

    private func shoot(auto: Bool, force: Bool) async {
        guard !capturing else { return }
        capturing = true
        defer {
            capturing = false
            okSince = -1
            stickyCoachKey = .none
            stickyCoachAt = -1
            latchedKey = .none
            latchedText = ""
            pendingKey = .none
            pendingCount = 0
            camera.setAwbLock(false)
        }
        do {
            let (jpeg, meta) = try await camera.captureJPEG(auto: auto, force: force)
            let url = store.newImageURL(purpose: purpose)
            try jpeg.write(to: url, options: .atomic)
            _ = try store.saveRecord(
                operatorName: operatorName,
                batchId: batchId,
                fabric: fabric,
                purpose: purpose,
                imageURL: url,
                meta: meta
            )
            shotsThisSession += 1
            coach.quiet()
            coach.onGuide(.capture)
            let px = meta.holeSidePx.map(String.init) ?? "?"
            let res = "\(meta.resolutionW ?? 0)×\(meta.resolutionH ?? 0)"
            let train = meta.framedOk && meta.exposureOk == true
            lastShotSummary =
                "Kaydedildi \(res) · hole≈\(px)px · framed=\(meta.framedOk) · exposure=\(meta.exposureOk ?? false) · train=\(train) · auto=\(auto)"
            guideText = train ? "Kayıt OK (train_eligible)" : "Kayıt OK (train_eligible=false)"
            guideOK = true
            UINotificationFeedbackGenerator().notificationOccurred(.success)

            // Auto: stop session and leave capture after a short success flash.
            if auto {
                try? await Task.sleep(nanoseconds: 450_000_000)
                closeCapture()
            }
        } catch {
            guideText = error.localizedDescription
            guideOK = false
            lastShotSummary = ""
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
    }
}
