# ztMetro Settings rows — about-style, one walk per pass

Status: **built and statically verified** on `ssh hermes-ubuntu`; **not installed on the
phone** (no adb in this task — the lead owns the device).  `render/settings-about-port.png`
is a design render from the module constants, not a device screenshot.

| item | value |
|---|---|
| module sources | `repo/settings/header-module/` |
| build script | `repo/scripts/build-header-module.sh` |
| signed APK | `evidence/settings-rows/ztmetro-settings-header.apk` |
| APK size / sha256 | 41 968 bytes / `ca04b6b52ebc8dba514dc660f5dbcf624cf92db6ebdd675ea5ea3cb4c73b8a36` |
| build host | `ssh hermes-ubuntu`, workdir `~/ztux/settingsrows`, JDK 17 (`~/android-jdk`), SDK 36 / build-tools 36.0.0 (`~/android-sdk`) |
| package / target | `org.ztmetro.settingsheader`, minSdk 29, targetSdk 36 |
| raw build log | `logs/build-verify.txt`, `logs/build-info.txt` |

## 1. Row look (the target: `refs/ztos-about.png`)

| part | value | source |
|---|---|---|
| label line | **15 sp, `#999999`**, the preference title | deliverable, `metro_row_label_size` / `metro_row_label` |
| value line | **22 sp, `#FFFFFF`**, the preference summary | deliverable, `metro_row_value_size` / `metro_row_value` |
| row without summary | title itself is the **22 sp white value** line; no grey line | deliverable |
| icon / chevron | every `ImageView` inside the row is `GONE` (switches exempt); the title's start inset (≤96 dp) and, without a switch, its end inset are cleared | `RowIcons`, `FlatRows` |
| surface | **`#000000`**, elevation 0, no card, no rounded group fill; group gaps ≤40 dp removed | `FlatRows` |
| spacing | 24 dp page margin left/right (header shares it), 4 dp top/bottom → ~53 dp two-line pitch | `metro_row_padding_h/v` |
| switch | track **`#0050EF` opaque**, thumb `#FFFFFF` | `MetroTinter` |

## 2. Page chrome (kept on home **and** sub-screens)

- `SETTINGS` caps label: **15 sp, 0.08 tracking, `#FFFFFF`**, 24 dp from the left.
- page title: **58 sp `sans-serif-light`**, lowercased `Activity.getTitle()`.
- both on flat black; header is attached to `content_frame` (sub-screens) and
  `homepage_container` (home), with a vertical-LinearLayout fallback and a home
  self-heal on `onResume`; the home title falls back to `settings` if the activity has none.
- status-bar inset: home page only (unchanged).  Bottom bar: still suppressed on hardware
  keys via `config_showNavigationBar` (unchanged).

## 3. One traversal per pass, two passes per screen

| | before | after |
|---|---|---|
| walks per pass | 4 (`MetroTinter`, `RowIcons`, `FlatRows`, `RowType`) | **1** (`ScreenPass`) |
| passes per kick | 3 (0 / 150 / 600 ms) + attach's extra `MetroTinter` | **2** (`attach`, one `post-layout` at 150 ms) |
| walks per screen open | 12–13 per kick, up to ~26 across attach + onResume + setTitle | **2** |

`ScreenPass` dispatches per view in one loop: protected container → descend; preference row
→ flatten + style its subtree inline and never revisit it; title-bearing group → black card
cleanup + descend.  Classification is allocation-free: id→name `SparseArray`, class→flags
`HashMap<Class,…>` (cold only), `android.R.id.title`/`summary` compared as `int`, title
counting capped at 2; walk/row stacks are static reusable arrays; `int[] count`/`String[]`
per view are gone; reflection `Method`s, `ColorStateList`s and the varargs `Object[]` are
cached/reused.

Proof line, one per pass (`logcat -s ztmetro-header`):

```
pass 1/2 attach: N views, M applies, T ms
pass 2/2 post-layout: N views, M applies, T ms
```

`onResume`/`setTitle` arriving while the 150 ms pass is queued joins it instead of adding a
third walk; a kick within 250 ms of pass 2/2 is ignored.

## 4. Static verification (`logs/build-verify.txt`)

- `aapt2 compile/link` succeeded; resource dump shows `color/metro_row_label` `#ff999999`,
  `color/metro_row_value` `#ffffffff`, `color/metro_switch_track` `#ff0050ef`,
  `color/metro_header_label` `#ffffffff`, `dimen/metro_row_label_size`,
  `dimen/metro_row_value_size`, `dimen/metro_row_padding_*`.
- `dexdump` class list includes `Lorg/ztmetro/settingsheader/ScreenPass;` and the other 14
  module classes (no old walker entry points); the ` views, ` / ` applies` log strings are
  in `classes.dex`.
- `apksigner verify` + `aapt2 dump badging`: package `org.ztmetro.settingsheader`,
  targetSdk 36, `assets/xposed_init`, `xposedmodule`/`xposedminversion=93` present.

## 5. What is still stock / unverified

- **Device acceptance is owed**: install, scroll-to-last-row, Wi-Fi switch, back/search, and
  the log line counts must be captured by the lead (no adb here).
- Rows the heuristic rejects stay stock: a `ViewGroup` with >8 children or >220 dp tall is a
  container, not a row; a page-height group keeps its margins (its grey fill is removed).
- Dashboard suggestion/contextual-card blocks with no preference title keep their own
  drawable; dialogs/helper activities in the Settings process are untouched.
- Data inflated after the 150 ms pass is restyled on the next `onResume`/`setTitle` pair
  (coalesced), not by a third fixed delay.
