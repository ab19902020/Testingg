"""Old Trafford stadium visualizer: per-frame 3D render of the bowl with a moving camera.

usage:
  python3 render_stadium.py still 6 52 80          # writes still_<t>.jpg frames for checking
  python3 render_stadium.py chunk A B out.mp4      # renders frames A..B-1 (30 fps) to a silent mp4
Needs feat.npz (python3 analyze.py) and lyrics_timing.json (python3 align_lyrics.py, or the bundled estimate).
"""
import numpy as np, cv2, math, sys, subprocess, os, json
from PIL import Image, ImageDraw, ImageFont

f32 = np.float32
W, H = 1920, 1080
FPS = 30
HERE = os.path.dirname(os.path.abspath(__file__))
def font_path(name, *fallbacks):
    for p in [os.path.join(HERE, 'fonts', name)] + list(fallbacks):
        if os.path.exists(p): return p
    raise FileNotFoundError(f'font {name} not found (expected in fonts/)')
DEJAVU_BOLD = font_path('DejaVuSans-Bold.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf')
DEJAVU_COND = font_path('DejaVuSansCondensed-Bold.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf')

# ================================================================== audio
F = np.load('feat.npz')
AUD = F['audio']; SR = 22050
N = len(F['rms']); DUR = N / FPS
def smooth(v, att, rel):
    o = np.zeros_like(v); a = 0.0
    for i, x in enumerate(v):
        a = a + (x - a) * (att if x > a else rel); o[i] = a
    return o
bass = smooth(F['bass'], 0.7, 0.16).astype(f32)
mid = smooth(F['mid'], 0.5, 0.15).astype(f32)
energy = smooth(np.convolve(F['rms'], np.ones(45) / 45, 'same'), 0.08, 0.03)
spec = F['spec'].astype(f32).copy()
for k in range(1, N): spec[k] = np.maximum(spec[k], spec[k - 1] * 0.8)
BEAT0, BPF = 7, 15
def beatpos(i): return (i - BEAT0) / BPF
def snap(t): return int(BEAT0 + round((t * FPS - BEAT0) / BPF) * BPF)
KICKOFF = snap(19.6)
DROPS = [snap(89.2), snap(114.2), snap(136.2), snap(163.5)]
BREAK = (snap(153.2), snap(158.4))
def since(evts, i):
    d = [i - e for e in evts if i >= e]
    return min(d) if d else 10 ** 9

# ================================================================== bowl geometry
A, B, RC = 60.5, 42.0, 16.0
LS, LZ = A - RC, B - RC
ARC = math.pi / 2 * RC
SEC = [LS, ARC, 2 * LZ, ARC, 2 * LS, ARC, 2 * LZ, ARC, LS]
SEC0 = np.cumsum([0.0] + SEC[:-1])
L = float(sum(SEC)); HALF = L / 2

def perim(s):
    s = np.asarray(s, np.float64) % L
    k = np.clip(np.searchsorted(SEC0, s, side='right') - 1, 0, 8)
    u = s - SEC0[k]
    x = np.zeros_like(s); z = np.zeros_like(s); nx = np.zeros_like(s); nz = np.zeros_like(s)
    ph = u / RC
    def arc(m, cx, cz, th):
        x[m] = cx + RC * np.cos(th[m]); z[m] = cz + RC * np.sin(th[m]); nx[m] = np.cos(th[m]); nz[m] = np.sin(th[m])
    m = k == 0; x[m] = u[m]; z[m] = B; nz[m] = 1
    arc(k == 1, LS, LZ, np.pi / 2 - ph)
    m = k == 2; x[m] = A; z[m] = LZ - u[m]; nx[m] = 1
    arc(k == 3, LS, -LZ, -ph)
    m = k == 4; x[m] = LS - u[m]; z[m] = -B; nz[m] = -1
    arc(k == 5, -LS, -LZ, -np.pi / 2 - ph)
    m = k == 6; x[m] = -A; z[m] = -LZ + u[m]; nx[m] = -1
    arc(k == 7, -LS, LZ, np.pi - ph)
    m = k == 8; x[m] = -LS + u[m]; z[m] = B; nz[m] = 1
    return x, z, nx, nz

def plan_inverse(x, z):
    """signed perimeter coordinate and outward offset for plan points (float32 arrays)"""
    ax, az = np.abs(x), np.abs(z)
    qx, qz = ax - LS, az - LZ
    s = np.zeros_like(x); d = np.zeros_like(x)
    m1 = qx <= 0                      # north / south straights
    m2 = (~m1) & (qz <= 0)            # east / west straights
    m3 = ~(m1 | m2)                   # corners
    n_ = z > 0
    s = np.where(m1 & n_, x, s); d = np.where(m1, az - B, d)
    s = np.where(m1 & ~n_ & (x >= 0), HALF - x, s); s = np.where(m1 & ~n_ & (x < 0), -HALF - x, s)
    e_ = x > 0
    s = np.where(m2 & e_, SEC0[2] + (LZ - z), s); s = np.where(m2 & ~e_, SEC0[6] + (z + LZ) - L, s); d = np.where(m2, ax - A, d)
    th = np.arctan2(qz, qx)  # 0..pi/2 in |x|,|z| space
    r = np.hypot(qx, qz)
    ne = m3 & e_ & n_; se = m3 & e_ & ~n_; sw = m3 & ~e_ & ~n_; nw = m3 & ~e_ & n_
    s = np.where(ne, SEC0[1] + (np.pi / 2 - th) * RC, s)
    s = np.where(se, SEC0[3] + th * RC, s)
    s = np.where(sw, SEC0[5] + (np.pi / 2 - th) * RC - L, s)
    s = np.where(nw, SEC0[7] + th * RC - L, s)
    d = np.where(m3, r - RC, d)
    return s.astype(f32), d.astype(f32)

_ts = np.linspace(-HALF, HALF, 40001)
def _turn_un(s):
    s = s % L
    k = np.clip(np.searchsorted(SEC0, s, side='right') - 1, 0, 8); u = s - SEC0[k]
    base = np.array([0, 0, .5, .5, 1, 1, 1.5, 1.5, 2]) * np.pi
    return np.where(k % 2 == 1, base[k] + u / RC, base[k])
_tn = _turn_un(_ts); _tn = np.where((_ts % L) > HALF, _tn - 2 * np.pi, _tn).astype(f32)
_ts = _ts.astype(f32)
def turn_of(ss): return np.interp(ss, _ts, _tn).astype(f32)

M_WALL, M_SEAT0, M_SEAT1, M_SEAT2, M_BOX, M_RIB0, M_RIB1, M_BACK, M_ROOF, M_FASCIA, M_LAMP, M_BOARD, M_SCREEN, M_FRAME = range(14)
PROF = [(0, 0, 0, 1.8, M_WALL), (0, 1.8, 24, 12.5, M_SEAT0), (24, 12.5, 24, 15.8, M_BOX), (24, 15.8, 24, 17.2, M_RIB0),
        (24, 17.2, 38, 27.5, M_SEAT1), (38, 27.5, 38, 30.6, M_BOX), (38, 30.6, 38, 31.8, M_RIB1),
        (38, 31.8, 60, 50, M_SEAT2), (60, 50, 60, 53.5, M_BACK), (8, 50, 60, 53.5, M_ROOF), (8, 50, 8, 53.5, M_FASCIA),
        (-4, 0, -4, 1.1, M_BOARD)]
NROWS = {M_SEAT0: 31, M_SEAT1: 20, M_SEAT2: 33}; CUMR = {M_SEAT0: 0, M_SEAT1: 31, M_SEAT2: 51}; TOTROWS = 84
NL = 96

def build():
    n1 = int(math.ceil(HALF / 1.5))
    sk = np.concatenate([np.linspace(0, HALF, n1 + 1), np.linspace(HALF, L, n1 + 1)[1:]])
    px, pz, nx, nz = perim(sk)
    Q, MAT, PRF, EX = [], [], [], []
    for pi_, (d0, y0, d1, y1, mat) in enumerate(PROF):
        for k in range(len(sk) - 1):
            def P(i, d, y): return (px[i] + d * nx[i], y, pz[i] + d * nz[i])
            Q.append((P(k, d0, y0), P(k + 1, d0, y0), P(k + 1, d1, y1), P(k, d1, y1))); MAT.append(mat); PRF.append(pi_); EX.append(-1)
    sl = (np.arange(NL) + 0.5) * L / NL
    lx, lz, lnx, lnz = perim(sl)
    lamps = []
    for i in range(NL):
        c = np.array([lx[i] + 9.3 * lnx[i], 49.65, lz[i] + 9.3 * lnz[i]])
        tg = np.array([lnz[i], 0, -lnx[i]]); nn = np.array([lnx[i], 0, lnz[i]])
        Q.append((c - 0.95 * tg - 0.55 * nn, c + 0.95 * tg - 0.55 * nn, c + 0.95 * tg + 0.55 * nn, c - 0.95 * tg + 0.55 * nn))
        MAT.append(M_LAMP); PRF.append(-1); EX.append(i)
        ss = sl[i] if sl[i] <= HALF else sl[i] - L
        lamps.append((c, np.array([lx[i] * 0.42, 0.0, lz[i] * 0.42]), ss))
    screens = []
    for sid, smid in enumerate((SEC0[1] + ARC / 2, SEC0[7] + ARC / 2)):
        qx, qz, qnx, qnz = perim(np.array([smid]))
        n = np.array([qnx[0], 0, qnz[0]])
        ctr = np.array([qx[0], 0, qz[0]]) + 47 * n; ctr[1] = 44.2
        right = np.array([n[2], 0, -n[0]]); tl = math.radians(10)
        up = np.array([0, math.cos(tl), 0]) - n * math.sin(tl)
        for mat, hw, hh, off in ((M_FRAME, 11.7, 4.3, 0.4), (M_SCREEN, 11.0, 3.75, 0.0)):
            c = ctr + n * off
            Q.append((c - right * hw + up * hh, c + right * hw + up * hh, c + right * hw - up * hh, c - right * hw - up * hh))
            MAT.append(mat); PRF.append(-1); EX.append(sid)
        screens.append((ctr - right * 11.0 + up * 3.75, right, up))
    Q = np.array(Q, np.float64)
    cen = Q.mean(1)
    nrm = np.cross(Q[:, 2] - Q[:, 0], Q[:, 3] - Q[:, 1]); nrm /= np.linalg.norm(nrm, axis=1, keepdims=True)
    dval = np.einsum('ij,ij->i', nrm, cen)
    return Q, cen, nrm.astype(f32), dval.astype(f32), np.array(MAT, np.int32), np.array(PRF, np.int32), np.array(EX, np.int32), lamps, screens

Q, QCEN, QN, QD, QMAT, QPRF, QEX, LAMPS, SCREENS = build()
NQ = len(Q)
PRF_ARR = np.array([p[:4] for p in PROF], f32)
LAMP_P = np.array([l[0] for l in LAMPS], f32)
LAMP_AIM = np.array([(l[1] - l[0]) / np.linalg.norm(l[1] - l[0]) for l in LAMPS], f32)
LAMP_S = np.array([l[2] for l in LAMPS], f32)
LAMP_GRP = np.minimum((np.abs(LAMP_S) / HALF * 8).astype(int), 7)
# draw order class: stands first (depth sorted), then frames, screens, lamps
LATE = np.where(QMAT == M_FRAME, 1, np.where(QMAT == M_SCREEN, 2, np.where(QMAT == M_LAMP, 3, 0)))

# ================================================================== textures
rng0 = np.random.default_rng(11)
def tile_noise(n, blur):
    a = rng0.random((n, n)).astype(f32)
    a = cv2.GaussianBlur(np.tile(a, (3, 3)), (0, 0), blur)[n:2 * n, n:2 * n]
    return ((a - a.mean()) / (a.std() + 1e-6)).astype(f32)
NOISE_BIG = tile_noise(512, 10); NOISE_FINE = tile_noise(512, 1.5)
CLOUD1 = tile_noise(256, 9); CLOUD2 = tile_noise(256, 3)
def samp(tex, u, v):
    n = tex.shape[0]
    u = np.mod(u, n).astype(f32); v = np.mod(v, n).astype(f32)
    i0 = u.astype(np.int32) % n; j0 = v.astype(np.int32) % n
    fu = np.clip(u - np.floor(u), 0, 1); fv = np.clip(v - np.floor(v), 0, 1)
    i1 = (i0 + 1) % n; j1 = (j0 + 1) % n
    return tex[j0, i0] * (1 - fu) * (1 - fv) + tex[j0, i1] * fu * (1 - fv) + tex[j1, i0] * (1 - fu) * fv + tex[j1, i1] * fu * fv
def hash2(a, b):
    v = np.sin(a * 12.9898 + b * 78.233) * 43758.5453
    return (v - np.floor(v)).astype(f32)
def letter_bitmap(txt, rows):
    fnt = ImageFont.truetype(DEJAVU_BOLD, rows * 8)
    l, t, r_, b = fnt.getbbox(txt)
    im = Image.new('L', (r_ - l + 16, rows * 8 + 8), 0)
    ImageDraw.Draw(im).text((8 - l, 4 - t + (rows * 8 - (b - t)) // 2), txt, font=fnt, fill=255)
    return np.asarray(im.resize((int(round(im.width / 8 * 0.85)), rows), Image.BOX)) > 100
LETTERS = letter_bitmap('MANCHESTER UNITED', 11); LET_ROW0 = 12
def text_strip(txt, rows):
    fnt = ImageFont.truetype(DEJAVU_COND, int(rows * 0.78))
    w = int(ImageDraw.Draw(Image.new('L', (1, 1))).textlength(txt, font=fnt)) + 4
    im = Image.new('L', (w, rows), 0); l, t, r, b = fnt.getbbox(txt)
    ImageDraw.Draw(im).text((2, (rows - (b - t)) // 2 - t), txt, font=fnt, fill=255)
    return np.asarray(im).astype(f32) / 255
S_ = '  ★  '
RIB_TXT = text_strip('UNITED ROAD' + S_ + 'MANCHESTER UNITED' + S_ + 'OLD TRAFFORD' + S_ + 'THEATRE OF DREAMS' + S_, 32)
BRD_TXT = text_strip('THEATRE OF DREAMS' + S_ + 'UNITED ROAD' + S_ + 'MANCHESTER UNITED' + S_ + 'OLD TRAFFORD' + S_, 32)
RIB_MPP = 1.4 / 32; BRD_MPP = 0.95 / 32

def pitch_lines(x, z):
    ax, az = np.abs(x), np.abs(z)
    def seg_h(zc, x0, x1):
        dx = np.maximum(np.maximum(x0 - ax, ax - x1), 0); return np.hypot(dx, az - zc)
    def seg_v(xc, z0, z1):
        dz = np.maximum(np.maximum(z0 - az, az - z1), 0); return np.hypot(ax - xc, dz)
    d = seg_h(34, 0, 52.5)
    for v in (seg_v(52.5, 0, 34), seg_v(0, 0, 34), seg_h(20.16, 36, 52.5), seg_v(36, 0, 20.16), seg_h(9.16, 47, 52.5), seg_v(47, 0, 9.16)):
        d = np.minimum(d, v)
    r = np.hypot(x, z); d = np.minimum(d, np.abs(r - 9.15)); d = np.where(r < 0.22, 0, d)
    rp = np.hypot(ax - 41.5, az)
    d = np.minimum(d, np.where(ax < 36, np.abs(rp - 9.15), 99)); d = np.where(rp < 0.15, 0, d)
    rc = np.hypot(ax - 52.5, az - 34); d = np.minimum(d, np.where((ax <= 52.5) & (az <= 34), np.abs(rc - 1), 99))
    return d
def sdf_rr(x, z, a, b, r):
    qx = np.abs(x) - (a - r); qz = np.abs(z) - (b - r)
    return np.hypot(np.maximum(qx, 0), np.maximum(qz, 0)) + np.minimum(np.maximum(qx, qz), 0) - r
def sstep(x): x = np.clip(x, 0, 1); return x * x * (3 - 2 * x)
def band(x, a, b, w):
    """soft membership of x in [a,b] with filter width w (anti-aliasing)"""
    w = np.maximum(w, 1e-4)
    return np.clip((x - a) / w + 0.5, 0, 1) * np.clip((b - x) / w + 0.5, 0, 1)


# ================================================================== fonts
ANTON = font_path('Anton-Regular.ttf', '/usr/share/fonts/truetype/google-fonts/ofl/anton/Anton-Regular.ttf')
ARCHIVO = font_path('ArchivoBlack-Regular.ttf', '/usr/share/fonts/truetype/google-fonts/ofl/archivoblack/ArchivoBlack-Regular.ttf')
BOARD_H = 1.1

# ================================================================== song structure + lyrics
# Timings come from lyrics_timing.json (written by align_lyrics.py with Whisper, or the bundled estimate).
TIMING_FILE = os.environ.get('LYRICS_TIMING', os.path.join(HERE, 'lyrics_timing.json'))
with open(TIMING_FILE) as fh:
    _TJ = json.load(fh)
S = _TJ['sections']
LYR = []   # (t0, t1, TEXT, card word or None, kind)
for e in _TJ['lines']:
    LYR.append((float(e['start']), float(e['end']) if e.get('end') is not None else DUR + 1, e['text'].upper(), e.get('card'), e['kind']))
LYR.sort(key=lambda e: e[0])
SECT = [(0, S['verse1'], 'chorus'), (S['verse1'], S['chorus2'], 'verse'), (S['chorus2'], S['verse2'], 'chorus'),
        (S['verse2'], S['chorus3'], 'verse'), (S['chorus3'], S['inst'], 'chorus'), (S['inst'], S['bridge'], 'inst'),
        (S['bridge'], S['build'], 'bridge'), (S['build'], S['final'], 'build'), (S['final'], S['outro'], 'final'),
        (S['outro'], S['lastchorus'], 'outro'), (S['lastchorus'], DUR + 1, 'chorus')]
def section(t):
    for a, b, k in SECT:
        if a <= t < b: return k, a, b
    return 'chorus', 0, 1
def lyric_at(t):
    for e in LYR:
        if e[0] <= t < e[1]: return e
    k, a, b = section(t)
    if k == 'inst': return (a, b, 'UNITED ROAD', 'UNITED ROAD', 'inst')
    if k == 'build': return (a, b, 'OLD TRAFFORD', None, 'build')
    return None
DROPS = [snap(S['chorus2']), snap(S['chorus3']), snap(S['final']), snap(S['lastchorus'])]
KICKOFF = snap(S['verse1'])
def is_dim(t): return section(t)[0] in ('bridge', 'outro')

# ================================================================== lyric strips (LED boards / ribbons)
SROWS = 56
_strip = {}
def lyric_strip(txt):
    """white-on-transparent strip of the line + a separator, with word end positions"""
    if txt in _strip: return _strip[txt]
    fnt = ImageFont.truetype(ANTON, int(SROWS * 0.80))
    sep = '   ●   '
    full = txt + sep
    dr = ImageDraw.Draw(Image.new('L', (1, 1)))
    w = int(dr.textlength(full, font=fnt)) + 2
    im = Image.new('L', (w, SROWS), 0)
    l, t_, r, b = fnt.getbbox('HG')
    ImageDraw.Draw(im).text((0, (SROWS - (b - t_)) // 2 - t_), full, font=fnt, fill=255)
    a = np.asarray(im).astype(f32) / 255
    # word ends (pixel x where each word finishes)
    ends = []; acc = ''
    for wd in txt.split(' '):
        acc = (acc + ' ' + wd).strip(); ends.append(dr.textlength(acc, font=fnt))
    tw = dr.textlength(txt, font=fnt)
    _strip[txt] = (a, np.array(ends, f32), tw)
    return _strip[txt]
for e in LYR: lyric_strip(e[2])
lyric_strip('UNITED ROAD'); lyric_strip('OLD TRAFFORD')

def reveal_px(e, t):
    a, ends, tw = lyric_strip(e[2])
    d = e[1] - e[0]; u = (t - e[0]) / max(0.4, min(d, 4.0) * 0.55)
    k = int(np.clip(u * len(ends), 0, len(ends)))
    return ends[k - 1] + 3 if k > 0 else 0.0, k

# ================================================================== card display bitmaps (seat lettering)
_card = {}
def card_bitmap(word):
    if word in _card: return _card[word]
    rows = 24
    fnt = ImageFont.truetype(ARCHIVO, rows * 6)
    l, t_, r, b = fnt.getbbox(word)
    im = Image.new('L', (r - l + 12, (b - t_) + 12), 0)
    ImageDraw.Draw(im).text((6 - l, 6 - t_), word, font=fnt, fill=255)
    wcols = int(round(im.width / im.height * rows * 1.25))
    g = np.asarray(im.resize((wcols, rows), Image.BOX)) > 110
    full = np.zeros((33, wcols), bool); full[5:5 + rows] = g
    _card[word] = full
    return full

# ================================================================== dot matrix screens (Anton glyphs)
COLS, ROWS, CELL = 96, 34, 7
_dot = np.zeros((CELL, CELL), f32); cv2.circle(_dot, (3, 3), 2, 1.0, -1, lineType=cv2.LINE_AA)
AMBER = np.array([1.0, 0.60, 0.12], f32)
_tc = {}
def text_grid(txt, rows):
    key = (txt, rows)
    if key in _tc: return _tc[key]
    fnt = ImageFont.truetype(ANTON, int(rows * 1.15) * 4)
    l, t, r, b = fnt.getbbox(txt + 'H')
    im = Image.new('L', (int(ImageDraw.Draw(Image.new('L', (1, 1))).textlength(txt, font=fnt)) + 8, rows * 4), 0)
    ImageDraw.Draw(im).text((4, (rows * 4 - (b - t)) // 2 - t), txt, font=fnt, fill=255)
    g = (np.asarray(im.resize((max(1, im.width // 4), rows), Image.BOX)).astype(f32) / 255 > 0.38).astype(f32)
    _tc[key] = g; return g
def fit(txt):
    r = ROWS; g = text_grid(txt, r)
    while g.shape[1] > COLS - 4 and r > 9:
        r -= 1; g = text_grid(txt, r)
    return g
def place(grid, g, x0, y0=0):
    h, w = g.shape; xs0, xs1 = max(0, x0), min(COLS, x0 + w)
    if xs1 > xs0: grid[y0:y0 + h, xs0:xs1] = np.maximum(grid[y0:y0 + h, xs0:xs1], g[:, xs0 - x0:xs1 - x0])
def center(grid, g): place(grid, g, (COLS - g.shape[1]) // 2, (ROWS - g.shape[0]) // 2)
def board_state(i):
    t = i / FPS; g = np.zeros((ROWS, COLS), f32)
    if t < 1.2: return g, 0.0
    if t < 2.4:
        r = np.random.default_rng(i).random((ROWS, COLS)); return (r > 0.3 + 0.7 * (t - 1.2) / 1.2).astype(f32), 0.8
    e = lyric_at(t)
    if t > DUR - 2.5: center(g, fit('UNITED ROAD')); return g, 1.0
    if e is None: center(g, fit('UNITED ROAD')); return g, 1.0
    sec = section(t)[0]
    if e[3] and sec in ('chorus', 'final', 'inst'):
        # big word, flashing on the beat, then spectrum underneath
        center(g, fit(e[3]))
        bp = beatpos(i); return g * (1 - 0.45 * (bp - math.floor(bp))), 1.0
    # scroll the current lyric line across the screen within its duration
    tg = text_grid(e[2], 24); w = tg.shape[1]
    u = (t - e[0]) / max(0.5, e[1] - e[0])
    x = int(COLS - u * (w + COLS) * 1.05)
    place(g, tg, x, (ROWS - 24) // 2)
    return g, 1.0

# ================================================================== visualizer bars
NB = 128
_bc = np.abs((np.arange(NB) - 64 + 0.5) * 3.0); _on = _bc / 95.5
_on = np.where(_on > 1, 2 - _on, _on).clip(0, 1)
BAND_POS = (_on * 45).astype(f32)
_BH = {}
def bar_heights(i):
    i = max(0, min(N - 1, i))
    if i in _BH: return _BH[i]
    h = np.interp(BAND_POS, np.arange(46), spec[i]).astype(f32)
    h = 0.25 * np.roll(h, 1) + 0.5 * h + 0.25 * np.roll(h, -1)
    h = np.clip((h - 0.18) / 0.82, 0, 1) ** 1.25
    _BH[i] = h.astype(f32)
    if len(_BH) > 64: _BH.pop(next(iter(_BH)))
    return _BH[i]
SEAT_STOPS = np.array([[0.0, 0.85, 0.03, 0.03], [0.45, 1.0, 0.10, 0.06], [0.8, 1.0, 0.32, 0.16], [1.0, 1.0, 0.8, 0.6]], f32)
LUT = np.stack([np.interp(np.linspace(0, 1, 256), SEAT_STOPS[:, 0], SEAT_STOPS[:, c]) for c in (1, 2, 3)], 1).astype(f32)

# ================================================================== light show
WHITE_L = np.array([1.0, 0.95, 0.86], f32); RED_L = np.array([1.0, 0.10, 0.07], f32)
def lamp_levels(i):
    """per-lamp white and red intensities, and the power-on base level"""
    t = i / FPS
    base = np.zeros(NL, f32)
    for g in range(8):
        ton = 0.9 + g * 0.42
        m = LAMP_GRP == g
        if t < ton: v = 0.0
        elif t < ton + 0.35: v = 1.0 if np.random.default_rng(i * 13 + g).random() > 0.45 else 0.12
        else: v = min(1.0, 0.75 + 0.25 * (t - ton - 0.35))
        toff = DUR - 2.6 + (7 - g) * 0.22
        if t > toff: v *= 0.0 if t > toff + 0.15 else 0.4
        base[m] = v
    pulse = 0.86 + 0.32 * float(bass[i])
    bp = beatpos(i); bi = int(math.floor(bp)); bf = bp - bi
    idx = np.arange(NL)
    sec, a, b = section(t)
    wv = np.ones(NL, f32) * pulse; rv = np.zeros(NL, f32)
    if sec == 'verse':
        p = ((i - snap(a)) / (4 * BPF) % 1.0) * NL
        dd = np.abs(((idx - p + NL / 2) % NL) - NL / 2)
        wv = pulse * (0.85 + 0.9 * np.exp(-(dd / 3.0) ** 2)).astype(f32)
    elif sec in ('chorus', 'final'):
        par = (idx + bi) % 2 == 0
        fl = (1 - bf) ** 2
        rv = np.where(par, 1.25 * fl, 0.0).astype(f32) * pulse
        wv = np.where(par, 0.35, 0.95).astype(f32) * pulse
        if sec == 'final' and (bi % 4 == 0):
            wv += 0.6 * fl
    elif sec == 'inst':
        p = ((i - snap(a)) / (2 * BPF) % 1.0) * NL
        dd = np.abs(((idx - p + NL / 2) % NL) - NL / 2)
        dd2 = np.abs(((idx - p - NL / 2 + NL / 2) % NL) - NL / 2)
        rv = 1.4 * (np.exp(-(dd / 3.5) ** 2) + np.exp(-(dd2 / 3.5) ** 2)).astype(f32)
        wv = np.full(NL, 0.7, f32) * pulse
    elif sec == 'bridge':
        wv[:] = 0.3
        p = (0.75 + (t - a) * 0.07) * NL
        dd = np.abs(((idx - p + NL / 2) % NL) - NL / 2)
        wv += 1.2 * np.exp(-(dd / 2.0) ** 2)
    elif sec == 'build':
        u = (t - a) / (b - a)
        rate = 1 + 3 * u
        p = (((t - a) * rate * 0.5) % 1.0) * NL
        dd = np.abs(((idx - p + NL / 2) % NL) - NL / 2)
        wv = (0.35 + 0.65 * u) * pulse + 1.0 * np.exp(-(dd / 4.0) ** 2)
        rv = np.full(NL, 0.5 * u, f32) * ((bi % 2) == 0)
    elif sec == 'outro':
        wv[:] = 0.36 + 0.08 * math.sin(t * 2.0)
    sd = since(DROPS, i)
    if sd < BPF * 2:
        st = 1.7 if (sd // 2) % 2 == 0 else 0.2
        wv = wv * st; rv = rv * st
    return (base * wv).astype(f32), (base * rv).astype(f32), base

RAIN_LEVEL = [(0, 0.7), (S['verse1'], 0.75), (S['verse2'], 1.15), (S['chorus3'], 0.9), (S['bridge'], 1.2), (S['final'], 1.0), (S['outro'], 1.25), (DUR + 1, 1.0)]
def rain_level(t):
    xs = [p[0] for p in RAIN_LEVEL]; ys = [p[1] for p in RAIN_LEVEL]
    return float(np.interp(t, xs, ys))

# ================================================================== camera path
def ease(u): u = min(1.0, max(0.0, u)); return u * u * (3 - 2 * u)
def lerp(a, b, u): return np.asarray(a, np.float64) + (np.asarray(b, np.float64) - np.asarray(a, np.float64)) * u
def look(pos, tgt, hfov): return np.asarray(pos, np.float64), np.asarray(tgt, np.float64), hfov
def perim_point(s, d, y):
    x, z, nx, nz = perim(np.array([s % L])); return np.array([x[0] + d * nx[0], y, z[0] + d * nz[0]]), np.array([nx[0], 0, nz[0]])
CUTS = [0.0, S['verse1'], S['chorus2'], S['verse2'], S['chorus3'], S['inst'], S['bridge'], S['final'], S['outro'], S['lastchorus'], DUR + 1]
def cam_at(t):
    k = max(j for j in range(len(CUTS) - 1) if CUTS[j] <= t)
    a, b = CUTS[k], min(CUTS[k + 1], DUR); u = (t - a) / (b - a); e = ease(u)
    if k == 0:   # lights on, low at the centre circle, slow crane up
        return look(lerp((0, 1.7, -6), (0, 10, -16), e), lerp((0, 27, 60), (0, 18, 60), e), 82)
    if k == 1:   # verse 1: slow 360 from the centre spot
        yaw = math.radians(-20 + 380 * u)
        pos = (6 * math.sin(yaw * 0.5), 6.5 + 2.5 * math.sin(u * math.pi), -4 + 4 * math.cos(yaw * 0.5))
        tgt = (pos[0] + math.sin(yaw) * 60, 17 + 3 * math.sin(u * 6.28), pos[2] + math.cos(yaw) * 60)
        return look(pos, tgt, 80)
    if k == 2:   # chorus 2: crane up and swing round
        az = math.radians(-90 + 55 * e); rad = 14 + 30 * e
        pos = (rad * math.cos(az), 2.0 + 28 * e, rad * math.sin(az))
        tgt = (-pos[0] * 0.6, 16 - 2 * e, -pos[2] * 0.6 + 10)
        return look(pos, tgt, 84 - 6 * e)
    if k == 3:   # verse 2: track along the north touchline
        x = -46 + 92 * e
        return look((x, 3.5 + 5 * math.sin(u * math.pi), 27), (x + 26 - 14 * e, 18, 64), 80)
    if k == 4:   # chorus 3: high orbit from the south side
        s = HALF - 20 + u * 75
        pos, n = perim_point(s, -12, 18 - 3 * math.sin(u * math.pi))
        tgt = (-pos[0] * 0.5, 14, -pos[2] * 0.5 + 6)
        return look(pos, tgt, 80)
    if k == 5:   # instrumental: low and fast along the boards
        s0 = (SEC0[4] + LS * 0.7) + u * 85
        pos, n = perim_point(s0, -7.5, 2.4 + 1.2 * u)
        pa, _ = perim_point(s0 + 14, -2, 2.4)
        return look(pos, pa + n * 6 + np.array([0, 9, 0]), 82)
    if k == 6:   # bridge: low, looking up at the north stand in the rain
        return look(lerp((0, 1.5, 2), (0, 2.6, 13), e), (0, 30, 70), 84)
    if k == 7:   # final chorus: big crane and orbit
        az = math.radians(-100 + 110 * e); rad = 10 + 34 * e
        pos = (rad * math.cos(az), 1.8 + 30 * e, rad * math.sin(az))
        tgt = (-pos[0] * 0.55, 15, -pos[2] * 0.55 + 6)
        return look(pos, tgt, 84 - 6 * e)
    if k == 8:   # outro: low in the rain, slow drift
        return look(lerp((-22, 1.3, 8), (-9, 1.9, 15), e), lerp((8, 21, 70), (14, 19, 70), e), 78)
    return look(lerp((0, 6, -18), (0, 26, -58), e), lerp((0, 22, 40), (0, 14, 25), e), lerp(80, 76, e))

def camera(t):
    pos, tgt, hfov = cam_at(t)
    f = tgt - pos; f /= np.linalg.norm(f)
    r = np.cross([0, 1.0, 0], f); r /= np.linalg.norm(r); u = np.cross(f, r)
    fp = (W / 2) / math.tan(math.radians(hfov) / 2)
    return pos, f, r, u, fp

# ================================================================== rasterise quad ids (painter's)
NEAR = 0.3
def clip2d(poly, x0, y0, x1, y1):
    for (ax, lim, keep_gt) in ((0, x0, True), (0, x1, False), (1, y0, True), (1, y1, False)):
        out = []; n = len(poly)
        if n == 0: return []
        for j in range(n):
            a, b = poly[j], poly[(j + 1) % n]
            ina = a[ax] >= lim if keep_gt else a[ax] <= lim
            inb = b[ax] >= lim if keep_gt else b[ax] <= lim
            if ina: out.append(a)
            if ina != inb:
                tt = (lim - a[ax]) / (b[ax] - a[ax]); out.append((a[0] + tt * (b[0] - a[0]), a[1] + tt * (b[1] - a[1])))
        poly = out
    return poly

def raster_ids(cam):
    C, f, r, u, fp = cam
    ids = np.full((H, W), -1, np.int32)
    D = Q - C
    zc = D @ f; xc = D @ r; yc = D @ u
    allin = (zc > NEAR).all(1); anyin = (zc > NEAR).any(1)
    with np.errstate(divide='ignore', invalid='ignore'):
        sx = W / 2 + fp * xc / zc; sy = H / 2 - fp * yc / zc
    offs = allin & ((sx.max(1) < 0) | (sx.min(1) > W) | (sy.max(1) < 0) | (sy.min(1) > H))
    vis = anyin & ~offs
    dist = np.linalg.norm(QCEN - C, axis=1)
    order = np.lexsort((-dist, LATE))
    SHIFT = 4; SC = 1 << SHIFT
    for q in order:
        if not vis[q]: continue
        if allin[q]:
            poly = list(zip(sx[q], sy[q]))
        else:
            V = Q[q]; z = zc[q]; P3 = []
            for j in range(4):
                a, b = V[j], V[(j + 1) % 4]; za, zb = z[j], z[(j + 1) % 4]
                if za > NEAR: P3.append(a)
                if (za > NEAR) != (zb > NEAR):
                    tt = (NEAR - za) / (zb - za); P3.append(a + tt * (b - a))
            if len(P3) < 3: continue
            P3 = np.array(P3) - C; pz_ = P3 @ f
            poly = list(zip(W / 2 + fp * (P3 @ r) / pz_, H / 2 - fp * (P3 @ u) / pz_))
        xs = [p[0] for p in poly]; ys = [p[1] for p in poly]
        if min(xs) < -1500 or max(xs) > W + 1500 or min(ys) < -1500 or max(ys) > H + 1500:
            poly = clip2d(poly, -50, -50, W + 50, H + 50)
            if len(poly) < 3: continue
        cv2.fillPoly(ids, [np.round(np.array(poly) * SC).astype(np.int32)], int(q), shift=SHIFT)
    return ids

# ================================================================== rain
ND = 7000
_rr = np.random.default_rng(77)
RAIN_H = _rr.random((ND, 4)).astype(np.float64)
RAIN_SPEED = 9.0 + 3.0 * _rr.random(ND)
RAIN_BOX = 56.0
def rain_layer(cam, t, dist, level):
    """returns (H,W) float rain-streak mask with per-depth weights"""
    C, f, r, u, fp = cam
    wind = 2.2
    bx = RAIN_H[:, 0] * RAIN_BOX + wind * t; bz = RAIN_H[:, 1] * RAIN_BOX
    x = C[0] - RAIN_BOX / 2 + np.mod(bx - (C[0] - RAIN_BOX / 2), RAIN_BOX)
    z = C[2] - RAIN_BOX / 2 + np.mod(bz - (C[2] - RAIN_BOX / 2), RAIN_BOX)
    y = 52 - np.mod(RAIN_H[:, 2] * 52 + RAIN_SPEED * t, 52)
    n_use = int(ND * min(1.0, level))
    P0 = np.stack([x, y, z], 1)[:n_use]
    vel = np.stack([np.full(ND, wind), -RAIN_SPEED, np.zeros(ND)], 1)[:n_use]
    P1 = P0 - vel * (1.6 / 30)
    def pj(P):
        d = P - C; zc = d @ f
        return W / 2 + fp * (d @ r) / zc, H / 2 - fp * (d @ u) / zc, zc
    x0, y0, z0 = pj(P0); x1, y1, z1 = pj(P1)
    ok = (z0 > 0.6) & (z1 > 0.6) & (np.maximum(x0, x1) > 0) & (np.minimum(x0, x1) < W) & (np.maximum(y0, y1) > 0) & (np.minimum(y0, y1) < H)
    # depth test against the scene
    xm = np.clip(((x0 + x1) / 2).astype(int), 0, W - 1); ym = np.clip(((y0 + y1) / 2).astype(int), 0, H - 1)
    ok &= np.linalg.norm(P0 - C, axis=1) < dist[ym, xm]
    out = np.zeros((H, W), f32)
    dd = np.linalg.norm(P0 - C, axis=1)
    for lo, hi, thick, wgt in ((0, 7, 2, 0.55), (7, 16, 1, 0.42), (16, 99, 1, 0.22)):
        m = ok & (dd >= lo) & (dd < hi)
        if not m.any(): continue
        segs = np.stack([np.stack([x0[m], y0[m]], 1), np.stack([x1[m], y1[m]], 1)], 1)
        segs = np.clip(segs * 16, -30000, 30000).astype(np.int32)
        lay = np.zeros((H, W), np.uint8)
        cv2.polylines(lay, list(segs), False, 255, thick, lineType=cv2.LINE_AA, shift=4)
        out += lay.astype(f32) * (wgt / 255)
    return out

# rain streak texture for the distant rain inside the light beams
_rt = np.zeros((256, 256), f32)
for k in range(900):
    xx = _rr.integers(0, 256); yy = _rr.integers(0, 256); ln = _rr.integers(6, 18)
    cv2.line(_rt, (int(xx), int(yy)), (int(xx + ln * 0.18), int(yy + ln)), float(0.5 + 0.5 * _rr.random()), 1)
RAIN_TEX = cv2.GaussianBlur(np.tile(_rt, (2, 2)), (0, 0), 0.6)[0:256, 0:256]

# ================================================================== frame
_xs = ((np.arange(W) + 0.5 - W / 2)).astype(f32); _ys = ((np.arange(H) + 0.5 - H / 2)).astype(f32)
yy_, xx_ = np.mgrid[0:H, 0:W].astype(f32)
VIG = (1 - 0.5 * (((xx_ - W / 2) / (W / 1.5)) ** 2 + ((yy_ - H / 2) / (H / 1.2)) ** 2)).clip(0.3, 1)[..., None].astype(f32)
del yy_, xx_
SCAN = np.where(np.arange(H) % 2 == 0, 1.0, 0.955).astype(f32)[:, None, None]
GRAIN = [rng0.normal(0, 0.016, (H, W, 1)).astype(f32) for _ in range(5)]
HZ = 5
HAZE_W = np.array([1.0, 0.88, 0.74], f32); HAZE_R = np.array([1.0, 0.13, 0.08], f32)

def shoulder(x):
    return np.where(x < 0.72, x, 0.72 + 0.28 * (1 - np.exp(-(x - 0.72) / 0.28))).astype(f32)

def led_text(aa, vv, fw, e, t, i, style):
    """colour of LED-board pixels showing lyric line e. aa: metres along the board, vv: 0 bottom..1 top"""
    n = len(aa)
    if e is None:
        return np.tile(np.array([0.5, 0.02, 0.03], f32), (n, 1))
    a, ends, tw = lyric_strip(e[2])
    mpp = BOARD_H / SROWS if style == 'board' else 1.4 / SROWS
    cc = (np.floor(aa / mpp) % a.shape[1]).astype(np.int32)
    ro = ((1 - vv) * (SROWS - 0.01)).astype(np.int32).clip(0, SROWS - 1)
    tx = a[ro, cc]
    rp, k = reveal_px(e, t)
    tx = tx * (cc < rp)
    # freshly revealed word flashes
    bp = beatpos(i); bf = bp - math.floor(bp)
    sec = section(t)[0]
    if sec in ('chorus', 'final'):
        bg = np.array([0.75, 0.03, 0.04], f32) * (0.75 + 0.5 * (1 - bf) ** 2)
        fg = np.array([1.6, 1.52, 1.45], f32)
    elif sec in ('bridge', 'outro'):
        bg = np.array([0.06, 0.005, 0.008], f32); fg = np.array([1.35, 0.25, 0.18], f32)
    else:
        bg = np.array([0.5, 0.02, 0.03], f32); fg = np.array([1.45, 1.4, 1.32], f32)
    age = t - e[0]
    if age < 0.12: fg = fg * 1.6
    col = bg[None] * (1 - tx[:, None]) + fg[None] * tx[:, None]
    # LED pixel texture when close
    pitch_m = 0.025
    amp = np.clip(1 - fw / pitch_m, 0, 1) * 0.35
    led = 1 - amp * (0.5 - 0.5 * np.cos(aa / pitch_m * 2 * np.pi)) * (0.5 - 0.5 * np.cos(vv * BOARD_H / pitch_m * 2 * np.pi))
    return col * led[:, None]

def frame(i):
    t = i / FPS
    cam = camera(t)
    C, f, r, u, fp = cam
    Cf = C.astype(f32)
    lw, lr, lbase = lamp_levels(i)
    lv = lw + lr
    redfrac = float(lr.sum() / max(lv.sum(), 1e-3))
    Lk = float(np.clip(lbase.mean() * (0.92 + 0.12 * float(bass[i])) * (0.72 if is_dim(t) else 1.0), 0, 1.2))
    Lcol = (np.array([1, 1, 1], f32) * (1 - 0.45 * redfrac) + np.array([1.0, 0.25, 0.2], f32) * 0.45 * redfrac).astype(f32)
    sec, sa, sb = section(t)
    ly = lyric_at(t)
    ids = raster_ids(cam)
    dirs = (f.astype(f32)[None, None] + (_xs / fp)[None, :, None] * r.astype(f32)[None, None] - (_ys / fp)[:, None, None] * u.astype(f32)[None, None])
    dirs /= np.linalg.norm(dirs, axis=-1, keepdims=True)
    img = np.zeros((H, W, 3), f32)
    dist = np.full((H, W), 600, f32)
    Pbuf = np.zeros((H, W, 3), f32)
    flat = img.reshape(-1, 3); dflat = dist.reshape(-1); D = dirs.reshape(-1, 3); idf = ids.reshape(-1); Pf = Pbuf.reshape(-1, 3)
    tri = np.flatnonzero(idf >= 0)
    rest = np.flatnonzero(idf < 0)
    gnd = rest[D[rest, 1] < -1e-4]; sky = rest[D[rest, 1] >= -1e-4]
    wet = np.zeros(H * W, f32)
    # ------------------------------------------------ ground (wet grass)
    if len(gnd):
        dg = D[gnd]; lam = -Cf[1] / dg[:, 1]
        x = Cf[0] + dg[:, 0] * lam; z = Cf[2] + dg[:, 2] * lam
        dflat[gnd] = lam
        fw = lam / fp / np.maximum(np.abs(dg[:, 1]), 0.08) ** 0.75
        inpitch = (np.abs(x) <= 52.5) & (np.abs(z) <= 34)
        sxv = np.mod((x + 52.5) / 5.25, 2.0); stx = np.clip(band(sxv, 0, 1, fw / 5.25), 0, 1)
        szv = np.mod((z + 34) / 6.8, 2.0); stz = np.clip(band(szv, 0, 1, fw / 6.8), 0, 1)
        nb = samp(NOISE_BIG, x * 4, z * 4); nf = samp(NOISE_FINE, x * 40, z * 40) * np.clip(1 - fw * 4, 0, 1)
        g = np.array([0.07, 0.24, 0.062], f32)[None] * ((0.84 + 0.30 * stx) * (0.97 + 0.06 * stz) * (1 + 0.06 * nb + 0.07 * nf))[:, None]
        g[~inpitch] *= 0.82
        E = 1.25 - 0.35 * ((x / 64) ** 2 + (z / 48) ** 2)
        ld = pitch_lines(x, z)
        lc = (np.clip((0.06 - ld) / np.maximum(fw, 0.02) + 0.5, 0, 1) * np.minimum(1, 0.14 / np.maximum(fw, 1e-3))).astype(f32)
        g = g * (1 - lc[:, None]) + np.array([0.72, 0.76, 0.70], f32)[None] * lc[:, None]
        sd = sdf_rr(x, z, A - 4, B - 4, RC - 4)
        out_ = sd > 0
        g[out_] = np.array([0.018, 0.02, 0.022], f32)
        E = E * (0.75 + 0.25 * np.clip(-sd / 6, 0, 1))
        flat[gnd] = g * (E * Lk)[:, None] * Lcol[None]
        cosv = np.abs(dg[:, 1])
        fres = 0.03 + 0.97 * (1 - cosv) ** 5
        wet[gnd] = np.where(out_, 0.3 + 0.7 * fres, (0.17 + 0.83 * fres) * (0.6 + 0.25 * nb.clip(-1, 1))) * 1.15
    # ------------------------------------------------ geometry
    q = idf[tri]; dv = D[tri]
    nq = QN[q]
    den = np.einsum('ij,ij->i', nq, dv)
    den = np.where(np.abs(den) < 1e-5, 1e-5, den)
    lam = np.clip((QD[q] - nq @ Cf) / den, 0.05, 2000).astype(f32)
    P = Cf[None] + dv * lam[:, None]
    dflat[tri] = lam; Pf[tri] = P
    mat = QMAT[q]
    fwm = lam / fp / np.maximum(np.abs(den), 0.12)
    stand = mat < M_LAMP
    col = np.zeros((len(tri), 3), f32); emi = np.zeros((len(tri), 3), f32)
    si = np.flatnonzero(stand | (mat == M_BOARD))
    if len(si):
        ss, dd = plan_inverse(P[si, 0], P[si, 2])
        ms = mat[si]; pr = QPRF[q[si]]
        d0, y0, d1, y1 = PRF_ARR[pr, 0], PRF_ARR[pr, 1], PRF_ARR[pr, 2], PRF_ARR[pr, 3]
        vert = np.abs(d1 - d0) < 1e-3
        tt = np.where(vert, (P[si, 1] - y0) / np.maximum(y1 - y0, 1e-3), (dd - d0) / np.where(vert, 1, d1 - d0))
        tt = np.clip(tt, 0, 0.99999).astype(f32)
        aa = (ss + dd * turn_of(ss)).astype(f32)
        fw = fwm[si]
        cs = np.zeros((len(si), 3), f32); es = np.zeros((len(si), 3), f32)
        H_ = bar_heights(i)
        gain = 0.0
        if t > 2.5:
            gain = 1.0
            if t < S['verse1']: gain = min(1.0, (t - 2.5) / 3) * 0.9
            if t > DUR - 4.0: gain *= max(0.0, (DUR - 1.2 - t) / 2.8)
            if sec in ('bridge', 'outro'): gain *= 0.3
        PK = np.max(np.stack([bar_heights(j) - 0.02 * (i - j) for j in range(max(0, i - 22), i + 1)]), 0) * gain
        card = ly[3] if (ly is not None and ly[3] and sec in ('chorus', 'final', 'inst') and t > 2.0) else None
        bp = beatpos(i); bi = int(math.floor(bp)); bf = bp - bi
        for mm in (M_SEAT0, M_SEAT1, M_SEAT2):
            m = ms == mm
            if not m.any(): continue
            nr = NROWS[mm]; tier = mm - M_SEAT0
            tr = tt[m] * nr; row = np.floor(tr); vf = tr - row
            ac = aa[m] / 0.5; cidx = np.floor(ac); uf = ac - cidx
            sg = ss[m]
            rowd = (26.3, 17.4, 28.6)[tier] / nr
            wu = fw[m] / 0.5; wv = fw[m] / rowd
            aisle = band(np.mod(sg, 12.0), -0.2, (1.15, 1.0, 0.65)[tier], fw[m] * 0.8)
            vstart = (10, 6, 9)[tier]
            vom = ((np.mod(sg + 6, 24.0) < 3.6) & (row >= vstart) & (row < vstart + 5) & (tier == 0)).astype(f32)
            shp = band(uf, 0.1, 0.9, wu) * band(vf, 0.26, 0.93, wv)
            mix_ = np.clip(np.maximum(wu, wv) * 1.5 - 0.5, 0, 1)
            shp = shp * (1 - mix_) + 0.53 * mix_
            shp = shp * (1 - aisle) * (1 - vom)
            Es = (0.30, 0.24, 0.19)[tier] * (0.8 + 0.2 * (1 - tt[m]))
            hs = hash2(cidx, row + tier * 100)
            red = np.array([0.50, 0.03, 0.04], f32)[None] * (0.88 + 0.24 * hs)[:, None]
            # wet seat backs catch the light
            glint = (band(vf, 0.26, 0.34, wv) * (1 - mix_) * (0.6 + 0.8 * hs))[:, None] * np.array([0.35, 0.33, 0.32], f32)[None]
            conc = np.array([0.04, 0.04, 0.042], f32)[None]
            wh = np.zeros(m.sum(), bool)
            if mm == M_SEAT2:
                Px = P[si[m], 0]; far = (P[si[m], 2] > 30) & (np.abs(Px) < LS)
                lr_ = (LET_ROW0 + LETTERS.shape[0] - 1) - row
                lc_ = np.floor(Px / 0.5).astype(int) + LETTERS.shape[1] // 2
                ok = far & (lr_ >= 0) & (lr_ < LETTERS.shape[0]) & (lc_ >= 0) & (lc_ < LETTERS.shape[1])
                wh[ok] = LETTERS[lr_[ok].astype(int), lc_[ok]]
                red[wh] = np.array([0.62, 0.62, 0.64], f32)
            stp = np.array([0.10, 0.10, 0.10], f32)[None]
            c = (red + glint) * shp[:, None] + conc * (1 - shp[:, None]) * (1 - aisle[:, None]) + stp * aisle[:, None] * (1 - vom[:, None])
            c = c * (1 - vom[:, None]) + np.array([0.006, 0.006, 0.008], f32)[None] * vom[:, None]
            cs[m] = c * Es[:, None]
            if gain <= 0: continue
            lvl = ((CUMR[mm] + row + 0.5) / TOTROWS).astype(f32)
            bar = np.clip(np.floor(sg / 3.0) + 64, 0, NB - 1).astype(np.int32)
            if card is not None and mm == M_SEAT2:
                # card display: the whole upper tier spells the chorus word round the bowl
                bm = card_bitmap(card); wc = bm.shape[1]; period = wc + 40
                crel = (cidx.astype(np.int64) + wc // 2) % period
                inw = crel < wc
                rr_ = (32 - row).astype(np.int64).clip(0, 32)
                letter = np.zeros(m.sum(), bool)
                letter[inw] = bm[rr_[inw], crel[inw]]
                fresh = np.clip(1 - (t - ly[0]) / 0.25, 0, 1)
                bgc = np.array([0.95, 0.035, 0.04], f32) * (0.55 + 0.55 * (1 - bf) ** 2)
                fgc = np.array([1.7, 1.6, 1.5], f32) * (1.0 + 0.6 * fresh)
                e_ = np.where(letter[:, None], fgc[None], bgc[None]) * shp[:, None]
                es[m] = e_
                continue
            hb = H_[bar] * gain
            if card is not None: hb = hb * (51.0 / 84.0)   # bars stay in the lower two tiers under the card display
            on = lvl < hb
            tip = np.clip(1 - (hb - lvl) / 0.07, 0, 1)
            inten = on * (1.2 + 1.2 * tip) * (0.85 + 0.3 * float(bass[i])) * shp
            cl = LUT[(np.clip(lvl, 0, 1) * 255).astype(np.int32)]
            cl[wh] = np.array([1.0, 0.92, 0.88], f32)
            e_ = cl * inten[:, None]
            pk = PK[bar] * ((51.0 / 84.0) if card is not None else 1.0)
            near = (np.abs(lvl - pk) < 0.6 / 84) & (pk > 0.05)
            e_ += np.array([1.6, 1.45, 1.3], f32) * (near * shp)[:, None]
            if sec == 'bridge' or (sec == 'build' and t - sa < 4):
                pos = (38 + (t - S['bridge']) * 10.0) % NB
                dbar = np.abs(((bar - pos + NB / 2) % NB) - NB / 2)
                w_ = np.exp(-(dbar / 2.2) ** 2) * (lvl < 0.97) * shp
                e_ += np.array([1.4, 0.9, 0.6], f32) * w_[:, None]
            es[m] = e_
        m = ms == M_WALL; cs[m] = np.array([0.04, 0.04, 0.044], f32)
        m = ms == M_BACK; cs[m] = np.array([0.02, 0.02, 0.024], f32)
        m = ms == M_BOX
        if m.any():
            am = aa[m]; tv = tt[m]; pid = np.floor(am / 2.5)
            mull = 1 - band(np.mod(am, 2.5), 0.3, 2.5, fw[m]) * band(tv, 0.22, 0.9, fw[m] / 3.3)
            on = hash2(pid, dd[m]) < 0.55
            warm = np.array([0.16, 0.085, 0.03], f32)[None] * (0.35 + 0.65 * hash2(pid + 7, dd[m]))[:, None]
            es[m] = warm * ((1 - mull) * on * (0.55 + 0.45 * tv))[:, None]
            cs[m] = np.array([0.035, 0.035, 0.04], f32)[None] * mull[:, None] + np.array([0.008, 0.01, 0.016], f32)[None] * (1 - mull)[:, None]
        m = ms == M_ROOF
        if m.any():
            am = aa[m]; bm_ = tt[m] * 52; fr = fw[m]
            truss = band(np.mod(am, 12), -0.1, 0.55, fr); purl = band(np.mod(bm_, 4.5), -0.1, 0.3, fr)
            fa = np.mod(am, 12) / 12; fb = np.mod(bm_, 4.5) / 4.5
            dg_ = np.maximum(band(fa - fb, -0.03, 0.03, fr / 12), band(fa - (1 - fb), -0.03, 0.03, fr / 12))
            steel = np.clip(np.maximum(np.maximum(truss, purl), dg_), 0, 1)
            steel = steel * (1 - np.clip(fr - 0.3, 0, 1)) + 0.2 * np.clip(fr - 0.3, 0, 1)
            Er = 0.18 + 0.82 * (1 - tt[m]) ** 2.2
            cs[m] = (np.array([0.30, 0.31, 0.34], f32)[None] * steel[:, None] + np.array([0.03, 0.032, 0.038], f32)[None] * (1 - steel)[:, None]) * Er[:, None]
        m = ms == M_FASCIA
        if m.any():
            seam = band(np.mod(aa[m], 6), -0.05, 0.1, fw[m])
            cs[m] = (np.array([0.40, 0.41, 0.44], f32)[None] * (1 - seam)[:, None] + 0.12 * seam[:, None]) * (0.55 + 0.45 * (1 - tt[m]))[:, None]
        # --- LED ribbons: lower ring = the lyric, upper ring = light patterns
        on_k = min(1.0, max(0.0, (t - 0.25) * 4))
        if t > DUR - 2.0: on_k *= max(0.0, (DUR - 0.8 - t) / 1.2)
        m = ms == M_RIB0
        if m.any():
            es[m] = led_text(aa[m] + 0.0, tt[m], fw[m], ly, t, i, 'rib') * on_k * (0.85 + 0.25 * float(bass[i])); cs[m] = 0
        m = ms == M_RIB1
        if m.any():
            a2 = aa[m]
            if sec in ('verse', 'inst', 'build'):
                fill = float(np.clip(energy[i] * 1.1 + 0.25 * bass[i], 0, 1.2)) * 125
                onv = np.abs(a2) < fill
                edge = np.clip(1 - (fill - np.abs(a2)) / 25, 0, 1)[:, None]
                cx_ = np.where(onv[:, None], np.array([1.2, 0.06, 0.05], f32) * (1 - edge) + np.array([1.6, 1.3, 1.1], f32) * edge, np.array([0.08, 0.005, 0.008], f32))
            elif sec in ('chorus', 'final'):
                sgi = np.floor(np.abs(a2) / 4.0).astype(np.int32)
                hit = ((sgi - bi) % 4) == 0
                cx_ = np.where(hit[:, None], np.array([1.6, 1.5, 1.4], f32) * (1.2 - 0.5 * bf), np.array([0.7, 0.03, 0.04], f32))
            else:
                cx_ = led_text(a2, tt[m], fw[m], ly, t, i, 'rib') * 0.8
            es[m] = cx_ * on_k; cs[m] = 0
        # --- pitchside boards: the lyric
        m = ms == M_BOARD
        if m.any():
            es[m] = led_text(aa[m], tt[m], fw[m], ly, t, i, 'board') * on_k * (0.8 + 0.3 * float(bass[i])); cs[m] = 0
        sd_ = since(DROPS, i)
        if sd_ < 12:
            fl_ = np.array([1.8, 1.7, 1.6], f32) * (1 - sd_ / 12)
            rb = (ms == M_RIB0) | (ms == M_RIB1) | (ms == M_BOARD)
            es[rb] += fl_
        col[si] = cs; emi[si] = es
    lm = np.flatnonzero(mat == M_LAMP)
    if len(lm):
        lid = QEX[q[lm]]
        emi[lm] = WHITE_L[None] * (0.15 + 5.5 * lw[lid])[:, None] + RED_L[None] * (5.5 * lr[lid])[:, None]
    sm = np.flatnonzero(mat == M_SCREEN)
    if len(sm):
        grid, brs = board_state(i)
        tex = np.maximum(np.kron(grid * brs, _dot) * 1.6, np.kron(np.ones_like(grid), _dot) * 0.06)
        sid = QEX[q[sm]]
        uu = np.zeros(len(sm), f32); vv = np.zeros(len(sm), f32)
        for k_ in (0, 1):
            mk = sid == k_
            tl_, rt, up = SCREENS[k_]
            rel = P[sm[mk]] - tl_.astype(f32)
            uu[mk] = rel @ rt.astype(f32) / 22.0; vv[mk] = -(rel @ up.astype(f32)) / 7.5
        span = np.median(fwm[sm])
        tw = int(np.clip(22.0 / max(span, 1e-3) * 1.2, 24, tex.shape[1])); th = max(8, int(tw * tex.shape[0] / tex.shape[1]))
        tex = cv2.resize(tex, (tw, th), interpolation=cv2.INTER_AREA)
        val = tex[(np.clip(vv, 0, 0.999) * th).astype(int), (np.clip(uu, 0, 0.999) * tw).astype(int)]
        emi[sm] = val[:, None] * AMBER[None]
    col[mat == M_FRAME] = np.array([0.02, 0.02, 0.022], f32)
    flat[tri] = col * Lk * Lcol[None] + emi
    # ------------------------------------------------ sky
    if len(sky):
        ds = D[sky]; e = ds[:, 1]
        up = np.maximum(e, 0.015)
        cx = (Cf[0] + ds[:, 0] * (900 - Cf[1]) / up); cz = (Cf[2] + ds[:, 2] * (900 - Cf[1]) / up)
        k_ = np.clip(e * 2.2, 0, 1) ** 0.7
        base = np.array([0.026, 0.02, 0.028], f32)[None] * (1 - k_)[:, None] + np.array([0.004, 0.006, 0.014], f32)[None] * k_[:, None]
        glow = np.exp(-np.maximum(e, 0) / 0.2)[:, None] * (np.array([0.30, 0.10, 0.065], f32) * (1 + redfrac))[None] * Lk
        ux = cx / 650 + t * 0.02; uz = cz / 650
        dens = np.clip((0.75 * samp(CLOUD1, ux * 40, uz * 40) + 0.3 * samp(CLOUD2, ux * 90, uz * 90) + 0.25) / 1.1, 0, 1) ** 1.2
        dens *= np.clip(e / 0.05, 0, 1)
        rh = np.hypot(cx, cz)
        ccol = np.array([0.40, 0.19, 0.15], f32)[None] * (np.exp(-rh / 1600) * Lk)[:, None] + np.array([0.012, 0.012, 0.018], f32)[None]
        flat[sky] = base + glow * (1 - 0.4 * dens[:, None]) + dens[:, None] * ccol
    # ------------------------------------------------ wet reflections (forward-splatted mirror image at 1/4 res)
    r4w, r4h = W // 4, H // 4
    sub = (slice(2, None, 4), slice(2, None, 4))
    Ps = Pbuf[sub].reshape(-1, 3); Cs = img[sub].reshape(-1, 3); vs = (ids[sub].reshape(-1) >= 0)
    Ps = Ps[vs]; Cs = Cs[vs]
    if len(Ps):
        Pm = Ps.copy(); Pm[:, 1] = -Pm[:, 1]
        d_ = Pm - Cf[None]; zc = d_ @ f.astype(f32)
        okm = zc > 0.5
        px_ = (r4w / 2 + (fp / 4) * (d_[okm] @ r.astype(f32)) / zc[okm]).astype(np.int32)
        py_ = (r4h / 2 - (fp / 4) * (d_[okm] @ u.astype(f32)) / zc[okm]).astype(np.int32)
        cc_ = Cs[okm]
        inb = (px_ >= 0) & (px_ < r4w) & (py_ >= 0) & (py_ < r4h)
        refl = np.zeros((r4h, r4w, 3), f32); wgt = np.zeros((r4h, r4w), f32)
        np.add.at(refl, (py_[inb], px_[inb]), cc_[inb]); np.add.at(wgt, (py_[inb], px_[inb]), 1.0)
        refl = cv2.GaussianBlur(refl, (0, 0), sigmaX=1.2, sigmaY=4.5); wgt = cv2.GaussianBlur(wgt, (0, 0), sigmaX=1.2, sigmaY=4.5)
        refl = refl / np.maximum(wgt, 0.02)[..., None] * np.clip(wgt * 4, 0, 1)[..., None]
        refl = cv2.resize(refl, (W, H), interpolation=cv2.INTER_LINEAR)
        img += refl * wet.reshape(H, W)[..., None]
    # ------------------------------------------------ haze (white + red) and beams
    hw, hh = W // HZ, H // HZ
    Dh = cv2.resize(dirs, (hw, hh), interpolation=cv2.INTER_AREA).reshape(-1, 3)
    Dh /= np.linalg.norm(Dh, axis=1, keepdims=True)
    tmax = cv2.resize(np.minimum(dist, 600), (hw, hh), interpolation=cv2.INTER_AREA).reshape(-1, 1)
    V = (LAMP_P - Cf[None])
    tc = Dh @ V.T
    vv2 = (V * V).sum(1)[None]
    hh_ = np.sqrt(np.maximum(vv2 - tc * tc, 0.36))
    I = (np.arctan((tmax - tc) / hh_) - np.arctan(-tc / hh_)) / hh_
    tcl = np.clip(tc, 0, tmax)
    VA = (V * LAMP_AIM).sum(1)[None]; DA = Dh @ LAMP_AIM.T
    dl_dot = (-VA + tcl * DA) / np.sqrt(np.maximum(vv2 - 2 * tcl * tc + tcl * tcl, 0.36))
    fac = np.clip(dl_dot, 0, 1) ** 3 * 0.9 + 0.1
    nearw = (np.minimum(1.0, np.sqrt(vv2) / 55.0) ** 2)[0]
    Wm = np.stack([lw * nearw, lr * nearw], 1)
    hz2 = ((I * fac) @ Wm).reshape(hh, hw, 2).astype(f32)
    beams = np.zeros((hh, hw, 2), f32)
    yb, xb = np.mgrid[0:hh, 0:hw].astype(f32) + 0.5
    fpb = fp / HZ
    def proj(Pw):
        d_ = Pw - C; z_ = d_ @ f
        return (hw / 2 + fpb * (d_ @ r) / z_, hh / 2 - fpb * (d_ @ u) / z_, z_)
    for li in range(0, NL, 2):
        if lv[li] < 0.05: continue
        Lp = LAMP_P[li].astype(np.float64); aim = LAMPS[li][1]
        ax_ = aim - Lp; ln = np.linalg.norm(ax_)
        pts = Lp + np.outer(np.linspace(0, 1, 24), ax_)
        zc = (pts - C) @ f
        if zc[0] < 1.0: continue
        good = np.nonzero(zc > 1.0)[0]; fe = good[-1] / 23
        end = Lp + ax_ * fe
        wv_ = np.cross(ax_ / ln, Lp - C); wv_ /= np.linalg.norm(wv_)
        re = ln * fe * math.tan(math.radians(7.5))
        quad = np.array([Lp + wv_ * 0.5, Lp - wv_ * 0.5, end - wv_ * re, end + wv_ * re])
        x_, y_, _ = proj(quad)
        if not (np.isfinite(x_).all() and np.isfinite(y_).all()): continue
        x0b, x1b = max(0, int(x_.min())), min(hw, int(x_.max()) + 2); y0b, y1b = max(0, int(y_.min())), min(hh, int(y_.max()) + 2)
        if x1b <= x0b or y1b <= y0b: continue
        tmp = np.zeros((y1b - y0b, x1b - x0b), f32)
        cv2.fillPoly(tmp, [np.clip(np.stack([x_ - x0b, y_ - y0b], 1), -5000, 5000).round().astype(np.int32)], 1.0)
        lx_, ly_, _ = proj(np.array([Lp, end]))
        vx, vy = lx_[1] - lx_[0], ly_[1] - ly_[0]; l2 = vx * vx + vy * vy + 1e-6
        pr_ = np.clip(((xb[y0b:y1b, x0b:x1b] - lx_[0]) * vx + (yb[y0b:y1b, x0b:x1b] - ly_[0]) * vy) / l2, 0, 1)
        fall = tmp * (1 - pr_) ** 1.6 * np.clip(pr_ * 12, 0, 1) * (1 - 0.4 * fe) * min(1.0, np.linalg.norm(Lp - C) / 75.0) ** 2
        beams[y0b:y1b, x0b:x1b, 0] += fall * lw[li]; beams[y0b:y1b, x0b:x1b, 1] += fall * lr[li]
    beams = cv2.GaussianBlur(beams, (0, 0), 1.2)
    rl = rain_level(t)
    # distant rain streaks visible inside the light
    off = int(t * 260) % 256; offx = int(t * 40) % 256
    rt = np.roll(np.roll(RAIN_TEX, off, 0), offx, 1)
    reps = (int(math.ceil(hh / 256)) + 1, int(math.ceil(hw / 256)) + 1)
    rt = np.tile(rt, reps)[:hh, :hw]
    shim = (0.7 + 0.9 * rt * rl)[..., None]
    bk = 0.07 * (0.55 + 0.9 * float(bass[i])) * (0.5 if is_dim(t) else 1.0)
    air2 = hz2 * 0.0115 + beams * bk * shim
    air_w = cv2.resize(air2[..., 0], (W, H), interpolation=cv2.INTER_LINEAR)
    air_r = cv2.resize(air2[..., 1], (W, H), interpolation=cv2.INTER_LINEAR)
    img += air_w[..., None] * HAZE_W + air_r[..., None] * HAZE_R
    # ------------------------------------------------ rain streaks (world space, lit by the floodlights)
    rmask = rain_layer(cam, t, dist, rl)
    rlight = 0.08 * Lk + (air_w + air_r * 0.6) * 8.0
    img += (rmask * rlight)[..., None] * (np.array([0.85, 0.9, 1.0], f32) * (1 - 0.5 * redfrac) + np.array([1.0, 0.3, 0.25], f32) * 0.5 * redfrac)
    # splashes on the grass near the camera
    rs = np.random.default_rng(i * 7 + 3)
    ns = int(260 * rl)
    ang = rs.random(ns) * 2 * np.pi; rad = 2 + rs.random(ns) ** 0.5 * 26
    sx = C[0] + np.cos(ang) * rad; sz = C[2] + np.sin(ang) * rad
    d_ = np.stack([sx - C[0], -C[1] * np.ones(ns), sz - C[2]], 1); zc = d_ @ f
    okk = zc > 0.5
    px_ = (W / 2 + fp * (d_[okk] @ r) / zc[okk]).astype(int); py_ = (H / 2 - fp * (d_[okk] @ u) / zc[okk]).astype(int)
    inb = (px_ >= 1) & (px_ < W - 2) & (py_ >= 1) & (py_ < H - 2)
    px_, py_ = px_[inb], py_[inb]
    isg = ids[py_, px_] < 0
    px_, py_ = px_[isg], py_[isg]
    img[py_, px_] += 0.35 * Lk; img[py_, px_ + 1] += 0.2 * Lk; img[py_ - 1, px_] += 0.12 * Lk
    # ------------------------------------------------ post
    out = img
    q4 = cv2.resize(out, (W // 4, H // 4), interpolation=cv2.INTER_AREA)
    hi = np.maximum(q4 - 0.7, 0)
    b1 = cv2.GaussianBlur(hi, (0, 0), 2.5)
    q8 = cv2.resize(hi, (W // 8, H // 8), interpolation=cv2.INTER_AREA)
    b2 = cv2.resize(cv2.GaussianBlur(q8, (0, 0), 4), (W // 4, H // 4))
    q16 = cv2.resize(q8, (W // 16, H // 16), interpolation=cv2.INTER_AREA)
    b3 = cv2.resize(cv2.GaussianBlur(q16, (0, 0), 5), (W // 4, H // 4))
    st = cv2.GaussianBlur(np.maximum(q4 - 1.8, 0), (0, 0), sigmaX=W / 40, sigmaY=0.6)
    bloom = 0.5 * b1 + 0.55 * b2 + 0.5 * b3 + st * np.array([0.5, 0.55, 0.7], f32) * 0.9 + (b2 + b3) * np.array([0.22, 0.02, 0.0], f32)
    out += cv2.resize(bloom, (W, H), interpolation=cv2.INTER_LINEAR)
    out = shoulder(out)
    out = out * np.array([1.0, 0.97, 0.95], f32) * 0.95 + np.array([0.014, 0.012, 0.02], f32)
    out[..., 0] = np.roll(out[..., 0], 1, axis=1); out[..., 2] = np.roll(out[..., 2], -1, axis=1)
    out = out * SCAN * VIG + GRAIN[i % 5]
    sd = since(DROPS, i)
    if sd < 6: out += 0.25 * (1 - sd / 6)
    if t < 0.6: out *= t / 0.6
    if t > DUR - 0.6: out *= max(0.0, (DUR - t) / 0.6)
    return (np.clip(out, 0, 1) * 255).astype(np.uint8)

if __name__ == '__main__':
    if sys.argv[1] == 'still':
        for s in sys.argv[2:]:
            fr = frame(int(float(s) * FPS))
            cv2.imwrite(f'still_{s}.jpg', cv2.cvtColor(fr, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 92])
    else:
        a, b, outp = int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
        p = subprocess.Popen(['ffmpeg', '-y', '-v', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(FPS),
                              '-i', '-', '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20', '-pix_fmt', 'yuv420p', outp], stdin=subprocess.PIPE)
        for i in range(a, b):
            p.stdin.write(frame(i).tobytes())
            if (i - a) % 100 == 0: print(i, flush=True)
        p.stdin.close(); sys.exit(p.wait())
