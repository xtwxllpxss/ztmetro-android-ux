#!/usr/bin/env python3
"""Minimal sfnt/cmap inspector — no fontTools, no network.

Reads the table directory and the cmap table of a TrueType/OpenType font and
reports: file magic, tables, variable-font axes (fvar), glyph count, OS/2
weight/embedding flags, name-table strings, cmap version, every enc/format
subtable, and the Unicode codepoint coverage (union of Unicode subtables),
including an explicit check of the Cyrillic codepoints the ztmetro module needs.

Only the Python 3 standard library is used, so this runs on a bare host
(the bundled python3 and Ubuntu's python3 both work).

Usage: font_coverage.py FONT.ttf [...]   -> JSON array on stdout
       font_coverage.py --check FONT.ttf -> exit 1 if a required codepoint is missing
"""

import json
import struct
import sys

CYRILLIC_REQUIRED = {
    0x0410: "CYRILLIC CAPITAL LETTER A",
    0x0430: "CYRILLIC SMALL LETTER A",
    0x0451: "CYRILLIC SMALL LETTER IO",
    0x0416: "CYRILLIC CAPITAL LETTER ZHE",
    0x044F: "CYRILLIC SMALL LETTER YA",
}
# Extra Cyrillic a Russian UI actually uses: Ё, й, ъ, ы, э, ю, №, «», —, …
CYRILLIC_EXTRA = [0x0401, 0x0439, 0x044A, 0x044B, 0x044D, 0x044E, 0x2116,
                  0x00AB, 0x00BB, 0x2014, 0x2026]


class FontError(Exception):
    pass


def u16(b, o):
    return struct.unpack_from(">H", b, o)[0]


def s16(b, o):
    return struct.unpack_from(">h", b, o)[0]


def u32(b, o):
    return struct.unpack_from(">I", b, o)[0]


def read_tables(data):
    if len(data) < 12:
        raise FontError("file shorter than an sfnt header")
    tag = data[0:4]
    if tag == b"true":
        tag = b"\x00\x01\x00\x00"
    if tag not in (b"\x00\x01\x00\x00", b"OTTO", b"ttcf"):
        raise FontError("not an sfnt (magic %r)" % data[0:4])
    if tag == b"ttcf":
        raise FontError("font collection (ttcf) — expected a single font")
    num = u16(data, 4)
    tables = {}
    for i in range(num):
        o = 12 + 16 * i
        if o + 16 > len(data):
            raise FontError("truncated table directory")
        t = data[o:o + 4]
        off, ln = u32(data, o + 8), u32(data, o + 12)
        if off + ln > len(data):
            raise FontError("table %s out of bounds" % t)
        tables[t] = (off, ln)
    return tables


def parse_name(data, off):
    fmt, count, str_off = u16(data, off), u16(data, off + 2), u16(data, off + 4)
    out = {}
    for i in range(count):
        o = off + 6 + 12 * i
        pid, eid, lid, nid, ln, so = struct.unpack_from(">6H", data, o)
        raw = data[off + str_off + so: off + str_off + so + ln]
        try:
            if pid in (0, 3):
                val = raw.decode("utf-16-be", "replace")
            else:
                val = raw.decode("mac_roman", "replace")
        except Exception:
            val = ""
        # prefer Windows English (3,1,0x409), then any Windows, then Unicode, then Mac
        pri = 0 if (pid == 3 and lid == 0x409) else 1 if pid == 3 else 2 if pid == 0 else 3
        if nid not in out or pri < out[nid][0]:
            out[nid] = (pri, val)
    names = {str(k): v[1] for k, v in out.items()}
    return names


def parse_cmap(data, off, length):
    version, n = u16(data, off), u16(data, off + 2)
    subs = []
    for i in range(n):
        pid, eid, sub_off = struct.unpack_from(">HHI", data, off + 4 + 8 * i)
        if sub_off + 2 > length:
            continue
        fmt = u16(data, off + sub_off)
        subs.append({"platform": pid, "encoding": eid, "format": fmt,
                     "offset": sub_off, "points": set()})
    for s in subs:
        b = off + s["offset"]
        f = s["format"]
        pts = s["points"]
        if f == 0:
            for c in range(256):
                if data[b + 6 + c]:
                    pts.add(c)
        elif f == 4:
            seg = u16(data, b + 6) // 2
            end = b + 14
            start = end + 2 * seg + 2
            delta = start + 2 * seg
            range_off = delta + 2 * seg
            for i in range(seg):
                e, st = u16(data, end + 2 * i), u16(data, start + 2 * i)
                d, ro = s16(data, delta + 2 * i), u16(data, range_off + 2 * i)
                if e < st:
                    continue
                for c in range(st, min(e, 0xFFFE) + 1):
                    if ro == 0:
                        gid = (c + d) & 0xFFFF
                    else:
                        pos = range_off + 2 * i + ro + 2 * (c - st)
                        if pos + 2 > b + length - off:
                            continue
                        gid = u16(data, pos)
                        if gid:
                            gid = (gid + d) & 0xFFFF
                    if gid:
                        pts.add(c)
        elif f == 6:
            first, cnt = u16(data, b + 6), u16(data, b + 8)
            for i in range(cnt):
                if u16(data, b + 10 + 2 * i):
                    pts.add(first + i)
        elif f == 12:
            ngroups = u32(data, b + 12)
            for i in range(ngroups):
                g = b + 16 + 12 * i
                start, end, gid = u32(data, g), u32(data, g + 4), u32(data, g + 8)
                if gid == 0:
                    continue
                for c in range(start, min(end, 0x10FFFF) + 1):
                    pts.add(c)
        else:
            s["unsupported"] = True
    return version, subs


def inspect(path):
    data = open(path, "rb").read()
    tables = read_tables(data)
    info = {
        "file": path,
        "bytes": len(data),
        "sfnt_version": data[0:4].hex(),
        "is_truetype_outlines": b"glyf" in tables and b"CFF " not in tables,
        "is_cff": b"CFF " in tables,
        "is_variable": b"fvar" in tables,
        "tables": sorted(t.decode("latin-1") for t in tables),
    }
    if b"maxp" in tables:
        info["num_glyphs"] = u16(data, tables[b"maxp"][0] + 4)
    if b"head" in tables:
        h = tables[b"head"][0]
        info["units_per_em"] = u16(data, h + 18)
        info["bbox"] = [s16(data, h + 36), s16(data, h + 38),
                        s16(data, h + 40), s16(data, h + 42)]
    if b"OS/2" in tables:
        o = tables[b"OS/2"][0]
        info["usWeightClass"] = u16(data, o + 4)
        info["usWidthClass"] = u16(data, o + 6)
        info["fsType"] = u16(data, o + 8)
        info["fsSelection"] = u16(data, o + 62)
    if b"name" in tables:
        info["names"] = parse_name(data, tables[b"name"][0])

    if b"cmap" not in tables:
        raise FontError("no cmap table")
    off, ln = tables[b"cmap"]
    version, subs = parse_cmap(data, off, ln)
    unicode_pts = set()
    for s in subs:
        # Unicode subtables: platform 0 (any encoding) or platform 3 enc 1/10.
        if s["platform"] == 0 or (s["platform"] == 3 and s["encoding"] in (1, 10)):
            unicode_pts |= s["points"]
    all_pts = set().union(*(s["points"] for s in subs)) if subs else set()

    info["cmap"] = {
        "version": version,
        "num_subtables": len(subs),
        "subtables": [{"platform": s["platform"], "encoding": s["encoding"],
                       "format": s["format"], "codepoints": len(s["points"]),
                       "unsupported": bool(s.get("unsupported"))} for s in subs],
        "unicode_codepoints": len(unicode_pts),
        "all_subtable_codepoints": len(all_pts),
    }
    # Cyrillic block coverage
    cyr_block = [c for c in unicode_pts if 0x0400 <= c <= 0x04FF]
    info["coverage"] = {
        "cyrillic_block_total": 256,
        "cyrillic_block_present": len(cyr_block),
        "greek_block_present": sum(1 for c in unicode_pts if 0x0370 <= c <= 0x03FF),
        "latin_basic_present": sum(1 for c in unicode_pts if 0x0020 <= c <= 0x007E),
        "required": {("U+%04X" % c): {"name": n, "present": c in unicode_pts}
                     for c, n in sorted(CYRILLIC_REQUIRED.items())},
        "extra_ru": {("U+%04X" % c): (c in unicode_pts) for c in CYRILLIC_EXTRA},
        "all_required_present": all(c in unicode_pts for c in CYRILLIC_REQUIRED),
    }
    return info


def main(argv):
    check = "--check" in argv
    paths = [a for a in argv[1:] if a != "--check"]
    if not paths:
        print(__doc__, file=sys.stderr)
        return 2
    out = []
    bad = False
    for p in paths:
        try:
            info = inspect(p)
        except FontError as e:
            out.append({"file": p, "error": str(e)})
            bad = True
            continue
        out.append(info)
        if check and not info["coverage"]["all_required_present"]:
            bad = True
    print(json.dumps(out, indent=1, ensure_ascii=False))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
