# 17 — Windows Phone design reference, as numbers

Measured reference for checking the Android port (`docs/16-ux-gap-analysis.md`) without taste
arguments. Everything here is either a pixel measurement on a genuine WP frame, a line in
`upstream/ztOS/ztmetro`, or a quotable sentence in a Microsoft/community page. Machine-readable
copy: [`design/wp-metrics.json`](../design/wp-metrics.json). Raw runs: `evidence/wp-reference/measurements.json`.

## 0. How to read the numbers

**Primary unit is %W — the value as a percentage of screen width.** WP phones are always 480 dp wide
portrait (WVGA 1×, 720p 1.5×, WXGA 1.6×), our port is 360 dp wide, so the same physical proportion
is a different dp number on each. A port can only be checked proportionally; %W is the invariant.

Conversions used, and their justification:

| frame set | pixels → dp | why |
|---|---|---|
| `ztos-*.png` (1440×2960) | **dp = px / 4** | 360×740 dp viewport: medium tile 424 px ↔ token 106 dp, status+pad 168 px ↔ 28+14 dp, both exactly ÷4 |
| `ztmetro-*.png` (720×…) | **dp = px / 2** | status strip 56 px ↔ `STATUS_H` 28 (`ztmetro.c:128`) |
| WP frames, 480 dp wide | **dp = px × 480 / frame_width** | WVGA/WP8 are 480 dp wide by platform spec |
| app-bar frame | validated, not assumed | measured bar 36 px at 240 px wide → 72 dp, the MSDN value (G1) |

Confidence: **[F]** = pixel-measured on a frame I read, or a literal constant/line in a file.
**[E]** = derived (scale assumption, glyph-height→font-size, antialias-corrected, or a claim about
WP that no readable frame proves).

Sources, short IDs used below:

| ID | what | read? |
|---|---|---|
| F1 | `build/wp-ux-refs-2026-11-15/img/wp81-start.png` 250×444, WP8.1 Start | yes |
| F2 | `…/img/wiki-Internet-Explorer-Mobile-7-png.png` 240×400, WP7 IE + app bar | yes |
| F3 | `…/img/pdf-p13-settings-maps.png` 209×350 = `repo/evidence/ux-compare/refs/wp7-settings-header.png`, WP7 «SETTINGS / maps» | yes |
| F4 | `…/raw/pdfimg/p27_0_613x395.png` 613×395, WP7 «SETTINGS / date+time» **with the guide's own annotations** («ALL CAPS; APPLICATION TITLE», «Title Caps») | yes |
| F5 | `…/img/wiki-Song-list-on-Mix-Radio-app-png.png` 540×960, WP8 app + bottom nav | yes |
| F6 | `…/img/msdn-appbar-default|expanded|mini.png` 325×56/75/28 | yes |
| F7 | `repo/evidence/ux-compare/refs/ztos-{start,app-list,about}.png` 1440×2960 | yes |
| F8 | `repo/evidence/ux-compare/refs/ztmetro-shade.png` 720×1480, `ztmetro-status.png` 720×56 | yes |
| C1 | `upstream/ztOS/ztmetro/ztmetro.c` | yes |
| C2 | `upstream/ztOS/ztmetro/core/zrnmetrocore.h` | yes |
| C3 | `upstream/ztOS/ztmetro/core/zrnmetrocore.c` | yes |
| G1 | MSDN «Application Bar for Windows Phone» https://learn.microsoft.com/en-us/previous-versions/windows/apps/ff431813(v=vs.105) | yes (fetched, quoted) |
| G2 | uisdc «Windows Phone 8 UI 设计指南» https://www.uisdc.com/windows-phone-8-guideline | yes (fetched, quoted) |

**Sources I could not read** (so no numbers from them):

- **WP7 «UI Design and Interaction Guide for Windows Phone 7»** (`download.microsoft.com/…D869941E…pdf`) — HTTP **404** (re-checked today, 1213-byte HTML error page); the Wayback copy is a 1-page stub. No type ramp or tile geometry from it.
- **`img/wp81-notification-center.png`** — 420 bytes of **HTML** (Commons 404), not an image. The 4-column action-centre claim therefore has **no genuine frame behind it** (see §7).
- **WP8.1 lock screen** — no genuine frame in the collection; `ztmetro-lock.png` is a **blank 720×160** strip (545 bytes, uniform black). Lock numbers are C-source only.
- 8 of the 31 files in `img/` are HTML error pages saved with `.png`/`.jpg` names (`wiki-WP8-1-Start-Screen`, `wiki-Telegram`, `wiki-Windows-Phone-7-mockup`, …). Only the files listed above were measured.
- `hw-htc-titan-buttons.jpg` is not an image (no hardware-button photo → no back/start/search geometry).

## 1. Start grid

Frame F1, gutter/margin found by black-run detection on tile rows (`y=130`: gaps at 0–10, 84–87, 123–126, 162–164, 239–249).

| metric | WP px (F1) | %W | dp @480 | our port (F7) | %W @360 | Δ |
|---|---|---|---|---|---|---|
| small unit | 35 | 14.00 | 67.2 | 198 px = 49.5 dp | 13.75 | −1.8 % |
| medium tile | 73 | 29.20 | 140.2 | 424 px = 106 dp | 29.44 | +0.8 % |
| wide tile | 150 | 60.00 | 288.0 | 219 dp | 60.83 | +1.4 % |
| tile gutter | 4 | 1.60 | 7.7 | 28 px = 7 dp | 1.94 | +21 % |
| side margin | 11 | 4.40 | 21.1 | 56 px = 14 dp | 3.89 | −12 % |
| first row top | 22 | — | 42.2 | 168 px = 42 dp | — | 0 |

- Columns: **3 medium / 6 small across** [F1]. Small tile sits inside a medium's cell, four per cell (`ztmetro.c:3283-3305` says the same).
- First row top = status strip 28 + top pad 14 = **42 dp** — the WP frame and `ztmetro.c:140` (`TOP_PAD 14`) + `:128` (`STATUS_H 28`) agree to the pixel [F].
- No rounded corners, no drop shadows, no tile border: flat fill, 1-px-class black gutter [F1, F8].
- Tile label (port, F7): `Phone` ink band 39 px = 9.75 dp for a 13 sp face; left inset 37 px = 9.25 dp; baseline 10 dp above the tile bottom (`ztmetro.c:3678` draws at `x=8, y=h−23`) [F]. WP equivalent is sub-pixel at F1's 250-px scale — [E] same order (≈8–10 dp inset, ≈13 dp type).
- Live tiles (counters «10», «1», date «30», photo/image tiles) are part of the WP look [F1]; our Start tiles are static glyph+label [F7].

## 2. Page header (caps label + title)

F3 is the only full-width WP header frame (status icons reach the right edge at x 165–204, clock at the right edge → not a crop). Glyph extents measured per column.

| metric | WP px (F3, ×2.297) | %W | dp @480 | port (F7 `ztos-about`) | %W @360 | Δ |
|---|---|---|---|---|---|---|
| caps cap-height | 8 (y 23–30) | 3.83 | 18.4 | 33 px = 8.25 dp | 2.29 | **−40 %** |
| caps font (derived) | 11.4 px → | — | ≈26 [E] | 11.5 sp | 3.19 | −41 % |
| title glyph band (lowercase + descender) | 24 (y 47–70) | 11.48 | 55.1 | 146 px = 36.5 dp | 10.14 | −12 % |
| title x-height | 17 (y 47–63) | 8.13 | 39.1 | — | — | — |
| title descender | 7 (p → y 70) | — | — | — | — | — |
| title font (derived x-height/0.5) | 34 px → | 16.27 | ≈78 [E] | 48 sp | 13.33 | **−18 %** |
| caps-top → title-top | 24 | — | 55.1 | 75 px = 18.75 dp | — | — |
| caps-bottom → title-top | 17 | — | 39.0 | 43 px = 10.75 dp | — | — |
| left inset | 10 | 4.78 | 23.0 | 16 dp | 4.44 | −7 % |

- The left inset **is the check that F3 is really 480 dp wide**: 10 px × 2.297 = 23.0 dp ≈ the guide's «24 px margin» rule (G2: «所有的页面都应遵循左边距12px或24px» — every page uses a 12 px or 24 px left margin) [F + G2].
- Independent cross-check of the title size, scale-free: F5's pivot title band is 62/540 = **11.48 %W**, identical to F3's 11.48 %W [F]. So «title ≈16 %W of type» is measured twice; the «78 dp» absolute rests on the 480-dp width assumption [E].
- Ratio check: F4 (the guide's own annotated page) gives title band 31 px / caps 8 px = **3.9**; F3 gives 78/26 = 3.0 — both say «title ≈ 3–4 × caps» [F]. Our port is 48/11.5 = 4.2 — the ratio is right, both terms are small.
- Title case is lower case, caps line is ALL CAPS — confirmed by the guide's own callout «SETTINGS — ALL CAPS; APPLICATION TITLE» / «date+time — lower case; menu» [F4].
- Header height: WP content starts at 103 px (F3) = 236 dp; ztmetro returns **108 dp** from `zmc_header` (`zrnmetrocore.c:529`), ZMC_HEADER_H 104 (`zrnmetrocore.h:39`); our port's first entry glyph top is 139 dp [F].

## 3. App list

| metric | WP (Lumia capture, C1:160-167) | %W | port (F7 `ztos-app-list`) | %W | Δ |
|---|---|---|---|---|---|
| icon square | 45 pt | 12.50 | 180 px = 45 dp | 12.50 | 0 |
| row pitch | 55 pt | 15.28 | 220 px = 55 dp | 15.28 | 0 |
| text column left | 80 pt | 22.22 | 320 px = 80 dp | 22.22 | 0 |
| first icon below status | 17 pt | 4.72 | 96 px = 24 dp | 6.67 | +7 dp / +41 %W |

- Source line: «The app list, as measured on the Lumia (0:175 of the capture, 506 px of screen to our 360 points): the column starts 80 points in, each square is 45, one row to the next 55, the first square's top 17 below the status bar» — `ztmetro.c:160-167`, constants `LIST_ICON 45`, `LIST_ROW_H 55`, `LIST_LEFT 80`, `LIST_TOP 17` (`:162-165`) [F for the C values; the «Lumia» attribution is the comment's claim, [E]].
- Letter head = alpha-silhouette outline in the accent, letter inside: `ztmetro.c:4515-4517` (1.5 px stroke, letter 27 Semilight) [F].
- Our frames reproduce all three ratios exactly — the app list is the part of the port that is already WP-proportional [F].

## 4. App bar (the 72 px spec)

| metric | WP px (F2) | %W | dp | source |
|---|---|---|---|---|
| bar height | 36 | 15.00 | **72** | G1 verbatim: «The App Bar height in portrait mode and width in landscape mode is fixed at 72 pixels and can't be modified.» + F2 measures 36 px at 240 px width = ×2 [F] |
| icon size | 24 | 10.00 | 48 | F2 [F] |
| icon top inset | 6 | 2.50 | 12 | F2 (bar 364–399, icons 370–393: 6 px above, 6 below) [F] |
| icon pitch | 42 | 17.50 | 84 | F2 (icons at x 66, 108, 150) [F] |
| icon group | centred | — | — | centre of 66–173 = 119.5 of 240 [F] |
| ellipsis | 13 wide, 3 dots | 5.42 | 26 | F2 (dots 215–217, 220–222, 225–227; dot 3 px, pitch 5 px) [F] |
| ellipsis right inset | 13 | 5.42 | 26 | F2 (right edge 240) [F] |
| expanded bar (labels shown) | 75 crop | — | ≈106 [E] | F6: icons 32 px at y 12–43, labels at y 51–59 in a 75 px crop; 32 px ↔ 48 dp ⇒ scale 0.667 ⇒ bar grows by ≈34 dp |
| mini bar (ellipsis only) | 28 tall | — | ≈42 [E] | F6 mini image 325×28, ellipsis centred at y 11–14 |

- Composition rule (G1, verbatim): «The default Application Bar is a row of icon buttons and an ellipsis along the bottom of the phone's screen. The user can click the ellipsis to display the labels for the icon buttons and menu items if they are enabled.» In landscape it moves to the side, vertically (G1) [F].
- Flat: no gradient, no shadow, icons and text on the theme background; ellipsis is the only permanent element [F2, F6].
- **Our port has no app bar.** It runs a 40 dp bottom nav bar and puts actions in a top bar (`build/wp-ux-refs-2026-11-15/REFERENCES.md:39-45`). WP parity for an action row = **72 dp at 480 dp = 15 %W = 54 dp at our 360 dp** [E from 72×360/480].

## 5. Status strip, nav bar, bottom chrome

| metric | WP (F5, ×0.889) | %W | dp | port | %W | Δ |
|---|---|---|---|---|---|---|
| status strip | 40 px | 7.41 | 35.6 | 28 dp (`ztmetro.c:128`) | 7.78 | +5 % %W, −8 dp |
| app-title bar under it | 70 px (y 40–109) | 12.96 | 62.2 | — (no such bar) | — | — |
| content top | 110 px | — | 97.8 | 42 dp on Start / 28 dp on pages | — | — |
| bottom nav bar | 60 px (y 900–960) | 11.11 | 53.3 | 40 dp (F7 icons 2852–2907, bar 160 px) | 11.11 | 0 |
| nav icons | 21 px tall | 3.89 | 18.7 | 56 px = 14 dp (app list) / 68 px = 17 dp (Start) | 3.89–4.72 | 0 … +21 % |

- WP8 bottom bar = back / Windows / search, flat, black, icons only [F5]. Ours keeps the same 11.1 %W band [F7].
- Android's own status bar is 24 dp; the port's `STATUS_H 28` is ztmetro's, not Android's — the 40 dp WP value is *not* matched in dp, only in %W [E].

## 6. Type ramp

Sizes are the C source of truth (sp at 360 dp, = dp at density 1×; `design/tokens/ztmetro.tokens.json` compiles them). %W = size/360.

| role | family / weight | size | %W | origin | WP check |
|---|---|---|---|---|---|
| lock clock | Light 300 | 92 | 25.6 | `ztmetro.c:5942` | no frame [E] |
| lock date | Semilight 350 | 30 | 8.3 | `ztmetro.c:5944,5946` | no frame [E] |
| splash wordmark | Light 300 | 56 | 15.6 | `ztmetro.c:6720` | — |
| page title | Semilight 350 | 48 | 13.3 | `zrnmetrocore.c:510` | WP ≈16.3 %W → **port 18 % small** [E] |
| caps / app name | Semibold 600 | 11.5 | 3.19 | `zrnmetrocore.c:509` | WP ≈5.4 %W → **port 41 % small** [E] |
| hub entry title | Semilight 350 | 25.5 | 7.08 | `zrnmetrocore.c:548` | F4 rows ⇒ ≈31 dp @480 = 6.5 %W ✓ |
| hub entry pitch | — | 59 | 16.4 | `zrnmetrocore.c:552` | F4 pitch 47 px ≈ 82 dp @480 = 17.1 %W ✓ |
| hub entry detail | Regular 400, 60 % white | 12 | 3.33 | `zrnmetrocore.c:549-550` | F4 detail line present [F] |
| app-list name | Semilight 350 | 22 | 6.11 | `ztmetro.c:4525` | — |
| app-list letter | Semilight 350 | 27 | 7.50 | `ztmetro.c:4517` | — |
| tile label | Regular 400 | 13 | 3.61 | `ztmetro.c:3678` | — |
| switch / dialog title | Semilight 350 | 20–21 | 5.6–5.8 | `zrnmetrocore.c:3459,4433` | — |
| quick-action label | Semibold 600 caps | 7.6 | 2.11 | `ztmetro.c:6350` | — |
| CLEAR ALL | caps | 10 | 2.78 | C1 quick-action links | — |
| body of a notification | Regular, 62 % white | 13 | 3.61 | `ztmetro.c:6393-6400` | — |

Use rules, stated as facts from the code: **Light** = the one huge number on a screen (clock, splash);
**Semilight** = every page/pivot/hub title and list name; **Regular** = everything the user reads as
content (tile labels, captions, notification bodies); **Semibold** = the ALL-CAPS application name,
section headings and quick-action labels. Font stacks: `zrnmetrocore.h:45-48`.

## 7. Colour rules

- Accent is one user-chosen colour, default **cobalt `#0050EF`** (`ztmetro.c:2983`); the 20-name palette at `ztmetro.c:2415-2422` is the WP8 accent set verbatim (lime `#A4C400`, green `#60A917`, emerald `#008A00`, teal `#00ABA9`, cyan `#1BA1E2`, cobalt `#0050EF`, indigo `#6A00FF`, violet `#AA00FF`, pink `#F472D0`, magenta `#D80073`, crimson `#A20025`, red `#E51400`, orange `#FA6800`, amber `#F0A30A`, yellow `#E3C800`, brown `#825A2C`, olive `#6D8764`, steel `#647687`, mauve `#76608A`, taupe `#87794E`) [F].
- Accent is used for: solid fills (tiles, quick actions ON, app-square, letter outline), links, toggles ON, the action-centre handle. Never for body text [F, C1/C3].
- Everything else is neutral: black screen, white text, greys by alpha — 0.60 detail (`zrnmetrocore.c:549`), 0.38 next-pivot title (`:513`), 0.62 notification body (`ztmetro.c:6395`), 0.30 disabled (`:6321`), 0.122 off surface (`:6316`), 0.07 empty (`:6299`) [F].
- WP8.1 Start is **not** monochrome-accent: brand fills appear beside the accent. Sampled fills in F1: `#EB3B00` (Office, 3971 px), `#00AFF0` (cyan), `#2952A2`, `#5034AB`, `#2A5B83` [F; F1 is a re-encoded capture, ±8 per channel].
- **Port deltas measured on F7 (`ztos-start.png`), all flat fills, beyond JPEG error:**
  - tile fill `#0049CB` vs token `#0050EF` → ΔG −7, ΔB −36 [F]
  - on-tile glyph/label ink `#FFE8D9` (1088 px of exactly that value) vs `#FFFFFF` → ΔG −23, ΔB −38 [F]
  - steel tile `#646B73` vs `#647687` → ΔG −11, ΔB −20 [F]
  - ⇒ something between the tokens and the panel remaps accent and foreground (theme overlay or a colour pass). Verify before any colour work: the WP rule is flat accent + pure white.

## 8. Motion

| motion | duration | source | conf |
|---|---|---|---|
| tilt in (press) | 90 ms | `zrnmetrocore.h:35` | [F] |
| tilt out (release) | 200 ms | `zrnmetrocore.h:36` | [F] |
| tilt geometry | 14°, lift 40, sink 4.5 % of eye distance | `zrnmetrocore.h:32-34` | [F] |
| press delay before tilt starts | 80 ms | `ztmetro.c:147` | [F] |
| long press | 450 ms | `ztmetro.c:145` | [F] |
| turnstile out, one row | **128 ms** | `zrnmetrocore.c:50` — comment names the source: «UIXMobile TurnstileForwardOut: 0.128 s» | [F] |
| turnstile in, one row | 180 ms | `zrnmetrocore.c:51` («the Lumia's Settings: ~150») | [F]; Lumia value [E] |
| row stagger | 33 ms | `zrnmetrocore.c:52` | [F] |
| black gap between pages | 120 ms | `zrnmetrocore.c:60` | [F] |
| tile arrival (Start) | 186 ms, in from 69.8°, 18.2 % behind the plane | `ztmetro.c:231-233` | [F] |
| tile row stagger / left-after-right | 50 ms / 51 ms | `ztmetro.c:234,236` | [F] |
| tapped tile exit | wait 80 ms + 55 ms life; others 85 ms at 45° | `ztmetro.c:227-230` | [F] |
| page change (Start ↔ list) | 300 ms | `ztmetro.c:PAGE_MS` | [F] |
| action centre | 260 ms | `ztmetro.c:139` | [F] |
| switch | 260 ms | `ztmetro.c:SHADE_MS` neighbours (`SWITCH_MS`) | [F] |

WP8.1's own transition set, read out of a Lumia 530's compiled Iris markup (`zrnmetrocore.c:3747-3776`,
values are the firmware's): `turnstile-in` 350 ms (−80°→0, log), `turnstile-out` 128 ms (0→50°, exp),
`swipe`/`slide-up-fade-in` 240 ms, `fade-in` 350 ms, `fade-out` 150 ms, `continuum-show` 350 ms,
`rotate-90` 167 ms, `tile-flip-notify` 520 ms (overshoot) [F for the table; «read out of firmware» is
the code's provenance claim, [E]].

Rules that hold in every frame: nothing eases in and out of a *colour*; motion is plane motion (turn,
swivel, tilt); the page leaves with rows staggered, and the arriving page comes in row by row, never as
one slab [F, C3].

## 9. Action centre (notification centre)

**No genuine WP frame could be read** (§0: the Commons file is HTML). Everything numeric below is the
reference implementation, `ztmetro-shade.png` (F8, dp = px/2), which is the surface our port copies:

| metric | value | %W | origin | WP parity |
|---|---|---|---|---|
| columns | 4 | — | `design/components/action-centre.md` | [E] |
| tile width | 172 px = 86 dp | 23.9 | F8 gaps at 0–7, 180–185, 357–362, 535–540, 712–719 | [E] |
| gap | 6 px = 3 dp | 0.83 | F8 | [E] |
| side margin | 8 px = 4 dp | 1.11 | F8 | [E] |
| row height | 116 px pitch = 58 dp | 16.1 | F8 (row 1 104–217, row 2 220–333) | [E] |
| grid top | 104 px = 52 dp | — | F8 (black→`#1F1F1F` at y=104) | [E] |
| label | 7.6 sp caps, bottom-left | 2.11 | `ztmetro.c:6350` | [E] |
| links row | `CLEAR ALL` left, `ALL SETTINGS` right | — | F8 (y≈365–380) | [E] |
| notification app square | 28×28 dp, accent | 7.78 | `design/components/action-centre.md` | [E] |
| push-up handle | 16 dp accent bar, 28×3 white grip | 4.44 | same | [E] |
| open/close | 260 ms | — | `ztmetro.c:139` | [F] |

Treat the whole section as provisional: the port already matches F8 by construction, so this only
checks port-vs-ztmetro, not port-vs-WP.

## 10. Lock screen

No readable WP frame and the reference frame is blank (§0). C only:

| metric | value | %W | origin |
|---|---|---|---|
| clock | Light 92, 16 dp inset, 268 dp above the screen bottom | 25.6 | `ztmetro.c:5942` |
| weekday / date | Semilight 30, 20 dp inset, 156 / 120 dp above the bottom | 8.3 | `ztmetro.c:5944-5946` |
| clock layer height | `LOCK_CLOCK_H` | — | `ztmetro.c:5948` |

WP lock-screen composition (time above date, notifications at the bottom, accent-tinted background
fade) is [E] here — no frame, no guidebook read.

## 11. The eight numbers that are off

1. Page-title type: **13.3 %W vs WP 16.3 %W — 18 % small** (§2, §6).
2. Caps app-name: **3.19 %W vs WP ≈5.4 %W — 41 % small**; the caps:title ratio is right, both terms are small (§2).
3. Header gap caps→title: **10.75 dp vs 39 dp @480 (29 dp @360)** — the two header lines are too close (§2).
4. Start side margin: **3.89 %W vs 4.40 %W** (−12 %); gutter **1.94 %W vs 1.60 %W** (+21 %) — margins tight, gutters wide (§1).
5. Accent renders `#0049CB` and on-tile ink `#FFE8D9`, tokens say `#0050EF` / `#FFFFFF` (§7).
6. No 72 dp app bar: bottom chrome is a 40 dp nav bar, actions live in a top bar (§4).
7. Action-centre geometry has no genuine WP frame behind it — 4-column grid and 57–58 dp rows are ztmetro's, not verified WP (§9).
8. Status strip 28 dp vs WP ≈36 dp (in %W both ≈7.5 %, so only a dp-level mismatch) (§5).

Already matching within noise, so don't touch: tile size 29.4 %W, tile top 42 dp, app-list 45/55/80,
bottom bar 11.1 %W, square corners, tilt 90/200 ms, turnstile 128/180/33 ms.

## 12. How to verify the port

Capture on device (720×1480, density 2 → **2 px = 1 dp**), no adb script needed beyond `screencap`:
`start` (with a small, a medium and a wide tile on screen), `app list`, `about` (a hub page),
`action centre` (pulled down), `lock`, and one page with the bottom row.

Measure, with the tolerance that matters:

| what | how | expected at 720 px | tolerance |
|---|---|---|---|
| medium tile edge | first black gutter left of the first tile | 212 px | ±2 px |
| tile gutter | black run between two tiles | 14 px | ±1 px |
| side margin | x of the first tile's left edge | 28 px | ±2 px |
| first row top | y of the first tile's top edge | 84 px | ±2 px |
| caps cap-height | ink rows of the caps line, page header | 16 px | ±2 px |
| title glyph band | ink rows of the lowercase title | 73 px | ±4 px |
| left inset | x of the caps line's first ink pixel | 32 px | ±2 px |
| caps→title glyph gap | title top − caps bottom | 21.5 px (10.75 dp) — **should become ~58 px if fixed to WP** | ±2 px |
| app-list icon | side of the accent square | 90 px | ±2 px |
| app-list pitch | y of consecutive icon tops | 110 px | ±2 px |
| status strip | bottom of the status area | 56 px | ±2 px |
| bottom bar | top edge of the nav bar | 80 px | ±2 px |
| AC tile width / gap / side | black runs across a quick-action row | 171.5 / 6 / 8 px | ±2 px |
| colours | one pixel in the middle of a flat accent tile, one on a glyph | `#0050EF` / `#FFFFFF` | exact |

`evidence/wp-reference/measure.py` does the gutter/ink-row extraction used for every number above;
run it on a new capture and it prints the same table. At 1440 px captures (density 4) double every
expected value. A frame passes when all edges are inside tolerance and both colour probes are exact;
glyph bands are the only ±4 px items, because antialiasing moves them.

## 13. What is still unverified

- Absolute dp of WP type (26 / 78 dp) — rests on the 480-dp width assumption, ±15 %; the %W is solid.
- 4-column action centre, lock-screen composition, hardware buttons: no readable genuine frame.
- WP7's own UI Guide PDF is unreachable (404); every WP7 number here is frame-measured, not quoted.
