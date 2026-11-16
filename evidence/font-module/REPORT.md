# ztmetro-font — Selawik system sans-serif for LineageOS 23.2 (Android 16)

Evidence for the Magisk module `ztmetro-font` and `repo/scripts/build-font-module.sh`.
Status labels: `[F]` verified from source/file, `[E]` engineering estimate backed by
the cited source, `[?]` not verified (needs the device).

- Module zip: `repo/artifacts/ztmetro-font-1.0.0.zip`
  sha256 `9d50fddfb08c7872af71f58546aaa4112bbb117aa1b7ec0a595bc9d7fea469ee`,
  134010 bytes, 13 entries. Built on `hermes-ubuntu` with no Android SDK
  ([build log](build-ubuntu.log)); the macOS build produced the identical hash.
- Module id `ztmetro-font`, version `1.0.0` / code `10000`, author `xtwxllpxss`.

## 1. Font provenance

Official Selawik release `1.01`
(<https://github.com/microsoft/Selawik/releases/tag/1.01>, asset `Selawik_Release.zip`,
530652 B, sha256 `3f62c51e05e3b5a1e6241cf92a371f0be2ea1183aa87b30718bbd40832a8d423`).
Selawik is Microsoft's OFL-licensed "open source replacement for Segoe UI"
([upstream README](https://github.com/microsoft/Selawik)) and the free fallback already
listed in `design/tokens/ztmetro.tokens.json`. The five shipped TTFs are unmodified
release bytes, only renamed:

| repo file | release name | bytes | sha256 | PS name | class |
|---|---|---|---|---|---|
| `fonts/selawik/Selawik-Regular.ttf` | `selawk.ttf` | 44224 | `e9d98518d8ac2817782a9a382430463a2e0793ea68350b695bb727d9a830ee1c` | Selawik-Regular | 400 |
| `fonts/selawik/Selawik-Semibold.ttf` | `selawksb.ttf` | 44192 | `0a9e9d0549a10f24bef9b3a29e06fe6e0b5c21e7a784c503b048a307841a7783` | Selawik-Semibold | 600 |
| `fonts/selawik/Selawik-Light.ttf` | `selawkl.ttf` | 44232 | `8e19d073091a1e869b5b0d48b925e605c0a4c6ece7df7b22a364fb065314c4c5` | Selawik-Light | 300 |
| `fonts/selawik/Selawik-Semilight.ttf` | `selawksl.ttf` | 44260 | `8620960344f12093482cded984a1aafa5a57d24cbf7e9299da125ccf0e9d4102` | Selawik-Semilight | 350 |
| `fonts/selawik/Selawik-Bold.ttf` | `selawkb.ttf` | 44068 | `f0db5e174a90e0956ad7d2844bdca1d5e6da92ec65b2c04e57ba9b180668c904` | Selawik-Bold | 700 |

- Every file is a real TrueType font: magic bytes `00 01 00 00` (none is OTTO/CFF),
  checked in the build and again on-device by `customize.sh` (`od`).
- License: SIL OFL 1.1, full text in [`repo/fonts/selawik/LICENSE.txt`](../../fonts/selawik/LICENSE.txt)
  (copied into the zip as `Selawik-OFL.txt`), attribution in
  [`ATTRIBUTION.md`](../../fonts/selawik/ATTRIBUTION.md). No Segoe UI file is in the
  repo or the zip; `repo/fonts/selawik/README.md` describes a **local, user-supplied**
  Segoe UI substitution using the same five file names.
- Coverage measured with `fc-query` on `Selawik-Regular.ttf`: **347 codepoints**,
  Latin/Latin-1/Latin-Ext-A + punctuation/currency. No Cyrillic (U+0410 absent), no
  Greek (U+03B1 absent), no CJK/Arabic/emoji. Selawik 1.01 has **no italic faces**.
- Size: 5 files ≈ 216 KiB, mmap-ed by the font stack, negligible for the 2 GB budget
  in `docs/05-ram-budget.md`; no extra service/process.

## 2. What Android 16 actually reads — the rewrite target

AOSP `android-16.0.0_r3` changed the live config, and this is the part a fonts.xml-only
module would get wrong:

- `SystemFonts` reads `/system/etc/font_fallback.xml`:
  `FONTS_XML = getFontsXmlDir() + "font_fallback.xml"` (line 59),
  `LEGACY_FONTS_XML = … "fonts.xml"` (line 61), `getSystemFontConfig()` calls
  `getSystemFontConfigInternal(FONTS_XML, …)` (line 350); the legacy entry point is
  `getSystemPreinstalledFontConfigFromLegacyXml()` (line 382). Source:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r3/graphics/java/android/graphics/fonts/SystemFonts.java>
  `[F]`.
- `fonts.xml` is still installed (`data/fonts/Android.bp`, `prebuilt_etc { name:
  "fonts.xml" }`) and is still parsed by Skia's Android font manager
  (`#define LMP_SYSTEM_FONTS_FILE "/system/etc/fonts.xml"`, parse at line 715 of
  `external/skia/src/ports/SkFontMgr_android_parser.cpp`) — i.e. native text and
  WebView's generic families `[F]` for the path, `[E]` for the per-app effect.
- **The sans-serif family is a variable font.** In `data/fonts/fonts.xml`
  (lines 38–129) all 18 entries (9 weights × 2 styles) are `Roboto-Regular.ttf` with
  `<axis tag="ital" …/> <axis tag="wdth" …/> <axis tag="wght" …/>`. There are no
  per-weight Roboto static files in play: `Roboto-Regular.ttf` is the single VF, and
  the same file also backs `sans-serif-condensed` (`wdth=75`) and the `roboto` family.
  The aliases (`sans-serif-light`, `sans-serif-medium`, …) follow at lines 134–141.
- `font_fallback.xml` is generated at build time (`Android.bp`:
  `prebuilt_etc { name: "font_fallback.xml", src: ":generate_font_fallback" }`) from
  `external/roboto-fonts/font_config.json`. That JSON defines the primary family as
  one variable entry:
  `{"name": "sans-serif", "fonts": [{"file": "Roboto-Regular.ttf",
  "supportedAxes": "wght,ital", "axes": {"wdth": "100"}}]}`.
  Running AOSP's own `generate_fonts_xml` over that JSON produces:

  ```xml
  <family name="sans-serif">
    <font supportedAxes="wght,ital">
      Roboto-Regular.ttf
      <axis tag="wdth" stylevalue="100.0"/>
    </font>
  </family>
  ```

  (reproduced in [`fixtures/font_fallback.aosp16-roboto.xml`](fixtures/font_fallback.aosp16-roboto.xml);
  the full build output also contains all Noto fallback families, which the installer
  preserves verbatim).
- `FontListParser` takes `weight` (default 400), `style="italic"`, and only the
  `wght`/`ital` tokens in `supportedAxes` (lines 373–401); a `<font>` without
  `supportedAxes`/`<axis>` is a **static** face. `[F]`

Because Selawik ships static weights only, option A from
`docs/10a-systemless-injection.md` §4.2 (replace the variable Roboto file with a
variable Selawik) is impossible. Replacing the *file name* `Roboto-Regular.ttf` with a
static Selawik copy is also rejected as fragile `[E]`: the two configs still request
`wght`/`ital`/`wdth` axes that the static font does not have (one face for every
weight), the same file backs `sans-serif`, `sans-serif-condensed` (`wdth=75`) and the
`roboto` family, and `fonts.xml` has 18 entries pointing at it. A file swap would
therefore collapse all weights and distort condensed text while leaving the configs
lying about axes. The module uses option B instead: rewrite the family to static
per-weight entries in **both** configs, keeping every other family and fallback.

## 3. The exact transformation

The installer reads the live file, replaces only the first
`<family name="sans-serif"> … </family>` block with
[`fonts.fragment`](../../modules/ztmetro-font/fonts.fragment) (20 entries: 10 weights ×
normal/italic), and copies every other byte. Fixed weight map:

| Android weight | file | | Android weight | file |
|---|---|---|---|---|
| 100, 200, 300 | `Selawik-Light.ttf` | | 600 | `Selawik-Semibold.ttf` |
| 350 | `Selawik-Semilight.ttf` | | 700, 800, 900 | `Selawik-Bold.ttf` |
| 400 | `Selawik-Regular.ttf` | | 500 (`sans-serif-medium`) | `Selawik-Semibold.ttf` |

### 3.1 `fonts.xml` (before → after)

Before, [`transforms/fonts.xml.sans-serif.before.xml`](transforms/fonts.xml.sans-serif.before.xml)
(AOSP r3 lines 38–129): 18 variable entries, e.g.

```xml
    <family name="sans-serif">
        <font weight="100" style="normal">Roboto-Regular.ttf
          <axis tag="ital" stylevalue="0" />
          <axis tag="wdth" stylevalue="100" />
          <axis tag="wght" stylevalue="100" />
        </font>
        … 17 more weight/style entries, same file/axes …
   </family>
```

After, [`transforms/fonts.xml.sans-serif.after.xml`](transforms/fonts.xml.sans-serif.after.xml):

```xml
    <family name="sans-serif">
        <!-- ztmetro-font: Selawik 1.01 (SIL OFL 1.1), Segoe UI metric-compatible. … -->
        <font weight="100" style="normal">Selawik-Light.ttf</font>
        <font weight="200" style="normal">Selawik-Light.ttf</font>
        <font weight="300" style="normal">Selawik-Light.ttf</font>
        <font weight="350" style="normal">Selawik-Semilight.ttf</font>
        <font weight="400" style="normal">Selawik-Regular.ttf</font>
        <font weight="500" style="normal">Selawik-Semibold.ttf</font>
        <font weight="600" style="normal">Selawik-Semibold.ttf</font>
        <font weight="700" style="normal">Selawik-Bold.ttf</font>
        <font weight="800" style="normal">Selawik-Bold.ttf</font>
        <font weight="900" style="normal">Selawik-Bold.ttf</font>
        … the same 10 weights with style="italic" …
    </family>
```

Result: 160 families and 24 aliases preserved, sans-serif 18 fonts/54 axes → 20 static
fonts/0 axes; validated by a Python XML parse in the build
([`transforms/fonts.xml.aosp16.rewritten`](transforms/fonts.xml.aosp16.rewritten)).
Weight 350 is added so the design token's Semilight/350 has a real face; the alias
`sans-serif-light` (300) still resolves to Light.

### 3.2 `font_fallback.xml` (before → after)

Before, [`transforms/font_fallback.xml.sans-serif.before.xml`](transforms/font_fallback.xml.sans-serif.before.xml)
(one variable entry):

```xml
  <family name="sans-serif">
    <font supportedAxes="wght,ital">
      Roboto-Regular.ttf
      <axis tag="wdth" stylevalue="100.0"/>
    </font>
  </family>
```

After: the same 20-entry fragment as above. On the Roboto-only reproduction fixture
the result is 3 families/6 aliases preserved, 1 variable entry → 20 static entries,
parsed successfully ([`transforms/font_fallback.aosp16-roboto.rewritten`](transforms/font_fallback.aosp16-roboto.rewritten)).
The full on-device file has many more Noto families; the splice does not count or
enumerate them — it replaces exactly one block and then proves the rest is unchanged
byte-for-byte (see §4).

## 4. Installer and safety

`customize.sh` (Magisk, runs as root) → `rewrite-fonts.sh` (POSIX sh, also runs on a
Linux host) → `verify-xml.awk`. It never writes `/system`; the result is written to
`$MODPATH/system/etc/`, which Magisk magic-mounts at boot. It is idempotent because it
always starts from the live config, not from a previous module copy.

Validation before the module is accepted (any failure → `abort`, nothing installed):

1. payload: five expected files, TTF magic, fragment is well-formed XML with exactly
   20 Selawik refs and no `<axis>`, every referenced file exists;
2. input: parses under `verify-xml.awk` (balanced tags, one root);
3. exactly one single-line `<family name="sans-serif">`; any other shape (extra
   attributes, missing family, nested family) is rejected rather than guessed;
4. output: parses again; family count unchanged; alias count unchanged; axis count is
   `before − axes_in_old_family`; font count is `before − fonts_in_old_family + 20`;
   exactly 20 Selawik entries;
5. **strip comparison**: the same splice run with the family replaced by a marker on
   input and output must be byte-identical, proving nothing outside the family moved.

Failure modes exercised on the host (`rc`: 3 malformed input, 4 unexpected family,
5 missing font/fragment, 6 splice failure, 7 output validation). Under
`busybox sh` + `busybox awk` (the Magisk installer shell) both AOSP fixtures rewrite
correctly; the build also replays the splice and parses the result with Python.

Boot-safety argument: the stock `fonts.xml`/`font_fallback.xml` are untouched on disk;
a module that cannot produce a validated config aborts installation, so a malformed
config is not mounted. If the platform still rejects the config at boot, recovery is
non-destructive: Magisk safe mode (hold **Vol−** while booting), the empty file
`/data/adb/modules/ztmetro-font/disable`, `magisk --remove-modules`, or deleting the
module directory in recovery. `[E]` There is no way to prove boot behaviour without
the device; this is the main residual risk.

## 5. Module file list

Source tree `repo/modules/ztmetro-font/`:

| file | role |
|---|---|
| `module.prop` | id/version/author/description |
| `customize.sh` | locate live configs, run rewrite, abort on any failure |
| `rewrite-fonts.sh` | splice + all XML/count/strip validation |
| `verify-xml.awk` | balanced-tag XML check (handles AOSP multi-line tags/comments) |
| `fonts.fragment` | the 20-entry Selawik family (single source of truth) |
| `README.md` | install/limitations/recovery |

Zip contents (13 entries): the six files above, `Selawik-OFL.txt`,
`Selawik-ATTRIBUTION.md`, and `system/fonts/Selawik-{Regular,Semibold,Light,Semilight,Bold}.ttf`.
The two config XMLs are deliberately **not** baked into the zip: they are produced
from the installed ROM at install time. Full list:
[`module-filelist.txt`](module-filelist.txt).

## 6. Expected coverage

| consumer | changes? | evidence |
|---|---|---|
| Framework `TextView`/`Button` etc. with default/`sans-serif` family (stock Settings, most apps) | yes `[E]` | Java `SystemFonts` reads `font_fallback.xml` (verified path); `sans-serif` is the first family `[F]` |
| SystemUI (status bar clock, QS labels, notifications, keyguard text) | yes `[E]` | uses framework typefaces; no bundled font in AOSP SystemUI `[E]` |
| Compose apps using `FontFamily.Default`/`MaterialTheme.typography` | yes `[E]` | Default maps to `Typeface.DEFAULT` → `sans-serif` `[E]` |
| Native/Skia canvas text using the system font manager | yes `[E]` | Skia parses `/system/etc/fonts.xml` (verified file/line); module rewrites it too `[F]` path, `[E]` effect |
| WebView generic `sans-serif`/CSS default text | likely `[E]`/`[?]` | Chromium uses the Android SkFontMgr; not device-tested; sites with webfonts unaffected |
| Emoji | no (unchanged `NotoColorEmoji.ttf`) `[E]` | only sans-serif block replaced |
| CJK (Chinese/Japanese/Korean) | no (Noto CJK families untouched) `[E]` | preserved families |
| Monospace, serif, `sans-serif-condensed`, symbols | no `[E]` | preserved families |
| Cyrillic/Greek text | **not Selawik**; falls through to `NotoSansCJK-Regular.ttc` `[E]` | Selawik has no Cyrillic/Greek (verified cmap); Noto CJK has U+0410/U+03B1 (verified cmap); fallback order logic adds every family to the fallback chain (SystemFonts `pushFamilyToFallback`) |
| Apps bundling fonts (Telegram headers, games, Flutter/RN apps shipping fonts) | no `[E]`, Telegram `[F]` source | see §7 |
| Apps explicitly using `serif`, `monospace`, `sans-serif-condensed`, or a font provider | no `[E]` | those families are untouched |

Italic text is rendered with the upright Selawik face of the same weight: Selawik
1.01 has no italic, and the module deliberately does not synthesize an oblique or
fall back to Roboto `[F]` files, `[E]` render.

## 7. What will NOT change — Telegram check

Telegram bundles its own fonts and sets them explicitly via
`Typeface.createFromAsset` / `AndroidUtilities.getTypeface`, so its branded styles do
not follow the system font `[F]` (source):
`TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java`
lines 253–257 define `fonts/rmedium.ttf`, `fonts/rextrabold.ttf`,
`fonts/rmediumitalic.ttf`, `fonts/rmono.ttf`, `fonts/mw_bold.ttf`; line 2501 is
`getTypeface(String assetPath)`. The asset directory in the same repo contains
`gram.ttf`, `mw_bold.ttf`, `mw_bolditalic.ttf`, `num.otf`, `rcondensedbold.ttf`,
`rextrabold.ttf`, `ritalic.ttf`, `rmedium.ttf`, `rmediumitalic.ttf`, `rmono.ttf`,
`rmono_var.ttf`; git blob sizes are e.g. `rmedium.ttf` 274864 B, `ritalic.ttf`
295296 B, `rextrabold.ttf` 112772 B, `rmono.ttf` 71864 B.

Expected: Telegram's **message list and normal labels** (default platform paint) pick
up Selawik `[E]`; its **headers/medium/extrabold/italic/mono/Merriweather** stay
bundled Roboto/Merriweather `[F]` source-level. The built APK itself was **not**
inspected: `https://telegram.org/dl/android/apk` redirects to
`cdn4.telesco.pe/file/Telegram.apk…`, and the download timed out after 80 s with 0
bytes from this host (same block recorded in `evidence/injection-research/04-fonts.log`).
The source tree is the stronger evidence here, but APK resource verification remains
open `[?]`.

Also outside coverage: apps using a `fontFamily` provider / downloadable font, apps
that set `Typeface.createFromAsset`, icon fonts, OEM `/product/fonts` families, and any
`/product/etc/fonts_customization.xml` that redefines the named `sans-serif` family
(FontListParser lets OEM customizations override named families — check this file on
the device).

## 8. Verification on a rooted device (not run here)

```sh
# 0. module present and mounted
adb shell su -c 'ls -l /data/adb/modules/ztmetro-font/system/etc /data/adb/modules/ztmetro-font/system/fonts'
adb shell su -c 'ls -l /system/etc/font_fallback.xml /system/etc/fonts.xml /system/fonts/Selawik-*.ttf'

# 1. the live config is the rewritten one (20 Selawik entries, no axes in the family)
adb shell su -c 'grep -c "Selawik-" /system/etc/font_fallback.xml'          # 20
adb shell su -c 'sed -n "/<family name=\"sans-serif\">/,/<\/family>/p" /system/etc/font_fallback.xml' | head -30
adb shell su -c 'grep -c "Selawik-" /system/etc/fonts.xml'                  # 20

# 2. prove it parses on a host (no XML parser on-device needed)
adb pull /system/etc/font_fallback.xml /tmp/fbf.xml
adb pull /system/etc/fonts.xml /tmp/fonts.xml
python3 -c 'import xml.etree.ElementTree as E; [E.parse(p) for p in ("/tmp/fbf.xml","/tmp/fonts.xml")]; print("parse ok")'

# 3. prove the framework loaded that file at boot
adb logcat -d | grep -i 'Loading font config'      # SystemFonts logs the path it parses

# 4. no OEM override and no stale config
adb shell su -c 'ls -l /product/etc/fonts_customization.xml 2>/dev/null; cat /product/etc/fonts_customization.xml 2>/dev/null'

# 5. visual: Settings, SystemUI, a Compose app, WebView, and a Cyrillic/Greek sample
adb shell am force-stop com.android.settings
adb shell am start -a android.settings.SETTINGS
adb exec-out screencap -p > /tmp/settings.png
adb shell cmd statusbar expand-notifications && sleep 1 && adb exec-out screencap -p > /tmp/shade.png

# 6. rollback (non-destructive; /system was never written)
adb shell su -c 'touch /data/adb/modules/ztmetro-font/disable' && adb reboot
# or: adb shell su -c 'magisk --remove-modules' && adb reboot
# bootloop without adb: Magisk safe mode = hold Vol- while booting
```

Pass criteria: both configs parse, each has 20 Selawik entries, `sans-serif` has no
`<axis>`, logcat names `/system/etc/font_fallback.xml`, Settings/SystemUI render
Selawik Latin, emoji/CJK/mono are unchanged, and Cyrillic text is still legible (it
will be Noto CJK, not Selawik).

## 9. What could not be verified

- **No device run.** LineageOS 23.2/`ugglite` was not available in this task: boot,
  render and recovery are untested `[?]`. The module's own boot risk is mitigated by
  validation + Magisk disable paths, not proven.
- **The exact LineageOS `font_fallback.xml` was not downloaded.** AOSP generates it at
  build time; no full published file was found. Its sans-serif block was reproduced
  with AOSP's `generate_fonts_xml` from the exact r3 `external/roboto-fonts/font_config.json`;
  the installer reads the real file on the device and refuses unknown shapes, so this
  affects evidence, not the installed result `[F]/[E]`.
- **Telegram APK resources not inspected** (CDN timeout; source tree used instead).
- **WebView/Chromium generic-font resolution** not device-tested `[?]`; only the
  underlying Skia `fonts.xml` parse is verified `[F]`.
- **Cyrillic/Greek fallback target** is reasoned from the fallback construction and
  verified Noto CJK cmap coverage, not rendered `[E]`.
- **Italic appearance** (upright instead of slanted) and **weight 350 matching** are
  `[E]`; they follow the parser/`FontStyle` rules but were not seen on a phone.
- **OTA/update survival** and **`/product` font customizations** on this device `[?]`.
