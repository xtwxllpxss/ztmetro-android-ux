"""Command line entry point for the ztMetro theme factory.

    theme-factory analyse  --apk Etar.apk --out evidence/theme-factory/app-profile.json
    theme-factory generate --profile evidence/theme-factory/app-profile.json --dry-run
    theme-factory generate --profile ... --out evidence/theme-factory/overlay
    theme-factory apply    --profile ... --out evidence/theme-factory/overlay

Exit codes: 0 ok, 1 verification/policy failure, 2 bad usage.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from .aapt import AndroidTools, ToolError
from .analyze import Analyzer
from .apply import commands, watchdog_script
from .generate import Generator, load_registry
from .tokens import Tokens
from .util import log, sha256_file

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))
EVIDENCE = os.path.join(REPO, "evidence", "theme-factory")


def _common(p):
    p.add_argument("--sdk", default=None, help="Android SDK root (default: $ANDROID_SDK or ~/android-sdk)")
    p.add_argument("--jdk", default=None, help="JDK root (default: $ANDROID_JDK or ~/android-jdk)")
    p.add_argument("--tokens", default=None, help="tokens JSON (default: design/tokens/ztmetro.tokens.json)")


def cmd_analyse(args) -> int:
    tools = AndroidTools(sdk=args.sdk, jdk=args.jdk)
    tokens = Tokens(args.tokens)
    analyzer = Analyzer(args.apk, tokens, tools=tools, scan_layouts=not args.no_layouts,
                        target_sdk=args.target_sdk)
    analyzer.load()
    profile = analyzer.profile()
    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(profile, fh, indent=1, sort_keys=False)
        fh.write("\n")
    by_role = {}
    for m in profile["mappings"]:
        by_role[m["role"]] = by_role.get(m["role"], 0) + 1
    log("")
    log("== app-profile  %s" % out)
    log("   package       %s" % profile["package"]["package"])
    log("   sha256        %s" % profile["apk"]["sha256"])
    log("   mappings      %s" % ", ".join("%s=%d" % kv for kv in sorted(by_role.items())))
    log("   coverage      %s" % json.dumps(profile["coverage"]))
    log("   contrast      %d check(s), %d escalated"
        % (len(profile["safety"]["checks"]),
           len([c for c in profile["safety"]["checks"] if not c["passes_4_5"]])))
    if args.print_table:
        _print_table(profile["mappings"], args.print_limit)
    return 0


def cmd_generate(args) -> int:
    with open(args.profile, "r", encoding="utf-8") as fh:
        profile = json.load(fh)
    tokens = Tokens(args.tokens)
    tools = AndroidTools(sdk=args.sdk, jdk=args.jdk)
    roles = {"surface", "text", "accent", "surface_key", "destructive", "success"}
    if not args.no_radii:
        roles.add("radius")
    if not args.no_type:
        roles.add("type")
    out = os.path.abspath(args.out or os.path.join(EVIDENCE, "overlay"))
    gen = Generator(profile, tokens, out, tools=tools, min_confidence=args.min_confidence,
                    include_roles=roles, target_name=args.target_name,
                    force_package=args.force_package)
    notes = gen.check_policy()
    mappings = gen.selected()
    if not mappings:
        log("no mapping passed the filters; nothing to build")
        return 1

    if args.dry_run:
        log("== DRY RUN: %d mapping(s) for %s (no APK built)" % (len(mappings), gen.package))
        _print_table(mappings, args.print_limit)
        _summary(mappings)
        log("")
        log("would write: %s/AndroidManifest.xml + %d values file(s); overlay package %s"
            % (out, len(gen.build_tree(mappings)), gen.overlay_package))
        return 0

    os.makedirs(out, exist_ok=True)
    tree = gen.write_sources(mappings, out)
    built = gen.build(out, mappings)
    verify = gen.verify_apk(built, mappings)
    expected = [{"resource": m["resource"], "configs": m["configs_after"]} for m in mappings]
    with open(os.path.join(out, "expected-mappings.json"), "w", encoding="utf-8") as fh:
        json.dump(expected, fh, indent=1)
    gen.write_verify_script(out, mappings, built)
    with open(os.path.join(out, "mapping-table.md"), "w", encoding="utf-8") as fh:
        fh.write(gen.report(mappings, built, verify, notes) + "\n")
    with open(os.path.join(out, "rollback-watchdog.sh"), "w", encoding="utf-8") as fh:
        fh.write(watchdog_script(gen.package, gen.overlay_package,
                                 profile["package"].get("launchable")))
    os.chmod(os.path.join(out, "rollback-watchdog.sh"), 0o755)
    built.update({"overlay_package": gen.overlay_package, "target": gen.package,
                  "verify": verify, "notes": notes})
    with open(os.path.join(out, "build.json"), "w", encoding="utf-8") as fh:
        json.dump(built, fh, indent=1)

    log("")
    log("== overlay       %s" % built["apk"])
    log("   sha256        %s" % built["sha256"])
    log("   size          %d bytes, %d entries" % (built["size"], built["entries"]))
    log("   resource-only %s | targetPackage %s | missing %d"
        % (verify["is_resource_only"], verify["manifest_has_target"],
           len(verify["missing_entries"])))
    log("   verify.sh     %s" % os.path.join(out, "verify.sh"))
    log("   apply         repo/scripts/theme-factory.sh apply --profile %s --out %s"
        % (args.profile, out))
    return 0 if verify["ok"] else 1


def cmd_apply(args) -> int:
    with open(args.profile, "r", encoding="utf-8") as fh:
        profile = json.load(fh)
    out = os.path.abspath(args.out or os.path.join(EVIDENCE, "overlay"))
    build = {}
    bpath = os.path.join(out, "build.json")
    if os.path.isfile(bpath):
        with open(bpath, "r", encoding="utf-8") as fh:
            build = json.load(fh)
    apk_name = build.get("apk_name") or "ztmetro-%s-rro.apk" % profile["package"]["package"]
    overlay_package = build.get("overlay_package") or "org.ztos.overlay.factory.%s" % \
        profile["package"]["package"].replace(".", "_")
    activity = args.activity or profile["package"].get("launchable")
    lines = commands(profile["package"]["package"], overlay_package, apk_name,
                     activity=activity, module_path=args.module_path, serial=args.serial,
                     settle_seconds=args.settle, out_dir=out)
    print("\n".join(lines))
    if not args.dry:
        os.makedirs(out, exist_ok=True)
        wd = os.path.join(out, "rollback-watchdog.sh")
        with open(wd, "w", encoding="utf-8") as fh:
            fh.write(watchdog_script(profile["package"]["package"], overlay_package,
                                     activity, seconds=30, serial=args.serial))
        os.chmod(wd, 0o755)
        log("\nwrote %s" % wd)
    return 0


def _print_table(mappings, limit=None):
    rows = [("RESOURCE", "BEFORE", "AFTER", "ROLE", "TOKEN", "CONF")]
    for m in sorted(mappings, key=lambda x: (-x["confidence"], x["resource"])):
        before = m.get("value_before") or next(iter(m.get("values_before", {}).values()), "")
        rows.append((m["resource"], str(before)[:26], str(m["value_after"]),
                     m["role"], m["token"], "%.2f" % m["confidence"]))
    widths = [max(len(r[i]) for r in rows) for i in range(6)]
    for i, row in enumerate(rows):
        line = "  ".join(row[j].ljust(widths[j]) for j in range(6))
        print(line)
        if i == 0:
            print("  ".join("-" * w for w in widths))
        if limit and i >= limit:
            print("  ... %d more" % (len(rows) - limit - 1))
            break


def _summary(mappings):
    by_role, by_config = {}, {}
    for m in mappings:
        by_role[m["role"]] = by_role.get(m["role"], 0) + 1
        for c in m["configs_after"]:
            by_config[c or "default"] = by_config.get(c or "default", 0) + 1
    log("")
    log("   roles:   %s" % ", ".join("%s=%d" % kv for kv in sorted(by_role.items())))
    log("   configs: %s" % ", ".join("%s=%d" % kv for kv in sorted(by_config.items())))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="theme-factory.sh",
                                     description="ztMetro resource-level theme factory")
    sub = parser.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("analyse", aliases=["analyze"], help="APK -> app-profile JSON")
    a.add_argument("--apk", required=True)
    a.add_argument("--out", default=os.path.join(EVIDENCE, "app-profile.json"))
    a.add_argument("--target-sdk", type=int, default=36)
    a.add_argument("--no-layouts", action="store_true", help="skip the layout xmltree scan")
    a.add_argument("--print-table", action="store_true")
    a.add_argument("--print-limit", type=int, default=0)
    _common(a)

    g = sub.add_parser("generate", help="app-profile -> signed RRO APK")
    g.add_argument("--profile", required=True)
    g.add_argument("--out", default=os.path.join(EVIDENCE, "overlay"))
    g.add_argument("--dry-run", action="store_true", help="print the mapping table, build nothing")
    g.add_argument("--min-confidence", type=float, default=0.6)
    g.add_argument("--no-radii", action="store_true")
    g.add_argument("--no-type", action="store_true")
    g.add_argument("--target-name", default=None,
                   help="android:targetName when the target declares <overlayable>")
    g.add_argument("--force-package", action="store_true",
                   help="build even if the package is not in tools/theme-factory/registry.json")
    g.add_argument("--print-limit", type=int, default=0)
    _common(g)

    ap = sub.add_parser("apply", help="print the device commands (never runs adb)")
    ap.add_argument("--profile", required=True)
    ap.add_argument("--out", default=os.path.join(EVIDENCE, "overlay"))
    ap.add_argument("--activity", default=None)
    ap.add_argument("--module-path", default="/data/adb/modules/ztmetro-overlays/system/product/overlay")
    ap.add_argument("--serial", default=None)
    ap.add_argument("--settle", type=int, default=20)
    ap.add_argument("--dry", action="store_true", help="print only, do not write the watchdog")
    _common(ap)

    args = parser.parse_args(argv)
    try:
        if args.cmd in ("analyse", "analyze"):
            return cmd_analyse(args)
        if args.cmd == "generate":
            return cmd_generate(args)
        if args.cmd == "apply":
            return cmd_apply(args)
    except ToolError as exc:
        log("ERROR: %s" % exc)
        return 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
