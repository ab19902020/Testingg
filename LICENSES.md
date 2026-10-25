# Licences and asset sources

## Shipped in the APK

| Component | Version | Licence | Source |
|---|---|---|---|
| Game code (`core`, `app`) | 1.0.0 | Original work for this project. No open-source licence has been chosen yet; add a `LICENSE` file if you intend to publish the source. | This repository |
| MediaPipe BlazePose models: `pose_detector.tflite`, `pose_landmarks_detector.tflite` from `pose_landmarker_lite.task` and `pose_landmarker_full.task` (float16, v1) | v1 | Apache License 2.0 | Google MediaPipe, storage.googleapis.com/mediapipe-models (see the "Pose Landmarker" model card) |
| TensorFlow Lite runtime: `tensorflow-lite`, `tensorflow-lite-api`, `tensorflow-lite-gpu`, `tensorflow-lite-gpu-api` (Java classes + native libraries) | 2.16.1 | Apache License 2.0. The native libraries include third-party components (e.g. XNNPACK, FlatBuffers, Abseil, ruy) under their own permissive licences, listed in TensorFlow's third-party notices. | Maven Central, `org.tensorflow` |
| Kotlin standard library | 2.2.21 | Apache License 2.0 | Maven Central, `org.jetbrains.kotlin` |
| JetBrains annotations (dependency of the Kotlin stdlib) | 13.0 | Apache License 2.0 | Maven Central, `org.jetbrains` |

The full Apache License 2.0 text: https://www.apache.org/licenses/LICENSE-2.0

### Art, music, sound and fonts

* **Artwork:** every character, background, icon and effect (snow buddies, elves, reindeer, trees, presents,
  courts, lanes, launcher icon) is drawn procedurally in code for this project. No image files are shipped.
* **Music:** five original compositions written for this project ("Snowfall Waltz", "Jingle Rush", "Elf Groove",
  "Victory Bells", "Fireside"), stored as note data and synthesised on the phone. They are not arrangements of
  existing songs.
* **Sound effects:** synthesised in code at startup (no recordings).
* **Fonts:** the phone's built-in system fonts (sans-serif). No font files are shipped.

## Used only to build or test (not shipped)

| Component | Licence | Use |
|---|---|---|
| Android SDK platform `android.jar` (API 34/35), build tools (aapt2, D8/R8 8.13.17, zipalign, apksigner) | Android SDK licence / Apache 2.0 | Compile, package, sign |
| Gradle 8.14.3 + wrapper, Kotlin Gradle plugin 2.2.21 | Apache 2.0 | Build |
| kotlin-test 2.2.21 | Apache 2.0 | Unit tests |
| JUnit Jupiter / Platform 5.10.1 | Eclipse Public License 2.0 | Unit tests |
| MediaPipe Python package, ai-edge-litert | Apache 2.0 | Real-model validation (reference output, model inference on desktop) |
| OpenCV (opencv-python-headless) | Apache 2.0 | Validation scene building |
| NumPy | BSD-3-Clause | Validation |
| Pillow | MIT-CMU (HPND) | Validation; test-render contact sheet |
| MediaPipe test images (`pose.jpg`, `male_full_height_hands.jpg`, `man-woman-okay.jpg`, `living_room.jpg`) from storage.googleapis.com/mediapipe-assets | Published by Google as MediaPipe test assets (MediaPipe is Apache 2.0) | Downloaded at validation time to compose two-person test scenes. **Not committed and not shipped.** The repository only contains numeric model outputs derived from them (`core/src/test/resources/pose/`). |
| DejaVu Sans font | Bitstream Vera / DejaVu licence (permissive) | Captions on `docs/images/test_render_contact_sheet.jpg` only |

## Privacy note

The app processes camera frames in memory only, keeps no images or video and has no network permission. Player
names, settings and tournament scores are stored in the app's private storage on the phone and are removed by
uninstalling the app or by Settings -> Developer -> Reset.
