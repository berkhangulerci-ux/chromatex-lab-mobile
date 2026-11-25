# ChromaTex Lab (kiosk) — USB tablete yukleme
$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\kiosk\debug\app-kiosk-debug.apk"
$pkg = "org.chromatex.lab.kiosk"

if (-not (Test-Path $apk)) {
    Write-Host "APK yok. Once: .\gradlew.bat :app:assembleKioskDebug"
    exit 1
}

& $adb start-server | Out-Null
$devices = & $adb devices
Write-Host $devices

if ($devices -match "unauthorized") {
    Write-Host ""
    Write-Host "TABLETTE: 'USB hata ayiklamaya izin ver' penceresinde İZİN VER / ALLOW tiklayin."
    Write-Host "Sonra bu scripti tekrar calistirin."
    exit 2
}

$serial = (& $adb devices | Select-String "\tdevice$" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
if (-not $serial) {
    Write-Host "Bagli tablet yok. USB kablo + Gelistirici secenekleri + USB hata ayiklama acik olmali."
    exit 3
}

Write-Host "Yukleniyor: $serial"
& $adb -s $serial install -r $apk
& $adb -s $serial shell monkey -p $pkg -c android.intent.category.LAUNCHER 1
Write-Host "Tamam."
