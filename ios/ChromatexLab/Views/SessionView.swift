import SwiftUI

struct SessionView: View {
    @EnvironmentObject private var prefs: SessionPrefs
    @EnvironmentObject private var store: SceneRecordStore

    @State private var showCapture = false
    @State private var confirmCapture = false
    @State private var statusText = ""
    @State private var isUploading = false
    @State private var showPending = false

    private var partiError: String? { Validation.partiNoError(prefs.partiNo) }
    private var canOpenCamera: Bool { partiError == nil }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    statusStrip
                    sessionSummary
                    section("Zorunlu alanlar") {
                        picker("Kumaş durumu *", selection: $prefs.fabricState, choices: Catalog.fabricStateChoices)
                        picker("Etiket *", selection: $prefs.label, choices: Catalog.labelChoices)
                        picker("Kumaş cinsi", selection: $prefs.fabricType, choices: Catalog.fabricTypeChoices)
                        picker("Kumaş rengi", selection: $prefs.fabricColor, choices: Catalog.fabricColorChoices)
                    }
                    section("Fabric kodları") {
                        Text("Opsiyonel — varsa doldurun")
                            .font(AppTheme.captionFont)
                            .foregroundStyle(AppTheme.muted)
                        labeledField("Örgü kodu", text: $prefs.orguKodu)
                        labeledField("Sipariş No", text: $prefs.sipNo)
                        labeledField("İş emri No", text: $prefs.isemriNo)
                        labeledField("Makina No", text: $prefs.makinaNo)
                        labeledField("Parti No", text: $prefs.partiNo)
                            .textInputAutocapitalization(.characters)
                        if let partiError {
                            Text(partiError)
                                .font(AppTheme.captionFont)
                                .foregroundStyle(AppTheme.bad)
                        }
                    }
                    actions
                    if !statusText.isEmpty {
                        Text(statusText)
                            .font(AppTheme.captionFont)
                            .foregroundStyle(AppTheme.muted)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background { AppTheme.pageBackground }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("Çekim")
                        .font(.system(.headline, design: .rounded).weight(.semibold))
                        .foregroundStyle(AppTheme.text)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showPending = true
                    } label: {
                        Text("Bekleyen")
                            .font(.system(.subheadline, design: .rounded).weight(.semibold))
                    }
                    .disabled(store.pendingCount == 0)
                }
            }
            .alert("Meta onay", isPresented: $confirmCapture) {
                Button("Kamerayı aç") { showCapture = true }
                Button("Düzelt", role: .cancel) {}
            } message: {
                Text(summaryText)
            }
            .fullScreenCover(isPresented: $showCapture, onDismiss: { store.refreshStats() }) {
                CaptureView(
                    operatorName: prefs.operatorName,
                    batchId: prefs.batchId,
                    fabric: prefs.fabricSession,
                    purpose: prefs.purpose
                )
                .environmentObject(store)
            }
            .sheet(isPresented: $showPending) {
                PendingListView()
                    .environmentObject(store)
                    .environmentObject(prefs)
            }
            .onAppear { store.refreshStats() }
        }
    }

    private var statusStrip: some View {
        HStack(spacing: 10) {
            Capsule()
                .fill(store.pendingCount > 0 ? AppTheme.warn : AppTheme.ok)
                .frame(width: 8, height: 8)
            Text("Bekleyen \(store.pendingCount)")
                .font(.system(.subheadline, design: .rounded).weight(.semibold))
                .foregroundStyle(AppTheme.text)
            Text("·")
                .foregroundStyle(AppTheme.muted)
            Text("Toplam \(store.totalCount)")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(AppTheme.muted)
            Spacer()
        }
        .padding(.horizontal, 4)
    }

    private var sessionSummary: some View {
        VStack(alignment: .leading, spacing: 6) {
            if prefs.serverUrl.isEmpty {
                Text("Hub adresi Ayarlar’da eksik — göndermeden önce girin.")
                    .font(AppTheme.captionFont)
                    .foregroundStyle(AppTheme.warn)
            }
            Text([
                prefs.operatorName.isEmpty ? "Operatör —" : prefs.operatorName,
                prefs.batchId,
            ].joined(separator: " · "))
            .font(.system(.caption, design: .rounded).weight(.semibold))
            .foregroundStyle(AppTheme.muted)
            .lineLimit(2)
            Text("Oturum / hub → Ayarlar")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(AppTheme.muted.opacity(0.9))
        }
        .padding(.horizontal, 4)
    }

    private var actions: some View {
        VStack(spacing: 12) {
            Button {
                guard canOpenCamera else { return }
                confirmCapture = true
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "viewfinder")
                        .font(.title3.weight(.semibold))
                    Text("Kamerayı aç")
                        .font(.system(.title3, design: .rounded).weight(.bold))
                }
                .foregroundStyle(AppTheme.primary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(AppTheme.accent, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .disabled(!canOpenCamera)
            .opacity(canOpenCamera ? 1 : 0.45)

            Button {
                upload()
            } label: {
                Text(isUploading ? "Gönderiliyor…" : "Bekleyenleri WiFi ile gönder")
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .foregroundStyle(AppTheme.primary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(AppTheme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(AppTheme.primary.opacity(0.25), lineWidth: 1.5)
                    )
            }
            .disabled(isUploading || store.pendingCount == 0 || prefs.serverUrl.isEmpty)
            .opacity((isUploading || store.pendingCount == 0 || prefs.serverUrl.isEmpty) ? 0.45 : 1)
        }
        .padding(.top, 4)
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

    private func picker(_ title: String, selection: Binding<String>, choices: [Catalog.Choice]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(AppTheme.captionFont)
                .foregroundStyle(AppTheme.muted)
            Picker(title, selection: selection) {
                ForEach(choices) { Text($0.title).tag($0.code) }
            }
            .pickerStyle(.menu)
            .tint(AppTheme.primary)
            .themeField()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var summaryText: String {
        var lines = [
            "Durum: \(Catalog.title(of: Catalog.fabricStateChoices, code: prefs.fabricState))",
            "Etiket: \(Catalog.title(of: Catalog.labelChoices, code: prefs.label))",
            "Cins: \(Catalog.title(of: Catalog.fabricTypeChoices, code: prefs.fabricType))",
            "Renk: \(Catalog.title(of: Catalog.fabricColorChoices, code: prefs.fabricColor))",
        ]
        let f = prefs.fabricSession
        if !f.orguKodu.isEmpty { lines.append("Örgü: \(f.orguKodu)") }
        if !f.sipNo.isEmpty { lines.append("Sipariş: \(f.sipNo)") }
        if !f.isemriNo.isEmpty { lines.append("İş emri: \(f.isemriNo)") }
        if !f.makinaNo.isEmpty { lines.append("Makina: \(f.makinaNo)") }
        if !f.partiNo.isEmpty { lines.append("Parti: \(f.partiNo)") }
        lines.append("Bu meta çekim kaydına yazılacak.")
        return lines.joined(separator: "\n")
    }

    private func upload() {
        isUploading = true
        statusText = "Gönderiliyor…"
        Task {
            let r = await UploadClient.uploadPending(store: store, serverUrl: prefs.serverUrl)
            statusText = "OK=\(r.ok) FAIL=\(r.fail) \(r.message)"
            isUploading = false
        }
    }

}

