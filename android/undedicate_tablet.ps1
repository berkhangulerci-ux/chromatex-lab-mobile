# Dedicated / kiosk modu geri al (org.chromatex.lab.kiosk)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = (& $adb devices | Select-String "`tdevice$" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
if (-not $serial) { throw "Tablet yok" }

& $adb -s $serial shell settings put global stay_on_while_plugged_in 0
& $adb -s $serial shell settings put system screen_off_timeout 60000
& $adb -s $serial shell settings put global window_animation_scale 1
& $adb -s $serial shell settings put global transition_animation_scale 1
& $adb -s $serial shell settings put global animator_duration_scale 1

$enable = @(
    "com.android.vending",
    "com.google.android.gm",
    "com.google.android.youtube",
    "com.google.android.apps.maps",
    "com.google.android.apps.photos",
    "com.google.android.videos",
    "com.android.chrome"
)
foreach ($p in $enable) {
    & $adb -s $serial shell pm enable $p 2>$null
}

# Lenovo varsayilan launcher (cihaza gore degisebilir)
& $adb -s $serial shell cmd package set-home-activity --user 0 com.android.launcher3/.Launcher3QuickStepApp 2>$null
Write-Host "Dedicated ayarlar gevsetildi. Ana ekrani tablette manuel secebilirsiniz."
Write-Host "Kiosk paket: org.chromatex.lab.kiosk"
