"""`generate`: turn an app-profile into a signed RRO overlay APK (or a dry-run table).

Rules that keep the output legal and reversible:
  * only names that exist in the target are emitted (they come from the profile,
    which came from the target's own resource table);
  * the overlay carries resources only: android:hasCode="false", no dex;
  * a config-qualified target entry gets a config-qualified overlay entry
    (a (v31) target value outranks a default overlay value — this is the mistake
    that makes naive overlays "not work" on Android 12+ with Material dynamic colour);
  * an app that declares <overlayable> is refused unless --target-name is given,
    because idmap2 will reject it otherwise;
  * packages outside tools/theme-factory/registry.json are refused without
    --force-package.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import zipfile

from .aapt import AndroidTools, ToolError
from .util import log, parse_color, rgb_hex, sha256_file, to_hex

HERE = os.path.dirname(os.path.abspath(__file__))
REGISTRY = os.path.join(os.path.dirname(HERE), "registry.json")
KEYSTORE = os.path.expanduser("~/.ztmetro-overlay-debug.keystore")
KEY_PASS = "ztmetro"


def load_registry(path: str = REGISTRY) -> dict:
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def slug_for(package: str) -> str:
    return re.sub(r"[^a-z0-9_]", "_", package.lower())


def values_dir(config: str) -> str:
    return "values" if not config else "values-" + config


def fmt_color(value: str) -> str:
    col = parse_color(value)
    if col is None:
        raise ValueError("not a colour: %r" % value)
    return rgb_hex(col)


def fmt_dimension(value: str) -> str:
    v = value.strip()
    if re.match(r"^-?\d+(\.\d+)?(dp|dip|sp|px)$", v):
        return v
    if v in ("0", "0.0"):
        return "0dp"
    raise ValueError("not a dimension: %r" % value)


class Generator:
    def __init__(self, profile: dict, tokens, out_dir: str, tools: AndroidTools | None = None,
                 min_confidence: float = 0.6, include_roles=("surface", "text", "accent",
                                                             "surface_key", "destructive",
                                                             "radius", "type"),
                 target_name: str | None = None, force_package: bool = False,
                 registry: dict | None = None, overlap_policy: str = "preinstalled"):
        self.profile = profile
        self.tokens = tokens
        self.out_dir = out_dir
        self.tools = tools or AndroidTools()
        self.min_confidence = min_confidence
        self.include_roles = set(include_roles)
        self.target_name = target_name
        self.force_package = force_package
        self.registry = registry or load_registry()
        self.package = profile["package"]["package"]
        self.slug = slug_for(self.package)
        self.overlay_package = "org.ztos.overlay.factory.%s" % self.slug

    # ------------------------------------------------------------------
    def check_policy(self) -> list:
        notes = []
        allow = self.registry.get("allow", {})
        if self.package not in allow and not self.force_package:
            raise SystemExit(
                "refusing to build an overlay for %r: not in %s (default policy: deny).\n"
                "  add it to the registry with evidence, or pass --force-package for a one-off."
                % (self.package, REGISTRY))
        if self.package not in allow:
            notes.append("package forced past the registry (no registry entry)")
        if self.profile["theme"].get("overlayable"):
            if not self.target_name:
                raise SystemExit(
                    "target declares <overlayable>: idmap2 requires android:targetName and will only "
                    "map resources listed in that policy. Re-run with --target-name <group> after "
                    "checking the app's overlayable policy.")
            notes.append("targetName=%s" % self.target_name)
        if self.profile["apk"]["sha256"] != self.registry.get("allow", {}).get(self.package, {}).get("sha256"):
            notes.append("APK sha256 differs from the registry pin (expected for a fresh pull)")
        return notes

    def selected(self) -> list:
        out = []
        for m in self.profile["mappings"]:
            if m.get("role") not in self.include_roles:
                continue
            if float(m.get("confidence", 0)) < self.min_confidence:
                continue
            if not m.get("configs_after"):
                continue
            out.append(m)
        return out

    # ------------------------------------------------------------------
    def build_tree(self, mappings) -> dict:
        """{relative res path: {name: (type, value)}}"""
        tree = {}
        for m in mappings:
            rtype = m["type"]
            for config, value in m["configs_after"].items():
                d = values_dir(config)
                fname = {"color": "colors.xml", "dimen": "dimens.xml"}.get(rtype)
                if fname is None:
                    continue
                res_name = m["resource"].split("/", 1)[1]
                tree.setdefault(os.path.join(d, fname), {})[res_name] = (rtype, value)
        return tree

    def write_sources(self, mappings, dest: str):
        tree = self.build_tree(mappings)
        res_root = os.path.join(dest, "res")
        if os.path.isdir(res_root):
            shutil.rmtree(res_root)
        for rel, entries in sorted(tree.items()):
            path = os.path.join(res_root, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            tag = "color" if entries and next(iter(entries.values()))[0] == "color" else "dimen"
            lines = ['<?xml version="1.0" encoding="utf-8"?>',
                     "<!-- GENERATED by repo/tools/theme-factory from %s" % os.path.basename(
                         self.profile["apk"]["path"]),
                     "     target %s; sha256 %s" % (self.package, self.profile["apk"]["sha256"]),
                     "     Values come from design/tokens/ztmetro.tokens.json. -->",
                     "<resources>"]
            for name in sorted(entries):
                _, value = entries[name]
                rendered = fmt_color(value) if tag == "color" else fmt_dimension(value)
                lines.append('    <%s name="%s">%s</%s>' % (tag, name, rendered, tag))
            lines.append("</resources>")
            lines.append("")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write("\n".join(lines))
        self._write_manifest(dest, tree)
        return tree

    def _write_manifest(self, dest: str, tree):
        overlay_attrs = ['android:targetPackage="%s"' % self.package, 'android:priority="10"']
        if self.target_name:
            overlay_attrs.append('android:targetName="%s"' % self.target_name)
        manifest = """<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED by repo/tools/theme-factory.  Resources only (hasCode=false):
  this APK cannot execute code, cannot add views and cannot change behaviour.
  target: %(pkg)s
  overlay entries: %(count)d in %(files)d file(s)
-->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="%(overlay)s"
    android:versionCode="1"
    android:versionName="1.0">

    <application
        android:hasCode="false"
        android:label="ztmetro %(pkg)s theme factory overlay" />

    <overlay %(attrs)s />
</manifest>
""" % {"pkg": self.package, "overlay": self.overlay_package,
       "count": sum(len(v) for v in tree.values()), "files": len(tree),
       "attrs": "\n        ".join(overlay_attrs)}
        with open(os.path.join(dest, "AndroidManifest.xml"), "w", encoding="utf-8") as fh:
            fh.write(manifest)

    # ------------------------------------------------------------------
    def build(self, dest: str, mappings) -> dict:
        os.makedirs(dest, exist_ok=True)
        work = os.path.join(dest, ".build")
        if os.path.isdir(work):
            shutil.rmtree(work)
        os.makedirs(work)
        aapt2 = self.tools.aapt2
        zipalign = self.tools.zipalign
        apksigner = self.tools.apksigner
        target_sdk = int(self.profile["package"].get("targetSdk") or 36)
        android_jar = self.tools.platform_jar(target_sdk)
        res_zip = os.path.join(work, "res.zip")
        unsigned = os.path.join(work, "unsigned.apk")
        aligned = os.path.join(work, "aligned.apk")
        apk_name = "ztmetro-%s-rro.apk" % self.slug
        final = os.path.join(dest, apk_name)

        log("== aapt2 compile")
        self.tools.run([aapt2, "compile", "--dir", os.path.join(dest, "res"), "-o", res_zip])
        log("== aapt2 link (target %s)" % self.package)
        link = self.tools.run([aapt2, "link", "-o", unsigned, "-I", android_jar,
                               "--manifest", os.path.join(dest, "AndroidManifest.xml"),
                               "--min-sdk-version", "26", "--target-sdk-version", str(target_sdk),
                               "--auto-add-overlay", "--no-resource-deduping",
                               "--no-resource-removal", res_zip], check=False)
        if link.returncode != 0:
            raise ToolError("aapt2 link failed:\n%s" % (link.stderr or link.stdout))
        self.tools.run([zipalign, "-p", "-f", "4", unsigned, aligned])
        self._ensure_keystore()
        log("== apksigner sign")
        self.tools.run([apksigner, "sign", "--ks", KEYSTORE, "--ks-pass", "pass:" + KEY_PASS,
                        "--key-pass", "pass:" + KEY_PASS, "--min-sdk-version", "26",
                        "--out", final, aligned])
        verify = self.tools.run([apksigner, "verify", "--min-sdk-version", "26", final], check=False)
        if verify.returncode != 0:
            raise ToolError("apksigner verify failed:\n%s" % (verify.stderr or verify.stdout))
        shutil.rmtree(work, ignore_errors=True)
        return {"apk": final, "apk_name": apk_name, "sha256": sha256_file(final),
                "size": os.path.getsize(final), "android_jar": android_jar,
                "entries": sum(len(v) for v in self.build_tree(mappings).values())}

    def _ensure_keystore(self):
        if os.path.exists(KEYSTORE):
            return
        self.tools.run([self.tools.keytool(), "-genkeypair", "-keystore", KEYSTORE,
                        "-alias", "ztmetro", "-storepass", KEY_PASS, "-keypass", KEY_PASS,
                        "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                        "-dname", "CN=ztmetro overlay (throwaway)"])

    # ------------------------------------------------------------------
    def verify_apk(self, built: dict, mappings) -> dict:
        apk = built["apk"]
        with zipfile.ZipFile(apk) as zf:
            names = zf.namelist()
        dex = [n for n in names if n.endswith(".dex")]
        badging = self.tools.dump_badging(apk)
        manifest = self.tools.dump_xmltree(apk, "AndroidManifest.xml")
        dump = self.tools.dump_resources(apk)
        target_ok = ("targetPackage=\"%s\"" % self.package) in manifest or self.package in manifest
        has_code = 'hasCode(0x0101050b)=false' in manifest or "hasCode" not in manifest
        overlay_dump = {}
        for line in dump.splitlines():
            m = re.match(r"    resource (0x[0-9a-f]+) (\S+)/(\S+?)(?: PUBLIC)?\s*$", line)
            if m:
                overlay_dump["%s/%s" % (m.group(2), m.group(3))] = []
            elif m is None:
                vm = re.match(r"      \(([^)]*)\) (.*)$", line)
                if vm and overlay_dump:
                    overlay_dump[list(overlay_dump)[-1]].append((vm.group(1), vm.group(2)))
        missing = [m["resource"] for m in mappings if m["resource"] not in overlay_dump]
        return {"dex_files": dex, "is_resource_only": not dex,
                "manifest_has_target": target_ok,
                "has_code_false": has_code,
                "resource_entries": len(overlay_dump),
                "missing_entries": missing,
                "badging_package": re.search(r"package: name='([^']*)'", badging).group(1)
                if re.search(r"package: name='([^']*)'", badging) else None,
                "ok": (not dex) and target_ok and has_code and not missing}

    # ------------------------------------------------------------------
    def mapping_table(self, mappings) -> str:
        rows = ["| resource | before | after | role | token | conf |",
                "|---|---|---|---|---|---|"]
        for m in sorted(mappings, key=lambda x: (-x["confidence"], x["resource"])):
            before = m.get("value_before") or "; ".join(
                "%s=%s" % (c or "default", v) for c, v in m["values_before"].items())
            if len(before) > 44:
                before = before[:41] + "..."
            rows.append("| `%s` | %s | **%s** | %s | `%s` | %.2f |"
                        % (m["resource"], before, m["value_after"], m["role"],
                           m["token"], m["confidence"]))
        return "\n".join(rows)

    def report(self, mappings, built, verify, notes) -> str:
        p = self.profile
        lines = [
            "# Calendar theme-factory run",
            "",
            "Generated by `repo/tools/theme-factory` from `design/tokens/ztmetro.tokens.json`.",
            "",
            "| | |", "|---|---|",
            "| target | `%s` %s (%s) |" % (p["package"]["package"], p["package"].get("label") or "",
                                           p["package"].get("versionName") or ""),
            "| source APK sha256 | `%s` |" % p["apk"]["sha256"],
            "| overlay package | `%s` |" % self.overlay_package,
            "| overlay APK | `%s` |" % os.path.basename(built["apk"]),
            "| overlay sha256 | `%s` |" % built["sha256"],
            "| overlay entries | %d |" % built["entries"],
            "| resource-only | %s |" % ("yes" if verify["is_resource_only"] else "NO"),
            "| targetPackage present | %s |" % verify["manifest_has_target"],
            "| min confidence | %.2f |" % self.min_confidence,
            "",
            "## Mapping table",
            "",
            self.mapping_table(mappings),
            "",
            "## Build notes",
            "",
        ]
        for n in notes:
            lines.append("* " + n)
        lines += [
            "* overlay is signed with the throwaway key `%s` (same key as the other ztmetro overlays)" % KEYSTORE,
            "* no `<overlayable>` in the target: a preinstalled overlay may map any resource (docs/10b-overlay-and-hook-facts.md)",
            "",
        ]
        return "\n".join(lines)

    # ------------------------------------------------------------------
    def write_verify_script(self, dest: str, mappings, built):
        expected = [{"resource": m["resource"], "configs": m["configs_after"]}
                    for m in mappings]
        script = VERIFY_TEMPLATE.replace("@@EXPECTED@@", json.dumps(expected, indent=1)) \
            .replace("@@TARGET@@", self.package) \
            .replace("@@OVERLAY@@", os.path.basename(built["apk"]))
        path = os.path.join(dest, "verify.sh")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(script)
        os.chmod(path, 0o755)
        return path


VERIFY_TEMPLATE = r"""#!/usr/bin/env bash
# GENERATED by repo/tools/theme-factory — compare the overlay against expectations
# with aapt2, then optionally diff two screenshots.
#
#   ./verify.sh <target-apk> [overlay-apk]
#   ./verify.sh --screenshots before.png after.png [--crop x,y,w,h]
#
# Exit 0 only when every expected resource is present in the overlay with the
# expected value and the target really defines the name.
set -uo pipefail
TARGET_APK="${1:-}"
OVERLAY_APK="${2:-$(dirname "$0")/@@OVERLAY@@}"
TARGET_PKG="@@TARGET@@"
AAPT2="${AAPT2:-$(command -v aapt2 || echo "$HOME/android-sdk/build-tools/36.0.0/aapt2")}"

if [ "${TARGET_APK:-}" = "--screenshots" ]; then
  BEFORE="${2:-}"; AFTER="${3:-}"; CROP="${5:-}"
  python3 - "$BEFORE" "$AFTER" "$CROP" <<'PY'
import sys
from PIL import Image, ImageChops
before, after, crop = sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else ""
a, b = Image.open(before).convert("RGB"), Image.open(after).convert("RGB")
if a.size != b.size:
    print("size differs %s vs %s -> layout/behaviour changed, not a pure restyle" % (a.size, b.size))
    b = b.resize(a.size)
if crop:
    x, y, w, h = (int(v) for v in crop.split(","))
    a, b = a.crop((x, y, x + w, y + h)), b.crop((x, y, x + w, y + h))
diff = ImageChops.difference(a, b)
px = list(diff.getdata())
changed = sum(1 for p in px if max(p) > 8)
total = len(px)
print("changed pixels: %d / %d = %.2f%% (threshold >8/255 on any channel)" % (changed, total, 100.0 * changed / total))
diff.save("screenshots-diff.png")
print("wrote screenshots-diff.png")
PY
  exit $?
fi

[ -f "$TARGET_APK" ] || { echo "usage: $0 <target-apk> [overlay-apk]" >&2; exit 2; }
[ -f "$OVERLAY_APK" ] || { echo "missing overlay: $OVERLAY_APK" >&2; exit 2; }
EXPECTED_JSON="$(dirname "$0")/expected-mappings.json"
fail=0

echo "== target:  $TARGET_APK"
echo "== overlay: $OVERLAY_APK"

python3 - "$AAPT2" "$TARGET_APK" "$OVERLAY_APK" "$EXPECTED_JSON" <<'PY'
import json, re, subprocess, sys

aapt2, target, overlay, expected_path = sys.argv[1:5]

def dump(apk):
    out = subprocess.run([aapt2, "dump", "resources", apk], capture_output=True, text=True).stdout
    res, cur = {}, None
    for line in out.splitlines():
        m = re.match(r"    resource (0x[0-9a-f]+) (\S+)/(\S+?)(?: PUBLIC)?\s*$", line)
        if m:
            cur = "%s/%s" % (m.group(2), m.group(3)); res[cur] = {}
            continue
        v = re.match(r"      \(([^)]*)\) (.*)$", line)
        if v and cur:
            res[cur][v.group(1).strip()] = v.group(2).strip()
    return res

t, o = dump(target), dump(overlay)
exp = json.load(open(expected_path))
ok = bad = missing = 0
for item in exp:
    key, configs = item["resource"], item["configs"]
    if key not in t:
        print("MISSING-IN-TARGET %s" % key); missing += 1; continue
    if key not in o:
        print("MISSING-IN-OVERLAY %s" % key); missing += 1; continue
    for config, want in configs.items():
        got = o[key].get(config)
        if key.split("/")[0] == "color":
            want = "#" + want.lstrip("#")[-6:]
            got = got if got is None else "#" + got.lstrip("#")[-6:]
        if got != want:
            print("MISMATCH %s [%s]: want %s got %s" % (key, config or "default", want, got)); bad += 1
        else:
            ok += 1
print("\nverify: %d values OK, %d mismatched, %d missing" % (ok, bad, missing))
sys.exit(0 if bad == 0 and missing == 0 else 1)
PY
rc=$?
[ $rc -ne 0 ] && echo "VERIFY: FAIL" || echo "VERIFY: PASS"
echo
echo "configure the overlay on the device (see docs/23-theme-factory.md):"
echo "  su 0 cmd overlay enable --user 0 $(basename "$(dirname "$0")")"
exit $rc
"""
