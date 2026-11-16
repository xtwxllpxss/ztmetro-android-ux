# ztmetro Start launcher v3 — WP redraw

**Status: built and statically verified on hermes-ubuntu; not installed or run on the
device** (adb is owned by the lead). This is one APK with the same package and the
**same v1/v2 signing key**, so the normal update path is `adb install -r`.

**Rebuild 2026-11-16 (design-critique pass).** The app list now puts the icon
column where `ztmetro.c` does: the 45 dp accent square starts at `LIST_LEFT`
x = 80 dp (not the 16 dp page margin) with a 25 dp white glyph inside it, and the
app name starts at 80 + 45 + 13 = 138 dp (`ztmetro.c:162-164, 4519-4525`). The
section-head square moved to the same column. APK rebuilt from these sources; the
sha256 above is the new artifact.

The start page was corrected after the lead compared the v1/v2 chrome with the real
ztmetro frame ([`refs/ztos-start.png`](../ux-compare/refs/ztos-start.png)) and the WP
8.1 reference ([`cmp-start.png`](../ux-compare/cmp-start.png)): **Start has no page
header and no pivot row**; tiles begin under the status strip and the app list is
reached by a right-edge circled arrow or a left swipe. The two-line header now lives
only on the app-list page. The original v3 brief's item 3 (48 sp "start" title + pivot
row on Start) is therefore intentionally not present; `MetroPivotView` was deleted.
The brief's "2–3 dp gutters" were also superseded: the lead measured the official
ztmetro frame at the token values (14 dp outer margin, 7 dp gutters), and v3 uses
those tokens unchanged.

| | |
|---|---|
| APK | [`ztmetro-start.apk`](ztmetro-start.apk) |
| size | **57 622 bytes** (56.3 KiB) < 3 MiB |
| sha256 | `5f4b08b113ac8823938323815ee2292641687a5f4598a228aa651f0d1911ea3c` |
| package | `org.ztmetro.start` (versionCode 3, versionName 3.0) |
| min / target | 29 / **36** |
| signing | v3, cert SHA-256 `5f05e8e4…6457831` — the v1/v2 key, so `-r` updates in place |
| build | hermes-ubuntu, JDK 17.0.21, build-tools 36.0.0, platform android-36 |
| deps | Android SDK + JDK + Python 3 only; no Gradle, no AndroidX, no network |

Static proof: [`aapt2-badging.txt`](aapt2-badging.txt),
[`aapt2-manifest.xmltree.txt`](aapt2-manifest.xmltree.txt),
[`apksigner-verify.txt`](apksigner-verify.txt),
[`dex-facts.txt`](dex-facts.txt), [`apk-contents.txt`](apk-contents.txt),
[`artifact-facts.txt`](artifact-facts.txt),
[`metro_tokens.generated.xml`](metro_tokens.generated.xml).

## What was redrawn (the point of v3)

### Start grid — `MetroGridView` + `MetroTileView` + `TileArt`

- **Geometry is the ztmetro C formula**, recomputed from the real width:
  `unit = (width − 2·14 − 5·7) / 6` = **49.5 dp** at the 360 dp reference. Small 1×1
  (49.5), medium 2×2 (106), wide 4×2 (219) dp. First-fit packing; medium/wide only on
  even cells; small tiles fill the four corners of one 2×2 block before the next block
  (so they never trail along a row). The seed is 15 tiles plus a 4×2 appearance tile;
  at 360×640 that packs to 10 rows / 558 dp, which fits the 576 dp area under the
  status strip and above the nav bar (simulated from the real packer). The packer
  reserves the right-centre 2×2 cell for the circled arrow, so the arrow floats in
  a black pocket instead of covering an app glyph (as in the reference frame).
- **Tiles are flat WP rectangles**: no corner radius, no elevation, no ripple, no
  Material surface. Tile colour is a deterministic package pick from the ztmetro
  palette (cobalt default and weighted, plus lime/green/emerald/teal/cyan/magenta/
  crimson/red/brown/olive/steel), so cobalt is still the modal colour but a Start is
  not a single-colour grid. No Material pastels.
- **Glyph pipeline (corrected after the `g0` screenshot):** `TileArt` now tries, in
  order:
  1. **API 33+ monochrome layer** — `AdaptiveIconDrawable.getMonochrome()`, tinted
     white with `SRC_IN`. This is the layer designed for monochrome icons. A layer
     that is near-solid (>55 %) or a tiny corner mark (<30 % of the box span) is
     rejected, so the Browser corner-diamond case falls through to the system glyph.
  2. **Fixed system packages** — our own white Path glyphs in `SystemGlyphs`: gear
     (Settings / Action Centre), camera body, calendar grid, two-person (Contacts),
     handset (Phone/Dialer), envelope (Messaging), globe (Browser), photo stack
     (Gallery), clock face (Clock), musical note (Music), 2×2 grid (Action Centre).
     These packages no longer get a full-bleed adaptive silhouette. If a system
     package does have a usable monochrome layer, that wins (step 1).
  3. **Everything else** — the original icon in **colour**, drawn at 44 % of the
     tile's short side, horizontally centred. For an adaptive icon only the foreground
     layer is drawn, so Chromium/Firefox show their brand mark, not a miniature app
     tile. A legacy icon is drawn whole.
  4. **Empty/transparent** — the app's first letter in white at 40 % of the tile
     height.

  No white circle, no alpha-silhouette blob. Small tiles are glyph-only, matching the
  ztmetro frame.
- **Label**: 13 sp white (`type.ramp.body`, system sans-serif → Selawik when the font
  module is on) at the lower-left, 8 dp in. No label on 1×1 tiles (the reference does
  not have one; 49 dp cannot fit readable text).
- **Press**: 80 ms delay, then a 14° 3-D lean toward the finger and scale 0.97 over
  90 ms; release returns over 200 ms. A 450 ms hold opens the tile menu and is
  swallowed, so it cannot launch. All values are the token motion entries.
- **Right-edge circled arrow**: 48 dp touch view, 1.5 dp white circle + arrow,
  vertically centred, white-on-tile; opens the app list. A clear left horizontal
  swipe on Start does the same.

### App list — `AppListView`, `MetroListRowView`, `MetroSectionHeaderView`, `AlphabetIndexView`

- Two-line header **only here**: `ZTMETRO` in 11.5 sp caps, then `applications` in
  48 sp light, at the 16 dp page margin.
- Search: a circled white search glyph at the left and a flat `EditText` with a
  **1 dp accent underline**; no pill, no fill, no Material search bar. The glyph and
  the bottom-strip magnifier both focus the field.
- Section heads are **hollow accent 1.5 dp squares with the lowercase letter inside**
  (`a`, `b`, …), as in `refs/ztos-app-list.png`, not a text header/divider.
- Rows: 56 dp, flat black, 45 dp accent square with the white monochrome/system
  glyph or the app's colour brand mark (letter fallback), app name 20–22 sp white
  (22 sp `app_list_title`). No divider, no ripple.
- Right-edge A–Z strip: tap or drag jumps; while dragging every letter stretches
  vertically (active letter the most) and the active letter is accent. A-Z letters are
  12 sp semibold.
- Bottom strip: 56 dp, back arrow / `ZRN` / search magnifier, matching the ztmetro
  app-list frame — **only when the device has an on-screen navigation bar**. On the
  ugglite (`config_showNavigationBar=false`, capacitive keys) the strip is skipped and
  the list gets the extra height. Back returns to Start; search focuses the field.

### Motion / pages

`MetroPager` is the page host: Start and the app list are full pages; a change is a
horizontal **slide + fade of the whole page** (300 ms arrival / 120 ms exit gap,
decelerate), never a Material cross-fade. Vertical drags stay with the ScrollView /
ListView except for one reserved gesture: a pull-down that starts in the **top 25 % of
the page content** and travels **≥80 dp** opens
`org.ztmetro.actioncentre/.ActionCentreActivity` (explicit component,
`NEW_TASK | SINGLE_TOP`, `resolveActivity` guard; a missing/disabled Action Centre is
a silent no-op). Horizontal wins when the drag is ambiguous, so the pivot swipe and
the shade pull do not fight. Predictive back is unchanged (app list → Start; back on
Start is consumed).

## What is inherited from v1/v2

- HOME activity and intent filters, `singleTask`, `clearTaskOnLaunch`,
  `excludeFromRecents`, `<queries>` for MAIN/LAUNCHER, **no `uses-permission`**.
- `TileStore` JSON pins (now with 1×1/2×2/4×2 sizes), pin/unpin/resize from the
  long-press white `MetroSheet`, launching via exact `ComponentName`, predictive back,
  edge-to-edge insets, `StartNotificationListener` + `LiveTiles` (optional; tiles show
  a small count in the upper-right when granted).
- Token pipeline (`design/tokens/gen_android.py`), throwaway v1/v2 signing key,
  build script shape, no AndroidX, no network.

## Token pipeline and v3-only values

Every colour, type size and motion duration still comes from
`design/tokens/ztmetro.tokens.json` via the generator. The build adds only launcher
chrome that the C shell draws but the token file does not carry:

| resource | value | source / reason |
|---|---|---|
| tile margin / gutter | **14 dp / 7 dp** | `metrics.start_margin_dp`, `metrics.tile_gap_dp`; matches the measured official frame (not the brief's 2–3 dp) |
| app-list row | **56 dp** | brief; 55 dp C pitch is the token, 56 dp keeps the 45 dp square inside the 48 dp touch floor |
| glyph | **44 %** | `fraction metro_v3_icon_fraction`; 44 % of the short side, C icon rule |
| row label gap | **13 dp** | ztmetro app-list name offset |
| circled arrow | **48 dp / 1.5 dp stroke** | Start affordance |
| bottom strip | **56 dp** | ztmetro back / ZRN / search strip |
| palette | cobalt + `#A4C400 #60A917 #008A00 #00ABA9 #1BA1E2 #D80073 #A20025 #E51400 #825A2C #6D8764 #647687` | lead-supplied ztmetro palette (`core/zrnmetrocore.c:203-208`) |

No token number is hand-duplicated in Java; the v3 launcher extras are generated into
`res/values/metro_tokens.xml` on every build and a copy of the file used is in
[`metro_tokens.generated.xml`](metro_tokens.generated.xml).

## Static verification performed

| check | result |
|---|---|
| build | `scripts/build-launcher.sh` exit 0 on hermes-ubuntu, JDK 17, SDK 36 |
| badging | `org.ztmetro.start`, versionCode **3**, versionName **3.0**, label `ztmetro start`, HOME, notification-listener |
| manifest | HOME/DEFAULT activity; listener service + `BIND_NOTIFICATION_LISTENER_SERVICE`; **0 `uses-permission`** |
| dex | `classes.dex` 82 616 B; StartActivity, MetroTileView, MetroGridView, MetroPager, MetroCircleGlyphView, MetroSectionHeaderView, MetroListRowView, SystemGlyphs, TileArt, TileStore, listener all present; **0 androidx** |
| resources | `metro_tile_gap` 7 dp, `metro_start_margin` 14 dp, `metro_v3_arrow` 48 dp, `metro_v3_icon_fraction` 44%, palette colours and `TextAppearance.Metro.SectionHeader` compiled |
| size | **57 622 B** < 3 MiB |
| signature | `apksigner verify` passes; cert `5f05e8e4…6457831`, same as v1/v2 |

Not verified: install, first-run seeding, swipe/tilt timing, glyph rendering on real
adaptive icons, font-scale layout, RAM/PSS. Those are the lead's device pass.

## adb install + set-home (exact)

From the workspace root, with the evidence APK:

```sh
# note the current home so reverting is exact
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c android.intent.category.HOME

adb install -r repo/evidence/launcher-v3/ztmetro-start.apk
adb shell cmd package set-home-activity org.ztmetro.start/.StartActivity
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c android.intent.category.HOME          # expect org.ztmetro.start/.StartActivity

# belt and braces
adb shell input keyevent KEYCODE_HOME
adb shell am start -n org.ztmetro.start/.StartActivity \
    -a android.intent.action.MAIN -c android.intent.category.HOME

# optional live counts (v2 listener, still no permission in the APK)
adb shell cmd notification allow_listener \
    org.ztmetro.start/org.ztmetro.start.StartNotificationListener

# memory
adb shell dumpsys meminfo org.ztmetro.start | head -20

# revert to the stock launcher (use the component noted above)
adb shell cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher
adb uninstall org.ztmetro.start
```

The evidence APK is signed with the v1/v2 key, so `install -r` works over the installed
v2. A clean rebuild on a different host generates a new throwaway key; then uninstall
first.

## Screens for the lead to screenshot

1. **Start** (`start.png`). After Home: no page title, no pivot, no white icon circles;
   tiles start directly under the status strip at the 14 dp outer margin with 7 dp
   gutters; first row should be three 2×2 tiles; second row a 4×2 + 2×2; small 1×1
   block below; cobalt dominant, with steel `#647687` and crimson `#A20025`
   samples likely on a normal app set; glyphs must read as Camera body, Contacts two
   people, Music note, Gallery photo stack, Clock face, Settings gear, Action Centre
   2×2 grid, Browser globe; Chromium/Firefox colour brand marks; no white blobs/dots;
   13 sp labels bottom-left; right-edge circled arrow in its black pocket.
2. **Pressed tile** (`pressed.png`). Press and hold a tile ~250 ms (before the 450 ms
   menu): it leans 14°, scales to 0.97, shifts its fill slightly, with no ripple and no
   shadow; release returns. On adb, `input motionevent DOWN x y` + screencap + `UP` if
   the ROM exposes it, otherwise two hands / a screen recorder.
3. **App list with jump strip** (`applications.png`). Tap the circled arrow or swipe
   left: `ZTMETRO` caps + 48 sp `applications`, circled search glyph + 1 dp accent
   underline, hollow accent squares with lowercase `a/b/c`, 56 dp rows with 45 dp
   accent square + readable glyph/brand mark + white name, right A–Z strip, bottom
   back/`ZRN`/search strip — on the ugglite this strip is correctly absent because
   `config_showNavigationBar` is false; the system back key returns to Start. Pull down
   from the top quarter (≥80 dp) to test the Action Centre launch; a missing
   `org.ztmetro.actioncentre` must do nothing. Drag the A–Z strip: letters stretch
   vertically and the list jumps.
4. **Long-press sheet** (`tile-menu.png`). Hold a tile ≥450 ms: white square-cornered
   panel over the 72% scrim with `open`, resize choices (make small/medium/wide) and
   `unpin from start`; no app launch after release. Long-press an app-list row for
   `pin as small/medium/wide tile`.

Suggested adb captures:

```sh
adb exec-out screencap -p > start.png
adb shell input swipe 600 640 100 640 250          # Start -> app list
adb exec-out screencap -p > applications.png
adb shell input swipe 320 200 320 200 700          # long-press a tile (adjust coords)
adb exec-out screencap -p > tile-menu.png
```

(Coordinates are 720×1280 pixels: e.g. first tile centre ≈ 134,154; right arrow centre
≈ 640,624; app-list A–Z strip x ≈ 680.)

## Open risks

1. **No device run yet.** Everything above is static/source-level; the new glyph
   priority, swipe vs. system back gesture, and the 2 GB frame budget are unmeasured.
2. **Monochrome quality is heuristic.** A monochrome layer covering more than 55 %
   of the box or spanning less than 30 % is treated as a background/blob/corner mark
   and falls through to the system glyph or colour brand mark. This is deliberately
   conservative; a legitimate large filled monochrome logo would fall back to colour.
3. **Glyph priority is heuristic.** The system-package match is a package-name token
   (`settings`, `camera`/`snap`, `calendar`, `contacts`, `dialer`/`phone`,
   `messaging`/`mms`/`sms`, `browser`/`jelly`, `gallery`, `clock`, `music`/`eleven`,
   `org.ztmetro.actioncentre`); a third-party package containing one of those tokens
   would get a white system glyph. A usable monochrome layer always wins over that.
   Colour brand marks are deliberately not monochrome and may be low-contrast on some
   palette tiles (white logos are the worst case).
4. **Palette is deterministic, not guaranteed.** A given installed app set can hash to
   cobalt/other colours without a steel or crimson tile. The weighting makes steel and
   crimson likely (2/26 each) but the screenshots depend on the actual packages.
5. **Small tiles have no label** (ztmetro reference); this is a deliberate deviation
   from the literal "label on every tile" sentence in the brief.
6. **The arrow pocket is fixed at rows 4–5.** On a much taller/scrollable grid the
   reserved black 2×2 cell scrolls with the content while the arrow is fixed; on the
   720×1280 target the seeded 10 rows fit without scrolling, so they align.
7. **No live text.** The optional listener only drives the upper-right count; zero live
   content remains when notification access is not granted. Granting it still does not
   add WP live titles.
8. **Start seed migration.** A v2 install keeps its pins; v3 tops the store up to 15
   tiles (four 1×1 + mediums) on first launch. That is a one-time data change, not a
   reset.
9. **App-list header cost.** The 104 dp two-line header + search leaves ~350 dp of
   list on the 640 dp screen; with an on-screen nav bar the 56 dp ZRN strip removes
   another row. At font scale 1.3 `applications` may clip and should be checked.
10. **Action Centre target is external.** The pull-down launches an explicit
    `org.ztmetro.actioncentre/.ActionCentreActivity`; if that APK is absent, renamed,
    not exported or blocked by package visibility, the gesture is a silent no-op. The
    manifest carries a `<package>` query for it so `resolveActivity` works on API 30+.
11. **Cache churn.** `TileArt` caps the glyph cache at 96; a 300-app list plus 15
   tiles will evict and re-render icons while scrolling. Watch for first-scroll jank.
12. **Key identity.** The evidence APK is installable over v1/v2 only because the v1/v2
    keystore was reused. Rebuilding elsewhere creates a new key and requires uninstall.

## File map (v3)

```
launcher/app/src/main/java/org/ztmetro/start/
  StartActivity.java          no-Start-header pages, Start arrow, app-list header,
                              search, conditional bottom strip, Action Centre pull,
                              page slide, back, tile menus
  MetroPager.java             horizontal page swipe + top-quarter pull-down host
  MetroGridView.java          first-fit 6-unit grid, 1x1/2x2/4x2 packing
  MetroTileView.java          flat WP tile, white/colour/system/letter glyph, tilt,
                              long-press
  TileArt.java                monochrome -> system glyph -> colour brand -> letter,
                              ztmetro palette
  SystemGlyphs.java           white code-drawn Paths for the fixed system packages
  MetroListRowView.java       56 dp row, 45 dp accent square at x 80 dp, 25 dp glyph,
                              name at x 138 dp
  MetroSectionHeaderView.java hollow accent square in the same 80 dp icon column
  MetroCircleGlyphView.java   48 dp circled arrow / search / plain back glyphs
  AppListView.java            sections, filtering, A-Z section map
  AlphabetIndexView.java      right-edge tap/drag strip with stretch
  MetroSheet.java             inherited white long-press sheet
  TileStore.java              inherited pins + SIZE_SMALL + v3 seed migration
  StartNotificationListener.java / LiveTiles.java   inherited optional listener
  MetroUi.java                resource-backed dp/type helper
scripts/build-launcher.sh     token gen, aapt2, javac, d8, align, sign, verify,
                              evidence, v3 class/resource checks
```

Reproduce:

```sh
ssh hermes-ubuntu
cd ~/ztux/launcher-v3
ANDROID_HOME=$HOME/android-sdk JAVA_HOME=$HOME/android-jdk ./scripts/build-launcher.sh
```
