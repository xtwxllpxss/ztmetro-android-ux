#!/system/bin/sh
# ztmetro-recents uninstall hook.
#
# Removing the module removes /system_ext/priv-app, the allowlist and the static
# overlay on the next reboot. The RRO is immutable while mounted, so the recents
# component stays overridden until that reboot: reboot is the rollback, there is
# no live `cmd overlay disable` for a static overlay.
#
# Nothing in /data is modified by this module (no settings, no appops, no
# accessibility grants), so there is nothing else to undo.
rm -f /data/local/tmp/ztmetro-recents.log /data/local/tmp/ztmetro-recents.log.1
exit 0
