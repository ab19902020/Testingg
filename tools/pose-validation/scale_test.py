import numpy as np, cv2, sys
sys.path.insert(0,'.')
from mypipe import *
I='/tmp/claude-0/imgs/'
room=cv2.resize(cv2.imread(I+'living_room.jpg'),(1280,720))
man=cv2.imread(I+'male_full_height_hands.jpg')[0:1000,60:600]
yoga=cv2.imread(I+'pose.jpg')[60:667,250:850]
def paste(dst, src, cx, bottom, h):
    s=h/src.shape[0]; src=cv2.resize(src,(int(src.shape[1]*s),h)); x=int(cx-src.shape[1]/2); y=int(bottom-h)
    H,W=dst.shape[:2]; x0=max(0,x); y0=max(0,y); x1=min(W,x+src.shape[1]); y1=min(H,y+src.shape[0])
    dst[y0:y1,x0:x1]=src[y0-y:y1-y, x0-x:x1-x]; return dst
for variant in ['lite','full']:
    p=Pipe(variant)
    for frac in [0.9,0.75,0.6,0.5,0.42,0.35,0.28]:
        h=int(720*frac); im=room.copy()
        paste(im, man, 1280*0.27, 700, h); paste(im, cv2.flip(man,1), 1280*0.73, 700, h)
        rgb=cv2.cvtColor(im,cv2.COLOR_BGR2RGB)
        full=p.detect(rgb)
        left=p.detect(rgb, roi=(0,0,int(1280*0.56),720)); right=p.detect(rgb, roi=(int(1280*0.44),0,int(1280*0.56),720))
        print(f'{variant} person height {frac:.2f} of frame: full-frame dets={len(full)} {[round(d["score"],2) for d in full]}  halves: L={len(left)} {[round(d["score"],2) for d in left]} R={len(right)} {[round(d["score"],2) for d in right]}')
