# 11 — ztmetro reference: shell concepts → Android counterparts

This document maps the original `ztOS/ztmetro` C implementation to Android
owners, with a `file:line` citation for every mapping. It is the narrative
companion to [`../design/tokens/ztmetro.tokens.json`](../design/tokens/ztmetro.tokens.json):
the JSON holds the numbers, this file explains where they come from and what
they mean. Paths are relative to `upstream/ztOS/ztmetro/` in the private tree.

Read [`07-ztmetro-mapping.md`](07-ztmetro-mapping.md) first for the ownership
argument (compositor vs. launcher + SystemUI); this file is the concrete,
per-widget version.

Legend: `[F]` fact about the C code, `[E]` Android engineering estimate /
adaptation.

## 1. Start grid and tile sizes

**ztmetro.** Start is a first-fit packing of small/medium/wide tiles into a grid
of `units` small cells, where `units` defaults to 6 (three medium tiles across)
and can be set to 4/6/8 (`ztmetro.c:141`, `:2996`, `:3080`). The geometry is
computed from the output width, not hard-coded (`ztmetro.c:3266-3272`):

```
gutter = round(width * 0.02)                         # 7 dp at 360 dp
margin = 2 * gutter                                  # 14 dp
unit   = (width - 2*margin - (units-1)*gutter) / units   # 49.5 dp at 360/6
```

Tiles sit on square cells at `y = TOP_PAD + row * (unit + gutter)` with
`TOP_PAD = 14` (`ztmetro.c:140`, `:3299-3301`). Sizes: small = 1×1, medium = 2×2,
wide = 4×2 (`ztmetro.c:3278-3280`); medium and wide sit on even cells
(`ztmetro.c:3304-3305`). The label is 13 pt regular, 8 dp in from the left and
23 dp up from the bottom, icon centred at 44 % height (`ztmetro.c:3675-3678`).
Tiles have **no corner radius**: they are plain `cairo_rectangle` fills
(`ztmetro.c:3657-3697`). App-list names/letters are a separate 45 dp grid
(`ztmetro.c:162-165`).

**Android.** The Start screen is an ordinary launcher `RecyclerView`
(`GridLayoutManager` span 6) or a custom `CellLayout`. Map:

| ztmetro | Android counterpart | citation |
|---|---|---|
| `units = 6`, small cell | grid span count 6; medium tile = span 2 × row span 2, wide = 4×2 | `ztmetro.c:3278-3280` |
| `unit` 49.5 dp, gutter 7 dp, margin 14 dp | `metro_tile_size` / `metro_tile_gap` / `metro_start_margin` dimens + item decoration | `ztmetro.c:3266-3272` |
| `TOP_PAD` 14 dp | first row top padding inside the content inset | `ztmetro.c:140` |
| first-fit packing | keep the C packing rule; Android's grid does not reflow mixed spans by itself | `ztmetro.c:3266-3336` |
| square tiles, no radius | `corner_radius_dp = 0`; do not add Material rounding | `ztmetro.c:3657-3697` |
| 13 pt label bottom-left | 13 sp label, 8 dp inset, no text shadow | `ztmetro.c:3678` |

Do not copy the literal 49.5 dp to other widths: reuse the C formula so a 411 dp
phone gets the same three-medium-columns look.

## 2. Two-line header (`zmc_header`) and pivot next (`zmc_pivot_next`)

**ztmetro.** Every page of a `zmc_*` app starts with the same fixed header
(`core/zrnmetrocore.c:471-541`):

- line 1: the app's name, ALL CAPS, 11.5 pt Semibold, at `y = 14`
  (`core/zrnmetrocore.c:509`);
- line 2: the page title, 48 pt Semilight, at `y = 24`, x = `MARGIN - 3`
  (`core/zrnmetrocore.c:510`);
- content starts at `y = 108` points (108 dp at the reference); the header band
  is 104 points and does not scroll
  (`core/zrnmetrocore.h:39`, `core/zrnmetrocore.c:532`);
- `zmc_header()` is `zmc_pivot_next(title, NULL)` (`core/zrnmetrocore.c:539-541`).

With a next tab, `zmc_pivot_next` draws the next title in grey 0.38 at
`title_width + 16` to the right, plus the title after it (the loop-around) so
short titles do not pop in mid-swipe (`core/zrnmetrocore.c:512-521`). The next
tab's tap band is from the end of the current title to the screen edge, 90 dp
tall, and returns `ZMC_ACT_PIVOT` plus the target page
(`core/zrnmetrocore.c:523-531`); the whole 90 dp band is otherwise
`ZMC_ACT_BACK` (`core/zrnmetrocore.c:531`). Switching pages slides the two
titles/tabs sideways (`core/zrnmetrocore.h:174-192`).

**Android.** There is no system-owned page header; each app/launcher screen
draws it. Map:

| ztmetro | Android counterpart | citation |
|---|---|---|
| caps line 11.5 sp + 48 sp title | `TextAppearance.Metro.Caps` + `TextAppearance.Metro.PageTitle` (fixed `104 dp` header) | `core/zrnmetrocore.c:509-510` |
| content starts at 108 dp | header height 104 dp + 4 dp breathing room; list scrolls under a fixed header | `core/zrnmetrocore.c:532`, `core/zrnmetrocore.h:39` |
| next title grey 0.38, 16 dp gap | second tab label inline in a horizontal `TextView` row / `TabLayout` with 16 dp item gap | `core/zrnmetrocore.c:512-514` |
| next-title tap band → slide | `ViewPager2` + click on the right-hand title region; 90 dp tall hit area | `core/zrnmetrocore.c:523-533` |
| title tap = back | `OnClickListener` on the title / `OnBackPressedDispatcher` | `core/zrnmetrocore.c:531` |
| header stays, rows turn | fixed header + `RecyclerView`; page transition staggers rows, not the header | `core/zrnmetrocore.c:36,52` |

`pivot_bar_height_dp = 90` in the tokens is this tap band, not the 104 dp header.

## 3. Status strip

**ztmetro.** A 28 dp strip (`ztmetro.c:128`) drawn white on whatever is behind
it; icons start at x = 14, the right cluster ends at `width - 14`
(`ztmetro.c:5415-5420`). Sizes are relative to the screen: battery cell height
`round(width*0.032*2)/2` = 11.5 dp at 360 dp, clock digits `1.3 × cell` ≈ 15 pt
(`ztmetro.c:5416-5418`, `:5477-5489`). Low battery fill is `#E51400` at 15 % or below
(`ztmetro.c:5530-5533`). Holding the strip opens a taller peek with a second
line (charge percentage, date) at `STATUS_H + 22` (`ztmetro.c:149-151`), and the
action centre repeats the same row with `details = true`
(`ztmetro.c:5409-5413`, `:6503`).

**Android.** The status bar belongs to SystemUI; a launcher cannot draw it. The
only honest counterparts are window insets (`WindowInsetsCompat.getInsets(statusBars())`)
and, for a full shell replacement, a SystemUI module. Do **not** paint a fake
28 dp strip over the real one; the C strip paints no background at all (it draws
white glyphs over whatever is behind), so the token records the black canvas it
sits on rather than a surface colour.

## 4. Action Centre

**ztmetro.** Pulled down from the status strip, covers everything above the nav
bar (`SHADE_MAX_H`, `ztmetro.c:137`), settles in 260 ms (`ztmetro.c:138`). Top to
bottom (`ztmetro.c:6230-6245`): status row with details, quick actions, a
CLEAR ALL / ALL SETTINGS link row, the notification list, and an accent handle
at the bottom.

| part | C values | citation |
|---|---|---|
| quick grid | 4 across, top 50 dp, side 4 dp, gap 3 dp | `ztmetro.c:6239-6241` |
| quick tile | width `(width-2*4-3*3)/4` = 85.75 dp, height `round(w*0.66)` = 57 dp | `ztmetro.c:6249-6251` |
| on / off / empty | accent / `#1F1F1F` / `#121212`; missing hardware = white at 30 % | `ztmetro.c:6297-6321` |
| quick label | 7.6 pt Semibold, bottom-left (do not port this size) | `ztmetro.c:6350` |
| links | `CLEAR ALL` / `ALL SETTINGS`, 10 pt Semibold | `ztmetro.c:6514-6519` |
| notification list | starts at links + 30; app square 28×28 accent; title 14.5 Semibold; time/body 13.5, body grey 0.62 | `ztmetro.c:6407-6412`, `:6360-6401` |
| handle | 16 dp accent bar at the bottom | `ztmetro.c:6242`, `:6546-6552` |
| menu overlay | black 72 % scrim, white panel, 50 dp rows, black 24 pt text | `core/zrnmetrocore.c:4400-4418` |

**Android.** The shade is SystemUI, not the launcher. Options, in order of
honesty: (a) use the platform shade and map quick actions to Settings intents /
foreground-service toggles; (b) a privileged `NotificationListenerService` plus a
custom in-app panel (no shade replacement, no scrim over other apps); (c) a
SystemUI replacement module — the only path that reproduces the 28 dp strip, the
peek and a shade that covers apps. `action_centre_row_height_dp = 57` and the
85.75 dp tile width are the numbers for (b)/(c).

## 5. Where the accent is applied

Accent is interaction/selection colour, never decoration (WP rule). The C code
uses it at these points — this is the complete list a port should reproduce:

| application point | citation |
|---|---|
| default quick tile colour for Start tiles (`"accent"` in the tile file / built-ins) | `ztmetro.c:2432-2433`, `:3219-3226`, `:9598-9609` |
| app-list icon/letter squares, letter outline | `ztmetro.c:4512-4522`, `:4557-4596` |
| action centre: quick action ON, notification app square, bottom handle | `ztmetro.c:6314`, `:6366`, `:6547` |
| on-screen keyboard: latched key fill and key pop-up | `ztmetro.c:7017`, `:7064-7065`, `:7110-7111` |
| volume banner fill and "change ›" affordance | `ztmetro.c:9917-9919`, `:9970-9971` |
| notification toast banner | `ztmetro.c:9981` |
| settings: section headings, chosen value, accent picker swatches, selected ring | `settings/ztsettings.c:1621-1633`, `:1662-1666`, `:1690-1694`, `:2531` |
| hyper search: result squares, group labels, "best match" | `ztmetro.c:8379-8380`, `:8400-8403`, `:8440-8457` |
| dialog / menu confirmation text | `ztmetro.c:9362-9364` |
| slider fill and switch ON | `core/zrnmetrocore.c:577-578`, `:626-628` |
| lock screen bottom fade (accent × 0.5) | `ztmetro.c:6030-6035` |

`color.accent.light1/light2/dark1/dark2` in the tokens exist only because
Android needs pressed/ripple/selected variants; the C code has no light accent
(see README, “Derived vs. adapted”).

## 6. What does **not** transfer

These are shell/compositor properties. Treat them as design intent, not as
something the Android layer can copy:

1. **Ownership of system chrome.** Status strip, nav bar, shade, lock screen,
   splash, on-screen keyboard and the page header drawn *around* foreign apps
   are compositor features (`ztmetro.c` draws all of them). Android distributes
   these to SystemUI, WMShell and each app; only a SystemUI/launcher replacement
   can reproduce the first four, and an app's own header must be drawn by that
   app.
2. **3D motion.** Tile tilt, swivel, row turnstile with perspective, Start's
   parallax, the 0.6-scale cards (`TILT_*`, `TURN_*`, `TILE_*_DEG`,
   `ztmetro.c:207-241`, `core/zrnmetrocore.c:64-67`). Android can approximate
   with `rotationY` + `cameraDistance` and `OvershootInterpolator`, but not the
   per-tile GPU layer/occlusion; `motion` in the tokens keeps the timings, not
   the geometry.
3. **Typography.** Segoe WP/Semilight/Semibold are not shipped; the tokens keep
   the names as fallback strings and the generator maps them to
   `sans-serif-light` / `sans-serif-medium` `[E]`. Sizes are preserved.
4. **Sub-48 dp targets.** 7.6 pt quick labels and 10 pt links (`ztmetro.c:6350`,
   `:6517`), 31 dp round button (`ztmetro.c:166`, `:4694`), 50 dp menu rows
   (`core/zrnmetrocore.c:4401`). The C code is a
   fingertip UI on a 360 dp screen but is below Android's accessibility floor in
   places; `metrics.min_touch_target_dp = 48` `[E]` is the adaptation.
5. **Edge gestures.** `EDGE 18`, `SWIPE 56`, `SLOP 8` (`ztmetro.c:142-144`)
   collide with Android's system gesture insets; a launcher must not start a
   gesture in the excluded edge zones.
6. **Nav bar 40 / 0.** `NAV_BAR_H 40` with a config switch (`ztmetro.c:130`,
   `:3032-3037`) is a ztmetro setting; Android owns navigation mode.
7. **Per-tile palette.** Tiles take any of 20 named colours or `"accent"`
   (`ztmetro.c:2415-2434`), and the wallpaper can tint them
   (`ztmetro.c:3664-3667`). Android themed icons are monochrome-tinted; a
   faithful port needs its own tile colour store.
8. **DotMatrix style.** The shell's alternate style swaps the whole widget set
   for dot-matrix rendering (`core/zrnmetrocore.h:125-129`,
   `core/zrnmetrocore.c:275-277`). Out of scope for the Metro tokens; do not try
   to express it as an Android theme.
9. **App-list geometry.** The 80 dp left column and 45 dp rows with 55 dp pitch
   (`ztmetro.c:162-165`) are a WP letterboard, not an Android drawer. Keep the
   type (`app_list_title` 22 sp) and the accent square; expect to reflow.
10. **Status-strip details.** The second line/peek (`ztmetro.c:149-151`) and the
    white-on-black row only exist inside ztmetro's own top-level window; on
    Android they belong to the shade, not the bar.

## 7. One-page summary

| concept | tokens | Android owner |
|---|---|---|
| Start grid, tile sizes, gap | `metrics.tile_*`, `start_columns`, `corner_radius_dp` | launcher app |
| two-line page header | `type.header`, `type.ramp.page_title/caption_caps`, `pivot_bar_height_dp` | each app / launcher screen |
| pivot next title | `metrics.header_height_dp`, `type.ramp.page_title` | `ViewPager2`/`TabLayout`-like row |
| status strip | `metrics.status_strip_height_dp`, `color.chrome.status_strip` | SystemUI (insets only if not replacing) |
| action centre | `metrics.action_centre_*`, `color.chrome.quick_action_*`, `color.accent.base` | SystemUI / privileged panel |
| accent | `color.accent.*` | `colorAccent`/`colorPrimary`, dynamic-colour seeds |
| press + page motion | `motion.*` | view property animators / ActivityOptions |
