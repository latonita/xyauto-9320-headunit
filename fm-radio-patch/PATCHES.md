# FM radio patches: full list (current = v8.3)

Every change made to the stock `com.android.fmradio` (v1.11, versionCode 3) on the XYAuto B9320B unit, so it can
be redone. How to build and install it: `README.md`.

## Redo it

1. Original APK: `input/FMRadio-orig.apk`, pulled from your unit: `adb pull /system/priv-app/FMRadio/FMRadio.apk`
   (on ours: v1.11, sha256 `1760ba37…c947`). If its classes differ, the smali
   anchors in `patch/apply_patch.py` may not match; the script then fails rather than patching blindly.
2. `python build.py` (needs JDK, `tools/apktool.jar`, Android build-tools, NDK, Pillow; host checks in `test/` run
   as part of it) → `out/FMRadio-v8.apk` (current build sha256 `89c8413e…ae5`).
3. Install as a system-app update: `adb install -r out/FMRadio-v8.apk`. It works because the ROM signs FMRadio with
   the public AOSP platform test key (`keys/platform.pk8` + `.x509.pem`; check with `apksigner verify --print-certs`,
   SHA-1 `27196e38…`). Undo: `adb uninstall com.android.fmradio`.

What's where:
| Path | Contents |
|---|---|
| `patch/apply_patch.py` | every edit to stock smali (hooks and fixes), by text anchor |
| `src/com/android/fmradio/*.java` | our new classes: `XyMediaSession`, `XyPi`, `XyPiTracker`, `XyAfStore`, `XyAfSwitch` |
| `stubs/` | compile-time stubs of stock classes (never packaged) |
| `native/xypi.c` | `libxypi.so` (PI, AF, PAMD, RDS-cache clear) |
| `v3/colormap.json`, `v3/recolor.py` | the silver recolour |
| `patch/v8.3-stock-smali.diff` | the exact resulting diff of the **stock** smali files (reference; build.py is the real recipe) |
| `patch/v8.3-changed-apk-entries.txt` | every zip entry that differs from the original |

## 1. Edits to stock code (smali, `patch/apply_patch.py`)

| # | Class / method | Change | Since |
|---|---|---|---|
| 1 | `FmService.setPs` / `setLRText` | call new `xyBroadcastRds()` when PS/RT changes | v1 |
| 2 | `FmService.xyBroadcastRds()` (new method) | `sendBroadcast("xy.radio.rds")` with `freq`, `ps`, `rt` (later also `pi`, `af`, `afKnown`) | v1 |
| 3 | `FmMainActivity.sendCurFreqToOther` | adds `ps` and `rt` extras to the stock `com.android.radio.widget.freq_volue` broadcast | v1 |
| 4 | `FmService.onCreate` / `onDestroy` | start / stop `XyMediaSession` | v2 |
| 5 | `FmService.notifyActivityStateChanged` | `XyMediaSession.refresh` | v2 |
| 6 | `FmMainActivity$4.handleMessage` | null guards: station name (LISTEN_PS_CHANGED) and RT string → "" instead of an NPE crash | v8.3 |
| 7 | colour constants in smali | `#EF4B24`, `#FF0000`, `#0bd9f1` → `#C8CCD0`; `0xFF0BD9F1` → `0xFFC8CCD0` (FMAreaAdapter, GalaFragmentView, FmScroller, FmVisualizerView, FmMainActivity) | v3 |

The diff also shows `ViewPager`, `ViewCompat` and `VerticalViewPager`. Those are only re-dex noise
(`= false` initialisers dropped), not edits.

## 2. New code (ours)

- **`XyMediaSession`** (v2+): MediaSession "FmRadio".
  - Metadata: `MEDIA_ID fm:<freq>`; TITLE = RDS PS, else the stored name, else "88.2 MHz"; ARTIST = RT; plus `xy.fm.pi`, `xy.fm.af`, `xy.fm.af.known`, `xy.fm.afsw`.
  - State: PLAYING / PAUSED.
  - Transport: PLAY, PAUSE, NEXT and PREV are mapped to the MCU broadcasts `xy.android.forceplay` / `forcepause` / `nextmedia` / `previousmedia`.
  - A 2 s poll catches missed changes.
- **`XyPi` + `native/xypi.c`** (v4+): reads the PI through `FMR_get_pi(0,…)` in the loaded `libfmjni.so`. It also reads the AF list (v6), PAMD (v7) and clears the cached PI/AF after a tune (v8.2/8.3). Every read is SIGSEGV-guarded.
- **`XyPiTracker`** (v5+): publishes a PI only once it's validated.
  - Needs 3 equal reads after a 2.5 s blanking window.
  - Stale values from the previous frequency are rejected unless `bler > 0`; reads at `bler ≥ 40` are skipped.
  - Fixes the unit's frequency glitches (`normFreq`).
- **`XyAfStore`** (v7+): learns AFs per PI in `filesDir/xyaf.txt` (format `#xyaf2`).
  - An AF is confirmed after 3 clean hits (`bler < 25`) in 2 tune sessions.
  - One-bit-shifted PIs are ignored.
  - Dump it with `am broadcast -a xy.radio.afdump`.
- **`XyAfSwitch`** (v8+): our own AF switch.
  - Trigger: PAMD ≤ 8 for 6 s.
  - Probes up to 3 confirmed AFs; accepts at PAMD ≥ 15 with the same PI, else tunes back.
  - Default on; toggle with `am broadcast -a xy.radio.afswitch --ez on false`.
- **Logs:** `XyPi`, `XyAf`, `XyAfStore`, `XySig` (rssi/pamd/bler every 2 s), `XyAfSw`.

## 3. Resources and packaging

- About 50 PNGs recoloured: red, orange and pink hues become neutral silver by a lightness ramp. Includes the flat `<` `>` buttons and the selected preset (`v3/`).
- `res/pa.xml`: save-dialog button text colour.
- `lib/armeabi-v7a/libxypi.so` added, stored uncompressed and page-aligned (the manifest has `extractNativeLibs=false`).
- `resources.arsc` and the manifest are unchanged; versionCode stays 3.
- Signed v2 with the AOSP platform test key.

## 4. Unit-side settings related to the radio

- `Settings.System activeAF` stays 0: the stock AF path is never used.
- `settings put system boot_xyapkstart 0` stops the vendor auto-starting the radio at ACC on. It's optional, and Dashwheel resumes it instead.
- Dashwheel reads all of this; nothing else on the unit depends on the patch.

