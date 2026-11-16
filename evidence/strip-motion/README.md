# Top-edge strip touches + unlock/home entrance motion

Two device-observed defects are addressed in this tree:

1. a top-edge swipe still opened the **stock shade first**; the log showed only
   `systemui window type=3 bounds=[0,-10][720,35] 4% -> skip` and never the
   strip's own `strip drag` line, so the 24 dp strip was either
   `FLAG_NOT_TOUCHABLE` (keyguard flag stuck) or not getting touches at all;
2. unlocking had **no motion**: the Metro lock overlay disappeared
   (`hidden: keyguard dismissed (fade 120 ms)`) and home appeared abruptly.

Both APKs were rebuilt for this change. This document is the evidence and the
deployment/logcat script; the device itself was not touched (no adb in this
task). Everything below is static verification plus the build artifacts in this
directory.

## Artifacts

| file | size | sha256 |
|---|---|---|
| `actioncentre/ztmetro-actioncentre.apk` | 345 804 bytes | `0b7cfa30a7a23a8b47ae4a9892accafa8920bc4fb6fb1620e53acedf92af1033` |
| `launcher/ztmetro-start.apk` | 74 155 bytes | `163bf3ac7da5e1a1dfd0581d767f7d33b3caba88d990d5997507deeca77aae4d` |

Build: `hermes-ubuntu` (`~/ztux/stripmotion`, SDK `~/android-sdk` build-tools
36.0.0, JDK 17). `apksigner verify` and the static checks in
`actioncentre/verify.txt` pass; the launcher build's own checks pass
(`launcher/aapt2-badging.txt`, `launcher/dex-facts.txt`). Package versions are
unchanged: action centre 1 (1.0), launcher 4 (4.0).

## FIX 1 — the strip provably receives touches

### Window

`ShadeBridgeService.addStripOverlay()` now creates:

* height **32 dp** (was 24 dp) at `Gravity.TOP|START`, full width;
* `TYPE_ACCESSIBILITY_OVERLAY` (AOSP layer above `TYPE_STATUS_BAR`, so the
  gesture never reaches SystemUI first);
* flags `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN |
  FLAG_LAYOUT_NO_LIMITS`, and `FLAG_NOT_TOUCHABLE` is explicitly **masked out
  at creation** (`params.flags &= ~FLAG_NOT_TOUCHABLE`) — a fresh strip is
  always touchable;
* content `View` with `setAlpha(0f)` and a transparent background, so nothing
  is drawn but the input region exists. The window's own alpha stays 1 so the
  input region is never optimised away.

`dumpsys window` proof the window exists (after re-enabling the service):

```sh
adb shell dumpsys window windows | grep -A4 "ztMetro Action Centre strip"
```

The strip's own creation log line contains the exact flag set:

```
ShadeTouch: strip overlay added: height=32dp type=TYPE_ACCESSIBILITY_OVERLAY(2032) alpha=0 content, flags=0x... (NOT_FOCUSABLE=true,NOT_TOUCH_MODAL=true,LAYOUT_IN_SCREEN=true,LAYOUT_NO_LIMITS=true,NOT_TOUCHABLE=false) touchable=true keyguardLocked=false
```

### Touchable-flag lifecycle (single owner)

`applyStripTouchable(boolean, reason)` is the only code that adds or removes
`FLAG_NOT_TOUCHABLE`; it logs **every transition** under tag `ShadeTouch` with
the reason, `keyguardLocked` and the resulting flags. The state is driven by:

| source | effect |
|---|---|
| service connect (`onServiceConnected` → `addStripOverlay`) | window is touchable by construction; then `keyguardLocked = KeyguardManager.isKeyguardLocked()` |
| `ACTION_USER_PRESENT` broadcast | **touchable ON** (`keyguard unlocked`) — definitive after unlock, a stale getter cannot keep it off |
| `ACTION_SCREEN_OFF` | touchable OFF (`ACTION_SCREEN_OFF`; the lock overlay owns the top edge) |
| `ACTION_SCREEN_ON` | OFF only if the keyguard is locked at that moment |
| `KeyguardManager.KeyguardLockedStateListener` (API 33+) | follows the callback; a `locked` callback is cross-checked against the getter so a stale `true` cannot re-arm the flag |
| throttled getter safety net (`reconcileStripKeyguard`, every accessibility event, ≤ every 500 ms) | may only **clear** the locked state (`keyguard getter reports unlocked (safety net)`); it never sets locked from the getter |

Rationale for the getter never setting locked: the observed defect is a
keyguard state flag that stays `true` after unlock. Locking the strip again
from that getter would reproduce the bug; only explicit lock events
(`SCREEN_OFF`, keyguard listener) may set it.

`openFromStrip()` and the touch listener act on the same tracked state
(`stripKeyguardLocked`), so the strip cannot be "touchable but refusing to
open" because of a second, independent keyguard read.

### Gesture

* every delivered `ACTION_DOWN` / `ACTION_MOVE` / `ACTION_UP` is logged under
  `ShadeTouch` with local and raw coordinates, `touchable` and
  `keyguardLocked`;
* `ACTION_DOWN` is consumed (`return true`) only when the keyguard is tracked
  as unlocked and the Action Centre is not already focused;
* a downward drag **> 24 dp** or a tap calls `openFromStrip()`, which logs the
  literal `strip open` and starts `ActionCentreActivity` with
  `FLAG_ACTIVITY_NEW_TASK` + `EXTRA_INSTANT=true`; the gesture is swallowed;
* the shade fallback for windows opened by other means is untouched: panel
  first, then at most one `GLOBAL_ACTION_BACK` after 120 ms if the stock shade
  is still visible (`backSent` cap, two call sites total).

## FIX 2 — unlock / home entrance motion

### Lock overlay reveal (Action Centre APK)

`LockOverlayController.hide()` now animates the lock root view:

* `alpha 1.0 → 0.0` and `scale 1.0 → 0.96` (center pivot), Log(10) easing,
  **160 ms** (`FADE_OUT_MS = 160`), then the window is removed in the
  animation end action;
* this build previously faded over **120 ms** and never scaled; 160 ms with the
  0.96 scale is the final value. The visible result is a short "step back and
  fade" over the keyguard/launcher instead of a hard window drop;
* the surface is now `PixelFormat.TRANSLUCENT` (was `OPAQUE`). An opaque buffer
  drops the alpha channel, which would make both this reveal and the existing
  180 ms screen-on fade-in composite as an abrupt window removal. The full-size
  gradient background still fills the window at alpha 1, so the lock screen
  looks the same at rest;
* animator scale 0 (or an unreadable scale) removes the window instantly and
  logs `hidden: <reason> (instant: animator scale 0)`.

Final numbers on this build:

| motion | property | duration | easing |
|---|---|---|---|
| lock overlay reveal (unlock) | alpha 1→0, scale 1→0.96 | 160 ms | WP `(1-0.1^t)/0.9` |
| lock overlay entrance (screen-on) | alpha 0→1 | 180 ms (unchanged) | same |
| Action Centre panel close on swipe | window slide-down | **220 ms (unchanged)** | accelerate_cubic |
| Action Centre panel open | window slide-up | 260 ms (unchanged) | — |
| home entrance on keyguard return | alpha 0→1, scale 0.985→1 | 180 ms | WP `(1-0.1^t)/0.9` |

### Home entrance (launcher APK)

`StartActivity` animates the window root (`decorView`, so the black window
background fades too) `alpha 0→1`, `scale 0.985→1` over 180 ms via
`Motion.ms(this, 180)`, so `ANIMATOR_DURATION_SCALE` scales it and **0 is
instant**. It runs only when the previous state was the keyguard:

* `ACTION_USER_PRESENT` arms it (and a 2.5 s freshness window means unlocking
  into another app cannot make a later ordinary Home press animate);
* `ACTION_SCREEN_ON` while `KeyguardManager.isKeyguardLocked()` arms it for the
  next resume (covers a missed broadcast);
* `onPause` records `KeyguardManager.isKeyguardLocked()` as a third fallback;
* any ordinary resume clears the flags and repairs an interrupted entrance
  (`alpha/scale` reset to 1), so the launcher can never stay dimmed/scaled.

## Exact logcat lines for the lead

Clear the buffer, then unlock the phone and swipe down from the very top, and
also swipe up from the lock overlay to unlock:

```sh
adb logcat -c
adb logcat -v time -s ShadeTouch ShadeBridge UnlockTimeline LockOverlay ZtStartMotion
```

### Proof the strip receives the gesture

A top-edge **drag** (on an unlocked home screen) must produce, in this order:

```
ShadeTouch: DOWN x=360 y=12 rawX=360 rawY=12 touchable=true keyguardLocked=false
ShadeTouch: MOVE x=360 y=40 rawX=360 rawY=40 touchable=true keyguardLocked=false
ShadeTouch: MOVE x=360 y=72 rawX=360 rawY=72 touchable=true keyguardLocked=false
ShadeTouch: strip open drag 24 dp -> ActionCentreActivity (NEW_TASK, extra_instant=true); gesture swallowed by the strip
ShadeBridge: panel start at 0 ms (strip drag 24 dp); strip=present, instant=true, NEW_TASK
ShadeBridge: panel visible N ms after strip trigger (start +M ms, BACK none, wall-clock delta ...)
```

A **tap** on the top edge gives `DOWN`, `UP`, then the same `strip open tap`
line. If the old defect is still present, the log instead shows no `ShadeTouch`
lines at all and a `ShadeBridge: shade detected ...` line after the stock shade
opened — that is the measurable difference.

The flag lifecycle is readable in the same tag:

```
ShadeTouch: strip touchable OFF reason=strip added; keyguard locked keyguardLocked=true flags=0x... (NOT_FOCUSABLE=true,NOT_TOUCH_MODAL=true,LAYOUT_IN_SCREEN=true,LAYOUT_NO_LIMITS=true,NOT_TOUCHABLE=true)
ShadeTouch: strip touchable ON reason=ACTION_USER_PRESENT; keyguard unlocked keyguardLocked=false flags=0x... (NOT_FOCUSABLE=true,NOT_TOUCH_MODAL=true,LAYOUT_IN_SCREEN=true,LAYOUT_NO_LIMITS=true,NOT_TOUCHABLE=false)
```

A `strip touchable ON reason=keyguard getter reports unlocked (safety net)` line
means the ROM dropped both the broadcast and the listener and the safety net
recovered the strip; either ON line is success.

### Proof of the motion numbers

Unlock the phone (bottom-strip swipe over the Metro lock screen):

```
LockOverlay: hidden: keyguard dismissed (reveal: alpha 1.0->0.0, scale 1.0->0.96 over 160 ms, then window removed)
UnlockTimeline: unlock timeline | overlay_removed (keyguard dismissed) | +N ms | wall=...
ZtStartMotion: keyguard dismissed (ACTION_USER_PRESENT); home entrance armed for the next resume
ZtStartMotion: home entrance: keyguard return, alpha 0.0->1.0, scale 0.985->1.0 over 180 ms (base 180 ms, animator scale 1.0)
```

With `Settings.Global.ANIMATOR_DURATION_SCALE=0` the same unlock logs
`hidden: keyguard dismissed (instant: animator scale 0)` and
`home entrance: keyguard return, instant (ANIMATOR_DURATION_SCALE=0)`.

Panel close must stay 220 ms (unchanged):

```
ActionCentreActivity: swipe-up close: ... (threshold 60 dp / ...) ... 220 ms
```

## Deploy (lead)

```sh
adb install -r repo/evidence/strip-motion/actioncentre/ztmetro-actioncentre.apk
adb install -r repo/evidence/strip-motion/launcher/ztmetro-start.apk
# The strip window only exists while the accessibility service is bound.
# Re-toggle it once so onServiceConnected runs with the new flags:
#   Settings > Accessibility > ztMetro Action Centre: off, then on
# (or `adb shell am force-stop org.ztmetro.actioncentre` and re-enable the service)
adb shell dumpsys window windows | grep -A4 "ztMetro Action Centre strip"
```

Then run the logcat block above. Acceptance is the `ShadeTouch: DOWN ...` /
`strip open` sequence for a top-edge gesture, no stock-shade flash, the two
motion lines on unlock, and the 220 ms panel close still present.

## Rebuild / rollback

Rebuild exactly as done here (host without a local android-36 stages to
`hermes-ubuntu`):

```sh
cd repo
ACTIONCENTRE_REMOTE_DIR=ztux/stripmotion \
  EVIDENCE_DIR="$PWD/evidence/strip-motion/actioncentre" \
  STATUS_STRIP_EVIDENCE_DIR="$PWD/evidence/strip-motion/.status-strip" \
  bash scripts/build-actioncentre.sh
LAUNCHER_REMOTE_DIR=ztux/stripmotion \
  EVIDENCE_DIR="$PWD/evidence/strip-motion/launcher" \
  bash scripts/build-launcher.sh
```

Rollback: reinstall the previous APKs (the repo's git history has them under
`evidence/actioncentre/` and `evidence/live-tiles/`); nothing in this change is
persistent on the device, no settings are written.

## Boundaries

* Device behavior is **not** verified in this task (no adb). The code paths are
  statically verified in the APKs (`actioncentre/verify.txt`, launcher build
  checks) and the logcat lines above are the accepted proof.
* If the ROM's keyguard getter is stuck `true` *and* `ACTION_USER_PRESENT` is
  not delivered either, `dumpsys`/logcat will show the strip stuck OFF; the
  500 ms safety net cannot help in that case because it trusts the same getter.
  The `ACTION_DOWN` cross-check would then also see `true`. This is the one
  residual uncertainty; it is visible immediately as a missing `strip touchable
  ON` line after unlock.
* The 32 dp strip covers the top 32 dp of our own Action Centre panel while it
  is open (as the 24 dp strip covered 24 dp before); taps on the very top of
  the panel still do not reach it by design.
