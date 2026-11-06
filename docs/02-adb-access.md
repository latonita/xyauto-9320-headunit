# 2. ADB access

Everything in this repo is done over ADB from a PC. Get
[platform-tools](https://developer.android.com/tools/releases/platform-tools) for your OS.

## USB

On this ROM USB debugging is normally already on (`persist.sys.usb.config=adb`). Plug a USB cable into the
unit's USB port (the one on the main harness, not a hub, works best) and run:

```
adb devices
```

You should see one device in state `device`. If it says `unauthorized`, accept the prompt on the unit's screen.
If nothing shows up, look for Developer options on the unit (Settings → About → tap the build number 7 times)
and switch USB debugging on.

**Big installs:** a 100+ MB APK takes 1–3 minutes over this USB port. Give `adb install` time and check the
result afterwards:

```
adb shell dumpsys package <package.name> | grep lastUpdateTime
```

## ADB over WiFi

Handy in the car, without a cable. Once, over USB:

```
adb shell "setprop persist.adb.tcp.port 5555; setprop ctl.restart adbd"
```

`persist.` survives reboots. Then from the PC on the same network: `adb connect <unit-ip>:5555`. The unit gets
its address by DHCP, so pin it on your router or look it up in WiFi settings.

Treat the car's WiFi as untrusted after this: anyone on it can connect.

## What the shell can do

ADB gives you uid 2000 (`shell`): `settings get/put`, `pm disable-user`, `pm grant`, `cmd package`,
`am broadcast`, `logcat`, `dumpsys`. That's all this repo needs.

**Root, if you need it** (not needed for anything here): SELinux is permissive and the ROM is a debug build, so
there is an AOSP `su`:

```
adb shell su 0 id                       # one-off root commands
adb shell "setprop service.adb.root 1; setprop ctl.restart adbd"   # whole adb session as root, until reboot
```

`adb root` itself doesn't work on this build; use the above.
