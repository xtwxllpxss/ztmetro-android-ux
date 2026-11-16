"""`analyse`: read a stock APK's resources and propose a ztMetro mapping.

The output is a JSON app-profile: every candidate resource, the role the
heuristics gave it, the token it maps to, a confidence and the evidence that
produced that confidence.  Nothing is hidden: resources the factory cannot reach
(hardcoded colours in layouts, selectors, styles, dynamic system colours) are
listed in `unmanaged` with a reason.

Heuristics (all deterministic, no ML):
  surface  = low-chroma colour used as a background / windowBackground / M3 surface
  text     = colour used in a textColor / colorOn* attribute
  accent   = high-chroma colour used for colorPrimary / colorAccent / tint
  radius   = dimen whose name says corner/radius
  type     = app dimension expressed in sp
Material semantic names (m3_sys_color_*) short-circuit the statistics with high
confidence because their role is fixed by the Material spec, not inferred.
"""

from __future__ import annotations

import concurrent.futures
import json
import os
import re
import time

from .aapt import AndroidTools
from .restable import ResourceTable
from .util import (chroma, contrast_ratio, log, luminance, parse_color,
                   rgb_hex, sha256_file, to_hex)

LIB_PREFIXES = ("abc_", "mtrl_", "m3_", "design_", "material_", "support_",
                "notification_", "preference_", "androidx_", "cardview_",
                "compat_", "tooltip_", "appcompat_")

# Attr name -> usage role.  The names are what aapt2 prints for framework attrs
# (resolved through android.jar) and for app attrs (defined in the APK).
ACCENT_ATTRS = {
    "coloraccent", "colorprimary", "colorprimarydark", "colorprimaryvariant",
    "colorsecondary", "colorsecondarycontainer", "colorcontrolactivated",
    "colorhighlight", "colortint", "colorbuttonnormal",
}
SURFACE_ATTRS = {
    "background", "windowbackground", "colorbackground", "colorbackgroundfloating",
    "colorsurface", "colorsurfacevariant", "navigationbarcolor", "statusbarcolor",
    "itembackground", "colorcontrolnormal",
}
TEXT_ATTRS = {
    "textcolor", "textcolorprimary", "textcolorsecondary", "textcolorhint",
    "textcolorprimaryinverse", "textcolorsecondaryinverse", "textcolortertiary",
    "textcolorlink", "textappearance", "coloronbackground", "coloronprimary",
    "coloronsecondary", "coloronsurface", "coloronerror", "colorontertiary",
}


def attr_role(name: str | None) -> str | None:
    if not name:
        return None
    n = name.lower()
    if n in TEXT_ATTRS or n.startswith("coloron") or n.endswith("textcolor"):
        return "text"
    if n in SURFACE_ATTRS or n.startswith("colorsurface") or n.endswith("background"):
        return "surface"
    if n in ACCENT_ATTRS or n.startswith("colorprimary") or n.startswith("colorsecondary"):
        return "accent"
    return None


def is_lib_name(name: str) -> bool:
    return name.startswith(LIB_PREFIXES)


class Analyzer:
    def __init__(self, apk: str, tokens, tools: AndroidTools | None = None,
                 scan_layouts: bool = True, target_sdk: int = 36):
        self.apk = apk
        self.tokens = tokens
        self.tools = tools or AndroidTools()
        self.scan_layouts = scan_layouts
        self.target_sdk = target_sdk
        self.table = ResourceTable()
        self.fw = {}
        self.badging = {}
        self.usage = {}       # "color/x" -> [ {role, kind, where} ]
        self.hardcoded = []   # {kind, where, attr, value}
        self.layout_theme_refs = set()
        self.styles_by_id = {}
        self._resolve_cache = {}

    # ------------------------------------------------------------------
    def load(self):
        log("== aapt2 dump resources %s" % self.apk)
        self.table.parse(self.tools.dump_resources(self.apk))
        log("   package %s: %d resources, %d colour resources"
            % (self.table.package, len(self.table.resources),
               len(self.table.of_type("color"))))
        self.badging = parse_badging(self.tools.dump_badging(self.apk))
        jar = self.tools.platform_jar(self.target_sdk)
        log("== framework names from %s" % jar)
        self.fw = self.tools.framework_attr_names(jar)
        self._scan_styles()
        if self.scan_layouts:
            self._scan_layouts()
        return self

    # -- name resolution -------------------------------------------------
    def attr_name(self, key: str) -> str | None:
        if key.startswith("0x"):
            return self.fw["attrs"].get(key)
        return key

    def fw_color_name(self, rid: str) -> str | None:
        return self.fw["colors"].get(rid)

    # -- resolution of a colour resource to a literal ---------------------
    def resolve(self, key: str, config: str = "", depth: int = 0):
        """-> (color|None, trail[list], dynamic_name|None)"""
        cache_key = (key, config, depth)
        if cache_key in self._resolve_cache:
            return self._resolve_cache[cache_key]
        res = self.table.get(key)
        trail = [key]
        result = (None, trail, None)
        if res is not None and depth < 8:
            val = res.values.get(config) or res.default()
            if val is not None:
                if val.kind == "color":
                    result = (val.color, trail, None)
                elif val.kind == "reference":
                    if val.ref_name:
                        if val.ref_name.startswith(("color/", "drawable/")):
                            sub = self.table.get(val.ref_name)
                            if sub is not None and val.ref_name.startswith("color/"):
                                c, sub_trail, dyn = self.resolve(val.ref_name, config, depth + 1)
                                result = (c, trail + sub_trail, dyn)
                    elif val.ref_id:
                        name = self.fw_color_name(val.ref_id)
                        if name:
                            result = (None, trail, "android:color/%s" % name)
        self._resolve_cache[cache_key] = result
        return result

    # -- scans -----------------------------------------------------------
    def _scan_styles(self):
        for style in self.table.of_type("style"):
            self.styles_by_id[style.id] = style
            for config, val in style.values.items():
                if val.kind != "style":
                    continue
                for attr_key, raw in val.attrs:
                    name = self.attr_name(attr_key)
                    role = attr_role(name)
                    ref = self._ref_from_raw(raw)
                    if role and ref:
                        self.usage.setdefault(ref, []).append(
                            {"role": role, "kind": "style", "where": style.key,
                             "config": config, "attr": name or attr_key})
                    if raw.startswith("#"):
                        self.hardcoded.append({"kind": "style-hardcoded-color",
                                               "where": style.key, "attr": name or attr_key,
                                               "config": config, "value": raw})

    def _ref_from_raw(self, raw: str) -> str | None:
        m = re.match(r"@(?:[\w.]+:)?([a-z-]+)/(\S+)$", raw)
        if m:
            return "%s/%s" % (m.group(1), m.group(2))
        m = re.match(r"@(0x[0-9a-f]+)$", raw)
        if m:
            res = self.table.by_id.get(m.group(1))
            return res.key if res else None
        return None

    def _scan_layouts(self):
        layouts = self.table.of_type("layout")
        files = []
        for res in layouts:
            val = res.default()
            if val is not None and val.kind == "file" and val.file:
                files.append((res, val.file))
        log("== aapt2 dump xmltree over %d layouts" % len(files))

        def one(item):
            res, path = item
            return res, path, self.tools.dump_xmltree(self.apk, path)

        results = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            for res, path, dump in pool.map(one, files):
                results.append((res, path, dump))
        for res, path, dump in results:
            for line in dump.splitlines():
                m = re.match(r"\s+A: (\S+):(\w+)\(0x[0-9a-f]+\)=(.*)$", line)
                if not m:
                    continue
                _, attr, raw = m.group(1), m.group(2), m.group(3).strip()
                role = attr_role(attr)
                ref = self._ref_from_raw(raw)
                if role and ref:
                    self.usage.setdefault(ref, []).append(
                        {"role": role, "kind": "layout", "where": res.key, "attr": attr})
                if raw.startswith("#") and attr in (
                        "background", "textColor", "tint", "color", "strokeColor", "fillColor"):
                    self.hardcoded.append({"kind": "layout-hardcoded-color", "where": res.key,
                                           "attr": attr, "value": raw, "file": path})

    # -- theme discovery --------------------------------------------------
    def theme_info(self):
        manifest = self.tools.dump_xmltree(self.apk, "AndroidManifest.xml")
        app_theme = None
        overlayable = False
        activity_themes = []
        tag_stack = []
        for line in manifest.splitlines():
            em = re.match(r"\s*E: (\S+)", line)
            if em:
                tag = em.group(1)
                if tag == "activity":
                    activity_themes.append(None)
                tag_stack.append(tag)
                if tag == "overlayable":
                    overlayable = True
                continue
            if re.match(r"\s*E: ", line):
                continue
            am = re.match(r"\s+A: \S+:theme\(0x01010000\)=@(0x[0-9a-f]+)", line)
            if am:
                if tag_stack and tag_stack[-1] == "application" and app_theme is None:
                    app_theme = am.group(1)
                elif tag_stack and tag_stack[-1] == "activity":
                    activity_themes[-1] = am.group(1)
        chain = []
        cur = app_theme
        seen = set()
        while cur and cur not in seen and len(chain) < 12:
            seen.add(cur)
            res = self.table.by_id.get(cur)
            if res is None:
                chain.append({"id": cur, "name": None, "reason": "framework style"})
                break
            chain.append({"id": cur, "name": res.key})
            val = res.default()
            parent = (val.style_parent if val else None) or (val.style_parent_id if val else None)
            if parent and parent.startswith("0x"):
                cur = parent
                res = self.table.by_id.get(cur)
                if res is None:
                    break
            else:
                cur = parent
                nxt = self.table.get(cur) if cur else None
                if nxt is None:
                    chain.append({"id": None, "name": cur, "reason": "framework/absent parent"})
                    break
        # styles whose v31 config points straight at framework system colours
        dynamic_styles = []
        for style in self.table.of_type("style"):
            for config, val in style.values.items():
                if val.kind != "style" or config.strip() != "v31":
                    continue
                if any(re.match(r"@0x010[0-9a-f]{4}$", raw) for _, raw in val.attrs):
                    dynamic_styles.append(style.key)
                    break
        return {"app_theme": chain[0] if chain else None, "chain": chain,
                "overlayable": overlayable,
                "activity_themes": [t for t in activity_themes if t],
                "dynamic_color_styles": sorted(dynamic_styles)}

    # -- classification ---------------------------------------------------
    def _role_from_m3(self, name: str) -> str | None:
        if not name.startswith("m3_sys_color_"):
            return None
        suffix = name[len("m3_sys_color_"):]
        if suffix.startswith("light_"):
            suffix = suffix[6:]
        elif suffix.startswith("dark_"):
            suffix = suffix[5:]
        if suffix.startswith("on_"):
            return "text"
        if suffix in ("surface", "background") or suffix.startswith("surface_container"):
            return "surface"
        if suffix.startswith("surface_"):
            return "surface"
        if suffix.startswith("outline") or suffix.startswith("scrim"):
            return "surface_key"
        if suffix.startswith("error"):
            return "destructive"
        if suffix.startswith(("primary", "secondary", "tertiary")):
            return "accent"
        if suffix.startswith("inverse_"):
            return "surface" if "inverse_surface" in suffix else "text"
        return None

    def _surface_token(self, name: str, color) -> tuple:
        n = name.lower()
        if any(k in n for k in ("outline", "divider", "separator", "stroke")):
            return "surface_key", "line/outline colour: ztmetro has no hairlines, keep a single dark grey"
        if any(k in n for k in ("scrim",)):
            return "surface_empty", "scrim"
        if any(k in n for k in ("card", "dialog", "menu", "popup", "sheet", "panel",
                                "container", "variant", "toolbar", "app_bar", "row", "item")):
            return "surface_off", "raised container (card/dialog/menu/row) -> #1F1F1F keeps rows readable on black"
        if any(k in n for k in ("disabled", "empty")):
            return "surface_empty", "empty/disabled surface"
        return "surface", "page surface -> pure black"

    def _text_token(self, name: str, color) -> tuple:
        n = name.lower()
        if "disabled" in n:
            return "text_disabled", "disabled text"
        if any(k in n for k in ("hint", "muted", "placeholder")):
            return "text_muted", "hint/placeholder text"
        if any(k in n for k in ("secondary", "variant", "caption", "subtitle", "label")):
            return "text_secondary", "secondary text"
        if color is not None:
            lum = luminance(color)
            if lum >= 0.72:
                return "text_primary", "light text (lum %.2f) stays white" % lum
            if lum >= 0.35:
                return "text_secondary", "mid grey (lum %.2f) -> #999999" % lum
            return "text_primary", "dark text (lum %.2f) on a light surface becomes white on black" % lum
        return "text_primary", "text colour with no literal value"

    def _accent_token(self, name: str, color, as_text: bool) -> tuple:
        n = name.lower()
        if as_text:
            return "accent_light", "accent used as text/tint: #0050EF on black is 2.4:1, so use light1 (5.4:1)"
        if any(k in n for k in ("dark", "container", "variant")):
            return "accent_dark", "accent container/dark variant"
        if any(k in n for k in ("secondary", "tertiary", "light")):
            return "accent_light", "secondary accent"
        return "accent", "accent -> cobalt #0050EF"

    def classify(self, res) -> dict | None:
        roles = self.usage.get(res.key, [])
        role_counts = {"surface": 0, "text": 0, "accent": 0}
        for u in roles:
            role_counts[u["role"]] = role_counts.get(u["role"], 0) + 1
        name = res.name
        # representative literal (prefer the default config)
        color, trail, dynamic = None, [], None
        for cfg in ([""] + [c for c in res.configs() if c != ""]):
            color, trail, dynamic = self.resolve(res.key, cfg)
            if color is not None or dynamic:
                break
        if color is None and dynamic is None and res.default() and res.default().kind == "file":
            return {"resource": res.key, "type": res.type, "role": "selector",
                    "reason": "colour state list (XML selector); needs a generated selector, not a value",
                    "confidence": 0.0}

        m3_role = self._role_from_m3(name)
        evidence = []
        role = None
        if m3_role == "text":
            role, conf = "text", 0.95
            evidence.append("Material semantic name (%s) fixes the role" % name)
        elif m3_role == "surface":
            role, conf = "surface", 0.95
            evidence.append("Material semantic name (%s) fixes the role" % name)
        elif m3_role == "surface_key":
            role, conf = "surface_key", 0.95
            evidence.append("Material semantic outline/scrim")
        elif m3_role == "accent":
            role, conf = "accent", 0.95
            evidence.append("Material semantic name (%s) fixes the role" % name)
        elif m3_role == "destructive":
            role, conf = "destructive", 0.9
            evidence.append("Material error role")
        else:
            text_hits = role_counts["text"]
            surface_hits = role_counts["surface"]
            accent_hits = role_counts["accent"]
            n = name.lower()
            name_text = any(k in n for k in ("text", "on_", "foreground", "hint", "label"))
            name_surface = any(k in n for k in ("background", "surface", "window", "panel",
                                                "dialog", "card", "sheet", "menu"))
            name_accent = any(k in n for k in ("accent", "primary", "highlight", "selected"))
            chroma_ok = color is not None and chroma(color) > 0.25
            scores = {
                "text": 2.0 * text_hits + (2.0 if name_text else 0),
                "surface": 2.0 * surface_hits + (2.0 if name_surface else 0) +
                           (0.5 if color is not None and chroma(color) < 0.15 else 0),
                "accent": 2.0 * accent_hits + (2.0 if name_accent else 0) +
                          (0.5 if chroma_ok else 0),
            }
            best = max(scores, key=scores.get)
            if scores[best] < 2.0:
                return {"resource": res.key, "type": res.type, "role": "unmapped",
                        "reason": "no usage, no name feature, no M3 semantic name",
                        "confidence": 0.0}
            role = best
            conf = 0.55 + 0.08 * min(scores[best], 4.0)
            if name_text:
                evidence.append("name contains a text word")
            if name_surface:
                evidence.append("name contains a surface word")
            if name_accent:
                evidence.append("name contains an accent word")
            for role_name, hits in role_counts.items():
                if hits:
                    evidence.append("used as %s in %d attribute(s)" % (role_name, hits))
            if color is not None:
                evidence.append("chroma %.2f, luminance %.2f" % (chroma(color), luminance(color)))
        if dynamic:
            evidence.append("value is a runtime system colour (%s) supplied by Material dynamic colour" % dynamic)
        if color is not None:
            evidence.append("literal %s" % rgb_hex(color))

        as_text = role_counts["text"] > 0
        if role == "surface":
            token, why = self._surface_token(name, color)
        elif role == "text":
            token, why = self._text_token(name, color)
        elif role == "destructive":
            token, why = "destructive", "error/destructive role"
        elif role == "surface_key":
            token = "surface_key"
            why = "outline: ztmetro has no hairlines; one dark grey keeps affordances visible"
        elif role == "accent":
            token, why = self._accent_token(name, color, as_text)
        else:
            return None
        evidence.append(why)
        token_value = self.tokens.role(token)["value"]
        conf = round(min(conf, 0.97), 3)
        values_before = {}
        configs_after = {}
        for cfg, val in res.values.items():
            if val.kind == "file":
                continue
            c, _, dyn = self.resolve(res.key, cfg)
            values_before[cfg] = (rgb_hex(c) if c else ("dynamic:%s" % dyn if dyn else val.raw))
            configs_after[cfg] = token_value
        if not configs_after:
            configs_after[""] = token_value
        return {"resource": res.key, "type": "color", "role": role, "token": token,
                "value_after": token_value, "values_before": values_before,
                "configs_after": configs_after, "confidence": conf,
                "evidence": evidence}

    # -- top level --------------------------------------------------------
    def profile(self) -> dict:
        theme = self.theme_info()
        colors = []
        unmapped_colors = []
        for res in self.table.of_type("color"):
            try:
                m = self.classify(res)
            except Exception as exc:  # keep the analyser running, record the gap
                m = {"resource": res.key, "type": "color", "role": "error",
                     "reason": "classify failed: %s" % exc, "confidence": 0.0}
            if m is None:
                continue
            used = res.key in self.usage
            if m["role"] in ("unmapped", "selector", "error"):
                if used:
                    unmapped_colors.append(m)
                continue
            colors.append(m)

        dimens = []
        for res in self.table.of_type("dimen"):
            val = res.default()
            if val is None or val.kind != "dimension":
                continue
            name = res.name.lower()
            app_owned = not is_lib_name(name)
            if any(k in name for k in ("corner", "radius")) and (val.dim_unit or "").startswith("d"):
                if val.dim_value and val.dim_value > 0:
                    dimens.append({"resource": res.key, "type": "dimen", "role": "radius",
                                   "token": "metrics.corner_radius_dp",
                                   "value_before": val.raw, "value_after": "0dp",
                                   "configs_after": {c: "0dp" for c in res.configs()},
                                   "confidence": 0.9 if app_owned else 0.8,
                                   "evidence": ["name says corner/radius", "dp value %.1f" % val.dim_value]})
            elif app_owned and val.dim_unit == "sp":
                ramp = self.tokens.roles["ramp"]
                target = min(ramp, key=lambda e: abs(e["sp"] - val.dim_value))
                dimens.append({"resource": res.key, "type": "dimen", "role": "type",
                               "token": target["token"],
                               "value_before": val.raw,
                               "value_after": ("%gsp" % target["sp"]),
                               "configs_after": {c: ("%gsp" % target["sp"]) for c in res.configs()},
                               "confidence": 0.6,
                               "evidence": ["app-owned sp dimension", "nearest ramp step %s (%.1fsp)"
                                            % (target["name"], target["sp"])]})

        # contrast validation for text/accent mappings
        safety = self._contrast_checks(colors)
        app_color_resources = [r for r in self.table.of_type("color")]
        used_total = len([r for r in app_color_resources if r.key in self.usage])
        mapped_used = len([m for m in colors if m["resource"] in self.usage])
        unmanaged = list(self.hardcoded[:40])
        unmanaged.append({"kind": "styles", "count": len(self.table.of_type("style")),
                          "reason": "style bags are not replaced: an overlay style replaces the whole "
                                    "bag (including parent), which destroys a theme's Material base"})
        unmanaged.append({"kind": "drawables", "count": len(self.table.of_type("drawable")),
                          "reason": "shape/vector drawables (inline corner radii, gradients) not overridden"})
        unmanaged.append({"kind": "drawable-colors", "count": len(unmapped_colors),
                          "reason": "colour state lists need generated selectors"})
        profile = {
            "schema": "ztos.theme-factory.app-profile/1",
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "generator": {"tool": "repo/tools/theme-factory", "version": "0.1.0",
                          "aapt2": self._aapt2_version()},
            "apk": {"path": os.path.abspath(self.apk), "sha256": sha256_file(self.apk),
                    "size": os.path.getsize(self.apk)},
            "package": self.badging,
            "theme": theme,
            "tokens": {"path": self.tokens.path,
                       "surface": self.tokens.role("surface")["value"],
                       "accent": self.tokens.role("accent")["value"],
                       "text_primary": self.tokens.role("text_primary")["value"],
                       "radius_dp": self.tokens.role("radius")["value"]},
            "mappings": colors + dimens,
            "safety": safety,
            "coverage": {"colour_resources_total": len(app_color_resources),
                         "colour_resources_used": used_total,
                         "colour_resources_mapped": mapped_used,
                         "mapped_ratio_of_used": (round(mapped_used / used_total, 3) if used_total else 0.0),
                         "layouts_scanned": len(self.table.of_type("layout")) if self.scan_layouts else 0},
            "unmanaged": unmanaged,
            "unmapped_color_resources": unmapped_colors[:60],
        }
        return profile

    def _aapt2_version(self) -> str:
        proc = self.tools.run([self.tools.aapt2, "version"], check=False)
        return (proc.stdout or "").strip()

    def _contrast_checks(self, mappings):
        checks = []
        surface = parse_color(self.tokens.role("surface")["value"])
        for m in mappings:
            if m["role"] not in ("text", "accent"):
                continue
            col = parse_color(m["value_after"])
            if not col:
                continue
            ratio = contrast_ratio(col, surface)
            ok = ratio >= 4.5
            entry = {"resource": m["resource"], "on": "color.neutrals.black",
                     "ratio": round(ratio, 2), "passes_4_5": ok}
            if not ok:
                # escalate to the lightest acceptable token of the same family
                fallback = self.tokens.role("text_primary") if m["role"] == "text" \
                    else self.tokens.role("accent_light")
                m["value_after"] = fallback["value"]
                m["configs_after"] = {c: fallback["value"] for c in m["configs_after"]}
                m["token"] = fallback["token"]
                m["confidence"] = round(min(m["confidence"], 0.7), 3)
                m["evidence"].append("contrast %.2f:1 < 4.5:1 on black, escalated to %s"
                                     % (ratio, fallback["token"]))
                entry["escalated_to"] = fallback["token"]
                entry["ratio_after"] = round(contrast_ratio(parse_color(fallback["value"]), surface), 2)
            checks.append(entry)
        return {"min_contrast": 4.5,
                "checks": checks,
                "failures": [c for c in checks if not c["passes_4_5"]]}


def parse_badging(text: str) -> dict:
    out = {"package": None, "versionName": None, "versionCode": None,
           "minSdk": None, "targetSdk": None, "label": None, "launchable": None}
    m = re.search(r"package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", text)
    if m:
        out["package"], out["versionCode"], out["versionName"] = m.group(1), m.group(2), m.group(3)
    m = re.search(r"minSdkVersion:'([^']*)'", text)
    if m:
        out["minSdk"] = m.group(1)
    m = re.search(r"targetSdkVersion:'([^']*)'", text)
    if m:
        out["targetSdk"] = m.group(1)
    m = re.search(r"application: label='([^']*)'", text)
    if m:
        out["label"] = m.group(1)
    m = re.search(r"launchable-activity: name='([^']*)'", text)
    if m:
        out["launchable"] = m.group(1)
    return out
