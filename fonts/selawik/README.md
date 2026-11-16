# Selawik 1.01 — the ztmetro public Segoe UI stand-in

These are the **official Selawik 1.01 binary release files** from
<https://github.com/microsoft/Selawik/releases/tag/1.01>, redistributed under the
SIL Open Font License 1.1 (see [`LICENSE.txt`](LICENSE.txt)). Selawik is Microsoft's
open, **metric-compatible clone of Segoe UI**: same advance widths and vertical
metrics, different outlines. It is the font the ztmetro design tokens already list as
the first free fallback after Segoe UI
(`display` = "Segoe UI Semilight, Selawik Semilight, …",
`body` = "Segoe UI, Selawik, …" in `design/tokens/ztmetro.tokens.json`).

Selawik is **not** Segoe UI and no Microsoft Segoe UI file is stored in this repo.

## Files

Original file names in the release are 8.3 (`selawk.ttf`, `selawkl.ttf`, …); they are
renamed here to the names the Magisk module installs under `/system/fonts/` so that
the font config references are self-describing. Only the name changed — bytes and
sha256 are the release bytes.

| file | release name | size (B) | sha256 | PS name | weight class |
|---|---|---|---|---|---|
| `Selawik-Regular.ttf` | `selawk.ttf` | 44224 | `e9d98518d8ac2817782a9a382430463a2e0793ea68350b695bb727d9a830ee1c` | Selawik-Regular | 400 |
| `Selawik-Semibold.ttf` | `selawksb.ttf` | 44192 | `0a9e9d0549a10f24bef9b3a29e06fe6e0b5c21e7a784c503b048a307841a7783` | Selawik-Semibold | 600 |
| `Selawik-Light.ttf` | `selawkl.ttf` | 44232 | `8e19d073091a1e869b5b0d48b925e605c0a4c6ece7df7b22a364fb065314c4c5` | Selawik-Light | 300 |
| `Selawik-Semilight.ttf` | `selawksl.ttf` | 44260 | `8620960344f12093482cded984a1aafa5a57d24cbf7e9299da125ccf0e9d4102` | Selawik-Semilight | 350 |
| `Selawik-Bold.ttf` | `selawkb.ttf` | 44068 | `f0db5e174a90e0956ad7d2844bdca1d5e6da92ec65b2c04e57ba9b180668c904` | Selawik-Bold | 700 |

All five files are genuine TrueType outlines: magic bytes `00 01 00 00` (verified;
no OTTO/CFF file). The release zip itself is
`Selawik_Release.zip`, sha256
`3f62c51e05e3b5a1e6241cf92a371f0be2ea1183aa87b30718bbd40832a8d423`, 530652 bytes.
`SHA256SUMS` holds the five hashes in `sha256sum -c` form.

## What Selawik covers — and what it does not

`fc-query` reports **347 codepoints** for every face: Basic Latin, Latin-1
Supplement, Latin Extended-A, combining marks, common punctuation, currency signs
(€, £), smart quotes, dashes and bullets. It has **no Cyrillic and no Greek**
(U+0410, U+03B1 absent), and no CJK, Arabic, emoji or symbols.

Consequences on the phone:

- Cyrillic/Greek UI text does **not** become Selawik; per-glyph fallback picks the
  first later family with the glyph, which in AOSP 16 is `NotoSansCJK-Regular.ttc`
  (verified to contain U+0410, U+03B1, U+4E00). This is readable but not Segoe-like
  and not the same design as the Latin text.
- If you need Cyrillic/Greek in the same face as the Latin UI, use Segoe UI itself
  locally (below); Segoe UI covers Cyrillic and Greek.
- Emoji, CJK, mono, serif and `sans-serif-condensed` are untouched by the module and
  keep their stock fonts.

Selawik 1.01 has **no italic faces**. The module's font config therefore maps
`style="italic"` entries to the upright Selawik face of the same weight. Android does
not synthesize an oblique for a declared-italic entry, so italic text is expected to
render upright instead of switching back to Roboto `[E]` (the declared style is
italic, the outlines are not). This was a deliberate choice to keep one typeface
family and one set of metrics.

## Using your own Segoe UI locally (not shipped)

Segoe UI is proprietary; Microsoft does not permit redistribution. If you own a
licensed copy you may substitute it **on your own machine**:

1. Copy your Segoe UI TTFs into this directory using exactly these names (the module
   and its font config refer to these names):
   `Selawik-Regular.ttf`, `Selawik-Semibold.ttf`, `Selawik-Light.ttf`,
   `Selawik-Semilight.ttf`, `Selawik-Bold.ttf` (overwrite the Selawik files).
2. Rebuild the module: `scripts/build-font-module.sh`. The build validates the TTF
   magic bytes and records the new hashes, but it will not match `SHA256SUMS` — pass
   `--allow-font-substitution` for that build (or update `SHA256SUMS` locally).
3. Never commit those files or a zip built from them; they are not OFL and are not
   part of this repository. The module README repeats this warning for end users.

The same mapping is why Segoe UI Semibold (600) drives Android's
`sans-serif-medium` (500) requests: the ztmetro semibold weight is 600 and Android's
`medium` is the closest stock weight, so mapping 500 → Semibold reproduces the
WP page-title look.
