#!/usr/bin/env python3
"""Render the Russian sample in the shipped Open Sans faces and in Selawik 1.01.

This is a visual companion to the cmap numbers: Open Sans shows Cyrillic, Selawik
1.01 (the previous module payload) has no Cyrillic glyphs and renders the sample with
.notdef boxes. Requires Pillow (bundled runtime); no fontTools.

Usage: render_cyrillic_proof.py REPO_ROOT OUT.png
"""
import sys

from PIL import Image, ImageDraw, ImageFont

root, out = sys.argv[1], sys.argv[2]
FONTS = root + "/fonts"
W, H = 1100, 420
BG = (0, 0, 0)
FG = (255, 255, 255)
DIM = (153, 153, 153)

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

small = ImageFont.truetype(FONTS + "/opensans/OpenSans-Regular.ttf", 15)
head = ImageFont.truetype(FONTS + "/opensans/OpenSans-SemiBold.ttf", 22)

d.text((32, 18), "ztmetro-font 1.1.0 — Cyrillic coverage, rendered", font=head, fill=FG)
d.text((32, 50), "sample: Привет, мир. Съешь ещё этих мягких французских булок, да выпей чаю. ЁЖИК — №1",
       font=small, fill=DIM)

rows = [
    ("OpenSans-Regular.ttf (module payload, 400)", FONTS + "/opensans/OpenSans-Regular.ttf", 44),
    ("OpenSans-SemiBold.ttf (module payload, 600)", FONTS + "/opensans/OpenSans-SemiBold.ttf", 44),
    ("Selawik-Regular.ttf (old 1.0.0 payload)", FONTS + "/selawik/Selawik-Regular.ttf", 44),
]
y = 92
for label, path, size in rows:
    d.text((32, y), label, font=small, fill=DIM)
    f = ImageFont.truetype(path, size)
    d.text((32, y + 22), "Привет, мир — ЁЖИК  я  Ж  ё  №1", font=f, fill=FG)
    y += 108

d.text((32, H - 30), "Selawik 1.01 cmap has 347 codepoints, no U+0410; Open Sans has 248/256 of U+0400–U+04FF.",
       font=small, fill=DIM)
img.save(out)
print(out)
