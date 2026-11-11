// Online play over WebRTC (PeerJS). One player hosts and shares a 4-letter code; up to 7 friends join.
// The host runs the bots and the shared obstacles. Every device runs its own player, so your own
// movement always feels instant. No game server is needed: PeerJS's free service only introduces
// the devices to each other.

const PREFIX = 'castlechaos-v2-';
const LETTERS = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
export const MAX_PLAYERS = 8;
const SNAP_EVERY = 1 / 15;

export class Net {
  constructor(game) {
    this.game = game;
    this.peer = null;
    this.isHost = false;
    this.conns = new Map(); // host: pid -> connection
    this.hostConn = null; // guest: connection to the host
    this.myPid = null;
    this.players = []; // lobby list: {pid, name, look}
    this.code = '';
    this.sendT = 0;
    this.remoteR = new Map(); // host: latest round state from each guest
    this.closed = false;
  }

  static available() {
    return typeof window.Peer === 'function' && 'RTCPeerConnection' in window;
  }

  peerOptions() {
    return { debug: 0, ...(window.CC_PEER || {}) };
  }

  /** Start hosting. Resolves with the room code. */
  host(me) {
    this.isHost = true;
    this.myPid = 'host';
    this.players = [{ pid: 'host', name: me.name, look: me.look }];
    return new Promise((resolve, reject) => {
      let tries = 0;
      const attempt = () => {
        this.code = Array.from({ length: 4 }, () => LETTERS[Math.floor(Math.random() * LETTERS.length)]).join('');
        const peer = new window.Peer(PREFIX + this.code, this.peerOptions());
        this.peer = peer;
        peer.on('open', () => resolve(this.code));
        peer.on('connection', (conn) => this.onGuest(conn));
        peer.on('error', (err) => {
          if (err.type === 'unavailable-id' && tries++ < 5) {
            peer.destroy();
            attempt();
          } else if (!peer.open) reject(new Error(friendly(err)));
          else this.game.onNetError(friendly(err));
        });
        peer.on('disconnected', () => {
          if (!this.closed) peer.reconnect();
        });
      };
      attempt();
    });
  }

  onGuest(conn) {
    conn.on('open', () => {
      if (this.players.length >= MAX_PLAYERS || this.game.state !== 'menu') {
        conn.send({ t: 'full', why: this.game.state !== 'menu' ? 'That game has already started.' : 'That game is full.' });
        setTimeout(() => conn.close(), 500);
        return;
      }
      const pid = 'p' + Math.random().toString(36).slice(2, 8);
      conn.pid = pid;
      this.conns.set(pid, conn);
      conn.on('data', (msg) => this.onMessage(msg, pid));
      conn.on('close', () => this.onGuestLeft(pid));
      conn.on('error', () => this.onGuestLeft(pid));
    });
  }

  onGuestLeft(pid) {
    if (!this.conns.has(pid)) return;
    this.conns.delete(pid);
    this.remoteR.delete(pid);
    const p = this.players.find((x) => x.pid === pid);
    this.players = this.players.filter((x) => x.pid !== pid);
    this.send({ t: 'lobby', players: this.players });
    this.game.onPlayerLeft(pid, p ? p.name : 'A player');
  }

  /** Join a hosted game by its code. Resolves once we're in the lobby. */
  join(code, me) {
    this.isHost = false;
    this.code = code.toUpperCase();
    return new Promise((resolve, reject) => {
      const peer = new window.Peer(this.peerOptions());
      this.peer = peer;
      let done = false;
      const fail = (msg) => {
        if (done) {
          this.game.onNetError(msg);
          return;
        }
        done = true;
        reject(new Error(msg));
      };
      const timer = setTimeout(() => fail('Could not reach that game. Check the code and your connection.'), 15000);
      peer.on('error', (err) => fail(friendly(err)));
      peer.on('open', () => {
        const conn = peer.connect(PREFIX + this.code, { reliable: true, serialization: 'json' });
        this.hostConn = conn;
        conn.on('open', () => conn.send({ t: 'hello', name: me.name, look: me.look }));
        conn.on('data', (msg) => {
          if (msg.t === 'welcome') {
            this.myPid = msg.pid;
            this.players = msg.players;
            done = true;
            clearTimeout(timer);
            resolve();
          } else if (msg.t === 'full') {
            clearTimeout(timer);
            fail(msg.why);
          } else this.onMessage(msg, 'host');
        });
        conn.on('close', () => {
          clearTimeout(timer);
          if (!this.closed) fail('The host has left the game.');
        });
      });
    });
  }

  close() {
    this.closed = true;
    try {
      this.peer?.destroy();
    } catch (e) { /* already gone */ }
    this.peer = null;
  }

  /** Host: send to every guest (optionally all but one). Guest: send to the host. */
  send(msg, exceptPid = null) {
    if (this.isHost) {
      for (const [pid, conn] of this.conns) if (pid !== exceptPid && conn.open) conn.send(msg);
    } else if (this.hostConn?.open) this.hostConn.send(msg);
  }

  sendEvent(kind, data) {
    this.send({ t: 'ev', k: kind, d: data });
  }

  onMessage(msg, from) {
    const g = this.game;
    switch (msg.t) {
      case 'hello': {
        // host only: a guest introduces themselves
        const name = String(msg.name || 'Player').slice(0, 14);
        this.players.push({ pid: from, name, look: msg.look });
        this.conns.get(from)?.send({ t: 'welcome', pid: from, players: this.players });
        this.send({ t: 'lobby', players: this.players });
        g.onLobby();
        break;
      }
      case 'lobby':
        this.players = msg.players;
        g.onLobby();
        break;
      case 'ev':
        if (this.isHost) this.send(msg, from);
        g.round?.onNet(msg.k, msg.d);
        break;
      case 's':
        this.onSnapshot(msg, from);
        break;
      default:
        g.onNetMessage(msg, from);
    }
  }

  onSnapshot(msg, from) {
    const g = this.game;
    if (!g.round) return;
    if (msg.c) {
      for (const a of msg.c) {
        const c = g.contestants[a[0]];
        if (c && c.remote && (!this.isHost || c.pid === from)) c.netApply(a);
      }
    }
    if (this.isHost) {
      if (msg.r) this.remoteR.set(from, msg.r);
      else this.remoteR.delete(from);
    }
    if (msg.r) g.round.netApply(msg.r);
    if (!this.isHost && msg.tt !== undefined) {
      // keep our clocks close to the host's so moving obstacles line up
      if (Math.abs(g.round.t - msg.tt) > 0.3) g.round.t = msg.tt;
      else g.round.t += (msg.tt - g.round.t) * 0.1;
      if (g.state === 'race') {
        if (Math.abs(g.raceT - msg.rt) > 0.3) g.raceT = msg.rt;
        else g.raceT += (msg.rt - g.raceT) * 0.1;
      }
    }
  }

  /** Called every frame: sends our characters' positions about 15 times a second. */
  tick(dt) {
    const g = this.game;
    if (!g.round || g.state === 'menu' || g.state === 'results') return;
    this.sendT -= dt;
    if (this.sendT > 0) return;
    this.sendT = SNAP_EVERY;
    const kart = g.round.isKart;
    const mine = g.round.netLocal();
    if (!this.isHost) {
      const p = g.player;
      const c = !kart && p && g.racers.includes(p) ? [p.netPack()] : null;
      this.send({ t: 's', c, r: mine });
      return;
    }
    const packs = kart ? null : g.racers.map((c) => [c, c.netPack()]);
    for (const [pid, conn] of this.conns) {
      if (!conn.open) continue;
      let r = mine;
      for (const [other, rr] of this.remoteR) if (other !== pid && rr) r = (r || []).concat(rr);
      conn.send({
        t: 's',
        c: packs ? packs.filter(([c]) => c.pid !== pid).map(([, a]) => a) : null,
        r,
        tt: Math.round(g.round.t * 100) / 100,
        rt: Math.round(g.raceT * 100) / 100,
      });
    }
  }
}

function friendly(err) {
  switch (err.type) {
    case 'peer-unavailable': return 'No game found with that code.';
    case 'network':
    case 'server-error':
    case 'socket-error':
    case 'socket-closed': return 'Could not reach the matchmaking service. Check your internet connection.';
    case 'browser-incompatible': return 'This device does not support online play.';
    default: return 'Connection problem: ' + (err.type || err.message || 'unknown');
  }
}
