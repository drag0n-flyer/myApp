#!/usr/bin/env python3
"""Render the placeholder app icons (iPhone, Mac, Android) with the stdlib only.

No SVG renderer is installed, so the geometry of design/icon/icon.svg is drawn
here with anti-aliased signed-distance shapes and written as PNG via zlib.
Output is deterministic: a re-run gives byte-identical files.

Usage: scripts/generate-icons.py [REPO_ROOT]
"""
import json
import math
import os
import struct
import sys
import zlib

# ---- design (1024 x 1024 units; keep in sync with design/icon/*.svg) --------
BG_TOP = (0x5A, 0xB0, 0xFF)
BG_BOTTOM = (0x0B, 0x57, 0xD0)
BUBBLE = (255, 255, 255)
BARS = (0x12, 0x60, 0xDD)
# Bubble body: centre x, centre y, half width, half height, corner radius.
BODY = (512.0, 462.0, 320.0, 230.0, 130.0)
# Tail triangle (overlaps the body so the union is seamless).
TAIL = ((478.0, 660.0), (300.0, 810.0), (328.0, 660.0))
# Waveform bars: centre x, centre y, half width, half height (capsules).
BAR_LIST = ((392.0, 462.0, 36.0, 60.0), (512.0, 462.0, 36.0, 125.0),
            (632.0, 462.0, 36.0, 85.0))
MARK_CENTRE_Y = 521.0  # vertical middle of bubble + tail

SVG_MARK = """<path fill="#FFFFFF" d="M322 232H702A130 130 0 0 1 832 362V562A130 130 0 0 1 702 692H478L300 810L328 692H322A130 130 0 0 1 192 562V362A130 130 0 0 1 322 232Z"/>
  <g fill="#1260DD">
    <rect x="356" y="402" width="72" height="120" rx="36"/>
    <rect x="476" y="337" width="72" height="250" rx="36"/>
    <rect x="596" y="377" width="72" height="170" rx="36"/>
  </g>"""


def rrect_d(u, v, cx, cy, hw, hh, r):
    qx = abs(u - cx) - hw + r
    qy = abs(v - cy) - hh + r
    return math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r


def _edges(poly):
    out = []
    n = len(poly)
    area = sum(poly[i][0] * poly[(i + 1) % n][1] - poly[(i + 1) % n][0] * poly[i][1]
               for i in range(n))
    sign = 1.0 if area > 0 else -1.0
    for i in range(n):
        (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % n]
        ln = math.hypot(x2 - x1, y2 - y1)
        # outward normal for the winding
        out.append((sign * (y2 - y1) / ln, -sign * (x2 - x1) / ln, x1, y1))
    return out


TAIL_EDGES = _edges(TAIL)


def tail_d(u, v):
    return max((nx * (u - x1) + ny * (v - y1)) for nx, ny, x1, y1 in TAIL_EDGES)


def cov(d_px):
    return min(1.0, max(0.0, 0.5 - d_px))


# style: (background shape, background half-size, corner radius, mark scale)
STYLES = {
    "full": ("rrect", 512.0, 0.0, 1.0),          # iOS, Play Store: square, OS masks it
    "mac": ("rrect", 412.0, 185.0, 0.8047),      # Apple Mac template: 824 px tile
    "legacy": ("rrect", 512.0, 225.0, 0.95),     # pre-Android-8 launcher icon
    "round": ("rrect", 512.0, 512.0, 0.88),      # pre-Android-8 round icon
}


def render(size, style, alpha):
    """Return PNG bytes (RGBA or RGB) of the icon at size x size."""
    _, half, rad, s = STYLES[style]
    k = size / 1024.0
    kd = s * k  # design-distance -> pixel scale for mark shapes
    rows = bytearray()
    tr, tg, tb = BG_TOP
    br, bg_, bb = BG_BOTTOM
    # mark bounds in design space (before transform), with margin
    for py in range(size):
        v = (py + 0.5) / k
        t = (py + 0.5) / size
        bgc = (tr + (br - tr) * t, tg + (bg_ - tg) * t, tb + (bb - tb) * t)
        mv = MARK_CENTRE_Y + (v - 512.0) / s
        rows.append(0)
        for px in range(size):
            u = (px + 0.5) / k
            if half == 512.0 and rad == 0.0:
                a = 1.0
            else:
                a = cov(rrect_d(u, v, 512.0, 512.0, half, half, rad) * k)
            if a <= 0.0:
                rows += b"\x00\x00\x00\x00" if alpha else b"\x00\x00\x00"
                continue
            r, g, b = bgc
            mu = 512.0 + (u - 512.0) / s
            if 150.0 < mu < 874.0 and 190.0 < mv < 850.0:
                cb = cov(min(rrect_d(mu, mv, *BODY), tail_d(mu, mv)) * kd)
                if cb > 0.0:
                    r += (BUBBLE[0] - r) * cb
                    g += (BUBBLE[1] - g) * cb
                    b += (BUBBLE[2] - b) * cb
                    if cb > 0.0 and 300.0 < mu < 724.0 and 320.0 < mv < 600.0:
                        cw = 0.0
                        for cx, cy, hw, hh in BAR_LIST:
                            cw = max(cw, cov(rrect_d(mu, mv, cx, cy, hw, hh, hw) * kd))
                        r += (BARS[0] - r) * cw
                        g += (BARS[1] - g) * cw
                        b += (BARS[2] - b) * cw
            rows += bytes((int(r + 0.5), int(g + 0.5), int(b + 0.5)))
            if alpha:
                rows.append(int(a * 255 + 0.5))
    return png_bytes(size, size, bytes(rows), 4 if alpha else 3)


def png_bytes(w, h, raw, channels):
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    ctype = 6 if channels == 4 else 2
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, ctype, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


# ---- file layout ------------------------------------------------------------
APPLE_SET = "apps/apple/App/Resources/Assets.xcassets/AppIcon.appiconset"
ANDROID_RES = "apps/android/app/src/main/res"
MAC_SIZES = (16, 32, 64, 128, 256, 512, 1024)
ANDROID_DPI = (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192))

# (point size, scale) -> pixel file, for the Mac entries
MAC_ENTRIES = [(p, sc) for p in (16, 32, 128, 256, 512) for sc in (1, 2)]


def contents_json():
    images = [{"filename": "ios-1024.png", "idiom": "universal",
               "platform": "ios", "size": "1024x1024"}]
    for p, sc in MAC_ENTRIES:
        images.append({"filename": "mac-%d.png" % (p * sc), "idiom": "mac",
                       "scale": "%dx" % sc, "size": "%dx%d" % (p, p)})
    doc = {"images": images, "info": {"author": "xcode", "version": 1}}
    return (json.dumps(doc, indent=2, sort_keys=True) + "\n").encode()


ANDROID_XML = {
    "mipmap-anydpi-v26/ic_launcher.xml": """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
""",
    "mipmap-anydpi-v26/ic_launcher_round.xml": """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
""",
    "drawable/ic_launcher_background.xml": """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0H108V108H0Z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:startX="54"
                android:startY="0"
                android:endX="54"
                android:endY="108"
                android:type="linear">
                <item android:color="#FF5AB0FF" android:offset="0.0" />
                <item android:color="#FF0B57D0" android:offset="1.0" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
""",
}

# Mark as a 108-unit vector: scale 0.078, centred (50 x 45 units, inside the 66-unit safe zone).
_VPATH = ("M322,232H702A130,130 0 0 1 832,362V562A130,130 0 0 1 702,692H478"
          "L300,810L328,692H322A130,130 0 0 1 192,562V362A130,130 0 0 1 322,232Z")
_BARS = ("M392,402A36,36 0 0 1 428,438V486A36,36 0 0 1 356,486V438A36,36 0 0 1 392,402Z"
         "M512,337A36,36 0 0 1 548,373V551A36,36 0 0 1 476,551V373A36,36 0 0 1 512,337Z"
         "M632,377A36,36 0 0 1 668,413V511A36,36 0 0 1 596,511V413A36,36 0 0 1 632,377Z")
_GROUP = ('    <group android:scaleX="0.078" android:scaleY="0.078"'
          ' android:translateX="14.06" android:translateY="13.36">\n')
ANDROID_XML["drawable/ic_launcher_foreground.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
""" + _GROUP + """        <path android:fillColor="#FFFFFFFF" android:pathData="%s" />
        <path android:fillColor="#FF1260DD" android:pathData="%s" />
    </group>
</vector>
""" % (_VPATH, _BARS)
ANDROID_XML["drawable/ic_launcher_monochrome.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
""" + _GROUP + """        <path
            android:fillColor="#FF000000"
            android:fillType="evenOdd"
            android:pathData="%s%s" />
    </group>
</vector>
""" % (_VPATH, _BARS)


def build_all():
    """Return {relative path: bytes} for every generated file."""
    files = {}
    files[APPLE_SET + "/Contents.json"] = contents_json()
    files[APPLE_SET + "/ios-1024.png"] = render(1024, "full", alpha=False)
    for n in MAC_SIZES:
        files[APPLE_SET + "/mac-%d.png" % n] = render(n, "mac", alpha=True)
    files["design/icon/play-store-512.png"] = render(512, "full", alpha=True)
    files["design/icon/preview-1024.png"] = files[APPLE_SET + "/ios-1024.png"]
    for dpi, n in ANDROID_DPI:
        files["%s/mipmap-%s/ic_launcher.png" % (ANDROID_RES, dpi)] = render(n, "legacy", True)
        files["%s/mipmap-%s/ic_launcher_round.png" % (ANDROID_RES, dpi)] = render(n, "round", True)
    for rel, text in ANDROID_XML.items():
        files["%s/%s" % (ANDROID_RES, rel)] = text.encode()
    return files


def write_all(root):
    for rel, data in build_all().items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)


if __name__ == "__main__":
    write_all(sys.argv[1] if len(sys.argv) > 1
              else os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
