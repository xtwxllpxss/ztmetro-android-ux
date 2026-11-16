# ztmetro UX verification — automated report

Run: `2026-11-16T15:09:47+03:00` → `2026-11-16T15:11:29+03:00` (101.8 s), mode **full**.

Evidence: `/Users/meow/Projects/redmi-note-5a-ztos/build/android-ux-research/repo/evidence/verify/20261116-150947`  ·  `report.json` + `annotated/*.png`

## Summary

| PASS | FAIL | SKIP | total |
|---:|---:|---:|---:|
| 20 | 7 | 1 | 28 |

### Failing checks

- **settings.bg_black** — background is #000000 (>=70% of pixels): `black_fraction=0.03` (expected `fraction=>=0.70`, tol `0` fraction)
- **settings.title_cap** — page title cap-height >= 60 px (34 dp): `cap_height_px=0 cap_height_dp=0 implied_size_sp=0 font_scale=1` (expected `cap_height_px=>=60 cap_height_dp=>=34`, tol `0` px)
- **settings.rows_white** — rows are #E6E6E6..FFFFFF on black: `median_text_rgb=[221, 231, 233] fraction_>=E6=0.27 black_fraction=0` (expected `median_text_rgb=#E6E6E6..FFFFFF black_fraction=>=0.70`, tol `fraction_>=E6=0.75` fraction)
- **settings.single_title** — exactly one large title text block: `large_title_blocks=0 blocks=[]` (expected `large_title_blocks=1`, tol `0` blocks)
- **wifi.switch_accent** — switch track/thumb is accent #0050EF (+/-20) when ON: `on=no track_rgb=[156, 207, 215] track_delta=[156, 127, 24] thumb_rgb=— thumb_delta=—` (expected `track_rgb=[0, 80, 239]`, tol `per_channel=20` rgb)
- **ac.tiles_square** — action tiles follow the ztmetro ratio 0.66 (+/-0.05): `worst_wh_delta_dp=35.69 sizes_dp=['105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2']` (expected `w_h_ratio=0.66 +/-0.05`, tol `0.05` ratio)
- **volume.panel_visible** — volume panel appears in the lower third: `panel=absent` (expected `panel=visible`, tol `0` )

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
| settings-home | `raw/settings-home.png` | `com.android.settings/com.android.settings.homepage.SettingsHomepageActivity}` | 36 | 2082 | 9 | False |
| settings-wifi | `raw/settings-wifi.png` | `com.android.settings/com.android.settings.Settings$WifiSettingsActivity}` | 36 | 2082 | 9 | False |
| action-centre | `raw/action-centre.png` | `org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreActivity}` | 36 | 2082 | 9 | False |
| lock | `raw/lock.png` | `NotificationShade}` | 36 | 2082 | 9 | True |
| volume | `raw/volume.png` | `com.android.settings/com.android.settings.homepage.SettingsHomepageActivity}` | 36 | 2082 | 9 | False |

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
| `applist.icon_column` — icon column starts at x = 80 dp | **PASS** | `x_dp=80 rows=9 dp` | `x_dp=80` | `x_dp=3 dp` |
| `applist.icon_size` — icon square is 45 dp | **PASS** | `w_dp=44.92 h_dp=44.92 dp` | `size_dp=45` | `size_dp=3 dp` |
| `applist.label_x` — app label starts at 138 dp | **PASS** | `label_x_dp=140.31 rows=8 dp` | `label_x_dp=138` | `label_x_dp=4 dp` |
| `applist.rows` — at least 3 app rows detected | **PASS** | `rows=9 rows` | `rows=>=3` | `0 rows` |
| `applist.contrast` — white text on black contrast >= 4.5:1 | **PASS** | `contrast=17.62 text_rgb=[243, 243, 243] bg_rgb=[0, 0, 0] :1` | `contrast=>=4.5` | `0 :1` |

- `applist.icon_column`: median left edge of the accent icon squares
- `applist.icon_size`: median square size
- `applist.label_x`: first white text column right of the icon square
- `applist.rows`: solid accent icon squares
- `applist.contrast`: WCAG 2.1 relative luminance, worst measured row

### settings-home

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `settings.bg_black` — background is #000000 (>=70% of pixels) | **FAIL** | `black_fraction=0.03 fraction` | `fraction=>=0.70` | `0 fraction` |
| `settings.no_banner` — no cobalt banner in the top 100 dp | **PASS** | `cobalt_px=0 saturated_px=0 px` | `cobalt_px=0 saturated_px=0` | `max=20 px` |
| `settings.title_cap` — page title cap-height >= 60 px (34 dp) | **FAIL** | `cap_height_px=0 cap_height_dp=0 implied_size_sp=0 font_scale=1 px` | `cap_height_px=>=60 cap_height_dp=>=34` | `0 px` |
| `settings.rows_white` — rows are #E6E6E6..FFFFFF on black | **FAIL** | `median_text_rgb=[221, 231, 233] fraction_>=E6=0.27 black_fraction=0 fraction` | `median_text_rgb=#E6E6E6..FFFFFF black_fraction=>=0.70` | `fraction_>=E6=0.75 fraction` |
| `settings.single_title` — exactly one large title text block | **FAIL** | `large_title_blocks=0 blocks=[] blocks` | `large_title_blocks=1` | `0 blocks` |

- `settings.bg_black`: max(R,G,B) <= 12
- `settings.no_banner`: the top band may contain only black, white caps and the title glyphs
- `settings.title_cap`: cap height = bbox of the first (uppercase) title glyph at fontScale 1; implied size uses the Open Sans cap ratio 0.714 from the token notes
- `settings.rows_white`: white pixels of the body row labels
- `settings.single_title`: large = ink band height >= 40 px and width >= 80 px in the top 200 dp

### settings-wifi

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `wifi.switch_accent` — switch track/thumb is accent #0050EF (+/-20) when ON | **FAIL** | `on=no track_rgb=[156, 207, 215] track_delta=[156, 127, 24] thumb_rgb=— thumb_delta=— rgb` | `track_rgb=[0, 80, 239]` | `per_channel=20 rgb` |
| `wifi.no_rounded_cards` — no >90%-wide group card with a corner radius >8 dp | **PASS** | `cards=[{'color': [19, 27, 28], 'x0': 0, 'x1': 719, 'y0': 46, 'y1': 224, 'width_frac': 1.0, 'radius_px': 0, 'radius_dp': 0.0, 'violates': False}, {'color': [36, 46, 47], 'x0': 26, 'x1': 693, 'y0': 225, 'y1': 378, 'width_frac': 0.9278, 'radius_px': 0, 'radius_dp': 0.0, 'violates': False}, {'color': [19, 27, 28], 'x0': 0, 'x1': 719, 'y0': 379, 'y1': 464, 'width_frac': 1.0, 'radius_px': 0, 'radius_dp': 0.0, 'violates': False}, {'color': [36, 46, 47], 'x0': 26, 'x1': 693, 'y0': 465, 'y1': 519, 'width_frac': 0.9278, 'radius_px': 0, 'radius_dp': 0.0, 'violates': False}, {'color': [36, 46, 47], 'x0': 26, 'x1': 693, 'y0': 534, 'y1': 581, 'width_frac': 0.9278, 'radius_px': 0, 'radius_dp': 0.0, 'violates': False}, {'color': [19, 27, 28], 'x0': 0, 'x1': 719, 'y0': 582, 'y1': 667, 'width_frac': 1.0, 'radius_px': 0, 'radius_dp': 0.0, 'violates': False}, {'color': [36, 46, 47], 'x0': 26, 'x1': 693, 'y0': 668, 'y1': 784, 'width_frac': 0.9278, 'radius_px': 0, 'radius_dp': 0.0, 'violates': False}, {'color': [36, 46, 47], 'x0': 26, 'x1': 693, 'y0': 788, 'y1': 904, 'width_frac': 0.9278, 'radius_px': 3, 'radius_dp': 1.85, 'violates': False}, {'color': [36, 46, 47], 'x0': 26, 'x1': 693, 'y0': 908, 'y1': 1024, 'width_frac': 0.9278, 'radius_px': 3, 'radius_dp': 1.85, 'violates': False}, {'color': [36, 46, 47], 'x0': 26, 'x1': 693, 'y0': 1028, 'y1': 1144, 'width_frac': 0.9278, 'radius_px': 3, 'radius_dp': 1.85, 'violates': False}, {'color': [36, 46, 47], 'x0': 26, 'x1': 693, 'y0': 1148, 'y1': 1264, 'width_frac': 0.9278, 'radius_px': 3, 'radius_dp': 1.85, 'violates': False}] violations=0 cards` | `violations=0` | `0 cards` |

- `wifi.switch_accent`: track = modal colour of the switch component; thumb = white centre
- `wifi.no_rounded_cards`: radius = rows from the card top until the left edge reaches its widest point

### action-centre

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `ac.focus` — panel is our activity (window focus) | **PASS** | `focus=Window{c2795d7 u0 org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreActivity}` | `focus=org.ztmetro.actioncentre` | `0` |
| `ac.status_row` — top 50 dp status row has non-black pixels | **PASS** | `nonblack_px=2342 px` | `nonblack_px=>=100` | `0 px` |
| `ac.tiles_4x2` — exactly 8 action tiles in a 4x2 grid | **PASS** | `tiles=8 columns=4 rows=2 tiles` | `tiles=8 columns=4 rows=2` | `0 tiles` |
| `ac.tiles_square` — action tiles follow the ztmetro ratio 0.66 (+/-0.05) | **FAIL** | `worst_wh_delta_dp=35.69 sizes_dp=['105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2', '105.8x70.2'] ratio` | `w_h_ratio=0.66 +/-0.05` | `0.05 ratio` |
| `ac.tile_active_fill` — active tile fill is #0050EF | **PASS** | `active_tiles=4 active_rgb=[[0, 80, 239], [0, 80, 239], [0, 80, 239], [0, 80, 239]] tiles` | `active_tiles=>=1 rgb=[0, 80, 239]` | `per_channel=10 tiles` |

- `ac.focus`: dumpsys window mCurrentFocus at capture time
- `ac.status_row`: tile pixels inside the band are excluded
- `ac.tiles_4x2`: accent #0050EF or off #1F1F1F fills, w/h >= 100 px
- `ac.tiles_square`: w/h of the measured tile boxes (tokens: 85.75 x 57)
- `ac.tile_active_fill`: modal fill per tile

### lock

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `lock.single_clock` — only one large clock glyph block | **PASS** | `large_blocks=1 block_heights_px=[108] clock_height_dp=66.50 blocks` | `large_blocks=1` | `0 blocks` |
| `lock.no_status_text` — no status-row text in the top 40 dp | **PASS** | `light_px=0 bbox=— px` | `light_px=0` | `max=30 px` |

- `lock.single_clock`: large = white ink band >= 48 dp tall (clock glyphs, not date/notification)
- `lock.no_status_text`: light text/icons on the lock wallpaper

### volume

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `volume.panel_visible` — volume panel appears in the lower third | **FAIL** | `panel=absent` | `panel=visible` | `0` |
| `volume.panel_black` — panel background is #000000 (>=50% of the blob) | **SKIP** | `panel=absent fraction` | `black_fraction=>=0.5` | `0 fraction` |

- `volume.panel_visible`: no pale-cyan or accent slider component on the right
- `volume.panel_black`: nothing to measure

## Sequence

- WAKEUP, unlock if keyguard, collapse shade
- KEYCODE_HOME; wait 3 s
- KEYCODE_BACK (Start is a no-op; app list returns to Start); wait 0.5 s
- swipe 360 520 -> 360 1120 (scroll Start to the top); wait 0.6 s
- input swipe 650 700 120 700 300 (Start -> app list); wait 2 s
- am start -a android.settings.SETTINGS; wait 4 s
- am start -a android.settings.WIFI_SETTINGS; wait 3 s
- am start -a android.intent.action.MAIN -c android.intent.category.APP_CALENDAR; wait 2 s
- input swipe 360 6 360 400 250 (top-edge strip); wait 2 s
- KEYCODE_SLEEP; wait 1.5 s; KEYCODE_WAKEUP; wait 2 s
- WAKEUP + unlock (required before volume: keyguard would hide Settings)
- am start -a android.settings.SETTINGS; wait 4 s
- KEYCODE_VOLUME_UP; wait 2 s
- KEYCODE_BACK

## Re-run

```sh
/Users/meow/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3 /Users/meow/Projects/redmi-note-5a-ztos/build/android-ux-research/repo/scripts/ux-verify.py
```

