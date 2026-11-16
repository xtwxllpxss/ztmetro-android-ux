#!/bin/sh
# Build the ztMetro overlays Magisk module from the built RRO APKs.
# Usage: scripts/build-overlay-module.sh [--out DIR]
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$ROOT/artifacts
if [ "${1:-}" = "--out" ]; then OUT=${2:-$ROOT/artifacts}; fi
mkdir -p "$OUT"
FW=$ROOT/evidence/framework-rro/ZtMetroFramework.apk
SU=$ROOT/evidence/sysui-rro/ztmetro-systemui-rro.apk
SE=$ROOT/evidence/settings-rro/ztmetro-settings-rro.apk
STF=$ROOT/evidence/status-strip/ztmetro-status-framework-rro.apk
STS=$ROOT/evidence/status-strip/ztmetro-status-systemui-rro.apk
for f in "$FW" "$SU" "$SE" "$STF" "$STS"; do [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }; done
cp "$FW" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ZtMetroFramework.apk"
cp "$SU" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-systemui-rro.apk"
cp "$SE" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-settings-rro.apk"
cp "$STF" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-status-framework-rro.apk"
cp "$STS" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-status-systemui-rro.apk"
ZIP=$OUT/ztmetro-overlays-1.0.0.zip
rm -f "$ZIP"
( cd "$ROOT/modules/ztmetro-overlays" && zip -qr -X "$ZIP" . )
echo "$ZIP"
sha256sum "$ZIP" 2>/dev/null || shasum -a 256 "$ZIP"
