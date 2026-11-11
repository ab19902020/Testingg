import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles } from '../fx.js';
import { sfx } from '../audio.js';
import { repeat } from '../textures.js';

// BIG BALLS: bounce across rows of giant red balls floating in the river.
// Every landing throws you back into the air. Land off-centre and you get flung sideways.
const BANK_EDGE = -2;
const LANES = [-5, 0, 5];
const ROWS_A = [-6.8, -12.3, -17.8, -23.3];
const MID = { z0: -27.5, z1: -32 };
const ROWS_B = [-36.8, -42.3, -47.8, -53.3];
const FAR_BANK = -57.5;
const BALL_R = 1.9; // visual radius
const TOP = 0.7; // height of the top of a ball
const LAND_R = 1.55; // how much of the top you can land on

export class BigBalls extends Round {
  static info = {
    id: 'bigballs',
    name: 'Big Balls',
    tagline: 'Bounce across the giant red balls. Land in the middle or get flung into the river!',
    tip: 'You bounce automatically. Steer in the air and aim for the middle of the next ball.',
  };

  build() {
    this.width = 14;
    this.startZ = 2;
    this.finishZ = FAR_BANK - 3.5;
    this.timeLimit = 110;
    this.camOffset = V(0, 7, 9.5);
    this.camLook = V(0, 1, -6);
    this.respawnWidth = 9;
    this.riverCourse(BANK_EDGE, FAR_BANK);
    const mid = this.floor(-7, 7, MID.z0, MID.z1, 0, 0, 1.4, { tex: 'stripes', texScale: 2 });
    mid.col.mid = true;
    for (const x of [-6, -2, 2, 6]) {
      this.foam(x, MID.z0, 1.6);
      this.foam(x, MID.z1, 1.6);
    }
    const geo = new THREE.SphereGeometry(BALL_R, 32, 20);
    const red = mat(0xe8222e, { rough: 0.22, env: 1.2 });
    const band = mat(0xffffff, { rough: 0.3 });
    const bandGeo = new THREE.TorusGeometry(BALL_R * 0.995, 0.09, 8, 40);
    this.balls = [];
    const add = (x, z, sway) => {
      const g = new THREE.Group();
      g.userData.dynamic = true;
      const m = new THREE.Mesh(geo, red);
      m.castShadow = true;
      g.add(m);
      const b1 = new THREE.Mesh(bandGeo, band);
      b1.rotation.x = Math.PI / 2;
      g.add(b1);
      this.group.add(g);
      const col = this.world.addCyl(x, TOP, z, LAND_R, 2.2);
      const ball = { x0: x, x, z, g, col, sway, ph: Math.random() * 6, w: 0.7 + Math.random() * 0.5, squish: 0, foamRef: { x, z, hidden: false } };
      col.ball = ball;
      this.foam(x, z, BALL_R * 0.92).follow = ball.foamRef;
      this.balls.push(ball);
    };
    for (const z of ROWS_A) for (const x of LANES) add(x + (Math.random() - 0.5) * 0.6, z, 0);
    ROWS_B.forEach((z, i) => {
      for (const x of LANES) add(x, z, i % 2 ? 1.4 : 0.9);
    });
    this.rowsZ = [...ROWS_A, ...ROWS_B];
    // padded posts at the launch edges
    for (const z of [BANK_EDGE, MID.z0, MID.z1, FAR_BANK]) {
      for (const sx of [-1, 1]) {
        const post = new THREE.Mesh(new THREE.CylinderGeometry(0.35, 0.35, 1.6, 12), mat(0xffffff, { map: repeat('stripes', 2, 1), rough: 0.4 }));
        post.position.set(sx * 7.4, 0.8, z + (z === MID.z1 || z === BANK_EDGE ? 0.4 : -0.4));
        post.castShadow = true;
        this.group.add(post);
      }
    }
  }

  update(dt) {
    super.update(dt);
    for (const b of this.balls) {
      if (b.sway) b.x = b.x0 + Math.sin(this.t * b.w + b.ph) * b.sway;
      b.squish = Math.max(0, b.squish - dt * 2.5);
      const sq = Math.sin(b.squish * Math.PI * 2) * b.squish * 0.18;
      this.world.moveTo(b.col, b.x, b.col.y, b.z);
      b.g.position.set(b.x, TOP - BALL_R + Math.sin(this.t * 1.7 + b.ph) * 0.06, b.z);
      b.g.scale.set(1 + sq, 1 - sq, 1 + sq);
      b.g.rotation.y = this.t * 0.2 + b.ph;
      b.foamRef.x = b.x;
    }
  }

  check(c) {
    if (c.respawning) return;
    const b = c.body;
    const g = b.grounded ? b.ground : null;
    if (g && g.ball && b.vel.y <= 0.1) {
      const ball = g.ball;
      const dx = (b.pos.x - ball.x) / LAND_R;
      const dz = (b.pos.z - ball.z) / LAND_R;
      b.vel.y = 10.6;
      // off-centre landings throw you sideways; a nudge forward keeps the rhythm going
      b.vel.x += dx * 4.5;
      b.vel.z += dz * 1.5 - 1.6;
      b.grounded = false;
      ball.squish = 1;
      c.squash = 0.3;
      c.diving = false;
      c.slide = 0;
      particles.burst(b.pos.clone(), [0xffffff, 0xff6b6b], 8, 3, 0.12);
      if (this.game.near(c)) sfx('boing', c.isPlayer ? 1 : 0.5);
    }
    if (g && g.mid) this.setCheckpoint(c, 0, 0, (MID.z0 + MID.z1) / 2);
    if (b.pos.y < this.waterY + 0.15) {
      this.sendBack(c, 'water', 1.0);
      return;
    }
    super.check(c);
  }

  // ---------- bots ----------
  think(c) {
    const b = c.body;
    const p = b.pos;
    const ai = c.ai;
    if (p.z < FAR_BANK + 0.5) {
      this.steer(c, ai.lane ?? (ai.lane = (Math.random() - 0.5) * 8), this.finishZ - 6);
      return;
    }
    // where next: a ball in the next row, or a platform
    const nextRowZ = this.rowsZ.find((z) => z < p.z - 1.6);
    const midAhead = p.z > MID.z0 + 0.3 && (nextRowZ === undefined || nextRowZ < MID.z1);
    const bankAhead = nextRowZ === undefined;
    let tx;
    let tz;
    if (midAhead && p.z < ROWS_A[ROWS_A.length - 1] + 1.6) {
      tx = Math.max(-5.5, Math.min(5.5, p.x));
      tz = MID.z0 - 1.6;
    } else if (bankAhead) {
      tx = Math.max(-5.5, Math.min(5.5, p.x));
      tz = FAR_BANK - 2;
    } else {
      if (!ai.ball || ai.ball.z !== nextRowZ) {
        const row = this.balls.filter((o) => o.z === nextRowZ);
        ai.ball = row.reduce((a, o) => (Math.abs(o.x - p.x) + Math.random() < Math.abs(a.x - p.x) + Math.random() ? o : a));
        ai.err = { x: (Math.random() - 0.5) * (1 - c.skill) * 1.6, z: (Math.random() - 0.5) * (1 - c.skill) * 1.2 };
      }
      tx = ai.ball.x + ai.err.x;
      tz = ai.ball.z + ai.err.z;
    }
    const d = this.steer(c, tx, tz);
    // on solid ground: run up and jump at the edge
    const g = b.grounded ? b.ground : null;
    if (g && !g.ball) {
      const edgeZ = g.mid ? MID.z1 : BANK_EDGE;
      if (p.z - edgeZ < 0.25) c.control.jump = true;
    }
  }
}
