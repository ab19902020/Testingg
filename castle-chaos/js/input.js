// Keyboard and touch input. The game reads `input.move` (x = right, y = forward)
// and asks for button presses with takeJump() / takeDive() / takeFire().

export const isTouch = (() => {
  try {
    return matchMedia('(pointer: coarse)').matches || 'ontouchstart' in window;
  } catch (e) {
    return false;
  }
})();

const keys = new Set();
const pressed = { jump: 0, dive: 0, fire: 0 };
const held = { jump: false, dive: false, fire: false };
const joy = { active: false, id: null, ox: 0, oy: 0, x: 0, y: 0 };

export const input = {
  move: { x: 0, y: 0 },
  enabled: true,
  update(now) {
    let x = 0;
    let y = 0;
    if (keys.has('KeyA') || keys.has('ArrowLeft')) x -= 1;
    if (keys.has('KeyD') || keys.has('ArrowRight')) x += 1;
    if (keys.has('KeyW') || keys.has('ArrowUp')) y += 1;
    if (keys.has('KeyS') || keys.has('ArrowDown')) y -= 1;
    if (joy.active) {
      x += joy.x;
      y += joy.y;
    }
    const l = Math.hypot(x, y);
    if (l > 1) {
      x /= l;
      y /= l;
    }
    this.move.x = x;
    this.move.y = y;
    this.now = now;
  },
  takeJump() { return take('jump'); },
  takeDive() { return take('dive'); },
  takeFire() { return take('fire'); },
  get fireHeld() { return held.fire || keys.has('Space') || keys.has('KeyF'); },
  clear() {
    pressed.jump = pressed.dive = pressed.fire = 0;
  },
};

// A press stays "buffered" for a short time so a jump pressed just before landing still counts.
function take(name) {
  const t = performance.now();
  if (pressed[name] && t - pressed[name] < 150) {
    pressed[name] = 0;
    return true;
  }
  return false;
}
function press(name) {
  pressed[name] = performance.now();
  held[name] = true;
}
function release(name) {
  held[name] = false;
}

window.addEventListener('keydown', (e) => {
  if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA')) return;
  if (!keys.has(e.code)) {
    if (e.code === 'Space') { press('jump'); press('fire'); }
    if (e.code === 'ShiftLeft' || e.code === 'ShiftRight' || e.code === 'KeyE' || e.code === 'KeyK') press('dive');
    if (e.code === 'KeyF' || e.code === 'KeyJ') press('fire');
  }
  keys.add(e.code);
  if (['Space', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.code)) e.preventDefault();
});
window.addEventListener('keyup', (e) => {
  keys.delete(e.code);
  if (e.code === 'Space') { release('jump'); release('fire'); }
  if (e.code === 'KeyF' || e.code === 'KeyJ') release('fire');
});
window.addEventListener('blur', () => keys.clear());

// ---------------------------------------------------------------------------
// Touch: drag anywhere on the left half for a floating joystick; buttons on the right.
// ---------------------------------------------------------------------------
export function setupTouch(zoneEl, baseEl, knobEl, buttons) {
  const RADIUS = 60;
  const showStick = (on) => {
    baseEl.hidden = !on;
  };
  showStick(false);
  zoneEl.addEventListener('pointerdown', (e) => {
    if (joy.active) return;
    joy.active = true;
    joy.id = e.pointerId;
    joy.ox = e.clientX;
    joy.oy = e.clientY;
    joy.x = joy.y = 0;
    baseEl.style.left = e.clientX + 'px';
    baseEl.style.top = e.clientY + 'px';
    knobEl.style.transform = 'translate(-50%, -50%)';
    showStick(true);
    zoneEl.setPointerCapture?.(e.pointerId);
    e.preventDefault();
  });
  const moveJoy = (e) => {
    if (!joy.active || e.pointerId !== joy.id) return;
    let dx = e.clientX - joy.ox;
    let dy = e.clientY - joy.oy;
    const l = Math.hypot(dx, dy);
    if (l > RADIUS) {
      // let the stick drift with the finger so it never feels stuck
      joy.ox += (dx / l) * (l - RADIUS);
      joy.oy += (dy / l) * (l - RADIUS);
      baseEl.style.left = joy.ox + 'px';
      baseEl.style.top = joy.oy + 'px';
      dx = (dx / l) * RADIUS;
      dy = (dy / l) * RADIUS;
    }
    joy.x = dx / RADIUS;
    joy.y = -dy / RADIUS;
    knobEl.style.transform = `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))`;
    e.preventDefault();
  };
  const endJoy = (e) => {
    if (e.pointerId !== joy.id) return;
    joy.active = false;
    joy.id = null;
    joy.x = joy.y = 0;
    showStick(false);
  };
  zoneEl.addEventListener('pointermove', moveJoy);
  zoneEl.addEventListener('pointerup', endJoy);
  zoneEl.addEventListener('pointercancel', endJoy);

  for (const [name, el] of Object.entries(buttons)) {
    if (!el) continue;
    el.addEventListener('pointerdown', (e) => {
      press(name);
      el.classList.add('down');
      e.preventDefault();
    });
    const up = () => {
      release(name);
      el.classList.remove('down');
    };
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);
    el.addEventListener('pointerleave', up);
  }
}
