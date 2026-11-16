# ztmetro Start — white glyph set and launcher glyph pipeline (v4)

**Status: built and statically verified on hermes-ubuntu; not installed** (adb is
owned by the lead). This is the same package and the **same v1/v2/v3 signing key**,
so the normal path is `adb install -r`.

| | |
|---|---|
| APK | [`ztmetro-start.apk`](ztmetro-start.apk) |
| size | **69 910 bytes** (68.3 KiB) < 3 MiB |
| sha256 | `a23db1bacca3064825d881479053ddc875b3b1363ca6bf6e35f06f8a1a4aa487` |
| package | `org.ztmetro.start` (versionCode 4, versionName 4.0) |
| min / target | 29 / 36 |
| signing | v3, cert SHA-256 `5f05e8e4…6457831` — the v1/v2/v3 key |
| build | hermes-ubuntu, JDK 17.0.21, build-tools 36.0.0, platform android-36 |
| glyph sheet | [`glyphs.png`](glyphs.png), sha256 `a463fd236eafd3a2fb4efecd66b5602f4bee3d63a0bae2e75cff3051f28d94fa` |

Static proof: [`aapt2-badging.txt`](aapt2-badging.txt),
[`apksigner-verify.txt`](apksigner-verify.txt),
[`artifact-facts.txt`](artifact-facts.txt), and the build-script guards described
below.

```
adb install -r repo/evidence/icon-set/ztmetro-start.apk
```

## What changed

### 1. White 24 dp glyph set (`GlyphSet.java`)

18 glyphs, all authored as Path commands in a **24 × 24 dp viewport** with a
**2 dp stroke** and WP-style filled shapes: browser (globe + meridians + three
parallels), gallery (frame, sun, mountain range), calendar (page, header bar,
two rings), calculator (display + 3×3 keypad), camera (body, hump, lens, flash
holes), contacts (two people), music (beamed note), settings (cog with 8 round
teeth and a centre hole), clock (circle + hands + centre pin), action centre
(4 rounded squares), phone (solid handset), messages (speech bubble with tail
and three rules), mail (envelope with open V), store (shopping bag), files
(folder with tab), weather (sun with 8 rays), maps (location pin), notes (page
with three rules).

`GlyphSet` imports no Android types. `SystemGlyphs` binds it to
`android.graphics.Path` for the APK; `launcher/tools/GlyphSheet.java` binds the
**same class** to Java2D for the offline sheet, so the proof image cannot drift
from the shipped geometry. Fill rule is even-odd only for the four glyphs that
punch true holes (camera, messages, mail, maps); settings uses winding with a
reversed centre circle.

### 2. Pipeline order (`TileArt.buildArt`)

1. **System package map first** — `SystemGlyphs.kindFor(pkg)` returns an authored
   white glyph before any drawable is consulted. This is the fix for the browser
   sparkle and contacts blob: their adaptive/monochrome layer can no longer win.
2. **Third-party monochrome** — API 33+ `AdaptiveIconDrawable.getMonochrome()`,
   tinted white, only if the silhouette is non-empty, not near-solid (>55 %) and
   spans at least 30 % of the box.
3. **Third-party brand mark in colour** — foreground layer for an adaptive icon
   (whole legacy icon otherwise), drawn in the callers' **44 % glyph box**, no
   white tint, no circle, no backdrop.
4. **Letter fallback** — first letter in white when steps 1–3 produce nothing.

Tiles draw only `flat tile colour + glyph/colour mark`. A build guard fails if
`TileArt.java` ever contains `drawCircle`/`addCircle`; none did before or after
this change. The build also fails if the `SystemGlyphs.kindFor` call stops
preceding the `Monochrome33.get` call.

### 3. Package → glyph map (exact entries)

| glyph | packages |
|---|---|
| browser | `org.lineageos.jelly`, `com.android.browser` |
| gallery | `com.android.gallery3d` |
| calendar | `org.lineageos.etar`, `com.android.calendar` |
| calculator | `com.android.calculator2`, `com.android.calculator3` |
| camera | `org.lineageos.aperture`, `com.android.camera2`, `com.android.camera` |
| contacts | `com.android.contacts` |
| music | `org.lineageos.twelve`, `org.lineageos.eleven`, `com.android.music` |
| settings | `com.android.settings` |
| clock | `com.android.deskclock` |
| action centre | `org.ztmetro.actioncentre`, `org.ztmetro.actioncenter`, `org.ztmetro.start` |
| phone | `com.android.dialer`, `com.android.phone` |
| messages | `com.android.messaging`, `com.android.mms` |
| mail | `com.android.email` |
| store | `com.android.vending` |
| files | `com.android.documentsui` |

Keyword fallback runs **only inside system namespaces** (`com.android.`,
`org.lineageos.`, `org.ztmetro.`, `org.aosp.`, `com.cyanogenmod.`): settings,
camera/aperture, calendar/etar, contacts, dialer/phone/telecom, messaging/mms/sms,
browser/jelly, gallery/photos, document/files, music/eleven/twelve,
clock/deskclock, calculator, email/mail, store/vending, weather, maps, notes,
actioncentre/actioncenter. Third-party packages (e.g.
`com.google.android.apps.maps`) deliberately fall through to the colour brand
mark. `weather`, `maps` and `notes` currently have no exact system package in
the evidence set; they are reachable through that fallback only.

No device `pm list packages` dump exists under `repo/evidence/`, so the exact map
is built from the LineageOS/AOSP package names the project already references
(launcher sources, Action Centre, reference frames), not from a fresh device
readback.

## Offline glyph proof

`launcher/tools/GlyphSheet.java` renders black-background white glyphs in two
blocks: **48 dp (2× = 96 px)** and **24 dp (2× = 48 px)**, six per row, three
rows each. It is rebuilt by `scripts/build-launcher.sh` from the shipped
`GlyphSet.java` and written to the evidence directory. At the reference 360 dp
width, a 2×2 tile glyph box is ~47 dp and a 1×1 small-tile glyph box is ~21.8 dp,
so the two sheet sizes bracket the real range.

### Glyphs I consider weak

- **calculator** — the 3×3 keypad plus display is the densest glyph; at 24 dp it
  can read as a grid rather than a calculator, though the frame still carries it.
- **store** — bag body + handle can read as a briefcase at 24 dp.
- **browser** — five 2 dp strokes in 24 dp is the busiest outline; the globe is
  still legible but heavier than the WP reference glyph.
- **calendar** — at 24 dp the rings are only ~5 px and the header band merges
  with the page border; it still reads as a calendar, but it is the least crisp
  of the filled glyphs.

All other glyphs (gallery, camera, contacts, music, settings, clock, action
centre, phone, messages, mail, files, weather, maps, notes) read correctly at
both 48 dp and 24 dp in the sheet.

## Files

- `launcher/app/src/main/java/org/ztmetro/start/GlyphSet.java` — shared geometry.
- `launcher/app/src/main/java/org/ztmetro/start/SystemGlyphs.java` — Android Path
  adapter + exact/fallback package map.
- `launcher/app/src/main/java/org/ztmetro/start/TileArt.java` — reordered pipeline.
- `launcher/app/src/main/java/org/ztmetro/start/MetroTileView.java` — settings
  glyph constant renamed to `SETTINGS`.
- `launcher/tools/GlyphSheet.java` — Java2D proof-sheet renderer.
- `scripts/build-launcher.sh` — pipeline-order/backdrop/map guards and sheet render.
