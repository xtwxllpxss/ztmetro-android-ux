# Shade → Action Centre bridge (AccessibilityService)

**Verdict.** `ShadeBridgeService` turns the system shade gesture into our panel without
SystemUI changes. On this LineageOS 23.2 build the shade window reports as
`android.widget.FrameLayout`, so detection does **not** use the class name: it finds the
expanded shade as the SystemUI window whose bounds cover at least 85 % of the display
(the status bar is the small SystemUI window). It sends **one** `GLOBAL_ACTION_BACK`,
polls that window every 50 ms for at most **250 ms**, and then starts
`ActionCentreActivity` with `extra_instant=true` — either when the shade window is gone or
at the 250 ms cap. There is **no recovery BACK**: a BACK sent after the panel starts would
dismiss our own focused panel (observed on device). The service is off by default; with it
off the app adds nothing to the shade path.
**Scope**: the APK was built and statically verified on `hermes-ubuntu`; the class-name
mismatch, the geometry trigger and the late-BACK panel dismissal were observed by the lead
on the device. This revision removes the recovery BACK entirely.

## 1. Artifact and static proof

| field | value |
|---|---|
| APK | [`ztmetro-actioncentre.apk`](ztmetro-actioncentre.apk) (build: `actioncentre/build/out/ztmetro-actioncentre.apk`) |
| size | **304 621 bytes (297.5 KiB)** |
| sha256 | `f1cc744b7efb28b27157dcf3e14b49514d7f2d64207d9b500c105f57058274c5` |
| package / version | `org.ztmetro.actioncentre` 1 (1.0), unchanged |
| service | `org.ztmetro.actioncentre.ShadeBridgeService` |
| config | `res/xml/shade_bridge.xml`, referenced by the service `meta-data` |
| bind permission | `android.permission.BIND_ACCESSIBILITY_SERVICE` (service attribute, **no new `uses-permission`**) |
| build | same script: `aapt2` + `javac` (Java 8) + `d8 --min-api 29` + `zipalign` + `apksigner`; no Gradle/AndroidX/network |

`verify.txt` has **48 PASS / 0 FAIL**, including: service + bind permission + intent action +
config meta-data; `typeWindowStateChanged` (`0x20`), `feedbackGeneric` (`0x10`), flags
`0x51`, `canRetrieveWindowContent=true`; **no** `packageNames` filter (needed for previous
foreground tracking); no new `uses-permission`; `dexdump -f` lists `ShadeBridgeService`,
`onAccessibilityEvent`, the geometry detector `findFullscreenSystemUiWindow`, the
panel-visible timing hook `notifyPanelVisible`, the packaged `extra_instant` extra, and a
regression guard that the service contains **exactly one** `performGlobalAction` call.

## 2. Detection and action sequence

The service receives only `TYPE_WINDOW_STATE_CHANGED` (no content events) for all packages.
Non-SystemUI events only update `lastForegroundPackage`; nothing is read from those apps.

| # | step | implementation |
|---|---|---|
| 1 | shade-window event | `event.getPackageName() == com.android.systemui` |
| 2 | geometry | `getWindows()`, keep `TYPE_SYSTEM`/`TYPE_APPLICATION`, root package `com.android.systemui` |
| 3 | fullscreen test | window bounds area ≥ **85 %** of `getMaximumWindowMetrics()`; status-bar-sized windows are ignored |
| 4 | throttle | 1500 ms since the last successful trigger |
| 5 | keyguard | `KeyguardManager.isKeyguardLocked()` → skip; unreadable lock state counts as locked |
| 6 | own panel | focused window's root package is ours → skip |
| 7 | previous app | `lastForegroundPackage == org.ztmetro.actioncentre` **and** our panel window is still on screen → skip (window check prevents a stale record blocking the bridge after the panel is closed) |
| 8 | collapse | `performGlobalAction(GLOBAL_ACTION_BACK)` #1 immediately; this is the **only** BACK in the service (build-enforced) |
| 9 | short wait | poll `getWindows()` every **50 ms**, max **250 ms**, for the full-screen SystemUI window |
| 10 | open | when it is gone, or at the 250 ms cap, `startActivity(ActionCentreActivity, NEW_TASK\|SINGLE_TOP\|NO_ANIMATION)` with `extra_instant=true`; then stop. No BACK is sent from here on |
| 11 | instant panel | `ActionCentreActivity` records the extra and skips the open travel entirely (`root.post(openRunnable)` only when not launched from the bridge); window transitions are suppressed on this build, and the close travel is unchanged |
| 12 | timing | when the panel's window first gains focus, the activity calls `notifyPanelVisible()`; the service logs detection → visible time (uptime delta + wall-clock delta) |

The threshold is area-based: 720×1280 display → a status bar of 720×96 is ~7.5 % and is
rejected, the expanded shade at 720×1280 (or 720×1216 without the nav bar, ~95 %) passes.
The wait is deliberately capped at 250 ms: if SystemUI still reports the window at the cap
the panel starts anyway, rather than waiting with the stock shade on screen. Because the
poll never sends BACK, the panel cannot be dismissed by the bridge after it is focused.
The window class and title are logged but never used for the decision. Every callback is
wrapped in `try/catch`; a bad event is logged and dropped.

## 3. How to enable

1. Open **Центр действий** (launcher icon or the Start tile).
2. Tap **`ВКЛЮЧИТЬ ЖЕСТ ЦЕНТРА ДЕЙСТВИЙ`** under `CLEAR ALL / ALL SETTINGS`; the row shows
   `ЖЕСТ ЦЕНТРА ДЕЙСТВИЙ ВКЛЮЧЁН` once on.
3. It opens **`Настройки → Специальные возможности`**.
4. Tap **`ztMetro Action Centre`**, enable it, confirm the system warning dialog.
5. Swipe down over any app: the stock shade is collapsed and the panel slides up.

After installing a new build the service normally rebinds automatically; if it does not,
toggle it off/on once in Accessibility.

**Android 13+ sideload guard.** Because this APK is installed by adb, the toggle may be
greyed out as *"Restricted setting"*. Allow it once: **Настройки → Приложения → Центр
действий → ⋮ → Разрешить ограниченные настройки**, or
`adb shell appops set org.ztmetro.actioncentre ACCESS_RESTRICTED_SETTINGS allow`, then
retry step 4.

adb enable (optional; **replaces** the enabled-services list — read and preserve existing
entries, colon-separated):

```sh
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services \
  "org.ztmetro.actioncentre/org.ztmetro.actioncentre.ShadeBridgeService"
adb shell settings put secure accessibility_enabled 1
```

## 4. Logcat — exact lines

Tag is exactly `ShadeBridge`:

```sh
adb logcat -s ShadeBridge:V
```

On enable:

```
I/ShadeBridge: service connected; shade = fullscreen com.android.systemui window (class name is not used)
```

A successful trigger (numbers depend on the display; 720×1280 here):

```
D/ShadeBridge: systemui window-state; inspecting 3 window(s), display=720x1280
D/ShadeBridge: systemui window type=3 bounds=[0,0][720,1280] 100% of display title=NotificationShade
I/ShadeBridge: shade detected: type=3 bounds=[0,0][720,1280] 100% of display 720x1280 last=com.android.launcher3; collapsing with GLOBAL_ACTION_BACK
I/ShadeBridge: GLOBAL_ACTION_BACK #1 returned true
I/ShadeBridge: shade gone after 100 ms; starting Action Centre
I/ShadeBridge: panel visible 160 ms after detection (wall-clock delta 161 ms)
```

If SystemUI still reports the full-screen window at the 250 ms cap, the panel starts
anyway — there is no second BACK:

```
I/ShadeBridge: shade still visible at 250 ms; starting Action Centre anyway
I/ShadeBridge: panel visible 310 ms after detection (wall-clock delta 311 ms)
```

Ordinary status-bar windows are ignored (small bounds):

```
D/ShadeBridge: systemui window type=3 bounds=[0,0][720,96] 7% of display title=StatusBar
D/ShadeBridge: skip: no SystemUI window covers >= 85% of the display
```

Decision skips are explicit:

```
I/ShadeBridge: skip: throttled (N ms < 1500 ms)
I/ShadeBridge: skip: keyguard locked, shade=type=…
I/ShadeBridge: skip: Action Centre is the focused window, shade=type=…
I/ShadeBridge: skip: previous foreground was Action Centre (last=org.ztmetro.actioncentre), shade=type=…
W/ShadeBridge: starting Action Centre failed
```

## 5. Expected on-screen timing

The owner measures the stock shade's on-screen time. The shade is BACKed immediately and
starts closing at once. The service waits for its full-screen window to disappear (poll
every 50 ms) and starts the panel no later than **250 ms** after detection, so the stock
shade can only be visible for its own close animation, never the old ~600 ms wait. The
panel launches with no animation and is simply revealed. The `panel visible` log gives the
detection → visible wall-clock delta for the frame-by-frame screenrecord comparison.
Zero-flicker still needs a SystemUI hook (the LSPosed attempt that did not fire).

## 6. Limitations and risks

- **Keyguard untouched.** When locked the service does nothing; lock-screen shade stays stock.
- **Geometry can catch another fullscreen SystemUI surface.** A full-screen SystemUI scrim
  (e.g. some power-menu/dialog implementations) would also pass the 85 % test. The title is
  logged; if it happens, disable the service. The status bar and volume dialog are too small
  to qualify.
- **Only window-state events.** No content-changed events, no text: the service reads window
  package/title/bounds only and all packages' foreground package name. Accessibility is still
  a high-trust grant.
- **Exactly one BACK, ever.** There is no recovery BACK, so a panel that has started cannot
  be dismissed by the bridge. The trade-off: if BACK #1 is swallowed and the shade is still
  full-screen at 250 ms, the panel starts underneath it (logged as `starting Action Centre
  anyway`); another swipe will trigger again after the 1500 ms throttle.
- **Panel may appear behind a closing shade.** The wait is capped at 250 ms; the panel is
  revealed as the shade closes, and the detection → visible log makes the overlap
  measurable.
- **Throttle is a safety valve.** A second shade open within 1500 ms stays stock.
- **Background activity start.** Accessibility services are bound by the system (documented
  exemption); if a build blocks it, the panel does not appear and
  `W/ShadeBridge: starting Action Centre failed` is logged.
- **No runtime re-verification yet.** The 250 ms cap, the single BACK and the
  instant-open extra must be confirmed on the device with the commands above.

## 7. Rollback

Settings → Accessibility → **ztMetro Action Centre** → off. The service unbinds
(`I/ShadeBridge: service unbound`), pending panel starts are cancelled, and the stock shade
works as before. Uninstalling also removes it. The app writes nothing to `Settings.Secure`.
