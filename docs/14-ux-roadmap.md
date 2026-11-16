# 14 — ztMetro UX roadmap: pattern → Android mechanism → risk

Written 2026-11-16 with the phone **offline** (battery). Nothing here was verified on
the device in this pass; every Android-side claim is source-level unless marked `[?]`.
Per-component detail lives in [`../design/components/`](../design/components/) — one
file per component, this document is the ledger and the order of work.

Sources named in the citations:

- design: `upstream/ztOS/ztmetro/{ztmetro.c, core/zrnmetrocore.c, core/zrnmetrocore.h, core/zrnboard.h}`
  (numbers duplicated in [`../design/tokens/ztmetro.tokens.json`](../design/tokens/ztmetro.tokens.json));
- Android: `lineage-23.2` — `frameworks_base` `797e4324…`, SystemUI tree
  `c8a86627…`, Settings `e1b8cd2d…` (same snapshot as [`10b`](10b-overlay-and-hook-facts.md)).

Labels: `[F]` read in source, `[E]` estimate, `[?]` needs the phone.
Effort: **S** ≤ 3 days, **M** ≤ 2 weeks, **L** 1–2 months, **XL** multi-month.

---

## 0. The four mechanisms, and what they can never do

| class | what it is | can | can **not** |
|---|---|---|---|
| **A** | our APK (HOME launcher, own activities, `TileService`, `NotificationListenerService`, IME) | anything inside our own window; notification *content* with user-granted access; publishing QS tiles | draw over another app's window with system chrome semantics; own status bar/shade/keyguard/nav; supply task transitions |
| **B** | preinstalled RRO in `/product/overlay` (resources only; `10b` §3-4) | replace any **existing** resource of the target — dimens, colours, styles, drawables, and layouts that reference only target resources | add a view, add a resource, or run code (a new `@drawable`/`@style`/custom view in an overlaid layout = `NotFoundException` at inflate, `10b` §3a) |
| **C** | LSPosed/Zygisk hook (Magisk + Zygisk + LSPosed ≥1.10.2) | call a method inside the target process and inflate **our own** views/resources there (the only way to get a new component into a system app) | survive refactors; fail safely — a throw in SystemUI takes the bars, shade and keyguard with it |
| **D** | own ROM build (own keys, SystemUI/framework fork, WMShell, platform-signed apps) | new windows, new layout order, new task transitions, keyguard replacement, per-app page chrome | ship this week; survive a LineageOS rebase without work |

The decision rule this roadmap applies: **a pattern that needs a view inside another
app's window is C or D, never B.** B only restyles what is already there.

---

## 1. Pattern inventory — what ztmetro actually does

### P1 · Two-line page header + pivot tabs
Every page: the app's name in caps, then the page title at 48, a grey next-tab title,
and a 104/108 dp fixed band. Quoted (`core/zrnmetrocore.c:509-532`):

```c
zmc_text(cr, zmc.app->caps, FONT_SEMIBOLD, 11.5, MARGIN, 14, 0);
zmc_text(cr, title, FONT_SEMILIGHT, 48, MARGIN - 3, 24, 0);
...
zmc_text(cr, next, FONT_SEMILIGHT, 48, MARGIN - 3 + w + 16, 24, 0);   /* 0.38 grey */
...
zmc_add_hit(MARGIN + w + 8, 0, zmc.width - (MARGIN + w + 8), 90, ZMC_ACT_PIVOT, arg);
zmc_add_hit(0, 0, zmc.width, 90, ZMC_ACT_BACK, 0);
return 108;
```
- the tab after the next one is drawn too (wrap-around) so short titles do not pop in
  mid-swipe (`core/zrnmetrocore.c:515-521`); header band is fixed at 104
  (`core/zrnmetrocore.h:39`) and content starts at 108;
- title tap = back, right of the title = next tab (`:523-531`); `zmc_header` is
  `zmc_pivot_next(title, NULL)` (`:539-541`);
- tab switch: 50 pt of drag (or 0.3 pt/ms) commits, title lights at 60
  (`core/zrnmetrocore.c:1812-1816`); the strip moves a title-width while the pages move
  a screen-width, which is the whole "pivot" feel (`:1806-1811`).

### P2 · Start tile grid
```c
double gutter = round(server->width * 0.02);      /* 7 at 360 */
double margin = 2 * gutter;                        /* 14 */
double unit = (server->width - 2*margin - (units-1)*gutter) / units;   /* 49.5 */
```
`ztmetro.c:3267-3269`, `units = 6` by default (`:2996`, "three medium tiles across"),
small 1×1 / medium 2×2 / wide 4×2 (`:3278-3280`), medium and wide only on even cells
(`:3304-3305`), first row at `TOP_PAD = 14` (`:140`), first-fit packing so small tiles
always stand as blocks (`:3273-3297`). Tiles are **square, no radius** — plain
`cairo_rectangle`/`cairo_paint` fills (`:3654-3697`); icon at 44 % of the height, label
13 regular 8 dp in from the left, 23 dp up from the bottom (`:3675-3678`); a live count
top-right at 22 Light (`:3684-3688`).

### P3 · Tile press (tilt) and long-press edit
`ZMC_TILT_DEG 14.0`, lift 40, sink 0.045 of the eye distance, in 90 ms / out 200 ms
(`core/zrnmetrocore.h:32-36`). The lean is delayed 80 ms so the start of a scroll does
not press the tile under it (`ztmetro.c:146-148`, `PRESS_DELAY_MS 80`); a hold of 450 ms
opens editing (`ztmetro.c:145`), where tiles get 32 dp round badges (`:170`) and drag.

### P4 · App list (letterboard)
```c
#define LIST_ICON 45
#define LIST_ROW_H 55
#define LIST_LEFT 80
#define LIST_TOP 17
```
(`ztmetro.c:162-165`, measured off a Lumia capture, comment `:159-161`). A row is an
accent square with the glyph and the name at 22 Semilight, 13 dp right of it
(`:4512-4525`); a letter head is an **outline** accent square with the letter in its
corner at 27 (`:4514-4518`).

### P5 · Semantic zoom: the letter grid (Swivel)
`ZB_COLS 4`, `ZB_ROWS 7`, `ZB_CELLS 28` (`#a…z` + globe), gap 9, top 9, font 27
(`core/zrnboard.h:26-30`); each cell turns from −45° about its own X axis in 300 ms
(Log-200), a 12 ms domino in reading order, scrim 0.85 over the list, out +60° in 200 ms
Exp-150 (`core/zrnboard.h:36-43`); a tile takes taps 100 ms after it starts (`:43`).

### P6 · Search
A 31 dp round button at x = 51 (`ztmetro.c:166-167`), a 40 dp field at
`STATUS_H + 12` (`:168-169`), the list filtered live under it; the "hyper" search page
is the same header with grouped results (`:8379-8457`).

### P7 · Action Centre (shade)
Pulled from the status strip, covers everything above the nav bar
(`SHADE_MAX_H`, `ztmetro.c:137`), settles in 260 ms (`:138`). Layout
(`:6230-6245`, `:6249-6253`):

```c
#define SHADE_TOP 50
#define SHADE_SIDE 4
#define SHADE_GAP 3
#define SHADE_HANDLE 16
*w = (width - 2*SHADE_SIDE - 3*SHADE_GAP) / 4.0;   /* 85.75 */
*h = round(*w * 0.66);                              /* 57 */
```
Quick action ON = accent, OFF = 0.122 grey, empty slot = 0.07
(`:6297-6316`); missing hardware is drawn white at 30 % and does not pretend to toggle
(`:6318-6321`); the label is 7.6 Semibold bottom-left (`:6350`). Under the grid: `CLEAR
ALL` / `ALL SETTINGS` at 10 (`:6517-6519`). Notifications: 28×28 accent app square,
app name 22 Semilight, title 14.5 Semibold with the time right-aligned 13.5, body 13.5
at 0.62 grey (`:6356-6405`), list starting 30 below the links (`:6407-6412`). An
accent bar 16 dp along the bottom is the handle (`:6546-6553`).

### P8 · Lock screen
Clock 92 Light at `height - 268`, weekday and `%B %-d` at 30 Semilight under it
(`ztmetro.c:5930-5945`); the background fades to `accent × 0.5` at the bottom
(`:6029-6035`); one row of per-app glyphs with counts at `height - 100`
(`LOCK_NOTE_Y`, `:5926`); unlock feedback in green/red (`:10162-10163`).

### P9 · Status strip
28 dp (`:128`), **no background** — white glyphs over whatever is behind
(`:5409-5418`); icons from x = 14 to `width - 14`; cell = `round(width*0.032*2)/2`
(11.5 dp), clock digits ≈1.3× cell, WP battery outline 1.95× cell, warning red at
≤15 % (`:5530-5533`). Holding it opens a peek with a second line at
`STATUS_H + 22` (`:149-151`), 320 ms hold, 3 s stay; the Action Centre repeats the same
row with details (`:5411-5413`).

### P10 · Motion: pivot slide, turnstile, tile in/out
Start leaves by turning 80° about the left screen edge, the app arrives from behind at
80°; `APP_IN 350`, `APP_OUT 250`, `TURN_GAP 110`, `BACK_GAP 150` (`ztmetro.c:215-216`,
`:250-253`). Tiles: out 45° on their own left edge in 85 ms, rows from the bottom up
50 ms apart, left tile 51 ms after its right neighbour, the tapped tile last
(`:227-241`); in from 69.8° at 0.182 eye-depth in 186 ms, 50 ms row stagger, ±6 ms
jitter (`:233-241`). Pivots move title-strip-slow / page-fast on a non-overshooting
spring (`core/zrnmetrocore.c:1812-1816`).

### P11 · Typography
Four faces — Light / Semilight / Regular / Semibold (`core/zrnmetrocore.h:45-48`) —
11.5 caps, 13 body, 17 field, 20 subtitle, 22 app list, 25.5 entry, 48 page title, 56
splash, 92 lock clock (ramp in the tokens file, each entry with its own origin).

### P12 · Where the accent is allowed
Interaction/selection only: quick action ON, app-list squares and letter outlines,
notification app square, shade handle, switch/slider ON, section headings, lock-screen
fade, keyboard latch. Never a decorative surface — the full list with citations is
`11-ztmetro-reference.md` §5.

---

## 2. Mechanism ledger

| pattern | class | exact target (lineage-23.2) | effort | visible gain | failure mode | breaks on the next LineageOS update |
|---|---|---|---|---|---|---|
| **P1 header/pivot — launcher pages** | **A** | our `HomeActivity`: fixed 104 dp header view + `TextView` tab strip + `ViewPager2` for the pages; the 90 dp hit band is a `GestureDetector` region, not a `TabLayout` | S | high — this is the most recognisable Metro cue after the tiles | edges of the title row collide with the system back gesture; 48 sp title clipped at large font scale unless the header is 104 dp **and** `includeFontPadding=false` | nothing (normal APK) |
| **P1 header — Settings** | **C** | `com.android.settings.core.SettingsBaseActivity#setContentView(int)` (Settings `:220`, the single choke point for home + every sub-screen), `#setTitle(CharSequence)` to mirror breadcrumbs, `com.android.settings.homepage.SettingsHomepageActivity#onCreate` (`:252`) for the launcher home | M (built, needs hardening) | high — every Settings screen stops looking Material | duplicate headers on config change (tag-guard), header stealing height from the scrolling fragment if inserted in `settings_main_prefs` instead of `content_frame` index 0 | **any** Settings refactor of those three points → header silently disappears (safe), or attaches to the wrong group (visible) |
| **P1 header — third-party apps** | **D** | framework patch in `PhoneWindow`/`DecorView`, or nothing | XL | low | every app that draws its own toolbar gets a second header, insets double | total |
| **P2 Start grid** | **A** | custom `ViewGroup` (or `RecyclerView` + `GridLayoutManager(6)` with our own first-fit `onLayout`); the C formula, not 49.5 dp hard-coded | M | high — it *is* Start | first-fit packing fights `RecyclerView` reflow → holes; per-tile `rotationX` on all 64 tiles drops frames on Adreno 308 | nothing |
| **P3 tilt / long-press** | **A** | `View.setRotationX/Y` + `cameraDistance` (~eye distance), `PRESS_DELAY_MS` via `postDelayed`, `ItemTouchHelper` for drag, 32 dp edit badges | S–M | medium-high (feel, not looks) | tilt applied to a recycled view during scroll (must cancel on `onScrollStateChanged`); haptics unavailable without `VibratorManager` permission — use `View.performHapticFeedback` | nothing |
| **P4 app list** | **A** | `RecyclerView` rows 55 dp, 45 dp accent square, 22 sp name; the 80 dp left column reflows on a 720p/320 dpi screen | S | high | 45 dp rows are below the 48 dp accessibility floor → bump the row to 56 dp (the C pitch is already 55) | nothing |
| **P5 letter grid (Swivel)** | **A** | 4×7 `TextView` grid, `rotationX` −45→0, 300 ms Log-200 ≈ `DecelerateInterpolator(1.6f)`, `startDelay = i*12`, scrim `View` at 0.85 | S | high — the one WP motion that is cheap on this GPU | 28 simultaneous property animators if done with a single `AnimatorSet`; must be one animator per tile or the stagger collapses | nothing |
| **P6 search** | **A** | `EditText`/`SearchView` at 40 dp under the header, live filter on `LauncherApps` labels; keep the round button at 48 dp **hit** area | S | medium | filtering on the main thread over 300 labels stutters; precompute a lowercase index | nothing |
| **P7 Action Centre — our panel** | **A** | own full-screen `Activity` (or `Dialog` with `windowIsFloating=false`) opened from a Start tile / swipe-up: 4×2 grid of 57 dp cells, links row, notification list from our `NotificationListenerService`, 16 dp accent handle | M | high inside the launcher; **cannot** be pulled down over other apps | it is a task, so "pull from the status strip" is lost and back-stack behaviour differs; `NotificationListenerService` may be killed → empty list | nothing (the platform can tighten listener policy `[?]`) |
| **P7 Action Centre — restyle the real shade** | **B** | `qs_corner_radius` 28→0 dp (`dimens.xml:726`), `qs_tile_background_shape`, `quick_settings_num_columns` 2→4 (`res/values/config.xml:68`, consumer `qs/TileLayout.java:152-153` — `[F]` for the View path, `[?]` whether the 23.2 Compose shade honours it), `TextAppearance.QS.TileLabel` 14→12 sp (`styles.xml:164-169`), `notification_corner_radius` 28→0 (`dimens.xml:348`), `shadeActive`/`shadeInactive` (`attrs.xml:125-131`), `overlay_shade_panel_shape_radius` 46→0 (`shade_dimens.xml:24-36`) | S (mostly done) | medium-high — square, flat, 4-across | setting the scrim colours to opaque black also dims dialogs and the power menu that share them; 4 columns unverified on the Compose shade | a resource rename or removal makes the overlay a silent no-op or an install failure; the 2025 shade was already a redesign once |
| **P7 Action Centre — layout hook** | **C** | `com.android.systemui.shade.NotificationPanelViewController` / `NotificationShadeWindowView` + the Compose QS container (`compose/features/…/qs/ui/composable/QuickSettingsContent.kt`) — private fields, Compose slot table | L | the only way to reorder the shade without a ROM | **a throw in SystemUI = no status bar, no shade, no keyguard**; recovery needs the LSPosed manager, i.e. a working UI | total — the 23.2 shade is Compose, so `R.id`/layout overlays no longer reach the order at all |
| **P7 Action Centre — own shade over apps** | **D** | SystemUI fork: our `ShadeView` as the expanded panel | L | full fidelity | — | rebase per release |
| **P8 lock screen** | **D** (B for tint) | keyguard is Compose/flexiglass in 23.2. The supported clock seam is `com.android.systemui.plugins.keyguard.ui.clocks.ClockProviderPlugin` (`ACTION com.android.systemui.action.PLUGIN_CLOCK_PROVIDER`), **but** loading a plugin needs `com.android.systemui.permission.PLUGIN`, `protectionLevel="signature"` (`packages/SystemUI/AndroidManifest.xml:205-206`) — we cannot sign it, and `ClockFaceLayout` is marked *"Unsupported with flexiglass. Move to composables."* | L | very high (it is the second screen the user sees) | anything shipped here that half-works leaves the phone insecure (keyguard disabled) or locked out | total |
| **P9 status strip — glyphs** | **B** | framework RRO (`android`): `stat_sys_battery*` / signal / wifi vectors → WP outlines (the names exist in the target, so this is a legal replacement), `TextAppearance.StatusBar.Default` (the style the bar's `Clock` TextView uses, `res/layout/status_bar.xml:94-100`); height `status_bar_height` 24 dp (`core/res/res/values/dimens.xml:44-60`) → 28 dp is possible but changes insets everywhere | M | medium — the top of every screen | battery vectors have per-level variants (`stat_sys_battery_0…100`, charge, and the `*_light`/`dark` variants); missing one shows a mix. Height change can clip cutout/inset math `[?]` | resource names are AOSP-stable; the clock style name is not |
| **P9 status strip — peek / 28 dp strip / second line** | **D** | SystemUI `statusbar/phone/` + shade header | L | low-medium | — | rebase |
| **P10 in-launcher motion** (tile in/out, page change, letter grid) | **A** | `ViewPropertyAnimator` with per-row `startDelay`; `PAGE_MS 300` on the pager; `OvershootInterpolator(1.0)` only for tile return | M | high — sells the whole port | 64 concurrent rotations at 720p; must cap to the visible rows and drop the per-tile GPU layer (`setLayerType(NONE)`) | nothing |
| **P10 turnstile Start↔app** | **D** | WMShell transitions (`libs/WindowManager/Shell`, `ActivityTransitionHandler`/`DefaultTransitionHandler`); an app cannot hand Android a two-window turn | L | medium (the black gap and the 80° turn are iconic, but Android's own cross-fade is not ugly) | faking it with a launcher-drawn black frame leaves the wallpaper visible and the app pops in late | rebase |
| **P11 typography** | **B** + Magisk | already: `fonts.xml` rewrite (module) + `TextAppearance`/`config_bodyFontFamily` where overlaid. Android has no Segoe WP; Selawik is the metric-compatible stand-in and is installed | S | high (already banked) | a `fonts.xml` rewrite that misses a new family (emoji, variable fonts) breaks text rendering in apps | **highest update risk we carry**: every LineageOS font change must be re-merged into the module |
| **P12 accent** | **A + B** | launcher: own palette (20 named colours + `accent` alias, `ztmetro.c:2415-2434`); system: framework RRO `colorAccent`/`colorPrimary` + SystemUI `shadeActive` | S | medium | third-party Material-You apps re-tint from the wallpaper and ignore our accent | dynamic-colour resource names move between releases |

### The C hooks, named

Class C appears three times in the ledger and only **once** in Wave 1–2 as a build item
(the Settings header, already built). The other two are gated: 2.4 is an *experiment*
that may promote a resource change into a hook, and the P7 layout hook is listed only to
be rejected in favour of D. Every hook we keep touches a **public or single-purpose**
method and is guarded so the failure mode is "no change", not "crash":

- `SettingsBaseActivity#setContentView(int)` is overridden for one purpose — it is the
  only path every Settings screen takes — and it *returns void*, so a guard that bails
  leaves stock behaviour untouched `[F: SettingsBaseActivity.java:220-238]`.
- The shade has no such method: `NotificationPanelViewController` is a ~5 kLOC God class
  with private fields, and in 23.2 the panel content is Compose. A hook there is not a
  restyle, it is a fork maintained through reflection. That is why it is **not** in
  Wave 1–2 as a build item, and why "Action Centre over apps" is a Wave 3 item.

**P8 is honestly D.** No plugin, no RRO and no hook gives us the 92 sp WP clock with the
accent fade on a 23.2 keyguard: the plugin needs a platform signature, and the View-based
clock layout API is deprecated in favour of Compose. Until we own the ROM the lock screen
is Android's, with at most typography/colour overrides.

---

## 3. The four waves

Each item carries the one test a human can run on the phone. "Pass" means the phone
shows it, not that a build succeeded.

### Wave 1 — finish on the current device this week (A + the B pass already installed)

| # | item | class | effort | acceptance test (on the phone) |
|---|---|---|---|---|
| 1.1 | Start grid: first-fit packing, 6 units, 7 dp gutter / 14 dp margin, square tiles, icon 44 %, label 13 sp at 8/23 | A | S | On Start, three medium tiles fit across with an even gap; no tile has a rounded corner; a small tile sits in a corner of a medium slot, never strung along a row. |
| 1.2 | Pivot header on every launcher page: caps line, 48 sp title, grey next title, right-of-title tap = next, title tap = up | A | S | Tap the grey next title → the page slides and the header re-centres; tap the solid title → you go back one level. |
| 1.3 | Tile press: 80 ms delayed 14° lean, 450 ms hold → edit mode with 32 dp badges, drag to reorder, layout survives reboot | A | S | Press a tile and hold still: it leans toward your finger; slide instead and it does not. Hold ½ s: badges appear and tiles drag. |
| 1.4 | App list: 55/56 dp rows, 45 dp accent square, 22 sp name, letter heads as accent outline, 4×7 Swivel letter grid | A | M | In the app list, open the letters and tap "S": the list jumps to S and the letters flip in over ~0.4 s without a stutter. |
| 1.5 | Action Centre v1 as our own panel (4×2 × 57 dp, links row, notification list, 16 dp accent handle) **+** the SystemUI RRO pass: `qs_corner_radius` 0, 12 sp labels, `notification_corner_radius` 0, `shadeInactive` `#1F1F1F` (`qs_tile_height` 80→72 only as a test, `[?]`) | A + B | M | Open our panel: 8 quick cells, accent when on, grey when off. Then pull the real shade: tiles are square and labels are 12 sp. |

Efforts assume the installed launcher already has a grid, an app list and a pivot — the
Week-1 work is geometry and type, not new architecture; if any of those is missing, add an
M to that item. Do not add anything else to Wave 1: 1.1–1.5 are what makes the phone read
as ztMetro in a screenshot.

### Wave 2 — deeper structural work (weeks; still no ROM)

| # | item | class | effort | acceptance test |
|---|---|---|---|---|
| 2.1 | Live tiles: `NotificationListenerService` counts/badges on the tile face (top-right number, 22 Light) | A | M | Get a message in Telegram → the tile shows "1" within a second; clear it → the number goes. |
| 2.2 | Tile motion: rows out from the bottom up (50 ms), tapped tile last, in from 69.8° in 186 ms, jitter | A | M | Launch an app from Start: the rows leave bottom-first and come back the same way, no frame drop on the 720p panel. |
| 2.3 | WP status glyphs via framework RRO: battery outline + fill, warning red ≤15 %, signal/wifi outlines, clock type | B | M | Battery icon is an outlined rectangle with a nub, turns red at 15 %; no half-themed icon appears at 100/charging. |
| 2.4 | Action Centre over apps — **decision experiment first**: set `quick_settings_num_columns` 4 and measure. If the Compose shade ignores it, stop and write it off until Wave 3 | B → C (gated) | S then L | Pull the shade: four quick tiles per row. If still two, the experiment failed — revert, do not hook. |
| 2.5 | Settings header hardening: sub-screens, back-stack titles, homepage, config changes, 2 GB memory | C | S–M | Drill Settings → Display → Dark theme: one header, correct breadcrumb on each level, none duplicated after rotating the phone. |
| 2.6 | Lock screen — only what B can do: keyguard clock type/colour, no new layout | B | S | Lock the phone: the clock is Selawik Light; accept that layout, fade and glyph row are Android's. |

### Wave 3 — platform level (own ROM build; months)

| # | item | class | acceptance test |
|---|---|---|---|
| 3.1 | SystemUI fork: 28 dp status strip with the WP row and the peek, shade with a 4×2 quick grid + notification rows + 16 dp accent handle, lock screen with the 92 sp clock, accent fade and glyph row, 40 dp nav bar | D | From a cold boot the strip is 28 dp, the shade is black with 4-across quick actions, and the lock screen shows the WP clock over the accent fade. |
| 3.2 | WMShell: tile turnstile Start→app with the 110/150 ms black gap, 80° turn | D | Launch an app: the tiles turn away, the screen is black for ~110 ms, the app swings in. Back: the reverse with the 150 ms gap. |
| 3.3 | Framework: per-app WP page header for apps that opt in (a theme attribute, not a forced patch) | D | A test app with `metroHeader=true` shows caps + title; an app without it is unchanged. |
| 3.4 | Preinstall: launcher, IME, font, overlays in the image, WP-style starting window | D | Factory-reset the phone: it boots into Start with the Selawik font and square QS, with no module installed. |

### Wave 4 — portability (after Wave 3 works once)

| # | item | class | acceptance test |
|---|---|---|---|
| 4.1 | Launcher stays a plain APK: no ROM-only API, feature-detect notification access, tiles degrade to icons without it | A | Install the launcher on a second Android 15/16 phone: Start, tiles and app list work, live counts are absent but nothing crashes. |
| 4.2 | RROs versioned per SDK (`values-v36`), generated from the tokens by `design/tokens/gen_android.py`; overlays skipped when a resource is missing | B | On a ROM where a resource is absent the overlay is not enabled, and the phone boots with stock colours instead of a half-skin. |
| 4.3 | Hook module: every hook guarded, behind a per-feature switch with a safe default, and a "disable all" flag readable before Zygote | C | Boot with the module's master switch off: Settings is stock, nothing crashes, no log spam. |
| 4.4 | One token source of truth: launcher dimens, RRO values and the ROM overlay all generated from `ztmetro.tokens.json` | all | Change one accent in the JSON, regenerate: the launcher, the QS tiles and the Settings header all change together. |

---

## 4. What **not** to do on a 2 GB 720p phone

1. **No SystemUI rewrite from scratch.** The framework expects SystemUI's services,
   `NotificationListener`, keyguard, and WMShell bindings; a reimplementation is a
   product fork with an unbounded bug surface (`07-ztmetro-mapping.md` §4, "Not worth
   attempting").
2. **No fake chrome over apps.** A `TYPE_APPLICATION_OVERLAY` "status strip"/"nav bar"/
   "shade" sits *below* the status bar and IME, cannot take edge gestures, doubles the
   chrome in screenshots, and fights insets. The C strip paints no background at all
   (`ztmetro.c:5409-5418`); a fake one cannot.
3. **No blur.** `RenderEffect` blur of one 720p layer is ~30–60 ms/frame on Adreno 308
   `[E]`; the reference design uses flat black, so there is nothing to gain.
4. **No 64-tile tilt.** Animate the pressed tile and the rows that are leaving; per-tile
   GPU layers for the whole grid will stutter on scroll and cost ~10–20 MB.
5. **No keyguard replacement as an app.** A `showWhenLocked` activity is an overlay on
   top of a security surface; it can show a clock, and it cannot show notifications
   safely, keep the PIN path, or survive an OEM "swipe to unlock" setting.
6. **No forced WP header in third-party apps.** Either a framework patch (every app with
   its own toolbar gets two headers) or an Accessibility overlay (kills gestures, is
   killed by the system on memory pressure). Wave 3.3 exists precisely to make this
   opt-in.
7. **No literal sub-48 dp Metro numbers.** 7.6 pt quick labels and 31 dp round buttons
   are from a stylus-era 360 dp UI; ship the sizes, keep the 48 dp hit boxes.
8. **No WP back-stack semantics.** Predictive back is owned by the framework; remapping
   back inside our apps only creates a second, inconsistent model.
9. **No shade internals hook in Wave 1–2.** `NotificationPanelViewController` is a God
   class; the 23.2 shade is Compose; a throw takes the bars, the shade and the keyguard
   down and the user has no UI left to disable the module.
10. **No third-party icon packs or per-tile wallpaper tint.** They multiply the
    launcher's bitmap memory for a look the tokens already forbid (accent is
    interaction-only, P12).

---

## 5. What is already banked (do not rebuild)

Framework RRO (accent, ramp, square controls, QS colours), SystemUI RRO (flat black
shade, 0 dp corners, tile labels), Selawik system font module, Start launcher (tiles,
app list, pivot) as HOME, Settings Metro header module. This roadmap starts from that
stack; Wave 1 is refinement and the grid/list/panel geometry, not a restart.
