# Kiosk APK'yi dataset_hub/releases altina yayinla (WiFi guncelleme icin)
param(
  [string]$Notes = "Siyah/koyu kumas cekim kilidi duzeltmesi (640p Nyquist doku tabani, Lenovo auto-AE 30ms destegi, beyaz halka ISP kontrast toleransi)"
)
$ErrorActionPreference = "Stop"
# android/ → mobile_capture/ → chromatex_lab/
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$releaseApk = Join-Path $PSScriptRoot "app\build\outputs\apk\kiosk\release\app-kiosk-release.apk"
$rel = Join-Path $root "dataset_hub\releases"
New-Item -ItemType Directory -Force -Path $rel | Out-Null
if (-not (Test-Path $releaseApk)) { throw "Once assembleKioskRelease calistirin: $releaseApk" }

$gradlePath = Join-Path $PSScriptRoot "app\build.gradle.kts"
$gradle = Get-Content -Raw -Path $gradlePath
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { throw "build.gradle.kts versionName okunamadi" }
$versionName = $Matches[1]
if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { throw "build.gradle.kts versionCode okunamadi" }
$versionCode = [int]$Matches[1]

Copy-Item $releaseApk (Join-Path $rel "chromatex-lab-latest.apk") -Force
$metaObj = [ordered]@{
  version_code = $versionCode
  version_name = $versionName
  notes = $Notes
  download_path = "/app/download"
  published_at = (Get-Date).ToUniversalTime().ToString("o")
}
$json = ($metaObj | ConvertTo-Json)
[System.IO.File]::WriteAllText((Join-Path $rel "version.json"), $json, (New-Object System.Text.UTF8Encoding $false))
Write-Host "Yayinlandi: $rel  v$versionName ($versionCode)  pkg=org.chromatex.lab.kiosk"
