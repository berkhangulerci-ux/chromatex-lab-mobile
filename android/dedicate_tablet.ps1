# ChromaTex Lab (kiosk) — tableti sadece bu is icin yapilandir
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\kiosk\debug\app-kiosk-debug.apk"
$pkg = "org.chromatex.lab.kiosk"
$activity = "$pkg/.MainActivity"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    & $adb @AdbArgs
    if ($LASTEXITCODE -ne 0) { Write-Warning ("adb exit {0}: {1}" -f $LASTEXITCODE, ($AdbArgs -join " ")) }
}

$serial = (Invoke-Adb devices | Select-String "`tdevice$" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
if (-not $serial) { throw "Bagli 'device' yok. USB hata ayiklama acik olsun." }
Write-Host "Tablet: $serial"

if (Test-Path $apk) {
    Write-Host "APK yukleniyor..."
    Invoke-Adb -s $serial install -r $apk
}

Write-Host "Kamera izni..."
Invoke-Adb -s $serial shell pm grant $pkg android.permission.CAMERA

Write-Host "Ana ekran = ChromaTex Lab..."
Invoke-Adb -s $serial shell cmd package set-home-activity --user 0 $activity

Write-Host "Sarjdayken ekran acik kalsin..."
Invoke-Adb -s $serial shell settings put global stay_on_while_plugged_in 7

Write-Host "Ekran zaman asimi uzun..."
Invoke-Adb -s $serial shell settings put system screen_off_timeout 2147483647

Write-Host "Animasyonlar kapatiliyor (hiz)..."
Invoke-Adb -s $serial shell settings put global window_animation_scale 0
Invoke-Adb -s $serial shell settings put global transition_animation_scale 0
Invoke-Adb -s $serial shell settings put global animator_duration_scale 0

Write-Host "Ekran sabitleme / egitim ipuclari gevsetiliyor..."
Invoke-Adb -s $serial shell settings put secure lock_to_app_enabled 0
Invoke-Adb -s $serial shell am task lock stop 2>$null
Invoke-Adb -s $serial shell settings put global policy_control null 2>$null

$disable = @(
    "com.android.vending",
    "com.google.android.gm",
    "com.google.android.youtube",
    "com.google.android.apps.maps",
    "com.google.android.apps.photos",
    "com.google.android.videos",
    "com.android.chrome",
    "com.instagram.android",
    "com.facebook.katana",
    "com.whatsapp",
    "com.zhiliaoapp.musically"
)
foreach ($p in $disable) {
    Write-Host "disable-user $p"
    Invoke-Adb -s $serial shell pm disable-user --user 0 $p 2>$null
}

Write-Host "Uygulama aciliyor..."
Invoke-Adb -s $serial shell am start -n $activity
Invoke-Adb -s $serial shell --% am start -a android.intent.action.MAIN -c android.intent.category.HOME

Write-Host ""
Write-Host "TAMAM. Tablette ilk seferde:"
Write-Host " 1) Ana ekran uygulamasi olarak ChromaTex Lab secin (sorarsa)"
Write-Host " 2) Ekran sabitleme / kilit gorevi onayini kabul edin"
Write-Host " 3) Kamera iznini verin"
Write-Host ""
Write-Host "Geri almak icin: .\undedicate_tablet.ps1"
