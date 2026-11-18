import numpy as np, subprocess
sr=22050
raw=subprocess.run(['ffmpeg','-v','error','-i','song.mp3','-ac','1','-ar',str(sr),'-f','f32le','-'],capture_output=True).stdout
x=np.frombuffer(raw,np.float32)
fps=30; hop=sr//fps; n=len(x)//hop
win=2048; w=np.hanning(win)
xp=np.pad(x,(win//2,win))
freqs=np.fft.rfftfreq(win,1/sr)
S=np.empty((n,win//2+1),np.float32)
for i in range(n):
    S[i]=np.abs(np.fft.rfft(xp[i*hop:i*hop+win]*w))
def band(a,b): m=(freqs>=a)&(freqs<b); return S[:,m].mean(1)
bass=band(30,150); mid=band(300,2500); high=band(4000,10000)
rms=np.sqrt(np.convolve(x**2,np.ones(hop)/hop,'same')[::hop][:n])
# 48 log bands for spectrum
bi=np.unique(np.round(np.geomspace(4,560,60)).astype(int))
bi=np.round(np.interp(np.linspace(0,len(bi)-1,47),np.arange(len(bi)),bi)).astype(int)
for k in range(1,len(bi)):
    bi[k]=max(bi[k],bi[k-1]+1)
spec=np.stack([S[:,bi[k]:bi[k+1]].mean(1) for k in range(46)],1)
spec=np.log1p(spec*4); spec/=np.percentile(spec,99.5,axis=0)+1e-6; spec=spec**1.4
def norm(v): v=np.log1p(v*10); return np.clip(v/np.percentile(v,99),0,1.2)
# onset
flux=np.maximum(np.diff(np.log1p(S[:,:200]),axis=0),0).sum(1); flux=np.r_[0,flux]
fl=flux-np.convolve(flux,np.ones(15)/15,'same'); fl=np.maximum(fl,0)
ac=np.correlate(fl,fl,'full')[len(fl)-1:]
lags=np.arange(len(ac)); bpms=60*fps/np.maximum(lags,1)
m=(bpms>80)&(bpms<170); lag=lags[m][np.argmax(ac[m])]
print('bpm~',60*fps/lag,'frames',n)
np.savez('feat.npz',audio=x,bass=norm(bass),mid=norm(mid),high=norm(high),rms=rms/np.percentile(rms,99),spec=np.clip(spec,0,1.3),flux=fl/np.percentile(fl,99))
e=np.convolve(rms,np.ones(fps*2)/(fps*2),'same')
for t in range(0,n,fps*5): print(t//fps, round(float(e[t]/np.percentile(rms,99)),2))
