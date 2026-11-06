# 6. Tweaks

Things that made the unit noticeably better, roughly in order of value.

## WiFi: use 5 GHz

On my unit 2.4 GHz connects but never gets an address (DHCP times out), whatever the router. 5 GHz works
right away. If your WiFi "connects but has no internet", try a 5 GHz network or phone hotspot first.

## Wireless Android Auto takes over the WiFi

The vendor's ZLink app (CarPlay / Android Auto) turns the unit's WiFi into a hotspot as soon as a phone
connects over Bluetooth, for wireless Android Auto. Your normal WiFi is gone until it lets go. If you don't use
wireless AA, turn off its auto-connect (on the phone, or in ZLink's settings).

## Keep accessibility services off

Any enabled accessibility service makes Android send the UI tree on every frame, and on 4 small cores that's
visible in Compose apps (Dashwheel included). Only enable one when you need it. Dashwheel turns its own on
only for the features that need it.

## Launcher survives sleep

See [03-dashwheel.md](03-dashwheel.md): installing the launcher as `com.android.launcher3` stops the vendor
killing it at ACC off and removes the black screen after the key turns on.

## Vendor apps that phone home (optional)

Some factory apps send device serials or "what you played" to vendor servers, partly over plain HTTP. These are
safe to disable (`adb shell pm disable-user --user 0 <pkg>`; undo with `pm enable`):

```
com.acloud.stub.localmusic      # serial registration
com.acloud.stub.video           # reports every played file URL
com.xyauto.theme                # theme downloader, dead endpoint anyway
com.xyauto.sysconfig            # update client; can install apps silently
```

Only with the consequences in mind:

```
com.zjinnova.zlink              # disables CarPlay / Android Auto entirely
```

Don't disable `com.autochips.*` (system services, including the reversing camera), `com.pve.otacheck` or the
canbus app (`com.xygala.pvcanset` and friends): car functions break.

ZLink also runs a root daemon with network services listening on the WiFi. Don't connect the unit to WiFi
networks you don't trust, and if you can, block inbound connections to it on your router.
