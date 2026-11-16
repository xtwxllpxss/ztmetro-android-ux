# ztMetro Settings depth — search, dialogs, two-state rows, segmented/radio, app info

Status: **built and statically verified** on `ssh hermes-ubuntu`; **not installed on the
phone** (no adb in this task — the lead owns the device).  The APK is the deliverable:

| item | value |
|---|---|
| APK | [`ztmetro-settings-header.apk`](ztmetro-settings-header.apk) |
| size / sha256 | 50 160 bytes / `34eb8e54d0437a4504927ad7471b77e405d345956b7bf04f307dc94a92a13552` |
| build host / workdir | `ssh hermes-ubuntu`, `~/ztux/settingsdepth/repo` |
| toolchain | SDK 36 / build-tools 36.0.0, JDK 17 (`~/android-jdk`), Xposed API 82 (compile only) |
| build log | [`logs/build-verify.txt`](logs/build-verify.txt), [`logs/build-info.txt`](logs/build-info.txt) |
| module scope | `com.android.settings` **and** `com.android.settings.intelligence` |

## 1. What is now covered

| surface | implementation | what the screen gets |
|---|---|---|
| **Search results** (`com.android.settings.intelligence` `SearchActivity` / `SearchResultsAdapter` rows) | new `SearchSurface`; rows go through the **existing** `ScreenPass` walk + its `OnHierarchyChangeListener` (late rows → `lightRow`). `search_bar` card flattened, list padding dropped to the 24 dp page margin. Bottom-bar search now opens `android.settings.APP_SEARCH_SETTINGS` (old intent is fallback). | black window, no search bar card, no result icon, no breadcrumb, grey 15 sp label + white 22 sp value (value may wrap to 2 lines) |
| **Alert dialogs opened from Settings** | new `DialogStyler`, hooked once at `android.app.Dialog#show`; targeted ids only (`alertTitle`, `button1..3`, `select_dialog_listview`) | black window, **0 corner radius**, white **20 sp** title (`metro_dialog_title_size`), `#0050EF` buttons, white list labels + accent radio dots; `FLAG_DIM_BEHIND` / `backgroundDimAmount` untouched (standard dim) |
| **Switch / two-state rows** | `TwoStateRow` (state text + `CompoundButton.setChecked` and `TextView.setText(CharSequence)` sync) + `MetroTinter` (track/thumb) | value line is **ВКЛ/ВЫКЛ, white 22 sp**; track `#0050EF`, thumb `#FFFFFF`; one state line is always present, so toggling does not add/remove a line (height stable) |
| **Segmented buttons** (`MaterialButtonToggleGroup`, `SegmentedButtonPreference`) | `SegmentedStyler`, dispatched inline from the one walk | square (radius 0), black fill, grey 1 dp outline, **accent `#0050EF` when checked**, white label |
| **Radio / checkbox groups** | `MetroTinter` radio/checkbox branch (`buttonTintList`: checked accent, unchecked `#999999`); dialog list `CheckedTextView` check mark accented | black square rows, accent selection dot, white option label |
| **App info page** (`AppInfoDashboardFragment`) | `Views.isTitle/isSummary` now recognise `entity_header_title` / `entity_header_summary` / `install_type` / `entity_header_second_summary`; `Views.isEntityHeader` also detects a wrapper; `ScreenPass.finishRow` renders the entity block as a row | Metro page header (unchanged hook), app name **white 22 sp**, version **grey 15 sp**, app icon hidden, left aligned at the 24 dp margin, followed by the normal grey-label/white-value rows |

`app_info_settings.xml` puts the header in a `LayoutPreference` with
`android:layout=@layout/settings_entity_header`, so the block is a real RecyclerView item.
Its title was **not** an `@android:id/title`, which is exactly why the old `RowType`
heuristic skipped it; the new ids make `titleCount(...) == 1` and `isRowCandidate` accepts
it (2 direct children, < 220 dp).

## 2. Single traversal preserved (requirement 6)

There is still exactly **one** walker, `ScreenPass`:

- Settings screens: the existing 2 passes (`attach`, one post-layout) — the new surfaces
  are dispatched *inside* that walk (segmented group, search bar card) or after it by the
  per-view helpers (`RowType`, `FlatRows`, `RowIcons`, `MetroTinter`, `TwoStateRow`).
- Search: **one** attach pass (`pass 1/1 attach [search]`); rows added while typing are
  styled by the hierarchy listener that `ScreenPass.trackRecycler` already installed. No
  adapter hook and no second tree walk.
- Dialogs: not part of the Activity tree — targeted id lookups on the dialog decor, no walk.

Per-surface proof lines (see [`logs/expected-logcat.txt`](logs/expected-logcat.txt)):

```
pass 1/2 attach [settings]: N views, M applies, T ms (rows=…, switch=…, entity=…, segmented=…)
surface switch [settings]: … / surface appinfo [settings]: … / surface segmented [settings]: …
pass 1/1 attach [search]: … / row pass [search]: … / surface search: …
dialog [dialog]: N applies, title=20sp white, corners=0, dim=theme default
```

## 3. Static verification

- Build gates (`scripts/build-header-module.sh`, green in `logs/build-verify.txt`): package
  `org.ztmetro.settingsheader`, targetSdk 36, `xposedmodule`/`xposedminversion`,
  `assets/xposed_init`, `#ff0050ef`, row/dialog dimens, switch colours, **both scope
  packages**, the constant-size checks from the perf pass (one-shot global-layout listener,
  weak recyclers, `metro_style` tag, no `postDelayed` in `ScreenPass`), all four new classes
  in `classes.dex`, `ВКЛ`/`ВЫКЛ`, the surface proof strings, and the surface anchors
  (`entity_header_title`, `search_bar`, `breadcrumb`, `button_group`,
  `materialbuttontogglegroup`, `alertTitle`).
- DEX hook evidence: [`logs/hook-targets.txt`](logs/hook-targets.txt) — `Dialog`/`show`,
  `CompoundButton`/`setChecked`, `TextView`/`setText`, `SearchActivity`, both search
  package/action strings.
- Upstream anchors used for the heuristics (commit + exact layout lines):
  [`logs/surface-anchors.txt`](logs/surface-anchors.txt).
- Full dexdump: [`logs/dexdump.txt`](logs/dexdump.txt).

## 4. Install / scope (the lead must do this)

The search list lives in **a different package** on LineageOS 23.2.  LSPosed will only load
the module there if the scope is enabled:

1. Install `ztmetro-settings-header.apk` (dev key: uninstall the previous build first).
2. LSPosed → Modules → *ztMetro Settings* → **Scope**: enable both
   `com.android.settings` **and** `com.android.settings.intelligence`.
3. Force-stop both processes (`am force-stop com.android.settings.intelligence` /
   `com.android.settings`) so the hooks install.

## 5. Exact screens to open

1. **Search**: `adb shell am start -a android.settings.APP_SEARCH_SETTINGS -p com.android.settings.intelligence`,
   then type `wifi` (`adb shell input text wifi`). Expect black list, no result icons, no
   breadcrumbs, 15 sp grey title + 22 sp white summary.
   (On a device without hardware keys the bottom-bar search glyph opens the same activity.)
2. **Wi-Fi switch + dialog**: `am start -a android.settings.WIFI_SETTINGS`. The Wi-Fi row
   must read grey `Wi-Fi` + white `ВКЛ`/`ВЫКЛ` with the cobalt/white toggle; tap a saved or
   connected network → dialog must be black, square, 20 sp white title, accent buttons.
   The dialog's custom body (edit fields, checkboxes) is stock.
3. **App info**: `am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.android.settings`.
   Expect `SETTINGS / app info` header, then the app name in white 22 sp + version in grey
   15 sp (no centred icon block), then the usual two-line rows.
4. **Other Settings dialogs**: e.g. Wi-Fi network → Forget, or a Bluetooth saved device →
   *Remove device* (same black/square/20 sp/accent treatment).
5. **Segmented (only reachable screen on this build)**: Connected devices → a Bluetooth
   device details page that exposes configurable options (LE audio/quality); the
   `SegmentedButtonPreference` group must be square/black with the accent checked segment.
6. **Radio group**: a single-choice dialog, e.g. Network & internet → SIMs → *Preferred
   network type*: white labels, accent dots (the glyph stays the system circle; only its
   tint and the row surface are Metro).

Log check: `adb logcat -s ztmetro-header` — expect the `pass … [settings]`, `surface …`,
`row pass [search]` and `dialog [dialog]` lines above.

## 6. Still stock and why

- **Search page header / bottom bar** — `SearchActivity` belongs to the separate
  SettingsIntelligence package and does not use the Settings `content_frame`/header hook;
  requirement 1 was the result rows, which are covered. (Bottom-bar search now *opens*
  this activity.)
- **Custom dialog bodies** (Wi-Fi/VPN edit fields, spinners, in-dialog checkboxes) — the
  dialog hook restyles the window, title, buttons and single-choice list only; styling
  every custom dialog layout would need per-layout code.
- **App info action buttons** (*Uninstall* / *Force stop*, `ActionButtonsPreference`) —
  Material pill buttons, not rows; requirement 5 asked for the header + rows.
- **Radio/checkbox glyph shape** — the system circular glyph is tinted (accent/grey); a
  square glyph would need a custom drawable replacing `buttonTintList`.
- **Switch rows without a stock `summary`** — there is no value line to write state into;
  the row name stays the 22 sp white line rather than inventing a TextView inside
  Settings' preference layout (all switch rows on the target screens have a summary).
- **Compose/SPA settings pages** — a View-tree Xposed module cannot restyle Compose
  content; none of the required surfaces are Compose on this build (`AppInfoDashboardFragment`
  and SettingsIntelligence search are classic Views).
- Search stays entirely stock unless LSPosed scope for `com.android.settings.intelligence`
  is enabled (see §4).

## Files

Module sources: `repo/settings/header-module/src/org/ztmetro/settingsheader/`
(`ScreenPass`, `Views`, `MetroTinter`, `FlatRows`, `RowType`, new `TwoStateRow`,
`SegmentedStyler`, `DialogStyler`, `SearchSurface`, `ModuleEntry`, `SettingsHeaderHooks`,
`BottomBar`), generated dialog dimen in `tools/gen_header_res.py`,
`res/values/arrays.xml` (scope), `README.md`.  Build gates: `repo/scripts/build-header-module.sh`.
