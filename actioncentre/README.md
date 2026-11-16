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
`android-36` the same script builds locally.

## Source map

| path | role |
|---|---|
| `ActionCentreActivity.java` | full-screen panel, tile wiring, settings intents, permission flows, slide transitions |
| `ShadeBridgeService.java` | optional accessibility service: system shade → BACK → this panel (`res/xml/shade_bridge.xml` config) |
| `QuickGrid.java` / `QuickTile.java` | 4-column square tile grid, custom flat drawing (no ripple, no elevation) |
| `StatusStripView.java` | our 30 dp operator/battery/clock/date strip |
| `NotificationRowView.java` | flat notification row, tap-to-open, swipe-left/X dismiss |
| `ActionCentreListener.java` | optional `NotificationListenerService` + cancel helpers |
| `NotificationRepository.java` | in-process notification cache shared by service and panel |
| `HandleView.java` | 16 dp accent bottom handle with 28x3 white grip |
| `DeviceState.java` | best-effort read-only state probes |
| `assets/fonts/` | Open Sans Light/Regular/SemiBold (OFL; licence bundled) |

## Shade gesture (optional accessibility bridge)

The panel offers `ВКЛЮЧИТЬ ЖЕСТ ЦЕНТРА ДЕЙСТВИЙ` under the links row. It opens
`Настройки → Специальные возможности`; the user enables **ztMetro Action Centre**.
Detection is by geometry, not class name: the SystemUI window covering ≥85 % of the
display is the shade (the device reports it as `android.widget.FrameLayout`); the small
status-bar window is ignored. BACK #1 goes out immediately and is the only BACK; the
service waits up to 250 ms for the full-screen shade window to disappear, then starts the
panel with `extra_instant=true` (no open slide, revealed as the shade closes). With the
service off nothing changes in the shade path. See
`../evidence/actioncentre/SHADE-BRIDGE.md` for the exact filter, logcat lines, the
expected timing and the rollback.

## Honest capability boundary

A non-privileged app cannot silently flip Wi-Fi, mobile data, Bluetooth or
airplane mode on Android 16. Those tiles show the best state the platform
exposes and open the matching Settings panel/page. Rotation lock and the
brightness cycle need the `WRITE_SETTINGS` appop; flashlight needs the runtime
`CAMERA` permission. The notification listener must be enabled by the user.

See `../evidence/actioncentre/REPORT.md` for the full capability table, the
layout mapping and the exact adb commands.
