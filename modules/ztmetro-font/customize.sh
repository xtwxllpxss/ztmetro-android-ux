#!/system/bin/sh
# Magisk installer for ztmetro-font.
#
# Runs on the device as root during `magisk --install-module`. It never touches
# /system: it reads the live font config, rewrites only the sans-serif family, and
# writes the result into this module's overlay ($MODPATH/system/etc/...). Magisk
# mounts the overlay on the next boot. If any check fails the install aborts, so a
# half-rewritten config is never activated.
#
# Magisk provides: $MODPATH, ui_print(), abort(), set_perm(), set_perm_recursive().

SKIPUNZIP=0

ui_print "***********************************************"
ui_print " ztmetro-font 1.1.0"
ui_print " Open Sans 3.003 (Segoe UI-class, OFL, Cyrillic)"
ui_print " system sans-serif, systemless via Magisk"
ui_print "***********************************************"

[ -n "${MODPATH:-}" ] || abort "! MODPATH is not set; flash this zip with Magisk"

FONTDIR="$MODPATH/system/fonts"
FRAGMENT="$MODPATH/fonts.fragment"
REWRITE="$MODPATH/rewrite-fonts.sh"

# --- 1. the payload must be what the fragment references ----------------------
FONT_NAMES="OpenSans-Light.ttf OpenSans-LightItalic.ttf OpenSans-Regular.ttf OpenSans-Italic.ttf OpenSans-SemiBold.ttf OpenSans-SemiBoldItalic.ttf OpenSans-Bold.ttf OpenSans-BoldItalic.ttf"
for name in $FONT_NAMES; do
  [ -f "$FONTDIR/$name" ] || abort "! missing font payload: $name"
  magic=$(od -A n -t x1 -N 4 "$FONTDIR/$name" 2>/dev/null | tr -d ' \n')
  case "$magic" in
    00010000|4f54544f) : ;;
    *) abort "! $name is not a TrueType/OpenType font (magic '${magic:-none}')" ;;
  esac
done
[ -f "$FRAGMENT" ] || abort "! missing fonts.fragment"
[ -f "$REWRITE" ] || abort "! missing rewrite-fonts.sh"

# --- 2. find the live config --------------------------------------------------
# Android 15+ SystemFonts reads /system/etc/font_fallback.xml. fonts.xml is the
# legacy config, still parsed by Skia (native text, WebView) and legacy readers.
SYSTEM_ROOT=
if [ -f /system/etc/font_fallback.xml ]; then
  SYSTEM_ROOT=/system
elif [ -f /system_root/system/etc/font_fallback.xml ]; then
  SYSTEM_ROOT=/system_root/system
else
  abort "! /system/etc/font_fallback.xml not found: unsupported ROM (Android 15+ required)"
fi

ui_print "- live config: $SYSTEM_ROOT/etc/font_fallback.xml"
[ -f "$SYSTEM_ROOT/etc/fonts.xml" ] && ui_print "- legacy config: $SYSTEM_ROOT/etc/fonts.xml"

mkdir -p "$MODPATH/system/etc" || abort "! cannot create $MODPATH/system/etc"

# --- 3. rewrite (fatal on failure: nothing installed if validation fails) -----
rewrite_one() {
  name=$1
  src="$SYSTEM_ROOT/etc/$name"
  dest="$MODPATH/system/etc/$name"

  [ -f "$src" ] || return 1
  if ! sh "$REWRITE" --in "$src" --out "$dest" --fragment "$FRAGMENT" --fontdir "$FONTDIR"; then
    abort "! $name rewrite/validation failed; module not installed"
  fi
  set_perm 0 0 0644 "$dest"
  ui_print "- overlay: system/etc/$name (sans-serif -> Open Sans)"
  return 0
}

rewrite_one font_fallback.xml || abort "! could not rewrite font_fallback.xml"
if rewrite_one fonts.xml; then
  :
else
  ui_print "- note: fonts.xml absent; native/Skia readers keep stock sans-serif"
fi

set_perm_recursive "$MODPATH/system/fonts" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/etc" 0 0 0755 0644

ui_print "- installed: 8 Open Sans faces + rewritten font config(s)"
ui_print "- reboot to apply"
ui_print "- recovery: Magisk safe mode (hold Vol- during boot) or"
ui_print "  'adb shell su -c magisk --remove-modules', then reboot"
