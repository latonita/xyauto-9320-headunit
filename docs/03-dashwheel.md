# 3. Dashwheel as the launcher

[Dashwheel](https://github.com/latonita/Dashwheelos) is a car dashboard launcher: map, media, gauges, tyres,
vehicle data, all as tiles you arrange yourself. My fork is based on the last open-source (GPLv3) version and
adds what this unit needs:

- runs on Android 8.1 (API 27) and on a GLES 2.0 GPU (MapLibre 10);
- TLS 1.3 through Conscrypt, so map routing works on old Android;
- XYAuto unit and Hiworld canbox support: gear, rpm, fuel, consumption, tyre pressure and temperature, indicators,
  ignition, the screen dimming with the headlights;
- the FM radio in Now Playing and as preset buttons (with the [patched radio](04-fm-radio-patch.md));
- map tweaks (darker style contrast, 3D buildings, smooth follow, a ride trail), elevation tile, custom gauge
  tile, transparent panels, Jeep fault-code reading over OBD;
- performance fixes for slow SoCs.

## Install

Get the APK from the [releases](https://github.com/latonita/Dashwheelos/releases) (the `launcher3` build), then
either run the script:

```
scripts/setup-dashwheel.sh Dashwheel-launcher3.apk       # Linux / macOS / Git Bash
scripts\setup-dashwheel.ps1 Dashwheel-launcher3.apk      # Windows PowerShell
```

or do it by hand:

```
adb install -r Dashwheel-launcher3.apk
adb shell cmd package set-home-activity com.android.launcher3/com.openauto.dash.MainActivity
adb shell settings put global xy_ext_bootstart0 com.android.launcher3/com.openauto.dash.DashApp
```

Optional: `adb shell settings put system boot_xyapkstart 0` stops the vendor from opening the FM radio on top
at every start (Dashwheel resumes the radio itself). The vendor sets it back to 1 now and then (it did on
mine), so this is a "nice to have", not something to rely on.

On first start grant location and notification access (Now Playing needs it). In Settings pick your car; on an
XYAuto unit the canbox is found by itself.

## Why the package is `com.android.launcher3`

At ACC off the vendor firmware kills user apps before the unit sleeps. It spares packages whose names contain
`com.android.launcher` and a quick-boot list that includes `com.android.launcher3`. Any other launcher is
killed, so after the key turns on you get ~5 seconds of black screen while it cold-starts. Under this
package name Dashwheel stays in memory and is there instantly.

`xy_ext_bootstart0` is the vendor's "start this app at power on" setting (it fires ~11 s after ACC on), as a
safety net in case Dashwheel was killed after all.

Notes:
- Check first that nothing else already uses the name: `adb shell pm list packages | grep launcher`. On this
  ROM the stock launcher is `com.android.launcher` (no 3), so `com.android.launcher3` is free.
- After a factory reset the vendor runs a one-time "clear user data". Install Dashwheel after the first boot
  has settled, not right away.

## Undo

```
adb uninstall com.android.launcher3
```

Then press Home and choose the stock launcher.

## Build it yourself

See the [Dashwheel repo](https://github.com/latonita/Dashwheelos). The short version: JDK 21, Android SDK, then

```
./gradlew :app:assembleGithubDebug -PdashApplicationId=com.android.launcher3
```

The `-PdashApplicationId` switch is what makes it `com.android.launcher3`; without it you get a normal app
(`com.openauto.dash`) that the unit will kill at every sleep.
