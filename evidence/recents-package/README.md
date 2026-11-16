# ztmetro-recents — system recents package

This is the honest system-side answer to the recents question from
`repo/evidence/transitions-recents/README.md`: an ordinary APK cannot list other
apps' tasks, cannot remove them and cannot replace the recents key/gesture. The
package in this directory is a Magisk module that makes
`org.ztmetro.actioncentre/.RecentsActivity` the platform's recents component,
with a static framework RRO, a priv-app install and the matching allowlist. It is
reversible by removing the module and rebooting.

Built artifacts and their hashes are in `repo/evidence/recents-package/`
(`hashes.txt`, `REPORT.md`, `verify.txt`). Build them with
`repo/scripts/build-recents-package.sh` (automatically staged to `hermes-ubuntu`
when the host has no Android SDK).

## What the module installs

| module path | device path | purpose |
|---|---|---|
| `system_ext/priv-app/ActionCentre/ztmetro-actioncentre.apk` | `/system_ext/priv-app/ActionCentre/` | factory/priv-app copy of the Action Centre, `versionCode 2`, so PMS scans it as a system package |
| `system_ext/etc/permissions/privapp-permissions-ztmetro-recents.xml` | `/system_ext/etc/permissions/` | priv-app allowlist: grants REAL_GET_TASKS and START_TASKS_FROM_RECENTS |
| `system/product/overlay/ztmetro-recents-rro.apk` | `/product/overlay/` | static RRO, package `org.ztmetro.overlay.recents`, target `android`, sets `config_recentsComponentName` |
| `service.sh` | `/data/adb/modules/ztmetro-recents/` | writes a post-boot report to `/data/local/tmp/ztmetro-recents.log` (overlay line, APK path, granted permissions) |
| `uninstall.sh` | `-` | removes the log; the payload disappears with the module |

Nothing else is touched: no settings, appops, accessibility grants, framework
patch, or boot image. The RRO is **static** on purpose: system_server reads
`config_recentsComponentName` once during boot (`RecentTasks.loadRecentsComponent`,
and PMS's `mRecentsPackage`), before any late_start module script could enable a
dynamic overlay. This is the same mechanism the existing `ZtMetroFramework`
static overlay uses in `/product/overlay`.

## The permission split (why the XML alone is not enough)

`PermissionManagerServiceImpl` in AOSP 16 grants a permission from the priv-app
allowlist only when the permission's protection includes `privileged`:

* `REAL_GET_TASKS` (`signature|privileged`) and `START_TASKS_FROM_RECENTS`
  (`signature|privileged|recents`) → granted by the allowlist XML above.
* `REMOVE_TASKS` (`signature|recents|role`) and `MANAGE_ACTIVITY_TASKS`
  (`signature|recents`) are **not** privileged and cannot be granted by the XML.
  They are granted by the separate `bp.isRecents()` branch to known package
  `PACKAGE_RECENTS` — the package PMS resolved from the same
  `config_recentsComponentName` string. That is why the RRO and the factory
  priv-app must ship together: the RRO alone gives read access but not dismissal.

`ActivityManager.getRunningTasks()` needs no permission once this UID is the
recents component (`ActivityTaskManagerService.isGetTasksAllowed` returns true
for `isCallerRecents` before checking REAL_GET_TASKS).

Removal does not depend on a hidden Java accessor: on this ROM's `framework.jar`,
`ActivityTaskManager.getInstance()` and `getService()` are hidden API **BLOCKED**
while `removeTask` is `BLOCKED, TEST-API` and `ServiceManager.getService` is
**UNSUPPORTED** (accessible). The activity therefore tries the clean
`ActivityTaskManager.removeTask` reflection first, then falls back to the exact
AIDL transaction behind it (`activity_task` binder, interface token
`android.app.IActivityTaskManager`, transaction 23, read from this ROM's
`IActivityTaskManager$Stub$Proxy.removeTask` bytecode, gated on SDK 36). The
server still enforces REMOVE_TASKS in both cases. Rebuild the package if the ROM
changes Android version: the transaction code is AIDL-order-specific.

## Install

Prerequisite: Magisk on the phone (the module is a normal Magisk zip).

1. If a user-installed `org.ztmetro.actioncentre` exists with a **different
   signature** than the module APK (signer SHA-256 is in `REPORT.md`; the
   transitions prototype `812fcae3…` uses a different key), uninstall it first:
   Settings → Apps → ztMetro Action Centre → Uninstall, or
   `adb uninstall org.ztmetro.actioncentre`. The system copy (v2) supersedes an
   older same-signature copy automatically. Uninstalling drops the
   accessibility/notification grants; re-enable them after the reboot.
2. Install the zip: Magisk app → Modules → Install from storage →
   `ztmetro-recents.zip`. Headless alternative:
   `adb push ztmetro-recents.zip /data/local/tmp/ && adb shell su -c 'magisk --install-module /data/local/tmp/ztmetro-recents.zip'`.
3. Reboot. The static overlay and the priv-app are only read at boot.
   (`magisk --install-module` does not copy an already-mounted system_ext into a
   running system_server; the reboot is the activation.)

## Verify (no silent assumptions)

```sh
# 1. the RRO is registered as an immutable static overlay
adb shell cmd overlay list | grep ztmetro.overlay.recents
#    expected: [x] org.ztmetro.overlay.recents (static overlays show enabled)
adb shell cmd overlay dump org.ztmetro.overlay.recents | head
#    expected: target package "android", enabled/immutable state

# 2. the APK really is the system copy and the grant landed
adb shell pm path org.ztmetro.actioncentre
#    expected: package:/system_ext/priv-app/ActionCentre/ztmetro-actioncentre.apk
adb shell dumpsys package org.ztmetro.actioncentre | grep -E 'versionCode|REMOVE_TASKS'
#    expected: versionCode=2 and REMOVE_TASKS: granted=true
adb shell dumpsys activity recents | head -3
#    expected: mRecentsComponent=ComponentInfo{org.ztmetro.actioncentre/org.ztmetro.actioncentre.RecentsActivity}

# 3. the activity lists real tasks and can remove them
adb shell am start -n org.ztmetro.actioncentre/.RecentsActivity
#    cards show icon + app label + task id; status line says
#    "N tasks · REMOVE_TASKS granted · system package"
#    close (×) or swipe up removes the card; a toast means the server refused
adb shell am start -n org.ztmetro.actioncentre/.RecentsActivity
#    the removed app is gone from the strip

# 4. boot report written by the module (no adb needed on the phone)
adb shell cat /data/local/tmp/ztmetro-recents.log
```

On the phone without adb: the same facts are on screen. The status line shows
`REMOVE_TASKS granted|denied` and `system package|data package`, and the
no-access frame states exactly which condition is missing. The module's
`service.sh` also records the overlay line, `pm path` and the permission dump in
`/data/local/tmp/ztmetro-recents.log` (readable with a root file manager).

If step 3 shows "no other tasks visible" while apps have been used: the RRO is
not in effect. Check step 1 first, then confirm there is no `/data/app` update
shadowing the system copy (step 2), then reboot once more.

## Rollback

1. Magisk app → Modules → `ztMetro Recents` → Remove (or
   `adb shell su -c 'touch /data/adb/modules/ztmetro-recents/remove'`), then
   reboot. The static overlay disappears with the module; the original
   `com.android.launcher3/com.android.quickstep.RecentsActivity` value comes back.
2. Reinstall the previous data APK of Action Centre if it was uninstalled.
3. `disable` instead of remove also works (Magisk skips the mount next boot),
   but the module must still be removed for the overlay to go away.

There is no live `cmd overlay disable` for a static overlay. Rollback is
"remove module + reboot", which is also why the module cannot half-apply: the
overlay, the APK and the allowlist are mounted together or not at all.

## What works, and what remains

* Works after install + reboot: the platform's recents component is this
  activity, so `am start -n org.ztmetro.actioncentre/.RecentsActivity` lists all
  recent tasks and the close button / swipe-up calls `ActivityTaskManager.removeTask`
  with REMOVE_TASKS granted (system package). The WP frame (black, 58 sp
  `recent`, flat icon+label cards, no screenshots) is unchanged.
* **The recents key and gesture still open Launcher3QuickStep.** WMShell builds
  its overview itself and never reads `config_recentsComponentName`
  (`RecentsAnimation` sets `ACTIVITY_TYPE_RECENTS` only when the client's intent
  component equals the configured component; QuickStep supplies its own
  `RecentsActivity`). Until WMShell is patched or QuickStep is replaced, the
  gesture is not routed here.
* Side effect of redirecting the recents identity: Launcher3QuickStep is no
  longer `PACKAGE_RECENTS`, so it loses the recents-only grants
  (REMOVE_TASKS/MANAGE_ACTIVITY_TASKS) it had through that known package. Its
  overview can therefore throw when it tries to dismiss a task, and its
  transition may be treated as HOME. A WMShell/QuickStep patch is the clean fix;
  this module does not do it and the stock overview is not the target UI.
* Not done here (out of scope, needs system image work): replacing the recents
  animation/gesture, task screenshot thumbnails (would need READ_FRAME_BUFFER),
  and making the overview part of the WP surface.
