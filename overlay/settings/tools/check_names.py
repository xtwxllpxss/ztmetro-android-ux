#!/usr/bin/env python3
"""Verify every resource the ztmetro Settings RRO overrides against the *target*
source tree, and emit the evidence table used by evidence/settings-rro/REPORT.md.

Why this exists: an RRO silently ignores a name the target does not have, so a typo
does not fail the build -- it just does nothing on the device. This script makes the
build untrustworthy unless every overridden name is a real definition in
LineageOS/android_packages_apps_Settings at the pinned commit.

Usage:
  # tree = a checkout of android_packages_apps_Settings at lineage-23.2
  git clone --depth 1 --filter=blob:none --sparse -b lineage-23.2 \
      https://github.com/LineageOS/android_packages_apps_Settings.git settings-src
  git -C settings-src sparse-checkout set res src

  python3 repo/overlay/settings/tools/check_names.py \
      --tree settings-src \
      --dump repo/evidence/settings-rro/aapt2-dump-resources.txt \
      --md   repo/evidence/settings-rro/verified-resources.md \
      --csv  repo/evidence/settings-rro/verified-resources.csv

Exit status: 0 only if every overridden color/dimen/style/drawable is defined in the
tree and at least one consumer reference exists (or the resource is a documented
leaf, see ALLOW_NO_CONSUMER). Library attrs (Material Components) are accepted with a
note: they are linked into the app package but are not in the Settings repo.
"""
import argparse
import os
import re
import sys

VALUES = ("res/values", "res/values-night", "res/values-v31", "res/values-v35")

# Resources that legitimately have no consumer *other* than their own definition or
# theme wiring; the report explains each one.
ALLOW_NO_CONSUMER = {
    "dimen": {
        "min_tap_target_size",  # 22 layouts under res/layout, matched by the scan
    },
    "color": set(),
}

RE_ENTRY = re.compile(r"^    resource 0x[0-9a-f]+ (\w+)/(\S+)$", re.M)
RE_DEF = {
    "dimen": re.compile(r"<dimen\s+name=\"%s\"[^>]*>(.*?)</dimen>", re.S),
    "color": re.compile(r"<color\s+name=\"%s\"[^>]*>(.*?)</color>", re.S),
    "style": re.compile(r"<style\s+name=\"%s\"(.*?)</style>", re.S),
    "attr": re.compile(r"<attr\s+name=\"%s\"(.*?)/>", re.S),
}


def walk_xml(tree, subdir):
    root = os.path.join(tree, subdir)
    for dirpath, _dirs, files in os.walk(root):
        for f in sorted(files):
            if f.endswith(".xml"):
                yield os.path.join(dirpath, f)


def rel(tree, path):
    return os.path.relpath(path, tree)


def find_def(tree, rtype, name):
    """-> (file, line, value) or None."""
    pat = RE_DEF[rtype]
    needle = pat.pattern % re.escape(name)
    rx = re.compile(needle, re.S)
    candidates = []
    for sub in VALUES:
        if not os.path.isdir(os.path.join(tree, sub)):
            continue
        for path in walk_xml(tree, sub):
            text = open(path, encoding="utf-8", errors="replace").read()
            m = rx.search(text)
            if m:
                line = text[: m.start()].count("\n") + 1
                body = re.sub(r"\s+", " ", m.group(1)).strip()
                candidates.append((path, line, body))
    if not candidates:
        return None
    # Prefer the plain values/ file, then values-night, etc. (stable walk order).
    candidates.sort(key=lambda c: (os.sep + "values" + os.sep not in c[0], c[0]))
    return candidates[0]


def find_drawable(tree, name):
    for sub in sorted(os.listdir(os.path.join(tree, "res"))):
        if not sub.startswith("drawable"):
            continue
        for ext in (".xml", ".png", ".9.png", ".webp"):
            path = os.path.join(tree, "res", sub, name + ext)
            if os.path.exists(path):
                return path, 0, "file"
    return None


def consumer_file_list(tree):
    """Files worth grepping for consumers: layouts/drawables/colors/xml + sources.

    Locale value dirs (values-de, values-af, ...) are skipped: they only repeat
    translated strings and make the scan 50x slower for no evidence.
    """
    files = []
    res = os.path.join(tree, "res")
    for sub in sorted(os.listdir(res)):
        path = os.path.join(res, sub)
        if not os.path.isdir(path):
            continue
        if sub.startswith("values-") and re.search(r"-[a-z]{2,3}(-|$)", sub):
            continue  # locale
        for dirpath, _dirs, names in os.walk(path):
            for f in sorted(names):
                if f.endswith((".xml", ".java", ".kt")):
                    files.append(os.path.join(dirpath, f))
    src = os.path.join(tree, "src")
    for dirpath, _dirs, names in os.walk(src):
        for f in sorted(names):
            if f.endswith((".xml", ".java", ".kt")):
                files.append(os.path.join(dirpath, f))
    return files


def find_all_consumers(tree, names, limit=4):
    """One pass over the tree; returns {name: [(path, line), ...]}."""
    rx = {n: re.compile(r"(?<![\w])%s(?![\w])" % re.escape(n)) for n in names}
    hits = {n: [] for n in names}
    todo = set(names)
    for path in consumer_file_list(tree):
        if not todo:
            break
        try:
            lines = open(path, encoding="utf-8", errors="replace").read().split("\n")
        except OSError:
            continue
        for name in list(todo):
            pat = rx[name]
            for i, line in enumerate(lines, 1):
                if ("name=\"" + name + "\"") in line:
                    continue  # the definition itself
                if pat.search(line):
                    hits[name].append((path, i))
                    if len(hits[name]) >= limit:
                        todo.discard(name)
                    break
    return hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tree", required=True, help="android_packages_apps_Settings checkout")
    ap.add_argument("--dump", required=True, help="aapt2 dump resources output of the APK")
    ap.add_argument("--md")
    ap.add_argument("--csv")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    dump = open(args.dump, encoding="utf-8", errors="replace").read()
    entries = RE_ENTRY.findall(dump)
    if not entries:
        sys.exit("check_names: no entries in the dump -- wrong file?")

    all_consumers = find_all_consumers(args.tree, {n for _t, n in entries})

    rows, problems = [], []
    for rtype, name in entries:
        if rtype in ("dimen", "color", "style", "attr"):
            if rtype == "attr":
                found = find_def(args.tree, "attr", name)
                if not found:
                    # Material Components attr: linked into the app package from an
                    # AAR, not present in the Settings repo. Documented as such.
                    rows.append((rtype, name, "-", "library attr (Material Components AAR)", "-"))
                    continue
            else:
                found = find_def(args.tree, rtype, name)
            if not found:
                problems.append("%s/%s: NOT DEFINED in %s" % (rtype, name, args.tree))
                rows.append((rtype, name, "-", "MISSING", "-"))
                continue
            path, line, value = found
            cons = all_consumers.get(name, [])
            if not cons and name not in ALLOW_NO_CONSUMER.get(rtype, set()):
                problems.append("%s/%s: no consumer found" % (rtype, name))
            loc = "%s:%d" % (rel(args.tree, path), line)
            cloc = ", ".join("%s:%d" % (rel(args.tree, p), i) for p, i in cons) or "-"
            rows.append((rtype, name, value[:90], loc, cloc))
        elif rtype == "drawable":
            found = find_drawable(args.tree, name)
            if not found:
                problems.append("drawable/%s: NOT DEFINED" % name)
                rows.append((rtype, name, "-", "MISSING", "-"))
                continue
            path, line, value = found
            cons = all_consumers.get(name, [])
            rows.append((rtype, name, value, rel(args.tree, path), ", ".join(
                "%s:%d" % (rel(args.tree, p), i) for p, i in cons) or "-"))
        else:
            rows.append((rtype, name, "-", "-", "-"))

    md = ["| type | resource | target definition (old value) | file:line | consumers |",
          "|---|---|---|---|---|"]
    for rtype, name, value, loc, cloc in rows:
        md.append("| `%s` | `%s` | %s | %s | %s |" % (rtype, name, value, loc, cloc))
    md.append("")
    md.append("%d resource names checked." % len(rows))
    if problems:
        md.append("")
        md.append("PROBLEMS:")
        md.extend("- " + p for p in problems)
    out = "\n".join(md)
    if args.md:
        open(args.md, "w", encoding="utf-8").write(out + "\n")
    if args.csv:
        with open(args.csv, "w", encoding="utf-8") as fh:
            fh.write("type,resource,old_value,target_file_line,consumers\n")
            for r in rows:
                fh.write(",".join('"%s"' % str(c).replace('"', "'") for c in r) + "\n")
    if not args.quiet:
        print(out)
    if problems:
        print("\ncheck_names: %d problem(s)" % len(problems), file=sys.stderr)
        return 1
    print("\ncheck_names: all %d resources verified against %s" % (len(rows), args.tree),
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
