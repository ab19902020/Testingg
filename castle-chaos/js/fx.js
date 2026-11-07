import * as THREE from 'three';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
export { mergeGeometries };

// Shared materials (one per colour) keep draw-call setup cheap.
const matCache = new Map();
export function mat(color, { rough = 0.8, metal = 0, emissive = 0, flat = false, map = null, env = 1 } = {}) {
  const key = `${color}|${rough}|${metal}|${emissive}|${flat}|${map ? map.uuid : ''}|${env}`;
  let m = matCache.get(key);
  if (!m) {
    m = new THREE.MeshStandardMaterial({ color, roughness: rough, metalness: metal, emissive, flatShading: flat, map, envMapIntensity: env });
    matCache.set(key, m);
  }
  return m;
}

/** A canvas texture with big bold text, used for signs and banners. */
export function textTexture(text, { w = 512, h = 128, bg = null, fg = '#fff', stroke = '#000', font = 'Bungee, Impact, sans-serif', size = 80, border = null } = {}) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  const g = c.getContext('2d');
  if (bg) {
    g.fillStyle = bg;
    g.fillRect(0, 0, w, h);
  }
  if (border) {
    g.strokeStyle = border;
    g.lineWidth = 10;
    g.strokeRect(5, 5, w - 10, h - 10);
  }
  g.font = `${size}px ${font}`;
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  if (stroke) {
    g.lineWidth = size * 0.14;
    g.strokeStyle = stroke;
    g.lineJoin = 'round';
    g.strokeText(text, w / 2, h / 2 + size * 0.05);
  }
  g.fillStyle = fg;
  g.fillText(text, w / 2, h / 2 + size * 0.05);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 4;
  return t;
}

// ---------------------------------------------------------------------------
// Particles: one instanced mesh for every splash, paper scrap, rock chip and confetti.
// ---------------------------------------------------------------------------
class Particles {
  constructor() {
    this.max = 700;
    this.items = [];
    this.mesh = null;
  }
  attach(scene) {
    if (!this.mesh) {
      const geo = new THREE.BoxGeometry(1, 1, 1);
      const m = new THREE.MeshLambertMaterial({ color: 0xffffff });
      this.mesh = new THREE.InstancedMesh(geo, m, this.max);
      this.mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
      this.mesh.frustumCulled = false;
      this.mesh.count = 0;
      for (let i = 0; i < this.max; i++) this.mesh.setColorAt(i, new THREE.Color(1, 1, 1));
    }
    scene.add(this.mesh);
  }
  clear() {
    this.items.length = 0;
  }
  burst(pos, color, n = 12, speed = 4, size = 0.12, { up = 1, gravity = 14, life = 0.9, spin = 6, flat = false } = {}) {
    const col = new THREE.Color(color);
    for (let i = 0; i < n; i++) {
      if (this.items.length >= this.max) this.items.shift();
      const a = Math.random() * Math.PI * 2;
      const s = speed * (0.4 + Math.random() * 0.6);
      this.items.push({
        p: pos.clone(),
        v: new THREE.Vector3(Math.cos(a) * s, (0.5 + Math.random()) * speed * up, Math.sin(a) * s),
        r: new THREE.Euler(Math.random() * 6, Math.random() * 6, Math.random() * 6),
        spin,
        life: life * (0.6 + Math.random() * 0.6),
        max: life,
        size: size * (0.6 + Math.random() * 0.8),
        col: Array.isArray(color) ? new THREE.Color(color[i % color.length]) : col,
        g: gravity,
        flat,
      });
    }
  }
  confetti(pos, n = 60) {
    this.burst(pos, [0xff3b3b, 0xffd23f, 0x2bb673, 0x2a6fdb, 0xf15bb5, 0xffffff], n, 7, 0.16, { up: 1.6, gravity: 5, life: 3, spin: 10, flat: true });
  }
  update(dt) {
    if (!this.mesh) return;
    const m4 = new THREE.Matrix4();
    const q = new THREE.Quaternion();
    const sc = new THREE.Vector3();
    let n = 0;
    for (let i = this.items.length - 1; i >= 0; i--) {
      const it = this.items[i];
      it.life -= dt;
      if (it.life <= 0) {
        this.items.splice(i, 1);
        continue;
      }
      it.v.y -= it.g * dt;
      if (it.flat) {
        it.v.x *= 1 - dt * 1.5;
        it.v.z *= 1 - dt * 1.5;
      }
      it.p.addScaledVector(it.v, dt);
      it.r.x += it.spin * dt;
      it.r.y += it.spin * 0.7 * dt;
      q.setFromEuler(it.r);
      const k = Math.min(1, it.life / (it.max * 0.4));
      const s = it.size * k;
      if (it.flat) sc.set(s, s * 0.15, s * 1.4);
      else sc.set(s, s, s);
      m4.compose(it.p, q, sc);
      this.mesh.setMatrixAt(n, m4);
      this.mesh.setColorAt(n, it.col);
      n++;
    }
    this.mesh.count = n;
    this.mesh.instanceMatrix.needsUpdate = true;
    if (this.mesh.instanceColor) this.mesh.instanceColor.needsUpdate = true;
  }
}

export const particles = new Particles();

/**
 * Merge every static mesh under `root` into one mesh per material. Huge win for phones:
 * a course goes from hundreds of draw calls to a few dozen. Anything flagged
 * userData.dynamic (doors, stones, sumos, karts, water...) is left alone.
 */
export function bakeStatic(root) {
  root.updateMatrixWorld(true);
  const inv = new THREE.Matrix4().copy(root.matrixWorld).invert();
  const buckets = new Map();
  const victims = [];
  const walk = (o) => {
    if (o.userData.dynamic) return;
    if (o.isMesh && !o.isInstancedMesh && !Array.isArray(o.material) && o.geometry) {
      const g = o.geometry;
      const attrs = Object.keys(g.attributes).sort().join(',');
      const key = `${o.material.uuid}|${attrs}|${g.index ? 1 : 0}|${o.castShadow ? 1 : 0}|${o.receiveShadow ? 1 : 0}`;
      let b = buckets.get(key);
      if (!b) buckets.set(key, (b = { material: o.material, cast: o.castShadow, receive: o.receiveShadow, geos: [] }));
      const m = new THREE.Matrix4().multiplyMatrices(inv, o.matrixWorld);
      b.geos.push(g.clone().applyMatrix4(m));
      victims.push(o);
    }
    for (const ch of o.children.slice()) walk(ch);
  };
  walk(root);
  for (const v of victims) v.parent.remove(v);
  for (const b of buckets.values()) {
    const geo = b.geos.length === 1 ? b.geos[0] : mergeGeometries(b.geos, false);
    if (!geo) continue;
    for (const g of b.geos) if (g !== geo) g.dispose();
    const mesh = new THREE.Mesh(geo, b.material);
    mesh.castShadow = b.cast;
    mesh.receiveShadow = b.receive;
    root.add(mesh);
  }
}

/** Clone a geometry, bake a transform and paint it one colour (for vertex-coloured merges). */
export function painted(geo, color, x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0, sx = 1, sy = 1, sz = 1) {
  const g = geo.index ? geo.toNonIndexed() : geo.clone();
  const m = new THREE.Matrix4().compose(
    new THREE.Vector3(x, y, z),
    new THREE.Quaternion().setFromEuler(new THREE.Euler(rx, ry, rz)),
    new THREE.Vector3(sx, sy, sz),
  );
  g.applyMatrix4(m);
  const c = new THREE.Color(color);
  const n = g.attributes.position.count;
  const arr = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) {
    arr[i * 3] = c.r;
    arr[i * 3 + 1] = c.g;
    arr[i * 3 + 2] = c.b;
  }
  g.setAttribute('color', new THREE.BufferAttribute(arr, 3));
  return g;
}
