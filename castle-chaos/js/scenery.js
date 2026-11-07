import * as THREE from 'three';
import { mat, textTexture } from './fx.js';

export const COLORS = {
  grass: 0x6cc24a,
  grassDark: 0x4e9a35,
  dirt: 0xc9a46b,
  sand: 0xe6cf9a,
  stone: 0x9b9a96,
  wall: 0xf4f1e8,
  roof: 0x2d3a4a,
  gold: 0xffc93c,
  red: 0xd7263d,
  pink: 0xff7aa8,
  wood: 0x7a4b2a,
  water: 0x2f9fd8,
  mud: 0x5b3a1e,
};

function box(parent, sx, sy, sz, color, x, y, z, opts = {}) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(sx, sy, sz), opts.material || mat(color, opts));
  m.position.set(x, y, z);
  if (opts.shadow !== false) {
    m.castShadow = !!opts.cast;
    m.receiveShadow = true;
  }
  parent.add(m);
  return m;
}
export { box };

export function ground(parent, size = 400, color = COLORS.grass, y = 0) {
  const geo = new THREE.PlaneGeometry(size, size, 40, 40);
  geo.rotateX(-Math.PI / 2);
  const pos = geo.attributes.position;
  const cols = [];
  const a = new THREE.Color(color);
  const b = new THREE.Color(COLORS.grassDark);
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i);
    const z = pos.getZ(i);
    const n = Math.sin(x * 0.11) * Math.cos(z * 0.13) * 0.5 + 0.5;
    const c = a.clone().lerp(b, n * 0.6);
    cols.push(c.r, c.g, c.b);
    const far = Math.max(0, Math.hypot(x, z) - 90);
    pos.setY(i, -0.05 + far * far * 0.0012 * (0.5 + n));
  }
  geo.setAttribute('color', new THREE.Float32BufferAttribute(cols, 3));
  geo.computeVertexNormals();
  const m = new THREE.Mesh(geo, new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 1 }));
  m.receiveShadow = true;
  m.position.y = y;
  parent.add(m);
  return m;
}

/** A Japanese castle: stone base, white walls, dark tiered roofs with gold trim. */
export function castle(parent, x, z, s = 1) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  g.scale.setScalar(s);
  parent.add(g);
  // sloped stone base
  const baseGeo = new THREE.CylinderGeometry(9 / Math.SQRT2, 12 / Math.SQRT2, 6, 4, 1);
  baseGeo.rotateY(Math.PI / 4);
  const base = new THREE.Mesh(baseGeo, mat(COLORS.stone, { flat: true }));
  base.position.y = 3;
  g.add(base);
  const tiers = [
    { w: 8, d: 6.5, h: 3.4 },
    { w: 6.4, d: 5.2, h: 3 },
    { w: 4.8, d: 4, h: 2.6 },
    { w: 3.2, d: 2.8, h: 2.2 },
  ];
  let y = 6;
  for (let i = 0; i < tiers.length; i++) {
    const t = tiers[i];
    box(g, t.w, t.h, t.d, COLORS.wall, 0, y + t.h / 2, 0, { shadow: false });
    // windows
    for (let k = -1; k <= 1; k++) {
      if (i === 3 && k !== 0) continue;
      box(g, 0.6, 0.8, 0.05, 0x222222, k * t.w * 0.28, y + t.h * 0.5, t.d / 2 + 0.03, { shadow: false });
    }
    y += t.h;
    // roof
    const rw = t.w * 1.45;
    const rd = t.d * 1.45;
    const roofGeo = new THREE.CylinderGeometry(0.5, Math.SQRT2 / 2, 1, 4, 1);
    roofGeo.rotateY(Math.PI / 4);
    const roof = new THREE.Mesh(roofGeo, mat(COLORS.roof, { flat: true }));
    roof.scale.set(rw, i === 3 ? 2.2 : 1.3, rd);
    roof.position.y = y + (i === 3 ? 1.1 : 0.65);
    g.add(roof);
    box(g, rw * 0.98, 0.15, rd * 0.98, COLORS.gold, 0, y + 0.05, 0, { shadow: false });
    // little gable on the front
    if (i < 3) {
      const gable = new THREE.Mesh(new THREE.ConeGeometry(1.2, 1.1, 3), mat(COLORS.roof, { flat: true }));
      gable.rotation.x = -Math.PI / 2;
      gable.rotation.z = Math.PI;
      gable.scale.set(1, 1, 0.5);
      gable.position.set(0, y + 1.2, rd * 0.38);
      g.add(gable);
    }
    y += i === 3 ? 0 : 1.1;
  }
  // golden shachi fish on the top ridge
  for (const sx of [-0.9, 0.9]) {
    const fish = new THREE.Mesh(new THREE.ConeGeometry(0.25, 0.8, 6), mat(COLORS.gold, { rough: 0.3, metal: 0.6 }));
    fish.position.set(sx, y + 2.4, 0);
    fish.rotation.z = sx > 0 ? 0.5 : -0.5;
    g.add(fish);
  }
  // corner turrets and a gate
  for (const sx of [-1, 1]) {
    const tower = new THREE.Group();
    tower.position.set(sx * 14, 0, 2);
    g.add(tower);
    const tb = new THREE.Mesh(baseGeo, mat(COLORS.stone, { flat: true }));
    tb.scale.set(0.35, 0.6, 0.35);
    tb.position.y = 1.8;
    tower.add(tb);
    box(tower, 2.6, 2, 2.6, COLORS.wall, 0, 4.6, 0, { shadow: false });
    const rg = new THREE.CylinderGeometry(0.5, Math.SQRT2 / 2, 1, 4, 1);
    rg.rotateY(Math.PI / 4);
    const r = new THREE.Mesh(rg, mat(COLORS.roof, { flat: true }));
    r.scale.set(4, 1.6, 4);
    r.position.y = 6.4;
    tower.add(r);
    box(g, 9, 2.2, 1, COLORS.wall, sx * 9.5, 1.1, 5, { shadow: false });
    box(g, 9.4, 0.5, 1.6, COLORS.roof, sx * 9.5, 2.4, 5, { shadow: false });
  }
  box(g, 4, 3.2, 1.4, COLORS.wood, 0, 1.6, 5.2, { shadow: false });
  box(g, 5.2, 0.6, 2, COLORS.roof, 0, 3.4, 5.2, { shadow: false });
  return g;
}

export function cherryTree(parent, x, z, s = 1) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  g.scale.setScalar(s);
  const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.18, 0.3, 2.4, 7), mat(0x5a3b28));
  trunk.position.y = 1.2;
  trunk.rotation.z = 0.08;
  trunk.castShadow = true;
  g.add(trunk);
  const blossom = mat(Math.random() < 0.7 ? 0xffb7d0 : 0xff94bb, { flat: true });
  const geo = new THREE.IcosahedronGeometry(1, 0);
  for (let i = 0; i < 4; i++) {
    const b = new THREE.Mesh(geo, blossom);
    b.position.set((Math.random() - 0.5) * 1.6, 2.6 + Math.random() * 0.9, (Math.random() - 0.5) * 1.6);
    b.scale.setScalar(0.8 + Math.random() * 0.6);
    b.castShadow = true;
    g.add(b);
  }
  parent.add(g);
  return g;
}

export function pineTree(parent, x, z, s = 1) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  g.scale.setScalar(s);
  const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.15, 0.25, 1.4, 6), mat(0x5a3b28));
  trunk.position.y = 0.7;
  g.add(trunk);
  const green = mat(0x2f6b3a, { flat: true });
  for (let i = 0; i < 3; i++) {
    const c = new THREE.Mesh(new THREE.ConeGeometry(1.4 - i * 0.35, 1.6, 7), green);
    c.position.y = 1.6 + i * 0.9;
    c.castShadow = true;
    g.add(c);
  }
  parent.add(g);
  return g;
}

/** Scatter trees along both sides of a course. */
export function forest(parent, { xMin, xMax, zFrom, zTo, gap = 6, rows = 2, skip = null }) {
  for (let z = zFrom; z > zTo; z -= gap) {
    if (skip && skip(z)) continue;
    for (const side of [-1, 1]) {
      for (let r = 0; r < rows; r++) {
        const x = (side < 0 ? xMin : xMax) + side * (3 + r * 5 + Math.random() * 3);
        const zz = z + (Math.random() - 0.5) * gap;
        if (Math.random() < 0.55) cherryTree(parent, x, zz, 0.9 + Math.random() * 0.5);
        else pineTree(parent, x, zz, 0.9 + Math.random() * 0.6);
      }
    }
  }
}

const BANNER_COLORS = [0xd7263d, 0xffffff, 0x1d3557, 0xffc93c, 0xf15bb5];
/** Tall nobori flag on a pole. */
export function banner(parent, x, z, color = null, h = 4) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.05, 0.05, h, 6), mat(0x333333));
  pole.position.y = h / 2;
  g.add(pole);
  const col = color ?? BANNER_COLORS[Math.floor(Math.random() * BANNER_COLORS.length)];
  const flag = new THREE.Mesh(new THREE.PlaneGeometry(0.8, h * 0.7), new THREE.MeshStandardMaterial({ color: col, side: THREE.DoubleSide, roughness: 0.9 }));
  flag.position.set(0.42, h * 0.6, 0);
  g.add(flag);
  const dot = new THREE.Mesh(new THREE.CircleGeometry(0.25, 16), new THREE.MeshBasicMaterial({ color: col === 0xffffff ? 0xd7263d : 0xffffff, side: THREE.DoubleSide }));
  dot.position.set(0.42, h * 0.7, 0.01);
  g.add(dot);
  parent.add(g);
  return g;
}

/** Red torii gate with a sign on top. Returns the group. */
export function torii(parent, x, y, z, width = 10, text = 'GOAL', signColor = '#1d1d1d') {
  const g = new THREE.Group();
  g.position.set(x, y, z);
  const red = mat(0xd7263d);
  const black = mat(0x1d1d1d);
  const h = 8.6;
  for (const sx of [-1, 1]) {
    const post = new THREE.Mesh(new THREE.CylinderGeometry(0.38, 0.45, h, 10), red);
    post.position.set(sx * width / 2, h / 2, 0);
    post.castShadow = true;
    g.add(post);
    const foot = new THREE.Mesh(new THREE.CylinderGeometry(0.42, 0.42, 0.5, 10), black);
    foot.position.set(sx * width / 2, 0.25, 0);
    g.add(foot);
  }
  box(g, width + 2.4, 0.45, 0.7, 0x1d1d1d, 0, h + 0.3, 0, { cast: true });
  box(g, width + 1.6, 0.4, 0.6, 0xd7263d, 0, h - 0.2, 0, { cast: true });
  box(g, width + 0.6, 0.3, 0.4, 0xd7263d, 0, h - 1.2, 0, { cast: true });
  if (text) {
    const sign = new THREE.Mesh(
      new THREE.PlaneGeometry(3.6, 1.1),
      new THREE.MeshBasicMaterial({ map: textTexture(text, { w: 512, h: 156, bg: signColor, fg: '#ffd23f', stroke: null, size: 92, border: '#ffd23f' }) }),
    );
    sign.position.set(0, h - 0.7, 0.36);
    g.add(sign);
  }
  parent.add(g);
  return g;
}

export function mountains(parent, radius = 190) {
  const rock = mat(0x6f8a7a, { flat: true });
  const snow = mat(0xffffff, { flat: true });
  for (let i = 0; i < 16; i++) {
    const a = (i / 16) * Math.PI * 2 + Math.random() * 0.2;
    const h = 30 + Math.random() * 40;
    const m = new THREE.Mesh(new THREE.ConeGeometry(h * 0.9, h, 6), rock);
    m.position.set(Math.cos(a) * radius, h / 2 - 4, Math.sin(a) * radius - 40);
    m.rotation.y = Math.random();
    parent.add(m);
  }
  // one big snowy peak behind the castle
  const fuji = new THREE.Mesh(new THREE.ConeGeometry(90, 70, 9), mat(0x5d7a9a, { flat: true }));
  fuji.position.set(30, 30, -260);
  parent.add(fuji);
  const cap = new THREE.Mesh(new THREE.ConeGeometry(27, 21, 9), snow);
  cap.position.set(30, 55.5, -260);
  parent.add(cap);
}

/** Grandstand full of cheering spectators. Returns an updater. */
export function crowdStand(parent, x, z, length = 20, facing = 1, rows = 4) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  g.rotation.y = facing > 0 ? Math.PI / 2 : -Math.PI / 2;
  parent.add(g);
  for (let r = 0; r < rows; r++) {
    box(g, length, 0.6 + r * 0.6, 1.2, r % 2 ? 0x8d99ae : 0xa7b0c0, 0, (0.6 + r * 0.6) / 2, -r * 1.2, { shadow: false });
  }
  box(g, length + 0.4, 0.3, rows * 1.2 + 0.6, 0xd7263d, 0, rows * 0.6 + 2.6, -(rows - 1) * 0.6, { shadow: false });
  for (const sx of [-1, 1]) box(g, 0.2, rows * 0.6 + 2.6, 0.2, 0x444444, sx * length / 2, (rows * 0.6 + 2.6) / 2, 0.5, { shadow: false });
  const perRow = Math.floor(length / 0.7);
  const count = perRow * rows;
  const geo = new THREE.CapsuleGeometry(0.18, 0.3, 3, 6);
  const inst = new THREE.InstancedMesh(geo, new THREE.MeshLambertMaterial(), count);
  const people = [];
  const cols = [0xe63946, 0xffd23f, 0x2a6fdb, 0xffffff, 0x2bb673, 0xff8c1a, 0xf15bb5, 0x222222];
  let i = 0;
  for (let r = 0; r < rows; r++) {
    for (let k = 0; k < perRow; k++) {
      const px = -length / 2 + 0.35 + k * 0.7 + (Math.random() - 0.5) * 0.15;
      people.push({ x: px, y: 0.6 + r * 0.6 + 0.35, z: -r * 1.2, ph: Math.random() * 6, sp: 6 + Math.random() * 6 });
      inst.setColorAt(i++, new THREE.Color(cols[Math.floor(Math.random() * cols.length)]));
    }
  }
  g.add(inst);
  const m4 = new THREE.Matrix4();
  let excitement = 0.3;
  return {
    cheer(v = 1) { excitement = Math.max(excitement, v); },
    update(t, dt) {
      excitement = Math.max(0.25, excitement - dt * 0.4);
      for (let j = 0; j < people.length; j++) {
        const p = people[j];
        const jump = Math.max(0, Math.sin(t * p.sp + p.ph)) * 0.25 * excitement;
        m4.makeTranslation(p.x, p.y + jump, p.z);
        inst.setMatrixAt(j, m4);
      }
      inst.instanceMatrix.needsUpdate = true;
    },
  };
}

/** Rippling water surface. Returns an updater. */
export function water(parent, w, d, x, y, z) {
  const geo = new THREE.PlaneGeometry(w, d, Math.min(60, Math.ceil(w / 2)), Math.min(60, Math.ceil(d / 2)));
  geo.rotateX(-Math.PI / 2);
  const m = new THREE.Mesh(geo, new THREE.MeshStandardMaterial({ color: COLORS.water, roughness: 0.15, metalness: 0.1, transparent: true, opacity: 0.88 }));
  m.position.set(x, y, z);
  m.receiveShadow = true;
  m.userData.dynamic = true;
  parent.add(m);
  const pos = geo.attributes.position;
  const base = Float32Array.from(pos.array);
  return {
    mesh: m,
    update(t) {
      for (let i = 0; i < pos.count; i++) {
        const bx = base[i * 3];
        const bz = base[i * 3 + 2];
        pos.array[i * 3 + 1] = Math.sin(bx * 0.6 + t * 1.6) * 0.06 + Math.cos(bz * 0.5 + t * 1.3) * 0.06;
      }
      pos.needsUpdate = true;
      geo.computeVertexNormals();
    },
  };
}

/** Paper lantern on a post. */
export function lantern(parent, x, z, h = 2.2) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  const post = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.06, h, 6), mat(0x333333));
  post.position.y = h / 2;
  g.add(post);
  const l = new THREE.Mesh(new THREE.SphereGeometry(0.32, 12, 8), new THREE.MeshStandardMaterial({ color: 0xff4d4d, emissive: 0x991111, roughness: 0.6 }));
  l.scale.y = 1.3;
  l.position.y = h + 0.2;
  g.add(l);
  parent.add(g);
  return g;
}
