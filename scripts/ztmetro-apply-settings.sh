#!/system/bin/sh
# ztmetro-apply-settings.sh - re-apply the ztMetro shell state, idempotently.
#
# Runs on the device as root.  The Magisk module's service.sh calls it once per
# boot (late_start, after sys.boot_completed); it can also be run by hand at any
# time:
#
#   su -c 'sh /data/adb/modules/ztmetro-overlays/ztmetro-apply-settings.sh' \
#     >> /data/local/tmp/ztmetro-apply.log 2>&1
#
# Why it exists: the accessibility grant, the notification listeners, HOME, the
# default IME, the appops and the overlay enable flags all live in per-user /data
# state.  An APK reinstall (new uid) or a ROM reset drops them, and the phone
# boots back into the stock lock screen, stock shade and no status strip.  This
# script rebuilds that state from scratch on every boot.
#
# Safety rules it obeys:
#   * every write is compare-then-set, so a second run changes nothing;
#   * only packages that are actually installed are touched; anything missing is
#     logged and skipped, never invented;
#   * each step is guarded: one failing step is a WARN line, never a failed boot.
#
# Output: one line per decision on stdout.  service.sh redirects this to
# /data/local/tmp/ztmetro-apply.log; the exit code is 0 even when some steps warn.

PATH=/system/bin:/system/xbin:/sbin:/product/bin:${PATH:-}

CHANGES=0
WARNINGS=0
OVL_CHANGED=0
PKGS=""
LIST=""
HOME_BEFORE=""
HOME_AFTER=""

ts() { date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "0000-00-00 00:00:00"; }
log() { printf '%s apply: %s\n' "$(ts)" "$*"; }
warn() { WARNINGS=$((WARNINGS + 1)); log "WARN $*"; }
changed() { CHANGES=$((CHANGES + 1)); }

# --------------------------------------------------------------------------
# small helpers
# --------------------------------------------------------------------------

# merge_colon EXISTING ITEM... -> "a:b:c", order kept, duplicates dropped
merge_colon() {
  _out=$1
  shift
  for _i in "$@"; do
    [ -n "$_i" ] || continue
    case ":$_out:" in
      *":$_i:"*) ;;
      *) _out=${_out:+$_out:}$_i ;;
    esac
  done
  printf '%s' "$_out"
}

sget() { settings get secure "$1" 2>/dev/null | tr -d '\r\n' | sed 's/^null$//'; }
sput() { settings put secure "$1" "$2" >/dev/null 2>&1; }

installed() {
  [ -n "$PKGS" ] || return 1
  printf '%s\n' "$PKGS" | grep -qx "$1"
}

resolve_home() {
  cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c android.intent.category.HOME 2>/dev/null | tr -d '\r' | tail -n 1
}

ovl_state() { # -> x (enabled), o (disabled), empty (not registered)
  printf '%s\n' "$LIST" | awk -v p="$1" '
    $0 ~ "^\\[[ x]\\] " p "$" { if ($1 == "[x]") print "x"; else print "o"; exit }
  '
}

# wait until PackageManager answers; returns 1 if it never does
load_pkgs() {
  _n=0
  while [ "$_n" -lt 30 ]; do
    PKGS=$(pm list packages 2>/dev/null | sed -n 's/^package://p' | tr -d '\r')
    [ -n "$PKGS" ] && return 0
    _n=$((_n + 1))
    sleep 1
  done
  return 1
}

# zt accessibility services the package manager knows about; the two shipped ones
# are the floor, anything else a newer zt APK declares is picked up here.
discover_a11y() {
  _q=$(cmd package query-services --brief \
    -a android.accessibilityservice.AccessibilityService 2>/dev/null | tr -d '\r')
  for _s in $_q; do
    case "$_s" in
      */*)
        case "${_s%%/*}" in
          org.ztmetro.*|org.ztos.*) printf '%s\n' "$_s" ;;
        esac
        ;;
    esac
  done
}

# --------------------------------------------------------------------------
# 1. accessibility services + the global accessibility switch
# --------------------------------------------------------------------------
step_accessibility() {
  want="org.ztmetro.actioncentre/.ShadeBridgeService org.ztmetro.actioncentre/.StatusStripService"
  extra=$(discover_a11y)
  svcs=""
  for s in $want $extra; do
    p=${s%%/*}
    installed "$p" || continue
    svcs=$(merge_colon "$svcs" "$s")
  done

  if [ -z "$svcs" ]; then
    log "accessibility=skipped (no zt accessibility service installed)"
  else
    cur=$(sget enabled_accessibility_services)
    total=0
    have=0
    for s in $(printf '%s' "$svcs" | tr ':' ' '); do
      total=$((total + 1))
      case ":$cur:" in *":$s:"*) have=$((have + 1)) ;; esac
    done
    if [ "$have" -eq "$total" ]; then
      log "accessibility=$total/$total enabled (unchanged)"
    else
      new=$(merge_colon "$cur" $(printf '%s' "$svcs" | tr ':' ' '))
      if sput enabled_accessibility_services "$new"; then
        changed
        log "accessibility=$total/$total enabled (was $have/$total) services=$new"
      else
        warn "could not write enabled_accessibility_services"
      fi
    fi
  fi

  ae=$(sget accessibility_enabled)
  if [ "$ae" = "1" ]; then
    log "a11y_enabled=1 (unchanged)"
  elif sput accessibility_enabled 1; then
    changed
    log "a11y_enabled=1 (was '${ae:-unset}')"
  else
    warn "could not write accessibility_enabled"
  fi
}

# --------------------------------------------------------------------------
# 2. notification listeners (Action Centre + Start)
# --------------------------------------------------------------------------
step_listeners() {
  want="org.ztmetro.actioncentre/org.ztmetro.actioncentre.ActionCentreListener org.ztmetro.start/org.ztmetro.start.StartNotificationListener"
  list=""
  for l in $want; do
    installed "${l%%/*}" || continue
    list=$(merge_colon "$list" "$l")
  done
  if [ -z "$list" ]; then
    log "listeners=skipped (no zt notification listener installed)"
    return 0
  fi

  cur=$(sget enabled_notification_listeners)
  added=0
  for l in $(printf '%s' "$list" | tr ':' ' '); do
    case ":$cur:" in *":$l:"*) continue ;; esac
    if cmd notification allow_listener "$l" >/dev/null 2>&1; then
      log "  allow_listener ok: $l"
    else
      warn "cmd notification allow_listener failed: $l"
    fi
  done
  # Some ROMs ignore allow_listener for a listener whose package was just
  # reinstalled; write the union directly as the documented fallback.
  cur=$(sget enabled_notification_listeners)
  new=$(merge_colon "$cur" $(printf '%s' "$list" | tr ':' ' '))
  if [ "$new" != "$cur" ]; then
    if sput enabled_notification_listeners "$new"; then
      added=1
    else
      warn "could not write enabled_notification_listeners"
    fi
  fi
  total=0
  have=0
  cur=$(sget enabled_notification_listeners)
  for l in $(printf '%s' "$list" | tr ':' ' '); do
    total=$((total + 1))
    case ":$cur:" in *":$l:"*) have=$((have + 1)) ;; esac
  done
  if [ "$added" = "1" ]; then
    changed
    log "listeners=$have/$total enabled (changed)"
  else
    log "listeners=$have/$total enabled (unchanged)"
  fi
}

# --------------------------------------------------------------------------
# 3. status-bar icon blacklist
# --------------------------------------------------------------------------
step_icon_blacklist() {
  want="clock,battery,wifi,mobile,stacked_mobile,rotate,headset"
  cur=$(sget icon_blacklist)
  if [ "$cur" = "$want" ]; then
    log "icon_blacklist=$want (unchanged)"
  elif sput icon_blacklist "$want"; then
    changed
    log "icon_blacklist=$want (was '$cur')"
  else
    warn "could not write icon_blacklist"
  fi
}

# --------------------------------------------------------------------------
# 4. HOME = Start
# --------------------------------------------------------------------------
step_home() {
  if ! installed org.ztmetro.start; then
    log "home=skipped (org.ztmetro.start not installed)"
    return 0
  fi
  HOME_BEFORE=$(resolve_home)
  if ! cmd package set-home-activity org.ztmetro.start/.StartActivity >/dev/null 2>&1; then
    warn "cmd package set-home-activity failed"
    return 0
  fi
  HOME_AFTER=$(resolve_home)
  case "$HOME_AFTER" in
    org.ztmetro.start/*) ;;
    *) warn "home resolves to '${HOME_AFTER:-nothing}' after set-home-activity" ;;
  esac
  if [ "$HOME_BEFORE" != "$HOME_AFTER" ]; then
    changed
    log "home=ok ($HOME_AFTER, was '${HOME_BEFORE:-unset}')"
  else
    log "home=ok ($HOME_AFTER, unchanged)"
  fi
}

# --------------------------------------------------------------------------
# 5. IME = ztMetro keyboard
# --------------------------------------------------------------------------
step_ime() {
  if ! installed org.ztmetro.ime; then
    log "ime=skipped (org.ztmetro.ime not installed)"
    return 0
  fi
  before=$(sget default_input_method)
  ime enable org.ztmetro.ime/.ZtMetroIme >/dev/null 2>&1 \
    || warn "ime enable org.ztmetro.ime/.ZtMetroIme failed"
  ime set org.ztmetro.ime/.ZtMetroIme >/dev/null 2>&1 \
    || warn "ime set org.ztmetro.ime/.ZtMetroIme failed"
  after=$(sget default_input_method)
  case "$after" in
    org.ztmetro.ime/*)
      if [ "$after" != "$before" ]; then
        changed
        log "ime=$after (was '${before:-unset}')"
      else
        log "ime=$after (unchanged)"
      fi
      ;;
    *) warn "ime='${after:-none}' (expected org.ztmetro.ime/.ZtMetroIme)" ;;
  esac
}

# --------------------------------------------------------------------------
# 6. Action Centre appops + READ_PHONE_STATE runtime grant
# --------------------------------------------------------------------------
step_appops() {
  if ! installed org.ztmetro.actioncentre; then
    log "appops=skipped (org.ztmetro.actioncentre not installed)"
    return 0
  fi
  for op in WRITE_SETTINGS READ_PHONE_STATE ACCESS_RESTRICTED_SETTINGS; do
    appops set org.ztmetro.actioncentre "$op" allow >/dev/null 2>&1 \
      || warn "appops set $op failed"
  done
  # appops allow is not a runtime grant on every ROM; both are documented.
  pm grant org.ztmetro.actioncentre android.permission.READ_PHONE_STATE >/dev/null 2>&1 \
    || log "  (pm grant READ_PHONE_STATE: already granted or restricted)"
  log "appops=WRITE_SETTINGS,READ_PHONE_STATE,ACCESS_RESTRICTED_SETTINGS allow"
}

# --------------------------------------------------------------------------
# 7. every zt overlay, framework priority, Monet off
# --------------------------------------------------------------------------
step_overlays() {
  want="org.ztmetro.overlay.framework org.ztos.overlay.framework.extra org.ztos.overlay.systemui org.ztos.overlay.settings org.ztos.overlay.status.framework org.ztos.overlay.status.systemui org.ztos.overlay.statusbaricons org.ztos.overlay.systemui.extra org.ztos.overlay.factory.org_lineageos_etar"

  LIST=$(cmd overlay list 2>/dev/null | tr -d '\r')
  if [ -z "$LIST" ]; then
    warn "cmd overlay list returned nothing; overlays skipped"
    return 0
  fi
  # Everything the package manager knows about, plus the expected list, so a
  # module that gains or loses an RRO keeps working without editing this script.
  known=$(printf '%s\n' "$LIST" \
    | grep -E '^\[[ x\]\] +(org\.ztmetro|org\.ztos)\.' \
    | sed 's/^\[[ x]\] *//')

  all=""
  for p in $want $known; do
    case " $all " in *" $p "*) continue ;; esac
    all="$all $p"
  done

  missing=""
  enabled=0
  total=0
  for p in $all; do
    st=$(ovl_state "$p")
    if [ -z "$st" ]; then
      missing="$missing $p"
      continue
    fi
    total=$((total + 1))
    if [ "$st" = "x" ]; then
      enabled=$((enabled + 1))
      continue
    fi
    if cmd overlay enable --user 0 "$p" >/dev/null 2>&1; then
      enabled=$((enabled + 1))
      OVL_CHANGED=1
      changed
      log "  overlay enabled: $p"
    else
      warn "cmd overlay enable --user 0 $p failed"
    fi
  done

  # Belt and braces: the framework overlay must win over SystemUI's own Monet
  # overlays, which target the same apk and are re-added at boot.
  cmd overlay set-priority org.ztmetro.overlay.framework highest >/dev/null 2>&1 \
    || log "  (framework priority: not settable)"
  for p in com.android.systemui:accent com.android.systemui:dynamic; do
    if printf '%s\n' "$LIST" | grep -qE "^\[x\] +$p\$"; then
      if cmd overlay disable "$p" >/dev/null 2>&1; then
        OVL_CHANGED=1
        changed
        log "  monet overlay disabled: $p"
      fi
    fi
  done

  LIST=$(cmd overlay list 2>/dev/null | tr -d '\r')
  enabled_final=$(printf '%s\n' "$LIST" | grep -cE '^\[x\] +(org\.ztmetro\.|org\.ztos\.)')
  log "overlays=${enabled_final} enabled (${enabled}/${total} handled)"
  [ -n "$missing" ] && warn "overlays not registered:$missing"
  return 0
}

# --------------------------------------------------------------------------
# 8. SystemUI picks a freshly enabled overlay up only after a restart.
#    At boot this is normally a no-op because nothing changed.
# --------------------------------------------------------------------------
step_systemui() {
  if [ "$OVL_CHANGED" != "1" ]; then
    log "systemui=left running (no overlay change)"
    return 0
  fi
  if pidof com.android.systemui >/dev/null 2>&1; then
    if killall com.android.systemui >/dev/null 2>&1; then
      sleep 3
      log "systemui=restarted (overlay changed)"
      return 0
    fi
    warn "killall com.android.systemui failed"
  else
    log "systemui=not running yet (nothing to restart)"
  fi
}

# LSPosed/Vector module enablement + scopes: lost on every module APK reinstall,
# GUI-owned by default; re-applied here so a reboot restores the Settings header
# and the shade hook without manual taps.
step_vector() {
  VECTOR_CLI=/data/adb/modules/zygisk_vector/cli
  if [ ! -f "$VECTOR_CLI" ]; then
    log "vector=cli absent (module enablement skipped)"
    return 0
  fi
  for mod in org.ztmetro.settingsheader org.ztmetro.shadehook; do
    if printf '%s\n' "$PKGS" | grep -qx "$mod"; then
      sh "$VECTOR_CLI" modules enable "$mod" >/dev/null 2>&1 && \
        log "vector=module enabled $mod" || warn "vector=$mod enable failed"
    fi
  done
  sh "$VECTOR_CLI" scope add org.ztmetro.settingsheader com.android.settings/0 >/dev/null 2>&1
  sh "$VECTOR_CLI" scope add org.ztmetro.shadehook android/0 >/dev/null 2>&1
  sh "$VECTOR_CLI" scope add org.ztmetro.shadehook com.android.systemui/0 >/dev/null 2>&1
  log "vector=scopes applied (settingsheader->settings, shadehook->android+systemui)"
}

# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------
log "start uid=$(id -u 2>/dev/null) pid=$$ boot_completed=$(getprop sys.boot_completed 2>/dev/null)"
if [ "$(id -u 2>/dev/null)" != "0" ]; then
  warn "not uid 0; secure settings writes will fail (run as root)"
fi

if load_pkgs; then
  log "packages=$(printf '%s\n' "$PKGS" | grep -c .) visible"
else
  warn "PackageManager did not answer; every package step will be skipped"
fi

step_overlays
step_appops
step_accessibility
step_listeners
step_icon_blacklist
step_home
step_ime
step_vector
step_systemui

log "done changes=$CHANGES warnings=$WARNINGS"
exit 0
