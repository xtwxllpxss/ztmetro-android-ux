# ztMetro conductor run 20261116-153233

- **command:** `./scripts/ztmetro-conductor.sh --with-factory`
- **snapshot:** `bb05aac17aec12e141a61308199aeca53c812ed1` (git archive; 2 uncommitted path(s) in the working tree excluded)
- **started:** 2026-11-16T15:32:33+03:00
- **finished:** 2026-11-16T15:33:14+03:00
- **duration:** 41 s
- **build host:** `hermes-ubuntu` (`~/ztux/conductor/snapshots/bb05aac17aec`, `~/android-root/conductor`)
- **logs:** `evidence/conductor/20261116-153233/conductor.log`, per-stage `build/*.log`

## Stages

| Stage | Status | Duration | Notes |
|---|---|---:|---|
| build | ok | 30 s |  |
| publish | failed | 11 s | see publish logs |

## Artifacts

| Artifact | Size | SHA-256 |
|---|---:|---|
| overlays | 78142 B (76.3 KiB) | `ba32c4a60b9aa20dd2219c93afd3dc5238980708d96c85f56268880148dbb87a` |
| launcher | 74155 B (72.4 KiB) | `40242fdc075fdacfaeda073b973d2ad43f75407f219a4b6eb393c01e3c1e439d` |
| actioncentre | 345804 B (337.7 KiB) | `93ceba73a179a95615ece43435b5a2cd21cdf9c7c00e31aefa7b9fce1ec6f6b8` |
| ime | 20817 B (20.3 KiB) | `f22250442df52fd14a857a80dce88a513bec9486cab056150e08c4463ff58df4` |
| header | 37872 B (37.0 KiB) | `c67086288b2575a2be5665bc1af8fcfddb9700a129b2676229e60cdb6e89178c` |

## Verify

_No verify summary was produced._

## Reproduce from a clean LineageOS install

```sh
# 0. one-time platform (manual, GUI steps are unavoidable):
#    flash LineageOS-for-microG 23.2 for Mi8917 (recovery -> format data -> adb -d sideload),
#    install Magisk (Zygisk on) and LSPosed/Vector, authorise adb, keep the phone unlocked.
# 1. on the Mac, from the repo root:
./scripts/ztmetro-conductor.sh --with-factory
# 2. stage-only re-runs (all idempotent):
./repo/scripts/ztmetro-conductor.sh --skip-build --skip-publish --skip-apply   # verify+report only
./repo/scripts/ztmetro-conductor.sh --skip-build --skip-publish --allow-deploy   # apply+verify (may reboot)
```

The conductor is the same state machine for every run: a changed module is
reinstalled and followed by a normal Android reboot plus a `sys.boot_completed`
wait; unchanged artifacts are detected by sha256 (module marker) and by comparing
the installed APK sha256 + signing certificate, so a second run is a no-op.

