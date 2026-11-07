import * as THREE from 'three';
import { PhysicsWorld, separateBodies } from './physics.js';
import { Contestant, makeLineup, dustFilter } from './character.js';
import { wind } from './scenery.js';
import { input, isTouch, setupTouch } from './input.js';
import { initAudio, sfx, playMusic, stopMusic, setMuted, isMuted } from './audio.js';
import { particles, bakeStatic, Petals } from './fx.js';
import { Sky } from './sky.js';
import * as S from './scenery.js';
import { KnockKnock } from './rounds/knockknock.js';
import { SkippingStones } from './rounds/stones.js';
import { BoulderDash } from './rounds/boulders.js';
import { SumoGong } from './rounds/sumo.js';
import { ShowDown, LORD_NAME } from './rounds/showdown.js';
import { BridgeBall } from './rounds/bridge.js';
import { MushroomTrip } from './rounds/mushrooms.js';
import { HoneycombMaze } from './rounds/maze.js';

// ---------------------------------------------------------------------------
// The show: 20 contestants, five elimination events picked at random, then the Show Down.
// ---------------------------------------------------------------------------
const FIELD = 20;
const RACES = [KnockKnock, SkippingStones, BoulderDash, SumoGong, BridgeBall, MushroomTrip, HoneycombMaze];
const KEEP = [16, 12, 9, 6, 4];
function makeShow() {
  const pool = RACES.slice().sort(() => Math.random() - 0.5).slice(0, KEEP.length);
  return [...pool.map((R, i) => ({ R, keep: KEEP[i] })), { R: ShowDown }];
}
const PRACTICE = [...RACES, ShowDown];
const PRACTICE_KEEP = { knock: 15, stones: 12, boulders: 12, sumo: 12, bridge: 12, mushrooms: 12, maze: 12 };

const LINES = {
  fall: ['Whoops! Off the edge!', 'Gravity wins again!'],
  water: ['SPLOOSH! Straight in the drink!', 'Swimming was not part of the plan!', 'That stone was a big fat fake!'],
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
  { name: 'Overhead', height: 13.5, back: 8.5, ahead: 4.5 },
  { name: 'Top-down', height: 21, back: 4, ahead: 2.5 },
  { name: 'Close', height: 0, back: 0, ahead: 0 }, // uses each event's own close-up chase camera
];

class Game {
  constructor() {
    this.canvas = document.getElementById('game');
    this.renderer = new THREE.WebGLRenderer({ canvas: this.canvas, antialias: true, powerPreference: 'high-performance' });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, isTouch ? 1.75 : 2));
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
    this.world = new PhysicsWorld();
    this.levelGroup = new THREE.Group();
    this.scene.add(this.levelGroup);
    particles.attach(this.scene);
    this.petals = new Petals(this.scene, isTouch ? 110 : 160);

    this.contestants = [];
    this.racers = [];
    this.qualified = [];
    this.round = null;
    this.state = 'menu';
    this.racing = false;
    this.focus = null;
    this.shakeT = 0;
    this.camLook = new THREE.Vector3();
    this.viewIdx = 0;
    try {
      const v = parseInt(localStorage.getItem('cc_view') || '0', 10);
      if (v >= 0 && v < VIEWS.length) this.viewIdx = v;
    } catch (e) { /* storage blocked */ }
    this.playerName = 'You';
    try {
      this.playerName = localStorage.getItem('cc_name') || 'You';
    } catch (e) { /* storage blocked */ }

    this.ui = {};
    for (const id of ['menu', 'practice', 'how', 'card', 'cardRound', 'cardName', 'cardTag', 'cardTip', 'cardKeep', 'count', 'hud', 'hudRound', 'hudTimer', 'hudQual',
      'caption', 'banner', 'results', 'resTitle', 'resSub', 'resList', 'resButtons', 'spectate', 'specName', 'specNext', 'nameInput', 'touch', 'quit',
      'btnJump', 'btnDive', 'btnFire', 'practiceList', 'muteBtn', 'skipHint', 'loading', 'camBtn']) {
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
    const click = (el, fn) => el.addEventListener('click', () => {
      initAudio();
      sfx('click');
      fn();
    });
    click(document.getElementById('playBtn'), () => this.startShow());
    click(document.getElementById('practiceBtn'), () => this.showScreen('practice'));
    click(document.getElementById('howBtn'), () => this.showScreen('how'));
    for (const b of document.querySelectorAll('[data-back]')) click(b, () => this.showScreen('menu'));
    click(u.muteBtn, () => {
      setMuted(!isMuted());
      u.muteBtn.textContent = isMuted() ? 'Sound: off' : 'Sound: on';
    });
    click(u.quit, () => this.toMenu());
    click(u.specNext, () => this.nextSpectate());
    click(u.camBtn, () => this.cycleView());
    u.camBtn.textContent = 'Cam: ' + VIEWS[this.viewIdx].name;
    PRACTICE.forEach((R) => {
      const b = document.createElement('button');
      b.className = 'btn';
      b.innerHTML = `<span>${R.info.name}</span><small>${R.info.tagline}</small>`;
      click(b, () => this.startShow(R));
      u.practiceList.appendChild(b);
    });
    u.card.addEventListener('pointerdown', () => {
      if (this.state === 'intro' && this.introT > 0.8) this.introT = 99;
    });
    setupTouch(document.getElementById('stickZone'), document.getElementById('stickBase'), document.getElementById('stickKnob'), {
      jump: u.btnJump, dive: u.btnDive, fire: u.btnFire,
    });
    u.touch.hidden = true;
    window.addEventListener('keydown', (e) => {
      if (e.code === 'Escape' && this.state !== 'menu') this.toMenu();
      if (e.code === 'KeyC' && this.state !== 'menu') this.cycleView();
      if (e.code === 'Tab' && this.ui.spectate && !this.ui.spectate.hidden) {
        e.preventDefault();
        this.nextSpectate();
      }
      if ((e.code === 'Enter' || e.code === 'Space') && this.state === 'intro' && this.introT > 0.8) this.introT = 99;
    });
    document.addEventListener('visibilitychange', () => {
      this.last = performance.now();
    });
  }

  showScreen(name) {
    for (const s of ['menu', 'practice', 'how', 'results']) this.ui[s].hidden = s !== name;
  }

  readName() {
    const v = this.ui.nameInput.value.trim().slice(0, 14);
    this.playerName = v || 'You';
    try {
      localStorage.setItem('cc_name', v);
    } catch (e) { /* storage blocked */ }
  }

  setHud(on) {
    this.ui.hud.hidden = !on;
    this.ui.quit.hidden = !on;
    this.ui.camBtn.hidden = !on;
    this.ui.touch.hidden = !(on && isTouch);
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
    this.renderer.toneMappingExposure = sunset ? 0.95 : 1.0;
  }

  cycleView() {
    this.viewIdx = (this.viewIdx + 1) % VIEWS.length;
    try {
      localStorage.setItem('cc_view', String(this.viewIdx));
    } catch (e) { /* storage blocked */ }
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
    S.torii(g, 0, 0, 2, 9, null);
    for (let i = 0; i < 26; i++) {
      const a = Math.random() * Math.PI * 2;
      const r = 18 + Math.random() * 30;
      (Math.random() < 0.6 ? S.cherryTree : S.pineTree)(g, Math.cos(a) * r, Math.sin(a) * r - 6, 1 + Math.random() * 0.6);
    }
    for (let i = 0; i < 8; i++) S.banner(g, -10 + i * 2.85, -6);
    for (const c of this.contestants) c.dispose(this.scene);
    this.contestants = makeLineup(7, this.playerName).map((d) => new Contestant(this.scene, d));
    this.contestants.forEach((c, i) => {
      const a = (i / 7) * Math.PI - Math.PI;
      c.placeAt(new THREE.Vector3(Math.cos(a) * 4, 0, 4 + Math.sin(a) * 2.5), 0);
      c.celebrate = true;
      c.flail = Math.random() * 6;
      c.body.grounded = true;
      if (c.marker) c.marker.visible = false;
    });
    this.world.addBox(0, -0.5, 0, 200, 1, 200);
    bakeStatic(this.levelGroup);
    this.menuT = 0;
    playMusic('menu');
  }

  toMenu() {
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

  startShow(practiceRound = null) {
    this.readName();
    initAudio();
    for (const c of this.contestants) c.dispose(this.scene);
    const isFinalPractice = practiceRound && practiceRound.info.final;
    const n = isFinalPractice ? 4 : FIELD;
    this.contestants = makeLineup(n, this.playerName).map((d) => new Contestant(this.scene, d));
    this.player = this.contestants[0];
    for (const c of this.contestants) c.eliminated = false;
    this.practice = !!practiceRound;
    this.show = practiceRound
      ? [{ R: practiceRound, keep: PRACTICE_KEEP[practiceRound.info.id] }]
      : makeShow();
    this.roundIdx = 0;
    this.showScreen(null);
    this.loadRound();
  }

  loadRound() {
    this.clearLevel();
    const step = this.show[this.roundIdx];
    const R = step.R;
    const round = new R(this);
    this.round = round;
    round.build();
    this.racers = this.contestants.filter((c) => !c.eliminated);
    for (const c of this.contestants) {
      c.root.visible = !c.eliminated;
      c.kart = null;
      c.out = false;
    }
    const n = this.racers.length;
    this.quota = R.info.final ? 1 : Math.max(1, Math.min(step.keep, n - 1));
    const starts = round.startPositions(n);
    this.racers.forEach((c, i) => {
      c.placeAt(starts[i], Math.PI);
      c.checkpoint.set(0, starts[i].y, round.startZ + 0.5);
      c.finished = false;
      c.savedByTime = false;
      c.celebrate = false;
      c.respawning = false;
      c.ai = {};
      c.body.noCollide = false;
      c.control.x = c.control.z = 0;
      c.stun = 0;
    });
    if (round.isKart) round.seat(this.racers);
    bakeStatic(this.levelGroup);
    this.qualified = [];
    this.setSky(round.sunset);
    this.focus = this.player.eliminated ? this.racers[0] : this.player;
    this.raceT = 0;
    this.racing = false;
    // intro card
    const u = this.ui;
    const total = this.show.length;
    u.cardRound.textContent = this.practice ? 'Practice' : R.info.final ? 'Grand final' : `Round ${this.roundIdx + 1} of ${total}`;
    u.cardName.textContent = R.info.name;
    u.cardTag.textContent = R.info.tagline;
    u.cardTip.textContent = R.info.tip;
    u.cardKeep.textContent = R.info.final
      ? `${n} finalist${n === 1 ? '' : 's'} vs the castle`
      : `${this.quota} of ${n} qualify`;
    u.card.hidden = false;
    u.card.classList.remove('in');
    void u.card.offsetWidth;
    u.card.classList.add('in');
    u.skipHint.textContent = isTouch ? 'Tap to skip' : 'Press Enter to skip';
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

  updateQualHud() {
    const u = this.ui;
    if (this.round?.isKart) {
      const left = this.racers.filter((c) => !c.out).length;
      const hp = this.round.lord ? this.round.lord.ringHp : 0;
      u.hudQual.textContent = `${left} left · Lord's ring ${'●'.repeat(this.round.lord?.ringOk ? hp : 0)}${'○'.repeat(3 - (this.round.lord?.ringOk ? hp : 0))}`;
    } else {
      u.hudQual.textContent = `Qualified ${this.qualified.length} / ${this.quota}`;
    }
  }

  beginCountdown() {
    this.ui.card.hidden = true;
    this.state = 'countdown';
    this.countT = 3.2;
    this.lastCount = 4;
    this.setHud(true);
    this.ui.count.hidden = false;
  }

  qualify(c) {
    if (c.finished || !this.racing) return;
    c.finished = true;
    this.qualified.push(c);
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
      // fireworks over the finish
      const fz = this.round.finishZ - 6;
      const fy = (this.round.finishY || 0) + 12;
      [0, 250, 500, 800, 1100].forEach((ms, i) => setTimeout(() => {
        const cols = [[0xff3b3b, 0xffd23f], [0x4cc9f0, 0xffffff], [0x2bb673, 0xffd23f], [0xf15bb5, 0xffffff], [0xffd23f, 0xff8c1a]][i];
        particles.burst(new THREE.Vector3((Math.random() - 0.5) * 14, fy + Math.random() * 4, fz - Math.random() * 6), cols, 40, 9, 0.18, { up: 0.5, gravity: 3, life: 1.6, spin: 4 });
        sfx('pop', 0.4);
      }, ms));
    } else if (this.near(c, 12)) {
      particles.confetti(c.body.pos.clone().setY(c.body.pos.y + 2), 16);
    }
    this.updateQualHud();
    if (this.qualified.length >= this.quota) this.endRound();
  }

  endRound() {
    if (this.state === 'roundEnd') return;
    this.state = 'roundEnd';
    this.racing = false;
    this.endT = 0;
    sfx('whistle');
    const round = this.round;
    if (round.isKart) {
      const w = round.winner;
      if (w && w.isPlayer) this.bigBanner('CASTLE STORMED!', 'good', 3);
      else if (w) this.bigBanner(`${w.name} WINS!`, 'neutral', 3);
      else this.bigBanner('THE CASTLE STANDS', 'bad', 3);
      this.finalWinner = w;
      for (const c of this.racers) if (c !== w) c.eliminated = true;
    } else {
      for (const c of this.racers) {
        if (!c.finished) {
          c.eliminated = true;
          c.control.x = c.control.z = 0;
        }
      }
      if (this.player.eliminated && this.racers.includes(this.player) && !this.player.finished) {
        sfx('eliminated');
        this.bigBanner('ELIMINATED', 'bad', 2.5);
      } else if (!this.player.eliminated) {
        this.bigBanner('ROUND OVER', 'neutral', 2);
      } else {
        this.bigBanner('ROUND OVER', 'neutral', 2);
      }
    }
    this.ui.spectate.hidden = true;
  }

  showResults() {
    this.state = 'results';
    this.setHud(false);
    const u = this.ui;
    const round = this.round;
    const step = this.show[this.roundIdx];
    const playerIn = this.racers.includes(this.player);
    const hasNext = this.roundIdx + 1 < this.show.length;
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
        ? (w.isPlayer ? `You burst ${LORD_NAME}'s ring. The crowd carries you home.` : `${w.name} burst ${LORD_NAME}'s ring and takes the crown.`)
        : `${LORD_NAME} and his guards soaked every finalist. Better luck next time.`;
      u.results.classList.toggle('win', !!(w && w.isPlayer));
      for (const c of this.racers) this.resultRow(c, c === w ? 'Champion' : 'Soaked', c === w);
      if (w && w.isPlayer) particles.confetti(this.camera.position.clone().add(new THREE.Vector3(0, -1, -6)), 150);
    } else {
      u.results.classList.remove('win');
      const gotThrough = !this.player.eliminated;
      u.resTitle.textContent = this.practice ? (this.player.finished ? 'Qualified!' : 'Eliminated') : !playerIn ? `${round.constructor.info.name}: results` : gotThrough ? 'Qualified!' : 'Eliminated';
      u.resSub.textContent = `${this.qualified.length} of ${this.racers.length} made it through ${round.constructor.info.name}.`;
      this.qualified.forEach((c, i) => this.resultRow(c, c.savedByTime ? 'Time' : `${i + 1}${ordinal(i + 1)}`, true));
      const rest = this.racers.filter((c) => !c.finished).sort((a, b) => a.body.pos.z - b.body.pos.z);
      for (const c of rest) this.resultRow(c, 'Out', false);
    }
    if (this.practice) {
      addBtn('Try again', () => this.startShow(step.R), true);
      addBtn('Main menu', () => this.toMenu());
    } else if (round.isKart || !hasNext) {
      addBtn('Play again', () => this.startShow(), true);
      addBtn('Main menu', () => this.toMenu());
    } else if (!this.player.eliminated) {
      addBtn(`Next round: ${this.show[this.roundIdx + 1].R.info.name}`, () => this.nextRound(), true);
      addBtn('Quit', () => this.toMenu());
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
    li.querySelector('span').textContent = c.isPlayer ? `${c.name} (you)` : c.name;
    this.ui.resList.appendChild(li);
  }

  nextRound() {
    this.roundIdx++;
    this.showScreen(null);
    this.loadRound();
  }

  nextSpectate() {
    const pool = this.round?.isKart ? this.racers.filter((c) => !c.out) : this.racers.filter((c) => !c.finished && !c.eliminated);
    const list = pool.length ? pool : this.racers;
    if (!list.length) return;
    const i = list.indexOf(this.focus);
    this.focus = list[(i + 1) % list.length];
  }

  // ---------------------------------------------------------------- loop
  frame(now) {
    const dt = Math.min(0.05, (now - this.last) / 1000);
    this.last = now;
    input.update(now);
    if (this.state === 'menu') this.updateMenu(dt);
    else this.updateGame(dt);
    particles.update(dt);
    this.world.endFrame();
    this.sky.update(dt, this.camera);
    wind.time.value += dt;
    this.petals.update(dt, this.state === 'menu' ? new THREE.Vector3(0, 1, 2) : this.camLook, wind.time.value);
    this.renderer.render(this.scene, this.camera);
  }

  updateMenu(dt) {
    this.menuT += dt;
    for (const c of this.contestants) c.animate(dt);
    const a = this.menuT * 0.08;
    this.camera.position.set(Math.sin(a) * 13, 4.2 + Math.sin(this.menuT * 0.3) * 0.6, 12 + Math.cos(a) * 5);
    this.camera.lookAt(0, 3.2, -6);
    this.followSun(new THREE.Vector3(0, 0, 0));
  }

  updateGame(dt) {
    const round = this.round;
    if (!round) return;
    const u = this.ui;
    if (this.state === 'intro') {
      this.introT += dt;
      if (this.introT > 5.5) this.beginCountdown();
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
      if (left <= 0) {
        if (!round.isKart) {
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
      if (round.isKart && round.over && round.overT > 2.5) this.endRound();
      if (round.isKart) this.updateQualHud();
    } else if (this.state === 'roundEnd') {
      this.endT += dt;
      if (this.endT > 2.6) this.showResults();
    }

    round.update(dt);

    if (!round.isKart) {
      const racing = this.state === 'race';
      for (const c of this.racers) {
        if (c.eliminated && this.state !== 'roundEnd') continue;
        if (c.finished) this.afterFinish(c);
        else if (!racing) c.control.x = c.control.z = 0;
        else if (c.isPlayer) this.playerControl(c);
        else if (!c.frozen) round.think(c, dt);
        c.update(dt, this.world);
      }
      separateBodies(this.racers.filter((c) => !c.frozen).map((c) => c.body));
      if (racing) for (const c of this.racers) if (!c.finished) round.check(c);
    }

    // spectating once you're done (or out)
    const playerRacing = this.racers.includes(this.player) && !this.player.finished && !this.player.out;
    if (!playerRacing && this.state === 'race') {
      if (this.focus === this.player && this.player.finished && !this.specTimer) this.specTimer = 3;
      if (this.specTimer) {
        this.specTimer -= dt;
        if (this.specTimer <= 0) {
          this.specTimer = 0;
          this.nextSpectate();
        }
      }
      if (this.focus && (this.focus.finished || this.focus.out) && this.focus !== this.player) this.nextSpectate();
      u.spectate.hidden = false;
      u.specName.textContent = this.focus ? (this.focus.isPlayer ? 'You' : this.focus.name) : '';
    } else if (this.state === 'race') {
      u.spectate.hidden = true;
      this.focus = this.player;
    }

    this.updateCamera(dt);
  }

  playerControl(c) {
    // camera looks down -z, so stick up = forward = -z
    c.control.x = input.move.x;
    c.control.z = -input.move.y;
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
    let target;
    if (round.isKart) {
      const k = round.updateCamera(cam, dt, this.focus, VIEWS[this.viewIdx].name);
      target = new THREE.Vector3(k.x, 0, k.z);
    } else {
      const f = this.focus || this.racers[0];
      target = f ? f.body.pos.clone() : new THREE.Vector3();
      if (f && f.respawning) target.copy(f.checkpoint);
      const v = VIEWS[this.viewIdx];
      let want;
      let look;
      if (v.height) {
        const tall = this.portrait ? 1.25 : 1;
        const sc = round.viewScale || 1;
        want = target.clone().add(new THREE.Vector3(0, v.height * tall * sc, v.back * tall * sc));
        look = target.clone().add(new THREE.Vector3(0, 0.5, -v.ahead * sc));
        want.x = target.x * 0.9;
        look.x = target.x * 0.95;
      } else {
        want = target.clone().add(round.camOffset);
        want.x = target.x * 0.75;
        look = target.clone().add(round.camLook);
        look.x = target.x * 0.85;
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
    if (!g.ui.practice.hidden || !g.ui.how.hidden) {
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
