import Foundation

enum UploadClient {
    struct Result {
        var ok: Int
        var fail: Int
        var message: String
    }

    static func ping(serverUrl: String) async -> Bool {
        let base = trimmed(serverUrl)
        guard isValidHttpBase(base), let url = URL(string: base + "/") else { return false }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.timeoutInterval = 4
        do {
            let (_, response) = try await URLSession.shared.data(for: req)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            return (200...299).contains(code)
        } catch {
            return false
        }
    }

    static func fetchActiveBatchId(serverUrl: String) async -> String? {
        let base = trimmed(serverUrl)
        guard isValidHttpBase(base), let url = URL(string: base + "/calibration/active") else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 5
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let bid = json["batch_id"] as? String
            else { return nil }
            let clean = bid.trimmingCharacters(in: .whitespacesAndNewlines)
            return clean.isEmpty ? nil : clean
        } catch {
            return nil
        }
    }

    static func uploadPending(store: SceneRecordStore, serverUrl: String) async -> Result {
        let base = trimmed(serverUrl)
        guard !base.isEmpty else {
            return Result(ok: 0, fail: 0, message: "Sunucu adresi boş")
        }
        guard isValidHttpBase(base) else {
            return Result(ok: 0, fail: 0, message: "Sunucu adresi http:// veya https:// ile başlamalı")
        }

        let pendingFiles = await MainActor.run { store.listPending() }
        var ok = 0
        var fail = 0
        var errors: [String] = []

        for pending in pendingFiles {
            do {
                let prepared = try await Task.detached(priority: .userInitiated) { () -> (Data, URL) in
                    let data = try Data(contentsOf: pending)
                    guard var body = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                        throw UploadError.badJSON
                    }
                    let imagePath = body["local_image_path"] as? String ?? ""
                    let imageURL = URL(fileURLWithPath: imagePath)
                    guard FileManager.default.fileExists(atPath: imageURL.path) else {
                        throw UploadError.missingImage
                    }
                    body.removeValue(forKey: "local_image_path")
                    let metaData = try JSONSerialization.data(withJSONObject: body, options: [])
                    return (metaData, imageURL)
                }.value

                guard let uploadUrl = URL(string: base + "/upload") else {
                    throw UploadError.invalidServerURL
                }
                try await postMultipart(
                    url: uploadUrl,
                    recordJSON: prepared.0,
                    imageURL: prepared.1
                )
                try await MainActor.run {
                    try store.markUploaded(pendingFile: pending)
                }
                ok += 1
            } catch {
                fail += 1
                errors.append("\(pending.lastPathComponent): \(error.localizedDescription.prefix(160))")
            }
        }

        let msg = errors.isEmpty ? "Tamam" : errors.prefix(3).joined(separator: " | ")
        return Result(ok: ok, fail: fail, message: msg)
    }

    private static func isValidHttpBase(_ base: String) -> Bool {
        base.hasPrefix("http://") || base.hasPrefix("https://")
    }

    private static func trimmed(_ url: String) -> String {
        var s = url.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    private static func postMultipart(url: URL, recordJSON: Data, imageURL: URL) async throws {
        let boundary = "----ChromatexBoundary\(Int(Date().timeIntervalSince1970))"
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 120

        // Stream body via a temporary file off main actor to keep UI responsive & memory low
        let tempFile = try await Task.detached(priority: .userInitiated) { () -> URL in
            let tempDir = FileManager.default.temporaryDirectory
            let tempFile = tempDir.appendingPathComponent("upload_\(UUID().uuidString).tmp")
            FileManager.default.createFile(atPath: tempFile.path, contents: nil)
            guard let handle = try? FileHandle(forWritingTo: tempFile) else {
                throw UploadError.fileIO
            }
            defer { try? handle.close() }

            func writeString(_ s: String) throws {
                if let d = s.data(using: .utf8) { try handle.write(contentsOf: d) }
            }

            try writeString("--\(boundary)\r\n")
            try writeString("Content-Disposition: form-data; name=\"record_json\"\r\n")
            try writeString("Content-Type: application/json; charset=utf-8\r\n\r\n")
            try handle.write(contentsOf: recordJSON)
            try writeString("\r\n")

            try writeString("--\(boundary)\r\n")
            try writeString("Content-Disposition: form-data; name=\"image\"; filename=\"\(imageURL.lastPathComponent)\"\r\n")
            try writeString("Content-Type: image/jpeg\r\n\r\n")

            let imageHandle = try FileHandle(forReadingFrom: imageURL)
            defer { try? imageHandle.close() }
            while true {
                let chunk = imageHandle.readData(ofLength: 64 * 1024)
                if chunk.isEmpty { break }
                try handle.write(contentsOf: chunk)
            }

            try writeString("\r\n--\(boundary)--\r\n")
            return tempFile
        }.value
        defer { try? FileManager.default.removeItem(at: tempFile) }

        let (data, response) = try await URLSession.shared.upload(for: req, fromFile: tempFile)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code) else {
            let err = String(data: data, encoding: .utf8)?.prefix(300) ?? ""
            throw UploadError.http(code, String(err))
        }
    }

    enum UploadError: LocalizedError {
        case badJSON
        case invalidServerURL
        case missingImage
        case fileIO
        case http(Int, String)
        var errorDescription: String? {
            switch self {
            case .badJSON: return "meta JSON bozuk"
            case .invalidServerURL: return "Geçersiz sunucu adresi"
            case .missingImage: return "görüntü yok"
            case .fileIO: return "Yükleme dosyası oluşturulamadı"
            case .http(let c, let b): return "HTTP \(c) \(b)"
            }
        }
    }
}
