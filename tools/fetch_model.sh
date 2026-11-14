#!/usr/bin/env bash
# Downloads the exact model archive the app uses and verifies it (for desktop benchmarks / device tests).
set -euo pipefail
OUT=${1:-models}
mkdir -p "$OUT" && cd "$OUT"
URL=https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/ddcolor/releases/v0.66.0/ddcolor-onnx-float.zip
curl -fL -o ddcolor-onnx-float.zip "$URL"
echo "512e788cab02db6796ede174af0db2302247130284a3a00eab9aafddafa48a49  ddcolor-onnx-float.zip" | sha256sum -c -
unzip -o -j ddcolor-onnx-float.zip '*/ddcolor.onnx' '*/ddcolor.data'
