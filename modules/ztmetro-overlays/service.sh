#!/system/bin/sh
# late_start service: re-apply the whole ztMetro shell state on every boot.
#
# The state this restores (accessibility grant, notification listeners, HOME,
# default IME, appops, overlay enable flags) lives in per-user /data state and is
# wiped by an APK reinstall (new uid) or a ROM reset.  Nothing else re-applies it
# at boot, which is why the phone came back with the stock lock screen, shade and
# no status strip.
#
# This file only starts the work: the boot_completed wait and the actual apply run
# in a background subshell, so a slow or failing step can never block boot.
# The script that does the work is ztmetro-apply-settings.sh next to this file
# (run with `sh`, because `magisk --install-module` normalises module files to
# 0644 and drops the exec bit).
#
# Log: /data/local/tmp/ztmetro-apply.log (appended, timestamped, rotated at 256 KB).
# ZTMETRO_APPLY_LOG overrides the path; the host test harness uses that, on a
# device the default is always right.
# Manual run at any time:
#   su -c 'sh /data/adb/modules/ztmetro-overlays/ztmetro-apply-settings.sh' \
#     >> /data/local/tmp/ztmetro-apply.log 2>&1

MODDIR=${0%/*}
LOG=${ZTMETRO_APPLY_LOG:-/data/local/tmp/ztmetro-apply.log}
APPLY="$MODDIR/ztmetro-apply-settings.sh"

ts() { date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "0000-00-00 00:00:00"; }

# Skip switch kept from the overlay-only service.sh: with skip_auto_enable
# present, the boot hook touches nothing (the manual command still works).
if [ -f "$MODDIR/skip_auto_enable" ]; then
  { echo "$(ts) service: skip_auto_enable present, boot apply skipped"; } >> "$LOG" 2>/dev/null
  exit 0
fi

if [ ! -f "$APPLY" ]; then
  { echo "$(ts) service: ERROR missing $APPLY, nothing applied"; } >> "$LOG" 2>/dev/null
  exit 0
fi

# Trim the log so it cannot grow without bound across reboots.
if [ -f "$LOG" ]; then
  sz=$(wc -c < "$LOG" 2>/dev/null | tr -d ' ')
  if [ -n "$sz" ] && [ "$sz" -gt 262144 ]; then
    mv -f "$LOG" "$LOG.1" 2>/dev/null
  fi
fi

# Background the whole wait+apply: late_start must return immediately.
(
  echo "$(ts) service: hook fired, waiting for sys.boot_completed"
  # 1. wait for Android to finish booting (60 s cap)
  i=0
  while [ "$i" -lt 60 ]; do
    [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
    i=$((i + 1))
    sleep 1
  done
  echo "$(ts) service: boot_completed=$(getprop sys.boot_completed 2>/dev/null) after ${i}s wait"

  # 2. wait for OverlayManager: a late_start service can run before system_server
  #    has registered it, and `cmd overlay` would then fail for every package.
  j=0
  while [ "$j" -lt 60 ]; do
    cmd overlay list >/dev/null 2>&1 && break
    j=$((j + 1))
    sleep 1
  done
  echo "$(ts) service: overlay-service-ready after ${j}s"

  # 3. one idempotent apply run, guarded so a crash still leaves a log line
  sh "$APPLY"
  rc=$?
  echo "$(ts) service: apply finished rc=$rc"
) >> "$LOG" 2>&1 &

exit 0
