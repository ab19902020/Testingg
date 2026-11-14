# Streaming metrics. usage: evaluate.py gt.mp4 name1=vid1 ...
import sys, numpy as np, cv2
gt_path = sys.argv[1]; runs = [a.split('=',1) for a in sys.argv[2:]]
def lab(f): return cv2.cvtColor(f.astype(np.float32)/255, cv2.COLOR_BGR2Lab)
for name,p in runs:
    g=cv2.VideoCapture(gt_path); c=cv2.VideoCapture(p)
    pg=pc=None; err=[]; fl=[]; st=[]; ch=[]; gfl=[]
    while True:
        ok1,fg=g.read(); ok2,fc=c.read()
        if not (ok1 and ok2): break
        a=lab(fg)[:,:,1:]; b=lab(fc)[:,:,1:]
        err.append(np.abs(a-b).mean()); ch.append(np.hypot(b[:,:,0],b[:,:,1]).mean())
        if pg is not None:
            m=(np.abs(a-pg).sum(-1)<1.0)
            d=np.abs(b-pc).sum(-1)
            fl.append(np.abs(b-pc).mean()); st.append(d[m].mean()); gfl.append(np.abs(a-pg).mean())
        pg,pc=a,b
    print(f'{name:10s} frames={len(err)} abErrVsGT={np.mean(err):.2f} temporal|d ab|={np.mean(fl):.2f} (gt {np.mean(gfl):.2f}) flickerInStaticGT={np.mean(st):.2f} meanChroma={np.mean(ch):.1f}')
