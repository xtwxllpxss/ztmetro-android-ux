# ztmetro parity — mockup vs shipped vs fixed (2026-11-16)

Density on the phone is **1.625 px/dp** (260 dpi, 720x1280 px = **443.1 x 787.7 dp**,
confirmed by `scripts/ux-verify.py` and by the 45 dp app-list square measuring 73 px).
The mockups are 720x1280 px at **2.0 px/dp** (360x640 dp), so every value below is
converted to dp before comparing.

Sources: `mockups/out/{start,app-list,action-centre,lock,keyboard}.png`,
`repo/evidence/ux-compare/current/{k0-start,p0-apps,d2-action,cc-lock,u1-ime}.png`.
"before" = the v4 APKs that produced those screenshots; "after" = the code in this
build, measured from the code arithmetic (no adb in this session — the lead installs
and re-runs `scripts/ux-verify.py`).

## Table

Units: px as measured on the PNG, dp after division by 1.625 (device) or 2.0
(mockup). Top-anchored elements use y from the top; lock rows use px/dp **from the
bottom** because the lock block is bottom-anchored (the screen is 788 dp, not 640).

| # | element (mockup) | mockup px | mockup dp | before px | before dp | Δ before | after (code) | Δ after |
|---|---|---|---|---|---|---|---|---|
| 1 | Start first tile top (42) | 84 | 42.0 | 46 | 28.3 | **-13.7** | 42.3 | +0.3 |
| 2 | Start page margin (14) | 28 | 14.0 | 23 | 14.2 | +0.2 | 14.0 | 0 |
| 3 | Start gutter (7) | 14 | 7.0 | 11 | 6.8 | -0.2 | 7.0 | 0 |
| 4 | Start unit / column (49.5 @360 → 60.9 proportional) | 99 | 49.5 | 103 | 63.4 | +2.5¹ | 63.5 | +2.6¹ |
| 5 | Start medium tile (106 @360 → 130.5 proportional) | 212 | 106.0 | 217 | 133.5 | +3.0¹ | 133.7 | +3.2¹ |
| 6 | Start glyph box top in tile (0.21 h = 22.2) | 44.4 | 22.2 | 21 | 13.2 | -9.0 | 0.21 h | 0 |
| 7 | Start glyph size (0.46 h medium / 0.56 small) | 97.5 | 48.8 | 93 | 46.6 | -2.2 | 0.46 h | 0 |
| 8 | app-list arrow circle (31) | 62 | 31.0 | 73 | 44.9 | **+13.9** | 31.0 | 0 |
| 9 | app-list arrow right / bottom margin (12 / 64) | 24/128 | 12 / 64 | 29/581 | 17.8 / 357.5 | +5.8 / +293 | 12 / 64 | 0 |
| 10 | app-list caps ink top (38) | 76 | 38.0 | 75 | 46.2 | **+8.2** | 38.3 | +0.3 |
| 11 | app-list list top (56) | 112 | 56.0 | 165 | 101.5² | +45.5 | 56.3 | +0.3 |
| 12 | app-list row pitch (55) | 110 | 55.0 | 91 | 56.0 | +1.0 | 55.0 | 0 |
| 13 | app-list icon left / size (80 / 45) | 160/90 | 80 / 45 | 130/73 | 80 / 44.9 | 0 / -0.1 | 80 / 45 | 0 |
| 14 | app-list name left (138 layout) | 276 | 138.0 | 228 | 140.3 (ink)³ | +2.3 | 138 + bearing | 0 |
| 15 | app-list letter head (27sp @ x14, block 36) | 54/72 | 27 / 36 | 45 dp hollow square @ x80 | — | style | letter 27 @ 14, block 36 | 0 |
| 16 | AC status row height (50) | 100 | 50.0 | 49 | 30.0 | **-20.0** | 50.0 | 0 |
| 17 | AC details line ink top (24.75) | 49.5 | 24.75 | 18 | 11.1 | **-13.7** | 24.75 | 0 |
| 18 | AC grid top (50) | 100 | 50.0 | 62 | 38.2 | **-11.8** | 50.0 | 0 |
| 19 | AC tile w x h (85.75 x 57 @360 → 105.6 x 70.2 proportional) | 171.5/114 | 85.75 / 57.0 | 170/170 | 104.6 / 104.6 | +0.9 / **+34.3** | 106.5 / 70.3 | +0.9 / +0.1 |
| 20 | AC tile gap (3) | 6 | 3.0 | 8 | 4.9 | +1.9 | 3.0 | 0 |
| 21 | AC tile glyph (18 @ 8,8) | 36 | 18.0 | 42 @ (centred, y13) | — | off | 18 @ 8,8 | 0 |
| 22 | AC tile label (7.6 @ x7, bottom 4) | 15.2 | 7.6 | 20 @ x7, bottom 8 | — | +2.4 size, +4 y | 7.6 @ bottom 4 | 0 |
| 23 | AC links ink top (grid bottom + 12) | 358 | 179 | 442 | 272 | grid+15.5¹ | grid+12 | 0 |
| 24 | AC notifications top (grid bottom + 45) | 424 | 212 | 500 | 307.7 | grid+71¹ ⁴ | grid+45 | 0 |
| 25 | lock clock ink bottom (197 from bottom) | 394 | 197.0 | 269 | 165.5 | **-31.5** | 197.3 (model) | +0.3 |
| 26 | lock clock ink height (67) | 134 | 67.0 | 108 | 66.5 | -0.5 | 65.7 | -1.3 |
| 27 | lock date ink top (161 from bottom, 2 x 30sp) | 322 | 161.0 | 203 | 124.9 | **-36.1** | ~160 (model) | -1 |
| 28 | lock counts row (ink 56..82 from bottom) | 112..164 | 56..82 | 94..131 | 57.9..80.6 | +1.9/-1.4 | 57..83 | +1 |
| 29 | lock hint ink bottom (17.5 from bottom) | 35 | 17.5 | 34 | 21.2 | +3.7 | 16.9 | -0.6 |
| 30 | IME key height / gap / pitch (56/1/57) | — | 56 / 1 / 57 | code 56 / 1 / 57 | 0 | — | 56 / 1 / 57 | 0 |
| 31 | IME top-key width (34.9 @360 → 42.9 @443) | 69.8 | 34.9 | code (W-11)/10 = 43.2 | +0.3 | — | 43.2 | +0.3 |
| 32 | IME key fills (letter #333, fn #1F1F1F) | — | — | #1F1F1F / #121212 | — | wrong | #333333 / #1F1F1F | 0 |

¹ Width- or grid-derived values are compared proportionally: the mockup is 360 dp
wide, the phone 443 dp. The C formula at 443 dp gives unit 60.3, medium 129.7; the
build uses 63.5 / 133.7 because `ux-verify.py` pins the **token** gutter/margin
(7/14 ±2 dp) at every width. AC links/notifications are measured from the grid bottom
because the taller mockup rows shift every absolute y.

² v3 had a 48sp `applications` page title and a permanent search field above the list.

³ "before" is the ink x of the first label glyph (140.3) vs the mockup's layout x
(138); the layout x is exactly 138 in both. Within tolerance.

⁴ v3 had an always-visible 36 dp bridge-hint row between links and notifications;
that row is now GONE once the shade bridge is enabled, which is the mockup's state.

## What was changed in code

**Start (launcher)**
- `MetroGridView`: 14 dp `start_top_pad` added (`topPadPx` was 0) → first tile 42 dp.
- `MetroTileView`: glyph box 0.46 h at 0.21 h (small: 0.56 h at 0.22 h), was
  0.44 h at 0.10 h.
- `StartActivity`: app-list arrow is the 31 dp `round_button` at right 12 / bottom 64,
  was 48 dp centred vertically.
- gutter/margin stay the 7/14 tokens (verifier pins them; see residual 1).

**App list (launcher)**
- Header is now the mockup's 28 dp band: 11.5sp caps at 34 dp, no 48sp title; the
  31 dp search circle is our one addition (search field slides in on tap — behaviour
  kept), so the A-Z list starts at 56 dp instead of 101.5.
- `MetroSectionHeaderView`: 27sp Light accent letter at the 14 dp margin on a 36 dp
  block (+10 dp before later sections), replacing the 45 dp hollow square at x 80.
- Rows 55 dp (`LIST_ROW_H`), icon column 80/45 and label 138 unchanged (already exact).
- Motion: the app-list collector feeds the individual letter heads and rows to the
  existing turnstile (rows grouped 2 per 33 ms step) instead of one list slab.

**Action Centre**
- `StatusStripView`: 50 dp two-line status row (28 dp glyph row + details at 24.75 dp,
  operator left, % and date right), was 30 dp single line.
- `QuickGrid`: side 4, gap 3, cell w=(W-8-9)/4, h=round(w*0.66), no top pad; was a
  4 dp-gap square grid starting 8 dp into the content.
- `QuickTile`: 18 dp glyph at (8,8), 7.6 sp caps label at (7, h-4), pressed
  scale 0.94 / alpha 0.85 in 90 ms down, 150 ms up.
- Links block 45 dp with its text 12 dp below the grid, so notifications start
  45 dp below the grid; the bridge hint row is GONE when the bridge is enabled.
- `NotificationRowView`: 28 dp accent square + 22 sp app name + 13.5 time + 14.5
  title + 13.5 body per the mockup; per-row dismiss 160 ms.
- `onBackPressed()` closes through `finish()` (220 ms slide down), explicitly.

**Lock overlay**
- `LockOverlayView`/`LockClockPanel`: two 30 sp date lines at a 36 dp line (was one
  20 sp), column margin 14, hint 10 sp / 0.08 em; counts 22 sp, chips 22 dp apart,
  glyph row inset 8 dp. A black→accent×0.5 gradient (#002878) replaces flat black.
- `LockOverlayController`: fade in 180 ms after addView on screen-on, fade out
  120 ms before removing on reveal.

**IME**
- Key fills #333333 (letter) / #1F1F1F (function) / space keeps the letter fill,
  matching `keyboard.html`; press flash 80 ms (fill lerp to #4D4D4D and back),
  scaled by `ANIMATOR_DURATION_SCALE`, 0 = no flash. Key-pop preview not added:
  no cheap room above a 56 dp row (documented in `ZtKeyboardView`).

## Residual > 2 dp after this build

1. **Start unit +2.6 dp, medium tile +3.2 dp** (proportional at 443 dp). Reason:
   `ux-verify.py` `start.grid_metrics` requires gutter 7 ±2 and margin 14 ±2 **at any
   width**; the C's `gutter = 2 %W` rule would give 9/18 and fail the verifier. The
   fixed 7/14 tokens are kept, so the 6-unit field stretches.
2. **`ac.tiles_square` will FAIL in `ux-verify.py`**: the check wants square tiles
   ±3 dp, but `ztmetro.c:6230-6245` and the approved mockup both draw
   w = (W-8-9)/4, h = round(w*0.66) — 106.5 x 70.3 dp on this panel. The check is
   stale relative to the design; the APK follows the mockup/C. (Verifier is outside
   this task's write scope.)
3. **Lock clock/date** match the mockup only in the model: the deltas above are
   computed from Open Sans metrics calibrated on the v3 screenshot, not from a new
   capture (no adb in this session). The lead must re-shoot to confirm.
4. **IME**: `u1-ime.png` is the **stock AOSP keyboard** (RU 11/11/11, bottom
   RU/shift/space/backspace/enter), not our APK — our IME has 10/9/7+shift rows and
   the `&123|smiley|,|space|.|enter` bottom. So there is no device column for the IME;
   only the code vs mockup rows above. The lead must enable `org.ztmetro.ime` and
   re-shoot before accepting key geometry.
5. **App-list caps copy**: mockup says ВСЕ ПРИЛОЖЕНИЯ, ours says ZTMETRO (the app is
   English-only; no values-ru). Position/size match, the string does not.
6. **App-list search** is not in the mockup: kept as a 31 dp circle in the caps band
   (field hidden until tapped), so the list body can start at 56 dp.

## Motion added (all scaled by Settings.Global.ANIMATOR_DURATION_SCALE)

| surface | motion | duration |
|---|---|---|
| Start | turnstile out / in / stagger (verified: tokens 128/180/33, `Turnstile` reads them; build asserts them) | 128 / 180 / 33 ms |
| Start | tile press delay / lean in / out / 14° | 80 / 90 / 200 ms |
| app list | each letter head and row turns/fades in on the turnstile, 2 rows per step | 180 ms, 33 ms step (≈0.5 s total) |
| Action Centre | tile press scale 0.94 + alpha 0.85, ripple-free | 90 ms down / 150 ms up |
| Action Centre | panel close on back | 220 ms slide down |
| Action Centre | notification row dismiss slide-out | 160 ms |
| lock overlay | fade in on screen-on / fade out on reveal | 180 / 120 ms |
| IME | key fill flash (#333 → #4D4D4D → back) | 80 ms |
| IME | key-pop preview | not added (no room; deferred) |

## APKs (built on hermes-ubuntu, ~/ztux/parity, SDK android-36, JDK 17)

```
f93f137a6c118c7957ed752c60556808620b7ac0a6b9e7c4204093527feb9639  74155  ztmetro-start.apk        (org.ztmetro.start 4.0)
fbd56147cb3235c6f75492d43b4f743174a4b90779e6a567a69b1f31ce2edaf9 341708  ztmetro-actioncentre.apk (org.ztmetro.actioncentre 1.0)
5eebf7b33ef7ddbe9d71c034368d59c9f37c5b3f3fdf2c2b19a400363363688e  20817  ztmetro-ime.apk          (org.ztmetro.ime 1.1)
```

Note: `repo/actioncentre` was being edited concurrently by another agent
(`UnlockProxyActivity`, `LockClockPanel`) while this build ran; the AC artifact is the
tree at 14:52 MSK and its hash is pinned above. The launcher and IME trees were stable.

Observation for the owner's "why can't the shipped UI follow the mockups": the
differences were not taste — they were five concrete numbers (Start's missing 14 dp top
pad, the 0.10 h glyph, the 48 dp arrow, the app list's 48sp title + 80 dp square letter
heads, the AC's 30 dp/ square tiles) plus the missing lock date line. Everything else
already matched the mockup within ~1 dp.
