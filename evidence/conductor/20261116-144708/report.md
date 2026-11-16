# ztMetro conductor run 20261116-144708

- **command:** `./scripts/ztmetro-conductor.sh --with-factory`
- **started:** 2026-11-16T14:47:08+03:00
- **finished:** 2026-11-16T14:47:20+03:00
- **duration:** 12 s
- **build host:** `hermes-ubuntu` (~/ztux/conductor/repo, ~/android-root/conductor)
- **logs:** `evidence/conductor/20261116-144708/conductor.log`, per-stage `build/*.log`

## Stages

| Stage | Status | Duration | Notes |
|---|---|---:|---|
| build | failed | 12 s | see build logs |

## Artifacts

| Artifact | Size | SHA-256 |
|---|---:|---|

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
./repo/scripts/ztmetro-conductor.sh --skip-build --skip-publish                # apply+verify
```

The conductor is the same state machine for every run: a changed module is
reinstalled and followed by a normal Android reboot plus a `sys.boot_completed`
wait; unchanged artifacts are detected by sha256 (module marker) and by comparing
the installed APK sha256 + signing certificate, so a second run is a no-op.

