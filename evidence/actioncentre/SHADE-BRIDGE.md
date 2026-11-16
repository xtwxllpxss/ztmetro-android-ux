# Shade → Action Centre bridge (AccessibilityService)

**Verdict.** `ShadeBridgeService` turns the system shade gesture into our panel without
SystemUI changes: when SystemUI's shade window appears it sends `GLOBAL_ACTION_BACK` to
collapse it and starts `ActionCentreActivity` 100 ms later. The service is off by default;
with it off the app adds nothing to the shade path and the stock shade is untouched.
**Scope is static**: the lead owns adb, so the APK below was built and verified on
`hermes-ubuntu`, not installed or exercised on the device.

## 1. Artifact and static proof

| field | value |
|---|---|
| APK | [`ztmetro-actioncentre.apk`](ztmetro-actioncentre.apk) (build: `actioncentre/build/out/ztmetro-actioncentre.apk`) |
| size | **300 525 bytes (293.5 KiB)** |
| sha256 | `a21e4ab0750660f93fee8ea7582682f540f24f5d4478d3e6f2bd948467f952bf` |
| package / version | `org.ztmetro.actioncentre` 1 (1.0), unchanged |
| service | `org.ztmetro.actioncentre.ShadeBridgeService` |
| config | `res/xml/shade_bridge.xml`, referenced by the service `meta-data` |
| bind permission | `android.permission.BIND_ACCESSIBILITY_SERVICE` (service attribute, **no new `uses-permission`**) |
| build | same script: `aapt2` + `javac` (Java 8) + `d8 --min-api 29` + `zipalign` + `apksigner`; no Gradle/AndroidX/network |

`verify.txt` in this directory now has **42 PASS / 0 FAIL**. New checks:

- manifest: `.ShadeBridgeService`, `BIND_ACCESSIBILITY_SERVICE`,
  `android.accessibilityservice.AccessibilityService`, config meta-data;
- config XML: `typeWindowStateChanged` (`0x20`), `feedbackGeneric` (`0x10`),
  `flagDefault|flagReportViewIds|flagRetrieveInteractiveWindows` (`0x51`),
  `canRetrieveWindowContent=true`, `packageNames="com.android.systemui"`;
- `dexdump -f` lists `Lorg/ztmetro/actioncentre/ShadeBridgeService;` and
  `onAccessibilityEvent` (`dexdump-classes.txt`);
- `res/xml/shade_bridge.xml` packaged; permission list unchanged
  (`CAMERA`, `WRITE_SETTINGS`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`), still no
  `BLUETOOTH_CONNECT`, no overlay/location/phone permission.

Raw evidence: `aapt2-manifest.xmltree.txt`, `aapt2-xmltree-shade_bridge.txt`,
`aapt2-resources.txt`, `dexdump-classes.txt`, `aapt2-badging.txt`, `verify.txt`.

## 2. Event filter and action sequence

The system binds the service only when the user enables it. Its event stream is limited to
`com.android.systemui` and `TYPE_WINDOW_STATE_CHANGED`.

| # | step | implementation |
|---|---|---|
| 1 | event type | `TYPE_WINDOW_STATE_CHANGED` only |
| 2 | package | exactly `com.android.systemui` (config `packageNames` + code check) |
| 3 | class | lowercase contains `shade` or `quicksettings` — covers `NotificationShadeWindowView`, `ShadeView`, `QuickSettings…` |
| 4 | throttle | 1500 ms since the last successful trigger (skips are logged, clock not reset) |
| 5 | keyguard | `KeyguardManager.isKeyguardLocked()` → skip; if unreadable, treated as locked |
| 6 | own panel | focused interactive window's root package is ours → skip |
| 7 | collapse | `performGlobalAction(GLOBAL_ACTION_BACK)`, result logged |
| 8 | open | `startActivity(ActionCentreActivity, NEW_TASK | SINGLE_TOP)` after **100 ms** |

The live LineageOS 23.2 SystemUI APK on the build host (`/tmp/sysui.apk`, sha256
`67e2f10c…`, see `../qs-square/REPORT.md`) contains
`com.android.systemui.shade.NotificationShadeWindowView` and
`com.android.systemui.qs.QSContainerImpl`, so the class filter matches the shade window on
this build. A debug breadcrumb prints any non-matching SystemUI window class, which is what
to check if a future build renames the window.

Every callback (`onServiceConnected`, `onAccessibilityEvent`, `onInterrupt`, `onUnbind`,
`onDestroy`) is wrapped in `try/catch`; a bad event is logged and dropped, never fatal.

## 3. How to enable

1. Open **Центр действий** (the app's launcher icon, or the Start tile).
2. Tap the row **`ВКЛЮЧИТЬ ЖЕСТ ЦЕНТРА ДЕЙСТВИЙ`** under `CLEAR ALL / ALL SETTINGS`.
   The row labels itself `ЖЕСТ ЦЕНТРА ДЕЙСТВИЙ ВКЛЮЧЁН` once the switch is on.
3. It opens **`Настройки → Специальные возможности`** (Settings → Accessibility).
4. Tap **`ztMetro Action Centre`**, enable the service, confirm the system warning dialog.
5. Go back and swipe down over any app: the stock shade is collapsed and the panel slides up.

**Android 13+ sideload guard.** This APK is installed by adb, so the Accessibility page
may grey the toggle out with *"Restricted setting"*. Allow it once per app: **Настройки →
Приложения → Центр действий → ⋮ → Разрешить ограниченные настройки** (Settings → Apps →
Action Centre → ⋮ → Allow restricted settings), then repeat step 4. This is a platform
rule for sideloaded apps, not something the APK can bypass. The shell equivalent is
`adb shell appops set org.ztmetro.actioncentre ACCESS_RESTRICTED_SETTINGS allow`.

adb equivalent (optional; **replaces** the enabled-services list — read it first and keep
existing entries colon-separated):

```sh
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services \
  "org.ztmetro.actioncentre/org.ztmetro.actioncentre.ShadeBridgeService"
adb shell settings put secure accessibility_enabled 1
```

## 4. Logcat

Tag is exactly `ShadeBridge` (`Log.i`/`Log.d`/`Log.w`):

```sh
adb logcat -s ShadeBridge:V
```

Expected on enable:

```
I/ShadeBridge: service connected; watching com.android.systemui window state changes
```

Expected on each swipe-down:

```
I/ShadeBridge: shade window detected pkg=com.android.systemui class=com.android.systemui.shade.NotificationShadeWindowView; collapsing with GLOBAL_ACTION_BACK
I/ShadeBridge: GLOBAL_ACTION_BACK returned true
I/ShadeBridge: Action Centre started (100 ms after BACK)
```

Skips are explicit: `skip: throttled (… ms < 1500 ms)`, `skip: keyguard locked`,
`skip: Action Centre is the focused window`. `D/ShadeBridge: systemui window-state
class=… (not a shade)` proves the event arrives when the class filter does not match.
Failures appear as `W/ShadeBridge: starting Action Centre failed` or
`W/ShadeBridge: window inspection failed`, with the stack trace.

## 5. Expected flicker

Non-zero by construction: the shade window must exist before
`TYPE_WINDOW_STATE_CHANGED` is delivered, so the stock shade can paint **1–3 frames**;
`BACK` dispatch plus the deliberate 100 ms gap extends the visible overlap to roughly
**100–300 ms** before the panel's 260 ms slide-up covers it. This is the price of an
accessibility-only bridge; zero-flicker needs a SystemUI hook (the LSPosed attempt that
did not fire). The gap exists so the shade's close animation is not drawn on top of the
panel for its full duration.

## 6. Limitations and risks

- **Keyguard untouched.** When the device is locked the service does nothing; the
  lock-screen shade and its behaviour are exactly stock.
- **Only SystemUI.** The config observes only `com.android.systemui`; apps that draw their
  own full-screen shade-like UI are unaffected (their gesture keeps that UI, not ours).
- **One BACK.** If SystemUI ignores the first BACK (e.g. the shade is already closing) the
  panel still opens; if the shade is already gone when the event is processed the BACK can
  reach the focused app. Reacting to the fresh window event and the 1500 ms throttle keep
  this rare, but it is the one path that could close an app's screen/back stack.
- **Class-name dependency.** The trigger is a window class token, not a public API. If a
  future build renames the shade window without `Shade`/`QuickSettings`, the bridge silently
  stops converting (debug line above shows the real class) and the stock shade returns.
- **Throttle is a safety valve, not a feature.** A second shade open within 1500 ms stays
  stock.
- **Background activity start.** Accessibility services are bound by the system, which is
  the documented exemption used here; if a build blocks it, the panel does not appear and
  `W/ShadeBridge: starting Action Centre failed` is logged. Disabling the service restores
  stock behaviour immediately.
- **Trust surface.** Accessibility is a powerful grant. The service's event stream is
  restricted to SystemUI, and it reads only the focused window's package name, but the
  user is still granting window-content access.
- **No runtime verification yet.** Install, the enable dialog, the real shade class and the
  flicker must be confirmed on the device by the lead.

## 7. Rollback

Settings → Accessibility → **ztMetro Action Centre** → off. The service unbinds
(`I/ShadeBridge: service unbound`), pending panel starts are cancelled, and the stock shade
works as before. Uninstalling the app also removes the service. Nothing is written to
`Settings.Secure` by the app itself, so there is no residue to clean up.
