#!/bin/sh
# rewrite-fonts.sh — replace the "sans-serif" family in an Android font config.
#
# Reads the LIVE config (/system/etc/font_fallback.xml or /system/etc/fonts.xml),
# swaps only the first <family name="sans-serif"> ... </family> block for the fixed
# Open Sans family in fonts.fragment, and leaves every other family, fallback and
# alias byte-for-byte intact. Nothing is written to /system: the caller points
# --out at the Magisk module overlay.
#
# Usage:
#   rewrite-fonts.sh --in FILE --out FILE --fragment FILE --fontdir DIR
#
# Exit codes:
#   0  output written and validated
#   2  usage / missing argument
#   3  input config missing or not well-formed
#   4  sans-serif family not in the expected single-line form
#   5  fonts.fragment is not valid / incomplete
#   6  transformation failed
#   7  output validation failed
set -u

PROG=ztmetro-font-rewrite
SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd) || SELF_DIR=.
VERIFY="$SELF_DIR/verify-xml.awk"
FONT_NAMES="OpenSans-Light.ttf OpenSans-LightItalic.ttf OpenSans-Regular.ttf OpenSans-Italic.ttf OpenSans-SemiBold.ttf OpenSans-SemiBoldItalic.ttf OpenSans-Bold.ttf OpenSans-BoldItalic.ttf"

IN=
OUT=
FRAG=
FONTDIR=

usage() {
  echo "usage: rewrite-fonts.sh --in FILE --out FILE --fragment FILE --fontdir DIR" >&2
}

fail() {
  code=$1
  shift
  echo "$PROG: $*" >&2
  exit "$code"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --in)       [ $# -ge 2 ] || { usage; exit 2; }; IN=$2; shift 2 ;;
    --out)      [ $# -ge 2 ] || { usage; exit 2; }; OUT=$2; shift 2 ;;
    --fragment) [ $# -ge 2 ] || { usage; exit 2; }; FRAG=$2; shift 2 ;;
    --fontdir)  [ $# -ge 2 ] || { usage; exit 2; }; FONTDIR=$2; shift 2 ;;
    -h|--help)  usage; exit 0 ;;
    *)          usage; exit 2 ;;
  esac
done

[ -n "$IN" ] && [ -n "$OUT" ] && [ -n "$FRAG" ] && [ -n "$FONTDIR" ] || { usage; exit 2; }
[ -f "$IN" ] || fail 3 "input config not found: $IN"
[ -r "$IN" ] || fail 3 "input config not readable: $IN"
[ -f "$FRAG" ] || fail 5 "fragment not found: $FRAG"
[ -d "$FONTDIR" ] || fail 5 "font directory not found: $FONTDIR"
[ -f "$VERIFY" ] || fail 5 "verify-xml.awk not found next to $0"

mkdir -p "$(dirname "$OUT")" || fail 7 "cannot create output directory for $OUT"

TMP_OUT="$OUT.tmp.$$"
TMP_STRIP_IN="$OUT.strip-in.$$"
TMP_STRIP_OUT="$OUT.strip-out.$$"
TMP_FRAG="$OUT.frag.$$"
TMP_STATS="$OUT.stats.$$"

cleanup() {
  rm -f "$TMP_OUT" "$TMP_STRIP_IN" "$TMP_STRIP_OUT" "$TMP_FRAG" "$TMP_STATS"
}
trap cleanup EXIT HUP INT TERM

# ---------------------------------------------------------------------------
# 1. The fragment we splice must itself be well-formed and reference only the
#    five shipped files. This catches a truncated/edited fragment at install time.
# ---------------------------------------------------------------------------
{
  printf '<familyset>\n'
  cat "$FRAG"
  printf '</familyset>\n'
} > "$TMP_FRAG" || fail 5 "cannot read fragment: $FRAG"

awk -f "$VERIFY" "$TMP_FRAG" || fail 5 "fonts.fragment is not well-formed XML"

frag_fonts=$(grep -c 'OpenSans-[A-Za-z]*\.ttf' "$FRAG" 2>/dev/null || true)
[ "$frag_fonts" = "20" ] || fail 5 "fonts.fragment has $frag_fonts Open Sans entries, expected 20"

for name in $FONT_NAMES; do
  [ -f "$FONTDIR/$name" ] || fail 5 "fragment references missing font: $name"
done

# ---------------------------------------------------------------------------
# 2. The live config must already be well-formed, and the target family must be
#    exactly one line: <family name="sans-serif">. Anything else is an unknown
#    ROM layout and we refuse rather than guess.
# ---------------------------------------------------------------------------
awk -f "$VERIFY" "$IN" || fail 3 "live config is not well-formed XML: $IN"

targets=$(grep -c '^[[:space:]]*<family name="sans-serif">[[:space:]]*$' "$IN" 2>/dev/null || true)
[ "$targets" = "1" ] || fail 4 "expected exactly one single-line <family name=\"sans-serif\"> in $IN, found ${targets:-0}"

families_before=$(grep -c '<family[[:space:]>]' "$IN" 2>/dev/null || true)
aliases_before=$(grep -c '<alias[[:space:]>]' "$IN" 2>/dev/null || true)
fonts_before=$(grep -c '<font[[:space:]>]' "$IN" 2>/dev/null || true)
axes_before=$(grep -c '<axis[[:space:]>]' "$IN" 2>/dev/null || true)
[ "${families_before:-0}" -ge 1 ] || fail 3 "no families found in $IN"

# ---------------------------------------------------------------------------
# 3. Splice. strip=1 emits a marker instead of the fragment; running that over
#    input and output proves the rest of the file was not touched.
# ---------------------------------------------------------------------------
transform() {
  # $1 = in, $2 = out, $3 = strip (0/1), $4 = stats file
  awk -v frag="$FRAG" -v stats="$4" -v strip="$3" '
    BEGIN { skip = 0; replaced = 0; tfonts = 0; taxes = 0; bad = 0 }
    {
      if (!skip) {
        if ($0 ~ /^[[:space:]]*<family name="sans-serif">[[:space:]]*$/) {
          if (strip == 1) {
            print "<!--SANS-REMOVED-->"
          } else {
            while ((getline fl < frag) > 0) {
              print fl
            }
            close(frag)
          }
          replaced++
          skip = 1
          next
        }
        print
        next
      }
      if ($0 ~ /^[[:space:]]*<family([[:space:]>]|$)/) { bad = 1; exit 25 }
      if ($0 ~ /^[[:space:]]*<\/family>[[:space:]]*$/) { skip = 0; next }
      if ($0 ~ /<font([[:space:]>])/) { tfonts++ }
      if ($0 ~ /<axis([[:space:]>])/) { taxes++ }
      next
    }
    END {
      printf "%d %d %d\n", replaced, tfonts, taxes > stats
      if (bad) exit 25
      if (skip) exit 26
      if (replaced != 1) exit 27
    }
  ' "$1" > "$2"
}

transform "$IN" "$TMP_OUT" 0 "$TMP_STATS"
rc=$?
[ "$rc" -eq 0 ] || fail 6 "sans-serif splice failed (awk exit $rc)"

read -r replaced tfonts taxes < "$TMP_STATS" || fail 6 "missing transformation stats"
[ "$replaced" = "1" ] || fail 6 "replaced $replaced families, expected 1"
[ "${tfonts:-0}" -ge 1 ] || fail 6 "the sans-serif family in $IN contains no <font> entries"

# ---------------------------------------------------------------------------
# 4. Validate the output: well-formed, same family/alias/axis counts (minus the
#    axes that belonged to the variable Roboto family), exactly 20 Open Sans
#    entries, and everything outside the family identical.
# ---------------------------------------------------------------------------
awk -f "$VERIFY" "$TMP_OUT" || fail 7 "rewritten config is not well-formed XML"

families_after=$(grep -c '<family[[:space:]>]' "$TMP_OUT" 2>/dev/null || true)
aliases_after=$(grep -c '<alias[[:space:]>]' "$TMP_OUT" 2>/dev/null || true)
fonts_after=$(grep -c '<font[[:space:]>]' "$TMP_OUT" 2>/dev/null || true)
axes_after=$(grep -c '<axis[[:space:]>]' "$TMP_OUT" 2>/dev/null || true)
opensans_after=$(grep -c 'OpenSans-[A-Za-z]*\.ttf' "$TMP_OUT" 2>/dev/null || true)

[ "$families_after" = "$families_before" ] || fail 7 "family count changed: $families_before -> $families_after"
[ "$aliases_after" = "$aliases_before" ] || fail 7 "alias count changed: $aliases_before -> $aliases_after"
[ "$axes_after" = "$((axes_before - taxes))" ] || fail 7 "axis count changed: expected $((axes_before - taxes)), got $axes_after"
[ "$fonts_after" = "$((fonts_before - tfonts + 20))" ] || fail 7 "font count changed: expected $((fonts_before - tfonts + 20)), got $fonts_after"
[ "$opensans_after" = "20" ] || fail 7 "expected 20 Open Sans entries in output, got ${opensans_after:-0}"

transform "$IN" "$TMP_STRIP_IN" 1 /dev/null || fail 7 "strip check failed on input"
transform "$TMP_OUT" "$TMP_STRIP_OUT" 1 /dev/null || fail 7 "strip check failed on output"
if command -v cmp >/dev/null 2>&1; then
  cmp -s "$TMP_STRIP_IN" "$TMP_STRIP_OUT" || fail 7 "content outside the sans-serif family changed"
else
  diff -q "$TMP_STRIP_IN" "$TMP_STRIP_OUT" >/dev/null 2>&1 || fail 7 "content outside the sans-serif family changed"
fi

# Catch a fragment whose entries would nest inside something unexpected.
if grep -q '<axis[[:space:]>]' "$FRAG" 2>/dev/null; then
  fail 5 "fonts.fragment must not contain variable axes"
fi

mv -f "$TMP_OUT" "$OUT" || fail 7 "cannot write $OUT"
chmod 0644 "$OUT" 2>/dev/null || true

echo "$PROG: $(basename "$IN") -> $(basename "$OUT"): sans-serif $tfonts font(s)/$taxes axis entry(s) -> 20 static Open Sans entries; all other families intact"
exit 0
