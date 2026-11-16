# Open Sans 3.003 — the ztmetro Cyrillic-capable Segoe UI-class family

These are the **official upstream static TTFs** from
<https://github.com/googlefonts/opensans>, pinned to commit
`6cbc400d4855abee59d85c4ed871d510399bce7b` (2026-11-10), path `fonts/ttf/`.
They are redistributed under the SIL Open Font License 1.1 (see
[`LICENSE.txt`](LICENSE.txt)). Open Sans was designed by **Steve Matteson**, the
type designer of **Segoe UI**; it is a humanist sans in the same class, has a real
Cyrillic and Greek, and is the family the ztmetro design tokens already list after
Segoe UI and Selawik (`design/tokens/ztmetro.tokens.json`, `type.families`).

Open Sans is **not** Segoe UI: no Microsoft font file is stored in this repo or
shipped in the module.

## Files

Upstream names are kept unchanged; they are already self-describing and are the
names the Magisk module installs under `/system/fonts/`.

| file | bytes | sha256 | PS name | weight class |
|---|---|---|---|---|
| `OpenSans-Light.ttf` | 143788 | `3472097ec4d9e57228cbfad3c9911e53923664d3df86568d0b05d674399e034e` | OpenSans-Light | 300 |
| `OpenSans-LightItalic.ttf` | 151216 | `548dc557bcef26267d16f1afaf0770814a329c9f04a0168d396ca9d1ceafb16f` | OpenSans-LightItalic | 300 |
| `OpenSans-Regular.ttf` | 147528 | `c53aceea2dcf5b4098099c0c4d0a061d17e178a049317b42a422b1a9f7f8eb59` | OpenSans-Regular | 400 |
| `OpenSans-Italic.ttf` | 153256 | `93bc1bb6abf4e6b7c75d7131714061d5b57cc478abcabe4cb3519bb38fb917aa` | OpenSans-Italic | 400 |
| `OpenSans-SemiBold.ttf` | 150592 | `4a413711684a9dd564ef0f1c10cb62b5d9f7eb6df2cff962f5341a6ecd5f64ae` | OpenSans-SemiBold | 600 |
| `OpenSans-SemiBoldItalic.ttf` | 157560 | `c646a9aefc0e964f66487260639e375685328a6280856faea3e012c43fe9c56d` | OpenSans-SemiBoldItalic | 600 |
| `OpenSans-Bold.ttf` | 147264 | `27da758f4dcac9a65abe914c13b463b42982b9909bc65713424099f4810bd1e6` | OpenSans-Bold | 700 |
| `OpenSans-BoldItalic.ttf` | 153308 | `d672a770037104b6af45e1336b3d3c1729c8aea940f81e010f5a8a7319c29a21` | OpenSans-BoldItalic | 700 |

All eight are genuine **static** TrueType fonts: sfnt magic `00 01 00 00`, `glyf`
outlines, **no `fvar` table** (not variable fonts), `fsType = 0` (installable
embedding allowed). The build re-checks magic bytes and `SHA256SUMS`; the on-device
installer re-checks magic bytes.

## Coverage (measured, not assumed)

Parsed from the `cmap` table with
[`evidence/font-module-opensans/scripts/font_coverage.py`](../../evidence/font-module-opensans/scripts/font_coverage.py)
(no fontTools needed): **cmap version 0**, two Unicode format-4 subtables
(platform 0/3 and 3/1), **969 Unicode codepoints** in the upright faces and **972**
in the italics (three extra combining marks, U+0326–U+0328). Every face contains **248 of 256** codepoints in
the Cyrillic block U+0400–U+04FF, including the required Russian set:

| codepoint | character | present |
|---|---|---|
| U+0410 | А | yes |
| U+0430 | а | yes |
| U+0451 | ё | yes |
| U+0401 | Ё | yes |
| U+0416 | Ж | yes |
| U+044F | я | yes |
| U+0439 U+044A U+044B U+044D U+044E | й ъ ы э ю | yes |
| U+2116 U+00AB U+00BB U+2014 U+2026 | № « » — … | yes |

The only Cyrillic block codepoints Open Sans 3.003 does not have are
U+045D (Ukrainian і with grave), U+045E (Belarusian ў), U+045F (Macedonian џ) and the
combining marks U+0483–U+0487 — none of which Russian uses. Greek is present
(75 codepoints in U+0370–U+03FF).

## Metrics / design notes

Open Sans is not metric-compatible with Segoe UI (Selawik is). Swapping the module
from Selawik to Open Sans therefore changes Latin advance widths slightly and can
reflow text; it does **not** change any layout constant in the module or the ROM.
The weight mapping and the typographic rationale are in
[`repo/evidence/font-module-opensans/REPORT.md`](../../evidence/font-module-opensans/REPORT.md).

Selawik 1.01 had no italics; Open Sans does, so the module now ships four real italic
faces and the `style="italic"` entries are no longer upright.

## Using your own local font (not shipped)

Do not add Segoe UI to this repository or to a distributed zip: it is proprietary
and not redistributable. For a purely local build, drop your licensed TTFs in with
exactly the eight names above and pass `--allow-font-substitution` to
`scripts/build-font-module.sh`; never publish that zip.
