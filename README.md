# ColourFilm

An Android app that turns your black-and-white videos into natural-looking colour videos
using AI (DDColor). It runs on the phone, and videos are never uploaded.

**Open the app → Choose video → Colourise → watch, compare, save or share.**

> Colours are AI estimates. Black-and-white film does not record the original colours, so results
> are plausible guesses, not historically accurate.

## Install
APKs are built by `./gradlew :app:assembleSnapdragonRelease` (details in `docs/BUILDING.md`), or by
the GitHub Actions workflow on every push (artifact `colourfilm-apks`):

* `app-snapdragon-release.apk` (≈68 MB, arm64): for your Honor Magic 6 Pro and other Snapdragon
  phones. It uses the Qualcomm NPU when verified to work and falls back to the CPU otherwise.
* `app-universal-release.apk` (≈55 MB): CPU only, for any Android 10+ phone.

On first use the app downloads the AI model once (206 MB, from Qualcomm AI Hub, integrity-checked).

## How it works
1. **Check**: reads the real container/codec/rotation/audio info with Android's media APIs.
   It warns before starting if the phone lacks a decoder, the video is HDR, the size needs
   scaling for the H.264 encoder, the soundtrack can't be decoded, or storage is short.
2. **Colourise**: decodes with `MediaCodec`, one frame in memory at a time. For each frame:
   * DDColor estimates colour (Lab a/b) from a 256×256 view of the picture.
   * Video-aware temporal layer (`core/.../TemporalColouriser.kt`): block-matching motion estimation,
     motion-compensated colour carried from the previous frame, per-pixel reliability (occlusions and
     revealed areas are not painted with stale colour), and shot-cut detection that resets colour
     memory. It also uses exposure-flicker-robust normalisation.
   * Edge-aware (guided-filter) upsampling of colour to full resolution, guided by the original picture.
   * The original luma plane is copied untouched, so detail, grain and brightness are exactly preserved.
3. **Export**: H.264 High-profile MP4 (falls back if the encoder refuses), original timestamps
   (variable frame rate kept), rotation kept, AAC audio copied as-is or other formats re-encoded to AAC.
4. **Long videos**: processed in 15-second segments, each saved durably with the colour state.
   Pause, cancel, app kills and restarts resume from the last finished segment. Segments are only
   reused when source, model and settings all match. It runs as a foreground service with a
   progress notification (Android 15+ `mediaProcessing` type), pauses while the phone is hot, and
   only reports "done" after the final MP4 is opened and checked.

Optional controls (Review → Advanced): colour intensity, Natural/Vivid, Best/Balanced/Fast
processing, colour stability, edge-aware colour, keep sound. All of them change the output.
There is also an optional 5-second preview render for long videos.

## Repository layout
| Path | What |
|---|---|
| `core/` | Platform-independent Kotlin colourisation engine (+ unit tests) |
| `app/` | Android app: media pipeline, model download/NPU selection, jobs/service, Compose UI, device tests |
| `bench/` | Desktop harness running the same core + model over ffmpeg (benchmarks, samples) |
| `samples/` | Real colourised sample exports + contact sheet |
| `docs/MODEL.md` | Engine research, exact versions, sources, licences |
| `docs/BUILDING.md` | Build, CI and test instructions |
| `docs/TEST_REPORT.md` | Measured results and what is untested |
| `tools/` | Model fetch, device-test runner, evaluation scripts |

## Handover: status and limitations
**Done and verified**
* Real AI colourisation engine, temporal consistency, scene-cut handling, edge-aware colour
  (measured against ground truth on desktop; flicker roughly halved vs per-frame colourisation).
* Complete Android pipeline (decode → colour → H.264 → MP4 + audio) passing 7 end-to-end tests
  on an Android 13 emulator: audio copy, PCM/MP3→AAC, VFR timing, rotation, silent video,
  damaged file, pause/resume, cancel.
* Both APKs compile (release, R8-shrunk). CI workflow included.

**Not verified (no phone was available)**
* Nothing has run on a real phone. NPU use on your Snapdragon 8 Gen 3 is implemented with a
  CPU cross-check and automatic CPU fallback, but untested. Real phone speed, heat and battery
  use are unknown. The app measures and displays them honestly as it runs.
* The UI has not been used by a person. Long films, 4K and HEVC sources are untested.
* Home.mp4 and the starter source never arrived, so the app was built fresh and tested on other footage.

**Known limitations**
* The colours are estimates: multicoloured clothing (e.g. striped football kits) may come out as one colour.
  "Known colour" corrections and reference images are **not** implemented (not shown in the UI).
* Fast motion can briefly change the colour of a small object (e.g. jeans flicker blue→red).
* No cloud/remote processing (not needed: the model is mobile-sized). Nothing is uploaded.
* HDR is rejected; 10-bit is exported as 8-bit; output is always H.264 (very large sizes are scaled
  down only if the phone's encoder can't handle them, and the app says so first).
* Android 15 limits this kind of background job to 6 hours a day. The app then pauses cleanly and
  asks you to tap Resume.
* The Snapdragon APK includes Qualcomm's QNN libraries under Qualcomm's licence: fine for personal
  use; check `docs/MODEL.md` before publishing.
