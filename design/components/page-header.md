# Component — Page header and pivot tabs

Owners: launcher APK (**A**) for our own screens; LSPosed module (**C**) for Settings;
ROM (**D**) for anything else. Roadmap: [`../../docs/14-ux-roadmap.md`](../../docs/14-ux-roadmap.md) §P1, Wave 1.2 / 2.5.
Tokens: `type.header`, `type.ramp.page_title`, `type.ramp.caption_caps`,
`metrics.header_height_dp`, `metrics.pivot_bar_height_dp`.
Citations: `upstream/ztOS/ztmetro/core/zrnmetrocore.c` (`.c`) and `.h`, unless stated.

## Original behaviour

One header for every page, measured off the phone's own settings hub (`:471-478`):

```c
zmc_text(cr, zmc.app->caps, FONT_SEMIBOLD, 11.5, MARGIN, 14, 0);      /* app name, caps */
zmc_text(cr, title, FONT_SEMILIGHT, 48, MARGIN - 3, 24, 0);           /* page title */
```

The title is drawn at `MARGIN - 3` because Semilight has a left side bearing the caps
line does not; this 3 pt is not a mistake and should be kept as a −3 dp text offset or
removed consistently from both lines. Content starts at `y = 108` (`:532`), the band is
fixed at 104 (`.h:39`) and does not scroll with the list.

With a next tab, the next title is drawn at 0.38 grey, 16 pt after the current title's
measured width, **and the title after that** at the same offset — the wrap-around exists
so that two short titles do not show a third one popping in at the edge mid-swipe
(`:515-521`). Hit areas (`:523-531`): everything right of `MARGIN + w + 8` in the top
90 dp is `ZMC_ACT_PIVOT(arg)` and must be registered *before* the page-wide
`ZMC_ACT_BACK` band, or the back hit swallows the tab tap.

Motion (`:1806-1816`): 50 pt of drag commits a tab change, 60 pt lights the next title,
or a flick of 0.3 pt/ms; release continues at the finger's speed on a spring of 0.018/ms
(no overshoot); the title strip moves a title-width while the pages move a screen-width.

## Android mechanism

**A — launcher.** Fixed 104 dp header view above a `ViewPager2` (or two pages in a
custom pager). The tab strip is a `HorizontalScrollView` of two `TextView`s, the second
at 0.38 alpha, with the next title's *left edge* as the pivot hit target. Get the
"strip moves slower" by translating the strip by `pageOffset * (titleWidth + 16)` while
the pager translates a full width: same relation as the C code, for free.

**C — Settings.** Hook the single choke point
`com.android.settings.core.SettingsBaseActivity#setContentView(int)`
(`packages/apps/Settings` `:220-238`) and insert the header at **index 0 of
`R.id.content_frame`** — not inside `settings_main_prefs`, whose index 1 is the
weight-1 fragment host and would lose height. Mirror `setTitle(CharSequence)` so the
breadcrumb stays in sync, and add `SettingsHomepageActivity#onCreate` (`:252`) for the
launcher-facing home, inserting at index 0 of `R.id.homepage_container` so the header
scrolls with the page. Full argument in
[`../settings-container-map.md`](../settings-container-map.md) §3.

**D — everything else.** A framework patch in `PhoneWindow`/`DecorView` would give every
app a second header. Do not.

## Build order

1. Launcher header + two titles + tap targets (S).
2. Launcher pager slide with the slower strip (S).
3. Settings module: tag-guard, `content_frame` index 0, `setTitle` mirror, homepage (M).
4. Settings: config-change and back-stack pass (S).

## Acceptance test

> Tap the grey next title → the page slides and the header re-centres; tap the solid
> title → you go back one level; in Settings, drill Display → Dark theme and rotate the
> phone: exactly one header, with the right breadcrumb on each level.

## Risks

- **The 90 dp band vs. system back gesture.** On Android 16 the left/right edge insets
  belong to the system. Register the pivot band only inside `safeDrawing` and never start
  a horizontal drag within 18 dp of an edge (`EDGE 18`, `ztmetro.c:142`).
- **Clipping.** A 48 sp Selawik title in a 104 dp fixed header clips descenders at large
  `fontScale`; use `includeFontPadding=false`, `singleLine`, and accept the ellipsis the
  C code also has — it clips the scrolling list to below `HEADER_H` (`c:1534-1541`) and
  redraws the band on top, which is exactly a fixed header outside the scroller.
- **C failure mode is silent.** If a Settings release changes `setContentView(int)`, the
  header simply disappears — annoying, not dangerous. Guard every reflection lookup and
  never throw from the hook.
- **Update break:** Settings module breaks whenever those three points move; the RRO and
  launcher do not. Rebuild against the new tag, do not "fix" with reflection into
  private fields.
