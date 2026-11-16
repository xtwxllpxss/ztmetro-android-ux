# ztmetro-font — systemless Open Sans (Cyrillic, Segoe UI-class) sans-serif

A Magisk module that makes **Open Sans 3.003** the phone's default `sans-serif` font
without rebuilding LineageOS. It targets **Android 15/16** (LineageOS 23.2 on
`ugglite`), where `SystemFonts` reads `/system/etc/font_fallback.xml` and
`/system/etc/fonts.xml` is the legacy/Skia config.

Open Sans was designed by **Steve Matteson** — the designer of **Segoe UI** — and is
SIL OFL 1.1. It has a real **Cyrillic** (248/256 of U+0400–U+04FF, including the full
Russian alphabet) and Greek (75 codepoints), plus real italics. This is what replaces
the old Selawik payload: Selawik 1.01 is Latin-only (347 codepoints, no Cyrillic), so
Russian text used to fall through to Noto and looked like a different typeface.

The ztmetro tokens already list it after Segoe UI and Selawik
(`design/tokens/ztmetro.tokens.json`, `type.families`). No Segoe UI file is included;
the source repo's `fonts/opensans/README.md` documents provenance and the local
substitution procedure. This zip ships `OpenSans-OFL.txt` and
`OpenSans-ATTRIBUTION.md`.

## Install

```sh
adb push ztmetro-font-1.1.0.zip /sdcard/
# Magisk app -> Modules -> Install from storage -> ztmetro-font-1.1.0.zip
# or:
adb shell su -c "magisk --install-module /sdcard/ztmetro-font-1.1.0.zip"
adb reboot
```

The installer runs on the device, reads the **live** config, and aborts (installing
nothing) if the file is not the expected Android 15/16 layout. No reboot is needed to
build the module; a reboot is needed to mount it. Installing 1.1.0 over 1.0.0
replaces the Selawik files in the same module directory.

## What it changes

| path | change |
|---|---|
| `/system/etc/font_fallback.xml` (Android 15+ primary) | `sans-serif` family replaced by 20 static Open Sans entries (10 weights × normal/italic); every other family, fallback and alias copied byte-for-byte |
| `/system/etc/fonts.xml` (legacy, Skia/native/WebView) | same replacement, same fragment |
| `/system/fonts/OpenSans-{Light,LightItalic,Regular,Italic,SemiBold,SemiBoldItalic,Bold,BoldItalic}.ttf` | eight new files |

Weight map in both configs:

| Android weight | normal | italic |
|---|---|---|
| 100, 200, 300, **350** | Light | LightItalic |
| 400 | Regular | Italic |
| 500, 600 | SemiBold (500 is `sans-serif-medium`) | SemiBoldItalic |
| 700, 800, 900 | Bold | BoldItalic |

**350** (the token's `semilight`) has no Open Sans face. It maps to **Light**, which
matches the design-token policy that sends semilight/350 to `sans-serif-light`/300;
mapping it to Regular would have made 350 indistinguishable from 400.

Unlike the Selawik build, the italic entries point at real italic faces, so italic
text is slanted and keeps the same family.

It does **not** touch emoji, CJK, monospace, serif, `sans-serif-condensed`, symbols
or any other font file. Alias lines (`sans-serif-light`, `sans-serif-medium`, …) are
left intact and re-point at the rewritten family automatically.

## Known limitations

- **Not metric-compatible with Segoe UI.** Selawik was; Open Sans is not. Latin
  advance widths differ slightly and text can reflow. This is the price of shipping a
  family that actually has Cyrillic; no layout constant in the ROM or module changes.
- **Italic is now real** (an improvement over 1.0.0), but Open Sans italic is a true
  italic design, not a Segoe UI italic.
- Apps that bundle their own fonts ignore this (Telegram's medium/extrabold/italic
  headers, games, many Flutter/RN apps). Web pages that ship webfonts also ignore it;
  only plain web content that asks for generic `sans-serif` may follow the system.
  See `evidence/font-module-opensans/REPORT.md` §6.
- A `/product/etc/fonts_customization.xml` that defines a named `sans-serif` family
  would override this module; check it on the device.
- Effects after a LineageOS OTA: re-flash the module (Magisk modules generally
  survive; re-check).

## Safety / recovery

The module only overlays files; `/system` is never written. The installer validates
the produced XML (well-formedness, family/alias counts, 20 Open Sans entries, and a
byte-comparison of everything outside the family) and aborts on any mismatch.

If the device fails to boot after installing:

1. **Magisk safe mode**: hold **Volume Down** while booting (Magisk disables all
   modules for that boot); remove/disable `ztmetro-font`, reboot.
2. With adb access: `adb shell su -c "touch /data/adb/modules/ztmetro-font/disable"`
   then reboot; or `adb shell su -c "magisk --remove-modules"` and reboot.
3. Recovery/TWRP: delete `/data/adb/modules/ztmetro-font/` or create the empty file
   `disable` inside it.
4. Worst case: the stock font config is untouched on disk; removing the module
   directory restores stock fonts on the next boot.

A malformed font config is the only boot risk; that is why the installer refuses to
write one it cannot validate.

## Files in this source tree

```
modules/ztmetro-font/
  module.prop            Magisk identity (1.1.0 / 10100)
  customize.sh           installer: locate live config, call rewrite, validate
  rewrite-fonts.sh       POSIX sh: splice + validate (also runs on a Linux host)
  verify-xml.awk         tag-balance XML check used on-device
  fonts.fragment         the only thing that replaces the sans-serif family
  README.md              this file
```

The built zip adds `system/fonts/OpenSans-*.ttf` (8), the generated
`system/etc/font_fallback.xml` + `system/etc/fonts.xml`, `OpenSans-OFL.txt` and
`OpenSans-ATTRIBUTION.md`.

## Build

```sh
scripts/build-font-module.sh
# -> artifacts/ztmetro-font-1.1.0.zip + sha256, module file list validated
```

No Android SDK, Gradle or device is needed: `bash`, `zip`, `unzip`, `awk`, `sh` and
(optionally) `python3` for the full XML parse and the cmap/Cyrillic check. The script
also replays the splice against the checked-in AOSP 16 fixtures, parses the result,
and verifies that each shipped upright face parses as a static TTF whose cmap
contains U+0410, U+0430, U+0451, U+0416 and U+044F.
