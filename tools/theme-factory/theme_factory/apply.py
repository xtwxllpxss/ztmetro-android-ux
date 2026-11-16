"""`apply`: print the exact device commands (no adb is run from here).

The lead owns the phone, so the factory never touches it.  What it does own is
the sequence: capture evidence *before* enabling anything, enable, restart the
app so it re-reads its resources, capture after, and have a watchdog that
disables the overlay and saves the crash log if the app dies.
"""

from __future__ import annotations

import os

DEFAULT_MODULE = "/data/adb/modules/ztmetro-overlays/system/product/overlay"


def _sh(cmd: str) -> str:
    return "  " + cmd


def commands(package: str, overlay_package: str, apk_name: str,
             activity: str | None = None, module_path: str = DEFAULT_MODULE,
             serial: str | None = None, settle_seconds: int = 20,
             out_dir: str = ".") -> list:
    adj = ("-s %s " % serial) if serial else ""
    adb = "adb %s" % adj
    start = ""
    if activity:
        start = ("%s shell am start -n %s" % (adb, activity))
    else:
        start = ("%s shell monkey -p %s -c android.intent.category.LAUNCHER 1" % (adb, package))
    lines = []
    add = lines.append

    add("# ---- 0. preconditions -------------------------------------------------")
    add(_sh("%s devices        # exactly one authorised device" % adb))
    add(_sh("%s shell su 0 cmd overlay list | grep -i ztmetro   # module already mounted?" % adb))
    add(_sh("# the overlay must be preinstalled on /product (read-only): an RRO that is"))
    add(_sh("# merely installed via `adb install` cannot map a target without <overlayable>"))
    add("")
    add("# ---- 1. BEFORE: prove what the app shows today --------------------------")
    add(_sh("%s exec-out screencap -p > %s/before.png" % (adb, out_dir)))
    add(_sh("%s shell dumpsys package %s | grep -E 'versionName|codePath' | tee %s/pkg-before.txt"
            % (adb, package, out_dir)))
    add(_sh("aapt2 dump resources %s > %s/resources-before.txt" % (apk_name, out_dir)))
    add("")
    add("# ---- 2. push into the Magisk module's /product/overlay ------------------")
    add(_sh("%s push %s /data/local/tmp/%s" % (adb, apk_name, apk_name)))
    add(_sh("%s shell su 0 cp /data/local/tmp/%s %s/%s" % (adb, apk_name, module_path, apk_name)))
    add(_sh("%s shell su 0 chmod 644 %s/%s && su 0 chown root:root %s/%s"
            % (adb, module_path, apk_name, module_path, apk_name)))
    add(_sh("%s shell su 0 ls -l %s/%s" % (adb, module_path, apk_name)))
    add(_sh("# visible without a reboot: the Magisk module binds over /product, so a"))
    add(_sh("# new file needs only an idmap cache drop (0 = no overlay yet) and pm refresh"))
    add(_sh("%s shell su 0 cmd overlay list | grep %s   # overlay must now be listed" % (adb, overlay_package)))
    add("")
    add("# ---- 3. enable the overlay for user 0 ----------------------------------")
    add(_sh("%s shell su 0 cmd overlay enable --user 0 %s" % (adb, overlay_package)))
    add(_sh("%s shell su 0 cmd overlay list | grep -A1 %s   # expect [x] %s"
            % (adb, overlay_package, overlay_package)))
    add("")
    add("# ---- 4. restart the app: resources are read at process start ------------")
    add(_sh("%s shell am force-stop %s" % (adb, package)))
    add(_sh(start))
    add(_sh("sleep %d" % settle_seconds))
    add("")
    add("# ---- 5. AFTER: same capture as step 1 ----------------------------------")
    add(_sh("%s exec-out screencap -p > %s/after.png" % (adb, out_dir)))
    add(_sh("python3 %s/verify.sh --screenshots %s/before.png %s/after.png"
            % (out_dir, out_dir, out_dir)))
    add(_sh("%s shell dumpsys activity top | head -20   # the activity must be the same one" % adb))
    add("")
    add("# ---- 6. crash watchdog + automatic rollback ----------------------------")
    add(_sh("bash %s/rollback-watchdog.sh --package %s --overlay %s --activity '%s' --seconds 30"
            % (out_dir, package, overlay_package, activity or "")))
    add(_sh("# the watchdog: clear logcat, re-enable, restart, wait, then"))
    add(_sh("#   adb logcat -b crash -d | grep -q %s  ->  disable overlay, force-stop," % package))
    add(_sh("#   save /tmp/%s-crash.log and re-launch the app unthemed" % package))
    add("")
    add("# ---- 7. manual rollback (always safe) ----------------------------------")
    add(_sh("%s shell su 0 cmd overlay disable --user 0 %s" % (adb, overlay_package)))
    add(_sh("%s shell am force-stop %s" % (adb, package)))
    add(_sh("%s shell su 0 rm -f %s/%s   # only if the APK itself is wrong" % (adb, module_path, apk_name)))
    add(_sh("%s shell su 0 cmd overlay list | grep %s   # must be gone or disabled" % (adb, overlay_package)))
    return lines


def watchdog_script(package: str, overlay_package: str, activity: str | None,
                    seconds: int = 30, serial: str | None = None,
                    log_dir: str = "/data/local/tmp") -> str:
    adj = ("-s %s " % serial) if serial else ""
    start = ("am start -n %s" % activity) if activity else \
        ("monkey -p %s -c android.intent.category.LAUNCHER 1" % package)
    return """#!/usr/bin/env bash
# GENERATED by repo/tools/theme-factory.
# Enable the overlay, restart the app, then disable the overlay automatically if
# the app crashed: the phone keeps working and the crash log is preserved.
set -uo pipefail
PKG="%(pkg)s"
OVL="%(ovl)s"
SECS="%(secs)s"
ADB="adb %(adj)s"
LOG="%(log)s/%(pkg)s-crash.log"

$ADB logcat -c || exit 2
$ADB shell su 0 cmd overlay enable --user 0 "$OVL" || exit 2
$ADB shell am force-stop "$PKG"
$ADB shell %(start)s
sleep "$SECS"

if $ADB logcat -b crash -d | grep -qF "$PKG"; then
  echo "CRASH detected for $PKG -> rolling back"
  $ADB shell su 0 cmd overlay disable --user 0 "$OVL"
  $ADB shell am force-stop "$PKG"
  $ADB logcat -b crash -d > "$LOG"
  echo "crash log: $LOG"
  $ADB shell su 0 cmd overlay list | grep -F "$OVL"
  $ADB shell %(start)s
  exit 1
fi

if ! $ADB shell pidof "$PKG" >/dev/null; then
  echo "process gone (no crash buffer entry) -> rolling back"
  $ADB shell su 0 cmd overlay disable --user 0 "$OVL"
  $ADB logcat -d | grep -F "$PKG" | tail -200 > "$LOG"
  exit 1
fi

echo "OK: $PKG alive after ${SECS}s with $OVL enabled"
""" % {"pkg": package, "ovl": overlay_package, "secs": seconds, "adj": adj,
       "log": log_dir, "start": start}
