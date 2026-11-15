# Streaming quality metrics against a colour original.
# usage: evaluate.py gt.mp4 name1=vid1 [name2=vid2 ...]
#   err      mean |ab - ab_gt| over the frame (Lab units)
#   edge     same, only within 5 px of strong luminance edges        -> colour bleeding / halos
#   motion   same, only around pixels that moved since last frame    -> errors on moving subjects
#   mflick   frame-to-frame chroma change around moving pixels minus the true change there
#   halo     chroma gradient in flat-luminance areas 3-16 px from strong edges (lower = less halo;
#            the first line printed is the colour original's own value for reference)
#   mwarp    colour change of moving objects along their true motion (optical flow on the original);
#            this is what the eye sees as colours flipping on a walking person
#   mlong    how far the colour of things that have moved drifts while tracked for up to 12 frames
#            (outfits changing colour as people walk); compare with the original's own value
#   sflick   frame-to-frame chroma change where the true colour is static -> flicker
import sys, numpy as np, cv2
gt_path = sys.argv[1]; runs = [a.split('=',1) for a in sys.argv[2:]]
def lab(f): return cv2.cvtColor(f.astype(np.float32)/255, cv2.COLOR_BGR2Lab)
k5=np.ones((11,11),np.uint8); k7=np.ones((15,15),np.uint8)
k3=np.ones((7,7),np.uint8); k16=np.ones((33,33),np.uint8)
def halo(gray, ab):
    e=cv2.Canny(gray,60,150)
    band=(cv2.dilate(e,k16)>0)&~(cv2.dilate(e,k3)>0)
    gx=cv2.Sobel(gray,cv2.CV_32F,1,0); gy=cv2.Sobel(gray,cv2.CV_32F,0,1)
    flat=cv2.blur(np.hypot(gx,gy),(5,5))<12
    m=band&flat
    if m.sum()<100: return None
    g=sum(np.abs(cv2.Sobel(ab[:,:,k],cv2.CV_32F,dx,dy,ksize=3)) for k in (0,1) for dx,dy in ((1,0),(0,1)))/8
    return g[m].mean()
runs=[['(original)',gt_path]]+runs
dis=cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
for name,p in runs:
    g=cv2.VideoCapture(gt_path); c=cv2.VideoCapture(p)
    pg=pc=pgray=None; err=[]; edge=[]; mot=[]; sf=[]; mf=[]; hl=[]; mw=[]; ml=[]; fi=0; ref=None
    while True:
        ok1,fg=g.read(); ok2,fc=c.read()
        if not (ok1 and ok2): break
        gray=cv2.cvtColor(fg,cv2.COLOR_BGR2GRAY)
        a=lab(fg)[:,:,1:]; b=lab(fc)[:,:,1:]
        d=np.abs(a-b).sum(-1)/2
        err.append(d.mean())
        h=halo(gray,b)
        if h is not None: hl.append(h)
        em=cv2.dilate(cv2.Canny(gray,60,150),k5)>0
        if em.any(): edge.append(d[em].mean())
        if pg is not None:
            mm=cv2.dilate((cv2.absdiff(gray,pgray)>14).astype(np.uint8),k7)>0
            dt=np.abs(b-pc).sum(-1)/2; dg=np.abs(a-pg).sum(-1)/2
            if mm.sum()>50:
                fl=dis.calc(gray,pgray,None)
                hh,ww=gray.shape; xs,ys=np.meshgrid(np.arange(ww,dtype=np.float32),np.arange(hh,dtype=np.float32))
                mx=xs+fl[:,:,0]; my=ys+fl[:,:,1]
                wpc=cv2.remap(pc,mx,my,cv2.INTER_LINEAR); wpg=cv2.remap(pgray,mx,my,cv2.INTER_LINEAR)
                ok=mm&(cv2.absdiff(wpg,gray)<10)   # only where the flow explains the picture
                if ok.sum()>50: mw.append((np.abs(b-wpc).sum(-1)/2)[ok].mean())
                mot.append(d[mm].mean()); mf.append(dt[mm].mean()-dg[mm].mean())
            st=(dg<0.5)&(~mm)
            sf.append(dt[st].mean())
        # long-range: carry a map from the current frame back to an anchor frame via chained flow
        hh,ww=gray.shape
        if ref is None or fi%12==0:
            ref=b.copy(); xs,ys=np.meshgrid(np.arange(ww,dtype=np.float32),np.arange(hh,dtype=np.float32)); MX,MY=xs.copy(),ys.copy(); valid=np.ones((hh,ww),bool)
        elif pgray is not None:
            fl=dis.calc(gray,pgray,None)
            px=xs+fl[:,:,0]; py=ys+fl[:,:,1]
            wpg=cv2.remap(pgray,px,py,cv2.INTER_LINEAR)
            step_ok=(cv2.absdiff(wpg,gray)<10)&(px>=0)&(py>=0)&(px<ww-1)&(py<hh-1)
            MX=cv2.remap(MX,px,py,cv2.INTER_LINEAR); MY=cv2.remap(MY,px,py,cv2.INTER_LINEAR)
            valid=step_ok&(cv2.remap(valid.astype(np.float32),px,py,cv2.INTER_LINEAR)>0.99)
            moved=valid&(np.hypot(MX-xs,MY-ys)>4)
            if moved.sum()>200:
                wr=cv2.remap(ref,MX,MY,cv2.INTER_LINEAR)
                ml.append((np.abs(b-wr).sum(-1)/2)[moved].mean())
        fi+=1
        pg,pc,pgray=a,b,gray
    print(f'{name:12s} n={len(err)} err={np.mean(err):.2f} edge={np.mean(edge):.2f} motion={np.mean(mot):.2f} mflick={np.mean(mf):.2f} mwarp={np.mean(mw):.2f} mlong={np.mean(ml) if ml else float('nan'):.2f} sflick={np.mean(sf):.2f}')
