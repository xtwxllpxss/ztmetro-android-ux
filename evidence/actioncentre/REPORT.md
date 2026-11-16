# Metro Action Centre — build and static verification report

Date: 2026-11-16 · Device target: Redmi Note 5A (ugglite), LineageOS 23.2 /
Android 16, 720x1280 @ 320 dpi (360x640 dp) · Package: `org.ztmetro.actioncentre`
· Scope: **static only** — the lead owns adb, so nothing here was installed or run.

## 1. Artifact

| field | value |
|---|---|
| APK | `repo/evidence/actioncentre/ztmetro-actioncentre.apk` (build output: `repo/actioncentre/build/out/ztmetro-actioncentre.apk`) |
| size | **329 275 bytes (321.6 KiB)** — limit 2 MiB |
| sha256 | `c3e3878d5cd18060fceed73800efd13f4c1742168c249827f9218eb76fc80ffb` |
| package / version | `org.ztmetro.actioncentre`, versionCode 1 / versionName 1.0 |
| SDK | minSdk 29, targetSdk 36, compileSdk 36 |
| signer | v3 signature, throwaway RSA-2048 key generated under `actioncentre/build/keystore/` (gitignored) |
| build | `repo/scripts/build-actioncentre.sh`: rsync to `hermes-ubuntu` (this run: `~/ztux/acfix`), then plain `aapt2` + `javac` (Java 8 bytecode, JDK 17) + `d8 --min-api 29` + `zipalign` + `apksigner`. No Gradle, no AndroidX, no network. |
| interaction build | notification tap / swipe-up close / shade timeline fixes (see §1.1); source as of 2026-11-16 14:43 MSK |

Static checks that passed (full log: `verify.txt`, 90 PASS / 0 FAIL):

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
  reports `android.widget.FrameLayout`): with the strip present it starts the
  activity immediately with `extra_instant=true` and sends at most one BACK after
  120 ms if the shade is still visible; without the strip it keeps the old
  BACK-first / 250 ms poll; see §1.1.3 and [`SHADE-BRIDGE.md`](SHADE-BRIDGE.md);
- `.TileAlias` activity-alias present and exported;
- permissions: `CAMERA`, `WRITE_SETTINGS`, `ACCESS_WIFI_STATE`,
  `ACCESS_NETWORK_STATE`, plus optional `READ_PHONE_STATE` (never requested at
  runtime; signal bars fall back to static); **no** `BLUETOOTH_CONNECT`, no
  location, no `SYSTEM_ALERT_WINDOW`;
- **no `androidx`** string/classes in `classes.dex`; all checked app classes present,
  and `dexdump -f` additionally lists `.ShadeBridgeService` and `onAccessibilityEvent`;
- `ac_slide_up` / `ac_slide_down` animation resources in the compiled table;
- launcher activity is **not** `HOME`;
- APK < 2 MiB; `apksigner verify` reports v3, one signer.

Evidence files: `aapt2-badging.txt`, `aapt2-manifest.xmltree.txt`,
`aapt2-resources.txt`, `aapt2-xmltree-shade_bridge.txt`, `dexdump-classes.txt`,
`apksigner-verify.txt`, `verify.txt`, `artifact-facts.txt`,
`ztmetro-actioncentre.apk.sha256`, `SHADE-BRIDGE.md`.

## 1.1 Interaction fixes in this build (device feedback)

Three reported defects were fixed in `ActionCentreActivity.java`,
`ActionCentreListener.java`, `Notif.java` and `ShadeBridgeService.java`. Anything
that needs a finger or adb is documented below as static reasoning plus the exact
log line it emits, so one device run can confirm each branch.

### 1.1.1 Notification rows: the tap sends the contentIntent and every branch logs

`onOpen()` used to call `contentIntent.send()` with no context and no fill-in
intent inside `catch (Throwable ignored)`. If the PendingIntent was
`FLAG_IMMUTABLE` or the launch was restricted, the row looked live and did
nothing, with no trace. It now calls

```java
pendingIntent.send(this, 0, new Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
```

inside try/catch, logs every failure, and falls back to the notification's first
action. Only a delivered intent closes the panel (same 220 ms slide as Back). With
neither a contentIntent nor an action the row does nothing visible and says so in
the log.

Android 16 notification intents are usually `FLAG_IMMUTABLE`; the platform then
ignores the fill-in intent's flags (the base intent still launches). The success
line records `immutable=true/false`, so a device run shows which case applies —
`immutable=true` plus no app opening points inside the posting app's
PendingIntent, not at this panel.

| # | path | condition | exact log (tag `ActionCentre`) | panel |
|---|---|---|---|---|
| 1 | content ok | contentIntent sent | `I/ActionCentre: tap key=… via contentIntent: send ok (immutable=false)` | closes |
| 2 | immutable | same, immutable PI | `I/ActionCentre: tap key=… via contentIntent: send ok (immutable=true, fill-in flags ignored by platform)` | closes |
| 3 | content null → action | no contentIntent, action[0] sent | `I/ActionCentre: tap key=…: contentIntent is null; falling back to action[0] '…'` then `I/ActionCentre: tap key=… via action[0] '…': send ok (immutable=…)` | closes |
| 4 | content fails → action | send throws, action[0] sent | `W/ActionCentre: tap key=…: contentIntent failed; falling back to action[0]` then the action send line | closes |
| 5 | both fail | send throws, no action | `W/ActionCentre: tap key=… via contentIntent: send failed: canceled (…)` then `W/ActionCentre: tap key=…: delivery failed; leaving panel open (use the row X to dismiss)` | stays |
| 6 | nothing to send | contentIntent + action both null | `W/ActionCentre: tap key=…: no contentIntent and no action; ignoring tap (panel stays)` | stays |
| 7 | delivered | any success | `I/ActionCentre: tap key=… via …: closing panel` | 220 ms slide down |

The per-row X still calls `cancelNotification(key)`; the listener now returns
whether the call went through and the row logs the result:

- `I/ActionCentre: dismiss key=…: cancelNotification ok; row removed`
- `W/ActionCentre: dismiss key=…: cancelNotification failed (listener absent or cancel threw); row removed optimistically`

Static reasoning why the old tap did nothing: the row *did* dispatch the click to
`onOpen` (the row is clickable and `onTouchEvent` calls `performClick()` for a
non-vertical gesture); the failure was inside the unobserved `send()` or a null
contentIntent, and the silent catch hid both. `adb logcat -s ActionCentre:V` now
names the failing branch on the first tap. If a tap produces **no `tap key=` line at
all**, the touch never reached `onOpen` (row/scroll arbitration or a mid-tap list
rebuild), which is a different defect from a failed send.

### 1.1.2 Swipe up to close, 1:1 handle, Back unchanged

`ActionCentreActivity.dispatchTouchEvent()` observes the gesture before children
and never consumes it. The close gesture is **armed** only where an upward drag
cannot be a list scroll:

- the panel's own top strip (`StatusStripView`, 50 dp, never scrollable);
- over the list while it is at scroll 0 and either the finger is on the empty tail
  below the last row or the list has nothing left to scroll
  (`!canScrollVertically(FOCUS_DOWN)`);
- the bottom handle keeps its own handler (excluded from the swipe arming so the
  two do not fight).

A 60 dp upward drag or an upward fling ≥ 1200 dp/s plays the normal 220 ms
slide-down and finishes. A scrollable list at the top keeps its scroll: a finger
up over the rows still scrolls the list, it does not close. The handle is now also
grabbed from an in-flight snap (`animate().cancel()` on DOWN) and tracks the finger
1:1 both ways; release below 40 dp snaps back open (260 ms), release past 40 dp
closes (220 ms). Back still calls `finish()` and uses the same 220 ms slide.

Arbitration matrix (what one gesture does):

| start point | list state | upward drag > 60 dp or fling | result |
|---|---|---|---|
| top strip (50 dp) | any | armed | closes, 220 ms slide |
| notification row / grid | `scrollY > 0` | not armed | list scrolls, no close |
| notification row / grid | at top, content scrollable | not armed | list scrolls, no close |
| notification row / grid | at top, content fits | armed | closes, 220 ms slide |
| empty tail below the last row | at top | armed | closes, 220 ms slide |
| bottom handle | any | its own 1:1 drag | release >40 dp down closes; up snaps back open |
| Back key | any | — | closes, 220 ms slide |

Logs:

- `I/ActionCentre: swipe-up close: drag up 67 dp (threshold 60 dp / 1200 dp/s); sliding panel down 220 ms`
- `I/ActionCentre: swipe-up close: fling up 3120 px/s (threshold 60 dp / 1200 dp/s); sliding panel down 220 ms`

### 1.1.3 Shade flash: panel first, BACK only after 120 ms

The strip path already had zero stock-shade frames: the 24 dp
`TYPE_ACCESSIBILITY_OVERLAY` consumes the top-edge gesture, so SystemUI never sees
it. The remaining flash was the window-state fallback (shade opened below the
strip, by adb, or by a long-press). It sent BACK first and only started the panel
after the shade window vanished, leaving a frame of black/launcher in between. The
fallback now starts the panel **immediately** with `NEW_TASK` +
`extra_instant=true`, so it has its first frame under the shade, then sends at most
one BACK at +120 ms if the shade window is still visible and the panel has not
taken focus. The 120 ms is the panel's draw budget; without it the reveal can show
the launcher.

Timeline (tag `ShadeBridge`), one line per event (values are the expected shape on
this build; exact milliseconds come from the device run):

```
I/ShadeBridge: shade detected: type=3 bounds=[0,0][720,1280] 100% of display 720x1280 last=com.android.launcher3; strip present -> panel first, BACK only if still visible at 120 ms
I/ShadeBridge: panel start at 3 ms (shade detected; panel drawn under the shade before BACK); strip=present, instant=true, NEW_TASK
I/ShadeBridge: shade still visible at 121 ms; GLOBAL_ACTION_BACK #1 at 121 ms returned true (cap 1)
I/ShadeBridge: panel visible 143 ms after detection (start +140 ms, BACK 121 ms, wall-clock delta 144 ms)
I/ShadeBridge: stock shade gone at 381 ms after detection (260 ms after BACK #1)
```

Skipped BACK / already gone:

```
I/ShadeBridge: BACK skipped at 121 ms: Action Centre already focused (shade window still listed)
I/ShadeBridge: no BACK needed: stock shade gone at 121 ms
I/ShadeBridge: stock shade gone at 190 ms after detection (no BACK sent)
```

Without the strip (overlay failed) the old BACK-first path is kept, and it now logs
the same timeline:

```
I/ShadeBridge: shade detected: …; no strip overlay -> collapsing with GLOBAL_ACTION_BACK
I/ShadeBridge: GLOBAL_ACTION_BACK #1 at 1 ms returned true (cap 1)
I/ShadeBridge: shade gone after 100 ms; starting Action Centre
I/ShadeBridge: panel start at 101 ms (shade window gone after BACK #1); strip=absent, instant=true, NEW_TASK
I/ShadeBridge: panel visible 160 ms after detection (start +59 ms, BACK 1 ms, wall-clock delta 161 ms)
```

Can this beat the stock animation? On the strip path, yes — SystemUI never receives
the gesture, so the stock shade contributes **0 frames**. On the fallback, no: the
shade is already being drawn before the accessibility event arrives, and BACK makes
SystemUI run its own close (≈260 ms on this build). Sending BACK at 0 ms would
finish the shade at ≈260 ms but usually before the panel's first frame, which is
the launcher flash; the 120 ms draw budget pushes the shade's disappearance to
≈380 ms but exposes **no blank frame**. Removing the stock shade on that path needs
a SystemUI hook, not this APK.

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
| dismiss | per-row close / swipe | per-row `×` 20 sp; horizontal swipe reveals and cancels past 64 dp; both call `cancelNotification(key)` and log the result (`dismiss key=…: cancelNotification ok/failed`) | `NotificationRowView.onTouchEvent()`, `onDismiss()` |
| empty state | `No notifications` in light type | **`Нет уведомлений`**, 17 sp Open Sans Light, 70 % white, 16 dp left, 22 dp top; second 11 sp line only when access is missing/connecting | `addEmptyState()` |
| bottom handle | 16 dp accent bar, 28×3 white grip | 16 dp `#0050EF`, grip 28×3 dp white; 1:1 drag both ways (grabs an in-flight snap), release <40 dp snaps open 260 ms, >40 dp closes 220 ms; tap closes | `HandleView.java`, `ActionCentreActivity.buildUi()` |
| motion | shade slide 260 ms | content `translate 100%→0` 260 ms on open, `0→100%` 220 ms on close; swipe up >60 dp or fling ≥1200 dp/s triggers the same 220 ms close; Back uses it too | `ActionCentreActivity`, `Motion.java` |

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

# 2. notification-listener access (Android 11+; the clean way)
adb shell cmd notification allow_listener \
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

### What to look for after install (the three reported defects)

```sh
# all three fixes log under these two tags
adb logcat -c
adb logcat -s ActionCentre:V ShadeBridge:V

# 1. tap: open the panel, tap a notification row
#    expect (any of the 1–4 success lines, then):
#      I/ActionCentre: tap key=… via contentIntent: send ok (immutable=…)
#      I/ActionCentre: tap key=… via …: closing panel
#    and the posting app in front; panel slides down over 220 ms.
#    if the row opens nothing, the log names the branch (send failed / action
#    fallback / no intent) — report that line.

# 2. swipe up: open the panel, swipe up from the top strip or the empty tail
#    expect:
#      I/ActionCentre: swipe-up close: drag up ≥60 dp … sliding panel down 220 ms
#    the panel slides down and closes. Swipe up over the rows with a long list:
#    the list must scroll instead (no swipe-up log).

# 3. strip path (shade not visible): with the bridge enabled, swipe down from the
#    very top edge; expect:
#      I/ShadeBridge: strip drag …: opening Action Centre (instant)
#      I/ShadeBridge: panel start at … (strip drag …); strip=present, instant=true, NEW_TASK
#      I/ShadeBridge: panel visible … after strip trigger (… BACK none …)
#    and NO "shade detected" / "GLOBAL_ACTION_BACK" lines.

# 3b. fallback path (shade got past the strip, e.g. swipe from 30 dp):
#    expect the panel-first timeline from §1.1.3: shade detected -> panel start
#    -> BACK at 120 ms (or "BACK skipped") -> panel visible -> stock shade gone.
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
  the lock screen and adb-triggered shades still show the stock shade: the
  fallback starts our panel under it and sends at most one BACK after 120 ms, so
  the blank frame is gone but the stock shade's own ~260 ms close is still on
  screen (~380 ms until it fully disappears) — see §1.1.3. With the bridge off
  the stock shade is untouched. It is still not the lock-screen shade; a
  genuinely different shade would need a SystemUI hook (level C/D in
  `design/components/action-centre.md`).
- **Self-granting the listener**: `BIND_NOTIFICATION_LISTENER_SERVICE` is held by
  the system; only the user (or adb/shell on a debuggable build) can enable it.
- **Reading Bluetooth state reliably**: `BLUETOOTH_CONNECT` is dangerous and was
  intentionally not requested; without it the tile's on/off is best-effort.
- **Guaranteed delivery**: the listener can be killed under memory pressure; the
  panel then shows the empty state rather than stale/blank rows.
- **All-settings page parity**: the panel uses the system's Settings pages for
  Wi-Fi/data/Bluetooth/airplane; their look is stock Material, outside this APK.

## 7. Not verified / open risks

1. **No runtime test of the interaction fixes.** The APK was rebuilt and statically
   verified, but nothing here was installed: the tap paths, the 60 dp/fling
   thresholds and the 120 ms shade timeline are reasoned + instrumented, not
   measured. Run the three logcat checks in §5 on the device first; the log lines
   are the acceptance evidence. The layout metrics remain static, so the first
   device acceptance must also confirm the 4×2 grid, the typography, and that the
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
9. **Shade bridge: two mechanisms.** The device showed the shade window as
   `android.widget.FrameLayout` (geometry fallback) and that a late BACK could
   dismiss the panel. Current design: a 24 dp transparent
   `TYPE_ACCESSIBILITY_OVERLAY` strip consumes the top-edge gesture (stock shade
   never opens on the common path); for a shade that opened below the strip the
   fallback now starts the panel first and sends at most one BACK at +120 ms, then
   only observes. Tap behaviour changes: taps on the status strip open the panel
   instead of reaching clock/battery. A swipe starting below the strip still shows
   the stock shade (its own close is ≈260 ms), and the overlay needs a device check
   for touch delivery/keyguard gating. Verify with
   `adb logcat -s ShadeBridge:V` and screenrecord. See
   [`SHADE-BRIDGE.md`](SHADE-BRIDGE.md) §2–7.
10. **Gesture row is a new visual element** under the WP8.1 links row. It was not in
    the reference frame; if it reads as chrome at acceptance, hide it when the bridge
    is on (it already dims to 50 % white and changes label).
11. **Immutable PendingIntents.** Most Android 16 notifications use
    `FLAG_IMMUTABLE`; the platform then ignores the NEW_TASK fill-in and launches
    the base intent. The tap fix still logs `immutable=true` and falls back to
    action[0]; if a tap still does nothing with `send ok` in the log, the block is
    inside the posting app's intent, and no third-party panel can bypass it (a
    system `SYSTEM_ALERT_WINDOW`/SystemUI hook would be needed).
12. **Concurrent work in the same app tree.** While this task ran, other tasks added
    `RecentsActivity`, `UnlockProxyActivity`, `UnlockTimeline`, `LockClockPanel`,
    lock-overlay edits, a window-animation style and a rework of this Activity's
    open/close to window transitions. This APK is the build at 14:43 MSK of the source
    as it stood then: the three interaction fixes with the in-content 260/220 ms
    slide. In the merged tree `finish()` (tap, Back, swipe-up) plays the window
    close animation instead; the 220 ms close is preserved, but the integration
    build must re-run the §5 checks, above all that a bridge launch with
    `EXTRA_INSTANT` stays instant (no double animation under the shade).
