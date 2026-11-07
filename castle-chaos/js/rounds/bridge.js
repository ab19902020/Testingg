import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles, painted, mergeGeometries } from '../fx.js';
import { sfx } from '../audio.js';
import { repeat } from '../textures.js';

// BRIDGE BALL: cross a wobbly rope bridge over the river while cannons on both sides
// fire big balls across it. Get hit and you're knocked into the water.
const BANK_EDGE = -2;
const BRIDGE_W = 2.2;
const SEG = [
  { z0: BANK_EDGE + 0.5, z1: -28 },
  { z0: -32, z1: -60 },
];
const MID = { z0: -28, z1: -32 };
const FAR_BANK = -60;
const BALL_R = 0.42;

export class BridgeBall extends Round {
  static info = {
    id: 'bridge',
    name: 'Bridge Ball',
    tagline: 'Cross the wobbly rope bridge while cannons fire balls at you. Get hit and you go in the river!',
    tip: 'Watch the cannons glow, then jump over the ball or wait for it to pass.',
  };

  build() {
    this.width = 12;
    this.startZ = 2;
    this.finishZ = FAR_BANK - 3.5;
    this.timeLimit = 120;
    this.camOffset = V(0, 6, 8.5);
    this.camLook = V(0, 0.5, -6);
    this.respawnWidth = 1.2;
    this.riverCourse(BANK_EDGE, FAR_BANK);

    // bridge decks (a separate group for each span so they can sway)
    this.spans = [];
    const plankGeo = new THREE.BoxGeometry(BRIDGE_W, 0.14, 0.78);
    const plankMat = mat(0xffffff, { rough: 0.8, map: repeat('wood', 1, 0.5) });
    const ropeMat = mat(0xc9a46b, { rough: 0.9 });
    const postMat = mat(0x6b4526, { rough: 0.8 });
    for (const sg of SEG) {
      const g = new THREE.Group();
      g.userData.dynamic = true;
      const cz = (sg.z0 + sg.z1) / 2;
      g.position.set(0, 0, cz);
      this.group.add(g);
      const len = sg.z0 - sg.z1;
      const planks = [];
      for (let z = sg.z0 - 0.45; z > sg.z1; z -= 0.86) {
        const p = plankGeo.clone();
        p.rotateY((Math.random() - 0.5) * 0.04);
        p.translate(0, -0.07, z - cz);
        planks.push(p);
      }
      const deck = new THREE.Mesh(mergeGeometries(planks, false), plankMat);
      deck.castShadow = deck.receiveShadow = true;
      g.add(deck);
      // posts and rope hand-rails (just for show: they won't stop you falling)
      const postParts = [];
      for (let z = sg.z0; z >= sg.z1 - 0.01; z -= 4) {
        for (const sx of [-1, 1]) postParts.push(painted(new THREE.CylinderGeometry(0.07, 0.08, 1.2, 6), 0x6b4526, sx * (BRIDGE_W / 2), 0.5, z - cz));
      }
      g.add(new THREE.Mesh(mergeGeometries(postParts, false), new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.8 })));
      for (const sx of [-1, 1]) {
        for (const y of [0.55, 1.05]) {
          const pts = [];
          for (let i = 0; i <= 16; i++) {
            const t = i / 16;
            pts.push(new THREE.Vector3(sx * BRIDGE_W / 2, y - Math.sin(t * Math.PI) * 0.12, sg.z0 - t * len - cz));
          }
          g.add(new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts), 24, 0.035, 4), ropeMat));
        }
      }
      // thick posts at each end, standing in the water
      for (const z of [sg.z0, sg.z1]) {
        for (const sx of [-1, 1]) {
          const post = new THREE.Mesh(new THREE.CylinderGeometry(0.16, 0.2, 3, 8), postMat);
          post.position.set(sx * (BRIDGE_W / 2 + 0.15), -0.4, z);
          this.group.add(post);
          this.foam(sx * (BRIDGE_W / 2 + 0.15), z, 0.45);
        }
      }
      const col = this.world.addBox(0, -0.1, cz, BRIDGE_W, 0.2, len, { bridge: true });
      this.spans.push({ g, col, cz, len, ph: Math.random() * 6 });
    }
    // halfway platform (checkpoint)
    const mid = this.floor(-2.6, 2.6, MID.z0, MID.z1, 0, 0, 1.2, { tex: 'wood', texScale: 1.5 });
    mid.col.mid = true;
    for (const sx of [-1, 1]) for (const z of [MID.z0, MID.z1]) this.foam(sx * 2.6, z, 0.6);
    this.foam(0, MID.z0, 1.5);
    this.foam(0, MID.z1, 1.5);

    // cannons on little islands either side
    this.cannons = [];
    const zs = [-5, -9, -13, -17, -21, -25, -35, -39, -43, -47, -51, -55];
    zs.forEach((z, i) => {
      const side = i % 2 ? 1 : -1;
      const x = side * 8.5;
      const g = new THREE.Group();
      g.position.set(x, 0, z);
      g.userData.dynamic = true;
      this.group.add(g);
      const rock = new THREE.Mesh(new THREE.CylinderGeometry(1.4, 1.8, 1.6, 9), mat(0x7f7a72, { flat: true, rough: 0.95, map: repeat('stone', 2, 0.6) }));
      rock.position.y = -0.5;
      g.add(rock);
      this.foam(x, z, 1.9);
      const barrel = new THREE.Mesh(new THREE.CylinderGeometry(0.42, 0.55, 1.8, 14), mat(0x2a2d34, { rough: 0.35, metal: 0.7 }));
      barrel.rotation.z = Math.PI / 2;
      barrel.position.set(-side * 0.5, 0.95, 0);
      g.add(barrel);
      const rim = new THREE.Mesh(new THREE.TorusGeometry(0.44, 0.08, 8, 16), mat(0xffc93c, { rough: 0.3, metal: 0.8 }));
      rim.rotation.y = Math.PI / 2;
      rim.position.set(-side * 1.4, 0.95, 0);
      g.add(rim);
      for (const wz of [-0.55, 0.55]) {
        const wheel = new THREE.Mesh(new THREE.CylinderGeometry(0.45, 0.45, 0.14, 14), mat(0x6b4526));
        wheel.rotation.x = Math.PI / 2;
        wheel.position.set(0, 0.5, wz);
        g.add(wheel);
      }
      const glow = new THREE.Mesh(new THREE.SphereGeometry(0.36, 12, 8), new THREE.MeshBasicMaterial({ color: 0xff5a1f, transparent: true, opacity: 0, toneMapped: false }));
      glow.position.set(-side * 1.45, 0.95, 0);
      g.add(glow);
      this.cannons.push({ x, z, side, g, barrel, glow, t: 1.5 + Math.random() * 3, charge: 0 });
    });
    this.balls = [];
    this.ballGeo = new THREE.SphereGeometry(BALL_R, 18, 12);
    this.ballMat = mat(0xffffff, { rough: 0.45 });
    this.ballMat2 = mat(0x111111, { rough: 0.45 });
  }

  startPositions(n) {
    // queue up at the bridge
    const out = [];
    for (let i = 0; i < n; i++) out.push(V(((i % 5) - 2) * 1.4 + (Math.random() - 0.5) * 0.2, 0, this.startZ + 1 + Math.floor(i / 5) * 1.3));
    return out.sort(() => Math.random() - 0.5);
  }

  respawnFor(c) {
    const p = c.checkpoint.clone();
    p.x = (Math.random() - 0.5) * 1.2;
    p.y += 0.5;
    return p;
  }

  update(dt) {
    super.update(dt);
    // sway the spans
    for (const sp of this.spans) {
      sp.sway = Math.sin(this.t * 1.3 + sp.ph) * 0.06 + Math.sin(this.t * 3.1 + sp.ph * 2) * 0.02;
      sp.g.rotation.z = sp.sway;
      sp.g.position.y = Math.sin(this.t * 2.1 + sp.ph) * 0.04;
    }
    // cannons charge up (glow), then fire
    for (const cn of this.cannons) {
      if (!this.game.racing) continue;
      // cannons speed up when someone is coming past them
      const busy = this.game.racers.some((c) => !c.finished && Math.abs(c.body.pos.z - cn.z) < 3.5);
      cn.t -= dt * (busy ? 1.6 : 0.7);
      if (cn.t < 0.8) {
        cn.charge = 1 - cn.t / 0.8;
        cn.glow.material.opacity = cn.charge * 0.9;
        cn.glow.scale.setScalar(0.6 + cn.charge * 0.7);
      }
      if (cn.t <= 0) {
        cn.t = 1.8 + Math.random() * 1.6;
        cn.glow.material.opacity = 0;
        cn.barrel.position.x = cn.side * 0.1;
        const mesh = new THREE.Mesh(this.ballGeo, Math.random() < 0.5 ? this.ballMat : this.ballMat2);
        mesh.castShadow = true;
        this.group.add(mesh);
        // aim at the nearest contestant coming up to this cannon
        let zz = cn.z + (Math.random() - 0.5) * 1.2;
        const near = this.game.racers.filter((c) => !c.finished && Math.abs(c.body.pos.z - cn.z) < 4).sort((a, b) => Math.abs(a.body.pos.z - cn.z) - Math.abs(b.body.pos.z - cn.z))[0];
        if (near && Math.random() < 0.5) zz = near.body.pos.z - 0.8 + (Math.random() - 0.5) * 1.6;
        this.balls.push({ x: cn.x - cn.side * 1.4, y: 0.95, z: zz, vx: -cn.side * (9 + Math.random() * 3), vy: 2.2, mesh });
        particles.burst(new THREE.Vector3(cn.x - cn.side * 1.5, 1, cn.z), [0xdddddd, 0xffffff, 0xffa040], 10, 3, 0.2, { up: 0.5, gravity: -1, life: 0.8 });
        if (this.game.nearPos(cn.x, cn.z, 18)) sfx('shoot', 0.7);
      }
      cn.barrel.position.x += (-cn.side * 0.5 - cn.barrel.position.x) * Math.min(1, dt * 4);
    }
    for (let i = this.balls.length - 1; i >= 0; i--) {
      const b = this.balls[i];
      b.vy -= 6 * dt;
      b.x += b.vx * dt;
      b.y += b.vy * dt;
      b.z += 0;
      if (b.y < BALL_R + 0.02 && Math.abs(b.x) < BRIDGE_W / 2 + 0.2) {
        b.y = BALL_R + 0.02;
        b.vy = Math.abs(b.vy) * 0.55; // bounce along the deck
      }
      b.mesh.position.set(b.x, b.y, b.z);
      b.mesh.rotation.z -= (b.vx / BALL_R) * dt;
      if (b.y < this.waterY || Math.abs(b.x) > 14) {
        if (b.y < this.waterY + 0.3) particles.burst(new THREE.Vector3(b.x, this.waterY + 0.1, b.z), [0xffffff, 0x9fd8ff], 12, 3, 0.12);
        this.group.remove(b.mesh);
        this.balls.splice(i, 1);
      }
    }
  }

  check(c) {
    if (c.respawning) return;
    const b = c.body;
    const p = b.pos;
    if (b.grounded && b.ground?.bridge && this.game.racing) {
      // the bridge wobbles you about a bit
      const sp = this.spans.find((s) => s.col === b.ground);
      if (sp) b.vel.x += sp.sway * 55 * (1 / 60);
    }
    if (b.grounded && b.ground?.mid) this.setCheckpoint(c, 0, 0, (MID.z0 + MID.z1) / 2);
    for (const ball of this.balls) {
      const dx = p.x - ball.x;
      const dy = p.y + 0.8 - ball.y;
      const dz = p.z - ball.z;
      if (dx * dx + dy * dy + dz * dz < (BALL_R + 0.45) ** 2 && c.stun <= 0.2) {
        c.knock(Math.sign(ball.vx) * 7.5, 4.5, (Math.random() - 0.5) * 2, 0.9);
        ball.vx *= 0.4;
        if (this.game.near(c)) sfx('thud');
        if (c.isPlayer) {
          this.game.caption(pickLine(), '#ffd0d0');
          this.game.shake(0.4);
        }
      }
    }
    if (p.y < this.waterY + 0.15) {
      this.sendBack(c, 'water', 1.2);
      return;
    }
    super.check(c);
  }

  // ---------- bots ----------
  think(c, dt) {
    const p = c.body.pos;
    if (c.ai.lane === undefined) c.ai.lane = (Math.random() - 0.5) * 0.6;
    const onBridge = p.z < BANK_EDGE + 1 && p.z > FAR_BANK;
    const lane = onBridge ? c.ai.lane : c.ai.lane * 4;
    // wait at the start of the bridge a moment so they don't all pile on at once
    this.steer(c, onBridge || p.z < BANK_EDGE + 3 ? lane : p.x * 0.8, p.z - 4);
    if (!onBridge) return;
    // jump over incoming balls (if they notice in time)
    for (const ball of this.balls) {
      if (Math.abs(ball.z - p.z) > 0.9) continue;
      const toward = (p.x - ball.x) * Math.sign(ball.vx);
      if (toward < 0) continue;
      const t = toward / Math.abs(ball.vx);
      if (t < 0.34 && t > 0.06 && c.body.grounded && Math.random() < 0.4 + c.skill * 0.5) c.control.jump = true;
      // stop short if a ball is about to cross just ahead
      if (Math.abs(ball.z - (p.z - 1.2)) < 0.8 && t < 0.6 && c.skill > 0.5) {
        c.control.x *= 0.2;
        c.control.z = 0.1;
      }
    }
  }
}

const LINES = ['BOOF! Right in the belly!', 'Bowled off the bridge!', 'Incoming! Too late!'];
function pickLine() {
  return LINES[Math.floor(Math.random() * LINES.length)];
}
