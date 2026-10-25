# Build test scenes resembling the game set-up: 16:9 landscape frame, two people side by side.
import os, cv2, numpy as np
I=os.environ.get('CLRG_IMGS', 'imgs').rstrip('/')+'/'
room=cv2.imread(I+'living_room.jpg')
man=cv2.imread(I+'male_full_height_hands.jpg')     # 638x1000 full body
yoga=cv2.imread(I+'pose.jpg')                       # 1000x667
def frame(w=1280,h=720):
    return cv2.resize(room,(w,h))
def paste(dst, src, x, y, h):
    s=h/src.shape[0]; src=cv2.resize(src,(int(src.shape[1]*s),h))
    H,W=dst.shape[:2]; x0=max(0,x); y0=max(0,y); x1=min(W,x+src.shape[1]); y1=min(H,y+src.shape[0])
    dst[y0:y1,x0:x1]=src[y0-y:y1-y, x0-x:x1-x]
    return dst
# scene A: two standing players (same source flipped for the second), ~80% frame height
a=frame(); manc=man[0:1000, 60:600]
paste(a, manc, 80, 120, 600); paste(a, cv2.flip(manc,1), 700, 110, 610)
cv2.imwrite('sceneA_two_players.png', a)
# scene B: yoga person (arms out) left, standing man right
b=frame(); yc=yoga[60:667, 250:850]
paste(b, yc, 20, 140, 560); paste(b, manc, 760, 100, 620)
cv2.imwrite('sceneB_mixed.png', b)
# scene C: two players + small spectator in background centre
c=a.copy(); paste(c, manc, 560, 200, 260)
cv2.imwrite('sceneC_spectator.png', c)
# scene D: empty room
cv2.imwrite('sceneD_empty.png', frame())
# scene E: real two-person photo
cv2.imwrite('sceneE_real_pair.png', cv2.imread(I+'man-woman-okay.jpg'))
print('ok')
# scene F: two players further back (~45% of frame height) - typical living-room distance in landscape
f=frame()
def paste_bottom(dst, src, cx, bottom, h):
    s=h/src.shape[0]; src=cv2.resize(src,(int(src.shape[1]*s),h)); x=int(cx-src.shape[1]/2); y=int(bottom-h)
    dst[y:y+h, x:x+src.shape[1]]=src
    return dst
paste_bottom(f, manc, 1280*0.3, 650, 330); paste_bottom(f, cv2.flip(manc,1), 1280*0.7, 650, 330)
cv2.imwrite('sceneF_far_players.png', f)
print('ok F')
