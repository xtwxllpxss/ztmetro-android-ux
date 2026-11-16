# Component — Start tile grid

Owner: launcher APK (**A**). Roadmap: [`../../docs/14-ux-roadmap.md`](../../docs/14-ux-roadmap.md) §P2, Wave 1.1.
Tokens: `metrics.tile_size_dp`, `tile_gap_dp`, `start_columns`, `start_margin_dp`,
`start_top_pad_dp`, `medium_tile_size_dp`, `wide_tile_width_dp`, `corner_radius_dp`.
Citations are `upstream/ztOS/ztmetro/ztmetro.c`.

## Original behaviour

The grid is computed from the output width, never hard-coded (`:3266-3269`):

```c
double gutter = round(server->width * 0.02);
double margin = 2 * gutter;
double unit = (server->width - 2 * margin - (server->units - 1) * gutter) / server->units;
```

At the 360 dp reference: gutter 7, margin 14, unit 49.5, `units = 6` — "three medium
tiles across" (`:2996`). Sizes are 1×1, 2×2, 4×2 (`:3278-3280`); medium and wide tiles
may only start on even cells (`:3304-3305`), which is what keeps a Metro Start from
looking jittery. Small tiles are packed **as blocks**: the loader looks for a free
medium slot and fills its four corners in order (`:3273-3297`), so they never trail along
a row. The first row sits at `TOP_PAD = 14` above the strip (`:140`), positions are
`x = margin + col*(unit+gutter)`, `y = TOP_PAD + row*(unit+gutter)` (`:3299-3301`).

Tile paint: a plain rectangle fill — no arc, no shadow, no gradient (`:3654-3697`).
Icon centred at 44 % of the tile height, 46 % of it tall (`:3677`), label at (`:3678`); label 13 pt
Regular, x = 8, y = `h - 23`, clipped 16 dp short of the right edge (`:3678`); a live
count at 22 Light top-right (`:3680-3687`); a live badge or progress bar on top of that
(`:3688-3693`). Under the last row, on the right, the round arrow into the app list
(`:3337`, `ROUND 31` at `:166`).

Tilt is a *whole-grid* behaviour, not a decoration: 14°, lifted 40, sinking 0.045 of the
eye distance, 90 ms in / 200 ms out (`core/zrnmetrocore.h:32-36`), delayed 80 ms so a
scroll does not press (`PRESS_DELAY_MS`, `:148`). Hold 450 ms → edit (`:145`).

## Android mechanism

| need | mechanism |
|---|---|
| 6-unit grid with mixed spans | own `ViewGroup` with first-fit `onLayout` (exact C behaviour), **or** `RecyclerView` + `GridLayoutManager(spanCount = 6)` where the adapter owns placement. Prefer the former: `GridLayoutManager` reflows and will not leave the holes Metro Start has. |
| gutter/margin | derive from width at runtime (`width * 0.02`), do not ship 7 dp; a 411 dp phone then still gets three medium columns. |
| square tiles | `corner_radius 0`; no `MaterialCardView`, no elevation (elevation = shadow = not Metro). |
| tilt | `view.rotationX/rotationY` toward the touch point + `cameraDistance ≈ 40 * density` (the C lift is 40 pt); animate with `ViewPropertyAnimator` 90/200 ms. |
| long-press edit | `ItemTouchHelper` drag + 32 dp `ImageButton` badges; persist the new order in the tile store, not in view order. |
| live count/badge | `NotificationListenerService` data on the tile model (Wave 2.1); no widget rendering, no `RemoteViews` host. |

Do **not** use `AppWidgetHost` for the tile itself: widgets cannot be square-tiled at
arbitrary spans and each host costs a process.

## Build order

1. Layout + tokens (grid looks right with static data).
2. Packing (small tiles as blocks, even-cell rule) + persist/reload.
3. Tilt + long-press edit + drag reordering.
4. Live counts (after the listener exists).

## Acceptance test

> On Start, three medium tiles fit across with an even gap; a small tile always sits in
> a corner of a medium slot rather than strung along a row; no tile has a rounded corner
> or a shadow; press and hold still → the tile leans; slide instead → it does not.

## Risks

- **Frames.** 64 tiles with `rotationX` and their own layers stalls Adreno 308 at 720p
  `[E]`. Animate the pressed tile and the leaving rows only; `setLayerType(NONE)`.
- **Wallpaper cost.** A 720×1280 wallpaper bitmap is ~3.7 MB; do not tint per tile
  (`WALL_TINT` in the C code multiplies the photo per tile, `:3664-3667`) — one drawable
  behind the grid is enough.
- **Long-press vs. drag.** `ItemTouchHelper` starts a drag at the same 450 ms; if both
  fire the tile lifts and jumps at once. Own the gesture with one detector.
- Nothing here breaks on a LineageOS update: it is our APK.
