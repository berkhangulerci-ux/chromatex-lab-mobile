import Foundation

struct FabricSession: Hashable {
    var fabricState: String
    var label: String
    var fabricType: String
    var fabricColor: String
    var orguKodu: String
    var sipNo: String
    var isemriNo: String
    var makinaNo: String
    var partiNo: String
}

struct CaptureMeta {
    var iso: Int?
    var exposureNs: Int64?
    var focalLengthMm: Double?
    var resolutionW: Int?
    var resolutionH: Int?
    var cameraId: String?
    var lumaMean: Double?
    var edgeLuma: Double?
    var centerStd: Double?
    var sharpness: Double?
    var skewPx: Double?
    var guidance: String?
    var framedOk: Bool = false
    var autoCaptured: Bool = false
    var geometryMode: String? = "window_priority"
    var paperEdgesVisible: Int?
    var colorRingScore: Double?
    var windowFraction: Double? = Catalog.holeFillFrac
    var holeSidePx: Int?
    var pxPerMm: Double?
    var exposurePolicyState: String?
    var targetLumaBand: String?
    var measuredLuma: Double?
    var exposureOk: Bool? = false
    var lightGuidance: String?
    var ringLeft: Double?
    var ringRight: Double?
    var ringTop: Double?
    var ringBottom: Double?
    var glareRatio: Double?
    var focusRegion: String? = "center_point"
    var torchOn: Bool = false
    var torchAvailable: Bool = false

    static func from(guide: FrameGuide, exposure: ExposureDecision?, auto: Bool, torchOn: Bool, torchAvailable: Bool, cameraId: String?, iso: Int?, expNs: Int64?, resW: Int?, resH: Int?) -> CaptureMeta {
        var m = CaptureMeta()
        m.iso = iso
        m.exposureNs = expNs
        m.resolutionW = resW
        m.resolutionH = resH
        m.cameraId = cameraId
        m.lumaMean = guide.luma
        m.edgeLuma = guide.edgeLuma
        m.centerStd = guide.centerStd
        m.sharpness = guide.sharpness
        m.skewPx = guide.skewPx
        m.guidance = guide.message
        m.framedOk = guide.ok && FramingGuideAnalyzer.isHardQualityOk(guide)
        m.autoCaptured = auto
        m.geometryMode = guide.geometryMode
        m.paperEdgesVisible = guide.paperEdgesVisible
        m.colorRingScore = guide.colorRingScore
        m.windowFraction = guide.windowFraction
        m.holeSidePx = guide.holeSidePx
        m.pxPerMm = guide.pxPerMm
        m.exposurePolicyState = exposure?.state.rawValue
        m.targetLumaBand = exposure?.targetLumaBand
        m.measuredLuma = exposure?.measuredLuma ?? guide.lightEma
        m.exposureOk = exposure?.exposureOk
        m.lightGuidance = exposure?.guidance
        m.ringLeft = guide.ringLeft
        m.ringRight = guide.ringRight
        m.ringTop = guide.ringTop
        m.ringBottom = guide.ringBottom
        m.glareRatio = guide.glareRatio
        m.torchOn = torchOn
        m.torchAvailable = torchAvailable
        return m
    }
}
