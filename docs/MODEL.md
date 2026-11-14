# Colourisation engine: choice, sources and licences

## What ColourFilm uses

| Item | Value |
|---|---|
| Model | **DDColor** ("DDColor: Towards Photo-Realistic Image Colorization via Dual Decoders", ICCV 2023), checkpoint `ddcolor_paper_tiny` (ConvNeXt-T encoder, 56.3 M parameters) |
| Original code + weights | https://github.com/piddnad/DDColor (Apache-2.0), weights at https://huggingface.co/piddnad/DDColor-models |
| Mobile export used | Qualcomm AI Hub Models **v0.66.0**, ONNX float export, https://huggingface.co/qualcomm/DDColor (Apache-2.0, points to the DDColor licence) |
| Download URL (used by the app on first run) | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/ddcolor/releases/v0.66.0/ddcolor-onnx-float.zip` |
| Archive SHA-256 (pinned in the app) | `512e788cab02db6796ede174af0db2302247130284a3a00eab9aafddafa48a49` (206,251,342 bytes) |
| Files used | `ddcolor.onnx` (graph, 2.75 MB) + `ddcolor.data` (weights, 225,312,128 bytes) |
| Input / output | 1×3×256×256 grey RGB in 0..1 → 1×2×256×256 Lab a/b (OpenCV Lab units) |
| Runtime | ONNX Runtime 1.29.0 (`onnxruntime-android-qnn` in the Snapdragon build, `onnxruntime-android` in the universal build) |
| NPU libraries | Qualcomm QNN runtime 2.42.0 (`com.qualcomm.qti:qnn-runtime`, pulled in by `onnxruntime-android-qnn`) |

`tools/fetch_model.sh` downloads and verifies exactly the same archive for desktop work.

## Why DDColor

Requirements were: real per-object colour estimates (skin, hair, clothing, grass, sky…), natural
rather than vivid output, a permissive licence with weights actually available, and something a
2024 phone can run.

* **DDColor tiny** – Apache-2.0 code and weights, state-of-the-art naturalness for its size, and
  Qualcomm has already exported and profiled it for Snapdragon NPUs: **48.7 ms per frame on a
  Snapdragon 8 Gen 3 NPU via ONNX Runtime** and 39.3 ms via QNN (Qualcomm's published figures,
  *not measured by me*). Chosen.
* DDColor large (ConvNeXt-L, 912 MB) – better in some scenes but ~4× the compute and memory; not
  practical for full films on a phone.
* DeOldify (MIT) – good results but the video model is ~870 MB and prone to a brownish cast;
  heavier than DDColor tiny.
* Zhang et al. (ECCV16/SIGGRAPH17, BSD) – lightweight but noticeably desaturated/sepia.
* Video-specific research models (DeepRemaster, BiSTNet, ColorMNet, TCVC, …) – mostly
  non-commercial licences, very heavy, and/or need reference frames. Not suitable.
* Generative video models – excluded by the brief (they can alter people and action).

Temporal consistency is therefore provided by ColourFilm's own motion-aware propagation layer
(`core/.../TemporalColouriser.kt`), not by the model.

## Measured on real footage (desktop, same Kotlin code and ONNX file)

See `docs/TEST_REPORT.md` for the full tables. In short, on a 4-core x86 cloud VM with
ONNX Runtime CPU: 263–280 ms per model run. On colour test footage converted to greyscale, the
model gives natural grass, sky, skin and building colours; typical failure cases are
multi-coloured clothing (a red/blue striped football shirt becomes solid red) and occasional
odd colours on small objects (an orange stripe on a grey lamp post).

## Licences of shipped/downloaded components

| Component | Licence | Notes |
|---|---|---|
| DDColor code & weights | Apache-2.0 | downloaded at runtime, not bundled |
| Qualcomm AI Hub export | Apache-2.0 (model card) | downloaded at runtime |
| ONNX Runtime | MIT | bundled |
| Qualcomm QNN runtime libraries | Qualcomm "AI Stack License" | bundled in the Snapdragon APK only. The licence allows use in your own applications but not standalone redistribution of the libraries. Fine for personal use; review before publishing the app. |
| AndroidX / Compose / Media3 | Apache-2.0 | bundled |
| Probe image (`assets/probe_grey_256.bin`) | OpenCV sample `building.jpg` (OpenCV samples licence) | used only to compare NPU vs CPU output |
