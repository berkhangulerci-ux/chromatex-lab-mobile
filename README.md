# Mobile Capture

Phone/tablet clients that write Dataset Hub scene records (master JPEG + `record.json`).

```
mobile_capture/
├── README.md            # this file — shared north star + contract
├── PRIVACY.md           # shared BYOS privacy policy (source)
├── index.html           # public Support URL (GitHub Pages)
├── privacy-policy.html  # public Privacy Policy URL (GitHub Pages)
├── android/             # Kotlin / CameraX (field tablet + Play edition; v0.5.27 / API 36)
│   └── store_assets/    # Google Play icons, screenshots, PLAY_STORE_METADATA.md
└── ios/                 # SwiftUI / AVFoundation (capture + gates; v0.2.11 / build 14)
    └── store_assets/    # Apple App Store icons, screenshots, promotional art, APP_STORE_METADATA.md
```

**Public mirror & live store URLs:**
- Public mobile repo: `https://github.com/berkhangulerci-ux/chromatex-lab-mobile`
- Support URL: `https://berkhangulerci-ux.github.io/chromatex-lab-mobile/`
- Privacy Policy URL: `https://berkhangulerci-ux.github.io/chromatex-lab-mobile/privacy-policy.html`

**Not in scope here:** macOS desktop app, PC webcam (lives in `dataset_hub` UI).

## North star

Master capture = ML training source. Max texture / `px/mm` + repeatable exposure — not stock ISP “pretty” look.  
`train_eligible` is true only when train-grade framing + exposure gates pass. FORCE / ungated P0 shots stay archive-only (`train_eligible=false`).

Printed target: 100 mm fabric hole + 122/138 mm concentric rings + 18 patches (220 mm span) on A4 **v2 FINAL** card (`../calibration_target/`, 600 DPI, ChromaTex Lab + QR stamp). Generate from Dataset Hub **Kalibrasyon → Baskı kartı** or `python ../calibration_target/generate_a4_target_v2_final.py --write`.
- **Android** uses fixed `HOLE_FILL_FRAC = 0.38` of the short frame edge (tuned for field tablets).
- **iOS** computes the yellow square dynamically from the active camera FOV (`~0.418` on a 24 mm-equiv main lens, clamped `0.28..0.56`).

## Hub contract

| Item | Value |
|------|--------|
| Ingest | `POST {hub}/upload` multipart `record_json` + `image` |
| Live Sync | `GET {hub}/catalog`, `GET {hub}/calibration/active` |
| Schema | `../dataset_hub/scene_record.schema.json` |
| Android `client_type` | `android` |
| iOS `client_type` | `ios` |
| Run hub | Windows or macOS LAN host: `python ../dataset_hub/receive_server.py` |
| Hub URL | `http://<hub-lan-ip>:8787` (production/public: `https://…`) |

## Build

```powershell
# Android
cd android
.\gradlew.bat assembleDebug
```

```bash
# iOS (Mac + Xcode 15+)
open ios/ChromatexLab.xcodeproj
```
