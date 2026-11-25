import ImageIO
import SwiftUI
import UIKit

struct GalleryView: View {
    @EnvironmentObject private var store: SceneRecordStore
    @EnvironmentObject private var prefs: SessionPrefs
    @State private var selected: GalleryItem?
    @State private var isUploading = false
    @State private var status = ""

    private let columns = [GridItem(.adaptive(minimum: 108), spacing: 8)]

    var body: some View {
        Group {
            if store.galleryItems.isEmpty {
                ContentUnavailableView(
                    "Galeri boş",
                    systemImage: "photo.on.rectangle.angled",
                    description: Text("Çekimler burada küçük resim olarak durur. Bir kareye dokununca açılır.")
                )
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(store.galleryItems) { item in
                            Button {
                                selected = item
                            } label: {
                                GalleryThumb(url: item.imageURL, train: item.trainEligible, synced: item.synced)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(12)
                }
            }
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Galeri")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(isUploading ? "Gönderiliyor…" : "Gönder") { upload() }
                    .disabled(isUploading || store.pendingCount == 0 || prefs.serverUrl.isEmpty)
            }
        }
        .safeAreaInset(edge: .bottom) {
            if !status.isEmpty || store.pendingCount > 0 {
                Text(status.isEmpty ? "\(store.galleryItems.count) kare · \(store.pendingCount) bekliyor" : status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(.bar)
            }
        }
        .refreshable { store.refreshStats() }
        .onAppear { store.refreshStats() }
        .sheet(item: $selected) { item in
            GalleryDetail(item: item) {
                _ = store.deleteGalleryItem(item)
                selected = nil
            }
        }
    }

    private func upload() {
        isUploading = true
        status = "Gönderiliyor…"
        Task {
            let r = await UploadClient.uploadPending(store: store, serverUrl: prefs.serverUrl)
            status = "Gönderildi \(r.ok), olmadı \(r.fail)"
            isUploading = false
            store.refreshStats()
        }
    }
}

private struct GalleryThumb: View {
    let url: URL?
    let train: Bool
    let synced: Bool
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Group {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Color(.secondarySystemFill)
                    Image(systemName: "photo")
                        .foregroundStyle(.secondary)
                }
            }
            .frame(minHeight: 144)
            .frame(maxWidth: .infinity)
            .clipped()

            HStack(spacing: 4) {
                tag(train ? "Eğitim" : "Arşiv", good: train)
                if !synced { tag("Bekliyor", good: false) }
            }
            .padding(6)
        }
        .frame(height: 144)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .task(id: url?.path) {
            image = url.flatMap { Thumbnail.load($0, maxPixel: 240) }
        }
    }

    private func tag(_ text: String, good: Bool) -> some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(.ultraThinMaterial, in: Capsule())
            .foregroundStyle(good ? Color.green : Color.orange)
    }
}

private struct GalleryDetail: View {
    let item: GalleryItem
    var onDelete: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var image: UIImage?

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Group {
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                    } else {
                        ProgressView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.label)
                        .font(.headline)
                    Text(item.createdAt)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text(item.trainEligible ? "Eğitime uygun" : "Eğitime uygun değil")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(item.trainEligible ? Color.green : Color.orange)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
            .navigationTitle("Kare")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Kapat") { dismiss() }
                }
                ToolbarItemGroup(placement: .primaryAction) {
                    if let imageURL = item.imageURL {
                        ShareLink(item: imageURL) {
                            Label("Paylaş", systemImage: "square.and.arrow.up")
                        }
                    }
                    Button("Sil", role: .destructive) { onDelete() }
                }
            }
            .task {
                image = item.imageURL.flatMap { Thumbnail.load($0, maxPixel: 1600) }
            }
        }
    }
}

private enum Thumbnail {
    static func load(_ url: URL, maxPixel: CGFloat) -> UIImage? {
        let srcOpts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let src = CGImageSourceCreateWithURL(url as CFURL, srcOpts) else { return nil }
        let opts = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ] as CFDictionary
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts) else { return nil }
        return UIImage(cgImage: cg)
    }
}
