# Privacy Policy — ChromaTex Lab

**Last updated:** 2026-11-25  
**Product:** ChromaTex Lab (mobile app) · part of the ChromaTex Framework  
**Architecture:** Bring Your Own Server (BYOS) via ChromaTex DataHub

This Privacy Policy explains how the ChromaTex Lab mobile application (“the App”) handles information. It applies to the free Android and iOS clients intended for scientific and industrial textile color and texture capture.

## 1. Who we are

ChromaTex Lab is published by an individual developer on personal store accounts. The App is free, contains no advertising, and does not require in-app purchases for core capture and upload features.

The App is designed for **Bring Your Own Server (BYOS)**: capture data is sent only to a **ChromaTex DataHub** (or compatible hub) that **your organization** deploys and controls—typically as a self-hosted service on your own local network or cloud. **We do not operate a mandatory developer cloud that receives your capture data.**

## 2. Summary for store reviewers

| Topic | Statement |
|-------|-----------|
| Ads / trackers / paid analytics SDKs | None |
| Mandatory developer backend | None — BYOS |
| Where capture data goes | Only to the DataHub URL your organization configures in Settings |
| Developer access to your fabric photos / measurements | No — we cannot see or delete data on your hub |
| Account / social login | Not required for core use |
| Children | Not directed at children |

## 3. Information the App may process

Depending on how you and your organization use the App, the following may be processed **on the device** and, when you capture or sync, **transmitted to your configured DataHub**:

### 3.1 Camera photos / images

- Still images of fabric samples and of an 18-patch reference color card used for camera characterization / Color Correction Matrix (CCM) estimation.
- Associated capture metadata (for example exposure-related fields, framing context, fabric or order labels you or your workflow enter).

**Purpose:** App functionality — scientific color and texture capture for textile QC and dataset building.  
**Required for core use:** Yes (camera access).  
**Shared with the developer:** No. Shared only with **your** DataHub if connected.

### 3.2 Optional operator or session labels

- An optional operator name or similar free-text label, if your deployment enables or the user enters it.
- Catalog / order / fabric fields you enter for the session.

**Purpose:** App functionality — attributing captures within your organization’s hub.  
**Required:** Optional (not needed to open the App; may be required by your org’s workflow).  
**Shared with the developer:** No.

### 3.3 Device and app identifiers

- On Android, a persistent app-scoped identifier derived from `Settings.Secure.ANDROID_ID` (with random UUID fallback); on iOS, an app-scoped random `UUID` stored in `UserDefaults`—used solely to register the device with **your** DataHub when configured. These identifiers are not linked to personal user accounts or used for cross-app tracking.
- Device model, OS version, and on-device storage/timer checks (for example displaying available disk space in the Stats tab and measuring capture hold timers via system uptime) used strictly on-device or for troubleshooting on your hub.
- Local app preferences (for example last hub URL, UI settings) stored on device.

**Purpose:** App functionality — device registration and multi-device workflows on your hub.  
**Required for hub sync:** Typically yes when connecting to a DataHub.  
**Shared with the developer:** No.

### 3.4 Network configuration

- The DataHub base URL (and related connection settings) that you or your organization configure in Settings.
- On local networks, local-network access is used to reach a hub on your LAN.

**Purpose:** App functionality — connecting to your server.  
**Shared with the developer:** No.

### 3.5 What we do **not** collect by design

- No advertising identifiers for ads.
- No third-party analytics, crash, or marketing SDKs in the intended free scientific build described by this policy.
- No payment or purchase data (core use is free; no IAP required).
- No contacts, SMS, call logs, precise location tracking, microphone audio recording, or health data as part of core capture.

Store listings and console forms should be updated if a future build adds telemetry; this policy will be revised accordingly.

## 4. How data is transferred (BYOS)

1. You (or your IT admin) deploy **ChromaTex DataHub** (self-hosted).
2. You connect the App by entering your DataHub URL in Settings.
3. Captures and registration payloads leave the phone **only toward that hub**.

**Encryption in transit:** We recommend configuring your DataHub with **HTTPS** (TLS). On trusted local networks some deployments may historically use HTTP cleartext to a LAN address. For production and store-facing deployments, **HTTPS is strongly recommended**. The App cannot make your hub’s TLS configuration secure on your behalf—that is under your organization’s control.

**Encryption at rest:** Controlled by your DataHub host, storage, and backups—not by the mobile developer.

## 5. Data sharing

We do **not** sell personal information. We do **not** share capture data with advertisers or data brokers.

The only intended network recipient of capture and device-registration data is **the DataHub endpoint your organization configures**. Third parties that host that infrastructure for you (for example your cloud VM provider) are bound by your contracts with them, not by a developer-operated processing agreement for ChromaTex capture payloads—because those payloads are not sent to us.

## 6. Retention and deletion

- **On device:** Local caches, preferences, and unsynced captures remain until you clear app data, uninstall, or your workflow deletes them.
- **On your DataHub:** Retention is entirely controlled by your organization (database, object storage, backup policies).

**User deletion requests:** Because we do not host your capture data, **the developer cannot delete your organization’s records**. To delete or export data, contact your **DataHub / organization administrator**. Uninstalling the App removes on-device app storage but does not erase data already stored on your hub.

## 7. Children’s privacy

The App is intended for professional and scientific use in textile and related industrial or research settings. It is not directed at children under 13 (or the equivalent age in your jurisdiction). We do not knowingly collect personal information from children through a developer-operated service.

## 8. Permissions (platform prompts)

Typical runtime / Info.plist / manifest permissions include:

- **Camera** — capture fabric and reference-card images.
- **Photo library** (if a build offers picking or saving images) — only when that feature is used.
- **Network / local network / Bonjour** (where applicable) — reach your DataHub on LAN or the internet.
- **Flashlight / vibration / wake lock** (Android, as applicable) — capture UX aids; not used to collect personal data.

Permission rationale strings in the App should match this policy.

## 9. Open source and transparency

ChromaTex Lab is part of the broader **ChromaTex Framework** open-source effort (mobile clients, calibration concepts, and self-hosted DataHub). Source availability does not change the BYOS privacy model: your production captures still go to **your** hub.

## 10. International use

Your organization chooses where the DataHub runs. Cross-border transfer rules for data stored on that hub are your organization’s responsibility.

## 11. Changes to this policy

We may update this Privacy Policy when the App’s data practices change. The “Last updated” date at the top will change, and material changes should be reflected in store Data Safety / App Privacy disclosures.

## 12. Contact

For privacy questions about the **mobile App listing** (not about data on your company’s hub), use the support email published on the Google Play and Apple App Store listing for ChromaTex Lab.

For deletion or access to capture datasets, contact your **organization’s DataHub administrator**.

---

*This document is intended for public posting (for example as `PRIVACY.md` in the project repository) and as the Privacy Policy URL linked from Google Play and the Apple App Store.*
