#!/bin/bash
# Assertions over the harness logs (PASS/FAIL lines, exit 1 on any FAIL).
set -u
HERE=$(cd -- "$(dirname -- "$0")" && pwd)
EVID=$(cd -- "$HERE/.." && pwd)
pass=0 fail=0
ok()   { printf 'PASS  %s\n' "$*"; pass=$((pass + 1)); }
bad()  { printf 'FAIL  %s\n' "$*"; fail=$((fail + 1)); }
has()  { grep -qF -- "$2" "$EVID/$1" && ok "$1: $2" || bad "$1: missing '$2'"; }
not()  { grep -qF -- "$2" "$EVID/$1" && bad "$1: unexpected '$2'" || ok "$1: no '$2'"; }

has run1-post-reinstall.log 'apply: accessibility=2/2 enabled (was 0/2)'
has run1-post-reinstall.log 'apply: a11y_enabled=1'
has run1-post-reinstall.log 'apply: listeners=2/2 enabled (changed)'
has run1-post-reinstall.log 'apply: icon_blacklist=clock,battery,wifi,mobile,stacked_mobile,rotate,headset'
has run1-post-reinstall.log 'apply: home=ok (org.ztmetro.start/.StartActivity'
has run1-post-reinstall.log 'apply: ime=org.ztmetro.ime/.ZtMetroIme'
has run1-post-reinstall.log 'apply: appops=WRITE_SETTINGS,READ_PHONE_STATE,ACCESS_RESTRICTED_SETTINGS allow'
has run1-post-reinstall.log 'apply: overlays=10 enabled'
has run1-post-reinstall.log 'apply: done changes='
has run2-after-wipe.log 'apply: accessibility=2/2 enabled (was 0/2)'
has run2-after-wipe.log 'apply: overlays=10 enabled'
has run3-noop.log 'apply: done changes=0 warnings=0'
has run3-noop.log 'apply: accessibility=2/2 enabled (unchanged)'
has run3-noop.log 'apply: systemui=left running (no overlay change)'
has run4-new-service.log 'apply: accessibility=3/3 enabled'
has service-sh.log 'service: boot_completed=1 after'
has service-sh.log 'apply: overlays=10 enabled'
has service-sh.log 'apply: accessibility=2/2 enabled (was 0/2)'
has service-sh.log 'service: apply finished rc=0'
grep -qE 'apply: accessibility=2/2 enabled \(was 0/2\)' "$EVID/service-sh.log"   && ok "service.sh ran the applier after the boot wait" || bad "service.sh did not apply"
not run1-post-reinstall.log 'WARN'
# every line must be timestamped
if awk '!/^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} apply: /{print; bad=1} END{exit bad}' \
   "$EVID/run1-post-reinstall.log"; then ok "all log lines timestamped"; else bad "untimestamped line in run1 log"; fi
# idempotence: run3 must not rewrite any secure setting
if grep -q 'was ' "$EVID/run3-noop.log"; then bad "run3 rewrote a setting"; else ok "run3 wrote nothing"; fi
echo "TOTAL PASS=$pass FAIL=$fail"
[ "$fail" -eq 0 ]
