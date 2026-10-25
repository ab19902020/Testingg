# Architecture

Two Gradle modules:

* **`:core`** - pure Kotlin, no Android dependencies (compiled against `android.jar` so only APIs that exist on
  Android are used). Pose decoding, multi-person pipeline, identity tracking, calibration, gestures, the ten games,
  menus/UI, tournament, settings, procedural art and synthesised audio. All of it runs and is tested on a normal
  JVM.
* **`:app`** - the Android layer: camera, TFLite inference, rendering to the phone/TV surfaces, audio output,
  display routing, lifecycle and storage. It implements the `Platform` interface that `:core` talks to.

About 11,300 lines of main code and 1,900 lines of tests.

## Threads and data flow

```
Camera2 (ImageReader, newest frame only)            ── camera thread
   │ YUV_420_888 image
   ▼
PoseEngine: MultiPosePipeline + TfliteRunner         ── inference thread (drops frames, never queues)
   │ PoseFrame: up to 3 people, 33 landmarks each, in mirrored/upright frame units
   ▼
GameApp.submitPoseFrame (lock-free hand-off)
   ▼
GameApp.frame()                                      ── render thread, ≤ 60 fps
   ├─ PlayerTracker   → 2 player slots (identity, loss, reacquisition)
   ├─ PlayerControls  → smoothed body frame, gestures, hand positions (per player)
   ├─ Ui              → touch / D-pad / hands-free dwell cursors
   ├─ Screen          → menus, setup, GameScreen
   │     └─ GameSession → fixed 120 Hz MiniGame.update(), pause/resume logic
   └─ Gfx             → AndroidGfx (Canvas) on the TV surface or the phone surface
AudioEngine (mixer)                                  ── audio thread (AudioTrack, float PCM)
```

Inference rate (camera, ~30 Hz) and render rate (up to 60 Hz) are independent. The game simulation steps at a
fixed 120 Hz from accumulated real time, so game speed doesn't depend on either.

## Pose pipeline (`core/pose`)

`MultiPosePipeline` is a Kotlin port of MediaPipe's BlazePose graph, extended to several people:

1. **Detection** (224x224, SSD with 2254 anchors, weighted NMS) over three overlapping search regions: left
   56%, right 56% and the full frame. Half-frame regions make far-away players big enough for the detector.
2. **Region of interest** per person from the detector's alignment key points (rotation-aware), or from the
   previous frame's landmarks once a person is tracked.
3. **Landmarks** (256x256 crop, 39 points + heatmap refinement on the full model), projected back to the frame.
4. **Tracking**: tracked people skip the detector; it only runs every few frames to look for new people
   (`detectIntervalWhenSatisfied`). Duplicate people are merged by key-point overlap.

Camera buffers are sampled directly from YUV with the sensor rotation folded into the sampling transform, so no
full-frame rotated copy is made; mirroring is applied afterwards when converting to frame units. `FrameGeometry` converts to "frame units" (x in [0, aspect], y in [0, 1],
mirrored if selected) which everything downstream uses.

## Identity tracking (`core/tracking`)

`PlayerTracker` keeps two slots (left and right player):

* **Acquisition** only from each player's starting area during setup (largest person in the area).
* **Continuity** each frame: an assignment that minimises a cost from predicted shoulder position, body scale
  ratio, clothing-colour signature and the pipeline's track id; swapping left/right order is penalised.
  Detection list order never matters.
* **Loss**: missing for 0.45 s or low landmark confidence -> LOST with a reason ("out of view", "low confidence").
* **Reacquisition** only for someone in that player's area, of the calibrated size (0.7-1.45x), with matching
  colours, stable for 0.6 s. Other people are tracked as "others" and never fill a slot that is still visible.

**Smoothing** (`SmoothedPose`): a One Euro filter per joint whose speed term is measured in body lengths per
second, so it behaves the same for near and far players. Its speed estimate reacts within a few frames (derivative
cutoff 4 Hz), so the filter opens up as soon as a swing starts. Low-visibility joints get a lower cutoff (steadier);
a joint that moves faster than any body can (40 body lengths per second) is held for one frame, which removes
single-frame landmark glitches. `TrackingQuality` turns a slot's visibility, size, light and the
inference rate into the Excellent-Weak meter and a tip shown on the framing screen.

**Latency compensation** (`PlayerControls.predict`): gesture detection (swings, throws, pumps, poses) uses the
measured (smoothed) frames only. The drawn avatar and the hand positions (used by the reach games and the menu
cursors) are moved ahead along each joint's velocity by 75% of the time since the camera frame, capped at 0.12 s
and 0.35 body lengths, so what players see and touch lines up better with what they are doing.

**Timing by when a movement was made** (`InputTiming`, `GestureEvent.at`): every pose frame carries the camera's
capture time, and a swing is stamped with the moment the wrist moved fastest (looked up in the last 0.25 s when the
swing is recognised). `GameApp` measures the camera-to-game delay for every frame (average and a high estimate);
the display delay (phone + TV) comes from the timing test (`TimingCalibrator`, `TimingTestScreen`) or a 60 ms
default. When `GameSession` hands a camera player's gesture to a game it sets
`at = playTime - (age of the gesture) - displayDelay`: the game moment the player was reacting to. Games use it:

* `RallyGame` (tennis, table tennis) compares `at` with the perfect hit moment, returns the ball from where it was
  at `at` (a 0.6 s history of ball positions), and calls a miss only once `MiniGame.inputGrace` (camera high
  estimate + 60 ms detection slack + display delay, at most 0.45 s) has passed.
* Goalkeepers keep the save window open for `inputGrace` longer; Snowball decides hit/blocked/dodged `inputGrace`
  after the landing; Elf Dance-Off compares poses with the beat shifted by the avatar's remaining lag.
* Computer players and simulated input are judged as their gestures arrive (no allowance).

`Calibration` measures neutral pose, body scale (torso length S), side and upward reach and the playing hand.
Every gesture threshold is expressed in S or in reach units, so the same relative movement works for a child and
an adult.

## Gestures (`core/gesture`)

`PlayerControls` turns a tracked slot into a body-relative frame (One Euro smoothing on joints), then detects:
swings (forehand/backhand, compact variant for table tennis), throws, underarm bowls with aim, arm pumping
cadence, lean, tuck, hands up, raise-and-hold, reach-space hand positions and dance shapes. Each gesture has a
confidence gate, cooldown and hysteresis. Thresholds differ for STANDARD, REDUCED and SEATED modes and scale with
the gesture-sensitivity setting.

Games only see `PlayerInput` (continuous values + gesture events), so all ten games share one tracking and
gesture system.

## Game framework (`core/game`, `core/games`)

* `MiniGame` - `start`, fixed-step `update(dt, inputs)`, `render`, `result`, instruction `demo`, and
  `autoplay(k, input, skill, rng)`: the game's computer player (solo mode's opponent; also used by tests and the
  developer autoplay).
* `GameSession` - instructions -> both players raise a hand -> countdown -> play -> results; pauses when a player
  is lost (0.5 s settle once everyone is back, then a 3 s resume countdown); user pause; TV-loss pause.
* Seeds: each match gets a seed; both players receive identical challenges (presents, shots, gates).
* `RallyGame` is the shared base for Tennis and Table Tennis.

## Solo mode and computer players (`core/game/Cpu.kt`)

* `PlayerTracker.solo`: slot 0 may stand anywhere in the picture; slot 1 is disabled (never acquired, never waited
  for). Switching mode forgets identities and calibrations because the starting areas change.
* `PlayerInfo.cpu` / `cpuSkill`: `GameSession` never fills a CPU player's input from tracking, treats it as always
  ready, never pauses for it, and before every 120 Hz step calls the game's `autoplay` for it. CPU gestures go
  through the same `PlayerInput` path as a camera player's and are valid for that step only.
* `CpuLevel` (Easy/Medium/Hard = skill 0.3/0.6/0.88) and `CpuTraits` (reaction time, hand speed) give every game's
  CPU the same human-like limits. Each CPU sees only what a player sees on screen (e.g. the rally ring after the
  bounce, presents as they appear, the visible stretch of slope) and has per-event errors: timing, aim, missed
  cues, wrong strokes, rhythm drops.
* `BotBody` turns the CPU's actions (hand targets, swing/throw/bowl/shield events, arm pumping, tuck, lean) into
  an `AvatarPose` with two-bone arm IK, so the CPU's character moves like a camera player's. The dance game poses its
  CPU dancer itself (`cpuPosesAvatar`).
* `GameApp.startSolo/leaveSolo` switch modes; Quick Match, the tournament and the full set-up always leave solo.

## UI (`core/ui`)

Immediate-mode UI on a virtual 1080-unit-high canvas (width follows the screen's aspect ratio). Input: touch,
D-pad/keyboard focus, and two hands-free dwell cursors (one per calibrated player). Dwell protection: full hold
time, must leave a button before it can fire again, 0.8 s global lock after any selection, cursors off during
live play.

## Android layer (`app`)

| Class | Role |
|---|---|
| `MainActivity` | Single landscape activity; lifecycle -> camera/pose/audio/render start/stop; permission result; keys |
| `CameraSource` | Camera2, front/back selection and switching, 1280x720 YUV in the low-delay preview template (fast noise reduction and edge modes), 30 or 60 fps range (switched on the running session), zoom-out for wide angle, error/retry, "in use" handling |
| `PoseEngine` | Inference thread, TFLite runner (GPU delegate or XNNPACK CPU), performance snapshot, AUTO quality (prefers whichever model reaches the 60 fps budget of 13 ms per frame; one try of the lite model per session), thermal adaptation and 30/60 fps camera adaptation; resets the pipeline when the camera changes. The camera preview image is made on a separate low-priority thread from a copy of the frame, only while a screen shows it |
| `GameRenderer` | Render thread on a Looper; frames start on the display vsync (Choreographer), ~60 fps on any refresh rate, so no queue of finished frames builds up. Draws to the TV `Presentation` surface or the phone surface; companion touch D-pad on the phone in second-screen mode. Both windows ask for minimal post-processing (HDMI ALLM / game mode, Android 11+) |
| `DisplayRouter` | `DisplayManager` listener; shows/dismisses the `Presentation`; reports TV name/size/refresh |
| `AndroidAudio` | Low-latency `AudioTrack` (float PCM) fed by the core mixer |
| `AndroidGfx` | `Gfx` on `android.graphics.Canvas`; linear and radial gradients use cached unit shaders moved into place with a local matrix, so no shader objects are created per frame |
| `PrefsStore` | `SharedPreferences` for settings, roster and tournament |
| `DeviceCheck` | Chipset vs the performance target (informational) |

## Assets

All artwork is drawn procedurally in code (`core/render/Draw.kt`, game render functions), using flat fills plus
linear and radial gradients and glows for lighting; all music and sound
effects are synthesised at runtime (`core/audio`). The only binary assets are the four BlazePose `.tflite` files.
