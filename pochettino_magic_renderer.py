#!/usr/bin/env python3
"""Animated music video renderer: cartoon Pochettino character, beat-synced scenes.
Usage: pochvid.py preview t1 t2 ...      -> writes preview PNGs
       pochvid.py render start end out.mp4 -> renders frames [start,end) to an mp4 segment
"""
import sys, os, json, math, random
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1280, 720
SS = 2            # supersample factor
FPS = 30
DUR = 169.58

# ---------------------------------------------------------------- audio features
F = json.load(open(os.path.join(HERE, "audio/features.json")))
FT = np.array(F["times"]); ONSET = np.array(F["onset"]); RMS = np.array(F["rms"])
LOW = np.array(F["low"]); HIGH = np.array(F["high"]); VOC = np.array(F["vocal"])
BEATS = np.array(F["beats"])
BEAT_LEN = float(np.median(np.diff(BEATS)))

def feat(arr, t):
    return float(np.interp(t, FT, arr))

# downbeat phase: pick offset in 0..3 maximizing low energy on those beats
_lowb = [np.mean([feat(LOW, b) for b in BEATS[k::4]]) for k in range(4)]
DB_OFF = int(np.argmax(_lowb))

def beat_info(t):
    """returns (beat index, phase 0..1 since last beat, is_downbeat)"""
    i = int(np.searchsorted(BEATS, t, side="right") - 1)
    if i < 0:
        return -1, 1.0, False
    nxt = BEATS[i + 1] if i + 1 < len(BEATS) else BEATS[i] + BEAT_LEN
    ph = (t - BEATS[i]) / max(nxt - BEATS[i], 1e-6)
    return i, min(ph, 1.0), ((i - DB_OFF) % 4 == 0)

def pulse(t, decay=5.0):
    i, ph, db = beat_info(t)
    p = math.exp(-decay * ph * BEAT_LEN)
    return p * (1.0 if db else 0.65)

def bar_pos(t):
    i, ph, db = beat_info(t)
    return ((i - DB_OFF) % 4 + ph) / 4.0

def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))

def ease(x):
    x = clamp(x); return x * x * (3 - 2 * x)

# ---------------------------------------------------------------- palette
RED = (218, 41, 28); DRED = (150, 20, 14); GOLD = (255, 200, 60)
INK = (38, 24, 20); WHITE = (255, 255, 255)
SKIN = (232, 186, 150); SKIN_D = (205, 150, 115)
HAIR = (62, 38, 26); HAIR_H = (96, 62, 40)
BEARD = (150, 148, 145); BEARD_D = (92, 88, 86); BEARD_L = (195, 193, 190)
GRASS = (46, 128, 60); GRASS2 = (56, 146, 70)

def lerp_c(a, b, t):
    t = clamp(t); return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

# ---------------------------------------------------------------- fonts
def font(size, kind="display"):
    size = int(size * SS)
    paths = {
        "display": "/usr/share/fonts/opentype/inter/InterDisplay-Bold.otf",
        "black": "/usr/share/fonts/opentype/inter/Inter-ExtraBold.otf",
        "serif": "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",
    }
    return ImageFont.truetype(paths[kind], size)

# ---------------------------------------------------------------- geometry helpers
def S(v):
    return v * SS

def rot(px, py, cx, cy, ang):
    a = math.radians(ang); c, s = math.cos(a), math.sin(a)
    dx, dy = px - cx, py - cy
    return cx + dx * c - dy * s, cy + dx * s + dy * c

def smooth(points, n=6, closed=True):
    """Catmull-Rom smoothing of a polyline/polygon"""
    pts = list(points)
    if closed:
        pts = [pts[-1]] + pts + [pts[0], pts[1]]
    else:
        pts = [pts[0]] + pts + [pts[-1]]
    out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for k in range(n):
            t = k / n; t2 = t * t; t3 = t2 * t
            x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, y))
    if not closed:
        out.append(pts[-2])
    return out

class Canvas:
    """Draw in output coords; everything is scaled by SS."""
    def __init__(self, img):
        self.img = img
        self.d = ImageDraw.Draw(img, "RGBA")

    def poly(self, pts, fill=None, outline=None, width=3):
        p = [(S(x), S(y)) for x, y in pts]
        if fill is not None:
            self.d.polygon(p, fill=fill)
        if outline is not None and width > 0:
            self.d.line(p + [p[0]], fill=outline, width=int(S(width)), joint="curve")

    def ell(self, cx, cy, rx, ry, fill=None, outline=None, width=3):
        b = [S(cx - rx), S(cy - ry), S(cx + rx), S(cy + ry)]
        self.d.ellipse(b, fill=fill, outline=outline, width=int(S(width)) if outline else 0)

    def line(self, pts, fill, width=3):
        self.d.line([(S(x), S(y)) for x, y in pts], fill=fill, width=int(S(width)), joint="curve")

    def rect(self, x0, y0, x1, y1, fill=None, outline=None, width=2, r=0):
        b = [S(x0), S(y0), S(x1), S(y1)]
        if r:
            self.d.rounded_rectangle(b, radius=S(r), fill=fill, outline=outline, width=int(S(width)) if outline else 0)
        else:
            self.d.rectangle(b, fill=fill, outline=outline, width=int(S(width)) if outline else 0)

    def text(self, x, y, s, f, fill, anchor="mm", stroke=0, stroke_fill=INK):
        self.d.text((S(x), S(y)), s, font=f, fill=fill, anchor=anchor, stroke_width=int(S(stroke)), stroke_fill=stroke_fill)

    def gradient_v(self, y0, y1, c0, c1, x0=0, x1=W):
        n = int(S(y1 - y0))
        for k in range(0, n, 2):
            t = k / max(n - 1, 1)
            self.d.rectangle([S(x0), S(y0) + k, S(x1), S(y0) + k + 2], fill=lerp_c(c0, c1, t))


# ---------------------------------------------------------------- beard geometry (precomputed)
def _pip(x, y, poly):
    inside = False; n = len(poly)
    for i in range(n):
        x0, y0 = poly[i]; x1, y1 = poly[(i + 1) % n]
        if (y0 > y) != (y1 > y):
            xi = x0 + (y - y0) * (x1 - x0) / (y1 - y0)
            if x < xi: inside = not inside
    return inside

# outer edge hugs the face polygon (inset ~3 units); top edge runs down the cheeks to the lip
BEARD_POLY = smooth([(-58, -8), (-56, 14), (-50, 48), (-32, 78), (0, 88), (32, 78), (50, 48), (56, 14), (58, -8),
                     (50, 8), (44, 24), (34, 34), (24, 38), (12, 32), (0, 34), (-12, 32), (-24, 38), (-34, 34), (-44, 24), (-50, 8)], 5)
BEARD_JAW = smooth([(-56, 30), (-50, 48), (-32, 78), (0, 88), (32, 78), (50, 48), (56, 30), (46, 44), (30, 66), (0, 74), (-30, 66), (-46, 44)], 5)
MO_POLY = smooth([(-30, 30), (-16, 26), (0, 31), (16, 26), (30, 30), (26, 42), (10, 41), (0, 44), (-10, 41), (-26, 42)], 5)
STUBBLE_COLS = [(226, 218, 212), (176, 166, 160), (96, 82, 74), (130, 116, 108)]
STUBBLE = []
_r = random.Random(2024)
while len(STUBBLE) < 1100:
    x = _r.uniform(-60, 60); y = _r.uniform(-24, 90)
    if not _pip(x, y, BEARD_POLY): continue
    # keep the mouth clear
    if (x / 26.0) ** 2 + ((y - 52) / 13.0) ** 2 < 1.0: continue
    in_mo = _pip(x, y, MO_POLY)
    r = _r.random()
    if in_mo:
        ci = 2 if r < 0.55 else (3 if r < 0.85 else 1)
    else:
        # whiter on cheeks/chin sides, darker near the jaw centre
        dark_bias = 0.25 + 0.25 * (abs(x) < 20 and y > 60)
        ci = 2 if r < dark_bias else (1 if r < dark_bias + 0.3 else 0)
    ang = math.radians(90 + (x / 60.0) * 35 + _r.uniform(-18, 18))
    ln = _r.uniform(2.0, 3.6)
    STUBBLE.append((x, y, ln * math.cos(ang), ln * math.sin(ang), ci))

# ---------------------------------------------------------------- the character
def draw_poch(cv, cx, cy, sc, pose, t):
    """Draw the character. (cx,cy)=centre of head; sc = pixels per unit (head ~ 130 units wide).
    pose keys: bob, tilt, mouth, blink, brow, look, smile, arm_l, arm_r, fore_l, fore_r,
    hand_l, hand_r, outfit ('track'|'magic'|'press'|'suit'), hat, cape, prop_l, prop_r, eyes_closed, body"""
    P = lambda x, y: (cx + x * sc, cy + y * sc)
    tilt = pose.get("tilt", 0.0)
    R = lambda x, y: rot(cx + x * sc, cy + y * sc, cx, cy + 90 * sc, tilt)  # rotate around neck
    outfit = pose.get("outfit", "track")
    lw = max(2.0, 2.6 * sc / 2.2)

    # ---- body (drawn first) ----
    if pose.get("body", True):
        col = {"track": RED, "magic": (28, 24, 32), "press": (36, 52, 128), "suit": (30, 30, 40)}[outfit]
        trim = {"track": WHITE, "magic": GOLD, "press": RED, "suit": WHITE}[outfit]
        sh = 92   # shoulder y
        torso = [(-62, sh - 6), (-112, sh + 18), (-130, sh + 120), (-125, 330), (125, 330), (130, sh + 120), (112, sh + 18), (62, sh - 6)]
        if pose.get("legs"):
            step = pose.get("step", 0.0)
            trouser = {"track": (30, 30, 36), "magic": (18, 16, 22), "press": (30, 34, 60), "suit": (30, 30, 40)}[outfit]
            for side in (-1, 1):
                sw = step * side * 22
                hx0, hy0 = side * 48, 300
                kx, ky = side * 50 + sw, 420
                fx, fy = side * 52 + sw * 1.6, 530
                cv.line([R(hx0, hy0), R(kx, ky), R(fx, fy)], fill=INK, width=lw * 2 + 48 * sc / 2.2)
                cv.line([R(hx0, hy0), R(kx, ky), R(fx, fy)], fill=trouser, width=48 * sc / 2.2)
                if outfit == "track":
                    cv.line([R(hx0 + side * 18, hy0 + 10), R(kx + side * 18, ky), R(fx + side * 16, fy - 10)], fill=WHITE, width=5 * sc)
                bx, by = R(fx + side * 10, fy + 14)
                cv.ell(bx, by, 36 * sc, 16 * sc, fill=(20, 20, 24), outline=INK, width=lw)
        # arms behind torso: upper arm from shoulder
        for side in (-1, 1):
            ua = pose.get("arm_l" if side < 0 else "arm_r", 15)      # angle from down, outward positive
            fa = pose.get("fore_l" if side < 0 else "fore_r", 10)    # elbow bend
            sx, sy = -100 * side, sh + 30
            ex, ey = sx - side * 92 * math.sin(math.radians(ua)), sy + 92 * math.cos(math.radians(ua))
            ang2 = ua + fa
            hx, hy = ex - side * 84 * math.sin(math.radians(ang2)), ey + 84 * math.cos(math.radians(ang2))
            cv.line([R(sx, sy), R(ex, ey)], fill=INK, width=lw * 2 + 36 * sc / 2.2)
            cv.line([R(ex, ey), R(hx, hy)], fill=INK, width=lw * 2 + 32 * sc / 2.2)
            cv.ell(*R(ex, ey), (18 * sc / 2.2 + lw), (18 * sc / 2.2 + lw), fill=INK)
            cv.line([R(sx, sy), R(ex, ey)], fill=col, width=36 * sc / 2.2)
            cv.line([R(ex, ey), R(hx, hy)], fill=col, width=32 * sc / 2.2)
            cv.ell(*R(ex, ey), 17 * sc / 2.2, 17 * sc / 2.2, fill=col)
            # cuff
            cuffx, cuffy = ex - side * 70 * math.sin(math.radians(ang2)), ey + 70 * math.cos(math.radians(ang2))
            cv.line([R(cuffx, cuffy), R(hx, hy)], fill=trim if outfit != "magic" else WHITE, width=32 * sc / 2.2)
            # hand
            hand = pose.get("hand_l" if side < 0 else "hand_r", "fist")
            hxr, hyr = R(hx, hy)
            prop = pose.get("prop_l" if side < 0 else "prop_r")
            if hand == "point":
                px2, py2 = R(hx - side * 10 * math.sin(math.radians(ang2)) + side * 4, hy + 34 * math.cos(math.radians(ang2)))
                cv.line([(hxr, hyr), (px2, py2)], fill=INK, width=lw * 2 + 14 * sc / 2.2)
                cv.line([(hxr, hyr), (px2, py2)], fill=SKIN, width=14 * sc / 2.2)
            cv.ell(hxr, hyr, 20 * sc, 19 * sc, fill=SKIN, outline=INK, width=lw)
            if prop == "mic":
                mx, my = R(hx, hy - 60)
                cv.line([(hxr, hyr), (mx, my)], fill=(40, 40, 44), width=14 * sc)
                cv.ell(mx, my, 20 * sc, 20 * sc, fill=(70, 70, 78), outline=INK, width=lw)
                for k in range(-2, 3):
                    cv.line([(mx - 14 * sc, my + k * 7 * sc), (mx + 14 * sc, my + k * 7 * sc)], fill=(40, 40, 44), width=2 * sc)
            if prop == "ball":
                bx, by = R(hx, hy - 36)
                draw_ball(cv, S(bx), S(by), S(30 * sc), t * 200)
            if prop == "wand":
                wx0, wy0 = R(hx + side * 20, hy - 10); wx1, wy1 = R(hx - side * 50, hy - 90)
                cv.line([(wx0, wy0), (wx1, wy1)], fill=INK, width=8 * sc)
                cv.line([R(hx - side * 36, hy - 74), (wx1, wy1)], fill=WHITE, width=8 * sc)
            if prop == "marker":
                wx0, wy0 = R(hx, hy); wx1, wy1 = R(hx - side * 30, hy - 50)
                cv.line([(wx0, wy0), (wx1, wy1)], fill=INK, width=12 * sc)
                cv.line([(wx0, wy0), R(hx - side * 10, hy - 16)], fill=RED, width=12 * sc)
            if prop == "trophy":
                tx, ty = R(hx, hy - 10)
                draw_trophy(cv, S(tx), S(ty), S(sc * 0.6))
        if pose.get("cape"):
            cape = [(-80, sh), (-150, sh + 60), (-175, 330), (175, 330), (150, sh + 60), (80, sh)]
            cv.poly([R(*p) for p in smooth(cape, 4)], fill=(120, 14, 20), outline=INK, width=lw)
        cv.poly([R(*p) for p in smooth(torso, 4)], fill=col, outline=INK, width=lw)
        if outfit == "track":
            # shoulder piping + zip
            cv.line([R(-62, sh - 4), R(-112, sh + 22), R(-128, sh + 110)], fill=WHITE, width=7 * sc)
            cv.line([R(62, sh - 4), R(112, sh + 22), R(128, sh + 110)], fill=WHITE, width=7 * sc)
            cv.line([R(0, sh + 30), R(0, 330)], fill=INK, width=3 * sc)
            cv.line([R(0, sh + 30), R(0, 330)], fill=(240, 240, 240), width=1.2 * sc)
            # collar
            cv.poly([R(-60, sh - 10), R(-48, sh + 30), R(0, sh + 48), R(48, sh + 30), R(60, sh - 10), R(30, sh + 6), R(0, sh + 14), R(-30, sh + 6)], fill=DRED, outline=INK, width=lw)
            # badge
            bx, by = -58, sh + 70
            cv.poly([R(bx - 16, by - 18), R(bx + 16, by - 18), R(bx + 16, by + 4), R(bx, by + 20), R(bx - 16, by + 4)], fill=GOLD, outline=INK, width=lw * 0.7)
            sx_, sy_ = R(bx, by - 2); draw_star(cv, S(sx_), S(sy_), S(9 * sc), RED)
        elif outfit == "magic":
            # tailcoat lapels + white shirt + red bow tie
            cv.poly([R(-50, sh - 6), R(-40, sh + 110), R(0, sh + 150), R(40, sh + 110), R(50, sh - 6), R(0, sh + 30)], fill=WHITE, outline=INK, width=lw)
            cv.poly([R(-52, sh - 8), R(-75, sh + 30), R(-30, sh + 140), R(-12, sh + 70), R(-30, sh + 20)], fill=(18, 16, 22), outline=INK, width=lw)
            cv.poly([R(52, sh - 8), R(75, sh + 30), R(30, sh + 140), R(12, sh + 70), R(30, sh + 20)], fill=(18, 16, 22), outline=INK, width=lw)
            cv.poly([R(-26, sh + 14), R(-4, sh + 26), R(-26, sh + 40)], fill=RED, outline=INK, width=lw * 0.8)
            cv.poly([R(26, sh + 14), R(4, sh + 26), R(26, sh + 40)], fill=RED, outline=INK, width=lw * 0.8)
            cv.ell(*R(0, sh + 27), 6 * sc, 6 * sc, fill=DRED, outline=INK, width=lw * 0.6)
            for k in range(3):
                cv.ell(*R(0, sh + 70 + k * 28), 4 * sc, 4 * sc, fill=GOLD, outline=INK, width=lw * 0.5)
        elif outfit == "press":
            cv.line([R(0, sh + 26), R(0, 330)], fill=INK, width=3 * sc)
            cv.line([R(0, sh + 26), R(0, 330)], fill=(200, 200, 210), width=1.2 * sc)
            cv.poly([R(-60, sh - 10), R(-46, sh + 28), R(0, sh + 44), R(46, sh + 28), R(60, sh - 10), R(30, sh + 6), R(0, sh + 14), R(-30, sh + 6)], fill=(26, 38, 100), outline=INK, width=lw)
            cv.line([R(-60, sh - 8), R(-46, sh + 30)], fill=RED, width=5 * sc)
            cv.line([R(60, sh - 8), R(46, sh + 30)], fill=RED, width=5 * sc)
    # ---- neck ----
    cv.poly([R(-26, 60), R(26, 60), R(30, 100), R(-30, 100)], fill=SKIN_D, outline=INK, width=lw)

    bob = pose.get("bob", 0.0)
    cy2 = cy + bob * sc
    H = lambda x, y: rot(cx + x * sc, cy2 + y * sc, cx, cy + 90 * sc, tilt)  # head point (bobbed, tilted)

    # ---- hair back layer ----
    hair_back = [(-74, -62), (-60, -100), (-20, -118), (20, -118), (62, -100), (78, -60), (84, -10), (86, 30), (80, 58), (70, 76), (54, 70), (56, 40),
                 (-56, 40), (-54, 70), (-70, 78), (-82, 60), (-88, 28), (-86, -14)]
    cv.poly([H(*p) for p in smooth(hair_back, 5)], fill=HAIR, outline=INK, width=lw)
    # ---- ears ----
    for sd in (-1, 1):
        cv.ell(*H(sd * 62, -6), 12 * sc, 16 * sc, fill=SKIN, outline=INK, width=lw)
        cv.ell(*H(sd * 62, -4), 6 * sc, 9 * sc, fill=SKIN_D)
    # ---- face ----
    face = [(-58, -70), (-30, -84), (0, -86), (30, -84), (58, -70), (62, -30), (60, 10), (54, 50), (34, 82), (0, 92), (-34, 82), (-54, 50), (-60, 10), (-62, -30)]
    cv.poly([H(*p) for p in smooth(face, 5)], fill=SKIN, outline=INK, width=lw)
    # cheek shading
    cv.ell(*H(-42, 6), 12 * sc, 7 * sc, fill=(224, 172, 138))
    cv.ell(*H(42, 6), 12 * sc, 7 * sc, fill=(224, 172, 138))
    # ---- beard: close-cropped salt-and-pepper stubble following the jaw ----
    cv.poly([H(*p) for p in BEARD_POLY], fill=(196, 178, 166))
    # darker shadow band along the jaw to give it depth
    cv.poly([H(*p) for p in BEARD_JAW], fill=(180, 160, 148))
    # moustache (darker, like the photo)
    cv.poly([H(*p) for p in MO_POLY], fill=(128, 112, 104))
    # soul patch under the lower lip
    cv.poly([H(-11, 62), H(11, 62), H(9, 78), H(0, 84), H(-9, 78)], fill=(118, 102, 94))
    # stubble strokes (precomputed, deterministic)
    for (x, y, dx, dy, ci) in STUBBLE:
        cv.line([H(x, y), H(x + dx, y + dy)], fill=STUBBLE_COLS[ci], width=1.1 * sc)
    # ---- mouth ----
    m = clamp(pose.get("mouth", 0.0)); smile = pose.get("smile", 0.0)
    mw = 20 + 8 * m; mh = 4 + 20 * m
    if m > 0.1:
        cv.ell(*H(0, 50 + mh * 0.5), mw * sc, mh * sc, fill=(90, 20, 26), outline=INK, width=lw * 0.8)
        cv.poly([H(-mw * 0.72, 51), H(mw * 0.72, 51), H(mw * 0.72, 53 + 5 * m), H(-mw * 0.72, 53 + 5 * m)], fill=WHITE)
        if m > 0.45:
            cv.ell(*H(0, 50 + mh), mw * 0.5 * sc, 4 * m * sc, fill=(200, 70, 80))
    else:
        pts = [H(-16, 50 - 5 * smile), H(-8, 52 + 1 * smile), H(0, 53 + 2 * smile), H(8, 52 + 1 * smile), H(16, 50 - 5 * smile)]
        cv.line(pts, fill=INK, width=lw * 1.1)
    # ---- nose ----
    nose = [(0, -12), (12, 18), (10, 28), (0, 30), (-10, 28), (-12, 18)]
    cv.poly([H(*p) for p in smooth(nose, 4)], fill=SKIN_D, outline=INK, width=lw * 0.8)
    cv.ell(*H(-7, 24), 3 * sc, 2.2 * sc, fill=(120, 70, 55))
    cv.ell(*H(7, 24), 3 * sc, 2.2 * sc, fill=(120, 70, 55))
    # ---- eyes ----
    blink = clamp(pose.get("blink", 0.0)); look = pose.get("look", 0.0); brow = pose.get("brow", 0.0)
    closed = pose.get("eyes_closed", False)
    for sd in (-1, 1):
        ex, ey = sd * 26, -24
        if closed or blink > 0.85:
            cv.line([H(ex - 14, ey + 2), H(ex - 6, ey + 6), H(ex + 6, ey + 6), H(ex + 14, ey + 2)], fill=INK, width=lw)
        else:
            eh = 10 * (1 - blink)
            cv.ell(*H(ex, ey), 16 * sc, eh * sc, fill=WHITE, outline=INK, width=lw * 0.9)
            cv.ell(*H(ex + look * 5, ey + 1), 6.5 * sc, min(6.5, eh) * sc, fill=(92, 58, 40))
            cv.ell(*H(ex + look * 5, ey + 1), 3.2 * sc, min(3.2, eh) * sc, fill=INK)
            cv.ell(*H(ex + look * 5 + 2, ey - 2), 1.6 * sc, min(1.6, eh) * sc, fill=WHITE)
            # hooded lid (signature heavy-lidded look)
            cv.poly([H(ex - 17, ey - 10), H(ex, ey - 13), H(ex + 17, ey - 10), H(ex + 15, ey - 4 + 2 * brow), H(ex, ey - 6 + 2 * brow), H(ex - 15, ey - 4 + 2 * brow)], fill=SKIN)
            cv.line([H(ex - 16, ey - 3 + 2 * brow), H(ex, ey - 6 + 2 * brow), H(ex + 16, ey - 3 + 2 * brow)], fill=INK, width=lw * 0.8)
        # eyebrow: thick, dark, slightly angled; brow param lifts outer end
        by = ey - 20 - brow * 6
        inner = (ex - sd * 18, by + 4 - brow * 2); outer = (ex + sd * 20, by - 1 + brow * 3)
        cv.poly([H(inner[0], inner[1] - 4), H(ex, by - 7), H(outer[0], outer[1] - 4), H(outer[0] + sd * 2, outer[1] + 4), H(ex, by + 4), H(inner[0], inner[1] + 5)], fill=HAIR, outline=INK, width=lw * 0.7)
    # ---- hair front: one cap with a left parting, fringe sweeping right over the brow ----
    cap = [(-76, -44), (-78, -80), (-60, -108), (-24, -124), (20, -126), (58, -112), (76, -84), (78, -48),
           (68, -68), (54, -82), (34, -90), (12, -88), (-4, -80), (-18, -86), (-34, -84), (-50, -72), (-64, -56)]
    cv.poly([H(*p) for p in smooth(cap, 5)], fill=HAIR, outline=INK, width=lw)
    # parting line and highlights
    cv.line([H(-16, -116), H(-10, -96), H(-4, -82)], fill=INK, width=lw * 0.7)
    cv.line([H(-40, -104), H(-56, -84)], fill=HAIR_H, width=4 * sc)
    cv.line([H(2, -112), H(30, -106), H(52, -92)], fill=HAIR_H, width=5 * sc)
    # side locks over ears, flowing to the jaw
    lock_l = [(-66, -60), (-78, -30), (-86, 10), (-82, 50), (-70, 74), (-62, 60), (-66, 28), (-62, -10), (-56, -40)]
    lock_r = [(66, -60), (78, -30), (86, 10), (82, 50), (70, 74), (62, 60), (66, 28), (62, -10), (56, -40)]
    cv.poly([H(*p) for p in smooth(lock_l, 5)], fill=HAIR, outline=INK, width=lw)
    cv.poly([H(*p) for p in smooth(lock_r, 5)], fill=HAIR, outline=INK, width=lw)
    cv.line([H(-74, -20), H(-80, 20), H(-74, 56)], fill=HAIR_H, width=4 * sc)
    cv.line([H(74, -20), H(80, 20), H(74, 56)], fill=HAIR_H, width=4 * sc)
    # ---- hat ----
    if pose.get("hat"):
        cv.ell(*H(0, -96), 92 * sc, 18 * sc, fill=(22, 20, 26), outline=INK, width=lw)
        cv.poly([H(-56, -98), H(-62, -200), H(62, -200), H(56, -98)], fill=(22, 20, 26), outline=INK, width=lw)
        cv.poly([H(-57, -118), H(-59, -140), H(59, -140), H(57, -118)], fill=RED, outline=INK, width=lw * 0.8)
        cv.ell(*H(0, -200), 62 * sc, 10 * sc, fill=(40, 36, 46), outline=INK, width=lw)

def draw_star(cv, x, y, r, fill, outline=None, n=5):
    pts = []
    for k in range(n * 2):
        a = -math.pi / 2 + k * math.pi / n
        rr = r if k % 2 == 0 else r * 0.45
        pts.append((x + rr * math.cos(a), y + rr * math.sin(a)))
    cv.d.polygon(pts, fill=fill, outline=outline)

def draw_sparkle(cv, x, y, r, col, a=255):
    f = col + (a,)
    cv.d.polygon([(x, y - r), (x + r * 0.22, y - r * 0.22), (x + r, y), (x + r * 0.22, y + r * 0.22), (x, y + r), (x - r * 0.22, y + r * 0.22), (x - r, y), (x - r * 0.22, y - r * 0.22)], fill=f)

def draw_ball(cv, x, y, r, spin=0.0):
    cv.d.ellipse([x - r, y - r, x + r, y + r], fill=WHITE, outline=INK, width=int(S(2.2)))
    a0 = math.radians(spin)
    for k in range(5):
        a = a0 + k * 2 * math.pi / 5
        px, py = x + r * 0.5 * math.cos(a), y + r * 0.5 * math.sin(a)
        pts = [(px + r * 0.22 * math.cos(a + j * 2 * math.pi / 5), py + r * 0.22 * math.sin(a + j * 2 * math.pi / 5)) for j in range(5)]
        cv.d.polygon(pts, fill=INK)
    cv.d.polygon([(x + r * 0.26 * math.cos(a0 + j * 2 * math.pi / 5 + 0.6), y + r * 0.26 * math.sin(a0 + j * 2 * math.pi / 5 + 0.6)) for j in range(5)], fill=INK)

def draw_trophy(cv, x, y, s):
    # x,y = base centre (output coords already scaled? no: x,y are canvas coords in SS units)
    d = cv.d; lw = int(S(2.5))
    d.rectangle([x - 34 * s, y - 10 * s, x + 34 * s, y + 10 * s], fill=(40, 30, 20), outline=INK, width=lw)
    d.rectangle([x - 8 * s, y - 60 * s, x + 8 * s, y - 10 * s], fill=GOLD, outline=INK, width=lw)
    cup = [(x - 48 * s, y - 150 * s), (x + 48 * s, y - 150 * s), (x + 40 * s, y - 100 * s), (x + 16 * s, y - 62 * s), (x - 16 * s, y - 62 * s), (x - 40 * s, y - 100 * s)]
    d.polygon(cup, fill=GOLD, outline=INK, width=lw)
    d.ellipse([x - 50 * s, y - 158 * s, x + 50 * s, y - 142 * s], fill=(255, 225, 120), outline=INK, width=lw)
    d.arc([x - 80 * s, y - 150 * s, x - 30 * s, y - 90 * s], 90, 270, fill=INK, width=int(S(7)))
    d.arc([x - 80 * s, y - 150 * s, x - 30 * s, y - 90 * s], 90, 270, fill=GOLD, width=int(S(4)))
    d.arc([x + 30 * s, y - 150 * s, x + 80 * s, y - 90 * s], -90, 90, fill=INK, width=int(S(7)))
    d.arc([x + 30 * s, y - 150 * s, x + 80 * s, y - 90 * s], -90, 90, fill=GOLD, width=int(S(4)))
    draw_sparkle(cv, x + 20 * s, y - 130 * s, 10 * s, WHITE)

# ---------------------------------------------------------------- backgrounds
CROWD_RNG = np.random.RandomState(3)
CROWD = [(CROWD_RNG.uniform(0, 1), CROWD_RNG.uniform(0, 1), CROWD_RNG.randint(0, 3), CROWD_RNG.uniform(0, 6.28)) for _ in range(900)]
CROWD_COLS = [RED, WHITE, (30, 30, 34)]

def draw_crowd(cv, x0, y0, x1, y1, t, energy, rows=7, jump=True, arms=False, seed_shift=0):
    """Dense cartoon crowd in a stand rectangle."""
    rh = (y1 - y0) / rows
    p = pulse(t, 6)
    for r in range(rows):
        yy = y0 + r * rh + rh * 0.9
        shade = 0.55 + 0.45 * r / max(rows - 1, 1)
        n = int((x1 - x0) / (rh * 0.62))
        for i in range(n):
            k = (r * 131 + i * 17 + seed_shift) % len(CROWD)
            u, v, ci, ph = CROWD[k]
            xx = x0 + (i + 0.5 + (u - 0.5) * 0.6) * (x1 - x0) / n
            amp = rh * 0.45 * energy
            dy = -amp * max(0.0, math.sin(2 * math.pi * (bar_pos(t) * 2 + ph / 6.28))) if jump else -amp * 0.3 * math.sin(t * 2 + ph)
            dy -= p * rh * 0.18 * energy
            col = tuple(int(c * shade) for c in CROWD_COLS[ci])
            hr = rh * 0.28
            # body
            cv.rect(xx - hr * 1.1, yy + dy - hr * 0.6, xx + hr * 1.1, yy + dy + rh * 0.9, fill=col, r=hr * 0.5)
            # head
            cv.ell(xx, yy + dy - hr * 1.1, hr * 0.8, hr * 0.8, fill=tuple(int(c * shade) for c in (232, 190, 150)) if ci != 2 else tuple(int(c * shade) for c in (180, 140, 110)))
            if arms and energy > 0.5:
                up = max(0.0, math.sin(2 * math.pi * (bar_pos(t) * 2 + ph / 6.28)))
                for sd in (-1, 1):
                    cv.line([(xx + sd * hr * 0.9, yy + dy), (xx + sd * hr * 1.6, yy + dy - rh * (0.3 + 0.8 * up))], fill=tuple(int(c * shade) for c in (232, 190, 150)), width=max(1.5, hr * 0.35))

def draw_sky(cv, t, c0=(14, 12, 40), c1=(50, 20, 60)):
    cv.gradient_v(0, H, c0, c1)
    rnd = random.Random(11)
    for _ in range(90):
        x, y = rnd.uniform(0, W), rnd.uniform(0, H * 0.55)
        tw = 0.5 + 0.5 * math.sin(t * 3 + x)
        cv.ell(x, y, 1.2 * tw + 0.4, 1.2 * tw + 0.4, fill=(220, 220, 255))

def draw_floodlight(cv, x, y, t, on=1.0, lean=1):
    # mast
    cv.line([(x, y), (x + lean * 10, y + 300)], fill=(40, 40, 50), width=8)
    cv.rect(x - 60, y - 40, x + 60, y + 10, fill=(60, 60, 70), outline=INK, width=2, r=4)
    if on > 0:
        a = int(140 * on)
        for i in range(4):
            for j in range(2):
                cv.ell(x - 45 + i * 30, y - 25 + j * 24, 10, 9, fill=(255, 255, 230, a + 100))
        # beam
        cv.d.polygon([(S(x - 60), S(y)), (S(x + 60), S(y)), (S(x + 420 * (1 if lean > 0 else -1) + 300 * (-1 if lean > 0 else 1)), S(H)), (S(x - 420 * (1 if lean > 0 else -1) - 300 * (-1 if lean > 0 else 1)), S(H))],
                      fill=(255, 250, 220, int(26 * on)))

def draw_stadium(cv, t, energy=0.6, arms=False, hoard_text="MAGIC", zoom=1.0):
    draw_sky(cv, t)
    # far stand roof
    cv.rect(0, 170, W, 215, fill=(60, 60, 72))
    cv.rect(0, 212, W, 222, fill=(90, 90, 104))
    # upper and lower tiers
    cv.rect(0, 222, W, 300, fill=(120, 20, 18))
    draw_crowd(cv, 0, 222, W, 300, t, energy * 0.8, rows=4, arms=arms)
    cv.rect(0, 300, W, 318, fill=(70, 70, 84))
    cv.rect(0, 318, W, 420, fill=(130, 24, 20))
    draw_crowd(cv, 0, 318, W, 420, t, energy, rows=5, arms=arms, seed_shift=37)
    # hoardings
    cv.rect(0, 420, W, 456, fill=RED, outline=INK, width=2)
    f = font(22, "black")
    for k in range(-1, 8):
        xx = (k * 220 - (t * 40) % 220)
        cv.text(xx + 110, 438, hoard_text, f, WHITE)
        cv.text(xx + 220, 438, "•", f, GOLD)
    # pitch
    cv.gradient_v(456, H, (52, 140, 66), (40, 115, 52))
    for k in range(8):
        y0 = 456 + k * (H - 456) / 8
        if k % 2 == 0:
            cv.rect(0, y0, W, y0 + (H - 456) / 8, fill=(48, 132, 60))
    cv.line([(0, 470), (W, 470)], fill=(230, 240, 230), width=3)
    cv.line([(W / 2, 470), (W / 2, H)], fill=(230, 240, 230), width=3)
    cv.ell(W / 2, 650, 170, 60, outline=(230, 240, 230), width=3)
    # floodlights
    draw_floodlight(cv, 120, 120, t, on=energy, lean=-1)
    draw_floodlight(cv, W - 120, 120, t, on=energy, lean=1)

def draw_curtain(cv, t):
    cv.gradient_v(0, H, (90, 8, 14), (140, 18, 22))
    for k in range(0, W + 60, 60):
        xx = k + 6 * math.sin(t * 1.5 + k * 0.05)
        cv.poly([(xx, 0), (xx + 30, 0), (xx + 36, H), (xx + 6, H)], fill=(110, 12, 18))
        cv.line([(xx + 14, 0), (xx + 20, H)], fill=(170, 30, 36), width=6)
    # gold valance
    cv.rect(0, 0, W, 60, fill=(120, 14, 20))
    for k in range(0, W + 80, 80):
        cv.ell(k, 60, 42, 26, fill=(120, 14, 20), outline=GOLD, width=4)
    cv.rect(0, 0, W, 22, fill=GOLD)
    # stage floor
    cv.gradient_v(560, H, (40, 30, 24), (20, 14, 10))
    for k in range(0, W, 80):
        cv.line([(k, 560), (k - 60, H)], fill=(60, 44, 34), width=3)

def draw_pressroom(cv, t, redness):
    back = lerp_c((28, 44, 120), (160, 24, 20), redness)
    cv.gradient_v(0, H, back, lerp_c(back, (0, 0, 0), 0.35))
    rnd = random.Random(5)
    f = font(16, "black")
    for r in range(7):
        for c in range(7):
            xx = c * 200 + (r % 2) * 100 - 40; yy = r * 95 + 10
            cv.rect(xx, yy, xx + 120, yy + 44, fill=lerp_c((255, 255, 255), (255, 230, 230), redness), r=8)
            cv.text(xx + 60, yy + 22, ["PRESS", "MAGIC", "SOCCER", "POCH", "FC", "LIVE"][(r * 7 + c) % 6], f, lerp_c((60, 70, 140), RED, redness))

def draw_pressdesk(cv, t):
    # desk (drawn in front of the character)
    cv.rect(0, 600, W, H, fill=(40, 30, 28))
    cv.rect(0, 590, W, 606, fill=(70, 54, 48))
    # mics
    for k, mx in enumerate([W * 0.5 - 200, W * 0.5 - 90, W * 0.5 + 90, W * 0.5 + 200]):
        p = pulse(t, 7) * 6
        tip = (mx + (k - 1.5) * 10, 500 - p)
        cv.line([(mx, 600), tip], fill=(40, 40, 46), width=8)
        cv.rect(tip[0] - 18, tip[1] + 24, tip[0] + 18, tip[1] + 48, fill=[(230, 230, 230), (20, 20, 20), RED, WHITE][k], outline=INK, width=2, r=3)
        cv.ell(tip[0], tip[1], 18, 24, fill=[(70, 70, 80), (200, 40, 40), (70, 70, 80), (40, 90, 200)][k], outline=INK, width=2)
        for j in range(-2, 3):
            cv.line([(tip[0] - 14, tip[1] + j * 8), (tip[0] + 14, tip[1] + j * 8)], fill=INK, width=1.5)
    # name plate
    cv.rect(W * 0.5 - 170, 616, W * 0.5 + 170, 658, fill=WHITE, outline=INK, width=2, r=4)
    cv.text(W * 0.5, 637, "M. POCHETTINO  •  HEAD COACH", font(18, "black"), INK)

def draw_dressing_room(cv, t, arrows_progress, flash):
    cv.gradient_v(0, H, (34, 30, 38), (18, 16, 22))
    # lockers
    for k in range(0, W, 110):
        cv.rect(k + 8, 40, k + 100, 560, fill=(150, 30, 26), outline=INK, width=2, r=4)
        cv.rect(k + 20, 60, k + 88, 120, fill=(120, 22, 20), r=3)
        cv.text(k + 54, 90, str((k // 110 + 1) * 7 % 23 + 1), font(26, "black"), WHITE)
        # hanging shirt
        cv.poly([(k + 30, 150), (k + 78, 150), (k + 86, 175), (k + 76, 180), (k + 74, 300), (k + 34, 300), (k + 32, 180), (k + 22, 175)], fill=lerp_c(RED, WHITE, 0.08), outline=INK, width=1.5)
    cv.rect(0, 560, W, H, fill=(60, 56, 64))
    # whiteboard with pitch
    bx0, by0, bx1, by1 = 120, 90, 760, 500
    cv.rect(bx0 - 12, by0 - 12, bx1 + 12, by1 + 12, fill=(200, 200, 205), outline=INK, width=3, r=6)
    cv.rect(bx0, by0, bx1, by1, fill=(246, 246, 250))
    cv.rect(bx0 + 20, by0 + 20, bx1 - 20, by1 - 20, outline=(40, 120, 60), width=3)
    cv.line([(bx0 + 20, (by0 + by1) / 2), (bx1 - 20, (by0 + by1) / 2)], fill=(40, 120, 60), width=3)
    cv.ell((bx0 + bx1) / 2, (by0 + by1) / 2, 50, 50, outline=(40, 120, 60), width=3)
    # players (O red, X blue) in 4-2-3-1
    reds = [(0.12, 0.5), (0.3, 0.15), (0.3, 0.38), (0.3, 0.62), (0.3, 0.85), (0.5, 0.35), (0.5, 0.65), (0.68, 0.2), (0.68, 0.5), (0.68, 0.8), (0.85, 0.5)]
    fr = font(20, "black")
    for i, (u, v) in enumerate(reds):
        x = bx0 + 20 + u * (bx1 - bx0 - 40); y = by0 + 20 + v * (by1 - by0 - 40)
        prog = clamp(arrows_progress * 11 - i)
        if prog > 0:
            ex = x + 70 * prog * (1 if u < 0.8 else 0.3); ey = y + (0.5 - v) * 50 * prog
            cv.line([(x, y), (ex, ey)], fill=RED, width=5)
            cv.poly([(ex + 10, ey), (ex - 6, ey - 9), (ex - 6, ey + 9)], fill=RED)
        cv.ell(x, y, 15, 15, fill=RED, outline=INK, width=2)
        cv.text(x, y, str(i + 1), font(14, "black"), WHITE)
    for u, v in [(0.9, 0.25), (0.9, 0.75), (0.75, 0.4), (0.75, 0.6)]:
        x = bx0 + 20 + u * (bx1 - bx0 - 40); y = by0 + 20 + v * (by1 - by0 - 40)
        cv.text(x, y, "X", fr, (40, 80, 200))
    cv.text((bx0 + bx1) / 2, by0 + 2, "THE PLAN", font(22, "black"), INK, anchor="mt")
    if flash > 0:
        cv.rect(bx0, by0, bx1, by1, fill=(255, 255, 255, int(120 * flash)))

def draw_spotlight(cv, x, y, r, col=(255, 240, 200), a=70):
    cv.d.polygon([(S(x - r * 0.25), 0), (S(x + r * 0.25), 0), (S(x + r), S(H)), (S(x - r), S(H))], fill=col + (a,))

def confetti(cv, t, t0, count=160, seed=1, spread=W, cx=W / 2, ytop=-20, speed=1.0):
    rnd = random.Random(seed)
    age = t - t0
    if age < 0: return
    for _ in range(count):
        x0 = cx + rnd.uniform(-spread / 2, spread / 2); vy = rnd.uniform(90, 200) * speed; ph = rnd.uniform(0, 6.3); c = rnd.choice([RED, WHITE, GOLD, (30, 30, 34)])
        delay = rnd.uniform(0, 1.5); w = rnd.uniform(5, 10)
        a = age - delay
        if a < 0: continue
        x = x0 + 30 * math.sin(a * 3 + ph); y = ytop + vy * a + 40 * a * a
        if y > H + 20: continue
        hh = w * abs(math.cos(a * 5 + ph)) + 1
        cv.rect(x - w / 2, y - hh / 2, x + w / 2, y + hh / 2, fill=c)

def firework(cv, x, y, age, col, seed):
    if age < 0 or age > 1.6: return
    rnd = random.Random(seed)
    r = 220 * (1 - math.exp(-age * 2.6))
    a = int(255 * clamp(1 - age / 1.6))
    for _ in range(46):
        ang = rnd.uniform(0, 6.283); rr = r * rnd.uniform(0.6, 1.0)
        px, py = x + rr * math.cos(ang), y + rr * math.sin(ang) + 60 * age * age
        cv.line([(x + rr * 0.6 * math.cos(ang), y + rr * 0.6 * math.sin(ang) + 60 * age * age * 0.6), (px, py)], fill=col + (a,), width=3)
        cv.ell(px, py, 3, 3, fill=(255, 255, 255, a))

# ---------------------------------------------------------------- base pose from audio
GEST_RNG = random.Random(42)
GESTURES = {}
for i, b in enumerate(BEATS):
    o = feat(ONSET, b + 0.03)
    if o > 0.62 and i % 2 == 0:
        GESTURES[i] = GEST_RNG.choice(["point_r", "point_l", "both_up", "fist", "shrug"])

def base_pose(t, style="sing", energy=1.0):
    i, ph, db = beat_info(t)
    p = pulse(t, 5)
    pose = dict(bob=-9 * p * energy, tilt=4 * math.sin(2 * math.pi * bar_pos(t)) * energy,
                mouth=clamp((feat(VOC, t) - 0.12) / 0.45) * (1 if style != "mute" else 0),
                blink=1.0 if (t % 3.7) < 0.12 or ((t + 1.3) % 5.3) < 0.1 else 0.0,
                brow=0.3 * p + 0.3 * clamp((feat(VOC, t) - 0.3) / 0.4), look=0.4 * math.sin(t * 0.7),
                smile=0.5 + 0.5 * math.sin(t * 0.3))
    # arms: default behind-ish/relaxed, gestures on strong hits
    g = None
    for k in range(i, max(i - 3, -1), -1):
        if k in GESTURES:
            g = GESTURES[k]; gage = t - BEATS[k]; break
    pose.update(arm_l=12, arm_r=12, fore_l=15, fore_r=15, hand_l="fist", hand_r="fist")
    if g and energy > 0.3:
        s = ease(gage / 0.18) * ease((1.6 * BEAT_LEN - gage) / 0.25)
        if g == "point_r":
            pose.update(arm_r=12 + 95 * s, fore_r=15 - 40 * s, hand_r="point")
        elif g == "point_l":
            pose.update(arm_l=12 + 95 * s, fore_l=15 - 40 * s, hand_l="point")
        elif g == "both_up":
            pose.update(arm_l=12 + 150 * s, arm_r=12 + 150 * s, fore_l=15 - 10 * s, fore_r=15 - 10 * s, hand_l="open", hand_r="open")
        elif g == "fist":
            pose.update(arm_r=12 + 40 * s, fore_r=15 + 120 * s)
        elif g == "shrug":
            pose.update(arm_l=12 + 60 * s, arm_r=12 + 60 * s, fore_l=15 + 110 * s, fore_r=15 + 110 * s, hand_l="open", hand_r="open", brow=1.0 * s)
    return pose

# ---------------------------------------------------------------- scenes
def title_text(cv, t, alpha=1.0):
    f1 = font(96, "display"); f2 = font(150, "serif")
    p = pulse(t, 4)
    cv.text(W / 2 + 6, 250 + 6, "POCHETTINO", f1, (0, 0, 0, int(160 * alpha)))
    cv.text(W / 2, 250, "POCHETTINO", f1, WHITE + (int(255 * alpha),), stroke=4, stroke_fill=INK + (int(255 * alpha),))
    y = 420 - 10 * p
    cv.text(W / 2 + 8, y + 8, "MAGIC", f2, (0, 0, 0, int(160 * alpha)))
    cv.text(W / 2, y, "MAGIC", f2, GOLD + (int(255 * alpha),), stroke=5, stroke_fill=DRED + (int(255 * alpha),))
    rnd = random.Random(int(t * 6))
    for _ in range(int(12 * alpha)):
        draw_sparkle(cv, S(rnd.uniform(240, 1040)), S(rnd.uniform(330, 500)), S(rnd.uniform(6, 20)), WHITE, int(220 * alpha))

def scene_intro(cv, t, u):
    # lights come on one by one; title appears
    draw_sky(cv, t)
    # silhouette stadium
    cv.gradient_v(380, H, (20, 18, 40), (8, 8, 20))
    for k in range(0, W + 40, 40):
        hgt = 110 + 40 * math.sin(k * 0.02)
        cv.rect(k, 380 - hgt, k + 36, 380, fill=(22, 20, 44))
    on1 = ease((t - 0.3) / 0.6); on2 = ease((t - 1.3) / 0.6)
    draw_floodlight(cv, 160, 150, t, on=on1, lean=-1)
    draw_floodlight(cv, W - 160, 150, t, on=on2, lean=1)
    title_text(cv, t, ease((t - 2.0) / 0.8))

def scene_tunnel_walk(cv, t, u):
    # Poch walks out of the tunnel toward camera as the intro builds
    draw_stadium(cv, t, energy=0.45 + 0.4 * u, hoard_text="MAGIC")
    # tunnel mouth
    cv.rect(W / 2 - 110, 300, W / 2 + 110, 456, fill=(20, 18, 26), outline=INK, width=3, r=8)
    cv.rect(W / 2 - 90, 300, W / 2 + 90, 440, fill=(50, 40, 60))
    sc = 0.45 + 1.95 * ease(u) ** 1.4
    cy = 300 + 180 * ease(u) ** 1.4
    pose = base_pose(t, style="mute", energy=0.6)
    step = math.sin(2 * math.pi * bar_pos(t) * 4)
    pose.update(bob=pose["bob"] + 6 * abs(step) * (1 - u), tilt=3 * step, arm_l=14 + 18 * step, arm_r=14 - 18 * step, fore_l=20, fore_r=20, smile=0.6,
                legs=True, step=step)
    if u > 0.7:
        pose["mouth"] = clamp((feat(VOC, t) - 0.12) / 0.45)
    draw_poch(cv, W / 2, cy, sc, pose, t)
    a = ease((0.22 - u) / 0.22)
    if a > 0:
        title_text(cv, t, a)

def scene_touchline(cv, t, u):
    draw_stadium(cv, t, energy=0.7 + 0.3 * feat(RMS, t), hoard_text="MAGIC")
    # technical area box
    cv.rect(W / 2 - 300, 470, W / 2 + 300, H, outline=(230, 240, 230), width=3)
    pose = base_pose(t, "sing")
    sc = 2.3 + 0.15 * ease(u)
    draw_poch(cv, W / 2 + 40 * math.sin(u * 3), 330, sc, pose, t)
    # bouncing ball at his feet
    bx = W / 2 - 300; by = 600 - abs(math.sin(2 * math.pi * bar_pos(t) * 2)) * 160
    draw_ball(cv, S(bx), S(by), S(32), t * 300)

def scene_magic(cv, t, u, big=False, sect_start=0.0):
    draw_curtain(cv, t)
    sw = 1 + 0.5 * math.sin(t * 2.3)
    draw_spotlight(cv, W / 2 - 240 + 60 * sw, 0, 240, (255, 200, 200), 60)
    draw_spotlight(cv, W / 2 + 240 - 60 * sw, 0, 240, (200, 220, 255), 60)
    draw_spotlight(cv, W / 2, 0, 300, (255, 240, 200), 90)
    pose = base_pose(t, "sing")
    pose.update(outfit="magic", hat=True, cape=True)
    i, ph, db = beat_info(t)
    # magic trick cycle every 2 bars: wand -> flash -> ball appears over hat
    cyc = ((t - sect_start) % (8 * BEAT_LEN)) / (8 * BEAT_LEN)
    pose.update(prop_r="wand", hand_r="fist")
    if cyc < 0.5:
        pose.update(arm_r=12 + 110 * ease(cyc / 0.12), fore_r=15 - 60 * ease(cyc / 0.12))
    else:
        pose.update(arm_r=12 + 150 * ease((1 - cyc) / 0.12) if cyc > 0.88 else 160, fore_r=-40)
    draw_poch(cv, W / 2, 330, 2.3, pose, t)
    # the levitating ball + sparkles after the wand flick
    if cyc > 0.5:
        a = (cyc - 0.5) / 0.5
        bx = W / 2 + 230; by = 250 - 120 * math.sin(a * math.pi) - 20 * pulse(t)
        draw_ball(cv, S(bx), S(by), S(44 * ease(a / 0.15)), t * 400)
        rnd = random.Random(int(t * 10))
        for _ in range(14):
            ang = rnd.uniform(0, 6.3); rr = rnd.uniform(50, 110)
            draw_sparkle(cv, S(bx + rr * math.cos(ang)), S(by + rr * math.sin(ang)), S(rnd.uniform(5, 16)), GOLD if rnd.random() < 0.5 else WHITE)
    # big MAGIC word on downbeats
    if True:
        p = pulse(t, 4)
        f = font(92 if big else 76, "serif")
        a = int(255 * (0.5 + 0.5 * p))
        cv.text(W / 2 - 400, 160 - 14 * p, "MAGIC", f, GOLD + (a,), stroke=5, stroke_fill=INK + (a,))
        if big:
            cv.text(W / 2 + 400, 160 - 14 * p, "MAGIC", f, GOLD + (a,), stroke=5, stroke_fill=INK + (a,))
    for k, b in enumerate(BEATS):
        if big and (k - DB_OFF) % 8 == 0 and 0 <= t - b < 3.0 and b >= sect_start:
            confetti(cv, t, b, count=120, seed=k, speed=1.1)

def scene_tactics(cv, t, u):
    i, ph, db = beat_info(t)
    seg = ((t - 60.0) % (8 * BEAT_LEN)) / (8 * BEAT_LEN)
    flash = max(0.0, 1 - ((t - 60.0) % (8 * BEAT_LEN)) / 0.3) if seg < 0.3 else 0
    draw_dressing_room(cv, t, arrows_progress=ease(seg / 0.85), flash=flash)
    pose = base_pose(t, "sing")
    pose.update(prop_l="marker", hand_l="fist", arm_l=12 + 80 * (0.5 + 0.5 * math.sin(2 * math.pi * seg)), fore_l=-30)
    pose["tilt"] = -6 + pose["tilt"]
    draw_poch(cv, 1000, 380, 2.1, pose, t)

def scene_press(cv, t, u):
    draw_pressroom(cv, t, redness=ease((u - 0.35) / 0.4))
    pose = base_pose(t, "sing", energy=0.8)
    pose.update(outfit="press" if u < 0.55 else "track", arm_l=6, arm_r=6, fore_l=70, fore_r=70, hand_l="open", hand_r="open", body=True)
    # lean in on loud bits
    pose["bob"] += 6 * clamp((feat(VOC, t) - 0.3) / 0.3)
    draw_poch(cv, W / 2, 330, 2.1, pose, t)
    draw_pressdesk(cv, t)

def scene_closeup(cv, t, u):
    cv.gradient_v(0, H, (10, 8, 14), (30, 10, 16))
    draw_spotlight(cv, W / 2, 0, 320, (255, 230, 220), 70)
    # drifting red smoke
    rnd = random.Random(9)
    for _ in range(26):
        x0, y0, r0, ph = rnd.uniform(-100, W + 100), rnd.uniform(300, H + 80), rnd.uniform(80, 220), rnd.uniform(0, 6.3)
        x = x0 + 30 * math.sin(t * 0.4 + ph); y = y0 - 20 * math.sin(t * 0.3 + ph)
        cv.ell(x, y, r0, r0 * 0.6, fill=(180, 30, 40, 26))
    pose = base_pose(t, "sing", energy=0.4)
    pose.update(body=True, eyes_closed=(u > 0.15 and u < 0.65 and feat(VOC, t) < 0.4), smile=1.0, tilt=5 * math.sin(2 * math.pi * bar_pos(t) / 2))
    pose["bob"] *= 0.6
    draw_poch(cv, W / 2, 420, 4.2, pose, t)
    # vignette
    cv.d.rectangle([0, 0, S(W), S(H)], fill=(0, 0, 0, 0))
    for k in range(6):
        cv.d.ellipse([S(-300 - k * 80), S(-500 - k * 80), S(W + 300 + k * 80), S(H + 500 + k * 80)], outline=(0, 0, 0, 40), width=int(S(60)))

def scene_finale(cv, t, u, sect_start=136.3):
    draw_stadium(cv, t, energy=1.0, arms=True, hoard_text="MAGIC")
    # fireworks on downbeats
    rnd = random.Random(77)
    for k, b in enumerate(BEATS):
        if (k - DB_OFF) % 4 == 0 and b >= sect_start - 0.1 and 0 <= t - b < 1.7:
            r = random.Random(k)
            firework(cv, r.uniform(150, W - 150), r.uniform(80, 260), t - b, r.choice([GOLD, RED, WHITE, (120, 200, 255)]), k)
    pose = base_pose(t, "sing")
    lift = ease((u - 0.1) / 0.2)
    p = pulse(t, 5)
    # left hand (screen left) hoists the trophy; right arm pumps on the beat
    pose.update(arm_l=12 + 125 * lift, fore_l=15 - 5 * lift, hand_l="fist", smile=1.0)
    pose.update(arm_r=12 + (60 + 30 * p) * lift, fore_r=15 + 95 * lift, hand_r="fist")
    if lift >= 1.0:
        pose.update(prop_l="trophy")
    draw_poch(cv, W / 2, 340, 2.3, pose, t)
    for k, b in enumerate(BEATS):
        if (k - DB_OFF) % 8 == 0 and b >= sect_start and 0 <= t - b < 3.2:
            confetti(cv, t, b, count=160, seed=k + 500, speed=1.2)
    f = font(96, "serif")
    a = int(255 * (0.55 + 0.45 * p))
    cv.text(W / 2 - 380, 150 - 12 * p, "MAGIC", f, GOLD + (a,), stroke=5, stroke_fill=INK + (a,))

def scene_outro(cv, t, u):
    draw_stadium(cv, t, energy=0.25 + 0.5 * clamp((u - 0.6) / 0.4), hoard_text="MAGIC")
    # tunnel mouth
    cv.rect(W / 2 - 110, 300, W / 2 + 110, 456, fill=(20, 18, 26), outline=INK, width=3, r=8)
    cv.rect(W / 2 - 90, 300, W / 2 + 90, 440, fill=(50, 40, 60))
    # walking off toward the tunnel (shrinking), then a last wave
    w = ease(u / 0.6)
    sc = 2.0 - 1.5 * w
    cy = 420 - 110 * w
    pose = base_pose(t, "mute", energy=0.4)
    step = math.sin(2 * math.pi * bar_pos(t) * 4)
    pose.update(bob=pose["bob"] + 4 * abs(step), arm_l=14 + 14 * step, arm_r=14 - 14 * step, smile=1.0, legs=True, step=step * (1 - ease((u - 0.6) / 0.05)))
    if u > 0.6:
        wave = 0.5 + 0.5 * math.sin(t * 7)
        pose.update(arm_r=140, fore_r=-30 + 40 * wave, hand_r="open", blink=1.0 if (u > 0.7 and u < 0.78) else pose["blink"], step=0.0)
        pose["mouth"] = clamp((feat(VOC, t) - 0.12) / 0.45)
    draw_poch(cv, W / 2, cy, sc, pose, t)
    # end card
    a = ease((u - 0.45) / 0.3)
    if a > 0:
        cv.text(W / 2, 70, "POCHETTINO", font(58, "display"), WHITE + (int(255 * a),), stroke=3, stroke_fill=INK + (int(255 * a),))
        cv.text(W / 2, 140, "MAGIC", font(72, "serif"), GOLD + (int(255 * a),), stroke=4, stroke_fill=DRED + (int(255 * a),))
        cv.text(W / 2, 660, "an animated video  •  cover of Pilot's “Magic”", font(22, "black"), WHITE + (int(220 * a),))
    # fade to black at the very end
    fb = ease((t - (DUR - 2.5)) / 2.5)
    if fb > 0:
        cv.rect(0, 0, W, H, fill=(0, 0, 0, int(255 * fb)))

# scene list: (start, end, fn)
SCENES = [
    (0.0, 5.0, scene_intro),
    (5.0, 21.1, scene_tunnel_walk),
    (21.1, 43.0, scene_touchline),
    (43.0, 60.0, lambda cv, t, u: scene_magic(cv, t, u, big=False, sect_start=43.0)),
    (60.0, 76.4, scene_tactics),
    (76.4, 100.2, scene_press),
    (100.2, 122.8, lambda cv, t, u: scene_magic(cv, t, u, big=True, sect_start=100.2)),
    (122.8, 136.3, scene_closeup),
    (136.3, 153.4, scene_finale),
    (153.4, DUR + 1, scene_outro),
]

def render_frame(t):
    img = Image.new("RGB", (W * SS, H * SS), (0, 0, 0))
    cv = Canvas(img)
    for (a, b, fn) in SCENES:
        if a <= t < b:
            fn(cv, t, (t - a) / (b - a))
            # white flash on scene cut (first 0.12s)
            if t - a < 0.12 and a > 0:
                cv.rect(0, 0, W, H, fill=(255, 255, 255, int(200 * (1 - (t - a) / 0.12))))
            # beat-synced camera punch-in for the energetic scenes
            if fn not in (scene_intro, scene_closeup, scene_outro) and t > 21:
                z = 1.0 + 0.025 * pulse(t, 6)
                cw, ch = W * SS / z, H * SS / z
                x0, y0 = (W * SS - cw) / 2, (H * SS - ch) / 2
                return img.crop((int(x0), int(y0), int(x0 + cw), int(y0 + ch))).resize((W, H), Image.LANCZOS)
            break
    return img.resize((W, H), Image.LANCZOS)

def main():
    mode = sys.argv[1]
    if mode == "preview":
        os.makedirs(os.path.join(HERE, "frames"), exist_ok=True)
        for ts in sys.argv[2:]:
            t = float(ts)
            render_frame(t).save(os.path.join(HERE, "frames", f"prev_{t:07.2f}.png"))
            print("ok", t)
    elif mode == "render":
        start, end, out = int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
        import subprocess
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
               "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", out]
        p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        for fi in range(start, end):
            img = render_frame(fi / FPS)
            p.stdin.write(img.tobytes())
            if (fi - start) % 150 == 0:
                print(f"[{out}] frame {fi}/{end}", flush=True)
        p.stdin.close(); p.wait()
        print("done", out)

if __name__ == "__main__":
    main()
