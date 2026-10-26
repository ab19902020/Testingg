# Architecture

Two Gradle modules:

* **`:core`** - pure Kotlin, no Android dependencies (compiled against `android.jar` so only APIs that exist on
  Android are used). Pose decoding, multi-person pipeline, finger tracking, identity tracking, calibration,
  gestures, all games (Sports & Arcade and Christmas), menus/UI, tournament, settings, procedural art and
  synthesised audio. All of it runs and is tested on a normal JVM.
* **`:app`** - the Android layer: camera, TFLite inference, rendering to the phone/TV surfaces, audio output,
  display routing, lifecycle and storage. It implements the `Platform` interface that `:core` talks to.

About 20,300 lines of core code, 2,300 lines of Android code and 5,400 lines of tests.

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

**Finger-gun trigger (1.9.0).** `HandShape.thumbLevel` is the thumb tip's distance on the picture from the side of
the index finger's first bone and from the middle finger's middle joint (averaged), in hand widths (knuckle line or
0.6 palm lengths) - flat, because a gun points at the camera and the model's depth is unreliable end-on.
`FingerTrigger` puts it on a 0..1 scale between the player's learned thumb-down and thumb-up averages, cocks above 0.7
(two frames), and fires below 0.3 if the thumb was at the top (0.75, judged on a 5-frame average) within the last
0.4 s; the signal is a median of 3 frames, "open hand" and "may fire" are voted over frames, and the shot is stamped
when the raw signal passed halfway. The design follows headset hand tracking (pinch strength with wide hysteresis;
thumb taps recognised as one quick movement). For an end-on hand the crop's direction comes from the knuckle line
and eases between frames (`HandLandmarkTracker.trackingRoi`).

**Finger tracking (1.7.0).** `HandLandmarkTracker` runs the MediaPipe hand landmark model (224x224 crop, 21
points with relative depth, presence score) on the hands that `GameApp` asks for (`Platform.setHandTracking`: per
player, the gun hand every frame and the other hand every other frame, only while a game sets `usesFingers`). The
first crop comes from the body pose's wrist, index and pinky points (rotated so the fingers point up, 4.6x the
wrist-to-knuckles distance); after that the crop follows the hand's own landmarks as MediaPipe does (2x the box of
the steady landmarks, shifted towards the fingers), falling back to the body pose when the hand is lost. Results are
attached to the person (`RawPose.hands` -> `PersonPose.hands` -> `BodyFrame.fing`). On Android the model runs on the
CPU (XNNPACK, 2 threads) in its own interpreter, created on first use.

**Tracking aids (1.8.0, reworked in 1.8.1).** Each is a switch in `PipelineConfig`, so old and new settings can be
compared. Only the low-light boost is on by default; the rest are switched together by `withExtraAids` (Settings ->
Motion -> Extra tracking aids, off by default; off is the tracking of 1.7.0).

* `autoLevel` / `autoLevelDetector` (low-light boost, on) - `AutoLevel` reads a luminance histogram of each landmark
  or hand crop (every 5th pixel, padding ignored, percentiles interpolated within bins). A crop whose brightest 2%
  are below 0.5 gets a gain bringing them up to 0.5 (at most 4x); if its middle is then still below 0.15, a gamma
  lifts the middle up to 0.15 (at most gamma 0.5). Crops above both targets are untouched, so the correction starts
  from nothing and a brighter crop never comes out darker than a dimmer one. Each tracked person (and hand) keeps
  an `AutoLevel.State` that eases the correction over frames (25% per frame), so the model never sees flicker. The
  curve is applied through a 1024-entry lookup table. The detector first searches the raw picture; only if it finds
  nobody new and the picture is clearly dark (gain or lift of at least 0.4) does it search a brightened copy.
  (1.8.0 switched on abruptly at a threshold - up to ~1.7x brighter either side of it - and ran `pow` per pixel.)
* `retryWider` - a tracked person whose crop fails is tried once more with a crop 1.45x wider before being dropped.
* `smoothRoi` - the next frame's crop follows the landmarks with a gain that rises with movement, so a still player's
  crop (and so their landmarks) is steadier while fast movement is followed at once.
* `keepPresence` - a lower presence score is accepted for someone already tracked than for a new person.
* `guardJumps` / `jumpDev` - each track keeps its measured box and velocity. If a new measurement lands further than
  `jumpDev` (in crop sizes) from where the track's own motion predicts, the landmark model is run again at the
  predicted spot and that result is used if it fits clearly better. With `jumpTargeted` (on) this check only runs
  when the jump moved the track towards another tracked person - the case that swaps players - so ordinary fast
  movement costs no extra model runs. When two tracks end up on one person, the one that jumped is dropped.
* `predictMotion` - moving crops ahead along each person's velocity. Off: it measured worse on jumps and sways.

Hand crops also move with the body pose's wrist between frames (`HandLandmarkTracker`). The measurements behind
these choices are in [TEST_REPORT.md](TEST_REPORT.md) (`TrackingBench`, `RealModelTrackingTest`).

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

**Swings (1.6.0).** `SwingDetector` measures each hand from the *unsmoothed* joints carried in `BodyFrame`
(`rwx/rwy/rwz/rwv`, `rex/rey/rev`) over a 0.22 s window: path and displacement of the wrist (depth weighted 0.45),
the held object's far end (x0.5), or - only while the wrist is not being seen - the elbow (x1.8). Steps are joined
across gaps of up to 0.17 s (blurred frames); a window must be mostly one-directional (displacement >= 55% of the
path) and covered over several frames (no step > 72% of the path), which rejects jitter and one-frame jumps.
Frames up to a detection are never re-used; a hand re-arms when it slows, clearly reverses (backswing -> swing) or
after 0.45 s. The event is stamped with the fastest moment (parabola through the smoothed step speeds). Games set
`PlayerInput.expectSwing` (0..1) while waiting for a swing; `GameSession` copies it to `PlayerControls.swingBoost`,
which lowers the thresholds by up to 40% and enables the "wrist vanished while moving fast" rule.

**Rally games.** `RallyGame` predicts the receiver's perfect moment at launch by flying the ball forward with the
game's own integration steps; every swing between -2.2 and +1.35 timing windows of it is a candidate, and the one
closest to the perfect moment is played once no closer one can still arrive (`decide`). `timingErrors` records how
far each played swing was from the perfect moment.

**Hand pointer.** `HandPointer` (one per player, in `GameApp`) maps the measured - not forward-predicted - hand into
a per-hand box (centre 0.28 reach to that hand's side, 0.05 below the shoulders; half-size 0.62 x 0.45 reach), with
on/off hysteresis (on above -0.58 reach, off below -0.68), a One Euro filter in screen pixels plus a 3 px still
zone, a 0.3 s hold through lost hands, and hand switching only when the other hand is clearly higher. `Ui` keeps a
slipping cursor's ring for 0.35 s and moves it to a new button after 0.12 s there.

**Menu control (1.10.0).** `MenuControl` (in `GameApp`) decides who steers the menus: slot 0 (Player 1, the red
area) after a wave - three changes of direction of a raised hand (above -0.35 reach), each a swing of at least 0.09
reach and within 0.9 s of the last. It releases after 12 s with no hand above the pointer's off line or 2 s without
Player 1 tracked. `GameApp.updateCursors` gives cursor 0 to Player 1 only when the screen does not need a wave
(`Screen.needsWave`, off for a golfer aiming) or control is engaged; cursor 1 only on screens with
`Screen.secondPointer` (none at present). Simulated input and the left/right check grant control.
`GameApp.drawControlPrompt` draws the "wave to take control" pill.

**Steady-hold dwell (1.10.0).** Each `HandCursor` keeps its last 16 positions; its speed is measured over the last
0.15 s (tracking jitter averages out, a real move does not). Below 380 px/s the cursor is steady: the ring fills and
buttons attract it (32 px, sticky to 36 px). Moving, the ring drains at 2.5x the fill rate, there is no magnetism and
no slip grace. The pointer's One Euro filter opens up sooner (min cutoff 1.4 Hz, beta 0.006).

**Finger gun, flick, punches, bow (1.7.0).**
* `HandShape` measures each finger's bend at the middle joint and reach from the wrist in 3D (scale- and
  rotation-free), and the thumb tip's distance from the side of the index finger in palm lengths. A gun = index out,
  ring and pinky in. Values on real photos: straight finger 2-10 deg, curled 140-160 deg; thumb against the hand
  0.1-0.25, raised 0.9+.
* `FingerTrigger`: thumb up (gap > 0.55) for at least 0.08 s, then down (gap < 0.33) while the hand is a gun fires;
  stamped at the moment the gap passed halfway, interpolated between frames.
* `FlickTrigger` (no finger data, e.g. a toy gun): a short, fast upward kick of the wrist that starts from a still,
  raised hand; stamped at the start of the kick.
* `GunControls` keeps per-hand aim points (the knuckles from the hand model, else the wrist plus the last knuckle
  offset), a 24-sample aim history (a shot uses the aim from 50 ms before the trigger), the gun hand and the finger
  mask.
* `PunchDetector`: a burst of wrist speed (3D, depth x0.7) judged when it ends: towards the camera (depth, forearm
  foreshortening, elbow rising) = straight, across from the side = hook, upwards = uppercut; pulling back is never a
  punch; a fast reversal splits wind-up and punch. Guard = both fists up in front of the face.
* `BowDetector`: hands together = nock; the dominant hand pulls away (2D + 0.6 x depth) = draw 0..1; release by
  opening the drawing hand (finger tracker), a fast fly-back of the drawing hand, or (reduced/seated) holding a good
  draw still for 1 s; hands back together = let down.
* `AimCursor` (`core/game/Aim.kt`) maps a hand's aim point into a crosshair like a touch pad in the air (0.34 x 0.22
  reach units to the screen edges; smaller for gentle modes), with a One Euro filter, a centre set by each player's
  first shot, an aim-assist offset and a 2 s history so shots are judged where the crosshair was when they were made.

## Golf (`core/golf`, `core/gesture/GolfSwing.kt`, `core/ui/screens/Golf*.kt`)

* `GolfSwingDetector` (run by `PlayerControls` while `golfStroke` is FULL or PUTT) follows the hands - the midpoint
  of both wrists weighted by visibility - in body units: grip (both wrists seen, under 0.42 body units apart),
  address (gripped, low, still), backswing (peak height = power, or sideways distance for putts; hands may be lost
  for up to 0.5 s), downswing (impact at the lowest point; minimum speed, else a practice swing), with thresholds
  scaled by reach and movement mode.
* `GolfScene3D` draws the hole in perspective with the ordinary 2D drawing calls: `Cam3` (eye, look-at, no roll)
  projects ground polygons (rough, fairway strips and joints, mowing stripes, green, bunkers, water) clipped at the
  near plane and fogged with distance, then depth-sorted billboards (pine trees from the hole's tree stands plus a
  framing forest, flag, tee markers, stakes, the ball with its trail and shadow, and a snowman golfer whose arms
  and club swing in a tilted plane along the target line by an angle taken from the player's hands). Camera views:
  behind the ball while aiming (with the planned flight dotted through the air), a blend into a camera riding
  behind the ball in flight, a low putting view, and a high overview between turns.
* `GolfCourse` holds the holes (tee, pin, green radius and slope, fairway centre line and width, bunkers, water,
  trees, boundary, wind); `GolfPhysics` turns club, lie, aim, power and offset into a carry with wind and spread, a
  roll that can find hazards or the hole, and simulates putts on the sloping green. `GolfMatch` runs stroke play
  for 1-4 players (whole hole per turn, honour order, penalties, pick-up at par + 4, gimme putts, computer golfers
  that read putts by trying lines on the real green).
* `GolfScreen` handles turns: `GameApp.startTurnTracking()` uses single-player tracking; for each new golfer
  `nextTurnPerson()` forgets the last one, the closest person is picked up, `calibrateTurnPerson()` measures them
  quickly and locks on, and a raised hand starts the turn. Club and aim buttons use the ordinary hand cursors, which
  are switched off while the swing detector sees an address or backswing.

**Left/right and upside-down** (`DirectionCheck`, `Settings.mirrorByCamera`/`upsideDownCameras`): mirroring and a
180-degree correction are stored per camera id and applied by `PoseEngine` (`mirror`, `upsideDown`). The first
raised hand on an unchecked camera starts a two-step check (slide towards a right arrow, then a left arrow; hand
movement measured in frame coordinates with cursors off); two reversed results flip the camera's mirroring. Every
camera frame is also checked for people whose shoulders are below their hips (upside-down picture).

**Magnetic cursors** (`Ui.hitMagnetic`): a cursor within 60 px of a button points at it, and stays on its button
until 70 px outside it.

**Held objects** (`core/pose/HeldObjectTracker.kt`). Runs on the camera thread inside `MultiPosePipeline` after the
landmarks. Learning: with the hand raised, 21 directions within 50 degrees of straight up are tried; for each, a
narrow strip from the hand is sampled against strips either side, and the most uniform, most different strip is the
object. Its colours (18 hues x 4 saturations x 4 brightnesses + 8 greys, with lighting neighbours) are gathered
against the flanks, a ring around the hand and the player's own body points over 24 frames; the model is the
per-bin share of object colour (`ObjectColorModel`, saved per player profile as a hex string). Tracking: a 64 x 64
grid around each hand is classified by the model, cells connected to the hand form the patch (one-cell gaps
bridged), and its principal axis and 95th-percentile reach give the direction and tip; patches that are too short,
too round or too wide are rejected. Results travel as `RawPose.held` -> `PersonPose.held` (mirrored like the
landmarks) -> `BodyFrame.obj*` -> `AvatarPose.propAngle` (drawn rackets and bats) and the swing detector.

**Hand cursors** are shown for every tracked person (calibrated or not; uncalibrated players use a typical reach).
**Expected people:** `Platform.setExpectedPeople` (1 in solo/golf, 2 otherwise) sets the pipeline's
`desiredPoses`/`maxPoses` live, so with one player the person-detector runs every few frames instead of every frame.

## Game framework (`core/game`, `core/games`)

* `MiniGame` - `start`, fixed-step `update(dt, inputs)`, `render`, `result`, instruction `demo`, and
  `autoplay(k, input, skill, rng)`: the game's computer player (solo mode's opponent; also used by tests and the
  developer autoplay).
* `GameSession` - instructions -> both players raise a hand -> countdown -> play -> results; pauses when a player
  is lost (0.5 s settle once everyone is back, then a 3 s resume countdown); user pause; TV-loss pause.
* Seeds: each match gets a seed; both players receive identical challenges (presents, shots, gates).
* `RallyGame` is the shared base for Tennis and Table Tennis.
* `GameRegistry` lists every game with its **mode** (Sports & Arcade or Christmas) and **style**; Tennis, Table
  Tennis, Bowling and the goalkeeper game appear in both modes under different ids (`tennis` / `tennis_s` ...), and
  `GameEnv.style` tells the game which look to draw (`Draw.person` = snowman buddy or athlete). The chosen mode is a
  setting; menus, solo and tournaments list `GameRegistry.forMode(app.mode)`.
* Games that can use a real object declare `GameInfo.objectKind` (racket, toy gun; golf uses club); objects are
  stored per player and kind. `GameApp.objectKind` / `useObject` decide which learned colour model the camera
  thread looks for.
* 3D scenes (Clay Shooting, Archery, Golf) use `Cam3` (`core/render/Cam3.kt`), a pinhole camera projecting into the
  same 2D drawing API.
* Shared-target shooting (Clay Shooting, Shooting Gallery): a hit goes to whoever's shot was *made* first; a shot
  arriving later but made earlier takes the target over within 0.3 s.

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
D-pad/keyboard focus, and hands-free dwell cursors (Player 1's after a wave on menus; see Menu control above).
Dwell protection: full hold time held steady, must leave a button before it can fire again, 0.8 s global lock after
any selection, cursors off during live play.

## Rendering (1.11.0, `core/render`)

* **Cached layers.** `Gfx.layer(key, l, t, r, b, version, res, blur, grain) { ... }` draws ordinary drawing code once
  into an offscreen picture (an ARGB `Bitmap` on Android via a software `Canvas`; a `BufferedImage` in tests) and then
  draws that picture each frame. It is rebuilt when the key, version or pixel size changes; an LRU keeps about 36 MB
  (`AndroidGfx.LAYER_BUDGET`). Resolution never exceeds the virtual canvas (1920 x 1080 on a TV). `ImageOps.blur`
  (three box passes on premultiplied colour; edges clamp for opaque backdrops and fade for sprites) and
  `ImageOps.grain` run once when a picture is made. Layers inside layers draw directly.
* **Sprites and frames.** The same call under a `translate`/`scale` caches a sprite at a reference size (pine trees,
  leafy trees, clouds, the ferris wheel). `Scenery.frames` shows a few cached frames in turn (crowds, light chases).
  Colour variants (fog) are quantised into a few steps so only a handful of pictures exist.
* **Blend modes.** `Gfx.setBlend(ADD | SCREEN | MULTIPLY)` (saved with `save`/`restore`) maps to
  `android.graphics.BlendMode.PLUS/SCREEN/MULTIPLY`; glowing particles, lamps and beams add light.
* **Gradients in any direction** (`fillPolygonLinear`) for side-lit shirts and metal.
* **Level of detail.** Small snowmen (`Draw.buddy` under 90 px) and small trees use simple shapes.
* **Shared scenery** (`Scenery`): day sky, clouds, layered mountains and tree line, crowds, night sky, winter forest,
  light helpers.
* **Cost guard.** `CostGfx` counts drawing operations, points, large fills, blended draws and cached pictures made;
  `DrawBudgetTest` plays every game with computer players and fails if a busy frame (95th percentile) exceeds 1300
  operations or more than 60 pictures are made in 20 s (a picture remade every frame). `DrawCost`
  (-Pclrg.drawcost) and `CharShots` (-Pclrg.charshots) are developer tools.

**Tracking tip during play (1.11.0).** `TrackingCoach` (one per `GameScreen`) watches each camera player's
`TrackingQuality`; weak (score < 0.5 with a tip) for 2 s shows the tip on that player's side until it has been good
for 1 s.

## Sound (`core/audio`)

* `Dsp` - noise colours, biquad filters, a Freeverb-style reverb, resonator banks, a table sine and a limiter.
* `SfxSynth` - every effect is a layered stereo recipe rendered at start-up (menu sounds first, so menus are never
  silent while the rest render), loudness-normalised with a weighting for small speakers and stored as 16-bit.
  Each `Sfx` carries its level, reverb send, pitch variation and whether it ducks the music.
* `Ambience` / `AmbienceGen` - live background beds per venue (arena, park, winter, fireside, lanes), generated as
  they play, with their own volume; `Soundscape` picks the ambience and `Room` (reverb size) for each game.
* `AudioEngine` - the stereo mixer: voices with pan and pitch, one reverb bus set by the current room, music ducking
  under big moments, and a master limiter. All of it runs on the JVM, so `AudioTest` checks levels, clipping,
  stereo width, headroom, ambience and ducking without a device.

## Android layer (`app`)

| Class | Role |
|---|---|
| `MainActivity` | Single landscape activity; lifecycle -> camera/pose/audio/render start/stop; permission result; keys |
| `CameraSource` | Camera2, front/back selection and switching, 1280x720 YUV in the low-delay preview template (fast noise reduction and edge modes), 30 or 60 fps range (switched on the running session), zoom-out for wide angle, error/retry, "in use" handling. Sharp-motion exposure: when the camera has the manual-sensor capability, after auto exposure settles (1.5 s) the same exposure x sensitivity is set up with a flicker-matched 10 ms (or 8.3 ms for 60 Hz) exposure and the sensitivity capped at twice the analogue maximum (3200 at most); a 4 Hz loop keeps the measured frame brightness at the auto-exposure reference, lengthens the exposure only as a last resort, returns to auto on large light changes, and gives up for the session if the camera refuses |
| `PoseEngine` | Inference thread, TFLite runner (GPU delegate or XNNPACK CPU), performance snapshot, AUTO quality (the full model unless it takes over 38 ms per picture or the phone is hot; back to full when lite is under 16 ms for 10 s), thermal adaptation and 30/60 fps camera adaptation (60 when offered, not warm and tracking under 40 ms per picture); resets the pipeline when the camera changes; measures each frame's brightness for the exposure loop; hosts the held-object tracker. The camera preview image is made on a separate low-priority thread from a copy of the frame, only while a screen shows it |
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
