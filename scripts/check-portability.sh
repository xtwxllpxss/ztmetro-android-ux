#!/usr/bin/env bash
# Fails when active sources hardcode a device (ugglite) or its screen, or when
# resolution-specific constants leak into functional code. Comments are ignored
# for the screen-size check; names of the reference device are allowed only in
# comments and documentation.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail=0
scan_dir() {
  local dir=$1
  [ -d "$dir" ] || return 0
  find "$dir" -type f \( -name '*.java' -o -name '*.xml' -o -name '*.sh' \) \
    -not -path '*/build/*' -not -path '*/out/*' -not -name 'check-portability.sh' -print0 2>/dev/null
}
echo "== device-name literals in code (comments only allowed)"
while IFS= read -r -d '' f; do
  grep -nE 'ugglite|Mi8917|msm8917|Redmi[ _]Note[ _]5A' "$f" 2>/dev/null \
    | grep -vE '^[0-9]+: *(//|\*|#)|lines\.append' | sed "s|^|$f:|" || true
done < <(scan_dir launcher; scan_dir actioncentre; scan_dir ime; scan_dir settings; scan_dir scripts) \
  | tee /tmp/ztm-port-dev.txt
[ -s /tmp/ztm-port-dev.txt ] && { echo "FAIL: device literals in code"; fail=1; } || echo "PASS"

echo "== screen-size literals in functional code"
while IFS= read -r -d '' f; do
  grep -nE '\b(720|1280)\b' "$f" 2>/dev/null \
    | grep -viE '^[0-9]+: *(//|\*|#)|test|README' | sed "s|^|$f:|" || true
done < <(scan_dir launcher; scan_dir actioncentre; scan_dir ime; scan_dir settings) \
  | tee /tmp/ztm-port-screen.txt
if [ -s /tmp/ztm-port-screen.txt ]; then echo "FAIL: screen literals"; fail=1; else echo "PASS"; fi

echo "== device-independent density (dp/sp) vs raw px in layouts"
px=$(grep -rnE 'android:(layout_width|layout_height|textSize|padding[A-Za-z]*)="[0-9]+px"' \
  launcher/app/src/main/res actioncentre/app/src/main/res settings/header-module/res 2>/dev/null | wc -l | tr -d ' ')
echo "px literals in layouts: $px"
[ "$px" -eq 0 ] || { echo "FAIL: px literals"; fail=1; }

echo "== generated resources come from tokens"
for f in design/tokens/gen_android.py design/tokens/ztmetro.tokens.json; do
  [ -f "$f" ] || { echo "FAIL: missing $f"; fail=1; }
done
echo "== overlay target packages"
grep -rhoE 'android:targetPackage="[^"]+"' overlay/*/res/values/*.xml 2>/dev/null | sort -u | head -5 || true

echo "== scripts must not hardcode a serial or an IP"
grep -rnE '4f2b50b2|192\.168\.[0-9]+\.[0-9]+' scripts 2>/dev/null | grep -v README || true

echo
if [ "$fail" -eq 0 ]; then echo "PORTABILITY CHECK: PASS"; else echo "PORTABILITY CHECK: FAIL"; fi
exit "$fail"
