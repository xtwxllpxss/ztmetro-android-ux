# Shade → Action Centre bridge (AccessibilityService)

**Verdict.** `ShadeBridgeService` turns the system shade gesture into our panel without
SystemUI changes. On this LineageOS 23.2 build the shade window reports as
`android.widget.FrameLayout`, so detection does **not** use the class name: it finds the
expanded shade as the SystemUI window whose bounds cover at least 85 % of the display
(the status bar is the small SystemUI window). It then sends `GLOBAL_ACTION_BACK`, polls
`getWindows()` every 50 ms until that window is gone (a second BACK at ~200 ms, hard cap
600 ms) and only then starts `ActionCentreActivity`. The service is off by default; with
it off the app adds nothing to the shade path.
**Scope**: the APK was built and statically verified on `hermes-ubuntu`; the class-name
mismatch, the geometry trigger and the "panel started but stock shade on top" race were
observed by the lead on the device. This revision fixes the sequencing.

## 1. Artifact and static proof

| field | value |
|---|---|
| APK | [`ztmetro-actioncentre.apk`](ztmetro-actioncentre.apk) (build: `actioncentre/build/out/ztmetro-actioncentre.apk`) |
| size | **300 525 bytes (293.5 KiB)** |
| sha256 | `54897ba7f050b3c5549447faab259ad8eb5f37f59ee02b3c9ec97fa29154f500` |
| package / version | `org.ztmetro.actioncentre` 1 (1.0), unchanged |
| service | `org.ztmetro.actioncentre.ShadeBridgeService` |
| config | `res/xml/shade_bridge.xml`, referenced by the service `meta-data` |
| bind permission | `android.permission.BIND_ACCESSIBILITY_SERVICE` (service attribute, **no new `uses-permission`**) |
| build | same script: `aapt2` + `javac` (Java 8) + `d8 --min-api 29` + `zipalign` + `apksigner`; no Gradle/AndroidX/network |

`verify.txt` has **44 PASS / 0 FAIL**, including: service + bind permission + intent action +
config meta-data; `typeWindowStateChanged` (`0x20`), `feedbackGeneric` (`0x10`), flags
`0x51`, `canRetrieveWindowContent=true`; **no** `packageNames` filter (needed for previous
foreground tracking); no new `uses-permission`; `dexdump -f` lists `ShadeBridgeService`,
`onAccessibilityEvent` and the geometry detector `findFullscreenSystemUiWindow`.

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
| 8 | collapse | `performGlobalAction(GLOBAL_ACTION_BACK)` #1, result logged |
| 9 | wait for close | poll `getWindows()` every **50 ms** up to **600 ms**; if a full-screen SystemUI window is still there at ~200 ms, send BACK #2 (**max 2**) |
| 10 | open | only when the full-screen SystemUI window is gone, or at the 600 ms timeout: `startActivity(ActionCentreActivity, NEW_TASK\|SINGLE_TOP)` |
| 11 | race probe | 400 ms after the start, scan once more and log `W/ShadeBridge: race: …` if a full-screen SystemUI window is visible again |

The threshold is area-based: 720×1280 display → a status bar of 720×96 is ~7.5 % and is
rejected, the expanded shade at 720×1280 (or 720×1216 without the nav bar, ~95 %) passes.
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
I/ShadeBridge: shade gone after 150 ms
I/ShadeBridge: Action Centre started (shade gone after 150 ms)
D/ShadeBridge: post-start check: no systemui shade visible
```

If the first BACK is not enough, the second one and the 600 ms cap look like this:

```
I/ShadeBridge: shade still visible after 200 ms; GLOBAL_ACTION_BACK #2 returned true
I/ShadeBridge: shade gone after 260 ms
I/ShadeBridge: Action Centre started (shade gone after 260 ms)
```

If the shade is still up at the cap (the failure case, now visible in logs):

```
I/ShadeBridge: shade still visible, starting anyway after 600 ms
I/ShadeBridge: Action Centre started (timeout after 600 ms)
W/ShadeBridge: race: fullscreen SystemUI shade visible after Action Centre start
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

## 5. Expected flicker

The stock shade is visible for as long as it takes to collapse — typically **100–300 ms**,
up to the 600 ms cap. Unlike the previous revision the panel no longer starts at a fixed
100 ms: it waits until the full-screen SystemUI window is gone, so the closing shade is
not drawn on top of it. If the 600 ms cap expires the shade may still cover the panel, and
the race line above records it. Zero-flicker still needs a SystemUI hook (the LSPosed
attempt that did not fire).

## 6. Limitations and risks

- **Keyguard untouched.** When locked the service does nothing; lock-screen shade stays stock.
- **Geometry can catch another fullscreen SystemUI surface.** A full-screen SystemUI scrim
  (e.g. some power-menu/dialog implementations) would also pass the 85 % test. The title is
  logged; if it happens, disable the service. The status bar and volume dialog are too small
  to qualify.
- **Only window-state events.** No content-changed events, no text: the service reads window
  package/title/bounds only and all packages' foreground package name. Accessibility is still
  a high-trust grant.
- **At most two BACKs.** The second BACK fires only if a poll at ≥200 ms still sees the
  full-screen shade, so a shade that disappears between polls is not hit again. Still, a
  second BACK while SystemUI is finishing the close can reach the focused app; the 600 ms
  timeout starts the panel anyway rather than looping. The 1500 ms throttle prevents a
  retry storm.
- **Throttle is a safety valve.** A second shade open within 1500 ms stays stock.
- **Background activity start.** Accessibility services are bound by the system (documented
  exemption); if a build blocks it, the panel does not appear and
  `W/ShadeBridge: starting Action Centre failed` is logged.
- **No runtime re-verification yet.** The poll/gone sequence, the 200 ms second BACK and the
  post-start race line must be confirmed on the device with the commands above.

## 7. Rollback

Settings → Accessibility → **ztMetro Action Centre** → off. The service unbinds
(`I/ShadeBridge: service unbound`), pending panel starts are cancelled, and the stock shade
works as before. Uninstalling also removes it. The app writes nothing to `Settings.Secure`.
