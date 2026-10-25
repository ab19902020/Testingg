# Building Living Room Games

The project builds with Gradle and a normal Android SDK. It does **not** use the Android Gradle Plugin (AGP):
`app/build.gradle.kts` calls the SDK tools (aapt2, D8, zipalign, apksigner) directly. This was necessary because
the build environment could not reach Google's Maven repository, where AGP is published. Everything else comes
from Maven Central.

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 or newer | Bytecode targets Java 17. The shipped APK was built with OpenJDK 21.0.12. |
| Gradle | 8.14.3 | Via the included wrapper (`./gradlew`), downloaded automatically. |
| Android SDK platform | `platforms;android-35` | Compile SDK 35. |
| Android SDK build tools | `build-tools;35.0.0` | Provides `aapt2`, `lib/d8.jar`, `zipalign`, `apksigner`. |
| Internet (first build only) | | Maven Central for Kotlin/TFLite, Google Cloud Storage for the two pose models. |

### Dependencies (all resolved by Gradle)

| Dependency | Version | Where | Used for |
|---|---|---|---|
| Kotlin Gradle plugin / `kotlin-stdlib` | 2.2.21 | Gradle Plugin Portal / Maven Central | Language + runtime (shipped in the APK) |
| `org.tensorflow:tensorflow-lite` | 2.16.1 | Maven Central | Pose model inference (shipped) |
| `org.tensorflow:tensorflow-lite-api` | 2.16.1 | Maven Central | (shipped) |
| `org.tensorflow:tensorflow-lite-gpu` | 2.16.1 | Maven Central | GPU delegate (shipped) |
| `org.tensorflow:tensorflow-lite-gpu-api` | 2.16.1 | Maven Central | (shipped) |
| `kotlin-test-junit5` / JUnit Jupiter | 2.2.21 / 5.10.1 | Maven Central | Unit tests only |
| MediaPipe `pose_landmarker_lite.task` (float16, v1) | SHA-256 `59929e1d…690d574a` | storage.googleapis.com/mediapipe-models | Bundled model (shipped) |
| MediaPipe `pose_landmarker_full.task` (float16, v1) | SHA-256 `5134a3aa…a839011b1` | storage.googleapis.com/mediapipe-models | Bundled model (shipped) |
| MediaPipe `hand_landmark_lite.tflite` | SHA-256 `d7fde8ac…ef5b2a48` | storage.googleapis.com/mediapipe-assets | Bundled model (shipped), finger tracking |

The models are downloaded once into `.model-cache/` (git-ignored), checked against the SHA-256 values in
`app/build.gradle.kts`, unpacked and stored **uncompressed** in the APK so TFLite can memory-map them. After
installation the game needs no network at all.

## Build

```bash
# 1. Tell the build where the SDK is (either way works)
export ANDROID_HOME=$HOME/Android/Sdk            # or:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 2. Make sure the SDK pieces are installed
sdkmanager "platforms;android-35" "build-tools;35.0.0"

# 3. Tests (pure JVM, no device needed)
./gradlew :core:test

# 4. APK
./gradlew :app:assembleDebugApk
# -> app/build/outputs/apk/LivingRoomGames-debug.apk

# 5. Install on a connected phone (USB debugging on)
adb install -r app/build/outputs/apk/LivingRoomGames-debug.apk
```

On Windows use `gradlew.bat`; the build picks `aapt2.exe`, `zipalign.exe` and `apksigner.bat` automatically.

### What `assembleDebugApk` does

1. `extractAars` - unpacks the TFLite AARs (classes + native libraries).
2. `fetchPoseModels` - downloads and checksum-verifies the models, extracts the two `.tflite` files of each pose
   model and copies the hand landmark model.
3. `processManifest`, `aapt2Compile`, `aapt2Link` - resources and manifest (`minSdk 29`, `targetSdk 35`).
4. Kotlin compile of `:core` and `:app` against `android.jar` (no JDK classes, so only Android APIs can be used).
5. `dex` - D8 (`--debug`, `--min-api 29`; no shrinking) into a single `classes.dex`.
6. `packageApk` - zip with `resources.arsc` and models stored, native libs for `arm64-v8a` and `x86_64`.
7. `zipalignApk` (`-p 4`), `signApk` (APK Signature Scheme v2 + v3, debug key), `verifyApk` (apksigner).
8. `verifyApkContents` - fails the build if the models or native libraries are missing or if the APK would request
   the INTERNET permission.

### Signing

Only debug signing is configured. The debug keystore is created (standard `android`/`androiddebugkey`
credentials) in `~/.android/debug.keystore`, **outside the project**, exactly like Android Studio does. No keystore,
password or signing config is stored in the repository (`*.jks`, `*.keystore`, `keystore.properties` are
git-ignored). For a release build, sign the aligned APK yourself with `apksigner sign --ks <your keystore> …`.

### Settings you can override

All in `gradle.properties` or as `-P` options: `clrg.compileSdk` (35), `clrg.buildToolsVersion` (35.0.0),
`clrg.minSdk` (29), `clrg.targetSdk` (35), `clrg.versionCode`, `clrg.versionName`, `clrg.abis`
(`arm64-v8a,x86_64`). Individual tools can be pointed elsewhere with `clrg.androidJar`, `clrg.resourceLinkJar`,
`clrg.aapt2`, `clrg.d8Jar`, `clrg.zipalign`, `clrg.apksigner`.

## Optional: real-model validation of the pose pipeline

`RealModelPipelineTest` runs the app's Kotlin multi-person pipeline with the real BlazePose models (inference via
a small Python TFLite helper) on two-person test images and compares it with the official MediaPipe
`PoseLandmarker`. It is skipped in a plain `./gradlew :core:test`. To run it (Linux/macOS, Python 3.10+):

```bash
tools/pose-validation/run_real_model_tests.sh
```

The script creates a virtualenv (`mediapipe`, `ai-edge-litert`, `opencv-python-headless`, `numpy`, `pillow`),
downloads MediaPipe's public test images and the models, builds the test scenes and the reference output, then
runs the test. Its working files go to `build/pose-validation/`.

## How the shipped APK was built (this environment)

The sandbox that produced `dist/LivingRoomGames-debug.apk` had no access to dl.google.com, so the SDK
was assembled by hand in `/opt/android-sdk-min` (referenced from the git-ignored `local.properties`):

| Piece | Source used | Version |
|---|---|---|
| `platforms/android-35/android.jar` | github.com/Sable/android-platforms (copy of the SDK stub jar) | API 35, SHA-256 `4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b` |
| `platforms/android-34/android.jar` | same | API 34, SHA-256 `6cea1df3efb77103ac3e2beb9bf4718964b0e0869ab16d39d29d5cbae1c147ad` |
| `build-tools/35.0.0/lib/d8.jar` | storage.googleapis.com/r8-releases (official R8 release `r8lib.jar`) | D8/R8 8.13.17 |
| `aapt2`, `zipalign`, `apksigner` | Ubuntu packages (`aapt`, `zipalign`, `apksigner`) | aapt2 2.19-debian, apksigner 0.9 (Debian) |
| Maven Central | through Google's official mirror (`maven-central.storage-download.googleapis.com`), via a machine-local Gradle init script that is not part of the project | |

Because Ubuntu's aapt2 cannot read the API 35 resource table, resources were linked against the API 34 jar
(`clrg.resourceLinkJar` in `local.properties`) while code was compiled against API 35. With a normal SDK
(`build-tools;35.0.0` from Google) neither workaround is needed.

