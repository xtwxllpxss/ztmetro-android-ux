# ztMetro lock overlay — custom auth panel (reference PIN pad)

Date: 2026-11-16 · Device: Redmi Note 5A (ugglite), LineageOS 23.2 / Android 16 ·
Package: `org.ztmetro.actioncentre` · Scope: **static only** — no adb/device run here.
Builds on `evidence/lock-overlay/README.md`; this report supersedes its §10
"does not add touch handling".

## 1. Artifact

| field | value |
|---|---|
| APK | `evidence/lock-auth/ztmetro-actioncentre.apk` (build: `actioncentre/build/out/`) |
| size | **353 996 bytes (345.7 KiB)** — limit 2 MiB |
| sha256 | `14fe6514085321609cacb081d0c6947f2bb5542873ee6456f38352f9aef65176` |
| package / version | `org.ztmetro.actioncentre`, versionCode 1 / versionName 1.0 |
| SDK | minSdk 29, targetSdk 36, compileSdk 36 |
| signer | v3 RSA-2048, cert SHA-256 `303ddd02…2e78ce` — **same key as the shipped Action Centre**, so `adb install -r` upgrades in place and keeps the accessibility grant |
| static verification | **136 PASS / 0 FAIL** (`verify.txt`), 7 of them new for this feature |

Build: `ACTIONCENTRE_SRC_DIR=actioncentre/build/lockauth-merged/app
ACTIONCENTRE_REMOTE_DIR=ztux/lockauth EVIDENCE_DIR=$PWD/evidence/lock-auth
scripts/build-actioncentre.sh` — rsync to `hermes-ubuntu:~/ztux/lockauth`,
`aapt2` + `javac` + `d8 --min-api 29` + `zipalign` + `apksigner`; keystore seeded
from `~/ztux/lock/build/keystore/actioncentre.p12` (sha256 `1ed82e88…5ea53`).
No Gradle, no AndroidX, no network.

**Base tree (honest note).** The live working tree was mid-edit by another task
(status-strip rewrite did not compile), so the APK was built from a snapshot:
the working tree at ~17:01 MSK for the lock/shade overlay **including this
change**, with `StatusStripService`/`StatusStripView`/`StatusStripOverlayView`
restored from committed `2cb4318` (the accepted 28 dp status strip). The four
lock-auth files in the snapshot were byte-identical to `repo/actioncentre/**`.
So the pad/overlay code is exactly what is in the repo; only an unrelated,
half-finished status-strip rewrite is excluded from this APK.

This APK is one fail-safe hardening ahead of the snapshot the verifier committed
in `bdfc739` (its APK is `36322939…`): the final `LockOverlayView` catch now
drops a half-inserted panel and re-inserts the clock if `setAuthPanel` throws
mid-swap. Pad/overlay behaviour is otherwise identical; all checks and the
signer are the same.

## 2. What was added

| file | role |
|---|---|
| `LockAuthPanel.java` | seam: `View getView()`, `setOnSubmitListener(OnSubmitListener)`, `reset()`; callback is `onSubmit(String pin)` |
| `LockPinPad.java` | reference pad: 0-9, backspace, enter, four dots; `Canvas` only, square `#1F1F1F` keys (pressed `#333333`), white Open Sans Light digits, accent `#0050EF` dots, disabled glyphs at 30 % white; no Material, no ripple, no elevation, no drawable resources; 56 dp keys, 296 dp tall |
| `LockOverlayView.java` | `setAuthPanel(View)` now swaps/restores the clock slot; `setAuthPanel(null)` puts `LockClockPanel` back; `getAuthPanel()` added; `getClockArea()` returns the current slot view; the catch path re-inserts the clock |
| `LockOverlayController.java` | secure gate, pad lifecycle, second touchable window, enter → unlock proxy, fail-safe, owner flag |
| `scripts/build-actioncentre.sh` | 7 new static checks (interface/callback, pad drawing, pad-is-UI-only, wiring, both triggers, second window, clock fallback) and `ACTIONCENTRE_SRC_DIR` for snapshot builds |

No manifest change, no new permission, no new service, no new activity: the pad
reuses the existing accessibility window and `UnlockProxyActivity`.

## 3. Enable / rollback

```java
// LockOverlayController.java
static final boolean PIN_PAD_ENABLED = true;
```

- **On** (this APK): secure lock → the clock slot is the PIN pad. Non-secure
  lock (swipe only) → the pad is not created, the clock stays, swipe-up dismisses
  directly.
- **Off**: set it to `false` and rebuild. The clock screen, counts, hint and the
  swipe-up unlock path are the pre-pad behaviour; nothing else changes.
- Requirements for the pad to appear: accessibility service on (same switch as
  the shade gesture), a secure screen lock (PIN/pattern/password), screen on,
  keyguard locked. Nothing new to grant.

## 4. Touch model

The black surface keeps `FLAG_NOT_TOUCHABLE`. When the pad is in the slot:

- a **second** `TYPE_ACCESSIBILITY_OVERLAY` window is added (after the bottom
  strip) whose bounds are exactly the pad's `getLocationOnScreen()` rect;
  flags `NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS |
  HARDWARE_ACCELERATED`, `PixelFormat.TRANSPARENT`;
- that window's `AuthTouchLayer` forwards each `MotionEvent` to the pad view;
  both share the same origin, so coordinates need no translation;
- the pad re-lays out on insets/resize and the window follows
  (`updateViewLayout`); it is removed with the strip on hide and re-added on show;
- the bottom swipe strip is untouched. Where the pad and strip overlap, the pad
  window is above (keys win); a swipe that starts **below the pad** (the hint
  area) or beside its narrow column still reaches the strip and starts the
  normal unlock. Nothing changed in the strip's gestures.

## 5. Unlock path for the pad

1. four digits + enter (`enter` is dim until 4 are entered) →
   `LockAuthPanel.OnSubmitListener.onSubmit(pin)`;
2. `LockOverlayController.onPinSubmitted` logs **only the digit count**, starts
   `UnlockProxyActivity` with the same intent as the swipe (`pin_enter` timeline
   trigger) — **the PIN is not put in the Intent, extras, logs or disk**;
3. the proxy calls `createConfirmDeviceCredentialIntent(null, null)` and the
   system asks for the real credential; the overlay stays until the credential
   window is on top, then hides (existing rule);
4. the pad clears on the next show (`reset()`), so a refused/cancelled attempt
   starts empty. Verification is the system's, never ours.

## 6. Fail-safe

- every pad callback, layout read, `addView`/`updateViewLayout` and submit is
  wrapped; the accessibility service cannot be crashed by the pad;
- if the pad cannot be created or the touch window fails to add **once**, the pad
  is removed from the slot for that wake, the clock returns, and the strip's
  unlock path (unchanged) remains; retry on the next screen-on;
- if `setAuthPanel` throws mid-swap, the clock is re-inserted;
- if the credential launch fails, the existing `revealStockKeyguard(reason)`
  path applies, exactly as for the swipe.

## 7. What it does **not** do (verification)

- **It does not verify the PIN.** No comparison, no hash, no storage, no
  network, no log of digits, no PIN in any Intent. The only current verifier is
  the system's `createConfirmDeviceCredentialIntent` screen.
- There is no public "verify this PIN" API for an ordinary app. A future custom
  lock implemented as a privileged/fork/device-owner component would have to
  bring its own trusted verification path (the `DevicePolicyManager` direction
  the owner has in mind); the callback seam here is where that would attach.
  Until then the pad is a reference UI only.
- It does not replace the stock keyguard or bouncer: fingerprint, SIM PIN,
  pattern fallback, emergency dialer and system dialogs still work, and the
  bouncer/IME/foreign-window hides are unchanged.
- It does not support pattern/password/variable-length PINs, keyboards, or
  TalkBack operation (the overlay is deliberately out of the accessibility
  tree, so TalkBack keeps seeing the stock keyguard).
- **Security perception:** unlike the previous lock overlay, this screen *asks*
  for a PIN. The digits are discarded, but a user could type their real PIN into
  the reference pad before the system prompt appears. The pad should stay behind
  the owner flag until a real custom lock verifies locally; do not ship it
  enabled to other users as if it were the credential surface.

## 8. What a future custom lock can reuse

- `LockAuthPanel` + `onSubmit(String pin)`: any View-based UI (PIN, pattern,
  password) plugs into the slot without touching the overlay/controller;
- `LockOverlayView.setAuthPanel` slot swap/restore and `getAuthPanel()`;
- the controller's secure gate, exact-bounds touch window, forwarding view,
  reposition-on-layout and fail-safe/clock-restore logic;
- `UnlockProxyActivity` (`requestDismissKeyguard` / credential intent) and the
  `UnlockTimeline` events (`pin_enter`, `pin_submitted`, `pin_pad_touch_window`,
  `pin_pad_fallback`);
- only `onPinSubmitted` would change for a future local verifier; everything
  above it (layout, touch, secure gate, fallback) stays.

## 9. Static verification — new checks (all PASS)

```
PASS  dexdump lists LockAuthPanel
PASS  dexdump lists LockPinPad
PASS  clock area is a separate panel with the PIN-pad hook
PASS  LockAuthPanel interface with onSubmit(String pin) callback
PASS  reference pad: Canvas square keys, 0-9/backspace/enter, 4 accent dots
PASS  PIN pad is UI only: no keyguard/credential/verify call in it
PASS  pad wiring: owner flag, secure-only panel, second touchable window
PASS  pad enter and strip swipe start the same unlock proxy (no PIN in Intent)
PASS  pad has its own touchable window while the main surface stays NOT_TOUCHABLE
PASS  pad fail-safe restores the clock and keeps the unlock path
PASS  class LockAuthPanel in dex · class LockPinPad in dex
```

Also still true: no `SYSTEM_ALERT_WINDOW`, main surface
`NOT_FOCUSABLE|NOT_TOUCHABLE`, `performGlobalAction` absent from the lock
controller, no AndroidX, APK < 2 MiB.

## 10. Expected logcat (lead's device run)

`adb logcat -s LockOverlay LockAuth UnlockTimeline UnlockProxy`

```
I/LockOverlay: pin pad: secure keyguard, clock slot replaced; pad touches go through a separate touchable window, main surface stays NOT_TOUCHABLE
I/LockAuth: pin pad touch window added: type=2032 bounds=[14,y][706,y+296] flags=NOT_FOCUSABLE|NOT_TOUCH_MODAL|LAYOUT_IN_SCREEN ...
I/LockOverlay: pin enter: 4 digit(s); not verified in this app
I/UnlockTimeline: unlock timeline | BEGIN pin_enter | +0 ms | wall=...
I/UnlockTimeline: unlock timeline | pin_submitted digits=4 (pad is UI only; system credential screen verifies) | +1 ms | ...
I/UnlockTimeline: unlock timeline | proxy_launch_requested | +2 ms | ...
I/UnlockTimeline: unlock timeline | credential_intent_launch | +40 ms | ...
I/UnlockTimeline: unlock timeline | credential_shown pkg=com.android.settings cls=...ConfirmDeviceCredentialActivity | +210 ms | ...
I/UnlockTimeline: unlock timeline | overlay_removed (credential on top: ...) | +212 ms | ...
```

Non-secure lock: no `pin pad …` lines; swipe logs the existing
`BEGIN swipe_up` → `dismiss_keyguard_granted` → `user_present`.

## 11. Device steps (static-only result; the lead owns adb)

1. `adb install -r evidence/lock-auth/ztmetro-actioncentre.apk`; accessibility
   switch already on (same cert, same service).
2. Secure lock (PIN), lock, wake: black overlay with four dots, square keys,
   accent dots; the date/clock is gone.
3. Tap four digits + enter: the **system** PIN prompt appears; enter it → home;
   logcat shows the `pin_enter` timeline above. Backspace edits; enter does
   nothing before four digits.
4. Wrong/empty entry: our pad never says "wrong" — it is the system screen that
   rejects. Our pad performs no verification.
5. Press Back on the credential screen: stock keyguard may remain until the next
   screen off/on (unchanged from the swipe path).
6. Swipe up from the hint area (below the pad): the same system prompt opens —
   the strip is still a valid unlock path.
7. Non-secure lock (swipe only), wake: clock screen, no pad; swipe up dismisses
   directly.
8. Fingerprint while the pad is up: overlay disappears on `user_present`.
9. Disable the accessibility service: stock keyguard, no pad, no overlay.
