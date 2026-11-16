# ztmetro UX verification — automated report

Run: `2026-11-16T15:29:08+03:00` → `2026-11-16T15:30:56+03:00` (108.3 s), mode **full**.

Evidence: `/Users/meow/Projects/redmi-note-5a-ztos/build/android-ux-research/repo/evidence/verify/20261116-152908`  ·  `report.json` + `annotated/*.png`

## Summary

| PASS | FAIL | SKIP | total |
|---:|---:|---:|---:|
| 26 | 2 | 0 | 28 |

### Failing checks

- **settings.title_cap** — title glyph band >= 46 px (lowercase Cyrillic, 58 sp): `band_px=0 band_dp=0 implied_size_sp=0 font_scale=1` (expected `band_px=>=46 band_dp=>=28`, tol `0` px)
- **settings.single_title** — exactly one large title text block: `large_title_blocks=0 blocks=[]` (expected `large_title_blocks=1`, tol `0` blocks)

## Device

| field | value |
|---|---|
| transport | `ssh hermes-ubuntu '~/android-sdk/platform-tools/adb ...'` |
| serial | `4f2b50b2` |
| sdk | `36` |
| wm size | `[720, 1280]` |
| wm density | `260` |
| density override (dpi) | `None` |
| px per dp (measured) | `1.625` |
| px per dp (brief) | `1.75` |
| font scale | `1.0` |
| systemui pid | `2082` |
| enabled ztmetro/ztos overlays | `9` |

dp = px / **1.625** (the live density). The brief's reference is **1.75 px/dp** (280 dpi); `pre.density_factor` records whether they agree.

## Captures

| surface | png | focus | sdk | systemui | overlays | keyguard |
|---|---|---|---|---|---|---|
| start | `raw/start.png` | `org.ztmetro.start/org.ztmetro.start.StartActivity}` | 36 | 2082 | 9 | False |
| app-list | `raw/app-list.png` | `org.ztmetro.start/org.ztmetro.start.StartActivity}` | 36 | 2082 | 9 | False |
| settings-home | `raw/settings-home.png` | `com.android.settings/com.android.settings.Settings}` | 36 | 2082 | 9 | False |
| settings-wifi | `raw/settings-wifi.png` | `com.android.settings/com.android.settings.Settings$WifiSettingsActivity}` | 36 | 2082 | 9 | False |
| action-centre | `raw/action-centre.png` | `org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreActivity}` | 36 | 2082 | 9 | False |
| lock | `raw/lock.png` | `NotificationShade}` | 36 | 2082 | 9 | True |
| volume | `raw/volume.png` | `com.android.settings/com.android.settings.Settings}` | 36 | 2082 | 9 | False |

## Checks

### start

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `start.no_header` — no page header text in the top 100 dp | **PASS** | `glyph_px_on_black=0 px` | `glyph_px_on_black=0` | `max_glyph_px=30 px` |
| `start.palette` — >=3 distinct ztmetro palette fills and cobalt | **PASS** | `distinct_palette_fills=6 fills={'cobalt': 1, 'teal': 1, 'green': 1, 'brown': 1, 'crimson': 1, 'red': 1} cobalt_px=243631 px` | `distinct_palette_fills=>=3 cobalt_px=>0` | `distinct_palette_fills=0 cobalt_px=0 px` |
| `start.grid_metrics` — tile gutter ~7 dp and page margins ~14 dp | **PASS** | `gutter_dp=6.77 left_margin_dp=14.15 right_margin_dp=12.92 tiles=12 dp` | `gutter_dp=7 margin_dp=14` | `gutter_dp=2 margin_dp=2 dp` |
| `start.tile_glyphs` — white glyph pixels inside >= 3 tiles | **PASS** | `tiles_with_white_glyph=11 glyph_px=[4220, 1455, 3468, 3591, 522, 4069, 1932, 1271] tiles` | `tiles_with_white_glyph=>=3` | `0 tiles` |
| `start.square_corners` — tile corners are fill, not black gap | **PASS** | `tiles_all_4_corners_fill=12 tiles_checked=12 fraction=1 fraction` | `fraction=>=0.9` | `0.10 fraction` |

- `start.no_header`: status strip excluded (y < 28 dp); tile labels sit on saturated fill and are not counted
- `start.palette`: fills clustered from saturated pixels, count >= 2000 px at 1/4 area
- `start.grid_metrics`: gaps from 5 adjacent tile pairs
- `start.tile_glyphs`: white = min(R,G,B) >= 200, >= 40 px per tile
- `start.square_corners`: corner sampled 2 px inside the measured tile bbox

### app-list

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `applist.icon_column` — icon column starts at x = 80 dp | **PASS** | `x_dp=80 rows=8 dp` | `x_dp=80` | `x_dp=3 dp` |
| `applist.icon_size` — icon square is 45 dp | **PASS** | `w_dp=44.92 h_dp=44.92 dp` | `size_dp=45` | `size_dp=3 dp` |
| `applist.label_x` — app label starts at 138 dp | **PASS** | `label_x_dp=140.31 rows=8 dp` | `label_x_dp=138` | `label_x_dp=4 dp` |
| `applist.rows` — at least 3 app rows detected | **PASS** | `rows=8 rows` | `rows=>=3` | `0 rows` |
| `applist.contrast` — white text on black contrast >= 4.5:1 | **PASS** | `contrast=17.62 text_rgb=[238, 238, 238] bg_rgb=[0, 0, 0] :1` | `contrast=>=4.5` | `0 :1` |

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
| `settings.title_cap` — title glyph band >= 46 px (lowercase Cyrillic, 58 sp) | **FAIL** | `band_px=0 band_dp=0 implied_size_sp=0 font_scale=1 px` | `band_px=>=46 band_dp=>=28` | `0 px` |
| `settings.rows_white` — rows are #E6E6E6..FFFFFF on black | **PASS** | `median_text_rgb=[255, 255, 255] fraction_>=E6=0.94 black_fraction=0.95 fraction` | `median_text_rgb=#E6E6E6..FFFFFF black_fraction=>=0.70` | `fraction_>=E6=0.75 fraction` |
| `settings.single_title` — exactly one large title text block | **FAIL** | `large_title_blocks=0 blocks=[] blocks` | `large_title_blocks=1` | `0 blocks` |

- `settings.bg_black`: max(R,G,B) <= 12
- `settings.no_banner`: the top band may contain only black, white caps and the title glyphs
- `settings.title_cap`: bbox of the first title glyph run at fontScale 1; lowercase Cyrillic has no caps, so the x-height ratio 0.53 is used for the implied size
- `settings.rows_white`: white pixels of the body row labels
- `settings.single_title`: large = ink band height >= 40 px and width >= 80 px in the top 200 dp

### settings-wifi

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `wifi.switch_accent` — switch track/thumb is accent #0050EF (+/-20) when ON | **PASS** | `on=yes track_rgb=[0, 80, 239] track_delta=[0, 0, 0] thumb_rgb=[255, 255, 255] thumb_delta=255 rgb` | `track_rgb=[0, 80, 239]` | `per_channel=20 rgb` |
| `wifi.no_rounded_cards` — no >90%-wide group card with a corner radius >8 dp | **PASS** | `cards=[{'color': [36, 46, 47], 'x0': 26, 'x1': 693, 'y0': 344, 'y1': 497, 'width_frac': 0.9278, 'radius_px': 0, 'radius_dp': 0.0, 'violates': False}, {'color': [36, 46, 47], 'x0': 26, 'x1': 693, 'y0': 787, 'y1': 1279, 'width_frac': 0.9278, 'radius_px': 0, 'radius_dp': 0.0, 'violates': False}] violations=0 cards` | `violations=0` | `0 cards` |

- `wifi.switch_accent`: track = modal colour of the switch component; thumb = white centre
- `wifi.no_rounded_cards`: radius = rows from the card top until the left edge reaches its widest point

### action-centre

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `ac.focus` — panel is our activity (window focus) | **PASS** | `focus=Window{ef2a641 u0 org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreActivity}` | `focus=org.ztmetro.actioncentre` | `0` |
| `ac.status_row` — top 50 dp status row has non-black pixels | **PASS** | `nonblack_px=2387 px` | `nonblack_px=>=100` | `0 px` |
| `ac.tiles_4x2` — exactly 8 action tiles in a 4x2 grid | **PASS** | `tiles=8 columns=4 rows=2 tiles` | `tiles=8 columns=4 rows=2` | `0 tiles` |
| `ac.tiles_square` — action tiles follow the ztmetro ratio 1.50 (+/-0.06) (85.75 x 57 dp) | **PASS** | `worst_ratio=1.51 worst_ratio_delta=0.01 sizes_dp=['105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2'] ratio` | `w_h_ratio=1.50 +/-0.06` | `0.05 ratio` |
| `ac.tile_active_fill` — active tile fill is #0050EF | **PASS** | `active_tiles=4 active_rgb=[[0, 80, 239], [0, 80, 239], [0, 80, 239], [0, 80, 239]] tiles` | `active_tiles=>=1 rgb=[0, 80, 239]` | `per_channel=10 tiles` |

- `ac.focus`: dumpsys window mCurrentFocus at capture time
- `ac.status_row`: tile pixels inside the band are excluded
- `ac.tiles_4x2`: accent #0050EF or off #1F1F1F fills, w/h >= 100 px
- `ac.tiles_square`: w/h of the measured tile boxes (tokens: 85.75 x 57)
- `ac.tile_active_fill`: modal fill per tile

### lock

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `lock.single_clock` — only one large clock glyph block | **PASS** | `large_blocks=1 block_heights_px=[109] clock_height_dp=67.10 blocks` | `large_blocks=1` | `0 blocks` |
| `lock.no_status_text` — no status-row text in the top 40 dp | **PASS** | `light_px=0 bbox=— px` | `light_px=0` | `max=30 px` |

- `lock.single_clock`: large = white ink band >= 48 dp tall (clock glyphs, not date/notification)
- `lock.no_status_text`: light text/icons on the lock wallpaper

### volume

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `volume.panel_visible` — volume panel is visible (position is ROM-specific) | **PASS** | `panel_top_px=343 panel_center_px=627.50 panel_top_dp=211.10 panel_center_dp=386.20 px` | `panel=visible` | `0 px` |
| `volume.panel_black` — panel background is #000000 (>=30% of the blob, slider colours excluded) | **PASS** | `black_fraction=0.64 modal_rgb=[0, 0, 0] fraction` | `black_fraction=>=0.50` | `0 fraction` |

- `volume.panel_visible`: panel box from the slider/thumb component, expanded to the screen edge
- `volume.panel_black`: max(R,G,B) <= 12 inside the panel box

## Sequence

- WAKEUP, unlock if keyguard, collapse shade
- KEYCODE_HOME; wait 3 s
- KEYCODE_BACK (Start is a no-op; app list returns to Start); wait 0.5 s
- swipe 360 520 -> 360 1120 (scroll Start to the top); wait 0.6 s
- not Start — collapse shade, HOME, BACK, scroll; recapture
- input swipe 650 700 120 700 300 (Start -> app list); wait 2 s
- am start -a android.settings.SETTINGS; wait 4 s
- am start -a android.settings.WIFI_SETTINGS; wait 3 s
- am start -a android.intent.action.MAIN -c android.intent.category.APP_CALENDAR; wait 2 s
- input swipe 360 6 360 400 250 (top-edge strip); wait 2 s
- KEYCODE_SLEEP; wait 1.5 s; KEYCODE_WAKEUP; wait 2 s
- WAKEUP + unlock (required before volume: keyguard would hide Settings)
- am start -a android.settings.SETTINGS; wait 4 s
- KEYCODE_VOLUME_DOWN; wait 1.2 s (down always changes volume)
- KEYCODE_BACK

## Re-run

```sh
/Users/meow/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3 /Users/meow/Projects/redmi-note-5a-ztos/build/android-ux-research/repo/scripts/ux-verify.py
```

