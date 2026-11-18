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
# tracked beats (the tempo drifts between about 117 and 121 bpm); every 4th beat from 0 is a downbeat
BEATS = np.asarray(F['beats'], np.float64) if 'beats' in F.files else np.arange(0.19, DUR, 0.5)
_BK = np.arange(len(BEATS), dtype=np.float64)
BPF = float(np.median(np.diff(BEATS))) * FPS          # frames per beat (about 15)
def beatpos(i):
    t = i / FPS
    if t <= BEATS[0]: return (t - BEATS[0]) / (BEATS[1] - BEATS[0])
    if t >= BEATS[-1]: return len(BEATS) - 1 + (t - BEATS[-1]) / (BEATS[-1] - BEATS[-2])
    return float(np.interp(t, BEATS, _BK))
def snap(t): return int(round(t * FPS))
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
# Line and word timings come from lyrics_timing.json (align_lyrics.py: Whisper + CTC forced alignment);
# the song structure (sections on downbeats, drops, pyro) from structure.json.
TIMING_FILE = os.environ.get('LYRICS_TIMING', os.path.join(HERE, 'lyrics_timing.json'))
with open(TIMING_FILE) as fh:
    _TJ = json.load(fh)
with open(os.path.join(HERE, 'structure.json')) as fh:
    _ST = json.load(fh)
LYR = []   # (t0, t1, TEXT, card word or None, kind, word start times, word end times)
for e in _TJ['lines']:
    ws = e.get('words') or []
    txt = e['text'].upper()
    if len(ws) != len(txt.split(' ')):          # no word times: spread the words over the line
        n_ = len(txt.split(' ')); d_ = (float(e['end'] or e['start'] + 3) - float(e['start'])) * 0.6
        ws = [[None, float(e['start']) + d_ * k / n_, float(e['start']) + d_ * (k + 1) / n_] for k in range(n_)]
    LYR.append((float(e['start']), float(e['end']) if e.get('end') is not None else DUR + 1, txt, e.get('card'), e['kind'],
                np.array([w[1] for w in ws], np.float64), np.array([w[2] for w in ws], np.float64)))
LYR.sort(key=lambda e: e[0])
_SS = _ST['sections']
SECT = [(s['start'], _SS[k + 1]['start'] if k + 1 < len(_SS) else DUR + 1, s['kind']) for k, s in enumerate(_SS)]
def section(t):
    for a, b, k in SECT:
        if a <= t < b: return k, a, b
    return SECT[-1][2], SECT[-1][0], SECT[-1][1]
def first_of(kind): return next(a for a, b, k in SECT if k == kind)
KICKOFF_T = first_of('verse'); END_T = first_of('end')
def lyric_at(t):
    for e in LYR:
        if e[0] <= t < e[1]: return e
    k, a, b = section(t)
    if k == 'inst': return (a, b, 'UNITED ROAD', 'UNITED ROAD', 'inst', np.array([a, a + 0.25]), np.array([a + 0.25, b]))
    return None
DROPS = [snap(t) for t in _ST['drops']]
PYRO = [float(t) for t in _ST.get('pyro', [])]
KICKOFF = snap(KICKOFF_T)
DIM = ('bridge', 'outro', 'build')
BRIDGE_T = first_of('bridge')
def is_dim(t): return section(t)[0] in DIM
# card display: the upper tier flips to MAGIC / POCHETTINO when that word is sung, and holds it until the next one
CARD_SECS = ('opening', 'chorus', 'final', 'build', 'outro', 'inst')
CARD_EV = []
for e in LYR:
    if not e[3]: continue
    wds = e[2].split(' ')
    j = next((k for k, w in enumerate(wds) if e[3].split(' ')[0] in w), 0)
    CARD_EV.append((float(e[5][j]) - 0.05, e[3]))
CARD_EV.sort()
def card_at(t):
    """(word, time it appeared) for the seat card display, or None"""
    sec = section(t)[0]
    if sec not in CARD_SECS or t < 2.0: return None
    if sec == 'inst': return ('UNITED ROAD', section(t)[1])
    cur = None
    for tt, w in CARD_EV:
        if tt <= t: cur = (w, tt)
        else: break
    if cur is None or t - cur[1] > 9.0: return None
    return cur
def torch_level(t):
    """phone torches in the stands: the quiet parts (build, outro, bridge breakdowns)"""
    sec, a, b = section(t)
    if sec not in ('build', 'outro', 'bridge'): return 0.0
    return float(min(1.0, (t - a) / 1.5) * min(1.0, max(0.0, (b - t) / 0.4)))

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

def words_sung(e, t):
    """how many words of line e have started by time t (from the aligned word times)"""
    return int(np.searchsorted(e[5], t + 0.02, side='right'))
def reveal_px(e, t):
    a, ends, tw = lyric_strip(e[2])
    k = min(words_sung(e, t), len(ends))
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
_wrap = {}
def wrapped(txt):
    """the line word-wrapped to fit the dot matrix: [(x, y, glyph grid)] per word, biggest text that fits"""
    if txt in _wrap: return _wrap[txt]
    words = txt.split(' ')
    for rh in range(17, 8, -1):
        gs = [text_grid(w, rh) for w in words]
        sp = max(2, rh // 3)
        rows_, cur, cw = [], [], 0
        for k, g in enumerate(gs):
            w = g.shape[1]
            if cur and cw + sp + w > COLS - 4:
                rows_.append((cur, cw)); cur, cw = [], 0
            cw = cw + (sp if cur else 0) + w; cur.append(k)
        rows_.append((cur, cw))
        gap = 2; tot = len(rows_) * rh + (len(rows_) - 1) * gap
        if tot <= ROWS - 2 and all(cw <= COLS - 4 for _, cw in rows_):
            out = [None] * len(words); y = (ROWS - tot) // 2
            for ks, cw in rows_:
                x = (COLS - cw) // 2
                for k in ks:
                    out[k] = (x, y, gs[k]); x += gs[k].shape[1] + sp
                y += rh + gap
            _wrap[txt] = out; return out
    _wrap[txt] = None; return None
def board_state(i):
    t = i / FPS; g = np.zeros((ROWS, COLS), f32)
    if t < 1.2: return g, 0.0
    if t < 2.4:
        r = np.random.default_rng(i).random((ROWS, COLS)); return (r > 0.3 + 0.7 * (t - 1.2) / 1.2).astype(f32), 0.8
    if t > END_T: center(g, fit('UNITED ROAD')); return g, 1.0 + 0.8 * math.exp(-(t - END_T) / 0.3)
    e = lyric_at(t); sec = section(t)[0]
    cd = card_at(t)
    if cd and sec in ('chorus', 'final', 'inst', 'hit'):
        # the chorus word, big, flashing on the beat
        center(g, fit(cd[0]))
        bp = beatpos(i); fresh = math.exp(-(t - cd[1]) / 0.2)
        return g * (1 - 0.45 * (bp - math.floor(bp))), 1.0 + 0.8 * fresh
    if e is None or e[4] == 'inst': center(g, fit('UNITED ROAD')); return g, 1.0
    # the line, word-wrapped; each word lights up as it is sung and the newest one is brightest
    lay = wrapped(e[2])
    k = words_sung(e, t)
    if lay is None:
        if k: center(g, fit(e[2].split(' ')[k - 1]))
        return g, 1.0
    for j in range(k):
        x, y, gg = lay[j]
        age = t - e[5][j]
        place(g, gg * (0.72 if j < k - 1 else 1.0 + 0.6 * math.exp(-age / 0.18)), x, y)
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
        toff = END_T + 0.35 + (7 - g) * 0.16
        if t > toff: v *= 0.0 if t > toff + 0.15 else 0.4
        base[m] = v
    pulse = 0.86 + 0.32 * float(bass[i])
    bp = beatpos(i); bi = int(math.floor(bp)); bf = bp - bi
    idx = np.arange(NL)
    sec, a, b = section(t)
    wv = np.ones(NL, f32) * pulse; rv = np.zeros(NL, f32)
    if sec == 'opening':
        # soft first chorus: steady white with a slow red wash travelling round the roof
        p = (t * 0.05 % 1.0) * NL
        dd = np.abs(((idx - p + NL / 2) % NL) - NL / 2)
        wv = (0.82 + 0.25 * np.exp(-(dd / 8.0) ** 2)).astype(f32) * pulse
        rv = (0.55 * np.exp(-(np.abs(((idx - p - NL / 2 + NL / 2) % NL) - NL / 2) / 7.0) ** 2)).astype(f32)
    elif sec == 'hit':
        par = (idx + bi) % 2 == 0
        fl = (1 - bf) ** 1.5
        wv = np.where(par, 0.4 + 1.3 * fl, 0.5).astype(f32) * pulse
        rv = np.where(par, 0.0, 1.5 * fl).astype(f32) * pulse
    elif sec == 'end':
        wv = np.full(NL, 1.0 + 1.2 * math.exp(-(t - a) / 0.5), f32); rv = np.zeros(NL, f32)
    elif sec == 'verse':
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
        # stripped-back final chorus: the bowl goes dark for the phone torches, one spot drifts round,
        # then the last 4 bars ramp up into the drop with an accelerating chase
        ramp = float(np.clip((t - (b - 8.0)) / 8.0, 0, 1)) ** 1.5
        p = (0.25 + (t - a) * 0.035) * NL
        dd = np.abs(((idx - p + NL / 2) % NL) - NL / 2)
        wv = (0.16 + 1.1 * np.exp(-(dd / 2.5) ** 2)).astype(f32)
        if ramp > 0:
            rate = 1 + 5 * ramp
            p2 = (((t - (b - 8.0)) * rate * 0.5) % 1.0) * NL
            d2 = np.abs(((idx - p2 + NL / 2) % NL) - NL / 2)
            wv = wv + ramp * (0.5 * pulse + 1.0 * np.exp(-(d2 / 4.0) ** 2))
            rv = np.full(NL, 0.6 * ramp, f32) * ((bi % 2) == 0)
    elif sec == 'outro':
        wv[:] = 0.36 + 0.08 * math.sin(t * 2.0)
    sd = since(DROPS, i)
    if sd < BPF * 2:
        st = 1.7 if (sd // 2) % 2 == 0 else 0.2
        wv = wv * st; rv = rv * st
    return (base * wv).astype(f32), (base * rv).astype(f32), base

def nth_of(kind, n): return [a for a, b, k in SECT if k == kind][n]
RAIN_LEVEL = [(0, 0.7), (KICKOFF_T, 0.75), (nth_of('verse', 1), 1.15), (nth_of('chorus', 1), 0.9), (first_of('bridge'), 1.2),
              (first_of('final'), 1.0), (first_of('outro'), 1.25), (DUR + 1, 1.0)]
def scene_light(t):
    """overall floodlight level on the stands and pitch for the section"""
    sec, a, b = section(t)
    if sec == 'build':
        return 0.42 + 0.58 * float(np.clip((t - (b - 8.0)) / 8.0, 0, 1)) ** 1.5
    if sec in ('bridge', 'outro'): return 0.72
    return 1.0
def rain_level(t):
    xs = [p[0] for p in RAIN_LEVEL]; ys = [p[1] for p in RAIN_LEVEL]
    return float(np.interp(t, xs, ys))

# ================================================================== camera path
def ease(u): u = min(1.0, max(0.0, u)); return u * u * (3 - 2 * u)
def lerp(a, b, u): return np.asarray(a, np.float64) + (np.asarray(b, np.float64) - np.asarray(a, np.float64)) * u
def look(pos, tgt, hfov): return np.asarray(pos, np.float64), np.asarray(tgt, np.float64), hfov
def perim_point(s, d, y):
    x, z, nx, nz = perim(np.array([s % L])); return np.array([x[0] + d * nx[0], y, z[0] + d * nz[0]]), np.array([nx[0], 0, nz[0]])
# one shot per run of sections with the same "shot" in structure.json; every cut lands on a downbeat
SHOTS = []
for s in _SS:
    if SHOTS and SHOTS[-1][2] == s['shot']: continue
    SHOTS.append([s['start'], None, s['shot']])
for k in range(len(SHOTS)): SHOTS[k][1] = SHOTS[k + 1][0] if k + 1 < len(SHOTS) else DUR + 1
SHOT_K = {'crane': 0, 'pan360': 1, 'swing': 2, 'touchline': 3, 'orbit': 4, 'boards': 5, 'lookup': 6, 'bigcrane': 7,
          'drift': 8, 'pullout': 9, 'pushin': 10}
def cam_at(t):
    a, b, name = next((s for s in SHOTS if s[0] <= t < s[1]), SHOTS[-1])
    k = SHOT_K[name]
    b = min(b, DUR); u = (t - a) / (b - a); e = ease(u)
    if k == 10:  # build: low push in from the centre circle towards the north stand full of phone torches
        return look(lerp((0, 1.6, -20), (0, 4.5, 6), e), lerp((0, 24, 70), (0, 27, 70), e), 74 - 4 * e)
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
    if k == 7:   # final chorus: big crane and orbit, starting low with the pyro in shot
        az = math.radians(-100 + 110 * e); rad = 10 + 34 * e
        pos = (rad * math.cos(az), 1.8 + 30 * e, rad * math.sin(az))
        tgt = (-pos[0] * 0.55, 6 + 9 * min(1.0, e * 2.5), -pos[2] * 0.55 + 6)
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
    if sec in ('chorus', 'final', 'hit'):
        bg = np.array([0.75, 0.03, 0.04], f32) * (0.75 + 0.5 * (1 - bf) ** 2)
        fg = np.array([1.6, 1.52, 1.45], f32)
    elif sec in ('bridge', 'outro', 'build'):
        bg = np.array([0.06, 0.005, 0.008], f32); fg = np.array([1.35, 0.25, 0.18], f32)
    else:
        bg = np.array([0.5, 0.02, 0.03], f32); fg = np.array([1.45, 1.4, 1.32], f32)
    # the word being sung flashes as it lands; earlier words settle back a little
    if k > 0 and len(e) > 5:
        wi = np.searchsorted(ends, cc - 1.0, side='left')          # word index of each pixel column
        age = t - float(e[5][k - 1])
        tx = tx * np.where(wi == (k - 1), 1.0 + 0.7 * math.exp(-age / 0.16), 0.82).astype(f32)
    col = bg[None] * (1 - np.minimum(tx, 1)[:, None]) + fg[None] * tx[:, None]
    # LED pixel texture when close
    pitch_m = 0.025
    amp = np.clip(1 - fw / pitch_m, 0, 1) * 0.35
    led = 1 - amp * (0.5 - 0.5 * np.cos(aa / pitch_m * 2 * np.pi)) * (0.5 - 0.5 * np.cos(vv * BOARD_H / pitch_m * 2 * np.pi))
    return col * led[:, None]

# ================================================================== pyro (flame jets round the pitch at the drops)
FLAME_P = np.array([(x, 0.0, z) for z in (-36.8, 36.8) for x in (-45, -30, -15, 0, 15, 30, 45)] +
                   [(x, 0.0, z) for x in (-55.5, 55.5) for z in (-21, -7, 7, 21)], np.float64)
NFL = len(FLAME_P)
def pyro_level(t):
    """flame intensity: a burst on each of the first four beats after every pyro cue"""
    lv = 0.0
    for p in PYRO:
        if 0 <= t - p < 2.3:
            bts = BEATS[(BEATS >= p - 0.05) & (BEATS < p + 1.9)]
            if len(bts) == 0: bts = np.array([p])
            past = bts[bts <= t + 1e-3]
            if len(past): lv = max(lv, (0.18 + 0.82 * math.exp(-(t - past[-1]) / 0.42)) * (1.0 if len(past) < 4 else 1.3) * min(1.0, (2.3 - (t - p)) / 0.4))
    return lv
FLAME_TEX = tile_noise(256, 2.2)
def flame_layer(cam, t, i, lvl, dist):
    """additive flame colour at quarter resolution (H/4, W/4, 3)"""
    C, f, r, u, fp = cam
    qh, qw = H // 4, W // 4
    out = np.zeros((qh, qw, 3), f32)
    if lvl <= 0.01: return out
    dq = cv2.resize(dist, (qw, qh), interpolation=cv2.INTER_NEAREST)
    fq = fp / 4
    hgt = 4.0 + 9.0 * min(1.0, lvl)
    for k in range(NFL):
        P0 = FLAME_P[k]; P1 = P0 + np.array([0, hgt, 0])
        d0 = P0 - C; d1 = P1 - C; z0 = d0 @ f; z1 = d1 @ f
        if z0 < 1.0 or z1 < 1.0: continue
        x0 = qw / 2 + fq * (d0 @ r) / z0; y0 = qh / 2 - fq * (d0 @ u) / z0
        x1 = qw / 2 + fq * (d1 @ r) / z1; y1 = qh / 2 - fq * (d1 @ u) / z1
        wpx = fq * 1.25 / z0
        xa, xb = int(min(x0, x1) - 3 * wpx - 2), int(max(x0, x1) + 3 * wpx + 3)
        ya, yb = int(min(y0, y1) - 2 * wpx - 2), int(max(y0, y1) + 2)
        xa, ya = max(xa, 0), max(ya, 0); xb, yb = min(xb, qw), min(yb, qh)
        if xb <= xa + 1 or yb <= ya + 1: continue
        yy, xx = np.mgrid[ya:yb, xa:xb].astype(f32) + 0.5
        vx, vy = x1 - x0, y1 - y0; l2 = vx * vx + vy * vy + 1e-6
        v = ((xx - x0) * vx + (yy - y0) * vy) / l2                    # 0 at the nozzle, 1 at the tip
        across = ((xx - x0) * vy - (yy - y0) * vx) / math.sqrt(l2)     # pixels from the axis
        vv = np.clip(v, 0, 1.2)
        n1 = samp(FLAME_TEX, xx * 0.0 + across / max(wpx, 0.5) * 9 + k * 37, vv * 50 - t * 95 + k * 11)
        wid = wpx * (0.45 + 1.5 * vv) * (1 + 0.3 * n1)
        sh = np.clip(1 - np.abs(across) / np.maximum(wid, 0.4), 0, 1) * np.clip(v * 8, 0, 1) * np.clip((1.05 - v) / 0.45, 0, 1)
        heat = sh ** 1.4 * (0.75 + 0.45 * n1) * (1 - 0.55 * vv)
        zf = z0 + (z1 - z0) * np.clip(v, 0, 1)
        vis = dq[ya:yb, xa:xb] > zf * 0.97
        heat = heat * vis * lvl
        col = (np.array([2.2, 1.8, 1.1], f32) * np.clip(heat - 0.45, 0, 1)[..., None] * 3.0 +
               np.array([2.2, 0.75, 0.12], f32) * np.clip(heat, 0, 1)[..., None] +
               np.array([0.8, 0.10, 0.03], f32) * np.clip(heat * 2.5, 0, 1)[..., None])
        out[ya:yb, xa:xb] += col
    return out

# ================================================================== ticker tape for the final chorus
NCF = 14000
_cr = np.random.default_rng(5)
CF = _cr.random((NCF, 7))
CF_COL = np.where((CF[:, 5] < 0.55)[:, None], np.array([1.0, 0.07, 0.06]), np.array([0.95, 0.94, 0.92])).astype(f32)
_SPLAT = [(0, 0, 1.0), (1, 0, 0.9), (0, 1, 0.8), (1, 1, 0.7), (-1, 0, 0.5), (0, -1, 0.5), (2, 0, 0.4), (2, 1, 0.35), (-1, 1, 0.35)]
def confetti_layer(cam, t, dist, Lk):
    """(H, W, 3) red and white ticker tape fluttering down through the bowl in the final chorus"""
    t0 = first_of('final'); t1 = first_of('outro') + 3.0
    if not (t0 - 0.1 <= t < t1): return None
    C, f, r, u, fp = cam
    age = t - t0
    x = (CF[:, 0] * 2 - 1) * (A - 2) + 1.2 * np.sin(age * (0.5 + 0.6 * CF[:, 3]) + CF[:, 3] * 6.28)
    z = (CF[:, 1] * 2 - 1) * (B - 2) + 1.2 * np.cos(age * (0.4 + 0.5 * CF[:, 4]) + CF[:, 4] * 6.28)
    pop = 9.0 * (1 - math.exp(-age / 0.35))                                    # fired up out of the cannons
    y = np.mod(14 + 36 * CF[:, 2] + pop - (0.9 + 0.8 * CF[:, 3]) * age, 52.0)
    P = np.stack([x, y, z], 1)
    d = P - C; zc = d @ f
    ok = (zc > 0.6) & (y > 0.1)
    if not ok.any(): return None
    sx = W / 2 + fp * (d[ok] @ r) / zc[ok]; sy = H / 2 - fp * (d[ok] @ u) / zc[ok]; zz = zc[ok]
    flt = np.abs(np.sin(age * (3 + 7 * CF[ok, 4]) + CF[ok, 6] * 20))          # tumbling: catches the light now and then
    ix = sx.astype(np.int32); iy = sy.astype(np.int32)
    inb = (ix >= 2) & (ix < W - 3) & (iy >= 2) & (iy < H - 3)
    ix, iy, zz, flt, col = ix[inb], iy[inb], zz[inb], flt[inb], CF_COL[ok][inb]
    vis = dist[iy, ix] > zz
    ix, iy, zz, flt, col = ix[vis], iy[vis], zz[vis], flt[vis], col[vis]
    fade = min(1.0, (t1 - t) / 3.0) * min(1.0, age / 0.2 + 0.05)
    wgt = ((0.45 + 2.2 * flt ** 4) * (0.5 + 0.5 * Lk) * fade * np.clip(40.0 / (zz + 10), 0.35, 1.6))[:, None] * col
    size = fp * 0.07 / zz                                                     # about 7 cm of paper
    out = np.zeros((H, W, 3), f32)
    for k, (ox, oy, wk) in enumerate(_SPLAT):
        m = size > (0.0 if k == 0 else (0.6 + 0.35 * k))
        if m.any(): np.add.at(out, (iy[m] + oy, ix[m] + ox), wgt[m] * wk)
    return out

# ================================================================== on-screen lyrics (karaoke captions) and the title
CAPTIONS = os.environ.get('CAPTIONS', '1') != '0'
CAP_FONT = {}
def cap_font(size, path=None):
    key = (size, path or ANTON)
    if key not in CAP_FONT: CAP_FONT[key] = ImageFont.truetype(path or ANTON, size)
    return CAP_FONT[key]
_lay = {}
def caption_layout(txt, big):
    """word masks and positions for a caption line: [(mask (h, w) float, x, y)]; wraps to two rows if needed"""
    key = (txt, big)
    if key in _lay: return _lay[key]
    size = 104 if big else 84
    fnt = cap_font(size)
    dr = ImageDraw.Draw(Image.new('L', (1, 1)))
    words = txt.split(' ')
    space = dr.textlength(' ', font=fnt) * 1.05
    wl = [dr.textlength(w, font=fnt) for w in words]
    total = sum(wl) + space * (len(words) - 1)
    rows = [list(range(len(words)))]
    if total > W * 0.84 and len(words) > 1:      # balance into two rows
        best = None
        for cut in range(1, len(words)):
            a = sum(wl[:cut]) + space * (cut - 1); b = sum(wl[cut:]) + space * (len(words) - cut - 1)
            if best is None or max(a, b) < best[0]: best = (max(a, b), cut)
        rows = [list(range(best[1])), list(range(best[1], len(words)))]
    asc, desc = fnt.getmetrics()
    lh = int(size * 1.12)
    y0 = H - 118 - lh * len(rows)
    out = [None] * len(words)
    for ri, ks in enumerate(rows):
        rw = sum(wl[k] for k in ks) + space * (len(ks) - 1)
        x = (W - rw) / 2
        for k in ks:
            im = Image.new('L', (int(wl[k]) + 24, asc + desc + 24), 0)
            ImageDraw.Draw(im).text((12, 12), words[k], font=fnt, fill=255)
            out[k] = (np.asarray(im).astype(f32) / 255, int(round(x)) - 12, y0 + ri * lh - 12)
            x += wl[k] + space
    _lay[key] = out
    return out
def paste(dst, src, x, y, k=1.0):
    h, w = src.shape[:2]
    xa, ya = max(x, 0), max(y, 0); xb, yb = min(x + w, dst.shape[1]), min(y + h, dst.shape[0])
    if xb > xa and yb > ya:
        dst[ya:yb, xa:xb] += src[ya - y:yb - y, xa - x:xb - x] * k
def caption_masks(t):
    """(white text alpha, red glow alpha) for the lyric on screen at time t, or None"""
    shown = []
    for e in LYR:
        if e[0] - 0.02 <= t < e[1] + 0.14 and e[4] != 'inst':
            shown.append(e)
    if not shown: return None
    A = np.zeros((H, W), f32); G = np.zeros((H, W), f32)
    for e in shown[-1:]:          # a new line replaces the old one at once; a line only fades out into a gap
        out_k = float(np.clip((e[1] + 0.14 - t) / 0.14, 0, 1)) if t >= e[1] else 1.0
        in_k = float(np.clip((t - e[0] + 0.02) / 0.14, 0, 1))
        lk = out_k * in_k
        if lk <= 0: continue
        rise = int(round((1 - in_k) * 14 - (1 - out_k) * 18))
        lay = caption_layout(e[2], bool(e[3]) and e[4] == 'chorus')
        k = words_sung(e, t)
        for j, (mk, x, y) in enumerate(lay):
            if j < k:
                age = t - float(e[5][j])
                cur = j == k - 1 and (t < float(e[6][j]) + 0.25)
                pop = 1.0 + 0.12 * math.exp(-age / 0.09) if j == k - 1 else 1.0
                m2 = mk
                if pop > 1.003:
                    hh_, ww_ = mk.shape
                    m2 = cv2.resize(mk, (int(ww_ * pop), int(hh_ * pop)), interpolation=cv2.INTER_LINEAR)
                    x2 = x - (m2.shape[1] - ww_) // 2; y2 = y - (m2.shape[0] - hh_) // 2
                else:
                    x2, y2 = x, y
                paste(A, m2, x2, y2 + rise, lk)
                if j == k - 1:
                    paste(G, m2, x2, y2 + rise, lk * (0.5 + 0.9 * math.exp(-age / 0.3)) * (1.0 if cur else 0.5))
            else:
                paste(A, mk, x, y + rise, 0.30 * lk)
    return np.minimum(A, 1), G
def title_masks(t):
    """the opening title: UNITED ROAD / MAGIC, before the first line is sung"""
    t_in, t_out = 0.7, LYR[0][0] - 0.35
    if not (t_in <= t < t_out): return None
    k = min(1.0, (t - t_in) / 0.9) * min(1.0, (t_out - t) / 0.6)
    k = sstep(k)
    A = np.zeros((H, W), f32); R = np.zeros((H, W), f32)
    track = 1.0 + 0.6 * (1 - sstep(min(1.0, (t - t_in) / 3.5)))
    def spaced(txt, fnt, cy, tr, dst, kk):
        dr = ImageDraw.Draw(Image.new('L', (1, 1)))
        ws = [dr.textlength(c, font=fnt) for c in txt]
        gap = fnt.size * 0.08 * tr
        tw = sum(ws) + gap * (len(txt) - 1)
        asc, desc = fnt.getmetrics()
        im = Image.new('L', (int(tw) + 40, asc + desc + 20), 0); d = ImageDraw.Draw(im); x = 20
        for c, w_ in zip(txt, ws):
            d.text((x, 10), c, font=fnt, fill=255); x += w_ + gap
        a = np.asarray(im).astype(f32) / 255
        paste(dst, a, int(W / 2 - a.shape[1] / 2), int(cy - a.shape[0] / 2), kk)
    spaced('UNITED ROAD', cap_font(40, ARCHIVO), H * 0.36, track * 4, R, k)
    spaced('MAGIC', cap_font(250), H * 0.52, track, A, k)
    spaced('MAURICIO POCHETTINO', cap_font(38, ARCHIVO), H * 0.69, track * 2.5, A, k * 0.85)
    return np.minimum(A, 1), np.minimum(R, 1)
def overlay_text(out, t):
    """composite the title and the karaoke captions onto the finished frame (display values 0..1)"""
    layers = []
    tm = title_masks(t)
    if tm is not None: layers.append((tm[0], tm[1], True))
    if CAPTIONS:
        cm = caption_masks(t)
        if cm is not None: layers.append((cm[0], cm[1], False))
    for A_, G_, is_title in layers:
        ys, xs = np.nonzero((A_ + G_) > 0.002)
        if len(ys) == 0: continue
        ya, yb = max(0, ys.min() - 60), min(H, ys.max() + 60); xa, xb = max(0, xs.min() - 60), min(W, xs.max() + 60)
        a = A_[ya:yb, xa:xb]; g = G_[ya:yb, xa:xb]; o = out[ya:yb, xa:xb]
        sh = cv2.GaussianBlur(np.maximum(a, g), (0, 0), 9)
        o *= (1 - 0.62 * np.clip(sh * 1.6, 0, 1))[..., None]
        if is_title:
            red = np.array([0.95, 0.12, 0.10], f32)
            o += cv2.GaussianBlur(g, (0, 0), 14)[..., None] * red * 0.9
            o[:] = o * (1 - g[..., None]) + g[..., None] * red
        else:
            glow = cv2.GaussianBlur(g, (0, 0), 7) * 0.9 + cv2.GaussianBlur(g, (0, 0), 22) * 0.8
            o += glow[..., None] * np.array([0.95, 0.10, 0.07], f32)
        o[:] = o * (1 - a[..., None]) + a[..., None] * np.array([1.0, 0.975, 0.95], f32)
    return out

def card_prev(cd):
    """the card word showing before cd took over (if it was recent)"""
    prev = None
    for tt, w in CARD_EV:
        if tt < cd[1] - 1e-6: prev = (w, tt)
    if prev is None or cd[1] - prev[1] > 9.0: return None
    return prev[0]

def frame(i):
    t = i / FPS
    cam = camera(t)
    C, f, r, u, fp = cam
    Cf = C.astype(f32)
    lw, lr, lbase = lamp_levels(i)
    lv = lw + lr
    redfrac = float(lr.sum() / max(lv.sum(), 1e-3))
    Lk = float(np.clip(lbase.mean() * (0.92 + 0.12 * float(bass[i])) * scene_light(t), 0, 1.2))
    pyro_k = pyro_level(t)
    Lk_pyro = 0.3 * pyro_k * float(lbase.mean() > 0.05 or t > 5)
    Lcol = (np.array([1, 1, 1], f32) * (1 - 0.45 * redfrac) + np.array([1.0, 0.25, 0.2], f32) * 0.45 * redfrac).astype(f32)
    LC = (Lk * Lcol + Lk_pyro * np.array([1.0, 0.5, 0.2], f32)).astype(f32)      # light on the stands and pitch
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
        flat[gnd] = g * E[:, None] * LC[None]
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
            if t < KICKOFF_T: gain = min(1.0, (t - 2.5) / 3) * 0.75
            if t > END_T: gain *= max(0.0, (END_T + 1.3 - t) / 1.3)
            if sec in ('bridge', 'outro'): gain *= 0.3
            if sec == 'build': gain *= 0.12 + 0.88 * float(np.clip((t - (sb - 8.0)) / 8.0, 0, 1)) ** 2
        PK = np.max(np.stack([bar_heights(j) - 0.02 * (i - j) for j in range(max(0, i - 22), i + 1)]), 0) * gain
        cd = card_at(t)
        card = cd[0] if cd else None
        tl = torch_level(t)
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
            # phone torches: a scatter of seats holding up a light, each twinkling at its own rate
            tor_e = 0.0
            if tl > 0:
                h3 = hash2(cidx * 1.37 + 5.1, row * 2.11 + tier * 57.0)
                held = h3 < 0.075 * tl * (1.25 if mm == M_SEAT2 else 1.0)
                if held.any():
                    h4 = hash2(cidx + 17.0, row + 3.0)
                    tw = 0.55 + 0.45 * np.sin(t * (2.0 + 5.0 * h4) + h4 * 40.0)
                    dot = band(uf, 0.25, 0.75, wu) * band(vf, 0.35, 0.85, wv)
                    dot = dot * (1 - mix_) + 0.25 * mix_
                    tor_e = (held * tw * dot * (2.6 + 1.5 * h3 / 0.075))[:, None] * np.array([1.0, 0.95, 0.86], f32)[None] * tl
            if gain <= 0:
                es[m] = tor_e; continue
            lvl = ((CUMR[mm] + row + 0.5) / TOTROWS).astype(f32)
            bar = np.clip(np.floor(sg / 3.0) + 64, 0, NB - 1).astype(np.int32)
            if card is not None and mm == M_SEAT2:
                # card display: the whole upper tier spells the chorus word round the bowl. When the word is
                # sung the cards flip over in a wave that runs out from the middle of the north stand.
                bm = card_bitmap(card); wc = bm.shape[1]; period = wc + 40
                crel = (cidx.astype(np.int64) + wc // 2) % period
                inw = crel < wc
                rr_ = (32 - row).astype(np.int64).clip(0, 32)
                letter = np.zeros(m.sum(), bool)
                letter[inw] = bm[rr_[inw], crel[inw]]
                prev = card_prev(cd)
                letter0 = np.zeros(m.sum(), bool)
                if prev is not None:
                    bm0 = card_bitmap(prev); wc0 = bm0.shape[1]; per0 = wc0 + 40
                    cr0 = (cidx.astype(np.int64) + wc0 // 2) % per0; in0 = cr0 < wc0
                    letter0[in0] = bm0[rr_[in0], cr0[in0]]
                ph = (t - cd[1] - 0.55 * np.abs(sg) / HALF - 0.012 * (32 - row)) / 0.16
                bgc = np.array([0.95, 0.035, 0.04], f32) * (0.55 + 0.55 * (1 - bf) ** 2)
                if sec == 'build' or sec == 'outro': bgc = bgc * 0.45
                fgc = np.array([1.7, 1.6, 1.5], f32) * (1.0 + 0.6 * np.clip(1 - (ph - 1) / 2.0, 0, 1))[:, None]
                cur = np.where(letter[:, None], fgc, bgc[None])
                old = np.where(letter0[:, None], np.array([1.7, 1.6, 1.5], f32)[None], bgc[None])
                flip = np.clip(ph, 0, 1)[:, None]
                flash = (np.sin(np.pi * np.clip(ph, 0, 1)) * 1.4)[:, None] * np.array([1.0, 0.9, 0.85], f32)[None]
                e_ = (old * (1 - flip) + cur * flip + flash) * shp[:, None]
                es[m] = e_ + tor_e
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
            if sec == 'bridge':
                pos = (38 + (t - BRIDGE_T) * 10.0) % NB
                dbar = np.abs(((bar - pos + NB / 2) % NB) - NB / 2)
                w_ = np.exp(-(dbar / 2.2) ** 2) * (lvl < 0.97) * shp
                e_ += np.array([1.4, 0.9, 0.6], f32) * w_[:, None]
            es[m] = e_ + tor_e
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
        if t > END_T + 0.4: on_k *= max(0.0, (END_T + 1.4 - t) / 1.0)
        m = ms == M_RIB0
        if m.any():
            es[m] = led_text(aa[m] + 0.0, tt[m], fw[m], ly, t, i, 'rib') * on_k * (0.85 + 0.25 * float(bass[i])); cs[m] = 0
        m = ms == M_RIB1
        if m.any():
            a2 = aa[m]
            if sec in ('verse', 'inst'):
                fill = float(np.clip(energy[i] * 1.1 + 0.25 * bass[i], 0, 1.2)) * 125
                onv = np.abs(a2) < fill
                edge = np.clip(1 - (fill - np.abs(a2)) / 25, 0, 1)[:, None]
                cx_ = np.where(onv[:, None], np.array([1.2, 0.06, 0.05], f32) * (1 - edge) + np.array([1.6, 1.3, 1.1], f32) * edge, np.array([0.08, 0.005, 0.008], f32))
            elif sec in ('chorus', 'final', 'hit', 'end'):
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
    flat[tri] = col * LC[None] + emi
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
    if pyro_k > 0.01:
        # the flames light up the rain and haze around them
        Vf = (FLAME_P + np.array([0, 3.5, 0])).astype(f32) - Cf[None]
        tcf = Dh @ Vf.T; vvf = (Vf * Vf).sum(1)[None]
        hf = np.sqrt(np.maximum(vvf - tcf * tcf, 1.0))
        If = (np.arctan((tmax - tcf) / hf) - np.arctan(-tcf / hf)) / hf
        hzf = cv2.resize((If.sum(1) * (0.022 * pyro_k)).reshape(hh, hw).astype(f32), (W, H), interpolation=cv2.INTER_LINEAR)
        img += hzf[..., None] * np.array([1.0, 0.38, 0.1], f32) * (1 + 0.4 * rl)
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
    # ------------------------------------------------ pyro and ticker tape
    if pyro_k > 0.01:
        fl4 = flame_layer(cam, t, i, pyro_k, dist)
        img += cv2.resize(cv2.GaussianBlur(fl4, (0, 0), 0.7), (W, H), interpolation=cv2.INTER_LINEAR)
    cf = confetti_layer(cam, t, dist, Lk)
    if cf is not None: img += cf
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
    out = overlay_text(np.clip(out, 0, 1.2), t)
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
