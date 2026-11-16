# Component — Status strip

Owner: **B** for the glyphs (framework RRO); **D** for the strip itself.
Roadmap: [`../../docs/14-ux-roadmap.md`](../../docs/14-ux-roadmap.md) §P9, Wave 2.3 / 3.1.
Tokens: `metrics.status_strip_height_dp`, `color.chrome.status_strip`,
`color.status.warning_battery`.
Citations: `upstream/ztOS/ztmetro/ztmetro.c`.

## Original behaviour

`#define STATUS_H 28` (`:128`) and **no background fill at all** — the row is white
glyphs drawn on whatever is behind it (`:5409-5418`). Sizes are proportional to the
screen, taken off photos of the real phone (`:5413-5418`):

```c
const double cell = round(server->width * 0.032 * 2) / 2;   /* 11.5 at 360 */
const double right = server->width - 14;
const double base = (STATUS_H + cell) / 2;
double x = 14;
```

Order left to right: signal bars (or an aeroplane), data generation (4G/H+/3G/E/G), the
Wi-Fi fan, Bluetooth, then the clock on the right at `1.3 × cell` (`:5477-5489`) with the
battery to its left — outline 1.95 × cell tall, terminal nub, inner fill, **warning red
at ≤15 %** (`:5530-5533`), charging plug over the outline when charging (`:5540-5560`).

Holding the strip opens a **peek** of `STATUS_H + 22` with a second line — charge percent
under the battery, the date under the clock (`:149-151`); the Action Centre repeats the
same row with `details = true` (`:5409-5413`, `:6503`).

## Android mechanism

The status bar is SystemUI's; the 28 dp row, the peek and the second line are **D**. What
is available now:

| element | class | exact resource / limit |
|---|---|---|
| battery glyphs, signal bars, Wi-Fi fan | **B** on `android` | the framework-res vectors `stat_sys_battery*`, `stat_sys_battery_charge*`, `stat_sys_signal_*`, `stat_sys_wifi_signal_*` **exist in the target**, so replacing them is a legal overlay (`10b` §3a). Must cover every level, charging state and the `_light`/`_dark`/`_err` variants or the bar shows a mix. |
| clock type | **B** | the bar clock is still `com.android.systemui.statusbar.policy.Clock`, a `TextView` styled `@style/TextAppearance.StatusBar.Default` (`res/layout/status_bar.xml:94-100`, centre variant `:212-218`) — that style **exists** in the target, so an RRO can restyle it (there is no `TextAppearance.StatusBar.Clock`; do not reference one) |
| 28 dp height | **B but risky** | `status_bar_height` lives in framework-res (`core/res/res/values/dimens.xml:44-60`, 24 dp); SystemUI only adds padding (`dimens.xml:463,469`). Setting it to 28 changes insets for **every** app and every cutout calculation `[?]` |
| peek / second line / details row | **D** | `NotificationShadeWindowView` + shade header in a fork |
| our own strip drawn by the launcher | **never** | the C strip paints nothing over black; a launcher-drawn strip would double the chrome, cannot cover other apps, and sits under the IME |

## Build order

1. Pick the glyph set to replace and count the variants with `aapt2 dump resources`
   (aatter a `[?]` check): one missing variant is visible at exactly the wrong moment.
2. Draw them at 720p/320 dpi: 1.95 × 11.5 dp ≈ 22 dp battery → 44 px tall vector; test at
   fontScale 1.0 and 1.3.
3. Clock text appearance (Selawik Light is already the system font, so this is 4 lines of
   XML).
4. Only in Wave 3: height and peek in the fork.

## Acceptance test

> Look at the status bar: the battery is an outlined rectangle with a nub, the fill is
> solid white above 15 % and red at or below it, the clock is in Selawik, and no stock
> Material icon appears at 100 %, while charging, or with no SIM.

## Risks

- **Variant completeness** is the whole risk of the B pass; it is also the cheapest fix
  when it goes wrong (one more drawable).
- **Height change** can clip the left clock on devices with a cutout and shift every
  app's layout by 4 dp; treat as an experiment, not a Wave 1 item.
- **Update break:** AOSP glyph names are stable, the clock style name is not; a rename
  turns the overlay into a no-op (silent) — check `cmd overlay list` after each OTA.
- Do not replace `stat_sys_battery` with a 9-patch of a fixed size: it must be a vector
  or density-specific PNGs, and the C bar scales with screen width.
