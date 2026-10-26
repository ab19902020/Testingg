# Known limitations and device compatibility

## Biggest open item: no measured real-device testing yet

The owner reports that versions up to 1.6.0 install and play on their phone. Nothing has been measured on a
device, though: the build environment had no Android device and no emulator, and the detailed checklist in
[TEST_REPORT.md](TEST_REPORT.md) has not been run. Until it has:

* live camera tracking, gesture recognition with real bodies and identity stability in a real room are unproven;
* frame rates, latency and heat on any phone are unknown (no numbers are claimed anywhere);
* USB-C/HDMI output, second-screen (Presentation) mode and cable unplug handling are unproven on hardware;
* audio output and latency over HDMI are unproven;
* gesture thresholds were tuned with synthetic motion and will probably need adjusting after the first real
  sessions;
* the 1.2.0 changes are untested on a phone: switching cameras while playing, the 60 fps camera mode, the motion
  prediction and the new graphics (which use more gradients and glows, so they cost more drawing time - if the
  game stutters, turn on **Reduced effects**);
* the 1.6.0 changes are untested on a phone: the new swing detection and tennis rules, the hand pointer, 3D golf
  (a few hundred polygons and trees per frame - its frame rate on a phone is not known), held-object tracking and
  the sharp-motion camera exposure;
* the 1.7.0 changes are untested on a phone: finger tracking (finger guns and the thumb trigger), the recoil flick,
  punches, the bow, darts throws, all the new games (Clay Shooting, Boxing, Archery, Shooting Gallery, Darts,
  Penalty Save), the sports looks and the toy-gun objects;
* the 1.8.0 changes are untested on a phone: the new sound (77 stereo effects, room echo, background sounds - how
  they come out of a phone or TV speaker, and whether the phone keeps up with the extra mixing, is unknown), the new
  menus and scoreboards, the 3D Sleigh Slalom and other heavier graphics (more drawing per frame; turn on **Reduced
  effects** if a game stutters), the golf flyovers and HUD, and every tracking change listed below;
* **1.8.0 tracked worse on the owner's phone** (moves missed, wrong triggers in bowling, skiing and archery). 1.8.1
  fixes the two faults found in the new low-light brightening (an abrupt switch that could make the picture
  flicker, and its cost per frame) and puts the other new tracking aids behind a setting, off by default. The cause
  is inferred from the code, not measured on the phone; whether 1.8.1 is back to 1.7.0's tracking or better is not
  known until it is played. If not, the Motion test screen (a screenshot) is the next thing to look at.

## Device compatibility

| Item | Status |
|---|---|
| Android version | Android 10 (API 29) or newer. Targets Android 15 (API 35). |
| CPU architecture | 64-bit only: `arm64-v8a` (phones) and `x86_64` (emulators/Chromebooks). 32-bit-only phones are not supported. |
| Performance target | Snapdragon 8 Gen 3 or equivalent/better. The app recognises (from `Build.SOC_MODEL`, Android 12+) Snapdragon 8 Gen 3 / 8 Elite / 8 Elite Gen 5, Dimensity 9300 / 9400 and Exynos 2400 / 2500 as meeting the target, and several older chips as below it (shows a note, never refuses to run). Other chips, including Google Tensor, are reported as "not verified". These ratings are from published chip tiers, not measurements. |
| Camera | Any camera (front or back). Rear camera and autofocus are optional. Uses 1280x720 in the camera's low-delay preview mode; asks for 60 fps when the camera offers it and tracking keeps up with about 25 pictures a second, otherwise 30 fps (Settings: "Smoother tracking"). Auto quality keeps the more accurate full pose model unless the phone is too slow (under ~26 per second) or hot. Some phones only allow 60 fps on certain cameras or not at all; the game then stays at 30. "Wide angle" works only where the camera supports a zoom ratio below 1x. |
| Sharp-motion exposure | Needs the camera to allow manual exposure (Camera2 "manual sensor"); many phones allow it on the back camera, fewer on the front. Elsewhere the camera's own exposure is used. Matched to 50 Hz lighting unless the camera reports 60 Hz flicker; under unusual lighting (some LED lamps) the picture may flicker or band - switch it off in Settings -> Motion. Sensitivity is capped, so in a very dim room the exposure gets longer again. Not yet tried on a phone. |
| GPU inference | TensorFlow Lite GPU delegate when the device is on TFLite's GPU compatibility list; otherwise multi-threaded CPU (XNNPACK). If the GPU delegate fails at runtime the app switches to CPU automatically. |
| USB-C video output | Required for a wired TV. **Not all phones have it**, including some flagships. It needs DisplayPort Alt Mode over USB-C; check the phone's specifications. |
| Second screen vs mirroring | The game uses the TV as a separate screen when Android offers it as a presentation display; otherwise it is mirrored. Behaviour depends on the phone maker: Samsung DeX and Motorola Ready For may take over the external display until mirroring is chosen. When mirroring, the TV shows the phone's aspect ratio (usually with black bars). |
| 16 KB memory pages | **Known issue.** TensorFlow Lite 2.16.1's native libraries are aligned for 4 KB pages. Devices that run Android with 16 KB pages may fail to load them (newer Android versions can run such apps in a compatibility mode). Fix: switch to Google's LiteRT packages (16 KB aligned), which are on Google's Maven repository that was not reachable from the build environment. |
| Casting / wireless display | Works as mirroring in principle but adds delay that makes timing games harder. Not tested. |

## Tracking limitations

* **Two players, side by side.** The pipeline processes up to three people (two players plus one person it keeps
  an eye on). Players should stay roughly an arm's length apart; heavy overlap between the two bodies can confuse
  the landmark model.
* **Framing.** Upper body must be in view; hips should be visible for standing players (seated mode does not need
  hips). Players further back (tested down to ~45% of the picture height) are found by the half-frame search;
  smaller players are untested and landmarks get less precise with distance.
* **Look-alike players.** Identity protection uses position, movement continuity, body size and clothing colours.
  Two similar-sized people wearing similar colours who swap places while one is out of view can be mixed up.
* **Lighting.** Dark rooms and strong backlight (window behind the players) reduce tracking quality. With the
  low-light boost (on by default) clearly dark or backlit crops are brightened up to a fixed target before the pose
  and hand models see them, and a brightened search runs when the normal one finds nobody new in a clearly dark
  picture. Crops with normal brightness are not changed. See TEST_REPORT.md for what it measured on a darkened
  photo. Darkening a photo is not
  the same as a dark room: a real camera adds its own noise, blur and auto-exposure, so how far this helps in your
  room is unknown until tried. Brightening adds a little work to each crop (not measured on a phone).
* **Tracking measurements are proxies.** The 1.8.0 tracking numbers come from the real pose models running on still
  photos turned into hard sequences (added noise, darkening, backlight, sway, lunges, jumps). They do not show real
  motion blur, rolling shutter, camera auto-exposure, frame drops or the phone's speed. On a fast sideways sway the
  new settings found both players in fewer frames (87% against 97%), mostly when the test had pushed a player half
  out of the picture, while no longer letting a track jump onto the other player. Tracking is better on these tests,
  not perfect, and "flawless" cannot be shown without testing on the phone.
* **Jump guard** (Extra tracking aids only). When a tracked person's position jumps towards the other player, away from where their own
  movement puts them, the game checks again at the expected spot and keeps the better fit. Fast side-to-side
  movement next to the other player can trigger this check, costing one extra body-model run for that person on
  that frame: on the test sequences up to about 20-25% more model runs while swaying or lunging (none when still).
  What that does to the frame rate on a phone is not measured.
* **Spectators.** A spectator cannot take a player's place while that player is visible. If a player leaves, only a
  person in that player's area who matches their calibrated size and colours is accepted back.
* **Timing allowance.** Timing games judge camera players by when they moved, using the camera's frame timestamps
  (measured automatically) plus a TV allowance that the phone cannot measure by itself: it is a typical 60 ms until
  the timing test has been run. A wrong allowance shifts every judgement the same way (swings seem early or late);
  re-run the test after changing TV or TV picture mode. Some phones' cameras do not report usable timestamps; the
  timing test then also covers the camera's processing time. Not yet tried on a phone or TV.
* **Waiting for late movements.** To be fair to a movement that is still on its way, a missed ball or a goal is
  called a little later than before (up to ~0.45 s, usually ~0.3 s): the ball flies on briefly before "Missed!".
* **Motion prediction** shows your character slightly ahead of the last camera frame (at most 0.12 s) to hide
  delay. Its size was chosen from recorded-pose tests, not tuned on a phone; on very sudden stops a hand can
  overshoot by a few centimetres for a frame or two.
* **Tracking meter** thresholds (how close, how much light) are estimates; the meter is a guide, not a measurement.
* **Simulated input** (Settings -> Developer) is a testing tool with a permanent on-screen banner. It is not
  tracking and proves nothing about cameras.
* **Swing detection** now uses unsmoothed body points and accepts a wrist that is lost for a few frames, the elbow
  when the wrist is not seen, and movement towards the camera (depth, which the pose model estimates less precisely).
  All of this was shaped with simulated bodies and simulated dropouts; real blur and real depth noise may need the
  thresholds adjusting. The Motion test screen shows each hand's swing meter for checking this on a phone.
* **Held objects** are found by colour. A brightly coloured, single-coloured object against a room without that
  colour works best; a brown stick in front of brown furniture, or an object the colour of your clothes or skin,
  may not be learned or followed (learning says so rather than guessing). Thin objects (a pencil-thin stick) are
  hard to see from 2-3 m. Strong colour changes in the room's light (a lamp switched on) can need re-learning.
  Tested on generated pictures only.
* **Finger tracking** (shooting games, archery release, the Motion test) runs the MediaPipe hand landmark model on
  a crop around each player's gun hand. It was checked with the real model on real *still* photos of hands - also
  shrunk to about 70 pixels across, roughly a hand 2.5 m from a 720p camera - where pointing, a fist, a raised thumb
  and two fingers were all read correctly. Live video is different: a hand pointing straight at the camera is
  foreshortened, moving fingers blur, a dim room adds noise, and a hand further away is smaller still. How reliably
  the thumb drop is seen in a real room is unknown until tried; the upward-flick fallback works without fingers.
  It costs extra time per camera frame on the phone's CPU (not measured; shown in the Motion test).
* **Aiming** (crosshairs) uses where your hand is, not exactly where it points: moving the hand a hand's width covers
  the screen. Each player's "straight ahead" is set by their first shot at the centre target. Aim assist pulls the
  crosshair a little onto nearby targets. The box sizes are estimates.
* **Punches** are recognised from the body model's arm speed and its estimate of depth (how far the fist is towards
  the camera), backed up by how short the forearm looks. That depth estimate is the least precise part of the body
  model; a straight punch straight at the camera may be seen less reliably than hooks and uppercuts. Shaped with
  simulated bodies only. Any fast arm movement can look like a punch to the detector, but only Boxing and Darts
  listen for punches.
* **The bow** needs both hands seen; pulling straight back from a bow arm pointed at the camera moves the hand mostly
  in depth, which the camera sees less well than sideways movement - pull back towards your face and a little to the
  side. Release by opening the hand needs the finger tracker to see the drawing hand; letting the hand fly back works
  without it.
* **Toy guns** are short: learning one needs it held pointing up, beside the shoulder, and a bright single-coloured
  toy. A toy gun can hide the fingers, so fire with the recoil flick.
* **Hand pointer**: each hand points from its own side; reaching the far left of the screen is easier with the
  left hand. The pointer box sizes are estimates, not tuned on a phone.

## Game and UI limitations

* **Left/right check.** It relies on the player sliding their hand towards the arrows; if they do something else,
  it asks once more and otherwise leaves the setting alone (use **Flip left/right**). The automatic upside-down fix
  only works if the pose model still finds people in the upside-down picture.
* **Golf swings are read from the front.** The camera sees your hands rise and fall, not the club face, so power
  comes from how high the backswing goes and direction from where the hands come through. Swings were tuned on
  simulated bodies, not real golfers; thresholds may need adjusting after the first real rounds.
* **Golf turns rely on taking turns honestly.** The game cannot tell family members apart by face: whoever is
  closest to the camera when a turn starts (and raises a hand) plays it. Others should stand back.
* **Hand cursors for anyone in view.** Before set-up, cursors use a typical arm reach, so pointing may feel a little
  off until the player is calibrated; spectators who raise a hand can also point.

* **Solo mode difficulty is tuned without real players.** The Easy/Medium/Hard opponents were balanced by playing
  them against each other (Hard beats Medium beats Easy in every game) and by modelling human reaction times and
  hand speeds. How they compare with a real person at the camera - including the tracking delay a person has and
  the computer does not - still has to be tried and may need retuning.
* Solo mode is one person against the computer; there is no two-people-against-the-computer mode, and the Party
  Tournament has no computer players.
* English only.
* The UI is drawn on a canvas, so Android TalkBack cannot read it. Large text, high contrast, reduced effects and
  sound captions are provided instead.
* Player names are typed on an on-screen keyboard (touch, remote or hands-free), maximum 12 characters.
* Tournament: 2-8 players, round-robin pairs, win 3 points, tie 1 point.
* Elf Dance-Off compensates for the audio output latency that Android reports; HDMI/TV audio adds unknown extra
  delay and there is no manual audio-offset setting yet.
* Bowling uses the simplified scoring that was agreed (pin = 1, strike = 15, spare = 13).
* **Boxing** shows your own gloves where your hands are and your opponent's arms as their tracker sees them - a
  cartoon, not a 3D body. Damage, energy and knockdowns are game rules, not a measure of real punch force.
* **Computer opponents in the new games** (shooting, boxing, archery, darts) were balanced against each other in
  automated tests only (Hard beats Easy in 5 or 6 out of 6 matches); against a real person they may need retuning.
* The Christmas games keep their Christmas look in both modes; only Tennis, Table Tennis, Bowling, the goalkeeper
  game and Golf have sports versions.

## Build limitations

* No Android Gradle Plugin (see [BUILDING.md](../BUILDING.md)). Consequences: Android Studio opens the project as
  a plain Gradle/Kotlin project (no "Run app" button; install with `adb`); no Android Lint; no R8 shrinking (the
  APK is ~23.5 MB, mostly models and native libraries); no on-device instrumentation tests.
* Debug-signed only. For distribution, sign with your own release key (not stored in the project).
* In this environment resources were linked against the API 34 platform jar (Ubuntu's aapt2 cannot read API 35);
  `aapt2 dump badging` therefore reports compileSdkVersion 34. Code is compiled against API 35 and the target SDK
  is 35. With Google's build-tools 35 this workaround is not needed.
