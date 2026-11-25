import AVFoundation
import Combine
import ImageIO
import UIKit

@MainActor
final class CameraController: NSObject, ObservableObject {
    @Published var isRunning = false
    @Published var isConfiguring = false
    @Published var torchOn = false
    @Published var torchAvailable = false
    /// 0.1…1.0 — applied while torch is on (device-clamped).
    @Published var torchLevel: Float = 0.7
    @Published var torchLevelLabel = "70%"
    @Published var errorMessage: String?
    @Published var authorizationDenied = false
    @Published var liveISO: Int?
    @Published var liveExposureMs: Double?
    @Published var liveResolution: String = "—"
    /// Yellow square ÷ short side, from the active format FOV.
    @Published var holeFraction: Double = CameraOptics.compositionFraction
    @Published var opticsLabel: String = ""
    @Published var latestGuide: FrameGuide?
    @Published var latestExposure: ExposureDecision?
    @Published var autoPolicy = true
    @Published var aeModeLabel = "AUTO"
    @Published var isoLabel = "ISO —"
    @Published var expLabel = "Exp —"
    @Published var analyzing = false
    /// Last analyzed frame uptime — CaptureView hold/auto ticks even when guide.message is unchanged.
    @Published private(set) var lastFrameAt: TimeInterval = 0

    let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "org.chromatex.lab.session")
    private let analysisQueue = DispatchQueue(label: "org.chromatex.lab.analysis", qos: .userInitiated)
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private var device: AVCaptureDevice?
    private var photoContinuation: CheckedContinuation<Data, Error>?
    private var meteringTimer: Timer?

    private let analysisEngine = AnalysisEngine()
    private let exposurePolicy = ExposurePolicyController()
    private var isoPresets: [Int] = []
    private var expPresetsNs: [Int64] = []
    private var isoIndex = 0
    private var expIndex = 0
    private var supportsManual = false
    private var captureGuideSnap: FrameGuide?
    private var captureExposureSnap: ExposureDecision?
    private var awbLocked = false

    var lastGuideForCapture: FrameGuide? { captureGuideSnap ?? latestGuide }
    var lastExposureForCapture: ExposureDecision? { captureExposureSnap ?? latestExposure }

    func requestAccessAndConfigure() async {
        errorMessage = nil
        isConfiguring = true
        defer { isConfiguring = false }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            await configureSession()
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            if granted {
                await configureSession()
            } else {
                authorizationDenied = true
            }
        default:
            authorizationDenied = true
        }
        // configureSession resumes before MainActor publishes isRunning — wait briefly.
        if !authorizationDenied, errorMessage == nil, !isRunning {
            for _ in 0..<40 {
                if isRunning || errorMessage != nil { break }
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
        // If the session never produced frames (common on Simulator), surface it.
        if isRunning, lastFrameAt == 0 {
            for _ in 0..<25 {
                if lastFrameAt > 0 || errorMessage != nil { break }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            if lastFrameAt == 0, errorMessage == nil {
                #if targetEnvironment(simulator)
                errorMessage = "Simülatörde kamera akışı yok — gerçek iPhone’da deneyin"
                #else
                errorMessage = "Kamera görüntüsü gelmiyor — uygulamayı kapatıp yeniden açın"
                #endif
            }
        }
    }

    func stop() {
        meteringTimer?.invalidate()
        meteringTimer = nil
        if torchOn { setTorch(on: false) }
        sessionQueue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
            Task { @MainActor in self.isRunning = false }
        }
    }

    func toggleTorch() {
        setTorch(on: !torchOn)
    }

    func setTorch(on: Bool) {
        guard let device, device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            if on {
                let level = clampedTorchLevel(torchLevel)
                try device.setTorchModeOn(level: level)
                torchOn = true
                torchLevel = level
                refreshTorchLabel()
            } else {
                device.torchMode = .off
                torchOn = false
            }
            device.unlockForConfiguration()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Live torch intensity while the lamp is on (and stores level for next turn-on).
    func setTorchLevel(_ raw: Float) {
        guard let device, device.hasTorch else { return }
        let level = clampedTorchLevel(raw)
        torchLevel = level
        refreshTorchLabel()
        guard torchOn else { return }
        do {
            try device.lockForConfiguration()
            try device.setTorchModeOn(level: level)
            device.unlockForConfiguration()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func clampedTorchLevel(_ raw: Float) -> Float {
        // setTorchModeOn requires > 0; max is a class property on AVCaptureDevice.
        let lo: Float = 0.1
        let hi = min(1.0, AVCaptureDevice.maxAvailableTorchLevel)
        return min(hi, max(lo, raw))
    }

    private func refreshTorchLabel() {
        torchLevelLabel = "\(Int((torchLevel * 100).rounded()))%"
    }

    func toggleAeMode() {
        autoPolicy.toggle()
        aeModeLabel = autoPolicy ? "AUTO" : "MANUAL"
        if !autoPolicy, supportsManual {
            applyManualExposure(
                iso: isoPresets.indices.contains(isoIndex) ? isoPresets[isoIndex] : nil,
                expNs: expPresetsNs.indices.contains(expIndex) ? expPresetsNs[expIndex] : nil
            )
        } else if autoPolicy {
            // Leave custom exposure; policy will retarget.
        }
        refreshAeLabels()
    }

    func cycleIso() {
        guard !autoPolicy, supportsManual, !isoPresets.isEmpty else { return }
        isoIndex = (isoIndex + 1) % isoPresets.count
        applyManualExposure(iso: isoPresets[isoIndex], expNs: expPresetsNs.indices.contains(expIndex) ? expPresetsNs[expIndex] : nil)
        refreshAeLabels()
    }

    func cycleExposure() {
        guard !autoPolicy, supportsManual, !expPresetsNs.isEmpty else { return }
        expIndex = (expIndex + 1) % expPresetsNs.count
        applyManualExposure(iso: isoPresets.indices.contains(isoIndex) ? isoPresets[isoIndex] : nil, expNs: expPresetsNs[expIndex])
        refreshAeLabels()
    }

    /// Focus/expose at normalized preview point (0…1). Default = frame center (hole).
    func focus(atNormalized point: CGPoint = CGPoint(x: 0.5, y: 0.5)) {
        guard let device else { return }
        let clamped = CGPoint(
            x: min(max(point.x, 0), 1),
            y: min(max(point.y, 0), 1)
        )
        do {
            try device.lockForConfiguration()
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = clamped
                if device.isFocusModeSupported(.continuousAutoFocus) {
                    device.focusMode = .continuousAutoFocus
                } else if device.isFocusModeSupported(.autoFocus) {
                    device.focusMode = .autoFocus
                }
            }
            if !device.isExposureModeSupported(.custom),
               device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = clamped
                if device.isExposureModeSupported(.continuousAutoExposure) {
                    device.exposureMode = .continuousAutoExposure
                }
            }
            device.unlockForConfiguration()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func setAwbLock(_ locked: Bool) {
        awbLocked = locked
        guard let device else { return }
        do {
            try device.lockForConfiguration()
            if device.isWhiteBalanceModeSupported(.locked) {
                device.whiteBalanceMode = locked ? .locked : .continuousAutoWhiteBalance
            }
            device.unlockForConfiguration()
        } catch {
            // ignore lock failures
        }
    }

    func snapGuideForCapture() {
        captureGuideSnap = latestGuide
        captureExposureSnap = latestExposure
    }

    func clearCaptureSnap() {
        captureGuideSnap = nil
        captureExposureSnap = nil
    }

    func captureJPEG(auto: Bool, force: Bool) async throws -> (Data, CaptureMeta) {
        guard session.isRunning else { throw CameraError.notReady }
        snapGuideForCapture()
        let settings = AVCapturePhotoSettings()
        settings.flashMode = .off
        if #available(iOS 16.0, *) {
            settings.photoQualityPrioritization = .quality
        }

        let data: Data = try await withCheckedThrowingContinuation { cont in
            self.photoContinuation = cont
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }

        let guide = lastGuideForCapture
        let exposure = lastExposureForCapture
        clearCaptureSnap()

        var iso: Int?
        var expNs: Int64?
        var resW: Int?
        var resH: Int?
        if let device {
            iso = Int(device.iso.rounded())
            let seconds = CMTimeGetSeconds(device.exposureDuration)
            if seconds.isFinite {
                expNs = Int64((seconds * 1_000_000_000).rounded())
            }
        }
        if let image = UIImage(data: data) {
            resW = Int(image.size.width * image.scale)
            resH = Int(image.size.height * image.scale)
        } else if let device {
            let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
            resW = Int(dims.width)
            resH = Int(dims.height)
        }

        let qualityPass: Bool = {
            guard let g = guide else { return false }
            return g.ok && FramingGuideAnalyzer.isHardQualityOk(g)
        }()
        let framedOk = qualityPass
        let exposureOk: Bool = {
            guard let d = exposure else { return false }
            if !d.exposureOk { return false }
            return autoPolicy ? d.state == .exposureOk : true
        }()

        var meta: CaptureMeta
        if let guide {
            meta = CaptureMeta.from(
                guide: guide,
                exposure: exposure,
                auto: auto,
                torchOn: torchOn,
                torchAvailable: torchAvailable,
                cameraId: device?.uniqueID,
                iso: iso,
                expNs: expNs,
                resW: resW,
                resH: resH
            )
            meta.framedOk = framedOk
            meta.exposureOk = exposureOk
            if force && !qualityPass {
                meta.guidance = "FORCE|\(guide.message)|mode=\(guide.geometryMode)|net=\(String(format: "%.1f", guide.rawSharpness))|egim=\(String(format: "%.1f", guide.rawSkewPx))"
                meta.geometryMode = "force_\(guide.geometryMode)"
            }
            // Scale hole px from analysis → capture resolution when possible
            if let resW, let resH, guide.analysisShortPx > 0, guide.cornersSeated, guide.pxPerMm > 1 {
                let scale = Double(min(resW, resH)) / Double(guide.analysisShortPx)
                let ppm = guide.pxPerMm * scale
                meta.pxPerMm = ppm
                meta.holeSidePx = Int(ppm * Catalog.holeMm)
            } else if let resW, let resH {
                let short = min(resW, resH)
                meta.holeSidePx = Int(Double(short) * holeFraction)
                meta.pxPerMm = Double(meta.holeSidePx ?? 0) / Catalog.holeMm
            }
        } else {
            meta = CaptureMeta()
            meta.iso = iso
            meta.exposureNs = expNs
            meta.resolutionW = resW
            meta.resolutionH = resH
            meta.cameraId = device?.uniqueID
            meta.torchOn = torchOn
            meta.torchAvailable = torchAvailable
            meta.framedOk = false
            meta.autoCaptured = auto
            meta.exposureOk = false
            meta.guidance = force ? "FORCE|no_guide" : "no_guide"
            meta.windowFraction = holeFraction
            if let resW, let resH {
                let short = min(resW, resH)
                meta.holeSidePx = Int(Double(short) * holeFraction)
                meta.pxPerMm = Double(meta.holeSidePx ?? 0) / Catalog.holeMm
            }
        }
        if let src = CGImageSourceCreateWithData(data as CFData, nil),
           let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
           let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any],
           let fl = exif[kCGImagePropertyExifFocalLength] as? Double {
            meta.focalLengthMm = fl
        }
        meta.autoCaptured = auto
        return (data, meta)
    }

    private func configureSession() async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            sessionQueue.async { [weak self] in
                guard let self else {
                    cont.resume()
                    return
                }
                var resumed = false
                let resumeOnce: () -> Void = {
                    guard !resumed else { return }
                    resumed = true
                    cont.resume()
                }

                self.session.beginConfiguration()
                self.session.sessionPreset = .photo

                self.session.inputs.forEach { self.session.removeInput($0) }
                self.session.outputs.forEach { self.session.removeOutput($0) }

                guard let device = Self.bestBackCamera() else {
                    self.session.commitConfiguration()
                    Task { @MainActor in self.errorMessage = "Kamera bulunamadı" }
                    resumeOnce()
                    return
                }
                self.device = device

                do {
                    try device.lockForConfiguration()
                    if device.isFocusModeSupported(.continuousAutoFocus) {
                        device.focusMode = .continuousAutoFocus
                    }
                    if device.isExposureModeSupported(.continuousAutoExposure) {
                        device.exposureMode = .continuousAutoExposure
                    }
                    if device.isFocusPointOfInterestSupported {
                        device.focusPointOfInterest = CGPoint(x: 0.5, y: 0.5)
                    }
                    if device.isExposurePointOfInterestSupported {
                        device.exposurePointOfInterest = CGPoint(x: 0.5, y: 0.5)
                    }
                    if device.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) {
                        device.whiteBalanceMode = .continuousAutoWhiteBalance
                    }
                    device.unlockForConfiguration()

                    let input = try AVCaptureDeviceInput(device: device)
                    if self.session.canAddInput(input) { self.session.addInput(input) }
                    if self.session.canAddOutput(self.photoOutput) {
                        self.session.addOutput(self.photoOutput)
                        if #available(iOS 16.0, *) {
                            self.photoOutput.maxPhotoQualityPrioritization = .quality
                        }
                    }
                    self.videoOutput.alwaysDiscardsLateVideoFrames = true
                    self.videoOutput.videoSettings = [
                        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                    ]
                    self.videoOutput.setSampleBufferDelegate(self, queue: self.analysisQueue)
                    if self.session.canAddOutput(self.videoOutput) {
                        self.session.addOutput(self.videoOutput)
                        if let conn = self.videoOutput.connection(with: .video) {
                            if #available(iOS 17.0, *), conn.isVideoRotationAngleSupported(90) {
                                conn.videoRotationAngle = 90
                            } else if conn.isVideoOrientationSupported {
                                conn.videoOrientation = .portrait
                            }
                        }
                    }
                    self.session.commitConfiguration()

                    let isoMin = Int(device.activeFormat.minISO.rounded())
                    let isoMax = Int(device.activeFormat.maxISO.rounded())
                    let expMin = Int64((CMTimeGetSeconds(device.activeFormat.minExposureDuration) * 1e9).rounded())
                    let expMax = Int64((CMTimeGetSeconds(device.activeFormat.maxExposureDuration) * 1e9).rounded())
                    let presetsIso = Self.buildIsoPresets(min: isoMin, max: isoMax)
                    let presetsExp = Self.buildExpPresets(minNs: expMin, maxNs: expMax)
                    let manual = device.isExposureModeSupported(.custom)
                    let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
                    let fov = Double(device.activeFormat.videoFieldOfView)
                    let zoom = Double(device.videoZoomFactor)
                    let fraction = CameraOptics.windowFraction(
                        horizontalFovDeg: fov,
                        formatWidth: Int(dims.width),
                        formatHeight: Int(dims.height),
                        zoom: zoom
                    )
                    let optics = String(
                        format: "FOV %.0f° · sarı %.0f%% · %.0f cm",
                        fov,
                        fraction * 100,
                        CameraOptics.referenceDistanceMm / 10
                    )

                    // Unblock UI BEFORE startRunning — that call can stall for seconds
                    // (Simulator / busy camera) and previously froze CaptureView entirely.
                    // Mark running optimistically so CaptureView does not race past a false isRunning.
                    Task { @MainActor in
                        self.isoPresets = presetsIso
                        self.expPresetsNs = presetsExp
                        self.supportsManual = manual
                        self.exposurePolicy.updateDeviceLimits(
                            isoMin: isoMin, isoMax: isoMax, expMinNs: expMin, expMaxNs: expMax
                        )
                        if let prefer = ExposurePolicyController.nearest(presetsIso, 160) {
                            self.isoIndex = presetsIso.firstIndex(of: prefer) ?? 0
                        }
                        if let preferE = ExposurePolicyController.nearestLong(presetsExp, 20_000_000) {
                            self.expIndex = presetsExp.firstIndex(of: preferE) ?? 0
                        }
                        self.torchAvailable = device.hasTorch
                        self.liveResolution = "\(dims.width)×\(dims.height)"
                        self.holeFraction = fraction
                        self.opticsLabel = optics
                        self.analysisEngine.setWindowFraction(fraction)
                        self.isRunning = true
                        self.refreshLiveStats()
                        self.refreshAeLabels()
                        self.startMetering()
                        self.analyzing = true
                    }
                    resumeOnce()

                    self.session.startRunning()

                    Task { @MainActor in
                        let running = self.session.isRunning
                        self.isRunning = running
                        self.refreshLiveStats()
                        self.refreshAeLabels()
                        if !running {
                            self.errorMessage = "Kamera oturumu başlamadı"
                        }
                    }
                } catch {
                    self.session.commitConfiguration()
                    Task { @MainActor in self.errorMessage = error.localizedDescription }
                    resumeOnce()
                }
            }
        }
    }

    private func startMetering() {
        meteringTimer?.invalidate()
        meteringTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshLiveStats() }
        }
    }

    private func refreshLiveStats() {
        guard let device else { return }
        liveISO = Int(device.iso.rounded())
        let seconds = CMTimeGetSeconds(device.exposureDuration)
        if seconds.isFinite {
            liveExposureMs = seconds * 1000.0
        }
    }

    private func refreshAeLabels() {
        aeModeLabel = autoPolicy ? "AUTO" : "MANUAL"
        if isoPresets.indices.contains(isoIndex) {
            isoLabel = "ISO \(isoPresets[isoIndex])"
        } else if let liveISO {
            isoLabel = "ISO \(liveISO)"
        } else {
            isoLabel = "ISO —"
        }
        if expPresetsNs.indices.contains(expIndex) {
            let ms = Double(expPresetsNs[expIndex]) / 1_000_000.0
            expLabel = String(format: "%.0fms", ms)
        } else if let liveExposureMs {
            expLabel = String(format: "%.1fms", liveExposureMs)
        } else {
            expLabel = "Exp —"
        }
    }

    nonisolated private func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        // AVFoundation videoOutput connection already rotates the pixel buffer to portrait
        // (videoRotationAngle = 90 / .portrait), so residual rotation to screen coords is 0°.
        let rotationDegrees = 0
        guard let guide = analysisEngine.analyze(sampleBuffer: sampleBuffer, rotationDegrees: rotationDegrees) else { return }
        Task { @MainActor in
            self.handleGuide(guide)
        }
    }

    private func handleGuide(_ guide: FrameGuide) {
        latestGuide = guide
        lastFrameAt = ProcessInfo.processInfo.systemUptime
        let now = Int64(ProcessInfo.processInfo.systemUptime * 1000)
        let iso = liveISO
        let expNs: Int64? = {
            guard let ms = liveExposureMs else { return nil }
            return Int64(ms * 1_000_000)
        }()
        let decision = exposurePolicy.evaluate(
            luma: guide.lightEma > 0 ? guide.lightEma : guide.luma,
            iso: iso,
            expNs: expNs,
            isoPresets: isoPresets,
            expPresetsNs: expPresetsNs,
            supportsManual: supportsManual,
            nowElapsed: now,
            apply: autoPolicy
        )
        latestExposure = decision
        if autoPolicy {
            applyPolicyDecision(decision)
        }
    }

    private func applyPolicyDecision(_ d: ExposureDecision) {
        guard supportsManual else { return }
        if let wantIso = d.wantIso, let wantExp = d.wantExpNs {
            if let idx = isoPresets.firstIndex(of: wantIso)
                ?? isoPresets.indices.min(by: { abs(isoPresets[$0] - wantIso) < abs(isoPresets[$1] - wantIso) }) {
                isoIndex = idx
            }
            if let idx = expPresetsNs.firstIndex(of: wantExp)
                ?? expPresetsNs.indices.min(by: { abs(expPresetsNs[$0] - wantExp) < abs(expPresetsNs[$1] - wantExp) }) {
                expIndex = idx
            }
            applyManualExposure(iso: wantIso, expNs: wantExp)
            refreshAeLabels()
        } else if let wantIso = d.wantIso {
            if let idx = isoPresets.firstIndex(of: wantIso)
                ?? isoPresets.indices.min(by: { abs(isoPresets[$0] - wantIso) < abs(isoPresets[$1] - wantIso) }) {
                isoIndex = idx
            }
            applyManualExposure(iso: wantIso, expNs: expPresetsNs.indices.contains(expIndex) ? expPresetsNs[expIndex] : nil)
            refreshAeLabels()
        } else if let wantExp = d.wantExpNs {
            if let idx = expPresetsNs.firstIndex(of: wantExp)
                ?? expPresetsNs.indices.min(by: { abs(expPresetsNs[$0] - wantExp) < abs(expPresetsNs[$1] - wantExp) }) {
                expIndex = idx
            }
            applyManualExposure(iso: isoPresets.indices.contains(isoIndex) ? isoPresets[isoIndex] : nil, expNs: wantExp)
            refreshAeLabels()
        }
    }

    private func applyManualExposure(iso: Int?, expNs: Int64?) {
        guard let device, supportsManual else { return }
        let isoVal = Float(iso ?? Int(device.iso.rounded()))
        let clampedIso = min(max(isoVal, device.activeFormat.minISO), device.activeFormat.maxISO)
        let seconds: Double
        if let expNs {
            seconds = Double(expNs) / 1_000_000_000.0
        } else {
            seconds = CMTimeGetSeconds(device.exposureDuration)
        }
        let minS = CMTimeGetSeconds(device.activeFormat.minExposureDuration)
        let maxS = CMTimeGetSeconds(device.activeFormat.maxExposureDuration)
        let clampedS = min(max(seconds, minS), maxS)
        let duration = CMTimeMake(value: Int64(clampedS * 1_000_000_000), timescale: 1_000_000_000)
        do {
            try device.lockForConfiguration()
            device.exposureMode = .custom
            device.setExposureModeCustom(duration: duration, iso: clampedIso) { _ in }
            device.unlockForConfiguration()
        } catch {
            // keep prior exposure
        }
    }

    private static func bestBackCamera() -> AVCaptureDevice? {
        if let dual = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
            return dual
        }
        return AVCaptureDevice.default(for: .video)
    }

    private static func buildIsoPresets(min: Int, max: Int) -> [Int] {
        let candidates = [
            50, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
            1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
        ]
        let inRange = candidates.filter { $0 >= min && $0 <= max }
        if inRange.isEmpty { return Array(Set([min, max])).sorted() }
        return Array(Set([min] + inRange + [max])).sorted()
    }

    private static func buildExpPresets(minNs: Int64, maxNs: Int64) -> [Int64] {
        let candidatesMs = [0.5, 1.0, 2.0, 4.0, 8.0, 10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 66.0, 100.0]
        let fromMs = candidatesMs.map { Int64($0 * 1_000_000.0) }.filter { $0 >= minNs && $0 <= maxNs }
        return Array(Set([minNs] + fromMs + [maxNs])).sorted()
    }

    enum CameraError: LocalizedError {
        case notReady
        var errorDescription: String? { "Kamera hazır değil" }
    }
}

/// Off-main framing analysis — only touched from the video analysis queue.
private final class AnalysisEngine: @unchecked Sendable {
    private let analyzer = FramingGuideAnalyzer()
    private let lock = NSLock()
    private var busy = false

    func setWindowFraction(_ value: Double) {
        lock.lock()
        analyzer.windowFraction = value
        lock.unlock()
    }

    func analyze(sampleBuffer: CMSampleBuffer, rotationDegrees: Int = 0) -> FrameGuide? {
        lock.lock()
        if busy {
            lock.unlock()
            return nil
        }
        busy = true
        lock.unlock()
        defer {
            lock.lock()
            busy = false
            lock.unlock()
        }

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        guard let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else { return nil }

        let short = min(width, height)
        let targetShort = min(480, short)
        let scale = Double(targetShort) / Double(short)
        let outW = max(1, Int((Double(width) * scale).rounded()))
        let outH = max(1, Int((Double(height) * scale).rounded()))
        var luma = [UInt8](repeating: 0, count: outW * outH)
        let src = base.assumingMemoryBound(to: UInt8.self)
        for y in 0..<outH {
            let sy = min(height - 1, Int((Double(y) / scale).rounded()))
            let row = src.advanced(by: sy * bytesPerRow)
            for x in 0..<outW {
                let sx = min(width - 1, Int((Double(x) / scale).rounded()))
                luma[y * outW + x] = row[sx]
            }
        }
        return analyzer.analyze(luma: luma, width: outW, height: outH, rotationDegrees: rotationDegrees)
    }
}

extension CameraController: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        Task { @MainActor in
            if let error {
                photoContinuation?.resume(throwing: error)
                photoContinuation = nil
                return
            }
            guard let data = photo.fileDataRepresentation() else {
                photoContinuation?.resume(throwing: CameraError.notReady)
                photoContinuation = nil
                return
            }
            photoContinuation?.resume(returning: data)
            photoContinuation = nil
        }
    }
}

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    nonisolated func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        processSampleBuffer(sampleBuffer, from: connection)
    }
}
