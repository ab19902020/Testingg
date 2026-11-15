# Simulates old-film damage on a clean clip, for measuring restoration.
# usage: degrade.py in.mp4 out.mp4 [--fade] [--frames N]
import sys, subprocess, numpy as np, cv2
src, dst = sys.argv[1], sys.argv[2]
fade = '--fade' in sys.argv
maxf = int(sys.argv[sys.argv.index('--frames')+1]) if '--frames' in sys.argv else 10**9
rng = np.random.default_rng(7)
c = cv2.VideoCapture(src); fps = c.get(cv2.CAP_PROP_FPS)
w = int(c.get(3)) & ~1; h = int(c.get(4)) & ~1
p = subprocess.Popen(['ffmpeg','-y','-v','error','-f','rawvideo','-pix_fmt','gray','-s',f'{w}x{h}','-r',str(fps),'-i','-',
                      '-c:v','libx264','-crf','8','-pix_fmt','yuv420p',dst], stdin=subprocess.PIPE)
i = 0
while i < maxf:
    ok, f = c.read()
    if not ok: break
    g = cv2.cvtColor(f[:h,:w], cv2.COLOR_BGR2GRAY).astype(np.float32)
    if fade: g = 58 + 0.5 * g                                   # faded print: grey blacks, dull whites
    g = g * (1 + np.clip(rng.normal(0, 0.07), -0.16, 0.16))      # exposure flicker
    grain = cv2.GaussianBlur(rng.normal(0, 14, g.shape).astype(np.float32), (0,0), 0.7)   # ~8 codes rms after blur
    g = g + grain
    for _ in range(rng.integers(4, 12)):                         # dust / blotches, one frame each
        x, y = int(rng.integers(0, w)), int(rng.integers(0, h)); r = int(rng.integers(1, max(3, w // 150)))
        cv2.ellipse(g, (x, y), (r, max(1, int(r * rng.uniform(0.4, 1)))), float(rng.uniform(0, 180)), 0, 360,
                    float(rng.choice([15, 235])), -1)
    p.stdin.write(np.clip(g, 0, 255).astype(np.uint8).tobytes()); i += 1
p.stdin.close(); p.wait(); print('frames', i, f'{w}x{h}')
