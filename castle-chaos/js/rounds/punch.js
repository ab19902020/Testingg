import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles, painted, mergeGeometries } from '../fx.js';
import { sfx } from '../audio.js';
import { repeat } from '../textures.js';

// PUNCH WALL: edge along a narrow ledge either side of a long wall while boxing gloves
// punch out of it. A glove pulls back and shakes just before it fires.
const BANK_EDGE = -2;
const FAR_BANK = -58;
const MID = { z0: -27.5, z1: -32 };
const WALL_HALF = 0.7;
const LEDGE_W = 2.5;
const REACH = 2.7;

export class PunchWall extends Round {
  static info = {
    id: 'punch',
    name: 'Punch Wall',
    tagline: 'Edge along the ledge while boxing gloves punch out of the wall. One hit and you are in the river!',
    tip: 'A glove pulls back and shakes just before it punches. Wait, then run past.',
  };

  build() {
    this.width = 12;
    this.startZ = 2;
    this.finishZ = FAR_BANK - 3.5;
    this.timeLimit = 110;
    this.camOffset = V(0, 6.5, 9);
    this.camLook = V(0, 0.5, -6);
    this.respawnWidth = 6;
    this.riverCourse(BANK_EDGE, FAR_BANK);
    const pad = mat(0xffffff, { map: repeat('stripes', 1, 6), rough: 0.45 });
    const wallMat = mat(0x3b4a8c, { rough: 0.5 });
    this.gloves = [];
    const gloveGeo = mergeGeometries([
      painted(new THREE.SphereGeometry(0.62, 18, 14), 0xe8222e, 0.1, 0, 0, 0, 0, 0, 1.15, 1, 1),
      painted(new THREE.SphereGeometry(0.3, 12, 10), 0xe8222e, -0.05, 0.42, 0.25),
      painted(new THREE.CylinderGeometry(0.42, 0.46, 0.4, 14), 0xffffff, -0.62, 0, 0, 0, 0, Math.PI / 2),
    ], false);
    const gloveMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.28 });
    const armGeo = new THREE.CylinderGeometry(0.16, 0.16, 1, 8);
    armGeo.rotateZ(Math.PI / 2);
    armGeo.translate(0.5, 0, 0);
    const armMat = mat(0x9aa3b2, { rough: 0.3, metal: 0.7 });
    const section = (z0, z1) => {
      const len = z0 - z1;
      const cz = (z0 + z1) / 2;
      // the wall, with a padded top, and a ledge on each side
      this.solid(0, 1.7, cz, WALL_HALF * 2, 3.4, len, 0, { material: wallMat });
      this.solid(0, 3.5, cz, WALL_HALF * 2 + 0.3, 0.3, len + 0.2, 0, { material: pad, noCollide: true });
      for (const sx of [-1, 1]) {
        const ledge = this.solid(sx * (WALL_HALF + LEDGE_W / 2), -0.25, cz, LEDGE_W, 0.5, len, 0, { tex: 'wood', texScale: 1.6, cast: false });
        ledge.col.ledge = sx;
        this.solid(sx * (WALL_HALF + LEDGE_W), -0.22, cz, 0.16, 0.5, len, 0xffc93c, { noCollide: true, rough: 0.4 });
        for (let z = z0 - 2; z > z1; z -= 6) this.foam(sx * (WALL_HALF + LEDGE_W), z, 1.2);
      }
      let i = 0;
      for (let z = z0 - 3; z > z1 + 1.5; z -= 3.1) {
        for (const sx of [-1, 1]) {
          if ((i + (sx > 0 ? 1 : 0)) % 3 === 2) continue; // leave some gaps
          const g = new THREE.Group();
          g.userData.dynamic = true;
          g.position.set(sx * WALL_HALF, 0.95, z);
          g.scale.x = sx;
          this.group.add(g);
          const arm = new THREE.Mesh(armGeo, armMat);
          g.add(arm);
          const head = new THREE.Mesh(gloveGeo, gloveMat);
          head.castShadow = true;
          g.add(head);
          // round port in the wall
          const port = new THREE.Mesh(new THREE.TorusGeometry(0.6, 0.1, 8, 20), mat(0xffc93c, { rough: 0.3, metal: 0.6 }));
          port.rotation.y = Math.PI / 2;
          port.position.set(sx * (WALL_HALF + 0.02), 0.95, z);
          this.group.add(port);
          this.gloves.push({ z, side: sx, g, arm, head, period: 2.3 + Math.random() * 1.3, ph: Math.random() * 4, e: 0, phase: 0 });
        }
        i++;
      }
    };
    section(BANK_EDGE + 0.4, MID.z0);
    section(MID.z1, FAR_BANK - 0.4);
    const mid = this.floor(-5.5, 5.5, MID.z0, MID.z1, 0, 0, 1.4, { tex: 'stripes', texScale: 2 });
    mid.col.mid = true;
    for (const x of [-5, 0, 5]) {
      this.foam(x, MID.z0, 1.6);
      this.foam(x, MID.z1, 1.6);
    }
  }

  /** How far a glove is out, and whether it is in its hitting phase, at time t. */
  gloveState(gl, t) {
    const u = (((t + gl.ph) % gl.period) + gl.period) % gl.period / gl.period;
    if (u < 0.5) return { e: 0.25, hit: false, wind: false, u };
    if (u < 0.74) return { e: 0.25 - 0.18 * Math.min(1, (u - 0.5) / 0.08) + Math.sin(t * 60) * 0.03, hit: false, wind: true, u };
    if (u < 0.78) return { e: 0.07 + (REACH - 0.07) * ((u - 0.74) / 0.04), hit: true, wind: false, u };
    if (u < 0.9) return { e: REACH, hit: u < 0.84, wind: false, u };
    return { e: REACH - (REACH - 0.25) * ((u - 0.9) / 0.1), hit: false, wind: false, u };
  }

  update(dt) {
    super.update(dt);
    for (const gl of this.gloves) {
      const st = this.gloveState(gl, this.t);
      if (st.hit && !gl.wasHit && this.game.nearPos(0, gl.z, 14)) sfx('shoot', 0.5);
      gl.wasHit = st.hit;
      gl.e = st.e;
      gl.hit = st.hit;
      gl.head.position.x = st.e - 0.1;
      gl.arm.scale.x = Math.max(0.05, st.e);
    }
  }

  check(c) {
    if (c.respawning) return;
    const b = c.body;
    const p = b.pos;
    if (this.game.racing && c.stun <= 0.2 && p.y < 1.7 && p.y > -0.3) {
      for (const gl of this.gloves) {
        if (!gl.hit || Math.abs(p.z - gl.z) > 0.85) continue;
        const dx = p.x * gl.side;
        if (dx > WALL_HALF && dx < WALL_HALF + gl.e + 0.65) {
          c.knock(gl.side * 10.5, 4.5, (Math.random() - 0.5) * 2, 0.9);
          particles.burst(p.clone().setY(p.y + 1), [0xffffff, 0xffd23f], 10, 4, 0.12);
          if (this.game.near(c)) sfx('thud');
          if (c.isPlayer) {
            this.game.caption(LINES[Math.floor(Math.random() * LINES.length)], '#ffd0d0');
            this.game.shake(0.5);
          }
          break;
        }
      }
    }
    if (b.grounded && b.ground?.mid) this.setCheckpoint(c, 0, 0, (MID.z0 + MID.z1) / 2);
    if (p.y < this.waterY + 0.15) {
      this.sendBack(c, 'water', 1.0);
      return;
    }
    super.check(c);
  }

  respawnFor(c) {
    const p = super.respawnFor(c);
    if (c.checkpoint.z < MID.z0) p.x = Math.max(-4.5, Math.min(4.5, p.x));
    return p;
  }

  // ---------- bots ----------
  think(c, dt) {
    const p = c.body.pos;
    const ai = c.ai;
    if (p.z < FAR_BANK + 0.5) {
      this.steer(c, ai.lane ?? (ai.lane = (Math.random() - 0.5) * 8), this.finishZ - 6);
      return;
    }
    if (this.unstick(c, dt)) return;
    const onPlatform = p.z > BANK_EDGE - 0.2 || (p.z < MID.z0 + 0.2 && p.z > MID.z1 - 0.2);
    if (onPlatform || ai.side === undefined) {
      if (ai.sideZ === undefined || Math.abs(ai.sideZ - p.z) > 12) {
        ai.side = Math.abs(p.x) < 0.8 ? (Math.random() < 0.5 ? -1 : 1) : Math.sign(p.x);
        if (Math.random() < 0.25) ai.side = -ai.side;
        ai.sideZ = p.z;
        ai.lx = WALL_HALF + 0.9 + Math.random() * 0.9;
      }
    }
    const lx = ai.side * ai.lx;
    if (onPlatform && Math.abs(p.x - lx) > 0.5) {
      // line up with the ledge before stepping on
      this.steer(c, lx, p.z > BANK_EDGE - 0.2 ? BANK_EDGE + 0.9 : MID.z1 + 0.9);
      return;
    }
    // look at the next glove on my side: will it fire as I pass?
    const speed = 7.2 * c.speedMul;
    let wait = false;
    for (const gl of this.gloves) {
      if (gl.side !== ai.side) continue;
      const ahead = p.z - gl.z;
      if (ahead < -0.9 || ahead > 2.4) continue;
      const tIn = Math.max(0, (ahead - 0.9) / speed);
      const tOut = (ahead + 0.9) / speed;
      // sample the glove over the time I'd be in front of it
      let danger = false;
      for (let k = 0; k <= 4; k++) {
        const st = this.gloveState(gl, this.t + tIn + ((tOut - tIn) * k) / 4);
        if (st.hit || (st.wind && st.u > 0.66)) danger = true;
      }
      if (danger && ahead > 0.95 && Math.random() < 0.55 + c.skill * 0.45) wait = true;
    }
    if (wait) {
      c.control.x = (lx - p.x) * 0.5;
      c.control.z = 0;
      return;
    }
    this.steer(c, lx, p.z - 3);
  }
}

const LINES = ['POW! Right in the kisser!', 'Knocked clean off the ledge!', 'That glove had your name on it!'];
