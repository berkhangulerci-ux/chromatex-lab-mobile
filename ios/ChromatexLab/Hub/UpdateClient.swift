import Foundation
import UIKit

struct UpdateInfo {
    var versionCode: Int
    var versionName: String
    var notes: String
    var downloadUrl: String
    var otaSupported: Bool
    var hasUpdate: Bool
}

enum UpdateClient {
    private static func hardwareModelIdentifier() -> String {
        var sysinfo = utsname()
        uname(&sysinfo)
        let id = withUnsafePointer(to: &sysinfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) {
                String(validatingUTF8: $0)
            }
        }
        return id ?? UIDevice.current.model
    }

    static func registerDevice(
        serverUrl: String,
        operatorName: String = "",
        batchId: String = ""
    ) async -> Bool {
        let base = trimmed(serverUrl)
        guard (base.hasPrefix("http://") || base.hasPrefix("https://")),
              let url = URL(string: base + "/devices/register") else { return false }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 8
        let payload: [String: Any] = [
            "device_id": DeviceId.get(),
            "client_type": Catalog.clientType,
            "model": "\(UIDevice.current.model) (\(hardwareModelIdentifier()))",
            "os_version": UIDevice.current.systemVersion,
            "app_version": Catalog.appVersion,
            "app_version_code": Catalog.appVersionCode,
            "operator": operatorName,
            "batch_id": batchId,
        ]
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        do {
            let (_, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            return (200...299).contains(code)
        } catch {
            return false
        }
    }

    static func check(serverUrl: String, currentCode: Int) async -> UpdateInfo? {
        let base = trimmed(serverUrl)
        guard (base.hasPrefix("http://") || base.hasPrefix("https://")),
              let url = URL(string: base + "/app/version?client_type=ios") else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 8
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { return nil }
            let otaSupported = json["ota_supported"] as? Bool ?? false
            let code = json["version_code"] as? Int ?? 0
            let name = json["version_name"] as? String ?? ""
            let notes = json["notes"] as? String ?? ""
            let path = json["download_path"] as? String ?? "/app/download"
            return UpdateInfo(
                versionCode: code,
                versionName: name,
                notes: notes,
                downloadUrl: base + path,
                otaSupported: otaSupported,
                hasUpdate: otaSupported && code > currentCode
            )
        } catch {
            return nil
        }
    }

    private static func trimmed(_ url: String) -> String {
        var s = url.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }
}
