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
SBI=$ROOT/evidence/statusbar-icons/ztmetro-statusbar-icons-rro.apk
FEX=$ROOT/evidence/volume-power-toast/ztmetro-framework-extra-rro.apk
SEX=$ROOT/evidence/volume-power-toast/ztmetro-systemui-extra-rro.apk
FAC=$ROOT/evidence/theme-factory/overlay/ztmetro-org_lineageos_etar-rro.apk
APPLY=$ROOT/scripts/ztmetro-apply-settings.sh
for f in "$FW" "$SU" "$SE" "$STF" "$STS" "$SBI" "$FEX" "$SEX" "$FAC" "$APPLY"; do [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }; done
cp "$FW" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ZtMetroFramework.apk"
cp "$SU" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-systemui-rro.apk"
cp "$SE" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-settings-rro.apk"
cp "$STF" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-status-framework-rro.apk"
cp "$STS" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-status-systemui-rro.apk"
cp "$SBI" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-statusbar-icons-rro.apk"
cp "$FEX" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-framework-extra-rro.apk"
cp "$SEX" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-systemui-extra-rro.apk"
cp "$FAC" "$ROOT/modules/ztmetro-overlays/system/product/overlay/ztmetro-org_lineageos_etar-rro.apk"
# Boot self-heal: ship the idempotent re-apply script inside the module and make
# sure it keeps its exec bit in the zip (Magisk's installer normalises module
# files to 0644, so service.sh calls it with `sh`; the bit is still set here so
# the entry is honest and `unzip` on a host restores it).
cp "$APPLY" "$ROOT/modules/ztmetro-overlays/ztmetro-apply-settings.sh"
chmod 0755 "$ROOT/modules/ztmetro-overlays/ztmetro-apply-settings.sh"
chmod 0755 "$ROOT/modules/ztmetro-overlays/service.sh"
ZIP=$OUT/ztmetro-overlays-1.0.0.zip
rm -f "$ZIP"
( cd "$ROOT/modules/ztmetro-overlays" && zip -qr -X "$ZIP" . )
# Fail the build if the module zip cannot self-heal a boot.
unzip -Z1 "$ZIP" | grep -qx 'ztmetro-apply-settings.sh' \
  || { echo "zip is missing ztmetro-apply-settings.sh" >&2; exit 1; }
unzip -Z1 "$ZIP" | grep -qx 'service.sh' \
  || { echo "zip is missing service.sh" >&2; exit 1; }
echo "$ZIP"
sha256sum "$ZIP" 2>/dev/null || shasum -a 256 "$ZIP"
unzip -Z "$ZIP" ztmetro-apply-settings.sh service.sh | sed -n '/^-[r-]/p' || true
