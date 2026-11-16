# Component — App list, search, letter grid

Owner: launcher APK (**A**). Roadmap: [`../../docs/14-ux-roadmap.md`](../../docs/14-ux-roadmap.md) §P4–P6, Wave 1.3–1.4.
Tokens: `metrics.list_icon_dp`, `list_row_height_dp`, `list_left_dp`, `list_top_dp`,
`round_button_dp`, `type.ramp.app_list_title`, `motion.*`.
Citations: `upstream/ztOS/ztmetro/ztmetro.c` unless stated.

## Original behaviour

Geometry is taken from a Lumia capture and stated in the source comment, not derived
(`:159-165`):

```c
#define LIST_ICON 45
#define LIST_ROW_H 55
#define LIST_LEFT 80
#define LIST_TOP 17
#define ROUND 31
```

The column starts 80 pt in and each row is a 45 pt square with a 55 pt pitch. A row is an
**accent-filled** square with the app glyph and the name at 22 Semilight, 13 pt to the
right (`:4512-4525`):

```c
cairo_rectangle(cr, 0, 0, LIST_ICON, LIST_ICON);
cairo_fill(cr);
draw_text(cr, it->name, FONT_SEMILIGHT, 22, LIST_ICON + 13, 7, w - LIST_ICON - 13, 0, false);
```

A **letter head** is the inverse: an accent 1.5 pt outline with the letter inside the
corner at 27 (`:4514-4518`). Search is a 31 pt round button centred at x = 51
(`:166-167`) and a 40 pt field at `STATUS_H + 12` (`:168-169`), filtering the list live.

Semantic zoom is the Swivel letter grid (`core/zrnboard.h:26-43`): 4 columns × 7 rows = 28
cells (`#`, a–z, globe), gap 9, top 9, letters 27; each cell turns from −45° about its own
X axis over 300 ms with a Log-200 curve, cells starting 12 ms apart in reading order,
over a 0.85 scrim; a cell accepts taps 100 ms after it starts. Out: +60° in 200 ms,
Exp-150.

## Android mechanism

| part | mechanism |
|---|---|
| rows | `RecyclerView` with `LinearLayoutManager`, item height 55–56 dp, square 45 dp `TextView` with accent background, name 22 sp `sans-serif-light` |
| letter heads | same adapter, view type = header; 1.5 dp accent stroke (`GradientDrawable`), letter 27 sp |
| search | `EditText` 40 dp under the header; filter on a precomputed lowercase `String[]`; `SearchView` only if we want the platform's voice/clear affordances |
| letter grid | a `GridLayout`/`RecyclerView` of 28 cells in an overlay `FrameLayout`; one `ObjectAnimator` per cell: `rotationX -45 → 0`, 300 ms, `startDelay = i * 12` |
| jump | on tap, `scrollToPosition` for the first item whose letter matches — no animation on the list itself, the C code jumps too |
| 80 dp column | keep the *look* (icon left, text right) but reduce the left inset to 16 dp + icon; 80 dp eats 22 % of a 360 dp width for nothing on a 720p phone |

Do not use `SectionIndexer`/`AlphabetIndexer` UI or a `sticky-header` library: both bring
a divider/header style the design does not have.

## Build order

1. Rows + letters + fonts (S).
2. Search field + live filter index (S).
3. Letter grid + swivel + scrim (S).
4. Tap-priority pass: rows, then grid, then back (S).

## Acceptance test

> In the app list, open the letters, tap "S": the list jumps to S and the letters flip in
> over about 0.4 s; type two characters in the search field and the list narrows without
> a visible stutter on a 2 GB phone.

## Risks

- **Sub-48 dp rows.** 45 dp is below the accessibility floor. The C pitch is 55, so use
  56 dp rows and keep the 45 dp square inside; nothing looks different, everything
  becomes tappable.
- **28 animators.** Build the stagger with `startDelay`, not 28 `Handler` posts; and
  cancel the set when the grid closes, or the launcher leaks animators on a 2 GB device.
- **Search cost.** Filter 300 labels on the main thread = jank. Keep a lowercase copy and
  a `Filter` with a 120 ms `postDelayed` debounce.
- **Recents/back.** Opening search must be a state inside the launcher, not a new
  activity, or back returns to a blank Start (the C code's `page` is the same idea).
- Nothing here breaks on a LineageOS update.
