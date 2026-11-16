# 20 — Design critique of the shell review sheet

**Status.** Review only, 2026-11-16. Nothing in `mockups/` or in the repo code was changed; no
commit. This is the designer's read of `mockups/out/review-sheet.png` before implementation is
accepted — not a PASS, and not a render.

**Inputs.** `mockups/out/{start,action-centre,lock,app-list}.png` (720×1280, 1 dp = 2 px),
`review-sheet.png`, `mockups/base.css`, `mockups/screens/*.html`, `mockups/render.py`;
`design/tokens/ztmetro.tokens.json`; `docs/17-wp-design-reference.md`; `design/components/*.md`;
captures `evidence/ux-compare/refs/ztos-start.png` and `ztos-app-list.png` (1440×2960, 1 dp = 4 px),
`ztmetro-shade.png` (720×1480, 1 dp = 2 px); `upstream/ztOS/ztmetro/ztmetro.c`.

**Method.** Every number below is read off the PNGs with the black-run / ink-run extraction of
`evidence/wp-reference/measure.py` (tolerance ±8/255, so 1 px = 0.5 dp on the sheet, 0.25 dp on the
`ztos-*` captures). Tokens are the reference for dp/sp; `ztmetro.c` is the reference for anything the
token file does not carry. `[F]` = measured here or a literal line in a file. `[E]` = inferred.

**Verdict up front.** The *grid* is ready: margins, gutters, pitches and row heights sit within
0.5 dp of both the tokens and the captures, and there is not one rounded corner, shadow, gradient,
ripple or FAB on the sheet. The *surfaces* are not ready: three of the four screens have lost the one
element that makes that surface Metro — the app list lost its accent icon column, Start lost the
live-tile interior, the shade lost its status row — and the fourth, the lock screen, shows two clocks. Accept the
geometry as a proposal, reject the surfaces as a design.

---

## 1. Per-screen read

### 1.1 Start (`start.png`)

| | measured on the sheet | token / source | Δ and judgement |
|---|---|---|---|
| **Hierarchy** | Eye goes to `21°` (44 sp, 43 dp ink) first, then to 16 equally weighted accent rectangles. Second fixation is the 31 dp ring alone in ~200 dp of black. | `draw_tile` (`ztmetro.c:3660-3697`) has four marks only: glyph, label, live count, badge/progress. Nothing draws a 44 sp temperature. | **Wrong by construction.** The sheet's focal point does not exist in the source; the tile *field* should be the primary object and the live count the only in-tile number. |
| **Grid / margins** | tiles at x 14 / 127 / 240, w 212 px = 106 dp, gutter 14 px = 7 dp, rows at 42 / 155 / 268 / 381, right edge 346, smalls 49.5, wide 219. | `metrics.tile_size_dp 49.5`, `tile_gap_dp 7`, `start_margin_dp 14`, `start_top_pad_dp 14`, `medium 106`, `wide 219`. | **0 dp off** — and identical to `ztos-start.png` (14 / 106 / 7 / rows 42, 155, 268, 381) [F]. This is the strongest part of the sheet. |
| **Tile interior** | icon ink 23.5 × 17 dp, anchored top-left at (8, 8). | `draw_glyph(…, w/2, h*0.44, h*0.46*k)` (`:3677`): centred at (w/2, 0.44 h), size 48.8 dp in a medium tile; the capture's icon ink is 65 × 62 dp. | **−52 % size, −32 dp in x, −36 dp in y.** The tiles read as Android app-grid cells, not live tiles. |
| **Type scale** | label 13 Regular, x 8–9 dp, ink 10 dp, 9.5 above the bottom; count 22 Light, right inset 9 dp, ink 15.5 dp; status clock 15 Regular. `21°` is 44 sp. | `type.ramp.body 13`; label `x=8, y=h-23`, count `FONT_LIGHT 22` at `w-8` (`:3679, 3685`); strip clock `cell*1.3 = 14.95` (`:5488`). | Everything **matches** except `21°`: 44 sp is in no ramp slot (48 page title, 25.5 list title) and in no C call. |
| **Colour / contrast** | tile `#0050EF` exact; ink `#FFFFFF`. White on accent **6.19:1**; white on `#1F1F1F` **16.48:1**; `#9E9E9E` on black **7.84:1**. 16 of 16 tiles are accent. | `color.accent.base`, `foreground_on_accent`; but `draw_tile` paints `t->color` and the capture shows a steel tile `#646B73`. | Ratios pass. **The sheet deletes the per-tile colour** that both the C and the capture use, so nothing can be emphasised and the whole field reads as one blue mass. (Caveat: the device currently renders `#0049CB`, docs/17 §7 — the sheet is *more* correct than the phone here.) |
| **Motion / affordance** | none shown. Only navigation cue is the app-list ring at y 545–576, right inset 12 dp. | tilt 14° / 90 ms in, 200 ms out, sink 0.045, 450 ms edit; `ROUND 31`; the arrow belongs "under the last row, on the right" (`:3337`). | The scan path has no press state at all, and the arrow is anchored like a **FAB**: the capture puts it at y 504–534 (content-anchored, last row ends 487) and 14 dp from the right. 41 dp too low, 2.5 dp too far out. |
| **WP recognisable?** | **Grid yes, tile no.** A WP user reads the field as Start and then asks why every tile is a ~24 dp line icon stuck in the corner. | | |

Also on Start: the strip orders `[clock][battery]` where the C draws the clock rightmost at `width-14`
(`:5620`) with the battery `cell*0.9` to its left (`:5508`); the data arrow is an 8 × 12 dp standalone
glyph where the C draws a 4.6 dp transient at the corner of whichever network is passing data (`:5420`,
`:5446`); and the nav glyph boxes are 10–13 dp of ink where `draw_nav` uses `draw_icon(…, 20)`
(`:6626-6633`).
The nav's centre mark is a stroked diamond; `draw_nav` can draw the ZRN logo, a filled 18 dp square,
a 4-square mark, a circle or the text "ZRN" — never a stroked diamond (`:6638-6653`).

### 1.2 Action Centre (`action-centre.png`)

| | measured on the sheet | token / source | Δ and judgement |
|---|---|---|---|
| **Hierarchy** | 8 quick cells are the entry point, and they are right. But the top 50 dp is empty black, so there is no clock, battery, carrier or date; the eye meets a floating grid. | `draw_shade` calls `status_row(server, cr, true, false)` (`:6503`), whose details line is carrier 12.1 sp at x 14 plus battery % and date right-aligned at `y = base+5` (`:5624-5639`). `ztmetro-shade.png` confirms it (carrier ink 27.5–34.5 dp). | **The shade loses the only clock it has.** Highest-impact miss on this screen. |
| **Grid / margins** | tiles x 4 / 93 / 181.5 / 270.5, w 86, gap 3, side 4; rows 50–107 and 110–167; links ink 181.5–189.5; notifications at 212; handle 584–600 (16 dp); nav 40. | `action_centre_side 4`, `gap 3`, `tile_width 85.75`, `row_height 57`, `top 50`, `handle 16`; links `= grid + 12`; notes `= links + 30`. | **≤1 dp everywhere**, links match the reference row to a pixel (ref 182–190). |
| **8th cell** | renders `#121212` at 30 % opacity → **`#050505`**; no tile edge is detectable at y = 110 for x > 270. Glyph/label land at **2.53:1**. | `quick_action_empty #121212` (0.07); the C applies 0.30 to the *glyph* of an unavailable action, not to the fill (`:6318-6321`). In `ztmetro-shade.png` the unavailable FLASHLIGHT cell is still a legible `#1F1F1F` cell. | **The cell disappears.** A 4 × 2 grid becomes 7 cells plus a floating glyph, which reads as a broken panel. |
| **Type scale** | labels 7.6/600, links 10/600 (+.04 em), name 22/350, title 14.5/600, body 13.5 at .62, time 13.5 `#8C8C8C`. | C: `:6350` labels, `:6515-6520` links, `:6356-6412` notifications. None of 7.6 / 10 / 14.5 / 13.5 exists in `type.ramp` (it steps 12 → 13 → 17). | The sheet is right and **the token file is incomplete** — add the four shade roles, or this surface can never be checked against tokens. |
| **Colour / contrast** | white on `#1F1F1F` 16.48:1, white on accent 6.19:1, `#8C8C8C` on black 6.25:1, unavailable cell 2.53:1. | `quick_action_on #0050EF` (the reference also draws exactly (0, 80, 239) [F]), `quick_action_off #1F1F1F` (reference `#1F1F1F` [F]). | ON/OFF colours exact. The unavailable state fails 4.5:1 **twice over**: the rendered 2.53:1 and even the token-correct construction (fill `#121212`, white at .30) gives 2.68:1. |
| **Motion / affordance** | handle present and correct (16 dp accent + 28 × 3 grip, above the nav bar, as the C intends); no drag cue, no scroll, no pressed state. | `shade_ms 260`; the notification list scrolls (`notes_layout` / `notes_build`), the sheet shows 3 items in ~370 dp of black. | Handle yes; everything else is a still of a panel that is supposed to move. |
| **WP recognisable?** | **Half.** 4 × 2 cells and the accent handle say Metro; the empty top band and the missing 8th cell say "our panel is half-done". | | |

Three AC deltas the table does not carry. **The notification block is indented as a whole:** name,
title and body all start at x 52–53.5 in the sheet, but the C hangs only the *name* to the right of the
28 dp square (x 52) and sets title and body full width from the **left margin x 14** (`:6377, 6391,
6398`), so the sheet's three text lines are shifted right by 39.5 dp and the block reads as a caption
under an icon instead of a row that spans the panel. Labels sit 5 dp above the tile bottom where the C
puts them ~9.5 dp (`y = h-15`, `:6350`). And the links row has no leading glyphs — the C draws `close`
at x 19 and a `gear` before ALL SETTINGS with the text starting at x 32 (`:6515-6520`); the sheet's
text starts at 14.

### 1.3 Lock (`lock.png`)

| | measured on the sheet | token / source | Δ and judgement |
|---|---|---|---|
| **Hierarchy** | Eye lands on the 92 Light clock — correct — but a second `21:47` sits 350 dp above it in the status strip, and the notification row (13 dp tall) is too small to be the second layer. | `draw_status` then, if locked, `draw_lock` (`:6217-6221`); `draw_lock` paints an opaque full-screen fade (`:6029-6035`), so **the lock covers the strip**. | **Two clocks, and the smaller one is not supposed to be visible.** The "Light = the one huge number on a screen" rule (docs/17 §6) is broken by the sheet, not by the C. |
| **Grid / placement** | clock ink top 376.5, left ink 17.5; date line boxes at 466 and 500 (34 dp); notes band 554–567; hint ink 616.5–623.5. | clock `height-268` (372), x 16, `display_large 92/300`; dates `height-156` (484) and `height-120` (520) = 36 dp, 30 Semilight, x 20; notes `height-100`, glyph 26, count 22 Semilight, x 22, row centre `height-120-LOCK_LIFT+64 = 571`. | Clock within 4.5 dp of its anchor. Date pitch **34 vs 36 dp (−2)**. Notes row is **−38 % glyph (16 vs 26) and −36 % count (14 vs 22)**, 8 dp left of its x. |
| **Type scale** | 92 Light ✓, 30 Semilight ✓ ×2, 14 counts ✗, hint 10/600 ✗. | ramp `display_large 92`, no 30 role, no 14, no 10; hint has no C counterpart at all. | 10 sp is below the ramp floor (`caption_caps 11.5`) — the smallest text on the sheet is off-scale. |
| **Colour / contrast** | gradient `#000` → `#000b2a` at 62 % → `#002878`; endpoint exact. Hint `rgba(255,255,255,.55)` at the bottom → composite (140, 158, 194), **4.96:1**. | `color.accent.dark1 #002878` = accent × 0.5; fade is two stops, 0.25 black → 1.0 accent × 0.5 (`:6029-6035`). | **The extra 62 % stop darkens the mid-fade**: measured (0, 11, 43) where Cairo's linear ramp gives (0, 20, 59) — 45 % dark in G, 27 % in B. Endpoint is right, the bloom is late and steep. |
| **Motion / affordance** | a text hint is the only affordance; no unlock wash, no wake lift. | swipe up; success wash `.4,1,.5`, failure `1,.5,.5` (`:10162-10163`); `LOCK_LIFT = NAV_H+16-43 = 13` (`:5925`). | **"СМАХНИТЕ ВВЕРХ" is invented** — no such element in `ztmetro.c`; Android already supplies a gesture affordance, and inventing a 10 sp label is the Android-ism this port is supposed to avoid. |
| **WP recognisable?** | **Composition yes** (big clock low-left, weekday/date under it, count row above the bottom edge); **artefacts no** (two clocks, chip-sized counts, a helper label). | | |

### 1.4 App list (`app-list.png`)

| | measured on the sheet | token / source | Δ and judgement |
|---|---|---|---|
| **Hierarchy** | Nothing lands: every row is 22 sp Semilight white with a 1.3 dp-stroke outline icon; the only colour is six bare accent letters at 3.39:1. | `draw_item` (`:4504-4528`) fills a 45 dp **accent square** per app and strokes a 45 dp **accent outline** for each letter head; `ztos-app-list.png` shows both. | **The accent column is the hierarchy** — the eye should scan a blue column of squares. The sheet has no mass, so the list reads as settings rows with stray blue initials. |
| **Grid / margins** | icons at x 14–59, names at x 80 (ink 81.5), rows 55 inside a group, group starts 101 apart, caps ink 36.5–45.5, last visible name 580–600, nav from 600. | `LIST_ICON 45`, `LIST_ROW_H 55`, `LIST_LEFT 80` = the **icon** column, name at `LIST_LEFT + LIST_ICON + 13 = 138` (`:4525`); capture: square 80–125, name 139.75 [F]. | **Icons −66 dp, names −58 dp, and no square.** docs/17 §3 calls 80 the "text column"; the C and the capture both say it is the icon column — the sheet inherited the mislabel. Group pitch is **+46 dp per letter** (34 + 2 + 10) against the capture's uniform 55. |
| **Overflow** | content bottom = 56 + 91 + 5 × 101 = 652 dp. `Настройки` (560–615) is clipped by the nav bar; the П group (Погода, Почта) and the Ф group (Фотографии) plus 2 letter heads are never rendered. | nothing in the C clips; the list scrolls. | **10 apps and 6 heads go in; 3 apps and 2 heads never render**, and the 7th row is cut. The sheet says A–Z and shows Б–Н. |
| **Type scale** | caps 11.5/600/.06 em `#9E9E9E`, names 22/350, letters 27/350 uppercase. | `caption_caps 11.5` ✓ token, but the **applied** WP correction is 15 sp + .08 em (docs/17 §11 #2), and a caps line without a 48–58 sp title contradicts the two-line header rule (§2). Letters: 27 inside the outline, **lower case** (`:4504-4505` comment, `:4517`; capture "a/b/c"). | The only page-header element on the whole sheet is the pre-correction caps, used without its title, on a screen that has **no header in either the C or the capture**. Uppercase letters are wrong too. |
| **Colour / contrast** | caps `#9E9E9E` 7.84:1 ✓; names white 21:1 ✓; letters `#0050EF` on black **3.39:1**. | `list_letter_accent`; spec = accent 1.5 dp outline with a **white** letter inside → 21:1. | 3.39:1 passes AA-large by 0.39; the specified treatment is 21:1. The sheet picked the weakest option and lost the outline. |
| **Motion / affordance** | none; and the top-left slot that the capture uses for the 31 dp round search button (`x 35–66, y 40–71`) holds the invented caps line instead. | `ROUND 31` search at `x = 51` (`:166-167`), swivel letter grid 300 ms, 28 cells. | **No search affordance at all** on a list of 10 apps, and no scroll cue. |
| **WP recognisable?** | **No.** A WP user sees a black list with outline icons; the accent squares and the letter tiles are the identity and they are missing. | | |

---

## 2. The ten biggest deviations, ranked by visual impact

| # | deviation | exact token / measure to change | expected visible result |
|---|---|---|---|
| 1 | **App list has no accent icon column** | `metrics.list_left_dp` is the **icon** column (80), name at `list_left + list_icon + 13 = 138`; each row fills a 45 dp square in `color.accent.base` with the app glyph at 25 dp (`ztmetro.c:4519-4525`) | A blue column of 45 dp squares with names at 138; the screen gets its scan rhythm back and matches the capture to ±2 dp |
| 2 | **Start tile interiors are generic line art** | glyph centred at `(w/2, 0.44·h)` at `0.46·h` = 48.8 dp (`:3677`), real app icon; delete the 44 sp `21°` | Tiles read as live tiles; the first fixation is the field, not an invented number |
| 3 | **Shade has no status row** (0–50 dp empty) | draw `status_row(details=true)`: carrier 12.1 sp at x 14, battery % + date right-aligned at `y = base+5` (`:5624-5639`, `:6503`) | Clock, battery, network and date visible with the shade down — the sheet stops looking like a headless panel |
| 4 | **Unavailable quick cell vanishes** (`#050505`) | 0.30 alpha on glyph/label only; fill stays `color.chrome.quick_action_empty #121212` (`:6318-6321`) | 4 × 2 stays a grid; measured fill `#121212`, not `#050505` |
| 5 | **Letter heads are bare uppercase accent glyphs** | 45 dp square, 1.5 dp accent stroke, letter 27 Semilight **lower case** inside the top-left (`:4514-4522`) | Letters join the icon column instead of floating; contrast 21:1 instead of 3.39:1 |
| 6 | **Lock shows the status strip** → two `21:47` | no status layer over the lock layer; the lock fade already covers it (`:6217-6221`) | One time on the screen; the 92 Light clock regains its role |
| 7 | **App list overflows and clips** | letter head at the 55 dp pitch (no 34 + 2 + 10), verify the content box at 640 dp, add a scroll cue or shorten the alphabet | `Настройки` no longer cut by the nav bar; the sheet stops claiming A–Z while showing Б–Н |
| 8 | **AC notification text is double-indented** | title and body from the left margin x 14, full width; only the 22 Semilight app name hangs right of the 28 dp square (`:6377, 6391, 6398`) | Three lines shift 39.5 dp left, align with the panel's left edge and the tile grid, and read as one full-width row per notification |
| 9 | **Start arrow sits like a FAB** | arrow top = last row bottom + 17 dp (504 at this content), right inset 14 dp (not 12) | It belongs to the content and stops reading as a Material floating button |
| 10 | **Lock count row is chip-sized** | glyph 26 dp, count 22 Semilight, x 22, centre `height-120-LOCK_LIFT+64` (`:5956-5985`) | The counts become the second information layer instead of decoration |

Outside the ten, but fix with the same pass: the lock fade's extra 62 % stop (`#000b2a`) → two stops
only, so mid-fade is (0, 20, 59) not (0, 11, 43); caps 11.5/.06 → 15/.08 (docs/17 §11 #2) or give the
app list no header at all; restore the 31 dp search button; swap the strip's battery/clock order;
shrink the data arrow 8 × 12 → 4.6 dp and make it transient; lift the AC labels 4.5 dp; nav glyph
boxes 20 dp with a real centre mark (not a stroked diamond); per-tile colour so not all 16 are accent.

---

## 3. Not portable — what the sheet must not promise

The sheet is an HTML proposal. Five of its elements cannot exist on stock Android 16 / LineageOS 23.2
without a SystemUI fork; the sheet must say so, or the next reader will treat the contact sheet as a
delivery target.

1. **The square 4-column shade.** In 23.2 the Quick Settings grid is Compose, and the tile shape is a
   `RoundedCornerShape` built from compile-time `Dp` constants — `InactiveCornerRadius = 50.dp` (clamped
   to h/2), `ActiveTileCornerRadius = 24.dp`, `ActiveIconCornerRadius = 16.dp`
   ([`evidence/qs-square/REPORT.md`](../evidence/qs-square/REPORT.md)). Measured on the live shade,
   radius/height = 0.494 and 0.329, i.e. 36/72 and 24/72 — two radii in one screenshot, so no single
   dimen can drive them; the RRO *is* live (4.31 dp gap = the overlay's `qs_tile_margin_horizontal`),
   it simply cannot reach the radius. So the sheet's AC grid is deliverable as **our own APK panel (A)**
   or **a SystemUI fork (D)** — never as "the shade", and never as an RRO pass.
2. **The 28 dp status strip as drawn.** `status_bar_height` is class **D / device** in
   `design/portability-matrix.json` (risk #1: sweet's device overlay sets 36.36 dp and the static RRO
   wins under the punch-hole); the glyph cluster, its order and the data arrow are SystemUI layout, also
   D. On stock A16 the strip is Android's, not ours.
3. **The lock screen.** The keyguard is Compose/flexiglass in A16; an RRO can only tint text
   appearances, and the clock plugin seam needs `com.android.systemui.permission.PLUGIN`
   (`signature`) which we do not have (`design/components/lock-screen.md`). The 92 Light clock, the
   accent fade, the glyph row **and "no strip"** are all fork-only (D).
4. **The WP bottom nav triad and the shade's bottom handle.** Stock A16 gives the gesture pill or
   SystemUI's 3-button bar; replacing glyphs/order is a fork (D). The 16 dp push-up handle sits in the
   gesture-inset region and only exists inside a panel we own (A) or in a fork (D).
5. **Four quick settings across** at all: the Compose grid reads only `qs_tile_margin_horizontal/vertical`
   from resources ([`evidence/qs-square/REPORT.md`](../evidence/qs-square/REPORT.md) §1), so
   `quick_settings_num_columns` only reaches the legacy `QSTileViewImpl` path that nothing on this
   shade calls (`design/components/action-centre.md`). Four columns = A/D.

Two further portability traps that are not Android limits but *device* facts:

- **Typeface.** `base.css:37` asks for `-apple-system, "Helvetica Neue", "Segoe UI", Selawik,
  system-ui`. On this Mac that resolves to an Apple face; Segoe is unobtainable, and nothing
  `@font-face`s `repo/fonts/opensans/*.ttf`. The phone's `sans-serif` is **Open Sans** (font module
  1.1.0). So the sheet's letterforms are a third family — type-width and tracking judgements on this
  PNG do not transfer. Load Open Sans (or Selawik for Latin) in `base.css` and re-render.
- **Fixed 360 × 640 dp.** ugglite runs 280 dpi = 411 × 731 dp, sweet 440 dpi = 393 × 873 dp
  (`design/portability-matrix.json`, `design-tokens` row). Absolute `left/top` values are a
  360-reference proposal; the launcher must derive gutter `width*0.02` and margin `2*gutter`
  (`design/components/start-grid.md`).

And the standing rule: a token-accurate render is a **proposal**, not a PASS on the phone.

---

## 4. What is already right

- **Geometry is exact, and it is the part that usually drifts.** Start 14 / 7 / 106 / 219 / 49.5,
  rows 42 / 155 / 268 / 381, right edge 346 — measured identical to `ztos-start.png` and to the tokens;
  AC 4 / 86 / 3 / 57 / top 50, links at 182, notifications at 212, handle 16 dp — within 1 dp of both
  the tokens and `ztmetro-shade.png` (whose links row lands on the same two pixels); app list 45 / 55
  rows and a 45 dp icon box.
- **No Material.** Zero corner radius, zero elevation/shadow, zero gradient inside a tile, no FAB
  component, no ripple, no Material icon set, no Material bottom-navigation component: the tiles' edges land on whole
  pixels with pure `#000` gutters and no antialias haze [F]. That is the adapter's whole failure-signal
  list cleared.
- **Colours are the tokens, not approximations.** `#0050EF` (the shade capture draws exactly
  (0, 80, 239) [F]), `#1F1F1F` off, `#9E9E9E` caps, 62 % body, `#002878` at the lock's bottom edge.
  Ratios: white on accent 6.19:1, secondary 7.84:1, tertiary 6.25:1 — all pass AA.
- **Type roles track the C constants**: label 13 Regular at x 8, count 22 Light at `w-8`, strip clock
  15 Regular (`cell*1.3 = 14.95`), battery 22 dp wide (`cell*1.95`).
- **The AC notification anatomy** reproduces `ztmetro.c:6356-6412`: 28 dp accent square, 22 Semilight
  name hanging at x 52, 14.5 Semibold title, 13.5 body at .62, right-aligned time — every size right,
  only the horizontal indent wrong (§2 #8). The links row lands on the reference's own two pixels.
- **The lock composition** gets the hard part right: 92 Light anchored near `height-268`, both 30
  Semilight date lines within 2 dp of `-156 / -120`, accent × 0.5 endpoint exact.
- **The review artefact itself**: 4-up contact sheet at 2× with a caption per surface naming what to
  check is the right shape for this review, and `render.py` regenerates it in one command.

---

## 5. Gate before acceptance

Do not accept the sheet as a design until, at minimum, (1) the app list has its accent icon column and
no clipped rows, (2) Start tiles have a centred real-icon interior and no invented temperature, (3) the
shade has its status row and eight visible cells, (4) the lock has one clock. Then re-render and
re-measure with the same extraction — every number in §1 is reproducible from the PNG plus the file
citations above.
