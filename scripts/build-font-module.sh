#!/usr/bin/env bash
# build-font-module.sh — build the ztmetro-font Magisk zip on any Linux/macOS host.
#
# Needs: bash, zip, unzip, awk, sh, coreutils (od, sha256sum/shasum, cmp).
# Does NOT need the Android SDK, Gradle, adb or a device.
#
# What it does:
#   1. verifies the Open Sans payload (magic bytes + SHA256SUMS) and module.prop;
#   2. syntax-checks the on-device installer scripts;
#   3. parses fonts.fragment as XML and checks 20 Open Sans entries;
#   4. replays the live splice against the checked-in AOSP 16 fixtures and parses
#      both rewritten configs (python3 if available);
#   5. stages the module tree, zips it with sorted entries/fixed mtimes, validates
#      the archive, and prints the zip path + sha256.
#
# Usage:
#   scripts/build-font-module.sh [--out PATH] [--allow-font-substitution]
#
# --allow-font-substitution skips the SHA256SUMS check for locally supplied
# replacement files (e.g. licensed Segoe UI copied to the Open Sans names); never
# distribute such a zip.

# Re-exec under bash if invoked as `sh script` (Linux dash would choke on pipefail).
if [ -z "${BASH_VERSION:-}" ]; then
  exec bash "$0" "$@"
fi

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
FONTS="$ROOT/fonts/opensans"
MOD="$ROOT/modules/ztmetro-font"
FIXTURES="$ROOT/evidence/font-module/fixtures"
OUT="$ROOT/artifacts/ztmetro-font-1.1.0.zip"
ALLOW_SUBSTITUTION=0

die() { printf 'build-font-module: %s\n' "$*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || die "required tool not found: $1"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --out) [ $# -ge 2 ] || die "--out needs a path"; OUT=$2; shift 2 ;;
    --allow-font-substitution) ALLOW_SUBSTITUTION=1; shift ;;
    -h|--help) sed -n '3,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done

for tool in awk zip unzip od sh tr sed sort find touch mktemp; do need "$tool"; done

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die "no sha256sum/shasum available"
  fi
}

sha256_check() {
  # $1 = directory, $2 = checksum file name in it
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$1" && sha256sum -c "$2" >/dev/null)
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$1" && shasum -a 256 -c "$2" >/dev/null)
  else
    die "no sha256sum/shasum available"
  fi
}

TMP=$(mktemp -d "${TMPDIR:-/tmp}/ztmetro-font.XXXXXX") || die "mktemp failed"
STAGE="$TMP/stage"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT HUP INT TERM

FONT_LIST="OpenSans-Light.ttf OpenSans-LightItalic.ttf OpenSans-Regular.ttf OpenSans-Italic.ttf OpenSans-SemiBold.ttf OpenSans-SemiBoldItalic.ttf OpenSans-Bold.ttf OpenSans-BoldItalic.ttf"

# ---------------------------------------------------------------------------
# 1. payload + identity
# ---------------------------------------------------------------------------
[ -f "$FONTS/SHA256SUMS" ] || die "missing $FONTS/SHA256SUMS"
[ -f "$MOD/module.prop" ] || die "missing $MOD/module.prop"

for f in $FONT_LIST; do
  [ -f "$FONTS/$f" ] || die "missing font: $FONTS/$f"
  magic=$(od -A n -t x1 -N 4 "$FONTS/$f" | tr -d ' \n')
  case "$magic" in
    00010000|4f54544f) : ;;
    *) die "$f is not a TTF/OTF (magic '${magic:-none}')" ;;
  esac
done

if [ "$ALLOW_SUBSTITUTION" -eq 1 ]; then
  say "warning: SHA256SUMS check skipped (--allow-font-substitution); do not distribute this zip"
else
  sha256_check "$FONTS" SHA256SUMS || die "font payload does not match $FONTS/SHA256SUMS"
fi

grep -qx 'id=ztmetro-font' "$MOD/module.prop" || die "module.prop id must be ztmetro-font"
grep -qx 'author=xtwxllpxss' "$MOD/module.prop" || die "module.prop author must be xtwxllpxss"
for key in name version versionCode description; do
  grep -q "^${key}=" "$MOD/module.prop" || die "module.prop lacks $key="
done

# No host paths and no hard-coded install paths may leak into the module.
if grep -RInE '/Users/|/home/[^ ]*/|/private/tmp|/var/folders' \
    "$MOD/module.prop" "$MOD/customize.sh" "$MOD/rewrite-fonts.sh" \
    "$MOD/verify-xml.awk" "$MOD/fonts.fragment" "$MOD/README.md" >/dev/null 2>&1; then
  die "module sources contain an absolute host path"
fi

# ---------------------------------------------------------------------------
# 2. scripts must parse under a POSIX shell (Magisk uses busybox ash)
# ---------------------------------------------------------------------------
sh -n "$MOD/customize.sh" || die "customize.sh has a shell syntax error"
sh -n "$MOD/rewrite-fonts.sh" || die "rewrite-fonts.sh has a shell syntax error"

# ---------------------------------------------------------------------------
# 3. fragment: well-formed XML, exactly 20 Open Sans entries, no variable axes
# ---------------------------------------------------------------------------
{
  printf '<familyset>\n'
  cat "$MOD/fonts.fragment"
  printf '</familyset>\n'
} > "$TMP/fragment.xml"
awk -f "$MOD/verify-xml.awk" "$TMP/fragment.xml" || die "fonts.fragment is not well-formed XML"

frag_entries=$(grep -c 'OpenSans-[A-Za-z]*\.ttf' "$MOD/fonts.fragment" || true)
[ "$frag_entries" = "20" ] || die "fonts.fragment has $frag_entries Open Sans entries, expected 20"
if grep -q '<axis[[:space:]>]' "$MOD/fonts.fragment"; then
  die "fonts.fragment must not contain <axis> entries"
fi

# Every face the fragment names must be in the shipped payload, and vice versa.
printf '%s\n' $FONT_LIST > "$TMP/faces"
frag_unknown=$(grep -o 'OpenSans-[A-Za-z]*\.ttf' "$MOD/fonts.fragment" | LC_ALL=C sort -u |
  grep -vxF -f "$TMP/faces" || true)
[ -z "$frag_unknown" ] || die "fonts.fragment names files not in the payload: $frag_unknown"
frag_faces=$(grep -o 'OpenSans-[A-Za-z]*\.ttf' "$MOD/fonts.fragment" | LC_ALL=C sort -u | wc -l | tr -d ' ')
[ "$frag_faces" = "8" ] || die "fonts.fragment uses $frag_faces distinct faces, expected 8"

# Static faces + real Cyrillic: parse the cmap (no fontTools/network needed).
COVER="$ROOT/evidence/font-module-opensans/scripts/font_coverage.py"
if command -v python3 >/dev/null 2>&1 && [ -f "$COVER" ]; then
  python3 "$COVER" --check \
    "$FONTS/OpenSans-Light.ttf" "$FONTS/OpenSans-Regular.ttf" \
    "$FONTS/OpenSans-SemiBold.ttf" "$FONTS/OpenSans-Bold.ttf" \
    > "$TMP/coverage.json" || die "cmap check failed: a shipped face lacks a required Cyrillic codepoint"
  python3 - "$TMP/coverage.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
for f in d:
    n = f['file'].rsplit('/', 1)[-1]
    if f.get('is_variable'):
        sys.exit(f'{n}: variable font, expected static')
    c = f['coverage']
    assert c['all_required_present'], (n, 'required Cyrillic missing')
    print(f"  cmap ok: {n}: {f['cmap']['unicode_codepoints']} codepoints, "
          f"cmap v{f['cmap']['version']}, Cyrillic {c['cyrillic_block_present']}/256, "
          f"U+0410/U+0430/U+0451/U+0416/U+044F present")
PY
else
  say "warning: python3 or $COVER missing; skipped cmap/Cyrillic check"
fi

# ---------------------------------------------------------------------------
# 4. replay the on-device splice against the AOSP 16 fixtures
# ---------------------------------------------------------------------------
simulate() {
  # $1 = fixture, $2 = output name, $3 = expected sans-serif fonts before
  fixture=$1
  out=$2
  [ -f "$fixture" ] || { say "warning: fixture missing, skipping simulation: $fixture"; return 0; }
  sh "$MOD/rewrite-fonts.sh" \
      --in "$fixture" \
      --out "$TMP/$out" \
      --fragment "$MOD/fonts.fragment" \
      --fontdir "$FONTS" || die "splice simulation failed for $fixture"
}

simulate "$FIXTURES/fonts.xml.aosp16" fonts.after.xml
simulate "$FIXTURES/font_fallback.aosp16-roboto.xml" font_fallback.after.xml

if command -v python3 >/dev/null 2>&1; then
  python3 - "$TMP/fonts.after.xml" "$TMP/font_fallback.after.xml" "$FONTS" <<'PY'
import sys, xml.etree.ElementTree as ET, os
a, b, fontdir = sys.argv[1], sys.argv[2], sys.argv[3]
for path in (a, b):
    root = ET.parse(path).getroot()
    assert root.tag == 'familyset', (path, root.tag)
    sans = [f for f in root.findall('family')
            if f.get('name') == 'sans-serif']
    assert len(sans) == 1, (path, 'sans-serif families', len(sans))
    fonts = sans[0].findall('font')
    assert len(fonts) == 20, (path, 'fonts', len(fonts))
    assert not sans[0].findall('.//axis'), (path, 'unexpected axis')
    for f in fonts:
        assert f.get('weight') is not None and f.get('style') in ('normal', 'italic')
        name = (f.text or '').strip()
        assert name.startswith('OpenSans-') and name.endswith('.ttf'), (path, name)
        assert os.path.isfile(os.path.join(fontdir, name)), (path, name)
    families = root.findall('family')
    aliases = root.findall('alias')
    assert families and aliases, path
    print(f'  parse ok: {os.path.basename(path)}: {len(families)} families, '
          f'{len(aliases)} aliases, sans-serif = 20 static Open Sans entries')
PY
else
  say "warning: python3 not found; skipped full XML parse (awk check already passed)"
fi

# ---------------------------------------------------------------------------
# 5. stage + zip
# ---------------------------------------------------------------------------
mkdir -p "$STAGE/system/fonts" "$STAGE/system/etc"
for f in module.prop customize.sh rewrite-fonts.sh verify-xml.awk fonts.fragment README.md; do
  cp "$MOD/$f" "$STAGE/$f"
done
for f in $FONT_LIST; do
  cp "$FONTS/$f" "$STAGE/system/fonts/$f"
done
cp "$FONTS/LICENSE.txt" "$STAGE/OpenSans-OFL.txt"
cp "$FONTS/ATTRIBUTION.md" "$STAGE/OpenSans-ATTRIBUTION.md"

chmod 0755 "$STAGE/customize.sh" "$STAGE/rewrite-fonts.sh"
find "$STAGE" -type f -exec chmod 0644 {} +
find "$STAGE" -type d -exec chmod 0755 {} +
# Fixed mtimes keep builds reproducible in content/order (zip stores them).
find "$STAGE" -exec touch -t 202601010000.00 {} + 2>/dev/null || true

FILELIST="$TMP/filelist"
( cd "$STAGE" && find . -type f -print | LC_ALL=C sort | sed 's|^\./||' ) > "$FILELIST"
[ -s "$FILELIST" ] || die "staging produced no files"

mkdir -p "$(dirname -- "$OUT")"
rm -f "$OUT"
( cd "$STAGE" && zip -X -9 -q "$OUT" -@ < "$FILELIST" ) || die "zip failed"
unzip -tq "$OUT" >/dev/null || die "zip integrity check failed"

ZIPLIST="$TMP/ziplist"
unzip -Z1 "$OUT" > "$ZIPLIST" || die "cannot list zip contents"
for required in module.prop customize.sh rewrite-fonts.sh verify-xml.awk fonts.fragment README.md OpenSans-OFL.txt OpenSans-ATTRIBUTION.md system/fonts/OpenSans-Regular.ttf; do
  grep -qx "$required" "$ZIPLIST" || die "zip lacks $required at the module root"
done
if grep -Eq '^/|(^|/)\.\.(/|$)' "$ZIPLIST"; then
  die "zip contains an absolute or traversing path"
fi
extra_fonts=$(grep -c '^system/fonts/.*\.ttf$' "$ZIPLIST" || true)
[ "$extra_fonts" = "8" ] || die "zip has $extra_fonts font files, expected 8"

HASH=$(sha256_file "$OUT")
SIZE=$(wc -c < "$OUT" | tr -d ' ')

say ""
say "module zip : $OUT"
say "sha256     : $HASH"
say "size       : $SIZE bytes"
say "fonts      : OpenSans-Light, OpenSans-Regular, OpenSans-SemiBold, OpenSans-Bold (+ 4 italics)"
say "entries    : $(wc -l < "$ZIPLIST" | tr -d ' ') files (module root + system/fonts)"
say "note       : config XML is generated on-device from the live ROM, not baked into the zip"
