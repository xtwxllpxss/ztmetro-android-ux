# 25 — The conductor: one unattended command for the whole ztMetro stack

**Status (2026-11-16):** implemented, run end-to-end on `ugglite`. This document is
the operator manual; the machine-readable run record lives in
[`evidence/conductor/`](../evidence/conductor/).

---

## 1. Why

Before this script the stack was re-applied by a hand-copied sequence: rebuild five
artifacts on `hermes-ubuntu`, `scp`/`adb push` them, install the Magisk module,
reboot, install four APKs, enable nine RROs, toggle two accessibility services and
two notification listeners, set `icon_blacklist`, grant appops, restart SystemUI,
then run `ux-verify.py --quick`. Every step had a different idempotence story, and a
missed step looked exactly like a broken artifact.

`repo/scripts/ztmetro-conductor.sh` is the single entry point for that sequence. It
is a state machine with six stages, per-stage logs, a machine-readable run record
and a dry-run mode. It never touches the ROM, boot, system or persist partitions.
The only reboot is the normal Android reboot needed to mount a changed Magisk
module (or a new factory RRO), and the script waits for `sys.boot_completed` plus a
ready package manager before measuring anything.

```sh
repo/scripts/ztmetro-conductor.sh --with-factory
```

Everything below is what that one command does.

---

## 2. Stages

| # | Stage | What it does | Idempotence key |
|---|---|---|---|
| 1 | `build` | Extract a **git-archive snapshot of one commit** to `hermes-ubuntu:~/ztux/conductor/snapshots/<sha12>` and run `build-overlay-module.sh`, `build-launcher.sh`, `build-actioncentre.sh`, `build-ime.sh`, `build-header-module.sh` there. Throwaway keystores and the pinned Xposed jar live in a stable `~/ztux/conductor/state` tree, symlinked into each snapshot. Collects size + sha256 of all five artifacts and the source commit. | rebuild is deterministic per commit; the live working tree is never built, so a parallel agent's transient broken file cannot fail the run. A failed build still stops with the exact 40-line error tail |
| 2 | `publish` | Copy artifacts to `hermes-ubuntu:~/android-root/conductor/` ("scp" without a second network hop: they are already on the build host), push + `magisk --install-module`, install the four APKs, set Start as HOME, write `/data/local/tmp/ztmetro-manifest.json`, reboot only if the module changed. | module zip sha256 stored in `/data/adb/modules/ztmetro-overlays/.conductor-sha256`; APKs compared by installed sha256 and, when it differs, by `apksigner` signing certificate |
| 3 | `apply` | Read the overlay package names from the module APKs with `aapt2 dump badging`, enable each RRO, log before/after, set framework priority, union both accessibility services into `enabled_accessibility_services`, set `accessibility_enabled`, union both notification listeners, set `icon_blacklist`, grant `WRITE_SETTINGS`/`ACCESS_RESTRICTED_SETTINGS` appops and `CAMERA`/`READ_PHONE_STATE`, restart SystemUI **only if an overlay changed**. | every write is a compare-then-set; setting lists are unioned, never replaced |
| 4 | `verify` | `python3 scripts/ux-verify.py --quick --out evidence/conductor/<ts>/verify` and parse the `TOTAL PASS/FAIL/SKIP` line. If the script is missing or produces no summary, the built-in checks run (expected overlays enabled, both services/listeners present, HOME resolves to Start). | read-only; a verify FAIL is reported, not "fixed" |
| 5 | `factory` | *(only with `--with-factory`)* For `com.android.deskclock`, `com.android.contacts`, `com.android.dialer`, `org.lineageos.aperture`: `adb pull` the installed APK, `theme-factory.sh analyse`, gate on the contrast check, `generate --force-package`, build the `ztmetro-factory` Magisk module, install it, reboot once, enable each RRO and force-stop the target app. Records coverage per package. | factory module zip sha256 stored in `/data/adb/modules/ztmetro-factory/.conductor-sha256`; an RRO whose contrast gate fails is never installed |
| 6 | `report` | Write `evidence/conductor/<ts>/report.md` and `run.json`: stages, durations, artifact table, verify summary, factory coverage, and the exact commands to reproduce the state from a clean LineageOS install. | always runs, including after a failed stage |

The build stage is **snapshot-isolated on purpose**. Parallel agents edit the
working tree constantly; a half-saved Java file must not decide whether the phone
gets a new module. `git archive HEAD` is streamed to the build host, so the run
builds exactly one commit and the report records `snapshot.commit` plus the number
of uncommitted paths that were excluded. Use `ZTMETRO_SNAPSHOT=<commit>` to build a
different commit without checking it out.

Skipping is explicit: `--skip-build`, `--skip-publish`, `--skip-apply`,
`--skip-verify`, `--skip-factory`, `--skip-report`. A skipped stage is recorded as
`skipped` in the report. The stages are deliberately sequential: each consumes the
previous one's output and the phone has a single owner.

---

## 3. Usage

```sh
# full unattended run, including the optional stock-app factory
repo/scripts/ztmetro-conductor.sh --with-factory

# print every command, touch neither hermes nor the phone
repo/scripts/ztmetro-conductor.sh --dry-run --with-factory

# rebuild + reinstall + reapply + verify
repo/scripts/ztmetro-conductor.sh

# apply/verify only (artifacts already on the build host)
repo/scripts/ztmetro-conductor.sh --skip-build --skip-publish

# verify + report only
repo/scripts/ztmetro-conductor.sh --skip-build --allow-deploy --skip-publish --skip-apply
```

`--skip-build` is gated: it deploys artifacts built by an earlier run, and those
may belong to a different commit. It refuses to run without **`--allow-deploy`**
and prints a warning that the phone may reboot.

Useful environment overrides:

| Variable | Default | Meaning |
|---|---|---|
| `ZTMETRO_SSH_HOST` | `hermes-ubuntu` | build host and the only route to adb |
| `ZTMETRO_REMOTE_SNAPSHOTS` | `ztux/conductor/snapshots` | per-commit extracted build trees |
| `ZTMETRO_REMOTE_STATE` | `ztux/conductor/state` | stable keystores + header deps across commits |
| `ZTMETRO_SNAPSHOT` | `HEAD` | commit to archive/build instead of HEAD |
| `ZTMETRO_REMOTE_STAGE` | `android-root/conductor` | published artifacts + manifest |
| `ZTMETRO_REMOTE_FACTORY` | `ztux/conductor/factory` | factory APKs/profiles/RROs |
| `ANDROID_SERIAL` | unset | passed through to adb when more than one device is attached |

Exit codes: `0` all executed stages ok; `1` a hard stage failed (build, publish,
apply, factory); `2` the run completed but `ux-verify` reported at least one `FAIL`.
The device is returned to booted state before exit in all cases except a failed
build (which never touches the phone).

### Prerequisites

* Clean base: **LineageOS-for-microG 23.2 for Mi8917 (`ugglite`)**. The flashing
  recipe and the recovery/sideload commands are in
  [`evidence/device-flash/PLAN.md`](../evidence/device-flash/PLAN.md); this is the
  one part that stays manual.
* **Magisk** (Zygisk on) + **LSPosed/Vector** installed. The conductor installs
  Magisk *modules* and *APKs*, but it cannot enable an LSPosed module or select its
  scope (GUI-only; see §7).
* `hermes-ubuntu` reachable by SSH key, with `~/android-sdk` (android-36,
  build-tools 36.0.0), `~/android-jdk` (JDK 17), `tar`, `zip`, `unzip`, `curl`.
  The header build downloads the pinned Xposed API 82 jar once, then reuses it
  from the stable state tree.
* Local `python3` with Pillow + numpy for `ux-verify.py --quick`.
* The phone unlocked for the verify stage (screenshots of Start, Settings and the
  Action Centre cannot be taken over a keyguard).

---

## 4. What each stage decides, and why

### 4.0 Snapshot: which code was built

`SNAP_COMMIT=$(git rev-parse HEAD)` (or `ZTMETRO_SNAPSHOT`) is archived with
`git archive --format=tar` and extracted on the build host; the live working tree
is not copied. The snapshot directory is named by the 12-character commit, so the
same commit reuses the same path and a different commit cannot inherit stale
output. Only the keystore/deps *directories* are shared via
`~/ztux/conductor/state`, which keeps the APK signing cert stable across commits
and avoids needless uninstall/reinstall cycles. `run.json` and `report.md` carry
the full commit and the count of uncommitted paths that were excluded.

### 4.1 Module: content hash, not version

`modules/ztmetro-overlays/module.prop` has a fixed `version=1.0.0`, so the module's
version code cannot tell a rebuild from a no-op. After a successful
`magisk --install-module`, the conductor writes the zip's sha256 to
`/data/adb/modules/ztmetro-overlays/.conductor-sha256` (the marker lives in the
module directory, so a Magisk removal takes it with it). A later run compares the
new zip hash to the marker and skips the install + reboot when they match. This is
what makes the second run take seconds instead of a reboot cycle.

### 4.2 APKs: installed sha256 first, signing cert second

For each of the four APKs the conductor:

1. reads `pm path`, pulls the installed base APK and hashes it; identical sha256
   means "already installed", no adb install;
2. if the hash differs, runs `apksigner verify --print-certs` on the installed and
   the new APK and compares the **signer certificate SHA-256 digest**;
3. same cert → `adb install -r` (keeps app data and grants);
4. different cert → `adb uninstall` then `adb install` (Android refuses an update
   across signing keys).

The build scripts generate throwaway keystores under `build/keystore`; the
conductor replaces that directory with a symlink into the stable
`~/ztux/conductor/state` tree, so the cert is stable across rebuilds and across
snapshot commits. A *new* build host therefore causes one uninstall/reinstall
cycle on the first run; that is intentional and is the only case where app
data/grants are dropped.

### 4.3 Overlays: enumerate the APKs, not a hard-coded list

The apply stage pulls the nine module APKs and reads each package name with
`aapt2 dump badging`. That keeps the conductor honest when the module gains or
loses an RRO. The hard-coded package list is only a fallback when the module APKs
cannot be pulled. Enabling is a compare: `[x] pkg` in `cmd overlay list` → already
enabled; `[ ]` → `cmd overlay enable --user 0`. SystemUI is killed only when this
comparison (or a module reinstall) changed something; Settings is force-stopped
only when the Settings RRO changed.

### 4.4 Settings: union, never replace

`enabled_accessibility_services` and `enabled_notification_listeners` are
colon-separated lists owned by the user. The conductor reads the current value,
merges in the two ztMetro components, writes it back only when it changed, then
re-reads to prove it. It never writes a single-component list over an existing one.
`icon_blacklist` is a fixed documented value and is set only when it differs.

### 4.5 Verification

`ux-verify.py --quick` drives Start → Settings home → Action Centre and measures
tokens/geometry; its own `report.md` and screenshots live under
`evidence/conductor/<ts>/verify/run/`. The conductor parses its
`TOTAL PASS n FAIL n SKIP n / n checks` line into `verify-summary.txt`. A `FAIL` is
a finding about the running stack, not a conductor bug: the run still produces the
report and exits `2` so CI notices. If `ux-verify.py` is absent, the built-in check
covers the state the conductor itself owns (overlays enabled, services/listeners
registered, HOME).

### 4.6 Factory: contrast gate before install

The factory never applies an overlay it cannot justify:

* `analyse` is gated on the profile's `safety.checks`: an entry that still has
  `passes_4_5=false` **and** no post-escalation ratio ≥ 4.5 is an unresolved
  contrast failure → the package is recorded as `contrast-fail` and skipped;
* `generate` must exit 0 and `build.json.verify.ok` must be true;
* only then is the RRO copied into the `ztmetro-factory` Magisk module. A new RRO
  or a changed module needs one reboot so PackageManager scans `/product/overlay`;
  unchanged content is skipped by the module hash.

Packages outside `tools/theme-factory/registry.json` are built with
`--force-package` and that fact is recorded in the report. An app that declares
`<overlayable>` still needs a `--target-name` chosen from its policy; the conductor
reports `generate-failed` instead of guessing.

---

## 5. Evidence layout

```
repo/evidence/conductor/<YYYYmmdd-HHMMSS>/
├── conductor.log            # every command/decision, timestamped
├── stages.tsv               # stage, status, duration, note
├── artifacts.tsv            # artifact, path, sha256, size
├── verify-summary.txt       # ux-verify TOTAL line
├── verify.log               # full stdout of ux-verify (or builtin fallback)
├── verify/run/report.md     # ux-verify's own report + raw/annotated PNGs
├── build/*.log              # five build logs (failure tail is copied to conductor.log)
├── factory/<pkg>/           # profile.json, analyse.log, generate.log, mapping-table.md
├── factory/coverage.tsv     # package, overlay, coverage JSON, status
├── ztmetro-manifest.json    # what is on the phone (also pushed to /data/local/tmp)
├── report.md                # the human run report
└── run.json                 # the same data, machine-readable
```

Nothing outside `evidence/conductor/` is written on the Mac; builds and their
`build/` trees live on `hermes-ubuntu`. Repo sources are only read.

---

## 6. Reproducing the same state from a clean LineageOS install

```sh
# 0. one-time platform (manual; recovery + GUI steps cannot be automated safely)
#    - flash LineageOS-for-microG 23.2 for Mi8917:
#        recovery -> format data -> adb -d sideload lineage-23.2-*-Mi8917.zip
#    - install Magisk (patch boot, flash, Zygisk on), install LSPosed/Vector
#    - enable adb, keep the phone unlocked
#
# 1. on the Mac, from the repo root — this is the only command:
repo/scripts/ztmetro-conductor.sh --with-factory

# 2. what it does under the hood, in order (all idempotent):
#    git archive HEAD -> snapshot on hermes -> build
#    build on hermes  -> publish to ~/android-root/conductor + adb
#    -> magisk --install-module + reboot + wait boot_completed
#    -> install APKs (cert-aware) -> set-home-activity
#    -> enable RROs -> a11y + listeners -> icon_blacklist -> appops
#    -> ux-verify --quick -> factory (optional) -> report.md
```

The first run on a fresh build host takes the long path (new keystore → APK
uninstall/reinstall, new module → reboot). Later runs are no-ops for unchanged
artifacts and skip the reboot; `--dry-run` proves that without touching anything.

---

## 7. Known limits (what one command still cannot do)

1. **LSPosed/Vector module activation and scope selection.** The Settings header
   and the keyguard module are Xposed modules. The conductor installs the APK, but
   enabling the module and setting its scope (`com.android.settings` only /
   `com.android.systemui` only) is a GUI action in the LSPosed manager. On a cert
   change the uninstall/reinstall can also reset that scope. The run reports the
   installed APK but cannot prove the hook is active.
2. **Human visual acceptance of the full 7-surface suite.** `ux-verify --quick`
   measures Start, Settings home and the Action Centre; the lock screen, volume
   panel, app list and shade still need the full suite or eyes, and “does it look
   like ztMetro” is not a pixel metric. The conductor records the quick PASS/FAIL,
   not a design sign-off.
3. **Choosing a `targetName` for `<overlayable>` apps in the factory.** When an app
   declares an overlayable policy, `idmap2` requires a group name; the conductor
   detects the refusal and reports the package as skipped rather than guessing a
   policy group. The same applies to apps whose resources are drawn in code: the
   factory says it cannot theme them, and no RRO is installed.

A fourth, smaller limit: the first run on a new build host uninstalls/reinstalls
the four APKs because their throwaway signing keys are new; this drops app-local
state and any manual grants, which the apply stage then re-creates from its
documented list.

---

## 8. Worked run

See the latest run under [`evidence/conductor/`](../evidence/conductor/) — the
`report.md` there contains the exact command line, stage durations, artifact hash
table, verify summary and factory coverage for that run. A failed build is also
recorded there with the failing stage and the error tail, so a red run is
diagnosable without re-reading the terminal.
