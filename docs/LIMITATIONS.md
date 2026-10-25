# Known limitations and device compatibility

## Biggest open item: no measured real-device testing yet

The owner reports that versions 1.0.1 and 1.1.0 install and play on their phone. Nothing has been measured on a
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
  game stutters, turn on **Reduced effects**).

## Device compatibility

| Item | Status |
|---|---|
| Android version | Android 10 (API 29) or newer. Targets Android 15 (API 35). |
| CPU architecture | 64-bit only: `arm64-v8a` (phones) and `x86_64` (emulators/Chromebooks). 32-bit-only phones are not supported. |
| Performance target | Snapdragon 8 Gen 3 or equivalent/better. The app recognises (from `Build.SOC_MODEL`, Android 12+) Snapdragon 8 Gen 3 / 8 Elite / 8 Elite Gen 5, Dimensity 9300 / 9400 and Exynos 2400 / 2500 as meeting the target, and several older chips as below it (shows a note, never refuses to run). Other chips, including Google Tensor, are reported as "not verified". These ratings are from published chip tiers, not measurements. |
| Camera | Any camera (front or back). Rear camera and autofocus are optional. Uses 1280x720 in the camera's low-delay preview mode; asks for 60 fps when the camera offers it and tracking keeps up (in Auto quality the lite model is tried if the full one is too slow for 60 fps), otherwise 30 fps (Settings: "Smoother tracking"). Some phones only allow 60 fps on certain cameras or not at all; the game then stays at 30. "Wide angle" works only where the camera supports a zoom ratio below 1x. |
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
* **Lighting.** Dark rooms and strong backlight (window behind the players) reduce tracking quality.
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

## Game and UI limitations

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

## Build limitations

* No Android Gradle Plugin (see [BUILDING.md](../BUILDING.md)). Consequences: Android Studio opens the project as
  a plain Gradle/Kotlin project (no "Run app" button; install with `adb`); no Android Lint; no R8 shrinking (the
  APK is ~21 MB, mostly models and native libraries); no on-device instrumentation tests.
* Debug-signed only. For distribution, sign with your own release key (not stored in the project).
* In this environment resources were linked against the API 34 platform jar (Ubuntu's aapt2 cannot read API 35);
  `aapt2 dump badging` therefore reports compileSdkVersion 34. Code is compiled against API 35 and the target SDK
  is 35. With Google's build-tools 35 this workaround is not needed.
