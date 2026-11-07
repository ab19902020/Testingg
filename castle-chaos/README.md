# Castle Chaos

A 3D knockout game show in the style of *Takeshi's Castle*, played like *Fall Guys*: 20 contestants race through four elimination events, and the survivors face a go-kart water-cannon battle at the castle gates.

Built with [Three.js](https://threejs.org/) (r160). It runs in any modern browser on phones and computers. There's no build step and no asset files: every model, texture, sound and the music are made in code.

This folder is a standalone game. It doesn't use anything else in this repository.

## The show

| # | Event | What happens | Who goes through |
|---|---|---|---|
| 1 | **Knock Knock** | 8 walls of paper doors. Some doors are solid wood (you bounce off), some hide a mud pit (you're sent back). | 15 of 20 |
| 2 | **Skipping Stones** | Hop across the river. Fake stones sink when you land on them and come back cracked. Halfway there's an island checkpoint. | 10 of 15 |
| 3 | **Boulder Dash** | Run up the castle slope while boulders roll down. Hide behind the bunkers. | 7 of 10 |
| 4 | **Sumo Gong** | Get past four lines of sumo wrestlers and bang the gong. If one grabs you, he throws you back down the field. | 4 of 7 |
| 5 | **Show Down** | Grand final in go-karts. Burst the 3-layer paper ring on Lord Kazan's golden kart. If a guard bursts your ring, you're out. | 1 champion |

If the clock runs out in a race, whoever is closest to the finish takes the places that are left. If you're knocked out, you can keep watching the rest of the show.

**Practice an event** on the main menu lets you play any single event.

## Controls

| | Phone | Keyboard |
|---|---|---|
| Run | Drag anywhere on the left half of the screen | WASD or arrow keys |
| Jump | **JUMP** button | Space |
| Dive | **DIVE** button | Shift (or E) |
| Drive (final) | Drag: up is throttle, left and right steer | W/S throttle, A/D steer |
| Fire (final) | Hold **FIRE** | Space or F |
| Quit to menu | ✕ button | Esc |
| Watch someone else | **Next ▸** | Tab |

Your water cannon aims itself at whatever is in front of you. The paper ring is on the front of each kart, so it can only be burst by a shot coming at the front.

## Running it

The game uses ES modules, so open it through a web server, not by double-clicking `index.html`:

```bash
cd castle-chaos
python3 -m http.server 8000
# then open http://localhost:8000
```

To play on your phone, host the folder anywhere that serves static files. For example, turn on **GitHub Pages** for this repository and open `https://<your-user>.github.io/<repo>/castle-chaos/`.

Three.js loads from the jsDelivr CDN (see the import map in `index.html`), so the first load needs an internet connection.

## Files

| File | What it does |
|---|---|
| `index.html` | Page, menus, HUD and touch controls |
| `style.css` | All the UI styling |
| `js/main.js` | The show: round order, qualifying, results, camera, commentary |
| `js/character.js` | Contestant model (helmet and numbered bib), running, jumping, diving, getting knocked |
| `js/physics.js` | Small collision world: boxes, stepping stones, ramps, moving platforms |
| `js/input.js` | Keyboard and floating touch joystick |
| `js/audio.js` | Sound effects and taiko-style music, made with the Web Audio API |
| `js/fx.js` | Particles, text textures, and the mesh merging that keeps it fast on phones |
| `js/scenery.js` | Castle, torii gates, cherry trees, banners, crowds, water, mountains |
| `js/rounds/base.js` | Shared round logic: building helpers, respawns, checkpoints, bot steering |
| `js/rounds/*.js` | One file per event, each with its own obstacles and bot AI |

## Tuning

- Event order and how many go through: `SHOW` at the top of `js/main.js`.
- Running speed, jump height and dive: top of `js/character.js`.
- Each event's length, timer and difficulty: the constants at the top of its file in `js/rounds/`.
- The final boss's name: `LORD_NAME` in `js/rounds/showdown.js`.
- Game name: the `<title>` and logo in `index.html`.
