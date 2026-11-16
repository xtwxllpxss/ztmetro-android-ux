#!/usr/bin/env bash
# Pass-2 performance before/after protocol (Redmi Note 5A, 2 GB, 720p).
#
#   cd repo/evidence/perf-pass2
#   ./protocol.sh run before            # on the CURRENT (old) APKs
#   # install the pass-2 APKs, reboot if props changed, wait 30 min of normal use
#   ./protocol.sh run after
#   ./protocol.sh compare before after  # prints PASS/FAIL against the thresholds
#
# The device is reached through adb. When the device is attached to the build
# host instead of this machine, put the ssh prefix in ADB_PREFIX (no argument may
# contain a space):
#
#   ADB_PREFIX="ssh hermes-ubuntu /home/denis/android-sdk/platform-tools/adb" ./protocol.sh run before
#
# Every label is measured 3 times. The summary uses the MEDIAN of the three
# runs, never the best one. Raw dumps stay in <label>/run<N>/raw/ so a surprising
# number can be re-checked without re-running the phone.
set -euo pipefail

ADB_PREFIX=${ADB_PREFIX:-adb}
read -r -a ADB <<<"$ADB_PREFIX"
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

START=org.ztmetro.start
AC=org.ztmetro.actioncentre
SETTINGS=com.android.settings
SYSUI=com.android.systemui

RUNS=3

say() { printf '==> %s\n' "$*"; }
die() { printf 'protocol: %s\n' "$*" >&2; exit 1; }

adb() { "${ADB[@]}" "$@"; }

# ---------------------------------------------------------------- primitives

pss_kb() { # package -> total PSS in kB, empty when the process is not running
    adb shell dumpsys meminfo "$1" 2>/dev/null | tr -d '\r' | awk '
        $1=="TOTAL" && $2 ~ /^[0-9]+$/ { print $2; exit }
        /TOTAL PSS:/ { for (i = 1; i <= NF; i++) if ($i == "PSS:") { print $(i+1); exit } }'
}

janky_pct() { # package -> "12.34"
    adb shell dumpsys gfxinfo "$1" 2>/dev/null | tr -d '\r' \
        | grep -m1 "Janky frames" | sed -n 's/.*(\([0-9.]*\)%).*/\1/p'
}

percentile() { # package 90th|95th|99th -> ms
    adb shell dumpsys gfxinfo "$1" 2>/dev/null | tr -d '\r' \
        | grep -m1 "$2 percentile" | sed -n 's/.*: *\([0-9]*\)ms.*/\1/p' | head -1
}

psi_parse() { # file field (some avg60 | full avg60 ...)
    awk -v want="$2" '{
        for (i = 1; i <= NF; i++) {
            if ($i ~ ("^" want "=")) { sub("^" want "=", "", $i); print $i; exit }
        }
    }' "$1" 2>/dev/null
}

total_time() { # intent args... -> TotalTime ms
    adb shell am start -W "$@" 2>/dev/null | tr -d '\r' \
        | sed -n 's/^TotalTime: *//p' | head -1
}

wait_time() {
    adb shell am start -W "$@" 2>/dev/null | tr -d '\r' \
        | sed -n 's/^WaitTime: *//p' | head -1
}

median() { # numbers... -> median (integer or one decimal)
    tr ' ' '\n' | grep -E '^[0-9]+(\.[0-9]+)?$' | sort -n \
        | awk '{ a[NR] = $1 } END {
              if (NR == 0) { print "NA"; exit }
              if (NR % 2) { printf "%s", a[(NR + 1) / 2] }
              else { printf "%.1f", (a[NR / 2] + a[NR / 2 + 1]) / 2 }
          }'
}

# ---------------------------------------------------------------- sequences

settings_open() { # cold open + 3 swipes, used for both gfxinfo and TotalTime
    adb shell dumpsys gfxinfo "$SETTINGS" reset >/dev/null 2>&1 || true
    adb shell am force-stop "$SETTINGS"
    total_time -n "$SETTINGS/.Settings" >"$1/settings_total_ms.txt"
    sleep 1
    for _ in 1 2 3; do adb shell input swipe 360 1000 360 300 250; done
    sleep 2
}

home_swipe() {
    adb shell dumpsys gfxinfo "$START" reset >/dev/null 2>&1 || true
    adb shell am force-stop "$START"
    adb shell input keyevent KEYCODE_HOME
    sleep 2
    adb shell input swipe 600 700 100 700 250
    sleep 2
}

collect_raw() { # dir
    local dir=$1
    adb shell dumpsys meminfo "$SYSUI"      >"$dir/mem-sysui.txt" 2>&1 || true
    adb shell dumpsys meminfo "$SETTINGS"   >"$dir/mem-settings.txt" 2>&1 || true
    adb shell dumpsys meminfo "$AC"         >"$dir/mem-actioncentre.txt" 2>&1 || true
    adb shell dumpsys meminfo "$START"      >"$dir/mem-start.txt" 2>&1 || true
    adb shell dumpsys meminfo surfaceflinger >"$dir/mem-surfaceflinger.txt" 2>&1 || true
    adb shell dumpsys gfxinfo "$SETTINGS" framestats >"$dir/gfx-settings-framestats.txt" 2>&1 || true
    adb shell dumpsys gfxinfo "$SETTINGS"   >"$dir/gfx-settings.txt" 2>&1 || true
    adb shell dumpsys gfxinfo "$START"      >"$dir/gfx-start.txt" 2>&1 || true
    adb shell dumpsys activity processes    >"$dir/processes.txt" 2>&1 || true
    adb shell dumpsys activity oom          >"$dir/oom.txt" 2>&1 || true
    adb shell cat /proc/pressure/memory     >"$dir/psi-memory.txt" 2>&1 || true
    adb shell cat /proc/pressure/cpu        >"$dir/psi-cpu.txt" 2>&1 || true
    adb shell cat /proc/swaps               >"$dir/swaps.txt" 2>&1 || true
    adb shell zramctl                       >"$dir/zramctl.txt" 2>&1 || true
    adb shell logcat -d -s StartPerf -s ztmetro-header -s StatusStrip -s ShadeBridge \
        >"$dir/logcat-modules.txt" 2>&1 || true
}

one_run() { # label run dir
    local label=$1 run=$2 dir=$3
    mkdir -p "$dir/raw"

    say "$label run $run/$RUNS: idle state"
    adb shell cat /sys/class/thermal/thermal_zone0/temp >"$dir/thermal.txt" 2>&1 || true
    adb shell cat /proc/pressure/memory >"$dir/psi-idle.txt" 2>&1 || true
    adb shell dumpsys meminfo -a | sed -n '1,40p' >"$dir/raw/meminfo-total.txt" 2>&1 || true
    say "$label run $run/$RUNS: Settings cold open + 3 swipes"
    settings_open "$dir"

    say "$label run $run/$RUNS: HOME (force-stop, HOME, one swipe)"
    home_swipe

    say "$label run $run/$RUNS: Action Centre cold start"
    adb shell am force-stop "$AC"
    total_time -n "$AC/.ActionCentreActivity" >"$dir/actioncentre_total_ms.txt"
    sleep 1

    say "$label run $run/$RUNS: Start cold start"
    adb shell am force-stop "$START"
    total_time -a android.intent.action.MAIN -c android.intent.category.HOME \
        >"$dir/start_total_ms.txt"
    sleep 1

    say "$label run $run/$RUNS: PSS + raw dumps"
    collect_raw "$dir/raw"

    # ---- the numbers this run contributes ----
    {
        printf 'ps_psi_full_avg60\t%s\n' "$(psi_parse "$dir/psi-idle.txt" "full avg60")"
        printf 'ps_psi_some_avg60\t%s\n' "$(psi_parse "$dir/psi-idle.txt" "some avg60")"
        printf 'pss_sysui_kb\t%s\n'  "$(pss_kb "$SYSUI")"
        printf 'pss_settings_kb\t%s\n' "$(pss_kb "$SETTINGS")"
        printf 'pss_actioncentre_kb\t%s\n' "$(pss_kb "$AC")"
        printf 'pss_start_kb\t%s\n' "$(pss_kb "$START")"
        printf 'pss_surfaceflinger_kb\t%s\n' "$(pss_kb surfaceflinger)"
        printf 'settings_janky_pct\t%s\n' "$(janky_pct "$SETTINGS")"
        printf 'settings_p50_ms\t%s\n' "$(percentile "$SETTINGS" 50th)"
        printf 'settings_p90_ms\t%s\n' "$(percentile "$SETTINGS" 90th)"
        printf 'settings_p95_ms\t%s\n' "$(percentile "$SETTINGS" 95th)"
        printf 'settings_p99_ms\t%s\n' "$(percentile "$SETTINGS" 99th)"
        printf 'start_janky_pct\t%s\n' "$(janky_pct "$START")"
        printf 'start_p90_ms\t%s\n' "$(percentile "$START" 90th)"
        printf 'settings_cold_ms\t%s\n' "$(cat "$dir/settings_total_ms.txt" 2>/dev/null || echo NA)"
        printf 'actioncentre_cold_ms\t%s\n' "$(cat "$dir/actioncentre_total_ms.txt" 2>/dev/null || echo NA)"
        printf 'start_cold_ms\t%s\n' "$(cat "$dir/start_total_ms.txt" 2>/dev/null || echo NA)"
    } >"$dir/metrics.tsv"

    say "$label run $run/$RUNS: done"
}

summarize() { # label
    local label=$1
    local out="$HERE/$label/median.tsv"
    : >"$out"
    # union of keys, in the order of the first run
    cut -f1 "$HERE/$label/run1/metrics.tsv" | while IFS= read -r key; do
        local values
        values=$(for r in $(seq 1 "$RUNS"); do
                     awk -F'\t' -v k="$key" '$1 == k { print $2 }' \
                         "$HERE/$label/run$r/metrics.tsv" 2>/dev/null || true
                 done | tr '\n' ' ')
        printf '%s\t%s\n' "$key" "$(printf '%s' "$values" | median)" >>"$out"
    done
    say "median of $RUNS runs written to $out"
}

# ---------------------------------------------------------------- compare

val() { # file key -> value
    awk -F'\t' -v k="$2" '$1 == k { print $2; exit }' "$1" 2>/dev/null
}

check() { # name actual op threshold verdict
    local name=$1 actual=$2 op=$3 threshold=$4
    if [ -z "$actual" ] || [ "$actual" = "NA" ]; then
        printf 'SKIP  %-34s no value\n' "$name"
        return 0
    fi
    if awk -v a="$actual" -v t="$threshold" -v op="$op" 'BEGIN{
           if (op == "<")  exit !(a <  t)
           if (op == "<=") exit !(a <= t)
           if (op == ">")  exit !(a >  t)
           if (op == ">=") exit !(a >= t)
           exit 0
       }'; then
        printf 'PASS  %-34s %s %s %s\n' "$name" "$actual" "$op" "$threshold"
    else
        printf 'FAIL  %-34s %s (target %s %s)\n' "$name" "$actual" "$op" "$threshold"
    fi
}

compare() { # before after
    local before="$HERE/$1/median.tsv" after="$HERE/$2/median.tsv"
    [ -f "$before" ] || die "missing $before (run ./protocol.sh run $1)"
    [ -f "$after" ]  || die "missing $after (run ./protocol.sh run $2)"

    local sb sa cb ca
    sb=$(val "$after" pss_start_kb);  sa=$(val "$after" pss_actioncentre_kb)
    cb=$(val "$after" pss_settings_kb)
    # zt_sum = the three surfaces this pass owns/changes; SystemUI, Magisk and
    # LSPosed are not ours and are reported but not gated.
    local zt_mb
    zt_mb=$(awk -v a="$sb" -v b="$sa" -v c="$cb" 'BEGIN{
                if (a == "" || b == "" || c == "") { print "NA"; exit }
                printf "%.1f", (a + b + c) / 1024 }')

    echo "metric                              before        after"
    for key in pss_sysui_kb pss_settings_kb pss_actioncentre_kb pss_start_kb \
               settings_janky_pct settings_p50_ms settings_p90_ms settings_p95_ms \
               settings_p99_ms start_janky_pct start_p90_ms settings_cold_ms \
               actioncentre_cold_ms start_cold_ms ps_psi_full_avg60; do
        printf '%-34s %-13s %s\n' "$key" "$(val "$before" "$key")" "$(val "$after" "$key")"
    done
    printf '%-34s %-13s %s\n' 'zt_sum_mb (start+ac+settings)' '-' "$zt_mb"
    echo
    echo "acceptance (after, median of $RUNS):"
    check "Settings 90th percentile"  "$(val "$after" settings_p90_ms)"  "<" 40
    check "Settings janky frames"     "$(val "$after" settings_janky_pct)" "<" 8
    check "Settings p95"              "$(val "$after" settings_p95_ms)"  "<" 33
    check "Start janky frames"        "$(val "$after" start_janky_pct)"  "<" 5
    check "zt PSS sum (MB)"           "$zt_mb" "<" 90
    check "Start+AC PSS (kB)"         "$(awk -v a="$sb" -v b="$sa" 'BEGIN{ if(a==""||b=="") print "NA"; else print a+b }')" "<" 62464
    check "PSI memory full avg60"     "$(val "$after" ps_psi_full_avg60)" "<" 5
    check "Settings cold start (ms)"  "$(val "$after" settings_cold_ms)" "<" 1100
    check "Start cold start (ms)"     "$(val "$after" start_cold_ms)"    "<" 800
    check "ActionCentre cold (ms)"    "$(val "$after" actioncentre_cold_ms)" "<" 500

    echo
    echo "relative change (after vs before, negative is better):"
    for key in pss_settings_kb pss_actioncentre_kb pss_start_kb settings_janky_pct \
               settings_p90_ms settings_cold_ms start_cold_ms; do
        awk -v k="$key" -v b="$(val "$before" "$key")" -v a="$(val "$after" "$key")" \
            'BEGIN{
                if (b == "" || a == "" || b == 0) { printf "%-34s NA\n", k; exit }
                printf "%-34s %+.1f %%\n", k, (a - b) * 100.0 / b }'
    done
}

# ---------------------------------------------------------------- main

usage() {
    cat <<EOF
usage: $(basename "$0") run <label>       measure 3 runs into ./<label>/run{1,2,3}
       $(basename "$0") summarize <label> medians into ./<label>/median.tsv
       $(basename "$0") compare <before> <after>
env:   ADB_PREFIX  adb command prefix (default: adb)
EOF
}

main() {
    case "${1:-}" in
        run)
            [ -n "${2:-}" ] || die "run needs a label"
            adb get-state >/dev/null 2>&1 || die "no device (set ADB_PREFIX)"
            for r in $(seq 1 "$RUNS"); do
                one_run "$2" "$r" "$HERE/$2/run$r"
            done
            summarize "$2"
            ;;
        summarize)
            [ -n "${2:-}" ] || die "summarize needs a label"
            summarize "$2"
            ;;
        compare)
            [ -n "${2:-}" ] && [ -n "${3:-}" ] || die "compare needs before and after labels"
            compare "$2" "$3"
            ;;
        *)
            usage
            exit 2
            ;;
    esac
}

main "$@"
