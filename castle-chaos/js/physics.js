// A small, game-feel-first physics world. Characters are upright cylinders that collide with:
//   - boxes (axis aligned, can move and carry riders)
//   - vertical cylinders (stepping stones, posts)
//   - ramps (a rectangle whose height changes along z)
// That's all the obstacle course needs, and it's fast enough for 20 contestants on a phone.

export const GRAVITY = 24;
const STEP_UP = 0.4;

let nextId = 1;

export class PhysicsWorld {
  constructor() {
    this.colliders = [];
  }

  clear() {
    this.colliders.length = 0;
  }

  /** Box by centre and full size. */
  addBox(x, y, z, sx, sy, sz, opts = {}) {
    const c = {
      id: nextId++, type: 'box', x, y, z, hx: sx / 2, hy: sy / 2, hz: sz / 2,
      dx: 0, dy: 0, dz: 0, enabled: true, ...opts,
    };
    this.colliders.push(c);
    return c;
  }

  /** Vertical cylinder: (x, z) centre, y = top surface, h = height below the top. */
  addCyl(x, top, z, r, h, opts = {}) {
    const c = { id: nextId++, type: 'cyl', x, y: top - h / 2, z, r, hy: h / 2, dx: 0, dy: 0, dz: 0, enabled: true, ...opts };
    this.colliders.push(c);
    return c;
  }

  /** Ramp over x0..x1 and z0..z1 whose surface goes from height y0 at z0 to y1 at z1. */
  addRamp(x0, x1, z0, z1, y0, y1, opts = {}) {
    const c = { id: nextId++, type: 'ramp', x0, x1, z0, z1, y0, y1, dx: 0, dy: 0, dz: 0, enabled: true, ...opts };
    this.colliders.push(c);
    return c;
  }

  remove(c) {
    const i = this.colliders.indexOf(c);
    if (i >= 0) this.colliders.splice(i, 1);
  }

  /** Move a collider and remember how far it moved so riders get carried along. */
  moveTo(c, x, y, z) {
    c.dx += x - c.x;
    c.dy += y - c.y;
    c.dz += z - c.z;
    c.x = x;
    c.y = y;
    c.z = z;
  }

  endFrame() {
    for (const c of this.colliders) c.dx = c.dy = c.dz = 0;
  }

  rampHeight(c, z) {
    const t = (z - c.z0) / (c.z1 - c.z0);
    return c.y0 + (c.y1 - c.y0) * Math.min(1, Math.max(0, t));
  }

  /** Highest walkable surface under (x, z) that is below `fromY` (used for shadows and AI). */
  groundAt(x, z, fromY = 1e9) {
    let best = -1e9;
    for (const c of this.colliders) {
      if (!c.enabled || c.ghost) continue;
      let top;
      if (c.type === 'box') {
        if (Math.abs(x - c.x) > c.hx || Math.abs(z - c.z) > c.hz) continue;
        top = c.y + c.hy;
      } else if (c.type === 'cyl') {
        if ((x - c.x) ** 2 + (z - c.z) ** 2 > c.r * c.r) continue;
        top = c.y + c.hy;
      } else {
        if (x < c.x0 || x > c.x1 || z < Math.min(c.z0, c.z1) || z > Math.max(c.z0, c.z1)) continue;
        top = this.rampHeight(c, z);
      }
      if (top <= fromY + 0.05 && top > best) best = top;
    }
    return best;
  }

  /**
   * Step one body. body: {pos, vel, r, h, grounded, ground, onLand?, onHit?}
   * Gravity is applied by the caller; this moves and resolves collisions.
   */
  step(body, dt) {
    // ride moving platforms
    const g = body.ground;
    if (g && body.grounded) {
      body.pos.x += g.dx;
      body.pos.y += g.dy;
      body.pos.z += g.dz;
    }
    const wasGrounded = body.grounded;
    const speed = Math.hypot(body.vel.x, body.vel.y, body.vel.z);
    const n = Math.min(8, Math.max(1, Math.ceil((speed * dt) / 0.18)));
    const sdt = dt / n;
    body.grounded = false;
    body.ground = null;
    for (let i = 0; i < n; i++) {
      body.pos.x += body.vel.x * sdt;
      body.pos.y += body.vel.y * sdt;
      body.pos.z += body.vel.z * sdt;
      this.resolve(body);
    }
    // stick to the ground when walking down small steps and slopes
    if (!body.grounded && wasGrounded && body.vel.y <= 0.01) {
      const gy = this.groundAt(body.pos.x, body.pos.z, body.pos.y + 0.05);
      if (gy > body.pos.y - 0.45) {
        body.pos.y = gy;
        body.vel.y = 0;
        body.grounded = true;
      }
    }
    if (body.grounded && !wasGrounded && body.onLand) body.onLand(body.ground);
  }

  resolve(b) {
    const p = b.pos;
    const v = b.vel;
    const r = b.r;
    for (const c of this.colliders) {
      if (!c.enabled || c.ghost) continue;
      if (c.type === 'ramp') {
        if (p.x < c.x0 - r || p.x > c.x1 + r) continue;
        if (p.z < Math.min(c.z0, c.z1) || p.z > Math.max(c.z0, c.z1)) continue;
        if (p.x < c.x0 || p.x > c.x1) continue;
        const gy = this.rampHeight(c, p.z);
        if (p.y <= gy + 0.02 && p.y > gy - 1.2 && v.y <= 0.5) {
          p.y = gy;
          if (v.y < 0) v.y = 0;
          b.grounded = true;
          b.ground = c;
          b.groundKind = c.kind;
        }
        continue;
      }
      const top = c.y + c.hy;
      const bot = c.y - c.hy;
      if (p.y >= top + 0.001 || p.y + b.h <= bot) continue;
      let dx;
      let dz;
      let inside = false;
      if (c.type === 'box') {
        const qx = Math.max(c.x - c.hx, Math.min(p.x, c.x + c.hx));
        const qz = Math.max(c.z - c.hz, Math.min(p.z, c.z + c.hz));
        dx = p.x - qx;
        dz = p.z - qz;
        if (dx === 0 && dz === 0) inside = true;
        if (!inside && dx * dx + dz * dz >= r * r) continue;
      } else {
        dx = p.x - c.x;
        dz = p.z - c.z;
        const rr = r + c.r;
        if (dx * dx + dz * dz >= rr * rr) continue;
      }
      // land on top
      if (p.y >= top - STEP_UP && v.y <= 0.5) {
        // only count it as standing if most of the body is over the surface
        let support;
        if (c.type === 'box') {
          support = Math.abs(p.x - c.x) <= c.hx + r * 0.55 && Math.abs(p.z - c.z) <= c.hz + r * 0.55;
        } else {
          support = dx * dx + dz * dz <= (c.r + r * 0.55) ** 2;
        }
        if (support) {
          p.y = top;
          if (v.y < 0) v.y = 0;
          b.grounded = true;
          b.ground = c;
          b.groundKind = c.kind;
          continue;
        }
        // feet level with the top: just brushing the edge of a neighbouring surface, don't push back
        if (p.y >= top - 0.05) continue;
      }
      // bump head
      if (v.y > 0 && p.y + b.h - bot < 0.4 && p.y < bot) {
        p.y = bot - b.h;
        v.y = 0;
        continue;
      }
      // push out sideways
      let nx;
      let nz;
      let push;
      if (c.type === 'box') {
        if (inside) {
          const ox = c.hx - Math.abs(p.x - c.x);
          const oz = c.hz - Math.abs(p.z - c.z);
          if (ox < oz) {
            nx = Math.sign(p.x - c.x) || 1;
            nz = 0;
            push = ox + r;
          } else {
            nx = 0;
            nz = Math.sign(p.z - c.z) || 1;
            push = oz + r;
          }
        } else {
          const d = Math.sqrt(dx * dx + dz * dz);
          nx = dx / d;
          nz = dz / d;
          push = r - d;
        }
      } else {
        const d = Math.sqrt(dx * dx + dz * dz) || 0.0001;
        nx = dx / d;
        nz = dz / d;
        push = r + c.r - d;
      }
      const into = v.x * nx + v.z * nz;
      // onHit can return true to let the body pass (e.g. bursting a paper door)
      if (c.onHit && c.onHit(b, nx, nz, -into)) continue;
      p.x += nx * push;
      p.z += nz * push;
      if (into < 0) {
        const bounce = c.bounce || 0;
        v.x -= into * nx * (1 + bounce);
        v.z -= into * nz * (1 + bounce);
      }
    }
  }
}

/** Push overlapping characters apart (shoulder barging!). */
export function separateBodies(bodies) {
  for (let i = 0; i < bodies.length; i++) {
    const a = bodies[i];
    if (a.noCollide) continue;
    for (let j = i + 1; j < bodies.length; j++) {
      const b = bodies[j];
      if (b.noCollide) continue;
      if (Math.abs(a.pos.y - b.pos.y) > 1.4) continue;
      const dx = b.pos.x - a.pos.x;
      const dz = b.pos.z - a.pos.z;
      const rr = a.r + b.r;
      const d2 = dx * dx + dz * dz;
      if (d2 >= rr * rr || d2 < 1e-8) continue;
      const d = Math.sqrt(d2);
      const push = (rr - d) * 0.5;
      const nx = dx / d;
      const nz = dz / d;
      a.pos.x -= nx * push;
      a.pos.z -= nz * push;
      b.pos.x += nx * push;
      b.pos.z += nz * push;
      // trade a little momentum so diving into someone knocks them
      const rel = (b.vel.x - a.vel.x) * nx + (b.vel.z - a.vel.z) * nz;
      if (rel < 0) {
        const k = rel * 0.5;
        a.vel.x += k * nx;
        a.vel.z += k * nz;
        b.vel.x -= k * nx;
        b.vel.z -= k * nz;
      }
    }
  }
}
