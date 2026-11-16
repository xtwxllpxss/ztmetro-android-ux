# Component — Action Centre (shade)

Owners: launcher APK (**A**, our panel) + SystemUI RRO (**B**, restyle) — layout over
apps is **D**. Roadmap: [`../../docs/14-ux-roadmap.md`](../../docs/14-ux-roadmap.md) §P7, Wave 1.5 / 2.4.
Tokens: `metrics.action_centre_*`, `color.chrome.quick_action_*`,
`color.accent.base`, `color.neutrals.text_secondary_strong`.
Citations: `upstream/ztOS/ztmetro/ztmetro.c`.

## Original behaviour

Pulled down from the status strip, it covers everything **above the nav bar only**
(`SHADE_MAX_H`, `:137`) and settles in 260 ms (`:138`). Top to bottom (`:6230-6245`):

```c
#define SHADE_TOP 50
#define SHADE_SIDE 4
#define SHADE_GAP 3
#define SHADE_HANDLE 16
*w = (width - 2*SHADE_SIDE - 3*SHADE_GAP) / 4.0;   /* 85.75 at 360 */
*h = round(*w * 0.66);                              /* 57 */
```

- quick actions: 4 across, `y = SHADE_TOP + (i/4)*(h+gap)`; ON = accent, OFF =
  0.122 grey, `Q_NONE` = 0.07 (`:6297-6316`);
- hardware that cannot be switched (no driver/service) is drawn white at 30 % and does
  not pretend (`:6318-6321`);
- label 7.6 Semibold, x = 7, `y = h - 15` (`:6350`);
- links row 12 below the grid: `CLEAR ALL` left, `ALL SETTINGS` right, 10 Semibold
  (`shade_links_y` `:6255-6258`, draw `:6517-6519`);
- notifications start 30 below the links (`:6407-6412`): app square 28×28 in accent at
  x = 14 with the glyph, app name 22 Semilight, title 14.5 Semibold with the time 13.5
  right-aligned, body 13.5 at 0.62 grey (`:6356-6405`);
- an accent bar 16 dp along the bottom is the push-up handle, with a 28×3 white grip
  (`:6546-6553`).

Source-fidelity note: the block comment at `:6230-6234` still says "CLEAR ALL and
COLLAPSE", but the drawing (`:6517-6519`) and the hit map (`:6261-6262`, `8`/`9`) are
`CLEAR ALL` / `ALL SETTINGS`. Follow the code, not the comment.

## Android mechanism

| level | what we get | what we lose |
|---|---|---|
| **A** — own full-screen activity/panel from a Start tile or a swipe-up | the layout, exactly: 4×2 of 57 dp cells, links, notification list from our `NotificationListenerService`, accent handle, 260 ms slide | it is a *task*: no pull-down from the status strip, no covering another app, back-stack semantics differ |
| **B** — SystemUI RRO | squarer, flatter, denser real shade: `qs_corner_radius` 0 (`dimens.xml:726`), `qs_tile_height`, `TextAppearance.QS.TileLabel` 12 sp (`styles.xml:164-169`), `notification_corner_radius` 0 (`dimens.xml:348`), `shadeActive`/`shadeInactive` (`attrs.xml:125-131`), `overlay_shade_panel_shape_radius` 0 (`shade_dimens.xml:24-36`), `quick_settings_num_columns` 2→4 (`config.xml:68`, read at `qs/TileLayout.java:152-153` — `[F]` for the View path, `[?]` for the 23.2 Compose shade) | no reordering, no links row, no handle, probably still 2 columns |
| **C** — hook the shade | reordering is *possible* in principle via `com.android.systemui.shade.NotificationPanelViewController` / the Compose QS container | a throw kills the bars, the shade and the keyguard; the 23.2 shade is Compose so `R.id`-based edits do not reach it |
| **D** — SystemUI fork | the real thing, including "above the nav bar only" | months |

## Build order

1. **A** panel: grid + toggles with real intents (`WifiManager.setWifiEnabled` is
   restricted on 16 — use `Settings.Panel` / `ACTION_*` trampolines, or our own
   foreground service for the toggles we own).
2. **A** notification list from the listener; `CLEAR ALL` via listener; `ALL SETTINGS`
   as a normal intent.
3. **B** pass; measure `quick_settings_num_columns` before writing any hook (Wave 2.4).
4. Only if 3 fails *and* the user insists: C, behind a master switch.

## Acceptance test

> From a Start tile, our panel opens with eight quick cells — accent when on, grey when
> off, no rounded corners — a working `CLEAR ALL`, and a 16 dp accent bar at the bottom.
> Then pull the real shade: tiles are square, labels 12 sp, and (if the experiment
> passed) four per row.

## Risks

- **Toggles are not ours.** Wi-Fi/Bluetooth/data toggles are privileged on Android 16;
  a third-party panel can only open the relevant settings panel or reflect into hidden
  APIs (which ages badly). Show the state read-only where we cannot switch.
- **Listener death.** `NotificationListenerService` is killed under memory pressure; the
  panel must render "no notifications" rather than an empty frame.
- **B collision.** `shadeActive`/`shadeInactive` and the scrim colours are shared with
  dialogs and the power menu; an opaque black scrim also makes those opaque. Change one
  resource at a time and look at a dialog and the power menu after each.
- **Update break:** the overlay dies quietly on any resource rename; the hook dies loudly.
  Prefer the overlay.
