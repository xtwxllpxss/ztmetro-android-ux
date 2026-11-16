# ztMetro lock overlay — build, window facts and static verification

Date: 2026-11-16 · Device target: Redmi Note 5A (ugglite), LineageOS 23.2 /
Android 16 · Package: `org.ztmetro.actioncentre`
Scope: **static only** — the lead owns adb, so nothing here was installed or run on
the phone. The window type below is the one that *should* work, supported by AOSP
android16 source and the maintained MetroWinLauncher implementation, not by a
readback from this device.

## 1. Artifact

| field | value |
|---|---|
| APK | `repo/evidence/lock-overlay/ztmetro-actioncentre.apk` (build: `repo/actioncentre/build/out/ztmetro-actioncentre.apk`) |
| size | **325 179 bytes (317.6 KiB)** — limit 2 MiB |
| sha256 | `4179ae089133527988f9f26017283ed34e6b7e282fdd7e98cc640c0ce0a973b6` |
| package / version | `org.ztmetro.actioncentre`, versionCode 1 / versionName 1.0 |
| SDK | minSdk 29, targetSdk 36, compileSdk 36 |
| signer | v3 RSA-2048, cert SHA-256 `303ddd02…2e78ce` — **same key as the previously shipped Action Centre**, so `adb install -r` upgrades in place and keeps the accessibility grant |
| build | `ACTIONCENTRE_REMOTE_DIR=ztux/lock EVIDENCE_DIR=$PWD/evidence/lock-overlay repo/scripts/build-actioncentre.sh`: rsync to `hermes-ubuntu:~/ztux/lock`, plain `aapt2` + `javac` (Java 8 bytecode, JDK 17) + `d8 --min-api 29` + `zipalign` + `apksigner`. No Gradle, no AndroidX, no network. The prior keystore was copied from `~/ztux/actbridge/build/keystore/actioncentre.p12`. |

Static verification: **84 PASS / 0 FAIL** (`verify.txt`; the merged build also carries
agent-29's top-edge strip and agent-37's separate 28 dp status-strip service, so the
log includes their checks). Passed checks relevant to this feature:

- `dexdump -f` lists `LockOverlayController` and `LockOverlayView` (plus the
  existing `ShadeBridgeService`, strip, shade detector);
- the source uses `TYPE_ACCESSIBILITY_OVERLAY` and
  `FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE`; no `performGlobalAction` in the lock
  controller (it never unlocks or presses keys);
- the accessibility config packs `accessibilityEventTypes=0x820`
  (`typeWindowStateChanged|typeWindowContentChanged`),
  `accessibilityFlags=0x51`, `canRetrieveWindowContent=true`, no package filter;
- `lock_swipe_hint` string is in the resource table;
- no `SYSTEM_ALERT_WINDOW` permission, no `BLUETOOTH_CONNECT`, no AndroidX;
- the APK is < 2 MiB.

Note: the APK is the merged build of the working tree at 14:03 — it also contains the
24 dp top-edge shade strip (agent-29) and the optional 28 dp status strip
(agent-37, separate `StatusStripService` + optional `READ_PHONE_STATE`; the panel
calls `StatusStripService.setPanelVisible()` so that strip is suppressed while the
Action Centre is open). The lock overlay itself adds no permission and no service.

## 2. Window type and flags (the part that matters)

**Type: `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` (2032).**
It is the only window type a non-system app can add over the keyguard without
`SYSTEM_ALERT_WINDOW` (the app deliberately does not hold it), because the
accessibility service already has the user-granted accessibility permission.

AOSP `android16-release`, `services/core/java/com/android/server/policy/WindowManagerPolicy.java`,
`getWindowLayerFromTypeLw()`:

| window | layer |
|---|---|
| `TYPE_APPLICATION_OVERLAY` | 11 |
| `TYPE_INPUT_METHOD` | 13 |
| `TYPE_STATUS_BAR` | 15 |
| **`TYPE_NOTIFICATION_SHADE`** — SystemUI draws the Android 16 keyguard inside this window | **17** |
| `TYPE_NAVIGATION_BAR` | 24 |
| **`TYPE_ACCESSIBILITY_OVERLAY`** | **31** |

So the accessibility overlay is above the keyguard, status bar, nav bar and IME.
`TYPE_KEYGUARD` is not in the layer switch any more; Android 16's keyguard is a
child of the notification-shade window. This matches the maintained
[MetroWinLauncher `LockOverlay.kt`](https://github.com/developerkenshi-coder/MetroWinLauncher),
which adds a `MATCH_PARENT` `TYPE_ACCESSIBILITY_OVERLAY` for the same purpose
(their window is `FLAG_NOT_FOCUSABLE|FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS`,
`PixelFormat.TRANSLUCENT` because they animate the lift; ours is opaque and does
not animate). Their `LockAccessibilityService.kt` shows the lifecycle we reuse:
`ACTION_SCREEN_OFF` → pre-add, `ACTION_SCREEN_ON` → refresh/hide, `ACTION_USER_PRESENT`
→ remove, plus a repeated `KeyguardManager.isKeyguardLocked()` check.

**Main surface (visual):**

```
TYPE_ACCESSIBILITY_OVERLAY, MATCH_PARENT × MATCH_PARENT, gravity TOP|START
flags = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN
      | FLAG_LAYOUT_NO_LIMITS | FLAG_HARDWARE_ACCELERATED
format = PixelFormat.OPAQUE
layoutInDisplayCutoutMode = ALWAYS (API 30+), fitInsetsTypes/sides = 0
```

`FLAG_NOT_TOUCHABLE` is deliberate: the black surface never takes the system's
unlock input away. `PixelFormat.OPAQUE` + a `#FF000000` background gives the
required 100 % black cover with no translucency and no rounded corners.
`setFitInsetsTypes(0)` keeps the surface edge-to-edge instead of being letterboxed
below the status/nav bars on Android 15/16.

**Bottom strip (the only touchable window):**

```
TYPE_ACCESSIBILITY_OVERLAY, MATCH_PARENT × max(200 dp, min(280 dp, h/3)),
gravity BOTTOM|START, flags = NOT_FOCUSABLE | NOT_TOUCH_MODAL
      | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS | HARDWARE_ACCELERATED,
format = TRANSPARENT
```

Added after the visual window, so it is on top of it. A swipe up of ≥24 dp
(mostly vertical) removes both windows and reveals the stock keyguard; taps and
other gestures are swallowed. It does not unlock, inject input, or call any
global action.

## 3. What is drawn

`LockOverlayView` is a bottom-anchored column on pure black, left-aligned like
the C lock screen (`design/components/lock-screen.md`, `ztmetro.c:6253-6330`):

| element | value | source |
|---|---|---|
| clock | 92 sp, `sans-serif-light`, white, 24 h `HH:mm` (12 h uses the system preference with a small AM/PM), refreshed on every `ACTION_SCREEN_ON` and once a minute **only while the screen is interactive**; no timer with the screen off | `type.ramp.display_large`, `ztmetro.c:5942` |
| date | 20 sp, `sans-serif-light`, white 80 %, `EEEE, d MMMM` in the system locale, one line (the C shell uses two 30 sp lines; this overlay follows the task requirement) | requirement; design quote |
| notification counts | one row in the lower third: per-app launcher icon 26 dp + count 20 sp Light white, newest app first, maximum 5 apps; app icons load off the main thread, a square outline + initial is shown until they do | `ztmetro.c:6284-6315` (glyph 26 + count 22, max 5) |
| empty state | counts row disappears when the notification listener has no active notifications (the clock/date stay) | C row fades out with no notifications |
| hint | `↑ СВАЙП ВВЕРХ`, 11 sp `sans-serif-medium`, 55 % white, letter-spacing 0.12, centred at the bottom | task requirement |
| background | `#FF000000`, square, no elevation, no corners, no status row | tokens `color.neutrals.black`, C `draw_lock` paints over the status row |

No notification text, titles or senders are drawn — only app icons and counts.
The view is `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`, so TalkBack keeps
seeing the stock keyguard underneath and can still unlock.

## 4. When it appears and disappears

`LockOverlayController` is owned by the existing `ShadeBridgeService` (same
accessibility permission as the shade gesture — there is **no second switch**):

- **Show:** `KeyguardManager.isKeyguardLocked()` is authoritative, combined with
  `PowerManager.isInteractive()`; window inspection is used for the IME guard and
  for the log (on Lineage the keyguard window can be absent from
  `getWindows()` while still locked, so it is not used to decide "locked").
  `ACTION_SCREEN_OFF` schedules a pre-add 250 ms later, so the first frame after
  wake is already black instead of flashing the teal clock. `ACTION_SCREEN_ON`
  re-checks and refreshes the clock. A `TYPE_WINDOW_STATE_CHANGED` from SystemUI
  while locked also retries if the service was restarted.
- **Hide immediately:** `ACTION_USER_PRESENT`; two consecutive
  `isKeyguardLocked()==false` reads on a 400 ms poll; an IME window or a keyguard
  bouncer/PIN/password/pattern class appears; any non-SystemUI app comes to the
  front while locked (incoming call, alarm, camera from lock, emergency dialer);
  `onInterrupt`; service unbind/destroy; swipe up on the bottom strip.
- **No timer when the screen is off:** polling and the minute tick are removed at
  `ACTION_SCREEN_OFF` and restarted at `ACTION_SCREEN_ON`; the clock is refreshed
  at wake. While a call is active (`AudioManager.getMode() != MODE_NORMAL`) the
  overlay is not shown at all.

## 5. Fail-safe

1. Every callback is wrapped; no accessibility event can crash the service.
2. If `addView` throws (`SecurityException`, `BadTokenException`, any runtime
   failure) the exact exception class/message and stack are logged once per wake
   (`addFailed` until the next `ACTION_SCREEN_ON`), any partially added window is
   removed, and nothing else changes: the stock keyguard is what the user sees.
3. The visual surface is `NOT_TOUCHABLE`; only the bottom strip is touchable. If
   the strip fails to add, the visual surface is removed too, so the user is
   never left with an opaque screen that has no working reveal gesture.
4. The overlay performs no `performGlobalAction`, no input injection, no keyguard
   dismissal and holds no credentials. Disabling the accessibility service (or
   uninstalling the app) removes it; with the service off the app runs no overlay
   code at all.
5. If a foreign app is in front while locked, the overlay yields and stays away
   until the next screen off. If a bouncer/IME appears, it yields immediately.

## 6. One-time setup, install and what to check

The accessibility permission is already the one used by the shade gesture; the
lock overlay reuses it, so **no new user grant**:

1. `adb install -r repo/evidence/lock-overlay/ztmetro-actioncentre.apk`
   (same certificate as the installed Action Centre; if the installed build was
   signed by a different key, uninstall first and re-enable accessibility).
2. Settings → Accessibility → **ztMetro Action Centre** = ON. If the system
   turned it off after the APK update, toggle it once. The same switch covers the
   shade gesture and the lock overlay.
3. Optional for the counts row: Settings → Notifications → Device & app
   notifications → **Центр действий: уведомления** = ON. Without it the clock and
   date still draw; the counts row stays empty.
4. No `Draw over other apps` grant, no root, no LSPosed, no reboot required
   (the service rebinds on update).
5. The merged APK also contains agent-37's separate 28 dp status strip. It is a
   **second, independent accessibility service** with its own Settings switch;
   the lock overlay does not need it. If it is enabled, it asks for the optional
   `READ_PHONE_STATE` runtime permission for live signal bars (four static bars
   without it) and, per that agent, removes itself while the keyguard is locked so
   it never draws over this lock screen.

Check on the phone:

- Lock the phone and turn the screen on: **no teal Material clock, no rounded
  notification card, no status bar** — black screen, big white light clock lower
  left, date under it, up to five app icons + counts in the lower third, and
  `↑ СВАЙП ВВЕРХ` at the bottom.
- Wait: the clock advances at the minute, nothing else appears over the black.
- Swipe up in the bottom ~200–280 dp: the black surface goes at once and the
  **stock keyguard** is there; unlock normally with fingerprint/PIN (this is a
  two-step reveal by design — the overlay never tries to unlock).
- Unlock with the fingerprint while the overlay is up: it must disappear on
  `ACTION_USER_PRESENT` (also within ~0.8 s via the poll).
- While locked, start an incoming call/alarm: the overlay must yield to the app.
- `adb logcat -s LockOverlay ShadeBridge` should show
  `shown: type=TYPE_ACCESSIBILITY_OVERLAY(2032) flags=NOT_FOCUSABLE|NOT_TOUCHABLE|LAYOUT_IN_SCREEN|LAYOUT_NO_LIMITS|HARDWARE_ACCELERATED …`,
  then `hidden: user present` / `hidden: swipe up: stock keyguard revealed`.
- If the ROM refuses the window: `addView failed; stock keyguard stays visible
  (SecurityException: …)` and the stock lock screen must be usable as before.

If something is wrong, turn the accessibility service off — the stock keyguard is
untouched and immediately usable.

## 7. Merge hook points (for the lead)

Only four hooks were added to `ShadeBridgeService.java`; everything else is in
the two new files:

1. field `private LockOverlayController lockOverlay;`
2. `onServiceConnected()`: after `addStripOverlay()`, stop an old controller if
   present, then `lockOverlay = new LockOverlayController(this); lockOverlay.start();`
3. `onAccessibilityEvent()`: first statement inside the `try`,
   `if (lockOverlay != null) lockOverlay.onAccessibilityEvent(event);`
   (before the existing `TYPE_WINDOW_STATE_CHANGED` early return, because the
   lock overlay also needs content-changed events);
4. `onInterrupt()` / `onUnbind()` / `onDestroy()`:
   `lockOverlay.onInterrupt()` and `lockOverlay.stop(); lockOverlay = null;`.

Resource changes: `res/xml/shade_bridge.xml` event types are now
`typeWindowStateChanged|typeWindowContentChanged`; `strings.xml` gains
`lock_swipe_hint` and one sentence in `shade_bridge_description`. No manifest
change for the lock overlay, no new service and no new permission for it; the
merged APK's separate status-strip service is agent-37's.

## 8. Risks and open questions

- **Not device-verified.** No adb in this task. Layer 31 vs keyguard layer 17 is
  AOSP source plus MetroWinLauncher's maintained Android 13+ implementation; a
  LineageOS 23.2-specific policy that hides accessibility overlays on the
  keyguard would make the overlay not appear (fail-safe: stock keyguard).
- **Touches above the strip** pass through the `NOT_TOUCHABLE` surface to the
  stock keyguard behind it. A blind swipe there can open the stock bouncer
  unseen; bouncer/IME detection hides the overlay when that happens, and the
  bottom strip always reveals the keyguard, but this needs a real-device check.
- **Two-step unlock** (swipe to reveal, then stock unlock) is intentional: no
  credential is requested or injected by us. If the design wants one-swipe
  unlock, that is a different (riskier) feature.
- **Content-changed events from all packages** raise the service's wakeup rate;
  the handler is throttled to 150 ms and `notificationTimeout` is 100 ms, but
  battery impact on this 2 GB phone is unmeasured.
- **Counts row** depends on the notification listener; with it off the row is
  absent rather than invented.
- **App icons, not monochrome glyphs**: small-icon bitmaps are not kept by
  `NotificationRepository`; using the launcher icon is honest and recognisable
  but not pixel-faithful to the C lock screen's notification glyphs.
- **Status strip stacking:** agent-37's separate 28 dp status-strip accessibility
  overlay is also layer 31. They have confirmed it removes itself on
  `isKeyguardLocked()`/`ACTION_SCREEN_OFF` and re-adds on `USER_PRESENT`; that
  gating is static-only here and must be checked together with this overlay on
  the phone (otherwise a white status row would appear over the black lock).
- **Security perception:** an opaque full-screen accessibility overlay that looks
  like a lock screen is exactly the shape of a phishing screen. This one never
  asks for a PIN, never reads notification text, never unlocks, and is the
  user's own app behind a visible accessibility switch. That must stay true.
- **Update break:** keyguard window/layering is AOSP policy; a future Android
  release can change the layer or add a keyguard-occlusion rule. Re-check the
  layer table and the MetroWinLauncher implementation at each LineageOS major.
