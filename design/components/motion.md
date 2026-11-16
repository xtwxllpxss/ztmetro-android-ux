# Component — Motion (pivot slide, tile in/out, page change, letter swivel)

Owner: launcher APK (**A**) for everything inside our windows; turnstile to an app is
**D**. Roadmap: [`../../docs/14-ux-roadmap.md`](../../docs/14-ux-roadmap.md) §P10, Wave 1.2/1.4, 2.2, 3.2.
Tokens: the whole `motion` group in
[`../tokens/ztmetro.tokens.json`](../tokens/ztmetro.tokens.json).
Citations: `upstream/ztOS/ztmetro/ztmetro.c` (`z`), `core/zrnmetrocore.c` (`c`),
`core/zrnboard.h` (`b`).

## Original behaviour

**Pivot.** 50 pt drag commits, 60 pt lights the next title, or 0.3 pt/ms flick; release
continues at the finger's speed on a spring of 0.018/ms (`c:1812-1816`). The title strip
moves a title-width while the pages move a screen-width (`c:1806-1811`).

**Start leaves / app arrives** — a turn about the left screen edge
(`z:213-216`):

```c
#define TURN_OUT_DEG 80
#define TURN_IN_DEG  80
```

with `APP_IN 350`, `APP_OUT 250`, `TURN_GAP 110` (black between Start leaving and app
arriving) and `BACK_GAP 150` (`z:250-253`).

**Tiles** (`z:227-241`): leave at 45° on their own left edge in 85 ms, swept left, rows
from the bottom up 50 ms apart, the left of two neighbours 51 ms after the right, the
tapped tile last (80 ms wait, then 55 ms); arrive from 69.8° at 0.182 eye-distance behind
the screen over 186 ms, each row 50 ms after the one below, small tiles 33 ms early,
±6 ms jitter so the grid does not look mechanical.

**Page change** (Start ↔ app list): 300 ms, or a flick at 0.35 pt/ms (`z:179-180`).

**Letter grid**: −45° → 0 about X in 300 ms (Log-200), 12 ms domino, out +60° in 200 ms
(Exp-150) (`b:36-43`).

Easing: a `log(10)` ease-out and a back-ease-out overshoot (`z:1096-1157`).

## Android mechanism

| motion | class | Android |
|---|---|---|
| title strip slower than the page | **A** | translate the strip by `pageOffset * (titleWidth + 16)` inside a `ViewPager2` `PageTransformer` — the same ratio the C code uses |
| pivot snap | **A** | `ViewPager2` with a custom `PagerSnapHelper`/`SpringInterpolator`; the C spring has no overshoot, so use `LinearOutSlowIn` or a plain critically-damped spring, **not** `OvershootInterpolator` |
| tile out/in tied to launch | **A** (inside our window) / **D** (across the launch) | `ViewPropertyAnimator` on `rotationY` measured from the tile's **left** edge (`pivotX = 0`), rows staggered from the bottom |
| Start↔app turnstile | **D** | WMShell `DefaultTransitionHandler`/`ActivityTransitionHandler`; `ActivityOptions.makeCustomAnimation` does not give a two-window turn, and a launcher-drawn black frame leaves the wallpaper visible |
| page change | **A** | `ViewPager2.setCurrentItem(..., true)` with a 300 ms `Interpolator`; `setPageTransformer` for the slide |
| letter swivel | **A** | one `ObjectAnimator` per cell, `rotationX -45 → 0`, 300 ms, `startDelay = i * 12` |
| tilt | **A** | see [`start-grid.md`](start-grid.md) |

Interpolator mapping `[E]`: ztmetro's Log(10) ease-out ≈ `DecelerateInterpolator(1.6f)`;
the tile-return back-ease ≈ `OvershootInterpolator(1.0f)`; the pivot spring ≈
`PathInterpolator(0.2, 0, 0.2, 1)` (no overshoot). Do not use `AnticipateOvershoot` — WP
never anticipated.

## Build order

1. Pivot slide + strip ratio (this is what makes the header read as WP).
2. Tile out (rows bottom-up) — visible on every launch, so do it before the arrival.
3. Tile arrival with row stagger; add the ±6 ms jitter only if it still looks mechanical.
4. Page change; letter swivel.
5. Turnstile: reject until Wave 3.

## Acceptance test

> Launch an app from Start: the rows leave bottom-first, the tapped tile last, and they
> come back the same way when you press back — without a dropped frame at 720p. Swipe the
> header: the title moves noticeably less than the page.

## Risks

- **GPUs lie.** Adreno 308 at 720p will drop frames on 6 large rotated layers `[E]`. Cap:
  one animated row set, `setLayerType(NONE)`, no `RenderEffect`, no shadow under a rotating
  view.
- **Stagger cost.** `startDelay` on 40 views is fine; 40 `ValueAnimator`s running at once
  is not. Use one animator per *row* and offset the row's children inside `onUpdate`.
- **`rotationY` from the wrong pivot** is the single most common port mistake: the C tiles
  turn on their **left** edge, so `pivotX = 0` and `cameraDistance` ≈ 40 dp × density.
- **Update break:** none for A. The turnstile (D) rebases with WMShell every release.
