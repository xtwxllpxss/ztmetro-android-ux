# ztmetro SystemUI + framework extras — volume, power, toast, dialogs

Two RROs that finish the four surfaces the Start / app-list / Settings /
Action-Centre work does not reach:

| module | target package | package id | enables |
|---|---|---|---|
| [`systemui/`](systemui/) | `com.android.systemui` | `org.ztos.overlay.systemui.extra` | volume panel shell, power menu |
| [`framework/`](framework/) | `android` | `org.ztos.overlay.framework.extra` | text-toast frame + type, Material alert-dialog background |

Build both with [`../../scripts/build-overlay-systemui-extra.sh`](../../scripts/build-overlay-systemui-extra.sh);
it prints the two APK paths and sha256 sums under
`repo/evidence/volume-power-toast/`.

## Why only these names

The shipped SystemUI APK (`67e2f10c…`, the APK examined in
`evidence/qs-square/REPORT.md`) no longer contains the legacy
`volume_dialog_legacy/*`, `volume_dialog_panel_width`,
`volume_row_rounded_background` or `TextAppearance.Volume.Header` resources
that still exist in the `lineage-23.2` source tree.  Its volume panel is
`res/layout/volume_dialog.xml` with an
`androidx.compose.ui.platform.ComposeView` slider.  Declaring the legacy names
would only add inert entries that idmap2 cannot map, so they are documented in
`evidence/volume-power-toast/REPORT.md` instead of shipped.

## Compose vs View (live APK)

* **Volume** — panel shell/ringer/floating capsule are View drawables (RRO-able);
  the vertical slider and its labels are Compose (`VolumeDialogSliderTrack.kt`)
  and stay stock.  The panel turns black/square; the slider does **not**.
* **Power menu** — View (`GlobalActionsDialogLite`, `GlobalActionsLayoutLite`);
  panel + button plate + label layout are RRO-able.
* **Toast** — the view is SystemUI's `text_toast.xml`, but its background and
  textAppearance are framework resources (`toast_frame`, `TextAppearance.Toast`);
  the framework overlay changes both.
* **Alert dialogs** — View (`AlertDialog` / `AlertController`); the window
  background is a framework drawable.

Deploy and verification steps are in `evidence/volume-power-toast/REPORT.md` §6.
