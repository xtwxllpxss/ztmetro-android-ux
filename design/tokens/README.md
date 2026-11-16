# ztmetro design tokens

One portable source of truth for the Metro / Windows-Phone look of the original
`ztOS/ztmetro` shell, so the Android layer does not re-derive it from screenshots.

| file | purpose |
|---|---|
| `ztmetro.tokens.json` | the source of truth: colour, type, metrics, motion |
| `gen_android.py` | dependency-free Python 3 generator (stdlib only, offline) |
| `../../docs/11-ztmetro-reference.md` | concept-by-concept mapping with C file:line |

Everything is derived from the C implementation, not from the Windows Phone design
guidance document. Read `ztmetro.c`, `core/zrnmetrocore.{c,h}` and
`settings/ztsettings.c` under `upstream/ztOS/ztmetro/` before changing a value.

## Regenerate

From this directory:

```sh
python3 gen_android.py --out DIR
```

`--help` prints the same CLI:

```text
usage: gen_android.py [-h] --out DIR [--tokens PATH]
```

- `--out DIR` — writes `DIR/res/values/metro_colors.xml`,
  `DIR/res/values/metro_dimens.xml`, `DIR/res/values/metro_styles.xml`,
  `DIR/overlay/metro_overlay.xml` and `DIR/java/MetroTokens.java`.
- `--tokens PATH` — read another token file (default: `ztmetro.tokens.json`
  beside the script).

Every run validates provenance first: if any number is missing an origin, the
script exits `2`, lists the gaps, and writes nothing.

The generated files are build inputs; do not copy them back into this repo.
Regenerate instead.

## Reference screen and units

The C shell lays out in logical points (`ztmetro.c:127`), and its own default
window is 360 × 640 points (`core/zrnmetrocore.c:68`). The reference phone is
720 × 1280 px at 320 dpi, i.e. **360 × 640 dp**, so **1 point = 1 dp**. Type
sizes are Pango absolute sizes in points, which map 1:1 to **sp at
fontScale 1.0**. No raw pixel constants are stored in the JSON.

Everything is resolution-independent, but note that the Start grid is computed
from the width (`ztmetro.c:3266-3272`): at 360 dp with the default six small-tile
units the tile is 49.5 dp and the gutter 7 dp. On a wider screen the C code
recomputes both; an Android launcher that wants the same look should use the same
formula (`unit = (width − 2·margin − (units−1)·gutter) / units`) rather than the
literal 49.5.

## Derived vs. adapted

Values marked `derived [E]` in the JSON are not read from a single C literal.
They fall into four groups:

1. **Arithmetic on C values** — `tile_size_dp` 49.5, `medium_tile_size_dp` 106,
   `wide_tile_width_dp` 219, `action_centre_row_height_dp` 57 (all from the
   formulas at `ztmetro.c:3266-3272` and `:6249-6251`).
2. **Android-only additions** — `min_touch_target_dp` 48 (the C shell uses a
   31 dp round button and 7.6 pt quick-action labels; the Android accessibility
   floor is deliberately larger), the `light1`/`light2` accent tints and
   `dark2` (the C code has no light accent variant; the lock-screen fade's
   accent × 0.5 supplies `dark1`).
3. **Line heights** — the C code places text at explicit y offsets rather than
   relying on a line-height scale. `list_title` uses the sourced 31 pt step
   (`core/zrnmetrocore.c:550-552`); the rest are 1.2–1.4 × size, marked `[E]`.
4. **Android asset mapping** — Semilight/Light → `sans-serif-light`, Semibold →
   `sans-serif-medium`, Semilight numeric weight 350, Log/back-ease-out →
   `DecelerateInterpolator` / `OvershootInterpolator(1.0)`.

The neutral palette is deliberately small: ztmetro is black-backed and uses white
text with a handful of grey steps (0.6, 0.62, 0.55, 0.38, 0.22 surfaces). There
are **no dividers** — `zmc_entry` draws nothing between rows
(`core/zrnmetrocore.c:544-552`) — and **no rounded corners** on tiles
(`ztmetro.c:3657-3697`).

## Accent and the framework overlay

The default accent is cobalt `#0050EF` (`ztmetro.c:2983`,
`core/zrnmetrocore.c:68,73`, `settings/ztsettings.c:200`). The user can pick one
of 20 named colours; the token file only stores the compiled-in default. If the
Android layer makes the accent user-selectable, keep `color.accent.base` as the
seed and recompute the four variants with the same mixes.

`overlay/metro_overlay.xml` (and the same entries as a comment inside
`metro_styles.xml`) is a **sample** resource-overlay map for `framework-res`:
`colorAccent`, `colorPrimary`, `system_accent1_100…700`, and
`textAppearanceLarge/Medium/Small`. Build it in its own overlay APK, not in the
launcher:

```sh
# in the overlay package: copy overlay/metro_overlay.xml and metro_styles.xml into its
# own res/values/ so the @style refs resolve, then
cd overlay-package
aapt2 compile --dir res -o overlay.zip
aapt2 link -o MetroOverlay.apk -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml --target-package android \
    --auto-add-overlay overlay.zip
```

On Android 12+ the dynamic-colour seeds (`system_accent1_*`) are what Material
You reads; the light/dark variants above are the honest approximation, because
the C shell has no tonal palette.

## Motion

Durations are the shell's own measured values, including the Lumia-derived
timings (`ROW_OUT_MS 128`, `ROW_STAGGER_MS 33`, `TILE_LIFE_MS 186`,
`TURN_GAP_MS 110`, `APP_IN_MS 350`). The C curves are Log(10) ease-out and
back-ease-out (`ztmetro.c:1096-1157`); Android interpolators are approximations.
`tilt_deg`, `tilt_lift_dp` and `press_sink_fraction` describe the Windows-Phone
press (lean toward the finger, max 40 dp lift, sink by 4.5 % of the eye
distance) and are emitted to `MetroTokens.java` only, since Android XML has no
degree or fraction resource type.

## Discrepancies against `artifacts/ztmetro-tokens.css`

The CSS artefact is a WP-guidance sketch, not a measurement of this codebase.
Kept as history; do not port from it.

| CSS token | CSS value | C source | verdict |
|---|---|---|---|
| `--zt-accent` | `#1f6feb` | `#0050EF` | placeholder; real default is cobalt |
| `--zt-surface` | `#101010` “tiles” | tiles are accent-coloured (`ztmetro.c:3665-3673`) | wrong model: tile colour comes from the per-tile palette, not a grey surface |
| `--zt-surface-raised` | `#1a1a1a` | `#1F1F1F` quick-action off / function key | near-miss |
| `--zt-divider` | `#262626` | no dividers exist | invented |
| `--zt-text-secondary` | `#b8b8b8` | `#999999` (0.6) / `#9E9E9E` (0.62) | too light |
| `--zt-text-disabled` | `#6b6b6b` | white at 30 % alpha ≈ `#4D4D4D` on black | too light |
| `--zt-font-caps` | `11.5px` | 11.5 pt Semibold | matches |
| `--zt-font-caption` / `-body` | `15` / `20` | 12–13 regular; 20 is a dialog/choice title | mislabelled |
| `--zt-font-list-title` | `25.3` | 25.5 Semilight | close, CSS is guidance-derived |
| `--zt-font-page-title` | `72` | 48 Semilight | wrong by 1.5× |
| `--zt-font-xl` / `-huge` | `42.7` / `186.7` | no such sizes; lock clock is 92 | invented |
| `--zt-status-height` / `--zt-nav-height` | `28` / `40` | `STATUS_H 28`, `NAV_BAR_H 40` | matches |
| `--zt-margin` / `--zt-gutter` | `12` / `12` | page margin 16; Start margin 14, gutter 7 | wrong |
| `--zt-tile-unit` | `72` | 49.5 dp at the 360 dp reference | CSS assumes a 480-wide reference |
| `--zt-tile-radius` | `0` | square tiles | matches |
| `--zt-motion-fast/normal/page` | `120/180/250 ms` | press 90/200, page 300 | no 120/250 in the press ramp |
| design reference | 480 px | 360 points | wrong base |

The CSS “Android mapping notes” (map accent to `colorPrimary`, keep 48 dp touch
targets, hide the real status bar only in immersive mode) remain sound advice and
agree with this README.

## Licence

- The token **values** are facts and measurements of a user interface. They are
  published under the repository terms in `../../LICENSE` (documentation
  CC BY 4.0, code artefacts MIT).
- The upstream C sources are `GPL-2.0-or-later` (SPDX headers). No C code is
  copied into this directory — only constants, sizes and colour values, cited by
  `file:line`.
- **No font files are shipped.** `Segoe UI`, `Selawik` and `Open Sans` appear only
  as font-family fallback names copied from the C `FONT_*` macros. Segoe is
  Microsoft's, Selawik is SIL OFL, Open Sans is Apache-2.0; the Android generator
  maps them to the system `sans-serif` families instead of bundling any of them.
