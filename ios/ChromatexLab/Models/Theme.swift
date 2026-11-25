import SwiftUI
import UIKit

/// Atelier ink — floor-tablet capture tool: high contrast, low chrome.
enum AppTheme {
    static let bg = Color(red: 0.898, green: 0.906, blue: 0.918) // #E5E7EA
    static let surface = Color(red: 0.969, green: 0.973, blue: 0.980) // #F7F8FA
    static let card = Color.white
    static let primary = Color(red: 0.078, green: 0.118, blue: 0.169) // #141E2B
    static let primarySoft = Color(red: 0.855, green: 0.878, blue: 0.910) // #DAE0E8
    static let accent = Color(red: 0.965, green: 0.690, blue: 0.075) // #F6B013 signal
    static let text = Color(red: 0.071, green: 0.086, blue: 0.118) // #12161E
    static let muted = Color(red: 0.353, green: 0.388, blue: 0.447) // #5A6372
    static let hairline = Color.black.opacity(0.08)
    static let ok = Color(red: 0.110, green: 0.525, blue: 0.353) // #1C865A
    static let okSoft = Color(red: 0.855, green: 0.945, blue: 0.890)
    static let warn = Color(red: 0.780, green: 0.380, blue: 0.090)
    static let warnSoft = Color(red: 1.0, green: 0.933, blue: 0.855)
    static let bad = Color(red: 0.780, green: 0.180, blue: 0.180)
    static let overlayStroke = accent
    static let chrome = Color(red: 0.055, green: 0.071, blue: 0.102) // camera chrome

    static let brandFont = Font.system(size: 32, weight: .heavy, design: .rounded)
    static let sectionFont = Font.system(.subheadline, design: .rounded).weight(.bold)
    static let bodyFont = Font.system(.body, design: .rounded)
    static let captionFont = Font.system(.caption, design: .rounded)

    static var pageBackground: some View {
        LinearGradient(
            colors: [
                Color(red: 0.918, green: 0.925, blue: 0.937),
                bg,
                Color(red: 0.875, green: 0.886, blue: 0.902),
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()
    }

    static func applyChrome() {
        let tab = UITabBarAppearance()
        tab.configureWithDefaultBackground()
        UITabBar.appearance().standardAppearance = tab
        UITabBar.appearance().scrollEdgeAppearance = tab

        let nav = UINavigationBarAppearance()
        nav.configureWithDefaultBackground()
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
    }
}

struct ThemeFieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
            .background(AppTheme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(AppTheme.hairline, lineWidth: 1)
            )
    }
}

extension View {
    func themeField() -> some View {
        modifier(ThemeFieldStyle())
    }
}
