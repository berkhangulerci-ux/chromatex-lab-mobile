import Foundation
import Combine

@MainActor
final class SessionPrefs: ObservableObject {
    private let defaults = UserDefaults.standard

    @Published var serverUrl: String {
        didSet { defaults.set(normalizedUrl(serverUrl), forKey: Keys.serverUrl) }
    }
    @Published var operatorName: String {
        didSet { defaults.set(operatorName.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Keys.operatorName) }
    }
    @Published var batchId: String {
        didSet { defaults.set(batchId.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Keys.batchId) }
    }
    @Published var fabricState: String {
        didSet { defaults.set(fabricState, forKey: Keys.fabricState) }
    }
    @Published var label: String {
        didSet { defaults.set(label, forKey: Keys.label) }
    }
    @Published var fabricType: String {
        didSet { defaults.set(fabricType, forKey: Keys.fabricType) }
    }
    @Published var fabricColor: String {
        didSet { defaults.set(fabricColor, forKey: Keys.fabricColor) }
    }
    @Published var purpose: String {
        didSet { defaults.set(purpose, forKey: Keys.purpose) }
    }
    @Published var orguKodu: String = ""
    @Published var sipNo: String = ""
    @Published var isemriNo: String = ""
    @Published var makinaNo: String = ""
    @Published var partiNo: String = ""

    init() {
        let stored = defaults.string(forKey: Keys.serverUrl)
        let retired = ["http://192.168.1.10:8787", "http://192.168.16.106:8787"]
        if let stored, !stored.isEmpty, !retired.contains(stored) {
            serverUrl = stored
        } else {
            serverUrl = ""
        }
        operatorName = defaults.string(forKey: Keys.operatorName) ?? ""
        batchId = defaults.string(forKey: Keys.batchId) ?? Catalog.defaultBatchId
        fabricState = defaults.string(forKey: Keys.fabricState) ?? "ham"
        label = defaults.string(forKey: Keys.label) ?? "normal_kumas"
        fabricType = defaults.string(forKey: Keys.fabricType) ?? "Suprem"
        fabricColor = defaults.string(forKey: Keys.fabricColor) ?? "Ekru"
        purpose = Catalog.Purpose.sceneCapture.rawValue
    }

    var fabricSession: FabricSession {
        FabricSession(
            fabricState: fabricState,
            label: label,
            fabricType: fabricType,
            fabricColor: fabricColor,
            orguKodu: orguKodu.trimmingCharacters(in: .whitespacesAndNewlines),
            sipNo: sipNo.trimmingCharacters(in: .whitespacesAndNewlines),
            isemriNo: isemriNo.trimmingCharacters(in: .whitespacesAndNewlines),
            makinaNo: makinaNo.trimmingCharacters(in: .whitespacesAndNewlines),
            partiNo: partiNo.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        )
    }

    private func normalizedUrl(_ url: String) -> String {
        var s = url.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    private enum Keys {
        static let serverUrl = "server_url"
        static let operatorName = "operator"
        static let batchId = "batch_id"
        static let fabricState = "fabric_state"
        static let label = "label"
        static let fabricType = "fabric_type"
        static let fabricColor = "fabric_color"
        static let purpose = "purpose"
    }
}
