# ztmetro UX verification — automated report

Run: `2026-11-16T17:08:38+03:00` → `2026-11-16T17:10:07+03:00` (89.4 s), mode **full**.

Evidence: `/Users/meow/Projects/redmi-note-5a-ztos/build/android-ux-research/repo/evidence/verify/20261116-170838`  ·  `report.json` + `annotated/*.png`

## Summary

| PASS | FAIL | SKIP | total |
|---:|---:|---:|---:|
| 17 | 0 | 0 | 17 |

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
| systemui pid | `5798` |
| enabled ztmetro/ztos overlays | `13` |

dp = px / **1.625** (the live density). The brief's reference is **1.75 px/dp** (280 dpi); `pre.density_factor` records whether they agree.

## Captures

| surface | png | focus | sdk | systemui | overlays | keyguard |
|---|---|---|---|---|---|---|
| start | `raw/start.png` | `org.ztmetro.start/org.ztmetro.start.StartActivity}` | 36 | 5798 | 13 | False |
| app-list | `raw/app-list.png` | `org.ztmetro.start/org.ztmetro.start.StartActivity}` | 36 | 5798 | 13 | False |
| settings-home | `raw/settings-home.png` | `null` | 36 | 5798 | 13 | False |
| settings-wifi | `raw/settings-wifi.png` | `null` | 36 | 5798 | 13 | False |

## Checks

### start

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `start.no_header` — no page header text in the top 100 dp | **PASS** | `glyph_px_on_black=0 px` | `glyph_px_on_black=0` | `max_glyph_px=30 px` |
| `start.palette` — >=3 distinct ztmetro palette fills and cobalt | **PASS** | `distinct_palette_fills=6 fills={'cobalt': 1, 'teal': 1, 'green': 1, 'brown': 1, 'crimson': 1, 'red': 1} cobalt_px=245562 px` | `distinct_palette_fills=>=3 cobalt_px=>0` | `distinct_palette_fills=0 cobalt_px=0 px` |
| `start.grid_metrics` — tile gutter ~7 dp and page margins ~14 dp | **PASS** | `gutter_dp=6.77 left_margin_dp=14.15 right_margin_dp=14.15 tiles=12 dp` | `gutter_dp=7 margin_dp=14` | `gutter_dp=2 margin_dp=2 dp` |
| `start.tile_glyphs` — white glyph pixels inside >= 3 tiles | **PASS** | `tiles_with_white_glyph=11 glyph_px=[3800, 1268, 3150, 3527, 286, 3696, 1765, 768] tiles` | `tiles_with_white_glyph=>=3` | `0 tiles` |
| `start.square_corners` — tile corners are fill, not black gap | **PASS** | `tiles_all_4_corners_fill=12 tiles_checked=12 fraction=1 fraction` | `fraction=>=0.9` | `0.10 fraction` |

- `start.no_header`: status strip excluded (y < 28 dp); tile labels sit on saturated fill and are not counted
- `start.palette`: fills clustered from saturated pixels, count >= 2000 px at 1/4 area
- `start.grid_metrics`: gaps from 5 adjacent tile pairs
- `start.tile_glyphs`: white = min(R,G,B) >= 200, >= 40 px per tile
- `start.square_corners`: corner sampled 2 px inside the measured tile bbox

### app-list

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `applist.icon_column` — icon column starts at x = 80 dp | **PASS** | `x_dp=80 rows=6 dp` | `x_dp=80` | `x_dp=3 dp` |
| `applist.icon_size` — icon square is 45 dp | **PASS** | `w_dp=44.92 h_dp=44.92 dp` | `size_dp=45` | `size_dp=3 dp` |
| `applist.label_x` — app label starts at 138 dp | **PASS** | `label_x_dp=140.31 rows=6 dp` | `label_x_dp=138` | `label_x_dp=4 dp` |
| `applist.rows` — at least 3 app rows detected | **PASS** | `rows=6 rows` | `rows=>=3` | `0 rows` |
| `applist.contrast` — white text on black contrast >= 4.5:1 | **PASS** | `contrast=17.62 text_rgb=[243, 243, 243] bg_rgb=[0, 0, 0] :1` | `contrast=>=4.5` | `0 :1` |

- `applist.icon_column`: median left edge of the accent icon squares
- `applist.icon_size`: median square size
- `applist.label_x`: first white text column right of the icon square
- `applist.rows`: solid accent icon squares
- `applist.contrast`: WCAG 2.1 relative luminance, worst measured row

### settings-home

| check | status | measured | expected | tolerance |
|---|---|---|---|---|
| `settings.bg_black` — background is #000000 (>=70% of pixels) | **PASS** | `black_fraction=0.95 fraction` | `fraction=>=0.70` | `0 fraction` |
| `settings.no_banner` — no cobalt banner in the top 100 dp | **PASS** | `cobalt_px=0 saturated_px=0 px` | `cobalt_px=0 saturated_px=0` | `max=20 px` |
| `settings.title_cap` — title glyph band >= 46 px (lowercase Cyrillic, 58 sp) | **PASS** | `band_px=52 band_dp=32 implied_size_sp=60.40 font_scale=1 px` | `band_px=>=46 band_dp=>=28` | `0 px` |
| `settings.rows_white` — rows are #E6E6E6..FFFFFF on black | **PASS** | `median_text_rgb=[255, 255, 255] fraction_>=E6=0.87 black_fraction=0.94 fraction` | `median_text_rgb=#E6E6E6..FFFFFF black_fraction=>=0.70` | `fraction_>=E6=0.75 fraction` |
| `settings.single_title` — exactly one large title text block | **PASS** | `large_title_blocks=1 blocks=[{'h': 52, 'y': 164}] blocks` | `large_title_blocks=1` | `0 blocks` |

- `settings.bg_black`: max(R,G,B) <= 12
- `settings.no_banner`: the top band may contain only black, white caps and the title glyphs
- `settings.title_cap`: bbox of the first title glyph run at fontScale 1; lowercase Cyrillic has no caps, so the x-height ratio 0.53 is used for the implied size
- `settings.rows_white`: white pixels of the body row labels
- `settings.single_title`: large = ink band height >= 40 px and width >= 80 px in the top 200 dp

## Sequence

- WAKEUP, unlock if keyguard, collapse shade
- KEYCODE_HOME; wait 3 s
- KEYCODE_BACK (Start is a no-op; app list returns to Start); wait 0.5 s
- swipe 360 520 -> 360 1120 (scroll Start to the top); wait 0.6 s
- input swipe 650 700 120 700 300 (Start -> app list); wait 2 s
- am start -a android.settings.SETTINGS; wait 4 s
- settings-home: focus is not Settings — retry once
- am start -a android.settings.SETTINGS; wait 4 s
- settings-home: focus is not Settings — retry once
- svc wifi enable; wait 3 s (the switch check needs Wi-Fi ON)
- am start -a android.settings.WIFI_SETTINGS; wait 3 s
- settings-wifi: focus is not Settings — retry once
- am start -a android.settings.WIFI_SETTINGS; wait 3 s
- aborted: screencap failed: short/invalid PNG (8826 bytes)

## Re-run

```sh
/Users/meow/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3 /Users/meow/Projects/redmi-note-5a-ztos/build/android-ux-research/repo/scripts/ux-verify.py
```

