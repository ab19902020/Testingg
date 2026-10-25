#!/usr/bin/env bash
# Reproduces the real-model validation of the Kotlin pose pipeline on a desktop (Linux, Python 3.10+).
# 1) creates a venv with mediapipe + ai-edge-litert, 2) downloads the MediaPipe test images (Apache-2.0 test
# assets) and the pose models, 3) builds two-person test scenes and the official MediaPipe reference output,
# 4) runs core's RealModelPipelineTest with the real models.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="${WORK:-$ROOT/build/pose-validation}"
mkdir -p "$WORK/imgs" "$WORK/models"
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install -q mediapipe ai-edge-litert numpy opencv-python-headless pillow
for f in pose.jpg male_full_height_hands.jpg man-woman-okay.jpg living_room.jpg; do
  [ -f "$WORK/imgs/$f" ] || curl -sSf -o "$WORK/imgs/$f" "https://storage.googleapis.com/mediapipe-assets/$f"
done
for m in lite full; do
  t="$WORK/models/pose_landmarker_$m.task"
  [ -f "$t" ] || curl -sSf -o "$t" "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_$m/float16/1/pose_landmarker_$m.task"
  mkdir -p "$WORK/models/$m" && (cd "$WORK/models/$m" && unzip -o -q "../pose_landmarker_$m.task")
done
cd "$WORK"
export CLRG_IMGS="$WORK/imgs" CLRG_MODELS="$WORK/models"
"$WORK/venv/bin/python" "$HERE/make_scenes.py"
"$WORK/venv/bin/python" "$HERE/mp_reference.py"
cd "$ROOT"
./gradlew :core:test --tests '*RealModelPipelineTest*' \
  -Pclrg.validation.python="$WORK/venv/bin/python" -Pclrg.validation.models="$WORK/models" -Pclrg.validation.scenes="$WORK"
