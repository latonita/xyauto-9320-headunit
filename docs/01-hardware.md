# 1. Hardware: what the unit really is

The About screen is mostly made up. These figures come from the board itself and from the running system
(`getprop`, `/proc/cpuinfo`, `/proc/partitions`, the device tree).

| | About screen says | Reality |
|---|---|---|
| Android | 15.0 | **8.1 Oreo, API 27** (`ro.build.version.sdk=27`), kernel 4.14.116 |
| CPU | 8 core | **4 × Cortex-A7**, 32-bit only (`armeabi-v7a`; no 64-bit apps) |
| SoC | — | **AutoChips AC8238PTVA** (the software reports `AC8227L`, platform `ZHANGXUN-9320`) |
| GPU | — | **Mali-450, OpenGL ES 2.0 only** |
| RAM | 4 GB | **2 GB** (4 × Samsung K4B4G0846D DDR3L, 512 MB each) |
| Storage | 64 GB | 64 GB (Samsung KLMCG4JENB eMMC; 58 GiB usable) |
| Display | 1280 × 720 | 1280 × 720 MIPI |

Other parts: MediaTek MT6323 PMIC; a separate MCU (keys, power, radio tuner) talking to Android over a UART.
The ROM is built from MediaTek/AutoChips `alps-mp-o1` with **AOSP test keys** (this matters for the FM patch).

Board: ZhangXun / Hechuang **B9320B** layout, PCB marked `B9320BH V0.1/C 64G`, maker XYAuto. Many brands sell it
(mine came as GOJOHO). About → device info shows `PLATFORM ZHANGXUN-9320`.

## What this means in practice

- **Apps:** anything needing Android 9+ (API 28+) or 64-bit won't install. Anything that needs OpenGL ES 3
  installs and then crashes when it opens its map or 3D view (GLES 2.0 only). See
  [05-apps-that-work.md](05-apps-that-work.md).
- **Speed:** 4 small 32-bit cores. Heavy Compose/Flutter UIs and accessibility services are noticeably slow;
  keep the number of background apps low.
- **Sleep:** the unit doesn't power off with the key. At ACC off it "quick-boot suspends" and kills most user
  apps first. That's why Dashwheel is installed under a special package name
  ([03-dashwheel.md](03-dashwheel.md)).
- **System:** SELinux is permissive and `ro.debuggable=1`, so an ADB shell can do a lot
  ([02-adb-access.md](02-adb-access.md)).

## Checking your own unit

```
adb shell getprop ro.build.version.sdk      # 27 = Android 8.1
adb shell getprop ro.product.cpu.abilist    # armeabi-v7a,armeabi only = 32-bit
adb shell cat /proc/cpuinfo | grep -c processor
adb shell cat /proc/meminfo | head -1
adb shell dumpsys SurfaceFlinger | grep GLES
```
