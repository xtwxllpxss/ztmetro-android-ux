# ztmetro UX verification — automated report

Run: `2026-11-16T14:28:54+03:00` → `2026-11-16T14:37:06+03:00` (491.3 s), mode **full**.

Evidence: `/Users/meow/Projects/redmi-note-5a-ztos/build/android-ux-research/repo/evidence/verify/20261116-142854`  ·  `report.json` + `annotated/*.png` + `diff/*`

## Summary

| PASS | FAIL | SKIP | total |
|---:|---:|---:|---:|
| 17 | 9 | 0 | 26 |

### Failing checks

- **pre.density_factor** — dd=px/dp factor matches the brief's 280 dpi reference: `px_per_dp=1.62 density_dpi=260 override_dpi=—` (expected `px_per_dp=1.75 density_dpi=280`, tol `px_per_dp=0.01` px/dp)
- **start.no_header** — no page header text in the top 100 dp: `glyph_px_on_black=826` (expected `glyph_px_on_black=0`, tol `max_glyph_px=30` px)
- **start.palette** — >=3 distinct ztmetro palette fills and cobalt: `distinct_palette_fills=0 fills={} cobalt_px=0` (expected `distinct_palette_fills=>=3 cobalt_px=>0`, tol `distinct_palette_fills=0 cobalt_px=0` px)
- **start.grid_metrics** — tile gutter ~7 dp and page margins ~14 dp: `gutter_dp=0 left_margin_dp=232 right_margin_dp=155.08 tiles=1` (expected `gutter_dp=7 margin_dp=14`, tol `gutter_dp=2 margin_dp=2` dp)
- **start.tile_glyphs** — white glyph pixels inside >= 3 tiles: `tiles_with_white_glyph=0 glyph_px=[]` (expected `tiles_with_white_glyph=>=3`, tol `0` tiles)
- **start.square_corners** — tile corners are fill, not black gap: `tiles_all_4_corners_fill=0 tiles_checked=1 fraction=0` (expected `fraction=>=0.9`, tol `0.10` fraction)
- **settings.title_cap** — page title cap-height >= 60 px (34 dp): `cap_height_px=52 cap_height_dp=32 font_scale=1` (expected `cap_height_px=>=60 cap_height_dp=>=34`, tol `0` px)
- **wifi.switch_accent** — switch track/thumb is accent #0050EF (+/-20) when ON: `on=yes track_rgb=[0, 40, 120] track_delta=[0, 40, 119] thumb_rgb=[255, 255, 255] thumb_delta=255` (expected `track_rgb=[0, 80, 239]`, tol `per_channel=20` rgb)
- **ac.focus** — panel is our activity (window focus): `focus=Window{979541e` (expected `focus=org.ztmetro.actioncentre`, tol `0` )

## Device

| field | value |
|---|---|
| transport | `ssh hermes-ubuntu '~/android-sdk/platform-tools/adb ...'` |
| serial | `` |
| sdk | `36` |
| wm size | `[720, 1280]` |
| wm density | `260` |
| density override (dpi) | `None` |
| px per dp (measured) | `1.625` |
| px per dp (brief) | `1.75` |
| font scale | `1.0` |
| systemui pid | `2098` |
| enabled ztmetro/ztos overlays | `9` |

dp = px / **1.625** (the live density). The brief's reference is **1.75 px/dp** (280 dpi); `pre.density_factor` records whether they agree.

## Captures

| surface | png | focus | sdk | systemui | overlays | keyguard |
|---|---|---|---|---|---|---|
| start | `raw/start.png` | `Window{e442f63` | 36 | 2098 | 9 | False |
| app-list | `raw/app-list.png` | `Window{3760d55` | 36 | 2098 | 9 | False |
| settings-home | `raw/settings-home.png` | `Window{c547228` | 36 | 2098 | 9 | False |
| settings-wifi | `raw/settings-wifi.png` | `Window{77b4409` | 36 | 2098 | 9 | False |
| action-centre | `raw/action-centre.png` | `Window{979541e` | 36 | 2098 | 9 | False |
| lock | `raw/lock.png` | `Window{82efaf6` | 36 | 2098 | 9 | True |

## Checks

### start

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `start.no_header` — no page header text in the top 100 dp | **FAIL** | `glyph_px_on_black=826 px` | `glyph_px_on_black=0` | `max_glyph_px=30 px` |
| `start.palette` — >=3 distinct ztmetro palette fills and cobalt | **FAIL** | `distinct_palette_fills=0 fills={} cobalt_px=0 px` | `distinct_palette_fills=>=3 cobalt_px=>0` | `distinct_palette_fills=0 cobalt_px=0 px` |
| `start.grid_metrics` — tile gutter ~7 dp and page margins ~14 dp | **FAIL** | `gutter_dp=0 left_margin_dp=232 right_margin_dp=155.08 tiles=1 dp` | `gutter_dp=7 margin_dp=14` | `gutter_dp=2 margin_dp=2 dp` |
| `start.tile_glyphs` — white glyph pixels inside >= 3 tiles | **FAIL** | `tiles_with_white_glyph=0 glyph_px=[] tiles` | `tiles_with_white_glyph=>=3` | `0 tiles` |
| `start.square_corners` — tile corners are fill, not black gap | **FAIL** | `tiles_all_4_corners_fill=0 tiles_checked=1 fraction=0 fraction` | `fraction=>=0.9` | `0.10 fraction` |

- `start.no_header`: status strip excluded (y < 28 dp); tile labels sit on saturated fill and are not counted
- `start.palette`: fills clustered from saturated pixels, count >= 2000 px at 1/4 area
- `start.grid_metrics`: gaps from 0 adjacent tile pairs
- `start.tile_glyphs`: white = min(R,G,B) >= 200, >= 40 px per tile
- `start.square_corners`: corner sampled 2 px inside the measured tile bbox

### app-list

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `applist.icon_column` — icon column starts at x = 80 dp | **PASS** | `x_dp=80 rows=6 dp` | `x_dp=80` | `x_dp=3 dp` |
| `applist.icon_size` — icon square is 45 dp | **PASS** | `w_dp=44.92 h_dp=44.92 dp` | `size_dp=45` | `size_dp=3 dp` |
| `applist.label_x` — app label starts at 138 dp | **PASS** | `label_x_dp=139.69 rows=6 dp` | `label_x_dp=138` | `label_x_dp=4 dp` |
| `applist.rows` — at least 3 app rows detected | **PASS** | `rows=6 rows` | `rows=>=3` | `0 rows` |
| `applist.contrast` — white text on black contrast >= 4.5:1 | **PASS** | `contrast=18.10 text_rgb=[244, 244, 244] bg_rgb=[0, 0, 0] :1` | `contrast=>=4.5` | `0 :1` |

- `applist.icon_column`: median left edge of the accent icon squares
- `applist.icon_size`: median square size
- `applist.label_x`: first white text column right of the icon square
- `applist.rows`: solid accent icon squares
- `applist.contrast`: WCAG 2.1 relative luminance, worst measured row

### settings-home

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `settings.bg_black` — background is #000000 (>=70% of pixels) | **PASS** | `black_fraction=0.97 fraction` | `fraction=>=0.70` | `0 fraction` |
| `settings.no_banner` — no cobalt banner in the top 100 dp | **PASS** | `cobalt_px=0 saturated_px=0 px` | `cobalt_px=0 saturated_px=0` | `max=20 px` |
| `settings.title_cap` — page title cap-height >= 60 px (34 dp) | **FAIL** | `cap_height_px=52 cap_height_dp=32 font_scale=1 px` | `cap_height_px=>=60 cap_height_dp=>=34` | `0 px` |
| `settings.rows_white` — rows are #E6E6E6..FFFFFF on black | **PASS** | `median_text_rgb=[255, 255, 255] fraction_>=E6=0.94 black_fraction=0.97 fraction` | `median_text_rgb=#E6E6E6..FFFFFF black_fraction=>=0.70` | `fraction_>=E6=0.75 fraction` |
| `settings.single_title` — exactly one large title text block | **PASS** | `large_title_blocks=1 blocks=[{'h': 52, 'y': 164}] blocks` | `large_title_blocks=1` | `0 blocks` |

- `settings.bg_black`: max(R,G,B) <= 12
- `settings.no_banner`: the top band may contain only black, white caps and the title glyphs
- `settings.title_cap`: cap height = bbox of the first (uppercase) title glyph at fontScale 1
- `settings.rows_white`: white pixels of the body row labels
- `settings.single_title`: large = ink band height >= 40 px and width >= 80 px in the top 200 dp

### settings-wifi

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `wifi.switch_accent` — switch track/thumb is accent #0050EF (+/-20) when ON | **FAIL** | `on=yes track_rgb=[0, 40, 120] track_delta=[0, 40, 119] thumb_rgb=[255, 255, 255] thumb_delta=255 rgb` | `track_rgb=[0, 80, 239]` | `per_channel=20 rgb` |
| `wifi.no_rounded_cards` — no >90%-wide group card with a corner radius >8 dp | **PASS** | `cards=[] violations=0 cards` | `violations=0` | `0 cards` |

- `wifi.switch_accent`: track = modal colour of the switch component; thumb = white centre
- `wifi.no_rounded_cards`: radius = rows from the card top until the left edge reaches its widest point

### action-centre

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `ac.focus` — panel is our activity (window focus) | **FAIL** | `focus=Window{979541e` | `focus=org.ztmetro.actioncentre` | `0` |
| `ac.status_row` — top 50 dp status row has non-black pixels | **PASS** | `nonblack_px=1573 px` | `nonblack_px=>=100` | `0 px` |
| `ac.tiles_4x2` — exactly 8 action tiles in a 4x2 grid | **PASS** | `tiles=8 columns=4 rows=2 tiles` | `tiles=8 columns=4 rows=2` | `0 tiles` |
| `ac.tiles_square` — action tiles are square (+/-3 dp) | **PASS** | `worst_wh_delta_dp=0 sizes_dp=['105.2x105.2', '105.2x105.2', '105.2x105.2', '105.2x105.2', '105.2x105.2', '105.2x105.2', '105.2x105.2', '105.2x105.2'] dp` | `wh_delta_dp=<=3` | `3 dp` |
| `ac.tile_active_fill` — active tile fill is #0050EF | **PASS** | `active_tiles=4 active_rgb=[[0, 80, 239], [0, 80, 239], [0, 80, 239], [0, 80, 239]] tiles` | `active_tiles=>=1 rgb=[0, 80, 239]` | `per_channel=10 tiles` |

- `ac.focus`: dumpsys window mCurrentFocus at capture time
- `ac.status_row`: tile pixels inside the band are excluded
- `ac.tiles_4x2`: accent #0050EF or off #1F1F1F fills, w/h >= 100 px
- `ac.tiles_square`: |w - h| from the measured tile boxes
- `ac.tile_active_fill`: modal fill per tile

### lock

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `lock.single_clock` — only one large clock glyph block | **PASS** | `large_blocks=1 block_heights_px=[109] clock_height_dp=67.10 blocks` | `large_blocks=1` | `0 blocks` |
| `lock.no_status_text` — no status-row text in the top 40 dp | **PASS** | `light_px=0 bbox=— px` | `light_px=0` | `max=30 px` |

- `lock.single_clock`: large = white ink band >= 48 dp tall (clock glyphs, not date/notification)
- `lock.no_status_text`: light text/icons on the lock wallpaper

## Reference diff (structural)

Edges (Sobel/FIND_EDGES) ±2 px; mismatch = symmetric difference / union, not pixel colour. 50% blends are in `diff/`.

| surface | mockup | mismatch | shot-only edges | mockup-only edges |
|---|---|---:|---:|---:|
| start | `start.png` | **83.27 %** | 26753 | 22615 |
| app-list | `app-list.png` | **90.01 %** | 16828 | 14623 |
| settings-home | `settings.png` | **87.83 %** | 16063 | 14511 |
| settings-wifi | `settings.png` | **90.46 %** | 18574 | 14775 |
| action-centre | `action-centre.png` | **80.13 %** | 24293 | 25119 |
| lock | `lock.png` | **86.76 %** | 4669 | 9513 |

## Sequence

- WAKEUP, unlock if keyguard
- KEYCODE_HOME; wait 3 s
- launcher was not on Start — KEYCODE_BACK; wait 1 s; recapture
- input swipe 650 700 120 700 300 (Start -> app list); wait 2 s
- app list not detected — retry swipe; wait 1.5 s
- am start -a android.settings.SETTINGS; wait 4 s
- am start -a android.settings.WIFI_SETTINGS; wait 3 s
- am start -a android.intent.action.MAIN -c android.intent.category.APP_CALENDAR; wait 2 s
- input swipe 360 6 360 400 250 (top-edge strip); wait 2 s
- KEYCODE_SLEEP; wait 1.5 s; KEYCODE_WAKEUP; wait 2 s
- WAKEUP + unlock (required before volume: keyguard would hide Settings)
- aborted: could not unlock the keyguard before the volume capture

## Re-run

```sh
/Users/meow/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3 /Users/meow/Projects/redmi-note-5a-ztos/build/android-ux-research/repo/scripts/ux-verify.py --diff-reference
```

