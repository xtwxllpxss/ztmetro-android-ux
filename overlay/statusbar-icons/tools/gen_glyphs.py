#!/usr/bin/env python3
"""Generate the ztmetro status-bar glyphs (WP/Metro, white, 24-unit viewport).

Writes:
  res/drawable/*.xml          the 20 resources this RRO overrides (compiled)
  fallback/drawable/*.xml     reference glyphs for battery / wifi / signal --
                              NOT part of the APK: SystemUI does not read those
                              three from drawables (see evidence/REPORT.md)
  tools/preview.html          black status-strip + enlarged glyph sheet

The XML is emitted from the same geometry as the preview, so the PNG in
evidence/ is the vector that ships, not a separate drawing.

Geometry is taken from ztmetro.c (upstream/ztOS/ztmetro/ztmetro.c):
  draw_signal()  five bars, width w, heights 0.2..1.0 h, pitch w/4.8, bar 0.8 pitch
  draw_wifi()    quarter fan: 3 bands 0.115 s thick, outer edges 1.0/0.756/0.513 s,
                 corner dot r 0.115 s, bottom-right corner
  status_row()   battery 1.95 x cell tall with a terminal nub and an inner fill
Everything else (alarm, bluetooth rune, headset, airplane, shield) is drawn in
the same flat, square-cap, single-weight language.

No colour: every path is #FFFFFFFF (the dim alarm is the same white at 0.3).
No cross-resource references: the overlaid drawable must be self-contained,
because idmap2 only rewrites references that exist in the target package
(docs/10b-overlay-and-hook-facts.md section 3a).

Usage: python3 tools/gen_glyphs.py
"""
import html
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "res" / "drawable"
FALLBACK = ROOT / "fallback" / "drawable"
WHITE = "#FFFFFFFF"

# ---------------------------------------------------------------- path helpers


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s else "0"


def pt(x, y):
    return f"{fmt(x)},{fmt(y)}"


def poly(points, close=True):
    out = "M" + pt(*points[0])
    for p in points[1:]:
        out += " L" + pt(*p)
    return out + (" Z" if close else "")


def circle(cx, cy, r):
    return (f"M{fmt(cx - r)},{fmt(cy)}"
            f" a{fmt(r)},{fmt(r)} 0 1,0 {fmt(2 * r)},0"
            f" a{fmt(r)},{fmt(r)} 0 1,0 {fmt(-2 * r)},0")


def rect(x0, y0, x1, y1):
    return poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])


def arc_band(cx, cy, r, band):
    """Upper-left quarter of a circle, corner at (cx, cy) bottom-right."""
    rr = r - band / 2.0
    return (f"M{fmt(cx - r)},{fmt(cy)} "
            f"A{fmt(rr)},{fmt(rr)} 0 0 1 {fmt(cx)},{fmt(cy - r)}")


# ---------------------------------------------------------------- path records


def stroke(d, width=2.0, alpha=1.0):
    return {"d": d, "kind": "stroke", "w": width, "alpha": alpha}


def fill(d, alpha=1.0):
    return {"d": d, "kind": "fill", "alpha": alpha}


# ---------------------------------------------------------------- glyph shapes


def alarm(alpha=1.0):
    return [
        stroke(circle(12, 13.2, 8.2), 2.0, alpha),
        stroke("M12,13.2 L12,8.4", 2.0, alpha),
        stroke("M12,13.2 L15.7,15.6", 2.0, alpha),
        stroke("M6.6,6.2 L4.5,4.1", 2.0, alpha),
        stroke("M17.4,6.2 L19.5,4.1", 2.0, alpha),
    ]


def bluetooth_rune(scale=1.0, dx=0.0):
    """Classic rune: vertical spine + two triangles, square caps."""
    def tp(x, y):
        return (12 + (x - 12) * scale + dx, 12 + (y - 12) * scale)
    spine = [tp(12, 3), tp(12, 21)]
    zig = [tp(12, 3), tp(17.5, 7.5), tp(12, 12), tp(17.5, 16.5), tp(12, 21)]
    return [stroke(poly(spine, close=False), 2.0),
            stroke(poly(zig, close=False), 2.0)]


def headset(mic=False):
    paths = [
        stroke("M2.9,12.2 A9.1,9.1 0 0 1 21.1,12.2", 2.0),
        stroke(rect(2.6, 11.6, 6.0, 21.5), 2.0),
        stroke(rect(18.0, 11.6, 21.4, 21.5), 2.0),
    ]
    if mic:
        paths.append(stroke("M18.2,22.5 L22.8,22.5", 2.0))
    return paths


def airplane():
    return [fill(
        "M12,2.5 L13.7,8.6 L21,12.6 L21,14.1 L13.7,12.3 L13.7,17.6"
        " L16.2,19.6 L16.2,21.1 L12,19.7 L7.8,21.1 L7.8,19.6 L10.3,17.6"
        " L10.3,12.3 L3,14.1 L3,12.6 L10.3,8.6 Z")]


def shield(exclamation=False):
    paths = [stroke(
        "M12,2.6 L19.6,5.6 L19.6,12 C19.6,16.4 16.4,19.6 12,21.4"
        " C7.6,19.6 4.4,16.4 4.4,12 L4.4,5.6 Z", 2.0)]
    if exclamation:
        paths.append(stroke("M12,8.1 L12,13.0", 2.0))
        paths.append(fill(circle(12, 15.9, 1.25)))
    return paths


def battery(level=75):
    inner_x0, inner_x1 = 4.9, 18.5
    inner_y0, inner_y1 = 8.7, 15.3
    w = (inner_x1 - inner_x0) * level / 100.0
    paths = [
        stroke(rect(3.0, 7.2, 19.4, 16.8), 1.8),
        fill(rect(19.4, 10.2, 21.4, 13.8)),
    ]
    if w > 0.2:
        paths.append(fill(rect(inner_x0, inner_y0, inner_x0 + w, inner_y1)))
    return paths


def wifi(bars=3):
    cx, cy, s = 21.0, 21.0, 18.0
    band = s * 0.115
    edges = (1.0, 0.756, 0.513)
    paths = []
    for i, e in enumerate(edges):
        r = s * e
        paths.append(stroke(arc_band(cx, cy, r, band), band,
                            alpha=1.0 if bars >= 3 - i else 0.35))
    paths.append(fill(circle(cx - band, cy - band, band)))
    return paths


def signal(bars=5):
    x0, base, w, h = 2.0, 21.0, 20.0, 18.0
    pitch = w / 4.8
    bar = pitch * 0.8
    paths = []
    for i in range(5):
        bh = h * (0.2 + 0.2 * i)
        x = x0 + i * pitch
        paths.append(fill(rect(x, base - bh, x + bar, base),
                          alpha=1.0 if i < bars else 0.3))
    return paths


# ---------------------------------------------------------------- XML emitter


def vector_xml(paths, note, width="17dp", height="17dp"):
    body = []
    for p in paths:
        attrs = []
        if p["kind"] == "fill":
            attrs.append(f'        android:fillColor="{WHITE}"')
            if p["alpha"] < 1.0:
                attrs.append(f'        android:fillAlpha="{fmt(p["alpha"])}"')
        else:
            attrs.append('        android:fillColor="#00000000"')
            attrs.append(f'        android:strokeColor="{WHITE}"')
            attrs.append(f'        android:strokeWidth="{fmt(p["w"])}"')
            attrs.append('        android:strokeLineCap="butt"')
            attrs.append('        android:strokeLineJoin="miter"')
            if p["alpha"] < 1.0:
                attrs.append(f'        android:strokeAlpha="{fmt(p["alpha"])}"')
        attrs.append(f'        android:pathData="{p["d"]}"')
        body.append("    <path\n" + "\n".join(attrs) + " />")
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {note} -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{width}"\n'
        f'    android:height="{height}"\n'
        '    android:viewportWidth="24"\n'
        '    android:viewportHeight="24">\n'
        + "\n".join(body) + "\n</vector>\n"
    )


def bt_gauge(level):
    """Bluetooth rune (shrunk) + a 10-step device battery cell on the right."""
    paths = bluetooth_rune(scale=0.70, dx=-3.4)
    paths.append(stroke(rect(14.0, 8.0, 22.0, 16.0), 1.6))
    paths.append(fill(rect(22.0, 10.6, 23.2, 13.4)))
    inner_w = 5.8
    w = inner_w * (level + 0.5) / 10.0
    paths.append(fill(rect(15.1, 9.1, 15.1 + w, 14.9)))
    return paths


# ---------------------------------------------------------------- resource map

# (name, builder, note). Only names that exist in
# packages/SystemUI/res/drawable*/ and are read by SystemUI code (see
# evidence/name-verification.txt).
SHIPPED = [
    ("stat_sys_alarm", lambda: alarm(), "WP alarm clock (ztmetro: flat outline, white)"),
    ("stat_sys_alarm_dim", lambda: alarm(alpha=0.3),
     "WP alarm clock, dimmed 0.3 like stock ic_alarm_dim (snoozed/zen)"),
    ("stat_sys_data_bluetooth_connected", lambda: bluetooth_rune(),
     "WP bluetooth rune; stock ic_bluetooth_connected also had two side dots, dropped"),
    ("stat_sys_headset", lambda: headset(False), "WP headset: arc + two square earcups"),
    ("stat_sys_headset_mic", lambda: headset(True), "WP headset with mic boom"),
    ("stat_sys_airplane_mode", airplane, "flat airplane silhouette"),
    ("stat_sys_vpn_ic", lambda: shield(False), "WP shield (VPN active)"),
    ("stat_sys_no_internet_vpn_ic", lambda: shield(True), "WP shield + exclamation"),
    ("stat_sys_branded_vpn", lambda: shield(False), "branded VPN: same shield"),
    ("stat_sys_no_internet_branded_vpn", lambda: shield(True), "branded VPN + exclamation"),
]
for lvl in range(10):
    SHIPPED.append((
        f"stat_sys_data_bluetooth_connected_battery_{lvl}",
        (lambda l=lvl: bt_gauge(l)),
        f"WP bluetooth rune + device battery gauge, level {lvl}/9",
    ))

# Reference geometry only: these three are NOT SystemUI drawables on
# lineage-23.2 (battery and mobile signal are Canvas code, wifi comes from
# framework ic_wifi_signal_* / SettingsLib ic_wifi_*). Kept next to the RRO so
# a fork or a framework overlay has the exact ztmetro proportions.
REFERENCE = [
    ("zt_battery_75", lambda: battery(75), "ztmetro battery: outline, nub, 75% fill; reference for a fork"),
    ("zt_wifi_3", lambda: wifi(3), "ztmetro wifi fan, all 3 bands; reference for a framework overlay"),
    ("zt_signal_5", lambda: signal(5), "ztmetro 5-bar signal, all bars lit; reference for a fork"),
]


# ---------------------------------------------------------------- preview html


def svg(paths, size, label=None):
    body = []
    for p in paths:
        if p["kind"] == "fill":
            body.append(
                f'<path d="{html.escape(p["d"])}" fill="{WHITE}" '
                f'fill-opacity="{fmt(p["alpha"])}"/>')
        else:
            body.append(
                f'<path d="{html.escape(p["d"])}" fill="none" stroke="{WHITE}" '
                f'stroke-width="{fmt(p["w"])}" stroke-linecap="butt" '
                f'stroke-linejoin="miter" stroke-opacity="{fmt(p["alpha"])}"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 24 24" aria-label="{html.escape(label or "")}">'
            + "".join(body) + "</svg>")


def write_preview():
    shipped_html = []
    for name, build, note in SHIPPED:
        paths = build()
        shipped_html.append(
            '<figure class="g">'
            f'<div class="big">{svg(paths, 72, name)}</div>'
            f'<figcaption>{html.escape(name)}<span>{html.escape(note)}</span></figcaption>'
            "</figure>")
    ref_html = []
    for name, build, note in REFERENCE:
        paths = build()
        ref_html.append(
            '<figure class="g">'
            f'<div class="big">{svg(paths, 72, name)}</div>'
            f'<figcaption>{html.escape(name)}<span>{html.escape(note)}</span></figcaption>'
            "</figure>")

    # mock strip: 360x28 dp, glyphs at 15 dp, the order Android uses for the
    # end side: airplane, wifi-likes, bluetooth, alarm, headset, vpn, battery.
    strip = [
        ("airplane", svg(airplane(), 15)),
        ("wifi", svg(wifi(3), 15)),
        ("signal", svg(signal(5), 15)),
        ("bluetooth", svg(bluetooth_rune(), 15)),
        ("alarm", svg(alarm(), 15)),
        ("headset", svg(headset(True), 15)),
        ("vpn", svg(shield(False), 15)),
        ("bt-gauge", svg(bt_gauge(6), 15)),
    ]
    strip_html = "".join(f'<span class="s" title="{n}">{s}</span>' for n, s in strip)

    doc = f"""<!doctype html>
<meta charset="utf-8">
<style>
  html,body{{margin:0;padding:0;background:#000;color:#fff;
    font-family:'Open Sans',Helvetica,Arial,sans-serif}}
  .sheet{{width:1090px;padding:22px 24px 30px}}
  h1{{font-size:20px;font-weight:300;letter-spacing:.02em;margin:0 0 4px}}
  h2{{font-size:12px;font-weight:600;letter-spacing:.12em;text-transform:uppercase;
    color:#9a9a9a;margin:26px 0 10px}}
  .sub{{font-size:12px;color:#9a9a9a;margin:0 0 18px}}
  .strip{{display:flex;align-items:center;gap:7px;height:28px;background:#000;
    border:1px solid #1f1f1f;padding:0 14px;width:360px}}
  .strip .s{{display:flex;align-items:center}}
  .grid{{display:flex;flex-wrap:wrap;gap:14px}}
  .g{{margin:0;width:124px}}
  .big{{height:84px;display:flex;align-items:center;justify-content:center;
    background:#0b0b0b;border:1px solid #1c1c1c}}
  figcaption{{font-size:9px;line-height:1.25;color:#cfcfcf;margin-top:5px;
    word-break:break-all}}
  figcaption span{{display:block;color:#8a8a8a;margin-top:2px}}
  .note{{font-size:11px;color:#8a8a8a;margin-top:8px;max-width:900px;line-height:1.4}}
</style>
<div class="sheet">
  <h1>ztmetro status-bar glyphs &mdash; 24-unit viewport, 17 dp intrinsic</h1>
  <p class="sub">White only (#FFFFFFFF), square caps, one weight. Rendered from the same
     path data as res/drawable/*.xml. Scale: the strip below is 1:1 at 15 dp
     (720p/320 dpi &rarr; 30 px tall row).</p>
  <div class="strip">{strip_html}</div>
  <p class="note">Strip: airplane, wifi fan, five signal bars, bluetooth rune, alarm,
     headset+mic, VPN shield, bluetooth device gauge &mdash; the shapes SystemUI can
     actually receive from this RRO are the alarm / bluetooth / headset / airplane /
     VPN ones; the first three are reference geometry for the fork (see REPORT.md).</p>
  <h2>Shipped: 20 overridden SystemUI drawables</h2>
  <div class="grid">{''.join(shipped_html)}</div>
  <h2>Reference only (not in the APK): battery / wifi / signal</h2>
  <div class="grid">{''.join(ref_html)}</div>
</div>
"""
    (ROOT / "tools" / "preview.html").write_text(doc, encoding="utf-8")


# ---------------------------------------------------------------- main


def main():
    RES.mkdir(parents=True, exist_ok=True)
    FALLBACK.mkdir(parents=True, exist_ok=True)
    for name, build, note in SHIPPED:
        (RES / f"{name}.xml").write_text(vector_xml(build(), note), encoding="utf-8")
    for name, build, note in REFERENCE:
        (FALLBACK / f"{name}.xml").write_text(
            vector_xml(build(), note + " (reference only, not compiled into the RRO)"),
            encoding="utf-8")
    write_preview()
    print(f"wrote {len(SHIPPED)} drawables to {RES}")
    print(f"wrote {len(REFERENCE)} reference drawables to {FALLBACK}")
    print(f"wrote {ROOT / 'tools' / 'preview.html'}")


if __name__ == "__main__":
    main()
