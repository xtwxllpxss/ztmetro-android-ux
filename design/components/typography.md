# Component — Typography

Owner: Magisk font module + **B** RRO (system); **A** (launcher, bundled fallback).
Roadmap: [`../../docs/14-ux-roadmap.md`](../../docs/14-ux-roadmap.md) §P11, Wave 4.4.
Tokens: the whole `type` group, each ramp entry with its own `origin`.

## Original behaviour

Four faces, all Segoe (`.h:45-48`):

```c
#define FONT_LIGHT     "Segoe UI Light, Selawik Light, Open Sans Light, sans-serif"
#define FONT_SEMILIGHT "Segoe UI Semilight, Selawik Semilight, Open Sans, sans-serif"
#define FONT_REGULAR   "Segoe UI, Selawik, Open Sans, sans-serif"
#define FONT_SEMIBOLD  "Segoe UI Semibold, Selawik Semibold, Open Sans Semibold, sans-serif"
```

The ramp is not a CSS scale: sizes are absolute Pango points chosen per widget role
(`core/zrnmetrocore.c:509-510`, `:548-552`, `ztmetro.c:3678`, `:5942`, `:6350`,
`settings/ztsettings.c:2531`). The ones that matter for a port:

| role | size | face | citation |
|---|---|---|---|
| caps line over a page title | 11.5 | Semibold, ALL CAPS | `c:509` |
| caption under an entry | 12 | Regular, 0.6 grey | `c:549-550` |
| tile label / notification body | 13 | Regular | `z:3678`, `z:6393-6400` |
| field value / picker option | 17 | Regular | `c:3495`, `c:4509` |
| switch / dialog / choice title | 20–21 | Semilight | `c:3459`, `c:4433` |
| hub entry, app-list *letter* | 25.5 / 27 | Semilight | `c:548`, `z:4517` |
| app-list name | 22 | Semilight | `z:4525` |
| page title | 48 | Semilight | `c:510` |
| splash word mark | 56 | Light | `z:6720` |
| lock clock | 92 | Light | `z:5942` |

There is **no** letter-spacing and no colour-by-role beyond white / 0.6–0.62 grey / 0.38
(next tab) / 0.55 (placeholder); hierarchy is carried by size and the
Light/Semilight/Regular/Semibold axis.

## Android mechanism

| layer | mechanism | status |
|---|---|---|
| font files | Magisk module rewriting `fonts.xml`/`fonts_customization.xml` so `sans-serif` = Selawik (metric-compatible with Segoe UI) | **done** on the device |
| `sans-serif-light` / `-medium` | already resolve to the Selawik weights; the tokens map Semilight→`sans-serif-light`, Semibold→`sans-serif-medium` `[E]` | done |
| system text appearances | **B** RRO on `android` (`TextAppearance.*`) for the handful that exist; no new styles, no new fonts (an RRO cannot ship a font file — `10b` §3a) | partial |
| launcher | use the system families and sizes from the ramp; bundle Selawik **only** as a fallback for Wave 4.1 | to do |

Do not use `fontFamily="sans-serif-condensed"` or variable-font axes: Selawik in the
system font map has the four static weights, and a synthetic weight will smear at 720p.

## Build order

1. Apply the ramp in the launcher (one `TextAppearance` per role, named after the token).
2. Audit the system for places where the module's `fonts.xml` is not used (WebView,
   emoji fallback) and write down what stays Roboto.
3. RRO pass for the `TextAppearance`s that exist and are visible (status bar clock, QS
   label, settings toolbar).
4. Wave 4.4: generate all three surfaces from `ztmetro.tokens.json` so sizes cannot drift.

## Acceptance test

> Open Settings, then the launcher, then a third-party app: all three are in Selawik, and
> a 48 sp page title in our header is not clipped at fontScale 1.3.

## Risks

- **This is our most update-fragile artefact.** The module rewrites a system font
  configuration file; any LineageOS release that adds or renames a family (or a variable
  font axis) must be merged into the module by hand before flashing. Keep the pristine
  `fonts.xml` in the repo and diff it on every OTA.
- **Clipping.** Android's default `includeFontPadding=true` plus a fixed 104 dp header
  clips Selawik descenders at 48 sp. Set it false on title views only (body text needs
  the padding for scripts with ascenders).
- **Synthetic bold.** If a widget asks for weight 700 and only 600 exists, Android
  synthesises and smears; pin `textStyle` and use `sans-serif-medium` explicitly.
- **Licensing.** Selawik is SIL OFL; keep the notice with the module.
