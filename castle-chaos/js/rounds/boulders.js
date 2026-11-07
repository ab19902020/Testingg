import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles } from '../fx.js';
import { sfx } from '../audio.js';
import * as S from '../scenery.js';
import { repeat } from '../textures.js';

// BOULDER DASH: run up the castle slope while giant boulders roll down. Duck behind the bunkers!
const RAMP_LEN = 48;
const RAMP_H = 12;
const SLOPE = RAMP_H / RAMP_LEN;

export class BoulderDash extends Round {
  static info = {
    id: 'boulders',
    name: 'Boulder Dash',
    tagline: 'Run up the slope while giant boulders roll down at you. Hide behind the bunkers!',
    tip: 'Watch the top of the hill and sidestep early.',
  };

  h(z) {
    if (z >= 0) return 0;
    if (z <= -RAMP_LEN) return RAMP_H;
    return -z * SLOPE;
  }

  build() {
    this.width = 12;
    this.startZ = 4;
    this.finishZ = -RAMP_LEN - 3;
    this.finishY = RAMP_H;
    this.timeLimit = 100;
    this.camOffset = V(0, 5.8, 9);
    this.camLook = V(0, 2.2, -6);
    this.respawnWidth = 8;
    this.decorate({ castleZ: -100, castleY: RAMP_H, crowds: false, skipZ: [3, -1000] });
    for (let i = 0; i < 22; i++) {
      const side = i % 2 ? 1 : -1;
      const t = (Math.random() < 0.6 ? S.cherryTree : S.pineTree)(this.group, side * (10 + Math.random() * 25), -RAMP_LEN - 4 - Math.random() * 40, 1 + Math.random() * 0.5);
      t.position.y = RAMP_H;
    }
    for (let i = 0; i < 16; i++) {
      const side = i % 2 ? 1 : -1;
      (Math.random() < 0.5 ? S.cherryTree : S.pineTree)(this.group, side * (9 + Math.random() * 20), 4 + Math.random() * 20, 1.1);
    }
    const w = this.width / 2;
    this.floor(-w - 1, w + 1, 12, 0, 0xc9a46b, 0, 1, { tex: 'dirt', texScale: 3 });
    this.world.addRamp(-w, w, 0, -RAMP_LEN, 0, RAMP_H);
    // the visible slope
    const len = Math.hypot(RAMP_LEN, RAMP_H);
    const ang = Math.atan(SLOPE);
    const ramp = new THREE.Mesh(new THREE.BoxGeometry(this.width, 0.6, len), new THREE.MeshStandardMaterial({ map: rampTexture(), roughness: 0.95 }));
    ramp.material.map.repeat.set(2, 10);
    ramp.rotation.x = ang;
    const n = new THREE.Vector3(0, Math.cos(ang), Math.sin(ang));
    ramp.position.set(0, RAMP_H / 2, -RAMP_LEN / 2).addScaledVector(n, -0.3);
    ramp.receiveShadow = true;
    this.group.add(ramp);
    // the hill top with the castle on it
    this.floor(-120, 120, -RAMP_LEN, -260, 0x6cc24a, RAMP_H, RAMP_H + 1, { tex: 'grass', texScale: 7, texTint: 0xd8f0c0 });
    this.solid(0, RAMP_H + 0.01, -RAMP_LEN - 8, this.width + 2, 0.02, 16, 0xc9a46b, { noCollide: true, cast: false, tex: 'dirt', texScale: 3 });
    // embankments either side of the slope
    for (const sx of [-1, 1]) {
      for (let z = 0; z > -RAMP_LEN; z -= 4) {
        const top = this.h(z - 4) + 1.6;
        this.solid(sx * (w + 0.4), top / 2 - 0.5, z - 2, 0.8, top + 1, 4.02, 0xd7263d);
      }
      const bank = new THREE.Mesh(new THREE.BoxGeometry(30, 0.5, len), mat(0xd8f0c0, { map: repeat('grass', 4, 7) }));
      bank.receiveShadow = true;
      bank.rotation.x = ang;
      bank.position.set(sx * (w + 15.8), RAMP_H / 2 - 0.4, -RAMP_LEN / 2);
      this.group.add(bank);
    }
    // bunkers: duck behind these
    this.bunkers = [];
    const rows = [-8, -15, -22, -29, -36, -42];
    rows.forEach((z, i) => {
      const xs = i % 2 === 0 ? [-3.6, 0.6, 4.4] : [-4.6, -0.8, 3.2];
      for (const x of xs) {
        const xx = x + (Math.random() - 0.5) * 0.6;
        const top = this.h(z - 0.6) + 1.5;
        const bottom = this.h(z + 0.6) - 0.3;
        const { mesh } = this.solid(xx, (top + bottom) / 2, z, 2.2, top - bottom, 1.2, 0x8a8f99);
        mesh.material = mat(0xffffff, { rough: 0.95, map: repeat('stone', 1.5, 1) });
        this.solid(xx, top + 0.08, z, 2.3, 0.16, 1.3, 0xd7263d, { noCollide: true });
        this.bunkers.push({ x: xx, z, hx: 1.1, hz: 0.6, top });
      }
    });
    this.boulders = [];
    this.spawnT = 1.2;
    this.boulderGeo = new THREE.IcosahedronGeometry(1, 1);
    this.warnGeo = new THREE.PlaneGeometry(1, 1);
    this.warnGeo.rotateX(-Math.PI / 2);
    this.warnMat = new THREE.MeshBasicMaterial({ map: S.tex('shadow'), transparent: true, depthWrite: false, opacity: 0.9, toneMapped: false });
    this.boulderMat = mat(0xb5a68f, { flat: true, rough: 0.95, map: repeat('stone', 2, 2) });
  }

  spawnBoulder() {
    const r = 0.7 + Math.random() * 0.45;
    const mesh = new THREE.Mesh(this.boulderGeo, this.boulderMat);
    mesh.scale.setScalar(r);
    mesh.castShadow = true;
    this.group.add(mesh);
    // a dark warning shadow slides down the slope ahead of each boulder
    const warn = new THREE.Mesh(this.warnGeo, this.warnMat);
    warn.renderOrder = 1;
    this.group.add(warn);
    const x = (Math.random() - 0.5) * (this.width - 2 * r - 0.4);
    const z = -RAMP_LEN - 1;
    this.boulders.push({ x, z, y: this.h(z) + r, r, vz: 1.5 + Math.random() * 1.5, vx: (Math.random() - 0.5) * 0.8, mesh, warn, dustT: 0 });
  }

  update(dt) {
    super.update(dt);
    this.update2(dt);
    if (this.game.racing) {
      this.spawnT -= dt;
      const pace = Math.min(1, this.t / 60);
      if (this.spawnT <= 0) {
        this.spawnBoulder();
        if (Math.random() < 0.1 + pace * 0.2) this.spawnBoulder();
        this.spawnT = 1.6 - pace * 0.4 + Math.random() * 0.6;
      }
    }
    const w = this.width / 2;
    for (let i = this.boulders.length - 1; i >= 0; i--) {
      const b = this.boulders[i];
      if (b.z < 0) b.vz = Math.min(10, b.vz + 4 * dt);
      else b.vz *= 1 - dt * 0.5;
      b.x += b.vx * dt;
      b.z += b.vz * dt;
      if (Math.abs(b.x) > w - b.r) {
        b.x = Math.sign(b.x) * (w - b.r);
        b.vx = -b.vx * 0.6;
      }
      b.y = this.h(b.z) + b.r;
      b.mesh.position.set(b.x, b.y, b.z);
      b.mesh.rotation.x += (b.vz / b.r) * dt;
      b.mesh.rotation.z -= (b.vx / b.r) * dt;
      // shadow on the slope just in front, so you can see where it's heading
      const wz = b.z + b.r * 0.6;
      b.warn.position.set(b.x, this.h(wz) + 0.03, wz);
      b.warn.scale.set(b.r * 1.3, 1, b.r * 1.9);
      b.dustT -= dt;
      if (b.dustT <= 0 && this.game.nearPos(b.x, b.z, 25)) {
        b.dustT = 0.08;
        particles.burst(new THREE.Vector3(b.x, this.h(b.z) + 0.1, b.z - b.r * 0.6), [0xd9c49a, 0xc9a46b], 2, 1.5, 0.2, { up: 0.5, gravity: 2, life: 0.6 });
      }
      const smashed = b.z > 14;
      // glance off the bunkers and keep rolling (people hiding behind them stay safe)
      for (const k of this.bunkers) {
        const reach = k.hx + b.r * 0.55;
        if (Math.abs(b.x - k.x) < reach && Math.abs(b.z - k.z) < k.hz + b.r * 0.55) {
          if (b.lastK !== k) {
            b.lastK = k;
            // go round whichever side has more room
            const room = (sx) => {
              const x = k.x + sx * (reach + 0.2);
              if (Math.abs(x) > w - b.r) return -1;
              let free = 9;
              for (const o of this.bunkers) if (o !== k && Math.abs(o.z - k.z) < 1 && Math.abs(o.x - x) < o.hx + b.r) free = 0;
              return free + (Math.sign(b.x - k.x) === sx ? 1 : 0);
            };
            b.dodge = room(1) >= room(-1) ? 1 : -1;
            b.vz = Math.max(2.5, b.vz * 0.8);
            if (this.game.nearPos(b.x, b.z, 20)) sfx('thud', 0.35);
            particles.burst(new THREE.Vector3(b.x, b.y, b.z - b.r * 0.6), [0x8b7d6b, 0xa89880], 6, 3, 0.15);
          }
          b.x += b.dodge * 6 * dt;
          b.vx = b.dodge * 3;
        }
      }
      b.vx *= 1 - dt * 0.6;
      if (smashed) {
        if (b.z < 14) {
          particles.burst(new THREE.Vector3(b.x, b.y, b.z), [0x8b7d6b, 0x6e6152, 0xa89880], 22, 6, 0.25);
          if (this.game.nearPos(b.x, b.z)) sfx('smash', 0.8);
        }
        this.group.remove(b.mesh);
        this.group.remove(b.warn);
        this.boulders.splice(i, 1);
      }
    }
  }

  check(c) {
    if (c.respawning) return;
    const p = c.body.pos;
    for (const b of this.boulders) {
      const dx = p.x - b.x;
      const dy = p.y + 0.8 - b.y;
      const dz = p.z - b.z;
      const rr = b.r + 0.45;
      if (dx * dx + dy * dy + dz * dz < rr * rr && c.stun <= 0.3 && !(c.safeT > 0)) {
        // knocked back a little way, then a moment of protection so you can't get hit twice
        c.knock(Math.sign(dx || 1) * (2 + Math.random() * 2), 6, 5 + b.vz * 0.3, 0.7);
        c.safeT = 2;
        if (this.game.near(c)) sfx('thud');
        if (c.isPlayer) {
          this.game.commentary('boulder');
          this.game.shake(0.5);
        }
        particles.burst(p.clone().setY(p.y + 1), 0xffffff, 8, 3, 0.1);
      }
    }
    super.check(c);
  }

  respawnFor(c) {
    return new THREE.Vector3((Math.random() - 0.5) * 8, 0.5, 2);
  }

  update2(dt) {
    // protection timer and blink after a hit
    for (const c of this.game.racers) {
      if (c.safeT > 0) {
        c.safeT -= dt;
        c.pose.visible = c.safeT <= 0 || Math.floor(c.safeT * 10) % 2 === 0;
        if (c.safeT <= 0) c.pose.visible = true;
      }
    }
  }

  // ---------- bots ----------
  think(c, dt) {
    const p = c.body.pos;
    if (p.z < this.finishZ + 1) {
      this.steer(c, c.ai.lane ?? 0, this.finishZ - 6);
      return;
    }
    if (c.ai.lane === undefined) c.ai.lane = (Math.random() - 0.5) * 8;
    if (this.unstick(c, dt)) return;
    const sight = 6 + c.skill * 10;
    let threat = null;
    for (const b of this.boulders) {
      const ahead = p.z - b.z;
      if (ahead < -0.5 || ahead > sight) continue;
      if (Math.abs(b.x - p.x) < b.r + 1.0) {
        if (!threat || b.z > threat.z) threat = b;
      }
    }
    // bots don't always notice in time
    if (threat && c.ai.noticed !== threat) {
      if (Math.random() < 0.08 + c.skill * 0.25) c.ai.noticed = threat;
      else threat = null;
    }
    if (threat) {
      // hide behind a nearby bunker, or sidestep
      const bunker = this.bunkers.find((k) => k.z < p.z + 0.5 && p.z - k.z < 3.5 && Math.abs(k.x - p.x) < 2.2);
      if (bunker && c.skill > 0.35) {
        this.steer(c, bunker.x, bunker.z + 1.3);
        return;
      }
      let side = Math.sign(p.x - threat.x) || (Math.random() < 0.5 ? -1 : 1);
      let tx = threat.x + side * (threat.r + 1.8);
      if (Math.abs(tx) > 5.2) {
        side = -side;
        tx = threat.x + side * (threat.r + 1.8);
      }
      c.ai.lane = Math.max(-5, Math.min(5, tx));
      this.steer(c, c.ai.lane, p.z - 1);
      return;
    }
    // steer round bunkers in the way
    const block = this.bunkers.find((k) => k.z < p.z && p.z - k.z < 3 && Math.abs(k.x - c.ai.lane) < k.hx + 0.6);
    if (block) c.ai.lane = block.x + (c.ai.lane > block.x ? 1 : -1) * (block.hx + 0.9);
    c.ai.lane = Math.max(-5.2, Math.min(5.2, c.ai.lane));
    this.steer(c, c.ai.lane, p.z - 5);
  }
}

function rampTexture() {
  const c = document.createElement('canvas');
  c.width = 128;
  c.height = 128;
  const g = c.getContext('2d');
  g.fillStyle = '#c9a46b';
  g.fillRect(0, 0, 128, 128);
  g.fillStyle = '#b48f58';
  for (let i = 0; i < 4; i++) g.fillRect(0, i * 32, 128, 4);
  for (let i = 0; i < 60; i++) {
    g.fillStyle = Math.random() < 0.5 ? '#d8b77f' : '#a8844f';
    g.fillRect(Math.random() * 128, Math.random() * 128, 3, 3);
  }
  const t = new THREE.CanvasTexture(c);
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}
