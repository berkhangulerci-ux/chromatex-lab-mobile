import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var prefs: SessionPrefs

    @State private var statusText = ""
    @State private var isPinging = false
    @State private var showPrivacy = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("ChromaTex Lab yerel olarak bağımsız çalışabilir veya isteğe bağlı olarak kendi Dataset Hub sunucunuza (port 8787) bağlanabilir.")
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(AppTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)

                section("Sunucu (Opsiyonel — BYOS)") {
                    labeledField("Bağlantı adresi", text: $prefs.serverUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    Text("Örnek: https://<hub-adresiniz> veya yerel ağda http://<hub-lan-ip>:8787")
                        .font(AppTheme.captionFont)
                        .foregroundStyle(AppTheme.muted)

                    HStack(spacing: 10) {
                        Button(isPinging ? "Ping…" : "Sunucuyu dene") { ping() }
                            .disabled(isPinging || prefs.serverUrl.isEmpty)
                        Button("Cihazı Hub’a kaydet") { registerAndSync() }
                            .disabled(prefs.serverUrl.isEmpty)
                    }
                    .buttonStyle(.bordered)
                    .tint(AppTheme.primary)
                    .controlSize(.large)
                }

                section("Oturum") {
                    labeledField("Operatör", text: $prefs.operatorName)
                    labeledField("Batch", text: $prefs.batchId)
                        .textInputAutocapitalization(.never)
                }

                section("Cihaz") {
                    infoRow("Cihaz ID", String(DeviceId.get().prefix(12)) + "…")
                    infoRow("Uygulama", "v\(Catalog.appVersion) (\(Catalog.appVersionCode))")
                    infoRow("İstemci", Catalog.clientType)
                }

                section("Gizlilik") {
                    Text("Çekilen görüntüler ve oturum etiketleri cihazınızda saklanır; yalnızca siz bir DataHub adresi tanımlayıp gönderdiğinizde kendi sunucunuza aktarılır. Geliştiriciye ait bir bulut sunucusuna veri gönderilmez, reklam ve izleme (tracking) SDK’sı bulunmaz.")
                        .font(AppTheme.captionFont)
                        .foregroundStyle(AppTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)

                    Button {
                        showPrivacy = true
                    } label: {
                        HStack {
                            Image(systemName: "hand.raised.fill")
                            Text("Gizlilik Politikası / Privacy Policy")
                                .font(.system(.subheadline, design: .rounded).weight(.semibold))
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.bold))
                        }
                        .foregroundStyle(AppTheme.primary)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                }

                if !statusText.isEmpty {
                    Text(statusText)
                        .font(AppTheme.captionFont)
                        .foregroundStyle(AppTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background { AppTheme.pageBackground }
        .navigationTitle("Ayarlar")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showPrivacy) {
            PrivacyPolicySheet()
        }
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title.uppercased())
                .font(.system(.caption, design: .rounded).weight(.bold))
                .tracking(0.8)
                .foregroundStyle(AppTheme.muted)
            VStack(alignment: .leading, spacing: 12) {
                content()
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(AppTheme.hairline, lineWidth: 1)
            )
        }
    }

    private func labeledField(_ title: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(AppTheme.captionFont)
                .foregroundStyle(AppTheme.muted)
            TextField(title, text: text)
                .font(AppTheme.bodyFont)
                .foregroundStyle(AppTheme.text)
                .themeField()
        }
    }

    private func infoRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
                .font(.system(.body, design: .rounded))
                .foregroundStyle(AppTheme.muted)
            Spacer()
            Text(value)
                .font(.system(.body, design: .rounded).weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.text)
                .textSelection(.enabled)
        }
    }

    private func ping() {
        isPinging = true
        statusText = "Ping…"
        Task {
            let ok = await UploadClient.ping(serverUrl: prefs.serverUrl)
            statusText = ok ? "Sunucu OK: \(prefs.serverUrl)" : "Sunucuya ulaşılamadı: \(prefs.serverUrl)"
            isPinging = false
        }
    }

    private func registerAndSync() {
        statusText = "Cihaz kaydı ve senkron…"
        Task {
            if let activeBatch = await UploadClient.fetchActiveBatchId(serverUrl: prefs.serverUrl) {
                prefs.batchId = activeBatch
            }
            let registered = await UpdateClient.registerDevice(
                serverUrl: prefs.serverUrl,
                operatorName: prefs.operatorName,
                batchId: prefs.batchId
            )
            statusText = registered
                ? "Hub kaydı tamam · aktif batch: \(prefs.batchId)"
                : "Hub kaydı başarısız — sunucu adresini kontrol edin"
        }
    }
}

private struct PrivacyPolicySheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Gizlilik Politikası — ChromaTex Lab")
                        .font(.title3.weight(.bold))
                    Text("Son güncelleme: 2026-11-25 · Mimari: Kendi Sunucunu Getir (BYOS)")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Group {
                        Text("1. Genel İlkeler (TR)")
                            .font(.headline)
                        Text("ChromaTex Lab, tekstil numune görüntüleme ve renk/doku kalite kontrolü için geliştirilmiş ücretsiz bir mobil istemcidir. Uygulamada reklam, üçüncü taraf analiz/izleme (tracking) SDK’sı veya zorunlu geliştirici bulutu yoktur.")
                        Text("2. İşlenen Veriler ve Amaç")
                            .font(.headline)
                        Text("• Kamera Görüntüleri: Çektiğiniz kumaş/hedef kart fotoğrafları ve pozlama/geometri metrikleri cihazda saklanır; Galeri’den dışa aktarılabilir veya yalnızca Ayarlar’da girdiğiniz DataHub sunucusuna yüklenir.\n• Oturum Bilgileri: Opsiyonel operatör etiketi ve kumaş/sipariş kodları yalnızca numune kaydına eklenir.\n• Cihaz Tanımlayıcısı: Uygulamaya özel rastgele üretilen UUID (UserDefaults) yalnızca kendi DataHub sunucunuzda cihaz kaydı için kullanılır; kullanıcı kimliğiyle ilişkilendirilmez.")
                        Text("3. Veri Paylaşımı ve Silme")
                            .font(.headline)
                        Text("Verileriniz geliştiriciye gönderilmez ve üçüncü taraflara satılmaz. Cihazdaki tüm kayıtları İstatistik → 'Tüm çekimleri sil' adımıyla veya uygulamayı kaldırarak silebilirsiniz. Kendi DataHub sunucunuza aktardığınız verilerin saklama ve silme politikası kurumunuzun kontrolündedir.")
                    }
                    .font(.subheadline)

                    Divider().padding(.vertical, 6)

                    Group {
                        Text("Privacy Policy Summary (EN)")
                            .font(.headline)
                        Text("ChromaTex Lab uses a Bring Your Own Server (BYOS) architecture. There are no ads, no third-party analytics/tracking SDKs, and no developer-operated cloud receiving your captures. Photos, capture diagnostics, optional session labels, and an app-scoped random device UUID remain on your device unless you explicitly upload them to the self-hosted DataHub URL configured in Settings. You can delete all local captures anytime from the Stats tab.")
                    }
                    .font(.subheadline)

                    if let url = URL(string: "https://berkhangulerci-ux.github.io/chromatex-lab-mobile/privacy-policy.html") {
                        Link(destination: url) {
                            Label("Tam metni web’de aç (HTTPS)", systemImage: "safari")
                                .font(.subheadline.weight(.semibold))
                        }
                        .padding(.top, 6)
                    }
                }
                .padding(18)
            }
            .navigationTitle("Gizlilik Politikası")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Kapat") { dismiss() }
                }
            }
        }
    }
}
