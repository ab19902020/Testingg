# Old Trafford visualizer: "Magic" (Mauricio Pochettino)

A music visualizer for the United Road Pochettino song. It's a 3D night-time Old Trafford bowl that reacts to the track:

- **Seats as an equaliser.** Every seat in the bowl lights up in bars (bass in the middle of the far stand).
- **Lyrics on the LED boards.** The pitchside boards and the lower tier ribbon show each line word by word, and the corner screens scroll it.
- **Card display.** In the choruses the upper tier spells out "MAGIC" and "POCHETTINO" round the bowl.
- **Rain and lights.** Rain falls through the floodlight beams, the pitch is wet and reflective, and the floodlights flash red and white on the beat.
- **Moving camera.** It does a 360° pan, a touchline track, crane moves, an orbit and a low run along the boards.

![chorus](previews/chorus_card_display.jpg)

## Files

| file | what it is |
|---|---|
| `song.mp3` | the track |
| `lyrics.txt` | lyric sheet, one display line per row, grouped by `[section]` |
| `lyrics_timing.json` | when each line starts and ends, plus the section start times the camera and lights follow |
| `align_lyrics.py` | uses Whisper to time `lyrics.txt` against the song and writes `lyrics_timing.json` |
| `analyze.py` | audio analysis (bass, spectrum, beats) to `feat.npz` |
| `render_stadium.py` | the renderer (numpy + OpenCV, CPU only) |
| `render.sh` | full render: analysis, parallel chunks, join, add the song |
| `fonts/` | Anton, Archivo Black, DejaVu (open licences included) |

## 1. Time the lyrics with Whisper

The `lyrics_timing.json` in this branch is an **estimate** made without speech recognition, so some lines are out of sync. To fix it:

```bash
pip install -r requirements.txt
pip install stable-ts            # or faster-whisper / openai-whisper
python3 align_lyrics.py --model medium
```

It prints every line with its start time and a match confidence. Lines marked `<-- check` were hard to hear. Re-running with `--model large-v3` usually helps. A vocals-only track helps even more:

```bash
pip install demucs
demucs --two-stems=vocals song.mp3
python3 align_lyrics.py --audio separated/htdemucs/song/vocals.wav
```

You can also edit `lyrics_timing.json` by hand. Each line has `start`/`end` in seconds, and `sections` sets when the camera cuts and the light show changes.

## 2. Render

```bash
./render.sh                        # writes United_Road_Magic_Old_Trafford.mp4 (1080p30)
python3 render_stadium.py still 6 52 80   # quick check frames at those seconds
```

On a 2-core machine it takes about 1.1 s per frame per core, so roughly 50 minutes for the full 2:50. More cores split it further (`JOBS=8 ./render.sh`).
