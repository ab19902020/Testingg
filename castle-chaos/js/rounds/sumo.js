import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles, painted, mergeGeometries } from '../fx.js';
import { sfx } from '../audio.js';
import { COLORS } from '../scenery.js';

// SUMO GONG: get past three lines of sumo wrestlers and bang the gong.
// Get grabbed and you're lifted up and thrown back down the field!
const FIELD_W = 16;
const ROWS = [
  { z: -10, n: 2 },
  { z: -19, n: 3 },
  { z: -28, n: 3 },
  { z: -37, n: 3 },
];
const GONG_Z = -47;

export class SumoGong extends Round {
  static info = {
    id: 'sumo',
    name: 'Sumo Gong',
    tagline: 'Slip past the sumo wrestlers and bang the gong. If one grabs you, you get thrown back!',
    tip: 'Wait for a gap, then sprint or dive through it.',
  };

  build() {
    this.width = FIELD_W;
    this.startZ = 2;
    this.finishZ = GONG_Z + 1.6;
    this.timeLimit = 100;
    this.camOffset = V(0, 7.5, 10);
    this.camLook = V(0, 0.8, -6);
    this.decorate({ castleZ: GONG_Z - 40, castleScale: 1.3 });
    const w = FIELD_W / 2;
    this.floor(-w, w, 10, GONG_Z - 10, COLORS.sand, 0, 1, { tex: 'sand', texScale: 4, texTint: 0xd6bf8c });
    for (const sx of [-1, 1]) this.solid(sx * (w + 0.3), 0.4, GONG_Z / 2 + 1, 0.6, 0.8, -GONG_Z + 22, 0xd7263d);
    // straw rope lines painted across the sand
    for (const r of ROWS) {
      this.solid(0, 0.02, r.z + 3, FIELD_W, 0.04, 0.3, 0xa8823e, { noCollide: true, cast: false });
      this.solid(0, 0.02, r.z - 3, FIELD_W, 0.04, 0.3, 0xa8823e, { noCollide: true, cast: false });
    }
    this.sumos = [];
    ROWS.forEach((row, ri) => {
      const zone = FIELD_W / row.n;
      for (let i = 0; i < row.n; i++) {
        const cx = -w + zone * (i + 0.5);
        const node = makeSumo();
        node.userData.dynamic = true;
        this.group.add(node);
        const col = this.world.addCyl(cx, 3, row.z, 0.95, 3);
        this.sumos.push({
          node, col, cx, x: cx, z0: row.z, z: row.z, zone: zone / 2 - 0.6,
          w: 0.8 + Math.random() * 0.6 + ri * 0.2, ph: Math.random() * 6,
          lungePh: Math.random() * 6, hold: null, holdT: 0, cool: 0, row: ri,
          speed: 3.5 + ri * 0.35,
        });
      }
    });
    // the gong
    const gong = new THREE.Group();
    gong.position.set(0, 0, GONG_Z);
    gong.userData.dynamic = true;
    this.group.add(gong);
    const wood = mat(0x3b2516);
    for (const sx of [-1.8, 1.8]) {
      const post = new THREE.Mesh(new THREE.BoxGeometry(0.3, 4.2, 0.3), wood);
      post.position.set(sx, 2.1, 0);
      gong.add(post);
    }
    const beam = new THREE.Mesh(new THREE.BoxGeometry(4.2, 0.35, 0.35), wood);
    beam.position.y = 4.1;
    gong.add(beam);
    const disc = new THREE.Mesh(new THREE.CylinderGeometry(1.4, 1.4, 0.15, 32), mat(COLORS.gold, { rough: 0.25, metal: 0.8 }));
    disc.rotation.x = Math.PI / 2;
    disc.position.y = 2.3;
    gong.add(disc);
    this.gongDisc = disc;
    this.gongSwing = 0;
    this.world.addBox(0, 2, GONG_Z - 0.3, 4, 4, 0.4);
  }

  update(dt) {
    super.update(dt);
    const w = FIELD_W / 2;
    for (const s of this.sumos) {
      s.cool -= dt;
      let tx = s.cx + Math.sin(this.t * s.w + s.ph) * s.zone;
      // chase the nearest contestant coming up to this line
      let target = null;
      let best = 7;
      for (const c of this.game.racers) {
        if (c.respawning || c.finished || c.frozen) continue;
        const dz = c.body.pos.z - s.z;
        if (dz < -0.5 || dz > 4) continue;
        const dx = Math.abs(c.body.pos.x - s.x);
        if (dx < best && dx < s.zone + 2) {
          best = dx;
          target = c;
        }
      }
      if (target && !s.hold) tx = target.body.pos.x;
      tx = Math.max(s.cx - s.zone - 1, Math.min(s.cx + s.zone + 1, tx));
      tx = Math.max(-w + 1, Math.min(w - 1, tx));
      if (!s.hold) {
        const step = (target ? s.speed * 0.75 : s.speed) * dt;
        s.x += Math.max(-step, Math.min(step, tx - s.x));
        const lunge = Math.max(0, Math.sin(this.t * 1.3 + s.lungePh) - 0.7) * 8;
        s.z = s.z0 + lunge;
      }
      this.world.moveTo(s.col, s.x, s.col.y, s.z);
      s.node.position.set(s.x, 0, s.z);
      s.node.rotation.z = Math.sin(this.t * 9 + s.ph) * 0.05;
      const arms = s.node.userData.arms;
      if (s.hold) {
        s.holdT += dt;
        arms.rotation.x = -2.4;
        const c = s.hold;
        c.body.pos.set(s.x, 2.5 + Math.sin(s.holdT * 10) * 0.1, s.z + 0.3);
        c.body.vel.set(0, 0, 0);
        if (s.holdT > 1.1) {
          // THROW!
          c.frozen = false;
          c.knock((Math.random() - 0.5) * 6, 8, 7 + Math.random() * 2, 1.1);
          s.hold = null;
          s.cool = 2.2;
          if (this.game.near(c)) sfx('throw');
          if (c.isPlayer) this.game.commentary('thrown');
        }
      } else {
        arms.rotation.x = -1.2 + Math.sin(this.t * 4 + s.ph) * 0.3;
      }
    }
    if (this.gongSwing > 0) {
      this.gongSwing = Math.max(0, this.gongSwing - dt * 0.6);
      this.gongDisc.rotation.z = Math.sin(this.t * 14) * this.gongSwing * 0.15;
      this.gongDisc.position.z = -Math.abs(Math.sin(this.t * 7)) * this.gongSwing * 0.3;
    }
  }

  check(c) {
    if (c.respawning || c.frozen) return;
    const p = c.body.pos;
    for (const s of this.sumos) {
      if (s.hold || s.cool > 0) continue;
      const dx = p.x - s.x;
      const dz = p.z - s.z;
      if (dx * dx + dz * dz < 1.55 * 1.55 && p.y < 1.5) {
        s.hold = c;
        s.holdT = 0;
        c.frozen = true;
        c.diving = false;
        c.celebrate = false;
        c.stun = 0;
        if (this.game.near(c)) sfx('grab');
        if (c.isPlayer) {
          this.game.commentary('grabbed');
          this.game.shake(0.3);
        }
        particles.burst(p.clone().setY(1.2), 0xffffff, 8, 3, 0.1);
        this.cheer(1);
        return;
      }
    }
    super.check(c);
  }

  crossedFinish(c) {
    const p = c.body.pos;
    if (p.z < this.finishZ && Math.abs(p.x) < 2.6) {
      this.gongSwing = 1;
      if (this.game.near(c, 30)) sfx('gong', c.isPlayer ? 1 : 0.5);
      return true;
    }
    return false;
  }

  // ---------- bots ----------
  think(c, dt) {
    if (this.unstick(c, dt)) return;
    const p = c.body.pos;
    c.ai.rethink = (c.ai.rethink ?? 0) - dt;
    const nextRow = ROWS.findIndex((r) => r.z < p.z + 0.6);
    if (nextRow < 0) {
      this.steer(c, 0, GONG_Z);
      return;
    }
    const row = ROWS[nextRow];
    const sumos = this.sumos.filter((s) => s.row === nextRow);
    if (c.ai.rethink <= 0 || c.ai.gapRow !== nextRow) {
      c.ai.rethink = 0.7 - c.skill * 0.5 + Math.random() * 0.2;
      c.ai.gapRow = nextRow;
      let bestX = 0;
      let bestS = -1e9;
      for (let x = -7; x <= 7; x += 0.5) {
        let near = 99;
        for (const s of sumos) near = Math.min(near, Math.abs(x - s.x));
        const score = near - Math.abs(x - p.x) * 0.15 + Math.random() * (1 - c.skill);
        if (score > bestS) {
          bestS = score;
          bestX = x;
        }
      }
      c.ai.gapX = bestX;
    }
    const distToRow = p.z - row.z;
    let near = 99;
    for (const s of sumos) near = Math.min(near, Math.abs(c.ai.gapX - s.x));
    // hang back if the gap is closing, then go
    if (c.ai.waitRow !== nextRow) {
      c.ai.waitRow = nextRow;
      c.ai.patience = 0.6 + Math.random() * 1.4;
    }
    if (distToRow < 5 && distToRow > 2.2 && near < 1.8 && c.skill > 0.3 && c.ai.patience > 0) {
      c.ai.patience -= dt;
      this.steer(c, c.ai.gapX, row.z + 4.5, 0.6);
      return;
    }
    this.steer(c, c.ai.gapX, row.z - 3);
    if (distToRow < 2.8 && distToRow > 1 && Math.random() < 0.08 + c.skill * 0.15) c.control.dive = true;
  }
}

function makeSumo() {
  // two meshes per wrestler (body and arms), vertex-coloured, so a field of sumos stays cheap to draw
  const n = new THREE.Group();
  const skin = 0xf0c8a0;
  const ink = 0x111111;
  const body = mergeGeometries([
    painted(new THREE.SphereGeometry(0.95, 22, 16), skin, 0, 1.25, 0, 0, 0, 0, 1.15, 1, 1),
    painted(new THREE.CylinderGeometry(1.0, 0.92, 0.45, 22), 0x1d2d6b, 0, 0.7, 0),
    painted(new THREE.TorusGeometry(0.98, 0.06, 6, 22), 0xffc93c, 0, 0.9, 0, Math.PI / 2),
    painted(new THREE.SphereGeometry(0.4, 16, 12), skin, 0, 2.35, 0),
    painted(new THREE.SphereGeometry(0.16, 10, 8), ink, 0, 2.75, -0.05),
    painted(new THREE.SphereGeometry(0.41, 16, 8, 0, Math.PI * 2, 0, Math.PI * 0.4), ink, 0, 2.37, 0),
    painted(new THREE.SphereGeometry(0.06, 8, 6), 0xffffff, -0.13, 2.4, 0.35),
    painted(new THREE.SphereGeometry(0.06, 8, 6), 0xffffff, 0.13, 2.4, 0.35),
    painted(new THREE.SphereGeometry(0.035, 6, 4), ink, -0.13, 2.4, 0.4),
    painted(new THREE.SphereGeometry(0.035, 6, 4), ink, 0.13, 2.4, 0.4),
    painted(new THREE.BoxGeometry(0.18, 0.045, 0.04), ink, -0.13, 2.5, 0.37, 0, 0, -0.35),
    painted(new THREE.BoxGeometry(0.18, 0.045, 0.04), ink, 0.13, 2.5, 0.37, 0, 0, 0.35),
    painted(new THREE.TorusGeometry(0.08, 0.02, 6, 10, Math.PI), ink, 0, 2.22, 0.37),
    painted(new THREE.CylinderGeometry(0.26, 0.24, 0.6, 12), skin, -0.45, 0.3, 0),
    painted(new THREE.CylinderGeometry(0.26, 0.24, 0.6, 12), skin, 0.45, 0.3, 0),
  ], false);
  const vm = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.55, envMapIntensity: 0.6 });
  const bm = new THREE.Mesh(body, vm);
  bm.castShadow = true;
  n.add(bm);
  const arms = new THREE.Group();
  arms.position.y = 1.7;
  n.add(arms);
  const am = new THREE.Mesh(mergeGeometries([
    painted(new THREE.CapsuleGeometry(0.2, 0.7, 4, 10), skin, -1.05, -0.45, 0),
    painted(new THREE.CapsuleGeometry(0.2, 0.7, 4, 10), skin, 1.05, -0.45, 0),
  ], false), vm);
  am.castShadow = true;
  arms.add(am);
  arms.rotation.x = -1.2;
  n.userData.arms = arms;
  return n;
}
