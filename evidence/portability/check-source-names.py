#!/usr/bin/env python3
"""Check overlay resource names against a *source tree* instead of an APK.

Use this before building another ROM, or when the target APK cannot be pulled
(boot loop, no root): it answers "does this ROM's source still define every
resource the ztMetro overlay replaces?".

It understands the three trees the overlays target:

  framework   <tree>/core/res/res/values*/*.xml and <tree>/core/res/res/color/*
  systemui    <tree>/packages/SystemUI/{res,res-keyguard,res-product}/values*/*.xml
  settings    <settings-tree>/res/values*/*.xml + <framework-tree>/packages/SettingsLib/**/values*/*.xml

Drawable overrides are checked as files (res/drawable*).  A name that is not
found is reported; known library resources (AndroidX/Material Components, which
are merged into the APK from AARs, not present in AOSP source) can be allowed
with --allow.

Usage:
  python3 evidence/portability/check-source-names.py \
      --overlay framework --framework-tree /src/android-16 \
      --settings-tree /src/Settings
  python3 evidence/portability/check-source-names.py \
      --overlay settings --framework-tree /src/android-16 --settings-tree /src/Settings \
      --allow dimen/settingslib_entry_bg_radius

Exit: 0 if every non-allowed name is defined, 1 otherwise.
"""

import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

VALUE_TAGS = ("color", "dimen", "style", "bool", "attr", "string", "integer",
              "fraction", "plurals", "array")


def die(message):
    sys.stderr.write("check-source-names: %s\n" % message)
    raise SystemExit(2)


def expected(overlay_dir):
    names = set()
    files = glob.glob(os.path.join(overlay_dir, "res", "values*", "*.xml"))
    if not files:
        die("no res/values*/ XML under %s" % overlay_dir)
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            die("cannot parse %s: %s" % (path, exc))
        for child in root:
            tag = child.tag.split("}")[-1]
            name = child.get("name")
            if tag == "item" and child.get("type") and name:
                tag = child.get("type")
            if tag in VALUE_TAGS and name:
                names.add((tag, name))
    for path in glob.glob(os.path.join(overlay_dir, "res", "drawable*", "*")):
        if not os.path.isfile(path) or os.path.basename(path).startswith("."):
            continue
        base = os.path.basename(path)
        names.add(("drawable", base[:-6] if base.endswith(".9.png")
                   else os.path.splitext(base)[0]))
    return names


def value_files(roots):
    files = []
    for root in roots:
        for dirpath, _dirs, names in os.walk(root):
            if os.path.basename(dirpath).startswith("values"):
                files.extend(os.path.join(dirpath, name)
                             for name in names if name.endswith(".xml"))
    return files


def has_definition(files, rtype, name):
    pattern = re.compile(
        r'<(?:%s|item\s+type=\"%s\")\s+name=\"%s\"'
        % (rtype, rtype, re.escape(name)))
    for path in files:
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        if pattern.search(text):
            return path
    return None


def has_drawable(roots, name):
    for root in roots:
        pattern = os.path.join(root, "**", "drawable*", name + "*")
        for path in glob.glob(pattern, recursive=True):
            if os.path.isfile(path):
                return path
    return None


def has_color_state_list(roots, name):
    """A <color> resource may be a res/color/<name>.xml ColorStateList."""
    for root in roots:
        path = os.path.join(root, "color", name + ".xml")
        if os.path.isfile(path):
            return path
    return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--overlay", required=True,
                        choices=("framework", "systemui", "settings"))
    parser.add_argument("--overlay-dir", help="default: overlay/<overlay>")
    parser.add_argument("--framework-tree", required=True,
                        help="AOSP/Lineage frameworks/base checkout")
    parser.add_argument("--settings-tree", help="packages/apps/Settings checkout")
    parser.add_argument("--allow", action="append", default=[], metavar="type/name")
    args = parser.parse_args(argv)

    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    overlay_dir = args.overlay_dir or os.path.join(repo, "overlay", args.overlay)

    if args.overlay == "framework":
        base = os.path.join(args.framework_tree, "core/res/res")
        files = value_files([base])
        draw_roots = [base]
    elif args.overlay == "systemui":
        base = os.path.join(args.framework_tree, "packages/SystemUI")
        files = value_files([os.path.join(base, part) for part in
                             ("res", "res-keyguard", "res-product")])
        draw_roots = [os.path.join(base, part) for part in
                      ("res", "res-keyguard", "res-product")]
    else:
        if not args.settings_tree:
            die("--settings-tree is required for --overlay settings")
        roots = [os.path.join(args.settings_tree, "res")]
        roots.append(os.path.join(args.framework_tree, "packages/SettingsLib"))
        files = value_files(roots)
        draw_roots = roots

    allow = set(args.allow)
    missing = []
    for rtype, name in sorted(expected(overlay_dir)):
        if rtype == "drawable":
            found = has_drawable(draw_roots, name)
        else:
            found = has_definition(files, rtype, name)
        if not found and rtype == "color" and args.overlay == "framework":
            found = has_color_state_list(draw_roots, name)
        if not found:
            missing.append((rtype, name))

    hard = [entry for entry in missing if "%s/%s" % entry not in allow]
    print("%s: %d entries, %d not in source%s"
          % (args.overlay, len(expected(overlay_dir)), len(missing),
             " (%d allowed)" % (len(missing) - len(hard)) if hard != missing else ""))
    for rtype, name in missing:
        print("%s%s/%s" % ("ALLOWED " if "%s/%s" % (rtype, name) in allow else "MISSING ",
                          rtype, name))
    return 1 if hard else 0


if __name__ == "__main__":
    sys.exit(main())
