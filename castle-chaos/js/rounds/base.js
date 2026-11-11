import * as THREE from 'three';
import { mat, particles } from '../fx.js';
import { sfx } from '../audio.js';
import * as S from '../scenery.js';

const V = (x, y, z) => new THREE.Vector3(x, y, z);

/**
 * Shared base for every event. A round builds its course, moves its obstacles,
 * checks hazards for each contestant and drives the bots.
 */
export class Round {
  constructor(game) {
    this.game = game;
    this.world = game.world;
    this.group = new THREE.Group();
    game.levelGroup.add(this.group);
    this.t = 0;
    this.updaters = [];
    this.crowds = [];
    this.banners = [];
    this.killY = -4;
    this.camOffset = V(0, 6.2, 9.5);
    this.camLook = V(0, 1, -6);
    this.timeLimit = 120;
    this.width = 12;
    this.startZ = 0;
    this.finishZ = -50;
    this.respawnWidth = 8;
    this.pending = []; // delayed respawns
  }

  // ---------- building helpers ----------
  /** Box mesh with a matching collider. y is the centre. */
  solid(x, y, z, sx, sy, sz, color, opts = {}) {
    // opts.tex tiles a procedural texture over the top face (opts.texScale metres per tile)
    if (opts.tex && !opts.material) opts = { ...opts, material: S.texMat(opts.tex, opts.texTint ?? 0xffffff, sx, sz, { texScale: opts.texScale || 4, rough: opts.rough ?? 0.9 }) };
    const mesh = S.box(this.group, sx, sy, sz, color, x, y, z, { cast: opts.cast ?? true, ...opts });
    const col = opts.noCollide ? null : this.world.addBox(x, y, z, sx, sy, sz, opts.col || {});
    if (col) col.mesh = mesh;
    return { mesh, col };
  }

  /** Walkable floor whose top is at `top`. */
  floor(x0, x1, z0, z1, color, top = 0, thick = 1, opts = {}) {
    const zA = Math.min(z0, z1);
    const zB = Math.max(z0, z1);
    return this.solid((x0 + x1) / 2, top - thick / 2, (zA + zB) / 2, x1 - x0, thick, zB - zA, color, { cast: false, ...opts });
  }

  /** Grass, mountains, trees, castle, crowds and flags around a straight course. */
  decorate({ castleZ = this.finishZ - 45, castleY = 0, castleScale = 1.4, crowds = true, treeGap = 7, groundY = 0, skipZ = null } = {}) {
    S.ground(this.group, 400, S.COLORS.grass, groundY);
    S.mountains(this.group);
    S.castle(this.group, 0, castleZ, castleScale).position.y = castleY;
    const w = this.width / 2;
    const skip = (z) => skipZ && z < skipZ[0] && z > skipZ[1];
    S.forest(this.group, { xMin: -w - 6, xMax: w + 6, zFrom: this.startZ + 12, zTo: castleZ + 10, gap: treeGap, skip });
    if (crowds) {
      const len = Math.min(26, Math.abs(this.finishZ - this.startZ) * 0.45);
      const mid = (this.startZ + this.finishZ) / 2;
      this.crowds.push(S.crowdStand(this.group, -w - 4.5, mid, len, 1));
      this.crowds.push(S.crowdStand(this.group, w + 4.5, mid, len, -1));
    }
    for (let z = this.startZ + 2; z > this.finishZ; z -= 6) {
      if (skip(z)) continue;
      this.banners.push(S.banner(this.group, -w - 1.6, z));
      this.banners.push(S.banner(this.group, w + 1.6, z));
      if (z - 6 > this.finishZ && !skip(z - 6)) {
        S.bunting(this.group, -w - 1.6, z, -w - 1.6, z - 6, 3.7, 0.5);
        S.bunting(this.group, w + 1.6, z, w + 1.6, z - 6, 3.7, 0.5);
      }
    }
    // grass tufts and flowers beside the course
    const gy = this.grassY ?? groundY;
    const zA = this.startZ + 25;
    const zB = castleZ + 15;
    S.grassField(this.group, [
      { x0: -w - 34, x1: -w - 1.2, z0: zB, z1: zA, y: gy },
      { x0: w + 1.2, x1: w + 34, z0: zB, z1: zA, y: gy },
      { x0: -w - 1, x1: w + 1, z0: this.startZ + 9, z1: zA, y: gy },
    ], { count: 2200, flowers: 320, blocked: (x, z) => skip(z) || (this.grassBlocked && this.grassBlocked(x, z)) });
    S.startLine(this.group, this.startY || 0, this.startZ + 0.3, this.width);
    S.showDressing(this.group, { startZ: this.startZ, finishZ: this.finishZ, width: this.width, y: this.startY || 0, finishY: this.finishY || 0 });
    this.goal = S.torii(this.group, 0, this.finishY || 0, this.finishZ, this.width + 1, 'GOAL');
  }

  /**
   * A river course: banks either side, water in between, set lower so the water shows.
   * Used by Skipping Stones, Bridge Ball and Mushroom Trip.
   */
  riverCourse(bankEdge, farBank, { castleZ = this.finishZ - 40 } = {}) {
    this.waterY = -0.7;
    this.killY = -0.5;
    this.grassY = 0;
    this.grassBlocked = (x, z) => z < bankEdge + 0.4 && z > farBank - 0.4 && Math.abs(x) < 31;
    this.decorate({ castleZ, groundY: -3, crowds: false, skipZ: [bankEdge + 1, farBank - 1] });
    const w = this.width / 2;
    const grassy = { tex: 'grass', texScale: 7, texTint: 0xd8f0c0 };
    this.floor(-120, 120, 40, bankEdge, 0x6cc24a, 0, 4, grassy);
    this.floor(-120, 120, farBank, -330, 0x6cc24a, 0, 4, grassy);
    this.floor(-120, -30, bankEdge, farBank, 0x6cc24a, 0, 4, grassy);
    this.floor(30, 120, bankEdge, farBank, 0x6cc24a, 0, 4, grassy);
    this.solid(0, 0.01, (12 + bankEdge) / 2, this.width + 2, 0.02, 12 - bankEdge, 0xc9a46b, { noCollide: true, cast: false, tex: 'dirt', texScale: 3 });
    this.solid(0, 0.01, (farBank + this.finishZ - 14) / 2, this.width + 2, 0.02, farBank - this.finishZ + 14, 0xc9a46b, { noCollide: true, cast: false, tex: 'dirt', texScale: 3 });
    this.crowds.push(S.crowdStand(this.group, -w - 5, 6, 12, 1));
    this.crowds.push(S.crowdStand(this.group, w + 5, 6, 12, -1));
    this.crowds.push(S.crowdStand(this.group, -w - 5, farBank - 8, 12, 1));
    this.crowds.push(S.crowdStand(this.group, w + 5, farBank - 8, 12, -1));
    const riverLen = bankEdge - farBank;
    S.water(this.group, 60, riverLen + 2, 0, this.waterY, (bankEdge + farBank) / 2);
    this.solid(0, this.waterY - 1.6, (bankEdge + farBank) / 2, 60, 0.2, riverLen + 2, 0x1d5f7a, { noCollide: true, cast: false, tex: 'sand', texScale: 5, texTint: 0x6a8a90 });
    // reeds and rocks along the banks
    for (let i = 0; i < 40; i++) {
      const z = Math.random() < 0.5 ? bankEdge - 0.2 : farBank + 0.2;
      const x = (Math.random() < 0.5 ? -1 : 1) * (w + 1 + Math.random() * 25);
      const reed = new THREE.Mesh(new THREE.CylinderGeometry(0.04, 0.05, 0.6 + Math.random() * 0.8, 4), mat(0x4f7d2a));
      reed.position.set(x, this.waterY + 0.3, z);
      this.group.add(reed);
    }
    // foam where the banks meet the water
    for (let x = -28; x <= 28; x += 3.5) {
      this.foam(x, bankEdge - 0.3, 1.6);
      this.foam(x, farBank + 0.3, 1.6);
    }
  }

  /** Register a ring of foam on the water around something standing in it. */
  foam(x, z, r) {
    (this.foams || (this.foams = [])).push({ x, z, r, ph: Math.random() * 6, follow: null });
    return this.foams[this.foams.length - 1];
  }

  updateFoam() {
    if (!this.foams || !this.foams.length) return;
    if (!this.foamMesh) {
      const geo = new THREE.RingGeometry(0.85, 1, 28);
      geo.rotateX(-Math.PI / 2);
      const m = new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.55, depthWrite: false, toneMapped: false });
      this.foamMesh = new THREE.InstancedMesh(geo, m, this.foams.length);
      this.foamMesh.userData.dynamic = true;
      this.foamMesh.frustumCulled = false;
      this.foamMesh.renderOrder = 2;
      this.group.add(this.foamMesh);
      this._m4 = new THREE.Matrix4();
    }
    const y = (this.waterY ?? 0) + 0.04;
    for (let i = 0; i < this.foams.length; i++) {
      const f = this.foams[i];
      const x = f.follow ? f.follow.x : f.x;
      const z = f.follow ? f.follow.z : f.z;
      const hidden = f.follow && f.follow.hidden;
      const s = hidden ? 0.0001 : f.r * (1.05 + Math.sin(this.t * 2 + f.ph) * 0.08);
      this._m4.makeScale(s, 1, s).setPosition(x, y, z);
      this.foamMesh.setMatrixAt(i, this._m4);
    }
    this.foamMesh.instanceMatrix.needsUpdate = true;
  }

  // ---------- online ----------
  /** True on the device that runs the shared obstacles (always true offline). */
  get auth() {
    return !this.game.net || this.game.net.isHost;
  }

  /** Something everyone must see (a door bursting, a boulder spawning...). Runs here and on every other device. */
  emit(kind, data) {
    this.onNet(kind, data);
    if (this.game.net) this.game.net.sendEvent(kind, data);
  }

  onNet() {}

  /** Extra state this device owns, sent with every snapshot (array of entries). */
  netLocal() {
    return null;
  }

  netApply() {}

  byId(id) {
    return this.game.contestants[id];
  }

  // ---------- life cycle ----------
  build() {}
  startPositions(n) {
    const out = [];
    const cols = Math.min(n, Math.max(4, Math.floor(this.width / 1.3)));
    for (let i = 0; i < n; i++) {
      const row = Math.floor(i / cols);
      const col = i % cols;
      const x = (col - (cols - 1) / 2) * ((this.width - 2) / Math.max(1, cols - 1));
      out.push(V(x + (Math.random() - 0.5) * 0.2, this.startY || 0, this.startZ + 1 + row * 1.3));
    }
    return out.sort(() => Math.random() - 0.5);
  }

  /** Called every frame before contestants move. */
  update(dt) {
    this.t += dt;
    this.updateFoam();
    for (const u of this.updaters) u(this.t, dt);
    for (const c of this.crowds) c.update(this.t, dt);
    for (let i = this.pending.length - 1; i >= 0; i--) {
      const p = this.pending[i];
      p.t -= dt;
      if (p.t <= 0) {
        this.pending.splice(i, 1);
        if (p.c.active && !p.c.finished) p.c.respawn(this.respawnFor(p.c));
        p.c.respawning = false;
      }
    }
  }

  /** Hazards and finish line for one contestant. Rounds call super. */
  check(c) {
    if (c.respawning) return;
    if (c.body.pos.y < this.killY) this.sendBack(c, 'fall');
    else if (!c.finished && this.crossedFinish(c)) this.game.requestQualify(c);
  }

  crossedFinish(c) {
    return c.body.pos.z < this.finishZ && c.body.grounded;
  }

  respawnFor(c) {
    const p = c.checkpoint.clone();
    p.x += (Math.random() - 0.5) * this.respawnWidth;
    p.y += 0.5;
    return p;
  }

  /** Send a contestant back to their checkpoint after a short delay. */
  sendBack(c, why = 'fall', delay = 0.9) {
    if (c.respawning) return;
    c.respawning = true;
    c.frozen = true;
    c.celebrate = false;
    const p = c.body.pos.clone();
    if (why === 'water') {
      particles.burst(p.clone().setY(this.waterY ?? p.y), [0xffffff, 0x9fd8ff], 26, 5, 0.14, { up: 1.4 });
      c.root.visible = false;
      if (this.game.near(c)) sfx('splash');
    } else if (why === 'mud') {
      particles.burst(p.clone().setY(p.y + 0.3), [0x5b3a1e, 0x7a5230], 30, 5, 0.14, { up: 1.2 });
      if (this.game.near(c)) sfx('mud');
    } else {
      c.root.visible = false;
    }
    if (c.isPlayer) {
      this.game.commentary(why);
      sfx('ooh', 0.7);
    }
    this.pending.push({ c, t: delay });
  }

  setCheckpoint(c, x, y, z) {
    c.checkpoint.set(x, y, z);
  }

  cheer(v = 1) {
    for (const c of this.crowds) c.cheer(v);
  }

  /** Default bot: run to the finish, sidestepping nothing. */
  think(c) {
    this.steer(c, c.ai.lane ?? 0, this.finishZ - 4);
  }

  // ---------- bot helpers ----------
  steer(c, x, z, slow = 1) {
    const dx = x - c.body.pos.x;
    const dz = z - c.body.pos.z;
    const d = Math.hypot(dx, dz);
    if (d < 0.05) {
      c.control.x = c.control.z = 0;
      return d;
    }
    const s = Math.min(1, d / 0.6) * slow;
    c.control.x = (dx / d) * s;
    c.control.z = (dz / d) * s;
    return d;
  }

  /** Bots occasionally get stuck against walls or each other. Wiggle free. */
  unstick(c, dt) {
    const ai = c.ai;
    ai.stuckT = (ai.stuckT || 0) + dt;
    if (ai.stuckT > 0.8) {
      const moved = ai.lastZ === undefined ? 1 : Math.abs(c.body.pos.z - ai.lastZ) + Math.abs(c.body.pos.x - ai.lastX);
      ai.lastZ = c.body.pos.z;
      ai.lastX = c.body.pos.x;
      ai.stuckT = 0;
      if (moved < 0.4 && !c.frozen && c.stun <= 0) {
        ai.wiggle = 0.5;
        ai.wiggleDir = Math.random() < 0.5 ? -1 : 1;
        return true;
      }
    }
    if (ai.wiggle > 0) {
      ai.wiggle -= dt;
      c.control.x = ai.wiggleDir;
      c.control.z = 0.3;
      if (Math.random() < 0.05) c.control.jump = true;
    }
    return ai.wiggle > 0;
  }

  dispose() {
    this.world.clear();
  }
}

export { V };
export { mat };
