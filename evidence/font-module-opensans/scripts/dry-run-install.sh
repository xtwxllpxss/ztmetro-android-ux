#!/bin/sh
# dry-run-install.sh — run modules/ztmetro-font/customize.sh as close to the device
# as a host allows, without Magisk/adb and without writing /system.
#
# It creates a temp tree that stands in for the live ROM:
#   $TMP/system/etc/font_fallback.xml   <- AOSP 16 fixture
#   $TMP/system/etc/fonts.xml           <- AOSP 16 fixture
# stages the module + fonts into $TMP/mod (MODPATH), rewrites the absolute /system
# probes/assignments in a COPY of customize.sh to point at $TMP, stubs the Magisk
# ui_print/abort/set_perm/set_perm_recursive functions, and sources it with
# `sh` (dash on Ubuntu, the closest host analogue of Magisk's busybox ash).
#
# Pass criteria: customize.sh exits 0; both overlay configs exist; each has exactly
# 20 OpenSans-*.ttf references; Python parses them and finds the same family/alias
# counts as the fixtures, 20 static fonts, and every referenced file on disk.
#
# Usage: dry-run-install.sh REPO_ROOT
set -eu

ROOT=${1:?usage: dry-run-install.sh REPO_ROOT}
SRC="$ROOT/modules/ztmetro-font"
FIX="$ROOT/evidence/font-module/fixtures"

[ -f "$SRC/customize.sh" ] || { echo "missing $SRC/customize.sh" >&2; exit 1; }
[ -f "$FIX/font_fallback.aosp16-roboto.xml" ] || { echo "missing fixture" >&2; exit 1; }

TMP=$(mktemp -d "${TMPDIR:-/tmp}/ztmetro-dryrun.XXXXXX")
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

mkdir -p "$TMP/system/etc"
cp "$FIX/font_fallback.aosp16-roboto.xml" "$TMP/system/etc/font_fallback.xml"
cp "$FIX/fonts.xml.aosp16" "$TMP/system/etc/fonts.xml"

# Stage the module payload exactly as the zip does (module root + system/fonts).
mkdir -p "$TMP/mod/system/fonts"
for f in module.prop customize.sh rewrite-fonts.sh verify-xml.awk fonts.fragment README.md; do
  cp "$SRC/$f" "$TMP/mod/$f"
done
for f in "$ROOT"/fonts/opensans/*.ttf; do
  cp "$f" "$TMP/mod/system/fonts/"
done
[ -f "$TMP/mod/system/fonts/OpenSans-Regular.ttf" ] || { echo "no staged fonts" >&2; exit 1; }

# Redirect only the live-config probes; $MODPATH/system/... paths stay untouched.
python3 - "$SRC/customize.sh" "$TMP/customize.sh" <<'PY'
import sys
s = open(sys.argv[1]).read()
pairs = [
    ('SYSTEM_ROOT=/system_root/system', 'SYSTEM_ROOT="$FAKE/system_root/system"'),
    ('SYSTEM_ROOT=/system', 'SYSTEM_ROOT="$FAKE/system"'),
    ('if [ -f /system/etc/font_fallback.xml ]; then',
     'if [ -f "$FAKE/system/etc/font_fallback.xml" ]; then'),
    ('elif [ -f /system_root/system/etc/font_fallback.xml ]; then',
     'elif [ -f "$FAKE/system_root/system/etc/font_fallback.xml" ]; then'),
]
for a, b in pairs:
    if a not in s:
        sys.exit('customize.sh no longer contains: ' + a)
    s = s.replace(a, b)
open(sys.argv[2], 'w').write(s)
PY

export FAKE="$TMP"
export MODPATH="$TMP/mod"

echo "== customize.sh dry run (sh=$(readlink -f /bin/sh 2>/dev/null || echo sh)) =="
sh -c '
  ui_print() { printf "%s\n" "$*"; }
  abort() { printf "ABORT: %s\n" "$*" >&2; exit 1; }
  set_perm() { :; }
  set_perm_recursive() { :; }
  . "$1"
' sh "$TMP/customize.sh"

FB="$MODPATH/system/etc/font_fallback.xml"
FX="$MODPATH/system/etc/fonts.xml"
[ -f "$FB" ] || { echo "FAIL: font_fallback.xml not produced" >&2; exit 1; }
[ -f "$FX" ] || { echo "FAIL: fonts.xml not produced" >&2; exit 1; }

for f in "$FB" "$FX"; do
  n=$(grep -c 'OpenSans-[A-Za-z]*\.ttf' "$f" || true)
  [ "$n" = "20" ] || { echo "FAIL: $f has $n OpenSans refs, expected 20" >&2; exit 1; }
done

python3 - "$FB" "$FX" "$MODPATH/system/fonts" <<'PY'
import os, sys, xml.etree.ElementTree as ET
fb, fx, fonts = sys.argv[1:4]
want = {'font_fallback.xml': (3, 6), 'fonts.xml': (160, 24)}
for path in (fb, fx):
    root = ET.parse(path).getroot()
    assert root.tag == 'familyset', (path, root.tag)
    fams = root.findall('family')
    aliases = root.findall('alias')
    exp = want[os.path.basename(path)]
    assert (len(fams), len(aliases)) == exp, (path, len(fams), len(aliases), exp)
    sans = [f for f in fams if f.get('name') == 'sans-serif']
    assert len(sans) == 1, (path, 'sans-serif count', len(sans))
    entries = sans[0].findall('font')
    assert len(entries) == 20, (path, 'font entries', len(entries))
    assert not sans[0].findall('.//axis'), (path, 'axis left in sans-serif')
    for f in entries:
        assert f.get('weight') and f.get('style') in ('normal', 'italic'), (path, f.attrib)
        name = (f.text or '').strip()
        assert name.startswith('OpenSans-') and name.endswith('.ttf'), (path, name)
        assert os.path.isfile(os.path.join(fonts, name)), (path, 'missing payload', name)
    print(f'  parse ok: {os.path.basename(path)}: {len(fams)} families, '
          f'{len(aliases)} aliases, sans-serif = 20 static Open Sans entries, '
          f'all payload files present')
PY

echo "PASS: customize.sh installed a validated overlay from the AOSP 16 fixtures"
echo "      (MODPATH=$MODPATH; removed on exit)"
