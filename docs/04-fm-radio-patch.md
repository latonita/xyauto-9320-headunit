# 4. FM radio patch

The stock radio (`com.android.fmradio`, an MTK FM app reskinned by the vendor) plays fine, but other apps can't
see it: there's no MediaSession, so launchers, steering-wheel media widgets and Dashwheel's Now Playing show
nothing. The patch adds that and fixes a few things on the way.

## What it adds

- A **MediaSession** "FmRadio": title = RDS station name (or the frequency), artist = RDS radio text. Play,
  pause, next and previous work from any media controller.
- An `xy.radio.rds` broadcast with frequency, name, text, PI and AF list, for apps that prefer broadcasts.
- **RDS PI** code (validated, stale values after a tune are rejected) and the **AF list**.
- Its **own AF switch**: learns which frequencies carry the same station and moves to a stronger one when the
  signal fades, checking the PI before it stays. Off with `adb shell am broadcast -a xy.radio.afswitch --ez on false`.
- **Crash fixes:** the stock UI crashed on some RDS updates (null station name / text).
- A **neutral grey/silver look** instead of the stock red/orange/cyan.

Every single change is listed in [fm-radio-patch/PATCHES.md](../fm-radio-patch/PATCHES.md).

## Does it fit my unit?

It installs as an **update to the system app**, which Android only allows if it's signed with the same key as
the original. This ROM signs its system apps with the **public AOSP test key**, so we can sign with it too.
Check yours:

```
adb shell pm path com.android.fmradio          # → /system/priv-app/FMRadio/FMRadio.apk
adb pull /system/priv-app/FMRadio/FMRadio.apk
apksigner verify --print-certs FMRadio.apk     # (apksigner is in Android build-tools)
```

The certificate must be `CN=Android, OU=Android, O=Android, L=Mountain View` with SHA-1
`27:19:6e:38:6b:87:5e:76:ad:f7:00:e7:ea:84:e4:c6:ee:e3:3d:fa`. If it's anything else, this patch can't be
installed on your unit.

Also compare the version: ours is v1.11 (versionCode 3). A different build of the stock app may not match the
patch; the build script then stops rather than producing a broken APK.

## Install

Download `FMRadio-v8.3.apk` from the [releases](../../../releases/latest), then:

```
adb install -r FMRadio-v8.3.apk
```

**Undo:** `adb uninstall com.android.fmradio` removes the update; the stock radio in `/system` comes back. Your
stations stay.

## Build it yourself

See [fm-radio-patch/README.md](../fm-radio-patch/README.md). You build it from **your own unit's** stock APK; the
result for our unit is byte-identical to the release (sha256 `89c8413e…6c9ae5`).
