# ztmetro Action Centre (org.ztmetro.actioncentre)

A standalone, full-screen Metro/WP8.1 Notification Centre for the Redmi Note 5A
running LineageOS 23.2 (Android 16). It is our own panel, not a SystemUI
modification: flat black, 4-column square quick-action tiles, a links row, and
notification rows fed by a `NotificationListenerService`.

Why a separate app: Android 16's shade layout (Compose QS container, 2-column
tiles) is built in SystemUI code. An RRO can restyle it but cannot rearrange the
grid or add the WP links row. This APK is level **A** of
`design/components/action-centre.md`; the real system shade remains the fallback.

## Build

No Gradle, no AndroidX, no network. Plain `javac` + `aapt2` + `d8` +
`apksigner`.

```sh
repo/scripts/build-actioncentre.sh
```

On this workstation (no local Android SDK) the script rsyncs the sources to
`hermes-ubuntu:~/ztux/actioncentre`, builds there with
`~/android-sdk` + `~/android-jdk`, and copies the APK plus static-verification
evidence back to:

- `repo/actioncentre/build/out/ztmetro-actioncentre.apk` (unsigned build output
  dir is gitignored)
- `repo/evidence/actioncentre/` (APK, badging, manifest tree, signature report,
  verification log, sha256)

Set `ACTIONCENTRE_REMOTE` to use another build host. On a Linux host that has
`android-36` the same script builds locally. `ACTIONCENTRE_REMOTE_DIR` selects the
remote work directory (the Metro lock overlay run used `ztux/lock`), and
`EVIDENCE_DIR` selects where the APK and the static-verification files are copied
(the lock overlay run used `repo/evidence/lock-overlay/`).

## Source map

| path | role |
|---|---|
| `ActionCentreActivity.java` | full-screen panel, tile wiring, settings intents, permission flows, slide transitions, swipe-up close + 1:1 handle |
| `ShadeBridgeService.java` | optional accessibility service: top-edge strip / system shade → this panel, plus the lock overlay hook (`res/xml/shade_bridge.xml` config) |
| `LockOverlayController.java` | keyguard detection + lock windows: display, bottom swipe strip, unlock proxy hand-off, above/below-keyguard window decision, lifecycle, fail-safe |
| `LockOverlayView.java` | the black lock surface: per-app counts, hint, `setAuthPanel()` seam |
| `LockClockPanel.java` | the clock/date block, separate for a future custom PIN pad |
| `UnlockProxyActivity.java` | transparent `showWhenLocked` helper: dismiss a non-secure keyguard or launch the system credential screen |
| `UnlockTimeline.java` | `UnlockTimeline` wall-clock log for swipe → proxy → credential → USER_PRESENT → removal |
| `QuickGrid.java` / `QuickTile.java` | 4-column square tile grid, custom flat drawing (no ripple, no elevation) |
| `StatusStripView.java` | our 50 dp status strip (glyph row + operator/date/charge) |
| `NotificationRowView.java` | flat notification row, tap-to-open (contentIntent + action[0] fallback, logged), swipe-left/X dismiss (logged) |
| `ActionCentreListener.java` | optional `NotificationListenerService` + cancel helpers |
| `NotificationRepository.java` | in-process notification cache shared by service and panel |
| `HandleView.java` | 16 dp accent bottom handle with 28x3 white grip |
| `DeviceState.java` | best-effort read-only state probes |
| `assets/fonts/` | Open Sans Light/Regular/SemiBold (OFL; licence bundled) |

## Shade gesture (optional accessibility bridge)

The panel offers `ВКЛЮЧИТЬ ЖЕСТ ЦЕНТРА ДЕЙСТВИЙ` under the links row. It opens
`Настройки → Специальные возможности`; the user enables **ztMetro Action Centre**.
The common path is a 32 dp transparent `TYPE_ACCESSIBILITY_OVERLAY` strip over the status
bar: a top-edge swipe/tap is consumed there and opens the panel, so the stock shade never
appears and there is no flicker. The strip window is created touchable and is never created
with `FLAG_NOT_TOUCHABLE`; that flag is added only while the keyguard is actually locked and
removed on every `ACTION_USER_PRESENT` (plus the API 33 keyguard listener and a throttled
getter safety net), with every change and every touch logged under the `ShadeTouch` tag. Taps on the status strip therefore no longer reach the
clock/battery. If the shade is opened another way, a geometry fallback (SystemUI window
covering ≥85 % of the display; the device reports it as `android.widget.FrameLayout`)
starts the panel immediately with `extra_instant=true` (so its first frame is under the
shade) and sends at most one BACK after 120 ms if the shade is still visible. Without the
strip the older BACK-first / 250 ms poll path is kept.
With the service off nothing changes in the shade path. See
`../evidence/actioncentre/SHADE-BRIDGE.md` for the overlay/flags, logcat lines, timing and
rollback.

## Lock overlay (optional, same accessibility service)

While `KeyguardManager.isKeyguardLocked()` is true, the service adds a full-screen
opaque `TYPE_ACCESSIBILITY_OVERLAY` (AOSP layer 31, above the keyguard's
notification-shade window at layer 17) and draws the ztMetro lock screen over it:
92 sp Light clock, two 30 sp date lines, up to five app icons + counts in the lower
third, and `↑ СВАЙП ВВЕРХ`. No status row, no Material clock, no notification card,
no rounded corners. The display window is
`FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE|FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS`,
`PixelFormat.OPAQUE`; the only touchable part is a transparent bottom strip
(200–280 dp).

A swipe up keeps the black surface attached and starts the transparent
`UnlockProxyActivity` (`showWhenLocked`, no animation, excluded from recents).
A non-secure lock is dismissed with `KeyguardManager.requestDismissKeyguard`; a
secure lock opens the system `createConfirmDeviceCredentialIntent` screen, which
is not the stock lock screen. The overlay is removed only on `USER_PRESENT` or
when the credential window is actually above it, so the stock lock screen is
never deliberately revealed during the swipe. If either call is refused, the
proxy reveals the stock keyguard and logs why. Timeline:
`adb logcat -s UnlockTimeline LockOverlay UnlockProxy`.

Same one-time setup as the shade gesture: **Settings → Accessibility → ztMetro
Action Centre = ON**. The counts row additionally needs the notification listener.
If the ROM refuses the window, the failure is logged and the stock keyguard stays;
nothing else is changed. Full facts, fail-safe table and on-device checks:
`../evidence/lock-overlay/README.md`.

## Honest capability boundary

A non-privileged app cannot silently flip Wi-Fi, mobile data, Bluetooth or
airplane mode on Android 16. Those tiles show the best state the platform
exposes and open the matching Settings panel/page. Rotation lock and the
brightness cycle need the `WRITE_SETTINGS` appop; flashlight needs the runtime
`CAMERA` permission. The notification listener must be enabled by the user.

See `../evidence/actioncentre/REPORT.md` for the full capability table, the
layout mapping and the exact adb commands.
