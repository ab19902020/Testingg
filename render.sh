#!/usr/bin/env bash
# Renders the full visualizer: audio analysis -> frames in parallel chunks -> join -> add the song.
# usage: ./render.sh [output.mp4]      (JOBS=4 ./render.sh to set the number of parallel chunks)
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-United_Road_Magic_Old_Trafford.mp4}"
JOBS="${JOBS:-$(nproc)}"
[ -f feat.npz ] || python3 analyze.py
N=$(python3 -c "import numpy as np; print(len(np.load('feat.npz')['rms']))")
mkdir -p build
rm -f build/part_*.mp4 build/list.txt
STEP=$(( (N + JOBS - 1) / JOBS ))
pids=()
for ((k = 0; k < JOBS; k++)); do
  A=$((k * STEP)); B=$(( (k + 1) * STEP )); [ "$B" -gt "$N" ] && B=$N
  [ "$A" -ge "$N" ] && break
  part=$(printf "build/part_%02d.mp4" "$k")
  python3 render_stadium.py chunk "$A" "$B" "$part.tmp.mp4" > "build/log_$k.txt" 2>&1 && mv "$part.tmp.mp4" "$part" &
  pids+=($!)
  echo "file '$(basename "$part")'" >> build/list.txt
done
for p in "${pids[@]}"; do wait "$p"; done
ffmpeg -y -v error -f concat -safe 0 -i build/list.txt -i song.mp3 -map 0:v -map 1:a -c:v libx264 -preset veryfast -crf 23 \
  -pix_fmt yuv420p -c:a aac -b:a 256k -shortest -movflags +faststart "$OUT"
echo "done: $OUT"
