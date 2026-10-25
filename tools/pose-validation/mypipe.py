"""Reference port of the BlazePose two-stage pipeline (mirrors the Kotlin implementation)."""
import math, os, numpy as np, cv2
MODELS=os.environ.get('CLRG_MODELS', 'models')
from ai_edge_litert.interpreter import Interpreter

def sigmoid(x): return 1.0/(1.0+np.exp(-x))

def ssd_anchors():
    strides=[8,16,32,32,32]; n=len(strides); anchors=[]
    layer=0
    while layer<n:
        count=0; last=layer
        while last<n and strides[last]==strides[layer]:
            count+=2  # aspect ratio 1.0 + interpolated scale
            last+=1
        s=strides[layer]; fh=math.ceil(224/s); fw=math.ceil(224/s)
        for y in range(fh):
            for x in range(fw):
                for _ in range(count):
                    anchors.append(((x+0.5)/fw,(y+0.5)/fh))
        layer=last
    return np.array(anchors,dtype=np.float32)
ANCH=ssd_anchors(); assert len(ANCH)==2254

def letterbox_params(rw, rh, size):
    s=size/max(rw,rh); return s,(size-rw*s)/2,(size-rh*s)/2

def sample_affine(img, M, size, border):
    # M maps tensor pixel centre coords -> image pixel coords (2x3). use cv2 with inverse map.
    out=cv2.warpAffine(img.astype(np.float32), M, (size,size), flags=cv2.INTER_LINEAR|cv2.WARP_INVERSE_MAP,
                       borderMode=cv2.BORDER_CONSTANT, borderValue=(border,border,border))
    return out

class Pipe:
    def __init__(self, variant):
        self.det=Interpreter(model_path=f'{MODELS}/{variant}/pose_detector.tflite'); self.det.allocate_tensors()
        self.lm=Interpreter(model_path=f'{MODELS}/{variant}/pose_landmarks_detector.tflite'); self.lm.allocate_tensors()
        o={d['name']:d['index'] for d in self.lm.get_output_details()}
        self.lm_out=(o['Identity'],o['Identity_1'],o['Identity_3'])
        od={d['name']:d['index'] for d in self.det.get_output_details()}
        self.det_out=(od['Identity'],od['Identity_1'])
        self.last_det_raw=None

    def detect(self, rgb, roi=None, min_score=0.5, max_det=4, iou=0.3):
        H,W=rgb.shape[:2]
        x0,y0,rw,rh = roi if roi else (0,0,W,H)
        s,ox,oy=letterbox_params(rw,rh,224)
        # tensor px (u+0.5) -> image px: x = x0 + ((u+0.5) - ox)/s ; cv2 uses pixel index u (centre convention matches with -0.5 terms)
        # image = x0 + (u + 0.5 - ox)/s - 0.5 ... cv2 maps dst pixel u -> src pixel coordinate directly (pixel centres at integers)
        M=np.array([[1/s,0,x0+(0.5-ox)/s-0.5],[0,1/s,y0+(0.5-oy)/s-0.5]],dtype=np.float64)
        t=sample_affine(rgb,M,224,0.0)/127.5-1.0
        self.det.set_tensor(self.det.get_input_details()[0]['index'], t[None].astype(np.float32)); self.det.invoke()
        raw=self.det.get_tensor(self.det_out[0])[0]; sc=self.det.get_tensor(self.det_out[1])[0,:,0]
        self.last_det_raw=(raw.copy(), sc.copy())
        return decode_detections(raw, sc, (x0,y0,rw,rh), min_score, max_det, iou)

    def landmarks(self, rgb, rect):
        cx,cy,side,rot=rect
        c,sn=math.cos(rot),math.sin(rot); k=side/256.0
        # tensor px (u,v) centre (u+0.5,v+0.5)/256-0.5 -> image: centre + R*(nx*side, ny*side)
        # image_x = cx + c*((u+0.5)*k - side/2) - sn*((v+0.5)*k - side/2) ; minus 0.5 for cv2 pixel-centre convention
        a=c*k; b=-sn*k; tx=cx + c*(0.5*k-side/2) - sn*(0.5*k-side/2) - 0.5
        d=sn*k; e=c*k;  ty=cy + sn*(0.5*k-side/2) + c*(0.5*k-side/2) - 0.5
        M=np.array([[a,b,tx],[d,e,ty]],dtype=np.float64)
        t=sample_affine(rgb,M,256,0.0)/255.0
        self.lm.set_tensor(self.lm.get_input_details()[0]['index'], t[None].astype(np.float32)); self.lm.invoke()
        raw=self.lm.get_tensor(self.lm_out[0])[0]; flag=float(self.lm.get_tensor(self.lm_out[1])[0,0])
        hm=self.lm.get_tensor(self.lm_out[2])[0]
        return raw.copy(), flag, hm.copy()

def decode_detections(raw, sc, roi, min_score, max_det, iou_thr):
    x0,y0,rw,rh=roi; s,ox,oy=letterbox_params(rw,rh,224)
    scores=sigmoid(np.clip(sc,-100,100))
    idx=np.where(scores>=min_score)[0]
    dets=[]
    for i in idx:
        r=raw[i]; ax,ay=ANCH[i]
        xc=r[0]/224+ax; yc=r[1]/224+ay; w=r[2]/224; h=r[3]/224
        kps=[(r[4+2*k]/224+ax, r[5+2*k]/224+ay) for k in range(4)]
        dets.append([scores[i], xc-w/2, yc-h/2, w, h, kps])
    dets.sort(key=lambda d:-d[0])
    out=[]
    while dets and len(out)<max_det:
        head=dets[0]; cand=[]; rem=[]
        for d in dets:
            (cand if box_iou(head,d)>iou_thr else rem).append(d)
        tot=sum(d[0] for d in cand)
        box=[sum(d[0]*d[j] for d in cand)/tot for j in range(1,5)]
        kps=[(sum(d[0]*d[5][k][0] for d in cand)/tot, sum(d[0]*d[5][k][1] for d in cand)/tot) for k in range(4)]
        out.append([head[0]]+box+[kps]); dets=rem
    # project tensor-normalised -> image px
    res=[]
    for d in out:
        f=lambda tx,ty: (x0+(tx*224-ox)/s, y0+(ty*224-oy)/s)
        res.append({'score':float(d[0]), 'box':(f(d[1],d[2]), f(d[1]+d[3],d[2]+d[4])), 'kps':[f(*k) for k in d[5]]})
    return res

def box_iou(a,b):
    ax0,ay0,aw,ah=a[1:5]; bx0,by0,bw,bh=b[1:5]
    ix=max(0,min(ax0+aw,bx0+bw)-max(ax0,bx0)); iy=max(0,min(ay0+ah,by0+bh)-max(ay0,by0))
    inter=ix*iy; uni=aw*ah+bw*bh-inter
    return inter/uni if uni>0 else 0

def rect_from_points(p0,p1,scale=1.25):
    cx,cy=p0; side=2*math.hypot(p1[0]-cx,p1[1]-cy)*scale
    rot=math.pi/2 - math.atan2(-(p1[1]-cy), p1[0]-cx)
    rot=rot-2*math.pi*math.floor((rot+math.pi)/(2*math.pi))
    return (cx,cy,side,rot)

def decode_landmarks(raw, hm, rect, refine=True):
    cx,cy,side,rot=rect; c,sn=math.cos(rot),math.sin(rot)
    L=raw.reshape(39,5)
    out=np.zeros((39,5))
    for i in range(39):
        x=L[i,0]/256; y=L[i,1]/256
        if refine and i<33 and hm is not None:
            x,y=refine_heatmap(hm, i, x, y)
        nx,ny=x-0.5,y-0.5
        out[i,0]=cx + (c*nx - sn*ny)*side
        out[i,1]=cy + (sn*nx + c*ny)*side
        out[i,2]=L[i,2]/256*side
        out[i,3]=sigmoid(L[i,3]); out[i,4]=sigmoid(L[i,4])
    return out

def refine_heatmap(hm, i, x, y, k=7, minc=0.5):
    Hh,Wh=hm.shape[:2]; off=(k-1)//2
    col=int(x*Wh); row=int(y*Hh)
    if col<0 or col>=Wh or row<0 or row>=Hh: return x,y
    r0,r1=max(0,row-off),min(Hh,row+off+1); c0,c1=max(0,col-off),min(Wh,col+off+1)
    w=sigmoid(hm[r0:r1,c0:c1,i]); s=w.sum()
    if w.max()<minc or s<=0: return x,y
    rr,cc=np.mgrid[r0:r1,c0:c1]
    return float((cc*w).sum()/Wh/s), float((rr*w).sum()/Hh/s)

def run_image(pipe, rgb, max_det=4):
    H,W=rgb.shape[:2]
    dets=pipe.detect(rgb, max_det=max_det)
    poses=[]
    for d in dets:
        rect=rect_from_points(d['kps'][0], d['kps'][1])
        raw,flag,hm=pipe.landmarks(rgb, rect)
        lm=decode_landmarks(raw,hm,rect)
        poses.append({'det':d,'rect':rect,'flag':flag,'lm':lm,'raw':raw,'hm':hm})
    return dets, poses
