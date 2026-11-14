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
