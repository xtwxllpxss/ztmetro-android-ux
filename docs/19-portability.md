# 19 — Portability of the ztMetro Android UX patch series

Scope: move the stack in this repo from **LineageOS-for-microG 23.2 on `ugglite`**
(MSM8917, 720×1280, 280 dpi, 2 GB) to (a) another device on the same
`lineage-23.2` branch and (b) another AOSP-like Android 16 ROM, without a ROM
build and without re-deriving the design.

The machine-readable form of this document is
[`design/portability-matrix.json`](../design/portability-matrix.json)
(19 artifact rows; every row carries the exact check).
Coverage/build commands and the current on-device status are in
[`docs/18-coverage-and-build.md`](18-coverage-and-build.md); the mechanism facts are
in [`docs/10b-overlay-and-hook-facts.md`](10b-overlay-and-hook-facts.md),
[`design/systemui-resource-map.json`](../design/systemui-resource-map.json) and
[`design/settings-container-map.md`](../design/settings-container-map.md).

Snapshot: **2026-11-16 12:20 MSK**, repo commit `69ea589`. Labels:
**[F]** verified in the cited file/commit, **[E]** engineering inference,
**[?]** needs the target device.

---

## 1. Coupling model

Every artifact is put in exactly one class. The class decides what a porter may
reuse verbatim and what must be re-checked.

| class | meaning | portable across |
|---|---|---|
| **a — device independent** | own code/resources, no target resource names, only public Android APIs | any Android 16 device/ROM |
| **b — Android-version coupled** | resource **names/types** in `framework-res`, SystemUI, Settings or SettingsLib, or a file format that changed between API levels | same API level (36) only |
| **c — ROM coupled** | LineageOS-added resources, Lineage fork behaviour, microG build specifics, Magisk/Zygisk/LSPosed injection stack | same ROM family only |
| **d — device coupled** | panel size/density, status-bar height/cutout, boot-image patching, RAM/blur class | one device only |

Counts in the matrix (primary class): **6 a, 6 b, 5 c, 2 d**. Rows that straddle two
classes carry the second one in `coupling_secondary` (the status RROs are also
Android-version coupled; the font module and Settings RRO are also ROM coupled; the
overlays module and boot stack are also device coupled).

The important consequence: **a resource name that does not exist in the target is not
an error at install time.** idmap2 drops the entry; `cmd overlay list` still shows the
overlay enabled; the device simply looks unchanged. The only reliable test is to dump
the *target APK* and compare names — `evidence/portability/check-target-resources.py`
does that and exits non-zero.

---

## 2. Inventory and what breaks

Paths are relative to `repo/`. "Check" is the fast host-side command; the runnable
target-APK form is in §5.3. Where a class cell shows two letters (`b + c`), the first
is the primary class used in the counts and the second is recorded in
`coupling_secondary` in the JSON.

| # | artifact | paths | class | what breaks on another target | check |
|---|---|---|---|---|---|
| 1 | **Start launcher APK** | `launcher/**`, `scripts/build-launcher.sh`, `evidence/launcher-v3/**` | **a** | HOME role and notification-listener grant are per-ROM user actions; the measured grid is tuned for 360–411 dp (4 columns). On a ≥600 dp tablet/landscape the first-fit grid is wider than the design, not broken. Live tiles stay empty until the listener is allowed. | `aapt2 dump badging … \| grep -E "package\|launchable-activity"`; on target `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`; screenshot Start. |
| 2 | **Action Centre APK** | `actioncentre/**`, `scripts/build-actioncentre.sh`, `evidence/actioncentre/**` | **a** | Torch needs `CAMERA` + a flash unit; brightness/rotation need the `WRITE_SETTINGS` appop; the notification listener must be enabled by the user. A ROM without a flash (or with a different torch HAL) makes only the torch tile inert. | `adb shell appops get org.ztmetro.actioncentre WRITE_SETTINGS`; `adb shell cmd notification allow_listener org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreListener`; open panel, toggle each tile. |
| 3 | **Design tokens / generated resources** | `design/tokens/**`, `overlay/framework/gen_overlay.py`, `evidence/*/metro_tokens.generated.xml` | **a** (values) / **d** (reference profile) | Values are dp/sp and therefore density-independent, but `design/tokens/README.md` pins the reference at **720×1280 @ 320 dpi = 360×640 dp**; the shipping ugglite is **280 dpi = 411×731 dp**, and sweet is 440 dpi = 393×873 dp. The Start grid recomputes from width (`MetroGridView.onMeasure`), so the *look* survives; any layout that hardcodes the 360 dp reference will not. | `adb shell wm size; adb shell wm density`; compare with `design/tokens/README.md`; regenerate with `python3 design/tokens/gen_android.py --out /tmp/tokens` and diff. |
| 4 | **Build tooling** | `scripts/*.sh` | **a** | Needs SDK platform 36 + build-tools 36.0.0 + JDK 17; scripts fall back to `ssh hermes-ubuntu` when the SDK is absent. No device paths, no ROM assumptions. `build-overlay-module.sh` *requires* all five evidence APKs to exist first. | `bash -n scripts/*.sh`; `ANDROID_HOME=… JAVA_HOME=… bash scripts/build-overlay-framework.sh --out /tmp/fw`; `bash scripts/build-launcher.sh`. |
| 5 | **framework-res RRO** | `overlay/framework/**`, `scripts/build-overlay-framework.sh`, `evidence/framework-rro/**` | **b** | 92 AOSP-16 names: `accent_*`, `system_accent1/2/3_*`, `TextAppearance.Material.*`, `config_*CornerRadius`, `dialog_corner_radius`, `action_bar_default_height_material`. They are AOSP-stable **at API 36**; an Android 15 base lacks the M3 names, and a ROM with its own static `android` overlay can out-rank this one (priority 2). The framework RRO is `isStatic=true` → cannot be toggled off at runtime. | `check-target-resources.py --overlay-dir overlay/framework --target-apk framework-res.apk`; `cmd overlay list \| grep ztmetro`; `cmd overlay dump org.ztmetro.overlay.framework`. |
| 6 | **SystemUI RRO** | `overlay/systemui/**`, `scripts/build-overlay-sysui.sh`, `evidence/sysui-rro/**` | **b** | 25 names tied to the **2025 shade** (`qs_*`, `notification_corner_radius*`, `shade_header_text_color`, `TextAppearance.QS.Status*`, `shade_panel_fallback`). AOSP 16 has all 25; `lineage-22.2` has no `shade_header_text_color` anywhere in `packages/SystemUI/res` (checked), so the header style's text colour has no target mapping there. `overlay_shade_panel_shape_radius` is **inert at 23.2 (0 consumers)** — it is not evidence the shade geometry works. On a blur-capable device the `*_fallback` colours are not used at all (§4.2). | `check-target-resources.py --overlay-dir overlay/systemui --target-apk SystemUI.apk`; check the four `TextAppearance.QS.*` styles and `qs_corner_radius` in the dump; screenshot shade. |
| 7 | **Settings RRO** | `overlay/settings/**`, `scripts/build-overlay-settings.sh`, `evidence/settings-rro/**` | **b** | 93 source names at the 12:10 snapshot: AOSP-16 Settings names (`homepage_*`, `TextAppearance.*`, `wifi_item*`, radii, `SearchBarStyle_v2`) plus SettingsLib names (`settingslib_*`) and three Material-Components style names. A ROM that rewrites its dashboard (crDroid/PE) may drop `homepage_*`; a pre-16 SettingsLib lacks the expressive radii. The checked-in **`aapt2-dump-resources.txt` is stale** (56 entries, 11:22) while the APK was rebuilt at 12:13 (21,196 B): refresh the dump before comparing (see §6). | `check-target-resources.py --overlay-dir overlay/settings --target-apk Settings.apk`; regenerate the dump from the rebuilt APK first. |
| 8 | **Settings library-only entries** | `overlay/settings/res/values/ztmetro_accent_and_shapes.xml`, `…/values-night/…`, `…/ztmetro_surface_and_type.xml` | **b + c** | Four `primary_material_*` colours are **framework** names and are not declared by Settings/SettingsLib on either Lineage 23.2 or AOSP 16; if the Settings APK does not carry a same-named resource (e.g. merged from AppCompat), those four entries silently no-op on every target. `cardview_default_radius` and `TextAppearance.M3.Sys.Typescale.*` come from AndroidX AARs and can disappear with a library/R8 change. `settingslib_entry_bg_radius` is **Lineage-only** (present at `SettingsLib/MainSwitchPreference/res/values-v31/dimens.xml:39`, absent in AOSP 16) and has no consumer in either tree at this commit. | Inspect the pulled Settings APK for each name: `check-target-resources.py --overlay-dir overlay/settings --target-apk Settings.apk`; source view: `check-source-names.py --overlay settings --framework-tree … --settings-tree …`. |
| 9 | **Status-strip framework RRO** | `overlay/status/framework/**`, `scripts/build-overlay-status.sh`, `evidence/status-strip/**` | **d** | Four entries, including **`status_bar_height*` 24→28 dp**. That is a device decision: sweet's device tree sets 100 px (= 36.36 dp) and a 93 px punch-hole cutout; this RRO would override both to 28 dp. It cannot be shipped unchanged to a device with a cutout or a taller bar. Icon size (`status_bar_system_icon_size` 13 dp) and the SystemUI paddings are portable; the height entries are not. | Dump sweet/device `framework-res.apk` and `adb shell dumpsys window \| grep -iE "status.?bar\|cutout"`; compare `status_bar_height` in the overlay with the device overlay. On the target: screenshot status bar, `adb shell dumpsys window displays`. |
| 10 | **Status-strip SystemUI RRO** | `overlay/status/systemui/**` | **d/b** | Six entries (`status_bar_padding_*`, icon size/alpha, `TextAppearance.StatusBar.Default`, a black-background pin). Padding is device-tuned (ugglite 4→8 dp; sweet's device tree itself uses 10/5 dp and would be overridden). Clock position is a LineageSettings runtime value, not a resource. | `check-target-resources.py --overlay-dir overlay/status/systemui --target-apk SystemUI.apk`; screenshot; compare `status_bar_padding_start` against the device overlay. |
| 11 | **Overlays Magisk module** | `modules/ztmetro-overlays/**`, `scripts/build-overlay-module.sh`, `artifacts/ztmetro-overlays-1.0.0.zip` | **c + d** | Needs Magisk (magic mount + root `cmd overlay`), the `/system/product/overlay` scan path, and a working boot image patch for *that* device. Lineage user builds do not grant `CHANGE_OVERLAY_PACKAGES` to `shell`; the module's `service.sh` enables the runtime overlays as uid 0 at `late_start`. Any ROM without Magisk needs another preinstall route (`adb root` + push on userdebug, or a ROM build). | `adb shell su -c 'magisk -v'`; `adb shell su -c 'cmd overlay list \| grep -E "ztos\|ztmetro"'`; `adb shell su -c 'cat /data/local/tmp/ztmetro-overlays.log'`. |
| 12 | **Font Magisk module** | `modules/ztmetro-font/**`, `fonts/opensans/**`, `scripts/build-font-module.sh`, `artifacts/ztmetro-font-1.1.0.zip` | **b + c** | The installer rewrites the live `<family name="sans-serif">` block in `system/etc/font_fallback.xml` (Android 15+ primary) **and** `system/etc/fonts.xml` (legacy). A ROM with a different file layout, a vendor `fonts_customization.xml`, or a different family set makes the installer abort by design (exit 3–7) rather than half-apply. Highest OTA/update risk in the stack. | `adb shell getprop ro.build.version.sdk` (must be ≥35 for `font_fallback.xml`); `adb shell su -c 'head -20 /system/etc/font_fallback.xml'`; run `scripts/build-font-module.sh` (it replays both AOSP-16 fixtures); `adb shell su -c 'grep -c OpenSans- /system/etc/font_fallback.xml'` → 20 after install. |
| 13 | **Settings header LSPosed module** | `settings/header-module/**`, `scripts/build-header-module.sh`, `evidence/settings-header/**` | **c** | Depends on LSPosed/Vector + Zygisk and on four Settings methods: `SettingsBaseActivity.setContentView(int)`, `.setTitle(CharSequence)`, `.onResume()`, `SettingsHomepageActivity.onCreate(Bundle)`. A ROM that renames/removes these classes (or ships a different Settings app) makes the hook log an error and leaves Settings untouched — fail-safe, but the header is gone. | `dexdump` the pulled Settings APK for `Lcom/android/settings/core/SettingsBaseActivity;` and the four method names (§5.4); `adb logcat -s ztmetro-header` after opening Settings. |
| 14 | **Keyguard LSPosed module** | `sysui/keyguard-module/**`, `scripts/build-keyguard-module.sh`, `evidence/keyguard-module/**` | **c** | Six SystemUI classes, seven methods: `NotificationShadeWindowView#onAttachedToWindow`, `KeyguardRootView` (anchor), `KeyguardClockViewBinder#bind`, `KeyguardIndicationTextView#setNextIndication`, `com.android.keyguard.KeyguardSliceView#showSlice`, `com.android.systemui.shared.clocks.AnimatableClockView#refreshTime/onMeasure/setColors`. Compose keyguard refactors move these; a missing class is guarded and logged, not fatal. | `dexdump` the pulled SystemUI APK for the six descriptors and method names (§5.4); `adb logcat -s ztmetro-keyguard` around lock/unlock. |
| 15 | **Name-check tooling / expected lists** | `evidence/portability/check-target-resources.py`, `check-source-names.py`, `expected-resources-*.tsv` | **a** | Tools are ROM/device independent, but the TSVs are a **snapshot of the source tree** and must be regenerated after every overlay edit (`--write-expected`). Do not trust a TSV older than the APK it is compared with. | `python3 evidence/portability/check-target-resources.py --overlay-dir overlay/settings --write-expected /tmp/settings.tsv`; diff with the committed TSV. |
| 16 | **Built evidence APKs** | `evidence/framework-rro/ZtMetroFramework.apk`, `evidence/sysui-rro/ztmetro-systemui-rro.apk`, `evidence/settings-rro/ztmetro-settings-rro.apk`, `evidence/status-strip/*.apk`, `evidence/launcher-v3/*.apk`, `evidence/actioncentre/*.apk` | **b/d** | They are build outputs, not sources. The Settings **dump** file predates the 12:13 APK (56 entries vs 93 in source), so a dump-based check reports false missing entries; refresh the dump from the rebuilt APK. Never port an evidence APK whose entry count differs from its source. | `aapt2 dump resources evidence/settings-rro/ztmetro-settings-rro.apk > /tmp/settings-current.txt; python3 evidence/portability/check-target-resources.py --overlay-dir overlay/settings --target-dump /tmp/settings-current.txt`; `sha256sum` vs `.sha256`. |
| 17 | **Release zips** | `artifacts/ztmetro-overlays-1.0.0.zip`, `artifacts/ztmetro-font-1.0.0.zip`, `artifacts/ztmetro-font-1.1.0.zip` | **c** | Zips embed the APKs of the moment they were built. `build-overlay-module.sh` copies from `evidence/`, so a stale evidence APK becomes a stale zip. The 1.0.0 font zip is Selawik (no Cyrillic) and must not be shipped. | `unzip -l artifacts/ztmetro-overlays-1.0.0.zip`; compare member sha256 with the rebuilt evidence APKs before pushing. |
| 18 | **Root / injection stack** | `docs/13-root-and-injection-setup.md`, `docs/15-boot-recovery.md`, `docs/10a-systemless-injection.md`, Magisk/Vector zips (not in repo) | **c + d** | Magisk boot-image patching is **per device and per ROM build**; Zygisk and LSPosed/Vector versions are Android-16 specific; an OTA replaces the boot image and removes root. The overlays module survives a Magisk update but not a ROM reflash. | `adb shell su -c 'magisk -v'`; `adb shell getprop ro.build.version.release`; `adb shell su -c 'ls /data/adb/modules'`; after OTA re-check `cmd overlay list`. |
| 19 | **Pinned source facts / maps** | `design/systemui-resource-map.json`, `design/settings-container-map.md`, `sources.md`, `docs/10b-overlay-and-hook-facts.md` | **a** (documents) | All name/line facts are pinned to `lineage-23.2` commits `797e4324…` (frameworks_base) and `e1b8cd2d…` (Settings). A branch move invalidates line numbers, not necessarily names; re-run the checkers, do not trust quoted lines. | `git -C frameworks/base rev-parse HEAD`; `python3 evidence/portability/check-source-names.py …`; `grep -n 'name="…"'` on the new tree. |

### 2.1 The three riskiest couplings

1. **Device (`d`) — status-bar height is a device decision, not a theme value.**
   `overlay/status/framework/res/values/dimens.xml` ships `status_bar_height* = 28dp`
   because ugglite has no cutout and a 24 dp stock bar. sweet's own device overlay sets
   `status_bar_height_default = 100.0px` (36.36 dp at 440 dpi) and a 93 px punch-hole
   cutout. A static framework RRO applied on sweet wins over the compiled device overlay
   and shortens the bar to 28 dp — below the cutout — so status content can sit under
   the camera hole. Fix: delete the three height entries from the portable overlay, or
   split them into a per-device overlay and keep only icon size/padding in the common
   one. [F source; E visual consequence — verify with `dumpsys window` + screenshot.]
2. **ROM (`c`) — the injection hooks are AOSP class names, not a stable API.**
   The header module hard-codes `com.android.settings.core.SettingsBaseActivity` and
   `SettingsHomepageActivity`; the keyguard module hard-codes six SystemUI classes
   including the new `KeyguardClockViewBinder` / `AnimatableClockView`. They exist at
   `lineage-23.2`, but a QPR/ROM rebase can move the keyguard clock or the Settings
   container. Every hook is guarded, so the failure mode is "header/clock silently
   missing", not a boot loop — which is exactly why the porter must dex-dump the target
   (§5.4) rather than trust the module name. [F class list; ? target classes.]
3. **Android-version (`b`) — the Settings/SettingsLib resource surface is the least
   stable of the three targets.** At API 36 the framework overlay is 92/92 and the
   SystemUI overlay 25/25 against AOSP `android-16.0.0_r3`, but the Settings overlay
   loses `settingslib_entry_bg_radius` on AOSP (Lineage-only) and depends on four
   framework-named `primary_material_*` entries and three Material-Components type
   styles that are **not in the Settings/SettingsLib source** at all. Those entries are
   either supplied by an AAR in the built APK or they silently no-op; the only way to
   know is the target APK check. [F source absence; ? APK presence.]

---

## 3. Second device on the same branch: `sweet`

### 3.1 Why `sweet`, and why not the suggested `msm8937` siblings

At 2026-11-16 the only Xiaomi trees with a real `lineage-23.2` branch are the ones
still maintained for Android 16. Verified with the GitHub branches API:

| tree | `lineage-23.2`? | note |
|---|---|---|
| [`LineageOS/android_device_xiaomi_sweet`](https://github.com/LineageOS/android_device_xiaomi_sweet/tree/lineage-23.2) | **yes** (also `lineage-24.0`) | Redmi Note 10 Pro, SM7150, 1080×2400, 440 dpi — **chosen** |
| [`LineageOS/android_device_xiaomi_miatoll`](https://github.com/LineageOS/android_device_xiaomi_miatoll/tree/lineage-23.2) | yes | same SoC family/resolution as sweet; second fallback |
| [`LineageOS/android_device_xiaomi_land`](https://github.com/LineageOS/android_device_xiaomi_land) | no | branches stop at `lineage-16.0` |
| [`LineageOS/android_device_xiaomi_santoni`](https://github.com/LineageOS/android_device_xiaomi_santoni) | no | branches stop at `lineage-16.0` |
| `LineageOS/android_device_xiaomi_daisy`, `…_joyeuse` | repo 404 | not published under the LineageOS org (the `miatoll` tree covers the joyeuse family) |

So an msm8937 sibling on 23.2 does not exist in the public LineageOS org; using
`sweet` is the honest "different SoC, different resolution, same branch" test.
Device tree commit read: `894ae0bb9465ed070f4a11e9d3ad7c3e91e7e14b`
([BoardConfig.mk](https://github.com/LineageOS/android_device_xiaomi_sweet/blob/lineage-23.2/BoardConfig.mk),
[lineage_sweet.mk](https://github.com/LineageOS/android_device_xiaomi_sweet/blob/lineage-23.2/lineage_sweet.mk)).
It inherits `device/xiaomi/sm6150-common` and ships the same
`frameworks_base` / `Settings` / `SettingsLib` branch as ugglite — that is what makes
the name checks below meaningful.

### 3.2 Confirmed deltas

| property | ugglite (baseline) | sweet (lineage-23.2) | source |
|---|---|---|---|
| SoC | MSM8917 (4×A53) | SM7150 / Snapdragon 732G (`include device/xiaomi/sm6150-common/BoardConfigCommon.mk`) | [F] `BoardConfig.mk` |
| panel | 720×1280 | **1080×2400** FHD; the tree ships `…_fhd_dsc_video_dsi_panel.xml` and a 1080 px fingerprint coordinate | [F] width/panel name; [E] 2400 height |
| density | 280 dpi → 411×731 dp | **440 dpi** (`TARGET_SCREEN_DENSITY := 440`) → 393×873 dp | [F] `BoardConfig.mk:38`; [E] dp arithmetic |
| status-bar height | AOSP 24 dp (no published ugglite 23.2 device overlay; verify on device) | **100 px = 36.36 dp** portrait, `status_bar_height_portrait` aliases it; landscape 30 dp | [F] [device dimens](https://github.com/LineageOS/android_device_xiaomi_sweet/blob/lineage-23.2/overlay/frameworks/base/core/res/res/values/dimens.xml) |
| status-bar padding | 4 dp stock, 8 dp after our status RRO | **10 dp start / 5 dp end** in the device overlay | [F] [SystemUI dimens](https://github.com/LineageOS/android_device_xiaomi_sweet/blob/lineage-23.2/overlay/frameworks/base/packages/SystemUI/res/values/dimens.xml) |
| display cutout | none | punch-hole path `M -24.5 51.75 …`, 93 px bounding rect, `config_fillMainBuiltInDisplayCutout=true` | [F] [core config.xml](https://github.com/LineageOS/android_device_xiaomi_sweet/blob/lineage-23.2/overlay/frameworks/base/core/res/res/values/config.xml) |
| rounded corners | none | `rounded_corner_radius_top/bottom = 90 px`, generic 20 dp | [F] device `dimens.xml` |
| SettingsLib names | baseline branch | **same branch/commit**; all 93 overlay names resolve except the four framework-named `primary_material_*`; `settingslib_entry_bg_radius` is present (Lineage-only) | [F] §5.2 checks |
| blur class | Adreno 308, 2 GB → blur expected off [?] | Adreno 618, 6/8 GB → blur likely on [E] | [E] |
| Magisk boot | ugglite `boot.img` patch | sweet `boot.img` patch (different image/partition sizes) | [E] device-specific |

**No Lineage-23.2-only resource is missing on sweet.** Verified against the shared
trees at `lineage-23.2` = `797e4324…` / `e1b8cd2d…`; the four Settings exceptions are
framework-named entries that are absent from the shared Settings/SettingsLib source
for both targets, so they are not a sweet delta:

| overlay | entries | not in the 23.2 tree |
|---|---|---|
| framework | 92 | **0** |
| SystemUI | 25 | **0** |
| status framework | 4 | **0** |
| status SystemUI | 6 | **0** |
| Settings | 93 | 4 framework-named `primary_material_*` (see row 8/§5.2); all other names present |

Raw output: `evidence/portability/check-lineage23.2-*.txt`.

### 3.3 What actually breaks on sweet, and the fix

- **Status bar (real break).** Our status framework RRO is `isStatic=true` and
  overrides `status_bar_height`/`_portrait`/`_default` to 28 dp. sweet's compiled
  device overlay wants 100 px (36.36 dp) and has a 93 px cutout. The RRO wins; the bar
  becomes ~8.4 dp shorter than the device intends and the cutout can overlap the
  clock/icons. **Fix:** remove the three height entries from
  `overlay/status/framework/res/values/dimens.xml` before building for any device with
  a cutout, or keep a per-device height overlay. The status SystemUI RRO's
  `status_bar_padding_start` 8 dp also overrides sweet's 10 dp; harmless but
  device-tuned.
- **Shade flatness (visual regression [E]).** On ugglite the flat black shade comes
  from `shade_panel_fallback` / `notification_scrim_fallback` (blur-off branch). On a
  blur-capable device `ShadeColors.kt:33-55` takes the `notification_scrim_base`
  branch at **50 % alpha** and the panel is composited from framework
  `shade_panel_fg/bg`; our `*_fallback` colours are ignored, so the shade reads dark
  translucent grey, not Metro black. If that matters, add `shade_panel_fg` /
  `shade_panel_bg` to the framework RRO (both are framework colours) or set the
  framework `config_supportsBlur`-equivalent off on the target; verify on the device.
- **Launcher grid.** 393 dp wide with 4 columns is inside the 360–411 dp band and
  `MetroGridView.onMeasure` recomputes the unit from the real width; no code change
  needed. The extra height simply shows more rows. [E]
- **Settings header.** Same Settings/SettingsLib branch and same class names, so the
  header module runs unchanged; the only difference is a taller status bar/cutout
  above the content, which the header (inside `content_frame`) does not overlap. [E]
- **Font module.** Same Android 16 `font_fallback.xml` layout (AOSP 16 file exists);
  the installer's live-config guard still applies. [F format, ? device file]
- **Magisk/overlays.** Same module works (product partition exists on sweet); boot
  patching is a new, device-specific step. [E]

### 3.4 How to re-run the second-target check

```sh
# device tree facts (no build)
git clone --depth 1 -b lineage-23.2 \
    https://github.com/LineageOS/android_device_xiaomi_sweet.git /tmp/sweet
grep -n 'TARGET_SCREEN_DENSITY' /tmp/sweet/BoardConfig.mk
cat /tmp/sweet/overlay/frameworks/base/core/res/res/values/dimens.xml
cat /tmp/sweet/overlay/frameworks/base/packages/SystemUI/res/values/dimens.xml

# resource-name check against the same branch (sparse clones)
python3 repo/evidence/portability/check-source-names.py \
    --overlay framework --framework-tree /tmp/lineage/frameworks/base
python3 repo/evidence/portability/check-source-names.py \
    --overlay systemui --framework-tree /tmp/lineage/frameworks/base
python3 repo/evidence/portability/check-source-names.py \
    --overlay settings --framework-tree /tmp/lineage/frameworks/base \
    --settings-tree /tmp/lineage/packages/apps/Settings
```

Replace `/tmp/lineage/...` with sparse clones of
[`android_frameworks_base@lineage-23.2`](https://github.com/LineageOS/android_frameworks_base/tree/lineage-23.2)
and [`android_packages_apps_Settings@lineage-23.2`](https://github.com/LineageOS/android_packages_apps_Settings/tree/lineage-23.2).

---

## 4. Another AOSP-like Android 16 ROM

### 4.1 What still applies

| block | AOSP 16.0.0_r3 | crDroid 23.x | Pixel Experience 16 | LineageOS-for-microG 23.2 |
|---|---|---|---|---|
| framework RRO (92 names) | **92/92 present** [F] | AOSP names retained; crDroid adds its own overlays | AOSP names retained | 92/92 [F] |
| SystemUI RRO (25 names) | **25/25 present** [F] | AOSP 2025 shade; crDroid patches QS but keeps `qs_*`/`TextAppearance.QS.*` names — re-check | PE may rebase the shade; re-check | 25/25 [F] || status RROs (4+6) | present | present | present | present |
| Settings RRO (93) | all names resolve in AOSP 16 Settings/SettingsLib **except** `settingslib_entry_bg_radius` (Lineage-only) and the 4 framework-named `primary_material_*`; 3 Material styles + `cardview_default_radius` are AAR-only and need the APK check [F] | Lineage-derived; `settingslib_entry_bg_radius` present | AOSP Settings + Pixel config; dashboard resources may be replaced — APK check mandatory | all names resolve except the same 4 framework-named `primary_material_*` |
| Settings header hooks | AOSP class names exist; verify dex | Lineage-derived, likely identical | PE Settings version may differ; verify dex | works [F on device] |
| keyguard hooks | AOSP 16 classes exist; verify dex | verify dex | verify dex | built but not device-verified [?] |
| font module | Android 16 `font_fallback.xml` exists; vendor customisation may differ | same | same | verified build fixtures [F] |
| preinstall route | userdebug: `adb root`+push, or Magisk; user: Magisk | Magisk | Magisk | Magisk module [F] |

The framework resource names are the most AOSP-stable part: every one of the 92
exists at `android-16.0.0_r3` (and at `lineage-23.2`), because both are the same
`framework-res` API surface. The SystemUI 2025-shade names are present in AOSP 16 but
**not** on older bases: on `lineage-22.2` the name `shade_header_text_color` appears
nowhere in `packages/SystemUI/res` (checked with a sparse clone), so the header
style's colour would be a dangling reference there. Older bases need the same
whole-tree check, not a `colors.xml` grep.

### 4.2 What changes, specifically

- **Lineage-added resources.** LineageOS adds `res/values/lineage_config.xml`,
  `lineage_dimens.xml` and the `lineage-sdk` package. The overlays reference **none** of
  them (`grep -r 'lineage_' overlay/` returns 0 hits), so Lineage-specific resources are
  not a coupling by themselves. The two that do matter are the Lineage-added SettingsLib
  dimen (`settingslib_entry_bg_radius`) and the Lineage/AOSP 16 shade source layout.
- **Settings/SettingsLib.** The overlay targets `com.android.settings` and depends on
  SettingsLib resources merged into that APK. On AOSP 16 `settingslib_entry_bg_radius`
  is absent (`packages/SettingsLib/MainSwitchPreference/res/values-v31/dimens.xml` has
  no such dimen in AOSP, Lineage adds it). That overlay entry no-ops; it has no
  consumer in either tree at this commit, so the visible loss is none — but the
  "radius set" is incomplete. `settingslib_expressive_radius_*` and
  `settingslib_switchbar_*` **are** present in AOSP 16 SettingsLib. A ROM on Android 15
  or an older SettingsLib loses the whole expressive set and the Material3 type names.
  When a name is missing, idmap2 drops only that entry (no crash). Remedy: delete it,
  or re-point it at a name the target actually has — `settingslib_entry_bg_radius` can
  simply be deleted (no consumer at this commit), `primary_material_*` should be left to
  the framework `primary_device_default_settings_*` entries, and a missing
  `homepage_*` means the ROM's dashboard keeps its own palette; accept that or dump
  the ROM's actual colour names and target those instead.
- **SystemUI shade version.** The 2025 shade (`shade_dimens.xml` in Lineage,
  `dimens.xml` at AOSP) is an API-36 design. On Android 15 the QS tile layout and the
  `TextAppearance.QS.Status*` styles differ; on a ROM that replaces SystemUI's QS with
  its own compose screen, the resource names may survive but the consumer may be gone.
  Re-run the dump; do not infer from the ROM's marketing version.
- **Dynamic colour / theme overlays.** A ROM that ships its own `android` RRO with a
  higher priority (or a ThemePicker-generated overlay) can override our static
  priority-2 framework overlay. Check `cmd overlay list` ordering and, if needed,
  raise `android:priority` or `cmd overlay set-priority`. [E]
- **microG builds.** No UI/resource delta from microG: it changes the GMS substitute,
  not framework/Settings/SystemUI resources. The same LineageOS base means the same
  names. The only microG-specific fact is the ROM signature (the overlays are
  preinstalled, so they need no target signature; the LSPosed modules are user apps).
  [E/F branch]
- **LSPosed hook classes.** Class names are an implementation detail, not a resource
  contract. Re-verify with §5.4 on every ROM, including point releases: Settings
  `SettingsBaseActivity`/`SettingsHomepageActivity`; SystemUI `NotificationShadeWindowView`,
  `KeyguardRootView`, `KeyguardClockViewBinder`, `KeyguardIndicationTextView`,
  `KeyguardSliceView`, `AnimatableClockView`. On AOSP 16 they exist; on a ROM that
  moves the clock to a different binder, only the keyguard clock hook is lost.
- **Fonts.** `fonts.xml`/`font_fallback.xml` are read by `SystemFonts`. AOSP 16,
  crDroid 23 and PE 16 all ship the Android 16 pair; Lineage/microG same. A ROM with a
  vendor `fonts_customization.xml` naming `sans-serif` can override the module; check
  `/product/etc/fonts_customization.xml` before blaming the module. [F format, ? ROM]

### 4.3 Detect drift in minutes

The full runnable sequence is §5.3. The core idea: dump the **live target APKs**, then
compare the overlay's names to the dump. Use `aapt2` from build-tools 36.0.0.

```sh
AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"

# framework-res / SystemUI / Settings from the target (root not needed for pull)
for p in android com.android.systemui com.android.settings; do adb shell pm path "$p"; done
adb pull /system/framework/framework-res.apk /tmp/target/framework-res.apk
adb pull "$(adb shell pm path com.android.systemui | tr -d '\r' | sed 's/package://')" /tmp/target/SystemUI.apk
adb pull "$(adb shell pm path com.android.settings | tr -d '\r' | sed 's/package://')" /tmp/target/Settings.apk

for pair in "framework overlay/framework /tmp/target/framework-res.apk" \
            "systemui overlay/systemui /tmp/target/SystemUI.apk" \
            "settings overlay/settings /tmp/target/Settings.apk" \
            "status-framework overlay/status/framework /tmp/target/framework-res.apk" \
            "status-systemui overlay/status/systemui /tmp/target/SystemUI.apk"; do
  set -- $pair
  python3 repo/evidence/portability/check-target-resources.py \
      --overlay-dir "$2" --aapt2 "$AAPT2" --target-apk "$3"
done

# if the ROM source, not the APK, is what you have:
python3 repo/evidence/portability/check-source-names.py \
    --overlay settings --framework-tree /src/frameworks/base \
    --settings-tree /src/packages/apps/Settings
```

The APK check is authoritative. A non-zero exit is the list of entries that would
silently no-op; decide per entry: remove it, re-point it at an existing name, or accept
the visual loss. For a source tree, allow only the known library names:
`attr/cardCornerRadius`, `attr/cardElevation`, `dimen/cardview_default_radius`,
`style/TextAppearance.M3.Sys.Typescale.*` (they come from AARs, not source).

---

## 5. Porter runbook (exact commands)

### 5.1 Build every artifact

From the workspace root (`android-ux-research/`, parent of `repo/`), with
`ANDROID_HOME` pointing at platform 36 + build-tools 36.0.0 and `JAVA_HOME` at JDK 17:

```sh
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export JAVA_HOME="${JAVA_HOME:-$HOME/android-jdk}"

repo/scripts/build-overlay-framework.sh --out repo/evidence/framework-rro  # -> ZtMetroFramework.apk
repo/scripts/build-overlay-sysui.sh                                        # -> evidence/sysui-rro/ztmetro-systemui-rro.apk
repo/scripts/build-overlay-settings.sh                                     # -> evidence/settings-rro/ztmetro-settings-rro.apk
repo/scripts/build-overlay-status.sh                                       # -> evidence/status-strip/*.apk
repo/scripts/build-overlay-module.sh                                       # -> artifacts/ztmetro-overlays-1.0.0.zip
repo/scripts/build-launcher.sh                                             # -> evidence/launcher-v3/ztmetro-start.apk
repo/scripts/build-actioncentre.sh                                         # -> evidence/actioncentre/ztmetro-actioncentre.apk
repo/scripts/build-header-module.sh                                        # -> settings/header-module/build/dist/ztmetro-settings-header.apk
repo/scripts/build-keyguard-module.sh                                      # -> sysui/keyguard-module/build/dist/ztmetro-keyguard.apk
repo/scripts/build-font-module.sh                                          # -> artifacts/ztmetro-font-1.1.0.zip
```

`docs/18` §3.3 lists the flags/env each script accepts. The order matters only for
`build-overlay-module.sh`, which copies the five evidence APKs (framework, SystemUI,
Settings, two status RROs).

### 5.2 Verify the build before it leaves the host

```sh
python3 repo/evidence/portability/check-target-resources.py \
    --overlay-dir repo/overlay/settings \
    --target-dump repo/evidence/settings-rro/aapt2-dump-resources.txt   # after refreshing the dump

# regenerate the checked-in expected lists whenever an overlay changes
for spec in "framework overlay/framework" "systemui overlay/systemui" \
            "settings overlay/settings" "status-framework overlay/status/framework" \
            "status-systemui overlay/status/systemui"; do
  set -- $spec
  python3 repo/evidence/portability/check-target-resources.py \
      --overlay "$1" --overlay-dir "repo/$2" \
      --write-expected "repo/evidence/portability/expected-resources-$1.tsv"
done

# sanity: the overlay must be an RRO, no code, no target references
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging \
    repo/evidence/settings-rro/ztmetro-settings-rro.apk | grep -E 'package|targetSdk'
unzip -l repo/evidence/settings-rro/ztmetro-settings-rro.apk | grep -c 'classes.*dex'  # must be 0
```

If the check reports entries missing from the evidence dump, rebuild the overlay
before packaging — the Settings evidence APK currently lags its source (56 vs 93
entries; `docs/18` §6.3).

### 5.3 Deploy and verify

```sh
# --- overlays: one Magisk module, preinstalled under /product/overlay -----------
adb push repo/artifacts/ztmetro-overlays-1.0.0.zip /sdcard/
adb shell su -c 'magisk --install-module /sdcard/ztmetro-overlays-1.0.0.zip'
adb reboot
adb shell su -c 'cmd overlay list | grep -E "ztos|ztmetro"'
adb shell su -c 'cat /data/local/tmp/ztmetro-overlays.log'

# framework + status-framework are static: a new APK needs the reboot above.
# Runtime three (service.sh does this at boot; do it explicitly for an iteration):
adb shell su -c 'cmd overlay enable --user 0 org.ztos.overlay.settings'
adb shell su -c 'cmd overlay enable --user 0 org.ztos.overlay.systemui'
adb shell su -c 'cmd overlay enable --user 0 org.ztos.overlay.status.systemui'
adb shell am force-stop com.android.settings
adb shell su -c 'killall com.android.systemui'

# --- Start / Action Centre ----------------------------------------------------
adb install -r repo/evidence/launcher-v3/ztmetro-start.apk
adb shell cmd package set-home-activity org.ztmetro.start/.StartActivity
adb install -r repo/evidence/actioncentre/ztmetro-actioncentre.apk
adb shell cmd notification allow_listener \
    org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreListener
adb shell appops set org.ztmetro.actioncentre WRITE_SETTINGS allow
adb shell pm grant org.ztmetro.actioncentre android.permission.CAMERA

# --- LSPosed modules ----------------------------------------------------------
adb install -r repo/evidence/settings-header/ztmetro-settings-header.apk
adb install -r repo/evidence/keyguard-module/ztmetro-keyguard.apk
# LSPosed/Vector Manager -> enable each module; scopes are in the APK manifests:
#   header -> com.android.settings ; keyguard -> com.android.systemui
adb shell am force-stop com.android.settings
adb shell su -c 'killall com.android.systemui'
adb logcat -s ztmetro-header -s ztmetro-keyguard

# --- font ---------------------------------------------------------------------
adb push repo/artifacts/ztmetro-font-1.1.0.zip /sdcard/
adb shell su -c 'magisk --install-module /sdcard/ztmetro-font-1.1.0.zip'
adb reboot
adb shell su -c 'grep -c OpenSans- /system/etc/font_fallback.xml'   # 20
```

Visual acceptance is the same as `docs/18` §3.5: Settings chrome, header, Start,
Action Centre, square shade, font. A resource check passing is not a visual pass.

### 5.4 Re-verify the hook targets on the new ROM

```sh
BT="$ANDROID_HOME/build-tools/36.0.0"
mkdir -p /tmp/target/settings-dex /tmp/target/sysui-dex
unzip -o /tmp/target/Settings.apk 'classes*.dex' -d /tmp/target/settings-dex
unzip -o /tmp/target/SystemUI.apk 'classes*.dex' -d /tmp/target/sysui-dex

for d in /tmp/target/settings-dex/classes*.dex; do "$BT/dexdump" -d "$d"; done \
    > /tmp/target/settings.dexdump
for d in /tmp/target/sysui-dex/classes*.dex; do "$BT/dexdump" -d "$d"; done \
    > /tmp/target/sysui.dexdump

# header module: 2 classes, 4 methods
grep -n 'Lcom/android/settings/core/SettingsBaseActivity;' /tmp/target/settings.dexdump
grep -n 'Lcom/android/settings/homepage/SettingsHomepageActivity;' /tmp/target/settings.dexdump
for m in setContentView setTitle onResume; do
  grep -n -A2 "name *: '$m'" /tmp/target/settings.dexdump
done

# keyguard module: 6 classes, 7 methods
for c in 'Lcom/android/systemui/shade/NotificationShadeWindowView;' \
         'Lcom/android/systemui/keyguard/ui/view/KeyguardRootView;' \
         'Lcom/android/systemui/keyguard/ui/binder/KeyguardClockViewBinder;' \
         'Lcom/android/systemui/statusbar/phone/KeyguardIndicationTextView;' \
         'Lcom/android/keyguard/KeyguardSliceView;' \
         'Lcom/android/systemui/shared/clocks/AnimatableClockView;'; do
  grep -q "$c" /tmp/target/sysui.dexdump && echo "OK $c" || echo "MISSING $c"
done
grep -n -A2 "name *: 'bind'" /tmp/target/sysui.dexdump
grep -n -A2 "name *: 'showSlice'" /tmp/target/sysui.dexdump
```

A `MISSING` class means: disable that module's scope before booting the phone into a
state where it could throw inside SystemUI/Settings; the hooks are guarded, but the
module is dead weight until the class names are updated.

---

## 6. Known gaps, no-ops and rollback

1. **Stale Settings dump.** `evidence/settings-rro/aapt2-dump-resources.txt` was
   captured at 11:22 (56 entries) before the accent/geometry additions; the APK was
   rebuilt at 12:13 (21,196 B) and the source now has 93 resource names. Checking the
   rebuild against the old dump reports 37 false "missing" entries. Refresh the dump
   with `aapt2 dump resources` before comparing (`docs/18` §6.3 flags the same lag).
2. **Entries that are inert even on ugglite.** `overlay_shade_panel_shape_radius` and
   `aux_spacing_overlay_panel_shape_radius` have **0 consumers** at 23.2;
   `settingslib_entry_bg_radius` has no consumer in Settings/SettingsLib. They are
   harmless but prove nothing. `primary_material_*` (4 colours) are declared by
   framework-res, not Settings/SettingsLib; unless the built Settings APK carries the
   same names from an AAR, they no-op on every ROM.
3. **Cutout/taller status bar devices.** The status framework RRO's 24→28 dp height is
   the one entry that must not be ported unchanged to sweet or any device with a
   cutout. Split it per device or drop it.
4. **Blur-capable devices.** The flat-black shade comes from the blur-off branch; on a
   blur-on device the shade is a 50 % black scrim over blur and the `*_fallback`
   colours are ignored. Add `shade_panel_fg`/`shade_panel_bg` to the framework RRO or
   accept the tonal shade.
5. **Rollback.**
   - overlays: `adb shell su -c 'cmd overlay disable --user 0 org.ztos.overlay.settings'`
     (runtime pair); framework is static → remove the Magisk module and reboot.
   - font: remove `ztmetro-font` in Magisk and reboot.
   - LSPosed: disable the module scope, then force-stop the target app.
   - Start: set another HOME app in Settings → Apps → Default apps.
   - There is no destructive write in this stack: no boot/system/recovery flash is
     part of a normal port; Magisk boot patching is the only device-specific write.

---

## 7. Evidence index

| file | what it proves |
|---|---|
| `evidence/portability/check-target-resources.py` | target-APK name check (non-zero if an entry would no-op) |
| `evidence/portability/check-source-names.py` | source-tree name check for framework/SystemUI/Settings/SettingsLib |
| `evidence/portability/expected-resources-*.tsv` | the overlay name lists (regenerate after edits) |
| `evidence/portability/check-lineage23.2-*.txt` | 0 missing on the 23.2 trees (framework 92, SystemUI 25, status 4+6); Settings exceptions listed |
| `evidence/portability/check-aosp16-*.txt` | 0 missing at AOSP 16 except `settingslib_entry_bg_radius`; library names listed |
| `docs/18-coverage-and-build.md` | current build/install/verify commands and on-device status |
| `docs/10b-overlay-and-hook-facts.md` | overlayable policy, shade facts, hook seam |
| `design/systemui-resource-map.json` | SystemUI names + consumer lines at `797e4324…` |

## Automated portability check (2026-11-16)

`scripts/check-portability.sh` fails when the active sources fall back to this
device or its screen. It scans `launcher/`, `actioncentre/`, `ime/`, `settings/`
and `scripts/` (build outputs excluded) and reports:

| rule | result |
|---|---|
| device-name literals in code (comments allowed) | **PASS** — only comment lines mention ugglite; the conductor's generated doc line is whitelisted |
| `720`/`1280` screen literals in functional code | **PASS** |
| raw `px` in layouts (dp/sp only) | **PASS** (0) |
| design tokens present (`design/tokens/*`) | **PASS** |
| scripts must not hardcode a serial or LAN IP | **PASS** |

Two real hardcodes were found and removed in this pass:

- `ShadeBridgeService` used `return 1280` as the last-resort display height;
  now `Resources.getSystem().getDisplayMetrics().heightPixels`.
- `LockOverlayController` used `new Rect(0, 0, 720, 1280)` as the last-resort
  display bounds; now the same system metrics.

Everything else is already ratio-based: Start is a six-unit grid driven by
`design/ztmetro.tokens.json`, the Action Centre tiles are `85.75 x 57 dp`,
the status strip is `28 dp`, the lock clock is `92 sp`, the strip gesture
window is `40 %` of the display height. None of those need a resolution.

Run it before publishing a build:

```sh
repo/scripts/check-portability.sh   # exit 0 = portable
```

The remaining device coupling is documented and intentional: the bottom bar is
suppressed on devices with hardware keys (`config_showNavigationBar == false`),
and the emulator/device transport lives only in the scripts that talk to adb.
