# Boot self-heal — evidence (2026-11-16)

Subject: [`scripts/ztmetro-apply-settings.sh`](../../scripts/ztmetro-apply-settings.sh),
[`modules/ztmetro-overlays/service.sh`](../../modules/ztmetro-overlays/service.sh),
[`scripts/build-overlay-module.sh`](../../scripts/build-overlay-module.sh) and the
rationale in [`docs/27-boot-apply.md`](../../docs/27-boot-apply.md).

No adb was available to this track: nothing below is a device measurement. It is a
host simulation of the device commands the two scripts issue, plus the module zip
inspection. The lead's boot is the acceptance step.

## What was proven here

1. **Wiped state comes back.** `run1-post-reinstall.log` starts from an empty secure
   settings store, all zt overlays disabled, no HOME, stock IME; the applier writes
   the accessibility union, `accessibility_enabled`, both listeners, the icon
   blacklist, HOME, the IME, the three appops and enables all ten zt overlays.
2. **The exact owner-visible reset is healed.** `wipe` clears a11y/listeners/HOME/
   IME/blacklist and resets the overlay flags; `run2-after-wipe.log` restores them
   (`accessibility=2/2 enabled (was 0/2)`, `overlays=10 enabled`).
3. **Idempotence.** `run3-noop.log` is `done changes=0 warnings=0`; every setting
   says `(unchanged)`, SystemUI is left running. Re-running the applier every boot
   cannot flap the shell.
4. **Discovery, not a hardcoded list.** `run4-new-service.log`: a third
   accessibility service declared by Action Centre is picked up
   (`accessibility=3/3 enabled`). Overlay discovery also picks
   `org.ztos.overlay.factory.ws_xsoh_etar`, which is in the module but not in the
   built-in expected list — hence `overlays=10 enabled`, not 9.
5. **The hook never blocks boot.** `run.sh` runs the real `service.sh` with
   `sys.boot_completed=0`, flips it after 3 s and measures the return: 0 s. The
   background subshell then logs `boot_completed=1 after 3s wait`, waits for
   `cmd overlay`, applies once and logs `apply finished rc=0`
   (`service-sh.log`).
6. **Everything is timestamped and guarded.** `verify.sh` fails if any log line
   lacks the timestamp, if run1 emits a `WARN`, or if run3 rewrites a setting.

```
TOTAL PASS=23 FAIL=0     (bash evidence/boot-apply/harness/verify.sh)
```

## Zip

See [`module-zip.txt`](module-zip.txt): `artifacts/ztmetro-overlays-1.0.0.zip`,
sha256 `2728e18d01dd1cd0e7df62e8e93b2674a7fcb5832933c6bf78a1e5f557f28ea5`,
16 entries, `service.sh` and `ztmetro-apply-settings.sh` both `-rwxr-xr-x`, the
shipped applier byte-identical to `scripts/ztmetro-apply-settings.sh`.

## Files

| file | what it is |
|---|---|
| `harness/bin/` | fake `pm`, `settings`, `cmd`, `ime`, `appops`, `id`, `pidof`, `killall`, `getprop` backed by state files |
| `harness/run.sh` | seeds a reinstalled phone, wipes it, runs the applier 4×, then the real `service.sh` hook |
| `harness/verify.sh` | 23 assertions → `PASS`/`FAIL` + `TOTAL` line |
| `run1-post-reinstall.log` … `run4-new-service.log` | applier output per scenario |
| `service-sh.log` | the boot hook: background wait, then one apply run |
| `state-after-*.txt` | resulting fake device state (settings, home, overlays, listeners, appops) |
| `expected-boot-log.txt` | the lines to look for in `/data/local/tmp/ztmetro-apply.log` on the phone |
| `module-zip.txt` | zip sha256, entry list, shipped script permissions |

Reproduce with:

```sh
bash evidence/boot-apply/harness/run.sh
bash evidence/boot-apply/harness/verify.sh
sh scripts/build-overlay-module.sh
```

## Limits

Not proven on hardware: `settings`/`cmd`/`ime`/`appops` behaviour under uid 0 on
LineageOS 23.2 (the harness accepts every call), SELinux labels of
`/data/adb/modules` (a copy run from `/sdcard` would be denied), the ROM's own
accessibility watchdog, and whether the `systemui=restarted` branch is needed at
all on a real boot. `docs/27-boot-apply.md` §7 lists the failure modes and the one
case no userspace self-heal can cover (Magisk not running `service.sh`).
