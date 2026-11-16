# Top-edge strip rework: whole-swipe window, gesture-scoped, zero-flash path

Date: 2026-11-16 · Target: Redmi Note 5A (ugglite), LineageOS 23.2 / Android 16,
720×1280 @ 320 dpi · Scope: **built and statically verified only** — no adb in
this task, so nothing was installed or measured on the phone.

## Verdict

`ShadeBridgeService` now owns the whole top-edge swipe instead of the first
32 dp of it:

* the strip window is created at the top **32 dp** (the only place a gesture may
  start) and **grows to 40 % of the display height — 512 px on this panel — in
  the same dispatch as `ACTION_DOWN`**, so the entire swipe happens inside the
  window and the framework has no bounds exit to cancel on;
* it shrinks back to 32 dp on `UP` / `CANCEL` (plus a 1500 ms safety timeout),
  so the top 40 % of every app is **not** a permanent dead touch zone;
* `ACTION_DOWN` still opens `ActionCentreActivity` immediately
  (`NEW_TASK | SINGLE_TOP | NO_ANIMATION`, `extra_instant=true`), so the panel
  is already drawing if anything cancels the touch later;
* every height transition and every `ACTION_CANCEL` is logged under
  `ShadeTouch` with pixel sizes and a classified **reason**;
* the one-`BACK` fallback for a shade opened by other means is unchanged.

APK: **349 900 bytes**, sha256
**`01bd51b89c7a181a4ae3f179622496c77968938dc7293500cf06e3d7e8a8c147`**
(`actioncentre/ztmetro-actioncentre.apk`). Static verification: **125 PASS /
0 FAIL** (`actioncentre/verify.txt`).

## Chosen variant (a′): the requested 40 % window, armed per gesture

| variant | geometry | contains the swipe | permanent dead touch zone | verdict |
|---|---|---|---|---|
| **(a)** permanent 40 % tall, View ignores events below 32 dp | 720×512 px always touchable | yes | **368 640 px², always** — touches over the top 40 % of every app, and of our own panel, are delivered to the overlay and dropped (a single overlay cannot forward them) | rejected |
| **(b)** keep 32 dp, open on DOWN, add a second full-height window while the shade opens | 64 px + second surface | n/a | none at rest, but… | rejected |
| **(a′)** shipped: 64 px at rest, 512 px for the in-flight gesture | 64 → 512 → 64 px | yes, up to 448 px of travel | **none at rest**; only while a gesture that started in the top 32 dp is in flight | **chosen** |

Why (b) was rejected even though it avoids the dead zone: a gesture that the
framework has already transferred to SystemUI cannot be taken back.
`InputDispatcher::transferTouch` (via `WindowManagerService.transferTouchGesture`)
moves the pointer state to the target window and cancels the previous one
(`cancelPointersForWindowsExcept`); a second, later window only ever sees a new
`ACTION_DOWN`. So (b) adds a second full-screen accessibility surface that can
swallow touches SystemUI already owns, and buys nothing for the flash.

Why (a′) is (a) with the tradeoff removed: the same 512 px window contains the
same swipe, but it only exists while a gesture that started in the top 32 dp is
in flight — a touch that starts in app content is never routed to the strip at
all, because a window's touchable region is exactly its frame
(`framework-facts.txt` A). The window is back at 64 px on the next frame after
`UP`/`CANCEL`.

## The constraint that was hit, and what was verified on Android 16

**1. A touchable region smaller than the window is impossible for this overlay.**
Verified against the real API-36 SDK on the build host, not from memory
(`api-verification.txt`): `WindowManager.LayoutParams` has only window-wide
touch flags (`NOT_TOUCHABLE`, `NOT_TOUCH_MODAL`, `SPLIT_TOUCH`,
`WATCH_OUTSIDE_TOUCH`), no region setter; `ViewTreeObserver` has no
internal-insets member in the public stub (that is the `@hide` API that can set
`TOUCHABLE_INSETS_REGION`); `SurfaceControl.Transaction` has no input-region
setter; and `InternalInsetsInfo` / `InputWindowInfo` are absent from
`android.jar`. `View.setSystemGestureExclusionRects()` does not help: it carves
exclusion for the **back** gesture, and the shade pull is a SystemUI panel, not
a system gesture — the framework's top-swipe recogniser does not consult
exclusion regions at all (`SystemGesturesPointerEventListener.detectSwipe`,
`framework-facts.txt` D).

**2. AOSP does not cancel a touch merely because the pointer left the window.**
`InputDispatcher` only cancels pointer events for a **removed** window
(`eraseRemovedWindowsFromWindowInfo`) or for hover bookkeeping
(`framework-facts.txt` B). So "the 32 dp window cannot track a swipe" is not
what the framework does by itself; the observed cancel had a cause that the old
code did not log.

**3. There is exactly one framework path that cancels a live top-edge touch and
hands it to SystemUI** (`framework-facts.txt` C):
`DisplayPolicy.requestTransientBars()` → `mStatusBar.transferTouch()`, after
`SystemGesturesPointerEventListener` recognised `SWIPE_FROM_TOP` — start within
`system_gestures_start_threshold` = **24 dp**, travel >
`system_gestures_distance_threshold` = **24 dp** within 500 ms (48 px on this
panel). That monitor runs on the global monitor channels, which receive every
motion event even when a window owns it. This path is gated by
`controlTarget == getNotificationShade()` (it returns early when SystemUI
normally controls the status bar, and fires when an app controls the bars, i.e.
immersive/fullscreen), so it is *conditional*, not universal — which is exactly
why the strip's new cancel log classifies instead of assuming.

Because 24 dp < 32 dp, the old "cancel at the 32 dp edge" reading was not
provable either way: the framework thresholds are just below the old window
edge. The new log settles it on the device — a cancel while the window is
attached, touchable and 512 px tall is **not** a bounds exit, and is logged as
such.

## Code changes (`repo/actioncentre/app/src/main/java/org/ztmetro/actioncentre/ShadeBridgeService.java`)

| what | how |
|---|---|
| gesture height, derived, not hardcoded | `STRIP_GESTURE_SCREEN_FRACTION = 0.40f`; `displayHeightPx()` reads `getMaximumWindowMetrics()` (same source as the shade detector) with `getRealMetrics` fallback; 512 px on 720×1280, 64 px rest height from the density |
| grow / shrink in one owner | `setStripGestureHeight(boolean, reason)` updates only `params.height` + `updateViewLayout`; logs `strip window grown: 64 px -> 512 px (40% of 1280 px) reason=…` and `strip window back at rest: 512 px -> 64 px (32 dp) reason=…` |
| `ACTION_DOWN` | start-region check (`rawY` inside the 64 px rest window), grow **first**, then `openFromStrip("down")`, then swallow; the panel is started in the same dispatch |
| `ACTION_MOVE` | stays swallowed while the gesture is active; tracks count and max `dy` for the end-of-gesture log |
| `ACTION_UP` | logs `UP after N ms, K MOVE(s), max dy=…`, shrinks back, swallows |
| `ACTION_CANCEL` | logs the reason: window removed mid-gesture (attach-state listener), strip became `NOT_TOUCHABLE` (keyguard), already at rest, or **framework cancelled while attached + touchable + 512 px tall (not a bounds exit)**; shrinks back |
| lost `UP`/`CANCEL` | 1500 ms safety runnable returns the window to 64 px and says so |
| keyguard | `applyStripTouchable(false)` first returns a tall window to rest, then adds `NOT_TOUCHABLE` (unchanged lock-screen behaviour) |
| untouched | keyguard touchable lifecycle, the DOWN/MOVE/UP coordinate log format, `strip open …` + `panel start at` + `panel visible …` timeline, panel-first shade path, at most one `GLOBAL_ACTION_BACK` (still two call sites total) |

## Build

```sh
cd repo
ACTIONCENTRE_REMOTE_DIR=ztux/striprework \
EVIDENCE_DIR="$PWD/evidence/strip-rework/actioncentre" \
STATUS_STRIP_EVIDENCE_DIR="$PWD/evidence/strip-rework/status-strip" \
  bash scripts/build-actioncentre.sh
```

`hermes-ubuntu` (`~/ztux/striprework`, SDK `~/android-sdk` platform android-36 +
build-tools 36.0.0, JDK 17.0.21), plain `aapt2` + `javac` + `d8` + `zipalign` +
`apksigner` — no Gradle, no AndroidX, no network beyond the SSH staging.
Fail-safe run: 125 PASS / 0 FAIL; `apksigner verify` passes; the APK sizes and
the packaged strings were checked (`grep` of `classes.dex` shows
`strip window grown`, `not a bounds exit`, `DOWN below the start region`).

## Acceptance on the device (lead, one gesture)

`logcat-acceptance.txt` has the exact strings this build emits, with the real
numbers for this panel; the four checks on one capture are:

1. `DOWN … tall=false window=64 px` → `strip window grown: 64 px -> 512 px` →
   `strip open down …` → `panel start at 0 ms` → `panel visible … BACK none`;
2. `grep -c GLOBAL_ACTION_BACK` = **0** on the strip path;
3. `grep -c "shade detected:"` = **0** — the stock shade never opened;
4. `grep -c CANCEL` = **0** — the strip kept the whole gesture.

Then a tap on the top edge (same lines, `max dy` 0–4 px) and, as the control,
a swipe that starts **below** the strip (no `ShadeTouch` lines; the app gets it).

## Boundaries / what is not proven here

* **No device run.** No adb in this task: nothing was installed, no logcat was
  captured, no gesture was measured on the phone. The acceptance lines are the
  strings this build emits, not observed output.
* The framework reroute in section C cannot be defeated by window geometry. If
  the device still shows a flash, the new `CANCEL … reason=framework cancelled
  the touch while the window was attached, touchable and 512 px tall` line names
  it, and the zero-flash path is then the SystemUI-side hook
  (`repo/sysui/shade-hook`, already built), not a taller overlay. The other
  companion option is a framework RRO that neutralises the top-swipe thresholds
  (`system_gestures_start_threshold` / `system_gestures_distance_threshold` in
  `android`), which is a real change to the immersive “reveal the bars” gesture
  and is outside this task's write scope — not built here.
* The 448 px of travel that stays inside the window is enough for a shade pull
  (the framework threshold is 48 px); a drag longer than 448 px still leaves the
  window, but the panel was already started on `DOWN`.
* The strip is `NOT_TOUCHABLE` while the keyguard is locked (unchanged), so the
  lock screen's top edge is not affected.
* No git commit/push; writes are limited to `repo/actioncentre/**`,
  `repo/scripts/build-actioncentre.sh` (unchanged) and
  `repo/evidence/strip-rework/**`.

## Files here

| file | what |
|---|---|
| `actioncentre/ztmetro-actioncentre.apk` | the built APK (349 900 bytes, sha256 `01bd51…c147`) |
| `actioncentre/verify.txt` | 125 PASS / 0 FAIL static checks |
| `actioncentre/dexdump-classes.txt`, `aapt2-*.txt`, `apksigner-verify.txt`, `artifact-facts.txt` | artifact facts |
| `api-verification.txt` | API-36 SDK evidence that a sub-window touchable region is not available to an app |
| `framework-facts.txt` | pinned LineageOS 23.2 sources, hashes, and the four excerpts (touchable region, cancel paths, `transferTouch`, gesture thresholds) |
| `measurements.txt` | geometry on this panel + the (a)/(b)/(a′) comparison |
| `logcat-acceptance.txt` | exact expected logcat transcript + the four acceptance greps + the residual-case line |
| `status-strip/` | the same build's status-strip evidence copies the script always emits |
