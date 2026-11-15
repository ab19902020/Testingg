# Scores re-created in-between frames against the real ones.
# usage: interp_metrics.py original.mp4 name=doubled.mp4 ...   (doubled = half-rate clip with 1 in-between frame added)
import sys, numpy as np, cv2
def frames(p):
    c = cv2.VideoCapture(p); o = []
    while True:
        ok, f = c.read()
        if not ok: break
        o.append(cv2.cvtColor(f, cv2.COLOR_BGR2GRAY).astype(np.float32))
    return o
orig = frames(sys.argv[1])
def psnr(a, b): return 10 * np.log10(255 ** 2 / max(1e-6, ((a - b) ** 2).mean()))
for name, p in [a.split('=', 1) for a in sys.argv[2:]]:
    d = frames(p); ps = []; pb = []; held = 0
    for i in range(1, min(len(orig), len(d)) - 1, 2):
        if np.abs(orig[i - 1] - orig[i + 1]).mean() > 25: continue          # cut
        if np.abs(d[i] - d[i + 1]).mean() < 0.3 or np.abs(d[i] - d[i - 1]).mean() < 0.3: held += 1; continue   # frame was held, not made
        ps.append(psnr(d[i], orig[i])); pb.append(psnr((orig[i - 1] + orig[i + 1]) / 2, orig[i]))
    print(f'{name:12s} in-between frames: {np.mean(ps):.2f} dB (plain blend {np.mean(pb):.2f} dB), {len(ps)} made, {held} held')
