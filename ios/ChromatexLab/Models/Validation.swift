import Foundation

enum Validation {
    /// Empty allowed; if filled must match hub `^[A-Za-z]\d{7}-\d{2}$`.
    private static let partiPattern = try! NSRegularExpression(
        pattern: #"^[A-Za-z]\d{7}-\d{2}$"#
    )

    static func partiNoOK(_ value: String) -> Bool {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return true }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return partiPattern.firstMatch(in: text, range: range) != nil
    }

    static func partiNoError(_ value: String) -> String? {
        partiNoOK(value) ? nil : "Parti: X9999999-99 (örn. P1234567-01)"
    }
}
