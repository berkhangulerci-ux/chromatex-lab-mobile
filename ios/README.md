# ChromaTex Lab — iOS

Phone/tablet only (`client_type: ios`). Mirrors Android **capture flow**: session meta → camera → master JPEG + `record.json` → hub upload.

Version **0.2.10** — Galeri küçük resim + dokununca açılır. Cihaz kalibrasyonu kalktı. Sekmeler sistem çubuğu. Sarı kare FOV’dan hesaplanır. Çift 90° skew rotasyonu ve 18-yama kart oran hatası giderildi; upload arka planda stream edilir; ATS yalnızca yerel ağa (`NSAllowsLocalNetworking`) izin verir.

## Open

```bash
cd chromatex_lab/mobile_capture/ios
open ChromatexLab.xcodeproj
```

Xcode 15+, iOS 17+. Set Signing Team. Camera + local networking (ATS) declared in `Info.plist`.

## Flow

1. **Ayarlar** — hub URL, operatör, batch, amaç; ping / cihaz kaydı
2. **Çekim** — kumaş alanları + salt-okunur oturum özeti → kamera
3. Confirm meta → full-screen camera with yellow 100 mm hole overlay
4. Live framing analyzer + ExposurePolicy (AUTO/MANUAL ISO·exp); TTS coach
5. Auto-shoot after ~700 ms OK hold; shutter always force-captures
6. **Galeri** / **İstatistik** tabs; pending WiFi multipart upload
7. Hub: device register + `/app/version` check (no APK OTA on iOS)

## Hub (Yerel ağ)

Dataset Hub aynı yerel ağdaki Windows veya Mac host üzerinde çalışır:

```bash
cd chromatex_lab/dataset_hub
python3 receive_server.py
```

- Aynı cihaz: `http://127.0.0.1:8787`
- Telefondan: `http://<hub-lan-ip>:8787` (üretimde `https://…` önerilir)

## Ops notes

- Capture screen disables idle timer (kiosk-lite). Prefer Guided Access for floor tablets.
- Cloud agent LAN hub / USB telefona erişemez; Mac local şart.
- `inbox/` ve `devices.json` commit etme.
