# ColourFilm test report

Everything below was actually run. Where something was **not** tested, it says so.
No physical phone was available: **nothing has been run on a real Android device, including your
Honor Magic 6 Pro**, and the Snapdragon NPU path has never executed.

Test inputs: `Home.mp4` and the starter source were **not received** (they were not in the repo or
your Google Drive), so other footage was used. OpenCV's sample clips (`Megamind.avi`: animated
dialogue with scene cuts and AC-3 audio; `vtest.avi`: pedestrians filmed from above) were
converted to greyscale. Their colour originals give a ground truth to measure against.

## 1. Automated JVM unit tests: `./gradlew :core:test`: 7/7 pass
Lab↔RGB round-trip; zero chroma gives neutral planes; red chroma gives positive Cr; motion
estimator recovers a known (+3,−2) px shift; stride/cut logic (model runs every 4th frame on a
static scene, and runs immediately with no colour carry-over after a cut); segment planning and
fingerprints; temporal state export/import.

## 2. Desktop pipeline with the real model (same Kotlin core + same ONNX file)
Machine: 4-vCPU x86-64 cloud VM, ONNX Runtime 1.29 CPU, 4 threads.

### Speed (measured)
| Clip | Mode | Model runs / frames | Throughput | Model time per run |
|---|---|---|---|---|
| Megamind 720×528, 271 frames | Best | 271/271 | 3.4 fps | 263 ms |
| Pedestrians 768×576, 300 frames | Best | 300/300 | 3.3 fps | 270 ms |
| Pedestrians | Balanced | 150/300 | 6.0 fps | 263 ms |
| Pedestrians | Fast (final) | 93/300 (adaptive) | 4.9 fps | ~280 ms |

Per-frame cost outside the model: motion analysis ~13 ms, full-resolution chroma composition
16–18 ms at 720×528. **Phone speed has not been measured.** For an estimate, Qualcomm publishes
48.7 ms per DDColor run on the Snapdragon 8 Gen 3 NPU through ONNX Runtime, so Best mode at 1080p
might reach several frames per second. That is unverified. On the phone, the app measures and
shows real speed and time remaining.

### Quality against the colour originals (lower = better, Lab units)
`flickerInStatic` = frame-to-frame chroma change where the true colour does not change.

| Clip / variant | Error vs true colour | Flicker in static areas | True-colour temporal change |
|---|---|---|---|
| Megamind, per-frame model only (naive) | 5.87 | 1.81 | 0.76 overall |
| Megamind, ColourFilm Best | 5.78 | **0.84** | |
| Pedestrians, naive | 9.53 | 1.72 | 0.22 overall |
| Pedestrians, Best | 9.47 | 0.81 | |
| Pedestrians, Balanced | 9.47 | 0.62 | |
| Pedestrians, Fast | 9.40 | 0.51 | |

The temporal layer halves to thirds the flicker without hurting accuracy. Luminance is passed
through untouched (the luma plane is copied bit-for-bit; the measured L* difference comes only
from re-encoding).

### Visual inspection (contact sheets and 2× crops were inspected frame by frame)
* Natural skin, hair, candle light, grass, sky, brick and vans. See `samples/contact_sheet.jpg`.
* Scene cuts: the first detector missed 2 of 4 real cuts in Megamind (it compared against a
  fixed threshold). It was changed to a relative test; all 4 cuts are now detected, with 0 false
  cuts over 30 s of moving pedestrians.
* Ghosting: an early Fast mode left a blue trail on the pavement beside a walker's legs. It was
  fixed (uncertain pixels take their surroundings' colour, and the model re-runs sooner); a re-check
  of the same crops shows no trail.
* Known model limitations seen: a red/blue striped football shirt becomes solid red, and a grey
  lamp post gets an orange stripe. A walker's jeans occasionally flip briefly from blue to red
  where motion is too fast to track (in every mode).

### Samples (`samples/`)
`megamind_bw_input.mp4` → `megamind_colour.mp4` (Balanced; audio re-encoded to AAC, 11.27 s audio
vs 11.26 s video); `pedestrians_bw_input.mp4` → `pedestrians_colour.mp4`.
These were produced by the desktop harness (ffmpeg I/O), not by the phone app.

## 3. Android end-to-end tests on an emulator (`PipelineTest`, Android 13 x86-64 ATD image, no hardware acceleration)
Real `MediaExtractor` → `MediaCodec` decoder → colour core → `MediaCodec` H.264 encoder →
`MediaMuxer`, plus segment assembly, audio handling, durable job state and cancellation.
**Because the emulator ran in pure software (minutes per DDColor frame), these 7 tests used a fast
stand-in colour model.** The real model on the emulator is test 8.

| Test | Result |
|---|---|
| B&W clip with AAC audio, split into 1.5 s segments: every frame once, first/last timestamps within 1 ms, AAC copied, output has colour | **pass** |
| Portrait clip (rotation metadata 90°), silent: rotation kept, no audio track invented, all frames | **pass** |
| Variable-frame-rate MKV with MP3 audio, 322×242 (not 16-aligned): every timestamp within 1 ms, audio transcoded to AAC | **pass** |
| MOV with PCM audio: PCM → AAC | **pass** |
| Damaged/truncated MP4: rejected with a clear message | **pass** |
| Pause after 2 segments, then resume: finished segments reused, no frame rendered twice, complete output | **pass** |
| Cancellation mid-job: stops, files removed | **pass** |
| 8. Real DDColor model on the emulator (0.3 s clip) | see section 5 |

Exported files were pulled and checked with ffprobe and by eye. Colour planes are correct (no
U/V swap, no stride damage), audio and video durations match, and the rotation tag is present.

Bugs found and fixed by these tests:
* The emulator's H.264 encoder rejected the high-quality format. The encoder now falls back step
  by step (High profile → no profile → no optional keys → minimal).
* Export validation compared `MediaFormat` objects by reference and never found the video track.
* The first damaged-file message was misleading.

## 4. Not tested (honest list)
* Any physical phone, the Honor Magic 6 Pro, and the QNN NPU path (compile, FP16 accuracy check,
  speed, context caching).
* Heat and thermal throttling behaviour, battery use, and memory on a real device.
* Long videos (the longest processed clip is 30 s on desktop and 4 s on the emulator). Feature-length
  films are designed for but untested.
* 4K, HEVC/10-bit, HDR rejection, AC-3/E-AC-3 sources on phones (decoder availability varies),
  >2-channel audio downmix.
* The model download inside the app (the URL and SHA-256 were verified with curl on desktop; the
  app's own downloader has not run), low-storage paths, and the 6-hour Android 15 foreground-service limit.
* Foreground service, notification actions, the Compose UI, gallery save and share: they compile,
  but no person has used them. (A UI screenshot check on the emulator is in section 5 if it completed.)
* Process death and phone restart in the real app. The resume logic underneath (segment reuse) is
  tested; the "Interrupted → Resume" UI flow is not.
* Picking from Google Photos and other gallery providers.

## 5. Late results
* **Real DDColor inside the Android app on the emulator: not completed.** The software-only
  x86-64 emulator (no KVM in this environment) spent over 80 minutes on the three warm-up model
  runs without finishing, so the run was stopped. This says nothing about phone speed; it is
  emulation overhead. The real model is verified on desktop with identical Kotlin and ONNX code
  (section 2); inside the app it is still unverified.
* **UI smoke test: incomplete.** The release universal APK installed and `MainActivity` reached the
  resumed state with no crash. The emulator's own system process kept showing "isn't responding",
  and its test image has no rendered display, so the screens could not be inspected.

## 6. Version 1.1.0 (after the first real-phone report)
The first install on the Honor Magic 6 Pro (v1.0.0) crashed after choosing a video. The crash
could not be reproduced on the emulator: the v1.0.0 release build opened a video and showed the
Review screen without crashing. The phone-only code path is the Qualcomm NPU start-up, so that
is the suspected cause. It is **not confirmed**.

Changes in 1.1.0:
* The AI model is bundled in the APK (no download). It is unpacked once on first use.
* The NPU is tested in a separate process; a native driver crash or hang there can no longer close
  the app, and the app falls back to the CPU. If the app still crashes while the NPU is in use,
  the next launch switches to the CPU permanently on that phone.
* A crash report (reason, device, last steps) is shown on Home after an unexpected close, with
  Copy and Share.

Tests run on the Android 13 emulator with the 1.1.0 code:
* Full `PipelineTest` suite on the debug build: 9 pass, 1 skipped (real model on emulator, by design).
  New tests: the foreground service runs a job to a finished MP4; the bundled model unpacks from
  the APK with the right size.
* Release universal APK: Home renders; opening a video shows Review (checked on v1.0.0 release);
  a forced native crash (`kill -SEGV`) produces the crash report card on the next launch.

Still untested: the NPU probe process and NPU inference (need a Snapdragon phone), and the
Colourise tap in the release build's UI (the emulator's automation could not drive it; the same
service path is covered by the instrumented test on the debug build).

## 7. Version 1.2.0 (second real-phone report: still stops after tapping Colourise)
The phone showed jobs left "Interrupted" and no crash card, so the process died in a way 1.1.0
did not classify as a crash. Still **not reproduced** off the phone: on the Android 13 emulator the
1.1.0 release build opens a video, starts the job, shows the Processing screen, starts the
foreground service and unpacks the model without crashing. The cause on the phone is unknown.

Changes in 1.2.0:
* The Qualcomm AI chip is off by default (opt-in switch on Home). The CPU runs the model.
* Stage tracking: if the process dies mid-job for any reason, the next launch switches off the
  riskiest component in use (AI chip first, then hardware codecs → Android software codecs).
* Step-by-step log written to disk (model load, decoder/encoder names, first frame through each
  step) and a "Copy diagnostics" button that is always available, including Android's recorded
  exit reasons for the last runs.

Emulator tests (debug build, Android 13): 11 pass, 1 skipped by design. New: full UI flow through
the real screens (open video → Review → Colourise → Processing with live progress → Result),
software-codec fallback, stage recovery logic.
Untested: everything on a real phone, as before.

## 8. Version 1.2.1: the real cause of the phone crash, found and fixed
**Reproduced on an Android 15 emulator (API 35 ATD image):** pressing Colourise killed the app with
`InvalidForegroundServiceTypeException: Starting FGS with type none ... targetSDK=35`.
Cause: `androidx.core` 1.15 `ServiceCompat.startForeground` masks the service type with the
Android 14 type list, which removes `mediaProcessing` (Android 15 only). Android 15 then rejects
the foreground service and kills the process. Android 13/14 use `dataSync` and never hit this,
which is why the Android 13 emulator tests passed. This matches the phone report exactly (app
shuts as soon as Colourise is pressed; jobs left "Interrupted").

Fix: the service calls the platform `Service.startForeground(id, notification, type)` directly,
with a fallback to `dataSync` if the preferred type is refused.

Verified on the Android 15 emulator (debug build, stand-in model): the full user flow test
(open video → Review → Colourise → foreground service type 8192 → Processing → finished MP4 →
Result) passes. Before the fix the same step crashed.

The earlier guesses (AI chip) were wrong as the cause of this crash. The 1.2.0 safety changes
stay (AI chip opt-in, stage recovery, diagnostics), since the AI chip is still untested.
Still untested: a real phone, Android 16, the NPU path.

## 9. Version 1.3.0: quality pass (first real-phone feedback: works; motion "goes funny", halos around people)
Confirmed working on the Honor Magic 6 Pro by the user with 1.2.1 (CPU path). Reported issues:
colour instability on moving people and colour halos around them. Both were reproduced on the
desktop harness (walker's jeans flipping grey/blue/red between frames; bluish halo round the head).

Changes (all in `core/`, same code on phone and desktop):
* Motion tracking now also runs at the full analysis resolution (was half), so thinner moving
  things are followed.
* Patch search: pixels the block tracker cannot follow look for matching luminance patches in
  a ±7 px window of the previous frame and take that colour, so limbs keep their colour and
  revealed background takes background colour.
* Edge-aware chroma clean-up on the analysis grid (joint bilateral, guided by brightness): dilutes
  the model's colour spill around subjects.

Measured (desktop, colour originals as ground truth; lower is better; `tools/evaluate.py`):

| Clip / mode | Version | Error | Error near edges | Error on moving areas | Change along true motion |
|---|---|---|---|---|---|
| Pedestrians, Best | 1.2.1 | 9.46 | 9.26 | 6.62 | 1.16 |
| | 1.3.0 | 9.33 | 9.05 | 6.32 | 0.98 |
| Pedestrians, Balanced | 1.2.1 | 9.42 | 9.19 | 6.36 | 1.06 |
| | 1.3.0 | 9.27 | 8.98 | 6.12 | 0.89 |
| Pedestrians, Fast | 1.2.1 | 9.40 | 9.12 | 6.17 | 0.81 |
| | 1.3.0 | 9.25 | 8.95 | 6.02 | 0.76 |
| Megamind, Best | 1.2.1 | 5.78 | 8.78 | 7.66 | 1.19 |
| | 1.3.0 | 5.72 | 8.69 | 7.61 | 1.14 |
| Megamind, Balanced | 1.2.1 | 5.71 | 8.59 | 7.49 | 1.11 |
| | 1.3.0 | 5.63 | 8.50 | 7.46 | 1.05 |

The numeric gains are modest because the totals are dominated by the model's colour guesses; the
visible gain is larger. `samples/before_after_1.3.0.jpg` shows the same frames at 1920×1440:
orange/blue fringes round the walker are gone and the jeans keep one colour. Static-area flicker
is unchanged (0.38 vs 0.39). Scene cuts: still 4/4 detected on Megamind, 0 false on pedestrians.
Extra cost: about 13 ms per frame on the desktop (not measured on the phone).

Not re-run for 1.3.0: the emulator device suite (no app-side code changed; core unit tests 7/7
and both release builds pass). Remaining visible faults: the model itself still tints some flat
backgrounds unevenly and can pick muted colours for small moving objects.

## 10. Version 1.4.0: colour consistency (phone feedback: outfits change colour on fast-moving people)
New metric in `tools/evaluate.py`: **mlong**, the colour drift of things that have moved, tracked
with optical flow on the colour original for up to 12 frames. Real colour footage scores about 1.0.

Changes (all in `core/`):
* Colour memory strength raised: an established colour now outweighs the model's fresh guess
  9:1 per two frames (was about 2:1), and is normalised so Best, Balanced and Fast hold colour
  equally long. A wrong first guess therefore also takes longer to correct (about ten frames of
  consistent disagreement).
* History search for untracked pixels now matches on brightness and surroundings instead of exact
  shape, so it survives limbs changing shape.
* Shot memory: the last 6 shots are remembered; when the picture cuts back to one of them, its
  colours are resumed instead of re-guessed.

Measured (desktop, Balanced unless stated; lower is better):

| Clip | Version | Error | Near edges | Moving areas | mlong (drift) | Static flicker |
|---|---|---|---|---|---|---|
| Pedestrians | per-frame AI only | 9.52 | 9.33 | 6.82 | 3.23 | 0.82 |
| | 1.2.1 | 9.42 | 9.19 | 6.36 | 1.88 | 0.31 |
| | 1.3.0 | 9.27 | 8.98 | 6.12 | 1.70 | 0.32 |
| | 1.4.0 | 9.12 | 8.85 | 5.91 | 0.95 | 0.15 |
| | 1.4.0 Best / Fast | 9.12 / 9.14 | | 6.02 / 5.89 | 1.09 / 0.91 | 0.14 / 0.18 |
| | colour original | | | | 1.03 | |
| Megamind | 1.2.1 | 5.71 | 8.59 | 7.49 | 1.69 | 0.31 |
| | 1.3.0 | 5.63 | 8.50 | 7.46 | 1.62 | 0.31 |
| | 1.4.0 | 5.44 | 8.16 | 7.27 | 1.02 | 0.19 |
| | colour original | | | | 1.02 | |

Shot memory on Megamind (cuts woman→man→woman→man close-up): the cut back to the woman is
recognised and resumed (match 0.66); the close-up of the man, a different framing, is correctly
treated as a new shot after the match threshold was raised from 0.5 to 0.62 (it scored 0.59 and
was wrongly resumed at 0.5). This threshold rests on one clip. See `samples/shot_memory_1.4.0.jpg`.

Known remaining case: a very small, fast walker at 10 frames/s still changes jeans colour once,
just after entering the frame (his legs' brightness matches the pavement, so the history search
finds pavement). Mean saturation is about 2% lower than 1.3.0.
Unit tests: 9/9 (two new: an established colour resists a contrary guess but yields to a
persistent one; a returning shot resumes its colours). Emulator suite not re-run (no app code changed
apart from one settings description).

## 11. Version 1.5.0: picture restoration and frame-rate increase

### Restoration (`core/LumaEnhancer.kt`, `core/DustRemover.kt`)
Tested on the desktop harness with deliberately damaged copies of clean clips
(`tools/degrade.py`: film grain, exposure flicker, 4–12 single-frame dust blotches per frame, and
optionally a faded print), scored against the clean clip with `tools/restore_metrics.py`.
Sharpening was off for the fidelity numbers (it changes the picture by design).

| Clip | Version | Fidelity (PSNR) | Grain left (rms) | Flicker (rms) |
|---|---|---|---|---|
| Megamind, damaged | input | 31.5 dB | 5.18 | 3.51 |
| | restored (default) | 36.2 dB | 3.14 | 0.41 |
| Pedestrians, damaged | input | 28.6 dB | 5.66 | 12.04 |
| | restored (default) | 35.5 dB | 3.29 | 0.49 |
| | restored (grain Strong) | 35.9 dB | 3.12 | 0.29 |
| Pedestrians, damaged + faded | input | 18.9 dB | 9.81 | 11.93 |
| | restored (default) | 29.9 dB | 6.48 | 0.93 |
| Megamind, clean (safety check) | restored, all steps | 46.5 dB | 0.79 | 0.50 |
| Pedestrians, clean (safety check) | restored, no sharpening | 46.6 dB | 0.73 | 0.62 |
| | restored, all steps | 39.1 dB | 2.11 | 0.62 |

Dust removal on the damaged clips: 770 spots (Megamind) and 876 (Pedestrians) removed, of roughly
2,200 and 1,200 inserted; the rest are low-contrast or next to motion and are left. False removals
on the clean clips: 0 (Megamind) and 6 small ones (Pedestrians).

Problems found and fixed during this work:
* Contrast restoration brightened a scene that is meant to be dark (Megamind dropped to 25 dB).
  It now only acts when the black point is lifted (faded film).
* The first dust detector erased walking people's feet and lower legs (461 false removals on the
  clean pedestrian clip). It now requires the surroundings of a spot to be unchanged, with a
  threshold that adapts to the grain level.
* Grain reduction switched itself off on very small frames (too few noise samples).

Limits: scratches that stay in place for many frames are not removed; dust next to moving things
is left; grain on moving objects is not reduced (only where the picture matches the previous
frame); no upscaling or AI detail reconstruction; the damage used for testing is simulated, not
real 1900s film. Restoration cost on the desktop: 5–11 ms per frame at 768×576.

### Frame-rate increase (`core/FrameInterpolator.kt`)
Engine comparison and choice are in `docs/MODEL.md`. Measured by dropping every other frame and
re-creating it: 38.5 dB (Megamind, 12→24 fps) and 30.8 dB (Pedestrians, 5→10 fps) against 34.5
and 29.1 dB for a plain cross-fade; 67–84 ms per new frame on the desktop. Visual check of the
5→10 fps case: the walker's body is placed correctly between the two frames, but his legs are
ghosted (they move further than the method can follow at that frame rate). No in-between frame
is made across a scene cut. Auto mode acts only below 20 fps.

### Tests
* JVM unit tests: 17/17 (new: dust speck removed and stream delayed by exactly one frame; small
  moving object not treated as dust; flicker steadied and full-contrast footage unchanged; grain
  reduced on a still scene; every frame output once with dust removal on or off; in-between frame
  lies halfway along the motion; unrelated frames are not interpolated; Auto mode thresholds).
* Android emulator (Android 13, debug build, stand-in colour model): see the results line below.
  Three tests were run on the final code and pass: portrait clip (every source frame output once
  with restoration's one-frame delay, rotation kept), frame-rate doubling across 0.7 s segments
  (frame count between 1.5× and 2×−1, strictly increasing timestamps, first/last timestamps kept),
  and the variable-frame-rate MKV (timestamps within 1 ms). The rest of the suite was not re-run:
  under software emulation the debug build now takes about 15 s per frame.

### Found while making the showcase: grain makes the colour model return grey
Colourising the faded, grainy test clip with restoration on first gave almost no colour (mean
chroma 2.6, against 14.7 for clean footage). Cause, confirmed on single frames: once contrast
restoration amplifies the grain, DDColor's output drops to 0.2; even un-stretched grainy film gets
about half the colour of a clean frame (8.7 vs 16.5); a 4-frame average restores it to 12.9.
Fix (`TemporalColouriser.conditionForModel`): the model is shown a de-grained view of each frame
(history averaged along the tracked motion, plus blur while history builds up). Only the model's
input changes; the output picture keeps its detail, and footage with no measurable grain is
passed through unchanged.

| Clip | Version | Mean chroma | Colour error vs truth |
|---|---|---|---|
| Pedestrians, faded + damaged | before fix | 2.6 | not measured |
| | 1.5.0 | 13.0 | not measured |
| Pedestrians, damaged | before fix | 13.4 | 12.12 |
| | 1.5.0 | 14.7 | 10.07 |
| Pedestrians, clean | 1.4.0 / 1.5.0 | 14.7 / 14.7 | 9.12 |
| Megamind, damaged | before fix | not measured | 9.58 |
| | 1.5.0 | not measured | 6.13 |
| Megamind, clean | 1.4.0 | not measured | 5.46 |

The first frames of a grainy shot are still less colourful (colour builds up over roughly 20 frames).
`samples/full_pipeline_1.5.0.jpg` and `samples/damaged_film_restored_colour_30fps.mp4` show the result.

Not verified for 1.5.0: anything on a real phone (speed of restoration and frame-rate increase
there is unknown), real archival film, the release build on a device.
