#!/system/bin/sh
# late_start service: enable the ztMetro overlays idempotently.
# The overlay APKs live in /product/overlay (preinstalled => may override targets
# without overlayables). Enabling needs CHANGE_OVERLAY_PACKAGES, which only uid 0
# holds here (shell lacks it on a user build). Wait for OverlayManager to exist:
# a late_start service can run before system_server has registered the service.
MODDIR=${0%/*}
# Debug switch: if skip_auto_enable exists, do not touch the overlay state at boot.
[ -f "$MODDIR/skip_auto_enable" ] && exit 0
LOG=/data/local/tmp/ztmetro-overlays.log
{
  echo "--- $(date) enabling ztMetro overlays"
  i=0
  while [ $i -lt 60 ]; do
    if cmd overlay list >/dev/null 2>&1; then break; fi
    i=$((i+1)); sleep 2
  done
  echo "waited ${i}x2s for overlay service"
  for pkg in org.ztmetro.overlay.framework org.ztos.overlay.systemui org.ztos.overlay.settings org.ztos.overlay.status.systemui; do
    cmd overlay enable --user 0 "$pkg" 2>&1 || echo "enable failed: $pkg"
  done
  cmd overlay list 2>/dev/null | grep -E "ztmetro|ztos" || true
} >> "$LOG" 2>&1
