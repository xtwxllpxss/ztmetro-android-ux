# 16 — UX gap analysis: ztmetro / Windows Phone 8.1 vs the current Android port

Method: screenshots captured on the real device with `adb exec-out screencap` (720x1280,
LineageOS 23.2, the ztMetro stack enabled) and compared side by side with
**genuine WP8.1 / WP7 frames** collected in the parent project
(`build/wp-ux-refs-2026-11-15`, sources in its `REFERENCES.md`) and with the original
**ztmetro** frames (`build/ztmetro-appdecor-off-2026-11-15/render-after-on`).
Side-by-sides: `evidence/ux-compare/cmp-start.png`, `cmp-settings.png`, `cmp-shade.png`.
Labels: `[F]` measured on the frames, `[E]` estimate.

## 1. Start / home screen

| | WP8.1 Start (`refs/wp81-start.png`) | Current (`current/z1-start.png`) | Gap |
|---|---|---|---|
| Chrome | **none** — tiles start at the top; no page title, no visible pivot | 48sp "start" title + "ZTMETRO" caps + visible `start | apps` pivot | Large: remove header/pivot from Start, keep swipe |
| Tile gutters | tiles touch, separated by a 2–3 px black line `[F: frame]` | ~16 px gutter at 720 wide (≈9 dp) `[F: frame]` | Medium: 2 dp gutters, edge-to-edge |
| Tile colours | theme accent **plus** app brand colours (Office red, Xbox green, Store green, camera grey) `[F]` | all cobalt `#0050EF` | Medium: per-tile palette from a Metro set |
| Glyphs | white monochrome glyphs `[F]` | full-colour Android adaptive icons in white circles | Large: alpha-silhouette glyphs (launcher v3) |
| Live content | titles/counters/images inside tiles (Messaging 1, Email 10, calendar date 30) `[F]` | none | Medium: notification-driven tile faces |
| Size mix | small/medium/wide packed first-fit `[F]` | medium/wide, but sparse | Medium: first-fit packing |

## 2. Notification shade / Action Centre

| | WP8.1 Notification Center (`refs/wp81-notification-center.png`) | Current (`current/z3-shade.png`) | Gap |
|---|---|---|---|
| Quick actions | **4-column square tiles**, icon above a small caps label, active = accent `[F]` | 2–3-column Material QS pills/tiles, pastel dynamic colour | Large: 4-col square grid + labels below |
| Brightness | a tile, not a slider `[F]` | full-width rounded slider pill | Medium |
| Links | `CLEAR ALL` / `ALL SETTINGS` text links above notifications `[F]` | "all settings" icon only, no clear-all | Small |
| Notifications | flat rows on black, light typography `[F]` | rounded cards (28 dp) — partially flattened by the RRO | Medium |
| Corners | none anywhere `[F]` | tiles still rounded (open item) | Medium |

## 3. Settings

| | WP8.1 settings (`refs/wp81-settings.png`, WP7 `refs/wp7-settings-header.png`) | Current (`evidence/device-bringup/80-settings.png`) | Gap |
|---|---|---|---|
| Header | two-line: caps label + 48sp light page title `[F]` | one 32sp cobalt banner with caps subtitle + ztMetro | **Close** (title light 32sp; caps label present as subtitle) |
| Body | flat black list, no cards, rows separated by thin lines `[F]` | flat rectangular panels (radii now 0dp) with 12px spacing | Small: remove card grouping/insets |
| Accent | one accent on toggles/links `[F]` | cobalt on dashboard glyphs; switches now overridden (pending verification) | Small |
| Type | light humanist sans for Cyrillic | Roboto fallback for Cyrillic (Selawik has no Cyrillic) | **Being fixed**: Open Sans module |

## 4. System chrome (not in the reference set, but visible)

- Status bar still Material (icon set, right-aligned clock); WP8.1 has a minimal strip with the
  clock on the right and no coloured icons `[F]` — restyleable only partly by RRO.
- Recents/power/volume are stock Material; no ztmetro analogue in the reference frames.

## 5. Priority order (impact x effort)

1. **Start redraw** (launcher v3): no header, flush tiles, white glyphs, first-fit packing — biggest gap.
2. **Metro Action Centre panel**: WP-style 4-column tiles + links + notification list, as our own
   full-screen activity fed by a notification listener; the stock shade stays as the fallback.
3. **Cyrillic font** (Open Sans, OFL) — fixes the mixed-font look in every Russian string.
4. **QS square pass** (RRO) for the stock shade until the Metro panel lands.
5. Settings: drop card grouping/insets (pure list), verify cobalt switches.
6. Lock screen (module already built), then status strip.

## 6. What cannot be closed without owning the ROM

The *system* shade layout (4-column grid, links row) is built in SystemUI code
(`QSTileRevealController`, Compose-hosted shade on 16); an RRO can restyle resources but not
re-arrange the grid. Our Metro Action Centre is therefore a separate panel, opened from Start;
hooking SystemUI to open it on a shade swipe is possible with LSPosed but is not done yet.
