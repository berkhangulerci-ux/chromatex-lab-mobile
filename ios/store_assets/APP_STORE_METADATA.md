# Apple App Store Connect — ChromaTex Lab (`v0.2.11` / Build `14`)

Bu doküman, **ChromaTex Lab** iOS istemcisinin (`org.chromatex.lab`) Apple App Store Connect üzerindeki tüm mağaza metinlerini, gizlilik beyanlarını, inceleme notlarını ve görsel envanterini içerir.

---

## 1. Uygulama Bilgileri (App Information)

| Alan | Değer |
|---|---|
| **Uygulama Adı (`Name`, maks. 30)** | `ChromaTex Lab` |
| **Alt Başlık (`Subtitle`, maks. 30)** | `Optik Kumaş Numune Çekimi` |
| **Bundle ID** | `org.chromatex.lab` |
| **Sürüm / Build** | `0.2.11` (`CURRENT_PROJECT_VERSION = 14`) |
| **Birincil Dil (`developmentRegion`)** | `Türkçe` (`tr`) |
| **Hedef Cihaz** | Yalnızca iPhone (`TARGETED_DEVICE_FAMILY = 1`, `UIRequiresFullScreen = true`) |
| **Minimum iOS** | `iOS 17.0` *(eski iPhone 6s+ saha kolu: `mobile/ios-old-iphone` / iOS 15)* |
| **Kategori** | Birincil: `Yardımcı Programlar` (`Utilities`) · İkincil: `Üretkenlik` (`Productivity`) |
| **Fiyatlandırma** | Ücretsiz (Reklamsız, Uygulama İçi Satın Alma yok) |
| **Export Compliance** | `ITSAppUsesNonExemptEncryption = false` |

---

## 2. Mağaza Metinleri (Türkçe — `tr`)

### Tanıtım Metni (`Promotional Text`, maks. 170 karakter)
```text
Tekstil üretim ve kalite kontrol süreçlerinde 100x100 mm optik vizörle standart kumaş numunesi çekin, çevrimdışı arşivleyin veya kendi DataHub sunucunuza aktarın.
```

### Açıklama (`Description`)
```text
ChromaTex Lab, tekstil üretim ve kalite kontrol süreçlerinde kumaş numunelerini 100x100 mm referans penceresiyle standart pozlama, keskinlik ve geometri kriterlerine göre fotoğraflamak için geliştirilmiş ücretsiz bir bilimsel/endüstriyel mobil istemcidir.

Özellikler:
• 100x100 mm optik hedef halkası ile otomatik hizalama, eğim (skew) ve netlik kontrolü
• Pozlama ve ışık yoğunluğu ayarı (manuel ISO/pozlama veya otomatik politika + kademeli fener)
• Kumaş durumu, cinsi, rengi ve opsiyonel parti/sipariş meta etiketleme
• Sunucu gerektirmeden çevrimdışı yerel çekim, Galeri önizleme ve iOS Paylaşım (Share) ile JPEG dışa aktarma
• İsteğe bağlı Kendi Sunucunu Getir (BYOS) mimarisi ile kurum içi ChromaTex DataHub senkronizasyonu
• Reklam, üçüncü taraf izleme (tracking) veya zorunlu bulut hesabı içermez.
```

### Anahtar Kelimeler (`Keywords`, maks. 100 karakter)
```text
tekstil,kumaş,kalite kontrol,renk kalibrasyon,numune,kamera,optik,spektro,örgü,datahub
```

### Bağlantılar (Public HTTPS — GitHub Pages)
- **Destek URL'si (`Support URL`):** `https://berkhangulerci-ux.github.io/chromatex-lab-mobile/`
- **Gizlilik Politikası URL'si (`Privacy Policy URL`):** `https://berkhangulerci-ux.github.io/chromatex-lab-mobile/privacy-policy.html`
- **Kaynak Deposu (Public):** `https://github.com/berkhangulerci-ux/chromatex-lab-mobile`

---

## 3. Gizlilik & `PrivacyInfo.xcprivacy` Beyanları

| Kategori | Değer / Reason Kodu |
|---|---|
| `NSPrivacyTracking` | `false` |
| Toplanan Veri Türleri (`Linked = false`, `Tracking = false`, Amaç = `AppFunctionality`) | `PhotosorVideos`, `DeviceID` (uygulama içi rastgele UUID), `Name` (opsiyonel operatör etiketi), `OtherDiagnosticData` |
| `NSPrivacyAccessedAPICategoryUserDefaults` | `CA92.1` |
| `NSPrivacyAccessedAPICategoryFileTimestamp` | `C617.1` |
| `NSPrivacyAccessedAPICategorySystemBootTime` | `35F9.1` |
| `NSPrivacyAccessedAPICategoryDiskSpace` | `85F4.1` |

---

## 4. App Review Notları (Apple İnceleme Ekibi İçin — İngilizce)

```text
ChromaTex Lab is a standalone scientific/industrial textile sample capture tool with optional Bring Your Own Server (BYOS) sync to a user's self-hosted ChromaTex DataHub.

How to test without any server or account:
1. Open the app — no login or account is required.
2. On the "Çekim" tab, tap "Kamerayı aç" -> confirm metadata -> tap the white shutter button at the bottom to capture a manual sample photo (no physical calibration card or hub server is required for manual capture).
3. Open the "Galeri" tab, tap the captured photo to view it, and tap the Share icon in the top-right toolbar to export/share the JPEG locally.
4. Open "İstatistik" to view storage stats and delete local captures, or "Ayarlar" to view the in-app Privacy Policy and optional BYOS DataHub settings.
```

---

## 5. Görsel Envanteri (`ios/store_assets/` — Tamamı 8-bit Alfa Kanalsız RGB PNG)

| Amaç / App Store Yuvası | Yol | Çözünürlük |
|---|---|---|
| **App Store İkonu** | `app-store-icon-1024.png` *(Xcode `AppIcon.png` ile aynı)* | `1024 × 1024 px` |
| **Product Page Header (`21:9`)** | `promotional/appstore-header-3840x1646.png` | `3840 × 1646 px` |
| **Search Results (`3:2`)** | `promotional/appstore-search-results-1920x1280.png` | `1920 × 1280 px` |
| **Universal Header + Search (`16:9`)** | `promotional/appstore-universal-5244x2950.png` | `5244 × 2950 px` |
| **iPhone 6,9" Ekran (4 görsel)** | `screenshots/iphone-6.9-1320x2868/ios-{1..4}-*.png` | `1320 × 2868 px` |
| **iPhone 6,7" Ekran (4 görsel)** | `screenshots/iphone-6.7-1290x2796/ios-{1..4}-*.png` | `1290 × 2796 px` |
| **iPhone 6,5" Ekran (4 görsel)** | `screenshots/iphone-6.5-1242x2688/ios-{1..4}-*.png` | `1242 × 2688 px` |
| **iPhone 6,1" Ekran (4 görsel)** | `screenshots/iphone-6.1-1179x2556/ios-{1..4}-*.png` | `1179 × 2556 px` |
| **iPhone 5,5" Ekran (4 görsel)** | `screenshots/iphone-5.5-1242x2208/ios-{1..4}-*.png` | `1242 × 2208 px` |
