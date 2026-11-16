#!/usr/bin/env python3
"""Find RRO entries that will silently do nothing on a target APK.

idmap2 matches overlay entries to the target by (type, name).  A name the
target package does not define is dropped without an error: the overlay
installs, `cmd overlay list` shows it enabled, and the device looks unchanged.
This script turns that invisible failure into a non-zero exit code.

It reads the names the overlay ships (from an overlay APK or from an overlay
source directory), reads the target's resource table with `aapt2 dump
resources`, and prints every overlay entry the target does not define.

Usage:
  # after pulling framework-res.apk / SystemUI.apk / Settings.apk from the target
  python3 evidence/portability/check-target-resources.py \
      --overlay-dir overlay/systemui \
      --aapt2 "$ANDROID_HOME/build-tools/36.0.0/aapt2" \
      --target-apk /tmp/target/SystemUI.apk

  # same, against an already captured dump (host without aapt2)
  python3 evidence/portability/check-target-resources.py \
      --overlay-dir overlay/settings \
      --target-dump /tmp/target/settings-resources.txt

  # regenerate the repo's checked-in expected-name list
  python3 evidence/portability/check-target-resources.py \
      --overlay-dir overlay/settings --write-expected /tmp/settings.tsv

Exit: 0 only when every overlay entry exists in the target; 1 otherwise (or on
usage/tool errors).  A missing entry is not always a bug: library resources
(AndroidX/Material Components) are expected to be absent from AOSP source but
present in the built APK, and a ROM-specific resource may be absent on another
ROM.  The point is that the porter must see it and decide.
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

RES_RE = re.compile(r"^\s*resource 0x[0-9a-fA-F]+ ([a-z-]+)/(\S+)\s*$")
VALUE_TAGS = ("color", "dimen", "style", "bool", "attr", "string", "integer",
              "fraction", "plurals", "array", "string-array", "integer-array")


def die(message):
    sys.stderr.write("check-target-resources: %s\n" % message)
    raise SystemExit(2)


def expected_from_dir(overlay_dir):
    """(type, name) pairs declared by an overlay source tree."""
    names = set()
    values = os.path.join(overlay_dir, "res")
    files = glob.glob(os.path.join(values, "values*", "*.xml"))
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
                names.add((tag if tag != "string-array" else "array", name))
    # Drawables are file resources, not <drawable> declarations.
    for path in glob.glob(os.path.join(values, "drawable*", "*")):
        if not os.path.isfile(path):
            continue
        base = os.path.basename(path)
        if base.startswith("."):
            continue
        if base.endswith(".9.png"):
            stem = base[:-6]
        else:
            stem = os.path.splitext(base)[0]
        names.add(("drawable", stem))
    return names


def expected_from_tsv(path):
    names = set()
    with open(path, encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("overlay\t"):
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                die("%s:%d: expected overlay<TAB>type<TAB>name" % (path, number))
            names.add((parts[1], parts[2]))
    return names


def dump_resources(apk, aapt2):
    try:
        proc = subprocess.run([aapt2, "dump", "resources", apk],
                              check=True, capture_output=True, text=True)
    except FileNotFoundError:
        die("aapt2 not found: %s" % aapt2)
    except subprocess.CalledProcessError as exc:
        die("aapt2 dump resources failed for %s:\n%s" % (apk, exc.stderr))
    return parse_dump(proc.stdout)


def parse_dump(text):
    names = set()
    for line in text.splitlines():
        match = RES_RE.match(line)
        if match:
            names.add((match.group(1), match.group(2)))
    return names


def write_expected(path, overlay, names):
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write("overlay\ttype\tname\n")
        for rtype, name in sorted(names):
            handle.write("%s\t%s\t%s\n" % (overlay, rtype, name))
    sys.stderr.write("wrote %d names to %s\n" % (len(names), path))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--overlay-dir", help="overlay source tree (contains res/)")
    source.add_argument("--overlay-apk", help="built overlay APK")
    source.add_argument("--expected", help="TSV: overlay<TAB>type<TAB>name")
    target = parser.add_mutually_exclusive_group(required=False)
    target.add_argument("--target-apk", help="target APK to dump with aapt2")
    target.add_argument("--target-dump", help="saved `aapt2 dump resources` output")
    parser.add_argument("--aapt2", default=os.environ.get("AAPT2", "aapt2"))
    parser.add_argument("--overlay", default="overlay",
                        help="label when reading --overlay-dir (default: overlay)")
    parser.add_argument("--write-expected", metavar="TSV",
                        help="write the expected names as a TSV and exit")
    parser.add_argument("--allow", action="append", default=[],
                        metavar="type/name",
                        help="known-absent entry to report but not fail on "
                             "(repeatable; e.g. dimen/settingslib_entry_bg_radius)")
    args = parser.parse_args(argv)

    if args.expected:
        expected = expected_from_tsv(args.expected)
        label = args.expected
    elif args.overlay_apk:
        expected = dump_resources(args.overlay_apk, args.aapt2)
        label = args.overlay_apk
    else:
        expected = expected_from_dir(args.overlay_dir)
        label = args.overlay_dir

    if args.write_expected:
        write_expected(args.write_expected, args.overlay, expected)
        return 0

    if not args.target_apk and not args.target_dump:
        die("one of --target-apk / --target-dump is required (unless --write-expected)")

    if args.target_dump:
        with open(args.target_dump, encoding="utf-8", errors="replace") as handle:
            target_names = parse_dump(handle.read())
    else:
        target_names = dump_resources(args.target_apk, args.aapt2)

    allow = set(args.allow)
    missing = sorted(expected - target_names)
    hard = [entry for entry in missing
            if "%s/%s" % entry not in allow]
    sys.stderr.write("%s: %d overlay entries, %d missing in target%s\n"
                     % (label, len(expected), len(missing),
                        " (%d allowed)" % (len(missing) - len(hard)) if hard != missing else ""))
    for rtype, name in missing:
        print("%s%s/%s" % ("ALLOWED " if "%s/%s" % (rtype, name) in allow else "MISSING ",
                          rtype, name))
    if hard:
        sys.stderr.write("FAIL: %d entries would silently no-op\n" % len(hard))
        return 1
    sys.stderr.write("OK: every checked entry exists in the target\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
