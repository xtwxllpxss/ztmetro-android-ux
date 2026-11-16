# ztMetro conductor run 20261116-154019

- **command:** `./scripts/ztmetro-conductor.sh --with-factory --skip-build --allow-deploy`
- **snapshot:** `bb05aac17aec12e141a61308199aeca53c812ed1` (git archive; 5 uncommitted path(s) in the working tree excluded)
- **root:** `adb root` (adbd uid=0, SELinux `u:r:su:s0`); Magisk `su` for adb shell is not used
- **started:** 2026-11-16T15:40:19+03:00
- **finished:** 2026-11-16T15:45:05+03:00
- **duration:** 286 s
- **build host:** `hermes-ubuntu` (`~/ztux/conductor/snapshots/bb05aac17aec`, `~/android-root/conductor`)
- **logs:** `evidence/conductor/20261116-154019/conductor.log`, per-stage `build/*.log`

## Stages

| Stage | Status | Duration | Notes |
|---|---|---:|---|
| build | skipped | 0 s | --skip-build |
| publish | ok | 21 s |  |
| apply | ok | 36 s |  |
| verify | ok | 58 s | PASS/FAIL summary in verify.log (FAILs present) |
| factory | ok | 170 s |  |

## Artifacts

| Artifact | Size | SHA-256 |
|---|---:|---|
| overlays | 78142 B (76.3 KiB) | `ba32c4a60b9aa20dd2219c93afd3dc5238980708d96c85f56268880148dbb87a` |
| launcher | 74155 B (72.4 KiB) | `40242fdc075fdacfaeda073b973d2ad43f75407f219a4b6eb393c01e3c1e439d` |
| actioncentre | 345804 B (337.7 KiB) | `93ceba73a179a95615ece43435b5a2cd21cdf9c7c00e31aefa7b9fce1ec6f6b8` |
| ime | 20817 B (20.3 KiB) | `f22250442df52fd14a857a80dce88a513bec9486cab056150e08c4463ff58df4` |
| header | 37872 B (37.0 KiB) | `c67086288b2575a2be5665bc1af8fcfddb9700a129b2676229e60cdb6e89178c` |

## Verify

```
TOTAL  PASS 13  FAIL 4  SKIP 0  / 17 checks
```

Detailed report: `evidence/conductor/20261116-154019/verify/run/report.md`

## Theme factory

| Package | Overlay | Coverage | Status |
|---|---|---|---|
| com.android.deskclock | `org.ztos.overlay.factory.com_android_deskclock` | `{"colour_resources_mapped": 174, "colour_resources_total": 492, "colour_resources_used": 232, "layouts_scanned": 110, "mapped_ratio_of_used": 0.75}` | ok |
| com.android.contacts | `org.ztos.overlay.factory.com_android_contacts` | `{"colour_resources_mapped": 239, "colour_resources_total": 1035, "colour_resources_used": 310, "layouts_scanned": 219, "mapped_ratio_of_used": 0.771}` | ok |
| com.android.dialer | `org.ztos.overlay.factory.com_android_dialer` | `{"colour_resources_mapped": 222, "colour_resources_total": 1061, "colour_resources_used": 301, "layouts_scanned": 258, "mapped_ratio_of_used": 0.738}` | ok |
| org.lineageos.aperture | `org.ztos.overlay.factory.org_lineageos_aperture` | `{"colour_resources_mapped": 173, "colour_resources_total": 493, "colour_resources_used": 231, "layouts_scanned": 80, "mapped_ratio_of_used": 0.749}` | ok |

## Reproduce from a clean LineageOS install

```sh
# 0. one-time platform (manual, GUI steps are unavoidable):
#    flash LineageOS-for-microG 23.2 for Mi8917 (recovery -> format data -> adb -d sideload),
#    install Magisk (Zygisk on) and LSPosed/Vector, authorise adb, keep the phone unlocked.
# 1. on the Mac, from the repo root:
./scripts/ztmetro-conductor.sh --with-factory --skip-build --allow-deploy
# 2. stage-only re-runs (all idempotent):
./repo/scripts/ztmetro-conductor.sh --skip-build --skip-publish --skip-apply   # verify+report only
./repo/scripts/ztmetro-conductor.sh --skip-build --skip-publish --allow-deploy   # apply+verify (may reboot)
```

The conductor is the same state machine for every run: a changed module is
reinstalled and followed by a normal Android reboot plus a `sys.boot_completed`
wait; unchanged artifacts are detected by sha256 (module marker) and by comparing
the installed APK sha256 + signing certificate, so a second run is a no-op.

