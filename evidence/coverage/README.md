# Coverage evidence index (derived)

This directory is an **index**, not new raw evidence. It maps each delivered block to
the screenshot or report that proves (or fails to prove) it on the device. Every claim
still rests on the file linked in the row; nothing was re-captured for this index.

| file | what it is |
|---|---|
| [`mechanisms.csv`](mechanisms.csv) | the nine blocks of [`docs/18`](../../docs/18-coverage-and-build.md) §1: mechanism, exact change, device status, evidence |
| [`screenshots.md`](screenshots.md) | screenshot chronology and what each frame actually shows |

Labels used in `mechanisms.csv`: `verified`, `partial / not effective on screen`,
`not verified`. See `screenshots.md` for the reasoning behind each status.

## Source screenshots (not copied here)

- `../device-bringup/` — 2026-11-16 11:11–11:42, the stack being applied step by step
  (`baseline-home.png`, `10-after-stack.png`, `23-start.png`, `41-settings.png`,
  `42-shade.png`, `60-settings.png`, `61-wifi.png`, `70-settings.png`, `71-wifi.png`,
  `80-settings.png`).
- `../ux-compare/current/` — 11:45–12:04, the same screens after the Settings pass and
  the header/tint update (`90-settings.png`, `91-wifi.png`, `92-start.png`,
  `93-shade.png`, `96-wifi.png`, `a0-settings.png`, `a1-wifi.png`).
- `../ux-compare/refs/` — the WP 8.1 / ztmetro reference frames used for comparison in
  [`docs/16`](../../docs/16-ux-gap-analysis.md); they are **not** this device.
