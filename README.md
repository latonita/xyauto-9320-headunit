# XYAuto 9320 (B9320B) head unit: notes, Dashwheel and an FM radio patch

How I run a cheap Chinese Android head unit with a better launcher and a fixed FM radio, and what I learned
about the unit on the way. Everything here works over ADB, without root and without flashing the ROM.

**My unit:** XYAuto, ZhangXun / Hechuang **B9320B** board (PCB `B9320BH V0.1/C 64G`), sold as a 9" "Android 15,
8-core, 4/64 GB" radio for the 2005–2010 Jeep Grand Cherokee WK. It is really an Android 8.1 unit with a 4-core
32-bit SoC and an OpenGL ES 2.0 GPU. Full specs: [docs/01-hardware.md](docs/01-hardware.md).

If your unit's About screen says `PLATFORM ZHANGXUN-9320` (or the build is `9320_…` / `full_8227L_demo`), most of
this should apply to it too.

## What you get

| | What | Where |
|---|---|---|
| **Dashwheel** | a car dashboard launcher (maps, media, gauges, tyres, gear, canbox data), back-ported to this unit's Android 8.1 and GLES 2.0 | its own repo: [latonita/Dashwheelos](https://github.com/latonita/Dashwheelos) (GPLv3), APK in its releases |
| **Patched FM radio** | the stock radio app plus a MediaSession with the RDS station name and text, so any launcher shows "now playing"; RDS PI/AF, its own AF switch, crash fixes, a neutral grey look | [fm-radio-patch/](fm-radio-patch/), APK in this repo's [releases](../../releases/latest) |
| **Setup notes** | what makes the unit faster and more stable | [docs/](docs/) |

## Quick start

You need a PC with [ADB](https://developer.android.com/tools/releases/platform-tools) and a USB cable to the unit.

1. **ADB:** connect USB, check `adb devices` lists the unit. → [docs/02-adb-access.md](docs/02-adb-access.md)
2. **Dashwheel:** download the APK from the [Dashwheel releases](https://github.com/latonita/Dashwheelos/releases),
   then run `scripts/setup-dashwheel.sh <apk>` (or `.ps1` on Windows). → [docs/03-dashwheel.md](docs/03-dashwheel.md)
3. **FM radio (optional):** check your ROM uses the AOSP test key, then
   `adb install -r FMRadio-v8.3.apk`. → [docs/04-fm-radio-patch.md](docs/04-fm-radio-patch.md)
4. **Tweaks:** WiFi band, wireless Android Auto, vendor apps. → [docs/06-tweaks.md](docs/06-tweaks.md)

Everything can be undone: `adb uninstall` the app (the FM radio goes back to the stock one) and choose the stock
launcher again as Home.

## Docs

1. [Hardware: what the unit really is](docs/01-hardware.md)
2. [ADB access (USB, WiFi, root shell)](docs/02-adb-access.md)
3. [Dashwheel as the launcher](docs/03-dashwheel.md)
4. [FM radio patch](docs/04-fm-radio-patch.md)
5. [Which apps work, and how to find versions that do](docs/05-apps-that-work.md)
6. [Tweaks](docs/06-tweaks.md)
7. [Troubleshooting](docs/07-troubleshooting.md)
8. [Jeep WK: canbox firmware](docs/08-jeep-canbox.md)

## Building it yourself

- FM radio patch: [fm-radio-patch/README.md](fm-radio-patch/README.md) (`python build.py`, needs your unit's own
  stock FMRadio APK). Every change is listed in [fm-radio-patch/PATCHES.md](fm-radio-patch/PATCHES.md).
- Dashwheel: see the build section in [its repo](https://github.com/latonita/Dashwheelos).

## Not in this repo

No vendor ROMs, stock APKs, firmware images or device backups (they're not mine to share, and a backup of
another unit can brick yours). No signing keys: the build tells you where to get the public AOSP test key.

## Disclaimer

Use at your own risk. Nothing here writes to the system partition, but a head unit is still a car part:
don't fiddle with it while driving.
