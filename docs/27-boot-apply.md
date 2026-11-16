# 27 — Boot self-heal of the ztMetro shell state

**Status (2026-11-16):** implemented and host-simulated (23/23 assertions in
[`evidence/boot-apply/`](../evidence/boot-apply/)). Not device-accepted: no adb was
available to this track, the lead has to boot the phone with the rebuilt module and
read the log lines in §5.

---

## 1. The bug

Every piece of ztMetro that makes the shell look like ztMetro is *per-install* or
*per-user* state, not module content:

| state | lives in | dropped by |
|---|---|---|
| `enabled_accessibility_services` (shade + status strip) | `/data/system/users/0/settings_secure.xml` | APK uninstall/reinstall (new uid), ROM reset, "turn off after crash" dialogs |
| `enabled_notification_listeners` | same | same |
| `icon_blacklist` (hides the stock status icons) | same | same |
| `default_input_method` | same | IME reinstall / ROM reset |
| HOME activity | `/data/system/packages.xml` | launcher reinstall / ROM reset |
| appops (`WRITE_SETTINGS`, `READ_PHONE_STATE`, …) | `/data/system/appops.xml` | same |
| overlay enable flags | `/data/system/overlays.xml` | ROM reset (survives a reboot) |

The Magisk module only mounted ten RRO APKs and enabled the overlays at boot.
Nothing re-applied the accessibility grant, so after a reinstall or a ROM reset the
phone booted with the stock lock screen, stock notification shade and no status
strip — the exact symptom the owner saw. Reboots were not the cause; a reboot was
the only thing that *looked* like the cause, because the module was the only boot
actor and it only handled overlays.

## 2. What runs, and when

```
Magisk late_start
  └─ /data/adb/modules/ztmetro-overlays/service.sh          (returns in ~0 s)
       └─ (background subshell, all output appended to the log)
            "service: hook fired, waiting for sys.boot_completed"
            poll getprop sys.boot_completed      up to 60 s, 1 s steps
            poll cmd overlay list               up to 60 s, 1 s steps
            sh ztmetro-apply-settings.sh        exactly once
            "service: apply finished rc=..."
```

`service.sh` deliberately **does not** block: the wait and the apply run in a
background subshell and the hook `exit 0`s immediately. A 60-second wait, a slow
`pm`, or a failing step can therefore never delay boot.

It is **not** called from `post-fs-data`. At that stage `/data` is mounted but
zygote and `system_server` are not: `settings`, `cmd overlay`, `cmd notification`,
`ime`, `appops` and `pm` all talk to system services that do not exist yet, so a
`post-fs-data` run would either fail every step or corrupt the log with false
warnings. `service.sh` runs after `sys.boot_completed`, which is where those
services are guaranteed. (Consequence: a reinstall *without* a reboot is not healed
until the next boot; use the manual command in §6.)

`skip_auto_enable` next to `service.sh` keeps its old meaning: the boot hook does
nothing at all when that file exists. The manual command still works.

## 3. What it re-applies

`repo/scripts/ztmetro-apply-settings.sh` is the canonical source; the module build
copies it byte-identically into the module root and the zip ships it `0755`.
Every step is compare-then-set, every package is checked against `pm list
packages`, and a failing step is a `WARN` line, never a failed boot.

| # | step | value written | skipped when |
|---|---|---|---|
| 1 | overlay enable | every `org.ztmetro.*` / `org.ztos.*` RRO the package manager knows about, plus the nine expected ones | package not registered (logged in `overlays not registered:`) |
| 2 | framework priority | `cmd overlay set-priority org.ztmetro.overlay.framework highest` | not settable |
| 3 | Monet off | `cmd overlay disable com.android.systemui:accent` / `:dynamic` | already off |
| 4 | accessibility services | union of `org.ztmetro.actioncentre/.ShadeBridgeService`, `.StatusStripService` **and every other zt accessibility service `cmd package query-services` reports** | no zt Action Centre installed |
| 5 | `accessibility_enabled` | `1` | already `1` |
| 6 | notification listeners | both, via `cmd notification allow_listener`, then a direct `settings put` union if the ROM ignored it | package not installed |
| 7 | `icon_blacklist` | `clock,battery,wifi,mobile,stacked_mobile,rotate,headset` | already equal |
| 8 | HOME | `cmd package set-home-activity org.ztmetro.start/.StartActivity`, then verified with `resolve-activity` | Start not installed |
| 9 | IME | `ime enable` + `ime set org.ztmetro.ime/.ZtMetroIme` | IME not installed |
| 10 | appops | `WRITE_SETTINGS`, `READ_PHONE_STATE`, `ACCESS_RESTRICTED_SETTINGS` = `allow` + `pm grant READ_PHONE_STATE` | Action Centre not installed |
| 11 | SystemUI | restarted **only if step 1/3 changed an overlay**, so the first boot after a reset shows the new overlays without a second reboot | no overlay change (the normal case) |

Service lists are **unioned, never replaced**: a foreign accessibility service or
notification listener (TalkBack, a password manager) survives the run. The applier
writes user 0 only.

## 4. Files

| file | role |
|---|---|
| [`scripts/ztmetro-apply-settings.sh`](../scripts/ztmetro-apply-settings.sh) | canonical applier (POSIX `sh`, no bash arrays, runs as root) |
| [`modules/ztmetro-overlays/service.sh`](../modules/ztmetro-overlays/service.sh) | boot hook: background wait + one apply run, timestamped log, 256 KB rotation |
| [`modules/ztmetro-overlays/ztmetro-apply-settings.sh`](../modules/ztmetro-overlays/ztmetro-apply-settings.sh) | build-time copy shipped in the zip (byte-identical, written by the build) |
| [`scripts/build-overlay-module.sh`](../scripts/build-overlay-module.sh) | copies the applier in, `chmod 0755`, **fails the build** if `service.sh` or the applier is missing from the zip, prints sha256 + the two entries with their permissions |
| log | `/data/local/tmp/ztmetro-apply.log` (appended across boots; `ZTMETRO_APPLY_LOG` overrides it for the host harness only) |

`magisk --install-module` normalises module files to `0644`, so `service.sh` runs
the applier as `sh "$MODDIR/ztmetro-apply-settings.sh"` — never by exec bit. The
zip entry is still `0755` (§5 of the evidence) and the build checks it.

## 5. What the lead should see after a reboot

```sh
su -c 'tail -n 40 /data/local/tmp/ztmetro-apply.log'
```

First boot on the rebuilt module, after a reinstall/reset — the interesting lines:

```
service: hook fired, waiting for sys.boot_completed
service: boot_completed=1 after <n>s wait
service: overlay-service-ready after <n>s
apply: start uid=0 pid=<pid> boot_completed=1
apply: overlays=10 enabled (10/10 handled)
apply: accessibility=2/2 enabled (was 0/2) services=org.ztmetro.actioncentre/.ShadeBridgeService:org.ztmetro.actioncentre/.StatusStripService
apply: a11y_enabled=1 (was 'unset')
apply: listeners=2/2 enabled (changed)
apply: icon_blacklist=clock,battery,wifi,mobile,stacked_mobile,rotate,headset (was '')
apply: home=ok (org.ztmetro.start/.StartActivity, was 'No activity found')
apply: ime=org.ztmetro.ime/.ZtMetroIme
apply: appops=WRITE_SETTINGS,READ_PHONE_STATE,ACCESS_RESTRICTED_SETTINGS allow
apply: systemui=restarted (overlay changed)      # only when an RRO was just enabled
apply: done changes=<n> warnings=0
service: apply finished rc=0
```

Second and later boots on healthy state must say `(unchanged)` for every setting
and `apply: done changes=0 warnings=0`, `apply: systemui=left running (no overlay
change)` — that is the idempotence signature. `warnings=` above zero means one
guarded step failed; the `WARN` line above it names the step.

The only line allowed to be absent on a normal boot is
`systemui=restarted (overlay changed)`.

## 6. Manual run, any time

On the device (root terminal or `su`):

```sh
su -c 'sh /data/adb/modules/ztmetro-overlays/ztmetro-apply-settings.sh'
```

Same, and append to the boot log so the lead reads one file:

```sh
su -c 'sh /data/adb/modules/ztmetro-overlays/ztmetro-apply-settings.sh >> /data/local/tmp/ztmetro-apply.log 2>&1'
su -c 'grep -E "apply: (accessibility|home|overlays|listeners|ime)|done" /data/local/tmp/ztmetro-apply.log | tail -n 8'
```

Reinstall the rebuilt module (`adb push … && su -c 'magisk --install-module …'`,
or Magisk app → Modules → Install from storage) and reboot once so `service.sh` is
the one that does it.

## 7. Failure modes

1. **Magisk disabled, module removed, or the phone booted in safe mode.** No
   `service.sh` runs, the RROs are not mounted and no shell state is restored. Only
   a re-enabled module + reboot fixes it; see §8.
2. **Exec bit / wrong interpreter.** `magisk --install-module` sets module files to
   `0644`. The hook calls the applier with `sh`, so this is safe as shipped; if
   `service.sh` is edited to run `"$APPLY"` directly it fails with
   `permission denied` and the log stops after `hook fired`.
3. **SELinux.** `/data/adb/modules` is `u:object_r:magisk_file:s0`; executing a
   *copy* of the applier from `/sdcard` or `/data/local/tmp` gives
   `u:r:shell:s0` denials. Run the installed path only. If the log shows no `apply:`
   lines, check `dmesg | grep avc` for `magisk_file`/`shell` denials.
4. **ROM resets settings again after the boot hook.** LineageOS disables an
   accessibility service after its app crashes or is force-stopped with the
   "turn off" dialog, and Settings → Accessibility can be toggled by the user. The
   next boot (or the manual command) re-applies it; the applier cannot prevent the
   ROM from doing it.
5. **`system_server` / PackageManager not answering yet.** The hook waits up to
   60 s for `sys.boot_completed` and up to 60 s for `cmd overlay`; the applier
   itself retries `pm` for 30 s. If it still fails the log shows
   `packages=0`/`skipped` and warnings instead of a failed boot — re-run manually.
6. **Reinstall during a session, no reboot.** State is broken until the next boot or
   a manual run (§6). Nothing in userspace gets a callback for "a11y grant wiped by
   package update" that is earlier than the next boot.
7. **Multi-user.** Only user 0 is written. A secondary user keeps stock settings.
8. **`skip_auto_enable`** present next to `service.sh` → the boot hook does nothing
   by design (one log line). Remove it for the automatic path.

## 8. The one case it cannot fix

**Magisk is not running the hook at all** — module disabled/removed, phone in safe
mode, or Magisk not installed. Then `service.sh` never executes and the overlays are
not even mounted, so there is no userspace self-heal: the phone stays on the stock
shell until the module is reinstalled and the phone is rebooted. Everything else in
this document assumes a boot where `service.sh` ran; the log's first line
(`service: hook fired`) is the proof that it did.

## 9. Verification

Host-only simulation, no adb: fake `pm`/`settings`/`cmd`/`ime`/`appops`/`id`/
`pidof`/`killall`/`getprop` backed by state files, then the real applier and the
real `service.sh` run against them.

```sh
bash evidence/boot-apply/harness/run.sh      # 4 apply runs + the boot hook
bash evidence/boot-apply/harness/verify.sh   # 23 PASS, exit 0
```

Covered: a reinstalled phone with no grants (`was 0/2`), the exact wipe the owner
saw (all settings cleared, overlay flags reset → everything comes back), a third run
that changes nothing (`changes=0`), a newer Action Centre exposing a third
accessibility service (`3/3`), the hook returning in 0 s and applying after the
boot wait, `overlays=10 enabled`, every line timestamped.

Not covered, and left for the lead's device boot: real `settings`/`cmd` permission
behaviour under `u:0`, the ROM's own accessibility watchdog, SELinux labels, and
whether SystemUI picks the overlays up without the extra restart.
