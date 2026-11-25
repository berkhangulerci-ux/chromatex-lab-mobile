# ChromaTex Lab

**Kuzey yıldızı:** Ana kayıt (master JPEG + `record.json`) ML / büyük görüntü modeli eğitiminde kullanılabilir sinyal taşımalı. Convert/sim sonra gelir ve master’dan türer; master’ı kurtarmaz. Hedef stok kamera görünümü değil — max doku/`px/mm` + tekrarlanabilir pozlama. `train_eligible` yalnızca train-grade kapılar yeşilse `true`.

Kamera optiği ile **100×100 mm delikte yüksek piksel + 18 yama kadraj içi**:

1. **Birincil:** 100×100 mm delik merkezde (AF/AE), `HOLE_FILL_FRAC = 0.38` → kısa kenarın %38’i
2. **İkincil:** v2_final siyah iç kenar (122 mm) → 220 mm tam kart bounding box kontrolü (%3 kenar payı)
3. **Canlı Hub senkronu:** `GET /catalog` + `GET /calibration/active` ile ön tanımlar ve aktif batch çekilir

Yüksek → düşük downsample OK; düşükten detay üretilmez.

Otomatik çekim + sesli koç + `ExposurePolicy` (luma/ISO/exp). Meta: `hole_side_px`, `px_per_mm`, `exposure_ok`, `train_eligible`.

## Product flavors

| Flavor | applicationId | OTA / HOME / BootReceiver | Signing |
|--------|---------------|---------------------------|---------|
| `playstore` | `org.chromatex.lab` | Yok (Play consumer) | `keystore.properties` veya `CHROMATEX_KEYSTORE_*` env |
| `kiosk` | `org.chromatex.lab.kiosk` | Var (saha tableti) | `field` keystore |

## Build

```powershell
# Kiosk debug APK (tablet)
.\gradlew.bat :app:assembleKioskDebug

# Play Store release AAB
.\gradlew.bat :app:bundlePlaystoreRelease

# Kiosk release APK (field OTA)
.\gradlew.bat :app:assembleKioskRelease
.\publish_release.ps1
```

Tablet: `.\install_to_tablet.ps1` / `.\reset_minimal_kiosk.ps1`  
PC: `python ..\..\dataset_hub\receive_server.py`
