import * as THREE from 'three';
import { GRAVITY } from './physics.js';
import { sfx } from './audio.js';
import { particles, painted, mergeGeometries } from './fx.js';
import { tex } from './textures.js';

// Movement feel
export const RUN_SPEED = 7.2;
const ACCEL_GROUND = 55;
const ACCEL_AIR = 16;
const JUMP_V = 8.8;
const DIVE_H = 10.5;
const COYOTE = 0.12;

const SKIN = [0xf2c6a0, 0xe0a97e, 0xc68863, 0x8d5a3b, 0xf6d5b8];

// shared geometry for every contestant
const G = {};
function geoms() {
  if (G.torso) return G;
  G.torso = new THREE.CapsuleGeometry(0.36, 0.42, 6, 14);
  G.head = new THREE.SphereGeometry(0.27, 16, 12);
  G.helmet = new THREE.SphereGeometry(0.3, 16, 10, 0, Math.PI * 2, 0, Math.PI * 0.55);
  G.brim = new THREE.CylinderGeometry(0.31, 0.31, 0.06, 18);
  G.eye = new THREE.SphereGeometry(0.045, 8, 6);
  G.arm = new THREE.CapsuleGeometry(0.085, 0.32, 4, 8);
  G.leg = new THREE.CapsuleGeometry(0.11, 0.24, 4, 8);
  G.shoe = new THREE.BoxGeometry(0.2, 0.1, 0.3);
  G.bib = new THREE.PlaneGeometry(0.42, 0.4);
  G.marker = new THREE.ConeGeometry(0.22, 0.4, 4);
  G.mouth = new THREE.TorusGeometry(0.07, 0.018, 6, 10, Math.PI);
  G.torso = new THREE.CapsuleGeometry(0.37, 0.42, 8, 20);
  G.head = new THREE.SphereGeometry(0.28, 24, 18);
  G.helmet = new THREE.SphereGeometry(0.31, 24, 12, 0, Math.PI * 2, 0, Math.PI * 0.55);
  G.brim = new THREE.CylinderGeometry(0.32, 0.32, 0.06, 24);
  G.stripe = new THREE.TorusGeometry(0.29, 0.035, 6, 24);
  G.sclera = new THREE.SphereGeometry(0.07, 12, 10);
  G.pupil = new THREE.SphereGeometry(0.038, 10, 8);
  G.glint = new THREE.SphereGeometry(0.014, 6, 4);
  G.cheek = new THREE.SphereGeometry(0.05, 8, 6);
  G.belt = new THREE.TorusGeometry(0.37, 0.05, 6, 24);
  G.hand = new THREE.SphereGeometry(0.1, 12, 8);
  G.leg = new THREE.CapsuleGeometry(0.115, 0.24, 6, 12);
  G.shoe = new THREE.CapsuleGeometry(0.1, 0.14, 4, 10);
  G.shoe.rotateX(Math.PI / 2);
  G.shadow = new THREE.PlaneGeometry(1.2, 1.2);
  G.shadow.rotateX(-Math.PI / 2);
  G.ring = new THREE.PlaneGeometry(1.7, 1.7);
  G.ring.rotateX(-Math.PI / 2);
  return G;
}

const SHADOW_MAT = new THREE.MeshBasicMaterial({ map: tex('shadow'), transparent: true, depthWrite: false, toneMapped: false });
const RING_MAT = new THREE.MeshBasicMaterial({ map: tex('ring'), transparent: true, depthWrite: false, toneMapped: false });

/** The game sets this so only contestants near the camera kick up dust. */
export const dustFilter = { near: () => true };

function bibTexture(num, color) {
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  const g = c.getContext('2d');
  g.fillStyle = '#fbf7ec';
  g.fillRect(0, 0, 64, 64);
  g.strokeStyle = '#' + new THREE.Color(color).getHexString();
  g.lineWidth = 6;
  g.strokeRect(3, 3, 58, 58);
  g.fillStyle = '#111';
  g.font = 'bold 36px Arial, sans-serif';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.fillText(String(num), 32, 35);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

export class Contestant {
  constructor(scene, { name, suit, helmet, number, isPlayer = false, skill = 0.5 }) {
    this.name = name;
    this.suit = suit;
    this.helmetColor = helmet;
    this.number = number;
    this.isPlayer = isPlayer;
    this.skill = skill; // 0..1 for bots: speed, reaction and smarts
    this.body = { pos: new THREE.Vector3(), vel: new THREE.Vector3(), r: 0.36, h: 1.6, grounded: false, ground: null, owner: this };
    this.body.onLand = (c) => this._onLand(c);
    this.control = { x: 0, z: 0, jump: false, dive: false };
    this.facing = Math.PI; // facing -z (towards the castle)
    this.state = 'idle';
    this.stun = 0;
    this.slide = 0;
    this.diving = false;
    this.coyote = 0;
    this.frozen = false; // controlled by the round (held by a sumo, stuck in mud...)
    this.active = true; // still in the round
    this.finished = false;
    this.celebrate = false;
    this.speedMul = isPlayer ? 1 : 0.86 + skill * 0.13;
    this.airTime = 0;
    this.phase = Math.random() * 10;
    this.flail = 0;
    this.spin = 0;
    this.squash = 0;
    this.ai = {};
    this.checkpoint = new THREE.Vector3();
    this._build(scene);
  }

  _build(scene) {
    const g = geoms();
    const root = new THREE.Group();
    const pose = new THREE.Group();
    root.add(pose);
    const skin = SKIN[this.number % SKIN.length];
    const dark = 0x161616;
    // glossy, toy-like plastic: low roughness plus the environment reflections
    const vmat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.32, envMapIntensity: 0.55 });
    this.vmat = vmat;
    const part = (parent, geos, x, y, z, shadow = false) => {
      const m = new THREE.Mesh(mergeGeometries(geos, false), vmat);
      m.position.set(x, y, z);
      m.castShadow = shadow;
      parent.add(m);
      return m;
    };
    // torso + bib
    part(pose, [
      painted(g.torso, this.suit, 0, 0.88, 0),
      painted(g.belt, 0x222222, 0, 0.62, 0, Math.PI / 2),
    ], 0, 0, 0, true);
    const bib = new THREE.Mesh(g.bib, new THREE.MeshStandardMaterial({ map: bibTexture(this.number, this.helmetColor), roughness: 0.7 }));
    bib.position.set(0, 0.92, 0.372);
    pose.add(bib);
    // head, helmet, face in one mesh
    const head = part(pose, [
      painted(g.head, skin),
      painted(g.helmet, this.helmetColor, 0, 0.02, 0, -0.18),
      painted(g.brim, this.helmetColor, 0, 0.03, 0.02, -0.18, 0, 0, 1, 1, 1.05),
      painted(g.stripe, this.suit, 0, 0.1, 0.01, Math.PI / 2 - 0.18, 0, 0, 1, 1, 1),
      painted(g.sclera, 0xffffff, -0.1, -0.02, 0.22, 0, 0, 0, 1, 1.2, 0.8),
      painted(g.sclera, 0xffffff, 0.1, -0.02, 0.22, 0, 0, 0, 1, 1.2, 0.8),
      painted(g.pupil, dark, -0.1, -0.02, 0.27),
      painted(g.pupil, dark, 0.1, -0.02, 0.27),
      painted(g.glint, 0xffffff, -0.088, 0.0, 0.302),
      painted(g.glint, 0xffffff, 0.112, 0.0, 0.302),
      painted(g.cheek, 0xff8a8a, -0.17, -0.1, 0.2, 0, 0, 0, 1, 0.6, 0.5),
      painted(g.cheek, 0xff8a8a, 0.17, -0.1, 0.2, 0, 0, 0, 1, 0.6, 0.5),
      painted(g.mouth, dark, 0, -0.13, 0.25, 0, 0, Math.PI),
    ], 0, 1.56, 0, true);
    this.head = head;
    const limb = (geos, x, y) => {
      const piv = new THREE.Group();
      piv.position.set(x, y, 0);
      part(piv, geos, 0, 0, 0);
      pose.add(piv);
      return piv;
    };
    const arm = () => [painted(g.arm, this.suit, 0, -0.22, 0), painted(g.hand, 0xffffff, 0, -0.46, 0)];
    const leg = () => [painted(g.leg, this.suit, 0, -0.2, 0), painted(g.shoe, 0x1c1c22, 0, -0.39, 0.06)];
    this.armL = limb(arm(), -0.43, 1.18);
    this.armR = limb(arm(), 0.43, 1.18);
    this.legL = limb(leg(), -0.16, 0.42);
    this.legR = limb(leg(), 0.16, 0.42);
    // soft contact shadow so you can always tell where someone will land
    const sh = new THREE.Mesh(g.shadow, SHADOW_MAT);
    sh.renderOrder = 1;
    root.add(sh);
    this.blob = sh;
    if (this.isPlayer) {
      const ring = new THREE.Mesh(g.ring, RING_MAT);
      ring.renderOrder = 2;
      root.add(ring);
      this.ring = ring;
      const mk = new THREE.Mesh(g.marker, new THREE.MeshBasicMaterial({ color: 0xffd23f, toneMapped: false }));
      mk.rotation.x = Math.PI;
      mk.position.y = 2.35;
      root.add(mk);
      this.marker = mk;
    }
    this.root = root;
    this.pose = pose;
    scene.add(root);
  }

  dispose(scene) {
    scene.remove(this.root);
    this.root.traverse((o) => {
      if (o.geometry && ![G.bib, G.marker, G.shadow, G.ring].includes(o.geometry)) o.geometry.dispose();
    });
  }

  placeAt(v, facing = Math.PI) {
    this.body.pos.copy(v);
    this.body.vel.set(0, 0, 0);
    this.body.grounded = false;
    this.body.ground = null;
    this.facing = facing;
    this.stun = 0;
    this.slide = 0;
    this.diving = false;
    this.frozen = false;
    this.spin = 0;
    this.root.position.copy(v);
    this.root.visible = true;
  }

  /** Knock the contestant flying. */
  knock(vx, vy, vz, stun = 0.8) {
    if (this.frozen) return;
    this.body.vel.set(vx, vy, vz);
    this.body.grounded = false;
    this.body.ground = null;
    this.stun = Math.max(this.stun, stun);
    this.diving = false;
    this.slide = 0;
    this.spin = 9 + Math.random() * 4;
  }

  respawn(v) {
    this.placeAt(v, Math.PI);
    this.stun = 0;
    this.squash = 0.3;
    particles.burst(v.clone().setY(v.y + 1), 0xffffff, 14, 3, 0.12);
  }

  _onLand() {
    if (this.airTime > 0.25) {
      this.squash = Math.min(0.35, this.airTime * 0.4);
      if (this.isPlayer) sfx('land', 0.8);
    }
    if (this.diving) {
      this.diving = false;
      this.slide = 0.42;
    }
  }

  /** Physics + controls. Called every frame by the game. */
  update(dt, world) {
    const b = this.body;
    if (this.frozen) {
      this.animate(dt);
      return;
    }
    const v = b.vel;
    if (b.grounded) this.coyote = COYOTE;
    else this.coyote -= dt;
    v.y -= GRAVITY * dt;
    if (v.y < -30) v.y = -30;

    const c = this.control;
    let mx = c.x;
    let mz = c.z;
    const ml = Math.hypot(mx, mz);
    if (ml > 1) {
      mx /= ml;
      mz /= ml;
    }
    const canControl = this.stun <= 0 && this.slide <= 0 && !this.diving;
    if (this.stun > 0) {
      this.stun -= dt;
      if (b.grounded) {
        const f = Math.max(0, 1 - 6 * dt);
        v.x *= f;
        v.z *= f;
        this.spin *= Math.max(0, 1 - 5 * dt);
      }
    } else if (this.slide > 0) {
      this.slide -= dt;
      const f = Math.max(0, 1 - 5 * dt);
      v.x *= f;
      v.z *= f;
    } else if (!this.diving) {
      const sp = RUN_SPEED * this.speedMul * (b.groundKind === 'slow' ? 0.55 : 1);
      const acc = b.grounded ? ACCEL_GROUND : ACCEL_AIR;
      v.x = approach(v.x, mx * sp, acc * dt);
      v.z = approach(v.z, mz * sp, acc * dt);
    }
    if (canControl && ml > 0.15) {
      this.facing = turnTo(this.facing, Math.atan2(mx, mz), 14 * dt);
    }

    if (canControl && c.jump && this.coyote > 0) {
      v.y = JUMP_V;
      this.coyote = 0;
      b.grounded = false;
      if (this.isPlayer) sfx('jump');
    }
    if (canControl && c.dive) {
      const dx = ml > 0.15 ? mx / Math.max(ml, 0.01) : Math.sin(this.facing);
      const dz = ml > 0.15 ? mz / Math.max(ml, 0.01) : Math.cos(this.facing);
      this.facing = Math.atan2(dx, dz);
      v.x = dx * DIVE_H * (0.9 + this.speedMul * 0.1);
      v.z = dz * DIVE_H * (0.9 + this.speedMul * 0.1);
      v.y = b.grounded || this.coyote > 0 ? 5.2 : Math.max(v.y, 2.2);
      this.diving = true;
      b.grounded = false;
      if (this.isPlayer) sfx('dive');
    }
    c.jump = false;
    c.dive = false;

    if (!b.grounded) this.airTime += dt;
    world.step(b, dt);
    if (b.grounded) this.airTime = 0;
    this.groundY = b.grounded ? b.pos.y : world.groundAt(b.pos.x, b.pos.z, b.pos.y + 0.05);
    // kick up dust when running
    const hs = Math.hypot(b.vel.x, b.vel.z);
    if (b.grounded && hs > 4 && b.groundKind !== 'noDust') {
      this.dustT = (this.dustT || 0) - dt;
      if (this.dustT <= 0) {
        this.dustT = 0.11 + Math.random() * 0.06;
        if (dustFilter.near(this)) {
          particles.burst(new THREE.Vector3(b.pos.x, b.pos.y + 0.08, b.pos.z), [0xe6d3ad, 0xd2bb8e, 0xf2e6cc], 2, 0.9, 0.14, { up: 0.6, gravity: 1.5, life: 0.55, spin: 2 });
        }
      }
    }
    this.animate(dt);
  }

  animate(dt) {
    const b = this.body;
    this.root.position.copy(b.pos);
    this.root.rotation.y = this.facing;
    const hs = Math.hypot(b.vel.x, b.vel.z);
    const run = b.grounded && this.slide <= 0 ? Math.min(1, hs / RUN_SPEED) : 0;
    this.phase += dt * (5 + hs * 1.7);
    const s = Math.sin(this.phase);
    let armX = -s * 1.1 * run;
    let armZ = 0.15;
    let legX = s * 1.0 * run;
    let lean = 0.18 * run;
    let bob = Math.abs(Math.cos(this.phase)) * 0.09 * run;
    let rollZ = 0;
    if (!b.grounded && !this.diving && this.stun <= 0) {
      armX = -2.6 + Math.sin(this.phase * 1.5) * 0.3;
      armZ = 0.5;
      legX = b.vel.y > 0 ? 0.6 : -0.3;
      lean = 0.05;
    }
    if (this.diving || this.slide > 0) {
      lean = 1.35;
      armX = -2.9;
      armZ = 0.25;
      legX = -0.2;
      bob = 0.2;
    }
    if (this.stun > 0 || this.frozen) {
      this.flail += dt * 22;
      armX = -2 + Math.sin(this.flail) * 1.2;
      armZ = 0.9 + Math.cos(this.flail * 1.3) * 0.5;
      legX = Math.sin(this.flail * 1.1) * 0.9;
      lean = 0;
    }
    if (this.celebrate && b.grounded && this.stun <= 0) {
      this.flail += dt * 10;
      armX = -2.8;
      armZ = 0.4 + Math.sin(this.flail) * 0.4;
      bob = Math.abs(Math.sin(this.flail * 0.5)) * 0.4;
    }
    // tumbling when knocked
    if (this.stun > 0 && this.spin > 0.2) {
      this.pose.rotation.x += this.spin * dt;
    } else {
      this.pose.rotation.x = approach(wrapAngle(this.pose.rotation.x), lean, 8 * dt);
    }
    this.pose.rotation.z = approach(this.pose.rotation.z, rollZ, 8 * dt);
    this.squash = Math.max(0, this.squash - dt * 2.2);
    const sq = Math.sin(this.squash * Math.PI * 3) * this.squash;
    this.pose.scale.set(1 + sq * 0.5, 1 - sq, 1 + sq * 0.5);
    this.pose.position.y = bob + (this.diving || this.slide > 0 ? 0.25 : 0);
    this.armL.rotation.x = approach(this.armL.rotation.x, armX, 18 * dt);
    this.armR.rotation.x = approach(this.armR.rotation.x, this.celebrate || !b.grounded || this.diving || this.stun > 0 || this.frozen ? armX : -armX, 18 * dt);
    this.armL.rotation.z = -armZ;
    this.armR.rotation.z = armZ;
    this.legL.rotation.x = approach(this.legL.rotation.x, legX, 18 * dt);
    this.legR.rotation.x = approach(this.legR.rotation.x, this.diving || this.slide > 0 ? legX : -legX, 18 * dt);
    if (this.marker) {
      this.marker.position.y = 2.35 + Math.sin(performance.now() / 250) * 0.08;
      this.marker.rotation.y += dt * 2;
    }
    // contact shadow (and the player's ring) sit on the ground below, shrinking with height
    const gy = this.groundY ?? b.pos.y;
    const hgt = Math.max(0, b.pos.y - gy);
    const show = gy > -50 && hgt < 12 && this.root.visible;
    this.blob.visible = show && !this.kart;
    if (show) {
      this.blob.position.y = gy - b.pos.y + 0.04;
      const k = Math.max(0.35, 1 - hgt * 0.12);
      this.blob.scale.set(k, 1, k);
      this.blob.material.opacity = 1;
    }
    if (this.ring) {
      this.ring.visible = show && !this.kart;
      this.ring.position.y = gy - b.pos.y + 0.05;
      this.ring.rotation.y = -this.facing + performance.now() / 900;
      const p = 1 + Math.sin(performance.now() / 200) * 0.06;
      this.ring.scale.set(p, 1, p);
    }
  }
}

export function approach(a, b, d) {
  if (a < b) return Math.min(b, a + d);
  return Math.max(b, a - d);
}
export function wrapAngle(a) {
  while (a > Math.PI) a -= Math.PI * 2;
  while (a < -Math.PI) a += Math.PI * 2;
  return a;
}
export function turnTo(a, b, d) {
  const diff = wrapAngle(b - a);
  if (Math.abs(diff) <= d) return b;
  return a + Math.sign(diff) * d;
}

// ---------------------------------------------------------------------------
// The contestant line-up: names, colours and bib numbers.
// ---------------------------------------------------------------------------
const SUITS = [0xe63946, 0x2a6fdb, 0x2bb673, 0xff8c1a, 0x9b5de5, 0x00b4d8, 0xf15bb5, 0x8ac926, 0xffbe0b, 0x3a3a8c, 0xd62828, 0x06d6a0];
const HELMETS = [0xffd23f, 0xffffff, 0xff5d8f, 0x4cc9f0, 0xff9f1c, 0xb8f15b];
const NAMES = [
  'Big Kenji', 'Mama Yuki', 'Turbo Taro', 'Hiro the Hero', 'Noodle', 'Sushi Sam', 'Aiko', 'Daisuke', 'Banzai Bob',
  'Rin', 'Captain Kaz', 'Speedy Sora', 'Tofu Tom', 'Mika', 'Grandpa Goro', 'Yoshi K', 'Rocket Ren', 'Haruka',
  'Wobbly Wes', 'Kumiko', 'Shin', 'Lucky Lee', 'Momo', 'Ichiro', 'Nana', 'Kenta the Tank',
];

export function makeLineup(count, playerName) {
  const list = [];
  const names = NAMES.slice().sort(() => Math.random() - 0.5);
  const nums = [];
  while (nums.length < count) {
    const n = 1 + Math.floor(Math.random() * 99);
    if (!nums.includes(n)) nums.push(n);
  }
  for (let i = 0; i < count; i++) {
    const isPlayer = i === 0;
    list.push({
      name: isPlayer ? playerName : names[i % names.length],
      suit: isPlayer ? 0xe63946 : SUITS[(i * 5) % SUITS.length],
      helmet: isPlayer ? 0xffd23f : HELMETS[(i * 7) % HELMETS.length],
      number: nums[i],
      isPlayer,
      skill: isPlayer ? 1 : Math.min(1, Math.max(0, 0.15 + Math.random() * 0.85)),
    });
  }
  return list;
}
