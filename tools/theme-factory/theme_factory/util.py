"""Small helpers shared by the theme factory tools.

Pure standard library except where a caller needs a DELTA / RGB conversion.
"""

from __future__ import annotations

import hashlib
import os
import re
import sys

HEX_RE = re.compile(r"^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")


def log(msg: str) -> None:
    sys.stderr.write(msg.rstrip() + "\n")
    sys.stderr.flush()


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_color(text: str):
    """'#ff000000' -> (a, r, g, b) ints, or None when not a literal colour."""
    text = (text or "").strip()
    if not HEX_RE.match(text):
        return None
    raw = text[1:]
    if len(raw) == 6:
        raw = "ff" + raw
    a, r, g, b = (int(raw[i : i + 2], 16) for i in (0, 2, 4, 6))
    return a, r, g, b


def to_hex(a: int, r: int, g: int, b: int) -> str:
    return "#%02x%02x%02x%02x" % (a, r, g, b)


def rgb_hex(color) -> str:
    a, r, g, b = color
    return "#%02x%02x%02x" % (r, g, b)


def _lin(c: int) -> float:
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance(color) -> float:
    """WCAG relative luminance of an (a,r,g,b) tuple, as if composited on black."""
    a, r, g, b = color
    alpha = a / 255.0
    y = 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)
    return y * alpha  # approximate: transparent colours darken toward black


def contrast_ratio(c1, c2) -> float:
    l1, l2 = luminance(c1), luminance(c2)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def chroma(color) -> float:
    """HSV saturation (0..1)."""
    _, r, g, b = color
    mx, mn = max(r, g, b), min(r, g, b)
    return 0.0 if mx == 0 else (mx - mn) / float(mx)


def hue(color) -> float:
    _, r, g, b = color
    mx, mn = max(r, g, b), min(r, g, b)
    d = mx - mn
    if d == 0:
        return 0.0
    if mx == r:
        h = ((g - b) / d) % 6
    elif mx == g:
        h = (b - r) / d + 2
    else:
        h = (r - g) / d + 4
    return h * 60.0


def nearest(value: float, choices):
    return min(choices, key=lambda c: abs(c[0] - value))


def ensure_dir(path: str) -> str:
    os.makedirs(path, exist_ok=True)
    return path
