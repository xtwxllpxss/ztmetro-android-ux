"""ztMetro design tokens, reduced to the roles the factory maps onto app resources.

The single source of truth stays design/tokens/ztmetro.tokens.json; this module
only looks values up by dotted path and adds the role vocabulary the analyser and
the generator share.  Nothing here invents a colour: every value is read from the
token file and the token path is recorded next to it, so a mapping can always be
traced back to `_origins` in that file.
"""

from __future__ import annotations

import json
import os

DEFAULT_TOKENS = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.dirname(os.path.abspath(__file__))))), "design", "tokens", "ztmetro.tokens.json")


class Tokens:
    def __init__(self, path: str | None = None):
        self.path = path or DEFAULT_TOKENS
        with open(self.path, "r", encoding="utf-8") as fh:
            self.data = json.load(fh)
        self.roles = self._build_roles()

    def get(self, dotted: str, default=None):
        node = self.data
        for part in dotted.split("."):
            if not isinstance(node, dict) or part not in node:
                return default
            node = node[part]
        return node

    def _r(self, token: str) -> str:
        val = self.get(token)
        if val is None:
            raise KeyError("token %s missing from %s" % (token, self.path))
        return val

    def _build_roles(self) -> dict:
        r = {}
        r["surface"] = {"token": "color.neutrals.black", "value": self._r("color.neutrals.black")}
        r["surface_off"] = {"token": "color.neutrals.surface_off",
                            "value": self._r("color.neutrals.surface_off")}
        r["surface_card"] = {"token": "color.neutrals.surface_cell",
                             "value": self._r("color.neutrals.surface_cell")}
        r["surface_key"] = {"token": "color.neutrals.surface_key",
                            "value": self._r("color.neutrals.surface_key")}
        r["surface_empty"] = {"token": "color.neutrals.surface_empty",
                              "value": self._r("color.neutrals.surface_empty")}
        r["text_primary"] = {"token": "color.neutrals.text_primary",
                             "value": self._r("color.neutrals.text_primary")}
        r["text_secondary"] = {"token": "color.neutrals.text_secondary",
                               "value": self._r("color.neutrals.text_secondary")}
        r["text_muted"] = {"token": "color.neutrals.text_muted",
                           "value": self._r("color.neutrals.text_muted")}
        r["text_disabled"] = {"token": "color.neutrals.text_disabled",
                              "value": self._r("color.neutrals.text_disabled")}
        r["accent"] = {"token": "color.accent.base", "value": self._r("color.accent.base")}
        r["accent_light"] = {"token": "color.accent.light1", "value": self._r("color.accent.light1")}
        r["accent_dark"] = {"token": "color.accent.dark1", "value": self._r("color.accent.dark1")}
        r["destructive"] = {"token": "color.status.destructive",
                            "value": self._r("color.status.destructive")}
        r["success"] = {"token": "color.status.success", "value": self._r("color.status.success")}
        r["warning"] = {"token": "color.status.warning_battery",
                        "value": self._r("color.status.warning_battery")}
        r["radius"] = {"token": "metrics.corner_radius_dp",
                       "value": float(self._r("metrics.corner_radius_dp"))}
        r["ramp"] = [{"token": "type.ramp.%s" % e["name"], "name": e["name"],
                      "sp": float(e["size_sp_at_density_1x"]),
                      "weight": e.get("semibold_weight", 400),
                      "line_height": e.get("line_height")}
                     for e in self.get("type.ramp", [])]
        return r

    def role(self, name: str) -> dict:
        return self.roles[name]
