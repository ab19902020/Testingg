# Castle Chaos

A 3D knockout game show in the style of *Takeshi's Castle*, played like *Fall Guys*: 20 contestants race through four elimination events, and the survivors face a go-kart water-cannon battle at the castle gates.

Built with [Three.js](https://threejs.org/) (r160). It runs as an Android app (APK) and in any modern browser. There's no build step and no asset files: every model, texture, sound and the music are made in code.

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

## Android APK

The game ships as a native Android app (`../castle-chaos-android`). It runs the game full screen in landscape, plays fully offline, keeps the screen awake, and the Back button returns to the menu.

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
| `js/input.js` | Keyboard and floating touch joystick |
| `js/audio.js` | Sound effects and taiko-style music, made with the Web Audio API |
| `js/fx.js` | Particles, text textures, and the mesh merging that keeps it fast on phones |
| `js/scenery.js` | Castle, torii gates, cherry trees, banners, crowds, water, mountains |
| `js/rounds/base.js` | Shared round logic: building helpers, respawns, checkpoints, bot steering |
| `js/rounds/*.js` | One file per event, each with its own obstacles and bot AI |
| `vendor/` | Bundled Three.js (MIT) and the Bungee and Nunito fonts (SIL Open Font License) |
| `../castle-chaos-android/` | The Android app that wraps the game |

## Tuning

- Event order and how many go through: `SHOW` at the top of `js/main.js`.
- Running speed, jump height and dive: top of `js/character.js`.
- Each event's length, timer and difficulty: the constants at the top of its file in `js/rounds/`.
- The final boss's name: `LORD_NAME` in `js/rounds/showdown.js`.
- Game name: the `<title>` and logo in `index.html`.
