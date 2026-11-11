import * as THREE from 'three';
import { mat, textTexture, mergeGeometries, painted } from './fx.js';
import { repeat, tex } from './textures.js';

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

/** Shared clock for swaying grass, flags and water. main.js advances it. */
export const wind = { time: { value: 0 } };

export function box(parent, sx, sy, sz, color, x, y, z, opts = {}) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(sx, sy, sz), opts.material || mat(color, opts));
  m.position.set(x, y, z);
  if (opts.shadow !== false) {
    m.castShadow = !!opts.cast;
    m.receiveShadow = true;
  }
  parent.add(m);
  return m;
}

/** Material with a tiling texture sized to the surface (texScale = metres per tile). */
export function texMat(name, color, w, d, { texScale = 4, rough = 0.9, metal = 0 } = {}) {
  return mat(color, { rough, metal, map: repeat(name, Math.max(1, w / texScale), Math.max(1, d / texScale)) });
}

// ---------------------------------------------------------------------------
// Ground: textured grass that rises into gentle hills far from the course.
// ---------------------------------------------------------------------------
export function ground(parent, size = 400, color = COLORS.grass, y = 0) {
  const geo = new THREE.PlaneGeometry(size, size, 60, 60);
  geo.rotateX(-Math.PI / 2);
  const pos = geo.attributes.position;
  const cols = [];
  const a = new THREE.Color(0xffffff);
  const b = new THREE.Color(0xb9d9a0);
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i);
    const z = pos.getZ(i);
    const n = Math.sin(x * 0.07) * Math.cos(z * 0.09) * 0.5 + 0.5;
    const c = a.clone().lerp(b, n * 0.8);
    cols.push(c.r, c.g, c.b);
    const far = Math.max(0, Math.hypot(x, z + 30) - 95);
    pos.setY(i, -0.05 + far * far * 0.0011 * (0.6 + n));
  }
  geo.setAttribute('color', new THREE.Float32BufferAttribute(cols, 3));
  geo.computeVertexNormals();
  const m = new THREE.Mesh(geo, new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.95, map: repeat('grass', size / 7), color: 0xd8f0c0, envMapIntensity: 0.5 }));
  m.receiveShadow = true;
  m.position.y = y;
  parent.add(m);
  return m;
}

// ---------------------------------------------------------------------------
// Swaying grass tufts and flowers (instanced: thousands for a couple of draw calls).
// ---------------------------------------------------------------------------
let grassGeo = null;
function tuftGeometry() {
  if (grassGeo) return grassGeo;
  const parts = [];
  for (let i = 0; i < 4; i++) {
    const blade = new THREE.BufferGeometry();
    const w = 0.07;
    const h = 0.45 + (i % 2) * 0.15;
    blade.setAttribute('position', new THREE.Float32BufferAttribute([-w, 0, 0, w, 0, 0, 0.03, h, 0], 3));
    blade.setAttribute('color', new THREE.Float32BufferAttribute([0.3, 0.55, 0.2, 0.3, 0.55, 0.2, 0.62, 0.86, 0.36], 3));
    blade.setAttribute('normal', new THREE.Float32BufferAttribute([0, 1, 0, 0, 1, 0, 0, 1, 0], 3));
    blade.rotateZ((i - 1.5) * 0.25);
    blade.rotateY((i / 4) * Math.PI);
    parts.push(blade);
  }
  grassGeo = mergeGeometries(parts, false);
  return grassGeo;
}

function swayMaterial(params, unlit = false) {
  // thin blades look best unlit (lit double-sided blades go black from behind)
  const m = unlit ? new THREE.MeshBasicMaterial(params) : new THREE.MeshLambertMaterial(params);
  m.onBeforeCompile = (sh) => {
    sh.uniforms.uTime = wind.time;
    sh.vertexShader = 'uniform float uTime;\n' + sh.vertexShader.replace(
      '#include <begin_vertex>',
      `#include <begin_vertex>
       vec4 wp = instanceMatrix * vec4(0.0, 0.0, 0.0, 1.0);
       float sway = sin(uTime * 2.2 + wp.x * 0.35 + wp.z * 0.25) * 0.12 * position.y;
       transformed.x += sway;
       transformed.z += sway * 0.5;`,
    );
  };
  return m;
}

/**
 * Scatter grass and flowers over the given rectangles, skipping spots where `blocked(x, z)` is true.
 * areas: [{x0, x1, z0, z1, y}]
 */
export function grassField(parent, areas, { count = 1800, flowers = 260, blocked = null } = {}) {
  const spots = [];
  const total = areas.reduce((s, a) => s + Math.abs((a.x1 - a.x0) * (a.z1 - a.z0)), 0);
  for (const a of areas) {
    const n = Math.round(((count + flowers) * Math.abs((a.x1 - a.x0) * (a.z1 - a.z0))) / total);
    for (let i = 0; i < n * 1.4 && spots.length < count + flowers; i++) {
      const x = a.x0 + Math.random() * (a.x1 - a.x0);
      const z = a.z0 + Math.random() * (a.z1 - a.z0);
      if (blocked && blocked(x, z)) continue;
      spots.push([x, a.y || 0, z]);
    }
  }
  spots.sort(() => Math.random() - 0.5);
  const m4 = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const up = new THREE.Vector3(0, 1, 0);
  const nG = Math.min(count, spots.length);
  const grass = new THREE.InstancedMesh(tuftGeometry(), swayMaterial({ vertexColors: true, side: THREE.DoubleSide }, true), nG);
  for (let i = 0; i < nG; i++) {
    const [x, y, z] = spots[i];
    q.setFromAxisAngle(up, Math.random() * Math.PI);
    const s = 0.7 + Math.random() * 0.9;
    m4.compose(new THREE.Vector3(x, y, z), q, new THREE.Vector3(s, s, s));
    grass.setMatrixAt(i, m4);
  }
  grass.userData.dynamic = true;
  parent.add(grass);
  const nF = spots.length - nG;
  if (nF > 0) {
    const fgeo = mergeGeometries([
      painted(new THREE.CylinderGeometry(0.015, 0.015, 0.35, 4), 0x3f7a2a, 0, 0.17, 0),
      painted(new THREE.IcosahedronGeometry(0.09, 0), 0xffffff, 0, 0.38, 0),
    ], false);
    const fm = new THREE.InstancedMesh(fgeo, swayMaterial({ vertexColors: true }), nF);
    const cols = [0xffffff, 0xffd23f, 0xff7aa8, 0xb48cff, 0xff5d5d].map((c) => new THREE.Color(c));
    for (let i = 0; i < nF; i++) {
      const [x, y, z] = spots[nG + i];
      m4.compose(new THREE.Vector3(x, y, z), q, new THREE.Vector3(1, 0.8 + Math.random() * 0.6, 1));
      fm.setMatrixAt(i, m4);
      fm.setColorAt(i, cols[i % cols.length]);
    }
    fm.userData.dynamic = true;
    parent.add(fm);
  }
}

// ---------------------------------------------------------------------------
// Japanese castle: stone base, plaster walls, tiled roofs with gold trim, outer walls.
// ---------------------------------------------------------------------------
export function castle(parent, x, z, s = 1) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  g.scale.setScalar(s);
  parent.add(g);
  const stone = mat(0xffffff, { rough: 0.95, map: repeat('stone', 4, 2), flat: true });
  const plaster = mat(0xffffff, { rough: 0.85, map: repeat('plaster', 2, 2) });
  const roofM = mat(0xffffff, { rough: 0.55, map: repeat('roof', 4, 2), flat: true });
  const gold = mat(COLORS.gold, { rough: 0.25, metal: 0.85 });
  const dark = mat(0x1d1a22, { rough: 0.6 });
  const wood = mat(0xffffff, { rough: 0.7, map: repeat('wood', 1, 1) });
  // sloped stone base
  const baseGeo = new THREE.CylinderGeometry(9 / Math.SQRT2, 12.5 / Math.SQRT2, 6, 4, 1);
  baseGeo.rotateY(Math.PI / 4);
  const base = new THREE.Mesh(baseGeo, stone);
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
    const wall = new THREE.Mesh(new THREE.BoxGeometry(t.w, t.h, t.d), plaster);
    wall.position.y = y + t.h / 2;
    g.add(wall);
    // dark timber band and windows
    box(g, t.w + 0.04, 0.25, t.d + 0.04, 0, 0, y + t.h - 0.3, 0, { material: dark, shadow: false });
    const nWin = i === 3 ? 1 : 3;
    for (let k = 0; k < nWin; k++) {
      const wx = nWin === 1 ? 0 : (k - 1) * t.w * 0.28;
      box(g, 0.7, 0.9, 0.06, 0, wx, y + t.h * 0.48, t.d / 2 + 0.03, { material: dark, shadow: false });
      box(g, 0.08, 0.9, 0.08, 0, wx, y + t.h * 0.48, t.d / 2 + 0.06, { material: wood, shadow: false });
    }
    y += t.h;
    // flared roof: wide thin base plus a sloped pyramid
    const rw = t.w * 1.5;
    const rd = t.d * 1.5;
    const eave = new THREE.Mesh(new THREE.BoxGeometry(rw, 0.18, rd), roofM);
    eave.position.y = y + 0.05;
    g.add(eave);
    box(g, rw + 0.06, 0.08, rd + 0.06, 0, 0, y + 0.16, 0, { material: gold, shadow: false });
    const roofGeo = new THREE.CylinderGeometry(0.42, Math.SQRT2 / 2, 1, 4, 1);
    roofGeo.rotateY(Math.PI / 4);
    const roof = new THREE.Mesh(roofGeo, roofM);
    roof.scale.set(rw * 0.92, i === 3 ? 2.2 : 1.3, rd * 0.92);
    roof.position.y = y + (i === 3 ? 1.2 : 0.75);
    g.add(roof);
    // white gable with gold trim on the front
    if (i < 3) {
      const gable = new THREE.Mesh(new THREE.ConeGeometry(1.3, 1.2, 3), plaster);
      gable.rotation.x = -Math.PI / 2;
      gable.rotation.z = Math.PI;
      gable.scale.set(1, 1, 0.45);
      gable.position.set(0, y + 1.25, rd * 0.36);
      g.add(gable);
      const gtrim = new THREE.Mesh(new THREE.TorusGeometry(0.35, 0.06, 6, 14), gold);
      gtrim.position.set(0, y + 1.1, rd * 0.36 + 0.3);
      g.add(gtrim);
    }
    y += i === 3 ? 0 : 1.2;
  }
  for (const sx of [-0.9, 0.9]) {
    const fish = new THREE.Mesh(new THREE.ConeGeometry(0.28, 0.9, 8), gold);
    fish.position.set(sx, y + 2.5, 0);
    fish.rotation.z = sx > 0 ? 0.5 : -0.5;
    g.add(fish);
  }
  // corner turrets
  for (const sx of [-1, 1]) {
    const tower = new THREE.Group();
    tower.position.set(sx * 14, 0, 2);
    g.add(tower);
    const tb = new THREE.Mesh(baseGeo, stone);
    tb.scale.set(0.35, 0.6, 0.35);
    tb.position.y = 1.8;
    tower.add(tb);
    const tw = new THREE.Mesh(new THREE.BoxGeometry(2.8, 2.2, 2.8), plaster);
    tw.position.y = 4.7;
    tower.add(tw);
    box(tower, 2.84, 0.2, 2.84, 0, 0, 5.5, 0, { material: dark, shadow: false });
    const rg = new THREE.CylinderGeometry(0.42, Math.SQRT2 / 2, 1, 4, 1);
    rg.rotateY(Math.PI / 4);
    const r = new THREE.Mesh(rg, roofM);
    r.scale.set(4.4, 1.7, 4.4);
    r.position.y = 6.6;
    tower.add(r);
  }
  // outer walls with tiled caps and a big wooden gate
  for (const sx of [-1, 1]) {
    const w = new THREE.Mesh(new THREE.BoxGeometry(9, 2.4, 1), plaster);
    w.position.set(sx * 9.5, 1.2, 5);
    g.add(w);
    box(g, 9, 0.6, 1.02, 0, sx * 9.5, 0.3, 5, { material: stone, shadow: false });
    const cap = new THREE.Mesh(new THREE.BoxGeometry(9.6, 0.35, 1.7), roofM);
    cap.position.set(sx * 9.5, 2.55, 5);
    g.add(cap);
    for (let k = 0; k < 4; k++) box(g, 0.5, 0.35, 0.1, 0, sx * (6.5 + k * 2), 1.6, 5.52, { material: dark, shadow: false });
  }
  box(g, 4.2, 3.4, 1.5, 0, 0, 1.7, 5.2, { material: wood, shadow: false });
  box(g, 0.12, 3.2, 0.1, 0, 0, 1.6, 5.98, { material: dark, shadow: false });
  for (const yy of [0.9, 2.4]) box(g, 4.2, 0.12, 0.1, 0, 0, yy, 5.98, { material: gold, shadow: false });
  const groof = new THREE.Mesh(new THREE.BoxGeometry(5.6, 0.5, 2.4), roofM);
  groof.position.set(0, 3.6, 5.2);
  g.add(groof);
  box(g, 5.7, 0.08, 2.5, 0, 0, 3.35, 5.2, { material: gold, shadow: false });
  // castle banners
  for (const sx of [-1, 1]) {
    const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.06, 5, 6), dark);
    pole.position.set(sx * 3, 5.8, 5.4);
    g.add(pole);
    const flag = new THREE.Mesh(new THREE.PlaneGeometry(1, 3), mat(COLORS.red, { rough: 0.9 }));
    flag.position.set(sx * 3 + 0.5, 6.6, 5.4);
    g.add(flag);
  }
  return g;
}

// ---------------------------------------------------------------------------
// Trees
// ---------------------------------------------------------------------------
const blobGeo = (() => {
  const g = new THREE.IcosahedronGeometry(1, 2);
  const p = g.attributes.position;
  for (let i = 0; i < p.count; i++) {
    const v = new THREE.Vector3().fromBufferAttribute(p, i);
    const n = 1 + Math.sin(v.x * 4.1) * 0.06 + Math.cos(v.y * 5.3 + v.z * 3) * 0.06;
    v.multiplyScalar(n);
    p.setXYZ(i, v.x, v.y, v.z);
  }
  g.computeVertexNormals();
  return g;
})();

export function cherryTree(parent, x, z, s = 1) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  g.scale.setScalar(s);
  g.rotation.y = Math.random() * 6;
  const bark = mat(0x5a3b2a, { rough: 0.9 });
  const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.16, 0.32, 2.6, 8), bark);
  trunk.position.y = 1.3;
  trunk.rotation.z = 0.08;
  trunk.castShadow = true;
  g.add(trunk);
  for (const [rz, ry] of [[0.8, 0], [-0.7, 2]]) {
    const br = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.12, 1.4, 6), bark);
    br.position.set(Math.sin(rz) * 0.5, 2.4, 0);
    br.rotation.set(0, ry, rz);
    g.add(br);
  }
  const pinks = [0xffc0d6, 0xff9fc2, 0xffd6e4];
  for (let i = 0; i < 5; i++) {
    const b = new THREE.Mesh(blobGeo, mat(pinks[i % 3], { rough: 0.75 }));
    b.position.set((Math.random() - 0.5) * 1.9, 2.8 + Math.random() * 1.1, (Math.random() - 0.5) * 1.9);
    b.scale.set(0.9 + Math.random() * 0.5, 0.7 + Math.random() * 0.3, 0.9 + Math.random() * 0.5);
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
  const greens = [0x2f6b3a, 0x3a7d45, 0x2a5e33];
  for (let i = 0; i < 4; i++) {
    const c = new THREE.Mesh(new THREE.ConeGeometry(1.5 - i * 0.32, 1.5, 9), mat(greens[i % 3], { rough: 0.85 }));
    c.position.y = 1.5 + i * 0.75;
    c.rotation.y = i;
    c.castShadow = true;
    g.add(c);
  }
  parent.add(g);
  return g;
}

/** Bushy round shrub. */
export function shrub(parent, x, z, s = 1) {
  const b = new THREE.Mesh(blobGeo, mat(Math.random() < 0.5 ? 0x4f9a3a : 0x5fae48, { rough: 0.85 }));
  b.position.set(x, 0.35 * s, z);
  b.scale.set(0.9 * s, 0.6 * s, 0.9 * s);
  b.castShadow = true;
  parent.add(b);
  return b;
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
        if (Math.random() < 0.6) shrub(parent, x + (Math.random() - 0.5) * 3, zz + 2, 0.8 + Math.random() * 0.6);
      }
    }
  }
}

const BANNER_COLORS = [0xd7263d, 0xffffff, 0x1d3557, 0xffc93c, 0xf15bb5];
/** Tall nobori flag on a pole. */
export function banner(parent, x, z, color = null, h = 4) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.05, 0.05, h, 6), mat(0x2a2a2a, { rough: 0.4, metal: 0.5 }));
  pole.position.y = h / 2;
  g.add(pole);
  const knob = new THREE.Mesh(new THREE.SphereGeometry(0.1, 8, 6), mat(COLORS.gold, { rough: 0.3, metal: 0.8 }));
  knob.position.y = h + 0.05;
  g.add(knob);
  const col = color ?? BANNER_COLORS[Math.floor(Math.random() * BANNER_COLORS.length)];
  const flag = new THREE.Mesh(new THREE.PlaneGeometry(0.8, h * 0.7), new THREE.MeshStandardMaterial({ color: col, side: THREE.DoubleSide, roughness: 0.9 }));
  flag.position.set(0.42, h * 0.6, 0);
  g.add(flag);
  const dot = new THREE.Mesh(new THREE.CircleGeometry(0.25, 16), new THREE.MeshStandardMaterial({ color: col === 0xffffff ? 0xd7263d : 0xffffff, side: THREE.DoubleSide }));
  dot.position.set(0.42, h * 0.7, 0.01);
  g.add(dot);
  parent.add(g);
  return g;
}

/** A line of triangle flags strung between two points. */
export function bunting(parent, x1, z1, x2, z2, h = 4, sag = 0.6) {
  const len = Math.hypot(x2 - x1, z2 - z1);
  const n = Math.max(2, Math.floor(len / 0.7));
  const parts = [];
  const cols = [0xd7263d, 0xffffff, 0xffc93c, 0x2a6fdb, 0x2bb673];
  const tri = new THREE.BufferGeometry();
  tri.setAttribute('position', new THREE.Float32BufferAttribute([-0.22, 0, 0, 0.22, 0, 0, 0, -0.45, 0], 3));
  tri.setAttribute('normal', new THREE.Float32BufferAttribute([0, 0, 1, 0, 0, 1, 0, 0, 1], 3));
  tri.setAttribute('uv', new THREE.Float32BufferAttribute([0, 0, 1, 0, 0.5, 1], 2));
  const ang = Math.atan2(x2 - x1, z2 - z1) - Math.PI / 2;
  for (let i = 0; i < n; i++) {
    const t = (i + 0.5) / n;
    const y = h - Math.sin(t * Math.PI) * sag;
    parts.push(painted(tri, cols[i % cols.length], x1 + (x2 - x1) * t, y, z1 + (z2 - z1) * t, 0, ang, 0));
  }
  const geo = mergeGeometries(parts, false);
  const m = new THREE.Mesh(geo, new THREE.MeshStandardMaterial({ vertexColors: true, side: THREE.DoubleSide, roughness: 0.9 }));
  parent.add(m);
  // the string
  const pts = [];
  for (let i = 0; i <= 12; i++) {
    const t = i / 12;
    pts.push(new THREE.Vector3(x1 + (x2 - x1) * t, h - Math.sin(t * Math.PI) * sag + 0.01, z1 + (z2 - z1) * t));
  }
  const line = new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts), 12, 0.02, 3), mat(0x333333));
  parent.add(line);
  return m;
}

/** Checkered start line with a START sign on each side (nothing overhead to block the camera). */
export function startLine(parent, y, z, width) {
  const n = Math.round(width / 0.5);
  const parts = [];
  const sq = new THREE.PlaneGeometry(0.5, 0.5);
  sq.rotateX(-Math.PI / 2);
  for (let i = 0; i < n; i++) {
    for (let r = 0; r < 2; r++) parts.push(painted(sq, (i + r) % 2 ? 0x111111 : 0xffffff, -width / 2 + 0.25 + i * 0.5, y + 0.04, z - r * 0.5));
  }
  parent.add(new THREE.Mesh(mergeGeometries(parts, false), new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.6 })));
  for (const sx of [-1, 1]) {
    const g = new THREE.Group();
    g.position.set(sx * (width / 2 + 1.2), y, z);
    const red = mat(0xd7263d, { rough: 0.45 });
    for (const px of [-0.7, 0.7]) {
      const post = new THREE.Mesh(new THREE.CylinderGeometry(0.1, 0.12, 3, 8), red);
      post.position.set(px, 1.5, 0);
      g.add(post);
    }
    const sign = new THREE.Mesh(new THREE.PlaneGeometry(1.9, 0.7), new THREE.MeshBasicMaterial({ map: textTexture('START', { w: 256, h: 96, bg: '#d7263d', fg: '#ffd23f', stroke: null, size: 64, border: '#ffd23f' }), toneMapped: false, side: THREE.DoubleSide }));
    sign.position.y = 2.6;
    sign.rotation.x = -0.5;
    g.add(sign);
    parent.add(g);
  }
}

/** Red torii gate with a sign on top. Returns the group. */
export function torii(parent, x, y, z, width = 10, text = 'GOAL', signColor = '#1d1d1d') {
  const g = new THREE.Group();
  g.position.set(x, y, z);
  const red = mat(0xd7263d, { rough: 0.45 });
  const black = mat(0x1d1d1d, { rough: 0.5 });
  const h = 8.6;
  for (const sx of [-1, 1]) {
    const post = new THREE.Mesh(new THREE.CylinderGeometry(0.38, 0.45, h, 14), red);
    post.position.set(sx * width / 2, h / 2, 0);
    post.castShadow = true;
    g.add(post);
    const foot = new THREE.Mesh(new THREE.CylinderGeometry(0.52, 0.56, 0.6, 14), black);
    foot.position.set(sx * width / 2, 0.3, 0);
    g.add(foot);
  }
  // curved top beam (kasagi) with upturned ends
  const kasagi = new THREE.BoxGeometry(width + 2.6, 0.5, 0.8, 16, 1, 1);
  const kp = kasagi.attributes.position;
  for (let i = 0; i < kp.count; i++) {
    const xx = kp.getX(i) / ((width + 2.6) / 2);
    kp.setY(i, kp.getY(i) + xx * xx * xx * xx * 0.45);
  }
  kasagi.computeVertexNormals();
  const top = new THREE.Mesh(kasagi, black);
  top.position.y = h + 0.3;
  top.castShadow = true;
  g.add(top);
  box(g, width + 1.8, 0.4, 0.65, 0, 0, h - 0.2, 0, { cast: true, material: red });
  box(g, width + 0.8, 0.32, 0.42, 0, 0, h - 1.3, 0, { cast: true, material: red });
  if (text) {
    const sign = new THREE.Mesh(
      new THREE.PlaneGeometry(3.6, 1.1),
      new THREE.MeshBasicMaterial({ map: textTexture(text, { w: 512, h: 156, bg: signColor, fg: '#ffd23f', stroke: null, size: 92, border: '#ffd23f' }), toneMapped: false }),
    );
    sign.position.set(0, h - 0.75, 0.37);
    g.add(sign);
    const back = sign.clone();
    back.rotation.y = Math.PI;
    back.position.z = -0.37;
    g.add(back);
  }
  parent.add(g);
  return g;
}

export function mountains(parent, radius = 210) {
  const rocks = [0x6f8f7c, 0x7a9686, 0x627f70];
  const snow = mat(0xffffff, { flat: true, rough: 0.6 });
  for (let i = 0; i < 18; i++) {
    const a = (i / 18) * Math.PI * 2 + Math.random() * 0.2;
    const h = 30 + Math.random() * 45;
    const geo = new THREE.ConeGeometry(h * 0.95, h, 7, 3);
    const p = geo.attributes.position;
    for (let k = 0; k < p.count; k++) {
      if (p.getY(k) < h / 2 - 0.1) {
        p.setX(k, p.getX(k) * (0.85 + Math.random() * 0.3));
        p.setZ(k, p.getZ(k) * (0.85 + Math.random() * 0.3));
      }
    }
    geo.computeVertexNormals();
    const m = new THREE.Mesh(geo, mat(rocks[i % 3], { flat: true }));
    m.position.set(Math.cos(a) * radius, h / 2 - 5, Math.sin(a) * radius - 40);
    m.rotation.y = Math.random();
    parent.add(m);
    if (h > 55) {
      const cap = new THREE.Mesh(new THREE.ConeGeometry(h * 0.95 * 0.25, h * 0.25, 7), snow);
      cap.position.set(m.position.x, m.position.y + h * 0.375 + 0.2, m.position.z);
      cap.rotation.y = m.rotation.y;
      parent.add(cap);
    }
  }
  const fuji = new THREE.Mesh(new THREE.ConeGeometry(100, 78, 12), mat(0x5d7a9a, { flat: true }));
  fuji.position.set(40, 34, -300);
  parent.add(fuji);
  const cap = new THREE.Mesh(new THREE.ConeGeometry(30, 23.4, 12), snow);
  cap.position.set(40, 61.4, -300);
  parent.add(cap);
}

/** Grandstand full of cheering spectators. Returns an updater. */
export function crowdStand(parent, x, z, length = 20, facing = 1, rows = 4) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  g.rotation.y = facing > 0 ? Math.PI / 2 : -Math.PI / 2;
  parent.add(g);
  const woodM = mat(0xffffff, { rough: 0.8, map: repeat('wood', length / 3, 1) });
  for (let r = 0; r < rows; r++) {
    box(g, length, 0.6 + r * 0.6, 1.2, 0, 0, (0.6 + r * 0.6) / 2, -r * 1.2, { material: woodM, shadow: true });
  }
  const roofY = rows * 0.6 + 2.6;
  box(g, length + 0.6, 0.25, rows * 1.2 + 1, COLORS.red, 0, roofY, -(rows - 1) * 0.6, { shadow: false, rough: 0.5 });
  // striped valance
  for (let i = 0; i < Math.floor(length / 1.2); i++) {
    box(g, 1.2, 0.45, 0.08, i % 2 ? 0xffffff : COLORS.red, -length / 2 + 0.6 + i * 1.2, roofY - 0.3, 0.6, { shadow: false });
  }
  for (const sx of [-1, 1]) box(g, 0.2, roofY, 0.2, 0x444444, sx * length / 2, roofY / 2, 0.5, { shadow: false, metal: 0.5, rough: 0.4 });
  const perRow = Math.floor(length / 0.6);
  const count = perRow * rows;
  const bodyGeo = new THREE.CapsuleGeometry(0.17, 0.28, 3, 8);
  const headGeo = new THREE.SphereGeometry(0.13, 8, 6);
  const bodies = new THREE.InstancedMesh(bodyGeo, new THREE.MeshStandardMaterial({ roughness: 0.7 }), count);
  const heads = new THREE.InstancedMesh(headGeo, new THREE.MeshStandardMaterial({ roughness: 0.6 }), count);
  const people = [];
  const cols = [0xe63946, 0xffd23f, 0x2a6fdb, 0xffffff, 0x2bb673, 0xff8c1a, 0xf15bb5, 0x222222];
  const skins = [0xf2c6a0, 0xe0a97e, 0xc68863, 0x8d5a3b];
  let i = 0;
  for (let r = 0; r < rows; r++) {
    for (let k = 0; k < perRow; k++) {
      const px = -length / 2 + 0.3 + k * 0.6 + (Math.random() - 0.5) * 0.12;
      people.push({ x: px, y: 0.6 + r * 0.6 + 0.32, z: -r * 1.2, ph: Math.random() * 6, sp: 6 + Math.random() * 6 });
      bodies.setColorAt(i, new THREE.Color(cols[Math.floor(Math.random() * cols.length)]));
      heads.setColorAt(i, new THREE.Color(skins[Math.floor(Math.random() * skins.length)]));
      i++;
    }
  }
  bodies.userData.dynamic = heads.userData.dynamic = true;
  g.add(bodies, heads);
  const m4 = new THREE.Matrix4();
  let excitement = 0.3;
  let frame = 0;
  return {
    cheer(v = 1) { excitement = Math.max(excitement, v); },
    update(t, dt) {
      excitement = Math.max(0.25, excitement - dt * 0.4);
      if (frame++ % 2) return; // half rate is plenty for a crowd
      for (let j = 0; j < people.length; j++) {
        const p = people[j];
        const jump = Math.max(0, Math.sin(t * p.sp + p.ph)) * 0.25 * excitement;
        m4.makeTranslation(p.x, p.y + jump, p.z);
        bodies.setMatrixAt(j, m4);
        m4.makeTranslation(p.x, p.y + jump + 0.36, p.z);
        heads.setMatrixAt(j, m4);
      }
      bodies.instanceMatrix.needsUpdate = true;
      heads.instanceMatrix.needsUpdate = true;
    },
  };
}

/** Animated water with waves, fresnel sky reflection and sun glints. Returns an updater. */
export function water(parent, w, d, x, y, z, { deep = 0x07507a, shallow = 0x2aa6c8 } = {}) {
  const geo = new THREE.PlaneGeometry(w, d, Math.min(90, Math.ceil(w)), Math.min(90, Math.ceil(d)));
  geo.rotateX(-Math.PI / 2);
  const uniforms = THREE.UniformsUtils.merge([THREE.UniformsLib.fog, {
    uTime: { value: 0 },
    uDeep: { value: new THREE.Color(deep) },
    uShallow: { value: new THREE.Color(shallow) },
    uSky: { value: new THREE.Color(0xcfe9ff) },
    uSunDir: { value: new THREE.Vector3(0.45, 0.62, 0.64).normalize() },
  }]);
  uniforms.uTime = wind.time;
  const m = new THREE.Mesh(geo, new THREE.ShaderMaterial({
    uniforms,
    transparent: true,
    fog: true,
    vertexShader: /* glsl */ `
      uniform float uTime;
      varying vec3 vWorld;
      varying vec3 vNormalW;
      #include <fog_pars_vertex>
      float h(vec2 p) {
        return sin(p.x * 0.55 + uTime * 1.4) * 0.07 + cos(p.y * 0.48 + uTime * 1.1) * 0.07
             + sin((p.x + p.y) * 1.3 + uTime * 2.3) * 0.03;
      }
      void main() {
        vec4 wp = modelMatrix * vec4(position, 1.0);
        float e = 0.1;
        float y0 = h(wp.xz);
        float dx = h(wp.xz + vec2(e, 0.0)) - y0;
        float dz = h(wp.xz + vec2(0.0, e)) - y0;
        wp.y += y0;
        vNormalW = normalize(vec3(-dx / e, 1.0, -dz / e));
        vWorld = wp.xyz;
        vec4 mvPosition = viewMatrix * wp;
        gl_Position = projectionMatrix * mvPosition;
        #include <fog_vertex>
      }`,
    fragmentShader: /* glsl */ `
      uniform float uTime;
      uniform vec3 uDeep, uShallow, uSky, uSunDir;
      varying vec3 vWorld;
      varying vec3 vNormalW;
      #include <fog_pars_fragment>
      void main() {
        vec3 n = normalize(vNormalW + vec3(sin(vWorld.x * 3.1 + uTime * 3.0), 0.0, cos(vWorld.z * 2.7 + uTime * 2.6)) * 0.05);
        vec3 v = normalize(cameraPosition - vWorld);
        float fres = pow(1.0 - max(dot(n, v), 0.0), 3.0);
        float ripple = sin(vWorld.x * 1.7 + uTime) * sin(vWorld.z * 1.9 - uTime * 1.3) * 0.5 + 0.5;
        vec3 col = mix(uDeep, uShallow, 0.35 + ripple * 0.25);
        col = mix(col, uSky, fres * 0.7);
        vec3 r = reflect(-normalize(uSunDir), n);
        float spec = pow(max(dot(r, v), 0.0), 90.0);
        col += vec3(1.0, 0.95, 0.85) * spec * 1.6;
        float sparkle = step(0.985, fract(sin(dot(floor(vWorld.xz * 6.0), vec2(12.9898, 78.233))) * 43758.5453 + uTime * 0.3));
        col += sparkle * 0.35 * (0.5 + fres);
        gl_FragColor = vec4(col, 0.92);
        #include <tonemapping_fragment>
        #include <colorspace_fragment>
        #include <fog_fragment>
      }`,
  }));
  m.position.set(x, y, z);
  m.userData.dynamic = true;
  parent.add(m);
  return { mesh: m, update() {} };
}

/**
 * TV game-show set dressing: hot-air balloons in the sky, bunches of balloons, inflatable pylons
 * and floodlight towers around a course.
 */
export function showDressing(parent, { startZ, finishZ, width, y = 0, finishY = 0 }) {
  const w = width / 2;
  const cols = [0xe8222e, 0xffc93c, 0x2a6fdb, 0x2bb673, 0xf15bb5, 0xffffff, 0xff8c1a];
  // hot-air balloons drifting in the distance
  const envGeo = new THREE.SphereGeometry(1, 20, 16);
  const spots = [[-55, 34, finishZ - 40], [48, 42, finishZ - 10], [-38, 50, startZ - 20], [70, 30, startZ + 10]];
  spots.forEach(([bx, by, bz], i) => {
    const g = new THREE.Group();
    g.position.set(bx, by, bz);
    const env = new THREE.Mesh(envGeo, mat(i % 2 ? 0xffffff : cols[i % cols.length], { map: repeat('stripes', 6, 1), rough: 0.5 }));
    env.scale.set(7, 8.5, 7);
    g.add(env);
    const skirt = new THREE.Mesh(new THREE.CylinderGeometry(2.6, 1.2, 3, 12, 1, true), mat(cols[(i + 2) % cols.length], { rough: 0.6 }));
    skirt.position.y = -8.2;
    g.add(skirt);
    const basket = new THREE.Mesh(new THREE.BoxGeometry(1.6, 1.3, 1.6), mat(0x8a5a34, { rough: 0.9 }));
    basket.position.y = -11.5;
    g.add(basket);
    parent.add(g);
  });
  // bunches of balloons and inflatable pylons at the start and finish
  const balloon = new THREE.SphereGeometry(0.32, 12, 10);
  const bunch = (x, z, yy) => {
    const parts = [painted(new THREE.CylinderGeometry(0.03, 0.03, 2.6, 4), 0x444444, x, yy + 1.3, z)];
    for (let k = 0; k < 6; k++) {
      const a = (k / 6) * Math.PI * 2;
      parts.push(painted(balloon, cols[(k + Math.floor(x + z + 100)) % cols.length], x + Math.cos(a) * 0.35, yy + 2.7 + (k % 3) * 0.32, z + Math.sin(a) * 0.35, 0, 0, 0, 1, 1.2, 1));
    }
    const m = new THREE.Mesh(mergeGeometries(parts, false), new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.25 }));
    m.castShadow = true;
    parent.add(m);
  };
  const pylon = (x, z, yy) => {
    const m = new THREE.Mesh(new THREE.CylinderGeometry(0.35, 0.75, 3.4, 14), mat(0xffffff, { map: repeat('stripes', 3, 2), rough: 0.4 }));
    m.position.set(x, yy + 1.7, z);
    m.castShadow = true;
    parent.add(m);
    const top = new THREE.Mesh(new THREE.SphereGeometry(0.42, 14, 10), mat(0xffc93c, { rough: 0.3, metal: 0.5 }));
    top.position.set(x, yy + 3.5, z);
    parent.add(top);
  };
  for (const sx of [-1, 1]) {
    bunch(sx * (w + 2.6), startZ + 3, y);
    bunch(sx * (w + 2.6), finishZ - 2, finishY);
    pylon(sx * (w + 1.6), startZ + 5, y);
    pylon(sx * (w + 3.4), finishZ - 4, finishY);
  }
  // floodlight towers
  const steel = mat(0x4b5563, { rough: 0.4, metal: 0.7 });
  const lamp = new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xfff2c4, emissiveIntensity: 1.6 });
  for (const sx of [-1, 1]) {
    for (const z of [startZ + 9, finishZ - 9]) {
      const yy = z < (startZ + finishZ) / 2 ? finishY : y;
      const g = new THREE.Group();
      g.position.set(sx * (w + 13), yy, z);
      g.rotation.y = Math.atan2(-sx * (w + 13), (startZ + finishZ) / 2 - z);
      box(g, 0.3, 13, 0.3, 0, 0, 6.5, 0, { material: steel, shadow: false });
      box(g, 3.4, 1.7, 0.4, 0, 0, 13.4, 0, { material: steel, shadow: false });
      for (let i = 0; i < 6; i++) {
        const l = new THREE.Mesh(new THREE.CircleGeometry(0.36, 12), lamp);
        l.position.set(-1.1 + (i % 3) * 1.1, 13 + Math.floor(i / 3) * 0.8, 0.21);
        g.add(l);
      }
      parent.add(g);
    }
  }
}

/** Paper lantern on a post. */
export function lantern(parent, x, z, h = 2.2) {
  const g = new THREE.Group();
  g.position.set(x, 0, z);
  const post = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.06, h, 6), mat(0x333333));
  post.position.y = h / 2;
  g.add(post);
  const l = new THREE.Mesh(new THREE.SphereGeometry(0.32, 14, 10), new THREE.MeshStandardMaterial({ color: 0xff4d4d, emissive: 0xff3a2a, emissiveIntensity: 0.9, roughness: 0.6 }));
  l.scale.y = 1.3;
  l.position.y = h + 0.2;
  g.add(l);
  for (const yy of [h - 0.2, h + 0.6]) {
    const cap = new THREE.Mesh(new THREE.CylinderGeometry(0.18, 0.18, 0.08, 10), mat(0x1d1d1d));
    cap.position.y = yy;
    g.add(cap);
  }
  parent.add(g);
  return g;
}

export { tex };
