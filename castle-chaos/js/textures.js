import * as THREE from 'three';

// Procedural textures drawn on canvases at start-up (no image files).
// Each is made once and cached; repeat() hands out a clone with its own tiling.

const cache = new Map();

function canvas(w, h, draw) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  draw(c.getContext('2d'), w, h);
  return c;
}

function rand(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function speckle(g, w, h, n, colors, size, r) {
  for (let i = 0; i < n; i++) {
    g.fillStyle = colors[Math.floor(r() * colors.length)];
    const s = size * (0.5 + r());
    g.fillRect(r() * w, r() * h, s, s);
  }
}

function make(name, w, h, draw, { srgb = true } = {}) {
  if (cache.has(name)) return cache.get(name);
  const t = new THREE.CanvasTexture(canvas(w, h, draw));
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  if (srgb) t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 8;
  t.userData.cached = true;
  cache.set(name, t);
  return t;
}

const DRAW = {
  grass(g, w, h) {
    const r = rand(7);
    g.fillStyle = '#5fb040';
    g.fillRect(0, 0, w, h);
    // soft patches
    for (let i = 0; i < 40; i++) {
      const x = r() * w;
      const y = r() * h;
      const rad = 20 + r() * 50;
      const grd = g.createRadialGradient(x, y, 0, x, y, rad);
      const c = r() < 0.5 ? 'rgba(120,200,80,0.35)' : 'rgba(60,130,40,0.35)';
      grd.addColorStop(0, c);
      grd.addColorStop(1, 'rgba(0,0,0,0)');
      g.fillStyle = grd;
      for (const ox of [-w, 0, w]) for (const oy of [-h, 0, h]) g.fillRect(x - rad + ox, y - rad + oy, rad * 2, rad * 2);
    }
    // blades
    for (let i = 0; i < 2600; i++) {
      const x = r() * w;
      const y = r() * h;
      const l = 3 + r() * 6;
      g.strokeStyle = r() < 0.5 ? 'rgba(140,215,90,0.55)' : 'rgba(55,120,35,0.5)';
      g.lineWidth = 1 + r();
      g.beginPath();
      g.moveTo(x, y);
      g.lineTo(x + (r() - 0.5) * 3, y - l);
      g.stroke();
    }
  },
  dirt(g, w, h) {
    const r = rand(11);
    g.fillStyle = '#c9a066';
    g.fillRect(0, 0, w, h);
    speckle(g, w, h, 1800, ['#b88d55', '#d9b37a', '#a8814c', '#e3c48e'], 2.5, r);
    for (let i = 0; i < 30; i++) {
      g.fillStyle = 'rgba(150,110,60,0.25)';
      g.beginPath();
      g.ellipse(r() * w, r() * h, 6 + r() * 20, 3 + r() * 8, r() * 3, 0, Math.PI * 2);
      g.fill();
    }
  },
  sand(g, w, h) {
    const r = rand(13);
    g.fillStyle = '#e8d29e';
    g.fillRect(0, 0, w, h);
    speckle(g, w, h, 2400, ['#dcc58f', '#f3e1b3', '#cdb47c'], 1.6, r);
    // raked lines
    g.strokeStyle = 'rgba(180,150,90,0.25)';
    g.lineWidth = 2;
    for (let y = 0; y < h; y += 10) {
      g.beginPath();
      for (let x = 0; x <= w; x += 8) g.lineTo(x, y + Math.sin(x * 0.05) * 2);
      g.stroke();
    }
  },
  wood(g, w, h) {
    const r = rand(17);
    const plank = h / 4;
    for (let i = 0; i < 4; i++) {
      const base = [138, 90, 52].map((v) => v + (r() - 0.5) * 30);
      g.fillStyle = `rgb(${base.join(',')})`;
      g.fillRect(0, i * plank, w, plank);
      for (let k = 0; k < 18; k++) {
        g.strokeStyle = `rgba(70,40,20,${0.15 + r() * 0.2})`;
        g.lineWidth = 1;
        g.beginPath();
        const y = i * plank + r() * plank;
        g.moveTo(0, y);
        for (let x = 0; x <= w; x += 16) g.lineTo(x, y + Math.sin(x * 0.03 + k) * 2);
        g.stroke();
      }
      g.fillStyle = 'rgba(40,20,10,0.6)';
      g.fillRect(0, i * plank, w, 2);
    }
  },
  stone(g, w, h) {
    const r = rand(19);
    g.fillStyle = '#6f6a63';
    g.fillRect(0, 0, w, h);
    const rows = 6;
    const rh = h / rows;
    for (let y = 0; y < rows; y++) {
      let x = y % 2 ? -rh * 0.7 : 0;
      while (x < w) {
        const bw = rh * (1.2 + r() * 1.2);
        const v = 125 + (r() - 0.5) * 40;
        g.fillStyle = `rgb(${v},${v - 4},${v - 10})`;
        g.beginPath();
        g.roundRect(x + 2, y * rh + 2, bw - 4, rh - 4, 5);
        g.fill();
        g.fillStyle = 'rgba(255,255,255,0.12)';
        g.fillRect(x + 4, y * rh + 3, bw - 8, 3);
        x += bw;
      }
    }
    speckle(g, w, h, 500, ['rgba(0,0,0,0.15)', 'rgba(255,255,255,0.1)'], 2, r);
  },
  plaster(g, w, h) {
    const r = rand(23);
    g.fillStyle = '#f5f1e6';
    g.fillRect(0, 0, w, h);
    speckle(g, w, h, 900, ['rgba(0,0,0,0.035)', 'rgba(150,130,100,0.06)'], 3, r);
  },
  roof(g, w, h) {
    const rows = 8;
    const rh = h / rows;
    for (let y = 0; y < rows; y++) {
      const grd = g.createLinearGradient(0, y * rh, 0, (y + 1) * rh);
      grd.addColorStop(0, '#3a4a5e');
      grd.addColorStop(1, '#1f2833');
      g.fillStyle = grd;
      g.fillRect(0, y * rh, w, rh);
      for (let x = (y % 2) * 8; x < w; x += 16) {
        g.fillStyle = 'rgba(0,0,0,0.35)';
        g.fillRect(x, y * rh, 2, rh);
      }
    }
  },
  tarmac(g, w, h) {
    const r = rand(29);
    g.fillStyle = '#4b4e57';
    g.fillRect(0, 0, w, h);
    speckle(g, w, h, 3000, ['#3f424a', '#5a5d66', '#666a73', '#383a41'], 1.5, r);
  },
  stripes(g, w, h) {
    // red and white padding, like inflatable game-show kit
    g.fillStyle = '#ffffff';
    g.fillRect(0, 0, w, h);
    g.fillStyle = '#e8222e';
    for (let i = -h; i < w; i += w / 4) {
      g.beginPath();
      g.moveTo(i, h);
      g.lineTo(i + w / 8, h);
      g.lineTo(i + w / 8 + h, 0);
      g.lineTo(i + h, 0);
      g.fill();
    }
  },
  checker(g, w, h) {
    const n = 4;
    const s = w / n;
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        g.fillStyle = (x + y) % 2 ? '#3f8ae0' : '#eaf4ff';
        g.fillRect(x * s, y * s, s, s);
      }
    }
  },
  shadow(g, w, h) {
    const grd = g.createRadialGradient(w / 2, h / 2, 0, w / 2, h / 2, w / 2);
    grd.addColorStop(0, 'rgba(0,0,0,0.55)');
    grd.addColorStop(0.6, 'rgba(0,0,0,0.25)');
    grd.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = grd;
    g.fillRect(0, 0, w, h);
  },
  ring(g, w, h) {
    g.strokeStyle = '#ffd23f';
    g.lineWidth = w * 0.07;
    g.beginPath();
    g.arc(w / 2, h / 2, w * 0.4, 0, Math.PI * 2);
    g.stroke();
    g.strokeStyle = 'rgba(255,210,63,0.35)';
    g.lineWidth = w * 0.16;
    g.beginPath();
    g.arc(w / 2, h / 2, w * 0.4, 0, Math.PI * 2);
    g.stroke();
  },
  cloud(g, w, h) {
    const grd = g.createRadialGradient(w / 2, h / 2, 0, w / 2, h / 2, w / 2);
    grd.addColorStop(0, 'rgba(255,255,255,1)');
    grd.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = grd;
    g.fillRect(0, 0, w, h);
  },
};

const SIZES = { stripes: 128, checker: 128, grass: 512, dirt: 256, sand: 256, wood: 256, stone: 256, plaster: 128, roof: 128, tarmac: 256, shadow: 64, ring: 128, cloud: 64 };

/** Get a texture by name: grass, dirt, sand, wood, stone, plaster, roof, tarmac, shadow, ring. */
export function tex(name) {
  const s = SIZES[name];
  return make(name, s, s, DRAW[name]);
}

/** A copy of the texture that tiles rx by ry times. */
export function repeat(name, rx, ry = rx) {
  const key = `${name}@${rx.toFixed(2)}x${ry.toFixed(2)}`;
  if (cache.has(key)) return cache.get(key);
  const t = tex(name).clone();
  t.repeat.set(rx, ry);
  t.needsUpdate = true;
  t.userData.cached = true;
  cache.set(key, t);
  return t;
}
