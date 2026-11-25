import SwiftUI

struct StatsView: View {
    @EnvironmentObject private var store: SceneRecordStore
    @State private var stats = StoreStats(total: 0, pending: 0, uploaded: 0, sceneShots: 0, freeMb: 0, totalMb: 0)
    @State private var confirmClear = false
    @State private var message = ""

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    statRow("Toplam çekim", "\(stats.total)")
                    statRow("Aktarılan", "\(stats.uploaded)")
                    statRow("Bekleyen", "\(stats.pending)")
                    statRow("Galeride", "\(stats.sceneShots)")
                    statRow("Boş alan", "\(stats.freeMb) / \(stats.totalMb) MB")
                }
                .padding(.vertical, 4)
            } header: {
                Text("Özet")
                    .font(.system(.caption, design: .rounded).weight(.bold))
            }

            Section {
                Button("Aktarılmış kayıtları sil") {
                    let n = store.deleteUploaded()
                    message = "\(n) aktarılmış kayıt silindi"
                    refresh()
                }
                .foregroundStyle(AppTheme.primary)
                Button("Tüm çekimleri sil", role: .destructive) {
                    confirmClear = true
                }
            } header: {
                Text("Temizlik")
                    .font(.system(.caption, design: .rounded).weight(.bold))
            }

            if !message.isEmpty {
                Section {
                    Text(message)
                        .font(AppTheme.captionFont)
                        .foregroundStyle(AppTheme.muted)
                }
            }

        }
        .scrollContentBackground(.hidden)
        .background { AppTheme.pageBackground }
        .navigationTitle("İstatistik")
        .onAppear { refresh() }
        .alert("Tüm çekimler silinsin mi?", isPresented: $confirmClear) {
            Button("Sil", role: .destructive) {
                let n = store.clearAll()
                message = "\(n) dosya silindi"
                refresh()
            }
            Button("İptal", role: .cancel) {}
        }
    }

    private func statRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
                .font(.system(.body, design: .rounded))
                .foregroundStyle(AppTheme.muted)
            Spacer()
            Text(value)
                .font(.system(.body, design: .rounded).weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.text)
        }
    }

    private func refresh() {
        stats = store.stats()
    }
}
