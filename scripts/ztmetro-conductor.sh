#!/usr/bin/env bash
# ztmetro-conductor.sh — one unattended entry point for the whole ztMetro stack.
#
#   repo/scripts/ztmetro-conductor.sh [options]
#
# Stages (each runs in this order, each can be skipped):
#
#   build    extract a git-archive snapshot of HEAD on hermes-ubuntu (the live
#            working tree is never built) and rebuild the overlays Magisk module,
#            Start, Action Centre, IME and the Settings header module there.
#   publish  publish the artifacts to hermes:~/android-root/conductor/, install
#            the Magisk module + APKs on the phone, set Start as HOME, write
#            /data/local/tmp/ztmetro-manifest.json, reboot if the module changed
#            and wait for sys.boot_completed.
#   apply    enable every RRO package found in the module APKs, enable both
#            accessibility services and both notification listeners, set
#            icon_blacklist, grant the documented appops/runtime permissions and
#            restart SystemUI only when an overlay actually changed.
#   verify   repo/scripts/ux-verify.py --quick (or a built-in fallback) and a
#            parsed PASS/FAIL/SKIP summary.
#   factory  (only with --with-factory) pull a list of stock APKs, run the theme
#            factory analyse+generate, build the factory Magisk module, install
#            it, reboot, enable the RROs and record coverage.  An overlay whose
#            contrast check failed is reported, never applied.
#   report   one Markdown file + run.json under evidence/conductor/<ts>/.
#
# Options:
#   --dry-run            print the commands, touch neither hermes nor the phone
#   --with-factory       run stage 5 (off by default)
#   --skip-build|publish|apply|verify|factory|report
#   --allow-deploy       required with --skip-build: deploy existing artifacts
#                        (this may reboot the phone; prints a warning)
#   --help
#
# Exit codes: 0 all executed stages ok; 1 a hard stage failed (build/publish/
# apply/factory); 2 the run completed but ux-verify reported at least one FAIL.
#
# Prerequisites (from clean LineageOS install):
#   * LineageOS-for-microG 23.2 on ugglite, Magisk + Zygisk + LSPosed/Vector,
#     adb authorised, device unlocked.
#   * hermes-ubuntu reachable by key, with ~/android-sdk (android-36,
#     build-tools 36.0.0), ~/android-jdk (JDK 17), tar, zip, unzip, curl.
#   * local python3 with Pillow + numpy for ux-verify.py --quick.
#
# The conductor never modifies the ROM, boot, system or persist partitions; the
# only reboot is the normal Android reboot needed to mount a changed Magisk
# module.  Module/APK installs are idempotent (sha256 + signing-cert compare).
set -u -o pipefail

# --------------------------------------------------------------------------
# Configuration (environment overridable)
# --------------------------------------------------------------------------
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

SSH_HOST=${ZTMETRO_SSH_HOST:-hermes-ubuntu}
REMOTE_SNAP_BASE=${ZTMETRO_REMOTE_SNAPSHOTS:-ztux/conductor/snapshots}
REMOTE_STATE=${ZTMETRO_REMOTE_STATE:-ztux/conductor/state}
REMOTE_STAGE=${ZTMETRO_REMOTE_STAGE:-android-root/conductor}
REMOTE_FACTORY=${ZTMETRO_REMOTE_FACTORY:-ztux/conductor/factory}
SNAP_SELECT=${ZTMETRO_SNAPSHOT:-}
ADB='$HOME/android-sdk/platform-tools/adb'

# Build stage artifacts, relative to the snapshot root.  Order matters: the
# overlay module consumes the prebuilt RRO APKs under evidence/.
ART_LABELS=(overlays launcher actioncentre ime header)
ART_RELS=(
  "artifacts/ztmetro-overlays-1.0.0.zip"
  "launcher/build/out/ztmetro-start.apk"
  "actioncentre/build/out/ztmetro-actioncentre.apk"
  "ime/build/out/ztmetro-ime.apk"
  "settings/header-module/build/dist/ztmetro-settings-header.apk"
)

# Overlay packages shipped by modules/ztmetro-overlays (fallback when aapt2
# cannot read one of the module APKs).
EXPECTED_OVERLAYS="org.ztmetro.overlay.framework org.ztos.overlay.framework.extra org.ztos.overlay.systemui org.ztos.overlay.settings org.ztos.overlay.status.framework org.ztos.overlay.status.systemui org.ztos.overlay.statusbaricons org.ztos.overlay.systemui.extra org.ztos.overlay.factory.org_lineageos_etar"

A11Y_SERVICES=(
  "org.ztmetro.actioncentre/.ShadeBridgeService"
  "org.ztmetro.actioncentre/.StatusStripService"
)
NOTIF_LISTENERS=(
  "org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreListener"
  "org.ztmetro.start/org.ztmetro.start.StartNotificationListener"
)
ICON_BLACKLIST="clock,battery,wifi,mobile,stacked_mobile,rotate,headset"

FACTORY_PACKAGES=(
  "com.android.deskclock"
  "com.android.contacts"
  "com.android.dialer"
  "org.lineageos.aperture"
)

# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
ORIG_ARGS=("$@")
DRY=0
WITH_FACTORY=0
ALLOW_DEPLOY=0
SKIP_BUILD=0 SKIP_PUBLISH=0 SKIP_APPLY=0 SKIP_VERIFY=0 SKIP_FACTORY=0 SKIP_REPORT=0

usage() {
  sed -n '2,52p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --with-factory) WITH_FACTORY=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --allow-deploy) ALLOW_DEPLOY=1 ;;
    --skip-publish) SKIP_PUBLISH=1 ;;
    --skip-apply) SKIP_APPLY=1 ;;
    --skip-verify) SKIP_VERIFY=1 ;;
    --skip-factory) SKIP_FACTORY=1; WITH_FACTORY=0 ;;
    --skip-report) SKIP_REPORT=1 ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [ "$SKIP_BUILD" -eq 1 ] && [ "$ALLOW_DEPLOY" -eq 0 ]; then
  printf 'error: --skip-build deploys artifacts built by an earlier run and may reboot the phone.\n' >&2
  printf '       Re-run with --allow-deploy if that is intended.\n' >&2
  exit 2
fi

# --------------------------------------------------------------------------
# Run directory and logging
# --------------------------------------------------------------------------
RUN_TS=$(date +%Y%m%d-%H%M%S)
EVID="$REPO/evidence/conductor/$RUN_TS"
SUFFIX=1
while [ -e "$EVID" ]; do
  suffix=$SUFFIX
  SUFFIX=$((SUFFIX + 1))
  EVID="$REPO/evidence/conductor/$RUN_TS-$suffix"
done
mkdir -p "$EVID/build" "$EVID/verify" "$EVID/factory"
MAIN_LOG="$EVID/conductor.log"
STAGES_TSV="$EVID/stages.tsv"
ART_TSV="$EVID/artifacts.tsv"
VERIFY_SUMMARY="$EVID/verify-summary.txt"
: > "$MAIN_LOG"
: > "$STAGES_TSV"
: > "$ART_TSV"
STARTED_EPOCH=$(date +%s)
RUN_COMMAND="$0 ${ORIG_ARGS[*]:-}"

# The build stage never trusts the live working tree: it builds a git archive of
# one commit.  Parallel agents edit the tree constantly; a transient broken file
# must not fail the conductor.  ZTMETRO_SNAPSHOT overrides the commit.
if [ -n "$SNAP_SELECT" ]; then
  SNAP_COMMIT=$(git -C "$REPO" rev-parse --verify "$SNAP_SELECT^{commit}" 2>/dev/null) \
    || die "ZTMETRO_SNAPSHOT=$SNAP_SELECT is not a commit in $REPO"
else
  SNAP_COMMIT=$(git -C "$REPO" rev-parse HEAD) || die "cannot read HEAD in $REPO"
fi
SNAP_SHORT=${SNAP_COMMIT:0:12}
SNAPDIR="$REMOTE_SNAP_BASE/$SNAP_SHORT"
SNAP_DIRTY=$(git -C "$REPO" status --porcelain 2>/dev/null | wc -l | tr -d ' ')
SNAP_DIRTY=${SNAP_DIRTY:-0}

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$MAIN_LOG"; }
die() { log "FATAL: $*"; exit 1; }

rssh() { # one remote command string; quiet, batch mode
  if [ "$DRY" = 1 ]; then log "DRY ssh $SSH_HOST: $1"; return 0; fi
  ssh -n -q -o BatchMode=yes -o ConnectTimeout=20 "$SSH_HOST" "$1"
}

rq() { # remote command whose stdout is captured
  ssh -n -q -o BatchMode=yes -o ConnectTimeout=20 "$SSH_HOST" "$1"
}

dev() { # full adb argument string (executed on hermes)
  rssh "$ADB $*"
}

devq() { # adb argument string, stdout captured
  rq "$ADB $*"
}

# --------------------------------------------------------------------------
# Stage bookkeeping
# --------------------------------------------------------------------------
STAGE=""
STAGE_T0=0
stage_begin() {
  STAGE=$1
  STAGE_T0=$(date +%s)
  log ""
  log "================ stage: $STAGE ================"
}
stage_end() { # status note
  local status=$1 note=${2:-} dur
  dur=$(( $(date +%s) - STAGE_T0 ))
  printf '%s\t%s\t%s\t%s\n' "$STAGE" "$status" "$dur" "$note" >> "$STAGES_TSV"
  log "---------------- $STAGE: $status (${dur}s) ${note} ----------------"
}
stage_skip() {
  local name=$1
  printf '%s\tskipped\t0\t--skip-%s\n' "$name" "$name" >> "$STAGES_TSV"
  log "stage $name skipped by flag"
}

# --------------------------------------------------------------------------
# Remote helpers
# --------------------------------------------------------------------------
PENV='export ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk JAVA_HOME=$HOME/android-jdk; export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH;'

BT_REMOTE=""
AAPT2=""
APKSIGNER=""
REMOTE_HOME=""
REMOTE_STAGE_ABS=""
REMOTE_FACTORY_ABS=""

resolve_remote_tools() {
  if [ "$DRY" = 1 ]; then
    BT_REMOTE='$HOME/android-sdk/build-tools/36.0.0'
    AAPT2="$BT_REMOTE/aapt2"
    APKSIGNER="$BT_REMOTE/apksigner"
    return 0
  fi
  BT_REMOTE=$(rq 'ls -d $HOME/android-sdk/build-tools/* 2>/dev/null | sort -V | tail -1') || BT_REMOTE=""
  [ -n "$BT_REMOTE" ] || die "no build-tools under $SSH_HOST:~/android-sdk"
  AAPT2="$BT_REMOTE/aapt2"
  APKSIGNER="$BT_REMOTE/apksigner"
  REMOTE_HOME=$(rq 'printf %s "$HOME"') || REMOTE_HOME=""
  [ -n "$REMOTE_HOME" ] || die "could not read $SSH_HOST:\$HOME"
  REMOTE_STAGE_ABS="$REMOTE_HOME/$REMOTE_STAGE"
  REMOTE_FACTORY_ABS="$REMOTE_HOME/$REMOTE_FACTORY"
}

sha_for_label() { # label -> sha256 from artifacts.tsv
  awk -F'\t' -v l="$1" '$1==l {print $3; exit}' "$ART_TSV"
}
size_for_label() {
  awk -F'\t' -v l="$1" '$1==l {print $4; exit}' "$ART_TSV"
}
rel_for_label() { # label -> path relative to the remote build tree
  local i
  for i in "${!ART_LABELS[@]}"; do
    if [ "${ART_LABELS[$i]}" = "$1" ]; then printf '%s' "${ART_RELS[$i]}"; return 0; fi
  done
  return 1
}

enable_adb_root() { # every device mutation runs through adbd as uid 0
  local who
  who=$(devq "shell id" 2>/dev/null | tr -d '\r\n')
  case "$who" in
    uid=0*) log "root: adb shell uid=0 ($(devq 'shell getenforce' 2>/dev/null | tr -d '\r\n'))"; return 0 ;;
  esac
  log "adbd is not root; running 'adb root'"
  dev "root" >/dev/null 2>&1 || true
  sleep 3
  dev "wait-for-device" >/dev/null 2>&1 || true
  sleep 1
  who=$(devq "shell id" 2>/dev/null | tr -d '\r\n')
  case "$who" in
    uid=0*) log "root: adb shell uid=0 after adb root"; return 0 ;;
  esac
  log "ERROR: 'adb root' did not give uid 0 (adb shell id: '${who:-unreachable}')"
  log "       This needs a LineageOS userdebug/eng build (adbd root).  Magisk su is not used."
  return 1
}

wait_for_boot() { # 240 s; boot_completed + package manager
  local i=0 bc
  dev "wait-for-device" || true
  while [ "$i" -lt 120 ]; do
    bc=$(devq "shell getprop sys.boot_completed" 2>/dev/null | tr -d '\r\n')
    if [ "$bc" = "1" ]; then
      if devq "shell pm path android" 2>/dev/null | grep -q '^package:'; then
        log "boot_completed=1, package manager ready (waited $((i * 2))s)"
        return 0
      fi
    fi
    sleep 2
    i=$((i + 1))
  done
  return 1
}

# --------------------------------------------------------------------------
# STAGE 1 — build
# --------------------------------------------------------------------------
build_component() { # label script
  local label=$1 script=$2 logf="$EVID/build/$1.log" rc
  log "build $label: scripts/$script"
  ssh -q -o BatchMode=yes -o ConnectTimeout=20 "$SSH_HOST" \
      "cd ~/$SNAPDIR && $PENV bash scripts/$script" 2>&1 | tee "$logf"
  rc=${PIPESTATUS[0]}
  if [ "$rc" -ne 0 ]; then
    log "build FAILED: $label (rc=$rc)"
    log "--- error tail ($logf) ---"
    tail -n 40 "$logf" | tee -a "$MAIN_LOG"
    log "--- end error tail ---"
    return "$rc"
  fi
  log "build ok: $label"
}

collect_artifacts() {
  local specs="" i out missing label size sha
  for i in "${!ART_LABELS[@]}"; do
    specs="$specs ${ART_LABELS[$i]}:${ART_RELS[$i]}"
  done
  : > "$ART_TSV"
  if [ "$DRY" = 1 ]; then
    for i in "${!ART_LABELS[@]}"; do
      log "DRY sha256sum ~/$SNAPDIR/${ART_RELS[$i]} (size via stat -c%s)"
    done
    return 0
  fi
  out=$(rq "cd ~/$SNAPDIR || exit 1; for s in$specs; do l=\${s%%:*}; f=\${s#*:}; if [ -f \"\$f\" ]; then printf '%s\t%s\t%s\n' \"\$l\" \"\$(stat -c%s \"\$f\")\" \"\$(sha256sum \"\$f\" | cut -d' ' -f1)\"; else printf '%s\tMISSING\tMISSING\n' \"\$l\"; fi; done") || return 1
  printf '%s\n' "$out" > "$EVID/.artifacts.raw"
  missing=0
  while IFS=$'\t' read -r label size sha; do
    [ -n "$label" ] || continue
    if [ "$size" = "MISSING" ]; then
      log "missing build artifact: $label"
      missing=1
      continue
    fi
    printf '%s\t%s\t%s\t%s\n' "$label" "$(rel_for_label "$label")" "$sha" "$size" >> "$ART_TSV"
  done < "$EVID/.artifacts.raw"
  [ "$missing" -eq 0 ] || return 1
  log "artifacts:"
  while IFS=$'\t' read -r label rel sha size; do
    printf '  %-12s %8s B  %s  %s\n' "$label" "$size" "${sha:0:16}..." "$rel" | tee -a "$MAIN_LOG"
  done < "$ART_TSV"
}

stage_build() {
  if [ "$DRY" = 1 ]; then
    log "DRY snapshot: commit $SNAP_COMMIT (git archive, working tree dirty paths excluded)"
    log "DRY git -C $REPO archive --format=tar $SNAP_COMMIT | ssh $SSH_HOST 'rm -rf ~/$SNAPDIR && mkdir -p ~/$SNAPDIR && tar -x -C ~/$SNAPDIR'"
    log "DRY ssh $SSH_HOST 'mkdir -p ~/$REMOTE_STATE/{launcher,actioncentre,ime,header}-keystore ~/$REMOTE_STATE/header-deps'"
    log "DRY ssh $SSH_HOST 'ln -sfn ~/$REMOTE_STATE/<component>-keystore ~/$SNAPDIR/<component>/build/keystore'   # x4"
    log "DRY ssh $SSH_HOST 'ln -sfn ~/$REMOTE_STATE/header-deps ~/$SNAPDIR/settings/header-module/build/deps'"
    log "DRY ssh $SSH_HOST 'cd ~/$SNAPDIR && $PENV bash scripts/build-overlay-module.sh'"
    log "DRY ssh $SSH_HOST 'cd ~/$SNAPDIR && $PENV bash scripts/build-launcher.sh'"
    log "DRY ssh $SSH_HOST 'cd ~/$SNAPDIR && $PENV bash scripts/build-actioncentre.sh'"
    log "DRY ssh $SSH_HOST 'cd ~/$SNAPDIR && $PENV bash scripts/build-ime.sh'"
    log "DRY ssh $SSH_HOST 'cd ~/$SNAPDIR && $PENV bash scripts/build-header-module.sh'"
    log "DRY ssh $SSH_HOST 'sha256sum + stat -c%s each artifact'"
    return 0
  fi

  log "snapshot: commit $SNAP_COMMIT (git archive; excluded $SNAP_DIRTY uncommitted path(s) from the working tree)"
  rssh "mkdir -p ~/$REMOTE_SNAP_BASE ~/$REMOTE_STATE" || return 1
  if [ -z "$SNAP_SHORT" ] || [ "$SNAP_SHORT" = "000000000000" ]; then
    log "refusing to replace an empty snapshot path"
    return 1
  fi
  log "extracting git archive to $SSH_HOST:~/$SNAPDIR"
  if ! git -C "$REPO" archive --format=tar "$SNAP_COMMIT" \
      | ssh -q -o BatchMode=yes -o ConnectTimeout=20 "$SSH_HOST" \
          "rm -rf ~/$SNAPDIR && mkdir -p ~/$SNAPDIR && tar -x -C ~/$SNAPDIR"; then
    log "git archive / remote extract failed"
    return 1
  fi

  # Build output + throwaway keystores live in a stable per-component state
  # directory, so rebuilds of a different commit keep the same signing cert.
  rssh "mkdir -p \
      ~/$SNAPDIR/launcher/build ~/$SNAPDIR/actioncentre/build \
      ~/$SNAPDIR/ime/build ~/$SNAPDIR/settings/header-module/build \
      ~/$REMOTE_STATE/launcher-keystore ~/$REMOTE_STATE/actioncentre-keystore \
      ~/$REMOTE_STATE/ime-keystore ~/$REMOTE_STATE/header-keystore \
      ~/$REMOTE_STATE/header-deps" || return 1
  rssh "ln -sfn ~/$REMOTE_STATE/launcher-keystore ~/$SNAPDIR/launcher/build/keystore; \
        ln -sfn ~/$REMOTE_STATE/actioncentre-keystore ~/$SNAPDIR/actioncentre/build/keystore; \
        ln -sfn ~/$REMOTE_STATE/ime-keystore ~/$SNAPDIR/ime/build/keystore; \
        ln -sfn ~/$REMOTE_STATE/header-keystore ~/$SNAPDIR/settings/header-module/build/keystore; \
        ln -sfn ~/$REMOTE_STATE/header-deps ~/$SNAPDIR/settings/header-module/build/deps" || return 1

  resolve_remote_tools
  build_component overlays build-overlay-module.sh || return 1
  build_component launcher build-launcher.sh || return 1
  build_component actioncentre build-actioncentre.sh || return 1
  build_component ime build-ime.sh || return 1
  build_component header build-header-module.sh || return 1
  collect_artifacts || return 1
  return 0
}

# --------------------------------------------------------------------------
# STAGE 2 — publish
# --------------------------------------------------------------------------
ensure_artifacts() {
  if [ ! -s "$ART_TSV" ] || [ "$(wc -l < "$ART_TSV" | tr -d ' ')" -eq 0 ]; then
    log "artifact table empty; collecting existing build outputs from $SSH_HOST"
    collect_artifacts || return 1
  fi
}

install_apk() { # label package apk-basename remote-path
  local label=$1 pkg=$2 base=$3 remote=$4
  local inst inst_path inst_sha oldcert newcert
  inst=$(devq "shell pm path $pkg" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)
  log "install $pkg ($label)"
  if [ -n "$inst" ]; then
    log "  installed: $inst"
    rssh "rm -f /tmp/conductor-installed-$pkg.apk" >/dev/null 2>&1 || true
    if ! dev "pull '$inst' /tmp/conductor-installed-$pkg.apk" >/dev/null 2>&1; then
      log "  WARN could not pull installed APK; using install -r without sha compare"
      dev "install -r $remote" || return 1
      return 0
    fi
    inst_sha=$(rq "sha256sum /tmp/conductor-installed-$pkg.apk | cut -d' ' -f1" | tr -d '\r\n')
    if [ "$inst_sha" = "$(sha_for_label "$label")" ]; then
      log "  identical sha256 -> already installed, nothing to do"
      return 0
    fi
    oldcert=$(rq "export PATH=\$HOME/android-jdk/bin:\$PATH; '$APKSIGNER' verify --print-certs /tmp/conductor-installed-$pkg.apk 2>/dev/null | grep -m1 'certificate SHA-256 digest'")
    newcert=$(rq "export PATH=\$HOME/android-jdk/bin:\$PATH; '$APKSIGNER' verify --print-certs '$remote' 2>/dev/null | grep -m1 'certificate SHA-256 digest'")
    if [ -z "$oldcert" ] || [ -z "$newcert" ]; then
      log "  WARN could not compare signing certs (old='$oldcert' new='$newcert'); using install -r"
      dev "install -r $remote" || return 1
      return 0
    fi
    if [ "$oldcert" != "$newcert" ]; then
      log "  signing cert changed -> uninstall first"
      log "    old: $oldcert"
      log "    new: $newcert"
      dev "uninstall $pkg" || { log "  WARN uninstall failed"; }
      dev "install $remote" || return 1
    else
      log "  same signing cert -> install -r"
      dev "install -r $remote" || return 1
    fi
  else
    log "  not installed -> fresh install"
    dev "install $remote" || return 1
  fi
}

stage_publish() {
  local modbase ovlbase label rel base remote module_marker module_sha
  if [ "$DRY" = 1 ]; then
    log "DRY ssh $SSH_HOST 'mkdir -p ~/$REMOTE_STAGE'"
    log "DRY ssh $SSH_HOST 'cp ~/$SNAPDIR/<artifact> ~/$REMOTE_STAGE/<basename>'   # x5 (scp within hermes)"
    log "DRY $ADB push ~/$REMOTE_STAGE/ztmetro-overlays-1.0.0.zip /sdcard/ztmetro-overlays-1.0.0.zip"
    log "DRY $ADB root   # adbd restarted as uid 0"
    log "DRY $ADB shell magisk --install-module /sdcard/ztmetro-overlays-1.0.0.zip"
    log "DRY $ADB shell \"echo <sha256> > /data/adb/modules/ztmetro-overlays/.conductor-sha256\""
    log "DRY $ADB shell pm path <pkg>  # compare sha256 + apksigner certs, then install -r / uninstall+install"
    log "DRY $ADB shell cmd package set-home-activity org.ztmetro.start/.StartActivity"
    log "DRY $ADB push <manifest> /data/local/tmp/ztmetro-manifest.json"
    log "DRY $ADB reboot && $ADB wait-for-device && poll sys.boot_completed (module changed)"
    return 0
  fi

  ensure_artifacts || return 1
  resolve_remote_tools
  enable_adb_root || return 1

  rssh "mkdir -p ~/$REMOTE_STAGE" || return 1
  while IFS=$'\t' read -r label rel sha size; do
    base=$(basename "$rel")
    rssh "cp -f ~/$SNAPDIR/$rel ~/$REMOTE_STAGE/$base" || {
      log "publish copy failed: $rel"
      return 1
    }
  done < "$ART_TSV"
  log "artifacts published to $SSH_HOST:~/$REMOTE_STAGE/"

  module_sha=$(sha_for_label overlays)
  module_fp=$(rq "cd ~/$SNAPDIR/modules/ztmetro-overlays && find . -type f | sort | xargs sha256sum | sha256sum | cut -d' ' -f1" | tr -d '\r\n')
  [ -n "$module_fp" ] || { log "could not fingerprint the module payload"; return 1; }
  module_marker=$(devq "shell cat /data/adb/modules/ztmetro-overlays/.conductor-sha256 2>/dev/null" 2>/dev/null | tr -d '\r\n')
  MODULE_CHANGED=0
  if [ "$module_marker" = "$module_fp" ]; then
    log "Magisk module payload already at ${module_fp:0:16}... -> skip install/reboot"
  else
    modbase=$(basename "$(rel_for_label overlays)")
    log "installing Magisk module $modbase (payload marker='${module_marker:0:16}')"
    dev "push $REMOTE_STAGE_ABS/$modbase /sdcard/$modbase" || return 1
    dev "shell magisk --install-module /sdcard/$modbase" || {
      log "magisk --install-module failed"
      return 1
    }
    MODULE_CHANGED=1
  fi

  install_apk launcher org.ztmetro.start ztmetro-start.apk "$REMOTE_STAGE_ABS/ztmetro-start.apk" || return 1
  install_apk actioncentre org.ztmetro.actioncentre ztmetro-actioncentre.apk "$REMOTE_STAGE_ABS/ztmetro-actioncentre.apk" || return 1
  install_apk ime org.ztmetro.ime ztmetro-ime.apk "$REMOTE_STAGE_ABS/ztmetro-ime.apk" || return 1
  install_apk header org.ztmetro.settingsheader ztmetro-settings-header.apk "$REMOTE_STAGE_ABS/ztmetro-settings-header.apk" || return 1

  log "setting Start as HOME"
  dev "shell cmd package set-home-activity org.ztmetro.start/.StartActivity" || return 1

  write_manifest || return 1

  if [ "$MODULE_CHANGED" -eq 1 ]; then
    log "module changed -> normal Android reboot to mount it"
    dev "reboot" || true
    if ! wait_for_boot; then
      log "device did not reach boot_completed after reboot"
      return 1
    fi
    enable_adb_root || return 1
    dev "shell \"echo $module_fp > /data/adb/modules/ztmetro-overlays/.conductor-sha256\"" || {
      log "could not write module marker after boot"
      return 1
    }
  else
    log "module unchanged -> no reboot needed"
  fi
  return 0
}

write_manifest() {
  local manifest="$EVID/ztmetro-manifest.json" i label rel base badging pkg vc vn
  local rows="" sep=""
  for i in "${!ART_LABELS[@]}"; do
    label=${ART_LABELS[$i]}
    rel=$(rel_for_label "$label")
    base=$(basename "$rel")
    badging=$(rq "$PENV '$AAPT2' dump badging $REMOTE_STAGE_ABS/$base 2>/dev/null | head -1")
    pkg=$(printf '%s' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
    vc=$(printf '%s' "$badging" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")
    vn=$(printf '%s' "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
    rows="$rows$sep    {\"artifact\": \"$label\", \"package\": \"$pkg\", \"versionName\": \"$vn\", \"versionCode\": \"$vc\", \"sha256\": \"$(sha_for_label "$label")\", \"size\": $(size_for_label "$label"), \"file\": \"$base\"}"
    sep=",
"
  done
  {
    printf '{\n'
    printf '  "schema": "ztos.conductor.manifest/1",\n'
    printf '  "generated_at": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  "run_id": "%s",\n' "$(basename "$EVID")"
    printf '  "snapshot": {"commit": "%s", "short": "%s", "source": "git archive HEAD; %s uncommitted path(s) excluded"},\n' "$SNAP_COMMIT" "$SNAP_SHORT" "$SNAP_DIRTY"
    printf '  "device": "%s",\n' "$(devq 'shell getprop ro.serialno' 2>/dev/null | tr -d '\r\n')"
    printf '  "home": "org.ztmetro.start/.StartActivity",\n'
    printf '  "module": {"id": "ztmetro-overlays", "zip_sha256": "%s", "content_sha256": "%s"},\n' "$(sha_for_label overlays)" "${module_fp:-}"
    printf '  "packages": [\n%s\n  ]\n' "$rows"
    printf '}\n'
  } > "$manifest"
  log "manifest: $manifest"
  scp -q -o BatchMode=yes -o ConnectTimeout=20 "$manifest" \
      "$SSH_HOST:$REMOTE_STAGE/ztmetro-manifest.json" || return 1
  dev "push $REMOTE_STAGE_ABS/ztmetro-manifest.json /data/local/tmp/ztmetro-manifest.json" || return 1
}

# --------------------------------------------------------------------------
# STAGE 3 — apply
# --------------------------------------------------------------------------
merge_colon() { # existing item...
  local out=$1 item
  shift
  [ "$out" = "null" ] && out=""
  for item in "$@"; do
    case ":$out:" in
      *":$item:"*) ;;
      *) out="${out:+$out:}$item" ;;
    esac
  done
  printf '%s' "$out"
}

stage_apply() {
  local changed=0 settings_changed=0 i pkg line list_before list_after
  local aa_before aa_after nl_before nl_after ovl_dir ovl_files f base apk_tmp
  local ovl_pkgs="" svc lsn cur
  if [ "$DRY" = 1 ]; then
    log "DRY $ADB shell ls /data/adb/modules/ztmetro-overlays/system/product/overlay/*.apk"
    log "DRY for each module APK: $ADB pull <apk> /tmp/... && aapt2 dump badging  # package list"
    for pkg in $EXPECTED_OVERLAYS; do
      log "DRY $ADB shell cmd overlay enable --user 0 $pkg   # if not already [x]"
    done
    log "DRY $ADB shell cmd overlay set-priority org.ztmetro.overlay.framework highest"
    log "DRY $ADB shell settings put secure enabled_accessibility_services '<union of both services>'"
    log "DRY $ADB shell settings put secure accessibility_enabled 1"
    log "DRY $ADB shell cmd notification allow_listener <listener>   # x2"
    log "DRY $ADB shell settings put secure icon_blacklist '$ICON_BLACKLIST'"
    log "DRY $ADB shell appops set org.ztmetro.actioncentre WRITE_SETTINGS allow"
    log "DRY $ADB shell appops set org.ztmetro.actioncentre ACCESS_RESTRICTED_SETTINGS allow"
    log "DRY $ADB shell pm grant org.ztmetro.actioncentre android.permission.CAMERA"
    log "DRY $ADB shell pm grant org.ztmetro.actioncentre android.permission.READ_PHONE_STATE"
    log "DRY $ADB shell ime enable org.ztmetro.ime/.ZtMetroIme && $ADB shell ime set org.ztmetro.ime/.ZtMetroIme"
    log "DRY $ADB shell killall com.android.systemui   # only if an overlay changed"
    return 0
  fi

  resolve_remote_tools
  wait_for_boot || { log "device not booted"; return 1; }
  enable_adb_root || return 1

  # --- 1. discover the overlay packages from the module APKs ---------------
  ovl_dir=/data/adb/modules/ztmetro-overlays/system/product/overlay
  ovl_files=$(devq "shell ls $ovl_dir/*.apk" 2>/dev/null | tr -d '\r')
  if [ -z "$ovl_files" ]; then
    log "no overlay APKs under $ovl_dir; falling back to the expected package list"
  else
    apk_tmp=/tmp/ztmetro-conductor-overlays
    rssh "rm -rf $apk_tmp && mkdir -p $apk_tmp" >/dev/null 2>&1 || true
    for f in $ovl_files; do
      base=$(basename "$f")
      dev "pull '$f' $apk_tmp/$base" >/dev/null 2>&1 || log "WARN could not pull $f"
    done
    for f in $(rssh "ls $apk_tmp/*.apk 2>/dev/null" | tr -d '\r'); do
      pkg=$(rq "$PENV '$AAPT2' dump badging '$f' 2>/dev/null | head -1 | sed -n \"1s/^package: name='\([^']*\)'.*/\1/p\"")
      [ -n "$pkg" ] || { log "WARN no package in $f"; continue; }
      ovl_pkgs="$ovl_pkgs $pkg"
    done
  fi
  [ -n "$ovl_pkgs" ] || ovl_pkgs="$EXPECTED_OVERLAYS"
  log "overlay packages:$(printf '%s\n' $ovl_pkgs | sed 's/^/ /' | tr '\n' ' ')"

  # --- 2. enable overlays, log before/after --------------------------------
  list_before=$(devq "shell cmd overlay list" | tr -d '\r')
  for pkg in $ovl_pkgs; do
    line=$(printf '%s\n' "$list_before" | grep -F "$pkg" | head -1)
    if [ -z "$line" ]; then
      log "  WARN $pkg is not registered (module not mounted yet?)"
      continue
    fi
    if printf '%s' "$line" | grep -q '^\[x\]'; then
      log "  overlay already enabled: $pkg"
      continue
    fi
    log "  enabling overlay: $pkg"
    if ! dev "shell cmd overlay enable --user 0 $pkg" >/dev/null 2>&1; then
      log "  WARN enable failed for $pkg"
      continue
    fi
    changed=1
    [ "$pkg" = "org.ztos.overlay.settings" ] && settings_changed=1
  done
  if [ "$changed" -eq 1 ] || [ "$MODULE_CHANGED" -eq 1 ]; then
    changed=1
  fi
  list_after=$(devq "shell cmd overlay list" | tr -d '\r')
  log "overlay state after apply:"
  printf '%s\n' "$list_after" | grep -E '\[[ x]\] .*(ztmetro|ztos)' | sed 's/^/  /' | tee -a "$MAIN_LOG"

  dev "shell cmd overlay set-priority org.ztmetro.overlay.framework highest" >/dev/null 2>&1 || true
  log "framework overlay priority: $(devq 'shell cmd overlay dump org.ztmetro.overlay.framework' 2>/dev/null | grep -m1 'mPriority' | sed 's/^ *//')"

  # --- 3. accessibility services (union, never clobber) --------------------
  aa_before=$(devq "shell settings get secure enabled_accessibility_services" | tr -d '\r\n')
  aa_after=$(merge_colon "$aa_before" "${A11Y_SERVICES[@]}")
  log "accessibility services: before='$aa_before'"
  if [ "$aa_after" != "$aa_before" ]; then
    dev "shell settings put secure enabled_accessibility_services '$aa_after'" || return 1
    log "accessibility services: after='$aa_after'"
  else
    log "accessibility services: already contain both"
  fi
  dev "shell settings put secure accessibility_enabled 1" || return 1

  # Android 13+ sideload guard: allow restricted settings for the a11y app.
  dev "shell appops set org.ztmetro.actioncentre ACCESS_RESTRICTED_SETTINGS allow" \
    || log "WARN ACCESS_RESTRICTED_SETTINGS appop failed"

  # --- 4. notification listeners (both) ------------------------------------
  nl_before=$(devq "shell settings get secure enabled_notification_listeners" | tr -d '\r\n')
  log "notification listeners: before='$nl_before'"
  for lsn in "${NOTIF_LISTENERS[@]}"; do
    if printf '%s' "$nl_before" | grep -qF "$lsn"; then
      log "  already enabled: $lsn"
      continue
    fi
    if dev "shell cmd notification allow_listener '$lsn'" >/dev/null 2>&1; then
      log "  allow_listener ok: $lsn"
    else
      log "  WARN cmd notification allow_listener failed: $lsn"
    fi
  done
  nl_after=$(devq "shell settings get secure enabled_notification_listeners" | tr -d '\r\n')
  for lsn in "${NOTIF_LISTENERS[@]}"; do
    if ! printf '%s' "$nl_after" | grep -qF "$lsn"; then
      log "  listener missing after allow_listener; writing union via settings"
      nl_after=$(merge_colon "$nl_after" "${NOTIF_LISTENERS[@]}")
      dev "shell settings put secure enabled_notification_listeners '$nl_after'" || return 1
      break
    fi
  done
  nl_after=$(devq "shell settings get secure enabled_notification_listeners" | tr -d '\r\n')
  log "notification listeners: after='$nl_after'"

  # --- 5. icon blacklist ---------------------------------------------------
  cur=$(devq "shell settings get secure icon_blacklist" | tr -d '\r\n')
  if [ "$cur" = "$ICON_BLACKLIST" ]; then
    log "icon_blacklist already '$ICON_BLACKLIST'"
  else
    log "icon_blacklist: before='$cur' -> '$ICON_BLACKLIST'"
    dev "shell settings put secure icon_blacklist '$ICON_BLACKLIST'" || return 1
    log "icon_blacklist: after='$(devq "shell settings get secure icon_blacklist" | tr -d '\r\n')'"
  fi

  # --- 6. documented appops / runtime permissions --------------------------
  dev "shell appops set org.ztmetro.actioncentre WRITE_SETTINGS allow" \
    || log "WARN WRITE_SETTINGS appop failed"
  for cur in android.permission.CAMERA android.permission.READ_PHONE_STATE; do
    if dev "shell pm grant org.ztmetro.actioncentre $cur" >/dev/null 2>&1; then
      log "granted $cur"
    else
      log "WARN pm grant $cur failed (already granted or restricted)"
    fi
  done
  log "actioncentre appops:"
  devq "shell appops get org.ztmetro.actioncentre" 2>/dev/null | sed 's/^/  /' | tee -a "$MAIN_LOG" || true

  # --- 7. IME: enable + select the ztMetro keyboard ------------------------
  log "default IME before: $(devq 'shell settings get secure default_input_method' | tr -d '\r\n')"
  dev "shell ime enable org.ztmetro.ime/.ZtMetroIme" >/dev/null 2>&1 \
    || log "WARN ime enable failed"
  dev "shell ime set org.ztmetro.ime/.ZtMetroIme" >/dev/null 2>&1 \
    || log "WARN ime set failed"
  log "default IME after:  $(devq 'shell settings get secure default_input_method' | tr -d '\r\n')"

  # --- 8. restart SystemUI only when an overlay changed --------------------
  if [ "$changed" -eq 1 ]; then
    log "an overlay changed -> restarting SystemUI"
    dev "shell killall com.android.systemui" >/dev/null 2>&1 || log "WARN killall SystemUI failed"
    sleep 5
    log "SystemUI pid: $(devq 'shell pidof com.android.systemui' | tr -d '\r\n')"
  else
    log "no overlay change -> SystemUI left running (pid $(devq 'shell pidof com.android.systemui' | tr -d '\r\n'))"
  fi
  if [ "$settings_changed" -eq 1 ] || [ "$MODULE_CHANGED" -eq 1 ]; then
    dev "shell am force-stop com.android.settings" >/dev/null 2>&1 || true
  fi
  return 0
}

# --------------------------------------------------------------------------
# STAGE 4 — verify
# --------------------------------------------------------------------------
verify_builtin() { # writes the same TOTAL line shape as ux-verify
  local pass=0 fail=0 line list aa nl home pkg
  list=$(devq "shell cmd overlay list" | tr -d '\r')
  for pkg in $EXPECTED_OVERLAYS; do
    if printf '%s\n' "$list" | grep -qE "^\[x\] +$pkg\$"; then
      printf 'PASS  overlay enabled: %s\n' "$pkg"; pass=$((pass + 1))
    else
      printf 'FAIL  overlay enabled: %s\n' "$pkg"; fail=$((fail + 1))
    fi
  done
  aa=$(devq "shell settings get secure enabled_accessibility_services" | tr -d '\r\n')
  for pkg in "${A11Y_SERVICES[@]}"; do
    if printf '%s' "$aa" | grep -qF "$pkg"; then
      printf 'PASS  accessibility service: %s\n' "$pkg"; pass=$((pass + 1))
    else
      printf 'FAIL  accessibility service: %s\n' "$pkg"; fail=$((fail + 1))
    fi
  done
  nl=$(devq "shell settings get secure enabled_notification_listeners" | tr -d '\r\n')
  for pkg in "${NOTIF_LISTENERS[@]}"; do
    if printf '%s' "$nl" | grep -qF "$pkg"; then
      printf 'PASS  notification listener: %s\n' "$pkg"; pass=$((pass + 1))
    else
      printf 'FAIL  notification listener: %s\n' "$pkg"; fail=$((fail + 1))
    fi
  done
  home=$(devq "shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | tr -d '\r' | tail -1)
  if [ "$home" = "org.ztmetro.start/.StartActivity" ]; then
    printf 'PASS  HOME is %s\n' "$home"; pass=$((pass + 1))
  else
    printf 'FAIL  HOME is %s (expected org.ztmetro.start/.StartActivity)\n' "$home"; fail=$((fail + 1))
  fi
  printf '==================================================================\n'
  printf 'TOTAL  PASS %d  FAIL %d  SKIP 0  / %d checks\n' "$pass" "$fail" "$((pass + fail))"
}

capture_performance() { # baseline requested by the owner: PSS, gfx jank, pressure
  local out="$EVID/perf" p pss
  mkdir -p "$out"
  : > "$out/meminfo.tsv"
  for p in com.android.systemui com.android.settings org.ztmetro.actioncentre org.ztmetro.start; do
    if devq "shell dumpsys meminfo $p" > "$out/meminfo-$p.txt" 2>/dev/null; then
      pss=$(grep -m1 'TOTAL PSS' "$out/meminfo-$p.txt" | sed -E 's/.*TOTAL PSS: *([0-9]+).*/\1/')
      [ -n "$pss" ] || pss=$(grep -m1 '^ *TOTAL' "$out/meminfo-$p.txt" | grep -oE '[0-9]+' | head -1)
      printf '%s\t%s\n' "$p" "${pss:-?}" >> "$out/meminfo.tsv"
    else
      log "WARN could not read meminfo for $p"
    fi
  done
  devq "shell dumpsys gfxinfo com.android.settings" > "$out/gfxinfo-settings.txt" 2>/dev/null \
    || log "WARN could not read gfxinfo for com.android.settings"
  grep -E 'Janky frames|percentile' "$out/gfxinfo-settings.txt" > "$out/gfxinfo-settings-summary.txt" 2>/dev/null || true
  devq "shell cat /proc/pressure/memory" > "$out/pressure-memory.txt" 2>/dev/null \
    || log "WARN could not read /proc/pressure/memory"
  log "performance baseline:"
  sed 's/^/  /' "$out/meminfo.tsv" | tee -a "$MAIN_LOG"
  sed 's/^/  /' "$out/gfxinfo-settings-summary.txt" | tee -a "$MAIN_LOG" || true
  sed 's/^/  /' "$out/pressure-memory.txt" | tee -a "$MAIN_LOG" || true
  return 0
}

stage_verify() {
  local logf="$EVID/verify.log" rc=0 summary=""
  if [ "$DRY" = 1 ]; then
    log "DRY python3 scripts/ux-verify.py --quick --out $EVID/verify --timestamp run"
    log "DRY parse 'TOTAL  PASS n  FAIL n  SKIP n / n checks'"
    log "DRY $ADB shell dumpsys meminfo <systemui|settings|actioncentre|start>  # PSS baseline"
    log "DRY $ADB shell dumpsys gfxinfo com.android.settings  # Janky frames + percentiles"
    log "DRY $ADB shell cat /proc/pressure/memory"
    return 0
  fi
  if [ -f "$REPO/scripts/ux-verify.py" ]; then
    log "running ux-verify.py --quick"
    python3 "$REPO/scripts/ux-verify.py" --quick --out "$EVID/verify" --timestamp run 2>&1 | tee "$logf"
    rc=${PIPESTATUS[0]}
    summary=$(grep -E '^TOTAL ' "$logf" | tail -1 || true)
    if [ -z "$summary" ]; then
      log "ux-verify produced no TOTAL line (rc=$rc); using built-in checks"
    fi
  else
    log "scripts/ux-verify.py not found; using built-in checks"
  fi
  if [ -z "$summary" ]; then
    verify_builtin 2>&1 | tee "$EVID/verify-builtin.log"
    summary=$(grep -E '^TOTAL ' "$EVID/verify-builtin.log" | tail -1 || true)
    cp "$EVID/verify-builtin.log" "$logf"
  fi
  [ -n "$summary" ] || { log "verification produced no summary"; return 1; }
  printf '%s\n' "$summary" > "$VERIFY_SUMMARY"
  log "verify: $summary"
  if printf '%s' "$summary" | grep -qE 'FAIL [1-9]'; then
    log "verify reported FAIL checks (run continues; exit code will be 2)"
    VERIFY_FAILS=1
  fi
  return 0
}

# --------------------------------------------------------------------------
# STAGE 5 — factory (optional)
# --------------------------------------------------------------------------
factory_contrast_ok() { # profile.json -> prints OK or CONTRAST_FAIL
  python3 - "$1" <<'PY'
import json, sys
p = json.load(open(sys.argv[1], encoding="utf-8"))
checks = (p.get("safety") or {}).get("checks") or []
bad = [c for c in checks
       if not c.get("passes_4_5") and not (c.get("ratio_after") or 0) >= 4.5]
print("OK" if not bad else "CONTRAST_FAIL " + json.dumps(bad, ensure_ascii=False))
PY
}

factory_build_json_field() { # build.json key
  python3 - "$1" "$2" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    print(""); raise SystemExit(0)
v = d
for part in sys.argv[2].split("."):
    v = v.get(part) if isinstance(v, dict) else None
print("" if v is None else v)
PY
}

stage_factory() {
  local pkg path base prof local_prof logdir policy gen_rc build_json apk ovl
  local ok_apks="" ok_ovls="" ok_targets="" sha marker
  if [ "$DRY" = 1 ]; then
    for pkg in "${FACTORY_PACKAGES[@]}"; do
      log "DRY $ADB shell pm path $pkg && $ADB pull <apk> ~/$REMOTE_FACTORY/apk/$pkg.apk"
      log "DRY ssh $SSH_HOST 'cd ~/$SNAPDIR && bash scripts/theme-factory.sh analyse --apk ... --out .../$pkg/profile.json'"
      log "DRY contrast gate: skip $pkg if safety.checks has an unresolved <4.5:1 entry"
      log "DRY ssh $SSH_HOST 'bash scripts/theme-factory.sh generate --profile ... --out ... --force-package'"
      log "DRY build ztmetro-factory Magisk module, push+install, reboot, cmd overlay enable <overlay>"
    done
    return 0
  fi

  resolve_remote_tools
  enable_adb_root || return 1
  rssh "mkdir -p ~/$REMOTE_FACTORY/apk" || return 1
  : > "$EVID/factory/coverage.tsv"

  for pkg in "${FACTORY_PACKAGES[@]}"; do
    log "factory: $pkg"
    logdir="$EVID/factory/$pkg"
    mkdir -p "$logdir"
    path=$(devq "shell pm path $pkg" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)
    if [ -z "$path" ]; then
      log "  not installed -> skipped"
      printf '%s\t%s\t%s\t%s\t%s\n' "$pkg" "-" "-" "not-installed" "0" >> "$EVID/factory/coverage.tsv"
      continue
    fi
    base="$pkg.apk"
    if ! dev "pull '$path' ~/$REMOTE_FACTORY/apk/$base" >/dev/null 2>&1; then
      # system apps are world-readable, but fall back to su + exec-out
      if ! rssh "$ADB exec-out cat $path > ~/$REMOTE_FACTORY/apk/$base"; then
        log "  could not pull $path -> skipped"
        printf '%s\t%s\t%s\t%s\t%s\n' "$pkg" "-" "-" "pull-failed" "0" >> "$EVID/factory/coverage.tsv"
        continue
      fi
    fi

    log "  analyse"
    ssh -q "$SSH_HOST" "cd ~/$SNAPDIR && $PENV bash scripts/theme-factory.sh analyse --apk ~/$REMOTE_FACTORY/apk/$base --out ~/$REMOTE_FACTORY/$pkg/profile.json --sdk \$HOME/android-sdk --jdk \$HOME/android-jdk" 2>&1 | tee "$logdir/analyse.log"
    local arc
    arc=${PIPESTATUS[0]}
    if [ "$arc" -ne 0 ]; then
      log "  analyse failed (rc=$arc) -> skipped"
      printf '%s\t%s\t%s\t%s\t%s\n' "$pkg" "-" "-" "analyse-failed" "0" >> "$EVID/factory/coverage.tsv"
      continue
    fi
    local_prof="$logdir/profile.json"
    rq "cat ~/$REMOTE_FACTORY/$pkg/profile.json" > "$local_prof" || true
    [ -s "$local_prof" ] || { log "  profile copy failed -> skipped"; continue; }
    policy=$(factory_contrast_ok "$local_prof" 2>/dev/null || echo "CONTRAST_FAIL unreadable-profile")
    if [ "${policy%% *}" != "OK" ]; then
      log "  contrast gate refused: $policy"
      printf '%s\t%s\t%s\t%s\t%s\n' "$pkg" "-" "-" "contrast-fail" "0" >> "$EVID/factory/coverage.tsv"
      continue
    fi

    log "  generate"
    ssh -q "$SSH_HOST" "cd ~/$SNAPDIR && $PENV bash scripts/theme-factory.sh generate --profile ~/$REMOTE_FACTORY/$pkg/profile.json --out ~/$REMOTE_FACTORY/$pkg/overlay --force-package" 2>&1 | tee "$logdir/generate.log"
    gen_rc=${PIPESTATUS[0]}
    if [ "$gen_rc" -ne 0 ]; then
      log "  generate failed (rc=$gen_rc) -> skipped"
      printf '%s\t%s\t%s\t%s\t%s\n' "$pkg" "-" "-" "generate-failed" "0" >> "$EVID/factory/coverage.tsv"
      continue
    fi
    build_json="$logdir/build.json"
    rq "cat ~/$REMOTE_FACTORY/$pkg/overlay/build.json" > "$build_json" 2>/dev/null || true
    [ -s "$build_json" ] || { log "  no build.json -> skipped"; continue; }
    rssh "cat ~/$REMOTE_FACTORY/$pkg/overlay/mapping-table.md" > "$logdir/mapping-table.md" 2>/dev/null || true
    ovl=$(factory_build_json_field "$build_json" overlay_package)
    apk=$(factory_build_json_field "$build_json" apk)
    local vok
    vok=$(factory_build_json_field "$build_json" verify.ok)
    if [ "$vok" != "True" ] && [ "$vok" != "true" ]; then
      log "  generated RRO did not verify (verify.ok=$vok) -> skipped"
      printf '%s\t%s\t%s\t%s\t%s\n' "$pkg" "$ovl" "-" "verify-failed" "0" >> "$EVID/factory/coverage.tsv"
      continue
    fi
    log "  RRO: $ovl ($apk)"
    ok_apks="$ok_apks $apk"
    ok_ovls="$ok_ovls $ovl"
    ok_targets="$ok_targets $pkg"
    local cov
    cov=$(python3 - "$local_prof" <<'PY'
import json, sys
p = json.load(open(sys.argv[1], encoding="utf-8"))
print(json.dumps(p.get("coverage") or {}, sort_keys=True))
PY
)
    printf '%s\t%s\t%s\t%s\t%s\n' "$pkg" "$ovl" "$cov" "ok" "0" >> "$EVID/factory/coverage.tsv"
  done

  if [ -z "$ok_ovls" ]; then
    log "no factory overlay passed the gate; nothing to install"
    return 0
  fi

  # --- build and install the factory Magisk module -------------------------
  log "building ztmetro-factory module for:$ok_targets"
  rssh "rm -rf ~/$REMOTE_FACTORY/module && mkdir -p ~/$REMOTE_FACTORY/module/system/product/overlay" || return 1
  for apk in $ok_apks; do
    rssh "cp '$apk' ~/$REMOTE_FACTORY/module/system/product/overlay/" || return 1
  done
  rssh "printf 'id=ztmetro-factory\nname=ztMetro factory RROs\nversion=1.0.0\nversionCode=1\nauthor=ztmetro-conductor\ndescription=Theme-factory RROs for stock apps; generated by repo/scripts/ztmetro-conductor.sh.\n' > ~/$REMOTE_FACTORY/module/module.prop"
  rssh "cd ~/$REMOTE_FACTORY/module && rm -f ~/$REMOTE_FACTORY/ztmetro-factory-rros.zip && zip -qr -X ~/$REMOTE_FACTORY/ztmetro-factory-rros.zip ." || return 1
  sha=$(rq "sha256sum ~/$REMOTE_FACTORY/ztmetro-factory-rros.zip | cut -d' ' -f1" | tr -d '\r\n')
  fac_fp=$(rq "cd ~/$REMOTE_FACTORY/module && find . -type f | sort | xargs sha256sum | sha256sum | cut -d' ' -f1" | tr -d '\r\n')
  [ -n "$fac_fp" ] || { log "could not fingerprint the factory module payload"; return 1; }
  marker=$(devq "shell cat /data/adb/modules/ztmetro-factory/.conductor-sha256 2>/dev/null" 2>/dev/null | tr -d '\r\n')
  if [ "$marker" = "$fac_fp" ]; then
    log "factory module payload already at ${fac_fp:0:16}... -> no reboot"
  else
    dev "push ~/$REMOTE_FACTORY/ztmetro-factory-rros.zip /sdcard/ztmetro-factory-rros.zip" || return 1
    dev "shell magisk --install-module /sdcard/ztmetro-factory-rros.zip" || return 1
    log "factory module installed; rebooting once so PackageManager scans the new RROs"
    dev "reboot" || true
    wait_for_boot || { log "device did not reach boot_completed after factory reboot"; return 1; }
    enable_adb_root || return 1
    dev "shell \"echo $fac_fp > /data/adb/modules/ztmetro-factory/.conductor-sha256\"" || return 1
  fi

  local pkg ovl attempt en_out enabled
  set -- $ok_ovls
  for ovl in "$@"; do
    pkg=""
    log "enable factory overlay $ovl"
    en_out=$(devq "shell cmd overlay enable --user 0 $ovl" 2>&1)
    log "  cmd: ${en_out:-ok}"
    enabled=0
    attempt=0
    while [ "$attempt" -lt 10 ]; do
      if devq "shell cmd overlay list" | tr -d '\r' | grep -qE "^\[x\] +$ovl\$"; then
        enabled=1
        break
      fi
      sleep 1
      attempt=$((attempt + 1))
    done
    if [ "$enabled" -eq 1 ]; then log "  enabled"; else log "  WARN not listed as enabled after 10s"; fi
  done
  # restart the affected apps so they re-read resources
  set -- $ok_targets
  for pkg in "$@"; do
    dev "shell am force-stop $pkg" >/dev/null 2>&1 || true
  done
  log "factory coverage:"
  sed 's/^/  /' "$EVID/factory/coverage.tsv" | tee -a "$MAIN_LOG"
  return 0
}

# --------------------------------------------------------------------------
# STAGE 6 — report
# --------------------------------------------------------------------------
stage_report() {
  local finished duration report="$EVID/report.md"
  if [ "$DRY" = 1 ]; then
    log "DRY write $report and $EVID/run.json from stages.tsv/artifacts.tsv/verify-summary.txt"
    return 0
  fi
  finished=$(date +%s)
  duration=$((finished - STARTED_EPOCH))
  python3 - "$EVID" "$STARTED_EPOCH" "$finished" "$SSH_HOST" "$RUN_COMMAND" "$SNAP_COMMIT" "$SNAP_DIRTY" <<'PY'
import json, os, sys, datetime as dt

evid, t0, t1, host, command = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5]
snap_commit, snap_dirty = sys.argv[6], sys.argv[7]

def rows(path):
    out = []
    if os.path.isfile(path):
        for line in open(path, encoding="utf-8"):
            line = line.rstrip("\n")
            if line:
                out.append(line.split("\t"))
    return out

stages = rows(os.path.join(evid, "stages.tsv"))
artifacts = rows(os.path.join(evid, "artifacts.tsv"))
factory = rows(os.path.join(evid, "factory", "coverage.tsv"))
verify = ""
vp = os.path.join(evid, "verify-summary.txt")
if os.path.isfile(vp):
    verify = open(vp, encoding="utf-8").read().strip()

started = dt.datetime.fromtimestamp(t0).astimezone()
finished = dt.datetime.fromtimestamp(t1).astimezone()

def human(n):
    n = int(n or 0)
    for unit in ("B", "KiB", "MiB"):
        if n < 1024 or unit == "MiB":
            return f"{n} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024.0
    return f"{n} B"

lines = []
lines.append(f"# ztMetro conductor run {os.path.basename(evid)}")
lines.append("")
lines.append(f"- **command:** `{command}`")
lines.append(f"- **snapshot:** `{snap_commit}` (git archive; {snap_dirty} uncommitted path(s) in the working tree excluded)")
lines.append("- **root:** `adb root` (adbd uid=0, SELinux `u:r:su:s0`); Magisk `su` for adb shell is not used")
lines.append(f"- **started:** {started.isoformat(timespec='seconds')}")
lines.append(f"- **finished:** {finished.isoformat(timespec='seconds')}")
lines.append(f"- **duration:** {t1 - t0} s")
lines.append(f"- **build host:** `{host}` (`~/ztux/conductor/snapshots/{snap_commit[:12]}`, `~/android-root/conductor`)")
lines.append(f"- **logs:** `{os.path.relpath(evid)}/conductor.log`, per-stage `build/*.log`")
lines.append("")
lines.append("## Stages")
lines.append("")
lines.append("| Stage | Status | Duration | Notes |")
lines.append("|---|---|---:|---|")
for r in stages:
    r += [""] * (4 - len(r))
    lines.append(f"| {r[0]} | {r[1]} | {r[2]} s | {r[3]} |")
lines.append("")
lines.append("## Artifacts")
lines.append("")
lines.append("| Artifact | Size | SHA-256 |")
lines.append("|---|---:|---|")
for r in artifacts:
    r += [""] * (4 - len(r))
    lines.append(f"| {r[0]} | {r[3]} B ({human(r[3])}) | `{r[2]}` |")
lines.append("")
lines.append("## Verify")
lines.append("")
if verify:
    lines.append("```")
    lines.append(verify)
    lines.append("```")
    vdir = os.path.join(evid, "verify")
    if os.path.isdir(vdir):
        for root, _, files in os.walk(vdir):
            for f in files:
                if f == "report.md":
                    lines.append(f"\nDetailed report: `{os.path.relpath(os.path.join(root, f))}`")
else:
    lines.append("_No verify summary was produced._")
lines.append("")
if factory:
    lines.append("## Theme factory")
    lines.append("")
    lines.append("| Package | Overlay | Coverage | Status |")
    lines.append("|---|---|---|---|")
    for r in factory:
        r += [""] * (5 - len(r))
        lines.append(f"| {r[0]} | `{r[1]}` | `{r[2]}` | {r[3]} |")
    lines.append("")
perf_dir = os.path.join(evid, "perf")
if os.path.isdir(perf_dir):
    lines.append("## Performance appendix")
    lines.append("")
    lines.append("Captured after the verify stage on the same boot (owner request: lag baseline).")
    lines.append("")
    mem = []
    mempath = os.path.join(perf_dir, "meminfo.tsv")
    if os.path.isfile(mempath):
        for line in open(mempath, encoding="utf-8"):
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                mem.append(parts)
    if mem:
        lines.append("| Process | TOTAL PSS (KB) | Raw dump |")
        lines.append("|---|---:|---|")
        for p, pss in mem:
            lines.append(f"| `{p}` | {pss} | `perf/meminfo-{p}.txt` |")
        lines.append("")
    gfxp = os.path.join(perf_dir, "gfxinfo-settings-summary.txt")
    if os.path.isfile(gfxp):
        gfx = open(gfxp, encoding="utf-8").read().strip()
        if gfx:
            lines.append("**`dumpsys gfxinfo com.android.settings`**")
            lines.append("")
            lines.append("```")
            lines.append(gfx)
            lines.append("```")
            lines.append("")
    pp = os.path.join(perf_dir, "pressure-memory.txt")
    if os.path.isfile(pp):
        pressure = open(pp, encoding="utf-8").read().strip()
        lines.append("**`/proc/pressure/memory`**")
        lines.append("")
        lines.append("```")
        lines.append(pressure or "(empty)")
        lines.append("```")
        lines.append("")
lines.append("## Reproduce from a clean LineageOS install")
lines.append("")
lines.append("```sh")
lines.append("# 0. one-time platform (manual, GUI steps are unavoidable):")
lines.append("#    flash LineageOS-for-microG 23.2 for Mi8917 (recovery -> format data -> adb -d sideload),")
lines.append("#    install Magisk (Zygisk on) and LSPosed/Vector, authorise adb, keep the phone unlocked.")
lines.append("# 1. on the Mac, from the repo root:")
lines.append(command)
lines.append("# 2. stage-only re-runs (all idempotent):")
lines.append("./repo/scripts/ztmetro-conductor.sh --skip-build --skip-publish --skip-apply   # verify+report only")
lines.append("./repo/scripts/ztmetro-conductor.sh --skip-build --skip-publish --allow-deploy   # apply+verify (may reboot)")
lines.append("```")
lines.append("")
lines.append("The conductor is the same state machine for every run: a changed module is")
lines.append("reinstalled and followed by a normal Android reboot plus a `sys.boot_completed`")
lines.append("wait; unchanged artifacts are detected by sha256 (module marker) and by comparing")
lines.append("the installed APK sha256 + signing certificate, so a second run is a no-op.")
lines.append("")
open(os.path.join(evid, "report.md"), "w", encoding="utf-8").write("\n".join(lines) + "\n")

run = {
    "schema": "ztos.conductor.run/1",
    "run_id": os.path.basename(evid),
    "command": command,
    "snapshot": {"commit": snap_commit, "dirty_paths_excluded": int(snap_dirty or 0)},
    "started": started.isoformat(timespec="seconds"),
    "finished": finished.isoformat(timespec="seconds"),
    "duration_s": t1 - t0,
    "stages": [{"stage": r[0], "status": r[1], "duration_s": int(r[2] or 0),
                "notes": r[3] if len(r) > 3 else ""} for r in stages],
    "artifacts": [{"artifact": r[0], "path": r[1], "sha256": r[2],
                   "size": int(r[3] or 0)} for r in artifacts],
    "verify_summary": verify,
    "factory": [{"package": r[0], "overlay": r[1], "coverage": r[2],
                 "status": r[3]} for r in factory],
}
open(os.path.join(evid, "run.json"), "w", encoding="utf-8").write(
    json.dumps(run, indent=2, ensure_ascii=False) + "\n")
print(os.path.join(evid, "report.md"))
PY
  if [ $? -ne 0 ]; then
    log "report generation failed"
    return 1
  fi
  log "report: $report"
  log "run.json: $EVID/run.json"
  return 0
}

# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------
OVERALL=0
VERIFY_FAILS=0
MODULE_CHANGED=0

log "ztMetro conductor $RUN_TS  (dry-run=$DRY, factory=$WITH_FACTORY)"
if [ "$ALLOW_DEPLOY" -eq 1 ] && [ "$SKIP_BUILD" -eq 1 ]; then
  log "WARNING: --allow-deploy: deploying artifacts from an earlier snapshot; a changed module may reboot the phone"
fi
log "repo:   $REPO"
log "snapshot: $SNAP_COMMIT ($SSH_HOST:~/$SNAPDIR)"
log "state:    $SSH_HOST:~/$REMOTE_STATE (stable keystores)"
log "stage:  $SSH_HOST:~/$REMOTE_STAGE"
log "logs:   $EVID"

# The one place where the conductor is intentionally sequential: each stage
# consumes the previous one's output, and the phone has a single owner.
if [ "$SKIP_BUILD" = 1 ]; then
  stage_skip build
else
  stage_begin build
  if stage_build; then stage_end ok; else stage_end failed "see build logs"; OVERALL=1; fi
fi

if [ "$OVERALL" -eq 0 ]; then
  if [ "$SKIP_PUBLISH" = 1 ]; then
    stage_skip publish
  else
    stage_begin publish
    if stage_publish; then stage_end ok; else stage_end failed "see publish logs"; OVERALL=1; fi
  fi
fi

if [ "$OVERALL" -eq 0 ]; then
  if [ "$SKIP_APPLY" = 1 ]; then
    stage_skip apply
  else
    stage_begin apply
    if stage_apply; then stage_end ok; else stage_end failed "see conductor.log"; OVERALL=1; fi
  fi
fi

if [ "$OVERALL" -eq 0 ]; then
  if [ "$SKIP_VERIFY" = 1 ]; then
    stage_skip verify
  else
    stage_begin verify
    if stage_verify; then
      if [ "$VERIFY_FAILS" -eq 1 ]; then
        stage_end ok "PASS/FAIL summary in verify.log (FAILs present)"
      else
        stage_end ok "PASS/FAIL summary in verify.log"
      fi
    else
      stage_end failed "verification did not produce a summary"
      OVERALL=1
    fi
  fi
fi

if [ "$OVERALL" -eq 0 ] && [ "$WITH_FACTORY" -eq 1 ]; then
  if [ "$SKIP_FACTORY" = 1 ]; then
    stage_skip factory
  else
    stage_begin factory
    if stage_factory; then stage_end ok; else stage_end failed "factory gate/log"; OVERALL=1; fi
  fi
elif [ "$WITH_FACTORY" -eq 0 ]; then
  printf '%s\tskipped\t0\tnot requested (--with-factory)\n' factory >> "$STAGES_TSV"
  log "stage factory not requested (use --with-factory)"
fi

if [ "$SKIP_REPORT" = 1 ]; then
  stage_skip report
else
  stage_begin report
  if stage_report; then stage_end ok; else stage_end failed "could not write report"; OVERALL=1; fi
fi

total=$(( $(date +%s) - STARTED_EPOCH ))
log "done: total ${total}s, exit=$([ "$OVERALL" -ne 0 ] && echo 1 || { [ "$VERIFY_FAILS" -eq 1 ] && echo 2 || echo 0; })"
if [ "$OVERALL" -ne 0 ]; then
  exit 1
fi
[ "$VERIFY_FAILS" -eq 1 ] && exit 2
exit 0
