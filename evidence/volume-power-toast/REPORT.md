# ztmetro volume / power / toast / dialogs — RRO evidence

Deliverable for the four stock Material surfaces that the Start, app-list,
Settings and Action-Centre work does not reach. Two preinstalled RROs, no code,
no APK replacement:

| module | package | target | APK | sha256 | entries |
|---|---|---|---|---|---|
| SystemUI extra | `org.ztos.overlay.systemui.extra` | `com.android.systemui` | [`ztmetro-systemui-extra-rro.apk`](ztmetro-systemui-extra-rro.apk) (13 185 B) | `48f14ebff7f6cda5dda7179ceb45a434097cfe58f4c427dacdf71dfabc4640cc` | **8** (2 dimen, 5 drawable, 1 layout) |
| framework extra | `org.ztos.overlay.framework.extra` | `android` | [`ztmetro-framework-extra-rro.apk`](ztmetro-framework-extra-rro.apk) (12 795 B) | `f66070a06b66c2bd0d0dba41325f0d8fe13ccbf09b13bc3447b0e416ae2116ba` | **3** (2 drawable, 1 style) |

Both are signed with the project throwaway key (`CN=ztmetro overlay (throwaway)`,
cert sha256 `16e91082…8d3e`), `hasCode=false`, no `targetName` (SystemUI and
framework-res declare no `<overlayable>`, docs/10b-overlay-and-hook-facts.md §1).
Raw proof: [`verify-transcript.txt`](verify-transcript.txt),
[`aapt2-dump-resources-systemui.txt`](aapt2-dump-resources-systemui.txt),
[`aapt2-dump-resources-framework.txt`](aapt2-dump-resources-framework.txt),
[`aapt2-dump-badging-systemui.txt`](aapt2-dump-badging-systemui.txt),
[`build-log.txt`](build-log.txt), [`sha256.txt`](sha256.txt).

Sources: [`../../overlay/systemui-extra/`](../../overlay/systemui-extra/),
build script [`../../scripts/build-overlay-systemui-extra.sh`](../../scripts/build-overlay-systemui-extra.sh).

---

## 1. Result in one line per surface

| surface | live implementation | what this RRO changes | full ztmetro? |
|---|---|---|---|
| volume panel | **View shell + Compose slider** | outer panel / ringer drawer / floating capsule → black, 0 corners | **no** — slider + labels are Compose |
| power menu | **View** | panel + popup → black, 0 corners; item layout → square plate, white 13sp label | **mostly** — destructive accent needs code |
| toast | **View** (`text_toast` inside SystemUI) | framework `toast_frame` → black, 0 corners; `TextAppearance.Toast` → 13sp white | **yes** for text toasts |
| alert dialogs | **View** (framework `AlertDialog`) | framework `dialog_background_material` → black, 0 corners | **yes in dark theme**; light-theme caveat §5 |

---

## 2. Compose vs View on Android 16 — verified, not guessed

The `lineage-23.2` **source tree contains both** the old View volume dialog and
the 2024 Compose-slider volume dialog. The **shipped APK** (`/tmp/sysui.apk`,
sha256 `67e2f10c99a515a44d817cc805eb5cac1a38f7c6611867e25d07b97d61ece947`,
the same APK examined in `evidence/qs-square/REPORT.md`) settles which one is live:
its resource table contains `volume_dialog*` / `volume_dialog_slider` but **none**
of `volume_dialog_legacy`, `volume_dialog_panel_width`,
`volume_row_rounded_background`, `volume_background_*`, `volume_row_seekbar`,
`TextAppearance.Volume.Header` (§4). The shipped build does not carry the
View path's resources.

| surface | class / layout | verdict | proof |
|---|---|---|---|
| volume | `VolumeDialog.kt:125` `setContentView(R.layout.volume_dialog)`; `res/layout/volume_dialog_slider.xml:21` is `androidx.compose.ui.platform.ComposeView`; colors from `VolumeDialogSliderViewBinder.kt:93-98` (`MaterialTheme.colorScheme`) | **hybrid**: the dialog/panel/ringer/floating capsules are View drawables; the vertical slider + labels are Compose | live APK `aapt2 dump xmltree` shows `ComposeView` inside `volume_dialog_slider.xml`; `volume_dialog.xml` background id `0x7f080ccc` |
| power menu | `GlobalActionsImpl.java:66` → `GlobalActionsDialogLite`; `res/layout/global_actions_grid_lite.xml:25` → `GlobalActionsLayoutLite`; items `global_actions_grid_item_lite.xml:16` → `GlobalActionsItem` | **View** | no `ComposeView` in the power layouts; live APK xmltree |
| toast | `SystemUIToast.java:174-176` inflates `res/layout/text_toast.xml` (`LinearLayout` + `TextView`), background `@android:drawable/toast_frame`, textAppearance `@android:style/TextAppearance.Toast` | **View**, framework-styled | live APK xmltree: background `0x01080096`, textAppearance `0x010303f1` |
| alert dialogs | `Theme.Material.Dialog.Alert` (`themes_material.xml:1219`) → `Theme.Material.BaseDialog` (`:1125-1131`, `windowBackground=@drawable/dialog_background_material`); `AlertDialog.Material` uses `@layout/alert_dialog_material` (`styles_material.xml:1260`) | **View** | framework source; no Compose dialog class in this path |

Consequence: **any volume override that targets only the legacy names is inert
on this ROM.** That is why the SystemUI module ships the live
`volume_dialog_background` / `volume_dialog_ringer_background` /
`volume_dialog_floating_slider_background` names instead.

---

## 3. Override table (11 entries, old → new)

Paths are relative to `packages/SystemUI/` for the SystemUI rows and to
`core/res/res/` for the framework rows, at `lineage-23.2`
(`797e43244b96c6a0dff5c81b21c4d4be665b082e`). "live" is the value read out of
the shipped APK with `aapt2 dump resources` (**30 dp / 28 dp** below).

### 3.1 Volume panel — 4 entries (View shell only)

| resource | target file:line | old | new | consumer in live APK | status |
|---|---|---|---|---|---|
| `volume_dialog_background` | `res/drawable/volume_dialog_background.xml:17-22` | corners `@dimen/volume_dialog_background_corner_radius` = **30 dp**; solid `@androidprv:color/materialColorSurface` | `<corners 0dp>`, `#FF000000` | `res/layout/volume_dialog.xml:33` (`@id/volume_dialog_background`) | consumed |
| `volume_dialog_ringer_background` | `res/drawable/volume_dialog_ringer_background.xml:17-22` | 30 dp corners; `materialColorSurface` | 0 dp; `#FF000000` | `res/layout/volume_dialog_top_section.xml:31` | consumed |
| `volume_dialog_floating_slider_background` | `res/drawable/volume_dialog_floating_slider_background.xml:16-21` | **20 dp** literal corners; `materialColorSurface` | 0 dp; `#FF000000` | `res/layout/volume_dialog_slider_floating.xml:19` | consumed |
| `volume_dialog_background_corner_radius` | `res/values/dimens.xml:2238` | `30dp` | `0dp` | `volume_dialog_background.xml:20` (already pinned by the drawable) | consumed / redundant |

The Compose slider itself reads **no** dimen: `VolumeDialogSliderTrack.kt:58-60`
hard-codes `trackCornerSize = 12.dp`, `trackInsideCornerSize = 2.dp`,
`trackSize = 40.dp`; `VolumeDialogSliderViewBinder.kt:93-98` takes active/inactive
colours from `MaterialTheme.colorScheme`. **Accent #0050EF and 12sp labels are
not addressable by this RRO.**

### 3.2 Power menu — 4 entries (View)

| resource | target file:line | old | new | consumer | status |
|---|---|---|---|---|---|
| `global_actions_lite_background` | `res/drawable/global_actions_lite_background.xml:19-23` | solid `@androidprv:color/materialColorSurfaceContainerLow`; corners `@dimen/global_actions_corner_radius` = **28 dp** | `#FF000000`; `0dp` | `global_actions_grid_lite.xml:44`; `global_actions_power_dialog_flow.xml:23` | consumed |
| `global_actions_popup_bg` | `res/drawable/global_actions_popup_bg.xml:17-25` | `?android:attr/colorBackgroundFloating`; `?android:attr/dialogCornerRadius` | `#FF000000`; `0dp` | `GlobalActionsPopupMenu.java:51-52` | consumed |
| `global_actions_grid_item_lite` | `res/layout/global_actions_grid_item_lite.xml:26-45` | icon tint `@androidprv:color/materialColorOnSurface` (`:33`); label **14sp** (`:42`); label colour `materialColorOnSurface` (`:44`) | icon tint `#FFFFFFFF`; label **13sp** white; icon plate `#FF1F1F1F`, no corners | `GlobalActionsDialogLite.java:2361,2427,3113` | consumed; runtime tint may win §4 |
| `global_actions_corner_radius` | `res/values/dimens.xml:1159` | `28dp` | `0dp` | `global_actions_lite_background.xml:22` (already pinned) | consumed / redundant |

The overlay layout uses the **same framework ids** the target code looks up
(`@android:id/icon` = `0x01020006`, `@android:id/message` = `0x0102000b`;
`GlobalActionsDialogLite.java` imports `com.android.internal.R`, line 115), so
`findViewById()` keeps working. It references **no SystemUI-only resource**, which
is what lets aapt2 link it against `android.jar` alone.

### 3.3 Toast — 2 entries (framework)

| resource | target file:line | old | new | consumer | status |
|---|---|---|---|---|---|
| `toast_frame` | `core/res/res/drawable/toast_frame.xml:18-22` | solid `?android:attr/colorBackgroundFloating`; corners **28 dp** | `#FF000000`; `0dp` | SystemUI `res/layout/text_toast.xml:26` `@android:drawable/toast_frame` (live id `0x01080096`) | consumed |
| `TextAppearance.Toast` | `core/res/res/values/styles.xml:992-999` | 14sp, lineHeight 20sp, `?android:attr/textColorPrimary`, feature-flagged family | **13sp**, lineHeight 18sp, `#FFFFFFFF`, `sans-serif`, tracking 0, no caps | SystemUI `res/layout/text_toast.xml:48` `@*android:style/TextAppearance.Toast` (live id `0x010303f1`) | consumed |

The toast view is SystemUI's, but both resources it points at are framework
resources — so the framework module changes every SystemUI text toast without a
SystemUI layout override (which would need SystemUI-only `@id/text` and cannot
be linked against `android.jar`).

### 3.4 Alert dialogs — 1 entry (framework)

| resource | target file:line | old | new | consumer | status |
|---|---|---|---|---|---|
| `dialog_background_material` | `core/res/res/drawable/dialog_background_material.xml:17-22` | `inset 16dp`; corners `?attr/dialogCornerRadius`; solid `?attr/colorBackground` | `inset 16dp`; corners **0dp**; solid `#FF000000` | `Theme.Material.BaseDialog` `themes_material.xml:1131` (`windowBackground`), reached by `Theme.Material.Dialog.Alert` `:1219` | consumed |

**Already covered by `repo/overlay/framework` — deliberately not duplicated:**

| name | existing override | why it already covers the dialog |
|---|---|---|
| `dialog_corner_radius` | `overlay/framework/res/values/ztmetro_dimens.xml:24` → `0dp`; target `core/res/res/values/dimens.xml:1162` = `2dp` | `Theme.Material` sets `dialogCornerRadius` from it (`themes_material.xml:192,570`); the old drawable's `?attr/dialogCornerRadius` was already 0 |
| `config_dialogCornerRadius` | `ztmetro_dimens.xml:12` → `0dp` | platform-wide dialog radius |
| `TextAppearance.Material.Title` | `overlay/framework/res/values/ztmetro_styles.xml:71-77` → **20sp** | `TextAppearance.Material.DialogWindowTitle` is an empty child of it (`styles_material.xml:410`), used by `DialogWindowTitle.Material` (`:1306-1310`) — the 20sp dialog title is already in place |

So the only missing dialog piece was the **fill**; no name above is re-declared.

---

## 4. Consumed / inert split (honest)

**Consumed (should change on the device):** all 11 entries above; every name was
confirmed present in the shipped SystemUI APK (`aapt2 dump resources`) or in
`android-36/android.jar` (§4.1). The name→id lookup for all 8 SystemUI entries
printed `PRESENT` in `verify-transcript.txt`.

**Inert / not shipped — and why:**

| item | state | reason |
|---|---|---|
| `volume_dialog_legacy`, `volume_dialog_panel_width` / `_half`, `volume_row_rounded_background`, `volume_background_top_legacy`, `volume_background_bottom`, `volume_row_seekbar`, `TextAppearance.Volume.Header` | **absent from the shipped APK**; not in our APK | resource-shrunk; the live volume panel is the 2024 Compose-slider layout. Declaring them would produce idmap entries with no target. |
| `volume_dialog_background_square_corner_radius` (source `dimens.xml:2264`, 12dp) | target name exists, **no live consumer**; not shipped | its only consumer, `drawable/volume_dialog_background_small_radius.xml`, is itself absent from the shipped APK. |
| volume slider (track/thumb), stream labels/icons | **Compose-only** | `VolumeDialogSliderTrack.kt` + `MaterialTheme.colorScheme`; no SystemUI resource feeds them. The panel turns black/square; the slider keeps Material rounding and the theme's primary colour. |
| `global_actions_lite_button` (the round icon plate) | target drawable present, **not overridden** | our replacement layout paints the plate inline `#FF1F1F1F`, because an overlay layout can only reference framework resources. The stock drawable is left untouched; it is referenced only by the layout we replace. |
| power icon/label **runtime tint** | may override the layout’s white | `GlobalActionsDialogLite.java:2392-2403` re-applies `materialColorOnSurface` when `QsInCompose` is on. In the ztOS dark palette that colour is already near-white, so the visible result stays white-ish; the **13sp size** survives. |
| **accent on destructive** (power) | **not delivered** | there is no per-item colour resource: all actions share `global_actions_grid_item_lite.xml:33` and the same runtime tint; `RestartAction` (`GlobalActionsDialogLite.java:1172-1210`) gets no red tint. Needs a code/icon patch. |
| app **custom-view** toasts | unaffected | only `ToastUI`/`SystemUIToast` text toasts use `toast_frame` + `TextAppearance.Toast`; an app-inflated custom toast keeps its own view. |

### 4.1 Build-verification summary

* `aapt2 dump resources` on the SystemUI APK lists exactly:
  * dimen: `global_actions_corner_radius`, `volume_dialog_background_corner_radius`
  * drawable: `global_actions_lite_background`, `global_actions_popup_bg`,
    `volume_dialog_background`, `volume_dialog_floating_slider_background`,
    `volume_dialog_ringer_background`
  * layout: `global_actions_grid_item_lite`
* Framework APK lists `dialog_background_material`, `toast_frame`,
  `TextAppearance.Toast`.
* Badging: correct `targetPackage`, `isStatic=false` (SystemUI) / `true`
  (framework), `hasCode=false`, no dex.
* SDK `android.jar` ids differ from the ROM (`TextAppearance.Toast`
  `0x010303ef` vs `0x010303f1`); idmap2 matches by `(type,name)` at install, so
  this is expected and does not affect mapping.

---

## 5. Known limits

1. **Volume is only half-skinned.** The black/square shell is real; the Compose
   slider and labels are not. Pinning #0050EF/12sp in the slider needs a
   SystemUI code change (or a Compose theme hook), not an RRO.
2. **Light-theme dialogs.** `Theme.Material.Light.Dialog.Alert`
   (`themes_material.xml:1332`) shares `dialog_background_material`; on
   that theme the fill becomes black while the text stays `textColorPrimary`
   (dark). ztOS is dark-first and the reference screenshots are black, so this is
   acceptable for the target; a follow-up should either ship the drawable only
   in `values-night` or override the light text colours. We did **not** ship a
   duplicate `values-night` variant because the device is dark and the target
   has no night variant of this drawable.
3. **Destructive accent** and the **Compose slider accent** are code-level, as
   listed in §4.
4. No on-device check was possible from this session (no adb); the deploy steps
   below are for the lead, who owns the device.

---

## 6. Build

Ran on `ssh hermes-ubuntu`, workdir `~/ztux/vpt`,
`~/android-sdk` (build-tools 36.0.0, platform android-36), JDK `~/android-jdk`.
The script builds locally when aapt2 is present, otherwise rsyncs
`repo/overlay/systemui-extra` and builds remotely:

```sh
repo/scripts/build-overlay-systemui-extra.sh
# ==> [ztmetro-systemui-extra-rro.apk] ... override entries: 8
# ==> [ztmetro-framework-extra-rro.apk] ... override entries: 3
# APK .../evidence/volume-power-toast/ztmetro-systemui-extra-rro.apk
# sha256 48f14ebf...
# APK .../evidence/volume-power-toast/ztmetro-framework-extra-rro.apk
# sha256 f66070a0...
```

Full transcript: [`build-log.txt`](build-log.txt),
[`verify-transcript.txt`](verify-transcript.txt).

---

## 7. Deploy / verify (for the lead, who owns the device)

Both APKs must be **preinstalled on a read-only partition** — a data install is
refused by idmap2 because SystemUI/framework declare no overlayable
(`ResourceMapping.cpp:69-75`). The project already has the right carrier:
[`modules/ztmetro-overlays/service.sh`](../../modules/ztmetro-overlays/service.sh)
documents that the overlay APKs live in `/product/overlay` and enables them from
a `late_start` service as uid 0.

```sh
# 1. preinstall into the existing module's product overlay dir
#    (create it if this module does not carry APKs yet)
mkdir -p /data/adb/modules/ztmetro-overlays/system/product/overlay
cp ztmetro-systemui-extra-rro.apk  /data/adb/modules/ztmetro-overlays/system/product/overlay/
cp ztmetro-framework-extra-rro.apk /data/adb/modules/ztmetro-overlays/system/product/overlay/
chmod 644 /data/adb/modules/ztmetro-overlays/system/product/overlay/ztmetro-*-rro.apk
chown root:root /data/adb/modules/ztmetro-overlays/system/product/overlay/ztmetro-*-rro.apk

# 2. add the two package names to the enable loop in
#    modules/ztmetro-overlays/service.sh (lead-owned file, outside this task's
#    write scope):
#      for pkg in ... org.ztos.overlay.systemui.extra org.ztos.overlay.framework.extra; do
#    The framework overlay is isStatic=true, so it is already enabled by the
#    boot scan; the enable call is a harmless no-op (the existing
#    org.ztmetro.overlay.framework overlay is treated the same way).

# 3. the APKs are picked up by PackageManager on the module's normal next boot
#    scan (do not issue an ad-hoc reboot: the lead owns the boot/transport
#    policy).  After that scan, confirm both are known:
su 0 cmd overlay list | grep -E 'ztos|ztmetro'
#   [x] org.ztos.overlay.framework.extra          <- static, enabled at boot
#   [ ] org.ztos.overlay.systemui.extra           <- non-static, enable now

# 4. enable the SystemUI overlay (shell uid lacks CHANGE_OVERLAY_PACKAGES;
#    root is required, docs/10b §4)
su 0 cmd overlay enable --user 0 org.ztos.overlay.systemui.extra

# 5. restart SystemUI so it re-reads its resources
su 0 killall com.android.systemui
```

If step 3 does not show the packages, the APKs are not on a scanned read-only
overlay path (or the mount did not happen); fix the preinstall path rather than
trying to enable them — a static overlay cannot be toggled, and a data-installed
copy has no idmap.

Verify mapping and the visual result:

```sh
# mapped entries (should list our names, not "no such resource")
su 0 cmd overlay dump org.ztos.overlay.systemui.extra | head -40
su 0 cmd overlay lookup com.android.systemui drawable/volume_dialog_background
su 0 cmd overlay lookup android drawable/toast_frame

# volume: press a volume key -> black square panel / ringer drawer;
#         the slider itself stays Material (Compose)
# power:  long-press power -> black square panel, square action plates
# toast:  trigger any app text toast (e.g. copy something in Settings) ->
#         black square frame, 13sp white text
# dialog: open a Material alert dialog (an app's About/confirm dialog) ->
#         black square window, 20sp title (title size is the pre-existing
#         framework RRO, not this APK)
```

Rollback is symmetric: remove the two APKs from the module's
`system/product/overlay/` and from the enable loop, then let the next boot scan
drop them.  `su 0 cmd overlay disable --user 0 org.ztos.overlay.systemui.extra`
takes effect for the SystemUI overlay without waiting for the module change.
