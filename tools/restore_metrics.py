# Compares black-and-white clips with a clean reference (luma only).
# usage: restore_metrics.py clean.mp4 name=clip ...
#   psnr     overall fidelity to the clean picture (higher is better)
#   noise    rms of the high-frequency difference from the clean picture (grain left; lower is better)
#   flicker  rms frame-to-frame change of mean brightness beyond the clean clip's own (lower is better)
import sys, numpy as np, cv2
clean = sys.argv[1]
for name, path in [a.split('=',1) for a in sys.argv[2:]]:
    a = cv2.VideoCapture(clean); b = cv2.VideoCapture(path); ps=[]; nz=[]; fl=[]; pm=None
    while True:
        o1, f1 = a.read(); o2, f2 = b.read()
        if not (o1 and o2): break
        g1 = cv2.cvtColor(f1, cv2.COLOR_BGR2GRAY).astype(np.float32); g2 = cv2.cvtColor(f2, cv2.COLOR_BGR2GRAY).astype(np.float32)
        g1 = g1[:g2.shape[0], :g2.shape[1]]
        d = g2 - g1
        ps.append(10*np.log10(255**2/max(1e-6,(d**2).mean())))
        hp = d - cv2.GaussianBlur(d, (0,0), 3); nz.append(np.sqrt((hp**2).mean()))
        m = g2.mean() - g1.mean()
        if pm is not None: fl.append(m - pm)
        pm = m
    print(f'{name:14s} n={len(ps)} psnr={np.mean(ps):.2f} dB  noise={np.mean(nz):.2f}  flicker={np.sqrt(np.mean(np.square(fl))):.2f}')
