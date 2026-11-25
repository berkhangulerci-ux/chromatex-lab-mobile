import SwiftUI

struct PendingListView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: SceneRecordStore
    @EnvironmentObject private var prefs: SessionPrefs

    @State private var status = ""
    @State private var uploading = false

    var body: some View {
        NavigationStack {
            List {
                if store.pendingItems.isEmpty {
                    Text("Bekleyen kayıt yok.")
                        .foregroundStyle(AppTheme.muted)
                } else {
                    ForEach(store.pendingItems) { item in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.label)
                                .font(.subheadline.weight(.semibold))
                            Text(item.imageName)
                                .font(.caption.monospaced())
                                .foregroundStyle(AppTheme.muted)
                            if !item.createdAt.isEmpty {
                                Text(item.createdAt)
                                    .font(.caption2)
                                    .foregroundStyle(AppTheme.muted)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .navigationTitle("Bekleyen (\(store.pendingCount))")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Kapat") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(uploading ? "…" : "Gönder") { upload() }
                        .disabled(uploading || store.pendingCount == 0 || prefs.serverUrl.isEmpty)
                }
            }
            .safeAreaInset(edge: .bottom) {
                if !status.isEmpty {
                    Text(status)
                        .font(.footnote)
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(AppTheme.bg)
                }
            }
            .onAppear { store.refreshStats() }
        }
    }

    private func upload() {
        uploading = true
        status = "Gönderiliyor…"
        Task {
            let r = await UploadClient.uploadPending(store: store, serverUrl: prefs.serverUrl)
            status = "OK=\(r.ok) FAIL=\(r.fail) \(r.message)"
            uploading = false
            if store.pendingCount == 0 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { dismiss() }
            }
        }
    }
}
