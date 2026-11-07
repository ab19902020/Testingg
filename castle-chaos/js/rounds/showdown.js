import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles, textTexture } from '../fx.js';
import { sfx } from '../audio.js';
import { input } from '../input.js';
import { wrapAngle, approach } from '../character.js';
import * as S from '../scenery.js';

// SHOW DOWN: the grand final. Drive a go-kart, fire your water cannon and burst the paper ring
// on the Lord's golden kart to storm the castle. If a guard bursts YOUR ring, you're out.
const X_LIM = 22;
const Z_NEAR = 14;
const Z_FAR = -36;
const SHOT_SPEED = 22;
const SHOT_G = 9;
const KART_R = 1.3;
export const LORD_NAME = 'Lord Kazan';

export class ShowDown extends Round {
  static info = {
    id: 'showdown',
    name: 'Show Down',
    tagline: `The grand final! Burst the paper ring on ${LORD_NAME}'s golden kart. If a guard bursts yours, you're out.`,
    tip: 'Your cannon aims itself at whatever is in front of you. Keep moving!',
    final: true,
  };

  build() {
    this.isKart = true;
    this.width = X_LIM * 2;
    this.startZ = Z_NEAR;
    this.finishZ = Z_FAR;
    this.timeLimit = 120;
    this.sunset = true;
    S.ground(this.group);
    S.mountains(this.group);
    S.castle(this.group, 0, Z_FAR - 34, 2);
    S.forest(this.group, { xMin: -X_LIM - 4, xMax: X_LIM + 4, zFrom: Z_NEAR + 8, zTo: Z_FAR - 10, gap: 8 });
    // tarmac arena with lines and a red barrier
    this.solid(0, -0.05, (Z_NEAR + Z_FAR) / 2, X_LIM * 2 + 4, 0.1, Z_NEAR - Z_FAR + 8, 0x4a4d55, { noCollide: true, cast: false });
    for (let z = Z_NEAR; z > Z_FAR; z -= 5) this.solid(0, 0.01, z, X_LIM * 2, 0.02, 0.15, 0xe9e9e9, { noCollide: true, cast: false });
    const bar = 0xd7263d;
    for (const sx of [-1, 1]) this.solid(sx * (X_LIM + 1.3), 0.5, (Z_NEAR + Z_FAR) / 2, 0.6, 1, Z_NEAR - Z_FAR + 6, bar, { noCollide: true });
    for (const z of [Z_NEAR + 3.3, Z_FAR - 3.3]) this.solid(0, 0.5, z, X_LIM * 2 + 3.2, 1, 0.6, bar, { noCollide: true });
    for (let i = 0; i < 10; i++) {
      this.banners.push(S.banner(this.group, -X_LIM + i * (X_LIM * 2 / 9), Z_FAR - 4.5, i % 2 ? 0xffc93c : 0xd7263d, 5));
    }
    this.crowds.push(S.crowdStand(this.group, -X_LIM - 5, -10, 30, 1, 5));
    this.crowds.push(S.crowdStand(this.group, X_LIM + 5, -10, 30, -1, 5));
    for (let i = 0; i < 8; i++) S.lantern(this.group, -X_LIM + 2 + i * 6, Z_NEAR + 4.5);

    this.karts = [];
    this.shots = [];
    this.shotGeo = new THREE.SphereGeometry(0.16, 8, 6);
    this.shotMat = new THREE.MeshStandardMaterial({ color: 0x5fd0ff, emissive: 0x1a6fa0, roughness: 0.2 });
    this.winner = null;
    this.over = false;
    this.overT = 0;
    // the castle side
    this.lord = this.makeKart({ team: 'castle', role: 'lord', body: 0xffc93c, trim: 0xd7263d, x: 0, z: -26, heading: 0 });
    for (let i = 0; i < 4; i++) {
      const k = this.makeKart({ team: 'castle', role: 'guard', body: 0x2a6fdb, trim: 0x1d3557, x: -15 + i * 10, z: -17 - Math.random() * 5, heading: 0 });
      k.ai.orbit = { cx: -15 + i * 10, cz: -18, rx: 3 + Math.random() * 3, rz: 2 + Math.random() * 3, w: (0.4 + Math.random() * 0.3) * (i % 2 ? -1 : 1), ph: Math.random() * 6 };
      k.ai.cool = 2 + i * 0.6;
      k.ai.skill = 0.5 + Math.random() * 0.3;
    }
    this.lord.ai.cool = 4;
  }

  /** Put the finalists in their karts. Called by the game after build. */
  seat(contestants) {
    const n = contestants.length;
    contestants.forEach((c, i) => {
      const x = (i - (n - 1) / 2) * 5;
      const k = this.makeKart({ team: 'player', role: 'contestant', body: c.suit, trim: 0x222222, x, z: Z_NEAR - 3, heading: Math.PI, driver: c });
      k.ai.cool = 1 + Math.random();
      k.ai.skill = c.skill;
      c.kart = k;
      c.body.noCollide = true;
    });
  }

  makeKart({ team, role, body, trim, x, z, heading, driver = null }) {
    const root = new THREE.Group();
    root.userData.dynamic = true;
    this.group.add(root);
    const bodyM = mat(body, { rough: 0.4 });
    const trimM = mat(trim);
    const add = (geo, m, px, py, pz) => {
      const mesh = new THREE.Mesh(geo, m);
      mesh.position.set(px, py, pz);
      mesh.castShadow = true;
      root.add(mesh);
      return mesh;
    };
    add(new THREE.BoxGeometry(1.6, 0.45, 2.6), bodyM, 0, 0.5, 0);
    add(new THREE.BoxGeometry(1.7, 0.3, 0.5), trimM, 0, 0.45, 1.35);
    add(new THREE.BoxGeometry(1.2, 0.8, 0.25), trimM, 0, 1.0, -0.85);
    const wheelGeo = new THREE.CylinderGeometry(0.34, 0.34, 0.28, 14);
    wheelGeo.rotateZ(Math.PI / 2);
    for (const wx of [-0.88, 0.88]) for (const wz of [-0.85, 0.85]) add(wheelGeo, mat(0x111111), wx, 0.34, wz);
    const cannon = add(new THREE.CylinderGeometry(0.1, 0.13, 1.1, 10), mat(0xc0c4cc, { metal: 0.6, rough: 0.3 }), 0.5, 1.15, 0.45);
    cannon.rotation.x = Math.PI / 2;
    // the paper ring on the front of the kart
    const ring = new THREE.Group();
    ring.position.set(0, 1.25, 1.45);
    root.add(ring);
    const torus = new THREE.Mesh(new THREE.TorusGeometry(0.42, 0.06, 8, 24), mat(0x222222));
    ring.add(torus);
    const paper = new THREE.Mesh(new THREE.CircleGeometry(0.42, 24), new THREE.MeshStandardMaterial({ color: 0xfbf6e4, side: THREE.DoubleSide, roughness: 0.9 }));
    ring.add(paper);
    const strut = new THREE.Mesh(new THREE.BoxGeometry(0.08, 0.6, 0.08), mat(0x222222));
    strut.position.y = -0.5;
    ring.add(strut);
    let figure = null;
    if (role === 'lord') {
      figure = makeLord();
      figure.position.set(0, 0.55, -0.2);
      root.add(figure);
      const sign = new THREE.Mesh(new THREE.PlaneGeometry(2.2, 0.55), new THREE.MeshBasicMaterial({ map: textTexture(LORD_NAME.toUpperCase(), { w: 512, h: 128, fg: '#ffd23f', size: 60 }), transparent: true }));
      sign.position.set(0, 3.4, 0);
      root.add(sign);
      root.userData.sign = sign;
    } else if (role === 'guard') {
      figure = makeGuard();
      figure.position.set(0, 0.55, -0.2);
      root.add(figure);
    }
    const k = {
      root, ring, paper, team, role, driver, figure,
      x, z, heading, speed: 0, ringOk: true, ringHp: role === 'lord' ? 3 : 1, out: false, outT: 0,
      steer: 0, throttle: 0, fire: false, ai: {}, cool: 0,
    };
    this.karts.push(k);
    this.placeKart(k);
    return k;
  }

  placeKart(k) {
    k.root.position.set(k.x, 0, k.z);
    k.root.rotation.y = k.heading;
    if (k.root.userData.sign) k.root.userData.sign.lookAt(this.game.camera.position);
    if (k.driver) {
      const c = k.driver;
      const fx = Math.sin(k.heading);
      const fz = Math.cos(k.heading);
      c.body.pos.set(k.x - fx * 0.2, 0.15, k.z - fz * 0.2);
      c.root.position.copy(c.body.pos);
      c.root.rotation.y = k.heading;
      c.facing = k.heading;
      c.pose.rotation.set(0, 0, 0);
      c.pose.position.y = 0;
      if (k.out) {
        c.flail += 0.3;
        c.armL.rotation.x = -2.5 + Math.sin(c.flail) * 0.6;
        c.armR.rotation.x = -2.5 - Math.sin(c.flail) * 0.6;
      } else {
        c.armL.rotation.x = -1.1;
        c.armR.rotation.x = -1.1;
      }
      c.armL.rotation.z = -0.2;
      c.armR.rotation.z = 0.2;
      c.legL.rotation.x = -1.4;
      c.legR.rotation.x = -1.4;
    }
  }

  ringPos(k, out = new THREE.Vector3()) {
    const fx = Math.sin(k.heading);
    const fz = Math.cos(k.heading);
    return out.set(k.x + fx * 1.45, 1.25, k.z + fz * 1.45);
  }

  startPositions(n) {
    return Array.from({ length: n }, () => V(0, 0, Z_NEAR));
  }

  // ---------- simulation ----------
  update(dt) {
    super.update(dt);
    const racing = this.game.racing && !this.over;
    for (const k of this.karts) {
      if (k.out) {
        k.speed = approach(k.speed, 0, 8 * dt);
        k.outT += dt;
      } else if (racing) {
        if (k.driver && k.driver.isPlayer) this.playerControl(k);
        else this.aiControl(k, dt);
      } else {
        k.throttle = 0;
        k.steer = 0;
        k.fire = false;
      }
      this.drive(k, dt);
    }
    // kart bumping
    for (let i = 0; i < this.karts.length; i++) {
      for (let j = i + 1; j < this.karts.length; j++) {
        const a = this.karts[i];
        const b = this.karts[j];
        const dx = b.x - a.x;
        const dz = b.z - a.z;
        const d = Math.hypot(dx, dz);
        if (d < KART_R * 2 && d > 0.001) {
          const push = (KART_R * 2 - d) / 2;
          a.x -= (dx / d) * push;
          a.z -= (dz / d) * push;
          b.x += (dx / d) * push;
          b.z += (dz / d) * push;
          a.speed *= 0.7;
          b.speed *= 0.7;
          if (this.game.nearPos(a.x, a.z, 12) && Math.random() < 0.1) sfx('thud', 0.3);
        }
      }
    }
    for (const k of this.karts) this.placeKart(k);
    this.updateShots(dt);
    if (this.over) this.overT += dt;
  }

  playerControl(k) {
    k.throttle = input.move.y;
    k.steer = -input.move.x;
    if (Math.abs(k.throttle) < 0.2 && Math.abs(input.move.x) > 0.3) k.throttle = 0.4;
    k.fire = input.takeFire() || (input.fireHeld && k.cool <= 0);
    input.takeJump();
  }

  drive(k, dt) {
    const max = k.role === 'lord' ? 7 : k.role === 'guard' ? 8 : 10.5;
    const target = k.throttle >= 0 ? k.throttle * max : k.throttle * max * 0.5;
    k.speed = approach(k.speed, target, (Math.abs(target) > Math.abs(k.speed) ? 12 : 9) * dt);
    const turn = 2.4 * Math.min(1, 0.35 + Math.abs(k.speed) / max);
    k.heading = wrapAngle(k.heading + k.steer * turn * dt * (k.speed < -0.1 ? -1 : 1));
    k.x += Math.sin(k.heading) * k.speed * dt;
    k.z += Math.cos(k.heading) * k.speed * dt;
    const lim = X_LIM - KART_R;
    if (Math.abs(k.x) > lim) {
      k.x = Math.sign(k.x) * lim;
      k.speed *= 0.5;
    }
    if (k.z > Z_NEAR + 2 - KART_R) {
      k.z = Z_NEAR + 2 - KART_R;
      k.speed *= 0.5;
    }
    if (k.z < Z_FAR - 2 + KART_R) {
      k.z = Z_FAR - 2 + KART_R;
      k.speed *= 0.5;
    }
    k.cool -= dt;
    if (k.fire && k.cool <= 0 && !k.out) this.fire(k);
    k.fire = false;
    // body roll and wheel spin
    k.root.rotation.z = -k.steer * Math.min(1, Math.abs(k.speed) / 8) * 0.06;
    k.paper.visible = k.ringOk;
  }

  enemiesOf(k) {
    return this.karts.filter((o) => o.team !== k.team && !o.out && o.ringOk);
  }

  /** Find something in front to lob a shot at. */
  autoAim(k, cone) {
    let best = null;
    let bestD = 30;
    const rp = new THREE.Vector3();
    for (const o of this.enemiesOf(k)) {
      this.ringPos(o, rp);
      const dx = rp.x - k.x;
      const dz = rp.z - k.z;
      const d = Math.hypot(dx, dz);
      const ang = Math.abs(wrapAngle(Math.atan2(dx, dz) - k.heading));
      if (ang < cone && d < bestD) {
        bestD = d;
        best = { x: rp.x, z: rp.z, y: rp.y, d, o };
      }
    }
    return best;
  }

  fire(k) {
    k.cool = k.role === 'contestant' ? (k.driver?.isPlayer ? 0.32 : 1.3 + Math.random() * 0.6) : k.role === 'lord' ? 3.2 + Math.random() : 2.2 + Math.random() * 1.2;
    const fx = Math.sin(k.heading);
    const fz = Math.cos(k.heading);
    const start = new THREE.Vector3(k.x + fx * 1.0 + Math.cos(k.heading) * 0.5, 1.2, k.z + fz * 1.0 - Math.sin(k.heading) * 0.5);
    const cone = k.role === 'lord' ? Math.PI : k.driver?.isPlayer ? 0.32 : 0.25;
    const tgt = this.autoAim(k, cone);
    let dirX = fx;
    let dirZ = fz;
    let vy = 2.5;
    if (tgt) {
      // lead a moving target a little and work out the lob
      const lead = tgt.d / SHOT_SPEED;
      const tx = tgt.x + Math.sin(tgt.o.heading) * tgt.o.speed * lead * 0.8;
      const tz = tgt.z + Math.cos(tgt.o.heading) * tgt.o.speed * lead * 0.8;
      const dx = tx - start.x;
      const dz = tz - start.z;
      const d = Math.hypot(dx, dz);
      const t = d / SHOT_SPEED;
      const spread = k.driver?.isPlayer ? 0.015 : k.role === 'contestant' ? 0.1 + (1 - (k.ai.skill ?? 0.5)) * 0.1 : k.role === 'lord' ? 0.22 : 0.2;
      const a = Math.atan2(dx, dz) + (Math.random() - 0.5) * spread * 2;
      dirX = Math.sin(a);
      dirZ = Math.cos(a);
      vy = (tgt.y - start.y) / t + 0.5 * SHOT_G * t + (Math.random() - 0.5) * spread * 8;
    }
    const mesh = new THREE.Mesh(this.shotGeo, this.shotMat);
    mesh.position.copy(start);
    this.group.add(mesh);
    this.shots.push({ pos: start, vel: new THREE.Vector3(dirX * SHOT_SPEED, vy, dirZ * SHOT_SPEED), team: k.team, from: k, mesh, life: 3 });
    if (this.game.nearPos(k.x, k.z, 16)) sfx('shoot', k.driver?.isPlayer ? 1 : 0.4);
  }

  updateShots(dt) {
    const rp = new THREE.Vector3();
    for (let i = this.shots.length - 1; i >= 0; i--) {
      const s = this.shots[i];
      s.vel.y -= SHOT_G * dt;
      s.pos.addScaledVector(s.vel, dt);
      s.mesh.position.copy(s.pos);
      s.life -= dt;
      let dead = s.life <= 0 || s.pos.y < 0;
      if (s.pos.y < 0) particles.burst(s.pos.clone().setY(0.05), [0x9fd8ff, 0xffffff], 6, 2, 0.08);
      if (!dead) {
        for (const k of this.karts) {
          if (k.team === s.team || k.out) continue;
          this.ringPos(k, rp);
          // the ring is on the front of the kart, so it only bursts from a shot coming at the front
          const frontOn = s.vel.x * Math.sin(k.heading) + s.vel.z * Math.cos(k.heading) < 0;
          if (k.ringOk && frontOn && rp.distanceToSquared(s.pos) < 0.55 * 0.55) {
            this.burstRing(k, s.from);
            dead = true;
            break;
          }
          const dx = s.pos.x - k.x;
          const dz = s.pos.z - k.z;
          if (dx * dx + dz * dz < 1.1 && s.pos.y < 1.6) {
            particles.burst(s.pos.clone(), [0x9fd8ff, 0xffffff], 8, 2.5, 0.08);
            dead = true;
            break;
          }
        }
      }
      if (dead) {
        this.group.remove(s.mesh);
        this.shots.splice(i, 1);
      }
    }
  }

  burstRing(k, by) {
    if (k.ringHp > 1) {
      // the Lord's ring has extra layers of paper: each hit tears one off
      k.ringHp--;
      k.paper.material.color.set(k.ringHp === 2 ? 0xe9dcb8 : 0xc9b48a);
      k.paper.scale.setScalar(k.ringHp === 2 ? 0.8 : 0.55);
      particles.burst(this.ringPos(k), [0xfbf6e4, 0xf1e6c8, 0x5fd0ff], 14, 3, 0.14, { flat: true, life: 1.2, gravity: 6 });
      if (this.game.nearPos(k.x, k.z, 40)) sfx('rip');
      this.cheer(0.8);
      this.game.caption(`${by?.driver?.isPlayer ? 'You tear' : (by?.driver?.name || 'Someone') + ' tears'} a layer off ${LORD_NAME}'s ring! ${k.ringHp} left!`, '#ffd23f', 2.5);
      return;
    }
    k.ringOk = false;
    k.out = true;
    const p = this.ringPos(k);
    particles.burst(p, [0xfbf6e4, 0xf1e6c8, 0x5fd0ff], 30, 4, 0.16, { flat: true, life: 1.6, gravity: 6 });
    if (this.game.nearPos(k.x, k.z, 30)) sfx('pop');
    this.cheer(1);
    if (k.role === 'lord') {
      this.over = true;
      this.winner = by?.driver || null;
      sfx('gong');
      sfx('cheer');
      particles.confetti(new THREE.Vector3(k.x, 3, k.z), 120);
      this.game.commentary('lordDown', by?.driver);
    } else if (k.role === 'contestant') {
      k.driver.out = true;
      if (k.driver.isPlayer) {
        this.game.commentary('ringBurst');
        this.game.shake(0.4);
      }
      if (this.karts.every((o) => o.role !== 'contestant' || o.out)) {
        this.over = true;
        this.winner = null;
        this.game.commentary('castleWins');
      }
    } else if (k.role === 'guard') {
      if (by?.driver?.isPlayer) this.game.commentary('guardDown');
    }
  }

  // ---------- AI drivers ----------
  aiControl(k, dt) {
    const steerTo = (tx, tz, throttle = 1) => {
      const want = Math.atan2(tx - k.x, tz - k.z);
      const diff = wrapAngle(want - k.heading);
      k.steer = Math.max(-1, Math.min(1, diff * 2.2));
      k.throttle = throttle * (Math.abs(diff) > 1.6 ? 0.5 : 1);
      return diff;
    };
    const fireIfAimed = (cone) => {
      if (k.cool > 0) return;
      const t = this.autoAim(k, cone);
      if (t && t.d < (k.role === 'contestant' ? 20 : 19)) k.fire = true;
    };
    if (k.role === 'lord') {
      // keep the ring pointed away from whoever is closest, and stay near the castle end
      let near = null;
      let nd = 15;
      for (const o of this.karts) {
        if (o.role !== 'contestant' || o.out) continue;
        const d = Math.hypot(o.x - k.x, o.z - k.z);
        if (d < nd) {
          nd = d;
          near = o;
        }
      }
      if (near) {
        const ax = k.x + (k.x - near.x) * 2;
        const az = Math.max(Z_FAR + 4, Math.min(-6, k.z + (k.z - near.z) * 2));
        steerTo(Math.max(-X_LIM + 4, Math.min(X_LIM - 4, ax)), az, 0.9);
      } else {
        const a = this.t * 0.25;
        steerTo(Math.sin(a) * 8, -24 + Math.cos(a) * 5, 0.6);
      }
      fireIfAimed(Math.PI);
      return;
    }
    if (k.role === 'guard') {
      // chase the closest contestant who's getting near the Lord, else patrol
      let prey = null;
      let best = 18;
      for (const o of this.karts) {
        if (o.role !== 'contestant' || o.out) continue;
        const d = Math.hypot(o.x - k.x, o.z - k.z);
        if (d < best) {
          best = d;
          prey = o;
        }
      }
      if (prey) {
        if (best < 7) steerTo(k.x + (k.x - prey.x), k.z + (k.z - prey.z), 0.7);
        else steerTo(prey.x, prey.z, 0.8);
        const diff = wrapAngle(Math.atan2(prey.x - k.x, prey.z - k.z) - k.heading);
        if (Math.abs(diff) < 0.25 && k.cool <= 0 && best < 16) k.fire = true;
      } else {
        const o = k.ai.orbit;
        const t = this.t * o.w + o.ph;
        steerTo(o.cx + Math.sin(t) * o.rx, o.cz + Math.cos(t) * o.rz, 0.6);
      }
      return;
    }
    // contestant bots: go for the Lord, dodge guards that get close
    const lord = this.lord;
    const d = Math.hypot(lord.x - k.x, lord.z - k.z);
    let threat = null;
    for (const o of this.karts) {
      if (o.role !== 'guard' || o.out) continue;
      if (Math.hypot(o.x - k.x, o.z - k.z) < 6) threat = o;
    }
    k.ai.wob = (k.ai.wob ?? Math.random() * 6) + dt;
    if (threat && Math.random() < 0.6) {
      // shoot the guard if it's in front, otherwise swerve away
      const diff = steerTo(threat.x, threat.z, 0.5);
      if (Math.abs(diff) > 1.2) steerTo(k.x + (k.x - threat.x) + Math.sin(k.ai.wob) * 3, k.z + (k.z - threat.z), 1);
      fireIfAimed(0.3);
      return;
    }
    if (d > 15) {
      steerTo(lord.x + Math.sin(k.ai.wob * 0.7) * 5, lord.z + 8, 1);
    } else {
      // circle and shoot
      const side = k.ai.side ?? (k.ai.side = Math.random() < 0.5 ? -1 : 1);
      const ang = Math.atan2(k.x - lord.x, k.z - lord.z) + side * 0.6;
      const diff = wrapAngle(Math.atan2(lord.x - k.x, lord.z - k.z) - k.heading);
      if (Math.abs(diff) < 0.5 || d < 8) steerTo(lord.x, lord.z, d < 8 ? -0.3 : 0.4);
      else steerTo(lord.x + Math.sin(ang) * 11, lord.z + Math.cos(ang) * 11, 0.9);
    }
    fireIfAimed(0.22);
  }

  // ---------- camera ----------
  updateCamera(cam, dt, focus) {
    const k = focus?.kart || this.karts.find((o) => o.role === 'contestant' && !o.out) || this.lord;
    const fx = Math.sin(k.heading);
    const fz = Math.cos(k.heading);
    const want = new THREE.Vector3(k.x - fx * 9, 6, k.z - fz * 9);
    const look = new THREE.Vector3(k.x + fx * 7, 1, k.z + fz * 7);
    if (this.over && this.lord) {
      want.set(this.lord.x + 6, 5, this.lord.z + 9);
      look.set(this.lord.x, 1.5, this.lord.z);
    }
    const a = 1 - Math.exp(-4 * dt);
    cam.position.lerp(want, a);
    cam.userData.look = cam.userData.look || look.clone();
    cam.userData.look.lerp(look, a);
    cam.lookAt(cam.userData.look);
    return k;
  }

  check() {}
}

function makeLord() {
  const g = new THREE.Group();
  const robe = new THREE.Mesh(new THREE.CylinderGeometry(0.45, 0.7, 1.1, 14), mat(0x8e1b2b));
  robe.position.y = 0.6;
  g.add(robe);
  const sash = new THREE.Mesh(new THREE.TorusGeometry(0.5, 0.06, 6, 16), mat(0xffc93c, { metal: 0.6, rough: 0.3 }));
  sash.rotation.x = Math.PI / 2;
  sash.position.y = 0.75;
  g.add(sash);
  const head = new THREE.Mesh(new THREE.SphereGeometry(0.34, 14, 10), mat(0xf0c8a0));
  head.position.y = 1.45;
  g.add(head);
  // golden kabuto helmet with horns
  const helm = new THREE.Mesh(new THREE.SphereGeometry(0.38, 14, 8, 0, Math.PI * 2, 0, Math.PI * 0.5), mat(0xffc93c, { metal: 0.7, rough: 0.3 }));
  helm.position.y = 1.5;
  g.add(helm);
  for (const sx of [-1, 1]) {
    const horn = new THREE.Mesh(new THREE.ConeGeometry(0.06, 0.7, 6), mat(0xffc93c, { metal: 0.7, rough: 0.3 }));
    horn.position.set(sx * 0.25, 2.0, 0.15);
    horn.rotation.z = -sx * 0.5;
    g.add(horn);
  }
  for (const sx of [-0.12, 0.12]) {
    const eye = new THREE.Mesh(new THREE.SphereGeometry(0.045, 6, 4), mat(0x111111));
    eye.position.set(sx, 1.48, 0.31);
    g.add(eye);
  }
  const tache = new THREE.Mesh(new THREE.BoxGeometry(0.3, 0.06, 0.05), mat(0x111111));
  tache.position.set(0, 1.35, 0.32);
  g.add(tache);
  return g;
}

function makeGuard() {
  const g = new THREE.Group();
  const body = new THREE.Mesh(new THREE.CylinderGeometry(0.35, 0.45, 0.9, 12), mat(0x1d3557));
  body.position.y = 0.5;
  g.add(body);
  const head = new THREE.Mesh(new THREE.SphereGeometry(0.28, 12, 8), mat(0xf0c8a0));
  head.position.y = 1.2;
  g.add(head);
  const helm = new THREE.Mesh(new THREE.ConeGeometry(0.42, 0.3, 12), mat(0x222222));
  helm.position.y = 1.45;
  g.add(helm);
  return g;
}
