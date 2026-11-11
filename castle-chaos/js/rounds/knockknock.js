import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles, textTexture } from '../fx.js';
import { sfx } from '../audio.js';
import { COLORS } from '../scenery.js';
import { tex, repeat } from '../textures.js';

// KNOCK KNOCK: burst through paper doors. Some are solid wood (BOING!) and some hide a mud pit.
const DOOR_XS = [-5.5, -3.3, -1.1, 1.1, 3.3, 5.5];
const DOOR_W = 1.6;
const DOOR_H = 2.5;
const WALL_SPACING = 7.5;
const FIRST_WALL = -9;
const PIT_LEN = 5;
const LAYOUTS = [
  ['paper', 'paper', 'paper', 'solid', 'solid', 'solid'],
  ['paper', 'paper', 'mud', 'solid', 'solid', 'solid'],
  ['paper', 'paper', 'mud', 'mud', 'solid', 'solid'],
  ['paper', 'mud', 'solid', 'solid', 'solid', 'solid'],
  ['paper', 'paper', 'mud', 'mud', 'solid', 'solid'],
  ['paper', 'mud', 'mud', 'solid', 'solid', 'solid'],
  ['paper', 'mud', 'mud', 'mud', 'solid', 'solid'],
  ['paper', 'mud', 'mud', 'solid', 'solid', 'solid'],
];

export class KnockKnock extends Round {
  static info = {
    id: 'knock',
    name: 'Knock Knock',
    tagline: 'Burst through the paper doors. Some are solid wood, and some hide a mud pit!',
    tip: 'Follow the holes other contestants have made, or gamble on a fresh door.',
  };

  build() {
    this.width = 14;
    this.startZ = 0;
    this.walls = [];
    this.pits = [];
    this.sinking = [];
    this.finishZ = FIRST_WALL - (LAYOUTS.length - 1) * WALL_SPACING - 7;
    this.timeLimit = 120;
    this.camOffset = V(0, 5.4, 8.6);
    this.camLook = V(0, 1.3, -5);
    this.decorate();
    const w = this.width / 2;
    this.floor(-w, w, 8, this.finishZ - 12, 0xd9c49a, 0, 1, { tex: 'dirt', texScale: 3 });
    // painted lane stripes
    for (let z = 6; z > this.finishZ - 10; z -= 3) this.solid(0, 0.005, z, 0.15, 0.01, 1.4, 0xffffff, { noCollide: true, cast: false });
    for (const sx of [-1, 1]) {
      this.solid(sx * (w + 0.25), 0.7, (8 + this.finishZ - 12) / 2, 0.5, 1.4, 20 - this.finishZ, 0xd7263d, { rough: 0.5 });
      this.solid(sx * (w + 0.25), 1.43, (8 + this.finishZ - 12) / 2, 0.62, 0.08, 20 - this.finishZ, 0xffc93c, { noCollide: true, rough: 0.3, metal: 0.7 });
    }
    const paperTex = makePaperTexture();
    this.paperMat = new THREE.MeshStandardMaterial({ map: paperTex, roughness: 0.9 });
    const crossTex = textTexture('✕', { w: 128, h: 128, fg: '#e0102f', stroke: '#fff', size: 110, font: 'Arial, sans-serif' });
    this.crossMat = new THREE.MeshBasicMaterial({ map: crossTex, transparent: true });

    LAYOUTS.forEach((layout, wi) => {
      const z = FIRST_WALL - wi * WALL_SPACING;
      const types = layout.slice().sort(() => Math.random() - 0.5);
      this.buildWall(z, types, wi);
    });
  }

  buildWall(z, types, wi) {
    const w = this.width / 2;
    const white = 0xf6f2e6;
    // every wall has its own materials so it can fade out when it's between you and the camera
    const fade = (m) => {
      m.transparent = true;
      return m;
    };
    const mats = {
      plaster: fade(new THREE.MeshStandardMaterial({ map: tex('plaster'), roughness: 0.85 })),
      red: fade(new THREE.MeshStandardMaterial({ color: 0xd7263d, roughness: 0.5 })),
      roof: fade(new THREE.MeshStandardMaterial({ map: repeat('roof', 6, 1), roughness: 0.55 })),
      paper: fade(this.paperMat.clone()),
      wood: fade(new THREE.MeshStandardMaterial({ map: tex('wood'), roughness: 0.7 })),
    };
    // posts between doors
    const edges = [-w];
    for (const x of DOOR_XS) edges.push(x - DOOR_W / 2, x + DOOR_W / 2);
    edges.push(w);
    for (let i = 0; i < edges.length; i += 2) {
      const a = edges[i];
      const b = edges[i + 1];
      this.solid((a + b) / 2, 1.7, z, b - a, 3.4, 0.5, white, { material: mats.plaster });
      this.solid((a + b) / 2, 1.7, z + 0.26, b - a + 0.02, 3.4, 0.04, 0xd7263d, { noCollide: true, cast: false, material: mats.red });
    }
    this.solid(0, DOOR_H + 0.45, z, this.width, 0.9, 0.5, white, { material: mats.plaster });
    this.solid(0, 3.5, z, this.width + 0.4, 0.25, 0.8, 0x2d3a4a, { material: mats.roof });
    this.solid(0, 3.68, z, this.width + 0.6, 0.12, 1.0, 0x2d3a4a, { material: mats.roof, noCollide: true });
    const wall = { z, doors: [], index: wi, mats, opacity: 1 };
    // corridors behind the doors, so you can't tell from outside which one hides the mud
    const fx = [-w + 0.1];
    for (let i = 0; i < DOOR_XS.length - 1; i++) fx.push((DOOR_XS[i] + DOOR_XS[i + 1]) / 2);
    fx.push(w - 0.1);
    for (const x of fx) this.solid(x, 0.75, z - 0.25 - PIT_LEN / 2, 0.16, 1.5, PIT_LEN, white, { material: mats.plaster });
    DOOR_XS.forEach((x, di) => {
      const type = types[di];
      const g = new THREE.Group();
      g.position.set(x, 0, z);
      g.userData.dynamic = true;
      this.group.add(g);
      const panel = new THREE.Mesh(new THREE.BoxGeometry(DOOR_W, DOOR_H, 0.12), mats.paper);
      panel.position.y = DOOR_H / 2;
      panel.castShadow = true;
      g.add(panel);
      for (const sx of [-1, 1]) {
        const frame = new THREE.Mesh(new THREE.BoxGeometry(0.1, DOOR_H, 0.16), mats.wood);
        frame.position.set(sx * DOOR_W / 2, DOOR_H / 2, 0);
        g.add(frame);
      }
      const door = { x, type, state: 'closed', group: g, panel, wobble: 0, wi, di };
      const col = this.world.addBox(x, DOOR_H / 2, z, DOOR_W, DOOR_H, 0.14, {
        onHit: (body, nx, nz, into) => this.hitDoor(door, body, nz, into),
      });
      door.col = col;
      if (type === 'mud') {
        const pit = { x0: x - DOOR_W / 2 - 0.1, x1: x + DOOR_W / 2 + 0.1, z0: z - 0.25 - PIT_LEN, z1: z - 0.25, wall };
        this.pits.push(pit);
        // the mud stays hidden until someone bursts the door
        const mudG = new THREE.Group();
        mudG.userData.dynamic = true;
        mudG.visible = false;
        this.group.add(mudG);
        const mud = new THREE.Mesh(new THREE.BoxGeometry(pit.x1 - pit.x0, 0.06, PIT_LEN), mat(COLORS.mud, { rough: 0.4 }));
        mud.position.set(x, 0.02, (pit.z0 + pit.z1) / 2);
        mudG.add(mud);
        for (let i = 0; i < 5; i++) {
          const bub = new THREE.Mesh(new THREE.SphereGeometry(0.12 + Math.random() * 0.1, 8, 6), mat(0x6b4526, { rough: 0.3 }));
          bub.position.set(x + (Math.random() - 0.5) * 1.3, 0.05, pit.z0 + Math.random() * PIT_LEN);
          bub.scale.y = 0.5;
          mudG.add(bub);
        }
        door.mud = mudG;
      }
      wall.doors.push(door);
    });
    this.walls.push(wall);
  }

  hitDoor(door, body, nz, into) {
    const c = body.owner;
    if (door.state !== 'closed' && door.state !== 'revealed') return false;
    if (door.type === 'solid') {
      if (nz > 0.5 && into > 2.5 && c && c.stun <= 0) {
        c.knock(body.vel.x * -0.2, 5.5, 8.5, 0.75);
        door.wobble = 1;
        if (door.state !== 'revealed') this.emit('rev', [door.wi, door.di]);
        particles.burst(body.pos.clone().setY(1.4), 0xffffff, 6, 2, 0.08);
        if (this.game.near(c)) sfx('boing');
        if (c.isPlayer) {
          sfx('thud');
          this.game.commentary('solid');
          this.game.shake(0.35);
        }
      }
      return false;
    }
    // paper (or paper hiding mud): burst through!
    this.emit('door', [door.wi, door.di]);
    if (c?.isPlayer) this.game.shake(0.15);
    return true;
  }

  onNet(kind, d) {
    const door = this.walls[d[0]]?.doors[d[1]];
    if (!door) return;
    if (kind === 'rev' && door.state === 'closed') {
      door.state = 'revealed';
      door.wobble = 1;
      const x = new THREE.Mesh(new THREE.PlaneGeometry(1.3, 1.3), this.crossMat);
      x.position.set(0, DOOR_H * 0.55, 0.075);
      door.group.add(x);
    } else if (kind === 'door' && door.state === 'closed') {
      door.state = door.type === 'mud' ? 'mudOpen' : 'open';
      if (door.mud) door.mud.visible = true;
      door.col.enabled = false;
      door.group.remove(door.panel);
      particles.burst(new THREE.Vector3(door.x, 1.3, door.col.z), [0xfbf6e4, 0xf1e6c8, 0xd7263d], 30, 4, 0.18, { up: 0.6, flat: true, life: 1.4, gravity: 8 });
      if (this.game.nearPos(door.x, door.col.z)) sfx('rip');
      this.cheer(0.7);
    }
  }

  check(c) {
    if (c.respawning) {
      return;
    }
    const p = c.body.pos;
    // walls passed -> checkpoint
    for (const wall of this.walls) {
      if (p.z < wall.z - PIT_LEN - 0.4 && c.checkpoint.z > wall.z - PIT_LEN) {
        this.setCheckpoint(c, 0, 0, wall.z - PIT_LEN - 1.1);
      }
    }
    if (p.y < 0.35) {
      for (const pit of this.pits) {
        if (p.x > pit.x0 && p.x < pit.x1 && p.z > pit.z0 && p.z < pit.z1) {
          this.setCheckpoint(c, 0, 0, pit.wall.z + 2.2);
          this.sinking.push(c);
          this.sendBack(c, 'mud', 1.6);
          return;
        }
      }
    }
    super.check(c);
  }

  respawnFor(c) {
    const p = super.respawnFor(c);
    p.x = Math.max(-6, Math.min(6, p.x));
    return p;
  }

  update(dt) {
    super.update(dt);
    for (let i = this.sinking.length - 1; i >= 0; i--) {
      const c = this.sinking[i];
      if (!c.respawning) {
        this.sinking.splice(i, 1);
        continue;
      }
      c.body.pos.y = Math.max(-1.3, c.body.pos.y - dt * 1.1);
    }
    // fade the wall you've just come through so it doesn't hide you
    const f = this.game.focus?.body?.pos;
    for (const wall of this.walls) {
      const behind = f && f.z < wall.z + 0.4 && f.z > wall.z - PIT_LEN - 1.5;
      const want = behind ? 0.22 : 1;
      if (Math.abs(wall.opacity - want) > 0.001) {
        wall.opacity += (want - wall.opacity) * Math.min(1, dt * 8);
        for (const m of Object.values(wall.mats)) {
          m.opacity = wall.opacity;
          m.depthWrite = wall.opacity > 0.95;
        }
      }
    }
    for (const wall of this.walls) {
      for (const d of wall.doors) {
        if (d.wobble > 0) {
          d.wobble = Math.max(0, d.wobble - dt * 3);
          d.group.rotation.x = Math.sin(d.wobble * 30) * d.wobble * 0.06;
        }
      }
    }
  }

  // ---------- bots ----------
  doorScore(c, d) {
    if (d.state === 'revealed' || d.state === 'mudOpen') return -1;
    const dist = Math.abs(d.x - c.body.pos.x);
    if (d.state === 'open') return (Math.random() < 0.35 + c.skill * 0.5 ? 3 + c.skill * 3 : 1) - dist * 0.35;
    return 1 + Math.random() * 2.2 - dist * 0.3;
  }

  chooseDoor(c, wall) {
    let best = null;
    let bestS = -1e9;
    for (const d of wall.doors) {
      const s = this.doorScore(c, d);
      if (s > bestS) {
        bestS = s;
        best = d;
      }
    }
    c.ai.door = best;
    c.ai.wall = wall;
    c.ai.off = (Math.random() - 0.5) * 0.5;
  }

  think(c, dt) {
    if (this.unstick(c, dt)) return;
    const p = c.body.pos;
    const wall = this.walls.find((w) => p.z > w.z - 0.2);
    if (!wall) {
      this.steer(c, c.ai.lane ?? (c.ai.lane = (Math.random() - 0.5) * 8), this.finishZ - 6);
      if (p.z < this.finishZ + 3 && Math.random() < 0.02 * c.skill) c.control.dive = true;
      return;
    }
    // inside a door lane between the wall and the next one? keep going straight
    if (c.ai.wall !== wall || !c.ai.door || c.ai.door.state === 'revealed' || c.ai.door.state === 'mudOpen') {
      this.chooseDoor(c, wall);
    }
    // re-think now and then when a door nearby has just been opened
    if (Math.random() < 0.01 && c.ai.door.state === 'closed') {
      const open = wall.doors.find((d) => d.state === 'open' && Math.abs(d.x - p.x) < 4);
      if (open && Math.random() < c.skill) c.ai.door = open;
    }
    const tx = c.ai.door.x + c.ai.off;
    const dx = tx - p.x;
    const dzWall = p.z - wall.z;
    if (Math.abs(dx) > 0.35 && dzWall < 2.2 && dzWall > 0) {
      // line up in front of the door first
      this.steer(c, tx, wall.z + 1.4);
    } else {
      this.steer(c, tx, wall.z - 3);
    }
  }
}

function makePaperTexture() {
  const c = document.createElement('canvas');
  c.width = 128;
  c.height = 200;
  const g = c.getContext('2d');
  g.fillStyle = '#fbf6e4';
  g.fillRect(0, 0, 128, 200);
  // shoji grid
  g.strokeStyle = '#b48a5a';
  g.lineWidth = 3;
  for (let x = 32; x < 128; x += 32) {
    g.beginPath();
    g.moveTo(x, 0);
    g.lineTo(x, 200);
    g.stroke();
  }
  for (let y = 40; y < 200; y += 40) {
    g.beginPath();
    g.moveTo(0, y);
    g.lineTo(128, y);
    g.stroke();
  }
  g.fillStyle = '#d7263d';
  g.beginPath();
  g.arc(64, 90, 24, 0, Math.PI * 2);
  g.fill();
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}
