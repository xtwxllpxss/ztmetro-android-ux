#!/usr/bin/env python3
"""Empirical measurement of the QS tile shape in evidence/ux-compare/current/93-shade.png.

Reads the *rendered* shade, finds the tile bands and fits the corner radius of the
top-left corner of two tiles in the first row:
  * "Интернет"  (inactive, big label + secondary label)
  * "Bluetooth" (active, dual-target: dark tile + accent icon square)
Prints px and dp (at the density implied by the Compose tile height) so the rendered
radius can be compared with the constants in the live SystemUI APK.
"""
import sys, math
import numpy as np
from PIL import Image

img = sys.argv[1] if len(sys.argv) > 1 else "repo/evidence/ux-compare/current/93-shade.png"
im = np.array(Image.open(img).convert("RGB")).astype(int)
bg = np.array([8, 11, 11])                      # tile fill sampled inside a tile
mask = np.abs(im - bg).sum(axis=2) <= 8

rows = np.where(mask.sum(axis=1) > 50)[0]
bands, s, p = [], rows[0], rows[0]
for r in rows[1:]:
    if r > p + 3:
        bands.append((s, p)); s = r
    p = r
bands.append((s, p))
print(f"image {img} {im.shape[1]}x{im.shape[0]}")
print("tile bands (y0,y1,height):", [(a, b, b - a + 1) for a, b in bands[:4]])

def fit_radius(y0, x0, xmax, rows_n=40):
    """Fit r to left-edge profile of a rounded rect whose flat edge starts at x0."""
    insets = []
    for dy in range(1, rows_n + 1):
        xs = np.where(mask[y0 + dy, x0:xmax])[0]
        if not len(xs):
            break
        insets.append((dy, xs.min()))
    best = min(
        ((sum((r - math.sqrt(max(r * r - (r - dy) ** 2, 0)) - dx) ** 2
              for dy, dx in insets), r) for r in np.arange(5.0, 120.0, 0.25)),
        key=lambda t: t[0])
    return best[1], insets[:4]

y0, y1 = bands[1]
h = y1 - y0 + 1
# mid-height flat edges (full width at the vertical centre of the shape)
mid = (y0 + y1) // 2
runs, s = [], None
tileish = im[mid].sum(axis=1) > 10
for x, v in enumerate(tileish):
    if v and s is None: s = x
    if not v and s is not None: runs.append((s, x - 1)); s = None
if s is not None: runs.append((s, len(mask[mid]) - 1))
print(f"mid-row runs: {runs[:4]}")
internet, bluetooth = runs[0], runs[1]
print(f"gap between tiles: {bluetooth[0] - internet[1] - 1} px")

for name, (xa, xb) in (("internet (inactive)", internet), ("bluetooth (active)", bluetooth)):
    r, prof = fit_radius(y0, xa, xa + 120)
    print(f"{name}: x={xa}..{xb} height={h}px  fitted corner r={r:.2f}px  "
          f"r/height={r/h:.3f}  profile(dy,dx)={prof}")

# density implied by the Compose TileHeight (72 dp) and the measured band height
d = h / 72.0
print(f"\ndensity implied by tile height 72dp: {d:.4f} (px/dp); screen = {im.shape[1]/d:.1f}dp wide")
print(f"tile height {h}px = {h/d:.2f}dp (Compose CommonTileDefaults.TileHeight = 72dp)")
print(f"side margin {internet[0]}px = {internet[0]/d:.2f}dp (stock qs_tiles_page_horizontal_margin 16dp)")
print(f"tile gap 7px = {7/d:.2f}dp (overlay qs_tile_margin_horizontal 4dp; stock 8dp = {8*d:.1f}px)")
for name, (xa, xb) in (("internet", internet), ("bluetooth", bluetooth)):
    r, _ = fit_radius(y0, xa, xa + 120)
    print(f"{name} r = {r/d:.2f}dp")
print("\nexpected if Compose: inactive = min(50dp, h/2) = 36dp stadium; active = 24dp")
print("expected if a single dimen were used: qs_corner_radius 28dp (stock) or 0dp (RRO) on BOTH tiles")
