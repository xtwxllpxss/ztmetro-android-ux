# What the device screenshots actually show (derived index)

All frames are 720×1280 PNGs captured from the device with `adb exec-out screencap`
(per [`docs/16`](../../docs/16-ux-gap-analysis.md)). Times are the status-bar clock in
the frame; the files were captured 2026-11-16 10:45–12:04. This page records only what
is visible in the frame — it is the basis for the `verified` column of
[`mechanisms.csv`](mechanisms.csv).

## Chronology

| frame | visible state | what it proves |
|---|---|---|
| `device-bringup/baseline-home.png` (10:45) | stock Settings, teal M3 controls, rounded cards | baseline before the stack |
| `device-bringup/10-after-stack.png` (11:11) | stock lock screen (teal accent, stock clock) | keyguard module not active; framework accent not visible |
| `device-bringup/23-start.png` (11:12) | ztMetro Start v1 as HOME: `ZTMETRO` caps, 48 sp `start`, `start / apps` pivot, cobalt tiles | launcher APK installed and running as HOME |
| `device-bringup/41-settings.png` (11:25) | stock Settings home: 40 dp rounded search, pastel M3 tiles, teal switches | Settings RRO and header not yet applied |
| `device-bringup/42-shade.png` (11:25) | stock shade: rounded teal QS tiles, black dark-theme panel | SystemUI RRO not yet effective |
| `device-bringup/60-settings.png` (11:29) | Settings home: every dashboard tile cobalt `#0050EF`, white glyph | Settings RRO colours applied; radii still rounded |
| `device-bringup/61-wifi.png` (11:29) | inset rows, teal switch, stock toolbar | before header + Settings dimens |
| `device-bringup/70-settings.png` (11:31) | cobalt 56 dp header (`Настройки` / `ztMetro`) on the home page | LSPosed header module active |
| `device-bringup/71-wifi.png` (11:35) | header on a sub-screen (`Интернет` / `ztMetro`), **plus** the stock toolbar title above it | header hooks run on `SubSettings`; duplicate title before the toolbar fix |
| `device-bringup/80-settings.png` (11:42) | header at the top of the scrolling home content, no search bar in frame | homepage insertion geometry works |
| `ux-compare/current/90-settings.png` (11:52) | full-bleed rows, smaller typography, cobalt tiles, header | Settings RRO typography/dimens + header together |
| `ux-compare/current/91-wifi.png` (11:53) | full-bleed Wi-Fi rows, 12 sp grey secondary line, header **and** toolbar title, teal switch | Settings rows/typography verified; switch accent still absent |
| `ux-compare/current/93-shade.png` (11:53) | rounded teal QS tiles, black panel, large white clock | SystemUI RRO square geometry/labels **not** visible; measured gaps 4.31 dp vs stock 8 dp show the overlay is partly live (`qs-square/forensics/measure-shade.txt`) |
| `ux-compare/current/96-wifi.png` (11:58) | caps section labels (`МОБИЛЬНЫЙ ИНТЕРНЕТ`), full-bleed rows, toolbar + header, teal switch | later Settings typography pass; switch still teal |
| `ux-compare/current/97-wifi.png` (12:01) | toolbar title gone, header at top, **cobalt switch with white thumb** | header module `MetroTinter` (or Settings switchbar tint) now applied |
| `ux-compare/current/a0-settings.png` (12:03) | header only, cobalt tiles, full-bleed list | final Settings home look |
| `ux-compare/current/a1-wifi.png` (12:04) | cobalt switch/white thumb, caps labels, header only; the row gear icon is still teal | Settings accent + header verified; framework-tinted icon unchanged |

## What these frames do **not** prove

- **Framework RRO.** No frame shows a framework-tinted control changing from teal to
  cobalt on its own. Active QS tiles are still teal (`93-shade.png`), which is what
  `customColorShadeActive` from the framework overlay would have changed. The overlay
  may be absent, disabled, or outranked by the Lineage monet overlay; the screenshots
  cannot distinguish these cases.
- **SystemUI RRO.** `93-shade.png` still has 28 dp rounded tiles and stock label sizes.
  The flat black panel is also what a stock dark-theme shade looks like, so it is not
  evidence of the overlay.
- **System font.** There is no readback or before/after pair with identical layout and
  type sizes. `a0`/`a1` were captured after the Settings typography overlay, so glyph
  differences cannot be attributed to the font module.
- **Start v2/v3.** The launcher on screen is the v1 layout (`ZTMETRO` + `start` + pivot);
  the v2/v3 APKs were built later and not installed in these frames.
- **Action Centre / keyguard.** No frame shows either; the Action Centre APK was built
  at 12:04, after the last screenshot.
