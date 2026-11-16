#!/system/bin/sh
# ztmetro-recents: one-shot post-boot report.
#
# The module itself changes nothing at runtime: the static RRO and the priv-app
# are already active when system_server boots. This service only records the
# three facts the verification plan needs, so `adb` is not required to find out
# whether the module actually took effect:
#
#   /data/local/tmp/ztmetro-recents.log
#     - the overlay registration line ("[x] org.ztmetro.overlay.recents")
#     - which APK path PMS uses for the package (system vs data update)
#     - whether REMOVE_TASKS / REAL_GET_TASKS / START_TASKS_FROM_RECENTS landed
#
# late_start must return immediately, so the wait+report runs in a subshell.

MODDIR=${0%/*}
LOG=/data/local/tmp/ztmetro-recents.log

if [ -f "$LOG" ]; then
  sz=$(wc -c < "$LOG" 2>/dev/null | tr -d ' ')
  [ -n "$sz" ] && [ "$sz" -gt 65536 ] && mv -f "$LOG" "$LOG.1" 2>/dev/null
fi

(
  i=0
  while [ "$i" -lt 60 ]; do
    [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
    i=$((i + 1))
    sleep 1
  done

  {
    echo "==== $(date '+%Y-%m-%d %H:%M:%S') boot_completed after ${i}s ===="
    echo "sdk=$(getprop ro.build.version.sdk 2>/dev/null)"
    echo "-- overlay (static; [x] means applied) --"
    cmd overlay list 2>/dev/null | grep -E 'ztmetro\.overlay\.recents' \
      || echo "MISSING: org.ztmetro.overlay.recents not registered"
    echo "-- apk path --"
    pm path org.ztmetro.actioncentre 2>/dev/null || echo "MISSING package"
    echo "-- package state --"
    dumpsys package org.ztmetro.actioncentre 2>/dev/null \
      | grep -E 'versionCode|versionName|REMOVE_TASKS|REAL_GET_TASKS|START_TASKS_FROM_RECENTS|MANAGE_ACTIVITY_TASKS' \
      | head -n 20
  } >> "$LOG" 2>&1
) &

exit 0
