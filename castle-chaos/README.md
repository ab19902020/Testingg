# Castle Chaos

A 3D knockout game show in the style of *Takeshi's Castle*, played like *Fall Guys*: 20 contestants race through four elimination events, and the survivors face a go-kart water-cannon battle at the castle gates.

Built with [Three.js](https://threejs.org/) (r160). It runs as an Android app (APK) and in any modern browser. There's no build step and no asset files: every model, texture, sound and the music are made in code.

This folder is a standalone game. It doesn't use anything else in this repository.

## The show

Every show picks **five events at random** from the ten below, then ends with the Show Down. 20 contestants start; 16, 12, 9, 6 and then 4 go through.

| Event | What happens |
|---|---|
| **Knock Knock** | 8 walls of paper doors. Some doors are solid wood (you bounce off), some hide a mud pit (you're sent back). |
| **Skipping Stones** | Hop across the river on big stepping stones. Fake ones wobble for a moment, then sink, and come back cracked. Halfway there's an island checkpoint. |
| **Boulder Dash** | Run up the castle slope while boulders roll down. Boulders glance off the bunkers and keep rolling, and a dark shadow shows where each one is heading. Hide behind a bunker. After a hit you're safe for 2 seconds. |
| **Sumo Gong** | Get past four lines of sumo wrestlers and bang the gong. If one grabs you, he throws you back down the field. |
| **Bridge Ball** | Cross a wobbly rope bridge while cannons fire balls across it. A glowing cannon is about to fire: jump the ball or wait. |
| **Mushroom Trip** | Hop across giant mushrooms in the river. Some bob up and down, some slide sideways, and the blue ones bounce you sky high. |
| **Honeycomb Maze** | Find your way through a new random maze every time. Sticky honey patches slow you down. |
| **Big Balls** | Bounce across rows of giant red balls. Every landing throws you back into the air; land off-centre and you're flung sideways. |
| **Punch Wall** | Edge along a ledge either side of a long wall while boxing gloves punch out of it. A glove pulls back and shakes just before it fires. |
| **Sweeper** | A last-ones-standing round. Jump the red arm; if the yellow arm is coming too, tap jump for a low hop under it. The outer ring drops into the lake after 35 seconds. |
| **Show Down** (final) | Go-karts. Burst the 3-layer paper ring on Lord Kazan's golden kart. If a guard bursts your ring, you're out. |

If the clock runs out in a race, whoever is closest to the finish takes the places that are left. If you're knocked out, you can keep watching the rest of the show.

**Practice an event** on the main menu lets you play any single event.

## Playing online

Tap **Online** on the main menu.

- **Host a game** gives you a 4-letter code. Tell your friends.
- They tap **Online**, type the code and press **Join**. Up to 8 real players; bots fill the rest of the 20 places.
- The host presses **Start the show**, and moves everyone on to the next round from the results screen.

How it works: the devices connect directly to each other (WebRTC, using the PeerJS library). PeerJS's free public service only introduces them; there is no game server to run or pay for. The host's device runs the bots and the shared obstacles, and every device runs its own player, so your own movement is always instant. If a player drops out, the host turns their contestant into a bot. If the host leaves, the game ends.

Limits to know about:

- Direct connections work on most home Wi-Fi. Some mobile networks and strict office or school networks block them, and joining will time out. Fixing that needs a relay (TURN) server, which costs money to run; one can be added in `peerOptions()` in `js/net.js`.
- Everyone needs the same version of the game.

## Other features

- **Locker**: choose your suit colour, helmet colour and hat. Win a show to unlock the crown.
- **Difficulty**: Easy, Normal or Hard changes how fast and sharp the bots are.
- **Race position** shows in the corner (for example 3rd / 20).
- The game lowers its resolution, then turns off real-time shadows, if a phone can't keep up.

## Controls

| | Phone | Keyboard |
|---|---|---|
| Run | Drag anywhere on the left half of the screen | WASD or arrow keys |
| Jump (hold for a full jump, tap for a low hop) | **JUMP** button | Space |
| Turn the camera | Drag on the right side of the screen | Drag with the mouse, or Z / X |
| Dive | **DIVE** button | Shift (or E) |
| Drive (final) | Drag: up is throttle, left and right steer | W/S throttle, A/D steer |
| Fire (final) | Hold **FIRE** | Space or F |
| Quit to menu | ✕ button | Esc |
| Switch camera (Overhead, Chase, Top-down) | **Cam** button | C |
| Watch someone else | **Next ▸** | Tab |

Your water cannon aims itself at whatever is in front of you. The paper ring is on the front of each kart, so it can only be burst by a shot coming at the front.

## Android APK

The game ships as a native Android app (`../castle-chaos-android`). It runs the game full screen in landscape, plays offline against bots (online play needs the internet), keeps the screen awake, and the Back button returns to the menu.

**Getting the APK:** every push that changes the game or the app runs the **Castle Chaos APK** GitHub workflow. It builds the APK and publishes it as a release. On your phone, open the repository's **Releases** page, download `CastleChaos.apk` from the newest release and open it. Android will ask you to allow "Install unknown apps" for your browser or Files app the first time.

**Signing:** with no secrets set, the APK is signed with a debug key. Each build gets a different one, so to install a newer build you uninstall the old one first. To get updates that install over the top (and to publish on Google Play), make your own key once and add it as repository secrets:

```bash
keytool -genkeypair -v -keystore castle-chaos.jks -alias castlechaos -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 castle-chaos.jks   # paste the output into the ANDROID_KEYSTORE_BASE64 secret
```

Then add `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`castlechaos`) and `ANDROID_KEY_PASSWORD`. Keep the `.jks` file safe and never commit it.

**Building it yourself:** with the Android SDK installed, run `./gradlew assembleRelease` in `castle-chaos-android`. The build copies this folder into the APK, so there's only one copy of the game to edit.

## Running it in a browser

The game uses ES modules, so open it through a web server, not by double-clicking `index.html`:

```bash
cd castle-chaos
python3 -m http.server 8000
# then open http://localhost:8000
```

Three.js and the fonts are bundled in `vendor/`, so it needs no internet connection.

## Files

| File | What it does |
|---|---|
| `index.html` | Page, menus, HUD and touch controls |
| `style.css` | All the UI styling |
| `js/main.js` | The show: round order, qualifying, results, camera, commentary |
| `js/character.js` | Contestant model (helmet and numbered bib), running, jumping, diving, getting knocked |
| `js/physics.js` | Small collision world: boxes, stepping stones, ramps, moving platforms |
| `js/input.js` | Keyboard, floating touch joystick and camera drag |
| `js/net.js` | Online play: lobby, room codes, sending positions and shared events between devices |
| `js/audio.js` | Sound effects and taiko-style music, made with the Web Audio API |
| `js/fx.js` | Particles, text textures, and the mesh merging that keeps it fast on phones |
| `js/sky.js` | Sky dome, sun glow, drifting clouds and the reflections that give everything its glossy look |
| `js/textures.js` | Grass, dirt, sand, wood, stone, plaster, roof-tile and tarmac textures, drawn in code |
| `js/scenery.js` | Castle, torii gates, cherry trees, grass and flowers, bunting, banners, crowds, the water shader, mountains |
| `js/rounds/base.js` | Shared round logic: building helpers, respawns, checkpoints, bot steering |
| `js/rounds/*.js` | One file per event, each with its own obstacles and bot AI |
| `vendor/` | Bundled Three.js (MIT), PeerJS (MIT) and the Bungee and Nunito fonts (SIL Open Font License) |
| `../castle-chaos-android/` | The Android app that wraps the game |

## Tuning

- Camera views: `VIEWS` at the top of `js/main.js`.
- Bot speed and skill at each difficulty: `DIFFICULTY` in `js/character.js`.
- Event order and how many go through: `SHOW` at the top of `js/main.js`.
- Running speed, jump height and dive: top of `js/character.js`.
- Each event's length, timer and difficulty: the constants at the top of its file in `js/rounds/`.
- The final boss's name: `LORD_NAME` in `js/rounds/showdown.js`.
- Game name: the `<title>` and logo in `index.html`.
