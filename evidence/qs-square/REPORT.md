# QS tiles still rounded: the 2025 shade draws them in Compose (RRO cannot square them)

**Verdict.** The rounded QS tile is not the drawable the RRO overrides. In LineageOS 23.2
(Android 16) the shade's Quick Settings grid is Compose (`qs/panels/ui/compose/infinitegrid/`),
and the tile shape is a `RoundedCornerShape` built from **compile-time `Dp` constants**
(`InactiveCornerRadius = 50.dp`, `ActiveTileCornerRadius = 24.dp`, `ActiveIconCornerRadius = 16.dp`).
No dimen, drawable, attr or colour feeds that value, so **no RRO — systemui or framework — can
square the shade tiles**. `qs_corner_radius` / `qs_tile_background_shape` only feed the legacy
View path (`QSTileViewImpl`), which no longer draws the shade. The real fix is a 3-line SystemUI
patch, provided as [`proposed-systemui-square-tiles.diff`](proposed-systemui-square-tiles.diff).

The RRO is **installed and applied** — this is proven below by the 4 dp tile gap that only the
overlay's `qs_tile_margin_horizontal` produces. The radii simply come from another renderer.

| item | value |
|---|---|
| live SystemUI APK examined | `/tmp/sysui.apk` on hermes, sha256 `67e2f10c99a515a44d817cc805eb5cac1a38f7c6611867e25d07b97d61ece947` |
| SystemUI source pinned at | `LineageOS/android_frameworks_base` `lineage-23.2` = `797e43244b96c6a0dff5c81b21d4b4be665b082e` (`~/ztux/sysui-rro/src-tree`) |
| RRO payload (rebuilt) | [`ztmetro-systemui-rro.apk`](ztmetro-systemui-rro.apk), 12 719 B, sha256 `5ab8c73aef25bcfe67d28df7e620086d932fcdb1dc0924ad6c2d52efcf49f390` |
| entries | 25 (6 colour, 11 dimen, 1 drawable, 7 style); resource table byte-identical to the old `9151c7f6…` payload (sha differs only in zip/XML metadata) |
| shade screenshot analysed | [`../ux-compare/current/93-shade.png`](../ux-compare/current/93-shade.png) (720×1280) |

---

## 1. Proof from the live APK (decompiled with jadx, not source-guesswork)

`/tmp/sysui.apk` → `classes2.dex`; full files in [`forensics/`](forensics/).

`com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults`
([source](forensics/com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.java)):

```java
TileHeight = Dp.m739constructorimpl(72);
InactiveCornerRadius = Dp.m739constructorimpl(50);      // <-- inactive tile corner
```

`…infinitegrid.TileDefaults` ([source](forensics/com.android.systemui.qs.panels.ui.compose.infinitegrid.TileDefaults.java)):

```java
public static final float ActiveIconCornerRadius = Dp.m739constructorimpl(16);
public static final float ActiveTileCornerRadius = Dp.m739constructorimpl(24);
…
if (i != 2) { f = CommonTileDefaults.InactiveCornerRadius; }   // 2 == STATE_ACTIVE
State animateDpAsState = AnimateAsStateKt.animateDpAsState(f, …);
… new RoundedCornerShape(corner, corner, corner, corner)      // corner reads the animated Dp
```

There is **no `R.dimen.*`, no `getDimension*`, no `R.drawable.*`** in either class — the only
resource use in `TileKt` is `StringResources_androidKt.stringResource(R.string.accessibility_long_click_tile)`.
`TileKt` (decompiled) passes that shape into `TileExpandable`, which does
`Modifier clip = ClipKt.clip(modifier, shape)` before the background is painted; the grid
([`InfiniteGridLayout.kt:100-145`](https://github.com/LineageOS/android_frameworks_base/blob/797e43244b96c6a0dff5c81b21d4b4be665b082e/packages/SystemUI/src/com/android/systemui/qs/panels/ui/compose/infinitegrid/InfiniteGridLayout.kt))
calls `Tile(...)` on **both** sides of the `QSMaterialExpressiveTiles` aconfig flag (which only
picks `ButtonGroupGrid` vs `VerticalSpannedGrid`, never the shape).

The only tile dimensions the Compose path *does* read from resources are the grid gutters:
`InfiniteGridLayout.kt:106,129,130` → `R.dimen.qs_tile_margin_horizontal` / `qs_tile_margin_vertical`.

The View path still exists and still uses the RRO's targets, but nothing on this shade calls it:

| View path (not used by the 2025 shade) | ref |
|---|---|
| `QSPanelControllerBase` constructs `QSTileViewImpl` | `QSPanelControllerBase.java:384` (only the legacy `QSPanel`; `CustomizeTileView.kt:25` subclasses it) |
| `QSTileViewImpl` inflates the ripple | `QSTileViewImpl.kt:336-347` → `R.drawable.qs_tile_background_flagged` (APK `0x7f080b99`) |
| that ripple → layer-list → shape | `res/drawable/qs_tile_background_flagged.xml:16-29` (APK `0x7f080b99` → `0x7f080b9a`) |
| the shape reads the dimen | `res/drawable/qs_tile_background_shape.xml:19` → `@dimen/qs_corner_radius` (APK `0x7f080b9a` → `0x7f070a68`) |
| and the view rounds its content with the same dimen | `QSTileViewImpl.kt:1038,1056,1069` |

No `res/layout/qs_tile.xml` exists in the APK (the View tile is code-built); `qs_paged_tile_layout.xml`
belongs to the legacy `PagedTileLayout`, not to the Compose grid. Both Compose grid implementations
(`PaginatedGridLayout`, `InfiniteGridLayout`) delegate to the same `Tile.kt`.

---

## 2. Proof from the rendered screenshot (numbers, not impressions)

Script: [`forensics/measure-shade.py`](forensics/measure-shade.py); output:
[`forensics/measure-shade.txt`](forensics/measure-shade.txt). Run it on the PNG. The quoted source
lines (with file:line) behind every number above are archived verbatim in
[`forensics/source-lines.txt`](forensics/source-lines.txt); `forensics/` also holds the aapt2
xmltree dumps, the resource ids and the jadx output of `TileDefaults` / `CommonTileDefaults`.

```
tile bands (y0,y1,height): (314,430,117) (438,554,117) (562,678,117)
mid-row runs: (26,356) (364,693)            gap 7 px
internet (inactive): fitted corner r = 57.75 px, r/height = 0.494
bluetooth (active):  fitted corner r = 38.50 px, r/height = 0.329

density implied by tile height 72 dp : 1.6250 px/dp  (720 px = 443.1 dp)
side margin 26 px                    = 16.00 dp     (qs_tiles_page_horizontal_margin 16 dp)
tile gap 7 px                        =  4.31 dp     (RRO 4 dp; stock 8 dp = 13 px)
internet r = 35.5 dp   bluetooth r = 23.7 dp
```

Mapping to the code, one constant at a time:

* **tile height 117 px = 72 dp** — exactly `CommonTileDefaults.TileHeight`. The RRO's
  `qs_tile_height = 68dp` would be 110.5 px, so the View-path dimen is demonstrably ignored.
* **inactive Internet tile radius 57.75 px ≈ h/2 = 36 dp** — `50.dp` clamped to half the 72 dp
  height, i.e. a stadium. Not 28 dp (stock `qs_corner_radius`, 45.5 px) and not 0 dp.
* **active Bluetooth tile radius 38.5 px ≈ 24 dp** — exactly `ActiveTileCornerRadius`. Two
  different radii in one screenshot cannot come from one dimen: the shape is state-dependent
  Compose code.
* **gap 7 px = 4.3 dp** — the overlay's `qs_tile_margin_horizontal` (stock 8 dp = 13 px). This is
  what proves the overlay **is** live and idmap-mapped; the RRO is not the problem.

Density-independent form of the same proof: the measured radius/height ratios are 0.494 and 0.329,
i.e. **36/72 and 24/72**. `InactiveCornerRadius = 50.dp` clamped to h/2 is 36/72; `ActiveTileCornerRadius
= 24.dp` is 24/72. A single `qs_corner_radius` would give 28/72 = 0.389 for both tiles (or 0.000
with this RRO). Ratios do not depend on the density estimate, so the identification holds even if
the panel density is mis-inferred.

---

## 3. Why the previous override was ineffective

The previous report treated `qs_corner_radius` + `qs_tile_background_shape` as the tile chain.
That is the chain of `QSTileViewImpl` (Android ≤15 style), and it is still present in the APK, so
the RRO maps it correctly — but the 2025 shade never inflates that drawable and never reads that
dimen. The RRO entries are inert for the shade *by design of the new renderer*, not because the
overlay failed to apply. The same applies to the RRO's `qs_tile_height`, `qs_tile_padding`,
`qs_tile_start_padding`, `qs_tile_text_size` and `TextAppearance.QS.TileLabel` on the shade: the
Compose tile hard-codes its metrics and uses `MaterialTheme.typography.titleSmallEmphasized` /
`labelMedium` for labels (`CommonTile.kt:216,224`).

---

## 4. Override table (what the payload contains, and whether the shade consumes it)

| entry | new value | shade consumer | status |
|---|---|---|---|
| `dimen/qs_tile_margin_horizontal` | 4dp | `InfiniteGridLayout.kt:106,129` | **live** (7 px gaps prove it) |
| `dimen/qs_tile_margin_vertical` | 4dp | `InfiniteGridLayout.kt:130` | **live** |
| `dimen/qs_corner_radius` | 0dp | `QSTileViewImpl.kt:1038,1056,1069`; `qs_tile_background_shape.xml:19` | inert on shade (View path only) |
| `drawable/qs_tile_background_shape` | `<corners radius="0dp">` | inflated by `qs_tile_background_flagged.xml` (View) | inert on shade |
| `dimen/qs_tile_height` | 68dp | `TileLayout.java` (View) | inert on shade (Compose = 72dp) |
| `dimen/qs_tile_padding`, `qs_tile_start_padding`, `qs_tile_text_size` | 10dp/12dp/13sp | `QSTileViewImpl` (View) | inert on shade |
| `style/TextAppearance.QS.TileLabel(.Secondary)` | 13sp etc. | `qs_tile_label.xml` (View) | inert on shade |
| colours, `notification_corner_radius*`, `overlay_shade_panel_*` | — | View notification stack / legacy scrims | unchanged |

The payload is the maximum an RRO for `com.android.systemui` can express: it has no entry that
reaches the Compose shape.

---

## 5. Build (hermes) and payload

`repo/scripts/build-overlay-sysui.sh` was extended with `DUMP=1` (prints the signed APK's resource
table next to the sha256) and keeps its default behaviour:

```sh
DUMP=1 OUT_DIR="$PWD/evidence/qs-square" REMOTE_WORK=ztux/qs2/build \
  repo/scripts/build-overlay-sysui.sh
```

Build transcript: [`build-log.txt`](build-log.txt). It compiles with build-tools 36.0.0 / JDK 17
on `hermes-ubuntu`, signs with the reusable throwaway key, verifies with `apksigner verify`, and
prints 25 mapped entries. `aapt2 dump resources` shows `drawable/qs_tile_background_shape` and
`dimen/qs_corner_radius` in the payload, so idmap2 has something to map:

```
type dimen id=02 entryCount=11
  resource 0x7f020004 dimen/qs_corner_radius
type drawable id=03 entryCount=1
  resource 0x7f030000 drawable/qs_tile_background_shape
```

APK: [`ztmetro-systemui-rro.apk`](ztmetro-systemui-rro.apk)
sha256: `5ab8c73aef25bcfe67d28df7e620086d932fcdb1dc0924ad6c2d52efcf49f390`
(the previous payload `9151c7f6…` is functionally identical: `aapt2 dump resources` of the two APKs
is byte-identical — [`forensics/payload-diff.txt`](forensics/payload-diff.txt); the sha differs only
in zip/XML line metadata. Redeploying is optional and will not change the shade.)

---

## 6. Deploy and one-command verification (lead)

Install as a preinstalled overlay (Magisk module `system/overlay/ztmetro-systemui-rro.apk`),
reboot, then:

```sh
adb shell 'su 0 cmd overlay list | grep org.ztos.overlay.systemui; \
           su 0 cmd overlay dump org.ztos.overlay.systemui' | grep -Ei 'org.ztos|state|target|idmap'
adb exec-out screencap -p > /tmp/shade.png
python3 repo/evidence/qs-square/forensics/measure-shade.py /tmp/shade.png
```

What proves the overlay is applied and what proves the shape is Compose:

* `cmd overlay list` → `[x] org.ztos.overlay.systemui`; `cmd overlay dump` → `STATE_ENABLED` and
  `mTargetPackage: com.android.systemui` (plus a non-empty idmap path). That is the cmd proof.
* The measurement script must print **gap ≈ 4.3 dp (7 px)** and **tile height 72 dp**. A stock
  device prints a 13 px (8 dp) gap — the gap is the resource-level proof that
  `qs_tile_margin_horizontal` is being overridden, i.e. the overlay is live.
* The **radii must stay 35.5 dp (inactive) and 23.7 dp (active)**. They do not move with any RRO;
  after the SystemUI patch they must both print ≈ 0 dp.

Rollback: `su 0 cmd overlay disable --user 0 org.ztos.overlay.systemui`.

---

## 7. What remains Compose-only

* QS tile corner radius (`Tile.kt:482-483`, `CommonTile.kt:425`) and its active/inactive animation
  (`Tile.kt:588-614`).
* Tile height (72 dp), icon-box radius (16 dp / 50 dp) and content paddings (`CommonTile.kt:414-425`).
* Tile label typography (`MaterialTheme.typography.titleSmallEmphasized` / `labelMedium`).
* Tile fills: `LocalAndroidColorScheme.current.surfaceEffect1/2` —
  `com.android.internal.R.color.surface_effect_*` (framework), `MaterialTheme.colorScheme.primary`
  (`SurfaceEffectColors.kt:28-35`) — not reachable from a `com.android.systemui`-target RRO.
* QS edit-mode tiles (separate Compose radii, `EditTile.kt:340,695,828,833`).

What the RRO still controls on the 2025 shade: the grid gutters (above) and the View-based
notification stack/media cards (`notification_corner_radius*`).

**Only real fix:** patch the three Compose constants and rebuild SystemUI
([`proposed-systemui-square-tiles.diff`](proposed-systemui-square-tiles.diff)); after that the same
script must report 0 dp for both tiles. Replacing the preinstalled SystemUI APK instead is
rejected: platform signature / privapp permissions make an unsigned rebuild unsafe.
