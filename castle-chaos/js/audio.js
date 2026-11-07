// Sound effects and music, all made in code with the Web Audio API (no sound files).

let ctx = null;
let master = null;
let sfxBus = null;
let musicBus = null;
let noiseBuf = null;
let muted = false;
let musicOn = false;
let musicTimer = null;
let nextBeat = 0;
let beat = 0;
let musicStyle = 'race';

export function initAudio() {
  if (ctx) {
    if (ctx.state === 'suspended') ctx.resume();
    return;
  }
  const AC = window.AudioContext || window.webkitAudioContext;
  if (!AC) return;
  ctx = new AC();
  master = ctx.createGain();
  master.gain.value = muted ? 0 : 0.8;
  master.connect(ctx.destination);
  sfxBus = ctx.createGain();
  sfxBus.gain.value = 0.9;
  sfxBus.connect(master);
  musicBus = ctx.createGain();
  musicBus.gain.value = 0.32;
  musicBus.connect(master);
  noiseBuf = ctx.createBuffer(1, ctx.sampleRate * 2, ctx.sampleRate);
  const d = noiseBuf.getChannelData(0);
  for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
}

export function setMuted(m) {
  muted = m;
  if (master) master.gain.setTargetAtTime(m ? 0 : 0.8, ctx.currentTime, 0.05);
}
export function isMuted() { return muted; }

function tone(freq, dur, { type = 'sine', vol = 0.3, slide = 0, attack = 0.005, delay = 0, bus = sfxBus } = {}) {
  if (!ctx) return;
  const t = ctx.currentTime + delay;
  const o = ctx.createOscillator();
  const g = ctx.createGain();
  o.type = type;
  o.frequency.setValueAtTime(freq, t);
  if (slide) o.frequency.exponentialRampToValueAtTime(Math.max(20, freq * slide), t + dur);
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(vol, t + attack);
  g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  o.connect(g).connect(bus);
  o.start(t);
  o.stop(t + dur + 0.05);
}

function noise(dur, { vol = 0.3, filter = 'lowpass', freq = 1000, q = 1, slide = 0, delay = 0, attack = 0.005, bus = sfxBus } = {}) {
  if (!ctx) return;
  const t = ctx.currentTime + delay;
  const s = ctx.createBufferSource();
  s.buffer = noiseBuf;
  const f = ctx.createBiquadFilter();
  f.type = filter;
  f.frequency.setValueAtTime(freq, t);
  if (slide) f.frequency.exponentialRampToValueAtTime(Math.max(30, freq * slide), t + dur);
  f.Q.value = q;
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(vol, t + attack);
  g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  s.connect(f).connect(g).connect(bus);
  s.start(t, Math.random());
  s.stop(t + dur + 0.05);
}

const SFX = {
  jump: () => tone(300 + Math.random() * 60, 0.18, { type: 'square', vol: 0.08, slide: 2.2 }),
  land: () => noise(0.08, { vol: 0.12, freq: 400 }),
  dive: () => noise(0.25, { vol: 0.15, filter: 'bandpass', freq: 900, slide: 0.4 }),
  splash: () => { noise(0.6, { vol: 0.45, freq: 2500, slide: 0.2 }); noise(0.3, { vol: 0.2, filter: 'highpass', freq: 3000, delay: 0.05 }); },
  mud: () => { noise(0.5, { vol: 0.45, freq: 300, slide: 0.3 }); tone(90, 0.4, { vol: 0.25, slide: 0.5 }); },
  rip: () => noise(0.22, { vol: 0.35, filter: 'highpass', freq: 1800, q: 0.5 }),
  thud: () => { tone(110, 0.25, { vol: 0.5, slide: 0.4 }); noise(0.12, { vol: 0.25, freq: 500 }); },
  boing: () => tone(180, 0.45, { type: 'triangle', vol: 0.35, slide: 2.5 }),
  crack: () => { noise(0.18, { vol: 0.35, filter: 'bandpass', freq: 1200, q: 2 }); tone(160, 0.2, { vol: 0.2, slide: 0.5 }); },
  rumble: () => noise(0.5, { vol: 0.2, freq: 160 }),
  smash: () => { noise(0.4, { vol: 0.4, freq: 900, slide: 0.3 }); tone(70, 0.3, { vol: 0.4, slide: 0.5 }); },
  grab: () => { tone(140, 0.3, { type: 'sawtooth', vol: 0.15, slide: 0.6 }); noise(0.1, { vol: 0.3, freq: 600 }); },
  throw: () => noise(0.4, { vol: 0.25, filter: 'bandpass', freq: 600, slide: 3 }),
  gong: () => {
    [1, 1.47, 2.09, 2.56, 3.2].forEach((m, i) => tone(110 * m, 2.6 - i * 0.3, { vol: 0.28 / (i + 1), attack: 0.01 }));
    noise(0.2, { vol: 0.2, freq: 900 });
  },
  beep: () => tone(660, 0.18, { type: 'square', vol: 0.12 }),
  go: () => { tone(990, 0.5, { type: 'square', vol: 0.14 }); tone(1320, 0.5, { type: 'square', vol: 0.08, delay: 0.02 }); },
  whistle: () => { tone(2200, 0.5, { type: 'sine', vol: 0.15 }); tone(2350, 0.5, { type: 'sine', vol: 0.07 }); },
  qualify: () => [523, 659, 784, 1047].forEach((f, i) => tone(f, 0.25, { type: 'square', vol: 0.1, delay: i * 0.09 })),
  eliminated: () => [392, 330, 262, 196].forEach((f, i) => tone(f, 0.35, { type: 'sawtooth', vol: 0.09, delay: i * 0.16 })),
  cheer: () => { noise(1.6, { vol: 0.22, filter: 'bandpass', freq: 1400, q: 0.7, attack: 0.15 }); noise(1.4, { vol: 0.12, filter: 'bandpass', freq: 2600, q: 1, attack: 0.2 }); },
  ooh: () => { tone(220, 0.9, { type: 'sawtooth', vol: 0.03, slide: 0.8, attack: 0.15 }); noise(0.9, { vol: 0.12, filter: 'bandpass', freq: 500, q: 2, attack: 0.15 }); },
  shoot: () => { noise(0.15, { vol: 0.25, filter: 'bandpass', freq: 1500, slide: 0.5 }); tone(400, 0.1, { vol: 0.08, slide: 0.5 }); },
  pop: () => { noise(0.15, { vol: 0.5, filter: 'highpass', freq: 1200 }); tone(800, 0.15, { type: 'square', vol: 0.1, slide: 0.3 }); },
  engine: () => tone(70, 0.15, { type: 'sawtooth', vol: 0.03, slide: 1.2 }),
  click: () => tone(880, 0.06, { type: 'square', vol: 0.06 }),
};

export function sfx(name, vol = 1) {
  if (!ctx || muted) return;
  const f = SFX[name];
  if (!f) return;
  if (vol !== 1) {
    const prev = sfxBus;
    const g = ctx.createGain();
    g.gain.value = vol;
    g.connect(prev);
    sfxBus = g;
    f();
    sfxBus = prev;
  } else f();
}

// ---------------------------------------------------------------------------
// Music: taiko drums plus a pentatonic tune, scheduled a little ahead of time.
// ---------------------------------------------------------------------------
const PENTA = [0, 2, 4, 7, 9, 12, 14, 16];
const TUNES = {
  race: { bpm: 150, root: 293.66, drums: 'X.x.X.xxX.x.X.xx', tune: [0, 2, 3, 2, 4, 3, 2, 0, 1, 2, 3, 5, 4, 3, 2, -1] },
  final: { bpm: 168, root: 261.63, drums: 'X.xxX.x.X.xxXxXx', tune: [5, 4, 3, 4, 5, 7, 5, 4, 3, 2, 3, 4, 2, 1, 0, -1] },
  menu: { bpm: 104, root: 329.63, drums: 'X...x...X...x.x.', tune: [0, -1, 2, -1, 3, 2, 4, -1, 3, -1, 2, 1, 0, -1, -1, -1] },
};

function scheduleStep(step, t) {
  const tune = TUNES[musicStyle];
  const d = tune.drums[step % 16];
  if (d === 'X') drum(t, 70, 0.5);
  else if (d === 'x') drum(t, 120, 0.22);
  if (step % 4 === 2) click(t);
  const n = tune.tune[(step >> 1) % 16];
  if (step % 2 === 0 && n >= 0) {
    const bar = Math.floor(step / 32) % 4;
    const shift = bar === 2 ? 2 : 0;
    const f = tune.root * Math.pow(2, PENTA[Math.min(PENTA.length - 1, n + shift)] / 12);
    pluck(t, f);
  }
  if (step % 8 === 0) {
    const f = tune.root / 2 * Math.pow(2, (PENTA[(Math.floor(step / 8)) % 4 === 3 ? 3 : 0]) / 12);
    bass(t, f);
  }
}

function drum(t, f, v) {
  const o = ctx.createOscillator();
  const g = ctx.createGain();
  o.frequency.setValueAtTime(f * 1.8, t);
  o.frequency.exponentialRampToValueAtTime(f * 0.7, t + 0.18);
  g.gain.setValueAtTime(v, t);
  g.gain.exponentialRampToValueAtTime(0.0001, t + 0.3);
  o.connect(g).connect(musicBus);
  o.start(t);
  o.stop(t + 0.35);
}
function click(t) {
  const s = ctx.createBufferSource();
  s.buffer = noiseBuf;
  const f = ctx.createBiquadFilter();
  f.type = 'highpass';
  f.frequency.value = 5000;
  const g = ctx.createGain();
  g.gain.setValueAtTime(0.08, t);
  g.gain.exponentialRampToValueAtTime(0.0001, t + 0.05);
  s.connect(f).connect(g).connect(musicBus);
  s.start(t, Math.random());
  s.stop(t + 0.06);
}
function pluck(t, f) {
  const o = ctx.createOscillator();
  const o2 = ctx.createOscillator();
  const g = ctx.createGain();
  o.type = 'triangle';
  o2.type = 'square';
  o.frequency.value = f;
  o2.frequency.value = f * 2.003;
  const g2 = ctx.createGain();
  g2.gain.value = 0.15;
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(0.16, t + 0.005);
  g.gain.exponentialRampToValueAtTime(0.0001, t + 0.35);
  o.connect(g);
  o2.connect(g2).connect(g);
  g.connect(musicBus);
  o.start(t); o2.start(t);
  o.stop(t + 0.4); o2.stop(t + 0.4);
}
function bass(t, f) {
  const o = ctx.createOscillator();
  const g = ctx.createGain();
  o.type = 'sawtooth';
  o.frequency.value = f;
  const fl = ctx.createBiquadFilter();
  fl.type = 'lowpass';
  fl.frequency.value = 500;
  g.gain.setValueAtTime(0.0001, t);
  g.gain.exponentialRampToValueAtTime(0.14, t + 0.01);
  g.gain.exponentialRampToValueAtTime(0.0001, t + 0.5);
  o.connect(fl).connect(g).connect(musicBus);
  o.start(t);
  o.stop(t + 0.55);
}

export function playMusic(style) {
  if (!ctx) return;
  musicStyle = style;
  if (musicOn) return;
  musicOn = true;
  nextBeat = ctx.currentTime + 0.1;
  beat = 0;
  musicTimer = setInterval(() => {
    if (!ctx) return;
    const stepDur = 60 / TUNES[musicStyle].bpm / 4;
    while (nextBeat < ctx.currentTime + 0.2) {
      scheduleStep(beat, nextBeat);
      nextBeat += stepDur;
      beat++;
    }
  }, 50);
}

export function stopMusic() {
  musicOn = false;
  if (musicTimer) clearInterval(musicTimer);
  musicTimer = null;
}
