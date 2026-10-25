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

* `MiniGame` - `start`, fixed-step `update(dt, inputs)`, `render`, `result`, instruction `demo`, and a scripted
  `autoplay` used only by tests and developer mode.
* `GameSession` - instructions -> both players raise a hand -> countdown -> play -> results; pauses when a player
  is lost (0.5 s settle once everyone is back, then a 3 s resume countdown); user pause; TV-loss pause.
* Seeds: each match gets a seed; both players receive identical challenges (presents, shots, gates).
* `RallyGame` is the shared base for Tennis and Table Tennis.

## UI (`core/ui`)

Immediate-mode UI on a virtual 1080-unit-high canvas (width follows the screen's aspect ratio). Input: touch,
D-pad/keyboard focus, and two hands-free dwell cursors (one per calibrated player). Dwell protection: full hold
time, must leave a button before it can fire again, 0.8 s global lock after any selection, cursors off during
live play.

## Android layer (`app`)

| Class | Role |
|---|---|
| `MainActivity` | Single landscape activity; lifecycle -> camera/pose/audio/render start/stop; permission result; keys |
| `CameraSource` | Camera2, front/back selection, 1280x720 YUV, 30 fps range, zoom-out for wide angle, error/retry, "in use" handling |
| `PoseEngine` | Inference thread, TFLite runner (GPU delegate or XNNPACK CPU), preview image for setup screens, performance snapshot, AUTO quality and thermal adaptation |
| `GameRenderer` | Render thread; draws to the TV `Presentation` surface or the phone surface; companion touch D-pad on the phone in second-screen mode |
| `DisplayRouter` | `DisplayManager` listener; shows/dismisses the `Presentation`; reports TV name/size/refresh |
| `AndroidAudio` | Low-latency `AudioTrack` (float PCM) fed by the core mixer |
| `AndroidGfx` | `Gfx` on `android.graphics.Canvas` |
| `PrefsStore` | `SharedPreferences` for settings, roster and tournament |
| `DeviceCheck` | Chipset vs the performance target (informational) |

## Assets

All artwork is drawn procedurally in code (`core/render/Draw.kt`, game render functions); all music and sound
effects are synthesised at runtime (`core/audio`). The only binary assets are the four BlazePose `.tflite` files.
