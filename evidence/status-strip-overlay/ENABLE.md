# WP status strip — build, enable, checks, failure mode

## Artifact

- `repo/actioncentre/build/out/ztmetro-actioncentre.apk`
- `repo/evidence/status-strip-overlay/ztmetro-actioncentre.apk` (same bytes)
- size **325 179 bytes** (318 KiB)
- sha256 **4179ae089133527988f9f26017283ed34e6b7e282fdd7e98cc640c0ce0a973b6**
- built on `hermes-ubuntu:~/ztux/statusstrip` with `~/android-sdk` (android-36,
  build-tools 36.0.0) and `~/android-jdk`; plain `aapt2 + javac + d8 + apksigner`,
  no Gradle, no AndroidX, no network.
- static checks: `verify.txt` and `status-strip-verify.txt` in this directory. The
  APK is signed and `apksigner verify` passes (`apksigner-verify.txt`); signer
  certificate SHA-256 `303ddd02...0e2e78ce` is the original actioncentre key, so it
  updates the installed app instead of failing with a signature mismatch.
- `preview-720x56.png` / `preview-1440x112.png` are a **static render of the view
  geometry**, not a device screenshot. No adb was available in this task.

## What is added

`StatusStripService` (a second, optional accessibility service) adds a
`TYPE_ACCESSIBILITY_OVERLAY` window: `MATCH_PARENT x 28 dp`, `gravity TOP|START`,
black `#000000`, flags `NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_IN_SCREEN |
LAYOUT_NO_LIMITS`, `layoutInDisplayCutoutMode=ALWAYS` on API 30+. `NOT_TOUCHABLE`
means it does not steal the top-edge gesture: the 24 dp transparent strip in
`ShadeBridgeService` below it still receives touches. The strip is suppressed while
`ActionCentreActivity` is resumed, because that screen hides the system bars and
draws its own in-panel strip; it is re-added when the panel stops.

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
   `su 0 pm grant org.ztmetro.actioncentre android.permission.READ_PHONE_STATE`
3. Settings → Accessibility → enable **ztMetro status strip**. Keep **ztMetro Action
   Centre** (the gesture bridge) enabled as well; they are separate services and the
   strip does not replace the gesture bridge.
4. Hide the stock slots with the verified LineageOS 23.2 names (see
   `ICON-BLACKLIST.md`):
   ```sh
   su 0 settings put secure icon_blacklist "clock,battery,wifi,mobile,stacked_mobile,rotate,headset"
   ```
5. Verify: `su 0 settings get secure icon_blacklist` must echo that list.
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
- **Stock inset**: without the framework status-strip RRO (`status_bar_height* =
  28 dp`), Android still insets app content by 24 dp. The 28 dp strip then covers
  4 dp of edge-to-edge content; it never extends past its own 28 dp band. The
  RRO/status module is the matching half for content insets.
- **Signal permission**: without `READ_PHONE_STATE`, `getSignalStrength()` is
  denied and the strip deliberately draws four static bars; it never crashes.
- **Two services**: the strip is separate from the shade bridge, so the owner must
  enable both; one can be disabled without affecting the other.
