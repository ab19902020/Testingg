import json, gzip, numpy as np, cv2, sys, math
sys.path.insert(0,'.')
import mypipe
from mypipe import *
import os
OUT=os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../core/src/test/resources/pose/')
def rep(img,M,size,border):
    if size==256:
        return cv2.warpAffine(img.astype(np.float32), M, (size,size), flags=cv2.INTER_LINEAR|cv2.WARP_INVERSE_MAP, borderMode=cv2.BORDER_REPLICATE)
    return cv2.warpAffine(img.astype(np.float32), M, (size,size), flags=cv2.INTER_LINEAR|cv2.WARP_INVERSE_MAP, borderMode=cv2.BORDER_CONSTANT, borderValue=(0,0,0))
mypipe.sample_affine=rep
def wf32(name, arr):
    with gzip.open(OUT+name,'wb') as f: f.write(np.asarray(arr,dtype='<f4').tobytes())
p=Pipe('lite')
rgb=cv2.cvtColor(cv2.imread('sceneA_two_players.png'),cv2.COLOR_BGR2RGB); H,W=rgb.shape[:2]
meta={'source':'sceneA_two_players (MediaPipe test assets composite), lite model','width':W,'height':H,'detector':[],'landmarks':[]}
for name,roi in [('full',(0,0,W,H)),('left',(0,0,int(W*0.56),H)),('right',(int(W*0.44),0,int(W*0.56),H))]:
    dets=p.detect(rgb, roi=roi, max_det=4)
    raw,sc=p.last_det_raw
    wf32(f'det_{name}_raw.f32.gz', raw.reshape(-1)); wf32(f'det_{name}_scores.f32.gz', sc.reshape(-1))
    exp=[{'score':d['score'],'kps':[list(map(float,k)) for k in d['kps']],'box':[float(d['box'][0][0]),float(d['box'][0][1]),float(d['box'][1][0]),float(d['box'][1][1])],
          'rect':list(map(float,rect_from_points(d['kps'][0],d['kps'][1])))} for d in dets]
    meta['detector'].append({'name':name,'roi':list(map(int,roi)),'raw':f'det_{name}_raw.f32.gz','scores':f'det_{name}_scores.f32.gz','expected':exp})
dets=p.detect(rgb, max_det=4)
for i,d in enumerate(dets):
    rect=rect_from_points(d['kps'][0],d['kps'][1])
    raw,flag,hm=p.landmarks(rgb,rect)
    entry={'rect':list(map(float,rect)),'raw':list(map(float,raw)),'flag':flag,
           'expectedNoRefine':decode_landmarks(raw,None,rect,refine=False).tolist()}
    if i==0:
        wf32('lm0_heatmap.f32.gz', hm.reshape(-1)); entry['heatmap']='lm0_heatmap.f32.gz'
        entry['expectedRefined']=decode_landmarks(raw,hm,rect,refine=True).tolist()
    # tracking ROI from aux landmarks 33 (hip centre) and 34 (scale point)
    lm=decode_landmarks(raw,hm,rect,refine=False)
    entry['expectedTrackingRect']=list(map(float,rect_from_points(lm[33,:2],lm[34,:2])))
    meta['landmarks'].append(entry)
# sampler fixture on a procedural image (same formula implemented in the Kotlin test)
w,h=320,200
yy,xx=np.mgrid[0:h,0:w]
proc=np.stack([(xx*7+yy*3)%256,(xx*2+yy*5+40)%256,((xx//8+yy//8)%2)*200+20],axis=-1).astype(np.float32)
samples=[]
rng=np.random.default_rng(7)
for rect in [(150.0,90.0,180.0,0.0),(160.0,100.0,150.0,0.6),(60.0,40.0,170.0,-2.3)]:
    cx,cy,side,rot=rect; c,sn=math.cos(rot),math.sin(rot); k=side/256.0
    M=np.array([[c*k,-sn*k,cx+c*(0.5*k-side/2)-sn*(0.5*k-side/2)-0.5],[sn*k,c*k,cy+sn*(0.5*k-side/2)+c*(0.5*k-side/2)-0.5]])
    t=rep(proc,M,256,0)
    pts=rng.integers(0,256,size=(300,2))
    samples.append({'rect':list(rect),'points':[[int(u),int(v)]+list(map(float,t[v,u])) for u,v in pts]})
# letterboxed detector crop of the procedural image (zero border)
s,ox,oy=letterbox_params(w,h,224)
M=np.array([[1/s,0,(0.5-ox)/s-0.5],[0,1/s,(0.5-oy)/s-0.5]])
t=rep(proc,M,224,0)
pts=rng.integers(0,224,size=(300,2))
meta['sampler']={'width':w,'height':h,'rotated':samples,'letterbox':[[int(u),int(v)]+list(map(float,t[v,u])) for u,v in pts]}
json.dump(meta, open(OUT+'blazepose_fixture.json','w'))
print('detections', [len(d['expected']) for d in meta['detector']], 'landmark sets', len(meta['landmarks']))
