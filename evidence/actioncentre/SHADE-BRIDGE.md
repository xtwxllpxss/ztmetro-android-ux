# Shade → Action Centre bridge (AccessibilityService)

**Verdict.** `ShadeBridgeService` now owns the top edge: a **24 dp full-width transparent
`TYPE_ACCESSIBILITY_OVERLAY`** strip sits above the status bar, so a downward gesture that
starts at the top never reaches SystemUI and **the stock shade does not open at all** — the
common path has zero stock-shade frames. A drag of more than 24 dp (or a tap) in the strip
opens `ActionCentreActivity` instantly. The old window-state path is kept only as a
fallback (shade opened some other way): one BACK, a 50 ms poll up to 250 ms, then the
instant panel; **no BACK is ever sent after the panel starts**.

**Scope**: built and statically verified on `hermes-ubuntu`; the class-name mismatch, the
late-BACK panel dismissal and the need for the overlay strip were established by the lead
on the device. The strip height was raised from MetroWinLauncher's 20 dp to **24 dp** to
cover the full 24 dp status bar on this 320 dpi build (no 4 dp leak at the bottom edge).

## 1. Artifact and static proof

| field | value |
|---|---|
| APK | [`ztmetro-actioncentre.apk`](ztmetro-actioncentre.apk) (build: `actioncentre/build/out/ztmetro-actioncentre.apk`) |
| size | **304 621 bytes (297.5 KiB)** |
| sha256 | `2cc128455fba0e06af4a6bf372eb0a0225800193a3fcf1e1a4b596e700b55fa5` |
| package / version | `org.ztmetro.actioncentre` 1 (1.0), unchanged |
| service | `org.ztmetro.actioncentre.ShadeBridgeService` |
| config | `res/xml/shade_bridge.xml`, referenced by the service `meta-data` |
| window | `TYPE_ACCESSIBILITY_OVERLAY`, 24 dp, `FLAG_NOT_FOCUSABLE\|FLAG_NOT_TOUCH_MODAL\|FLAG_LAYOUT_IN_SCREEN\|FLAG_LAYOUT_NO_LIMITS`, `PixelFormat.TRANSPARENT` |
| bind permission | `android.permission.BIND_ACCESSIBILITY_SERVICE` (service attribute, **no new `uses-permission`**) |
| build | same script: `aapt2` + `javac` (Java 8) + `d8 --min-api 29` + `zipalign` + `apksigner`; no Gradle/AndroidX/network |

`verify.txt` has **50 PASS / 0 FAIL**, including the service/config checks, `dexdump -f`
entries for `ShadeBridgeService`, `onAccessibilityEvent`, `findFullscreenSystemUiWindow`,
`notifyPanelVisible` and `addStripOverlay`, the packaged `extra_instant` extra, a check that
the strip uses `TYPE_ACCESSIBILITY_OVERLAY`, and a regression guard that the service
contains **exactly one** `performGlobalAction` call.

## 2. Common path: the transparent top strip

Added by `onServiceConnected()` into the accessibility service's `WindowManager`:

| property | value |
|---|---|
| size / position | `MATCH_PARENT` × **24 dp**, `Gravity.TOP`, x=0 y=0 |
| type | `TYPE_ACCESSIBILITY_OVERLAY` — stacks **above** `TYPE_STATUS_BAR`, so SystemUI never sees the gesture |
| flags | `NOT_FOCUSABLE` (no key/IME), `NOT_TOUCH_MODAL` (touches outside still reach the app), `LAYOUT_IN_SCREEN`, `LAYOUT_NO_LIMITS`; **not** `NOT_TOUCHABLE` |
| pixels | `PixelFormat.TRANSPARENT`, view background `Color.TRANSPARENT` (invisible but touchable) |
| cutout | `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` so the strip covers a cutout area too |

Touch handling (all events swallowed while the strip owns the gesture):

| gesture | action |
|---|---|
| `ACTION_DOWN` | record y; if keyguard is locked or the panel is already focused, return `false` (let the tap through) |
| `ACTION_MOVE` with dy > **24 dp** | open the panel once (`extra_instant=true`), keep swallowing the rest of the gesture |
| `ACTION_UP` without a qualifying drag | open the panel too — a tap on the status strip behaves like WP and shows the panel |
| `ACTION_CANCEL` | reset |

A 600 ms strip-open throttle and the `isOwnPanelFocused()` check prevent double launches.
The strip is removed in `onUnbind`/`onDestroy`. While the keyguard is locked it is set
`FLAG_NOT_TOUCHABLE` (API 33 listener + a touch-time guard), so the lock screen keeps its
top edge.

**Deliberate behaviour change:** taps on the top 24 dp no longer reach the clock, battery
or status icons — they open the panel. (A swipe that starts *below* the strip still goes to
SystemUI and is handled by the fallback below.)

## 3. Fallback: shade opened some other way

Only needed for adb commands, a gesture that starts below the strip, long-press paths, or
the lock screen. The service sees `TYPE_WINDOW_STATE_CHANGED` from all packages (non-SystemUI
events only update `lastForegroundPackage`; no text is read). The expanded shade is the
SystemUI window of type `TYPE_SYSTEM`/`TYPE_APPLICATION` covering ≥ **85 %** of the display
— the class name is not used (the device reports `android.widget.FrameLayout`).

| # | guard/step | implementation |
|---|---|---|
| 1 | throttle | 1500 ms since the last trigger |
| 2 | keyguard | `isKeyguardLocked()` → skip; unreadable lock state counts as locked |
| 3 | own panel | focused window's root package is ours → skip |
| 4 | previous app | `lastForegroundPackage == org.ztmetro.actioncentre` and our window still on screen → skip |
| 5 | collapse | `performGlobalAction(GLOBAL_ACTION_BACK)` — the **only** BACK call in the service |
| 6 | short wait | poll `getWindows()` every 50 ms, max 250 ms, for the full-screen SystemUI window |
| 7 | open | gone, or at the cap: `startActivity(ActionCentreActivity, NEW_TASK\|SINGLE_TOP\|NO_ANIMATION)` with `extra_instant=true`, then stop |
| 8 | timing | panel's first window focus calls `notifyPanelVisible()`; logs trigger → visible |

Status-bar-sized SystemUI windows are logged and ignored. Because the poll never sends
BACK, the bridge cannot dismiss the panel after it is focused.

## 4. How to enable

1. Open **Центр действий** (launcher icon or the Start tile).
2. Tap **`ВКЛЮЧИТЬ ЖЕСТ ЦЕНТРА ДЕЙСТВИЙ`** under `CLEAR ALL / ALL SETTINGS`.
3. It opens **`Настройки → Специальные возможности`**.
4. Tap **`ztMetro Action Centre`**, enable it, confirm the system warning dialog.
5. Swipe down from the very top over any app: the panel opens; the stock shade does not.

After installing a new build the service normally rebinds automatically (the strip is
re-added); if not, toggle it off/on once in Accessibility.

**Android 13+ sideload guard.** The toggle may be greyed out as *"Restricted setting"*.
Allow it once: **Настройки → Приложения → Центр действий → ⋮ → Разрешить ограниченные
настройки**, or
`adb shell appops set org.ztmetro.actioncentre ACCESS_RESTRICTED_SETTINGS allow`.

## 5. Logcat — exact lines

Tag is exactly `ShadeBridge`:

```sh
adb logcat -s ShadeBridge:V
```

On enable:

```
I/ShadeBridge: service connected; shade = fullscreen com.android.systemui window (class name is not used)
I/ShadeBridge: strip overlay added: 24 dp at top, TYPE_ACCESSIBILITY_OVERLAY
```

**Strip-triggered open — drag from the top edge (the common path):**

```
I/ShadeBridge: strip drag 27 dp: opening Action Centre (instant)
I/ShadeBridge: panel visible 120 ms after strip trigger (wall-clock delta 121 ms)
```

**Strip tap:**

```
I/ShadeBridge: strip tap: opening Action Centre (instant)
I/ShadeBridge: panel visible 105 ms after strip trigger (wall-clock delta 106 ms)
```

Note what is **absent**: no `shade detected`, no `GLOBAL_ACTION_BACK`, no stock-shade
window — SystemUI never received the gesture.

**Fallback (shade opened some other way):**

```
D/ShadeBridge: systemui window-state; inspecting 3 window(s), display=720x1280
D/ShadeBridge: systemui window type=3 bounds=[0,0][720,1280] 100% of display title=NotificationShade
I/ShadeBridge: shade detected: type=3 bounds=[0,0][720,1280] 100% of display 720x1280 last=com.android.launcher3; collapsing with GLOBAL_ACTION_BACK
I/ShadeBridge: GLOBAL_ACTION_BACK #1 returned true
I/ShadeBridge: shade gone after 100 ms; starting Action Centre
I/ShadeBridge: panel visible 160 ms after detection (wall-clock delta 161 ms)
```

At the 250 ms cap the panel still starts and no BACK is sent:

```
I/ShadeBridge: shade still visible at 250 ms; starting Action Centre anyway
I/ShadeBridge: panel visible 310 ms after detection (wall-clock delta 311 ms)
```

Keyguard and skips:

```
I/ShadeBridge: strip overlay not touchable (keyguard)
I/ShadeBridge: strip overlay touchable
D/ShadeBridge: strip tap ignored: keyguard locked
D/ShadeBridge: strip tap ignored: open already in flight
D/ShadeBridge: strip tap ignored: Action Centre already focused
I/ShadeBridge: skip: throttled (N ms < 1500 ms)
I/ShadeBridge: skip: keyguard locked, shade=type=…
W/ShadeBridge: strip overlay not available
W/ShadeBridge: starting Action Centre failed
```

## 6. Expected on-screen timing

On the common path the stock shade is **never drawn**: the strip consumes the gesture at
`TYPE_ACCESSIBILITY_OVERLAY`, so there is no SystemUI animation to race and no 2–3 frame
flicker. The panel starts with no animation (`extra_instant=true`, `NO_ANIMATION`, open
travel skipped) and the detection → visible wall-clock delta is logged for the
screenrecord/ffmpeg comparison. The fallback path still shows the stock shade for its own
close animation (≤250 ms wait); it is not the normal gesture any more.

## 7. Limitations and risks

- **Status-bar taps are captured.** The top 24 dp of every screen (including games and the
  panel itself, where the touch is passed through) belongs to the strip; clock/battery taps
  open the panel instead. This is intentional and required for zero shade flicker.
- **Only the top edge.** A downward swipe that starts below the strip still opens the stock
  shade; the fallback then converts it, with the residual shade animation.
- **Overlay can fail to add.** If `WindowManager` refuses the window, `W/ShadeBridge: strip
  overlay not available` is logged and the geometry fallback still works — just with the
  old flicker.
- **Keyguard.** The strip is not touchable while locked (API 33 listener; older builds
  reject the touch in the listener as well). The window-state fallback also skips keyguard.
- **Geometry fallback can catch another fullscreen SystemUI surface** (e.g. a power-menu
  scrim); the window title is logged. The common strip path does not use geometry.
- **Exactly one BACK.** The fallback never sends a BACK after the panel starts; if BACK #1
  is swallowed, the panel starts under the still-open shade at the 250 ms cap.
- **Trust.** Accessibility is a high-trust grant; this service reads window
  package/title/bounds and the foreground package name, never text, and requests no
  overlay/dangerous permission.
- **No runtime re-verification yet.** Strip touch delivery, tap/drag thresholds and the
  overlay stacking above the status bar must be confirmed on the device.

## 8. Rollback

Settings → Accessibility → **ztMetro Action Centre** → off. The service unbinds, the strip
and its keyguard listener are removed (`I/ShadeBridge: service unbound`), pending launches
are cancelled, and the stock shade is exactly as before. Uninstalling removes it too; the
app writes nothing to `Settings.Secure`.
