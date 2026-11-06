# 7. Troubleshooting

**Black screen for ~5 s after turning the key, then the launcher appears.**
The launcher was killed at ACC off and is cold-starting. Install it as `com.android.launcher3`
([03-dashwheel.md](03-dashwheel.md)).

**The FM radio pops up on top after every start.**
The vendor auto-starts it (`boot_xyapkstart`). `adb shell settings put system boot_xyapkstart 0`; it may come
back after a while.

**`adb install` printed nothing / timed out, but did it install?**
Big APKs take minutes over the unit's USB. Check
`adb shell dumpsys package <pkg> | grep lastUpdateTime` instead of trusting the console.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signatures don't match on the FM radio.**
Your ROM isn't signed with the AOSP test key; the patch can't go on this unit. See
[04-fm-radio-patch.md](04-fm-radio-patch.md).

**`INSTALL_FAILED_OLDER_SDK` or `NO_MATCHING_ABIS`.**
The app is too new or 64-bit only. See [05-apps-that-work.md](05-apps-that-work.md).

**An app opens and then crashes on its map.**
It needs OpenGL ES 3; the unit only has 2.0. Use an older version.

**WiFi connected, no internet.**
Try 5 GHz ([06-tweaks.md](06-tweaks.md)), and check ZLink hasn't turned the WiFi into a hotspot.

**Maps load but routing never comes back.**
The server wants TLS 1.3, which Android 8.1 doesn't have. The app has to bring its own TLS (the Dashwheel fork
does).

**`adb devices` is empty over WiFi after a reboot.**
The unit's address changed (DHCP). Find it in the WiFi settings or pin it on the router.

**Things got weird; how do I go back to stock?**
`adb uninstall com.android.launcher3` and `adb uninstall com.android.fmradio`, then pick the stock launcher at
Home. Nothing in this repo touches `/system`.
