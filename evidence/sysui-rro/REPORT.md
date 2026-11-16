# ztmetro SystemUI RRO — evidence report

Metro / Action-Centre skin for the **stock** notification shade + Quick Settings of
LineageOS 23.2 (Android 16) on the Redmi Note 5A (`ugglite`, 720×1280).
Deliverable: a preinstalled **Runtime Resource Overlay** targeting `com.android.systemui`.
No APK replacement, no code, no new resources in the target.

| item | value |
|---|---|
| overlay source | [`repo/overlay/systemui/`](../../overlay/systemui/) |
| build script | [`repo/scripts/build-overlay-sysui.sh`](../../scripts/build-overlay-sysui.sh) |
| APK | `repo/evidence/sysui-rro/ztmetro-systemui-rro.apk` (12 719 B) |
| APK sha256 | `9151c7f6f137296b187183c9fff5dad22241a1c9d22ac12774af0eb50ce01c2e` |
| package / target | `org.ztos.overlay.systemui` → `com.android.systemui` (`hasCode=false`, **no** `targetName`) |
| signer | throwaway RSA-2048, `CN=ztmetro overlay (throwaway)`, cert sha256 `16e91082…3e` |
| override entries | **25** (11 dimen, 6 color × {default, night}, 7 style, 1 drawable) |
| target commit verified against | `LineageOS/android_frameworks_base` `lineage-23.2` = `797e43244b96c6a0dff5c81b21c4d4be665b082e` (**same commit the research map pins**) |

Raw evidence in this directory: [`evidence-verify.txt`](evidence-verify.txt) (target
definitions + consumer lines, generated from a sparse git clone at that commit),
[`aapt2-dump-resources.txt`](aapt2-dump-resources.txt),
[`aapt2-dump-badging.txt`](aapt2-dump-badging.txt), [`build-log.txt`](build-log.txt).

Labels: `[F]` verified in source at the commit above, `[E]` expected visual result /
engineering estimate, `[?]` needs the device.

---

## 1. Verified override table

Paths are relative to `packages/SystemUI/`. "old" is the target value at
`797e4324…`; "consumers" are the actual reads found by grep in that tree (the same
commit), i.e. these overrides are not guesses.

### 1.1 Dimensions — `res/values/dimens.xml`, `res/values/shade_dimens.xml`

| resource | target file:line | old | new | consumers [F] | expected visual change [E] |
|---|---|---|---|---|---|
| `qs_corner_radius` | dimens.xml:726 | `28dp` | `0dp` | QSTileViewImpl.kt:1038,1056,1069; qs_tile_background_shape.xml:19 | every QS tile becomes a hard square, incl. the press-corner animation |
| `qs_tile_height` | dimens.xml:727 | `80dp` | `68dp` | TileLayout.java:37; TileAdapter.java:146,913 | shorter, denser tile grid (Metro action-centre row is 57 dp) |
| `qs_tile_padding` | dimens.xml:758 | `12dp` | `10dp` | QSTileViewImpl.kt:238,279; TileLayout.java:268; QuickQSPanel.java:198 | icon/label sit tighter inside the square |
| `qs_tile_start_padding` | dimens.xml:759 | `16dp` | `12dp` | QSTileViewImpl.kt:239,280 | label/icon move left, Metro left-aligned tile content |
| `qs_tile_margin_horizontal` | dimens.xml:728 | `8dp` | `4dp` | TileLayout.java:156; InfiniteGridLayout.kt:106,129; QuickQuickSettings.kt:61,85; NotificationStackScrollLayout.java:1004 | 4 dp gutters (token `action_centre_gap_dp`-class), near-full-bleed QS grid |
| `qs_tile_margin_vertical` | dimens.xml:729 | `@dimen/qs_tile_margin_horizontal` | `4dp` | TileLayout.java:158; QSPanel.java:127; QSFragmentCompose.kt:1511-1552 | symmetric 4 dp vertical gaps |
| `qs_tile_text_size` | dimens.xml:767 | `14sp` | `13sp` | QSTileViewImpl.kt:270-271; QuickQSPanel.java:195; TileAdapter.java:914 | Metro body-size tile labels (token `body` 13 sp) |
| `notification_corner_radius` | dimens.xml:348 | `28dp` | `0dp` | NotificationStackScrollLayout.java:1008,1047; MediaContainerView.kt:49; MediaControlPanel.java:1389; AODPromotedNotification.kt:130 | square notification stack / media container corners — the visible "rounded shade" cue that does move |
| `notification_corner_radius_small` | dimens.xml:357 | `4dp` | `0dp` | StackScrollAlgorithm.java:128 | square small-radius stack children |
| `overlay_shade_panel_shape_radius` | shade_dimens.xml:28 | `@dimen/aux_spacing_overlay_panel_shape_radius` | `0dp` | **none** (grep of `packages/SystemUI/{src,res,res-keyguard,res-product}` + `libs/WindowManager/Shell`: 0 hits) | **inert** at this commit — row kept because the brief names it |
| `aux_spacing_overlay_panel_shape_radius` | shade_dimens.xml:32 | `46dp` | `0dp` | 0 (only aliased by the row above) | inert, same reason |

`shade_panel_width` (shade_dimens.xml:20, `600dp`) is **not overridden**: on this panel
(360 dp wide) 600 dp already exceeds the screen, so the shade is full-bleed already;
overriding would only change ≥600 dp machines and is a no-op here [F: value + width].

### 1.2 Colours — `res/values/colors.xml` (+ `res/values-night/colors.xml`)

Night twins are **mandatory**: the target has night variants for every one of these
(`res/values-night/colors.xml:26,27,31,135,138,139`), and an RRO value only replaces the
configuration it declares. The overlay ships both configs with the same flat literals
(aapt2 dump proves it: each colour entry has `()` and `(night)`).

| resource | target file:line (default / night) | old | new | consumers [F] | expected visual change [E] |
|---|---|---|---|---|---|
| `shade_panel_fallback` | colors.xml:43 / night:138 | `@android:color/system_accent2_200` / `…accent2_800` | `#FF000000` | ShadeColors.kt:76 (blur **unsupported** branch), BrightnessSlider.kt:572 | the shade panel becomes flat opaque black; brightness slider chip follows |
| `notification_scrim_fallback` | colors.xml:44 / night:139 | `@android:color/system_surface_dim_light` / `…_dark` | `#FF000000` | ShadeColors.kt:89 | notification area behind cards is black, no grey tonal wash |
| `notification_scrim_base` | colors.xml:40 / night:135 | `@android:color/system_accent1_100` / `…_800` | `#FF000000` | ShadeColors.kt:82 (blur branch, applied at **50 % alpha**) | darkens the blur path too; not fully flat when blur is on |
| `notification_legacy_background_color` | colors.xml:84 / night:31 | `#ff1a1a1a` / `@color/GM2_grey_900` | `#FF1F1F1F` | NotificationCustomViewWrapper.java:38 | legacy/custom-view notification cards use Metro `surface_off` |
| `shade_header_text_color` | colors.xml:28 / night:26 (night entry is feature-flagged) | `#FF000000` (black on the light lavender panel) | `#FFFFFFFF` | ShadeHeaderController.kt:415-427 (`getFgColor`), and `TextAppearance.QS.Status` item | header clock/date stay readable on the black panel (without this they are black-on-black) |
| `shade_header_text_color_bg` | colors.xml:30 / night:27 | `#FFFFFFFF` | `#FF000000` | ShadeHeaderController.kt:416 (`getBgColor`) | "on light background" twin inverted consistently |

Why these three are *the* shade background: there is no shade background drawable.
`ShadeColors.kt:33-90` picks, per **blur support**:
`blur unsupported → shade_panel_fallback / notification_scrim_fallback`;
`blur supported → composite(framework shade_panel_fg, shade_panel_bg)` and
`notification_scrim_base @50 %`. Called from `ScrimController.java:1695-1699`
(`isBlurCurrentlySupported()`). So: **flat black on a blur-off device** (expected on a
2 GB ugglite [E], confirm on device), *semi*-flat if blur is enabled.

### 1.3 Typography — `res/values/styles.xml`

An RRO replaces a style **bag wholesale** (`TextAppearance` styles are bags, not values),
so each style below re-declares the items the target had plus the Metro change; `parent=""`
is used where the implicit dot-parent exists only in the target
(`TextAppearance.StatusBar.Expanded`) and is therefore not linkable in a standalone
overlay build.

| style | target file:line | old body | new body | consumers [F] | expected visual change [E] |
|---|---|---|---|---|---|
| `TextAppearance.QS.TileLabel` | styles.xml:164-169 | 14sp, letterSpacing .01, lineHeight 20sp, `config_bodyFontFamilyMedium` | 13sp, letterSpacing 0, lineHeight 18sp, `sans-serif`, `textAllCaps=false` | qs_tile_label.xml:42; QSTileViewImpl.kt:270 (size dimen too) | regular-weight, un-tracked, non-caps tile labels at Metro body size |
| `TextAppearance.QS.TileLabel.Secondary` | styles.xml:171-173 | `config_bodyFontFamily` | 10sp, `sans-serif`, tracking 0 (inherits the overlay TileLabel) | qs_tile_label.xml:56 | app sub-label shrinks to a whisper (Metro tiles have no second line; it cannot be hidden by a style — `visibility` is not a TextAppearance attr) |
| `TextAppearance.StatusBar.Expanded.Clock` | styles.xml:37-42 | `qs_time_expanded_size` (14sp), headlineMedium, textColorPrimary, normal | 28sp, `sans-serif-light`, letterSpacing −0.02, textColorPrimary | **0 consumers** → inert (kept per brief) | none at 23.2 [F] |
| `TextAppearance.StatusBar.Expanded.Date` | styles.xml:44-49 | 14sp, body family, textColorPrimary | 14sp, `sans-serif`, tracking 0 | **0 consumers** → inert | none at 23.2 [F] |
| `TextAppearance.QS.Status` | styles.xml:180-186 | headline family (feature-flagged `variable-body-medium-emphasized`), textColor `@color/shade_header_text_color` (#000), 14sp, tracking .01 | 20sp, `sans-serif-light`, tracking 0, lineHeight 27sp, textColor → overlay's white `shade_header_text_color` | ShadeHeaderController.kt:335-338, 553-556 (clock, date, carrier group); shade_carrier_group.xml:35 | Metro Semilight header: 20 sp white clock/date (they share ONE style, so 28 sp was not possible without inflating the date too) |
| `TextAppearance.QS.Status.Clock` | styles.xml:188-191 | feature-flagged `variable-display-small-emphasized` | 30sp, `sans-serif-light`, tracking −0.02 | combined_qs_header.xml:88 | big light clock wherever the combined header layout is used |
| `TextAppearance.QS.Status.Build` | styles.xml:193-195 | textColor `?attr/onShadeInactiveVariant` | 11sp, `#FF9E9E9E`, tracking 0 | QSFooterView.java:135 | Metro caption-grey build/status line in the QS footer |

### 1.4 Drawable — `res/drawable/qs_tile_background_shape.xml`

| resource | target | old | new | expected visual change [E] |
|---|---|---|---|---|
| `qs_tile_background_shape` | drawable/qs_tile_background_shape.xml:18-21 | `<corners android:radius="@dimen/qs_corner_radius"/>` (28 dp) + `<solid #FFFFFF>` | `<corners android:radius="0dp"/>` + `<solid #FFFFFF>` (inline literals, no cross-package ref) | square tile body **and** square ripple mask; `QSTileViewImpl` still tints the compiled `GradientDrawable` (base id `qs_tile_background_base`, QSTileViewImpl.kt:336-347,819-837) |

`qs_tile_background` (ripple → layer-list → shape, reference `@color/qs_tile_ripple_color`
= `res/color/qs_tile_ripple_color.xml`) is deliberately **not** overridden, so ripple,
hover and focus behaviour stay stock — the only thing that changes is the geometry of the
shape they point at. Note the shape is also used by `qs_tile_background_flagged.xml:20-29`.

---

## 2. Build

Heavy build ran on `ssh hermes-ubuntu`, workdir `~/ztux/sysui-rro`,
`~/android-sdk` (build-tools 36.0.0, platform android-36), JDK `~/android-jdk`.

One command (idempotent; from the Mac it rsyncs the sources, builds remotely and copies
the APK back; on the build host it builds locally and prints the path):

```sh
repo/scripts/build-overlay-sysui.sh
# -> /Users/meow/…/repo/evidence/sysui-rro/ztmetro-systemui-rro.apk
```

What the script runs (aapt2 path is the only difference): 

```sh
$AAPT2 compile --dir overlay/systemui/res -o $WORK/res.zip
$AAPT2 link -o $WORK/unsigned.apk -I ~/android-sdk/platforms/android-36/android.jar \
  --manifest overlay/systemui/AndroidManifest.xml \
  --min-sdk-version 34 --target-sdk-version 36 \
  --no-resource-deduping --no-resource-removal $WORK/res.zip
$ZIPALIGN -p -f 4 $WORK/unsigned.apk $WORK/aligned.apk
keytool -genkeypair -keystore ~/.ztmetro-overlay-debug.keystore -alias ztmetro \
  -storepass ztmetro -keypass ztmetro -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=ztmetro overlay (throwaway)"
$APKSIGNER sign --ks ~/.ztmetro-overlay-debug.keystore --ks-pass pass:ztmetro \
  --key-pass pass:ztmetro --min-sdk-version 34 --out ztmetro-systemui-rro.apk $WORK/aligned.apk
$APKSIGNER verify --min-sdk-version 34 ztmetro-systemui-rro.apk
```

The overlay is **self-contained by construction**: it never references a
`com.android.systemui` resource, only framework `?android:attr/…` attrs that
`android.jar` exports, plus its own names. That is the workaround for "SystemUI names are
not in android.jar": instead of a stub/dummy target APK (which would have to reproduce
the target's whole resource table), every Metro value is a literal, and cross-resource
links inside the overlay (style → `@color/shade_header_text_color`, style → parent style)
point at names this APK itself defines and that the target also defines — exactly the
name-matched entries idmap2 maps (docs/10b §3a). No `@androidprv:` private references,
no `@*android:string/…` private strings, no target drawables.

### aapt2 excerpt (full file: `aapt2-dump-resources.txt`)

```
Package name=org.ztos.overlay.systemui id=7f
  type color id=01 entryCount=6
    resource 0x7f010000 color/notification_legacy_background_color
      () #ff1f1f1f
      (night) #ff1f1f1f
    resource 0x7f010003 color/shade_header_text_color
      () #ffffffff
      (night) #ffffffff
    resource 0x7f010005 color/shade_panel_fallback
      () #ff000000
      (night) #ff000000
  type dimen id=02 entryCount=11
    resource 0x7f020004 dimen/qs_corner_radius
      () 0.000000dp
    resource 0x7f020005 dimen/qs_tile_height
      () 68.000000dp
    resource 0x7f02000a dimen/qs_tile_text_size
      () 13.000000sp
  type drawable id=03 entryCount=1
    resource 0x7f030000 drawable/qs_tile_background_shape
      () (file) res/drawable/qs_tile_background_shape.xml type=XML
  type style id=04 entryCount=7
    resource 0x7f040000 style/TextAppearance.QS.Status
      () (style) size=5
        0x01010095=20.000000sp          # android:textSize
        0x01010098=@color/shade_header_text_color
        0x010103ac="sans-serif-light"   # android:fontFamily
        0x010104b6=0.000000e+00         # android:letterSpacing
        0x0101057f=27.000000sp          # android:lineHeight
    resource 0x7f040003 style/TextAppearance.QS.TileLabel
      () (style) size=5
        0x01010095=13.000000sp
        0x0101038c=false                # android:textAllCaps
        …
```

Badging:

```
package: name='org.ztos.overlay.systemui' versionCode='1' versionName='1.0' … compileSdkVersion='36'
application: label='ztmetro SystemUI overlay' icon=''
overlay: targetPackage='com.android.systemui' priority='10' isStatic='false'
```

---

## 3. What is NOT reachable without code / a different target

### 3.1 QS tile active/inactive **fill** — the honest limitation

`shadeActive` / `shadeInactive` are **attrs**, not colours with a value you can replace.
The theme that sets them is `Theme.SystemUI.QuickSettings` (styles.xml:533) and its items
are (styles.xml:550-556):

```xml
<item name="shadeActive">@androidprv:color/customColorShadeActive</item>
<item name="onShadeActive">@androidprv:color/customColorOnShadeActive</item>
<item name="shadeInactive">@androidprv:color/customColorShadeInactive</item>
<item name="onShadeInactive">@androidprv:color/customColorOnShadeInactive</item>
```

i.e. **framework-res private colours** (package `android`), read once per tile in
`QSTileViewImpl.kt:123-145`. Consequences, exactly:

* A SystemUI-target overlay **cannot** change them: there is no SystemUI colour resource
  in that chain, and a whole-bag replacement of `Theme.SystemUI.QuickSettings` would have
  to re-declare every other item of the QS theme (parent `@*android:style/Theme.DeviceDefault`,
  `surfaceBright`/`scHigh`/`primary`/`onSurface`/`outline`, `android:windowIsFloating`, …)
  including `@androidprv:` private refs that do not exist in `android.jar` — not buildable
  standalone and high-risk at runtime. **Not attempted** (documented, not silently skipped).
* What *is* possible, and what this overlay does, is everything around the fill: square
  shape, metrics, gutters, label typography. Accent-on-active would need either a separate
  overlay **targeting `android`** for `customColorShadeActive` (framework-res declares
  overlayables → its own policy/precedence problem, and SystemUI's `ThemeOverlayController`
  re-applies dynamic-colour overlays at runtime, so the result is `[?]`), or a SystemUI
  fork/hook that calls `setColor()` with Metro tokens. Both are outside this deliverable.

### 3.2 A Metro pivot header row in `status_bar_expanded.xml` — not done

Marked optional in the brief; it stays **not done**, for concrete reasons (docs/10b §3):

* A standalone aapt2 build has no `com.android.systemui` resource table, so a copy of
  `res/layout/status_bar_expanded.xml` cannot even link: it references
  `@layout/status_bar_expanded_plugin_frame`, `@layout/notification_stack_scroll_layout`,
  `@layout/qs_panel`, `@layout/dock_info_overlay`, `@integer/notification_panel_layout_gravity`,
  `@dimen/notification_panel_margin_horizontal`, `@string/report_rejected_touch`,
  `@drawable/rounded_bg_full`, `@dimen/status_bar_header_height_keyguard`, … None of these
  are exported by `android.jar`. (A stub target APK is the only workaround, and then every
  stub id must match the real target's ids — not something to fake blindly.)
* The root is `NotificationPanelView` and its children/ids are contract with
  `NotificationPanelViewController` (comment in the file: "This layout should always
  include a version of NotificationStackScrollLayout"). Rearranging them risks
  `NullPointerException` in code, not just a visual difference.
* A genuinely **new** header row (Metro pivot: caps app name + big title) needs new
  ids/strings/drawables, which an RRO cannot add at all — there is no target id to map
  to. That is the "new views need code" verdict of docs/10b §3b/§7.

If this is still wanted, it is a SystemUI-fork / code-hook item (in-tree build against
SystemUI's `R`, new resources compiled into the *target*), not an RRO item.

### 3.3 Other things this overlay cannot reach

* **Fonts.** No SystemUI `font/` resources are involved in the overridden styles; the
  target font families are `@*android:string/config_bodyFontFamily*` (framework-res).
  SystemUI overlay ⇒ Roboto stand-ins (`sans-serif` / `sans-serif-light`) [F]; real Segoe/
  Selawik files would need an `android`-target overlay or `fonts_customization.xml`, and
  none are shipped in `repo/fonts/` yet.
* **Blur path.** When `isBlurCurrentlySupported()` is true, the panel colour is a composite
  of framework `shade_panel_fg`/`shade_panel_bg` (ShadeColors.kt:66-72) — untouched by a
  SystemUI overlay. Only `notification_scrim_base` (50 % alpha) responds.
* **Status bar height / insets** are framework dimens (deliberately untouched — riskiest
  dimension, see map row `status_bar_height`).
* **`shade_panel_width`** is left stock (see §1.1) because it is already full-bleed here.
* **QS tile icon/label colours** are set in code from `onShadeActive`/`onShadeInactive`
  (QSTileViewImpl.kt:130-145), so they keep following the Material theme; the overlay does
  not set a `textColor` in `TextAppearance.QS.TileLabel` (the target had none either).

---

## 4. Expected before → after on the device [E]

**Before (stock LineageOS 23.2 shade):** translucent lavender/grey tonal panel with a big
corner radius; 28 dp rounded QS tiles, 80 dp tall, 8 dp gutters, medium-weight 14 sp labels
with slight positive tracking; black header clock/date at 14 sp; rounded notification cards
and media container.

**After (this overlay enabled):** full-bleed **solid black** Action Centre (blur-off path),
white 20 sp Semilight clock/date, 11 sp grey build line; **square** QS tiles 68 dp tall with
4 dp gutters and 13 sp regular, un-tracked labels; **square** notification stack, media
container and cards (cards `#1F1F1F` on black); ripple/hover geometry square but behaviour
unchanged. Tile *fills* remain Material You tonal (see §3.1) — the Metro difference is
geometry + typography + a flat black plane, not accent-on-active.

### Device checklist (not run here — no device in this session)

```sh
# 1. preinstall (Magisk: module system/overlay/ztmetro-systemui-rro.apk), reboot
# 2. enable + restart SystemUI
su 0 cmd overlay enable --user 0 org.ztos.overlay.systemui
su 0 cmd overlay dump org.ztos.overlay.systemui     # state must be enabled, no idmap error
su 0 killall com.android.systemui                   # or reboot
# 3. record blur state, it decides whether the panel is flat or semi-flat
su 0 settings get global disable_window_blurs
# 4. pull the shade down; verify: square tiles, black panel, white header, square cards
# 5. rollback: su 0 cmd overlay disable --user 0 org.ztos.overlay.systemui
```

Risks to look at on device: `qs_tile_height` 68 dp × padding 10 dp is the only metric
chosen without a layout measurement [E] — watch for label clipping on the two-line tile
variant; a 20 sp date line may ellipsize on long locales [E].

---

## 5. Corrections to `design/systemui-resource-map.json` (found while re-verifying)

1. `overlay_shade_panel_shape_radius` (row 13) is **defined but never consumed** at
   `797e4324…` — no hit in `packages/SystemUI/{src,res,res-keyguard,res-product}` or
   `libs/WindowManager/Shell`. Line numbers in the map (24-36) are also off: the file has
   `shade_panel_width` at :20 and the radius alias at :28-32.
2. `TextAppearance.StatusBar.Expanded.Clock/.Date` (rows 15) exist but have **0 consumers**
   at this commit; the live shade-header type is `TextAppearance.QS.Status`
   (clock + date + carrier group share it, ShadeHeaderController.kt:335-338), so a 28 sp
   clock there is impossible without also inflating the date.
3. The map's line for `notification_scrim_base`/`shade_panel_fallback`/`notification_scrim_fallback`
   is close (40/43/44) but it misses the **night twins** (values-night/colors.xml:135,138,139)
   which an overlay must also replace to work in dark mode — done here.
4. `shadeActive`/`shadeInactive` are attrs set to `@androidprv:` framework colours
   (styles.xml:550-556) — confirmed; a SystemUI overlay cannot set them (§3.1).
