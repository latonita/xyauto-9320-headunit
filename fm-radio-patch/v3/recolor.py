"""v3 recolour: returns {zip entry name: new bytes} computed from the ORIGINAL apk + colormap.json."""
import io, json, os, re, struct, sys, zipfile, colorsys, collections
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import arsc

H = os.path.dirname(os.path.abspath(__file__))
CFG = json.load(open(os.path.join(H, "colormap.json")))

def hexrgb(s): s = s.lstrip("#"); return tuple(int(s[i:i+2], 16) for i in (0, 2, 4))

RAMP = [(p, hexrgb(c)) for p, c in CFG["ramp"]]
def ramp(l):
    for (p0, c0), (p1, c1) in zip(RAMP, RAMP[1:]):
        if l <= p1:
            t = (l - p0) / (p1 - p0) if p1 > p0 else 0
            return tuple(c0[i] + (c1[i] - c0[i]) * t for i in range(3))
    return RAMP[-1][1]

def in_hue(h_deg):
    return any(a <= h_deg <= b for a, b in CFG["hue_ranges_deg"])

def weight(r, g, b):
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    if not in_hue(h * 360): return 0.0, l
    lo, hi = CFG["sat_min"], CFG["sat_full"]
    return max(0.0, min(1.0, (s - lo) / (hi - lo))), l

def recolor_rgb(r, g, b):
    w, l = weight(r, g, b)
    if w == 0: return r, g, b
    t = ramp(l)
    return tuple(int(round(c * (1 - w) + t[i] * w)) for i, c in enumerate((r, g, b)))

def recolor_png(data):
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    px = im.load(); n = 0
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a == 0: continue
            nr = recolor_rgb(r, g, b)
            if nr != (r, g, b): px[x, y] = nr + (a,); n += 1
    return im, n

def encode_png(im, orig):
    out = io.BytesIO(); im.save(out, "PNG", optimize=True); new = out.getvalue()
    # keep compiled 9-patch chunks (npTc/npLb/npOl) if the original had any
    keep = b""; p = 8
    while p < len(orig):
        ln = struct.unpack(">I", orig[p:p+4])[0]; ty = orig[p+4:p+8]
        if ty in (b"npTc", b"npLb", b"npOl"): keep += orig[p:p+12+ln]
        p += 12 + ln
    if keep:
        i = new.index(b"IDAT") - 4
        new = new[:i] + keep + new[i:]
    return new

def ss(im, f=4):  # supersample helpers
    return im.resize((im.width * f, im.height * f), Image.LANCZOS)

def flat_arrow(data, style):
    im = Image.open(io.BytesIO(data)).convert("RGBA"); W, Hh = im.size
    px = im.load(); a = im.getchannel("A")
    bb = a.point(lambda v: 255 if v > 40 else 0).getbbox()
    cx, cy = (bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2; R = (bb[2] - bb[0]) / 2
    fill, ring = hexrgb(style["fill"]), hexrgb(style["ring"]); rw = style["ring_px_per_72"] * (2 * R) / 72
    out = Image.new("RGBA", im.size); po = out.load()
    for y in range(Hh):
        for x in range(W):
            r, g, b, al = px[x, y]
            if al == 0: continue
            d = ((x + .5 - cx) ** 2 + (y + .5 - cy) ** 2) ** .5
            base = ring if d > R - rw - 0.5 else fill
            t = max(0.0, min(1.0, (min(r, g, b) - 45) / 190.0))   # white chevron glyph
            c = tuple(int(base[i] * (1 - t) + 255 * t) for i in range(3))
            po[x, y] = c + (al,)
    return out

def flat_box(data, st):
    im = Image.open(io.BytesIO(data)).convert("RGBA"); W, Hh = im.size; f = 4
    big = Image.new("RGBA", (W * f, Hh * f), (0, 0, 0, 0)); d = ImageDraw.Draw(big)
    rad, rw = st["radius_px"] * f, st["ring_px"] * f
    fa, ra = int(255 * st["fill_alpha"]), int(255 * st["ring_alpha"])
    d.rounded_rectangle([0, 0, W * f - 1, Hh * f - 1], rad, fill=hexrgb(st["ring"]) + (ra,))
    inner = Image.new("RGBA", big.size, (0, 0, 0, 0)); di = ImageDraw.Draw(inner)
    di.rounded_rectangle([rw, rw, W * f - 1 - rw, Hh * f - 1 - rw], max(rad - rw, 1), fill=hexrgb(st["fill"]) + (fa,))
    # punch the interior out of the ring layer, then lay the translucent fill in
    mask = inner.getchannel("A").point(lambda v: 255 if v else 0)
    big.paste((0, 0, 0, 0), mask=mask); big.alpha_composite(inner)
    return big.resize((W, Hh), Image.LANCZOS)

def patch_xml_literals(data):
    pos = struct.unpack_from("<H", data, 2)[0]; ba = bytearray(data); n = 0
    pos += struct.unpack_from("<I", data, pos + 4)[0]  # skip string pool
    while pos < len(ba):
        t, hs, sz = struct.unpack_from("<HHI", ba, pos)
        if t == 0x102:
            ac = struct.unpack_from("<H", ba, pos + 28)[0]
            ao = pos + 16 + struct.unpack_from("<H", ba, pos + 24)[0]
            for i in range(ac):
                a = ao + i * 20
                if ba[a + 15] in (0x1c, 0x1d):
                    v = struct.unpack_from("<I", ba, a + 16)[0]
                    r, g, b = (v >> 16) & 255, (v >> 8) & 255, v & 255
                    nr = recolor_rgb(r, g, b)
                    if nr != (r, g, b):
                        struct.pack_into("<I", ba, a + 16, (v & 0xFF000000) | (nr[0] << 16) | (nr[1] << 8) | nr[2]); n += 1
        pos += sz
    return bytes(ba), n

def compute(orig_apk, log=print):
    z = zipfile.ZipFile(orig_apk)
    ar = bytearray(z.read("resources.arsc")); gs, res = arsc.entries(ar)
    path = {}
    for d in res:
        if d["type"] == "drawable" and d["vtype"] == 3 and gs[d["vdata"]].endswith(".png"): path.setdefault(d["key"], gs[d["vdata"]])
    flatmap = {}
    for k, st in CFG["flat"].items():
        if k.startswith("_"): continue
        for dn in st["drawables"]: flatmap[path[dn]] = (k, st)
    skip = {path[d] for d in CFG.get("skip_drawables", []) if d in path}
    changes = {}
    for n in z.namelist():
        if n in skip: continue
        if n.endswith(".png"):
            data = z.read(n)
            if n in flatmap:
                k, st = flatmap[n]
                im = flat_box(data, st) if k == "selected_preset" else flat_arrow(data, st)
                changes[n] = encode_png(im, data); log("flat  %-14s %s" % (k, n))
            else:
                im, cnt = recolor_png(data)
                if cnt: changes[n] = encode_png(im, data); log("recol %5d px %s" % (cnt, n))
        elif n.startswith("res/") and n.endswith(".xml"):
            try: nd, cnt = patch_xml_literals(z.read(n))
            except Exception: continue
            if cnt: changes[n] = nd; log("xml   %d literal(s) %s" % (cnt, n))
    return changes, {v: k for k, v in path.items()}

if __name__ == "__main__":
    compute(sys.argv[1])
