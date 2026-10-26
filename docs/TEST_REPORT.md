# Test report

Date: 2026-10-26 (version 1.11.0). Environment: Linux cloud container, OpenJDK 21, Gradle 8.14.3. **No Android phone, emulator
(no KVM) or TV was available here.** The only real-device evidence is the owner's reports that versions 1.0.1 to
1.6.0 run on their phone, with their comments on how well tracking works (section 4). Everything else that needs a device is marked **UNVERIFIED**.

## Summary

| Area | Result |
|---|---|
| Automated JVM tests | **238 passed, 0 failed, 10 skipped** (the skipped ones are developer tools: diagnostics, screenshots, a sound dump, the tracking benchmark, the hand-model probe and a trace) (`./gradlew :core:test` with the real-model environment configured) |
| Pose pipeline with the real BlazePose models (on still images, JVM) | **Passed** - matches official MediaPipe output |
| Finger tracking with the real MediaPipe hand model (on still photos of hands, JVM) | **Passed** - finger shapes read correctly, also on hands shrunk to ~70 px; hands found from the body pose on a real two-person photo |
| APK build, signature, contents | **Passed** - built, aligned, v2+v3 signed, verified |
| App launches and runs on Android | **Reported working by the owner** (1.0.1 on their phone, after the camera crash fix); not tested here |
| Live camera tracking of real people | Owner reports the game working; tracking quality not measured - **UNVERIFIED** in detail |
| Solo mode against the computer (1.1.0) | Passed with simulated input and CPU-vs-CPU matches; owner reports it working |
| Camera switching, motion smoothing/prediction, 60 fps camera, new graphics (1.2.0) | Passed in JVM tests (simulated input, synthetic motion, test renders); **not yet tried on a phone** |
| Lag compensation, timing test, lower-delay camera/drawing, bigger tennis court (1.3.0) | Passed in JVM tests with simulated people and simulated camera/TV delays; the Android parts (vsync drawing, camera preview mode, TV game-mode request, preview thread, Auto model choice) build but are **not yet tried on a phone or TV** |
| Christmas Golf, hand cursors before set-up, one-player tracking load (1.4.0) | Passed in JVM tests with simulated bodies; **not yet tried with a real camera** |
| Left/right check, upside-down fix, new Present Catch, magnetic cursors (1.5.0) | Passed in JVM tests with a simulated mirrored / upside-down camera and simulated bodies; owner reports 1.5.0 runs |
| Swing detection rework, tennis rules, hand pointer, 3D golf with grip, held objects, motion test, sharp-motion exposure (1.6.0) | Passed in JVM tests with simulated bodies, simulated tracking dropouts and jitter, and generated pictures (held objects); the camera exposure code builds but is **not yet tried on a phone**; none of 1.6.0 has been tried with a real camera yet |
| Sports & Arcade mode, finger guns, recoil flick, punches, bow, Clay Shooting, Boxing, Archery, Shooting Gallery, Darts, Penalty Save, sports looks, toy-gun objects (1.7.0) | Passed in JVM tests with simulated bodies, scripted game input and simulated app flows; finger shapes also with the real hand model on still photos. The hand model's Android runner builds but is **not yet tried on a phone**; none of 1.7.0 has been tried with a real camera yet |
| New sound, menus and game screens, graphics pass, golf upgrade (1.8.0) | Passed in JVM tests: sound rendering, levels, stereo, headroom, ambience and ducking measured on the JVM; screens and games rendered with Java2D and checked by eye; golf matches with simulated players. How the sound comes out of a phone or TV speaker and how smoothly the heavier graphics draw on a phone are **not yet tried** |
| Tracking aids: dark-room search, brightened crops, jump guard, steadier crops (1.8.0) | Passed with the **real pose models on a real two-person photo turned into hard sequences** (noise, darkening, backlight, sway, lunges, jumps): better in most sequences, worse in one (section 1). A proxy for a camera: **not yet tried with a live camera** |
| Tracking fix after the owner's report that 1.8.0 tracks worse (1.8.1) | Low-light boost rebuilt (no jump at a threshold, smoothed per player, lookup table) and checked on the JVM; real pose models on photo sequences: in normal light the default now tracks **identically to 1.7.0**, and dark rooms are still found. **Not yet tried on the owner's phone** - whether this is the whole cause is not known |
| Finger-gun trigger rebuilt, steadier hand crop, archery anchor, steady tuck, Motion test graph (1.9.0) | Passed with the **real hand model's landmarks of a real photo turned to point at the camera** (12 angles, squashed depth, frame-to-frame wobble) and with simulated bodies; the real lite and full hand models read the thumb clearly on real photos shrunk to living-room size. **Not yet tried with a live camera** |
| Player 1 waves to take control of the menus, hold-still selection, quicker pointer, golf grip with a hidden wrist, golf graph, gallery and arena graphics (1.10.0) | Passed in JVM tests with simulated bodies (whole app: two players, waves, raised hands, slow pointing), scripted cursor paths with jitter, and simulated golf swings at 30 and 15 pictures a second; screens rendered with Java2D and checked by eye. **Not yet tried with a live camera** |
| Graphics overhaul with cached scenery, new characters, lighting and effects; in-game tracking tip (1.11.0) | Passed in JVM tests: every game played by computer players with its drawing work counted (54% less in total than 1.10.0, all games under the budget test), cached-picture reuse checked, blur/grain/blend checked pixel by pixel, screens rendered with Java2D and checked by eye. How smoothly it draws on a phone or TV is **not yet tried** |
| On-device performance (fps, latency, thermal) | **UNVERIFIED** - no numbers measured; none are claimed |
| TV output over USB-C/HDMI, Presentation mode, cable unplug | **UNVERIFIED** |
| Audio output and latency | **UNVERIFIED** |

Important: the game tests drive the games with **scripted, simulated body input**. They prove the rules,
scoring, gesture logic, identity logic, menus and flows work as programmed. They do **not** prove that real camera
tracking works, how fast it is on a phone, or that the TV output works.

## 1. Automated tests (248: 238 passed, 10 skipped)

Run: `./gradlew :core:test` with the real-model validation environment configured
(`tools/pose-validation/run_real_model_tests.sh` sets it up). 13 of the tests use the real pose and hand models and are
skipped without that environment. The 8 skipped tests are developer tools that run only when asked: two
diagnostics, a sound dump, two screenshot tools, the tracking benchmark, the hand-model probe and a finger-gun trace.

### Graphics and drawing cost (new in 1.11.0)

The owner asked for much better graphics without making the game laggy or breaking the motion controls. The
approach: draw detailed, static scenery once into cached pictures and reuse them (see ARCHITECTURE.md, Rendering).
What was checked:

* `LayerTest` (5 tests): a cached picture is drawn once and reused, redrawn for a new version; its content lands in
  the same place as drawing directly (at half resolution too); the blur keeps transparent edges white (no dark
  fringe) and keeps total brightness; grain changes pixels by a few levels only and leaves transparent pixels alone;
  "add" brightens, "multiply" darkens and `restore` returns to normal drawing.
* `DrawBudgetTest`: every game played for 20 s by computer players, each frame's drawing counted with `CostGfx`; no
  busy frame (95th percentile) above 1300 operations, and no game makes more than 60 cached pictures (a picture remade
  every frame would make about 600).
* `TrackingCoachTest` (2 tests): a weak-tracking tip appears only after 2 s, stays a moment, goes once tracking is
  good; short dips and computer players never show one.
* Drawing work per frame, before (1.10.0) and after, counted over 40 s of computer play per game at 30 frames a second
  (`DrawCost` tool). **A stand-in for frame cost, not a frame-rate measurement**: a phone's real cost also depends on
  how many pixels are filled, which was not measured. Cached pictures count as one operation each:

  | Game | Mean ops before | Mean ops after | Change | Busiest (p95) before | Busiest (p95) after | Pictures made |
  |---|---|---|---|---|---|---|
| Christmas Tennis | 1,673 | 388 | -77% | 1678 | 391 | 7 |
| Snowball Showdown | 1,089 | 499 | -54% | 1133 | 541 | 3 |
| Present Catch | 1,114 | 564 | -49% | 1186 | 635 | 4 |
| Reindeer Racing | 2,197 | 707 | -68% | 2468 | 873 | 5 |
| Festive Bowling | 711 | 435 | -39% | 796 | 520 | 2 |
| Table Tennis (Christmas) | 445 | 434 | -2% | 449 | 438 | 4 |
| Elf Dance-Off | 494 | 536 | +9% | 572 | 614 | 1 |
| Sleigh Slalom | 2,892 | 1,063 | -63% | 3060 | 1132 | 18 |
| Christmas Goalkeepers | 890 | 608 | -32% | 994 | 712 | 10 |
| Decorate the Tree | 414 | 256 | -38% | 478 | 320 | 2 |
| Clay Shooting | 364 | 141 | -61% | 428 | 204 | 8 |
| Boxing | 819 | 339 | -59% | 876 | 399 | 2 |
| Archery | 395 | 174 | -56% | 402 | 181 | 27 |
| Shooting Gallery | 697 | 342 | -51% | 786 | 422 | 12 |
| Darts | 588 | 149 | -75% | 596 | 165 | 1 |
| Penalty Save (Sports) | 456 | 559 | +23% | 566 | 671 | 10 |
| Tennis (Sports) | 952 | 281 | -70% | 955 | 284 | 10 |
| Table Tennis (Sports) | 866 | 273 | -68% | 870 | 278 | 7 |
| Bowling (Sports) | 571 | 376 | -34% | 656 | 461 | 2 |
| **All games** | **17,627** | **8,124** | **-54%** | | | |

* Characters keep every joint where it was (same arm, hand, head and prop positions), so the avatars copy movement
  exactly as before; the gesture code is unchanged in this version (all gesture tests pass unchanged).
* Screens rendered with Java2D at 1920 x 1080 and checked by eye: every game, the menus and the golf aiming view.

What this does not show: frame rate, smoothness, memory pressure or heat on a phone, or how the new look reads on a
TV across the room. These need the phone.

### Menus and golf grip (new in 1.10.0)

The owner asked that only Player 1 (the red player) controls the menus after waving at the screen, that a hand
hovering by accident should not choose, that the pointer should move away faster, and that golf should pick up the
swing.

* `MenuControlTest` (3 tests, the whole app with two simulated players): raised hands alone give no pointer; a wave
  from the blue player gives no control and Player 2 never gets a menu pointer; a wave from the red player gives
  control and a pointer; a short rest keeps control and about 12 s with hands down gives it up; raising a hand,
  pointing around slowly and scratching the head are not a wave.
* `UiDwellTest` (3 new tests, 10 in all): a cursor sweeping back and forth across a long button for 5 s never
  selects it (the ring stays under 30%); a ring 2/3 full empties completely while the hand moves away over a quarter
  of a second, nothing chosen; a held hand trembling by about 6 px every frame still selects. The other tests (hold
  time, edges and wobble, the lock after a selection, two players) still pass.
* `GolfSwingTest` (3 new tests): a clasped grip with one wrist barely seen (visibility 0.12) from the start addresses
  and swings (it did not before the change - the test was checked failing first); a downswing lost to blur from
  halfway down until just after impact is still struck at 30 and 15 pictures a second, with its power; a quick
  downswing (0.12 s and 0.2 s) at 15 pictures a second is seen.
* Screens: the "wave to take control" prompt, the golf graph, the new Shooting Gallery booth and the Table Tennis
  arena were rendered with the screenshot tools and checked by eye.

What this does not show: how big and how fast real people wave, how still a real hand is when held over a button,
or how the clasped grip looks to the body model on the owner's camera. These need the phone.

### Finger guns, bow and tuck (new in 1.9.0)

The owner reported on 1.8.1 that the gun games don't recognise the thumb going down, and asked for every game to
react quickly without over-reacting. Approach taken from published hand-tracking practice (searched): headset pinch
detectors use a 0..1 strength with separate on/off thresholds far apart (e.g. Ultraleap's pinch/unpinch distances,
Meta's grab-strength states entered above 0.75 and left below 0.25); Meta's thumb "microgestures" (thumb tapping the
side of the index finger) are recognised as one smooth, moderately quick movement, and very slow ones are not;
research event detectors fire on a sharp drop in a smoothed finger-distance signal, with a cooldown.

* `HandProbe` (developer tool): the **real** MediaPipe hand landmark models (lite and full) on MediaPipe's real photos
  of a raised thumb, a pointing hand (thumb down) and a fist, shrunk so the hands are ~22, ~33 and ~50 px across (2.5
  m from a 720p camera is ~33 px), with camera noise and a wobbling crop, 16 tries each. Raised and lowered thumbs are
  told apart clearly at every size by both models (d' over 20 on still photos). So on still, side-on photos, hand
  size is not the problem; what changed is how the thumb is measured and judged.
* `FingerTriggerEndOnTest` (7 tests, plus a developer trace): the full hand model's 3D landmarks of `pointing_up.jpg`, with the thumb swung
  out to "cocked" and the hand turned to point at the camera at 12 angles (tilt 0/20/35 deg, roll 0/45/90/135 deg),
  depth squashed x0.4 like the model's, every landmark wobbling (5% of a hand width per frame; 8% in the held tests),
  30 fps. One finger: 48/48 thumb drops fired (the trigger up to 1.8.1: 33/48), each within 0.06 s of when the thumb
  passed halfway; two fingers: 48/48 (old: 23/48); a 0.35 s unhurried drop fires at every angle; a player whose thumb
  rises only half way and does not go fully down: 47/48. No shots with the thumb held up or down, little twitches, a
  one-frame misread every 0.8 s, or the hand turning 90 deg slowly; a thumb drifting down over 3 s never fires while
  it is still up (at some angles once, when it is well down). Hand-crop direction wobble: 3.6 deg average (MediaPipe's
  wrist-to-knuckles method: 10.3 deg), under 12 deg at every angle.
* `FingerGunTest` (5, simulated side-on hands, updated), `BowTest.aQuickDrawIsNotARelease` (a 0.25 s pull to full draw
  fired an arrow before the fix; now only the release after the anchor does), `GestureTest.tuckHoldsSteady` (hands
  wavering around the old edge: no flicker; a wrist hidden for 0.15 s keeps the tuck).
* These are a model of a finger gun seen by a camera, built from real hand-model output - not a camera. Motion blur,
  the phone's real frame rate and how the model really reads an end-on hand in a living room can only be seen there;
  the Motion test graph is there to show it.

### Tracking fix (new in 1.8.1)

The owner reported that 1.8.0 tracked worse than before in every game they tried (moves missed, wrong triggers, at
2-3 m in normal conditions). Nothing in the gesture or player-tracking code changed in 1.8.0; the change that ran on
every frame was the new low-light brightening, and two faults were found in it by reading and measuring it:

* it switched on abruptly: crops just darker than its threshold were made up to ~1.7 times brighter than crops just
  lighter, so a room near the threshold could flicker between the two from frame to frame (by the measure below, a
  0.5% change in light could change the model's input brightness by 0.21);
* it was slow: 3.7 ms per dark 256x256 body crop on this machine's JVM (a `pow` per pixel), probably several times
  that on a phone, per player - possibly enough for the game to switch to the lite body model (not measured on a
  phone).

These are inferred causes, not measured on the phone. Tests:

* `AutoLevelTest` (8 passed): normal and fairly dim crops untouched; dark crops and a dark figure against a window
  brightened; padding handled; **no jump** - stepping the light in 0.5% steps from very dark to bright, the largest
  change in the model's input brightness is 0.011; a tracked crop eases into the boost over several frames; the
  lookup table matches the exact curve within 0.004; a dark 256x256 crop takes about 0.5 ms (1.8.0: 3.7 ms, same
  machine).
* `RealModelTrackingTest` (3 passed, real BlazePose full models): with the default settings a normally lit noisy
  picture gives the same joint jitter as 1.7.0 (0.0388 both: the boost stays out of the way); a dark room (about 2.7
  stops under) finds both players in 16/16 frames (1.7.0: 0/16); with the extra aids switched on, the fast sway
  never jumps a track onto the other player (1.7.0: once) and the noisy picture's jitter is 0.0267.
* `TrackingBench` on the real two-person photo (same measures as the 1.8.0 table below), 1.7.0 against the 1.8.1
  default and the 1.8.1 default with the extra aids on:

  | Sequence | 1.7.0 | 1.8.1 default | 1.8.1 + extra aids |
  |---|---|---|---|
  | still, noise, backlit, fast sway, lunges, jumping, walking in, dropped frames | (baseline) | **identical to 1.7.0** | as the 1.8.0 table below |
  | dim (30% light): error / jitter | 0.0509 / 0.0497 | 0.0437 / 0.0359 | 0.0427 / 0.0304 |
  | dark (15%): both found | 0% | 100% (error 0.0410) | 100% (error 0.0453) |
  | very dark (9%): both found / error | 75% / 0.0576 | 75% / 0.0474 | 75% / 0.0464 |
  | dark and swaying: both found | 57% | 97% | 97% |
  | fast sway: both found | 97% | 97% | 87% |

  On the generated two-person scene every sequence found both people in 100% of frames with all three settings.
  Same caveat as below: these are photos made into sequences, not a camera.

### Sound, screens, golf and tracking (new in 1.8.0)

* `AudioTest` (9 passed, plus the skipped dump tool) - every one of the 77 effects has a sensible length, does not
  clip, is not near-silent and ends without a click, and the whole bank fits in 14 MiB; rendering is repeatable;
  crowds and rooms are really stereo; a busy mix (many effects at once over music and ambience) stays inside full
  scale; background sounds sit at a quiet level (-50 to -22 dB) and fade in and out cleanly; bigger rooms ring
  longer; big moments dip the music; asking for effects before the bank has rendered is safe. All measured on the
  rendered samples on the JVM - not heard through a phone or TV speaker.
* Screens and games: rendered with Java2D by the developer screenshot tools and checked by eye
  ([docs/images/screens_1_8_0.jpg](images/screens_1_8_0.jpg): menus, game list, instructions, boxing, results, golf
  flyover and play, Sleigh Slalom, Reindeer Racing, clays, darts, bowling, snowballs). The existing game and flow
  tests drive all of them with simulated players. `GolfMatchTest` now also plays the 9-hole tour.
* `AutoLevelTest` (4 passed) - well-lit crops are left alone; dark crops are brightened; a dark figure in front of a
  bright window is lifted; the detector's input range and black padding are handled.
* `RealModelTrackingTest` (3 passed, **real BlazePose full models** on MediaPipe's Apache-2.0 photo of two people,
  turned into sequences):

  | Test | 1.7.0 settings | 1.8.0 settings |
  |---|---|---|
  | Dark room (picture darkened about 2.7 stops, with noise): frames with both players found | 0 / 16 | 16 / 16 |
  | Fast side-by-side sway: times a track jumped clearly onto the other person | 1 | 0 |
  | Noisy still picture: joint jitter (fraction of the picture height) | 0.0388 | 0.0267 |

* `TrackingBench` (developer tool, `-Pclrg.trackbench=1`) - *(measured with 1.8.0's brightening, before the 1.8.1
  fix; in 1.8.1 these aids are the "Extra tracking aids" setting)* - the same real models on 12 harder sequences made from the
  same photo. Found = frames with both people found; error = average key-joint distance from MediaPipe's own result on the
  untouched photo (moved and scaled along with the picture), as a fraction of the picture size; jitter = how much
  the joints wander on the still sequences (standard deviation, fraction of the picture height); ids = track ids used (2 is
  ideal); runs = body-model runs per frame (cost):

  | Sequence | 1.7.0: found / error / jitter / ids / runs | 1.8.0: found / error / jitter / ids / runs |
  |---|---|---|
  | still | 100% / 0.0206 / 0.0238 / 2 / 2.0 | 100% / 0.0213 / 0.0204 / 2 / 2.0 |
  | noise | 100% / 0.0299 / 0.0405 / 3 / 2.0 | 100% / 0.0288 / 0.0223 / 2 / 2.0 |
  | dim (30% light) | 100% / 0.0509 / 0.0497 / 3 / 2.1 | 100% / 0.0331 / 0.0300 / 2 / 2.0 |
  | dark (15%) | **0%** / 0.0256 (one person) / - / 1 / 1.0 | **100%** / 0.0384 / 0.0231 / 2 / 2.0 |
  | very dark (9%) | 75% / 0.0576 / 0.0242 / 2 / 1.8 | 75% / 0.0430 / 0.0216 / 2 / 1.8 |
  | backlit (bright window) | 100% / 0.0607 / 0.0540 / 3 / 2.1 | 100% / 0.0607 / 0.0355 / 2 / 2.0 |
  | fast sway | 97% / 0.0372 / - / 4 / 2.1 | **87%** / 0.0391 / - / 4 / 2.2 |
  | lunges | 93% / 0.0366 / - / 6 / 2.1 | 97% / 0.0314 / - / 6 / 2.3 |
  | jumping | 100% / 0.0248 / - / 2 / 2.0 | 100% / 0.0223 / - / 2 / 2.0 |
  | walking towards the camera | 100% / 0.0269 / - / 2 / 2.0 | 100% / 0.0281 / - / 2 / 2.0 |
  | dropped frames | 100% / 0.0222 / - / 4 / 1.8 | 100% / 0.0218 / - / 4 / 1.9 |
  | dark and swaying | 57% / 0.0494 / - / 3 / 1.6 | **97%** / 0.0438 / - / 4 / 2.1 |

  Better on most sequences; **worse on fast sway** (87% against 97% of frames with both found - the lost frames are
  where the sequence pushes a player partly out of the picture) and slightly worse on walking towards the camera
  (error 0.0281 against 0.0269). On a second, generated two-person scene every sequence found both people in 100% of
  frames with both settings; there the jump guard costs up to 2.4 runs per frame in fast sway (1.7.0: 2.0) and 2.6
  in lunges (2.1). These sequences are made by moving, darkening and adding noise to a still photo: they have no
  motion blur, rolling shutter, auto-exposure or real sensor noise, and say nothing about speed on a phone. **They
  are a stand-in for a camera, not proof that tracking works in a real room.** Movement prediction of crops was also
  measured and left off (worse on jumping and sway).

### Finger guns, punches, the bow and the new games (new in 1.7.0)

* `RealModelHandTest` (4 passed, **real MediaPipe hand landmark model (lite)** run through the Kotlin crop, decode
  and tracking code, on MediaPipe's Apache-2.0 test photos of hands). Bend at the middle joint of index, middle,
  ring and little finger (0 = straight), and the thumb's distance from the side of the index finger (palm lengths):

  | Photo | Bends (deg) | Thumb gap | Read as |
  |---|---|---|---|
  | pointing up, full size | 3, 147, 142, 140 | 0.25 | gun, thumb down |
  | pointing up, shrunk to ~70 px | 3, 148, 147, 149 | 0.24 | gun, thumb down |
  | fist, full / shrunk | 148-158 for all | 0.15 / 0.13 | not a gun |
  | two fingers, full / shrunk | 9, 4, 162, 160 / 7, 2, 162, 160 | 0.45 / 0.42 | two-finger gun |
  | thumb up, full / ~75 px | 146-178 | 1.10 / 1.11 | thumb raised, index curled |

  A sideways pointing hand: first crop (not turned) presence 0.72; after following the hand's own landmarks for
  three frames the crop turned 73 degrees, presence 1.00, read as pointing. A photo without hands: no hand. On the
  real two-person photo (`man-woman-okay.jpg`), crops from the body pose found 3 hands with presence 0.99-1.00, the
  hand model's wrist 13-49 px from the body model's wrist (shoulder width ~160 px). Still photos only: no motion
  blur, no live camera, no foreshortened finger pointing straight at the lens.
* `FingerGunTest` (5 passed, simulated hands): three thumb clicks give exactly three shots, each stamped within
  40 ms of the moment the thumb passed halfway down; a thumb that stays down, a fist opening and closing its thumb,
  and five seconds of a cocked gun with camera jitter give no shot; without finger data a quick upward kick of the
  hand fires once, stamped at the start of the kick with the aim from before it, while slowly raising the aim and
  raising the arm from the side do not; the gun hand is the one making the gun, and the finger tracker is asked for
  that hand every frame and the other (raised) hand every other frame.
* `PunchTest` (5 passed, simulated bodies): guard seen and lost; a punch towards the camera is a straight, and
  pulling it back is not a punch; a wind-up to the side then a swing across is a hook; driving up from below the
  chest is an uppercut; slow reaches and waving with jitter give nothing; reduced mode takes a shorter, slower punch.
* `BowTest` (5 passed, simulated bodies): hands together nock; pulling back gives a full draw (>85%); holding still
  does not release in standard mode; letting the drawing hand fly back releases at full power (>80%) aimed with the
  bow hand; opening the fingers of a closed drawing hand releases a partial draw (41%); slowly bringing the hands
  back together puts the arrow down; a seated archer releases by holding still; the drawing hand's fingers are
  tracked every frame while drawing.
* `ClayShootingTest` (8 passed, scripted input): the first shot sets "straight ahead" and uses no shell; a shot on a
  clay breaks it, a wide one misses; a shot judged when it was made (0.3 s before it reached the game) hits, the
  same shot judged on arrival misses; a shot made 50 ms earlier but arriving a frame later takes the clay from the
  other player; two shells then a reload; seated players' crosshair resting on a clay fires; Hard beat Easy in 6 of
  6 matches (first match 44-5 points).
* `BoxingTest` (6 passed): a clean punch lands; guard blocks the head but not the body; leaning slips a straight,
  ducking slips a straight but an uppercut catches a ducking boxer harder; after ten punches in a second a punch
  does less than 75% of a fresh one's damage; knockdowns, getting up after the count, KO after three; Hard beat
  Easy in 6 of 6 (first match 258-67, a knockout).
* `ArcheryTest` (5 passed): ring scoring; a full draw aimed at the centre scores 10, a half draw drops 0.15 m+;
  wind drifts the arrow downwind on the second end; nine arrows each, then the match ends; Hard beat Easy in 6 of 6
  (first match 79-58).
* `GalleryAndDartsTest` (6 passed): first shot made wins a gallery target; six shots then a 1 s reload; dartboard
  scoring (bull, 25, T20, D20, the numbers at 3, 6, 9 o'clock); a dart lands where the sight was held steady even
  though the throw drags the hand away, and a floppy throw drops; Hard beat Easy in 5-6 of 6 in both (first matches
  49-14 gallery, 251-141 darts). The hard gallery computer was toned down from an unbeatable 138 hits to 28.
* `AllGamesTest` (6 passed, updated): all 19 registered games (10 Christmas, 9 Sports & Arcade) play to a valid
  result; ids unique; the active player beats an idle one in 14 games, including all the new ones; seated and
  reduced-movement players can finish the new games.
* `SportsFlowTest` (4 passed, simulated input, through the real app): a new console opens in Sports & Arcade; the
  mode switch is remembered; the sports game list shows the nine sports games and no Christmas ones; from Clay
  Shooting's start screen a player teaches a toy gun, comes back to the same start screen with finger tracking still
  on, can switch between finger gun and toy gun, and plays the match to the results (finger tracking off again
  afterwards); golf in sports mode is the summer course with the club object; Boxing and Archery play through.

### Swings, pointing and held objects (new in 1.6.0 - simulated bodies, simulated dropouts, generated pictures)

* `SwingRobustnessTest` (8 passed): a quick backswing out to the side followed by the forehand gives both swings,
  the last one stamped at the forehand's fastest moment (within 40 ms); a swing whose wrist is lost for two frames
  in the middle counts once; with the wrist never seen, the elbow carries the swing (reported as "elbow"); a forehand
  that is mostly towards the camera (0.5 body units across, 1.5 towards the TV) counts, while the same sideways
  movement without depth does not; a wrist placed elsewhere for single frames every third of a second is no swing;
  a gentle 0.5-unit swing counts only while a ball is arriving; a wrist and elbow that vanish mid-swing while a
  swing is expected still count; four seconds of standing still with 3x the usual jitter and maximum sensitivity
  give no swing.
* `HeldObjectSwingTest` (2 passed): with the arm blurred out of view, a swing is seen through the held object (and
  reported as "object"); the drawn racket follows the object (wrist cocked 1.2 rad away from the forearm) and goes
  back to the forearm when the object is put down.
* `SwingTimingTest` (2 passed, updated): with the unsmoothed joints, a parabola through the step speeds, and one more
  picture awaited when a swing is recognised while the hand is still speeding up, swings of 0.2-0.45 s at 30 and
  60 fps are stamped within 17 ms of the moment the hand moved fastest (-2 to +17 ms) and recognised 8-50 ms after
  it (1.5.0: 17-42 ms).
* `GestureTest` (18 passed, one updated): waving a hand six times a second for two seconds gives at most one swing
  per stroke (13 strokes), where 1.5.0 counted at most one swing per 0.35 s.
* `HandPointerTest` (7 passed): every corner and the bottom row of the screen are reached with the hand between the
  chest and the face and no further than 0.4 reach across the body, with the pointer on; a still hand with
  tracking noise of 1% of body size keeps the pointer within a 2.0 px spread; a hand sagging up and down at the
  bottom of the screen never switches the pointer off; a hand lost for 0.15 s keeps its pointer; a deliberate move
  across half the screen is followed within 50 ms of the hand arriving; arms by the sides give no pointer; holding
  over a bottom-right button with jitter chooses it in under 2 s.
* `HeldObjectTrackerTest` (5 passed, generated 1280x720 pictures of a room with a person and a 12 px yellow stick):
  holding the stick up teaches its colours and length (0.9 body units, within 0.25); the model survives being saved;
  the stick is then found at seven angles from straight up to out to either side within 1 degree and 0.02 body
  units at the tip; a stick smeared over 24 degrees (motion blur) is still found within 1 degree; an empty hand
  gives nothing; a stick the colour of the wall is refused with an explanation.
* `MotionScreensTest` (2 passed): the object screen starts learning for the person in front of the camera after its
  countdown, shows the learned object being followed before it is kept, keeps it for that player and then uses it;
  the motion test screen counts a forehand and shows the golf grip. `PointerLookTest` renders the pointer half-way
  through a choice.
* `TennisGameTest` (12 passed, updated rules): perfect timing keeps the rally going; 0.62 s early sails out, 0.42 s
  late hits the net; a quarter of a second either way still returns; swings at the top of the bounce (0.45 s early)
  return early in a rally; a backswing 0.6 s early followed by a swing on time plays the swing on time every time;
  a player swinging every 0.3 s whatever the ball does still returns balls; the perfect moment is known before the
  bounce and the receiver's detection is made more sensitive as the ball arrives; first to 7, auto-serve, rematch.
* `LatencyCompensationTest` (3 passed, updated): swings made on time on a TV 0.1 s late with camera frames 0.16 s
  late are judged 22 ms early on average with compensation, and 265 ms late without (the "my swing is always late"
  problem); with the more forgiving 1.6.0 rules lateness now costs shot quality rather than whole points.
* `GolfSwingTest` (7 passed, 2 new): hands low in front but apart give no address, and the guide reports them apart;
  together, they grip and address; both wrists lost for 0.35 s at the top of the backswing do not cancel the swing,
  which strikes with the backswing's power.

### Pose pipeline - fixtures from the real models (`BlazePoseFixtureTest`, 5 passed)
Decoding checked against raw tensors exported from the real models: anchor count (2254), detector decoding,
landmark decoding, crop sampler vs OpenCV `warpAffine`, half-frame detections agree with full-frame ones.

### Orientation and pipeline logic (`OrientationAndPipelineTest`, 7 passed)
Rotation formula for front and back cameras; upright-to-buffer mapping for 0/90/180/270 degrees; mirroring puts the
person's left hand on screen left; YUV -> RGB conversion; detector runs once then tracking takes over; person who
leaves is dropped and comes back with a new track; with one person in view, expecting one player runs the
person-detector 7 times in 24 frames instead of 25 (new in 1.4.0, scripted model).

### Real models, real images (`RealModelPipelineTest`, 6 passed)
The app's Kotlin multi-person pipeline running the actual `.tflite` models (inference via a Python TFLite helper)
on two-person images built from MediaPipe's public test photos, compared with the official MediaPipe
`PoseLandmarker` run on each person separately. Error = mean of |dx|+|dy| / 2 over shoulders, elbows, wrists and
hips that MediaPipe marks visible, in fractions of image width/height.

| Scene | Lite model | Full model |
|---|---|---|
| A: two standing players (~80% frame height) | 2 found; errors 0.0016, 0.0017 | 2 found; 0.0016, 0.0017 |
| B: arms-out pose + standing player | 2 found; 0.0015, 0.0022 | 2 found; 0.0019, 0.0013 |
| E: real two-person photo, close-up (hips out of frame) | 2 found; 0.0186, 0.0464 | 2 found; 0.0242, 0.0196 |
| D: empty room | 0 people | 0 people |
| F: two players far back (~45% height) | | half-frame search finds 2; full-frame-only search finds 1 |

Also passed: a 90-degree rotated camera buffer gives the same landmarks (within 8 px); the YUV camera path matches
the RGB path (within 10 px); over 20 frames both track ids stay stable, both players are acquired from their own
areas in mirrored view, and the detector ran 6 times (40 landmark runs) instead of every frame.

Limits of this evidence: still images, desktop TFLite (not the Android TFLite/GPU delegate), no motion blur, no
real living-room lighting, no camera noise.

### Motion quality (`MotionQualityTest`, 5 passed - synthetic motion, new in 1.2.0)
Smoothing lag (in body lengths) is the same for a player close to the camera and a player far back; a one-frame
wrist "teleport" is ignored while a lasting change is followed; render-time prediction moves a moving hand ahead
along its motion and adds no drift when the player is still; a crouch shows on the character and standing up
returns it to normal; the tracking meter rates a clear, well-sized player highly with no tip, and gives the right
tip when a hand is out of the picture and when tracking is slow.

The 6 real-model tests above were re-run after the smoothing changes with the same results (they test the
landmark pipeline, which smoothing does not change).

### Left and right (new in 1.5.0 - simulated cameras)

* `DirectionCheckTest` (3 passed): a simulated phone whose camera delivers the picture already mirrored (so the
  pose model also labels the hands the other way, as it would on such a phone): the first raised hand starts the
  check, the person slides their hand to their right and then their left as the arrows ask, the game flips the
  picture for that camera and saves it, and afterwards moving the hand right moves the cursor right. A normal camera
  is checked once and left as it is (no second check on the next start). A camera delivering the picture upside
  down is turned the right way up within a few seconds.
* `UiDwellTest` now 7 tests: a hand 25 px off a button still selects it, and wobbling 30 px either side of a
  button's edge does not restart the ring.

### Golf (new in 1.4.0 - simulated bodies)

* `GolfSwingTest` (5 passed): a full backswing (hands ~1.35 body lengths up) gives full power and a half one about
  half (1.00 and 0.47, at both 30 and 60 fps), from either side; hands coming through 0.45 body lengths to
  one side send the ball that way; a slow downswing is a practice swing and reaching up to pick a club is not a
  swing; putts of 0.15 and 0.5 body lengths back give a short and a long putt; reduced-movement mode needs only a
  chest-high swing; no shot when golf is not active.
* `GolfMatchTest` (5 passed): every hole has its tee and pin in the right place; on all three courses a skilled
  computer golfer scores near par and better than a beginner over four rounds (Candy Cane 11.3 vs 12.8, Frozen
  Lake 12.3 vs 15.3, North Pole 12.3 vs 15.3, par 12); with four golfers each plays a whole hole before the next
  starts and the best score tees off first on the next hole; water costs one stroke and the ball is replayed from
  the same spot; a putt aimed straight at the hole on a slope misses while a read putt finishes in or next to it;
  club distances, half power, off-line swings and the rough behave as described.
* `GolfFlowTest` (1 passed): without any touch, from the main menu: a simulated person raises a hand to open Golf,
  chooses the golfers (deselects Player 2, adds Sprout) and tees off by hovering, steps up and raises a hand to
  start the turn, changes club twice by hovering, swings (address, backswing, downswing) and the ball flies over
  110 m; the round is then finished by the developer autoplay, the scorecard is complete, and "Main menu" (by
  hovering) restores two-player tracking.

### Timing and lag (new in 1.3.0 - simulated people and simulated delays)

* `LatencyCompensationTest` (3 passed): the whole chain - simulated camera frames, tracker, gestures, match flow,
  tennis - with a player who swings exactly when the ball reaches them *on their TV*, frames arriving 0.16 s after
  capture and a TV 0.1 s late. With the delays allowed for, none of their swings is judged late, early or missed
  over 45 s of rallies; with the allowance switched off, the same swings go into the net (at least 3 lost points -
  the problem the owner reported); with no delay, nothing changes.
* `DelayAllowanceTest` (2 passed): with the same delays, a goalkeeper whose hand reaches each target exactly as they
  see the ball arrive saves 8 of 8 (as with no delay at all); with the allowance off, 0 of 8. A snowball target who
  raises the shield 0.1 s before they see the ball land blocks 3 of 3; with the allowance off, 0 of 3.
* `SwingTimingTest` (2 passed): swings of 0.2, 0.3 and 0.45 s at 30 and 60 fps are stamped within 17 ms of the
  moment the hand moved fastest, and recognised 17-42 ms after it (1.2.0: 33-75 ms, measured with the same
  synthetic swings). The quicker smoothing adds under 15% jitter to a hand held still (measured: +8%).
* `TimingTestFlowTest` (2 passed): the timing test through the real app loop in camera mode: a simulated player
  with a 140 ms TV delay and 120 ms camera delay is measured at 150 ms (within the 25 ms tolerance; the camera part
  is taken from the frame timestamps) and the value is saved and used. Too few or too scattered swings are refused;
  one stray swing does not move the result.

These use synthetic bodies and assumed delays; the real delays of a phone and TV have not been measured.

### Identity tracking (`PlayerTrackerTest`, 13 passed - synthetic poses)
Players assigned from their starting areas; identities stable when detection order changes every frame; players
standing close together do not swap; brief occlusion bridged without pausing; low confidence triggers loss with a
reason; long loss then reacquisition without swapping; both lost and back to the right slots; spectator in the
background ignored; spectator beside a player never replaces them; a different-sized or differently dressed
person cannot take over a lost player; child and adult tracked side by side; seated player with hidden hips
tracked; setup mode releases a player who leaves their area.

### Gestures (`GestureTest`, 18 passed - synthetic motion)
Forehand/backhand for right- and left-handers; slow arm movement is not a swing; standing still with tracker
jitter produces no actions; cooldown and hysteresis prevent repeated triggers; the same body-relative swing works
for small and large players; reduced-movement mode accepts smaller swings; throw detected and not confused with
lowering an arm; underarm bowl with aim; seated bowl from lap height; arm-pump speed depends on cadence, not
amplitude (also in reduced mode); leaning; hands-up edge; raise-and-hold fires once; reach space uses calibrated
reach; dance shapes distinguishable; calibration measures reach and playing hand.

### Games (`TennisGameTest` 7, `TennisScreenshots` 1, `AllGamesTest` 6 - scripted players)
Tennis: perfect timing keeps the rally going; far too early -> out, far too late -> net; slightly off still
returns; first to 7 with alternating serve; a receiver who never swings loses the point; auto-serve; rematch starts
fresh. All ten games: the registry has exactly the ten agreed games; every game finishes with a valid result; an
active player beats an idle one; timed games end on time; identical players get identical challenges (fairness,
fixed seeds); seated and reduced modes still playable.

### App flows (`AppFlowTest`, 4 passed - simulated input)
Quick match from main menu through setup, calibration, instructions, countdown, play, results and rematch; tracking
loss pauses the match and resumes after the countdown; party tournament rotates pairs and persists across a
restart of the app object; TV unplugged mid-match pauses with a notice and freezes the game until resumed;
backgrounding pauses the match and returning does not restart play by itself. (The display and lifecycle events
are injected by the test; the Android side that produces them is not exercised.)

### Computer opponents (`CpuOpponentTest`, 4 passed)
Every game is playable against every level (CPU against CPU through the real match flow); the computer player never
causes a tracking pause while the camera player does; the CPU's character animates. Difficulty ordering, from 8
matches per pairing (both sides swapped):

| Game | Hard beats Easy | Medium beats Easy | Hard beats Medium |
|---|---|---|---|
| Tennis (1.6.0 rules) | 1.00 | 1.00 | 0.75 |
| Snowball Showdown | 0.88 | 0.88 | 0.69 |
| Present Catch (1.5.0 rebuild) | 0.94 | 0.63 | 0.88 |
| Reindeer Racing | 1.00 | 1.00 | 1.00 |
| Festive Bowling | 0.75 | 1.00 | 0.75 |
| Table Tennis | 1.00 | 1.00 | 1.00 |
| Elf Dance-Off | 1.00 | 1.00 | 1.00 |
| Sleigh Slalom | 0.88 | 0.75 | 1.00 |
| Goalkeepers | 1.00 | 0.94 | 0.81 |
| Decorate the Tree | 1.00 | 1.00 | 1.00 |

This shows the levels are ordered; it does not show how they compare with a real person at the camera.

### Solo flow (`SoloFlowTest`, 2 passed - simulated input)
Main menu -> Play Solo -> choose Hard -> game -> one-person framing, calibration (no panel for the CPU) and start
gesture -> match with the CPU (both sides score) -> results -> another solo game keeps the calibration -> Quick
Match switches back to two people. Quick Match's "Just me" button switches to solo with one person.

### Camera switching (`CameraSwitchTest`, 2 passed - simulated input, new in 1.2.0)
The main-menu camera button switches front <-> back and the choice is saved; the pause-menu switch during a match
changes camera, resets calibration and returns to the "Stand in your areas" screen, then the match carries on.
(The Android camera itself is not exercised: the test checks which camera the game asks for.)

### Crash report (`CrashReportFlowTest`, 2 passed)
A report about a previous crash is shown first at startup, long reports page through, nothing is shared until
Share is tapped, Continue returns to the main menu; a "body tracking could not start" message appears on camera
screens when the platform reports one.

### Hands-free dwell menus (`UiDwellTest`, 6 passed)
Selection only after the full hold time, with progress shown; sweeping a hand across buttons never selects;
keeping the hand on a button selects it once and it must leave before selecting again; after one player selects,
the other player's cursor is locked for 0.8 s and must then dwell again in full; lowering the hand resets
progress; no selection while cursors are off (live gameplay) or on disabled buttons.

Screens rendered by these tests (Java2D, simulated input, not device screenshots):
[docs/images/test_render_contact_sheet.jpg](images/test_render_contact_sheet.jpg) (earlier versions) and
[docs/images/screens_1_8_0.jpg](images/screens_1_8_0.jpg) (1.8.0).

## 2. APK checks (passed)

| Check | Result |
|---|---|
| File | `dist/LivingRoomGames-debug.apk`, 23,704,792 bytes, SHA-256 `d524cb8ebe4798d8a6f73ebe5029523c62a2068171fd0dafa437727bc3d81cd3` |
| Package / version | `com.clrg.christmasgames` (unchanged, so it installs over earlier versions), versionCode 14, versionName 1.11.0, label "Living Room Games" |
| SDK levels | minSdk 29 (Android 10), targetSdk 35 |
| Signature | `apksigner verify --min-sdk-version 24`: APK Signature Scheme v2 and v3 verified, 1 signer (`CN=Android Debug`, certificate SHA-256 `af5122d7549be77841521095d8fb1cd74f0ddf5410a4a76286c93b377221836e`) |
| Reproducibility | Two clean builds (`./gradlew clean :app:assembleDebugApk`) produced byte-identical APKs with the same debug key |
| Alignment | `zipalign -p 4` |
| Code | single `classes.dex`, 1,804 classes (game, Android layer, TFLite Java API, Kotlin stdlib) |
| Models | 5 `.tflite` files (4 pose + 1 hand landmark), stored uncompressed (memory-mappable); the build downloads and checks each against a SHA-256 |
| Native libs | TFLite + GPU delegate for `arm64-v8a` and `x86_64` |
| Permissions | `CAMERA` only. No `INTERNET` (enforced by the build) |
| Features | `camera.any` required; rear camera and autofocus optional |

## 3. Requirement checks

| Requirement | Status | Evidence |
|---|---|---|
| Two people detected simultaneously with real models | Passed (still images) / UNVERIFIED (live camera) | RealModelPipelineTest |
| Shoulders, elbows, wrists, hips located accurately | Passed (still images) | errors ~0.002 vs MediaPipe |
| Identity by position/continuity, not list order | Passed (synthetic) | PlayerTrackerTest |
| Spectators can't take over | Passed (synthetic) | PlayerTrackerTest |
| Tracking loss: fair pause, names the player, reacquire, 3-s countdown | Passed (simulated) | AppFlowTest, PlayerTrackerTest |
| Mirroring, landscape, rotation, coordinates | Passed (unit + real model) / UNVERIFIED on device sensors | Orientation tests |
| Front/rear camera selection and in-game switching | Game side passed (simulated) / Android camera switching UNVERIFIED | CameraSwitchTest, CameraSource |
| 60 fps camera when the phone keeps up | Implemented / UNVERIFIED | CameraSource, PoseEngine |
| Body-normalised thresholds, smoothing, cooldown, hysteresis | Passed (synthetic) | GestureTest |
| Left-handed, reduced-movement, seated | Passed (synthetic) | GestureTest, AllGamesTest |
| Ten complete games with instructions, countdown, scoring, results, rematch | Passed (scripted) | AllGamesTest, AppFlowTest |
| Tournament pair rotation + persistent scores | Passed | AppFlowTest |
| Hands-free dwell menus with accidental-activation protection | Passed (unit) / UNVERIFIED with real hands | UiDwellTest |
| Only Player 1 steers the menus, after a wave | Passed (simulated bodies, whole app) / UNVERIFIED with real people | MenuControlTest |
| Richer graphics without heavier frames | Passed (drawing work counted per game, budget test) / smoothness on a phone UNVERIFIED | DrawBudgetTest, LayerTest, DrawCost |
| Debug overlays (skeleton, performance) | Implemented / UNVERIFIED on device | Settings -> Developer |
| Camera fps, inference latency, render fps, tracking-loss counters | Implemented (overlay) / no measurements yet | DevOverlay |
| Camera keeps running while TV shows the game | Implemented / UNVERIFIED | DisplayRouter, GameRenderer |
| TV connect/disconnect, Presentation, mirroring | Game reaction passed (injected events) / Android display handling UNVERIFIED | AppFlowTest, DisplayRouter |
| Permission prompt, denial, settings link | Implemented / UNVERIFIED | PermissionScreen, MainActivity |
| Backgrounding, phone call, camera taken by another app | Game reaction passed (injected events) / Android lifecycle UNVERIFIED | AppFlowTest, MainActivity, CameraSource |
| Thermal adaptation | Implemented / UNVERIFIED | PoseEngine, GameRenderer |
| Offline after install | Passed (APK contents: models bundled, no INTERNET permission) | verifyApkContents |
| No recording / upload | Passed by construction (no storage of frames, no network permission) / code review only | |
| Sports & Arcade and Christmas modes; game lists, solo and tournaments per mode | Passed (simulated input) | SportsFlowTest, AllGamesTest |
| Boxing 1v1, archery, clay shooting (first hit wins / most hits), more mini games | Passed (scripted) / UNVERIFIED with real bodies | BoxingTest, ArcheryTest, ClayShootingTest, GalleryAndDartsTest |
| 3D where it fits (clays, archery, golf) | Implemented (`Cam3` projection) / frame rate on a phone UNVERIFIED | test renders |
| Finger gun: thumb click fires | Passed (real hand model on still photos for the shapes; simulated hands for the click) / UNVERIFIED live | RealModelHandTest, FingerGunTest |
| Toy gun (or other object) added before a game | Passed (simulated input; learning itself shares the 1.6.0 held-object code tested on generated pictures) / UNVERIFIED with a real toy | SportsFlowTest, HeldObjectTrackerTest |

## 4. Field reports

| Report | Version | Status |
|---|---|---|
| "Keep improving the graphics, take it to the next level graphically, as it's not that impressive at the moment, but do not make it laggy or anything, or destroy the motion capture controls. If you can improve the motion capture for these games, please do as well." - owner | 1.10.0 | 1.11.0: cached detailed scenery for every game and the menus, new shaded characters, light blending, depth blur and grain, new effects, a proper golfer; total drawing work per frame down 54% (build-machine count, not a frame rate); joints and gesture code unchanged; a tracking tip during play when a player's tracking stays weak. **Not yet confirmed on the phone.** |
| "Only player one can control the main menu screen ... anyone who's in the red player will be player one ... it should say wave at the screen to get full control ... it's selecting a bit too quickly if I'm not able to control it ... if it's hovering above something when I don't want it, I've got to move my hand and it's too slow at moving ... the golf game wasn't picking up my swing properly ... make the graphics even better" - owner | 1.9.0 | 1.10.0: Player 1 (red area) waves to take control and is the only one with a menu pointer; the ring fills only while the hand is held still and empties quickly when it moves; the pointer follows movement sooner; golf accepts a clasped grip with one wrist hidden (a likely reason the swing never started); golf graph in the Motion test; new Shooting Gallery and Table Tennis arena graphics. **Not yet confirmed on the phone.** |
| "Motion tracking still not working on the gun game. It doesn't recognise my thumb going down to shoot ... very reactive, but doesn't overreact ... the gun should be your hand pointing out with your two fingers pointing out and your thumb up; when your thumb goes down, it shoots ... look how other games do it" - owner | 1.8.1 | Cause found: fixed 3D limits and a strict shape check that a gun pointing at the camera cannot pass. 1.9.0 rebuilds the trigger (flat measure, learned per player, wide on/off gap, quick-movement rule - as in headset hand tracking), steadies the hand crop, fixes a bow that fired during a quick draw and a flickering sleigh tuck, and adds a live graph to the Motion test. **Not yet confirmed on the phone.** |
| "Motion tracking is not working at all really well with anything - bowling, skiing, archery, all doesn't work great" - owner; asked further: moves missed and wrong triggers, worse than before, standing 2-3 m away | 1.8.0 | Two faults found in 1.8.0's new low-light brightening (abrupt switching that can make the picture flicker; cost per frame), fixed in 1.8.1; the other new tracking aids are now off by default (Settings -> Motion -> Extra tracking aids), so in normal light 1.8.1 tracks as 1.7.0. The Motion test now shows whether the phone is holding tracking back. **Not yet confirmed on the phone.** |
| "Improve the UI menu and game screens, make golf better, massive graphics improvement on every mini game, massive sound FX upgrade ... then more upgrades for the camera tracking, we need it flawless" - owner | 1.7.0 | 1.8.0 rebuilds the sound, menus and in-game screens, improves the graphics of the mini games, upgrades golf and adds tracking aids for dark rooms, backlight and players swapping tracks (section 1). Tracking was measured with the real models on photo sequences only; "flawless" cannot be shown without the phone. **Not yet confirmed on the phone.** |
| "It works better now. We're not perfect ... I don't want it now to be purely a Christmas game - like a Nintendo Wii ... boxing 1v1, archery, shooting with your hand like a gun, clay pigeon shooting, first to hit gets the point or most things out of the sky ... 3D where it can be ... add objects like a fake gun before a game, or use your fingers with the thumb clicking down to shoot" - owner | 1.6.0 | 1.7.0 adds the Sports & Arcade mode (Christmas kept as its own mode), finger tracking with the thumb trigger, Clay Shooting (first to hit, and a flurry round), Boxing, Archery, Shooting Gallery, Darts, Penalty Save, sports versions of tennis, table tennis, bowling and golf, and teaching a toy gun or racket from a game's start screen. **Not yet confirmed on the phone.** |
| "The tennis game's no good - the ball goes past you even when you're swinging ... golf needs to be 3D, tell you to put your hands together ... add your own objects (a stick as the racket) ... the motion tracker: you've got to concentrate and move your hand all the time" - owner | 1.5.0 | Causes found in code: a swing before the bounce was ignored and an early swing (or a backswing) used up the shot and blocked the real swing for 0.35 s; fast arms were read from smoothed points and missed when the wrist blurred; the pointer's bottom row sat where it switched off, edges needed a stretch across the body, and forward prediction made it overshoot. 1.6.0 reworks swing detection and the rally rules, rebuilds the pointer, puts golf in 3D with a grip, adds held objects, the motion test screen and a sharper camera exposure. **Not yet confirmed on the phone.** |
| "It's reverse mirroring - your hands go the opposite way to you moving them on the screen ... the present game is crap" - owner | 1.4.0 | The camera picture on the owner's phone is most likely delivered already mirrored for the camera they use, so the game's own mirroring reversed it. 1.5.0 detects and fixes this per camera (left/right check), adds Flip left/right, and rebuilds Present Catch. **Not yet confirmed on the phone.** |
| "It's all good, just keep improving the motion tracking" - owner, after 1.3.0 | 1.3.0 | Runs on the owner's phone. Asked for golf (1.4.0) and for controlling everything with hand gestures (hand cursors before set-up, 1.4.0). |
| "It works ... the tracking is a bit behind: in tennis it's not catching the swing of your arm and hitting the ball at the same time" - owner | 1.2.0 | Causes found: swings were judged when they reached the game (after camera, tracking and TV delays) and detected 33-75 ms after the swing's fastest point. 1.3.0 judges swings by when they were made, adds the timing test and cuts delay in smoothing, drawing and the camera. **Not yet confirmed on the phone.** |
| "Okay, it's all working" - owner, after installing 1.1.0 (solo mode) | 1.1.0 | App and solo mode run on the owner's phone. |
| "This is all working now" - owner, after installing 1.0.1 | 1.0.1 | App starts, camera permission works, game runs on the owner's phone (model not reported). |
| Crash right after allowing the camera (first real-device report) | 1.0.0 | Most likely cause found by code review (camera opened twice after the permission dialog; an error on the camera thread was not caught). Fixed in 1.0.1. **The fix itself is not yet confirmed on the phone.** 1.0.1 also shows a crash report on the next launch if anything still goes wrong. |

## 5. Physical-device checklist (to do)

Use a Snapdragon 8 Gen 3 class phone with USB-C video output, a USB-C to HDMI adapter (with power pass-through),
a TV, and two people. Turn on **Settings -> Developer -> Performance overlay** and **Skeleton + tracking debug
overlay**. Record PASS/FAIL and the overlay numbers. Repeat key items on a second brand of phone.

**Install and start**
1. [ ] APK installs; app starts in landscape; no crash on first launch.
2. [ ] Camera explanation screen appears before the Android permission prompt. Deny once -> app explains and
   offers to ask again. Deny twice -> app offers Android settings. Grant -> camera preview appears.

**Camera and tracking**
3. [ ] Left/right check: raise a hand on each camera and follow the arrows; afterwards, moving your right hand to
   your right moves the cursor right on both cameras. Front camera and rear camera both show an upright, correctly mirrored picture (raise your right hand: it
   appears on the right of the screen for a mirrored view). Switch cameras from the main menu, the framing
   screen and the pause menu: each switch works without a crash and the match carries on after re-framing.
4. [ ] Two adults side by side at 2-3 m: both skeletons drawn, shoulders/elbows/wrists/hips on the right joints.
5. [ ] Framing: Player 1 found only in the left outline, Player 2 only in the right.
6. [ ] Overlay: camera 30 or 60 fps (the level shows "/60fps" when the faster mode is on); pose time per frame (target <= 25 ms on full model); capture->game latency
   (target <= 100 ms); render ~60 fps. Write down the real numbers.
7. [ ] Players stand close (shoulders ~30 cm apart) and wave: no identity swap for 2 minutes.
8. [ ] One player walks out: game pauses within ~0.5 s and names that player; returns: 3-s countdown, same
   colours/sides as before.
9. [ ] A third person walks behind and beside the players: never becomes a player.
10. [ ] Third person steps into a lost player's area: is not accepted if clearly different in size/clothing.
11. [ ] Child + adult pair; seated player (hips hidden by a table); left-handed player: each can calibrate and play.
12. [ ] Dim room and a bright window behind the players: note tracking quality and whether the tracking meter
    says "More light please".
13. [ ] Wide-angle option (if the camera supports zooming out).

**Games** (both players, each game to the end; note anything that triggers by accident or fails to trigger)
14. [ ] Tennis 15. [ ] Snowball 16. [ ] Present Catch 17. [ ] Reindeer Racing (also reduced mode)
18. [ ] Bowling 19. [ ] Table Tennis 20. [ ] Dance-Off 21. [ ] Sleigh Slalom (also steering-only)
22. [ ] Goalkeepers 23. [ ] Decorate the Tree
24. [ ] Standing still for 30 s during a game produces no actions.
25. [ ] Hands-free menus: from launch (nobody set up), raise a hand - a cursor appears; dwell selection works; no
    accidental double selections; touch fallback works.
25b. [ ] Golf with 2-4 people: turn changes pick up the next person; full swings at different heights give different
    distances; straight swings go straight; putting strokes control distance; club and aim buttons work by hovering;
    no accidental club change during a swing.
25c. [ ] 1.6.0 tracking: in Settings -> Motion -> Motion test, normal forehands, forehands swung towards the TV and
    quick backswing-then-swing movements each flash "SWING" once per stroke (note which part saw them); standing
    still flashes nothing. In tennis, swings when the ball arrives are returned - note any ball that passes a swing.
25d. [ ] Pointer: every button on the main menu, settings and golf screens can be chosen with a relaxed hand at chest
    height, including the bottom row, without the pointer switching off; it holds still enough to fill the ring.
25e. [ ] 3D golf: smooth on the phone (note the render fps in the overlay); the grip guide reacts to hands together /
    apart; the snowman copies the swing; the camera follows the ball.
25f. [ ] Held object: learn a brightly coloured toy racket or pool noodle; the green line follows it at all angles;
    the tennis racket points the way it does; try a dull stick in front of furniture and note whether learning
    refuses it.
25g. [ ] Sharp-motion exposure: the motion test shows "sharp-motion exposure ... ms" on the back camera; the picture is
    not dark, flickering or banded; switching the option off returns to auto exposure. Compare a fast arm's blur in
    the camera preview with the option on and off.

25h. [ ] 1.7.0 finger guns: Motion test, hold a finger gun towards the TV at 2-3 m: it says "Finger gun ... thumb UP";
    dropping the thumb counts one shot each time; a fist or an open hand never counts. Note the "Finger tracking: x ms"
    line and whether the tracking rate drops. Repeat in a dimmer room and further back; note where it stops working.
25i. [ ] Clay Shooting and Shooting Gallery with two people: the centre-target shot sets each crosshair; crosshairs
    follow the hands steadily; hits feel fair (a clean shot breaks the clay). Try a toy gun taught from the start
    screen, fired with the upward flick.
25j. [ ] Boxing: jabs at the TV, hooks and uppercuts each register as the right punch (Motion test shows the kind);
    guard blocks; leaning and ducking slip; nothing fires while standing still.
25k. [ ] Archery: hands together loads; pulling back fills the power bar; opening the hand (or letting it fly back)
    releases; slowly bringing the hands together puts the arrow down.
25l. [ ] Darts: the dart lands where the sight was held before the throw; a quick push throws, a slow reach does not.
25m. [ ] Sports mode overall: mode switch, sports characters, summer golf; frame rate of the 3D scenes.
25n. [ ] 1.8.0 sound: effects, music and background sounds all audible on the phone and on the TV; the three volume
    settings work; nothing crackles or clips in a busy moment (a boxing knockdown, a clay flurry, a strike); menus
    make sound straight after launch.
25o. [ ] 1.8.0 screens: main menu cards, game list previews, instructions card, pause panel, podium and scoreboard
    draw correctly on the TV; note the render fps in Sleigh Slalom (now 3D), Reindeer Racing and golf flyovers; turn
    on Reduced effects and compare.
25r. [ ] 1.9.0 finger gun: Motion test, Graph: thumb. Point one or two fingers at the TV from 2-3 m, thumb up: the
    trace sits near the top; drop the thumb: it falls below the lower line and "BANG" is marked. Note how far the
    trace moves and any misses or extra BANGs; screenshot it. Then Clay Shooting with two players.
25t. [ ] 1.10.0 menus: stand on the red side, raise a hand - no pointer yet, the prompt says to wave; wave side to
    side - the bar fills and a chime plays; then point and hold still over a card. Note how many waves it took, and
    whether a wave was ever missed or a stray movement took control. Move across buttons and away from a half-filled
    ring: nothing should be chosen. Check the blue player gets no pointer.
25u. [ ] 1.10.0 golf: Motion test, Graph: golf. Clasp the hands as for a real club: the line should read "grip OK",
    then "ready - swing!" after holding still low; swing back and through: a SHOT mark appears. Screenshot it,
    especially when a swing is missed. Then play a hole.
25v. [ ] 1.11.0 smoothness: play Sleigh Slalom, Tennis and Boxing for a minute each on the TV - any stutter, and does
    the phone get hot? Note any pause when a game's "how to play" card appears. Try Reduced effects if anything
    stutters, and report which game.
25w. [ ] 1.11.0 look on the TV from the sofa: are the players easy to see against the new crowds and scenery? Is
    anything too dark or too busy?
25x. [ ] 1.11.0 tracking tip: during a game, put one hand behind your back for a few seconds - a tip should appear on
    your side and go when you bring it back.
25s. [ ] 1.9.0 graphs for bowling and skiing: Graph: bowling while bowling a few times, Graph: lean + tuck while
    steering and tucking; screenshot each, especially when a move was missed or fired by itself.
25q. [ ] 1.8.1: play bowling, Sleigh Slalom and archery as before; note whether moves are picked up and whether
    anything fires by itself. Then Settings -> Motion -> Extra tracking aids on, same games again; keep whichever
    is better. Take a screenshot of the Motion test (it shows the body model, tracking speed and any warning).
25p. [ ] 1.8.0 tracking, compared with 1.7.0 if possible: (a) a dim room with one lamp - both players found, note the
    tracking meter; (b) a bright window behind the players; (c) two players side by side swaying and lunging quickly
    for a minute - colours never swap; (d) one player jumping. Note the pose time per frame in the overlay during
    fast movement (the jump guard can add model runs).

**TV**
26. [ ] HDMI connected before launch: TV detected (name, resolution, Hz shown); game on TV, phone shows camera +
    touch buttons (second-screen mode) - or a mirrored picture on phones that only mirror.
27. [ ] Cable pulled mid-game: game pauses with "TV disconnected"; reconnect or Continue on phone works.
28. [ ] Cable connected mid-game: picture moves to TV without a crash; camera keeps running.
29. [ ] Samsung DeX / Motorola Ready For: mirroring mode works; note behaviour.
30. [ ] Measure end-to-end delay roughly (film TV and player with another phone at 240 fps) - optional.
30b. [ ] Settings -> Timing -> Run the timing test, with the TV's Game mode off and on: write down both results.
    Then play tennis: swings made as the ball reaches you on the TV are judged on time ("PERFECT!" / "Great!").
    Developer overlay: note "Capture->game" (avg/high) and the "/60fps" level.

**Lifecycle**
31. [ ] Home button mid-game and back: game paused, camera resumes, no crash.
32. [ ] Incoming call / another camera app opened: camera reconnects afterwards.
33. [ ] Screen rotated 180 degrees (other landscape): picture and controls still correct.
34. [ ] 30-minute session while charging: note thermal status and whether the app drops to the lite model or 30 fps.

**Audio**
35. [ ] Music and effects audible on the TV over HDMI; volumes independent; captions appear for effects.
