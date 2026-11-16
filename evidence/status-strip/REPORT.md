# Status strip — RRO resources, build and verification

Scope: `repo/overlay/status/**`, built on `hermes-ubuntu` (`~/ztux/status`) with
`~/android-sdk` build-tools 36.0.0 and `~/android-jdk` (Temurin 17), signed with the
project throwaway key `~/.ztmetro-overlay-debug.keystore` (CN=ztmetro overlay).
No device/adb work was done in this task; the lead wires the APKs into the module.

Source of truth for every name: `LineageOS/android_frameworks_base` @
`797e43244b96c6a0dff5c81b21c4d4be665b082e` (`lineage-23.2`), read from an isolated
git worktree (`~/ztux/status/tree`, sparse checkout of `core/res/res/values` and
`packages/SystemUI`). Exact definition lines are captured verbatim in
[`name-verification.txt`](name-verification.txt).

## 1. Inventory — what actually controls the strip

| part | resource / code | file:line | note |
|---|---|---|---|
| bar height | `status_bar_height` = `@dimen/status_bar_height_portrait` | `core/res/res/values/dimens.xml:48` | portrait at `:52` = 24dp, default at `:44` = 24dp, landscape at `:56` follows portrait |
| icon size (system) | `status_bar_system_icon_size` 15dp | `core/res/res/values/dimens.xml:106` | read in `StatusBarIconView.java:356-360`; intrinsic `status_bar_system_icon_intrinsic_size` 17dp `:109` |
| icon size (notif.) | `status_bar_icon_drawing_size` 15dp | `packages/SystemUI/res/values/dimens.xml:441` | read in `StatusBarIconView.java:350,352` |
| icon opacity | `status_bar_icon_drawing_alpha` 90% | `packages/SystemUI/res/values/dimens.xml:449` | `PhoneStatusBarTransitions.java:49`, applied to start-side, status icons, battery and clock when the bar is opaque (`:114-122`, `:97-100`) |
| icon tint | **no resource** — hardcoded | `packages/SystemUI/src/com/android/systemui/statusbar/phone/DarkIconDispatcherImpl.java:77-85` | `newStatusBarIcons()` → `Color.BLACK`/`Color.WHITE`; otherwise SettingsLib colours. Applied via `StatusBarIconView.onDarkChanged` → `setImageTintList` (`StatusBarIconView.java:973-977`) |
| strip padding | `status_bar_padding_start` 4dp, `_top` 0dp | `packages/SystemUI/res/values/dimens.xml:463,469` | used in `res/layout/status_bar.xml:37,49,51,210` and `res/layout/keyguard_status_bar.xml:33,80` |
| clock type | `TextAppearance.StatusBar.Default` | `packages/SystemUI/res/values/styles.xml:20-25` | textSize `@dimen/status_bar_clock_size` = 14sp (`dimens.xml:178`), medium family, colour `@color/status_bar_clock_color` = `#FFFFFFFF` (`colors.xml:26`); used by `@id/clock`, `@id/clock_center`, `@id/clock_right` |
| clock colour | `status_bar_clock_color` `#FFFFFFFF` | `packages/SystemUI/res/values/colors.xml:26` | already white; only the clock text uses it |
| bar background | `@drawable/system_bar_background` → `@color/system_bar_background_opaque` `#ff000000` | `packages/SystemUI/res/values/colors.xml:21-22`, used at `res/layout/super_status_bar.xml:37` and `res/layout/navigation_bar.xml:27` | already solid black; `status_bar_container` is the `BarTransitions` background view |
| keyguard strip | `status_bar_header_height_keyguard` = `@dimen/status_bar_height` | `packages/SystemUI/res/values/dimens.xml:605`, layout `res/layout/keyguard_status_bar.xml:24,33,80` | follows the framework height; keyguard start inset is `system_icons_super_container_margin_start` 16dp (`:608`), not `status_bar_padding_start` |
| keyguard clock | Compose, not a TextView | `packages/SystemUI/src/com/android/systemui/keyguard/ui/view/layout/sections/ClockSection.kt` | `TextAppearance` does not reach the lock-screen clock |
| clock position | `LineageSettings.System.STATUS_BAR_CLOCK`, default `CLOCK_POSITION_LEFT` | `packages/SystemUI/src/com/android/systemui/statusbar/pipeline/shared/ui/binder/HomeStatusBarViewBinder.kt:100-102,593-599` | runtime setting, not a resource |
| existing ztmetro overlays | framework priority 2 (static), SystemUI priority 10 | `repo/overlay/framework/AndroidManifest.xml`, `repo/overlay/systemui/AndroidManifest.xml` | no shared resource names with this overlay; new ones use priority 20 |

## 2. Overrides (10 entries, all names verified in the trees above)

### `ztmetro-status-framework-rro.apk` — target `android`, static

| resource | file:line | old | new | expected visual change |
|---|---|---|---|---|
| `status_bar_height` | `core/res/res/values/dimens.xml:48` | `@dimen/status_bar_height_portrait` (24dp) | `28dp` | strip 24dp → 28dp = 42 → 49 px at 280 dpi; every window inset below it moves down 7 px |
| `status_bar_height_portrait` | `core/res/res/values/dimens.xml:52` | `24dp` | `28dp` | portrait/landscape `SystemBarUtils` path returns 28dp; landscape (`:56`) inherits it |
| `status_bar_height_default` | `core/res/res/values/dimens.xml:44` | `24dp` | `28dp` | fallback/default reads used by `SystemBarUtils#getStatusBarHeight` stay consistent |
| `status_bar_system_icon_size` | `core/res/res/values/dimens.xml:106` | `15dp` | `13dp` | wifi/battery/mobile glyphs drawn 26 → 23 px (scale `13/17` of the 17dp intrinsic) |

### `ztmetro-status-systemui-rro.apk` — target `com.android.systemui`, runtime-enabled

| resource | file:line | old | new | expected visual change |
|---|---|---|---|---|
| `status_bar_padding_start` | `packages/SystemUI/res/values/dimens.xml:463` | `4dp` | `8dp` | left glyph/clock block inset 7 → 14 px; more air at the left edge |
| `status_bar_padding_top` | `packages/SystemUI/res/values/dimens.xml:469` | `0dp` | `2dp` | 3.5 px top inset inside the 49 px strip; also applies to the keyguard strip |
| `status_bar_icon_drawing_size` | `packages/SystemUI/res/values/dimens.xml:441` | `15dp` | `13dp` | notification icons 26 → 23 px |
| `status_bar_icon_drawing_alpha` | `packages/SystemUI/res/values/dimens.xml:449` | `90%` | `70%` | on an opaque bar the glyphs/battery/clock render at 0.70 alpha — white reads as ≈`#B3B3B3` ("70% white"). On a transparent bar the alpha stays 1.0 |
| `TextAppearance.StatusBar.Default` | `packages/SystemUI/res/values/styles.xml:20-25` | 14sp, medium family, feature-flagged, no letterSpacing | 12sp, `sans-serif-light`, `letterSpacing 0`, `#FFFFFFFF` | clock 24.5 → 21 px, lighter stroke, no extra tracking. Applies to `clock`, `clock_center`, `clock_right` |
| `system_bar_background_opaque` | `packages/SystemUI/res/values/colors.xml:22` | `#ff000000` | `#ff000000` | **none** — deliberate pin; Lineage already paints the status bar window solid black |

`status_bar_height_landscape` (`:56`) is intentionally not overridden: it is an alias of
`status_bar_height_portrait`, so it follows the 28dp. There are SystemUI variants in
`values-sw1500dp-110dpi` and `values-sw1900dp-90dpi` (`status-bar-dimens.xml:142-168`);
the device is 720×1280 @ 280 dpi ≈ 411dp wide, so the base `values/` entries are the
ones that apply and the huge-screen variants are untouched.

No unknown name is shipped: `aapt2 dump resources` lists exactly the 10 entries above
(4 dimen in the framework APK; 1 color + 4 dimen + 1 style in the SystemUI APK), and all
10 definitions were located in the target trees first. The only entry whose old value
equals its new value is the deliberate `system_bar_background_opaque` pin.

## 3. Build and verification

Script: [`repo/scripts/build-overlay-status.sh`](../../scripts/build-overlay-status.sh)
(portable: local `aapt2`/`android.jar` if present, otherwise `ssh hermes-ubuntu` with
workdir `~/ztux/status`; reuses the throwaway key; wipes only `.build`; deterministic —
two consecutive runs produced identical sha256). Full transcript:
[`build-log.txt`](build-log.txt).

```
== [framework] compile/link/align/sign/verify ==
overlay: targetPackage='android' priority='20' isStatic='true'
== [systemui] compile/link/align/sign/verify ==
overlay: targetPackage='com.android.systemui' priority='20' isStatic='false'

== APKs ==
APK    repo/evidence/status-strip/ztmetro-status-framework-rro.apk
sha256 7a3016a05ece55be4214f2acc9b0b1d85748d3269796f1b7963bb569a12b7161
APK    repo/evidence/status-strip/ztmetro-status-systemui-rro.apk
sha256 ea9bde64b4b078f959f5b2606762801b7a8fff38a38b1d39c20206c3e4b155bf
```

- `apksigner verify --print-certs`: signature verified on both APKs, signer
  `CN=ztmetro overlay (throwaway)`, SHA-256
  `16e91082631bef535eba7ebe3a62ff3b01f47b95acd1415db151c92addcb8d3e`
  (`apksigner-verify-*.txt`).
- `aapt2 dump badging`: `overlay: targetPackage='android' isStatic='true' priority='20'`
  and `overlay: targetPackage='com.android.systemui' isStatic='false' priority='20'`.
- `aapt2 dump resources` (`aapt2-dump-resources-*.txt`): framework =
  `status_bar_height 28dp, status_bar_height_default 28dp,
  status_bar_height_portrait 28dp, status_bar_system_icon_size 13dp`;
  SystemUI = `system_bar_background_opaque #ff000000`, `status_bar_icon_drawing_alpha
  0.7`, `status_bar_icon_drawing_size 13dp`, `status_bar_padding_start 8dp`,
  `status_bar_padding_top 2dp`, `style/TextAppearance.StatusBar.Default` with
  textSize 12sp, fontFamily `sans-serif-light`, textColor `#ffffffff`,
  letterSpacing 0.
- **Verified override count: 10** (4 framework + 6 SystemUI), zero names outside the
  verified list.

Install/enable (lead):
- framework APK is static → `system/product/overlay/`, applies at boot; no `cmd overlay`.
- SystemUI APK must be preinstalled the same way (SystemUI has no `<overlayable>`, so
  idmap only accepts a preinstalled overlay) and enabled once:
  `su 0 cmd overlay enable org.ztos.overlay.status.systemui`, then restart SystemUI
  (`killall com.android.systemui` / reboot) for the new dimension/style values.
- Both APKs matter: framework insets only take effect after `system_server` re-reads
  resources (reboot), SystemUI values after a SystemUI restart.

## 4. Expected result

### Home screen (720×1280, launcher black canvas)

Before: 24dp (42 px) black strip; 4dp left inset; notification/system glyphs 15dp;
clock at left (`11:45`) in 14sp medium white; battery/wifi 15dp.

After: 28dp (49 px) black strip (+7 px); 8dp left inset (+7 px); notification and
system glyphs 13dp (26 → 23 px); clock 12sp Roboto Light, still at the left because
clock position is a Lineage setting (see §5). First row of tiles / app content shifts
down 7 px because the framework inset grows. On an opaque app bar the glyphs and clock
dim to 70% white; on the transparent home bar they stay full white.

### Lock screen

Before: keyguard header 24dp; system-icon row with 0dp top padding; 16dp start margin;
Compose clock below.

After: keyguard header 28dp and 2dp top padding (both inherited from the framework
height and `status_bar_padding_top`), glyphs 13dp. The big lock-screen clock is Compose,
so its size/weight/position are unchanged by this overlay; the strip background is the
same black `system_bar_background`.

## 5. What cannot change without a code hook (or a runtime setting)

1. **Clock position (left → right).** `HomeStatusBarViewBinder.kt:593-599` reads
   `LineageSettings.System.STATUS_BAR_CLOCK` (default `CLOCK_POSITION_LEFT`; constants
   `:100-102` 0=right, 1=center, 2=left). Not a resource. Fix by setting the Lineage
   status-bar clock setting (Settings → Status bar → Clock position, or the matching
   `settings put system …` key once its exact name is confirmed from lineage-sdk), or a
   SystemUI hook. This is the largest remaining WP gap on the strip.
2. **Per-icon colours.** No SystemUI/framework colour resource: with
   `newStatusBarIcons()` the tint is hardcoded `Color.BLACK`/`Color.WHITE`
   (`DarkIconDispatcherImpl.java:77-85`), otherwise it comes from SettingsLib
   (`dark_mode_icon_color_single_tone` / `light_mode_icon_color_single_tone`), which is a
   different package and not touched by an RRO against SystemUI. A fixed white/70%
   tint independent of the bar needs a hook in `DarkIconDispatcherImpl` or
   `StatusBarIconView.onDarkChanged` (`StatusBarIconView.java:973-977`). The 70% alpha
   resource above only approximates it while the bar is opaque.
3. **Icon shapes.** Notification glyphs are the app-provided drawables
   (`StatusBarIconView` loads `mIcon.icon`); wifi/battery shapes are SystemUI drawables
   referenced by code. RRO can restyle individual named SystemUI drawables, but it
   cannot make third-party icons monochrome silhouettes or change their geometry.
4. **Lock-screen clock.** Compose (`ClockSection.kt`), no `TextAppearance`/dimen hook;
   its family/size/position need a keyguard/Compose hook or a dedicated overlay theme,
   not this resource set.
5. **Per-app status bar background / edge-to-edge.** `system_bar_background_opaque` is
   already `#000000`, and `status_bar_container` is subject to `BarTransitions` and
   window flags. Forcing black over every app (instead of transparent/edge-to-edge) is a
   window-policy change, not an RRO resource.
6. **Dynamic system glyph colours** (e.g. charging battery tint) are drawn in code
   (`BatteryMeterView`/status icon pipeline); not overridable here.

## 6. Risks / honest limits

- Changing `status_bar_height` shifts all window insets and grows the chained
  `qs_header_row_min_height` / `status_bar_header_height_keyguard`
  (`packages/SystemUI/res/values/dimens.xml:605,783-784`); this is the riskiest part of
  the set. If anything overflows, revert just the framework APK.
- A static framework overlay cannot be toggled at runtime; removal requires deleting
  the APK and rebooting.
- The SystemUI overlay is not static, so it does nothing until enabled and SystemUI is
  restarted; if idmap creation is refused, the APK was not seen as preinstalled.
- No on-device screenshot was produced by this task (no adb/device ownership); all
  pixel numbers are computed from 280 dpi (1dp = 1.75 px), not measured.
