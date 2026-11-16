# ztmetro motion in the launcher and Action Centre

Status 2026-11-16: implemented, both APKs built on `hermes-ubuntu`, **not run on the
device** (no adb on this host; the lead owns the phone). Every duration below is a
constant compiled into the APK and checked by the build script; the feel at 60 Hz on
the ugglite panel is the one thing that still needs a device look.

Verbatim source lines are in [`sources.txt`](sources.txt). The two artifacts and their
build facts are under [`launcher/`](launcher) and [`actioncentre/`](actioncentre).

## The five motions

| # | motion | implementation | number | source |
|---|---|---|---|---|
| 1 | Start ↔ app-list turnstile | `Turnstile.java` (Choreographer, one scene position `p`; each block is a pure function of `p`) | out **128 ms**, in **180 ms**, stagger **33 ms**; turn 50° out / 80° in; 9.8 % sweep | `core/zrnmetrocore.c:50-52,66-67`; curves `:1424-1475`; sweep `ztmetro.c:228` |
| 2 | tile press tilt | `MetroTileView.java` (`commitTilt`, `resetTilt`, `highlightRunnable`) | delay **80 ms** → lean **90 ms** to **14°** + 0.97 scale → release **200 ms**; long press **450 ms**, cancels the lean; white press wash **16 ms** before the lean | `ztmetro.c:148,145`; `core/zrnmetrocore.h:32,35,36` |
| 3 | pivot / page header | the app-list page is four blocks (header, search row, list, bottom strip) staggered 33 ms top-to-bottom, so the header leads the page in | 33 ms per block, 180 ms each | `core/zrnmetrocore.c:52` |
| 4 | Action Centre open / close | `ActionCentreActivity` slides its own root view (`openRunnable`, `finish()`); handle drag follows the finger 1:1 and snaps | open **260 ms**, close **220 ms**, drag commit **40 dp** | `ztmetro.c:138,6558` (SHADE_MS 260); the 220 is the repo's existing `ac_slide_down.xml`; commit `ztmetro.c:11761` |
| 5 | system animator scale | `Motion.scale()` in both apps; every duration goes through `Motion.ms()` | `Settings.Global.ANIMATOR_DURATION_SCALE`; **0 = instant** | requirement; also `docs/21-performance.md` §1.5 |

### Turnstile timeline (actual, at scale 1.0)

`rows_duration = last_stagger * 33 + row_ms` (`core/zrnmetrocore.c:1393-1396`), applied
to our blocks:

* Start page: three visual columns → outgoing ends at `33*2 + 128 = 194 ms`;
* app list: three blocks on the ugglite (no on-screen nav bar) → incoming runs
  `194 … 194 + 33*2 + 180 = 440 ms`; four blocks on a nav-bar device → 473 ms.

The incoming page starts when the last outgoing column lands (no black `GAP_MS 120`,
`core/zrnmetrocore.c:60`): that gap belongs between two independent app surfaces, while
ztmetro's own Start↔list change is a 300 ms pivot (`ztmetro.c:179 PAGE_MS`) — inserting
120 ms of black here would turn a 300 ms switch into 560 ms. The 128/180/33 the brief
asked for are exact; the gap is the one deliberate omission and is called out here.

The curves are the C's, not a generic decelerate: out `e = t*t` with alpha held to
`e = 0.7` then faded over the last 0.3; in `e = 1-(1-t)^3` with alpha solid after
`e = 0.3` (`core/zrnmetrocore.c:1428-1471`). Both are monotonic — no overshoot anywhere.
The 3D turn uses `rotationY` with `pivotX = 0` (left edge) and the default camera
distance; it is applied to the real tile views, so the 3D work is done by the renderer,
not by a bitmap copy.

Interruptible: a second swipe during the 440 ms calls `Turnstile.retarget()`, which
keeps the same page pair and animates the scene position back to the other end from
where it is. No per-frame allocation: one `Choreographer.FrameCallback` object, parallel
arrays, no `ValueAnimator` boxing, no `String` work in the frame loop.

### The brief's numbers that the C does not contain

Read against `ztmetro.c`/`zrnmetrocore.*`, three numbers in the brief are other numbers
from the same block; the C values are what shipped:

| brief said | C says | evidence |
|---|---|---|
| 90 ms delay before the lean | **80 ms** press delay; 90 ms is the lean-in duration | `ztmetro.c:148` `PRESS_DELAY_MS 80`; `core/zrnmetrocore.h:35` `ZMC_TILT_IN_MS 90` |
| 200 ms hold threshold | **450 ms** hold; 200 ms is the release-out duration | `ztmetro.c:145` `LONG_PRESS_MS 450`; `core/zrnmetrocore.h:36` `ZMC_TILT_OUT_MS 200` |
| release resets in ~120 ms | **200 ms**, the Log(10) return | `core/zrnmetrocore.h:36` |
| Action Centre closes in 220 ms | ztmetro has **one** `SHADE_MS 260` for both directions | `ztmetro.c:138,6558`; kept 220 because the shipped repo animation already used it and the brief asks for it |

Hold thresholds (80 / 450 ms) are not scaled by the animator setting: they decide *when*
a press or hold is real, and at scale 0 the lean is instant but a long press still waits
450 ms. Animation durations (90/128/180/200/220/260/33) all scale, and 0 makes them
instant.

## Action Centre: what moved and why it is not a window animation

The window is now `windowIsTranslucent` with a transparent background and no
`windowAnimationStyle`; the panel's root is translated by us. That buys three things a
window resource cannot: the 260/220 ms values follow `ANIMATOR_DURATION_SCALE`, the
handle can drag the panel 1:1, and the launcher stays visible behind the travel. The
old `ac_slide_up/ac_slide_down` resources stay compiled so the existing verify checks and
other consumers keep working. The 40 dp commit is in points, as in the C; our panel is a
bottom sheet, so a downward drag closes it (ztmetro's shade is a top shade whose bottom
handle is pushed up).

## Build and measurements

Built on `hermes-ubuntu` (SDK `~/android-sdk`, JDK `~/android-jdk`) with
`repo/scripts/build-launcher.sh` and `repo/scripts/build-actioncentre.sh`; both scripts
now write `motion-facts.txt` and fail if a motion token or class is missing from the
artifact.

| artifact | size | sha256 |
|---|---|---|
| `ztmetro-start.apk` | 65 814 B | `46682bb48dee863a6f7c85b23a1e6321d93542465f11981c70e25f40a0f02a11` |
| `ztmetro-actioncentre.apk` | 304 621 B | `d26c4f89cf93ae65ce46459a28379bd17e5d10f42c0d497afd6cd9234d0cd607` |

Static checks that passed: no androidx, no new permissions, HOME category and the
notification listener still in the launcher; tokens in the linked APK are
`row_out 128`, `row_in 180`, `row_stagger 33`, `press_delay 80`, `press_in 90`,
`press_out 200`, `long_press 450`, `tilt_deg 14` (see
`launcher/metro_tokens.generated.xml`); `Motion` and `Turnstile` are in the launcher dex,
`Motion` is in the Action Centre dex.

Frame counts at 60 Hz (for a device trace later): out 7.7, in 10.8, stagger 2.0,
tilt-in 5.4, tilt-out 12.0, press delay 4.8, long press 27.0, shade open 15.6,
shade close 13.2.

## Gestures kept

Pivot swipe Start↔list, swipe-down in the top quarter to open the Action Centre, back on
the app list (clear filter → hide keyboard → Start) all still run through the old code
paths; only `selectPage()`'s animation body changed, and the turnstile is now the thing a
second swipe retargets. The tile long-press menu, search focus, and the on-screen
nav-strip path are untouched.

## What cannot be done without a framework change

* **Turnstile to an app.** A Start→app turn is two windows; an APK can only turn pages
  inside its own window. That is WMShell / a ROM transition handler (`docs/14` P10.2,
  `design/components/motion.md`: owner **D**). The in-window turnstile above is the most
  an APK can do.
* **SystemUI motion** (status bar, QS, keyguard, nav bar) is not ours to animate; those
  surfaces need a fork/module (**D**), not this APK.
* **Window/transition animation scale.** Our in-app motions read
  `ANIMATOR_DURATION_SCALE`; anything still animated by the window manager follows
  `WINDOW_ANIMATION_SCALE`/`TRANSITION_ANIMATION_SCALE`, which an app can read but must
  not apply to itself. At "remove animations" all three are 0, so the visible result
  matches; a 0.5 vs 1.0 mismatch between our motion and a system window animation cannot
  be fixed from an APK.
* **True WP perspective.** ztmetro projects with a 34° FOV (`core/zrnmetrocore.c:68`);
  Android gives `setCameraDistance` only, so the depth of the turn is approximate.
* **Live-tile flip.** Owner agent-35; `MetroTileView` exposes `setLiveFace(Face, boolean)`
  and `setLiveFaceFlip(degrees)` as the handoff but does not draw the back face.
