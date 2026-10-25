# Christmas Living Room Games

An Android party game for families. The phone runs the game, its camera follows two players' bodies (no
controllers or wearables) and the picture goes to the TV through a wired USB-C to HDMI adapter. Ten two-player
minigames, a party tournament with pair rotation and a persistent scoreboard - and **Play Solo**: one person
against a computer opponent (Easy, Medium or Hard) in any of the ten games.

> **Status, plainly:** all automated tests pass and a signed debug APK has been built and verified. The owner
> reports that version 1.0.1 works on their phone; the detailed on-device checklist (tracking quality, frame
> rates, latency, TV modes) has not been run yet, and solo mode (1.1.0) has only been tested with simulated input.
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
