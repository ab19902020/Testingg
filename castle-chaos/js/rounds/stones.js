import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles } from '../fx.js';
import { sfx } from '../audio.js';
import { water, crowdStand } from '../scenery.js';
import * as S2 from '../scenery.js';
import { repeat } from '../textures.js';

// SKIPPING STONES: hop across the river. Some stones are fakes that sink as soon as you land.
const ROWS = 12;
const GAP = 3.0;
const ISLAND_ROW = 5;
const COL_X = [-4.2, -1.4, 1.4, 4.2];
const R = 0.85;
const BANK_EDGE = -2;

export class SkippingStones extends Round {
  static info = {
    id: 'stones',
    name: 'Skipping Stones',
    tagline: 'Hop across the river. Some stones are fakes and sink the moment you land!',
    tip: 'Cracked stones are fakes. Watch where others land safely.',
  };

  build() {
    this.width = 12;
    this.startZ = 2;
    this.waterY = -0.7;
    this.killY = -0.5;
    this.timeLimit = 110;
    this.camOffset = V(0, 7, 8.8);
    this.camLook = V(0, 0, -6);
    this.respawnWidth = 7;
    const lastRowZ = BANK_EDGE - 2.6 - (ROWS - 1) * GAP;
    const farBank = lastRowZ - 2.4;
    this.finishZ = farBank - 3.5;
    this.grassY = 0;
    this.grassBlocked = (x, z) => z < BANK_EDGE + 0.4 && z > farBank - 0.4 && Math.abs(x) < 31;
    this.decorate({ castleZ: this.finishZ - 40, groundY: -3, crowds: false, skipZ: [BANK_EDGE + 1, farBank - 1] });
    const w = this.width / 2;
    // land on both banks and either side of the river (the grass sits lower so the water shows)
    const grassy = { tex: 'grass', texScale: 7, texTint: 0xd8f0c0 };
    this.floor(-120, 120, 40, BANK_EDGE, 0x6cc24a, 0, 4, grassy);
    this.floor(-120, 120, farBank, -330, 0x6cc24a, 0, 4, grassy);
    this.floor(-120, -30, BANK_EDGE, farBank, 0x6cc24a, 0, 4, grassy);
    this.floor(30, 120, BANK_EDGE, farBank, 0x6cc24a, 0, 4, grassy);
    this.solid(0, 0.01, (12 + BANK_EDGE) / 2, this.width + 2, 0.02, 12 - BANK_EDGE, 0xc9a46b, { noCollide: true, cast: false, tex: 'dirt', texScale: 3 });
    this.solid(0, 0.01, (farBank + this.finishZ - 14) / 2, this.width + 2, 0.02, farBank - this.finishZ + 14, 0xc9a46b, { noCollide: true, cast: false, tex: 'dirt', texScale: 3 });
    this.crowds.push(crowdStand(this.group, -w - 5, 6, 12, 1));
    this.crowds.push(crowdStand(this.group, w + 5, 6, 12, -1));
    this.crowds.push(crowdStand(this.group, -w - 5, farBank - 8, 12, 1));
    this.crowds.push(crowdStand(this.group, w + 5, farBank - 8, 12, -1));
    const riverLen = BANK_EDGE - farBank;
    const river = water(this.group, 60, riverLen + 2, 0, this.waterY, (BANK_EDGE + farBank) / 2);
    this.updaters.push((t) => river.update(t));
    // river bed so it doesn't look like a void
    this.solid(0, this.waterY - 1.6, (BANK_EDGE + farBank) / 2, 60, 0.2, riverLen + 2, 0x1d5f7a, { noCollide: true, cast: false, tex: 'sand', texScale: 5, texTint: 0x6a8a90 });
    // reeds on the banks
    for (let i = 0; i < 30; i++) {
      const z = Math.random() < 0.5 ? BANK_EDGE - 0.2 : farBank + 0.2;
      const reed = new THREE.Mesh(new THREE.CylinderGeometry(0.04, 0.05, 0.6 + Math.random() * 0.8, 4), mat(0x4f7d2a));
      reed.position.set((Math.random() - 0.5) * 30, this.waterY + 0.3, z);
      if (Math.abs(reed.position.x) > w + 0.5) this.group.add(reed);
    }
    this.stones = [];
    this.rows = [];
    // lumpy river rocks rather than perfect cylinders
    this.stoneGeo = new THREE.CylinderGeometry(R * 0.88, R * 1.02, 0.5, 12, 2);
    {
      const p = this.stoneGeo.attributes.position;
      for (let i = 0; i < p.count; i++) {
        const y = p.getY(i);
        const a = Math.atan2(p.getZ(i), p.getX(i));
        const k = 1 + Math.sin(a * 3 + 1) * 0.05 + Math.cos(a * 5) * 0.04;
        p.setX(i, p.getX(i) * k);
        p.setZ(i, p.getZ(i) * k);
        if (y > 0.2) p.setY(i, y + Math.sin(a * 2) * 0.03);
      }
      this.stoneGeo.computeVertexNormals();
    }
    this.crackMat = mat(0x6e5a48, { flat: true, rough: 0.95, map: repeat('stone', 3, 0.8) });
    for (let r = 0; r < ROWS; r++) {
      const z = BANK_EDGE - 2.6 - r * GAP;
      if (r === ISLAND_ROW) {
        const island = this.floor(-w, w, z + 1.2, z - 1.2, 0x9cbf5a, 0, 1, { tex: 'grass', texScale: 3, texTint: 0xd8f0c0 });
        for (const lx of [-w + 0.6, w - 0.6]) S2.lantern(this.group, lx, z);
        island.col.row = r;
        island.col.island = true;
        this.rows.push({ z, island: true, stones: [] });
        this.islandZ = z;
        // a little shrine lantern pair on the island
        continue;
      }
      const realCount = r < ISLAND_ROW ? 2 : 1;
      const idx = [0, 1, 2, 3].sort(() => Math.random() - 0.5);
      const row = { z, stones: [] };
      COL_X.forEach((x0, ci) => {
        const fake = !idx.slice(0, realCount).includes(ci);
        row.stones.push(this.makeStone(x0 + (Math.random() - 0.5) * 0.5, z + (Math.random() - 0.5) * 0.4, fake, r));
      });
      this.rows.push(row);
    }
    // safety posts so nobody walks round the river
    for (const sx of [-1, 1]) {
      this.solid(sx * (w + 1.3), 1, 4, 0.4, 2, 12, 0xd7263d);
      this.solid(sx * (w + 1.3), 1, farBank - 5, 0.4, 2, 10, 0xd7263d);
    }
  }

  makeStone(x, z, fake, row) {
    const g = 0.55 + Math.random() * 0.1;
    const mesh = new THREE.Mesh(this.stoneGeo, mat(new THREE.Color(g * 0.42, g * 0.41, g * 0.4).getHex(), { flat: true, rough: 0.8 }));
    mesh.position.set(x, -0.25, z);
    mesh.userData.dynamic = true;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    this.group.add(mesh);
    const stone = { x, z, fake, row, mesh, state: 'up', t: 0, knownFake: false, knownReal: false, standT: 0, homeY: -0.25 };
    const col = this.world.addCyl(x, 0, z, R, 3, {
      onLand: null,
    });
    col.stone = stone;
    col.row = row;
    stone.col = col;
    this.stones.push(stone);
    return stone;
  }

  update(dt) {
    super.update(dt);
    for (const s of this.stones) {
      if (s.state === 'shaking') {
        s.t += dt;
        s.mesh.position.x = s.x + Math.sin(s.t * 70) * 0.06;
        if (s.t > 0.32) {
          s.state = 'sinking';
          s.t = 0;
          s.col.enabled = false;
          s.knownFake = true;
          particles.burst(new THREE.Vector3(s.x, this.waterY + 0.2, s.z), [0xffffff, 0x9fd8ff], 16, 3, 0.12);
          if (this.game.nearPos(s.x, s.z)) sfx('crack');
        }
      } else if (s.state === 'sinking') {
        s.t += dt;
        s.mesh.position.x = s.x;
        s.mesh.position.y = s.homeY - Math.min(2.5, s.t * 3);
        s.mesh.rotation.z = Math.min(0.6, s.t);
        if (s.t > 4) {
          s.state = 'rising';
          s.t = 0;
          s.mesh.material = this.crackMat;
        }
      } else if (s.state === 'rising') {
        s.t += dt;
        const k = Math.min(1, s.t / 0.8);
        s.mesh.position.y = s.homeY - 2.5 * (1 - k);
        s.mesh.rotation.z = 0.6 * (1 - k);
        if (k >= 1) {
          s.state = 'up';
          s.col.enabled = true;
        }
      }
    }
  }

  check(c) {
    if (c.respawning) return;
    const b = c.body;
    const g = b.grounded ? b.ground : null;
    if (g && g.stone) {
      const s = g.stone;
      if (s.fake && s.state === 'up') {
        s.state = 'shaking';
        s.t = 0;
      } else if (!s.fake) {
        s.standT += 1 / 60;
        if (s.standT > 0.6) s.knownReal = true;
      }
    }
    if (g && g.island) this.setCheckpoint(c, 0, 0, this.islandZ);
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
    if (c.body.pos.z < this.rows[ROWS - 1].z - 1) return ROWS;
    return c.ai.row ?? -1;
  }

  pickStone(c, rowIdx) {
    const row = this.rows[rowIdx];
    const p = c.body.pos;
    let best = null;
    let bestS = -1e9;
    for (const s of row.stones) {
      if (s.state !== 'up') continue;
      const dist = Math.hypot(s.x - p.x, s.z - p.z);
      if (dist > 6.3 && c.body.ground?.stone) continue;
      let score = Math.random() * 1.5 - dist * 0.45;
      if (s.knownFake) score -= 2 + c.skill * 6;
      if (s.knownReal) score += 1 + c.skill * 4;
      if (score > bestS) {
        bestS = score;
        best = s;
      }
    }
    return best;
  }

  think(c, dt) {
    const b = c.body;
    const p = b.pos;
    const cur = this.rowOf(c);
    c.ai.row = cur;
    if (cur >= ROWS) {
      this.steer(c, c.ai.lane ?? (c.ai.lane = (Math.random() - 0.5) * 7), this.finishZ - 6);
      return;
    }
    const next = cur + 1;
    if (!b.grounded) {
      // air control: keep heading for the target
      if (c.ai.target) this.steer(c, c.ai.target.x, c.ai.target.z);
      return;
    }
    if (next >= ROWS) {
      // last stone -> jump to the far bank
      this.steer(c, p.x * 0.8, p.z - 4);
      const ground = b.ground;
      const off = ground?.stone ? Math.hypot(p.x - ground.stone.x, p.z - ground.stone.z) : 0;
      if (off > R - 0.35 || !ground?.stone) c.control.jump = true;
      c.ai.target = { x: p.x * 0.8, z: p.z - 4 };
      return;
    }
    const row = this.rows[next];
    if (row.island) {
      const tx = Math.max(-5, Math.min(5, p.x));
      c.ai.target = { x: tx, z: row.z };
      this.steer(c, tx, row.z);
      const ground = b.ground;
      const off = ground?.stone ? Math.hypot(p.x - ground.stone.x, p.z - ground.stone.z) : 0;
      if (Math.abs(p.z - (row.z + 1.2)) < 2.6 || off > R - 0.3) c.control.jump = true;
      return;
    }
    // careful bots pause a moment when every option is a gamble
    if (c.ai.wait > 0) {
      c.ai.wait -= dt;
      c.control.x = c.control.z = 0;
      return;
    }
    let s = c.ai.stone;
    if (!s || s.row !== next || s.state !== 'up' || (s.knownFake && Math.random() < c.skill)) {
      s = this.pickStone(c, next);
      c.ai.stone = s;
      if (s && !s.knownReal && c.skill > 0.55 && Math.random() < 0.3) {
        c.ai.wait = 0.4 + Math.random() * 1.2;
        return;
      }
    }
    if (!s) {
      c.control.x = c.control.z = 0;
      return;
    }
    c.ai.target = { x: s.x, z: s.z };
    const d = this.steer(c, s.x, s.z);
    const ground = b.ground;
    let off = 0;
    let edge = 99;
    if (ground?.stone) {
      off = Math.hypot(p.x - ground.stone.x, p.z - ground.stone.z);
      edge = R - 0.25;
    } else if (ground?.island) {
      edge = 99;
      off = 0;
    }
    const jumpDist = 2.9 + c.skill * 0.6;
    if (d < jumpDist || off > edge) c.control.jump = true;
  }
}
