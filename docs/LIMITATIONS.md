# Known limitations and device compatibility

## Biggest open item: no real-device testing yet

The app has not been installed on a phone, connected to a TV or played by real people. The build environment had
no Android device and no emulator. Until the checklist in [TEST_REPORT.md](TEST_REPORT.md) has been run:

* live camera tracking, gesture recognition with real bodies and identity stability in a real room are unproven;
* frame rates, latency and heat on any phone are unknown (no numbers are claimed anywhere);
* USB-C/HDMI output, second-screen (Presentation) mode and cable unplug handling are unproven on hardware;
* audio output and latency over HDMI are unproven;
* gesture thresholds were tuned with synthetic motion and will probably need adjusting after the first real
  sessions.

## Device compatibility

| Item | Status |
|---|---|
| Android version | Android 10 (API 29) or newer. Targets Android 15 (API 35). |
| CPU architecture | 64-bit only: `arm64-v8a` (phones) and `x86_64` (emulators/Chromebooks). 32-bit-only phones are not supported. |
| Performance target | Snapdragon 8 Gen 3 or equivalent/better. The app recognises (from `Build.SOC_MODEL`, Android 12+) Snapdragon 8 Gen 3 / 8 Elite / 8 Elite Gen 5, Dimensity 9300 / 9400 and Exynos 2400 / 2500 as meeting the target, and several older chips as below it (shows a note, never refuses to run). Other chips, including Google Tensor, are reported as "not verified". These ratings are from published chip tiers, not measurements. |
| Camera | Any camera (front or back). Rear camera and autofocus are optional. Uses 1280x720 at 30 fps where offered. "Wide angle" works only where the camera supports a zoom ratio below 1x. |
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
* **Simulated input** (Settings -> Developer) is a testing tool with a permanent on-screen banner. It is not
  tracking and proves nothing about cameras.

## Game and UI limitations

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
