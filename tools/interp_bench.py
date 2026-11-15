# Benchmarks frame interpolation: drops every other frame of a clip, re-creates it, compares with the truth.
# usage: interp_bench.py rife.onnx clip.mp4 [max_pairs] [scale]
import sys, time, numpy as np, cv2, onnxruntime as ort
model, clip = sys.argv[1], sys.argv[2]; maxp = int(sys.argv[3]) if len(sys.argv) > 3 else 40
scale = float(sys.argv[4]) if len(sys.argv) > 4 else 1.0
so = ort.SessionOptions(); so.intra_op_num_threads = 4
s = ort.InferenceSession(model, so, providers=['CPUExecutionProvider'])
c = cv2.VideoCapture(clip); fr = []
while len(fr) < 2 * maxp + 1:
    ok, f = c.read()
    if not ok: break
    fr.append(cv2.cvtColor(f, cv2.COLOR_BGR2GRAY))
h, w = fr[0].shape
def prep(g):
    if scale != 1.0: g = cv2.resize(g, (int(w * scale) // 2 * 2, int(h * scale) // 2 * 2), interpolation=cv2.INTER_AREA)
    hh, ww = g.shape; ph = (64 - hh % 64) % 64; pw = (64 - ww % 64) % 64
    g = np.pad(g, ((0, ph), (0, pw)), mode='edge').astype(np.float32) / 255
    return np.repeat(g[None, None], 3, 1), hh, ww
def psnr(a, b): return 10 * np.log10(255 ** 2 / max(1e-6, ((a.astype(np.float32) - b) ** 2).mean()))
pr = []; pb = []; ts = []
for i in range(0, len(fr) - 2, 2):
    a, hh, ww = prep(fr[i]); b, _, _ = prep(fr[i + 2])
    t = time.time(); o = s.run(None, {'img0': a, 'img1': b, 'timestep': np.array([0.5], np.float32)})[0]; ts.append(time.time() - t)
    m = (o[0].mean(0)[:hh, :ww] * 255).clip(0, 255)
    if scale != 1.0: m = cv2.resize(m, (w, h), interpolation=cv2.INTER_CUBIC)
    truth = fr[i + 1].astype(np.float32)
    if np.abs(fr[i].astype(np.float32) - fr[i + 2]).mean() > 25: continue      # scene cut between the pair
    pr.append(psnr(m, truth)); pb.append(psnr((fr[i].astype(np.float32) + fr[i + 2]) / 2, truth))
print(f'{w}x{h} scale={scale}: RIFE {np.mean(pr):.2f} dB, plain blend {np.mean(pb):.2f} dB, {np.mean(ts[1:])*1000:.0f} ms per frame ({len(pr)} pairs)')
