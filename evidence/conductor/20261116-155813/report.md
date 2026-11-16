# ztMetro conductor run 20261116-155813

- **command:** `./scripts/ztmetro-conductor.sh --with-factory`
- **snapshot:** `1c6a7f1fdd9729e50a0ce6815b774e44f2aca51b` (git archive; 11 uncommitted path(s) in the working tree excluded)
- **root:** `adb root` (adbd uid=0, SELinux `u:r:su:s0`); Magisk `su` for adb shell is not used
- **started:** 2026-11-16T15:58:13+03:00
- **finished:** 2026-11-16T16:05:14+03:00
- **duration:** 421 s
- **build host:** `hermes-ubuntu` (`~/ztux/conductor/snapshots/1c6a7f1fdd97`, `~/android-root/conductor`)
- **logs:** `evidence/conductor/20261116-155813/conductor.log`, per-stage `build/*.log`

## Stages

| Stage | Status | Duration | Notes |
|---|---|---:|---|
| build | ok | 31 s |  |
| publish | ok | 32 s |  |
| apply | ok | 35 s |  |
| verify | ok | 66 s | PASS/FAIL summary in verify.log (FAILs present) |
| factory | ok | 257 s |  |

## Artifacts

| Artifact | Size | SHA-256 |
|---|---:|---|
| overlays | 78142 B (76.3 KiB) | `ea89a8941457363e7d0342046a7f96c730c6668b39db498325e040e1959fa5e5` |
| launcher | 74155 B (72.4 KiB) | `00247799ab4a19a5c57f63129c3d4943ea015c9d030ec57a2e0ffbe5f3ce1443` |
| actioncentre | 345804 B (337.7 KiB) | `91db93d7c074438112ed18f05987ab59a0e2ddbb147e5f56b373c7bbb5338010` |
| ime | 20817 B (20.3 KiB) | `f22250442df52fd14a857a80dce88a513bec9486cab056150e08c4463ff58df4` |
| header | 41968 B (41.0 KiB) | `adcab3556e9b908654c8489a13f55242039af2335797aba1f7da69629fea6d6f` |

## Verify

```
TOTAL  PASS 13  FAIL 4  SKIP 0  / 17 checks
```

Detailed report: `evidence/conductor/20261116-155813/verify/run/report.md`

## Theme factory

| Package | Overlay | Coverage | Status |
|---|---|---|---|
| com.android.deskclock | `org.ztos.overlay.factory.com_android_deskclock` | `{"colour_resources_mapped": 174, "colour_resources_total": 492, "colour_resources_used": 232, "layouts_scanned": 110, "mapped_ratio_of_used": 0.75}` | ok |
| com.android.contacts | `org.ztos.overlay.factory.com_android_contacts` | `{"colour_resources_mapped": 239, "colour_resources_total": 1035, "colour_resources_used": 310, "layouts_scanned": 219, "mapped_ratio_of_used": 0.771}` | ok |
| com.android.dialer | `org.ztos.overlay.factory.com_android_dialer` | `{"colour_resources_mapped": 222, "colour_resources_total": 1061, "colour_resources_used": 301, "layouts_scanned": 258, "mapped_ratio_of_used": 0.738}` | ok |
| org.lineageos.aperture | `org.ztos.overlay.factory.org_lineageos_aperture` | `{"colour_resources_mapped": 173, "colour_resources_total": 493, "colour_resources_used": 231, "layouts_scanned": 80, "mapped_ratio_of_used": 0.749}` | ok |

## Performance appendix

Captured after the verify stage on the same boot (owner request: lag baseline).

| Process | TOTAL PSS (KB) | Raw dump |
|---|---:|---|
| `com.android.systemui` | 150043 | `perf/meminfo-com.android.systemui.txt` |
| `com.android.settings` | 41792 | `perf/meminfo-com.android.settings.txt` |
| `org.ztmetro.actioncentre` | 29210 | `perf/meminfo-org.ztmetro.actioncentre.txt` |
| `org.ztmetro.start` | 25745 | `perf/meminfo-org.ztmetro.start.txt` |

**`dumpsys gfxinfo com.android.settings`**

```
Janky frames: 8 (12.31%)
Janky frames (legacy): 28 (43.08%)
50th percentile: 25ms
90th percentile: 38ms
95th percentile: 61ms
99th percentile: 450ms
50th gpu percentile: 12ms
90th gpu percentile: 18ms
95th gpu percentile: 20ms
99th gpu percentile: 21ms
```

**`/proc/pressure/memory`**

```
some avg10=0.58 avg60=1.03 avg300=0.97 total=6830396
full avg10=0.22 avg60=0.23 avg300=0.17 total=1624053
```

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

