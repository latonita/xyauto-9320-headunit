# Install Dashwheel (launcher3 build) on an XYAuto 9320 unit and make it the home + auto-start app.
# Usage: .\setup-dashwheel.ps1 <Dashwheel-launcher3.apk> [-NoRadioAutostart]
# Needs adb on the PATH and exactly one device connected (or $env:ANDROID_SERIAL set).
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [switch]$NoRadioAutostart
)
$ErrorActionPreference = 'Stop'
$Pkg = 'com.android.launcher3'
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }

adb get-state | Out-Null
if ($LASTEXITCODE -ne 0) { throw "no device: check 'adb devices'" }

Write-Host 'Installing (a big APK takes 1-3 minutes over the unit''s USB)...'
adb install -r $Apk
if ($LASTEXITCODE -ne 0) { Write-Host 'adb reported an error; checking what is installed...' }
$info = adb shell dumpsys package $Pkg | Out-String
if ($info -notmatch 'lastUpdateTime=(\S+ \S+)') { throw "$Pkg is not installed" }
Write-Host "Installed, lastUpdateTime $($Matches[1])"
if ($info -notmatch 'com\.openauto\.dash\.MainActivity') { throw "$Pkg is installed but it isn't Dashwheel (the launcher3 build is needed)" }

Write-Host 'Setting home and auto-start...'
adb shell cmd package set-home-activity "$Pkg/com.openauto.dash.MainActivity"
adb shell settings put global xy_ext_bootstart0 "$Pkg/com.openauto.dash.DashApp"

if ($NoRadioAutostart) {
    adb shell settings put system boot_xyapkstart 0
    Write-Host 'FM radio auto-start off (the vendor may turn it back on later).'
}

adb shell input keyevent KEYCODE_HOME
Write-Host 'Done. Grant location and notification access when Dashwheel asks.'
