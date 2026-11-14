import sys,cv2,numpy as np
out=sys.argv[1]; x,y,w,h=map(int,sys.argv[2].split(',')); frames=list(map(int,sys.argv[3].split(',')))
rows=[]
for p in sys.argv[4:]:
    c=cv2.VideoCapture(p); fs=[]; i=0
    while True:
        ok,f=c.read()
        if not ok: break
        if i in frames: fs.append(cv2.resize(f[y:y+h,x:x+w],(w*2,h*2),interpolation=cv2.INTER_NEAREST))
        i+=1
    rows.append(np.hstack(fs))
cv2.imwrite(out,np.vstack(rows))
