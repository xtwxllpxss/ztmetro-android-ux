# Metro Action Centre — build and static verification report

Date: 2026-11-16 · Device target: Redmi Note 5A (ugglite), LineageOS 23.2 /
Android 16, 720x1280 @ 320 dpi (360x640 dp) · Package: `org.ztmetro.actioncentre`
· Scope: **static only** — the lead owns adb, so nothing here was installed or run.

## 1. Artifact

| field | value |
|---|---|
| APK | `repo/evidence/actioncentre/ztmetro-actioncentre.apk` (build output: `repo/actioncentre/build/out/ztmetro-actioncentre.apk`) |
| size | **304 621 bytes (297.5 KiB)** — limit 2 MiB |
| sha256 | `2cc128455fba0e06af4a6bf372eb0a0225800193a3fcf1e1a4b596e700b55fa5` |
| package / version | `org.ztmetro.actioncentre`, versionCode 1 / versionName 1.0 |
| SDK | minSdk 29, targetSdk 36, compileSdk 36 |
| signer | v3 signature, throwaway RSA-2048 key generated under `actioncentre/build/keystore/` (gitignored) |
| build | `repo/scripts/build-actioncentre.sh`: rsync to `hermes-ubuntu` (this run: `~/ztux/actbridge`), then plain `aapt2` + `javac` (Java 8 bytecode, JDK 17) + `d8 --min-api 29` + `zipalign` + `apksigner`. No Gradle, no AndroidX, no network. |

Static checks that passed (full log: `verify.txt`, 50 PASS / 0 FAIL):

- package name, application label `Центр действий`, launcher activity
  `org.ztmetro.actioncentre.ActionCentreActivity`, minSdk 29 / targetSdk 36;
- listener service `.ActionCentreListener` with
  `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` and the
  `android.service.notification.NotificationListenerService` action;
- **accessibility bridge** `.ShadeBridgeService` with
  `android.permission.BIND_ACCESSIBILITY_SERVICE`, the
  `android.accessibilityservice.AccessibilityService` action and the
  `res/xml/shade_bridge.xml` config (window-state events only, no package filter
  so the previous foreground app can be tracked,
  `flagRetrieveInteractiveWindows|flagReportViewIds`). The common path is a
  **24 dp transparent `TYPE_ACCESSIBILITY_OVERLAY` strip** above the status bar:
  a top-edge drag/tap is consumed there, so the stock shade never opens. The
  window-state path remains as fallback (geometry, not class name: the device
  reports `android.widget.FrameLayout`): one BACK, poll the full-screen SystemUI
  window up to 250 ms, then start the activity with `extra_instant=true` when it
  is gone or at the cap (open travel skipped); see
  [`SHADE-BRIDGE.md`](SHADE-BRIDGE.md);
- `.TileAlias` activity-alias present and exported;
- permissions: `CAMERA`, `WRITE_SETTINGS`, `ACCESS_WIFI_STATE`,
  `ACCESS_NETWORK_STATE` only; **no** `BLUETOOTH_CONNECT`, no location, no
  `READ_PHONE_STATE`, no `SYSTEM_ALERT_WINDOW`;
- **no `androidx`** string/classes in `classes.dex`; all checked app classes present,
  and `dexdump -f` additionally lists `.ShadeBridgeService` and `onAccessibilityEvent`;
- `ac_slide_up` / `ac_slide_down` animation resources in the compiled table;
- launcher activity is **not** `HOME`;
- APK < 2 MiB; `apksigner verify` reports v3, one signer.

Evidence files: `aapt2-badging.txt`, `aapt2-manifest.xmltree.txt`,
`aapt2-resources.txt`, `aapt2-xmltree-shade_bridge.txt`, `dexdump-classes.txt`,
`apksigner-verify.txt`, `verify.txt`, `artifact-facts.txt`,
`ztmetro-actioncentre.apk.sha256`, `SHADE-BRIDGE.md`.

## 2. Reference frame and its condition

`repo/evidence/ux-compare/refs/wp81-notification-center.png` is **not a PNG**:
it is a 3 220-byte Wikimedia "Error" HTML page saved with a `.png` name (the copy
under `build/wp-ux-refs-2026-11-15/img/` is the same HTML). The usable visual
target is the right half of `repo/evidence/ux-compare/cmp-shade.png` (the WP8.1
style render: 4×2 tiles, links row, empty state, accent bottom handle), backed by
`design/components/action-centre.md`, which quotes the exact geometry from
`ztmetro.c:6230-6553`. A genuine frame should be re-downloaded before a
pixel-level comparison; this build follows the frame layout and the ztmetro
metrics, not a pixel trace of the corrupt file.

## 3. Layout mapping

Reference (WP8.1, right half of `cmp-shade.png`, read at 480 px panel width) →
implementation at 360 dp panel width (720 px @ 320 dpi).

| reference element | reference | implementation | code |
|---|---|---|---|
| outer surface | flat black, square, no chrome | `#000000` window background; no elevation, no radius; system bars hidden | `styles.xml` `Theme.ActionCentre`, `ActionCentreActivity.hideSystemBars()` |
| top strip | operator left; battery/clock/date right, small type | **30 dp** strip: operator 12 sp `#B3FFFFFF` at x=12 dp; right cluster date `dd.MM` + clock `HH:mm` in 12 sp (`#B3FFFFFF` / `#FFFFFF`), drawn battery 19×9 dp + percent | `StatusStripView.java` |
| tile grid | 4 columns, square cells, ~4 px gutters | 4 columns; side margin 4 dp, gutter 4 dp; cell = (360−8−12)/4 = **85 dp** square (170 px); grid top pad 8 dp, bottom 4 dp | `QuickGrid.java` |
| tile fill | off dark grey / on cobalt | off `#1F1F1F`, on `#0050EF`; pressed `#333333` / `#1A5FE0`; plain colour change, no ripple | `QuickTile.onDraw()`, `U.java` |
| tile glyph | white, 24–28 dp | 26 dp white vector glyph, centred, 13 dp from tile top | `QuickTile`, `res/drawable/ic_*.xml` |
| tile label | small caps at lower left | 10 sp SemiBold caps, left x=7 dp, baseline h−8 dp, 70 % white (white on accent); falls back to 9 sp when it would overflow | `QuickTile.onDraw()` |
| tiles | 8 actions | Wi-Fi, mobile data, Bluetooth, airplane, rotation lock, flashlight, brightness (label shows current %), all settings | `ActionCentreActivity.buildTiles()` |
| links row | `CLEAR ALL` left, `ALL SETTINGS` right | 36 dp row; 12 sp SemiBold caps, 0.08 letter-spacing, 70 % white, pressed accent; `CLEAR ALL` has the × prefix from the frame | `buildLinksRow()` |
| gesture link | — (new row, no WP8.1 equivalent) | 36 dp row under the links row; 10 sp SemiBold caps, 0.08 letter-spacing, ellipsized; accent when the bridge is off, 50 % white when on; tap opens Settings > Accessibility | `ActionCentreActivity.buildGestureRow()` |
| notifications | flat rows on black, light type, app grouping | rows: 12/9/4/9 dp padding; sender 13 sp Regular white + time 11 sp 50 %; title 13 sp Light white; body 12 sp Light 70 %, 2 lines; app label 10 sp SemiBold accent caps above each group | `NotificationRowView.java`, `addAppLabel()` |
| dismiss | per-row close / swipe | per-row `×` 20 sp; horizontal swipe reveals and cancels past 64 dp; both call `cancelNotification(key)` | `NotificationRowView.onTouchEvent()` |
| empty state | `No notifications` in light type | **`Нет уведомлений`**, 17 sp Open Sans Light, 70 % white, 16 dp left, 22 dp top; second 11 sp line only when access is missing/connecting | `addEmptyState()` |
| bottom handle | 16 dp accent bar, 28×3 white grip | 16 dp `#0050EF`, grip 28×3 dp white; tap closes | `HandleView.java` |
| motion | shade slide 260 ms | window `translate 100%→0` 260 ms on open, `0→100%` 220 ms on close, plus `overrideActivityTransition` on API 34+; predictive back is opted out so Back uses the same close slide | `res/anim/ac_*.xml`, `finish()` |

Font: the panel is not dependent on the system font module — Open Sans Light /
Regular / SemiBold (OFL, licence bundled) are in `assets/fonts/`. That gives the
same light Cyrillic face the project is adopting, on any device.

## 4. Honest toggle capability table

| tile | state read | tap action | can the app switch it silently? | limitation |
|---|---|---|---|---|
| Wi-Fi | `WifiManager.isWifiEnabled()`, then `getWifiState()` (normal `ACCESS_WIFI_STATE`) | `Settings.Panel.ACTION_WIFI` (Android internet panel), fallback `ACTION_WIFI_SETTINGS` | **no** | `setWifiEnabled` is privileged/blocked for third-party apps on 16; we only reflect state |
| mobile data | `Settings.Global "mobile_data"`, fallback: active network has `TRANSPORT_CELLULAR` | `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`, fallback `ACTION_WIRELESS_SETTINGS` | **no** | the public setting can be unreadable on some builds; fallback means "on" only when a cellular network is actually active |
| Bluetooth | `BluetoothAdapter.isEnabled()` inside try/catch, fallback `Settings.Global "bluetooth_on"` | `ACTION_BLUETOOTH_SETTINGS` | **no** | on Android 12+ `isEnabled()` needs dangerous `BLUETOOTH_CONNECT`, which we deliberately do **not** request; state is best-effort, the settings page is authoritative |
| airplane | `Settings.Global.AIRPLANE_MODE_ON` (public read) | `ACTION_AIRPLANE_MODE_SETTINGS` | **no** | read is reliable; toggling is system-only |
| rotation lock | `Settings.System.ACCELEROMETER_ROTATION` (0 = locked) | `Settings.System.putInt(...)` — real toggle | **yes**, with `WRITE_SETTINGS` appop | no appop → `ACTION_MANAGE_WRITE_SETTINGS` request screen; grant with adb below |
| flashlight | local state, synced by `CameraManager.registerTorchCallback` | `CameraManager.setTorchMode(cameraId, on)` — real toggle | **yes**, with runtime `CAMERA` | first tap requests `CAMERA`; no flash hardware → dimmed tile + toast; camera-in-use → "занят камерой" |
| brightness | `Settings.System.SCREEN_BRIGHTNESS` (0–255 → %) | cycle 25→50→75→100→25 via `putInt`, also sets `SCREEN_BRIGHTNESS_MODE_MANUAL` | **yes**, with `WRITE_SETTINGS` appop | no appop → opens `ACTION_DISPLAY_SETTINGS` (per spec), does not fake a change; auto-brightness is switched to manual by a successful cycle |
| all settings | — | `ACTION_SETTINGS` | n/a | pure link |

Permissions: `CAMERA` is the only dangerous (runtime) permission.
`WRITE_SETTINGS` is a special appop and does not raise a runtime dialog; it must
be granted in Settings or by adb. `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE`
are normal install-time permissions. The notification listener is a bound
system service the user must enable; the app cannot enable it itself.

## 5. adb commands for the lead

```sh
# 1. install (host path; adjust if you copy the APK elsewhere)
adb install -r repo/evidence/actioncentre/ztmetro-actioncentre.apk

# 1b. shade bridge: preferred enable path is the UI, in the panel tap
#     "ВКЛЮЧИТЬ ЖЕСТ ЦЕНТРА ДЕЙСТВИЙ" ->
#     Настройки -> Специальные возможности -> ztMetro Action Centre -> on.
#     Android 13+ sideload guard: if the toggle is greyed out as "Restricted
#     setting", allow it once in Настройки -> Приложения -> Центр действий ->
#     ⋮ -> Разрешить ограниченные настройки, or:
adb shell appops set org.ztmetro.actioncentre ACCESS_RESTRICTED_SETTINGS allow
#     adb alternative (REPLACES the list; read it first and keep existing
#     entries colon-separated):
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services \
  "org.ztmetro.actioncentre/org.ztmetro.actioncentre.ShadeBridgeService"
adb shell settings put secure accessibility_enabled 1

# 1c. watch the bridge (tag is exactly ShadeBridge)
adb logcat -s ShadeBridge:V

# 2. notification-listener access (Android 11+; the clean way)adb shell cmd notification allow_listener \
  org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreListener

# 2b. fallback if the cmd is unavailable — never clobber other listeners:
adb shell settings get secure enabled_notification_listeners
# if the value above is empty, use this; otherwise prefix the existing value
# with "<existing>:" so other listeners stay enabled:
adb shell settings put secure enabled_notification_listeners \
  "org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreListener"

# 3. WRITE_SETTINGS appop for rotation lock + brightness cycle
adb shell appops set org.ztmetro.actioncentre WRITE_SETTINGS allow

# 4. optional: pre-grant CAMERA instead of tapping the runtime dialog
adb shell pm grant org.ztmetro.actioncentre android.permission.CAMERA

# 5. launch the panel (normal launcher entry)
adb shell am start -n org.ztmetro.actioncentre/.ActionCentreActivity

# 5b. launch through the tiny Start tile alias
adb shell am start -n org.ztmetro.actioncentre/.TileAlias

# checks
adb shell settings get secure enabled_notification_listeners
adb shell settings get secure enabled_accessibility_services
adb shell appops get org.ztmetro.actioncentre WRITE_SETTINGS
adb shell dumpsys package org.ztmetro.actioncentre | grep -E 'versionName|granted=true'

# undo (shade bridge: switch it off in Settings > Accessibility first)
adb shell cmd notification disallow_listener \
  org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreListener
adb shell appops set org.ztmetro.actioncentre WRITE_SETTINGS default
```

If the listener command reports success but the panel still says
`Доступ к уведомлениям не выдан`, open **Settings → Notifications → Device &
app notifications** once; the panel also has a tap target for that page. Reopen
the panel afterwards — `requestRebind` runs on start/resume.

## 6. What is not possible without system privilege

- **Silent toggles**: Wi-Fi, mobile data, Bluetooth and airplane mode cannot be
  flipped by a third-party app on Android 16. `WifiManager.setWifiEnabled`,
  `BluetoothAdapter.enable/disable`, `ConnectivityManager.setMobileDataEnabled`
  and airplane-mode writes are privileged or removed; reflection into hidden
  APIs is unstable and would still require system/root. The tiles open the
  matching system panel/page and show the state the platform exposes.
- **Being the shade**: this is a task activity, not SystemUI. The accessibility
  bridge owns the top 24 dp with a `TYPE_ACCESSIBILITY_OVERLAY` strip, so the
  top-edge gesture opens the panel without SystemUI ever drawing the stock shade
  (see [`SHADE-BRIDGE.md`](SHADE-BRIDGE.md)). A swipe starting below the strip,
  the lock screen and adb-triggered shades still use the one-BACK fallback, and
  with the bridge off the stock shade is the fallback. It is still not the
  lock-screen shade; a genuinely different shade would need a SystemUI hook
  (level C/D in `design/components/action-centre.md`).
- **Self-granting the listener**: `BIND_NOTIFICATION_LISTENER_SERVICE` is held by
  the system; only the user (or adb/shell on a debuggable build) can enable it.
- **Reading Bluetooth state reliably**: `BLUETOOTH_CONNECT` is dangerous and was
  intentionally not requested; without it the tile's on/off is best-effort.
- **Guaranteed delivery**: the listener can be killed under memory pressure; the
  panel then shows the empty state rather than stale/blank rows.
- **All-settings page parity**: the panel uses the system's Settings pages for
  Wi-Fi/data/Bluetooth/airplane; their look is stock Material, outside this APK.

## 7. Not verified / open risks

1. **No runtime test.** No install, no launch, no screenshot, no NotificationListener
   binding was attempted (adb ownership). The layout metrics are static; the first
   device acceptance must confirm the 4×2 grid, the typography, and that the
   panel animates without a black flash.
2. **Brightness appop / manual mode.** A successful cycle sets
   `SCREEN_BRIGHTNESS_MODE_MANUAL`; this is the correct way to make the value
   visible, but it disables auto-brightness until the user re-enables it. If that
   is undesirable, drop the mode write and only change `SCREEN_BRIGHTNESS`.
3. **Bottom handle vs gesture nav.** A 16 dp handle at the very bottom competes
   with the gesture-navigation home swipe; back still closes the panel. Consider
   raising it above the gesture inset if acceptance finds it hard to hit.
4. **Reference frame is corrupt** (see §2); pixel comparison must wait for a
   genuine WP8.1 frame.
5. **Tile alias visibility.** `.TileAlias` is exported with the custom action
   `org.ztmetro.actioncentre.OPEN_TILE` and is intentionally *not* a second
   app-drawer icon. The Start launcher must be given that component/action; if a
   drawer icon is wanted instead, add `MAIN`/`LAUNCHER` to the alias.
6. **State freshness.** Wi-Fi/Bluetooth/airplane tiles refresh on panel
   resume and after an in-panel toggle; an external change while the panel stays
   open is not observed until the next resume.
7. **Build key.** The signing key lives only in the gitignored build dir; a clean
   rebuild re-signs with a new key (fine for sideload, not for Play updates).
8. **No new tests** were added (per project rules); this report is build +
   static-manifest evidence, not a runtime acceptance.
9. **Shade bridge moved to a top-edge accessibility overlay.** The device showed the
   shade window as `android.widget.FrameLayout` (geometry fallback) and, later, that a
   second BACK dismissed the panel once it had focus. Current design: a 24 dp
   transparent `TYPE_ACCESSIBILITY_OVERLAY` strip consumes the top-edge gesture, so the
   stock shade never opens on the common path; the geometry fallback remains for other
   opens (one BACK, 50 ms poll, 250 ms cap, instant panel, no later BACK). Tap behaviour
   changes: taps on the status strip open the panel instead of reaching clock/battery.
   A swipe starting below the strip still hits the fallback (residual shade animation),
   and the overlay may need a device check for touch delivery/keyguard gating. Verify
   with `adb logcat -s ShadeBridge:V` and screenrecord. See
   [`SHADE-BRIDGE.md`](SHADE-BRIDGE.md) §2, §5–7.
10. **Gesture row is a new visual element** under the WP8.1 links row. It was not in
    the reference frame; if it reads as chrome at acceptance, hide it when the bridge
    is on (it already dims to 50 % white and changes label).
