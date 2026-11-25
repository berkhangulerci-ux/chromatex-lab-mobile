import SwiftUI

@main
struct ChromatexLabApp: App {
    @StateObject private var prefs = SessionPrefs()
    @StateObject private var store = SceneRecordStore()

    init() {
        AppTheme.applyChrome()
    }

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environmentObject(prefs)
                .environmentObject(store)
                .tint(.blue)
                .preferredColorScheme(.light)
        }
    }
}

struct RootTabView: View {
    var body: some View {
        TabView {
            SessionView()
                .tabItem { Label("Çekim", systemImage: "camera.fill") }
            NavigationStack {
                GalleryView()
            }
            .tabItem { Label("Galeri", systemImage: "photo.on.rectangle") }
            NavigationStack {
                StatsView()
            }
            .tabItem { Label("İstatistik", systemImage: "chart.bar.fill") }
            NavigationStack {
                SettingsView()
            }
            .tabItem { Label("Ayarlar", systemImage: "gearshape.fill") }
        }
    }
}
