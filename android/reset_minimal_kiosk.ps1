# Tableti temizle + sadece ChromaTex Lab kiosk (minimum kaynak)
$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\kiosk\debug\app-kiosk-debug.apk"
$pkg = "org.chromatex.lab.kiosk"
$activity = "$pkg/.MainActivity"

function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb -s $script:serial @Args
}

$script:serial = (
    & $adb devices |
    Select-String "`tdevice$" |
    ForEach-Object { ($_ -split "\s+")[0] } |
    Where-Object { $_ -notmatch ":" } |
    Select-Object -First 1
)
if (-not $script:serial) {
    $script:serial = (
        & $adb devices | Select-String "`tdevice$" |
        ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1
    )
}
if (-not $script:serial) { throw "Tablet bagli degil (USB hata ayiklama acik olsun)." }
Write-Host "Tablet: $script:serial"

Write-Host "`n=== 1) Ucuncu parti uygulamalari kaldir ==="
$third = Adb shell pm list packages -3 |
    ForEach-Object { ($_ -replace "package:", "").Trim() } |
    Where-Object { $_ -and $_ -ne $pkg }
foreach ($p in $third) {
    Write-Host "uninstall $p"
    Adb shell pm uninstall --user 0 $p 2>$null | Out-Null
    Adb uninstall $p 2>$null | Out-Null
}

Write-Host "`n=== 2) Sistem / Google bloat disable ==="
$disable = @(
    "com.android.vending",
    "com.google.android.gm",
    "com.google.android.youtube",
    "com.google.android.apps.maps",
    "com.google.android.apps.photos",
    "com.google.android.videos",
    "com.android.chrome",
    "com.google.android.apps.docs",
    "com.google.android.apps.docs.editors.docs",
    "com.google.android.apps.docs.editors.sheets",
    "com.google.android.apps.docs.editors.slides",
    "com.google.android.apps.messaging",
    "com.google.android.calendar",
    "com.google.android.contacts",
    "com.google.android.apps.tachyon",
    "com.google.android.music",
    "com.google.android.apps.youtube.music",
    "com.google.android.googlequicksearchbox",
    "com.google.android.apps.subscriptions.red",
    "com.google.android.apps.magazines",
    "com.google.android.apps.fitness",
    "com.google.android.apps.chromecast.app",
    "com.google.android.apps.nbu.files",
    "com.google.android.calculator",
    "com.google.android.keep",
    "com.lenovo.serviceit",
    "com.tblenovo.soundrecorder",
    "com.netflix.mediaclient",
    "com.spotify.music"
)
foreach ($p in $disable) {
    Adb shell pm disable-user --user 0 $p 2>$null | Out-Null
}

Write-Host "`n=== 3) ChromaTex Lab kiosk yukleme ==="
if (-not (Test-Path $apk)) { throw "APK yok: $apk — once :app:assembleKioskDebug" }
Adb install -r -d $apk
Adb shell pm grant $pkg android.permission.CAMERA
Adb shell pm grant $pkg android.permission.INTERNET 2>$null

Write-Host "`n=== 4) Kiosk / dusuk kaynak ==="
Adb shell cmd package set-home-activity --user 0 $activity
Adb shell settings put global stay_on_while_plugged_in 7
Adb shell settings put system screen_off_timeout 2147483647
Adb shell settings put global window_animation_scale 0
Adb shell settings put global transition_animation_scale 0
Adb shell settings put global animator_duration_scale 0
Adb shell settings put secure lock_to_app_enabled 0
Adb shell settings put global ble_scan_always_enabled 0 2>$null
Adb shell settings put global wifi_networks_available_notification_on 0 2>$null
Adb shell settings put global wifi_scan_always_enabled 0 2>$null
Adb shell cmd package set-home-activity --user 0 $activity

Write-Host "`n=== 5) Acilis ==="
Adb shell am force-stop $pkg 2>$null
Adb shell am start -n $activity
Start-Sleep -Seconds 1
& $adb -s $script:serial shell --% am start -a android.intent.action.MAIN -c android.intent.category.HOME

Write-Host "`n=== Kalan 3. parti paketler ==="
Adb shell pm list packages -3
Write-Host "`nHOME resolver:"
& $adb -s $script:serial shell --% cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
Write-Host "`nTAMAM — sadece ChromaTex Lab kiosk + minimum sistem."
Write-Host "Tablette 'Ana ekran uygulamasi' diye sorarsa ChromaTex Lab secin."
