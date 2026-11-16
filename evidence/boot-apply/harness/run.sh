#!/bin/bash
# Host-only simulation of the on-device applier (no adb, no phone).
#
# It fakes `pm`, `settings`, `cmd`, `ime`, `appops`, `id`, `pidof` and `killall`
# with state files, then runs repo/scripts/ztmetro-apply-settings.sh through:
#
#   run1  a freshly reinstalled phone: no grants, all zt overlays disabled
#   wipe  the reset the owner saw (a11y/listeners/home/IME/blacklist wiped,
#         overlay flags reset)
#   run2  the boot that must heal it
#   run3  an extra run that must change nothing (idempotence)
#   run4  a newer Action Centre that declares a third accessibility service
#
# Usage: bash evidence/boot-apply/harness/run.sh
set -u
HERE=$(cd -- "$(dirname -- "$0")" && pwd)
EVID=$(cd -- "$HERE/.." && pwd)
REPO=$(cd -- "$HERE/../../.." && pwd)
APPLY=$REPO/scripts/ztmetro-apply-settings.sh
STATE=$EVID/state
export PATH=$HERE/bin:/usr/bin:/bin:/usr/sbin:/sbin
export FAKE_STATE=$STATE

seed() {
  rm -rf "$STATE"
  mkdir -p "$STATE/secure"
  printf '%s\n' \
    org.ztmetro.actioncentre org.ztmetro.start org.ztmetro.ime \
    com.android.systemui com.android.settings com.android.launcher3 \
    > "$STATE/packages"
  printf '%s\n' \
    org.ztmetro.actioncentre/.ShadeBridgeService \
    org.ztmetro.actioncentre/.StatusStripService \
    com.android.talkback/.TalkBackService \
    > "$STATE/a11y_services"
  cat > "$STATE/overlays" <<'P'
com.android.systemui
[x] com.android.systemui:accent
[x] com.android.systemui:dynamic
[ ] com.android.internal.systemui.navbar.gestural
[ ] org.ztmetro.overlay.framework
[ ] org.ztos.overlay.framework.extra
[ ] org.ztos.overlay.systemui
[ ] org.ztos.overlay.settings
[ ] org.ztos.overlay.status.framework
[ ] org.ztos.overlay.status.systemui
[ ] org.ztos.overlay.statusbaricons
[ ] org.ztos.overlay.systemui.extra
[ ] org.ztos.overlay.factory.org_lineageos_etar
[ ] org.ztos.overlay.factory.ws_xsoh_etar
P
  : > "$STATE/listeners"
  : > "$STATE/appops"
  : > "$STATE/pm-grant"
  : > "$STATE/priority"
  : > "$STATE/killed"
  : > "$STATE/ime_enabled"
  echo "com.android.inputmethod.latin/.LatinIME" > "$STATE/secure/default_input_method"
}

wipe() {
  rm -f "$STATE/secure/"*
  : > "$STATE/listeners"
  : > "$STATE/appops"
  : > "$STATE/ime_enabled"
  sed -e 's/^\[x\] \(org\.ztmetro\.\|org\.ztos\.\)/[ ] \1/' -i '' "$STATE/overlays"
  rm -f "$STATE/home"
  echo "com.android.inputmethod.latin/.LatinIME" > "$STATE/secure/default_input_method"
}

snap() {
  {
    echo "== $1"
    echo "-- secure settings:"
    for k in enabled_accessibility_services accessibility_enabled \
             enabled_notification_listeners icon_blacklist default_input_method; do
      printf '   %s=%s\n' "$k" "$(cat "$STATE/secure/$k" 2>/dev/null || echo '<unset>')"
    done
    echo "-- home: $(cat "$STATE/home" 2>/dev/null || echo '<unset>')"
    echo "-- enabled zt overlays ($(grep -c '^\[x\]' "$STATE/overlays" 2>/dev/null) total):"
    grep '^\[x\]' "$STATE/overlays" | grep -E 'org\.(ztmetro|ztos)\.' | sed 's/^/   /'
    echo "-- notification listeners:"
    sed 's/^/   /' "$STATE/listeners" 2>/dev/null
    echo "-- appops:"
    sed 's/^/   /' "$STATE/appops" 2>/dev/null
    echo "-- systemui kills: $(grep -c . "$STATE/killed" 2>/dev/null || echo 0)"
  } > "$EVID/$1.txt"
}

seed
snap state-before
sh "$APPLY" > "$EVID/run1-post-reinstall.log" 2>&1; echo "run1 rc=$?"
snap state-after-run1 | tail -n 3
wipe
snap state-after-wipe
sh "$APPLY" > "$EVID/run2-after-wipe.log" 2>&1; echo "run2 rc=$?"
snap state-after-run2 | tail -n 3
sh "$APPLY" > "$EVID/run3-noop.log" 2>&1; echo "run3 rc=$?"
snap state-after-run3 | tail -n 3
# run4: a newer Action Centre with an extra accessibility service must be picked up
printf '%s\n' org.ztmetro.actioncentre/.ThirdService >> "$STATE/a11y_services"
sh "$APPLY" > "$EVID/run4-new-service.log" 2>&1; echo "run4 rc=$?"
snap state-after-run4 | tail -n 3

# ---- the boot hook itself: service.sh must return at once, wait, then apply ----
seed
wipe
echo 0 > "$STATE/boot_completed"
rm -f "$EVID/service-sh.log"
MODDIR=$EVID/moddir
rm -rf "$MODDIR"
mkdir -p "$MODDIR"
ln -sf "$REPO/scripts/ztmetro-apply-settings.sh" "$MODDIR/ztmetro-apply-settings.sh"
ln -sf "$REPO/modules/ztmetro-overlays/service.sh" "$MODDIR/service.sh"
t0=$(date +%s)
ZTMETRO_APPLY_LOG=$EVID/service-sh.log sh "$MODDIR/service.sh"
echo "service.sh returned rc=$? after $(( $(date +%s) - t0 ))s (must be 0s: it backgrounds the wait)"
sleep 3
echo 1 > "$STATE/boot_completed"
for i in $(seq 1 30); do
  grep -q 'apply finished' "$EVID/service-sh.log" 2>/dev/null && break
  sleep 1
done
snap state-after-boot-hook
