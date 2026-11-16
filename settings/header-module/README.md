# ztMetro Settings — LSPosed module

Turns the **stock** `com.android.settings` on LineageOS 23.2 / Android 16 into the ztmetro
Settings: flat black page header (`SETTINGS` caps label at 15 sp / 0.08 tracking / #FFFFFF,
58 sp lowercase page title), about-style two-line rows (15 sp grey label + 22 sp white
value, no icon, no chevron, no card) and the signature 40 dp **back / ZRN / search** bottom
bar — without touching the system APK.

Reference frames: `repo/evidence/ux-compare/refs/ztos-about.png`,
`ztos-networking.png` and the 3-way comparison `cmp-settings-3way.png`.  The container map
is [`repo/design/settings-container-map.md`](../../design/settings-container-map.md); the
hand-off, guard list and install commands are in
[`repo/evidence/settings-rows/REPORT.md`](../../evidence/settings-rows/REPORT.md) (the
about-style rows / one-pass build) and
[`repo/evidence/settings-header-ztmetro/REPORT.md`](../../evidence/settings-header-ztmetro/REPORT.md)
(the earlier header/container work).

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
| `src/.../HeaderBinder.java` | index-0 insertion into `content_frame` / `homepage_container` (with fallback), header height offset, stock chrome hiding, title sync, home self-heal on resume |
| `src/.../HeaderTitles.java` | title = lowercased `Activity.getTitle()` with ALL-CAPS tokens kept; subtitle = last distinct back-stack breadcrumb, else `ztMetro` |
| `src/.../HeaderViewFactory.java` | inflate/bind the header and bottom bar from module resources |
| `src/.../HeaderResources.java` | module `Resources` via createPackageContext → getResourcesForApplication → AssetManager |
| `src/.../ModuleContext.java` | context wrapper so the inflater resolves module resources only |
| `src/.../BottomBar.java` | 40 dp back/ZRN/search bar in the window decor + content padding so no row is covered |
| `src/.../ScreenPass.java` | **the one walk**: visits the content tree once per pass, dispatches per view (row / group / leaf), caches ids/classes, logs `pass n/2 …: N views, M applies` |
| `src/.../RowType.java` | per-view row typography (no walk): 15 sp #999999 label, 22 sp #FFFFFF value, title-as-value when no summary |
| `src/.../RowIcons.java` | per-view icon/chevron removal inside a row (no walk), switches exempt |
| `src/.../FlatRows.java` | per-row/group flattening (no walk): black surface, elevation 0, 24 dp page margin, no chevron slot |
| `src/.../ScreenStyler.java` | black window chrome + exactly two passes: `attach` and one `post-layout` at 150 ms, coalesced per Activity |
| `src/.../Views.java` | allocation-free id/class classification, title counting, protected-container list |
| `src/.../MetroTinter.java` | per-view switch tint (no walk): #0050EF opaque track, white thumb, cached reflection/colours |
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
  `header attached to …`, `bottom bar attached …`, the per-pass proof
  `pass 1/2 attach: N views, M applies, T ms` / `pass 2/2 post-layout: …`, and the failure
  lines `… (Settings stays stock)` / `… (stock rows kept)`.
- The bottom bar's search glyph starts the stock Settings activity with
  `action=android.settings.SETTINGS` plus the boolean extra `search`; if nothing resolves
  the intent the click is a logged no-op.
- Debug builds use a fresh dev key per build directory: **uninstall before install**
  (see the REPORT for the exact commands).
