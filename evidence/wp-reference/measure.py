#!/usr/bin/env python3
"""Measure the numbers in docs/17-wp-design-reference.md on any frame.

Usage:
    python3 measure.py <frame.png> [--dp-scale 4] [--row Y] [--band Y0 Y1]

What it prints, per frame:
  * gutter/margin runs: columns that are pure black (>90 % of a tile row), and
    rows that are pure black, restricted to the tile band -> tile edges, gutter,
    side margin, row pitch;
  * ink rows: runs of rows containing light pixels -> glyph bands (caps line,
    page title, row text), with each band's x-span and ink-row count;
  * colour probes: the mean of a 20x20 patch at given points.

dp = px / dp_scale (ztos frames: 4, ztmetro frames: 2, WP frames: 480/width).
Nothing here guesses: every printed number is a raw pixel run.
"""
import argparse
import numpy as np
from PIL import Image


def load(path):
    return np.asarray(Image.open(path).convert("RGB")).astype(int)


def runs(mask_1d, minlen=1):
    out, start = [], None
    for i, v in enumerate(mask_1d):
        if v and start is None:
            start = i
        if not v and start is not None:
            if i - start >= minlen:
                out.append((start, i - 1))
            start = None
    if start is not None and len(mask_1d) - start >= minlen:
        out.append((start, len(mask_1d) - 1))
    return out


def gutters(a, y0, y1, thr=40, frac=0.9):
    """Columns dark over the whole band: tile boundaries."""
    band = a[y0:y1 + 1]
    dark = (band.max(axis=2) < thr).mean(axis=0) > frac
    return runs(dark)


def ink_rows(a, thr=110, x0=0, x1=None, minpix=2):
    sub = a[:, x0:x1]
    lit = sub.max(axis=2) > thr
    out = []
    for s, e in runs(lit.sum(axis=1) >= minpix):
        cols = np.where(lit[s:e + 1].any(axis=0))[0]
        out.append((s, e, e - s + 1, int(cols.min()) + x0, int(cols.max()) + x0))
    return out


def patch(a, x, y, n=20):
    p = a[y:y + n, x:x + n].reshape(-1, 3).mean(axis=0).round().astype(int)
    return "#%02X%02X%02X" % tuple(p)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("frame")
    ap.add_argument("--dp-scale", type=float, default=1.0,
                    help="px per dp (ztos 4, ztmetro 2, WP 480/frame_width)")
    ap.add_argument("--row", type=int, nargs=2, metavar=("Y0", "Y1"),
                    help="band to test for tile gutters")
    ap.add_argument("--ink", type=int, nargs=2, metavar=("X0", "X1"),
                    help="x window for the ink-row scan")
    ap.add_argument("--patch", type=int, nargs=2, action="append", metavar=("X", "Y"))
    args = ap.parse_args()

    a = load(args.frame)
    h, w, _ = a.shape
    s = args.dp_scale
    print(f"# {args.frame} {w}x{h}  dp = px/{s:g}")
    if args.row:
        y0, y1 = args.row
        g = gutters(a, y0, y1)
        print(f"# tile gutters in rows {y0}..{y1}:")
        for s0, e0 in g:
            kind = "margin" if (s0 == 0 or e0 == w - 1) else "gutter"
            print(f"  {kind:6s} x {s0}-{e0}  width {e0 - s0 + 1} px = {(e0 - s0 + 1) / s:.2f} dp"
                  f" = {100 * (e0 - s0 + 1) / w:.2f} %W")
        edges = [r[1] + 1 for r in g]
        steps = [b - a0 for a0, b in zip(edges, edges[1:])]
        print(f"  edges: {edges}  steps: {steps}")
    if args.ink:
        x0, x1 = args.ink
        print(f"# ink rows, x {x0}..{x1}:")
        for s0, e0, hh, cx0, cx1 in ink_rows(a, x0=x0, x1=x1):
            print(f"  y {s0}-{e0}  h {hh} px = {hh / s:.2f} dp  x {cx0}..{cx1}")
    if args.patch:
        print("# colour probes")
        for x, y in args.patch:
            print(f"  ({x},{y}) = {patch(a, x, y)}")


if __name__ == "__main__":
    main()
