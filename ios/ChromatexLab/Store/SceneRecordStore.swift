import Combine
import Foundation
import UIKit

struct PendingItem: Identifiable {
    let id: String
    let fileURL: URL
    let imageName: String
    let label: String
    let purpose: String
    let createdAt: String
}

struct GalleryItem: Identifiable {
    let id: String
    let recordURL: URL
    let imageURL: URL?
    let label: String
    let purpose: String
    let createdAt: String
    let synced: Bool
    let trainEligible: Bool
    let framedOk: Bool
}

struct StoreStats {
    var total: Int
    var pending: Int
    var uploaded: Int
    var sceneShots: Int
    var freeMb: Int
    var totalMb: Int
}

@MainActor
final class SceneRecordStore: ObservableObject {
    @Published private(set) var pendingCount: Int = 0
    @Published private(set) var totalCount: Int = 0
    @Published private(set) var uploadedCount: Int = 0
    @Published private(set) var pendingItems: [PendingItem] = []
    @Published private(set) var galleryItems: [GalleryItem] = []

    private let fm = FileManager.default

    private var rootDir: URL {
        let base = fm.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("ChromatexLab", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private var imagesDir: URL { subdir("images") }
    private var recordsDir: URL { subdir("records") }
    private var pendingDir: URL { subdir("pending_upload") }
    private var uploadedDir: URL { subdir("uploaded") }

    init() {
        refreshStats()
    }

    func refreshStats() {
        pendingItems = listPendingItems()
        pendingCount = pendingItems.count
        galleryItems = listGallery()
        let records = (try? fm.contentsOfDirectory(at: recordsDir, includingPropertiesForKeys: nil))?
            .filter { $0.pathExtension == "json" } ?? []
        totalCount = records.count
        uploadedCount = ((try? fm.contentsOfDirectory(at: uploadedDir, includingPropertiesForKeys: nil))?
            .filter { $0.pathExtension == "json" }.count) ?? 0
    }

    func stats() -> StoreStats {
        refreshStats()
        let (freeMb, totalMb) = diskSpaceMb()
        return StoreStats(
            total: totalCount,
            pending: pendingCount,
            uploaded: uploadedCount,
            sceneShots: galleryItems.count,
            freeMb: freeMb,
            totalMb: totalMb
        )
    }

    func listPendingItems() -> [PendingItem] {
        listPending().compactMap { url in
            guard
                let data = try? Data(contentsOf: url),
                let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { return nil }
            let capture = json["capture"] as? [String: Any]
            return PendingItem(
                id: url.deletingPathExtension().lastPathComponent,
                fileURL: url,
                imageName: capture?["image_file"] as? String ?? "—",
                label: json["label"] as? String ?? "—",
                purpose: capture?["purpose"] as? String ?? "—",
                createdAt: json["created_at"] as? String ?? ""
            )
        }
    }

    func listGallery() -> [GalleryItem] {
        let records = ((try? fm.contentsOfDirectory(at: recordsDir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [])
            .filter { $0.pathExtension == "json" }
            .sorted { a, b in
                let da = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let db = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return da > db
            }
        return records.compactMap { url in
            guard
                let data = try? Data(contentsOf: url),
                let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { return nil }
            let capture = json["capture"] as? [String: Any]
            let name = capture?["image_file"] as? String ?? ""
            let imageURL = name.isEmpty ? nil : imagesDir.appendingPathComponent(name)
            let exists = imageURL.map { fm.fileExists(atPath: $0.path) } ?? false
            let uploaded = json["uploaded"] as? Bool ?? false
            let synced = uploaded || fm.fileExists(atPath: uploadedDir.appendingPathComponent(url.lastPathComponent).path)
            return GalleryItem(
                id: url.deletingPathExtension().lastPathComponent,
                recordURL: url,
                imageURL: exists ? imageURL : nil,
                label: json["label"] as? String ?? "—",
                purpose: capture?["purpose"] as? String ?? "—",
                createdAt: json["created_at"] as? String ?? "",
                synced: synced,
                trainEligible: json["train_eligible"] as? Bool ?? false,
                framedOk: capture?["framed_ok"] as? Bool ?? false
            )
        }
    }

    func newImageURL(purpose: String = "scene_capture") -> URL {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let name = "\(purpose)_\(formatter.string(from: Date())).jpg"
        return imagesDir.appendingPathComponent(name)
    }

    @discardableResult
    func saveRecord(
        operatorName: String,
        batchId: String,
        fabric: FabricSession,
        purpose: String,
        imageURL: URL,
        meta: CaptureMeta
    ) throws -> URL {
        let id = UUID().uuidString.lowercased()
        let short = min(meta.resolutionW ?? 0, meta.resolutionH ?? 0)
        let holeSide = meta.holeSidePx ?? Int(Double(short) * Catalog.holeFillFrac)
        let pxPerMm = meta.pxPerMm ?? (short > 0 ? Double(holeSide) / Catalog.holeMm : nil)

        let createdAt: String = {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyy-MM-dd'T'HH:mm:ssZ"
            return f.string(from: Date())
        }()

        let trainEligible = meta.framedOk && meta.exposureOk == true
            && purpose == Catalog.Purpose.sceneCapture.rawValue

        var camera: [String: Any] = [:]
        if let v = meta.cameraId { camera["camera_id"] = v }
        if let v = meta.resolutionW { camera["resolution_w"] = v }
        if let v = meta.resolutionH { camera["resolution_h"] = v }
        if let v = meta.iso { camera["iso"] = v }
        if let v = meta.exposureNs { camera["exposure_ns"] = v }
        if let v = meta.focalLengthMm, v.isFinite { camera["focal_length_mm"] = v }

        var light: [String: Any] = [
            "torch_on": meta.torchOn,
            "torch_available": meta.torchAvailable,
        ]
        putFinite(&light, "luma_mean", meta.lumaMean)
        putFinite(&light, "edge_luma", meta.edgeLuma)
        putFinite(&light, "center_std", meta.centerStd)
        putFinite(&light, "sharpness", meta.sharpness)
        putFinite(&light, "skew_px", meta.skewPx)
        putFinite(&light, "measured_luma", meta.measuredLuma)
        putFinite(&light, "glare_ratio", meta.glareRatio)
        if let v = meta.exposurePolicyState { light["exposure_policy_state"] = v }
        if let v = meta.targetLumaBand { light["target_luma_band"] = v }
        if let v = meta.exposureOk { light["exposure_ok"] = v }
        if let v = meta.lightGuidance { light["light_guidance"] = v }

        var geometry: [String: Any] = [
            "fabric_window_mm": 100,
            "hole_side_px": holeSide,
        ]
        if let v = meta.geometryMode { geometry["mode"] = v }
        if let v = meta.paperEdgesVisible { geometry["paper_edges_visible"] = v }
        putFinite(&geometry, "window_fraction", meta.windowFraction)
        putFinite(&geometry, "px_per_mm", pxPerMm)
        putFinite(&geometry, "color_ring_score", meta.colorRingScore)
        putFinite(&geometry, "ring_left", meta.ringLeft)
        putFinite(&geometry, "ring_right", meta.ringRight)
        putFinite(&geometry, "ring_top", meta.ringTop)
        putFinite(&geometry, "ring_bottom", meta.ringBottom)

        var capture: [String: Any] = [
            "client_type": Catalog.clientType,
            "device": [
                "device_id": DeviceId.get(),
                "model": UIDevice.current.model,
                "ios_version": UIDevice.current.systemVersion,
                "app_version": Catalog.appVersion,
            ] as [String: Any],
            "camera": camera,
            "light": light,
            "framed_ok": meta.framedOk,
            "auto_captured": meta.autoCaptured,
            "geometry": geometry,
            "image_file": imageURL.lastPathComponent,
            "purpose": purpose,
        ]
        if let v = meta.guidance { capture["guidance_at_capture"] = v }
        if let v = meta.focusRegion { capture["focus_region"] = v }

        let record: [String: Any] = [
            "record_id": id,
            "created_at": createdAt,
            "operator": operatorName,
            "device_id": DeviceId.get(),
            "calibration_batch_id": batchId,
            "fabric_window_mm": 100,
            "train_eligible": trainEligible,
            "uploaded": false,
            "fabric_state": fabric.fabricState,
            "label": fabric.label,
            "fabric_type": fabric.fabricType,
            "fabric_color": fabric.fabricColor,
            "fabric_codes": [
                "orgu_kodu": fabric.orguKodu,
                "sip_no": fabric.sipNo,
                "isemri_no": fabric.isemriNo,
                "makina_no": fabric.makinaNo,
                "parti_no": fabric.partiNo,
            ] as [String: Any],
            "capture": capture,
        ]

        let out = recordsDir.appendingPathComponent("\(id).json")
        try writeJSON(record, to: out)

        var pending = record
        pending["local_image_path"] = imageURL.path
        try writeJSON(pending, to: pendingDir.appendingPathComponent("\(id).json"))

        refreshStats()
        return out
    }

    func listPending() -> [URL] {
        ((try? fm.contentsOfDirectory(at: pendingDir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [])
            .filter { $0.pathExtension == "json" }
            .sorted { a, b in
                let da = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let db = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return da > db
            }
    }

    func markUploaded(pendingFile: URL) throws {
        let id = pendingFile.deletingPathExtension().lastPathComponent
        let record = recordsDir.appendingPathComponent("\(id).json")
        if fm.fileExists(atPath: record.path),
           var json = try readJSON(record) {
            json["uploaded"] = true
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyy-MM-dd'T'HH:mm:ssZ"
            json["uploaded_at"] = f.string(from: Date())
            try writeJSON(json, to: record)
            try writeJSON(json, to: uploadedDir.appendingPathComponent("\(id).json"))
        }
        try? fm.removeItem(at: pendingFile)
        refreshStats()
    }

    @discardableResult
    func deleteGalleryItem(_ item: GalleryItem) -> Bool {
        try? fm.removeItem(at: item.recordURL)
        if let img = item.imageURL { try? fm.removeItem(at: img) }
        let pending = pendingDir.appendingPathComponent("\(item.id).json")
        try? fm.removeItem(at: pending)
        let uploaded = uploadedDir.appendingPathComponent("\(item.id).json")
        try? fm.removeItem(at: uploaded)
        refreshStats()
        return true
    }

    @discardableResult
    func deleteUploaded() -> Int {
        let files = ((try? fm.contentsOfDirectory(at: uploadedDir, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "json" }
        var n = 0
        for f in files {
            let id = f.deletingPathExtension().lastPathComponent
            if let data = try? Data(contentsOf: f),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let capture = json["capture"] as? [String: Any],
               let name = capture["image_file"] as? String {
                try? fm.removeItem(at: imagesDir.appendingPathComponent(name))
            }
            try? fm.removeItem(at: recordsDir.appendingPathComponent("\(id).json"))
            try? fm.removeItem(at: f)
            n += 1
        }
        refreshStats()
        return n
    }

    @discardableResult
    func clearAll() -> Int {
        var n = 0
        for dir in [imagesDir, recordsDir, pendingDir, uploadedDir] {
            let files = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
            for f in files {
                try? fm.removeItem(at: f)
                n += 1
            }
        }
        refreshStats()
        return n
    }

    private func diskSpaceMb() -> (Int, Int) {
        guard let attrs = try? fm.attributesOfFileSystem(forPath: rootDir.path),
              let free = attrs[.systemFreeSize] as? NSNumber,
              let total = attrs[.systemSize] as? NSNumber
        else { return (0, 0) }
        return (Int(free.int64Value / 1_048_576), Int(total.int64Value / 1_048_576))
    }

    private func putFinite(_ dict: inout [String: Any], _ key: String, _ value: Double?) {
        guard let value, value.isFinite else { return }
        dict[key] = value
    }

    private func subdir(_ name: String) -> URL {
        let dir = rootDir.appendingPathComponent(name, isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func writeJSON(_ object: [String: Any], to url: URL) throws {
        let data = try JSONSerialization.data(withJSONObject: sanitize(object), options: [.prettyPrinted, .sortedKeys])
        try data.write(to: url, options: .atomic)
    }

    private func readJSON(_ url: URL) throws -> [String: Any]? {
        let data = try Data(contentsOf: url)
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func sanitize(_ value: Any) -> Any {
        switch value {
        case let d as Double:
            return d.isFinite ? d : NSNull()
        case let f as Float:
            return f.isFinite ? f : NSNull()
        case let dict as [String: Any]:
            return dict.mapValues { sanitize($0) }
        case let arr as [Any]:
            return arr.map { sanitize($0) }
        default:
            return value
        }
    }
}
