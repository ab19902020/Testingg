import json, numpy as np, cv2, sys
sys.path.insert(0,'.')
from mypipe import *
ref=json.load(open('mp_reference.json'))
for variant in ['lite','full']:
    p=Pipe(variant)
    for s in ['sceneA_two_players.png','sceneB_mixed.png','sceneC_spectator.png','sceneD_empty.png','sceneE_real_pair.png']:
        bgr=cv2.imread(s); rgb=cv2.cvtColor(bgr,cv2.COLOR_BGR2RGB); H,W=rgb.shape[:2]
        dets,poses=run_image(p,rgb)
        rp=ref[f'{variant}/{s}']
        line=f'{variant} {s}: mine={len(poses)} ref={len(rp)} flags={[round(q["flag"],3) for q in poses]}'
        errs=[]
        for q in poses:
            lm=q['lm'][:33,:2]/[W,H]
            best=min(rp, key=lambda r: np.abs(np.array(r)[:33,:2]-lm).mean()) if rp else None
            if best is not None: errs.append(np.abs(np.array(best)[:33,:2]-lm).mean())
        print(line, 'meanAbsErr(norm)=', [round(e,4) for e in errs])
