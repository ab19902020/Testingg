import json, os, sys, cv2, numpy as np
MODELS=os.environ.get('CLRG_MODELS', 'models')
import mediapipe as mp
from mediapipe.tasks import python as mpt
from mediapipe.tasks.python import vision
scenes=['sceneA_two_players.png','sceneB_mixed.png','sceneC_spectator.png','sceneD_empty.png','sceneE_real_pair.png']
out={}
for variant in ['lite','full']:
    opts=vision.PoseLandmarkerOptions(base_options=mpt.BaseOptions(model_asset_path=f'{MODELS}/pose_landmarker_{variant}.task'),
        running_mode=vision.RunningMode.IMAGE, num_poses=4, min_pose_detection_confidence=0.5, min_pose_presence_confidence=0.5)
    lm=vision.PoseLandmarker.create_from_options(opts)
    for s in scenes:
        img=mp.Image.create_from_file(s)
        r=lm.detect(img)
        poses=[[[p.x,p.y,p.z,p.visibility,p.presence] for p in pose] for pose in r.pose_landmarks]
        out[f'{variant}/{s}']=poses
        print(variant, s, 'poses:', len(poses), ['hipx=%.2f'%((p[23][0]+p[24][0])/2) for p in poses])
json.dump(out, open('mp_reference.json','w'))
