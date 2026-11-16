# framework-res RRO — ztmetro accent, type and control metrics

Deliverable: one preinstalled runtime resource overlay targeting **`android`**
(framework-res) for LineageOS 23.2 / Android 16 (API 36), built from
`repo/design/tokens/ztmetro.tokens.json`. Values only, `hasCode="false"`, no
components. Every overridden name was grepped in AOSP
`android-16.0.0_r3` `core/res/res/values/*.xml` (table in §3); a name that is
missing from the target is ignored *silently* by idmap2, so nothing guesses.

- APK (in this directory): `ZtMetroFramework.apk` — 16 728 bytes,
  sha256 `99b7bf129805966dcb0ca13d46908009a6749e2c579da65f987334c80581e457`.
  The unsigned payload (manifest + resources.arsc) is deterministic:
  sha256 `2f0b617a585f8c607144c71be1ec2a4a8b0b03f1b0d3c0e381c69885bb3dfd88`;
  only the throwaway signature changes the APK file hash.
- package: `org.ztmetro.overlay.framework`, target package `android`
- 92 resource entries: 63 colour, 7 dimen, 2 bool, 20 style
- **v2 (this build) adds the accent *role* colours** that Material3 widgets in
  stock apps read (`?attr/colorPrimary`, `colorOnPrimary`, the monet seeds) — the
  36 entries live in the hand-maintained
  `repo/overlay/framework/res/values/ztmetro_accent_roles.xml` and are listed in
  §3b. They are what unblocks the M3 switch/slider inside Settings (§3c).
- install (systemless, see `docs/10a-systemless-injection.md` §2.5):
  `/product/overlay/ZtMetroFramework.apk` (Magisk module maps it under
  `system/product/overlay/`). `android:isStatic="true"` → immutable, enabled at
  boot, and — because only immutable overlays targeting `android` reach
  `Resources.getSystem()` — visible to zygote-forked apps without any
  `cmd overlay enable`.
- source: `repo/overlay/framework/{AndroidManifest.xml,gen_overlay.py,res/values/*.xml,tools/check_names.py}`
- build script: `repo/scripts/build-overlay-framework.sh`

## 1. Why this overlay is allowed to touch everything

`android-16.0.0_r3` framework-res declares **no `<overlayable>`**: there is no
`core/res/res/values/overlayable.xml` in the tag's values listing (checked
directly; also probed 404 on the raw path), matching `docs/10a` §2.4. idmap2's
`CheckOverlayable` therefore takes the `!defines_overlayable` branch
(`cmds/idmap2/libidmap2/ResourceMapping.cpp:69-75`) and allows every resource as
long as the overlay fulfils one of `kDefaultPolicies` (`:55-58`) — a
`/product/overlay` install supplies `PRODUCT_PARTITION`. That is why the
manifest **omits `android:targetName`**: there is no group name to target, and a
non-existent `targetName` would fail the policy check.

## 2. Build (exact commands, on `hermes-ubuntu`)

```sh
# v2 build host layout (only overlay/framework + design/tokens + the script are needed)
~/ztux/framework-ro/src/{overlay/framework,design/tokens,scripts}

cd ~/ztux/framework-ro/src
ANDROID_SDK_ROOT=$HOME/android-sdk JAVA_HOME=$HOME/android-jdk \
  bash scripts/build-overlay-framework.sh --out ~/ztux/framework-ro/out
```

Output (verbatim tail):

```
==> SDK          /home/denis/android-sdk
==> build-tools  /home/denis/android-sdk/build-tools/36.0.0 (Android Asset Packaging Tool (aapt) 2.20-13193326)
==> android.jar  /home/denis/android-sdk/platforms/android-36/android.jar
==> JDK          /home/denis/android-jdk
==> regenerating res/values from design/tokens/ztmetro.tokens.json
==> aapt2 compile
==> aapt2 link (target package: android)
==> generating a throwaway signing key (not stored in the repo)
==> apksigner sign
    Signer #1 certificate DN: CN=ztmetro framework overlay, O=ztOS
    Signer #1 certificate SHA-256 digest: 66a754dbf108103869839e333cae2e6cec68cef8a3355152de4c9bb0bf181a95
==> verifying the package
==> overridden resources: 92

APK      /home/denis/ztux/framework-ro/out/ZtMetroFramework.apk
sha256   99b7bf129805966dcb0ca13d46908009a6749e2c579da65f987334c80581e457
payload  2f0b617a585f8c607144c71be1ec2a4a8b0b03f1b0d3c0e381c69885bb3dfd88
size     16728 bytes
```

Two extra checks run on the built APK (output in `check-names.log`, CSV in
`verified-resources.csv`):

```sh
# every name exists in the AOSP tag (0 MISSING, exit 0) -- 92 resources
python3 tools/check_names.py --apk out/ZtMetroFramework.apk --aapt2 $AAPT2 \
    --aosp-values ~/ztux/framework-ro/aosp --aosp-color ~/ztux/framework-ro/aosp/color \
    --csv out/verified-resources.csv

# no attribute references in the overlay sources, so nothing can dangle
grep -rn '@android:attr' overlay/framework/res/   # -> 0 hits
```

`@android:attr` is 0 by construction: an overlay may only *define* values, and an
attribute path is spelled `?android:attr/…` when a *value* needs it. Keeping the
count at 0 means no overlay entry depends on an attr id being present in the
target's package.

Tools: `aapt2` 2.20-13193326 (build-tools 36.0.0), JDK at `~/android-jdk`,
`apksigner` 36.0.0. The script:

1. regenerates `res/values/*.xml` from the token file (`gen_overlay.py`), or uses
   the committed copies when `python3` is absent (`--no-regen` skips it);
2. `aapt2 compile --dir res -o compiled.zip`;
3. `aapt2 link -o … -I android.jar --manifest … --min-sdk-version 36
   --target-sdk-version 36 --auto-add-overlay compiled.zip`;
4. `keytool -genkeypair` into a `mktemp -d` directory (never in the repo),
   `apksigner sign` (RSA-2048, PKCS12, 10 000 days — a throwaway; the overlay
   needs no target signature because it is preinstalled);
5. fails the build unless the manifest says `targetPackage="android"` +
   `hasCode="false"`, the APK has **no `classes*.dex`**, and
   `aapt2 dump resources` reports at least one entry.

Exact resource verification commands:

```sh
AAPT2=~/android-sdk/build-tools/36.0.0/aapt2
$AAPT2 dump resources out/ZtMetroFramework.apk              # entries + values
$AAPT2 dump xmltree out/ZtMetroFramework.apk --file AndroidManifest.xml
$AAPT2 dump resources ~/android-sdk/platforms/android-36/android.jar \
    | grep 'style/TextAppearance.Material'                  # id cross-check
unzip -l out/ZtMetroFramework.apk                            # no dex
python3 overlay/framework/tools/check_names.py \
    --apk out/ZtMetroFramework.apk --aapt2 $AAPT2 \
    --aosp-values aosp/values --aosp-color aosp/color \
    --csv out/verified-resources.csv
```

`check_names.py` output: `all 92 resources verified` (exit 0, 0 `MISSING`; log in
`check-names.log`, per-resource table in `verified-resources.csv`)
(full table `verified-resources.csv`, mirrored here in §3).

## 3. Verified resource list (name → AOSP `core/res/res/values` file:line)

Tag: `android-16.0.0_r3`, files fetched from
`raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r3/core/res/res/`.
`color/` entries are ColorStateLists under `core/res/res/color/`, not values.

| resource | overlay value | AOSP source |
|---|---|---|
| `bool/config_actionMenuItemAllCaps` | `false` | `values/config.xml:2766` |
| `bool/config_buttonTextAllCaps` | `false` | `values/config.xml:5383` |
| `color/accent_device_default` | `#ff0050ef` | `values/colors_device_defaults.xml:37` |
| `color/accent_device_default_dark` | `#ff3d7bff` | `values/colors_device_defaults.xml:36` |
| `color/accent_device_default_light` | `#ff0050ef` | `values/colors_device_defaults.xml:35` |
| `color/accent_material_dark` | `#ff3d7bff` | `values/colors_material.xml:43` |
| `color/accent_material_light` | `#ff0050ef` | `values/colors_material.xml:42` |
| `color/ripple_material_dark` | `#1f0050ef` | `color/ripple_material_dark.xml:17` |
| `color/ripple_material_light` | `#1f0050ef` | `color/ripple_material_light.xml:17` |
| `color/customColorOnShadeActive` | `#ffffffff` | `values/colors_dynamic.xml:171` |
| `color/customColorOnShadeActiveVariant` | `#ffd9e2ff` | `values/colors_dynamic.xml:172` |
| `color/customColorShadeActive` | `#ff0050ef` | `values/colors_dynamic.xml:177` |
| `color/customColorShadeDisabled` | `#ff00143c` | `values/colors_dynamic.xml:178` |
| `color/customColorShadeInactive` | `#ff1f1f1f` | `values/colors_dynamic.xml:179` |
| `color/system_accent1_100` | `#ff7fa8ff` | `values/colors_dynamic.xml:22` |
| `color/system_accent1_100_dark` | `#ff7fa8ff` | `values/colors.xml:404` |
| `color/system_accent1_100_light` | `#ff7fa8ff` | `values/colors.xml:248` |
| `color/system_accent1_200` | `#ff3d7bff` | `values/colors_dynamic.xml:23` |
| `color/system_accent1_200_dark` | `#ff3d7bff` | `values/colors.xml:406` |
| `color/system_accent1_200_light` | `#ff3d7bff` | `values/colors.xml:250` |
| `color/system_accent1_500` | `#ff0050ef` | `values/colors_dynamic.xml:26` |
| `color/system_accent1_500_dark` | `#ff0050ef` | `values/colors.xml:412` |
| `color/system_accent1_500_light` | `#ff0050ef` | `values/colors.xml:256` |
| `color/system_accent1_600` | `#ff002878` | `values/colors_dynamic.xml:27` |
| `color/system_accent1_600_dark` | `#ff002878` | `values/colors.xml:414` |
| `color/system_accent1_600_light` | `#ff002878` | `values/colors.xml:258` |
| `color/system_accent1_700` | `#ff00143c` | `values/colors_dynamic.xml:28` |
| `color/system_accent1_700_dark` | `#ff00143c` | `values/colors.xml:416` |
| `color/system_accent1_700_light` | `#ff00143c` | `values/colors.xml:260` |
| `dimen/action_bar_default_height_material` | `48dp` | `values/dimens_material.xml:39` |
| `dimen/config_bottomDialogCornerRadius` | `0dp` | `values/config.xml:5379` |
| `dimen/config_buttonCornerRadius` | `0dp` | `values/config.xml:5377` |
| `dimen/config_dialogCornerRadius` | `0dp` | `values/config.xml:5375` |
| `dimen/config_progressBarCornerRadius` | `0dp` | `values/config.xml:5381` |
| `dimen/control_corner_material` | `0dp` | `values/dimens_material.xml:118` |
| `dimen/dialog_corner_radius` | `0dp` | `values/dimens.xml:1123` |
| `style/TextAppearance.Material` | base: 13sp/±1.3846/parent `TextAppearance` (0x0103003e) | `values/styles_material.xml:155` |
| `style/TextAppearance.Material.Body1` | 13sp | `values/styles_material.xml:227` |
| `style/TextAppearance.Material.Body2` | 13sp | `values/styles_material.xml:221` |
| `style/TextAppearance.Material.Button` | 13sp, sentence case | `values/styles_material.xml:251` |
| `style/TextAppearance.Material.Caption` | 12sp | `values/styles_material.xml:233` |
| `style/TextAppearance.Material.Display1` | 25.5sp light | `values/styles_material.xml:183` |
| `style/TextAppearance.Material.Display2` | 48sp light | `values/styles_material.xml:177` |
| `style/TextAppearance.Material.Display3` | 56sp light | `values/styles_material.xml:171` |
| `style/TextAppearance.Material.Display4` | 92sp light | `values/styles_material.xml:165` |
| `style/TextAppearance.Material.Headline` | 22sp light | `values/styles_material.xml:189` |
| `style/TextAppearance.Material.Menu` | 17sp | `values/styles_material.xml:239` |
| `style/TextAppearance.Material.Notification` | 13sp | `values/styles_material.xml:468` |
| `style/TextAppearance.Material.Notification.Line2` | 12sp | `values/styles_material.xml:488` |
| `style/TextAppearance.Material.Notification.Title` | 17sp | `values/styles_material.xml:475` |
| `style/TextAppearance.Material.Subhead` | 17sp | `values/styles_material.xml:208` |
| `style/TextAppearance.Material.Title` | 20sp light | `values/styles_material.xml:195` |
| `style/TextAppearance.Material.Widget.ActionBar.Subtitle` | 11.5sp medium, ALL CAPS | `values/styles_material.xml:372` |
| `style/TextAppearance.Material.Widget.ActionBar.Title` | 20sp light | `values/styles_material.xml:362` |
| `style/TextAppearance.StatusBar.EventContent` | 13sp `#999999` | `values/styles.xml:280` |
| `style/TextAppearance.StatusBar.EventContent.Title` | 17sp `#FFFFFF` | `values/styles.xml:284` |

Colour/type/dimen provenance of the *values* (not the names):

| token | used as |
|---|---|
| `color.accent.base` `#0050EF` | light accent (`accent_device_default_light`, `accent_device_default`, `accent_material_light`), `system_accent1_500`, ripple base |
| `color.accent.light1` `#3D7BFF` | dark accent (`*_dark` variants, `accent_material_dark`), `system_accent1_200` — the token's own "on dark" lift |
| `color.accent.light2` `#7FA8FF` | `system_accent1_100` (the tone DeviceDefault dark uses as its accent) |
| `color.accent.dark1` `#002878` | `system_accent1_600` (tonal container) |
| `color.accent.dark2` `#00143C` | `system_accent1_700` |
| `metrics.corner_radius_dp` = 0 | all six corner-radius resources |
| `metrics.min_touch_target_dp` = 48 | `action_bar_default_height_material` |
| `type.ramp[*].size_sp_at_density_1x` | every `textSize` |
| `type.ramp[*].line_height / size` | `lineSpacingMultiplier` (1.1957 … 1.3913) |
| ramp weight ≤350 / ≥500 / else | `sans-serif-light` / `sans-serif-medium` / `sans-serif` |
| ripple alpha 12 % | **framework constant** (`highlight_alpha_material_light/dark`), not a ztmetro token → `#1F` |
| `neutrals.surface_off` `#1F1F1F` | QS inactive tile fill (`ztmetro.c:6316` quick action off) |
| `accent.foreground_on_accent` `#FFFFFF` | QS label/icon colour on an active tile |
| `#D9E2FF` | **framework tone** (`colors.xml:688`), used for the on-active *variant* because the token file has no second on-accent emphasis |

`letterSpacing` is emitted as `0`: the token file has **no** letter-spacing
field (ztmetro draws Pango text with default zero tracking), so any non-zero
tracking would be invented. `textAllCaps` is `false` everywhere except the
11.5sp semibold caps line, which is the only ALL-CAPS role in ztmetro
(`core/zrnmetrocore.c:509`, `caption_caps`).

### 3b. Accent role colours (new in v2) — `res/values/ztmetro_accent_roles.xml`

These are the **colour resources** that the DeviceDefault and Material3 themes
point their accent attributes at. The attributes themselves are `<attr>`
declarations (`core/res/res/values/attrs.xml:1122,1126,1129,1133,1160,1163`) and
cannot be replaced — see §5 for the proof and for what that implies.

| resource | overlay value | AOSP source (name → file:line) | why |
|---|---|---|---|
| `primary_device_default_settings_light` | `#ff0050ef` | `colors_device_defaults.xml:23` (stock `@color/primary_device_default_light` → `system_neutral1_50`) | the colour Settings' `colorPrimary` resolves to in light mode: `res/values/themes.xml:68,188,249` |
| `primary_device_default_settings` | `#ff3d7bff` | `colors_device_defaults.xml:22` (stock `@color/system_neutral1_900`) | Settings' night `colorPrimary`: `res/values-night/themes.xml:21`. (There is **no** `primary_device_default_settings_dark` at API 36.) |
| `system_primary_light` | `#ff0050ef` | `colors.xml:582` (stock `#4C5E8B`) | monet "primary" role — every M3 app |
| `system_primary_dark` | `#ff3d7bff` | `colors.xml:637` (stock `#B8C6EE`) | same, dark palette |
| `system_on_primary_light` | `#ffffffff` | `colors.xml:566` (stock `#F9F8FF`) | foreground on primary; closest real resource to the `colorOnPrimary` intent |
| `system_on_primary_dark` | `#ffffffff` | `colors.xml:621` (stock `#323F60`) | same, dark palette |
| `system_accent2_{100,200,500,600,700}[,_light,_dark]` | `#ff7fa8ff` / `#ff3d7bff` / `#ff0050ef` / `#ff002878` / `#ff00143c` | plain: `colors_dynamic.xml:35,36,39,40,41`; `_light`: `colors.xml:274,276,282,284,286`; `_dark`: `colors.xml:430,438,…` | monet **secondary** seed; pinned to the accent1 ramp so M3 apps do not introduce a second hue |
| `system_accent3_{100,200,500,600,700}[,_light,_dark]` | same five | plain: `colors_dynamic.xml:48,49,52,53,54`; `_light`: `colors.xml:300,…`; `_dark`: `colors.xml:456,464,…` | monet **tertiary** seed, same reasoning |

30 + 6 = 36 new entries. Steps `0,10,50,300,400,800,900,1000` of accent2/accent3
keep their stock tones — only the five steps that `ztmetro_colors.xml` already
pins for `system_accent1_*` are overridden, so the ramps stay internally
consistent.

### 3c. The M3 switch/slider these roles unblock

Settings' switch preference widget is a Material3 switch, not an AppCompat one:

```
packages/apps/Settings/res/layout/preference_widget_switch_compat.xml:18
    <com.google.android.material.materialswitch.MaterialSwitch ...>
```

`MaterialSwitch` tints its checked track with `?attr/colorPrimary` and its handle
with the library's on-primary role. Settings' theme supplies the former from
framework-res:

```
packages/apps/Settings/res/values/themes.xml:68,188,249   colorPrimary = @*android:color/primary_device_default_settings_light
packages/apps/Settings/res/values-night/themes.xml:21     colorPrimary = @*android:color/primary_device_default_settings
```

so §3b entries 1–2 are exactly the switch accent (`#0050EF` light / `#3D7BFF`
dark). The framework `SeekBar` used by `SettingsSeekBarPreference` and every
AppCompat control keep using `?attr/colorControlActivated` → `?attr/colorAccent`
→ `accent_device_default_*`, already cobalt since v1 (§5 table).

## 4. aapt2 output excerpt (full log: `aapt2-dump-resources.txt`)

```
Binary APK
Package name=org.ztmetro.overlay.framework id=7f
  type bool id=01 entryCount=2
    resource 0x7f010000 bool/config_actionMenuItemAllCaps
      () false
    resource 0x7f010001 bool/config_buttonTextAllCaps
      () false
  type color id=02 entryCount=63
    resource 0x7f020000 color/accent_device_default
      () #ff0050ef
    resource 0x7f020002 color/accent_device_default_light
      () #ff0050ef
    resource 0x7f020003 color/accent_material_dark
      () #ff3d7bff
    … system_accent1_{100,200,500,600,700}[_light|_dark] = #ff7fa8ff/#ff3d7bff/#ff0050ef/#ff002878/#ff00143c
    resource 0x7f02000b color/primary_device_default_settings_light
      () #ff0050ef
    resource 0x7f02001d color/system_accent2_100
      () #ff7fa8ff
    resource 0x7f02003e color/system_primary_light
      () #ff0050ef
  type dimen id=03 entryCount=7
    resource 0x7f030000 dimen/action_bar_default_height_material
      () 48.000000dp
    resource 0x7f030002 dimen/config_buttonCornerRadius
      () 0.000000dp
    resource 0x7f030006 dimen/dialog_corner_radius
      () 0.000000dp
  type style id=04 entryCount=20
    resource 0x7f040003 style/TextAppearance.Material.Button
      () (style) size=6 parent=0x010301ed
        0x01010095=13.000000sp          # android:textSize
        0x01010098=?0x01010036          # android:textColor -> ?attr/textColorPrimary
        0x01010218=1.3846               # android:lineSpacingMultiplier
        0x0101038c=false                # android:textAllCaps
        0x010103ac="sans-serif"         # android:fontFamily
        0x010104b6=0.000000e+00         # android:letterSpacing
    resource 0x7f040010 style/TextAppearance.Material.Widget.ActionBar.Subtitle
      () (style) size=6 parent=0x010301ed
        0x01010095=11.500000sp
        0x01010098=?0x01010038          # ?attr/textColorSecondary
        0x01010218=1.3913
        0x0101038c=true
        0x010103ac="sans-serif-medium"
```

Manifest (`aapt2 dump xmltree … --file AndroidManifest.xml`):

```
E: manifest
  A: package="org.ztmetro.overlay.framework"
  E: application  A: android:hasCode=false
  E: overlay      A: android:targetPackage="android" (Raw: "android")
                  A: android:isStatic=true  A: android:priority=2
```

Package id check: every `parent=` in a style is a **framework** id
(`0x0103003e` = `style/TextAppearance`, `0x010301ed` =
`style/TextAppearance.Material`, verified against
`aapt2 dump resources $ANDROID_SDK/platforms/android-36/android.jar`), and every
item id is a framework attr id (`0x0101….`). The overlay therefore never
references a resource that only exists in its own package — the reference class
that RROs cannot resolve safely (`docs/10b` §3).

`unzip -l`: `AndroidManifest.xml`, `resources.arsc`, `META-INF/*` only — no
`classes.dex`, so `hasCode="false"` is literally true.

## 4b. Quick Settings tile fills (private framework colours, cross-branch fact)

A `com.android.systemui` overlay cannot set `shadeActive`/`shadeInactive`: those
are **attrs** whose values are private framework colours,
`@androidprv:color/customColorShadeActive` / `customColorShadeInactive`
(`packages/SystemUI/res/values/styles.xml:550-556`, recorded from the
SystemUI branch in `evidence/sysui-rro/REPORT.md:203-211` — SystemUI-side fact,
not re-fetched here). The colours themselves live in framework-res, so
this overlay overrides them — the SystemUI overlay renders the shade flat black
and squares the tiles, and these five entries give the tiles their Metro fill:

| resource | value | role |
|---|---|---|
| `customColorShadeActive` | `#FF0050EF` (accent base) | active tile fill |
| `customColorShadeInactive` | `#FF1F1F1F` (`neutrals.surface_off`) | inactive tile fill — Metro "quick action off", stays dark on the black shade |
| `customColorShadeDisabled` | `#FF00143C` (accent dark2) | disabled tile fill |
| `customColorOnShadeActive` | `#FFFFFFFF` (`accent.foreground_on_accent`) | label/icon on an active tile — the stock `#516391` is invisible on cobalt |
| `customColorOnShadeActiveVariant` | `#FFD9E2FF` (framework tone, `colors.xml:688`) | secondary icon on an active tile; stock `#334671` is invisible on cobalt |

Names and lines: `colors_dynamic.xml:171,172,177,178,179`. There is **no
`customColorShadePressed`** at API 36: `grep customColorShade` over
`core/res/res/values` returns exactly three declarations
(`customColorShadeActive`/`Disabled`/`Inactive`, `:177-179`), and the only other
shade colours are the four `customColorOnShade*` (`:171-174`). The pressed state
is the ripple, already accent-tinted via `ripple_material_*`; `customColorShadeDisabled`
is the closest "disabled twin". `customColorOnShadeInactive`
(`#E1E2ED`) is left at stock: it is legible on `#1F1F1F` (≈12:1). These are
*private* resources, but defining the same name in an overlay needs no reference
to the private symbol, and a preinstalled overlay may override it (framework-res
declares no overlayable, §1).

## 5. Deliberately NOT overridden, and why

Theme **attrs** (`colorAccent`, `colorPrimary`, `colorPrimaryDark`,
`colorSecondary`, `colorControlActivated`, `colorControlHighlight`) are
declarations in `attrs.xml`, not values: an RRO replaces *resources* that the
themes point at. The resources behind them (all verified):

| attr (AOSP `attrs.xml`) | backed by | overridden? |
|---|---|---|
| `colorAccent` (:1133) | `accent_device_default_light/dark` (`themes_device_defaults.xml:216`), `accent_material_light/dark` (`themes_material.xml:399,780`) | **yes** |
| `colorControlActivated` (:1160) | `?attr/colorAccent` (`themes_material.xml:403`) | **yes, transitively** (switch/checkbox/radio/text-field tint) |
| `colorControlHighlight` (:1163) | `ripple_material_light/dark` (`themes_material.xml:405,786`) | **yes** (accent ripple) |
| `colorPrimary` (:1122) | general: `primary_device_default_light/dark` (`themes_device_defaults.xml:214`); **Settings**: `primary_device_default_settings_light` / `…settings` (`themes_device_defaults.xml:2284,2388`, Settings' own `themes.xml:68,188,249` + `values-night/themes.xml:21`) | **partly — on purpose.** The general `primary_device_default_*` stay stock: they are the app-bar *surface* of every DeviceDefault app, and a cobalt surface breaks `textColorPrimary` contrast. The two Settings-scoped colours **are** overridden (§3b), because they are referenced by the DeviceDefault *Settings* themes only (7 sites, all Settings) and because that is exactly the value the Material3 switch/slider inside Settings reads as `?attr/colorPrimary` (§3c). Trade-off: Settings' app bar becomes the accent too — see §7. |
| `colorPrimaryDark` (:1126) | `primary_dark_device_default_*`; Settings variant `primary_dark_device_default_settings(_light)` (`colors_device_defaults.xml:26-27`) | **no** — status-bar surface. Metro's status bar is black, which is the SystemUI/launcher deliverable, not a framework accent. |
| `colorSecondary` (:1129) | `secondary_device_default_settings*` (`colors_device_defaults.xml:28-29`, `themes_device_defaults.xml:2286`) | **no** — a *surface/container* role in `Theme.DeviceDefault.Settings`; tinting it cobalt would repaint dialogs and sheets. The M3 secondary *hue* is handled through the monet seed `system_accent2_*` instead (§3b). |
| `colorOnPrimary` | **does not exist** at API 36 — neither as `<attr>` nor as `<color>` (`grep 'name="colorOnPrimary"' attrs.xml` → 0 hits) | omitted as a name; its intent is served by `system_on_primary_light/dark` (§3b), which are real framework colours (`colors.xml:566,621`) that M3 widgets read for the on-primary role. |
| `colorAccentVariant` | **does not exist** in `attrs.xml` at API 36 | omitted (would be a no-op) |

`colorControlHighlighted` does not exist either; the real attribute is
`colorControlHighlight` (`attrs.xml:1163`).

Why `<color name="colorPrimary">` would have been a silent no-op (and was removed
from the first cut of `ztmetro_accent_roles.xml`): RRO replacement is keyed on
(type, name). framework-res has an **attr** named `colorPrimary` and no **colour**
of that name, so a `color/colorPrimary` entry in the overlay has nothing to map to
and idmap2 drops it. The same holds for `colorPrimaryDark`, `colorSecondary`,
`colorAccent`, `colorControlActivated`, `colorControlHighlight` and for
`colorOnPrimary` (which does not exist at all). The build is not what catches
this — `tools/check_names.py` is (§2).

Controls — names in the brief that do not exist, or cannot be changed by values:

- `Widget.Material.Switch` — **does not exist**. The framework name is
  `Widget.Material.CompoundButton.Switch` (`styles_material.xml:669`), reached
  through `Widget.DeviceDefault.CompoundButton.Switch` (`styles_device_defaults.xml:128`).
  Not overridden: its `track`/`thumb` are drawables and the pill radius is
  hardcoded at `drawable/switch_track_material.xml:23` (`<corners radius="7dp">`),
  so no values-only overlay can square it. Accent still reaches it through
  `colorControlActivated`.
- `Widget.Material.SwitchCompat` — appcompat class, not framework. Same for
  `Widget.Material.TextField*` (Material Components library); framework's field
  style is `Widget.Material.EditText` (`styles_material.xml:679`), whose
  background is the nine-patch `edit_text_material.xml` tinted by
  `colorControlActivated` — the accent change is the only values-level knob, so
  the style itself is not overridden.
- `Widget.Material.Button` / `.Borderless` / `.Colored` — **no override
  needed**, and one is impossible in place: an overlay style cannot parent the
  very resource it replaces. Its only knobs are `background` /
  `stateListAnimator` (drawables — out of the values-only scope) plus
  `minHeight` 48dip (already Metro's `min_touch_target_dp`) and `minWidth`
  88dip. Its **label** is `TextAppearance.Material.Button` (overridden: 13sp,
  sentence case), its **corners** come from `config_buttonCornerRadius` /
  `control_corner_material` → 0dp, its **ripple** from `ripple_material_*`.
- `Widget.Material.Toolbar` (`styles_material.xml:909`) and
  `Widget.Material.ActionBar` (:1013) — not overridden: they carry
  `titleTextAppearance`/`subtitleTextAppearance` pointing at
  `TextAppearance.Material.Widget.ActionBar.{Title,Subtitle}` (overridden) and
  their height comes from `action_bar_default_height_material` (overridden).
  Replacing the widget style would only re-copy those items.
- `Widget.Material.PopupMenu` (:955) — the 2dp popup radius is hardcoded in
  `drawable/popup_background_material.xml:21`; menu text is
  `TextAppearance.Material.Menu` (overridden to 17sp).
- `Theme.Material.Dialog.Alert` / `Theme.Material.Light.Dialog.Alert` — at
  `android-16.0.0_r3` both are **empty aliases** of
  `Theme.Material(.Light).Dialog.BaseAlert` (`themes_material.xml:1219,1332`);
  overriding the alias would be a no-op, and re-declaring the whole dialog theme
  is the one change class that cannot be verified without a device. The visible
  dialog knobs *are* overridden instead: `config_dialogCornerRadius` /
  `dialog_corner_radius` → 0dp, dialog titles inherit
  `TextAppearance.Material.Title` → 20sp semilight, buttons inherit
  `TextAppearance.Material.Button` → 13sp sentence case.
- `config_*` dimensions other than the four corner radii: not touched. They are
  behavioural knobs read by framework code (`config_minScrollbarWidth`,
  hyphenation, window sizes …); only corner radii were judged safe.
- `system_neutral*` / `textColorPrimary*` / `config_*FontFamily` — not
  overridden. Neutrals would repaint every surface (and fight Lineage's
  dark/light theme); the `config_*FontFamily` strings belong to the separate
  **font module** deliverable (overriding them here would make two overlays
  fight over the same resource). Consequence: for `Theme.DeviceDefault.*`
  consumers the *family* is re-applied by `TextAppearance.DeviceDefault.*`
  (`styles_device_defaults.xml:257-323`), so in stock Settings the visible
  Metro effect is **size / line height / caps**, and the family follows the ROM
  font until the font module lands. [E]

## 6. Expected visible effects

Stock Settings (`Theme.DeviceDefault.*` / AndroidX Material views) [E] — not yet
device-tested:

1. **Accent = cobalt #0050EF** on switches, checkboxes, radio buttons, text-field
   underlines, progress bars, links, list-arrow tints, and the ripple/pressed
   state (dark theme uses the token's `light1` `#3D7BFF` so controls stay legible
   on black). Material You consumers read the same hue from
   `system_accent1_100…700`, and — new in v2 — from the M3 roles
   `system_primary_*`, `system_on_primary_*`, `system_accent2_*`,
   `system_accent3_*` (§3b).
   **Material3 widgets in Settings** (v2): the switch preference is a real
   `MaterialSwitch` (`Settings/res/layout/preference_widget_switch_compat.xml:18`)
   whose checked track is `?attr/colorPrimary`; that attribute resolves to
   `primary_device_default_settings_light` in light mode and
   `primary_device_default_settings` in dark mode, both overridden now — so the
   switch (and any M3 Slider on the same theme) turns cobalt instead of the stock
   near-white/neutral track. Side effect: Settings' app bar and the
   `?android:attr/colorPrimary`-tinted dashboard icons
   (e.g. `Settings/res/drawable/ic_settings_wireless.xml:25,29`) take the same
   colour — see §7.
   **Quick Settings**: active tiles are cobalt with white labels, inactive tiles
   `#1F1F1F` on the black shade, disabled tiles accent-dark2 — the part the
   SystemUI overlay cannot reach (§4b).
2. **Square geometry**: buttons 4dp→0, alert dialogs 28dp→0 (Material themes
   2dp→0), bottom sheets 16dp→0, linear progress bars pill→square.
3. **Sentence case**: button labels and overflow/action-bar menu items lose
   ALL CAPS (`config_buttonTextAllCaps`, `config_actionMenuItemAllCaps`).
4. **Metro type ramp**: body 13sp/1.38, title 20sp semilight, headline 22,
   list title 25.5, page title 48, splash 56, lock clock 92; dialog titles 20sp;
   notification title 17sp; app-bar title 20sp semilight with an 11.5sp
   semibold ALL-CAPS subtitle.
5. **Compact chrome**: Material app bars 56dp→48dp (DeviceDefault was already
   48dp), matching ztmetro's 48dp touch floor.

The QS tile colours are only visible if the SystemUI overlay
(`evidence/sysui-rro/`) is installed too: SystemUI reads
`@androidprv:color/customColorShade*` through those attrs.

Plain-View / minimal app (no AndroidX, `Theme.Material.*` or
`Theme.DeviceDefault.*`) [E]: `Button` renders square with a 13sp sentence-case
label and a cobalt ripple; `Switch` gets a cobalt track/thumb while keeping its
Material capsule; `ProgressBar` (horizontal) is square; `AlertDialog` is square
with a 20sp semilight title and 13sp buttons; `TextView` with
`textAppearanceMedium`/`Large`/`Small` keeps its own size unless the app uses
`TextAppearance.Material.*` (then the ramp applies); `Toolbar` loses 8dp of
height and its title becomes 20sp semilight.

## 7. Not verified / limitations

**v2-specific, read before shipping:**
- **Settings' app bar turns accent.** `primary_device_default_settings_light` /
  `…settings` are the Settings theme's `colorPrimary`, used both by M3 widgets and
  by the `AppBarLayout`/Toolbar fill. That is the price of the switch accent: one
  colour resource, two consumers. If a black/neutral Settings app bar is wanted
  instead, this pair must come out — and then the M3 switch falls back to the
  neutral track, because a Settings-theme-level split is not reachable from an
  RRO. The `?android:attr/colorPrimary`-tinted dashboard icons follow the same
  colour for the same reason.
- `system_on_primary_dark` = `#FFFFFF` on `#3D7BFF` is **4.1 : 1** — fine for the
  switch thumb / icons (the token's own on-accent colour) but below AA for small
  body text. No body text uses the on-primary role in Settings.
- `system_accent2_*`/`system_accent3_*` are pinned to the accent1 ramp only on the
  five steps accent1 already overrides; the remaining tones of those palettes are
  stock, so a component that mixes step 300 with the overridden 200 will show a
  hue step. Accepted for now; the alternative (a hand-built 13-step ramp) invents
  tones the token file does not define.
- **No device test of v2.** Same as v1: idmap mapping, boot behaviour of the
  static overlay and every visual line remain `[E]`. Host checks are the 92-name
  verification, the compiled values, the manifest flags and the absence of dex.

- **No device test.** The idmap mapping itself, the boot behaviour of an
  immutable overlay and every visual line above are `[E]`. Host checks cover:
  name existence (§3), compiled values (`aapt2 dump resources`), manifest flags,
  absence of dex.
- `idmap2` is not part of the host build-tools here, so a real
  `idmap2 create --target-apk framework-res.apk --overlay-apk …` was not run;
  on device the checks are `cmd overlay list`, `cmd overlay dump` and logcat for
  `idmap2 … not allowed to overlay resource`.
- **Static overlay = immutable.** There is no runtime off switch; rollback is
  Magisk module removal. If `/product/overlay/config/config.xml` already exists
  on the device, `isStatic`/`priority` are ignored and the package must be listed
  there (`<overlay package="org.ztmetro.overlay.framework" mutable="false"
  enabled="true"/>`) or it stays disabled — `[?]` for lineage-23.2.
- LineageOS' own Styles/ThemePicker accent overlays also target `android` and
  are installed later, so a user-picked accent would win over this overlay —
  including the QS tile fills, because the monet overlay redefines
  `customColorShade*` too
  (`[E]` from overlay precedence). Do not use the Styles accent picker if this
  overlay is meant to hold.
- A framework overlay is applied in zygote to every process — the highest
  blast-radius change in the project. First boot after install is the real test.
- `letterSpacing` cannot be sourced from the tokens (no field); it is 0.
- Switch capsule and popup 2dp radius need a **drawable** overlay (separate
  deliverable) if Metro squareness is required there.

## 8. Reproduce / regenerate

```sh
# regenerate the four token-derived values files (ztmetro_accent_roles.xml is
# hand-maintained and must NOT be generated: it is not a token projection)
python3 repo/overlay/framework/gen_overlay.py
python3 repo/overlay/framework/gen_overlay.py --check   # fail if stale

# full build anywhere with SDK + JDK
ANDROID_SDK_ROOT=/path/to/sdk JAVA_HOME=/path/to/jdk \
  repo/scripts/build-overlay-framework.sh --out /tmp/ztmetro-overlay

# name verification: --aosp-values is the core/res/res/values mirror of the tag
# (config, dimens, colors*, styles*, attrs, themes*), --aosp-color the res/color
# directory (the two ripple_material CSLs). Then:
python3 repo/overlay/framework/tools/check_names.py \
  --apk /tmp/ztmetro-overlay/ZtMetroFramework.apk \
  --aapt2 "$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2" \
  --aosp-values /tmp/aosp/values --aosp-color /tmp/aosp/color \
  --csv /tmp/verified-resources.csv
```
