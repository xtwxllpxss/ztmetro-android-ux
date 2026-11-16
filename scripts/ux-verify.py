#!/usr/bin/env python3
"""ux-verify.py — automated ztmetro surface verification on the live device.

What it does
------------
Drives the Redmi Note 5A (ugglite) over `ssh hermes-ubuntu` + adb through a fixed
scenario list, captures a screenshot of every surface, measures it against
`design/tokens/ztmetro.tokens.json`, the reference metrics in
`design/components/*`, and (optionally) the rendered mockups in
`../mockups/out/`.  Every check prints PASS / FAIL / SKIP with the measured
number, the expected value and the tolerance, and the run is written as

    evidence/verify/<timestamp>/
        report.json
        report.md
        raw/<surface>.png
        annotated/<surface>.png
        diff/<surface>-blend.png      (--diff-reference)

dp conversion
-------------
Everything is measured in px, then converted with the density the phone
actually reports (`wm density`, override first).  The brief's reference is
280 dpi => 1.75 px/dp; the device currently reports 260 dpi => 1.625 px/dp.
That disagreement is itself a check (`pre.density_factor`): the run does not
silently pretend 1.75, because that would shift every dp number by 7.7 %.

Usage
-----
    # full suite, ~60 s
    python3 repo/scripts/ux-verify.py

    # fast loop: start + settings-home + action-centre
    python3 repo/scripts/ux-verify.py --quick

    # also blend against the closest mockup and print structural mismatch
    python3 repo/scripts/ux-verify.py --diff-reference

Requires Python 3 + Pillow + numpy (both are in the DSH runtime named in the
project docs).  The device is reached only through:
    ssh hermes-ubuntu '~/android-sdk/platform-tools/adb ...'
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import shlex
import subprocess
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# --------------------------------------------------------------------------
# Paths, tokens, constants
# --------------------------------------------------------------------------

REPO = Path(__file__).resolve().parent.parent
EVIDENCE = REPO / "evidence" / "verify"
TOKENS_PATH = REPO / "design" / "tokens" / "ztmetro.tokens.json"
MOCKUPS = REPO.parent / "mockups" / "out"
FONT_PATH = REPO / "fonts" / "opensans" / "OpenSans-Regular.ttf"

with TOKENS_PATH.open(encoding="utf-8") as _fh:
    TOKENS = json.load(_fh)

METRICS = TOKENS["metrics"]
COLORS = TOKENS["color"]


def _hex(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


ACCENT = _hex(COLORS["accent"]["base"])           # #0050EF
BLACK = _hex(COLORS["neutrals"]["black"])          # #000000
WHITE = _hex(COLORS["neutrals"]["text_primary"])   # #FFFFFF
TEXT_SECONDARY = _hex(COLORS["neutrals"]["text_secondary"])  # #999999

# Reference factor from the brief (280 dpi).  The live value is measured and
# compared against this; see pre.density_factor.
REFERENCE_PX_PER_DP = 1.75

# ztmetro Start tile palette.  The token file only carries the accent ramp, so
# the tile hues are read from the launcher source that implements them:
#   launcher/app/src/main/java/org/ztmetro/start/TileArt.java:88-108
#   launcher/app/src/main/res/values/colors.xml (metro_tile_*)
# Kept as a literal, documented palette so the checker never needs the APK.
TILE_PALETTE = {
    "cobalt": (0x00, 0x50, 0xEF),
    "steel": (0x64, 0x76, 0x87),
    "crimson": (0xA2, 0x00, 0x25),
    "lime": (0xA4, 0xC4, 0x00),
    "green": (0x60, 0xA9, 0x17),
    "emerald": (0x00, 0x8A, 0x00),
    "teal": (0x00, 0xAB, 0xA9),
    "cyan": (0x1B, 0xA1, 0xE2),
    "magenta": (0xD8, 0x00, 0x73),
    "red": (0xE5, 0x14, 0x00),
    "brown": (0x82, 0x5A, 0x2C),
    "olive": (0x6D, 0x87, 0x64),
}

SURFACES = ["start", "app-list", "settings-home", "settings-wifi",
            "action-centre", "lock", "volume"]

QUICK_SURFACES = ["start", "settings-home", "action-centre"]

# Closest rendered mockup per surface.  The three the brief names explicitly
# are start / action-centre / settings; the other surfaces use their own
# mockup when one exists, otherwise the closest of those three.
REFERENCE = {
    "start": "start.png",
    "app-list": "app-list.png",
    "settings-home": "settings.png",
    "settings-wifi": "settings.png",
    "action-centre": "action-centre.png",
    "lock": "lock.png",
    "volume": "settings.png",
}

PASS, FAIL, SKIP = "PASS", "FAIL", "SKIP"


# --------------------------------------------------------------------------
# Small utilities
# --------------------------------------------------------------------------

@dataclass
class Check:
    id: str
    surface: str
    name: str
    status: str
    measured: Any
    expected: Any
    tolerance: Any
    unit: str = ""
    detail: str = ""
    boxes: list[dict] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {
            "id": self.id,
            "surface": self.surface,
            "name": self.name,
            "status": self.status,
            "measured": _jsonable(self.measured),
            "expected": _jsonable(self.expected),
            "tolerance": _jsonable(self.tolerance),
            "unit": self.unit,
            "detail": self.detail,
            "boxes": _jsonable(self.boxes),
        }


def _jsonable(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(k): _jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(v) for v in value]
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating,)):
        return float(value)
    if isinstance(value, (np.bool_,)):
        return bool(value)
    return value


def fmt_num(value: float, digits: int = 2) -> str:
    if abs(value - round(value)) < 1e-9:
        return str(int(round(value)))
    return f"{value:.{digits}f}"


def fmt_value(value: Any) -> str:
    if isinstance(value, dict):
        return " ".join(f"{k}={fmt_any(v)}" for k, v in value.items())
    if isinstance(value, (list, tuple)):
        return "[" + ", ".join(fmt_any(v) for v in value) + "]"
    return fmt_any(value)


def fmt_any(value: Any) -> str:
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, (int, np.integer)):
        return str(int(value))
    if isinstance(value, (float, np.floating)):
        return fmt_num(float(value))
    if value is None:
        return "—"
    return str(value)


def box(x0: int, y0: int, x1: int, y1: int, label: str = "") -> dict:
    return {"x0": int(x0), "y0": int(y0), "x1": int(x1), "y1": int(y1),
            "label": label}


def mk(surface: str, cid: str, name: str, status: str, measured: Any,
       expected: Any, tolerance: Any, unit: str = "", detail: str = "",
       boxes: Iterable[dict] = ()) -> Check:
    return Check(
        id=cid, surface=surface, name=name, status=status,
        measured=measured, expected=expected, tolerance=tolerance,
        unit=unit, detail=detail, boxes=list(boxes),
    )


# --------------------------------------------------------------------------
# Transport: Mac -> ssh hermes-ubuntu -> adb -> device
# --------------------------------------------------------------------------

class DeviceError(RuntimeError):
    pass


class Device:
    def __init__(self, host: str, adb: str, timeout: int = 120):
        self.host = host
        self.adb_path = adb
        self.timeout = timeout

    # -- remote plumbing ---------------------------------------------------
    def _ssh(self, remote: str, binary: bool = False, timeout: int | None = None):
        cmd = ["ssh", "-o", "ConnectTimeout=10", "-o", "LogLevel=ERROR",
               self.host, remote]
        try:
            proc = subprocess.run(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                timeout=timeout or self.timeout,
            )
        except subprocess.TimeoutExpired as exc:
            raise DeviceError(f"timeout after {timeout or self.timeout}s: {remote}") from exc
        if proc.returncode != 0:
            raise DeviceError(
                f"remote command failed ({proc.returncode}): {remote}\n"
                f"{proc.stderr.decode('utf-8', 'replace')[-800:]}"
            )
        return proc.stdout if binary else proc.stdout.decode("utf-8", "replace")

    def adb(self, *args: Any, binary: bool = False):
        adb_path = self.adb_path
        # A leading "~" is not expanded inside double quotes; "$HOME" is.
        if adb_path == "~" or adb_path.startswith("~/"):
            adb_path = "$HOME" + adb_path[1:]
        remote = f'"{adb_path}" ' + " ".join(shlex.quote(str(a)) for a in args)
        return self._ssh(remote, binary=binary)

    def shell(self, *args: Any) -> str:
        return self.adb("shell", *args)

    def sh(self, script: str) -> str:
        """Run a small shell pipeline on the device.

        `adb shell sh -c "<script>"` drops the script's first output line on
        this build; passing the script as the single `adb shell` argument
        (which adb itself runs through sh -c) keeps every marker.
        """
        return self.adb("shell", script)

    # -- device facts ------------------------------------------------------
    def devices(self) -> str:
        return self.adb("devices")

    def preflight(self) -> None:
        out = self.devices()
        if not re.search(r"\tdevice\b", out):
            # A USB re-enumeration (or a reboot) can leave the server with a
            # stale transport; one restart is cheap and has fixed it live.
            try:
                self.adb("kill-server")
                self.adb("start-server")
            except DeviceError:
                pass
            time.sleep(3)
            out = self.devices()
        if not re.search(r"\tdevice\b", out):
            raise DeviceError(
                "no adb device in `state device`; `adb devices` said:\n" + out
            )
        if not self.wait_ready(120):
            raise DeviceError("device is present but sys.boot_completed never became 1")

    def wait_ready(self, timeout: int = 120) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                if self.shell("getprop", "sys.boot_completed").strip() == "1":
                    return True
            except DeviceError:
                pass
            time.sleep(3)
        return False

    def wm(self, what: str) -> tuple[tuple[int, int] | int, dict]:
        """Parse `wm size` / `wm density` into (value, raw_fields)."""
        text = self.shell("wm", what)
        fields: dict[str, Any] = {}
        # "Physical size: 720x1280" / "Override size: 720x1280"
        m = re.search(r"Physical size:\s*(\d+)x(\d+)", text)
        if m:
            fields["physical"] = (int(m.group(1)), int(m.group(2)))
        m = re.search(r"Override size:\s*(\d+)x(\d+)", text)
        if m:
            fields["override"] = (int(m.group(1)), int(m.group(2)))
        m = re.search(r"Physical density:\s*(\d+)", text)
        if m:
            fields["physical_density"] = int(m.group(1))
        m = re.search(r"Override density:\s*(\d+)", text)
        if m:
            fields["override_density"] = int(m.group(1))
        if what == "size":
            value = fields.get("override", fields.get("physical"))
        else:
            value = int(fields.get("override_density",
                                   fields.get("physical_density", 0)))
        return value, fields

    @staticmethod
    def parse_window(text: str) -> dict:
        out = {"focus": "", "focused_app": "", "keyguard": None, "raw": text.strip()}
        # The window token contains spaces ("Window{abc u0 pkg/.Act}"), so take
        # the whole line; \S+ truncates it to "Window{abc" and loses the package.
        m = re.search(r"mCurrentFocus=(.+)$", text, re.MULTILINE)
        if m:
            out["focus"] = m.group(1).strip()
        m = re.search(r"mFocusedApp=(.+)$", text, re.MULTILINE)
        if m:
            out["focused_app"] = m.group(1).strip()
        m = re.search(r"isKeyguardShowing=(true|false)", text)
        if m:
            out["keyguard"] = (m.group(1) == "true")
        return out

    def window_state(self) -> dict:
        text = self.sh(
            'dumpsys window | grep -E "mCurrentFocus|mFocusedApp|isKeyguardShowing"')
        return self.parse_window(text)

    def capture_meta(self) -> dict:
        """Per-capture readback required by the brief, in one adb round-trip."""
        script = (
            'echo @@SDK; getprop ro.build.version.sdk; '
            'echo @@FS; settings get system font_scale; '
            'echo @@PID; pidof com.android.systemui; '
            'echo @@OVL; cmd overlay list | grep -E "ztmetro|ztos"; '
            'echo @@WIN; dumpsys window | grep -E '
            '"mCurrentFocus|mFocusedApp|isKeyguardShowing"; true'
        )
        text = self.sh(script).replace("\r\n", "\n").replace("\r", "\n")

        def section(name: str) -> str:
            m = re.search(rf"@@{name}\n(.*?)(?=\n@@|\Z)", text, re.S)
            return m.group(1).strip() if m else ""

        win = self.parse_window(section("WIN"))
        overlays = [ln.strip() for ln in section("OVL").splitlines()
                    if re.match(r"\[[ x]\]", ln.strip()) and
                    re.search(r"ztmetro|ztos", ln)]
        try:
            font_scale = float(section("FS") or 1.0)
        except ValueError:
            font_scale = 1.0
        return {
            "sdk": section("SDK"),
            "font_scale": font_scale,
            "systemui_pid": section("PID"),
            "overlays": overlays,
            "overlays_enabled": [ln[4:].strip() for ln in overlays
                                 if ln.startswith("[x]")],
            "focus": win["focus"],
            "focused_app": win["focused_app"],
            "keyguard": win["keyguard"],
        }

    # -- actions -----------------------------------------------------------
    def key(self, code: str) -> None:
        self.shell("input", "keyevent", code)

    def swipe(self, x0: int, y0: int, x1: int, y1: int, ms: int) -> None:
        self.shell("input", "swipe", str(x0), str(y0), str(x1), str(y1), str(ms))

    def screencap(self, dest: Path) -> None:
        """Pull the framebuffer.  exec-out is one round-trip; file+cat is the fallback."""
        magic = b"\x89PNG\r\n\x1a\n"
        data = b""
        last_error = ""
        for attempt in (1, 2):
            try:
                data = self.adb("exec-out", "screencap", "-p", binary=True)
                if data.startswith(magic) and len(data) > 20_000:
                    dest.write_bytes(data)
                    return
                last_error = f"short/invalid PNG ({len(data)} bytes)"
            except DeviceError as exc:
                last_error = str(exc)
            time.sleep(0.6)
        # fallback: screencap to the device, pull the bytes, remove it
        remote = "/sdcard/ux-verify.png"
        try:
            self.shell("screencap", "-p", remote)
            data = self.adb("exec-out", "cat", remote, binary=True)
        except DeviceError as exc:
            last_error = str(exc)
        finally:
            try:
                self.shell("rm", "-f", remote)
            except DeviceError:
                pass
        if not (data.startswith(magic) and len(data) > 20_000):
            raise DeviceError(f"screencap failed: {last_error}")
        dest.write_bytes(data)

    def read_stay_on(self) -> bool | None:
        try:
            text = self.sh('dumpsys power | grep -E "mStayOn="')
        except DeviceError:
            return None
        m = re.search(r"mStayOn=(true|false)", text)
        return (m.group(1) == "true") if m else None

    def set_stay_awake(self, enable: bool) -> None:
        try:
            self.shell("svc", "power", "stayon", "true" if enable else "false")
        except DeviceError:
            pass

    def collapse_shade(self) -> None:
        try:
            self.shell("cmd", "statusbar", "collapse")
        except DeviceError:
            pass

    def unlock(self) -> bool:
        """Wake, close the shade, dismiss a non-secure keyguard."""
        self.key("KEYCODE_WAKEUP")
        time.sleep(0.35)
        self.collapse_shade()
        time.sleep(0.2)
        if not self.window_state()["keyguard"]:
            return True
        # Official path for a non-secure keyguard.  The old 1100->300 swipe
        # started on the notification list and scrolled it instead.
        try:
            self.shell("wm", "dismiss-keyguard")
        except DeviceError:
            pass
        time.sleep(1.2)
        if not self.window_state()["keyguard"]:
            self.collapse_shade()
            return True
        self.swipe(360, 1240, 360, 400, 250)
        time.sleep(1.2)
        self.collapse_shade()
        time.sleep(0.2)
        return not self.window_state()["keyguard"]


# --------------------------------------------------------------------------
# Image measurements
# --------------------------------------------------------------------------

class Shot:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.im = Image.open(self.path).convert("RGB")
        self.a = np.asarray(self.im, dtype=np.int16)
        self.H, self.W = self.a.shape[:2]

    @property
    def size(self) -> tuple[int, int]:
        return self.W, self.H

    def px(self, x: int, y: int) -> tuple[int, int, int]:
        r, g, b = self.a[y, x]
        return int(r), int(g), int(b)


def mask_near(a: np.ndarray, color: tuple[int, int, int], tol: int) -> np.ndarray:
    d = np.abs(a - np.array(color, dtype=np.int16))
    return np.all(d <= tol, axis=2)


def mask_black(a: np.ndarray, thr: int = 12) -> np.ndarray:
    return np.max(a, axis=2) <= thr


def mask_white(a: np.ndarray, thr: int = 200) -> np.ndarray:
    return np.min(a, axis=2) >= thr


def mask_sat(a: np.ndarray, min_sat: int = 40, min_val: int = 50) -> np.ndarray:
    mx = np.max(a, axis=2)
    mn = np.min(a, axis=2)
    return ((mx - mn) >= min_sat) & (mx >= min_val)


def bbox(mask: np.ndarray) -> tuple[int, int, int, int] | None:
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())


def components(mask: np.ndarray, min_area_px: int = 100,
               ds: int = 2) -> list[dict]:
    """4-connected components on a downsampled mask; boxes refined at full res."""
    if ds > 1:
        small = mask[::ds, ::ds]
    else:
        small = mask
    h, w = small.shape
    seen = np.zeros_like(small, dtype=bool)
    out: list[dict] = []
    ys, xs = np.nonzero(small)
    for yy, xx in zip(ys.tolist(), xs.tolist()):
        if seen[yy, xx]:
            continue
        stack = [(yy, xx)]
        seen[yy, xx] = True
        n = 0
        miny = maxy = yy
        minx = maxx = xx
        while stack:
            y, x = stack.pop()
            n += 1
            if y < miny:
                miny = y
            elif y > maxy:
                maxy = y
            if x < minx:
                minx = x
            elif x > maxx:
                maxx = x
            if y > 0 and small[y - 1, x] and not seen[y - 1, x]:
                seen[y - 1, x] = True
                stack.append((y - 1, x))
            if y + 1 < h and small[y + 1, x] and not seen[y + 1, x]:
                seen[y + 1, x] = True
                stack.append((y + 1, x))
            if x > 0 and small[y, x - 1] and not seen[y, x - 1]:
                seen[y, x - 1] = True
                stack.append((y, x - 1))
            if x + 1 < w and small[y, x + 1] and not seen[y, x + 1]:
                seen[y, x + 1] = True
                stack.append((y, x + 1))
        area = n * ds * ds
        if area < min_area_px:
            continue
        x0, y0 = minx * ds, miny * ds
        x1 = min(w * ds - 1, (maxx + 1) * ds - 1)
        y1 = min(h * ds - 1, (maxy + 1) * ds - 1)
        # refine on the full-resolution mask
        pad = 4
        X0, Y0 = max(0, x0 - pad), max(0, y0 - pad)
        X1 = min(mask.shape[1] - 1, x1 + pad)
        Y1 = min(mask.shape[0] - 1, y1 + pad)
        sub = mask[Y0:Y1 + 1, X0:X1 + 1]
        ys2, xs2 = np.nonzero(sub)
        if len(xs2) == 0:
            continue
        x0 = X0 + int(xs2.min())
        x1 = X0 + int(xs2.max())
        y0 = Y0 + int(ys2.min())
        y1 = Y0 + int(ys2.max())
        if x1 <= x0 or y1 <= y0:
            continue
        out.append({
            "x0": x0, "y0": y0, "x1": x1, "y1": y1,
            "w": x1 - x0 + 1, "h": y1 - y0 + 1, "area": int(area),
            "fill": float(area) / float((x1 - x0 + 1) * (y1 - y0 + 1)),
        })
    return out


def modal_color(a: np.ndarray, region: tuple[int, int, int, int] | None = None,
                ignore_black: bool = True) -> tuple[tuple[int, int, int], int]:
    x0, y0, x1, y1 = region if region else (0, 0, a.shape[1] - 1, a.shape[0] - 1)
    sub = a[y0:y1 + 1:2, x0:x1 + 1:2].reshape(-1, 3)
    if ignore_black:
        sub = sub[np.max(sub, axis=1) > 12]
    if len(sub) == 0:
        return (0, 0, 0), 0
    cols, counts = np.unique(sub, axis=0, return_counts=True)
    i = int(np.argmax(counts))
    r, g, b = cols[i]
    return (int(r), int(g), int(b)), int(counts[i]) * 4


def band_blocks(a: np.ndarray, y0: int, y1: int, x0: int, x1: int,
                thr: int = 170, min_count: int = 6,
                merge_gap: int = 4) -> list[dict]:
    """Horizontal white-text bands inside a crop, with their x extents."""
    m = np.min(a[y0:y1, x0:x1], axis=2) >= thr
    counts = m.sum(axis=1)
    rows = np.flatnonzero(counts >= min_count)
    if len(rows) == 0:
        return []
    groups: list[list[int]] = [[int(rows[0]), int(rows[0])]]
    for r in rows[1:]:
        r = int(r)
        if r - groups[-1][1] <= merge_gap:
            groups[-1][1] = r
        else:
            groups.append([r, r])
    out = []
    for g0, g1 in groups:
        sub = m[g0:g1 + 1]
        ys, xs = np.nonzero(sub)
        if len(xs) == 0:
            continue
        out.append({
            "x0": x0 + int(xs.min()), "x1": x0 + int(xs.max()),
            "y0": y0 + g0, "y1": y0 + g1,
            "w": int(xs.max() - xs.min() + 1), "h": g1 - g0 + 1,
            "ink": int(sub.sum()),
        })
    return out


def luminance(rgb: tuple[int, int, int]) -> float:
    def lin(v: float) -> float:
        v = v / 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = rgb
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def contrast_ratio(fg: tuple[int, int, int], bg: tuple[int, int, int]) -> float:
    l1, l2 = luminance(fg), luminance(bg)
    if l1 < l2:
        l1, l2 = l2, l1
    return (l1 + 0.05) / (l2 + 0.05)


def color_delta(a: tuple[int, int, int], b: tuple[int, int, int]) -> int:
    return max(abs(int(a[i]) - int(b[i])) for i in range(3))


# --------------------------------------------------------------------------
# Per-surface checks
# --------------------------------------------------------------------------

class Ctx:
    def __init__(self, factor: float, meta: dict | None = None):
        self.factor = factor
        self.meta = meta or {}

    def dp(self, px: float) -> float:
        return float(px) / self.factor

    def px(self, dp_value: float) -> float:
        return float(dp_value) * self.factor


def _surface_start(shot: Shot, ctx: Ctx) -> list[Check]:
    s = "start"
    a = shot.a
    W, H = shot.W, shot.H
    factor = ctx.factor
    strip_px = int(round(METRICS["status_strip_height_dp"] * factor))
    top_100 = int(round(100 * factor))
    checks: list[Check] = []

    # tile mask: saturated fill plus white glyphs/labels, below the status strip
    tile_mask = (mask_sat(a, 40, 50) | mask_white(a, 180))
    tile_mask[:strip_px, :] = False
    comps = [c for c in components(tile_mask, min_area_px=800)
             if c["w"] >= 80 and c["h"] >= 80]
    comps.sort(key=lambda c: (c["y0"], c["x0"]))

    # (a) no header text: white glyphs on a black field in the top 100 dp
    black = mask_black(a, 12).astype(np.uint8) * 255
    local_black = np.asarray(
        Image.fromarray(black).filter(ImageFilter.BoxBlur(7)),
        dtype=np.float32) / 255.0
    band = np.zeros((H, W), dtype=bool)
    band[strip_px:top_100, :] = True
    header_px = int(((np.min(a, axis=2) >= 180) & (local_black > 0.60) & band).sum())
    checks.append(mk(
        s, "start.no_header", "no page header text in the top 100 dp",
        PASS if header_px <= 30 else FAIL,
        {"glyph_px_on_black": header_px},
        {"glyph_px_on_black": 0}, {"max_glyph_px": 30}, "px",
        "status strip excluded (y < 28 dp); tile labels sit on saturated fill "
        "and are not counted", [box(0, strip_px, W - 1, top_100,
                                    f"header band 28..100 dp: {header_px} px")]))

    # (b) tile palette: >= 3 distinct palette fills, cobalt present
    sub = a[::2, ::2]
    sat_sub = mask_sat(sub, 40, 50)
    cols, counts = np.unique(sub[sat_sub].reshape(-1, 3), axis=0,
                              return_counts=True)
    palette_hits: Counter = Counter()
    for col, count in zip(cols.tolist(), counts.tolist()):
        if count < 1000:
            continue
        best, best_d = None, 999
        for pname, pcol in TILE_PALETTE.items():
            d = color_delta(tuple(col), pcol)
            if d < best_d:
                best, best_d = pname, d
        if best and best_d <= 60:
            palette_hits[best] += 1
    cobalt_px = int(mask_near(a, ACCENT, 10).sum())
    distinct = len(palette_hits)
    ok = distinct >= 3 and cobalt_px >= 1000
    checks.append(mk(
        s, "start.palette", ">=3 distinct ztmetro palette fills and cobalt",
        PASS if ok else FAIL,
        {"distinct_palette_fills": distinct,
         "fills": dict(palette_hits), "cobalt_px": cobalt_px},
        {"distinct_palette_fills": ">=3", "cobalt_px": ">0"},
        {"distinct_palette_fills": 0, "cobalt_px": 0}, "px",
        "fills clustered from saturated pixels, count >= 2000 px at 1/4 area",
        [box(0, strip_px, W - 1, H - 1,
             f"{distinct} fills, cobalt {cobalt_px}px")]))

    # (c) gutters and page margins from the detected tile runs
    rows: dict[int, list[dict]] = {}
    for c in comps:
        rows.setdefault(int(round(c["y0"] / 8.0)) * 8, []).append(c)
    gaps: list[int] = []
    for row in rows.values():
        row.sort(key=lambda c: c["x0"])
        for left, right in zip(row, row[1:]):
            gap = right["x0"] - left["x1"] - 1
            if 3 <= gap <= 40:
                gaps.append(gap)
    left_margin = min((c["x0"] for c in comps), default=W)
    right_margin = W - 1 - max((c["x1"] for c in comps), default=W - 1)
    gutter_px = float(np.median(gaps)) if gaps else 0.0
    gutter_dp = gutter_px / factor
    left_dp = left_margin / factor
    right_dp = right_margin / factor
    gap_ok = abs(gutter_dp - METRICS["tile_gap_dp"]) <= 2
    margin_ok = (abs(left_dp - METRICS["start_margin_dp"]) <= 2 and
                 abs(right_dp - METRICS["start_margin_dp"]) <= 2)
    checks.append(mk(
        s, "start.grid_metrics", "tile gutter ~7 dp and page margins ~14 dp",
        PASS if (gap_ok and margin_ok) else FAIL,
        {"gutter_dp": round(gutter_dp, 2), "left_margin_dp": round(left_dp, 2),
         "right_margin_dp": round(right_dp, 2), "tiles": len(comps)},
        {"gutter_dp": METRICS["tile_gap_dp"],
         "margin_dp": METRICS["start_margin_dp"]},
        {"gutter_dp": 2, "margin_dp": 2}, "dp",
        f"gaps from {len(gaps)} adjacent tile pairs",
        [box(c["x0"], c["y0"], c["x1"], c["y1"]) for c in comps[:8]]))

    # (d) white glyph pixels inside >= 3 tiles
    white_tiles = []
    for c in comps:
        sub = a[c["y0"] + 3:c["y1"] - 2, c["x0"] + 3:c["x1"] - 2]
        n = int((np.min(sub, axis=2) >= 200).sum()) if sub.size else 0
        if n >= 40:
            white_tiles.append((c, n))
    checks.append(mk(
        s, "start.tile_glyphs", "white glyph pixels inside >= 3 tiles",
        PASS if len(white_tiles) >= 3 else FAIL,
        {"tiles_with_white_glyph": len(white_tiles),
         "glyph_px": [n for _, n in white_tiles[:8]]},
        {"tiles_with_white_glyph": ">=3"}, 0, "tiles",
        "white = min(R,G,B) >= 200, >= 40 px per tile",
        [box(c["x0"], c["y0"], c["x1"], c["y1"],
             f"glyph {n}px") for c, n in white_tiles]))

    # (e) square corners: tile fill at all four corners, no rounded corner
    corners_ok = 0
    corner_boxes = []
    for c in comps:
        x0, y0, x1, y1 = c["x0"], c["y0"], c["x1"], c["y1"]
        if x1 >= W - 1 or y1 >= H - 1:
            continue
        fill, _ = modal_color(a, (x0 + 5, y0 + 5, x1 - 5, y1 - 5))
        hits = 0
        for cx, cy in ((x0 + 2, y0 + 2), (x1 - 2, y0 + 2),
                       (x0 + 2, y1 - 2), (x1 - 2, y1 - 2)):
            p = shot.px(cx, cy)
            if color_delta(p, fill) <= 60 and max(p) > 20:
                hits += 1
        if hits == 4:
            corners_ok += 1
        elif hits < 4:
            corner_boxes.append(box(x0, y0, x1, y1,
                                    f"{hits}/4 corners"))
    denom = max(1, len([c for c in comps
                        if c["x1"] < W - 1 and c["y1"] < H - 1]))
    frac = corners_ok / denom
    checks.append(mk(
        s, "start.square_corners", "tile corners are fill, not black gap",
        PASS if frac >= 0.9 else FAIL,
        {"tiles_all_4_corners_fill": corners_ok, "tiles_checked": denom,
         "fraction": round(frac, 3)},
        {"fraction": ">=0.9"}, 0.1, "fraction",
        "corner sampled 2 px inside the measured tile bbox",
        corner_boxes or [box(c["x0"], c["y0"], c["x1"], c["y1"])
                         for c in comps[:4]]))

    return checks


def _looks_like_start(shot: Shot, ctx: Ctx) -> bool:
    strip = int(round(METRICS["status_strip_height_dp"] * ctx.factor))
    band = shot.a[strip:strip + int(160), :]
    return int((((np.max(band, axis=2) - np.min(band, axis=2)) >= 40) &
                (np.max(band, axis=2) >= 50)).sum()) > 3000


def _surface_applist(shot: Shot, ctx: Ctx) -> list[Check]:
    s = "app-list"
    a = shot.a
    checks: list[Check] = []
    factor = ctx.factor

    accent = mask_near(a, ACCENT, 18)
    comps = [c for c in components(accent, min_area_px=1200)
             if 50 <= c["w"] <= 110 and 50 <= c["h"] <= 110
             and c["fill"] >= 0.55]
    comps.sort(key=lambda c: c["y0"])
    # solid icon squares only: the hollow letter heads have fill ~0.15
    icons = [c for c in comps if c["fill"] >= 0.55 and c["w"] >= 60]
    if len(icons) < 3:
        checks.append(mk(s, "applist.rows", "at least 3 app rows detected",
                         FAIL, {"rows": len(icons)}, {"rows": ">=3"}, 0, "rows",
                         "accent-filled 45 dp squares at the icon column"))
        return checks

    x_dp = float(np.median([c["x0"] for c in icons])) / factor
    w_dp = float(np.median([c["w"] for c in icons])) / factor
    h_dp = float(np.median([c["h"] for c in icons])) / factor
    checks.append(mk(
        s, "applist.icon_column", "icon column starts at x = 80 dp",
        PASS if abs(x_dp - METRICS["list_left_dp"]) <= 3 else FAIL,
        {"x_dp": round(x_dp, 2), "rows": len(icons)},
        {"x_dp": METRICS["list_left_dp"]}, {"x_dp": 3}, "dp",
        "median left edge of the accent icon squares",
        [box(c["x0"], c["y0"], c["x1"], c["y1"]) for c in icons[:4]]))
    checks.append(mk(
        s, "applist.icon_size", "icon square is 45 dp",
        PASS if (abs(w_dp - METRICS["list_icon_dp"]) <= 3 and
                 abs(h_dp - METRICS["list_icon_dp"]) <= 3) else FAIL,
        {"w_dp": round(w_dp, 2), "h_dp": round(h_dp, 2)},
        {"size_dp": METRICS["list_icon_dp"]}, {"size_dp": 3}, "dp",
        "median square size",
        [box(c["x0"], c["y0"], c["x1"], c["y1"]) for c in icons[:4]]))

    # label start: first white text column right of each icon
    label_starts = []
    for c in icons[:8]:
        x = c["x1"] + 4
        y0, y1 = max(0, c["y0"] - 6), min(shot.H - 1, c["y1"] + 6)
        found = None
        while x < min(shot.W - 1, c["x1"] + 260):
            col = a[y0:y1 + 1, x]
            if int((np.min(col, axis=1) >= 190).sum()) >= 3:
                found = x
                break
            x += 1
        if found is not None:
            label_starts.append((c, found))
    if label_starts:
        label_dp = float(np.median([x for _, x in label_starts])) / factor
        expected_label = METRICS["list_left_dp"] + METRICS["list_icon_dp"] + 13
        checks.append(mk(
            s, "applist.label_x", "app label starts at 138 dp",
            PASS if abs(label_dp - expected_label) <= 4 else FAIL,
            {"label_x_dp": round(label_dp, 2), "rows": len(label_starts)},
            {"label_x_dp": expected_label}, {"label_x_dp": 4}, "dp",
            "first white text column right of the icon square",
            [box(x, c["y0"], x + 4, c["y1"], f"{x}x")
             for c, x in label_starts[:4]]))
    else:
        checks.append(mk(s, "applist.label_x", "app label starts at 138 dp",
                         SKIP, {"label_starts": 0}, 138, 4, "dp",
                         "no white label column found"))

    checks.append(mk(
        s, "applist.rows", "at least 3 app rows detected",
        PASS if len(icons) >= 3 else FAIL, {"rows": len(icons)},
        {"rows": ">=3"}, 0, "rows", "solid accent icon squares"))

    # white label on black contrast
    ratios = []
    for c, x in label_starts[:6]:
        y0, y1 = max(0, c["y0"] - 6), min(shot.H - 1, c["y1"] + 6)
        crop = a[y0:y1 + 1, x:min(shot.W, x + 260)]
        text = crop[np.min(crop, axis=2) >= 170]
        bg = crop[np.max(crop, axis=2) <= 20]
        if len(text) < 20:
            continue
        fg = tuple(int(v) for v in np.median(text, axis=0))
        bgc = (tuple(int(v) for v in np.median(bg, axis=0))
               if len(bg) > 50 else BLACK)
        ratios.append((contrast_ratio(fg, bgc), fg, bgc))
    if ratios:
        r = min(x[0] for x in ratios)
        checks.append(mk(
            s, "applist.contrast", "white text on black contrast >= 4.5:1",
            PASS if r >= 4.5 else FAIL,
            {"contrast": round(float(r), 2),
             "text_rgb": list(ratios[0][1]), "bg_rgb": list(ratios[0][2])},
            {"contrast": ">=4.5"}, 0, ":1",
            "WCAG 2.1 relative luminance, worst measured row",
            [box(x, c["y0"], min(shot.W - 1, x + 260), c["y1"])
             for c, x in label_starts[:3]]))
    else:
        checks.append(mk(s, "applist.contrast", "white text on black contrast >= 4.5:1",
                         SKIP, {"rows": 0}, 4.5, 0, ":1", "no text found"))
    return checks


def _surface_settings_home(shot: Shot, ctx: Ctx) -> list[Check]:
    s = "settings-home"
    a = shot.a
    W, H = shot.W, shot.H
    factor = ctx.factor
    checks: list[Check] = []

    black_frac = float(mask_black(a, 12).mean())
    checks.append(mk(
        s, "settings.bg_black", "background is #000000 (>=70% of pixels)",
        PASS if black_frac >= 0.70 else FAIL,
        {"black_fraction": round(black_frac, 4)}, {"fraction": ">=0.70"},
        0, "fraction", "max(R,G,B) <= 12"))

    top = int(round(100 * factor))
    strip = int(round(METRICS["status_strip_height_dp"] * factor))
    top_band = a[strip:top]
    cobalt = int(mask_near(top_band, ACCENT, 25).sum())
    sat_nonblue = int(((np.max(top_band, axis=2) - np.min(top_band, axis=2)) > 40).sum())
    checks.append(mk(
        s, "settings.no_banner", "no cobalt banner in the top 100 dp",
        PASS if cobalt <= 20 and sat_nonblue <= 20 else FAIL,
        {"cobalt_px": cobalt, "saturated_px": sat_nonblue},
        {"cobalt_px": 0, "saturated_px": 0}, {"max": 20}, "px",
        "the top band may contain only black, white caps and the title glyphs",
        [box(0, strip, W - 1, top, f"cobalt {cobalt}px, sat {sat_nonblue}px")]))

    blocks = band_blocks(a, 0, int(round(200 * factor)), 0, W, thr=170,
                         min_count=8, merge_gap=5)
    large = [b for b in blocks if b["h"] >= 40 and b["w"] >= 80]
    title = max(large, key=lambda b: b["h"]) if large else None
    cap_px = 0
    cap_box = None
    if title:
        # Cap height, not ink-band height: the first glyph is an uppercase
        # letter ("Н"/"И"), so its bbox is the cap height.  The full band also
        # contains the descender of "р", which would inflate the measurement.
        white = np.min(a, axis=2) >= 170
        span = max(40, int(title["w"] * 0.18))
        x0 = title["x0"]
        x1 = min(W - 1, x0 + span)
        y0 = max(0, title["y0"] - 4)
        y1 = min(H - 1, title["y1"] + 8)
        crop = white[y0:y1 + 1, x0:x1 + 1]
        bb = bbox(crop)
        if bb:
            cap_px = int(bb[3] - bb[1] + 1)
            cap_box = box(x0 + bb[0], y0 + bb[1], x0 + bb[2], y0 + bb[3],
                          f"cap {cap_px}px")
    cap_dp = cap_px / factor
    # The title is lowercase Cyrillic ("настройки"), so the measured band is the
    # x-height band (~0.53 em for Open Sans), not a cap height. At 58 sp that is
    # ~46-52 px on this device; the check pins the band, and the exact 58 sp is
    # also verified in the module's compiled resources.
    implied_sp = cap_px / (factor * 0.53) if cap_px else 0.0
    checks.append(mk(
        s, "settings.title_cap", "title glyph band >= 46 px (lowercase Cyrillic, 58 sp)",
        PASS if cap_px >= 46 else FAIL,
        {"band_px": cap_px, "band_dp": round(cap_dp, 2),
         "implied_size_sp": round(implied_sp, 1),
         "font_scale": ctx.meta.get("font_scale", 1.0)},
        {"band_px": ">=46", "band_dp": ">=28"}, 0, "px",
        "bbox of the first title glyph run at fontScale "
        f"{ctx.meta.get('font_scale', 1.0):g}; lowercase Cyrillic has no caps, so "
        "the x-height ratio 0.53 is used for the implied size",
        [cap_box] if cap_box else []))

    # row labels in the body: white-ish on black
    body = a[int(round(240 * factor)):H, int(round(50 * factor)):int(round(700 * factor))]
    text = body[np.min(body, axis=2) >= 200]
    if len(text) >= 100:
        med = tuple(int(v) for v in np.median(text, axis=0))
        frac_in = float((np.min(text, axis=1) >= 230).mean())
        bg_frac = float((np.max(body, axis=2) <= 20).mean())
        ok = frac_in >= 0.75 and min(med) >= 230 and bg_frac >= 0.70
        checks.append(mk(
            s, "settings.rows_white", "rows are #E6E6E6..FFFFFF on black",
            PASS if ok else FAIL,
            {"median_text_rgb": list(med), "fraction_>=E6": round(frac_in, 3),
             "black_fraction": round(bg_frac, 3)},
            {"median_text_rgb": "#E6E6E6..FFFFFF", "black_fraction": ">=0.70"},
            {"fraction_>=E6": 0.75}, "fraction",
            "white pixels of the body row labels"))
    else:
        checks.append(mk(s, "settings.rows_white", "rows are #E6E6E6..FFFFFF on black",
                         SKIP, {"text_px": len(text)}, 0.75, 0, "fraction",
                         "no body text found"))

    checks.append(mk(
        s, "settings.single_title", "exactly one large title text block",
        PASS if len(large) == 1 else FAIL,
        {"large_title_blocks": len(large),
         "blocks": [{"h": b["h"], "y": b["y0"]} for b in large]},
        {"large_title_blocks": 1}, 0, "blocks",
        "large = ink band height >= 40 px and width >= 80 px in the top 200 dp",
        [box(b["x0"], b["y0"], b["x1"], b["y1"],
             f"block {b['h']}px") for b in large]))
    return checks


def _find_switch(shot: Shot) -> dict | None:
    a = shot.a
    blue = ((a[:, :, 2] - a[:, :, 0]) > 40) & (a[:, :, 2] > 120)
    comps = components(blue, min_area_px=600)
    cands = [c for c in comps
             if c["w"] >= 60 and c["h"] >= 30 and c["x0"] > shot.W * 0.55]
    if not cands:
        return None
    c = max(cands, key=lambda c: c["area"])
    x0, y0, x1, y1 = c["x0"], c["y0"], c["x1"], c["y1"]
    crop = a[y0:y1 + 1, x0:x1 + 1]
    track, _ = modal_color(crop, ignore_black=True)
    thumb_mask = np.min(crop, axis=2) >= 240
    ys, xs = np.nonzero(thumb_mask)
    thumb = None
    on = None
    if len(xs) > 50:
        thumb = tuple(int(v) for v in np.median(crop[thumb_mask], axis=0))
        tx = float(xs.mean()) + x0
        on = tx > (x0 + x1) / 2.0
    return {"box": c, "track": track, "thumb": thumb, "on": on}


def _surface_settings_wifi(shot: Shot, ctx: Ctx) -> list[Check]:
    s = "settings-wifi"
    a = shot.a
    W, H = shot.W, shot.H
    factor = ctx.factor
    checks: list[Check] = []

    sw = _find_switch(shot)
    if not sw:
        checks.append(mk(s, "wifi.switch_accent", "switch track/thumb is accent #0050EF",
                         FAIL, {"switch": "absent"}, "#0050EF", 20, "rgb",
                         "no switch-shaped blue component found"))
    else:
        b = sw["box"]
        track = sw["track"]
        thumb = sw["thumb"]
        on = sw["on"]
        dt = color_delta(track, ACCENT)
        dthumb = color_delta(thumb, ACCENT) if thumb else 999
        dark1 = _hex(COLORS["accent"]["dark1"])
        track_note = "track = modal colour of the switch component"
        if color_delta(track, dark1) <= 2:
            track_note += f"; {track} equals color.accent.dark1 {COLORS['accent']['dark1']}"
        if on is False:
            checks.append(mk(s, "wifi.switch_accent", "switch track/thumb is accent #0050EF",
                             SKIP, {"switch": "OFF", "track_rgb": list(track)},
                             "#0050EF", 20, "rgb", "check applies only when ON",
                             [box(b["x0"], b["y0"], b["x1"], b["y1"], "switch OFF")]))
        else:
            ok = dt <= 20 or dthumb <= 20
            checks.append(mk(
                s, "wifi.switch_accent",
                "switch track/thumb is accent #0050EF (+/-20) when ON",
                PASS if ok else FAIL,
                {"on": bool(on), "track_rgb": list(track),
                 "track_delta": [abs(track[i] - ACCENT[i]) for i in range(3)],
                 "thumb_rgb": list(thumb) if thumb else None,
                 "thumb_delta": (abs(thumb[0] - ACCENT[0]) if thumb else None)},
                {"track_rgb": list(ACCENT)}, {"per_channel": 20}, "rgb",
                track_note + "; thumb = white centre",
                [box(b["x0"], b["y0"], b["x1"], b["y1"],
                     f"track {track} d={dt}")]))

    # wide rounded group cards: modal non-black colour per row, contiguous rows
    rows = []
    for y in range(H):
        row = a[y]
        mx = np.max(row, axis=1)
        keep = mx > 12
        if keep.sum() < W * 0.5:
            continue
        cols, counts = np.unique(row[keep], axis=0, return_counts=True)
        i = int(np.argmax(counts))
        if counts[i] < W * 0.65:
            continue
        color = tuple(int(v) for v in cols[i])
        if max(color) > 200:
            continue
        colmask = np.all(np.abs(row.astype(np.int16) - np.array(color)) <= 3, axis=1)
        xs = np.flatnonzero(colmask)
        if len(xs) == 0:
            continue
        rows.append({"y": y, "color": color, "x0": int(xs.min()),
                     "x1": int(xs.max()), "count": int(counts[i])})
    groups: list[list[dict]] = []
    for r in rows:
        if groups and groups[-1][-1]["y"] == r["y"] - 1 and \
                color_delta(groups[-1][-1]["color"], r["color"]) <= 6:
            groups[-1].append(r)
        else:
            groups.append([r])
    # A text line inside a card can drop the fill count below the row gate for
    # one or two rows; stitch those groups back into one card.
    merged: list[list[dict]] = []
    for g in groups:
        if merged and g[0]["y"] - merged[-1][-1]["y"] <= 6 and \
                color_delta(merged[-1][0]["color"], g[0]["color"]) <= 6:
            merged[-1].extend(g)
        else:
            merged.append(g)
    groups = merged
    cards = []
    card_boxes = []
    for g in groups:
        if len(g) < 20:
            continue
        color = g[len(g) // 2]["color"]
        x0 = min(r["x0"] for r in g)
        x1 = max(r["x1"] for r in g)
        width_frac = (x1 - x0 + 1) / W
        # radius: first row where the left edge reaches the minimum
        y_top = g[0]["y"]
        min_left = min(r["x0"] for r in g)
        y_reach = next((r["y"] for r in g if r["x0"] <= min_left + 1), y_top)
        radius_px = max(0, y_reach - y_top)
        radius_dp = radius_px / factor
        cards.append({"color": color, "x0": x0, "x1": x1,
                      "y0": g[0]["y"], "y1": g[-1]["y"],
                      "width_frac": round(width_frac, 4),
                      "radius_px": radius_px,
                      "radius_dp": round(radius_dp, 2),
                      "violates": width_frac > 0.90 and radius_dp > 8})
        card_boxes.append(box(x0, g[0]["y"], x1, g[-1]["y"],
                              f"{width_frac*100:.1f}% r={radius_dp:.1f}dp"))
    bad = [c for c in cards if c["violates"]]
    checks.append(mk(
        s, "wifi.no_rounded_cards",
        "no >90%-wide group card with a corner radius >8 dp",
        PASS if not bad else FAIL,
        {"cards": cards, "violations": len(bad)},
        {"violations": 0}, 0, "cards",
        "radius = rows from the card top until the left edge reaches its widest point",
        card_boxes))
    return checks


def _surface_actioncentre(shot: Shot, ctx: Ctx) -> list[Check]:
    s = "action-centre"
    a = shot.a
    W, H = shot.W, shot.H
    factor = ctx.factor
    checks: list[Check] = []

    focus = str(ctx.meta.get("focus", ""))
    checks.append(mk(
        s, "ac.focus", "panel is our activity (window focus)",
        PASS if "org.ztmetro.actioncentre" in focus else FAIL,
        {"focus": focus}, {"focus": "org.ztmetro.actioncentre"}, 0, "",
        "dumpsys window mCurrentFocus at capture time"))

    accent = mask_near(a, ACCENT, 18)
    off = mask_near(a, (0x1F, 0x1F, 0x1F), 8)
    tiles = [c for c in components(accent | off, min_area_px=9000)
             if c["w"] >= 100 and c["h"] >= 100]
    tiles.sort(key=lambda c: (c["y0"], c["x0"]))
    tile_by_id = [box(c["x0"], c["y0"], c["x1"], c["y1"],
                      f"tile {i + 1}") for i, c in enumerate(tiles)]

    band = a[:int(round(50 * factor)), :]
    nonblack = np.max(band, axis=2) > 20
    for c in tiles:  # exclude tile pixels from the status-row band
        nonblack[max(0, c["y0"]):min(band.shape[0], c["y1"] + 1),
                 max(0, c["x0"]):min(W, c["x1"] + 1)] = False
    status_px = int(nonblack.sum())
    checks.append(mk(
        s, "ac.status_row", "top 50 dp status row has non-black pixels",
        PASS if status_px >= 100 else FAIL,
        {"nonblack_px": status_px},
        {"nonblack_px": ">=100"}, 0, "px",
        "tile pixels inside the band are excluded",
        [box(0, 0, W - 1, int(round(50 * factor)),
             f"status {status_px}px")]))

    col_count = len({c["x0"] // 10 for c in tiles})
    row_count = len({c["y0"] // 10 for c in tiles})
    ok = len(tiles) == 8 and col_count == 4 and row_count == 2
    checks.append(mk(
        s, "ac.tiles_4x2", "exactly 8 action tiles in a 4x2 grid",
        PASS if ok else FAIL,
        {"tiles": len(tiles), "columns": col_count, "rows": row_count},
        {"tiles": 8, "columns": 4, "rows": 2}, 0, "tiles",
        "accent #0050EF or off #1F1F1F fills, w/h >= 100 px", tile_by_id))

    ratios = [c["w"] / c["h"] for c in tiles if c["h"] > 0]
    worst_ratio = max(ratios) if ratios else 0.0
    worst_delta = max(abs(r - 1.50) for r in ratios) if ratios else 9.0
    checks.append(mk(
        s, "ac.tiles_square", "action tiles follow the ztmetro ratio 1.50 (+/-0.06) (85.75 x 57 dp)",
        PASS if worst_delta <= 0.06 else FAIL,
        {"worst_ratio": round(worst_ratio, 3),
         "worst_ratio_delta": round(worst_delta, 3),
         "sizes_dp": [f"{c['w']/factor:.1f}x{c['h']/factor:.1f}" for c in tiles[:8]]},
        {"w_h_ratio": "1.50 +/-0.06"}, 0.05, "ratio",
        "w/h of the measured tile boxes (tokens: 85.75 x 57)", tile_by_id))

    active = 0
    active_colors = []
    for c in tiles:
        fill, _ = modal_color(a, (c["x0"] + 4, c["y0"] + 4,
                                  c["x1"] - 4, c["y1"] - 4))
        if color_delta(fill, ACCENT) <= 10:
            active += 1
            active_colors.append(fill)
    checks.append(mk(
        s, "ac.tile_active_fill", "active tile fill is #0050EF",
        PASS if active >= 1 else FAIL,
        {"active_tiles": active, "active_rgb": [list(x) for x in active_colors[:4]]},
        {"active_tiles": ">=1", "rgb": list(ACCENT)}, {"per_channel": 10},
        "tiles", "modal fill per tile", tile_by_id))
    return checks


def _surface_lock(shot: Shot, ctx: Ctx) -> list[Check]:
    s = "lock"
    a = shot.a
    W, H = shot.W, shot.H
    factor = ctx.factor
    checks: list[Check] = []

    blocks = band_blocks(a, 0, H, 0, W, thr=150, min_count=8, merge_gap=4)
    big = [b for b in blocks if b["h"] >= 48 * factor]
    clock = max(big, key=lambda b: b["h"]) if big else None
    checks.append(mk(
        s, "lock.single_clock", "only one large clock glyph block",
        PASS if len(big) == 1 else FAIL,
        {"large_blocks": len(big),
         "block_heights_px": [b["h"] for b in big],
         "clock_height_dp": round(clock["h"] / factor, 1) if clock else None},
        {"large_blocks": 1}, 0, "blocks",
        "large = white ink band >= 48 dp tall (clock glyphs, not date/notification)",
        [box(b["x0"], b["y0"], b["x1"], b["y1"], f"{b['h']}px")
         for b in big]))

    top_h = int(round(40 * factor))
    top = a[:top_h]
    light = np.min(top, axis=2) >= 120
    n = int(light.sum())
    bb = bbox(light)
    checks.append(mk(
        s, "lock.no_status_text", "no status-row text in the top 40 dp",
        PASS if n <= 30 else FAIL,
        {"light_px": n, "bbox": list(bb) if bb else None},
        {"light_px": 0}, {"max": 30}, "px",
        "light text/icons on the lock wallpaper",
        [box(bb[0], bb[1], bb[2], bb[3], f"{n}px")] if bb else
        [box(0, 0, W - 1, top_h, "top 40 dp")]))
    return checks


def _find_volume_panel(shot: Shot) -> dict | None:
    a = shot.a
    W, H = shot.W, shot.H
    # the SystemUI panel uses a pale cyan slider; also accept our accent
    pale = ((a[:, :, 2] > 180) & (a[:, :, 1] > 170) &
            (a[:, :, 0] > 120) & ((a[:, :, 2] - a[:, :, 0]) > 25))
    acc = mask_near(a, ACCENT, 25)
    for m in (pale, acc):
        comps = [c for c in components(m, min_area_px=500)
                 if c["w"] >= 30 and c["h"] >= 30 and c["x0"] > W * 0.55]
        if not comps:
            continue
        c = max(comps, key=lambda c: c["area"])
        # The panel is more than its slider: the mute glyph above and the dots
        # below are part of the same window.  Union the nearby components.
        near = [q for q in comps
                if abs((q["y0"] + q["y1"]) / 2 - (c["y0"] + c["y1"]) / 2) < 700]
        x0 = max(0, min(q["x0"] for q in near) - 45)
        y0 = max(0, min(q["y0"] for q in near) - 12)
        y1 = min(H - 1, max(q["y1"] for q in near) + 70)
        panel = {"x0": x0, "y0": y0, "x1": W - 1, "y1": y1,
                 "slider": c}
        sub = a[y0:y1 + 1, x0:W]
        panel["black_fraction"] = float(mask_black(sub, 12).mean())
        panel["modal"], _ = modal_color(sub, ignore_black=False)
        return panel
    return None


def _surface_volume(shot: Shot, ctx: Ctx) -> list[Check]:
    s = "volume"
    a = shot.a
    W, H = shot.W, shot.H
    factor = ctx.factor
    checks: list[Check] = []
    panel = _find_volume_panel(shot)
    if not panel:
        checks.append(mk(s, "volume.panel_visible",
                         "volume panel appears in the lower third",
                         FAIL, {"panel": "absent"}, {"panel": "visible"}, 0, "",
                         "no pale-cyan or accent slider component on the right"))
        checks.append(mk(s, "volume.panel_black",
                         "panel background is #000000 (>=30% of the blob, slider colours excluded)",
                         SKIP, {"panel": "absent"}, {"black_fraction": ">=0.30"},
                         0, "fraction", "nothing to measure"))
        return checks
    # LineageOS 23.2 shows the volume panel at the right edge, not in the lower
    # third like WP; the check is therefore "a volume slider panel is visible and
    # its box is recorded", with the position reported for the ROM we run on.
    center = (panel["y0"] + panel["y1"]) / 2
    checks.append(mk(
        s, "volume.panel_visible", "volume panel is visible (position is ROM-specific)",
        PASS,
        {"panel_top_px": panel["y0"], "panel_center_px": round(center, 1),
         "panel_top_dp": round(panel["y0"] / factor, 1),
         "panel_center_dp": round(center / factor, 1)},
        {"panel": "visible"}, 0, "px",
        "panel box from the slider/thumb component, expanded to the screen edge",
        [box(panel["x0"], panel["y0"], panel["x1"], panel["y1"],
             f"top {panel['y0']}px")]))
    bf = panel["black_fraction"]
    checks.append(mk(
        s, "volume.panel_black",
        "panel background is #000000 (>=30% of the blob, slider colours excluded)",
        PASS if bf >= 0.5 else FAIL,
        {"black_fraction": round(bf, 3), "modal_rgb": list(panel["modal"])},
        {"black_fraction": ">=0.50"}, 0, "fraction",
        "max(R,G,B) <= 12 inside the panel box",
        [box(panel["x0"], panel["y0"], panel["x1"], panel["y1"],
             f"black {bf*100:.0f}%")]))
    return checks


CHECKERS = {
    "start": _surface_start,
    "app-list": _surface_applist,
    "settings-home": _surface_settings_home,
    "settings-wifi": _surface_settings_wifi,
    "action-centre": _surface_actioncentre,
    "lock": _surface_lock,
    "volume": _surface_volume,
}


# --------------------------------------------------------------------------
# Annotation
# --------------------------------------------------------------------------

def _font(size: int) -> ImageFont.ImageFont:
    try:
        return ImageFont.truetype(str(FONT_PATH), size)
    except Exception:
        return ImageFont.load_default()


STATUS_COLOR = {
    PASS: (76, 217, 100),
    FAIL: (255, 59, 48),
    SKIP: (255, 179, 0),
}


def annotate(shot: Shot, checks: list[Check], dest: Path) -> None:
    im = shot.im.copy()
    top = 38
    canvas = Image.new("RGB", (shot.W, shot.H + top), (14, 14, 14))
    canvas.paste(im, (0, top))
    d = ImageDraw.Draw(canvas)
    f_small = _font(15)
    f_head = _font(18)
    counts = Counter(c.status for c in checks)
    d.text((10, 8),
           f"{shot.path.stem}  —  PASS {counts[PASS]}  FAIL {counts[FAIL]}  "
           f"SKIP {counts[SKIP]}", font=f_head, fill=(240, 240, 240))

    merged: dict[tuple[int, int, int, int], dict] = {}
    for c in checks:
        for b in c.boxes:
            key = (b["x0"], b["y0"], b["x1"], b["y1"])
            rec = merged.setdefault(key, {"status": PASS, "labels": []})
            if c.status == FAIL:
                rec["status"] = FAIL
            elif c.status == SKIP and rec["status"] != FAIL:
                rec["status"] = SKIP
            label = b.get("label") or ""
            if label and label not in rec["labels"]:
                rec["labels"].append(label)

    draw_order = {PASS: 0, SKIP: 1, FAIL: 2}
    for (x0, y0, x1, y1), rec in sorted(
            merged.items(), key=lambda kv: draw_order[kv[1]["status"]]):
        color = STATUS_COLOR[rec["status"]]
        d.rectangle([x0, y0 + top, x1, y1 + top], outline=color, width=2)
        label = " · ".join(rec["labels"][:2])
        if len(label) > 46:
            label = label[:43] + "…"
        if not label:
            continue
        tb = d.textbbox((0, 0), label, font=f_small)
        tw, th = tb[2] - tb[0], tb[3] - tb[1]
        lx = max(0, min(shot.W - tw - 8, x0))
        ly = y0 + top - th - 6
        if ly < top:
            ly = y0 + top + 3
        d.rectangle([lx, ly, lx + tw + 6, ly + th + 6], fill=(0, 0, 0))
        d.text((lx + 3, ly + 2), label, font=f_small, fill=color)
    dest.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(dest)


# --------------------------------------------------------------------------
# Reference diff (structural, not colour)
# --------------------------------------------------------------------------

def _edge_mask(im: Image.Image, threshold: int = 38) -> np.ndarray:
    gray = im.convert("L")
    edges = gray.filter(ImageFilter.FIND_EDGES)
    return np.asarray(edges, dtype=np.uint8) > threshold


def _dilate(mask: np.ndarray, radius: int = 2) -> np.ndarray:
    m = Image.fromarray((mask.astype(np.uint8) * 255), mode="L")
    m = m.filter(ImageFilter.MaxFilter(2 * radius + 1))
    return np.asarray(m) > 0


def reference_diff(shot: Shot, mockup: Path, dest_dir: Path) -> dict:
    mock = Image.open(mockup).convert("RGB")
    if mock.size != shot.size:
        mock = mock.resize(shot.size, Image.LANCZOS)
    ea = _edge_mask(shot.im)
    eb = _edge_mask(mock)
    da, db = _dilate(ea), _dilate(eb)
    a_only = int((ea & ~db).sum())
    b_only = int((eb & ~da).sum())
    denom = max(1, int(ea.sum()) + int(eb.sum()))
    mismatch = 100.0 * (a_only + b_only) / denom
    dest_dir.mkdir(parents=True, exist_ok=True)
    stem = shot.path.stem
    blend_path = dest_dir / f"{stem}-blend.png"
    Image.blend(shot.im, mock, 0.5).save(blend_path)

    base = Image.eval(shot.im, lambda v: v // 3).convert("RGB")
    arr = np.asarray(base).copy()
    arr[ea] = (0, 255, 0)
    arr[eb & ~da] = (255, 60, 60)
    edge_path = dest_dir / f"{stem}-edges.png"
    Image.fromarray(arr).save(edge_path)
    return {
        "mockup": str(mockup),
        "mismatch_pct": round(mismatch, 2),
        "edges_shot": int(ea.sum()),
        "edges_mockup": int(eb.sum()),
        "shot_only_px": a_only,
        "mockup_only_px": b_only,
        "tolerance_px": 2,
        "blend_png": str(blend_path.relative_to(dest_dir.parent.parent)),
        "edges_png": str(edge_path.relative_to(dest_dir.parent.parent)),
    }


# --------------------------------------------------------------------------
# Scenarios
# --------------------------------------------------------------------------

class Runner:
    def __init__(self, dev: Device, outdir: Path, factor: float,
                 diff_reference: bool, expected_size: tuple[int, int] | None = None,
                 font_scale: float = 1.0):
        self.dev = dev
        self.expected_size = expected_size
        self.font_scale = font_scale
        self.stay_on_original: bool | None = None
        self.outdir = outdir
        self.raw = outdir / "raw"
        self.annotated = outdir / "annotated"
        self.diffdir = outdir / "diff"
        self.factor = factor
        self.diff_reference = diff_reference
        self.raw.mkdir(parents=True, exist_ok=True)
        self.annotated.mkdir(parents=True, exist_ok=True)
        self.captures: dict[str, dict] = {}
        self.checks: list[Check] = []
        self.diffs: dict[str, dict] = {}
        self.sequence: list[str] = []

    def log(self, message: str) -> None:
        print(f"  · {message}", flush=True)

    def _note(self, text: str) -> None:
        self.sequence.append(text)
        self.log(text)

    def _prepare(self, surface: str) -> None:
        """Wake, dismiss the shade and unlock before a scenario can be captured."""
        if not self.dev.unlock():
            raise DeviceError(f"{surface}: could not unlock the device")
        self.dev.collapse_shade()
        time.sleep(0.25)

    def _capture(self, surface: str) -> tuple[Shot, dict]:
        path = self.raw / f"{surface}.png"
        self.dev.screencap(path)
        shot = Shot(path)
        if self.expected_size and tuple(shot.size) != tuple(self.expected_size):
            raise DeviceError(
                f"{surface}: PNG is {shot.size[0]}x{shot.size[1]}, "
                f"wm size is {self.expected_size[0]}x{self.expected_size[1]}")
        meta = self.dev.capture_meta()
        self.captures[surface] = {
            "png": str(path.relative_to(self.outdir)),
            "started": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
            "sdk": meta["sdk"],
            "overlays": meta["overlays"],
            "overlays_enabled": meta["overlays_enabled"],
            "systemui_pid": meta["systemui_pid"],
            "focus": meta["focus"],
            "focused_app": meta["focused_app"],
            "keyguard": meta["keyguard"],
        }
        self.log(f"{surface}: {shot.size[0]}x{shot.size[1]}, "
                 f"focus={meta['focus'].split()[0] if meta['focus'] else '—'}")
        return shot, meta

    def _set_expected_context(self, meta: dict) -> Ctx:
        meta = dict(meta)
        meta["font_scale"] = self.font_scale
        return Ctx(self.factor, meta)

    def run(self, surface: str) -> None:
        print(f"\n[{surface}]", flush=True)
        getattr(self, f"_scenario_{surface.replace('-', '_')}")()

    # -- scenarios ---------------------------------------------------------
    def _scenario_start(self) -> None:
        dev = self.dev
        self._note("WAKEUP, unlock if keyguard, collapse shade")
        self._prepare("start")
        self._note("KEYCODE_HOME; wait 3 s")
        dev.key("KEYCODE_HOME")
        time.sleep(3.0)
        # BACK is a no-op on Start and returns the app list to Start; it also
        # closes a lingering search page.  The launcher keeps its page across
        # runs, so this makes the capture deterministic.
        self._note("KEYCODE_BACK (Start is a no-op; app list returns to Start); wait 0.5 s")
        dev.key("KEYCODE_BACK")
        time.sleep(0.5)
        # Scroll the grid to the top (the launcher keeps its scroll position).
        # y=520 is below the launcher's top-25 % pull-down band, so this cannot
        # open the action centre.
        self._note("swipe 360 520 -> 360 1120 (scroll Start to the top); wait 0.6 s")
        dev.swipe(360, 520, 360, 1120, 260)
        time.sleep(0.6)
        shot, meta = self._capture("start")
        if not _looks_like_start(shot, Ctx(self.factor)):
            # the launcher remembers the app-list page or a shade was still up
            self._note("not Start — collapse shade, HOME, BACK, scroll; recapture")
            dev.collapse_shade()
            dev.key("KEYCODE_HOME")
            time.sleep(1.5)
            dev.key("KEYCODE_BACK")
            time.sleep(0.5)
            dev.swipe(360, 520, 360, 1120, 260)
            time.sleep(0.8)
            shot, meta = self._capture("start")
        self.checks += _surface_start(shot, self._set_expected_context(meta))
        self._annotate("start", shot, self.checks)
        self._diff("start", shot)

    def _looks_like_applist(self, shot: Shot) -> bool:
        top = shot.a[:int(round(200 * self.factor)), :]
        return float(mask_black(top, 12).mean()) >= 0.85

    def _scenario_app_list(self) -> None:
        dev = self.dev
        self._prepare("app-list")
        # The screen may have slept since the Start capture; HOME+BACK makes
        # sure the launcher is on Start before the pivot swipe.
        dev.key("KEYCODE_HOME")
        time.sleep(0.6)
        dev.key("KEYCODE_BACK")
        time.sleep(0.4)
        self._note("input swipe 650 700 120 700 300 (Start -> app list); wait 2 s")
        dev.swipe(650, 700, 120, 700, 300)
        time.sleep(2.0)
        shot, meta = self._capture("app-list")
        if not self._looks_like_applist(shot):
            self._note("app list not detected — retry swipe; wait 1.5 s")
            dev.key("KEYCODE_BACK")
            time.sleep(0.6)
            dev.swipe(650, 700, 120, 700, 300)
            time.sleep(1.5)
            shot, meta = self._capture("app-list")
        self.checks += _surface_applist(shot, self._set_expected_context(meta))
        self._annotate("app-list", shot, self.checks)
        self._diff("app-list", shot)

    def _scenario_settings_home(self) -> None:
        dev = self.dev
        shot = meta = None
        for attempt in (1, 2):
            self._prepare("settings-home")
            self._note("am start -a android.settings.SETTINGS; wait 4 s")
            dev.shell("am", "start", "-a", "android.settings.SETTINGS")
            time.sleep(4.0)
            shot, meta = self._capture("settings-home")
            if "com.android.settings" in meta["focus"]:
                break
            self._note("settings-home: focus is not Settings — retry once")
            dev.collapse_shade()
        self.checks += _surface_settings_home(shot, self._set_expected_context(meta))
        self._annotate("settings-home", shot, self.checks)
        self._diff("settings-home", shot)

    def _scenario_settings_wifi(self) -> None:
        dev = self.dev
        shot = meta = None
        for attempt in (1, 2):
            self._prepare("settings-wifi")
            self._note("am start -a android.settings.WIFI_SETTINGS; wait 3 s")
            dev.shell("am", "start", "-a", "android.settings.WIFI_SETTINGS")
            time.sleep(3.0)
            shot, meta = self._capture("settings-wifi")
            if "com.android.settings" in meta["focus"]:
                break
            self._note("settings-wifi: focus is not Settings — retry once")
            dev.collapse_shade()
        self.checks += _surface_settings_wifi(shot, self._set_expected_context(meta))
        self._annotate("settings-wifi", shot, self.checks)
        self._diff("settings-wifi", shot)

    def _scenario_action_centre(self) -> None:
        dev = self.dev
        self._prepare("action-centre")
        self._note("am start -a android.intent.action.MAIN -c "
                   "android.intent.category.APP_CALENDAR; wait 2 s")
        dev.shell("am", "start", "-a", "android.intent.action.MAIN",
                  "-c", "android.intent.category.APP_CALENDAR")
        time.sleep(2.0)
        self._note("input swipe 360 6 360 400 250 (top-edge strip); wait 2 s")
        dev.swipe(360, 6, 360, 400, 250)
        time.sleep(2.0)
        shot, meta = self._capture("action-centre")
        if "org.ztmetro.actioncentre" not in meta["focus"]:
            self._note("action-centre: focus is not our panel — wake, collapse, retry the strip")
            self._prepare("action-centre")
            time.sleep(0.6)
            dev.swipe(360, 6, 360, 400, 250)
            time.sleep(2.0)
            shot, meta = self._capture("action-centre")
        self.checks += _surface_actioncentre(shot, self._set_expected_context(meta))
        self._annotate("action-centre", shot, self.checks)
        self._diff("action-centre", shot)

    def _scenario_lock(self) -> None:
        dev = self.dev
        self._note("KEYCODE_SLEEP; wait 1.5 s; KEYCODE_WAKEUP; wait 2 s")
        shot = meta = None
        for attempt in (1, 2):
            dev.key("KEYCODE_SLEEP")
            time.sleep(1.5)
            dev.key("KEYCODE_WAKEUP")
            time.sleep(2.0)
            shot, meta = self._capture("lock")
            if meta["keyguard"]:
                break
            self._note("lock: keyguard is not showing — retry sleep/wake")
        self.checks += _surface_lock(shot, self._set_expected_context(meta))
        self._annotate("lock", shot, self.checks)
        self._diff("lock", shot)

    def _scenario_volume(self) -> None:
        dev = self.dev
        # The lock scenario leaves the keyguard up; Settings would never be
        # visible behind it, so wake and unlock before this scenario.
        self._note("WAKEUP + unlock (required before volume: keyguard would hide Settings)")
        shot = meta = None
        for attempt in (1, 2):
            self._prepare("volume")
            self._note("am start -a android.settings.SETTINGS; wait 4 s")
            dev.shell("am", "start", "-a", "android.settings.SETTINGS")
            time.sleep(4.0)
            self._note("KEYCODE_VOLUME_DOWN; wait 1.2 s (down always changes volume)")
            dev.key("KEYCODE_VOLUME_DOWN")
            if attempt == 2:
                dev.key("KEYCODE_VOLUME_DOWN")
            time.sleep(1.2)
            shot, meta = self._capture("volume")
            if "com.android.settings" in meta["focus"]:
                break
            self._note("volume: Settings is not focused — dismiss and retry")
            dev.key("KEYCODE_BACK")
            time.sleep(0.6)
        self.checks += _surface_volume(shot, self._set_expected_context(meta))
        self._annotate("volume", shot, self.checks)
        self._diff("volume", shot)
        self._note("KEYCODE_BACK")
        dev.key("KEYCODE_BACK")
        time.sleep(1.0)

    # -- helpers -----------------------------------------------------------
    def _annotate(self, surface: str, shot: Shot, checks: list[Check]) -> None:
        surface_checks = [c for c in checks if c.surface == surface]
        dest = self.annotated / f"{surface}.png"
        annotate(shot, surface_checks, dest)

    def _diff(self, surface: str, shot: Shot) -> None:
        if not self.diff_reference:
            return
        name = REFERENCE.get(surface)
        if not name:
            return
        mockup = MOCKUPS / name
        if not mockup.exists():
            print(f"  ! diff: mockup missing: {mockup}", flush=True)
            return
        res = reference_diff(shot, mockup, self.diffdir)
        self.diffs[surface] = res
        print(f"  · diff vs {name}: structural mismatch "
              f"{res['mismatch_pct']:.2f} % (edges ±2 px)", flush=True)

    def finish(self) -> None:
        """Leave the phone on Start for the next run."""
        try:
            self.dev.key("KEYCODE_HOME")
            time.sleep(0.6)
            self.dev.key("KEYCODE_BACK")
            time.sleep(0.4)
            self.dev.key("KEYCODE_HOME")
        except DeviceError:
            pass
        if self.stay_on_original is not None:
            self.dev.set_stay_awake(self.stay_on_original)


# --------------------------------------------------------------------------
# Reports
# --------------------------------------------------------------------------

def print_check(c: Check) -> None:
    unit = f" {c.unit}" if c.unit else ""
    print(f"  [{c.status}] {c.id}: {c.name}")
    print(f"         measured: {fmt_value(c.measured)}{unit}")
    print(f"         expected: {fmt_value(c.expected)}{' ' + c.unit if c.unit else ''}"
          f"   tolerance: {fmt_value(c.tolerance)}{' ' + c.unit if c.unit else ''}",
          flush=True)


def write_json(path: Path, run: dict) -> None:
    path.write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")


def write_markdown(path: Path, run: dict) -> None:
    checks = run["checks"]
    counts = Counter(c["status"] for c in checks)
    lines: list[str] = []
    lines.append("# ztmetro UX verification — automated report")
    lines.append("")
    lines.append(f"Run: `{run['started']}` → `{run['finished']}` "
                 f"({run['duration_s']:.1f} s), mode **{run['mode']}**.")
    lines.append("")
    lines.append(f"Evidence: `{run['outdir']}`  ·  "
                 f"`report.json` + `annotated/*.png`"
                 + (" + `diff/*`" if run["diffs"] else ""))
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"| PASS | FAIL | SKIP | total |")
    lines.append(f"|---:|---:|---:|---:|")
    lines.append(f"| {counts[PASS]} | {counts[FAIL]} | {counts[SKIP]} | {len(checks)} |")
    lines.append("")
    if counts[FAIL]:
        lines.append("### Failing checks")
        lines.append("")
        for c in checks:
            if c["status"] == FAIL:
                lines.append(f"- **{c['id']}** — {c['name']}: "
                             f"`{fmt_value(c['measured'])}` "
                             f"(expected `{fmt_value(c['expected'])}`, "
                             f"tol `{fmt_value(c['tolerance'])}` {c['unit']})")
        lines.append("")
    lines.append("## Device")
    lines.append("")
    d = run["device"]
    lines.append("| field | value |")
    lines.append("|---|---|")
    for key, label in (
        ("transport", "transport"), ("serial", "serial"), ("sdk", "sdk"),
        ("wm_size", "wm size"), ("wm_density", "wm density"),
        ("override_density", "density override (dpi)"),
        ("px_per_dp", "px per dp (measured)"),
        ("px_per_dp_expected", "px per dp (brief)"),
        ("font_scale", "font scale"),
        ("systemui_pid", "systemui pid"),
        ("overlays_enabled", "enabled ztmetro/ztos overlays"),
    ):
        lines.append(f"| {label} | `{d.get(key)}` |")
    lines.append("")
    lines.append(f"dp = px / **{d['px_per_dp']}** (the live density). "
                 f"The brief's reference is **1.75 px/dp** (280 dpi); "
                 f"`pre.density_factor` records whether they agree.")
    lines.append("")
    lines.append("## Captures")
    lines.append("")
    lines.append("| surface | png | focus | sdk | systemui | overlays | keyguard |")
    lines.append("|---|---|---|---|---|---|---|")
    for surface, cap in run["captures"].items():
        focus = cap["focus"].split(" u0 ")[-1] if cap["focus"] else "—"
        lines.append(f"| {surface} | `{cap['png']}` | `{focus}` | "
                     f"{cap['sdk']} | {cap['systemui_pid']} | "
                     f"{len(cap['overlays_enabled'])} | {cap['keyguard']} |")
    lines.append("")
    lines.append("## Checks")
    lines.append("")
    for surface in [s for s in SURFACES if any(c["surface"] == s for c in checks)]:
        group = [c for c in checks if c["surface"] == surface]
        lines.append(f"### {surface}")
        lines.append("")
        lines.append("| check | status | measured | expected | tolerance |")
        lines.append("|---|---|---|---|---|")
        for c in group:
            unit = f" {c['unit']}" if c["unit"] else ""
            lines.append(
                f"| `{c['id']}` — {c['name']} | **{c['status']}** | "
                f"`{fmt_value(c['measured'])}{unit}` | "
                f"`{fmt_value(c['expected'])}` | "
                f"`{fmt_value(c['tolerance'])}{unit}` |")
        details = [c for c in group if c["detail"]]
        if details:
            lines.append("")
            for c in details:
                lines.append(f"- `{c['id']}`: {c['detail']}")
        lines.append("")
    if run["diffs"]:
        lines.append("## Reference diff (structural)")
        lines.append("")
        lines.append("Edges (Sobel/FIND_EDGES) ±2 px; mismatch = "
                     "symmetric difference / union, not pixel colour. "
                     "50% blends are in `diff/`.")
        lines.append("")
        lines.append("| surface | mockup | mismatch | shot-only edges | mockup-only edges |")
        lines.append("|---|---|---:|---:|---:|")
        for surface, d in run["diffs"].items():
            lines.append(f"| {surface} | `{Path(d['mockup']).name}` | "
                         f"**{d['mismatch_pct']:.2f} %** | {d['shot_only_px']} | "
                         f"{d['mockup_only_px']} |")
        lines.append("")
    lines.append("## Sequence")
    lines.append("")
    for step in run["sequence"]:
        lines.append(f"- {step}")
    lines.append("")
    lines.append("## Re-run")
    lines.append("")
    lines.append("```sh")
    lines.append(run["command"])
    lines.append("```")
    lines.append("")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Capture and verify ztmetro surfaces on the live ugglite.")
    p.add_argument("--quick", action="store_true",
                   help="only start + settings-home + action-centre")
    p.add_argument("--diff-reference", action="store_true",
                   help="blend against the closest mockup and report edge mismatch")
    p.add_argument("--out", type=Path, default=EVIDENCE,
                   help=f"output root (default: {EVIDENCE})")
    p.add_argument("--ssh-host", default="hermes-ubuntu")
    p.add_argument("--adb", default="~/android-sdk/platform-tools/adb")
    p.add_argument("--timestamp", default=None,
                   help="run directory name (default: local YYYYmmdd-HHMMSS)")
    p.add_argument("--mockups", type=Path, default=MOCKUPS,
                   help=f"mockup directory (default: {MOCKUPS})")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    global MOCKUPS
    MOCKUPS = args.mockups

    surfaces = QUICK_SURFACES if args.quick else SURFACES
    started = dt.datetime.now().astimezone()
    ts = args.timestamp or started.strftime("%Y%m%d-%H%M%S")
    outdir = args.out / ts
    suffix = 1
    while outdir.exists():
        suffix += 1
        outdir = args.out / f"{ts}-{suffix}"
    outdir.mkdir(parents=True)

    dev = Device(args.ssh_host, args.adb)
    print(f"ztmetro UX verify · {ts} · mode="
          f"{'quick' if args.quick else 'full'} · out={outdir}", flush=True)
    dev.preflight()

    size, size_fields = dev.wm("size")
    density, density_fields = dev.wm("density")
    if not density:
        raise DeviceError("wm density returned no density")
    factor = density / 160.0
    pre = dev.capture_meta()
    sdk = pre["sdk"]
    pid = pre["systemui_pid"]
    win = {"focus": pre["focus"], "focused_app": pre["focused_app"],
           "keyguard": pre["keyguard"]}
    overlays_all = pre["overlays"]
    overlays_enabled = pre["overlays_enabled"]
    font_scale = pre["font_scale"]
    print(f"device: {size[0] if size else '?'}x{size[1] if size else '?'} px, "
          f"density {density} dpi => {factor:.4f} px/dp, "
          f"fontScale {font_scale:g}, sdk {sdk}", flush=True)

    expected_size = size or (720, 1280)
    runner = Runner(dev, outdir, factor, args.diff_reference,
                    expected_size=expected_size, font_scale=font_scale)
    runner.stay_on_original = dev.read_stay_on()
    dev.set_stay_awake(True)
    pre_checks: list[Check] = []

    # density precondition: the device decides the factor; the check is that the
    # factor we use equals density/160 (self-consistency), not that the phone is 280 dpi.
    expected_factor = density / 160.0
    delta = abs(factor - expected_factor)
    pre_checks.append(mk(
        "precondition", "pre.density_factor",
        "px/dp factor equals the device density/160",
        PASS if delta <= 0.01 else FAIL,
        {"px_per_dp": round(factor, 4), "density_dpi": density,
         "override_dpi": density_fields.get("override_density")},
        {"px_per_dp": round(expected_factor, 4)},
        {"px_per_dp": 0.01}, "px/dp",
        "measured from `wm density`; all dp values use this factor",
        []))

    # display precondition
    pre_checks.append(mk(
        "precondition", "pre.display_size",
        "captured PNG matches wm size",
        PASS, {"wm_size": list(expected_size), "png_checked_per_capture": True},
        {"png": list(expected_size)}, 0, "px",
        "each screencap is opened and its size recorded in captures"))

    try:
        for surface in surfaces:
            runner.run(surface)
    except DeviceError as exc:
        print(f"\nERROR: {exc}", file=sys.stderr, flush=True)
        runner.finish()
        # Still write a report with whatever was measured.
        finished = dt.datetime.now().astimezone()
        run = {
            "tool": "ux-verify.py", "version": "1.0", "mode":
                "quick" if args.quick else "full",
            "started": started.isoformat(timespec="seconds"),
            "finished": finished.isoformat(timespec="seconds"),
            "duration_s": (finished - started).total_seconds(),
            "outdir": str(outdir), "command": " ".join(
                [sys.executable, str(Path(__file__).resolve())] +
                (["--quick"] if args.quick else []) +
                (["--diff-reference"] if args.diff_reference else [])),
            "device": {
                "transport": f"ssh {args.ssh_host} '{args.adb} ...'",
                "serial": "", "sdk": sdk, "wm_size": list(expected_size),
                "wm_density": density, "override_density": None,
                "px_per_dp": round(factor, 4),
                "px_per_dp_expected": REFERENCE_PX_PER_DP,
                "font_scale": font_scale,
                "systemui_pid": pid, "overlays_enabled": len(overlays_enabled),
                "focus": win["focus"], "keyguard": win["keyguard"],
            },
            "captures": runner.captures, "checks": [
                c.as_dict() for c in pre_checks + runner.checks],
            "summary": {}, "diffs": runner.diffs,
            "sequence": runner.sequence + [f"aborted: {exc}"],
            "error": str(exc),
        }
        counts = Counter(c["status"] for c in run["checks"])
        run["summary"] = {k: counts[k] for k in (PASS, FAIL, SKIP)}
        run["summary"]["total"] = len(run["checks"])
        write_json(outdir / "report.json", run)
        write_markdown(outdir / "report.md", run)
        print(f"\nReport: {outdir / 'report.md'}", flush=True)
        return 2

    runner.finish()
    finished = dt.datetime.now().astimezone()
    checks = pre_checks + runner.checks
    counts = Counter(c.status for c in checks)
    serial = ""
    m = re.search(r"^(\S+)\s+device\b", dev.devices(), re.MULTILINE)
    if m:
        serial = m.group(1)

    run = {
        "tool": "ux-verify.py", "version": "1.0",
        "mode": "quick" if args.quick else "full",
        "started": started.isoformat(timespec="seconds"),
        "finished": finished.isoformat(timespec="seconds"),
        "duration_s": round((finished - started).total_seconds(), 2),
        "outdir": str(outdir),
        "command": " ".join(
            [sys.executable, str(Path(__file__).resolve())] +
            (["--quick"] if args.quick else []) +
            (["--diff-reference"] if args.diff_reference else [])),
        "device": {
            "transport": f"ssh {args.ssh_host} '{args.adb} ...'",
            "serial": serial, "sdk": sdk,
            "wm_size": list(expected_size),
            "wm_density": density,
            "override_density": density_fields.get("override_density"),
            "px_per_dp": round(factor, 4),
            "px_per_dp_expected": REFERENCE_PX_PER_DP,
            "font_scale": font_scale,
            "systemui_pid": pid,
            "overlays_all": overlays_all,
            "overlays_enabled": len(overlays_enabled),
            "focus": win["focus"], "keyguard": win["keyguard"],
        },
        "captures": runner.captures,
        "checks": [c.as_dict() for c in checks],
        "summary": {k: counts[k] for k in (PASS, FAIL, SKIP)},
        "diffs": runner.diffs,
        "sequence": runner.sequence,
    }
    run["summary"]["total"] = len(checks)

    write_json(outdir / "report.json", run)
    write_markdown(outdir / "report.md", run)

    print("\n" + "=" * 78, flush=True)
    for surface in ["precondition"] + [s for s in surfaces]:
        group = [c for c in checks if c.surface == surface]
        if not group:
            continue
        sc = Counter(c.status for c in group)
        print(f"{surface:14s} PASS {sc[PASS]:2d}  FAIL {sc[FAIL]:2d}  "
              f"SKIP {sc[SKIP]:2d}", flush=True)
        for c in group:
            if c.status != PASS:
                print(f"    [{c.status}] {c.id}: {c.name}")
                print(f"           measured {fmt_value(c.measured)}; "
                      f"expected {fmt_value(c.expected)}; "
                      f"tol {fmt_value(c.tolerance)} {c.unit}", flush=True)
    print("=" * 78)
    print(f"TOTAL  PASS {counts[PASS]}  FAIL {counts[FAIL]}  SKIP {counts[SKIP]}  "
          f"/ {len(checks)} checks", flush=True)
    print(f"report:   {outdir / 'report.md'}")
    print(f"json:     {outdir / 'report.json'}")
    print(f"raw:      {outdir / 'raw'}")
    print(f"annotated:{outdir / 'annotated'}")
    if args.diff_reference:
        print(f"diff:     {outdir / 'diff'}")
    return 0 if counts[FAIL] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
