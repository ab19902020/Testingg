import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles, painted, mergeGeometries } from '../fx.js';
import { sfx } from '../audio.js';
import { repeat } from '../textures.js';
import * as S from '../scenery.js';

// SWEEPER: everyone stands on a round platform in the lake while two padded arms sweep round.
// Jump the low arm, stay down for the high one. Get knocked into the water and you're out.
// No finish line: the last ones standing go through.
const R_BIG = 10;
const R_SMALL = 7;
const SHRINK_AT = 35;
const ARM_LEN = 10.2;

export class Sweeper extends Round {
  static info = {
    id: 'sweeper',
    name: 'Sweeper',
    tagline: 'Jump the red arm and stay under the yellow one. Fall in the lake and you are out!',
    tip: 'Jump the red arm. If the yellow arm is coming too, just tap jump for a low hop under it.',
    survival: true,
  };

  /** Arm angles at race time t (same on every device). */
  angles(t) {
    const tt = Math.max(0, t);
    const cap1 = 34;
    const a1 = tt < cap1 ? 0.9 * tt + 0.025 * tt * tt : 0.9 * cap1 + 0.025 * cap1 * cap1 + (0.9 + 0.05 * cap1) * (tt - cap1);
    const w1 = 0.9 + 0.05 * Math.min(tt, cap1);
    const cap2 = 40;
    const a2 = -(tt < cap2 ? 0.55 * tt + 0.015 * tt * tt : 0.55 * cap2 + 0.015 * cap2 * cap2 + (0.55 + 0.03 * cap2) * (tt - cap2)) + 1.3;
    const w2 = -(0.55 + 0.03 * Math.min(tt, cap2));
    return { a1, w1, a2, w2 };
  }

  build() {
    this.survival = true;
    this.width = 20;
    this.startZ = 9;
    this.finishZ = -9;
    this.waterY = -0.7;
    this.killY = -3;
    this.timeLimit = 90;
    this.camFixed = { pos: V(0, 19, 17), look: V(0, 0, -0.5) };
    this.radius = R_BIG;
    // a lake with the castle on the far shore
    S.ground(this.group, 400, S.COLORS.grass, -3);
    S.mountains(this.group);
    S.castle(this.group, 0, -75, 1.7).position.y = -0.7;
    S.water(this.group, 190, 190, 0, this.waterY, -20);
    S.showDressing(this.group, { startZ: 14, finishZ: -14, width: 34, y: -0.7, finishY: -0.7 });
    this.solid(0, this.waterY - 1.6, -20, 190, 0.2, 190, 0x1d5f7a, { noCollide: true, cast: false, tex: 'sand', texScale: 6, texTint: 0x6a8a90 });
    for (const sx of [-1, 1]) {
      // floating grandstands
      this.solid(sx * 19, -0.45, 0, 7, 0.9, 26, 0, { noCollide: true, tex: 'wood', texScale: 2 });
      this.crowds.push(S.crowdStand(this.group, sx * 19.5, 0, 22, -sx, 4));
      for (const z of [-11, 0, 11]) this.foam(sx * 15.6, z, 2.2);
    }
    // the platform: inner disc and an outer ring that drops away later
    const top = mat(0xffffff, { map: repeat('checker', 5, 5), rough: 0.5 });
    const inner = new THREE.Mesh(new THREE.CylinderGeometry(R_SMALL, R_SMALL - 0.4, 1.2, 40), [mat(0x2a6fdb, { rough: 0.4 }), top, mat(0x2a6fdb)]);
    inner.position.y = -0.6;
    inner.receiveShadow = true;
    inner.userData.dynamic = true;
    this.group.add(inner);
    const ringGeo = new THREE.CylinderGeometry(R_BIG, R_BIG - 0.4, 1.0, 48, 1, false);
    const ring = new THREE.Mesh(ringGeo, [mat(0xe8222e, { rough: 0.4 }), mat(0xffffff, { map: repeat('stripes', 8, 8), rough: 0.5 }), mat(0xe8222e)]);
    ring.position.y = -0.52;
    ring.receiveShadow = true;
    ring.userData.dynamic = true;
    this.group.add(ring);
    this.ring = ring;
    this.col = this.world.addCyl(0, 0, 0, R_BIG, 1.4);
    this.foamRing = this.foam(0, 0, R_BIG + 0.2);
    // hub and arms
    const hub = new THREE.Mesh(new THREE.CylinderGeometry(1.0, 1.25, 3.0, 20), mat(0xffffff, { map: repeat('stripes', 4, 2), rough: 0.4 }));
    hub.position.y = 1.5;
    hub.castShadow = true;
    hub.userData.dynamic = true;
    this.group.add(hub);
    const cap = new THREE.Mesh(new THREE.SphereGeometry(1.0, 20, 12, 0, Math.PI * 2, 0, Math.PI / 2), mat(0xffc93c, { rough: 0.25, metal: 0.7 }));
    cap.position.y = 3.0;
    cap.userData.dynamic = true;
    this.group.add(cap);
    this.world.addCyl(0, 2.6, 0, 1.1, 2.6);
    const makeArm = (y, colA, colB) => {
      const parts = [];
      const seg = new THREE.CapsuleGeometry(0.34, 1.2, 6, 12);
      seg.rotateX(Math.PI / 2);
      for (let i = 0; i < 12; i++) {
        const z = -ARM_LEN + 0.9 + i * ((ARM_LEN * 2 - 1.8) / 11);
        if (Math.abs(z) < 1) continue;
        parts.push(painted(seg, i % 2 ? colA : colB, 0, 0, z));
      }
      const m = new THREE.Mesh(mergeGeometries(parts, false), new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.3 }));
      m.position.y = y;
      m.castShadow = true;
      m.userData.dynamic = true;
      this.group.add(m);
      return m;
    };
    this.low = makeArm(0.36, 0xe8222e, 0xffffff);
    this.high = makeArm(2.4, 0xffc93c, 0x222222);
    this.splashed = [];
  }

  startPositions(n) {
    const out = [];
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2 + 0.3;
      const r = 5 + (i % 3) * 1.4;
      out.push(V(Math.sin(a) * r, 0, Math.cos(a) * r));
    }
    return out.sort(() => Math.random() - 0.5);
  }

  update(dt) {
    super.update(dt);
    const t = this.game.raceT;
    const { a1, a2 } = this.angles(this.game.racing || this.game.state === 'roundEnd' ? t : 0);
    this.low.rotation.y = a1;
    this.high.rotation.y = a2;
    // outer ring: flash a warning, then drop into the lake
    if (t > SHRINK_AT - 6 && t < SHRINK_AT) {
      this.ring.position.y = -0.52 + Math.sin(t * 30) * 0.03;
      if (!this.warned) {
        this.warned = true;
        this.game.caption('The red ring is about to drop. Get to the middle!', '#ffd23f', 3);
      }
    } else if (t >= SHRINK_AT) {
      if (this.radius !== R_SMALL) {
        this.radius = R_SMALL;
        this.col.r = R_SMALL;
        this.foamRing.r = R_SMALL + 0.2;
        if (this.game.racing) sfx('smash');
        this.game.shake(0.3);
      }
      this.ring.position.y = Math.max(-4, this.ring.position.y - dt * 3);
    }
  }

  check(c) {
    if (c.respawning || c.ko) return;
    const b = c.body;
    const p = b.pos;
    const { a1, w1, a2, w2 } = this.angles(this.game.raceT);
    const rad = Math.hypot(p.x, p.z);
    if (rad > 1.2 && rad < ARM_LEN + 0.3 && c.stun <= 0.25 && this.game.raceT > 1.2) {
      const hit = (a, w, isLow) => {
        const dx = Math.sin(a);
        const dz = Math.cos(a);
        const perp = p.x * dz - p.z * dx;
        if (Math.abs(perp) > (isLow ? 0.46 : 0.5)) return false;
        // red arm: your feet must be off the floor. Yellow arm: only catches a full-height jump.
        if (isLow ? p.y > 0.4 : p.y < 1.0) return false;
        // shoved along with the arm, and outwards
        const side = Math.sign(w);
        const tx = dz * side;
        const tz = -dx * side;
        const sp = 7 + Math.abs(w) * rad * 0.55;
        c.knock(tx * sp + (p.x / rad) * 4.5, isLow ? 5.5 : 3, tz * sp + (p.z / rad) * 4.5, 0.85);
        particles.burst(p.clone().setY(p.y + 0.9), 0xffffff, 8, 3, 0.1);
        if (this.game.near(c)) sfx('thud');
        if (c.isPlayer) {
          this.game.caption(isLow ? 'Swept off your feet!' : 'Clotheslined! Stay low for the yellow arm!', '#ffd0d0');
          this.game.shake(0.45);
        }
        return true;
      };
      if (!hit(a1, w1, true)) hit(a2, w2, false);
    }
    if (p.y < this.waterY + 0.15) {
      particles.burst(p.clone().setY(this.waterY), [0xffffff, 0x9fd8ff], 26, 5, 0.14, { up: 1.4 });
      if (this.game.near(c, 40)) sfx('splash');
      this.game.requestKO(c);
    }
  }

  // ---------- bots ----------
  think(c, dt) {
    const p = c.body.pos;
    const ai = c.ai;
    const t = this.game.raceT;
    if (ai.r === undefined) {
      ai.r = 3.2 + Math.random() * 2.6;
      ai.drift = (Math.random() - 0.5) * 0.5;
      ai.ang = Math.atan2(p.x, p.z);
    }
    const maxR = t > SHRINK_AT - 7 ? R_SMALL - 1.6 : R_BIG - 1.5;
    const r = Math.min(ai.r, maxR);
    ai.ang += ai.drift * dt;
    this.steer(c, Math.sin(ai.ang) * r, Math.cos(ai.ang) * r, 0.8);
    if (!c.body.grounded) return;
    const { a1, w1, a2, w2 } = this.angles(t);
    const me = Math.atan2(p.x, p.z);
    // time until a blade of each arm reaches me (two blades, so the gap repeats every half turn)
    const eta = (a, w) => {
      let gap = (me - a) * Math.sign(w);
      gap = ((gap % Math.PI) + Math.PI) % Math.PI;
      return gap / Math.abs(w);
    };
    const tLow = eta(a1, w1);
    const tHigh = eta(a2, w2);
    const radius = Math.max(1.5, Math.hypot(p.x, p.z));
    const lead = 0.46 / (Math.abs(w1) * radius) + 0.1 + (Math.random() - 0.5) * (1 - c.skill) * 0.16;
    if (tLow < lead && tLow > 0.05) {
      // jumping takes about 0.7s: don't jump into the high arm (good bots check, clumsy ones forget)
      // the yellow arm is coming too: a low hop clears red and stays under yellow (clumsy bots forget)
      if (tHigh < 0.9 && Math.random() < 0.55 + c.skill * 0.45) c.control.jumpHeld = false;
      if (Math.random() < 0.995 - (1 - c.skill) * 0.05) c.control.jump = true;
    }
  }
}
