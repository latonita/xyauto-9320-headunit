# FM radio patch: build

Rebuilds the patched `com.android.fmradio` from your unit's stock APK. What it does and how to install it:
[../docs/04-fm-radio-patch.md](../docs/04-fm-radio-patch.md). Every change, one by one: [PATCHES.md](PATCHES.md).

## You need

- Python 3 with Pillow (`pip install pillow`)
- JDK 17+ (`java`, `javac` on the PATH)
- Android SDK with build-tools, one platform (`android.jar`) and the NDK; set `ANDROID_HOME`
- `tools/apktool.jar` from [apktool.org](https://apktool.org) (or set `APKTOOL_JAR`)
- `input/FMRadio-orig.apk`, your unit's stock radio:
  ```
  adb pull /system/priv-app/FMRadio/FMRadio.apk input/FMRadio-orig.apk
  ```
- `keys/platform.pk8` and `keys/platform.x509.pem`, the public AOSP platform test key, from
  [AOSP `build/target/product/security`](https://android.googlesource.com/platform/build/+/refs/heads/oreo-mr1-release/target/product/security/).
  Gitiles serves files as base64: open each with `?format=TEXT` and decode, for example
  ```
  curl -s "https://android.googlesource.com/platform/build/+/refs/heads/oreo-mr1-release/target/product/security/platform.pk8?format=TEXT" | base64 -d > keys/platform.pk8
  curl -s "https://android.googlesource.com/platform/build/+/refs/heads/oreo-mr1-release/target/product/security/platform.x509.pem?format=TEXT" | base64 -d > keys/platform.x509.pem
  ```

`input/`, `keys/`, `tools/` and `out/` are git-ignored on purpose: none of them belongs in the repo.

Optional overrides: `BUILD_TOOLS`, `ANDROID_JAR`, `NDK_HOME` (the newest one under `ANDROID_HOME` is used
otherwise).

## Build

```
python build.py
```

→ `out/FMRadio-v8.apk`. The build also runs the logic checks in `test/` (AF store, AF switch) on the PC.

From the stock v1.11 APK (sha256 `1760ba37…c947`) the result is byte-identical to the release
(sha256 `89c8413ec0ca9b5506d606a78c4881559e51454820bb49d19070bb38eb6c9ae5`).

## Layout

| Path | What |
|---|---|
| `build.py` | the whole recipe: decompile, add our classes, patch smali, recolour, add the native lib, sign |
| `patch/apply_patch.py` | edits to the stock smali, each anchored on exact text; fails loudly if an anchor is missing |
| `src/com/android/fmradio/` | our classes: `XyMediaSession`, `XyPi`, `XyPiTracker`, `XyAfStore`, `XyAfSwitch` |
| `stubs/` | compile-time stand-ins for stock classes, never packaged |
| `native/xypi.c` | `libxypi.so`: reads PI / AF / signal quality from the stock `libfmjni.so` |
| `v3/` | the recolour (`colormap.json`, `recolor.py`) |
| `test/` | PC-side checks |
| `patch/v8.3-stock-smali.diff` | reference: the resulting diff of the stock smali |

## Debugging on the unit

Log tags: `XyPi`, `XyAf`, `XyAfStore`, `XySig` (rssi / pamd / bler every 2 s), `XyAfSw`.

```
adb logcat -s XyPi XyAf XyAfSw
adb shell am broadcast -a xy.radio.afdump          # dump the learned AF table to the log
```
