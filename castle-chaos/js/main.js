import * as THREE from 'three';
import { PhysicsWorld, separateBodies } from './physics.js';
import { Contestant, makeLineup, dustFilter, SUIT_COLORS, HELMET_COLORS, HATS, HAT_NAMES } from './character.js';
import { wind } from './scenery.js';
import { input, isTouch, setupTouch, setupLook } from './input.js';
import { initAudio, sfx, playMusic, setMuted, isMuted } from './audio.js';
import { particles, bakeStatic, Petals } from './fx.js';
import { Sky } from './sky.js';
import { Net, MAX_PLAYERS } from './net.js';
import * as S from './scenery.js';
import { KnockKnock } from './rounds/knockknock.js';
import { SkippingStones } from './rounds/stones.js';
import { BoulderDash } from './rounds/boulders.js';
import { SumoGong } from './rounds/sumo.js';
import { ShowDown, LORD_NAME } from './rounds/showdown.js';
import { BridgeBall } from './rounds/bridge.js';
import { MushroomTrip } from './rounds/mushrooms.js';
import { HoneycombMaze } from './rounds/maze.js';
import { BigBalls } from './rounds/bigballs.js';
import { Sweeper } from './rounds/sweeper.js';
import { PunchWall } from './rounds/punch.js';

// ---------------------------------------------------------------------------
// The show: 20 contestants, five elimination events picked at random, then the Show Down.
// ---------------------------------------------------------------------------
const FIELD = 20;
const RACES = [KnockKnock, SkippingStones, BoulderDash, SumoGong, BridgeBall, MushroomTrip, HoneycombMaze, BigBalls, PunchWall, Sweeper];
const ALL = [...RACES, ShowDown];
const BY_ID = Object.fromEntries(ALL.map((R) => [R.info.id, R]));
const KEEP = [16, 12, 9, 6, 4];
const PRACTICE_KEEP = 12;
function makeShowIds() {
  if (window.CC_SHOW) return window.CC_SHOW.slice(); // test hook: force the event order
  const pool = RACES.slice().sort(() => Math.random() - 0.5).slice(0, KEEP.length);
  return [...pool.map((R) => R.info.id), ShowDown.info.id];
}

const LINES = {
  fall: ['Whoops! Off the edge!', 'Gravity wins again!'],
  water: ['SPLOOSH! Straight in the drink!', 'Swimming was not part of the plan!', 'In you go!'],
  mud: ['SPLAT! Face first in the mud!', "That's going to need a good wash!", 'Mud, glorious mud!'],
  solid: ['BOING! That door was solid!', 'Knock knock... nobody home!', 'Ouch! Solid wood!'],
  boulder: ['Flattened like a pancake!', 'STRIKE! Bowled right over!', 'Rolled over like a hedgehog!'],
  grabbed: ["The sumo's got you!", 'A big hug from the big fella!', 'Caught! Up you go!'],
  thrown: ['...and back down the field you go!', 'Air mail, special delivery!'],
  qualified: ['QUALIFIED! Magnificent!', "You're through! The crowd goes wild!", 'Safe! On to the next round!'],
  ringBurst: ['Your ring is burst! You are OUT!', 'Soaked! Your ring has popped!'],
  guardDown: ['A guard goes down! Keep going!', 'Splash! One less guard!'],
  castleWins: ['The castle stands! Nobody gets in today!'],
};
const pick = (a) => a[Math.floor(Math.random() * a.length)];

// Camera views the player can switch between with the camera button.
// height/back are metres above and behind the contestant; ahead is how far in front the camera looks.
const VIEWS = [
  { name: 'Overhead', height: 12.5, back: 9, ahead: 4.5 },
  { name: 'Chase', height: 7, back: 10, ahead: 5 },
  { name: 'Top-down', height: 21, back: 4, ahead: 2.5 },
];

function load(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v === null ? fallback : JSON.parse(v);
  } catch (e) {
    return fallback;
  }
}
function save(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (e) { /* storage blocked */ }
}

/** Small seeded random generator so every device builds exactly the same course. */
function seeded(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

class Game {
  constructor() {
    this.canvas = document.getElementById('game');
    this.renderer = new THREE.WebGLRenderer({ canvas: this.canvas, antialias: true, powerPreference: 'high-performance' });
    this.maxPR = Math.min(window.devicePixelRatio || 1, isTouch ? 1.75 : 2);
    this.pr = this.maxPR;
    this.renderer.setPixelRatio(this.pr);
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.0;
    this.scene = new THREE.Scene();
    this.scene.fog = new THREE.Fog(0xcfe6f7, 110, 380);
    this.camera = new THREE.PerspectiveCamera(55, 1, 0.1, 900);
    this.camera.position.set(0, 8, 14);
    this.sky = new Sky(this.scene, this.renderer);
    this.hemi = new THREE.HemisphereLight(0xdcefff, 0x5d8a3c, 0.75);
    this.scene.add(this.hemi);
    this.sun = new THREE.DirectionalLight(0xfff1d8, 3.1);
    this.sun.castShadow = true;
    this.sun.shadow.mapSize.set(2048, 2048);
    const sc = this.sun.shadow.camera;
    sc.left = sc.bottom = -30;
    sc.right = sc.top = 30;
    sc.near = 1;
    sc.far = 160;
    this.sun.shadow.bias = -0.0005;
    this.sun.shadow.normalBias = 0.03;
    this.sun.shadow.radius = 3;
    this.scene.add(this.sun);
    this.scene.add(this.sun.target);
    // a soft light from the other side so nothing goes flat in shadow
    this.fill = new THREE.DirectionalLight(0x9fd0ff, 0.7);
    this.scene.add(this.fill);
    this.fill.target = this.sun.target;
    this.world = new PhysicsWorld();
    this.levelGroup = new THREE.Group();
    this.scene.add(this.levelGroup);
    particles.attach(this.scene);
    this.petals = new Petals(this.scene, isTouch ? 110 : 160);

    this.contestants = [];
    this.racers = [];
    this.qualified = [];
    this.koOrder = [];
    this.round = null;
    this.net = null;
    this.state = 'menu';
    this.screen = 'menu';
    this.racing = false;
    this.raceT = 0;
    this.focus = null;
    this.shakeT = 0;
    this.camLook = new THREE.Vector3();
    this.camYaw = 0;
    this.lookIdle = 9;
    this.frameAvg = 1 / 60;
    this.perfT = 0;
    this.viewIdx = Math.min(VIEWS.length - 1, Math.max(0, load('cc_view', 0) | 0));
    this.playerName = load('cc_name2', '') || 'You';
    this.look = { suit: SUIT_COLORS[0], helmet: HELMET_COLORS[0], hat: 'none', ...load('cc_look', {}) };
    this.difficulty = load('cc_diff', 'normal');
    this.crowns = load('cc_crowns', 0) | 0;

    this.ui = {};
    for (const id of ['menu', 'practice', 'how', 'online', 'locker', 'card', 'cardRound', 'cardName', 'cardTag', 'cardTip', 'cardKeep', 'count', 'hud', 'hudRound', 'hudTimer', 'hudQual',
      'caption', 'banner', 'results', 'resTitle', 'resSub', 'resList', 'resButtons', 'spectate', 'specName', 'specNext', 'nameInput', 'touch', 'quit',
      'btnJump', 'btnDive', 'btnFire', 'practiceList', 'muteBtn', 'skipHint', 'loading', 'camBtn', 'hudPos', 'toast', 'crownCount',
      'onlineStart', 'lobby', 'lobbyCode', 'lobbyHint', 'lobbyList', 'startOnlineBtn', 'onlineStatus', 'codeInput', 'suitRow', 'helmetRow', 'hatRow']) {
      this.ui[id] = document.getElementById(id);
    }
    dustFilter.near = (c) => this.near(c, 22);
    this.bindUI();
    this.onResize();
    window.addEventListener('resize', () => this.onResize());
    this.buildMenuScene();
    this.showScreen('menu');
    this.ui.loading.hidden = true;
    this.last = performance.now();
    this.renderer.setAnimationLoop((t) => this.frame(t));
  }

  // ---------------------------------------------------------------- UI
  bindUI() {
    const u = this.ui;
    u.nameInput.value = this.playerName === 'You' ? '' : this.playerName;
    u.crownCount.textContent = this.crowns;
    const click = (el, fn) => el.addEventListener('click', () => {
      initAudio();
      sfx('click');
      fn();
    });
    click(document.getElementById('playBtn'), () => this.startShow());
    click(document.getElementById('practiceBtn'), () => this.showScreen('practice'));
    click(document.getElementById('howBtn'), () => this.showScreen('how'));
    click(document.getElementById('lockerBtn'), () => this.showScreen('locker'));
    click(document.getElementById('onlineBtn'), () => this.openOnline());
    click(document.getElementById('hostBtn'), () => this.hostGame());
    click(document.getElementById('joinBtn'), () => this.joinGame());
    click(document.getElementById('onlineBack'), () => this.leaveOnline());
    click(u.startOnlineBtn, () => this.startOnlineShow());
    u.codeInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') this.joinGame();
    });
    for (const b of document.querySelectorAll('[data-back]')) click(b, () => this.showScreen('menu'));
    for (const b of document.querySelectorAll('[data-diff]')) {
      b.classList.toggle('on', b.dataset.diff === this.difficulty);
      click(b, () => {
        this.difficulty = b.dataset.diff;
        save('cc_diff', this.difficulty);
        for (const o of document.querySelectorAll('[data-diff]')) o.classList.toggle('on', o === b);
      });
    }
    click(u.muteBtn, () => {
      setMuted(!isMuted());
      u.muteBtn.textContent = isMuted() ? 'Sound: off' : 'Sound: on';
    });
    click(u.quit, () => this.toMenu());
    click(u.specNext, () => this.nextSpectate());
    click(u.camBtn, () => this.cycleView());
    u.camBtn.textContent = 'Cam: ' + VIEWS[this.viewIdx].name;
    ALL.forEach((R) => {
      const b = document.createElement('button');
      b.className = 'btn';
      b.innerHTML = `<span>${R.info.name}</span><small>${R.info.tagline}</small>`;
      click(b, () => this.startShow(R));
      u.practiceList.appendChild(b);
    });
    this.buildLocker();
    u.card.addEventListener('pointerdown', () => this.skipIntro());
    setupTouch(document.getElementById('stickZone'), document.getElementById('stickBase'), document.getElementById('stickKnob'), {
      jump: u.btnJump, dive: u.btnDive, fire: u.btnFire,
    });
    setupLook(document.getElementById('lookZone'));
    if (!isTouch) setupLook(this.canvas);
    u.touch.hidden = true;
    window.addEventListener('keydown', (e) => {
      if (e.target && e.target.tagName === 'INPUT') return;
      if (e.code === 'Escape' && this.state !== 'menu') this.toMenu();
      if (e.code === 'KeyC' && this.state !== 'menu') this.cycleView();
      if (e.code === 'Tab' && this.ui.spectate && !this.ui.spectate.hidden) {
        e.preventDefault();
        this.nextSpectate();
      }
      if (e.code === 'Enter' || e.code === 'Space') this.skipIntro();
    });
    document.addEventListener('visibilitychange', () => {
      this.last = performance.now();
    });
  }

  buildLocker() {
    const u = this.ui;
    const hex = (c) => '#' + new THREE.Color(c).getHexString();
    const fill = (row, list, key, cls, label) => {
      row.innerHTML = '';
      for (const v of list) {
        const b = document.createElement('button');
        b.className = cls + (this.look[key] === v ? ' on' : '');
        if (cls === 'swatch') {
          b.style.background = hex(v);
          b.setAttribute('aria-label', label + ' ' + hex(v));
        } else {
          b.textContent = HAT_NAMES[v] + (v === 'crown' && !this.crowns ? ' (win a show)' : '');
          b.disabled = v === 'crown' && !this.crowns;
        }
        b.addEventListener('click', () => {
          sfx('click');
          this.look[key] = v;
          save('cc_look', this.look);
          this.buildLocker();
          this.refreshMenuPlayer();
        });
        row.appendChild(b);
      }
    };
    fill(u.suitRow, SUIT_COLORS, 'suit', 'swatch', 'Suit colour');
    fill(u.helmetRow, HELMET_COLORS, 'helmet', 'swatch', 'Helmet colour');
    fill(u.hatRow, HATS, 'hat', 'chip', 'Hat');
  }

  showScreen(name) {
    this.screen = name;
    for (const s of ['menu', 'practice', 'how', 'results', 'online', 'locker']) this.ui[s].hidden = s !== name;
  }

  toast(text, time = 3) {
    const t = this.ui.toast;
    t.textContent = text;
    t.classList.add('show');
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => t.classList.remove('show'), time * 1000);
  }

  readName() {
    const v = this.ui.nameInput.value.trim().slice(0, 14);
    this.playerName = v || 'You';
    save('cc_name2', v);
  }

  me() {
    this.readName();
    return { name: this.playerName === 'You' ? 'Player' : this.playerName, look: this.look };
  }

  setHud(on) {
    this.ui.hud.hidden = !on;
    this.ui.quit.hidden = !on;
    this.ui.camBtn.hidden = !on;
    this.ui.touch.hidden = !(on && isTouch);
    if (!on) this.ui.hudPos.hidden = true;
  }

  caption(text, color = '#fff', time = 2.2) {
    const c = this.ui.caption;
    c.textContent = text;
    c.style.color = color;
    c.classList.remove('pop');
    void c.offsetWidth;
    c.classList.add('pop');
    clearTimeout(this.capTimer);
    this.capTimer = setTimeout(() => c.classList.remove('pop'), time * 1000);
  }

  bigBanner(text, kind = 'good', time = 2) {
    const b = this.ui.banner;
    b.textContent = text;
    b.className = 'banner show ' + kind;
    clearTimeout(this.banTimer);
    this.banTimer = setTimeout(() => (b.className = 'banner'), time * 1000);
  }

  commentary(kind, who) {
    if (kind === 'lordDown') {
      const name = who ? (who.isPlayer ? 'YOU' : who.name.toUpperCase()) : 'SOMEONE';
      this.caption(`${name} BURST ${LORD_NAME.toUpperCase()}'S RING!`, '#ffd23f', 4);
      return;
    }
    const lines = LINES[kind];
    if (lines) this.caption(pick(lines), kind === 'qualified' || kind === 'guardDown' ? '#9dffb0' : '#ffd0d0');
  }

  shake(v) {
    this.shakeT = Math.max(this.shakeT, v);
    // a little buzz on phones when you get hit
    try {
      navigator.vibrate?.(Math.round(20 + v * 60));
    } catch (e) { /* not supported */ }
  }

  near(c, dist = 20) {
    if (!c) return false;
    return this.nearPos(c.body.pos.x, c.body.pos.z, dist);
  }
  nearPos(x, z, dist = 20) {
    const f = this.focusPos();
    return (f.x - x) ** 2 + (f.z - z) ** 2 < dist * dist;
  }
  focusPos() {
    if (this.focus) return this.focus.kart ? { x: this.focus.kart.x, z: this.focus.kart.z } : this.focus.body.pos;
    return this.camera.position;
  }

  onResize() {
    const w = window.innerWidth;
    const h = window.innerHeight;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    // pull the camera back a bit on tall portrait screens
    this.camera.fov = w < h ? 70 : 55;
    this.portrait = w < h;
    this.camera.updateProjectionMatrix();
  }

  setSky(sunset) {
    document.body.classList.toggle('sunset', !!sunset);
    this.sky.set(sunset ? 'sunset' : 'day');
    this.scene.fog.color.set(this.sky.mood.fog);
    this.sunDir = new THREE.Vector3(...this.sky.mood.sunDir).normalize();
    this.sun.color.set(sunset ? 0xffc08a : 0xfff1d8);
    this.sun.intensity = sunset ? 2.6 : 3.1;
    this.hemi.color.set(sunset ? 0xffcfb0 : 0xdcefff);
    this.hemi.intensity = sunset ? 0.45 : 0.5;
    this.fill.color.set(sunset ? 0x8a7bd8 : 0x9fd0ff);
    this.renderer.toneMappingExposure = sunset ? 0.95 : 1.0;
  }

  cycleView() {
    this.viewIdx = (this.viewIdx + 1) % VIEWS.length;
    save('cc_view', this.viewIdx);
    this.ui.camBtn.textContent = 'Cam: ' + VIEWS[this.viewIdx].name;
    this.caption('Camera: ' + VIEWS[this.viewIdx].name, '#fff', 1.2);
  }

  // ---------------------------------------------------------------- scenes
  clearLevel() {
    if (this.round) this.round.dispose();
    this.round = null;
    this.world.clear();
    particles.clear();
    // free one-off geometry and textures (signs, mushroom caps...) but keep the shared cached ones
    this.levelGroup.traverse((o) => {
      if (o.geometry && !o.geometry.userData.shared) o.geometry.dispose();
      const mats = o.material ? (Array.isArray(o.material) ? o.material : [o.material]) : [];
      for (const m of mats) {
        if (m.map && !m.map.userData.cached) m.map.dispose();
      }
    });
    this.levelGroup.clear();
  }

  buildMenuScene() {
    this.clearLevel();
    this.setSky(false);
    const g = this.levelGroup;
    S.ground(g);
    S.mountains(g);
    S.castle(g, 0, -30, 1.6);
    S.torii(g, 0, 0, -2, 11, null);
    S.showDressing(g, { startZ: 6, finishZ: -8, width: 12 });
    for (let i = 0; i < 26; i++) {
      const a = Math.random() * Math.PI * 2;
      const r = 18 + Math.random() * 30;
      (Math.random() < 0.6 ? S.cherryTree : S.pineTree)(g, Math.cos(a) * r, Math.sin(a) * r - 6, 1 + Math.random() * 0.6);
    }
    for (let i = 0; i < 8; i++) S.banner(g, -10 + i * 2.85, -6);
    S.grassField(g, [{ x0: -30, x1: 30, z0: -20, z1: 20 }], { count: 1600, flowers: 260, blocked: (x, z) => Math.abs(x) < 5 && z > -3 && z < 9 });
    for (const c of this.contestants) c.dispose(this.scene);
    const data = makeLineup(7, [{ name: this.playerName, look: this.look }], 'normal');
    this.contestants = data.map((d) => new Contestant(this.scene, d));
    this.contestants.forEach((c, i) => this.placeMenuContestant(c, i));
    this.world.addBox(0, -0.5, 0, 200, 1, 200);
    bakeStatic(this.levelGroup);
    this.menuT = 0;
    playMusic('menu');
  }

  placeMenuContestant(c, i) {
    const a = ((i - 1) / 5) * Math.PI - Math.PI;
    const p = i === 0 ? new THREE.Vector3(0, 0, 5.2) : new THREE.Vector3(Math.cos(a) * 4.6, 0, 3.4 + Math.sin(a) * 2.6);
    c.placeAt(p, 0);
    c.celebrate = true;
    c.flail = Math.random() * 6;
    c.body.grounded = true;
    c.groundY = 0;
    if (c.tag) c.tag.visible = false;
    if (c.marker) c.marker.visible = false;
    if (c.ring) c.ring.visible = false;
  }

  /** Rebuild the player's model on the menu after changing colours or hat. */
  refreshMenuPlayer() {
    if (this.state !== 'menu' || !this.contestants.length) return;
    const old = this.contestants[0];
    old.dispose(this.scene);
    const d = makeLineup(1, [{ name: this.playerName, look: this.look }])[0];
    d.number = old.number;
    const c = new Contestant(this.scene, d);
    this.contestants[0] = c;
    this.placeMenuContestant(c, 0);
    c.squash = 0.3;
    particles.burst(new THREE.Vector3(0, 1.2, 5.2), [this.look.suit, this.look.helmet, 0xffffff], 14, 3, 0.12);
  }

  toMenu() {
    if (this.net) {
      this.net.close();
      this.net = null;
    }
    this.state = 'menu';
    this.racing = false;
    this.setHud(false);
    this.ui.card.hidden = true;
    this.ui.count.hidden = true;
    this.ui.spectate.hidden = true;
    this.ui.banner.className = 'banner';
    this.buildMenuScene();
    this.showScreen('menu');
  }

  // ---------------------------------------------------------------- online
  openOnline() {
    const u = this.ui;
    this.showScreen('online');
    u.onlineStart.hidden = false;
    u.lobby.hidden = true;
    u.onlineStatus.textContent = Net.available() ? '' : 'Online play is not available on this device or browser.';
  }

  leaveOnline() {
    if (this.net) {
      this.net.close();
      this.net = null;
    }
    this.showScreen('menu');
  }

  hostGame() {
    if (!Net.available() || this.net) return;
    const u = this.ui;
    u.onlineStatus.textContent = 'Creating your game…';
    this.net = new Net(this);
    this.net.host(this.me()).then(() => {
      u.onlineStatus.textContent = '';
      this.onLobby();
    }).catch((err) => {
      this.net?.close();
      this.net = null;
      u.onlineStatus.textContent = err.message;
    });
  }

  joinGame() {
    if (!Net.available() || this.net) return;
    const u = this.ui;
    const code = u.codeInput.value.trim().toUpperCase();
    if (!/^[A-Z]{4}$/.test(code)) {
      u.onlineStatus.textContent = 'Type the 4-letter code from the host.';
      return;
    }
    u.onlineStatus.textContent = 'Joining…';
    this.net = new Net(this);
    this.net.join(code, this.me()).then(() => {
      u.onlineStatus.textContent = '';
      this.onLobby();
    }).catch((err) => {
      this.net?.close();
      this.net = null;
      u.onlineStatus.textContent = err.message;
    });
  }

  /** The lobby list changed (or we just got in). */
  onLobby() {
    const net = this.net;
    if (!net || this.state !== 'menu') return;
    const u = this.ui;
    if (this.screen !== 'online') this.showScreen('online');
    u.onlineStart.hidden = true;
    u.lobby.hidden = false;
    u.lobbyCode.textContent = net.code;
    u.lobbyHint.textContent = net.isHost
      ? `Tell your friends this code. They tap Online, type it in and press Join. ${net.players.length} of ${MAX_PLAYERS} players.`
      : 'You are in! Waiting for the host to start the show.';
    u.startOnlineBtn.hidden = !net.isHost;
    u.lobbyList.innerHTML = '';
    for (const p of net.players) {
      const li = document.createElement('li');
      li.className = 'good' + (p.pid === net.myPid ? ' me' : '');
      const dot = '#' + new THREE.Color(p.look.suit).getHexString();
      li.innerHTML = `<b>${p.pid === 'host' ? 'Host' : 'Player'}</b><i style="background:${dot}"></i><span></span><em></em>`;
      li.querySelector('span').textContent = p.name + (p.pid === net.myPid ? ' (you)' : '');
      u.lobbyList.appendChild(li);
    }
  }

  startOnlineShow() {
    const net = this.net;
    if (!net || !net.isHost) return;
    const lineup = makeLineup(FIELD, net.players.map((p) => ({ name: p.name, look: p.look, pid: p.pid })), this.difficulty);
    const show = makeShowIds();
    net.send({ t: 'start', lineup, show });
    this.beginShow(lineup, show, false);
    this.loadRound();
  }

  onNetMessage(msg, from) {
    const net = this.net;
    if (!net) return;
    const c = msg.id !== undefined ? this.contestants[msg.id] : null;
    switch (msg.t) {
      case 'start':
        this.beginShow(msg.lineup, msg.show, false);
        break;
      case 'load':
        this.roundIdx = msg.idx;
        this.showScreen(null);
        this.doLoad(msg.seed);
        break;
      case 'count':
        if (this.state === 'intro') this.beginCountdown();
        break;
      case 'fin':
        if (net.isHost && c && c.pid === from) this.qualify(c);
        break;
      case 'q':
        if (c) this.qualify(c);
        break;
      case 'ko':
        if (c && (!net.isHost || c.pid === from)) this.knockOut(c);
        break;
      case 'end':
        this.endRound(msg);
        break;
      default:
    }
  }

  onPlayerLeft(pid, name) {
    this.toast(`${name} left the game.`);
    if (this.state === 'menu') {
      this.onLobby();
      return;
    }
    // their contestant carries on as a bot, run by the host
    const c = this.contestants.find((x) => x.pid === pid);
    if (c) {
      c.remote = false;
      c.human = false;
      c.pid = null;
      c.body.fixed = false;
      c.skill = 0.5;
      c.speedMul = 0.85;
      c.ai = {};
      if (c.tag) c.tag.visible = false;
    }
  }

  onNetError(text) {
    const wasGuest = this.net && !this.net.isHost;
    this.toast(text, 4);
    if (wasGuest) {
      this.net.close();
      this.net = null;
      if (this.state !== 'menu') this.toMenu();
      else this.showScreen('menu');
    }
  }

  // ---------------------------------------------------------------- show flow
  startShow(practiceRound = null) {
    this.readName();
    initAudio();
    const isFinalPractice = practiceRound && practiceRound.info.final;
    const lineup = makeLineup(isFinalPractice ? 4 : FIELD, [{ name: this.playerName, look: this.look }], this.difficulty);
    this.beginShow(lineup, practiceRound ? [practiceRound.info.id] : makeShowIds(), !!practiceRound);
    this.loadRound();
  }

  /** Create the contestants from plain line-up data (the same data on every device online). */
  beginShow(lineup, showIds, practice) {
    const net = this.net;
    for (const c of this.contestants) c.dispose(this.scene);
    this.contestants = lineup.map((d) => {
      const mine = net ? d.pid === net.myPid : d.id === 0;
      const remote = net ? (net.isHost ? d.human && !mine : !mine) : false;
      const c = new Contestant(this.scene, { ...d, isPlayer: mine, remote });
      c.body.fixed = remote;
      c.eliminated = false;
      return c;
    });
    this.player = this.contestants.find((c) => c.isPlayer) || this.contestants[0];
    this.practice = practice;
    this.show = showIds.map((id, i) => ({ R: BY_ID[id], keep: practice ? PRACTICE_KEEP : KEEP[i] }));
    this.roundIdx = 0;
    this.state = 'loading';
    this.showScreen(null);
  }

  get auth() {
    return !this.net || this.net.isHost;
  }

  /** Host (or offline): pick a seed and load the round. Guests wait to be told. */
  loadRound() {
    if (!this.auth) return;
    const seed = (Math.random() * 0xffffffff) >>> 0;
    if (this.net) this.net.send({ t: 'load', idx: this.roundIdx, seed });
    this.doLoad(seed);
  }

  doLoad(seed) {
    this.clearLevel();
    const step = this.show[this.roundIdx];
    const R = step.R;
    const round = new R(this);
    this.round = round;
    this.racers = this.contestants.filter((c) => !c.eliminated);
    const n = this.racers.length;
    // build with a seeded generator so the course is identical for everyone
    const realRandom = Math.random;
    Math.random = seeded(seed);
    let starts;
    try {
      round.build();
      starts = round.startPositions(n);
      for (const c of this.contestants) {
        c.root.visible = !c.eliminated;
        c.kart = null;
        c.out = false;
        c.ko = false;
      }
      this.racers.forEach((c, i) => {
        c.placeAt(starts[i], round.survival ? Math.atan2(-starts[i].x, -starts[i].z) : Math.PI);
        c.checkpoint.set(0, starts[i].y, round.startZ + 0.5);
        c.finished = false;
        c.finPending = false;
        c.savedByTime = false;
        c.celebrate = false;
        c.respawning = false;
        c.ai = {};
        c.netT = null;
        c.body.noCollide = false;
        c.control.x = c.control.z = 0;
        c.stun = 0;
      });
      if (round.isKart) round.seat(this.racers);
      bakeStatic(this.levelGroup);
    } finally {
      Math.random = realRandom;
    }
    this.quota = R.info.final ? 1 : Math.max(1, Math.min(step.keep, n - 1));
    this.qualified = [];
    this.koOrder = [];
    this.setSky(round.sunset);
    this.focus = this.player.eliminated ? this.racers[0] : this.player;
    this.raceT = 0;
    this.racing = false;
    this.camYaw = 0;
    this.specTimer = 0;
    // intro card
    const u = this.ui;
    const total = this.show.length;
    u.cardRound.textContent = this.practice ? 'Practice' : R.info.final ? 'Grand final' : `Round ${this.roundIdx + 1} of ${total}`;
    u.cardName.textContent = R.info.name;
    u.cardTag.textContent = R.info.tagline;
    u.cardTip.textContent = R.info.tip;
    u.cardKeep.textContent = R.info.final
      ? `${n} finalist${n === 1 ? '' : 's'} vs the castle`
      : round.survival ? `Last ${this.quota} of ${n} standing go through` : `${this.quota} of ${n} qualify`;
    u.card.hidden = false;
    u.card.classList.remove('in');
    void u.card.offsetWidth;
    u.card.classList.add('in');
    u.skipHint.textContent = !this.auth ? 'The host starts the round' : isTouch ? 'Tap to skip' : 'Press Enter to skip';
    this.setHud(false);
    u.hudRound.textContent = R.info.name;
    this.updateQualHud();
    u.spectate.hidden = true;
    u.btnJump.hidden = !!round.isKart;
    u.btnDive.hidden = !!round.isKart;
    u.btnFire.hidden = !round.isKart;
    this.state = 'intro';
    this.introT = 0;
    playMusic(R.info.final ? 'final' : 'race');
    sfx('cheer', 0.6);
  }

  skipIntro() {
    if (this.state === 'intro' && this.introT > 0.8 && this.auth) this.introT = 99;
  }

  alive() {
    return this.racers.filter((c) => !c.ko);
  }

  updateQualHud() {
    const u = this.ui;
    if (this.round?.isKart) {
      const left = this.racers.filter((c) => !c.out).length;
      const hp = this.round.lord ? this.round.lord.ringHp : 0;
      u.hudQual.textContent = `${left} left · Lord's ring ${'●'.repeat(this.round.lord?.ringOk ? hp : 0)}${'○'.repeat(3 - (this.round.lord?.ringOk ? hp : 0))}`;
    } else if (this.round?.survival) {
      u.hudQual.textContent = `Still in ${this.alive().length} · ${this.quota} go through`;
    } else {
      u.hudQual.textContent = `Qualified ${this.qualified.length} / ${this.quota}`;
    }
  }

  beginCountdown() {
    if (this.state !== 'intro') return;
    if (this.net?.isHost) this.net.send({ t: 'count' });
    this.ui.card.hidden = true;
    this.state = 'countdown';
    this.countT = 3.2;
    this.lastCount = 4;
    this.setHud(true);
    this.ui.count.hidden = false;
  }

  /** A contestant on this device crossed the line. Online guests ask the host, who decides the order. */
  requestQualify(c) {
    if (c.finished || !this.racing) return;
    if (!this.auth) {
      if (!c.finPending) {
        c.finPending = true;
        this.net.send({ t: 'fin', id: c.id });
      }
      return;
    }
    this.qualify(c);
  }

  qualify(c) {
    if (c.finished || !this.racing) return;
    c.finished = true;
    this.qualified.push(c);
    if (this.net?.isHost) this.net.send({ t: 'q', id: c.id });
    c.celebrate = true;
    const w = this.round.width / 2 - 1;
    const side = this.round instanceof SumoGong ? (Math.random() < 0.5 ? -1 : 1) * (3.5 + Math.random() * 3.5) : (Math.random() - 0.5) * 2 * w;
    c.ai.done = { x: side, z: this.round.finishZ - 2.5 - Math.random() * 4 };
    this.round.cheer(1);
    if (c.isPlayer) {
      sfx('qualify');
      sfx('cheer');
      this.bigBanner('QUALIFIED!', 'good', 2.2);
      this.commentary('qualified');
      particles.confetti(c.body.pos.clone().setY(c.body.pos.y + 2), 50);
      this.fireworks();
    } else if (this.near(c, 12)) {
      particles.confetti(c.body.pos.clone().setY(c.body.pos.y + 2), 16);
    }
    this.updateQualHud();
    if (this.auth && this.qualified.length >= this.quota) this.endRound();
  }

  fireworks() {
    const fz = this.round.finishZ - 6;
    const fy = (this.round.finishY || 0) + 12;
    [0, 250, 500, 800, 1100].forEach((ms, i) => setTimeout(() => {
      const cols = [[0xff3b3b, 0xffd23f], [0x4cc9f0, 0xffffff], [0x2bb673, 0xffd23f], [0xf15bb5, 0xffffff], [0xffd23f, 0xff8c1a]][i];
      particles.burst(new THREE.Vector3((Math.random() - 0.5) * 14, fy + Math.random() * 4, fz - Math.random() * 6), cols, 40, 9, 0.18, { up: 0.5, gravity: 3, life: 1.6, spin: 4 });
      sfx('pop', 0.4);
    }, ms));
  }

  /** Survival rounds: a contestant on this device fell in. */
  requestKO(c) {
    if (c.ko || !this.racing) return;
    if (!this.auth) {
      // hide straight away; the host confirms to everyone
      c.respawning = true;
      c.frozen = true;
      c.root.visible = false;
      this.net.send({ t: 'ko', id: c.id });
      return;
    }
    this.knockOut(c);
  }

  knockOut(c) {
    if (c.ko || this.state !== 'race') return;
    c.ko = true;
    c.frozen = true;
    c.respawning = true;
    c.root.visible = false;
    this.koOrder.push(c);
    if (this.net?.isHost) this.net.send({ t: 'ko', id: c.id });
    if (c.isPlayer) {
      sfx('eliminated');
      this.bigBanner('KNOCKED OUT', 'bad', 2.2);
      this.commentary('water');
    } else if (c.human) this.caption(`${c.name} is out!`, '#ffd0d0', 1.5);
    this.round.cheer(1);
    this.updateQualHud();
    if (this.auth && this.alive().length <= this.quota) this.endRound();
  }

  /** The round is over. The host decides and tells everyone (msg is what a guest receives). */
  endRound(msg = null) {
    if (this.state === 'roundEnd' || this.state === 'results' || !this.round) return;
    const round = this.round;
    if (msg) {
      this.qualified = msg.q.map((id) => this.contestants[id]).filter(Boolean);
      for (const c of this.qualified) c.finished = true;
      for (const id of msg.saved) if (this.contestants[id]) this.contestants[id].savedByTime = true;
      if (round.isKart) {
        round.over = true;
        round.winner = msg.w === null ? null : this.contestants[msg.w];
      }
    } else {
      if (round.survival) {
        // everyone still standing goes through
        for (const c of this.alive()) {
          if (!c.finished) {
            c.finished = true;
            this.qualified.push(c);
          }
        }
      }
      if (this.net?.isHost) {
        this.net.send({
          t: 'end',
          q: this.qualified.map((c) => c.id),
          saved: this.qualified.filter((c) => c.savedByTime).map((c) => c.id),
          w: round.isKart && round.winner ? round.winner.id : null,
        });
      }
    }
    this.state = 'roundEnd';
    this.racing = false;
    this.endT = 0;
    sfx('whistle');
    const inRound = this.racers.includes(this.player);
    if (round.isKart) {
      const w = round.winner;
      if (w && w.isPlayer) this.bigBanner('CASTLE STORMED!', 'good', 3);
      else if (w) this.bigBanner(`${w.name} WINS!`, 'neutral', 3);
      else this.bigBanner('THE CASTLE STANDS', 'bad', 3);
      this.finalWinner = w;
      for (const c of this.racers) if (c !== w) c.eliminated = true;
      if (w && w.isPlayer && !this.practice) {
        this.crowns++;
        save('cc_crowns', this.crowns);
        this.ui.crownCount.textContent = this.crowns;
        this.buildLocker();
      }
    } else {
      for (const c of this.racers) {
        if (!c.finished) {
          c.eliminated = true;
          c.control.x = c.control.z = 0;
        } else c.celebrate = true;
      }
      if (inRound && this.player.eliminated) {
        if (!this.player.ko) sfx('eliminated');
        this.bigBanner('ELIMINATED', 'bad', 2.5);
      } else if (inRound && round.survival) {
        sfx('qualify');
        this.bigBanner('YOU SURVIVED!', 'good', 2.5);
        this.fireworks();
      } else this.bigBanner('ROUND OVER', 'neutral', 2);
    }
    this.ui.spectate.hidden = true;
    this.ui.hudPos.hidden = true;
  }

  showResults() {
    this.state = 'results';
    this.setHud(false);
    const u = this.ui;
    const round = this.round;
    const step = this.show[this.roundIdx];
    const playerIn = this.racers.includes(this.player);
    const hasNext = this.roundIdx + 1 < this.show.length;
    const name = round.constructor.info.name;
    u.resList.innerHTML = '';
    u.resButtons.innerHTML = '';
    const addBtn = (text, fn, primary = false) => {
      const b = document.createElement('button');
      b.className = 'btn' + (primary ? ' primary' : '');
      b.textContent = text;
      b.addEventListener('click', () => {
        sfx('click');
        fn();
      });
      u.resButtons.appendChild(b);
    };
    if (round.isKart) {
      const w = this.finalWinner;
      u.resTitle.textContent = w ? (w.isPlayer ? 'You stormed the castle!' : `${w.name} stormed the castle!`) : 'The castle stands!';
      u.resSub.textContent = w
        ? (w.isPlayer ? `You burst ${LORD_NAME}'s ring and win a crown. Wear it from the Locker!` : `${w.name} burst ${LORD_NAME}'s ring and takes the crown.`)
        : `${LORD_NAME} and his guards soaked every finalist. Better luck next time.`;
      u.results.classList.toggle('win', !!(w && w.isPlayer));
      for (const c of this.racers) this.resultRow(c, c === w ? 'Champion' : 'Soaked', c === w);
      if (w && w.isPlayer) particles.confetti(this.camera.position.clone().add(new THREE.Vector3(0, -1, -6)), 150);
    } else {
      u.results.classList.remove('win');
      const through = playerIn && this.player.finished;
      u.resTitle.textContent = !playerIn ? `${name}: results` : through ? (round.survival ? 'You survived!' : 'Qualified!') : (round.survival ? 'Knocked out' : 'Eliminated');
      u.resSub.textContent = `${this.qualified.length} of ${this.racers.length} made it through ${name}.`;
      this.qualified.forEach((c, i) => this.resultRow(c, round.survival ? 'Safe' : c.savedByTime ? 'Time' : `${i + 1}${ordinal(i + 1)}`, true));
      const rest = round.survival
        ? this.koOrder.slice().reverse()
        : this.racers.filter((c) => !c.finished).sort((a, b) => a.body.pos.z - b.body.pos.z);
      for (const c of rest) this.resultRow(c, 'Out', false);
    }
    const online = !!this.net;
    if (online && !this.net.isHost) {
      const p = document.createElement('p');
      p.className = 'status';
      p.textContent = round.isKart || !hasNext ? 'Waiting for the host to start a new show…' : 'Waiting for the host to start the next round…';
      u.resButtons.appendChild(p);
      addBtn('Leave game', () => this.toMenu());
    } else if (this.practice) {
      addBtn('Try again', () => this.startShow(step.R), true);
      addBtn('Main menu', () => this.toMenu());
    } else if (round.isKart || !hasNext) {
      addBtn('Play again', () => (online ? this.startOnlineShow() : this.startShow()), true);
      addBtn(online ? 'End game' : 'Main menu', () => this.toMenu());
    } else if (online || !this.player.eliminated) {
      addBtn(`Next round: ${this.show[this.roundIdx + 1].R.info.name}`, () => this.nextRound(), true);
      addBtn(online ? 'End game' : 'Quit', () => this.toMenu());
    } else {
      addBtn('Watch the rest of the show', () => this.nextRound(), true);
      addBtn('Play again', () => this.startShow());
      addBtn('Main menu', () => this.toMenu());
    }
    this.showScreen('results');
  }

  resultRow(c, label, good) {
    const li = document.createElement('li');
    li.className = (good ? 'good' : 'bad') + (c.isPlayer ? ' me' : '');
    const dot = '#' + new THREE.Color(c.suit).getHexString();
    li.innerHTML = `<b>${label}</b><i style="background:${dot}"></i><span></span><em>#${c.number}</em>`;
    li.querySelector('span').textContent = c.isPlayer ? `${c.name} (you)` : c.human && this.net ? `${c.name} ★` : c.name;
    this.ui.resList.appendChild(li);
  }

  nextRound() {
    this.roundIdx++;
    this.showScreen(null);
    this.loadRound();
  }

  nextSpectate() {
    const pool = this.round?.isKart ? this.racers.filter((c) => !c.out) : this.racers.filter((c) => !c.finished && !c.eliminated && !c.ko);
    const list = pool.length ? pool : this.racers.filter((c) => !c.ko);
    if (!list.length) return;
    const i = list.indexOf(this.focus);
    this.focus = list[(i + 1) % list.length];
  }

  // ---------------------------------------------------------------- loop
  frame(now) {
    const rawDt = (now - this.last) / 1000;
    const dt = Math.min(0.05, rawDt);
    this.last = now;
    this.tunePerformance(rawDt);
    input.update(now);
    if (this.state === 'menu') this.updateMenu(dt);
    else this.updateGame(dt);
    if (this.net) this.net.tick(dt);
    particles.update(dt);
    this.world.endFrame();
    this.sky.update(dt, this.camera);
    wind.time.value += dt;
    this.petals.update(dt, this.state === 'menu' ? new THREE.Vector3(0, 1, 2) : this.camLook, wind.time.value);
    this.renderer.render(this.scene, this.camera);
  }

  /** Keep the game smooth: if frames are slow, render at a lower resolution, then drop real-time shadows. */
  tunePerformance(dt) {
    if (dt > 0.25) return; // tab was in the background
    this.frameAvg += (dt - this.frameAvg) * 0.05;
    this.perfT += dt;
    if (this.perfT < 2.5) return;
    this.perfT = 0;
    if (this.frameAvg > 1 / 42) {
      if (this.pr > 1) {
        this.pr = Math.max(1, this.pr - 0.25);
        this.renderer.setPixelRatio(this.pr);
      } else if (this.sun.castShadow) {
        this.sun.castShadow = false;
      }
    } else if (this.frameAvg < 1 / 57 && this.pr < this.maxPR && this.sun.castShadow) {
      this.pr = Math.min(this.maxPR, this.pr + 0.25);
      this.renderer.setPixelRatio(this.pr);
    }
  }

  updateMenu(dt) {
    this.menuT += dt;
    for (const c of this.contestants) c.animate(dt);
    const cam = this.camera;
    if (this.screen === 'locker') {
      // close-up of your contestant, standing to the left of the locker panel
      const side = this.portrait ? 0 : 1.1;
      cam.position.lerp(new THREE.Vector3(side + Math.sin(this.menuT * 0.4) * 0.5, 1.7, 9.6), 1 - Math.exp(-4 * dt));
      this.camLook.lerp(new THREE.Vector3(side, this.portrait ? 0.4 : 1.15, 5.2), 1 - Math.exp(-4 * dt));
    } else {
      const a = this.menuT * 0.08;
      cam.position.lerp(new THREE.Vector3(Math.sin(a) * 13, 4.2 + Math.sin(this.menuT * 0.3) * 0.6, 14 + Math.cos(a) * 4), 1 - Math.exp(-3 * dt));
      this.camLook.lerp(new THREE.Vector3(0, 3.2, -6), 1 - Math.exp(-3 * dt));
    }
    cam.lookAt(this.camLook);
    this.followSun(new THREE.Vector3(0, 0, 0));
  }

  updateGame(dt) {
    const round = this.round;
    if (!round) return;
    const u = this.ui;
    const auth = this.auth;
    if (this.state === 'intro') {
      this.introT += dt;
      if (this.introT > (auth ? 5.5 : 14)) this.beginCountdown();
    } else if (this.state === 'countdown') {
      this.countT -= dt;
      const n = Math.ceil(this.countT);
      if (n !== this.lastCount) {
        this.lastCount = n;
        if (n > 0) {
          u.count.textContent = n;
          sfx('beep');
        } else {
          u.count.textContent = round.isKart ? 'FIGHT!' : 'GO!';
          sfx('go');
          this.state = 'race';
          this.racing = true;
          input.clear();
          setTimeout(() => (u.count.hidden = true), 700);
        }
        u.count.classList.remove('pop');
        void u.count.offsetWidth;
        u.count.classList.add('pop');
      }
    } else if (this.state === 'race') {
      this.raceT += dt;
      const left = Math.max(0, round.timeLimit - this.raceT);
      u.hudTimer.textContent = `${Math.floor(left / 60)}:${String(Math.floor(left % 60)).padStart(2, '0')}`;
      u.hudTimer.classList.toggle('low', left < 10);
      if (auth && left <= 0) {
        if (!round.isKart && !round.survival) {
          // time's up: the closest to the finish take any places left
          const rest = this.racers.filter((c) => !c.finished).sort((a, b) => a.body.pos.z - b.body.pos.z);
          const spare = this.quota - this.qualified.length;
          for (const c of rest.slice(0, spare)) {
            c.finished = true;
            c.savedByTime = true;
            this.qualified.push(c);
          }
          if (spare > 0) this.caption("Time's up! The closest to the finish go through.", '#ffd23f', 3);
        }
        if (round.isKart) {
          round.over = true;
          this.commentary('castleWins');
        }
        this.endRound();
      }
      if (auth && round.isKart && round.over && round.overT > 2.5) this.endRound();
      if (round.isKart) this.updateQualHud();
    } else if (this.state === 'roundEnd') {
      this.endT += dt;
      if (this.endT > 2.6) this.showResults();
    }

    round.update(dt);

    if (!round.isKart) {
      const racing = this.state === 'race';
      for (const c of this.racers) {
        if (c.ko) continue;
        if (c.remote) {
          c.netUpdate(dt, this.world);
          continue;
        }
        if (c.eliminated && this.state !== 'roundEnd') continue;
        c.control.jumpHeld = true;
        if (c.finished && !round.survival) this.afterFinish(c);
        else if (!racing) c.control.x = c.control.z = 0;
        else if (c.isPlayer) this.playerControl(c);
        else if (!c.frozen) round.think(c, dt);
        c.update(dt, this.world);
      }
      separateBodies(this.racers.filter((c) => !c.frozen && !c.ko).map((c) => c.body));
      if (racing) for (const c of this.racers) if (!c.finished && !c.remote && !c.ko) round.check(c);
    }

    // spectating once you're done (or out)
    const p = this.player;
    const playerRacing = this.racers.includes(p) && !p.finished && !p.out && !p.ko;
    if (!playerRacing && this.state === 'race') {
      if (this.focus === p && (p.finished || p.ko) && !this.specTimer) this.specTimer = p.ko ? 1.5 : 3;
      if (this.specTimer) {
        this.specTimer -= dt;
        if (this.specTimer <= 0) {
          this.specTimer = 0;
          this.nextSpectate();
        }
      }
      if (this.focus && (this.focus.finished || this.focus.out || this.focus.ko) && this.focus !== p) this.nextSpectate();
      u.spectate.hidden = false;
      u.specName.textContent = this.focus ? (this.focus.isPlayer ? 'You' : this.focus.name) : '';
    } else if (this.state === 'race') {
      u.spectate.hidden = true;
      this.focus = p;
    }
    this.updatePosition(dt, playerRacing);
    this.updateCamera(dt);
  }

  /** Race position in the corner: 3rd / 20. */
  updatePosition(dt, playerRacing) {
    const u = this.ui;
    const round = this.round;
    const show = this.state === 'race' && playerRacing && !round.isKart && !round.survival;
    u.hudPos.hidden = !show;
    if (!show) return;
    this.posT = (this.posT || 0) - dt;
    if (this.posT > 0) return;
    this.posT = 0.25;
    const z = this.player.body.pos.z;
    let rank = this.qualified.length + 1;
    for (const c of this.racers) if (c !== this.player && !c.finished && c.body.pos.z < z) rank++;
    u.hudPos.innerHTML = `${rank}<small>${ordinal(rank)} / ${this.racers.length}</small>`;
  }

  playerControl(c) {
    // the stick is relative to the camera: up is always "away from the camera"
    const sin = Math.sin(this.camYaw);
    const cos = Math.cos(this.camYaw);
    const mx = input.move.x;
    const my = input.move.y;
    c.control.x = cos * mx - sin * my;
    c.control.z = -sin * mx - cos * my;
    c.control.jumpHeld = input.jumpHeld;
    if (input.takeJump()) c.control.jump = true;
    if (input.takeDive()) c.control.dive = true;
  }

  afterFinish(c) {
    const d = c.ai.done;
    if (!d || c.frozen) {
      c.control.x = c.control.z = 0;
      return;
    }
    const dx = d.x - c.body.pos.x;
    const dz = d.z - c.body.pos.z;
    const l = Math.hypot(dx, dz);
    if (l < 0.6) {
      c.control.x = c.control.z = 0;
      c.facing = 0;
      if (Math.random() < 0.01) c.control.jump = true;
    } else {
      c.control.x = (dx / l) * 0.7;
      c.control.z = (dz / l) * 0.7;
    }
  }

  updateCamera(dt) {
    const cam = this.camera;
    const round = this.round;
    if (this.state === 'intro') {
      // fly over the course from the castle back to the start line
      const k = smooth(Math.min(1, this.introT / 5));
      const from = new THREE.Vector3(10, (round.finishY || 0) + 16, round.finishZ - 10);
      const to = new THREE.Vector3(0, (round.startY || 0) + 9, round.startZ + 14);
      cam.position.lerpVectors(from, to, k);
      const lookFrom = new THREE.Vector3(0, round.finishY || 0, round.finishZ + 6);
      const lookTo = new THREE.Vector3(0, 0, round.startZ - 8);
      this.camLook.lerpVectors(lookFrom, lookTo, k);
      cam.lookAt(this.camLook);
      cam.userData.look = this.camLook.clone();
      this.followSun(this.camLook);
      return;
    }
    // drag to turn the camera; it drifts back behind you when you leave it alone
    const turn = input.takeLook(dt);
    if (turn) {
      this.camYaw += turn;
      this.lookIdle = 0;
    } else {
      this.lookIdle += dt;
      if (this.lookIdle > 2.5) this.camYaw *= Math.exp(-1.5 * dt);
    }
    let target;
    if (round.isKart) {
      const k = round.updateCamera(cam, dt, this.focus, VIEWS[this.viewIdx].name);
      target = new THREE.Vector3(k.x, 0, k.z);
    } else if (round.camFixed) {
      const sc = this.portrait ? 1.45 : 1;
      const want = round.camFixed.pos.clone().multiplyScalar(sc).applyAxisAngle(THREE.Object3D.DEFAULT_UP, this.camYaw);
      const a = 1 - Math.exp(-4 * dt);
      cam.position.lerp(want, a);
      this.camLook.lerp(round.camFixed.look, a);
      cam.lookAt(this.camLook);
      target = round.camFixed.look.clone();
    } else {
      const f = this.focus || this.racers[0];
      target = f ? f.body.pos.clone() : new THREE.Vector3();
      if (f && f.respawning) target.copy(f.checkpoint);
      const v = VIEWS[this.viewIdx];
      const tall = this.portrait ? 1.25 : 1;
      const sc = round.viewScale || 1;
      // on short landscape phone screens, look a little less far ahead so you sit
      // nearer the middle of the screen, clear of the touch buttons
      const wide = !this.portrait && window.innerHeight < 500 ? 0.45 : 1;
      const off = new THREE.Vector3(0, v.height * tall * sc, v.back * tall * sc).applyAxisAngle(THREE.Object3D.DEFAULT_UP, this.camYaw);
      const ahead = new THREE.Vector3(0, 0.5, -v.ahead * sc * wide).applyAxisAngle(THREE.Object3D.DEFAULT_UP, this.camYaw);
      const want = target.clone().add(off);
      const look = target.clone().add(ahead);
      if (Math.abs(this.camYaw) < 0.05) {
        // keep the course centred when looking straight down it
        want.x = target.x * 0.9;
        look.x = target.x * 0.95;
      }
      const a = 1 - Math.exp(-5 * dt);
      cam.position.lerp(want, a);
      this.camLook.lerp(look, a);
      cam.lookAt(this.camLook);
    }
    if (this.shakeT > 0) {
      this.shakeT = Math.max(0, this.shakeT - dt * 1.6);
      const s = this.shakeT * 0.5;
      cam.position.x += (Math.random() - 0.5) * s;
      cam.position.y += (Math.random() - 0.5) * s;
    }
    this.followSun(target);
  }

  followSun(target) {
    const d = this.sunDir || new THREE.Vector3(0.45, 0.62, 0.64).normalize();
    this.sun.position.set(target.x + d.x * 70, target.y + Math.max(d.y, 0.45) * 70, target.z + d.z * 70);
    this.sun.target.position.copy(target);
    this.fill.position.set(target.x - d.x * 50, target.y + 30, target.z - d.z * 50);
  }
}

function smooth(t) {
  return t * t * (3 - 2 * t);
}
function ordinal(n) {
  const s = ['th', 'st', 'nd', 'rd'];
  const v = n % 100;
  return s[(v - 20) % 10] || s[v] || s[0];
}

// Android back button (called by the app): leave a screen or round, or tell the app to close.
window.handleBack = () => {
  const g = window.game;
  if (!g) return 'exit';
  if (g.state === 'menu') {
    if (g.screen === 'online') {
      g.leaveOnline();
      return 'handled';
    }
    if (g.screen !== 'menu') {
      g.showScreen('menu');
      return 'handled';
    }
    return 'exit';
  }
  g.toMenu();
  return 'handled';
};

try {
  window.game = new Game();
} catch (err) {
  console.error(err);
  const l = document.getElementById('loading');
  if (l) l.textContent = 'Sorry, this device could not start the 3D game (WebGL is needed).';
}
