# WP status strip — build, enable, checks, failure mode

## Artifact

- `repo/actioncentre/build/out/ztmetro-actioncentre.apk`
- `repo/evidence/status-strip-overlay/ztmetro-actioncentre.apk` (same bytes)
- size **325 179 bytes** (318 KiB)
- sha256 **e7299bf1548896ddb50c7c6700212eb83bdd8e471985f7289d4f7de9a78d14ec**
- built on `hermes-ubuntu:~/ztux/statusstrip` with `~/android-sdk` (android-36,
  build-tools 36.0.0) and `~/android-jdk`; plain `aapt2 + javac + d8 + apksigner`,
  no Gradle, no AndroidX, no network.
- static checks: `verify.txt` and `status-strip-verify.txt` in this directory. The
  APK is signed and `apksigner verify` passes (`apksigner-verify.txt`).
- `preview-720x56.png` / `preview-1440x112.png` are a **static render of the view
  geometry**, not a device screenshot. No adb was available in this task.

## What is added

`StatusStripService` (a second, optional accessibility service) adds a
`TYPE_ACCESSIBILITY_OVERLAY` window: `MATCH_PARENT x 28 dp`, `gravity TOP|START`,
black `#000000`, flags `NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_IN_SCREEN |
LAYOUT_NO_LIMITS`, `layoutInDisplayCutoutMode=ALWAYS` on API 30+. `NOT_TOUCHABLE`
means it does not steal the top-edge gesture: the 24 dp transparent strip in
`ShadeBridgeService` below it still receives touches.

Drawn by `StatusStripOverlayView`:

- left: WP battery outline with a white fill level, a small two-prong plug while
  charging; three Wi-Fi arcs (50 % white if enabled but not connected); four
  cellular bars (level 0–4, or four static 50 % bars without `READ_PHONE_STATE`);
- right: `HH:mm`, 12 sp, Open Sans Light, white, right-aligned at 12 dp;
- only white (`#FFFFFF`, `#B3FFFFFF`, `#80FFFFFF`, `#4DFFFFFF`). No green, no red.

Sources: sticky `ACTION_BATTERY_CHANGED`; `WifiManager.isWifiEnabled()` plus a
default-network callback; `TelephonyManager.getSignalStrength()` plus a signal
callback; `ACTION_TIME_TICK` / `TIME_CHANGED` / `TIMEZONE_CHANGED` for the clock.
No 1 Hz timer, no `postDelayed` loop, no wakelock.

## Enable

1. Install/update this APK (normal ztOS module or `adb install -r`).
2. Optional, for real signal levels (otherwise four static bars):
   `su -c 'pm grant org.ztmetro.actioncentre android.permission.READ_PHONE_STATE'`
3. Settings → Accessibility → enable **ztMetro status strip**. Keep **ztMetro shade
   bridge** enabled as well; they are separate services and the strip does not
   replace the gesture bridge.
4. Hide the stock slots with the verified LineageOS 23.2 names (see
   `ICON-BLACKLIST.md`):
   ```sh
   su -c 'settings put secure icon_blacklist "clock,battery,wifi,mobile,stacked_mobile,airplane,rotate,headset"'
   ```
5. Verify: `su -c 'settings get secure icon_blacklist'` must echo that list.
6. Optional log check: `logcat -s StatusStrip ShadeBridge`.

## Check on the Start screen

- The top **28 dp is solid black** over the stock bar, full width, edge to edge.
- Left cluster: battery (white outline + white level), then Wi-Fi arcs, then four
  bars. Charging adds the small plug; nothing turns green or red.
- Right: `HH:mm` in white 12 sp, with a 12 dp right inset. No stock clock, battery,
  Wi-Fi or signal icon visible in the band.
- Pulling down from the top still opens Action Centre / the shade (the strip is
  not touchable).
- Press power: the strip disappears on the lock screen; unlock and it returns.
  The lock overlay owns the screen; no second clock row on the lock screen.
- In a fullscreen app the window is still exactly 28 dp; it does not grow or push
  content. Touches below and inside the band pass through.

## Failure mode / risks

- **Overlay cannot be added** (service off, `addView` throws, no accessibility
  connection): `StatusStripService` logs `status strip not added; leaving the stock
  bar as is` and does nothing. No crash, no stock-bar change. Disabling the service
  removes the window; the process death also removes the window.
- **Clock double** if the blacklist is not applied (root command missing or
  overwritten) *and* the strip is absent/temporarily removed, e.g. on the keyguard
  or before the service rebinds. While the strip is up, opaque black covers the
  stock clock slot; the blacklist is the belt-and-suspenders for the gaps.
- **Overlay above dialogs**: an accessibility overlay is above app windows. The top
  28 dp of any app dialog/IME that draws at y=0 is covered. Nothing below 28 dp is.
- **Battery accuracy**: the sticky `ACTION_BATTERY_CHANGED` level is integer
  `level/scale`; the plug is driven by `BATTERY_STATUS_CHARGING/FULL`, not by a
  charge-current reading. It is a WP glyph, not a precision gauge.
- **Cutout**: the window stays 28 dp even if a cutout is taller; the rest of the
  stock bar is not covered by this window. ugglite has no cutout.
- **Signal permission**: without `READ_PHONE_STATE`, `getSignalStrength()` is
  denied and the strip deliberately draws four static bars; it never crashes.
- **Two services**: the strip is separate from the shade bridge, so the owner must
  enable both; one can be disabled without affecting the other.
