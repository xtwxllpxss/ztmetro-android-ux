#!/usr/bin/env python3
"""Reproduce the parity numbers from the PNGs (PIL + numpy).

    python3 repo/evidence/parity/measure.py

Mockups are 720x1280 at 2.0 px/dp; the device screenshots are 720x1280 at
1.625 px/dp (260 dpi). Lock values are reported as distance from the bottom
because the lock block is bottom-anchored; the screen is 788 dp, not 640.
"""
from PIL import Image
import numpy as np

M, D = 2.0, 1.625
ROOT = "repo/evidence/ux-compare/current/"
MOCK = "mockups/out/"


def load(path):
    return np.array(Image.open(path).convert("RGB")).astype(int)


def bbox(im, x0, y0, x1, y1, thr=150):
    sub = im[y0:y1, x0:x1]
    m = sub.max(axis=2) > thr
    ys, xs = np.where(m)
    if len(xs) == 0:
        return None
    return (x0 + xs.min(), y0 + ys.min(), x0 + xs.max(), y0 + ys.max())


def dp(b, f):
    return tuple(round(v / f, 1) for v in b)


def report():
    start = load(ROOT + "k0-start.png")
    a = load(ROOT + "p0-apps.png")
    ac = load(ROOT + "d2-action.png")
    lock = load(ROOT + "cc-lock.png")

    print("START device first tile top px", bbox(start, 0, 40, 500, 280)[1],
          "->", round(bbox(start, 0, 40, 500, 280)[1] / D, 1), "dp")
    print("APPS caps ink", dp(bbox(a, 0, 60, 700, 110), D))
    print("APPS icon square x/width", dp(bbox(a, 115, 440, 215, 540), D))
    print("AC tile1", dp(bbox(ac, 0, 50, 200, 250), D))
    print("AC details line", dp(bbox(ac, 0, 0, 720, 60), D))
    print("LOCK clock ink", dp(bbox(lock, 0, 800, 720, 1020), D),
          "from bottom", round((1280 - bbox(lock, 0, 800, 720, 1020)[3]) / D, 1))
    print("LOCK hint ink", dp(bbox(lock, 0, 1200, 720, 1280, 100), D))


if __name__ == "__main__":
    report()
