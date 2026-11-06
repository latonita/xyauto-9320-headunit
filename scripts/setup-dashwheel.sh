#!/bin/sh
# Install Dashwheel (launcher3 build) on an XYAuto 9320 unit and make it the home + auto-start app.
# Usage: setup-dashwheel.sh <Dashwheel-launcher3.apk> [--no-radio-autostart]
# Needs adb on the PATH and exactly one device connected (or ANDROID_SERIAL set).
set -e
APK="$1"
[ -f "$APK" ] || { echo "usage: $0 <Dashwheel-launcher3.apk> [--no-radio-autostart]"; exit 1; }
PKG=com.android.launcher3

adb get-state >/dev/null || { echo "no device: check 'adb devices'"; exit 1; }

echo "Installing (a big APK takes 1-3 minutes over the unit's USB)..."
adb install -r "$APK" || echo "adb reported an error; checking what is installed..."
adb shell dumpsys package $PKG | grep -m1 lastUpdateTime || { echo "$PKG is not installed"; exit 1; }
adb shell dumpsys package $PKG | grep -q 'com.openauto.dash.MainActivity' \
  || { echo "$PKG is installed but it isn't Dashwheel (the launcher3 build is needed)"; exit 1; }

echo "Setting home and auto-start..."
adb shell cmd package set-home-activity $PKG/com.openauto.dash.MainActivity
adb shell settings put global xy_ext_bootstart0 $PKG/com.openauto.dash.DashApp

if [ "$2" = "--no-radio-autostart" ]; then
  adb shell settings put system boot_xyapkstart 0
  echo "FM radio auto-start off (the vendor may turn it back on later)."
fi

adb shell input keyevent KEYCODE_HOME
echo "Done. Grant location and notification access when Dashwheel asks."
