# ztMetro Settings header — LSPosed module

Adds the Metro page header (accent block, large light title, 70 %-opacity subtitle) to the
**stock** `com.android.settings` on LineageOS 23.2 / Android 16, without touching the
system APK.  The design rationale, hook line numbers and device checklist live in
[`repo/evidence/settings-header/REPORT.md`](../../evidence/settings-header/REPORT.md); the
container map is [`repo/design/settings-container-map.md`](../../design/settings-container-map.md).

## Build

From the repo root (needs an Android SDK with `platforms/android-36`, build-tools with
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
signing keystore and `verify.txt`.  No Gradle, no network after the first download.

## Sources

| path | role |
|---|---|
| `AndroidManifest.xml` | `xposedmodule` / `xposeddescription` / `xposedminversion=93` / `xposedscope`; no components, no permissions |
| `assets/xposed_init` | legacy LSPosed entry point: `org.ztmetro.settingsheader.ModuleEntry` |
| `src/.../ModuleEntry.java` | `IXposedHookLoadPackage`, filters `com.android.settings` |
| `src/.../SettingsHeaderHooks.java` | the four hooks, each guarded by try/catch |
| `src/.../HeaderBinder.java` | index-0 insertion into `content_frame` / `homepage_container`, idempotency, title sync |
| `src/.../HeaderTitles.java` | title = `Activity.getTitle()`; subtitle = last distinct back-stack breadcrumb, else `ztMetro` |
| `src/.../HeaderResources.java` | module `Resources` via createPackageContext → getResourcesForApplication → AssetManager |
| `src/.../ModuleContext.java` | context wrapper so the inflater resolves module resources only |
| `src/.../HeaderViewFactory.java` | inflate + bind the header, springs its height from the generated dimens |
| `res/layout/metro_header.xml` | the header view (tag `ztmetro_header`) |
| `res/drawable/metro_header_bg.xml` | accent shape drawable (module-owned) |
| `res/values/metro_header_*.xml` | **generated** from `design/tokens/ztmetro.tokens.json` |
| `tools/gen_header_res.py` | token → res generator (also `--check`) |

The generated files must not be edited by hand: change the token JSON (or the explicitly
sourced module spec inside the generator) and re-run
`python3 settings/header-module/tools/gen_header_res.py`.

## Runtime notes

- Scope is `com.android.settings`; the module does nothing in any other process.
- Image/state: header absent ⇒ stock Settings, by construction (hooks only add a view and
  every failure is logged, never rethrown).
- Logcat tag: `ztmetro-header`; the first attach names the resource mechanism that worked
  (`package-context`, `package-resources` or `asset-manager`) — quote that line when
  reporting device results.
