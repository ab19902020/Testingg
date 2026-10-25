# Christmas Living Room Games

An Android party game for families. The phone runs the game, its camera follows two players' bodies (no
controllers or wearables) and the picture goes to the TV through a wired USB-C to HDMI adapter. Ten two-player
minigames, a party tournament with pair rotation and a persistent scoreboard, **Play Solo** (one person against a
computer opponent in any of the ten games) - and **Christmas Golf**: 1 to 4 players taking turns over three
courses. Everything can be controlled with your body: raise a hand and hold it over a button.

> **Status, plainly:** all automated tests pass and a signed debug APK has been built and verified. The owner
> reports that versions 1.0.1 to 1.3.0 work on their phone. The detailed on-device checklist (tracking quality,
> frame rates, measured latency, TV modes) has not been run yet. The 1.4.0 changes (Christmas Golf, hand cursors
> from the moment you stand in front of the camera, less tracking work in one-player modes) are tested with
> simulated bodies only - how golf swings feel in front of a real camera has not been checked yet.
> See [docs/TEST_REPORT.md](docs/TEST_REPORT.md) for the exact status of every check.

## What's in the box

| | |
|---|---|
| APK | `dist/ChristmasLivingRoomGames-debug.apk` (debug-signed, ~21 MB, arm64-v8a + x86_64), SHA-256 in `dist/SHA256SUMS` |
| Build guide + versions | [BUILDING.md](BUILDING.md) |
| Install + TV setup | [docs/INSTALL_AND_TV_SETUP.md](docs/INSTALL_AND_TV_SETUP.md) |
| Controls for every game | [docs/CONTROLS.md](docs/CONTROLS.md) |
| Test results + device checklist | [docs/TEST_REPORT.md](docs/TEST_REPORT.md) |
| Limitations + device compatibility | [docs/LIMITATIONS.md](docs/LIMITATIONS.md) |
| How it works | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Licences | [LICENSES.md](LICENSES.md) |

## Changes in 1.4.0 - golf, and control everything with your body

* **Christmas Golf** (main menu): 1 to 4 players - family members and/or computer golfers (Sprout, Jingles,
  Blitz) - take turns, so only the player whose turn it is stands in front of the camera. Each player plays a
  whole hole, then the next steps up and raises a hand to start. Three courses of three holes (par 12 each):
  *Candy Cane Links* (wide and gentle), *Frozen Lake Club* (water everywhere), *North Pole Peaks* (narrow,
  tree-lined, a steep green).
  * **Drive, approach, putt - three movements:** a full golf swing for the tee shot and the approach (the higher
    your backswing, the further the ball goes; bring your hands back through where they started to hit it
    straight), and a small pendulum stroke for putting (draw your hands back sideways - further back, harder putt).
    A live meter shows the distance while you swing back; coming down slowly is a practice swing.
  * **Clubs and aim by hand:** raise a hand and hold it over Driver, 3 Wood, 5 Iron, 9 Iron, Wedge or Putter, or
    over an aim arrow (hold to keep turning). The game suggests a club and aims for you, so you can just swing.
  * Wind, bunkers, snowy rough, trees, frozen lakes (one penalty stroke), sloping greens with arrows showing the
    break, and short putts tapped in automatically. A scorecard after each hole; fewest strokes wins.
* **Hands-free from the start:** a hand cursor now appears for anyone standing in front of the camera - no set-up
  needed first - so you can open games, pick players and start matches without touching the phone. (The very
  first camera permission still needs a tap on the phone; Android requires that.)
* **Less tracking work for one player:** in Play Solo and golf the camera follows one player (plus one spectator),
  so the person-search runs far less often - 7 instead of 25 times in 24 frames in the test - leaving more time
  for 60 fps tracking.

## Changes in 1.3.0 - less lag, fairer timing

* **Swings count when you make them, not when the game hears about them.** Every movement reaches the game a
  little late (the camera and body tracking take ~0.1 s, and the TV shows the picture late too), which made
  on-time tennis swings land "late". Swings are now stamped with the moment your arm moved fastest - where the racket
  meets the ball - and judged against where the ball was *on your TV* at that moment. The same allowance is used for
  goalkeeper saves, snowball shields and dodges, and dance poses. Computer players are unaffected.
* **Timing test** (Settings -> Timing): a snowball flies into a ring ten times; swing as it gets there. The game
  measures your phone + TV delay and allows for exactly that. Without the test it assumes a typical 60 ms. You can
  also nudge the allowance up or down by hand.
* **Less delay to begin with:**
  * Swings are recognised 17-42 ms after the fastest moment of the swing (was 33-75 ms): the smoothing now opens up
    as soon as you start moving.
  * Drawing is synchronised with the screen refresh, so finished pictures no longer wait in a queue (up to ~33 ms).
  * The game asks the TV for its low-delay game mode (HDMI ALLM) where the TV and phone support it.
  * The camera uses its low-delay preview mode with fast noise reduction (no multi-frame smoothing that smears
    fast arms).
  * The small camera picture is made on a separate thread, only while it is shown, so it never holds up tracking.
  * In Auto quality the game picks whichever body-tracking model lets the camera run at 60 fps on your phone.
  * Your character's arms are shown slightly further ahead (up to 0.12 s) to hide the remaining delay; steering
    (lean) reacts faster.
* **Bigger tennis court and more time:** the court is about 18% wider, shots take about 0.15-0.2 s longer to cross,
  and the timing window is wider (0.36 s). Long rallies still get faster, so matches still end.

## Changes in 1.2.0

* **Front or back camera, one tap away:** a camera button on the main menu, on the "Stand in your areas" screen
  and in the pause menu switches between the front camera and the back camera. The back camera is usually sharper
  and wider; with it the phone's screen faces away from the players, which is fine because the game is on the TV. Switching re-checks everyone's
  position, and the choice is remembered.
* **Smoother, steadier body tracking:**
  * Smoothing now adapts to each player's size in the picture, so a child far from the camera gets the same quick,
    steady response as an adult close to it. Low-confidence joints are smoothed harder; confident fast moves stay
    sharp.
  * One-frame glitches (a wrist that jumps across the body for a single frame) are ignored.
  * The on-screen avatar and hands are predicted a few hundredths of a second ahead to hide camera and processing
    delay (capped, so it never overshoots far).
  * Crouching, ducking and head tilt now show on your character.
  * The camera asks for 60 frames per second when the phone keeps up and drops back to 30 when it doesn't
    (Settings: "Smoother tracking").
  * A tracking meter on the "Stand in your areas" screen rates each player (Excellent to Weak) and says what would
    help: come closer, step back, more light, keep both hands in the picture.
* **New graphics everywhere:** lit, shaded snowmen with knitted hats and mittens; skies with aurora and moon;
  floodlit courts and pitches; a log cabin and brick snow forts; ski tracks and snow spray on the slalom; a
  mountain road with lanterns; a polished bowling lane; a lamp-lit table tennis hall; a dance stage with moving
  spotlights and a light-up floor; a living room with frosty windows for the tree. Buttons, panels and the
  scoreboard are glossier, and hits, catches and saves now flash with impact rings and glowing sparks.
  "Reduced effects" in Settings still tones all of this down.

## Changes in 1.1.0

* **Play Solo:** one person against a computer player in all ten games. Pick who is playing and the opponent:
  **Sprout (Easy)**, **Jingles (Medium)** or **Blitz (Hard)**. You can stand anywhere in the middle of the picture;
  setup, calibration and the start gesture are for you alone, and the game only ever pauses for you.
* The computer players play fair: each sees only what a person sees on screen, reacts after a human-like delay,
  moves its hands at a human speed and makes mistakes (more on Easy, fewer on Hard). Their characters animate their
  swings, throws, reaches and dances like a camera player's would.
* Quick Match's "Who is playing?" screen has a **Just me** button that starts the chosen game against the
  computer. The Party Tournament stays a two-person mode.

## Changes in 1.0.1

* **Crash right after allowing the camera: most likely cause fixed** (found by code review; not yet confirmed on
  the phone). Granting the permission started the camera twice (from the permission result and again when the app
  resumed), so the same camera was opened twice and the first one was shut down mid-setup; an error on the camera
  thread was not caught and closed the app. Camera start is now idempotent, stale camera callbacks are ignored and
  every camera callback is guarded.
* If the game ever closes unexpectedly, the next launch shows what happened (phone model and where the error was)
  with a **Share details** button. Nothing is sent automatically.
* If the phone's GPU driver crashes during body tracking, the game switches to the CPU for good on that phone.
* If body tracking cannot start (for example the TensorFlow Lite library cannot load), a message says so instead
  of tracking silently not working.
* Safer camera image handling and pose-thread restarts after pausing.

## The games

1. **Christmas Tennis** - swing like a racket, first to 7.
2. **Snowball Showdown** - throw, dodge by leaning, shield with both hands up.
3. **Present Catch** - reach for presents, avoid coal.
4. **Reindeer Racing** - pump your arms in rhythm (no running), lean to steer.
5. **Festive Bowling** - underarm swing, 5 frames, independent lanes.
6. **Table Tennis** - small quick forehands/backhands, first to 11.
7. **Elf Dance-Off** - copy the elf's poses on the beat.
8. **Sleigh Slalom** - lean to steer, tuck for speed (steering-only mode available).
9. **Christmas Goalkeepers** - reach to save shots.
10. **Decorate the Tree** - hold a hand still to pick up and hang ornaments.

Every game is played by two people at the same time, each controlling their own side with their own body - or,
with **Play Solo**, by one person against the computer.

## Quick start (players)

1. Install the APK (see [install guide](docs/INSTALL_AND_TV_SETUP.md)).
2. Plug a USB-C to HDMI adapter into the phone and the TV (phone must support USB-C video output).
3. Put the phone in landscape on a stand near the TV at about chest height, camera facing the room.
4. Open the app and follow the setup: TV check -> place the phone -> step into your outlines -> calibrate ->
   both players raise a hand and hold to start.
5. On your own? Choose **Play Solo**, pick yourself and an opponent level, then a game. Stand in the middle of
   the picture, calibrate, and raise a hand to start.

## Quick start (developers)

```bash
# needs JDK 17+ and an Android SDK with platforms;android-35 and build-tools;35.0.0
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :core:test            # 81 JVM tests (the 6 real-model tests are skipped unless configured)
./gradlew :app:assembleDebugApk # -> app/build/outputs/apk/ChristmasLivingRoomGames-debug.apk
adb install -r app/build/outputs/apk/ChristmasLivingRoomGames-debug.apk
```

## Technology choice

* **Kotlin, plain Android framework, no game engine.** Rendering is a small immediate-mode 2D layer over
  `Canvas` (hardware accelerated via `lockHardwareCanvas`). This keeps the APK small, the build simple and the
  whole game (rules, UI, tracking logic) testable on a normal JVM.
* **Pose model: MediaPipe BlazePose (lite + full), Apache-2.0**, run with **TensorFlow Lite 2.16.1** (GPU
  delegate, CPU/XNNPACK fallback). The models are bundled in the APK; nothing is downloaded at runtime.
* **Genuine multi-person tracking.** The stock MediaPipe Pose Landmarker reliably tracks one person per image, so
  the app contains its own Kotlin implementation of the BlazePose two-stage pipeline: the person detector runs on
  overlapping left/right/full search regions, each found person gets their own landmark pass and their own region
  of interest is tracked frame to frame. This port was checked against the official MediaPipe output on real
  two-person images (mean landmark error about 0.002 of the image size; details in the test report).
* **Identity by position and continuity, not detection order.** Each player is locked to a slot after
  calibration; spectators cannot take over; lost players are re-acquired only if they match the calibrated body.
* **Custom Gradle pipeline instead of the Android Gradle Plugin.** The build environment could not reach Google's
  Maven repository, so `app/build.gradle.kts` drives aapt2, D8, zipalign and apksigner directly. It works with a
  normal Android SDK on any machine (see [BUILDING.md](BUILDING.md)).

## Performance budgets (targets, not measurements)

These are the design targets the code is built around. **They have not been measured on a device yet**; the
in-app performance overlay (Settings -> Developer) shows the live values to check against them.

| Item | Target |
|---|---|
| Camera | 1280x720, 30 fps, newest frame only (no queue) |
| Pose inference, both players | <= 25 ms per camera frame (full model, GPU) |
| Camera capture -> game input | <= 100 ms |
| Render | 60 fps cap (16.7 ms), independent of inference rate |
| Simulation | fixed 120 Hz step, independent of render rate |
| Adaptation | AUTO quality drops to the lite model after 3 s above 30 ms or when the phone is hot; returns after 15 s below 12 ms |
| Thermal | SEVERE: render capped at 30 fps and lite model; CRITICAL: pose runs on every other camera frame |

## Privacy

Camera frames are processed in memory on the phone and discarded. No video or images are recorded, stored or
uploaded. The app does not request the INTERNET permission (the build fails if it ever does). Player names and
scores are stored only on the phone.
