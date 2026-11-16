# ztMetro Settings — LSPosed module

Turns the **stock** `com.android.settings` on LineageOS 23.2 / Android 16 into the ztmetro
Settings: flat black page header (`SETTINGS` caps label, 48 sp lowercase page title,
subtitle), text-first icon-free home rows, flat preference rows instead of cards, and the
signature 40 dp **back / ZRN / search** bottom bar — without touching the system APK.

Reference frames: `repo/evidence/ux-compare/refs/ztos-about.png`,
`ztos-networking.png` and the 3-way comparison `cmp-settings-3way.png`.  The container map
is [`repo/design/settings-container-map.md`](../../design/settings-container-map.md); the
hand-off, guard list and install commands are in
[`repo/evidence/settings-header-ztmetro/REPORT.md`](../../evidence/settings-header-ztmetro/REPORT.md).

## Build

From the repo root (Android SDK with `platforms/android-36`, build-tools with
aapt2/d8/zipalign/apksigner, JDK 17, python3; the Xposed API jar is downloaded once and
sha256-pinned from the official Xposed Maven repository,
`de.robv.android.xposed:api:82`):

```sh
repo/scripts/build-header-module.sh
# APK:    <module>/build/dist/ztmetro-settings-header.apk
# SIZE:   … bytes
# SHA256: …
```

The build directory (`build/`, gitignored) holds the pinned Xposed API jar, the dev
signing keystore and `verify.txt`.  No Gradle, no network after the first download.  The
script is unchanged by the ztmetro-ization: the accent colour stays in the module resource
table (pressed state of the bottom-bar buttons), so its resource assertion still passes.

## Sources

| path | role |
|---|---|
| `AndroidManifest.xml` | `xposedmodule` / `xposeddescription` / `xposedminversion=93` / `xposedscope`; no components, no permissions |
| `assets/xposed_init` | legacy LSPosed entry point: `org.ztmetro.settingsheader.ModuleEntry` |
| `src/.../ModuleEntry.java` | `IXposedHookLoadPackage`, filters `com.android.settings` |
| `src/.../SettingsHeaderHooks.java` | the four hooks (`setContentView(int)`, `setTitle`, `onResume`, `SettingsHomepageActivity.onCreate`), each guarded |
| `src/.../HeaderBinder.java` | index-0 insertion into `content_frame` / `homepage_container`, header height offset, stock chrome hiding, title sync |
| `src/.../HeaderTitles.java` | title = lowercased `Activity.getTitle()` with ALL-CAPS tokens kept; subtitle = last distinct back-stack breadcrumb, else `ztMetro` |
| `src/.../HeaderViewFactory.java` | inflate/bind the header and bottom bar from module resources |
| `src/.../HeaderResources.java` | module `Resources` via createPackageContext → getResourcesForApplication → AssetManager |
| `src/.../ModuleContext.java` | context wrapper so the inflater resolves module resources only |
| `src/.../BottomBar.java` | 40 dp back/ZRN/search bar in the window decor + content padding so no row is covered |
| `src/.../RowIcons.java` | hides dashboard row icons (`GONE`) on the home page only, keeps titles/chevrons/switches |
| `src/.../FlatRows.java` | removes the card look from preference rows (black background, elevation 0, 24 dp page margin, card margins zeroed) |
| `src/.../ScreenStyler.java` | black window chrome + coalesced re-apply passes at 0/200/700/1600 ms |
| `src/.../Views.java` | shared id/class heuristics and the protected-container list |
| `src/.../MetroTinter.java` | tints stock switches/sliders cobalt |
| `res/layout/metro_header.xml` | caps label + title + subtitle, flat black, tag `ztmetro_header` |
| `res/layout/metro_bottom_bar.xml` | back arrow, centred `ZRN`, search glyph |
| `res/drawable/metro_header_bg.xml` | flat black block (no cobalt) |
| `res/drawable/ic_metro_back.xml`, `ic_metro_search.xml` | 24 dp white glyphs |
| `res/values/metro_header_*.xml` | **generated** from `design/tokens/ztmetro.tokens.json` |
| `tools/gen_header_res.py` | token → res generator (also `--check`) |

The generated files must not be edited by hand: change the token JSON (or the explicitly
sourced module spec inside the generator) and re-run
`python3 settings/header-module/tools/gen_header_res.py`.

## Runtime notes

- Scope is `com.android.settings`; the module does nothing in any other process, and only
  activities that carry the `ztmetro_header` tag get the bottom bar/row work (dialogs and
  helper activities stay stock).
- Image/state: header absent ⇒ stock Settings, by construction.  Every hook body is
  try/catch(Throwable) and every view mutation has a containment/whitelist guard.
- The stock toolbar/app bar is hidden, except when Settings shows a Save/Done action
  button: in that case the toolbar stays (painted black, title cleared) so the flow keeps
  working.  Back is always available in the bottom bar.
- Logcat tag: `ztmetro-header`.  Relevant lines: `module resources via …`,
  `header attached to …`, `bottom bar attached …`, `hid N dashboard row icons`,
  `flat rows: N preference rows flattened`, and the failure lines `… (Settings stays
  stock)` / `… (icons kept)` / `… (cards kept)`.
- The bottom bar's search glyph starts the stock Settings activity with
  `action=android.settings.SETTINGS` plus the boolean extra `search`; if nothing resolves
  the intent the click is a logged no-op.
- Debug builds use a fresh dev key per build directory: **uninstall before install**
  (see the REPORT for the exact commands).
