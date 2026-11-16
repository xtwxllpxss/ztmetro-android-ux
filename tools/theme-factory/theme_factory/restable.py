"""Parse `aapt2 dump resources` into a queryable resource table.

Only the shapes aapt2 2.20 actually prints are handled, but they are handled
strictly: an unknown value line is kept as a raw value rather than silently
dropped, so the analyser can report it instead of pretending it does not exist.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .util import parse_color

RE_TYPE = re.compile(r"^  type (\S+) id=([0-9a-f]+) entryCount=(\d+)\s*$")
RE_RES = re.compile(r"^    resource (0x[0-9a-f]+) (\S+)/(\S+?)(?: PUBLIC)?\s*$")
RE_ENTRY = re.compile(r"^      \(([^)]*)\) (.*)$")
RE_ATTR = re.compile(r"^        (\S+)=(.*)$")
RE_DIM = re.compile(r"^(-?[0-9.]+)(dp|dip|sp|px|pt)$")
RE_STYLE = re.compile(r"^\(style\) size=(\d+)(?: parent=(\S+)(?: \((0x[0-9a-f]+)\))?)?")


@dataclass
class Value:
    config: str = ""
    kind: str = "value"          # value | color | dimension | reference | file | style | null
    raw: str = ""
    color: tuple | None = None   # (a, r, g, b)
    ref_name: str | None = None  # e.g. color/bg_dark
    ref_id: str | None = None    # e.g. 0x0106003e
    dim_value: float | None = None
    dim_unit: str | None = None
    file: str | None = None
    style_parent: str | None = None
    style_parent_id: str | None = None
    attrs: list = field(default_factory=list)   # [(attr_name_or_id, raw_value)]


@dataclass
class Resource:
    type: str
    name: str
    id: str
    values: dict = field(default_factory=dict)  # config -> Value

    @property
    def key(self) -> str:
        return "%s/%s" % (self.type, self.name)

    def default(self) -> Value | None:
        if "" in self.values:
            return self.values[""]
        return next(iter(self.values.values()), None)

    def configs(self):
        return sorted(self.values.keys())


def _parse_value(text: str) -> tuple:
    """Return (kind, kwargs) for one aapt2 value payload."""
    text = text.strip()
    if text.startswith("(file)"):
        m = re.match(r"\(file\) (\S+)(?: type=(\S+))?", text)
        return "file", {"file": m.group(1) if m else None, "raw": text}
    if text.startswith("(style)"):
        m = RE_STYLE.match(text)
        return "style", {"raw": text,
                         "style_parent": m.group(2) if m else None,
                         "style_parent_id": m.group(3) if m else None}
    col = parse_color(text)
    if col:
        return "color", {"color": col, "raw": text}
    if text.startswith("@"):
        m = re.match(r"@(?:([\w.]+):)?([a-z-]+)/(\S+)$", text)
        if m:
            return "reference", {"ref_name": "%s/%s" % (m.group(2), m.group(3)), "raw": text}
        m = re.match(r"@(0x[0-9a-f]+)$", text)
        if m:
            return "reference", {"ref_id": m.group(1), "raw": text}
        return "reference", {"raw": text}
    dim = RE_DIM.match(text)
    if dim:
        return "dimension", {"dim_value": float(dim.group(1)), "dim_unit": dim.group(2), "raw": text}
    if text in ("@null", "@empty", ""):
        return "null", {"raw": text}
    return "value", {"raw": text}


class ResourceTable:
    def __init__(self):
        self.package: str | None = None
        self.resources: dict = {}      # "type/name" -> Resource
        self.by_id: dict = {}          # "0x...." -> Resource
        self.types: dict = {}          # type -> [Resource]

    def get(self, key: str) -> Resource | None:
        return self.resources.get(key)

    def by_key_or_id(self, ref: str | None) -> Resource | None:
        if not ref:
            return None
        if ref.startswith("0x"):
            return self.by_id.get(ref)
        return self.resources.get(ref)

    def of_type(self, type_: str):
        return self.types.get(type_, [])

    # ------------------------------------------------------------------
    def parse(self, dump: str) -> "ResourceTable":
        current_type = None
        resource = None
        value = None
        for line in dump.splitlines():
            m = RE_TYPE.match(line)
            if m:
                current_type = m.group(1)
                self.types.setdefault(current_type, [])
                continue
            if line.startswith("Package name="):
                mm = re.match(r"Package name=(\S+) id=([0-9a-f]+)", line)
                if mm:
                    self.package = mm.group(1)
                continue
            m = RE_RES.match(line)
            if m:
                _, type_, name = m.group(1), m.group(2), m.group(3)
                resource = Resource(type=type_, name=name, id=m.group(1))
                self.resources[resource.key] = resource
                self.by_id[resource.id] = resource
                self.types.setdefault(type_, []).append(resource)
                current_type = type_
                value = None
                continue
            m = RE_ENTRY.match(line)
            if m and resource is not None:
                config = m.group(1).strip()
                kind, kw = _parse_value(m.group(2))
                value = Value(config=config, kind=kind, **kw)
                resource.values[config] = value
                continue
            m = RE_ATTR.match(line)
            if m and value is not None and value.kind == "style":
                key = m.group(1)
                if "(" in key and key.endswith(")"):
                    head, _, tail = key.partition("(")
                    key = head
                value.attrs.append((key, m.group(2).strip()))
        return self
