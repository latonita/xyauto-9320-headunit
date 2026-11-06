# 5. Which apps work

The unit is Android 8.1 (API 27), 32-bit (`armeabi-v7a`) and OpenGL ES 2.0. Most current apps need more:

- **minSdk above 27:** the install fails with `INSTALL_FAILED_OLDER_SDK`.
- **64-bit only:** the install fails with `INSTALL_FAILED_NO_MATCHING_ABIS`.
- **OpenGL ES 3:** the app installs and the UI shows, then it dies the moment it opens a map or 3D view
  (`eglCreateContext` fails). This one is the sneaky one.

There's no Google account on the unit, so the Play Store can't pick a compatible version for you. You have to
sideload the **last version that still fits**.

## Finding the last compatible version

On [APKMirror](https://www.apkmirror.com), open the app's page and filter the variants by
**minimum Android 8.0 (API 26)** and **arch armeabi-v7a**; take the newest version that's left. Check the app
doesn't list `OpenGL ES 3.0` under requirements.

APKMirror gives `.apkm` bundles. They're zip files: unzip, then install the base APK plus the 32-bit split
(leave the arm64 split out):

```
adb install-multiple base.apk split_config.armeabi_v7a.apk
```

Pre-grant permissions instead of tapping through dialogs: `adb shell pm grant <pkg> android.permission.ACCESS_FINE_LOCATION`.

## Known good

| App | Version | Notes |
|---|---|---|
| Waze | **5.9.5.4** | the last one with minSdk 26 and GLES 2.0; everything after needs Android 10+ |
| Dashwheel (fork) | releases | [03-dashwheel.md](03-dashwheel.md) |

Dashwheel's maps use MapLibre **10.3.7**, the last MapLibre release that runs on GLES 2.0. If you build your
own map app for this unit, that's your ceiling too.

## HTTPS

The system's TLS stack is from 2018. Some servers only accept TLS 1.3 now (map routing services, for example),
and an app using the system HTTPS just gets connection errors. Apps that bundle their own TLS (Conscrypt, as
Dashwheel does, or Chrome-based ones) are fine.
