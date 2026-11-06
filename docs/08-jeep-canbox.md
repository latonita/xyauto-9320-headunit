# 8. Jeep WK: canbox firmware

Only for the 2005–2010 Jeep Grand Cherokee / Commander (WK/XK) with the **Hiworld** canbox the unit ships with
(protocol JPF2; firmware `H1N4JPF20A`).

The stock box sends the head unit only part of what the car knows. I rebuilt its firmware so it also sends
fuel level and flow, tyre pressure and temperature, ignition state and more, all of which Dashwheel shows.

The firmware has its own repo: **[latonita/hiworld-custom-canbox](https://github.com/latonita/hiworld-custom-canbox)**,
with the protocol spec, build instructions and `.iap` files in its releases.

## Flashing, in short

1. Get `canupdateH1N4JPF20L-<date>.iap` from the releases.
2. Copy it to the root of the unit's internal storage, and make sure it's the **only** `.iap` there:
   ```
   adb push canupdateH1N4JPF20L-<date>.iap /sdcard/
   adb shell ls /sdcard/*.iap
   ```
3. On the unit: car / canbus settings → canbox update → run it. Keep the ignition on until it reports success.
4. Check the version in the canbus settings: `H1N4JPF20L-<date>`.

The box's bootloader is never touched, so a failed update can just be repeated, and the stock firmware can be
flashed back the same way. A factory reset of the head unit doesn't touch the canbox.

Details, and what each version adds, are in the firmware repo's README.
