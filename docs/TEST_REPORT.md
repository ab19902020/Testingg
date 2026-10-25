# Test report

Date: 2026-10-25 (version 1.1.0). Environment: Linux cloud container, OpenJDK 21, Gradle 8.14.3. **No Android phone, emulator
(no KVM) or TV was available here.** The only real-device evidence is the owner's report that version 1.0.1 works
on their phone (section 4). Everything else that needs a device is marked **UNVERIFIED**.

## Summary

| Area | Result |
|---|---|
| Automated JVM tests | **81 passed, 0 failed, 1 skipped** (the skipped one is a developer diagnostic) (`./gradlew :core:test` with the real-model environment configured) |
| Pose pipeline with the real BlazePose models (on still images, JVM) | **Passed** - matches official MediaPipe output |
| APK build, signature, contents | **Passed** - built, aligned, v2+v3 signed, verified |
| App launches and runs on Android | **Reported working by the owner** (1.0.1 on their phone, after the camera crash fix); not tested here |
| Live camera tracking of real people | Owner reports the game working; tracking quality not measured - **UNVERIFIED** in detail |
| Solo mode against the computer (1.1.0) | Passed with simulated input and CPU-vs-CPU matches; **not yet tried on a phone** |
| On-device performance (fps, latency, thermal) | **UNVERIFIED** - no numbers measured; none are claimed |
| TV output over USB-C/HDMI, Presentation mode, cable unplug | **UNVERIFIED** |
| Audio output and latency | **UNVERIFIED** |

Important: the game tests drive the games with **scripted, simulated body input**. They prove the rules,
scoring, gesture logic, identity logic, menus and flows work as programmed. They do **not** prove that real camera
tracking works, how fast it is on a phone, or that the TV output works.

## 1. Automated tests (81)

Run: `./gradlew :core:test` (75 tests) plus 6 real-model tests that need the validation environment
(`tools/pose-validation/run_real_model_tests.sh`).

### Pose pipeline - fixtures from the real models (`BlazePoseFixtureTest`, 5 passed)
Decoding checked against raw tensors exported from the real models: anchor count (2254), detector decoding,
landmark decoding, crop sampler vs OpenCV `warpAffine`, half-frame detections agree with full-frame ones.

### Orientation and pipeline logic (`OrientationAndPipelineTest`, 6 passed)
Rotation formula for front and back cameras; upright-to-buffer mapping for 0/90/180/270 degrees; mirroring puts the
person's left hand on screen left; YUV -> RGB conversion; detector runs once then tracking takes over; person who
leaves is dropped and comes back with a new track.

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
| Tennis | 1.00 | 1.00 | 1.00 |
| Snowball Showdown | 0.88 | 0.88 | 0.69 |
| Present Catch | 1.00 | 1.00 | 0.88 |
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
[docs/images/test_render_contact_sheet.jpg](images/test_render_contact_sheet.jpg).

## 2. APK checks (passed)

| Check | Result |
|---|---|
| File | `dist/ChristmasLivingRoomGames-debug.apk`, 21,189,763 bytes, SHA-256 `26be12664d60ff98a6df9c8d2407339c006af9472e71e0e22f87c53b4ae6a236` |
| Package / version | `com.clrg.christmasgames`, versionCode 3, versionName 1.1.0 |
| SDK levels | minSdk 29 (Android 10), targetSdk 35 |
| Signature | `apksigner verify`: APK Signature Scheme v2 and v3 verified, 1 signer (`CN=Android Debug`, certificate SHA-256 `af5122d7549be77841521095d8fb1cd74f0ddf5410a4a76286c93b377221836e`) |
| Reproducibility | A clean rebuild (`./gradlew clean :app:assembleDebugApk`) produced a byte-identical APK with the same debug key |
| Alignment | `zipalign -p 4` |
| Code | single `classes.dex`, 1,493 classes (game, Android layer, TFLite Java API, Kotlin stdlib) |
| Models | 4 `.tflite` files, stored uncompressed (memory-mappable) |
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
| Front/rear camera selection | Implemented / UNVERIFIED | CameraSource |
| Body-normalised thresholds, smoothing, cooldown, hysteresis | Passed (synthetic) | GestureTest |
| Left-handed, reduced-movement, seated | Passed (synthetic) | GestureTest, AllGamesTest |
| Ten complete games with instructions, countdown, scoring, results, rematch | Passed (scripted) | AllGamesTest, AppFlowTest |
| Tournament pair rotation + persistent scores | Passed | AppFlowTest |
| Hands-free dwell menus with accidental-activation protection | Passed (unit) / UNVERIFIED with real hands | UiDwellTest |
| Debug overlays (skeleton, performance) | Implemented / UNVERIFIED on device | Settings -> Developer |
| Camera fps, inference latency, render fps, tracking-loss counters | Implemented (overlay) / no measurements yet | DevOverlay |
| Camera keeps running while TV shows the game | Implemented / UNVERIFIED | DisplayRouter, GameRenderer |
| TV connect/disconnect, Presentation, mirroring | Game reaction passed (injected events) / Android display handling UNVERIFIED | AppFlowTest, DisplayRouter |
| Permission prompt, denial, settings link | Implemented / UNVERIFIED | PermissionScreen, MainActivity |
| Backgrounding, phone call, camera taken by another app | Game reaction passed (injected events) / Android lifecycle UNVERIFIED | AppFlowTest, MainActivity, CameraSource |
| Thermal adaptation | Implemented / UNVERIFIED | PoseEngine, GameRenderer |
| Offline after install | Passed (APK contents: models bundled, no INTERNET permission) | verifyApkContents |
| No recording / upload | Passed by construction (no storage of frames, no network permission) / code review only | |

## 4. Field reports

| Report | Version | Status |
|---|---|---|
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
3. [ ] Front camera and rear camera both show an upright, correctly mirrored picture (raise your right hand: it
   appears on the right of the screen for a mirrored view).
4. [ ] Two adults side by side at 2-3 m: both skeletons drawn, shoulders/elbows/wrists/hips on the right joints.
5. [ ] Framing: Player 1 found only in the left outline, Player 2 only in the right.
6. [ ] Overlay: camera ~30 fps; pose time per frame (target <= 25 ms on full model); capture->game latency
   (target <= 100 ms); render ~60 fps. Write down the real numbers.
7. [ ] Players stand close (shoulders ~30 cm apart) and wave: no identity swap for 2 minutes.
8. [ ] One player walks out: game pauses within ~0.5 s and names that player; returns: 3-s countdown, same
   colours/sides as before.
9. [ ] A third person walks behind and beside the players: never becomes a player.
10. [ ] Third person steps into a lost player's area: is not accepted if clearly different in size/clothing.
11. [ ] Child + adult pair; seated player (hips hidden by a table); left-handed player: each can calibrate and play.
12. [ ] Dim room and a bright window behind the players: note tracking quality.
13. [ ] Wide-angle option (if the camera supports zooming out).

**Games** (both players, each game to the end; note anything that triggers by accident or fails to trigger)
14. [ ] Tennis 15. [ ] Snowball 16. [ ] Present Catch 17. [ ] Reindeer Racing (also reduced mode)
18. [ ] Bowling 19. [ ] Table Tennis 20. [ ] Dance-Off 21. [ ] Sleigh Slalom (also steering-only)
22. [ ] Goalkeepers 23. [ ] Decorate the Tree
24. [ ] Standing still for 30 s during a game produces no actions.
25. [ ] Hands-free menus: dwell selection works; no accidental double selections; touch fallback works.

**TV**
26. [ ] HDMI connected before launch: TV detected (name, resolution, Hz shown); game on TV, phone shows camera +
    touch buttons (second-screen mode) - or a mirrored picture on phones that only mirror.
27. [ ] Cable pulled mid-game: game pauses with "TV disconnected"; reconnect or Continue on phone works.
28. [ ] Cable connected mid-game: picture moves to TV without a crash; camera keeps running.
29. [ ] Samsung DeX / Motorola Ready For: mirroring mode works; note behaviour.
30. [ ] Measure end-to-end delay roughly (film TV and player with another phone at 240 fps) - optional.

**Lifecycle**
31. [ ] Home button mid-game and back: game paused, camera resumes, no crash.
32. [ ] Incoming call / another camera app opened: camera reconnects afterwards.
33. [ ] Screen rotated 180 degrees (other landscape): picture and controls still correct.
34. [ ] 30-minute session while charging: note thermal status and whether the app drops to the lite model or 30 fps.

**Audio**
35. [ ] Music and effects audible on the TV over HDMI; volumes independent; captions appear for effects.
