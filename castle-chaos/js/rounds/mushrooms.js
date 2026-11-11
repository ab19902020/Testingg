import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles } from '../fx.js';
import { sfx } from '../audio.js';

// MUSHROOM TRIP: hop across giant mushrooms in the river. Some bob up and down, some slide
// from side to side, and the blue ones bounce you sky high.
const ROWS = 11;
const GAP = 3.3;
const ISLAND_ROW = 5;
const BANK_EDGE = -2;
const COL_X = [-3.8, 0, 3.8];

export class MushroomTrip extends Round {
  static info = {
    id: 'mushrooms',
    name: 'Mushroom Trip',
    tagline: 'Hop across the giant mushrooms. Some bob, some slide, and the blue ones bounce you sky high!',
    tip: 'Time your jump for when a bobbing mushroom is low.',
  };

  build() {
    this.width = 12;
    this.startZ = 2;
    this.timeLimit = 110;
    this.camOffset = V(0, 7, 9);
    this.camLook = V(0, 0.5, -6);
    this.respawnWidth = 6;
    const lastRowZ = BANK_EDGE - 2.8 - (ROWS - 1) * GAP;
    const farBank = lastRowZ - 2.6;
    this.farBank = farBank;
    this.finishZ = farBank - 3.5;
    this.riverCourse(BANK_EDGE, farBank);

    this.capTex = {
      red: spotTexture('#e8323c'),
      orange: spotTexture('#ff8a1f'),
      blue: spotTexture('#2f8cff'),
    };
    this.capGeo = new THREE.SphereGeometry(1, 28, 14, 0, Math.PI * 2, 0, Math.PI / 2);
    this.gillGeo = new THREE.CircleGeometry(1, 28);
    this.gillGeo.rotateX(Math.PI / 2);
    this.stemGeo = new THREE.CylinderGeometry(0.3, 0.42, 1, 12);
    this.gillMat = mat(0xf2dcc0, { rough: 0.9 });
    this.stemMat = mat(0xf7efe0, { rough: 0.7 });
    this.mushrooms = [];
    this.rows = [];
    for (let r = 0; r < ROWS; r++) {
      const z = BANK_EDGE - 2.8 - r * GAP;
      if (r === ISLAND_ROW) {
        const island = this.floor(-4.5, 4.5, z + 1.3, z - 1.3, 0, 0.4, 1.4, { tex: 'grass', texScale: 3, texTint: 0xd8f0c0 });
        island.col.row = r;
        island.col.island = true;
        this.foam(-3, z, 2.2);
        this.foam(3, z, 2.2);
        this.rows.push({ z, island: true, list: [] });
        this.islandZ = z;
        continue;
      }
      const row = { z, list: [] };
      let hasEasy = false;
      COL_X.forEach((x0, ci) => {
        let type = 'still';
        if (r >= 2) {
          const k = Math.random();
          type = k < 0.42 ? 'bob' : k < 0.66 ? 'slide' : k < 0.78 && r < ROWS - 1 ? 'bounce' : 'still';
        }
        if (ci === 2 && !hasEasy && type === 'bounce') type = 'still';
        if (type !== 'bounce') hasEasy = true;
        row.list.push(this.makeMushroom(x0 + (Math.random() - 0.5) * 0.6, z + (Math.random() - 0.5) * 0.5, type, r));
      });
      this.rows.push(row);
    }
  }

  makeMushroom(x, z, type, row) {
    const r = 1.05 + Math.random() * 0.3;
    const base = 0.25 + Math.random() * 0.6 + (row % 3) * 0.15;
    const g = new THREE.Group();
    g.userData.dynamic = true;
    this.group.add(g);
    const colour = type === 'bounce' ? 'blue' : Math.random() < 0.6 ? 'red' : 'orange';
    const cap = new THREE.Mesh(this.capGeo, new THREE.MeshStandardMaterial({ map: this.capTex[colour], roughness: 0.35, emissive: type === 'bounce' ? 0x0a2a66 : 0 }));
    cap.scale.set(r * 1.08, r * 0.5, r * 1.08);
    cap.castShadow = true;
    g.add(cap);
    const gill = new THREE.Mesh(this.gillGeo, this.gillMat);
    gill.scale.setScalar(r * 1.04);
    g.add(gill);
    const stem = new THREE.Mesh(this.stemGeo, this.stemMat);
    g.add(stem);
    const col = this.world.addCyl(x, base, z, r * 0.98, 2.2, { bouncy: type === 'bounce' });
    const m = {
      x0: x, z, x, top: base, base, r, type, row, g, cap, stem, col,
      w: 0.9 + Math.random() * 0.8, ph: Math.random() * 6, amp: type === 'slide' ? 1.4 + Math.random() * 0.6 : 0.55,
      squish: 0,
    };
    col.mush = m;
    col.row = row;
    m.foamRef = { x, z, hidden: false };
    this.foam(x, z, r * 0.55).follow = m.foamRef;
    this.place(m);
    this.mushrooms.push(m);
    return m;
  }

  place(m) {
    if (m.type === 'bob') m.top = m.base + Math.sin(this.t * m.w + m.ph) * m.amp;
    if (m.type === 'slide') m.x = m.x0 + Math.sin(this.t * m.w * 0.8 + m.ph) * m.amp;
    this.world.moveTo(m.col, m.x, m.top - m.col.hy, m.z);
    m.squish = Math.max(0, m.squish - 1 / 60 * 3);
    const sq = Math.sin(m.squish * Math.PI * 3) * m.squish * 0.3;
    m.g.position.set(m.x, m.top - m.r * 0.08, m.z);
    m.cap.scale.y = m.r * 0.5 * (1 - sq);
    // stem reaches down into the water
    const stemLen = m.top - this.waterY + 0.6;
    m.stem.scale.set(m.r * 0.9, stemLen, m.r * 0.9);
    m.stem.position.y = -stemLen / 2;
    m.foamRef.x = m.x;
  }

  update(dt) {
    super.update(dt);
    for (const m of this.mushrooms) this.place(m);
  }

  check(c) {
    if (c.respawning) return;
    const b = c.body;
    const g = b.grounded ? b.ground : null;
    if (g && g.bouncy && b.vel.y <= 0.1) {
      // BOING!
      b.vel.y = 13.5;
      b.vel.z = Math.min(b.vel.z, -3);
      b.grounded = false;
      g.mush.squish = 1;
      // bots: you've used this one, look for the next
      c.ai.row = g.mush.row;
      c.ai.target = null;
      c.ai.mush = null;
      c.squash = 0.35;
      particles.burst(b.pos.clone().setY(b.pos.y + 0.2), [0x7fc1ff, 0xffffff], 10, 3, 0.12);
      if (this.game.near(c)) sfx('boing');
    }
    if (g && g.island) this.setCheckpoint(c, 0, 0.4, this.islandZ);
    if (b.pos.y < this.waterY + 0.15) {
      this.sendBack(c, 'water', 1.2);
      return;
    }
    super.check(c);
  }

  // ---------- bots ----------
  rowOf(c) {
    const g = c.body.ground;
    if (g && g.row !== undefined) return g.row;
    if (c.body.pos.z > BANK_EDGE - 0.5) return -1;
    if (c.body.pos.z < this.farBank + 0.5) return ROWS;
    return c.ai.row ?? -1;
  }

  think(c) {
    const b = c.body;
    const p = b.pos;
    const cur = this.rowOf(c);
    c.ai.row = cur;
    const t = c.ai.target;
    if (!b.grounded && t) {
      this.steer(c, t.x, t.z);
      return;
    }
    if (cur >= ROWS) {
      this.steer(c, c.ai.lane ?? (c.ai.lane = (Math.random() - 0.5) * 7), this.finishZ - 6);
      return;
    }
    const ground = b.ground;
    const onM = ground?.mush;
    const myTop = onM ? onM.top : p.y;
    const off = onM ? Math.hypot(p.x - onM.x, p.z - onM.z) : 0;
    const edge = onM ? onM.r - 0.35 : 99;
    const next = cur + 1;
    if (next >= ROWS) {
      const tx = Math.max(-5, Math.min(5, p.x));
      c.ai.target = { x: tx, z: this.farBank - 1.5 };
      this.steer(c, tx, this.farBank - 1.5);
      if (Math.abs(p.z - this.farBank) < 3.3 || off > edge) c.control.jump = true;
      return;
    }
    const row = this.rows[next];
    if (row.island) {
      const tx = Math.max(-3.5, Math.min(3.5, p.x));
      c.ai.target = { x: tx, z: row.z };
      this.steer(c, tx, row.z);
      if (Math.abs(p.z - (row.z + 1.3)) < 2.8 || off > edge) c.control.jump = true;
      return;
    }
    // pick a mushroom in the next row (prefer still ones, then the closest)
    let m = c.ai.mush;
    if (!m || m.row !== next || Math.random() < 0.01) {
      let best = null;
      let bestS = -1e9;
      for (const o of row.list) {
        const d = Math.hypot(o.x - p.x, o.z - p.z);
        let s = -d * 0.6 + Math.random();
        if (o.type === 'still') s += c.skill * 1.5;
        if (o.type === 'bounce') s -= 0.5;
        if (d > 4.3) s -= 4;
        if (s > bestS) {
          bestS = s;
          best = o;
        }
      }
      m = best;
      c.ai.mush = m;
    }
    c.ai.target = { x: m.x, z: m.z };
    const d = this.steer(c, m.x, m.z);
    const tooHigh = m.top > myTop + 1.15;
    const jumpDist = 3 + c.skill * 0.6;
    if (tooHigh && off < edge) {
      // wait for a bobbing one to come down
      c.control.x *= 0.2;
      c.control.z *= 0.2;
      return;
    }
    if (d < jumpDist || off > edge) c.control.jump = true;
  }
}

function spotTexture(base) {
  const c = document.createElement('canvas');
  c.width = 256;
  c.height = 128;
  const g = c.getContext('2d');
  const grd = g.createLinearGradient(0, 0, 0, 128);
  grd.addColorStop(0, base);
  grd.addColorStop(1, '#ffffff22');
  g.fillStyle = base;
  g.fillRect(0, 0, 256, 128);
  g.fillStyle = 'rgba(255,255,255,0.18)';
  g.fillRect(0, 0, 256, 30);
  for (let i = 0; i < 16; i++) {
    const x = Math.random() * 256;
    const y = 10 + Math.random() * 100;
    const r = 6 + Math.random() * 10;
    g.fillStyle = '#fff8ee';
    g.beginPath();
    g.ellipse(x, y, r, r * 0.8, 0, 0, Math.PI * 2);
    g.fill();
  }
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  t.wrapS = THREE.RepeatWrapping;
  return t;
}
