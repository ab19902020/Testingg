# Building ColourFilm

## Requirements
* JDK 17 or newer (built here with OpenJDK 21)
* Android SDK with `platforms;android-35` and `build-tools;35.0.0` (set `sdk.dir` in `local.properties` or `ANDROID_HOME`)
* Internet access to Maven Central and Google Maven (first build)

The Gradle wrapper (Gradle 8.14.3) is included. Plugin versions: AGP 8.7.3, Kotlin 2.0.21,
Compose BOM 2024.12.01, Media3 1.5.1, ONNX Runtime 1.29.0.

## Commands
```bash
./gradlew :core:test                      # unit tests for the colourisation core (JVM)
./gradlew :app:assembleSnapdragonRelease  # arm64 APK with Qualcomm NPU support  (≈68 MB)
./gradlew :app:assembleUniversalRelease   # CPU-only APK for any Android 10+ phone (≈55 MB)
```
APKs land in `app/build/outputs/apk/<flavor>/release/`. Release builds are signed with the
Android debug key so they install directly; use your own keystore before distributing.

CI: `.github/workflows/android.yml` runs the unit tests and builds both APKs on every push,
uploading them as a workflow artifact.

## Flavors
* **snapdragon** (`colourfilm.app`): `onnxruntime-android-qnn` + Qualcomm QNN HTP libraries
  (V69/V73/V75/V79/V81 = Snapdragon 8 Gen 1 … 8 Elite Gen 5, 7-series with those NPUs).
  At first run the app compiles the model for the NPU, checks NPU output against the CPU on a
  real photo and uses the NPU only if it agrees and is faster. Otherwise it uses the CPU.
* **universal** (`colourfilm.app.universal`): plain `onnxruntime-android` (CPU), all ABIs. Can be
  installed side by side with the Snapdragon build.

## Desktop benchmark / sample generator
Runs the exact same Kotlin core and ONNX model over any file ffmpeg can read:
```bash
tools/fetch_model.sh models
./gradlew :bench:installDist
bench/build/install/bench/bin/bench models/ddcolor.onnx input.mp4 output.mp4 --speed BALANCED
python3 tools/evaluate.py groundtruth_colour.mp4 mine=output.mp4   # metrics when a colour original exists
```

## Device / emulator end-to-end tests
```bash
tools/run_device_tests.sh models universal     # or: snapdragon (on a real Snapdragon phone)
```
This pushes the model to `/data/local/tmp`, installs the app and test APKs, and runs
`app/src/androidTest/.../PipelineTest.kt` (MediaCodec decode → model → H.264 encode → MP4 with audio).
