#!/system/bin/sh
# ztmetro-recents installer hook (Magisk calls this after unzip).
#
# The module is deliberately plain files: two APKs and one XML. There is no
# service-side payload to run, and nothing is patched in place. What matters is
# the path:
#   /system_ext/priv-app/ActionCentre/            -> PMS scans it as a system app
#   /system_ext/etc/permissions/                  -> privapp allowlist
#   /product/overlay/                             -> static RRO, read at boot
#
# A reboot is required: RecentTasks.loadRecentsComponent() and
# PackageManagerService read config_recentsComponentName once during boot.

SKIPUNZIP=0

ui_print "- ztmetro-recents: system recents provider"
ui_print "  /system_ext/priv-app/ActionCentre"
ui_print "  /system_ext/etc/permissions"
ui_print "  /product/overlay (static framework RRO)"

# magisk --install-module normalises module files; be explicit anyway.
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/system_ext/priv-app/ActionCentre" 0 0 0755 0644
set_perm_recursive "$MODPATH/system_ext/etc/permissions" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/product/overlay" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "- reboot now: the RRO is read once at boot"
