#!/usr/bin/env python3
"""Cross-check every resource in the built overlay against AOSP framework-res.

A resource name that does not exist in the target package is *silently ignored*
by idmap2, so a typo is invisible on device.  This tool makes it visible:

  1. reads the resource names out of the built APK with `aapt2 dump resources`;
  2. greps the same name in the AOSP tag's core/res/res/values/*.xml (and
     res/color/*.xml for ColorStateList-backed colour resources);
  3. prints `type,name,overlay_value,aosp_source:line,stock_declaration` and
     fails if any name is missing from the target.

Usage:
  check_names.py --apk OUT.apk --aapt2 $SDK/build-tools/36.0.0/aapt2 \
                 --aosp-values DIR/values --aosp-color DIR/color [--csv FILE]

`DIR/values` must be the `core/res/res/values` directory of the AOSP tag
(fetch: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/
android-16.0.0_r3/core/res/res/values/<file>).
"""

import argparse
import csv
import os
import re
import subprocess
import sys

RESOURCE_RE = re.compile(r"^\s+resource 0x[0-9a-fA-F]+ ([a-z-]+)/(\S+)\s*$")
VALUE_RE = re.compile(r"^\s+\(\)\s+(.*)$")
DECL_RE = re.compile(r'<(color|dimen|bool|style|string|integer|item)\s+name="([^"]+)"')


def dump(apk, aapt2):
    out = subprocess.run([aapt2, "dump", "resources", apk],
                         check=True, capture_output=True, text=True).stdout
    entries = []
    current = None
    for line in out.splitlines():
        match = RESOURCE_RE.match(line)
        if match:
            current = {"type": match.group(1), "name": match.group(2), "value": ""}
            entries.append(current)
            continue
        match = VALUE_RE.match(line)
        if match and current is not None and not current["value"]:
            current["value"] = match.group(1).strip()
    return entries


def find_in_values(values_dir, type_name, name):
    """Return (path, line, declaration) of the first declaration, or None."""
    pattern = re.compile(r'<%s\s+name="%s"' % (re.escape(type_name), re.escape(name)))
    for filename in sorted(os.listdir(values_dir)):
        if not filename.endswith(".xml"):
            continue
        path = os.path.join(values_dir, filename)
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            for number, line in enumerate(handle, 1):
                if pattern.search(line):
                    return path, number, DECL_RE.search(line).group(0) if DECL_RE.search(line) else ""
    return None


def find_color_state_list(color_dir, name):
    path = os.path.join(color_dir, name + ".xml")
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for number, line in enumerate(handle, 1):
            stripped = line.strip()
            if stripped.startswith("<selector") or stripped.startswith("<item"):
                return path, number, stripped
    return path, 1, ""


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", required=True)
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--aosp-values", required=True)
    parser.add_argument("--aosp-color", required=True)
    parser.add_argument("--csv", help="also write the table here")
    args = parser.parse_args(argv)

    entries = dump(args.apk, args.aapt2)
    rows = []
    missing = []
    for entry in entries:
        found = find_in_values(args.aosp_values, entry["type"], entry["name"])
        if found is None and entry["type"] == "color":
            found = find_color_state_list(args.aosp_color, entry["name"])
        if found is None:
            missing.append("%s/%s" % (entry["type"], entry["name"]))
            rows.append([entry["type"], entry["name"], entry["value"], "MISSING", "", ""])
            continue
        path, line, declaration = found
        rows.append([entry["type"], entry["name"], entry["value"],
                     "%s:%d" % (os.path.basename(os.path.dirname(path)) + "/" + os.path.basename(path), line),
                     declaration, ""])

    writer = csv.writer(sys.stdout, lineterminator="\n")
    writer.writerow(["type", "name", "overlay_value", "aosp_source:line", "stock_declaration"])
    for row in rows:
        writer.writerow(row[:5])

    if args.csv:
        with open(args.csv, "w", encoding="utf-8", newline="") as handle:
            csv_writer = csv.writer(handle, lineterminator="\n")
            csv_writer.writerow(["type", "name", "overlay_value", "aosp_source_line", "stock_declaration"])
            csv_writer.writerows(row[:5] for row in rows)

    if missing:
        sys.stderr.write("not found in AOSP target (%d):\n" % len(missing))
        for name in missing:
            sys.stderr.write("  - %s\n" % name)
        return 1
    sys.stderr.write("all %d resources verified against the AOSP values\n" % len(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
