# Google Play Console — ChromaTex Lab (`v0.5.27` / `versionCode = 79`)

Bu doküman, **ChromaTex Lab** Android Play Store sürümünün (`playstore` flavor, `org.chromatex.lab`) Google Play Console üzerindeki tüm mağaza metinlerini, politika/veri güvenliği beyanlarını, derleme/imzalama adımlarını ve görsel envanterini içerir.

---

## 1. Uygulama ve Sürüm Bilgileri

| Alan | Değer |
|---|---|
| **Uygulama Adı (`tr-TR`)** | `ChromaTex Lab` |
| **Paket Adı (`applicationId`)** | `org.chromatex.lab` *(Kiosk flavor ayrıdır: `org.chromatex.lab.kiosk`)* |
| **Play Console App ID** | `4972975588223198757` |
| **Güncel Mağaza Sürümü** | `0.5.27` (`versionCode = 79`) |
| **SDK Düzeyleri** | `minSdk = 26` · `compileSdk = 36` · `targetSdk = 36` (Android 16) |
| **16 KB Bellek Sayfası** | Uyumlu (`libimage_processing_util_jni.so` tüm 4 ABI'de `PT_LOAD = 0x4000`) |
| **Kategori** | `Uygulama` → `Araçlar` (`Tools`) |
| **Fiyat / Reklam** | Ücretsiz · Reklam yok · `AD_ID` kullanmıyor |
| **Web Sitesi / Destek URL'si** | `https://berkhangulerci-ux.github.io/chromatex-lab-mobile/` |
| **Gizlilik Politikası URL'si** | `https://berkhangulerci-ux.github.io/chromatex-lab-mobile/privacy-policy.html` |

---

## 2. Varsayılan Mağaza Girişi (`tr-TR`)

### Kısa Açıklama (`Short Description`, 75 / 80 karakter)
```text
Tekstil numuneleri için 100x100 mm optik hizalama ve kalite kontrol çekimi.
```

### Tam Açıklama (`Full Description`, 759 / 4000 karakter)
```text
ChromaTex Lab, tekstil üretim ve kalite kontrol süreçlerinde kumaş numunelerini 100x100 mm referans penceresiyle standart pozlama, keskinlik ve geometri kriterlerine göre fotoğraflamak için geliştirilmiş ücretsiz bir bilimsel/endüstriyel mobil istemcidir.

Özellikler:
• 100x100 mm optik hedef halkası ile otomatik hizalama, eğim (skew) ve netlik kontrolü
• Beyaz referans halkası üzerinden ortam ışığı ve parlama (glare) denetimi
• Kumaş durumu, cinsi, rengi ve opsiyonel parti/sipariş meta etiketleme
• Sunucu gerektirmeden çevrimdışı yerel çekim, Galeri önizleme ve JPEG paylaşımı
• İsteğe bağlı Kendi Sunucunu Getir (BYOS) mimarisi ile kurum içi ChromaTex DataHub senkronizasyonu
• Reklam, üçüncü taraf izleme (tracking) veya zorunlu bulut hesabı içermez.
```

### Sürüm Notları (`v0.5.27` — Kapalı Test)
```xml
<tr-TR>
İlk kapalı test sürümü (v0.5.27):
• 100×100 mm optik kalibrasyon vizörü ile kumaş numune hizalama, eğim ve netlik kontrolü
• Beyaz referans halkası üzerinden ortam ışığı ve parlama denetimi
• Çevrimdışı yerel Galeri, numune düzenleme ve JPEG paylaşımı
• İsteğe bağlı kendi DataHub sunucunuza Wi-Fi üzerinden kayıt aktarımı
• Android 15/16 tam ekran uyumu ve uygulama içi Gizlilik Politikası
</tr-TR>
```

---

## 3. Uygulama İçeriği ve Veri Güvenliği Beyanları (Play Console)

| Bölüm | Kaydedilen Beyan |
|---|---|
| **Gizlilik Politikası** | `https://berkhangulerci-ux.github.io/chromatex-lab-mobile/privacy-policy.html` |
| **Uygulama Erişimi** | Tüm işlevler özel bir erişim (oturum açma) olmadan kullanılabilir |
| **Reklamlar / Reklam Kimliği (`AD_ID`)** | Hayır |
| **İçerik Derecelendirmesi (IARC)** | `PEGI 3` / `3+` / `Everyone` |
| **Hedef Kitle** | `18 yaş ve üzeri` (çocuklara yönelik değil) |
| **Veri Güvenliği (Data Safety)** | İsteğe bağlı BYOS yüklemesinde toplanan veriler: `Fotoğraflar`, `Ad` (opsiyonel operatör), `Tanılama bilgileri`, `Cihaz veya diğer kimlikler` (`ANDROID_ID`/UUID). Üçüncü taraflarla paylaşılmaz, yalnızca `Uygulama işlevselliği` amaçlıdır. |
| **Resmi Kurum / Finans / Sağlık / Haber** | Yok / Hayır |

---

## 4. Görsel Envanteri (`android/store_assets/` — Tamamı 8-bit RGB PNG)

| Play Console Alanı | Yol | Çözünürlük |
|---|---|---|
| **Uygulama Simgesi** | `play-icon-512.png` | `512 × 512 px` (< 1 MB) |
| **Özellik Grafiği (Feature Graphic)** | `play-feature-graphic-1024x500.png` | `1024 × 500 px` |
| **Telefon Ekran Görüntüleri (4 adet)** | `screenshots/phone/phone-{1..4}-*.png` | `1080 × 2220 px` |
| **7 inç Tablet Ekran Görüntüleri (2 adet)** | `screenshots/tablet-7/tablet7-{1..2}-*.png` | `1200 × 1920 px` |
| **10 inç Tablet Ekran Görüntüleri (2 adet)** | `screenshots/tablet-10/tablet10-{1..2}-*.png` | `1600 × 2560 px` |

---

## 5. İmzalı `.aab` Üretimi

1. `keystore.properties.example` dosyasını `keystore.properties` olarak kopyalayıp üretim `playstore-upload.jks` bilgilerini girin *(her ikisi de `.gitignore` ile korunur; kesinlikle commit etmeyin ve anahtar dosyasını yedekleyin)*.
2. İmzalı Play Store paketi oluşturun:
   ```powershell
   cd chromatex_lab/mobile_capture/android
   .\gradlew.bat bundlePlaystoreRelease
   ```
3. Çıktı: `app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab`
