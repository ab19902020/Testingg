import * as THREE from 'three';
import { GRAVITY } from './physics.js';
import { sfx } from './audio.js';
import { particles, painted, mergeGeometries } from './fx.js';
import { tex } from './textures.js';

// Movement feel
export const RUN_SPEED = 7.2;
const ACCEL_GROUND = 55;
const ACCEL_AIR = 24;
const JUMP_V = 8.8;
const DIVE_H = 10.5;
const COYOTE = 0.16;

const SKIN = [0xf2c6a0, 0xe0a97e, 0xc68863, 0x8d5a3b, 0xf6d5b8];

/** Things the player can choose in the locker. */
export const SUIT_COLORS = [0xe63946, 0x2a6fdb, 0x2bb673, 0xff8c1a, 0x9b5de5, 0x00b4d8, 0xf15bb5, 0x8ac926, 0xffbe0b, 0x3a3a8c, 0x111827, 0xf5f5f5];
export const HELMET_COLORS = [0xffd23f, 0xffffff, 0xff5d8f, 0x4cc9f0, 0xff9f1c, 0xb8f15b, 0x9b5de5, 0x1f2937];
export const HATS = ['none', 'horns', 'propeller', 'fin', 'antenna', 'mohawk', 'ears', 'crown'];
export const HAT_NAMES = { none: 'No hat', horns: 'Horns', propeller: 'Propeller', fin: 'Shark fin', antenna: 'Antenna', mohawk: 'Mohawk', ears: 'Bear ears', crown: 'Crown' };

// shared geometry for every contestant
const G = {};
function geoms() {
  if (G.torso) return G;
  G.torso = new THREE.CapsuleGeometry(0.4, 0.36, 8, 22);
  G.belly = new THREE.SphereGeometry(0.3, 16, 12);
  G.belt = new THREE.TorusGeometry(0.4, 0.05, 6, 24);
  G.head = new THREE.SphereGeometry(0.33, 24, 18);
  G.helmet = new THREE.SphereGeometry(0.365, 24, 12, 0, Math.PI * 2, 0, Math.PI * 0.55);
  G.brim = new THREE.CylinderGeometry(0.375, 0.375, 0.06, 24);
  G.stripe = new THREE.TorusGeometry(0.34, 0.04, 6, 24);
  G.sclera = new THREE.SphereGeometry(0.09, 14, 10);
  G.pupil = new THREE.SphereGeometry(0.048, 10, 8);
  G.glint = new THREE.SphereGeometry(0.018, 6, 4);
  G.cheek = new THREE.SphereGeometry(0.055, 8, 6);
  G.mouth = new THREE.SphereGeometry(0.075, 12, 8, 0, Math.PI * 2, Math.PI / 2, Math.PI / 2);
  G.arm = new THREE.CapsuleGeometry(0.1, 0.3, 4, 10);
  G.hand = new THREE.SphereGeometry(0.13, 12, 8);
  G.leg = new THREE.CapsuleGeometry(0.13, 0.2, 6, 12);
  G.shoe = new THREE.CapsuleGeometry(0.125, 0.16, 4, 10);
  G.shoe.rotateX(Math.PI / 2);
  G.sole = new THREE.BoxGeometry(0.24, 0.05, 0.4);
  G.bib = new THREE.PlaneGeometry(0.44, 0.4);
  G.marker = new THREE.ConeGeometry(0.22, 0.4, 4);
  G.shadow = new THREE.PlaneGeometry(1.3, 1.3);
  G.shadow.rotateX(-Math.PI / 2);
  G.ring = new THREE.PlaneGeometry(1.8, 1.8);
  G.ring.rotateX(-Math.PI / 2);
  // hat parts
  G.horn = new THREE.ConeGeometry(0.08, 0.34, 8);
  G.cone = new THREE.ConeGeometry(0.1, 0.3, 4);
  G.ball = new THREE.SphereGeometry(0.08, 10, 8);
  G.stick = new THREE.CylinderGeometry(0.02, 0.02, 0.3, 5);
  G.blade = new THREE.BoxGeometry(0.62, 0.02, 0.1);
  G.fin = new THREE.ConeGeometry(0.2, 0.42, 3);
  G.ear = new THREE.SphereGeometry(0.12, 10, 8);
  G.crown = new THREE.CylinderGeometry(0.22, 0.18, 0.16, 8, 1, true);
  return G;
}

// One material for every contestant: glossy toy plastic with a soft rim light.
let CHAR_MAT = null;
function charMaterial() {
  if (CHAR_MAT) return CHAR_MAT;
  CHAR_MAT = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.3, envMapIntensity: 0.6 });
  CHAR_MAT.onBeforeCompile = (sh) => {
    sh.fragmentShader = sh.fragmentShader.replace(
      '#include <emissivemap_fragment>',
      `#include <emissivemap_fragment>
       float rim = pow(1.0 - clamp(dot(normalize(vViewPosition), normal), 0.0, 1.0), 2.6);
       totalEmissiveRadiance += diffuseColor.rgb * rim * 0.22 + vec3(rim * 0.04);`,
    );
  };
  return CHAR_MAT;
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
  g.beginPath();
  g.roundRect(0, 0, 64, 64, 10);
  g.fill();
  g.strokeStyle = '#' + new THREE.Color(color).getHexString();
  g.lineWidth = 6;
  g.beginPath();
  g.roundRect(3, 3, 58, 58, 8);
  g.stroke();
  g.fillStyle = '#111';
  g.font = 'bold 36px Arial, sans-serif';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.fillText(String(num), 32, 35);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

function nameTexture(name) {
  const c = document.createElement('canvas');
  c.width = 256;
  c.height = 64;
  const g = c.getContext('2d');
  g.font = '900 34px Nunito, Arial, sans-serif';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.lineWidth = 8;
  g.lineJoin = 'round';
  g.strokeStyle = 'rgba(20,14,30,0.9)';
  g.strokeText(name, 128, 34, 240);
  g.fillStyle = '#fff';
  g.fillText(name, 128, 34, 240);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

/** Geometry list for a hat, in head space (head centre at 0, helmet top about y = 0.38). */
function hatParts(hat, g, suit) {
  const gold = 0xffc93c;
  switch (hat) {
    case 'horns':
      return [painted(g.horn, 0xfff3d6, -0.3, 0.34, 0, 0, 0, 0.7), painted(g.horn, 0xfff3d6, 0.3, 0.34, 0, 0, 0, -0.7)];
    case 'fin':
      return [painted(g.fin, 0x6b7c93, 0, 0.5, -0.05, 0, Math.PI / 2, 0, 0.5, 1, 1)];
    case 'antenna':
      return [painted(g.stick, 0x333333, 0, 0.5, 0), painted(g.ball, 0xff3b3b, 0, 0.68, 0)];
    case 'mohawk':
      return [-0.2, -0.07, 0.06, 0.19].map((z, i) => painted(g.cone, suit, 0, 0.44 - Math.abs(i - 1.5) * 0.035, z, 0, Math.PI / 4, 0, 0.7, 1, 1));
    case 'ears':
      return [painted(g.ear, 0x8d5a3b, -0.26, 0.36, 0), painted(g.ear, 0x8d5a3b, 0.26, 0.36, 0), painted(g.ball, 0xf2c6a0, -0.26, 0.36, 0.07), painted(g.ball, 0xf2c6a0, 0.26, 0.36, 0.07)];
    case 'crown': {
      const parts = [painted(g.crown, gold, 0, 0.44, 0)];
      for (let i = 0; i < 6; i++) {
        const a = (i / 6) * Math.PI * 2;
        parts.push(painted(g.cone, gold, Math.cos(a) * 0.2, 0.58, Math.sin(a) * 0.2, 0, 0, 0, 0.5, 0.6, 0.5));
      }
      return parts;
    }
    case 'propeller':
      return [painted(g.stick, 0x333333, 0, 0.46, 0, 0, 0, 0, 1, 0.6, 1), painted(g.ball, 0xff3b3b, 0, 0.56, 0, 0, 0, 0, 0.6, 0.6, 0.6)];
    default:
      return [];
  }
}

export class Contestant {
  constructor(scene, { id = 0, name, suit, helmet, number, hat = 'none', isPlayer = false, human = false, remote = false, pid = null, skill = 0.5, speedMul = null }) {
    this.id = id;
    this.name = name;
    this.suit = suit;
    this.helmetColor = helmet;
    this.number = number;
    this.hat = hat;
    this.isPlayer = isPlayer; // the human on THIS device
    this.human = human || isPlayer; // any human (online)
    this.remote = remote; // simulated on another device: we only show it
    this.pid = pid;
    this.skill = skill; // 0..1 for bots: speed, reaction and smarts
    this.body = { pos: new THREE.Vector3(), vel: new THREE.Vector3(), r: 0.36, h: 1.6, grounded: false, ground: null, owner: this };
    this.body.onLand = (c) => this._onLand(c);
    this.control = { x: 0, z: 0, jump: false, dive: false, jumpHeld: true };
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
    this.speedMul = speedMul ?? (this.human ? 1 : 0.86 + skill * 0.13);
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
    const vmat = charMaterial();
    const part = (parent, geos, x, y, z, shadow = false) => {
      const m = new THREE.Mesh(mergeGeometries(geos, false), vmat);
      m.position.set(x, y, z);
      m.castShadow = shadow;
      parent.add(m);
      return m;
    };
    // chunky torso with a lighter belly and a belt
    const light = new THREE.Color(this.suit).lerp(new THREE.Color(0xffffff), 0.45).getHex();
    part(pose, [
      painted(g.torso, this.suit, 0, 0.86, 0),
      painted(g.belly, light, 0, 0.84, 0.17, 0, 0, 0, 1, 1.05, 0.75),
      painted(g.belt, 0x222222, 0, 0.58, 0, Math.PI / 2),
    ], 0, 0, 0, true);
    const bib = new THREE.Mesh(g.bib, new THREE.MeshStandardMaterial({ map: bibTexture(this.number, this.helmetColor), roughness: 0.7, transparent: true }));
    bib.position.set(0, 0.95, 0.405);
    bib.rotation.x = -0.08;
    pose.add(bib);
    this.bib = bib;
    // big head, helmet and a friendly face, all one mesh
    const head = part(pose, [
      painted(g.head, skin),
      painted(g.helmet, this.helmetColor, 0, 0.03, 0, -0.18),
      painted(g.brim, this.helmetColor, 0, 0.04, 0.03, -0.18, 0, 0, 1, 1, 1.06),
      painted(g.stripe, this.suit, 0, 0.13, 0.01, Math.PI / 2 - 0.18),
      painted(g.sclera, 0xffffff, -0.125, -0.02, 0.255, 0, 0, 0, 1, 1.25, 0.8),
      painted(g.sclera, 0xffffff, 0.125, -0.02, 0.255, 0, 0, 0, 1, 1.25, 0.8),
      painted(g.pupil, dark, -0.12, -0.025, 0.318),
      painted(g.pupil, dark, 0.12, -0.025, 0.318),
      painted(g.glint, 0xffffff, -0.105, 0.0, 0.358),
      painted(g.glint, 0xffffff, 0.135, 0.0, 0.358),
      painted(g.cheek, 0xff8a8a, -0.215, -0.12, 0.22, 0, 0, 0, 1, 0.6, 0.5),
      painted(g.cheek, 0xff8a8a, 0.215, -0.12, 0.22, 0, 0, 0, 1, 0.6, 0.5),
      painted(g.mouth, 0x5a1a1a, 0, -0.13, 0.285, -0.25, 0, 0, 1, 1, 0.5),
      ...hatParts(this.hat, g, this.suit),
    ], 0, 1.62, 0, true);
    this.head = head;
    if (this.hat === 'propeller') {
      const prop = new THREE.Mesh(g.blade, vmat.clone());
      prop.material.vertexColors = false;
      prop.material.color.set(0xffd23f);
      prop.position.y = 0.56;
      head.add(prop);
      this.prop = prop;
    }
    const limb = (geos, x, y) => {
      const piv = new THREE.Group();
      piv.position.set(x, y, 0);
      part(piv, geos, 0, 0, 0);
      pose.add(piv);
      return piv;
    };
    const arm = () => [painted(g.arm, this.suit, 0, -0.2, 0), painted(g.hand, 0xffffff, 0, -0.44, 0)];
    const leg = () => [
      painted(g.leg, this.suit, 0, -0.18, 0),
      painted(g.shoe, this.helmetColor, 0, -0.36, 0.07),
      painted(g.sole, 0xffffff, 0, -0.46, 0.07),
    ];
    this.armL = limb(arm(), -0.48, 1.16);
    this.armR = limb(arm(), 0.48, 1.16);
    this.legL = limb(leg(), -0.18, 0.48);
    this.legR = limb(leg(), 0.18, 0.48);
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
      mk.position.y = 2.6;
      root.add(mk);
      this.marker = mk;
    } else if (this.human) {
      // other real players get a name tag
      const tag = new THREE.Sprite(new THREE.SpriteMaterial({ map: nameTexture(this.name), depthWrite: false, toneMapped: false }));
      tag.scale.set(2.4, 0.6, 1);
      tag.position.y = 2.75;
      root.add(tag);
      this.tag = tag;
    }
    this.root = root;
    this.pose = pose;
    scene.add(root);
  }

  // ---------- online: send our state / show someone else's ----------
  netPack() {
    const b = this.body;
    const r = (v) => Math.round(v * 100) / 100;
    const flags = (b.grounded ? 1 : 0) | (this.diving || this.slide > 0 ? 2 : 0) | (this.stun > 0 ? 4 : 0) | (this.frozen ? 8 : 0)
      | (this.celebrate ? 16 : 0) | (this.root.visible ? 0 : 32) | (this.pose.visible ? 0 : 64);
    return [this.id, r(b.pos.x), r(b.pos.y), r(b.pos.z), r(this.facing), flags, r(b.vel.x), r(b.vel.z)];
  }

  netApply(a) {
    this.netT = { x: a[1], y: a[2], z: a[3], f: a[4], flags: a[5], vx: a[6], vz: a[7] };
  }

  /** Puppet update for characters simulated on another device. */
  netUpdate(dt, world) {
    const n = this.netT;
    const b = this.body;
    if (n) {
      const dx = n.x - b.pos.x;
      const dy = n.y - b.pos.y;
      const dz = n.z - b.pos.z;
      if (dx * dx + dy * dy + dz * dz > 36) b.pos.set(n.x, n.y, n.z);
      else {
        const k = 1 - Math.exp(-14 * dt);
        b.pos.x += dx * k;
        b.pos.y += dy * k;
        b.pos.z += dz * k;
      }
      // keep moving between updates so it looks smooth
      n.x += n.vx * dt;
      n.z += n.vz * dt;
      this.facing = turnTo(this.facing, n.f, 12 * dt);
      const f = n.flags;
      b.grounded = !!(f & 1);
      this.diving = !!(f & 2);
      const stunned = !!(f & 4);
      if (stunned && this.stun <= 0) this.spin = 9;
      this.stun = stunned ? 0.2 : 0;
      this.frozen = !!(f & 8);
      this.celebrate = !!(f & 16);
      this.root.visible = !(f & 32);
      this.pose.visible = !(f & 64);
      b.vel.x = n.vx;
      b.vel.z = n.vz;
    }
    this.groundY = b.grounded ? b.pos.y : world.groundAt(b.pos.x, b.pos.z, b.pos.y + 0.05);
    this.animate(dt);
  }

  dispose(scene) {
    scene.remove(this.root);
    this.root.traverse((o) => {
      if (o.geometry && !Object.values(G).includes(o.geometry)) o.geometry.dispose();
      if (o.isSprite || o === this.bib) o.material.map?.dispose();
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
    this.pose.visible = true;
    this.safeT = 0;
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
      this.jumpCut = false;
      if (this.isPlayer) sfx('jump');
      particles.burst(new THREE.Vector3(b.pos.x, b.pos.y + 0.05, b.pos.z), [0xffffff, 0xe6d3ad], 4, 1.6, 0.1, { up: 0.3, gravity: 3, life: 0.4 });
    }
    // let go of jump early for a shorter hop
    if (!c.jumpHeld && !this.jumpCut && v.y > 3.5 && !this.diving && this.stun <= 0) {
      v.y *= 0.7;
      this.jumpCut = true;
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
    if (this.prop) this.prop.rotation.y += dt * (8 + Math.hypot(b.vel.x, b.vel.z) * 3);
    if (this.marker) {
      this.marker.position.y = 2.6 + Math.sin(performance.now() / 250) * 0.08;
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
const BOT_SUITS = [0x2a6fdb, 0x2bb673, 0xff8c1a, 0x9b5de5, 0x00b4d8, 0xf15bb5, 0x8ac926, 0xffbe0b, 0x3a3a8c, 0xd62828, 0x06d6a0, 0xe63946];
const BOT_HELMETS = [0xffffff, 0xff5d8f, 0x4cc9f0, 0xff9f1c, 0xb8f15b, 0xffd23f];
const NAMES = [
  'Big Kenji', 'Mama Yuki', 'Turbo Taro', 'Hiro the Hero', 'Noodle', 'Sushi Sam', 'Aiko', 'Daisuke', 'Banzai Bob',
  'Rin', 'Captain Kaz', 'Speedy Sora', 'Tofu Tom', 'Mika', 'Grandpa Goro', 'Yoshi K', 'Rocket Ren', 'Haruka',
  'Wobbly Wes', 'Kumiko', 'Shin', 'Lucky Lee', 'Momo', 'Ichiro', 'Nana', 'Kenta the Tank',
];
/** How fast and sharp the bots are at each difficulty: [slowest, fastest] speed, and skill range. */
export const DIFFICULTY = {
  easy: { speed: [0.7, 0.84], skill: [0.05, 0.6] },
  normal: { speed: [0.78, 0.93], skill: [0.15, 0.85] },
  hard: { speed: [0.88, 1.0], skill: [0.4, 1.0] },
};

/**
 * Build the line-up. `humans` is a list of {name, look: {suit, helmet, hat}, pid}; the rest are bots.
 * The result is plain data, so the host can send it to everyone in an online game.
 */
export function makeLineup(count, humans, difficulty = 'normal') {
  const d = DIFFICULTY[difficulty] || DIFFICULTY.normal;
  const list = [];
  const names = NAMES.slice().sort(() => Math.random() - 0.5);
  const nums = [];
  while (nums.length < count) {
    const n = 1 + Math.floor(Math.random() * 99);
    if (!nums.includes(n)) nums.push(n);
  }
  const botHats = HATS.filter((h) => h !== 'crown');
  for (let i = 0; i < count; i++) {
    const h = humans[i];
    if (h) {
      list.push({ id: i, name: h.name, suit: h.look.suit, helmet: h.look.helmet, hat: h.look.hat, number: nums[i], human: true, pid: h.pid ?? null, skill: 1, speedMul: 1 });
    } else {
      const k = Math.random();
      list.push({
        id: i,
        name: names[i % names.length],
        suit: BOT_SUITS[(i * 5) % BOT_SUITS.length],
        helmet: BOT_HELMETS[(i * 7) % BOT_HELMETS.length],
        hat: Math.random() < 0.35 ? botHats[1 + Math.floor(Math.random() * (botHats.length - 1))] : 'none',
        number: nums[i],
        human: false,
        pid: null,
        skill: d.skill[0] + k * (d.skill[1] - d.skill[0]),
        speedMul: d.speed[0] + k * (d.speed[1] - d.speed[0]),
      });
    }
  }
  return list;
}
