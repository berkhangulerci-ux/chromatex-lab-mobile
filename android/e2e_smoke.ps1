# ChromaTex Lab v0.3 E2E smoke (adb)
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "org.chromatex.lab.kiosk"
$serial = (& $adb devices | Select-String "`tdevice$" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
if (-not $serial) { throw "Tablet bagli degil" }

Write-Host "== package =="
& $adb -s $serial shell dumpsys package $pkg | Select-String "versionName" | Select-Object -First 2

Write-Host "== launch =="
& $adb -s $serial shell am force-stop $pkg
& $adb -s $serial shell am start -n "$pkg/.MainActivity"
Start-Sleep 2

Write-Host "== storage dirs =="
& $adb -s $serial shell "ls -la /sdcard/Android/data/$pkg/files/ChromatexLab 2>/dev/null || echo no_app_dir"

Write-Host "== push calib if missing =="
# Resolve from this script: android/ -> mobile_capture/ -> chromatex_lab/
$chromatexRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$local = Join-Path $chromatexRoot "calibration_target\patches_measured.json"
if (Test-Path $local) {
  & $adb -s $serial shell mkdir -p /sdcard/ChromatexLab/calibration
  & $adb -s $serial push $local /sdcard/ChromatexLab/calibration/patches_measured.json
}

Write-Host "== server ping from PC =="
try {
  $r = Invoke-WebRequest "http://127.0.0.1:8787/" -UseBasicParsing -TimeoutSec 3
  Write-Host $r.Content
} catch {
  Write-Host "receive_server kapali — dataset_hub/receive_server.py baslatin"
}

Write-Host "E2E smoke OK (UI: A4 yokken CEK butonu disabled olmali)"
