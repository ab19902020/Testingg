import * as THREE from 'three';
import { Round, V } from './base.js';
import { mat, particles, painted, mergeGeometries } from '../fx.js';
import { sfx } from '../audio.js';
import { repeat } from '../textures.js';

// HONEYCOMB MAZE: find your way through a honeycomb maze to the exit.
// Dead ends everywhere, and sticky honey patches slow you right down.
const COLS = 9;
const ROWS = 12;
const CELL = 2.6;
const WALL_H = 2.0;
const WALL_T = 0.32;
const X0 = -(COLS * CELL) / 2;
const Z0 = -1; // maze starts here and runs towards -z

export class HoneycombMaze extends Round {
  static info = {
    id: 'maze',
    name: 'Honeycomb Maze',
    tagline: 'Find the way through the honeycomb maze. Dead ends everywhere, and the honey is sticky!',
    tip: 'The overhead camera lets you see ahead. Avoid the shiny honey patches.',
  };

  cellCenter(c, r) {
    return { x: X0 + (c + 0.5) * CELL, z: Z0 - (r + 0.5) * CELL };
  }

  cellAt(x, z) {
    const c = Math.floor((x - X0) / CELL);
    const r = Math.floor((Z0 - z) / CELL);
    if (c < 0 || c >= COLS || r < 0 || r >= ROWS) return null;
    return { c, r };
  }

  build() {
    this.width = COLS * CELL;
    this.startZ = 4;
    this.finishZ = Z0 - ROWS * CELL - 2.5;
    this.timeLimit = 100;
    this.camOffset = V(0, 9, 8);
    this.camLook = V(0, 0, -4);
    this.viewScale = 1.1;
    this.decorate({ castleZ: this.finishZ - 38 });
    const w = this.width / 2;
    this.floor(-w - 1, w + 1, 12, this.finishZ - 12, 0xe8c070, 0, 1, { tex: 'sand', texScale: 4, texTint: 0xe0c48a });
    // fences along both sides so the only way through is the maze itself
    for (const sx of [-1, 1]) {
      this.solid(sx * (w + 0.7), 1, (12 + this.finishZ - 12) / 2, 0.5, 2, 24 - this.finishZ, 0xd7263d, { rough: 0.5 });
      this.solid(sx * (w + 0.7), 2.04, (12 + this.finishZ - 12) / 2, 0.62, 0.08, 24 - this.finishZ, 0xffc93c, { noCollide: true, rough: 0.3, metal: 0.7 });
      // close the gap between the fence and the maze's outer wall
      this.solid(sx * (w + 0.35), WALL_H / 2, Z0 - (ROWS * CELL) / 2, 0.4, WALL_H, ROWS * CELL, 0xf2b630, { cast: false });
    }

    // ---- generate the maze: a random depth-first maze, then knock out extra walls for loops
    const open = []; // open[r][c] = {n, s, e, w}
    for (let r = 0; r < ROWS; r++) {
      open.push([]);
      for (let c = 0; c < COLS; c++) open[r].push({ n: false, s: false, e: false, w: false });
    }
    const seen = new Set();
    const stack = [[Math.floor(COLS / 2), 0]];
    seen.add(`${stack[0][0]},0`);
    const dirs = [['n', 0, 1, 's'], ['s', 0, -1, 'n'], ['e', 1, 0, 'w'], ['w', -1, 0, 'e']];
    while (stack.length) {
      const [c, r] = stack[stack.length - 1];
      const opts = dirs.filter(([, dc, dr]) => {
        const nc = c + dc;
        const nr = r + dr;
        return nc >= 0 && nc < COLS && nr >= 0 && nr < ROWS && !seen.has(`${nc},${nr}`);
      });
      if (!opts.length) {
        stack.pop();
        continue;
      }
      const [d, dc, dr, back] = opts[Math.floor(Math.random() * opts.length)];
      open[r][c][d] = true;
      open[r + dr][c + dc][back] = true;
      seen.add(`${c + dc},${r + dr}`);
      stack.push([c + dc, r + dr]);
    }
    for (let k = 0; k < 9; k++) {
      const c = Math.floor(Math.random() * (COLS - 1));
      const r = Math.floor(Math.random() * ROWS);
      open[r][c].e = open[r][c + 1].w = true;
    }
    for (let k = 0; k < 5; k++) {
      const c = Math.floor(Math.random() * COLS);
      const r = Math.floor(Math.random() * (ROWS - 1));
      open[r][c].n = open[r + 1][c].s = true;
    }
    // entrances on the near side, exits on the far side
    this.entrances = [1, 4, 7];
    for (const c of this.entrances) open[0][c].s = true;
    this.exits = [Math.floor(Math.random() * 3), 6 + Math.floor(Math.random() * 3)];
    for (const c of this.exits) open[ROWS - 1][c].n = true;
    this.open = open;

    // ---- build walls (merged honey-coloured blocks) and hexagon posts at the corners
    const wallMat = new THREE.MeshStandardMaterial({ color: 0xf2b630, roughness: 0.45, map: repeat('plaster', 3, 1) });
    const capParts = [];
    const wallParts = [];
    const hex = new THREE.CylinderGeometry(0.34, 0.34, WALL_H + 0.25, 6);
    const hexCap = new THREE.CylinderGeometry(0.4, 0.4, 0.12, 6);
    const addWall = (x, z, sx, sz) => {
      this.world.addBox(x, WALL_H / 2, z, sx, WALL_H, sz);
      wallParts.push(painted(new THREE.BoxGeometry(sx, WALL_H, sz), 0xf2b630, x, WALL_H / 2, z));
      capParts.push(painted(new THREE.BoxGeometry(sx + 0.06, 0.1, sz + 0.06), 0xb8741a, x, WALL_H + 0.05, z));
    };
    for (let r = 0; r <= ROWS; r++) {
      for (let c = 0; c < COLS; c++) {
        // horizontal wall on the near (south) edge of row r
        const closed = r === ROWS ? !open[ROWS - 1][c].n : r === 0 ? !open[0][c].s : !open[r][c].s;
        if (closed) {
          const { x } = this.cellCenter(c, 0);
          addWall(x, Z0 - r * CELL, CELL, WALL_T);
        }
      }
    }
    for (let r = 0; r < ROWS; r++) {
      for (let c = 0; c <= COLS; c++) {
        const closed = c === COLS ? true : c === 0 ? true : !open[r][c].w;
        if (closed) addWall(X0 + c * CELL, this.cellCenter(0, r).z, WALL_T, CELL);
      }
    }
    for (let r = 0; r <= ROWS; r++) {
      for (let c = 0; c <= COLS; c++) {
        const x = X0 + c * CELL;
        const z = Z0 - r * CELL;
        capParts.push(painted(hex, 0xd99420, x, (WALL_H + 0.25) / 2, z));
        capParts.push(painted(hexCap, 0xfff0b0, x, WALL_H + 0.3, z));
      }
    }
    const wm = new THREE.Mesh(mergeGeometries(wallParts, false), new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.4, map: repeat('plaster', 1, 1) }));
    wm.castShadow = wm.receiveShadow = true;
    this.group.add(wm);
    const cm = new THREE.Mesh(mergeGeometries(capParts, false), new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.35 }));
    cm.castShadow = true;
    this.group.add(cm);
    wallMat.dispose();

    // ---- sticky honey patches
    this.honey = [];
    const honeyMat = new THREE.MeshStandardMaterial({ color: 0xffa51f, roughness: 0.08, metalness: 0.1, emissive: 0x3a1a00 });
    const blob = new THREE.CircleGeometry(1, 20);
    blob.rotateX(-Math.PI / 2);
    for (let k = 0; k < 9; k++) {
      const c = Math.floor(Math.random() * COLS);
      const r = 1 + Math.floor(Math.random() * (ROWS - 2));
      if (this.honey.some((h) => h.c === c && h.r === r)) continue;
      const { x, z } = this.cellCenter(c, r);
      const m = new THREE.Mesh(blob, honeyMat);
      m.scale.set(CELL * 0.42, 1, CELL * 0.38);
      m.position.set(x, 0.02, z);
      this.group.add(m);
      this.world.addBox(x, 0.01, z, CELL * 0.75, 0.02, CELL * 0.7, { kind: 'slow' });
      this.honey.push({ c, r });
    }
    // little bees buzzing over the maze
    this.bees = [];
    const beeGeo = mergeGeometries([
      painted(new THREE.SphereGeometry(0.16, 10, 8), 0xffc93c, 0, 0, 0, 0, 0, 0, 1, 0.9, 1.3),
      painted(new THREE.TorusGeometry(0.13, 0.04, 6, 12), 0x222222, 0, 0, 0.03),
      painted(new THREE.SphereGeometry(0.1, 8, 6), 0xffffff, 0.12, 0.12, 0, 0, 0, 0, 1, 0.3, 1.4),
      painted(new THREE.SphereGeometry(0.1, 8, 6), 0xffffff, -0.12, 0.12, 0, 0, 0, 0, 1, 0.3, 1.4),
    ], false);
    const beeMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.4 });
    for (let i = 0; i < 10; i++) {
      const bee = new THREE.Mesh(beeGeo, beeMat);
      bee.userData.dynamic = true;
      this.group.add(bee);
      this.bees.push({ m: bee, cx: (Math.random() - 0.5) * this.width, cz: Z0 - Math.random() * ROWS * CELL, ph: Math.random() * 6, r: 1 + Math.random() * 2 });
    }

    // distance (in cells) to the nearest exit, for the bots
    const dist = [];
    for (let r = 0; r < ROWS; r++) dist.push(new Array(COLS).fill(1e9));
    const q = [];
    for (const c of this.exits) {
      dist[ROWS - 1][c] = 0;
      q.push([c, ROWS - 1]);
    }
    while (q.length) {
      const [c, r] = q.shift();
      for (const [nc, nr] of this.neighbours(c, r)) {
        if (dist[nr][nc] > dist[r][c] + 1) {
          dist[nr][nc] = dist[r][c] + 1;
          q.push([nc, nr]);
        }
      }
    }
    this.dist = dist;
  }

  neighbours(c, r) {
    const o = this.open[r][c];
    const out = [];
    if (o.n && r + 1 < ROWS) out.push([c, r + 1]);
    if (o.s && r > 0) out.push([c, r - 1]);
    if (o.e && c + 1 < COLS) out.push([c + 1, r]);
    if (o.w && c > 0) out.push([c - 1, r]);
    return out;
  }

  update(dt) {
    super.update(dt);
    for (const b of this.bees) {
      const a = this.t * 1.5 + b.ph;
      b.m.position.set(b.cx + Math.cos(a) * b.r, 2.6 + Math.sin(a * 3) * 0.3, b.cz + Math.sin(a) * b.r);
      b.m.rotation.y = -a;
    }
  }

  check(c) {
    if (c.respawning) return;
    // honey is sticky: a little splash of goo when you step in
    if (c.body.grounded && c.body.groundKind === 'slow' && !c.ai.inHoney) {
      c.ai.inHoney = true;
      particles.burst(c.body.pos.clone().setY(0.1), [0xffa51f, 0xffc64f], 6, 1.5, 0.12, { up: 0.5 });
      if (c.isPlayer) this.game.caption('Stuck in the honey!', '#ffd23f', 1.2);
    } else if (c.body.groundKind !== 'slow') c.ai.inHoney = false;
    super.check(c);
  }

  // ---------- bots ----------
  think(c, dt) {
    if (this.unstick(c, dt)) return;
    const p = c.body.pos;
    const ai = c.ai;
    if (p.z > Z0) {
      // pick an entrance
      if (ai.entry === undefined) {
        const sorted = this.entrances.slice().sort((a, b) => Math.abs(this.cellCenter(a, 0).x - p.x) - Math.abs(this.cellCenter(b, 0).x - p.x));
        ai.entry = Math.random() < 0.7 ? sorted[0] : sorted[1];
      }
      const { x } = this.cellCenter(ai.entry, 0);
      if (Math.abs(p.x - x) > 0.5 && p.z < Z0 + 1.6) this.steer(c, x, Z0 + 1.8);
      else this.steer(c, x, Z0 - CELL * 0.5);
      return;
    }
    const cell = this.cellAt(p.x, p.z);
    if (!cell) {
      this.steer(c, ai.lane ?? (ai.lane = (Math.random() - 0.5) * 8), this.finishZ - 6);
      return;
    }
    const key = cell.c + ',' + cell.r;
    if (ai.cellKey !== key || !ai.next) {
      // arrived in a new cell: decide where to go next
      const prev = ai.cellKey;
      ai.cellKey = key;
      if (cell.r === ROWS - 1 && this.exits.includes(cell.c)) {
        ai.next = { c: cell.c, r: ROWS };
      } else {
        const nb = this.neighbours(cell.c, cell.r);
        const forward = nb.filter(([nc, nr]) => `${nc},${nr}` !== prev);
        const choices = forward.length ? forward : nb;
        const smart = Math.random() < 0.3 + c.skill * 0.4;
        let pick;
        if (smart) {
          pick = choices.reduce((a, b) => (this.dist[b[1]][b[0]] < this.dist[a[1]][a[0]] ? b : a));
        } else pick = choices[Math.floor(Math.random() * choices.length)];
        ai.next = { c: pick[0], r: pick[1] };
      }
    }
    const t = ai.next.r >= ROWS ? { x: this.cellCenter(ai.next.c, 0).x, z: Z0 - ROWS * CELL - 2 } : this.cellCenter(ai.next.c, ai.next.r);
    // go via the middle of the doorway so we don't scrape the walls
    const here = this.cellCenter(cell.c, cell.r);
    const offAxis = ai.next.c !== cell.c ? Math.abs(p.z - here.z) : Math.abs(p.x - here.x);
    if (offAxis > 0.5) this.steer(c, ai.next.c !== cell.c ? p.x + Math.sign(t.x - p.x) * 0.3 : here.x, ai.next.c !== cell.c ? here.z : p.z + Math.sign(t.z - p.z) * 0.3);
    else this.steer(c, t.x, t.z);
  }
}
