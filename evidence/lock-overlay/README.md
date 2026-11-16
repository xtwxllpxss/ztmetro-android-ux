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
| size | **345 804 bytes (337.7 KiB)** — limit 2 MiB |
| sha256 | `dadabd4d84e87b3ed0a5e30227799362de4a1c61cc811eb1bad11c78e9bd0130` |
| package / version | `org.ztmetro.actioncentre`, versionCode 1 / versionName 1.0 |
| SDK | minSdk 29, targetSdk 36, compileSdk 36 |
| signer | v3 RSA-2048, cert SHA-256 `303ddd02…2e78ce` — **same key as the previously shipped Action Centre**, so `adb install -r` upgrades in place and keeps the accessibility grant |
| build | `ACTIONCENTRE_REMOTE_DIR=ztux/lock EVIDENCE_DIR=$PWD/evidence/lock-overlay repo/scripts/build-actioncentre.sh`: rsync to `hermes-ubuntu:~/ztux/lock`, plain `aapt2` + `javac` (Java 8 bytecode, JDK 17) + `d8 --min-api 29` + `zipalign` + `apksigner`. No Gradle, no AndroidX, no network. The prior keystore was copied from `~/ztux/actbridge/build/keystore/actioncentre.p12`. |

Static verification: **119 PASS / 0 FAIL** (`verify.txt`; the merged build also carries
agent-29's top-edge strip and agent-37's separate 28 dp status-strip service, so the
log includes their checks). Passed checks relevant to this feature:

- `dexdump -f` lists `LockOverlayController`, `LockOverlayView`, `LockClockPanel`,
  `UnlockProxyActivity` and `UnlockTimeline` (plus the existing
  `ShadeBridgeService`, strip, shade detector);
- the source uses `TYPE_ACCESSIBILITY_OVERLAY` and
  `FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE`; no `performGlobalAction` in the lock
  controller (it never unlocks or presses keys);
- the accessibility config packs `accessibilityEventTypes=0x820`
  (`typeWindowStateChanged|typeWindowContentChanged`),
  `accessibilityFlags=0x51`, `canRetrieveWindowContent=true`, no package filter;
- `lock_swipe_hint` string is in the resource table;
- no `SYSTEM_ALERT_WINDOW` permission, no `BLUETOOTH_CONNECT`, no AndroidX;
- the APK is < 2 MiB;
- the unlock proxy is registered with `showWhenLocked`, `excludeFromRecents`,
  `exported=false`, the no-animation transparent theme and the
  `DISABLE_KEYGUARD` normal permission; `requestDismissKeyguard` and
  `createConfirmDeviceCredentialIntent` are both in the dex/source; the foreign
  window decision compares above/below the keyguard (window order + logged layer);
  `LockClockPanel` is a separate clock area with `setAuthPanel()`.

Note: the APK is the merged build of the working tree at 14:5x — it also contains
the 24 dp top-edge shade strip (agent-29) and the optional 28 dp status strip
(agent-37, separate `StatusStripService` + optional `READ_PHONE_STATE`; the panel
calls `StatusStripService.setPanelVisible()` so that strip is suppressed while the
Action Centre is open). The lock overlay itself adds no permission and no service.
The overlay window was confirmed on the phone earlier (lead evidence, 14:39); the
new unlock path below is **static-only** until the lead runs it.

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
(mostly vertical) starts the unlock proxy (`UnlockProxyActivity`); the black
surface stays attached during the swipe and is not removed just because the
finger lifted. Taps and other gestures are swallowed. The strip never injects
input and never calls a global action; the unlock itself is done by the system
(see §9).

## 3. What is drawn

`LockOverlayView` is a bottom-anchored column on pure black, left-aligned like
the C lock screen (`design/components/lock-screen.md`, `ztmetro.c:6253-6330`):

| element | value | source |
|---|---|---|
| clock | 92 sp, `sans-serif-light`, white, 24 h `HH:mm` (12 h uses the system preference with a small AM/PM), refreshed on every `ACTION_SCREEN_ON` and once a minute **only while the screen is interactive**; no timer with the screen off | `type.ramp.display_large`, `ztmetro.c:5942` |
| date | two 30 sp `sans-serif-light` lines, white, `EEEE` then `d MMMM` in the system locale, 36 dp line box (0.882 spacing) — the mockup's two-line date | design/components/lock-screen.md, mockup `lock.html`, `ztmetro.c:5930-5945` |
| notification counts | one row in the lower third: per-app launcher icon 26 dp + count 22 sp Light white, newest app first, maximum 5 apps; app icons load off the main thread, a square outline + initial is shown until they do | `ztmetro.c:6284-6315` (glyph 26 + count 22, max 5) |
| empty state | counts row disappears when the notification listener has no active notifications (the clock/date stay) | C row fades out with no notifications |
| hint | `↑ СВАЙП ВВЕРХ`, 10 sp `sans-serif-medium`, 55 % white, letter-spacing 0.08, centred at the bottom | mockup `lock.html` |
| background | `#FF000000`, square, no elevation, no corners, no status row | tokens `color.neutrals.black`, C `draw_lock` paints over the status row |

No notification text, titles or senders are drawn — only app icons and counts.
The clock/date block is its own `LockClockPanel`; `LockOverlayView.setAuthPanel(View)`
replaces it with a future custom PIN pad in the same slot (§10). The view is
`IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`, so TalkBack keeps seeing the
stock keyguard underneath and can still unlock.

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
- **Unlock (keeps the surface up):** a bottom-strip swipe starts
  `UnlockProxyActivity` (§9). The overlay is not removed just because the finger
  lifted; it stays until `ACTION_USER_PRESENT` (non-secure dismiss) or until the
  system credential window is actually on top (secure), whichever applies.
- **Hide immediately:** `ACTION_USER_PRESENT`; two consecutive
  `isKeyguardLocked()==false` reads on a 400 ms poll **when no unlock proxy is in
  flight**; an IME window or a keyguard bouncer/PIN/password/pattern class
  appears; a **foreign window that is actually above the keyguard** (incoming
  call, alarm, camera from lock, emergency dialer — decided by the
  `getWindows()` stack order/layer, not by the event package alone); power menu /
  volume dialog; `onInterrupt`; service unbind/destroy.
- **Not hidden by:** a normal activity that merely sits behind the keyguard. This
  was the on-device bug where Settings (`Settings$WifiSettingsActivity`) was the
  last app: its window-state event arrived on screen-on and the old rule hid the
  overlay. The controller now logs
  `foreign window decision: pkg=… aboveKeyguard=false eventIndex=… keyguardIndex=… eventLayer=… keyguardLayer=… eventType=… focused=… keyguardFocused=…` and keeps the overlay. A foreign window is above only when its stack index is before the keyguard's; if the keyguard window is absent (a `showWhenLocked` call/camera can hide it), a full-screen focused foreign window counts after a 1.5 s post-show grace.
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
   never left with an opaque screen that has no working unlock gesture.
4. The overlay performs no `performGlobalAction`, no input injection, no keyguard
   dismissal and holds no credentials. Disabling the accessibility service (or
   uninstalling the app) removes it; with the service off the app runs no overlay
   code at all.
5. If a foreign window is actually above the keyguard, the overlay yields and
   stays away until the next screen off. If a bouncer/IME appears, it yields
   immediately.
6. **Unlock fallback:** if the proxy cannot start, `requestDismissKeyguard` is
   refused (`SecurityException`/`onDismissError`/`onDismissCancelled`), the
   credential intent is null or its launch fails, the proxy calls
   `LockOverlayController.revealStockKeyguard(reason)` and the overlay fades out
   to the stock keyguard — the previous behaviour. The reason is logged in
   `UnlockTimeline` (`fallback: …`, `fallback_reveal_stock: …`). If no credential
   window event arrives within 2.5 s, the same reveal happens instead of leaving
   the PIN behind an opaque surface.

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
- Open Settings, lock, wake: the overlay must **stay**; logcat must show
  `foreign window decision: pkg=com.android.settings … aboveKeyguard=false …`
  and no `hidden: app over keyguard`.
- Secure lock: swipe up in the bottom ~200–280 dp → the system PIN/pattern/
  password prompt appears directly, with **no stock lock screen** in between.
  Enter the credential → home; overlay gone.
- Non-secure lock (swipe only): swipe up → the black surface stays for the
  transition, then the home/app is there with no stock lock screen.
- Unlock with the fingerprint while the overlay is up: it must disappear on
  `ACTION_USER_PRESENT` (also within ~0.8 s via the poll).
- While locked, start an incoming call/alarm: the overlay must yield to the app.
- `adb logcat -s LockOverlay ShadeBridge UnlockProxy UnlockTimeline` should show
  `shown: type=TYPE_ACCESSIBILITY_OVERLAY(2032) flags=NOT_FOCUSABLE|NOT_TOUCHABLE|LAYOUT_IN_SCREEN|LAYOUT_NO_LIMITS|HARDWARE_ACCELERATED …`,
  then the unlock timeline from §9 and `hidden: user present` (non-secure) or
  `hidden: credential on top: …` (secure).
- If the ROM refuses the window: `addView failed; stock keyguard stays visible
  (SecurityException: …)` and the stock lock screen must be usable as before.

If something is wrong, turn the accessibility service off — the stock keyguard is
untouched and immediately usable.

## 7. Merge hook points (for the lead)

Only four hooks were added to `ShadeBridgeService.java`; everything else is in
the lock overlay files (`LockOverlayController`, `LockOverlayView`,
`LockClockPanel`, `UnlockProxyActivity`, `UnlockTimeline`):

1. field `private LockOverlayController lockOverlay;`
2. `onServiceConnected()`: after `addStripOverlay()`, stop an old controller if
   present, then `lockOverlay = new LockOverlayController(this); lockOverlay.start();`
3. `onAccessibilityEvent()`: first statement inside the `try`,
   `if (lockOverlay != null) lockOverlay.onAccessibilityEvent(event);`
   (before the existing `TYPE_WINDOW_STATE_CHANGED` early return, because the
   lock overlay also needs content-changed events);
4. `onInterrupt()` / `onUnbind()` / `onDestroy()`:
   `lockOverlay.onInterrupt()` and `lockOverlay.stop(); lockOverlay = null;`.

Resource/manifest changes for the overlay: `res/xml/shade_bridge.xml` event
types are now `typeWindowStateChanged|typeWindowContentChanged`; `strings.xml`
gains `lock_swipe_hint` and one sentence in `shade_bridge_description`; the
manifest gains `.UnlockProxyActivity` (showWhenLocked, excluded from recents,
exported=false, no-animation transparent theme) and the **normal**
`DISABLE_KEYGUARD` permission (required by `requestDismissKeyguard`; no prompt).
No new accessibility service for the lock overlay; the merged APK's separate
status-strip service is agent-37's.

## 8. Risks and open questions

- **Window verified, unlock path not.** The overlay window itself was confirmed
  on the phone (teal clock gone, our lock screen visible); the new
  `UnlockProxyActivity` flow is static-only here. `requestDismissKeyguard`
  requires the proxy to be visible via `showWhenLocked` and the
  `DISABLE_KEYGUARD` normal permission; every refusal path falls back to the
  stock keyguard and is logged.
- **Touches above the strip** pass through the `NOT_TOUCHABLE` surface to the
  stock keyguard behind it. A blind swipe there can open the stock bouncer
  unseen; bouncer/IME detection hides the overlay when that happens, and the
  bottom strip always reveals the keyguard, but this needs a real-device check.
- **Unlock risks:** the secure path depends on the credential activity's
  package/class (`com.android.settings…ConfirmDeviceCredentialActivity`). If the
  window event is missed, the 2.5 s timeout reveals it, which can flash the stock
  keyguard; cancel/back from the credential screen returns to the stock keyguard
  until the next screen off (we do not re-cover an authentication UI). A
  non-secure keyguard uses `requestDismissKeyguard`; a SIM-PIN or trusted-state
  edge case may return `onDismissError/Cancelled`, which falls back.
- **Foreign-window decision** uses `getWindows()` order (topmost first) and logs
  the layer from `getLayer()`. The Settings regression is fixed, but a
  `showWhenLocked` app that keeps the keyguard window listed above it is only
  detected by the focused/not-keyguard-focused fallback after 1.5 s; calls or
  the camera in the first 1.5 s after wake could be briefly covered (the strip
  still reveals).
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

## 9. Unlock path (swipe → proxy → system credential)

The stock lock screen is never deliberately shown during a swipe. The surface
stays attached while the finger is on the strip; the strip only starts a
transparent helper (`UnlockProxyActivity`, `showWhenLocked`, no animation,
excluded from recents). The proxy is the activity that owns the keyguard calls;
the overlay remains above it.

**Non-secure keyguard** (`KeyguardManager.isDeviceSecure() == false`):

1. Swipe starts the proxy and logs `BEGIN swipe_up` / `proxy_launch_requested`.
2. Proxy calls `KeyguardManager.requestDismissKeyguard(this, callback)`.
3. On `onDismissSucceeded` it logs `dismiss_keyguard_granted` and finishes.
   The overlay stays attached until `ACTION_USER_PRESENT` (or, if that broadcast
   is late, the 2.5 s net) and then fades out: the stock lock screen was never
   on top, only the black surface over the dismissing keyguard.

**Secure keyguard** (`isDeviceSecure() == true`):

1. Proxy calls `createConfirmDeviceCredentialIntent(null, null)` and
   `startActivityForResult` (`credential_intent_launch`).
2. The system credential activity is a normal `showWhenLocked` activity above the
   keyguard; the controller gets its window-state event
   (`credential_shown pkg=com.android.settings cls=…ConfirmDeviceCredentialActivity`)
   and only then removes the overlay (`overlay_removed (credential on top: …)`).
   What the user sees is the system PIN/pattern/password prompt, not the stock
   lock screen.
3. `onActivityResult` logs `credential_result resultCode=…` and finishes the
   proxy; a successful unlock also emits `user_present`.

**Exact logcat lines** (`adb logcat -s UnlockTimeline LockOverlay UnlockProxy`):

```
I/UnlockTimeline: unlock timeline | BEGIN swipe_up | +0 ms | wall=14:52:10.123
I/UnlockTimeline: unlock timeline | proxy_launch_requested | +2 ms | wall=14:52:10.125
I/UnlockTimeline: unlock timeline | proxy_started | +9 ms | wall=14:52:10.132
I/UnlockTimeline: unlock timeline | proxy_on_create | +38 ms | wall=14:52:10.161
I/UnlockTimeline: unlock timeline | proxy_on_resume | +41 ms | wall=14:52:10.164
I/UnlockTimeline: unlock timeline | dismiss_keyguard_requested | +44 ms | wall=14:52:10.167
I/UnlockTimeline: unlock timeline | dismiss_keyguard_granted | +173 ms | wall=14:52:10.296
I/UnlockTimeline: unlock timeline | user_present | +402 ms | wall=14:52:10.525
I/UnlockTimeline: unlock timeline | overlay_removed (user present) | +404 ms | wall=14:52:10.527
```

```
I/UnlockTimeline: unlock timeline | BEGIN swipe_up | +0 ms | wall=14:53:02.001
I/UnlockTimeline: unlock timeline | proxy_launch_requested | +2 ms | wall=14:53:02.003
I/UnlockTimeline: unlock timeline | proxy_started | +8 ms | wall=14:53:02.009
I/UnlockTimeline: unlock timeline | proxy_on_create | +35 ms | wall=14:53:02.036
I/UnlockTimeline: unlock timeline | proxy_on_resume | +38 ms | wall=14:53:02.039
I/UnlockTimeline: unlock timeline | credential_intent_launch | +41 ms | wall=14:53:02.042
I/UnlockTimeline: unlock timeline | credential_shown pkg=com.android.settings cls=com.android.settings.password.ConfirmDeviceCredentialActivity | +214 ms | wall=14:53:02.215
I/UnlockTimeline: unlock timeline | overlay_removed (credential on top: com.android.settings/com.android.settings.password.ConfirmDeviceCredentialActivity) | +216 ms | wall=14:53:02.217
I/UnlockTimeline: unlock timeline | credential_result resultCode=-1 | +6841 ms | wall=14:53:08.842
I/UnlockTimeline: unlock timeline | user_present | +6843 ms | wall=14:53:08.844
I/UnlockTimeline: unlock timeline | proxy_finish | +6846 ms | wall=14:53:08.847
```

The last two events can arrive in either order (the system broadcasts
USER_PRESENT while the activity result is delivered). If the following appears
instead, the fail-safe took over and the stock keyguard is visible:

```
I/UnlockTimeline: unlock timeline | credential_timeout | +2500 ms | wall=…
I/UnlockTimeline: unlock timeline | fallback_reveal_stock: no credential window within 2500 ms | +2501 ms | wall=…
I/UnlockTimeline: unlock timeline | overlay_removed (unlock fallback: no credential window within 2500 ms) | +2502 ms | wall=…
I/UnlockTimeline: unlock timeline | proxy_finish | +2504 ms | wall=…
```

The Settings regression is logged separately, without hiding the overlay:

```
I/LockOverlay: foreign window decision: pkg=com.android.settings cls=com.android.settings.Settings$WifiSettingsActivity aboveKeyguard=false eventIndex=2 keyguardIndex=1 eventLayer=… keyguardLayer=… eventType=1 focused=false active=false fullscreen=true keyguardFocused=true sinceShown=…
```

**Device steps for the lead:**

1. **Settings regression:** open Settings (e.g. Wi-Fi), press power to lock,
   press power to wake. Expect our black lock screen to stay; logcat shows the
   `foreign window decision … aboveKeyguard=false` line and no
   `hidden: app over keyguard`.
2. **Non-secure:** Settings → Security → Screen lock → *None* or *Swipe* (no
   PIN/pattern/password), lock, wake, swipe up in the bottom ~200–280 dp.
   Expect: no teal clock, no stock lock screen at any point; the home screen (or
   the app behind) appears; timeline ends with `dismiss_keyguard_granted`,
   `user_present`, `overlay_removed (user present)`.
3. **Secure:** set a PIN, lock, wake, swipe up. Expect the **system PIN prompt
   directly** (no stock lock screen before it); enter the PIN; home; overlay gone.
   Timeline shows `credential_intent_launch`, `credential_shown`,
   `overlay_removed (credential on top…)`, then `credential_result resultCode=-1`.
4. **Cancel:** from the PIN prompt press Back. The stock keyguard may be visible
   until the next screen-off/on (we do not re-cover an authentication surface);
   the next wake must restore our overlay.
5. **Fingerprint / normal unlock:** with the overlay up, unlock with the
   fingerprint; the overlay must disappear on `user_present`.
6. **Foreign app above the keyguard:** start an incoming call while locked; the
   overlay must yield (`foreign window decision … aboveKeyguard=true`).
7. If `addView` or the proxy is refused, the log shows the reason and the stock
   lock screen is usable; disabling **Settings → Accessibility → ztMetro Action
   Centre** restores stock behaviour completely.

## 10. Future custom PIN pad hook

- The clock/date block is `LockClockPanel` (its own class, own file). The black
  surface, counts row and hint are `LockOverlayView`.
- `LockOverlayView.setAuthPanel(View)` removes `LockClockPanel` from the column
  and inserts the given view in the same slot; `getClockArea()` exposes the
  current panel for a test/verifier. `setAuthPanel` is the documented seam; it
  does not add touch handling.
- The lock surface is `NOT_TOUCHABLE` today. A real custom PIN pad must also get
  a touchable window (or a touchable sub-window like the bottom strip) and its
  own key handling; that is deliberately **not** part of this change. The
  current unlock uses the system credential screen, so credentials never pass
  through our process.
