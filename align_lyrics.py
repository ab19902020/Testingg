"""Time every lyric line and every word to the song, for the stadium visualizer.

Whisper on its own is good at hearing *which* words are sung, but its timestamps drift on sung
vocals (held notes pull the next word early by up to a second). So this does what WhisperX does,
on a separated vocal track, and cross-checks the result:

  1. vocals     Demucs (htdemucs, two stems) pulls the lead vocal out of the mix  -> build/vocals.wav
  2. whisper    faster-whisper large-v3 transcribes it with word timestamps      -> alignment/whisper_words.json
  3. windows    the transcript is matched to lyrics.txt to find where each [section] is sung
  4. ctc        wav2vec2 CTC forced alignment of the lyric text inside each window, with two
                acoustic models: LV60K-960h (main) and MMS-FA (second opinion)    -> alignment/ctc_*.json
  5. consensus  main CTC word starts; a word whose first letter is stranded far ahead of the rest is
                moved to where the second model puts it. Writes lyrics_timing.json (per-word times).

usage:
  python3 align_lyrics.py              # full run: about 10 minutes on 4 CPU cores
  python3 align_lyrics.py --reuse      # rebuild lyrics_timing.json from alignment/*.json (seconds)

needs: torch, torchaudio, faster-whisper, demucs  (see README)
lyrics.txt should be written as it is actually sung: the aligner places the words you give it.
"""
import argparse, difflib, json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, 'alignment')
SECTION_ORDER = ['chorus1', 'verse1', 'chorus2', 'verse2', 'chorus3', 'bridge', 'final', 'outro']
LEAD = 0.15          # a line appears this long before its first word is sung
HOLD = 1.2           # and stays this long after its last word when the next line is far away
MAIN, SECOND = 'WAV2VEC2_ASR_LARGE_LV60K_960H', 'MMS_FA'


def norm(w):
    return re.sub(r"[^a-z0-9']", '', w.lower().replace('’', "'"))


def parse_lyrics(path):
    lines, sec = [], None
    for raw in open(path, encoding='utf-8'):
        s = raw.strip()
        if not s or s.startswith('#'):
            continue
        m = re.match(r'^\[(\w+)\]$', s)
        if m:
            sec = m.group(1).lower(); continue
        if sec is None:
            raise SystemExit('lyrics.txt: put a [section] header before the first line')
        lines.append(dict(section=sec, text=s, words=s.split()))
    found = {l['section'] for l in lines}
    for s_ in SECTION_ORDER:
        if s_ not in found:
            raise SystemExit(f'lyrics.txt: missing section [{s_}]')
    return lines


def card_word(line):
    if line['section'] in ('verse1', 'verse2', 'bridge'):
        return None
    low = line['text'].lower()
    if 'magic' in low: return 'MAGIC'
    if 'pochettino' in low: return 'POCHETTINO'
    return None


def kind_of(line):
    s = line['section']
    if s.startswith('chorus') or s == 'final': return 'chorus'
    if s.startswith('verse'): return 'verse'
    if s == 'outro' and card_word(line): return 'chorus'
    return s


def decode(path, sr=16000):
    import numpy as np
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-ac', '1', '-ar', str(sr), '-f', 'f32le', '-'],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32).copy()


# ------------------------------------------------------------------ 1. vocals
def separate_vocals(song):
    out = os.path.join(HERE, 'build', 'vocals.wav')
    if os.path.exists(out):
        return out
    tmp = os.path.join(HERE, 'build', 'demucs')
    print('separating the vocal with demucs ...', file=sys.stderr)
    subprocess.run([sys.executable, '-m', 'demucs.separate', '--two-stems=vocals', '-n', 'htdemucs', '-o', tmp, song], check=True)
    name = os.path.splitext(os.path.basename(song))[0]
    os.replace(os.path.join(tmp, 'htdemucs', name, 'vocals.wav'), out)
    return out


# ------------------------------------------------------------------ 2. whisper
def whisper_words(audio, model_size):
    from faster_whisper import WhisperModel
    print(f'transcribing with faster-whisper {model_size} ...', file=sys.stderr)
    model = WhisperModel(model_size, device='cpu', compute_type='int8')
    segs, _ = model.transcribe(decode(audio), language='en', word_timestamps=True, beam_size=5,
                               condition_on_previous_text=False, vad_filter=False)
    return [(w.word.strip(), round(w.start, 3), round(w.end, 3)) for s in segs for w in (s.words or [])]


# ------------------------------------------------------------------ 3. windows
def align_words(lyric, rec):
    """global alignment of lyric tokens to recognised tokens; returns {lyric_index: rec_index}"""
    n, m = len(lyric), len(rec)
    GAP_L, GAP_R = -0.45, -0.25
    sim = lambda a, b: difflib.SequenceMatcher(None, a, b).ratio() if a and b else 0.0
    score = [[0.0] * (m + 1) for _ in range(n + 1)]
    back = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1): score[i][0] = i * GAP_L; back[i][0] = 1
    for j in range(1, m + 1): score[0][j] = j * GAP_R; back[0][j] = 2
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            s = sim(lyric[i - 1], rec[j - 1])
            mt = score[i - 1][j - 1] + (2 * s - 0.8 if s >= 0.55 else -1.2)
            a = score[i - 1][j] + GAP_L
            b = score[i][j - 1] + GAP_R
            if mt >= a and mt >= b: score[i][j], back[i][j] = mt, 0
            elif a >= b: score[i][j], back[i][j] = a, 1
            else: score[i][j], back[i][j] = b, 2
    pairs = {}
    i, j = n, m
    while i > 0 and j > 0:
        k = back[i][j]
        if k == 0:
            if sim(lyric[i - 1], rec[j - 1]) >= 0.55:
                pairs[i - 1] = j - 1
            i, j = i - 1, j - 1
        elif k == 1: i -= 1
        else: j -= 1
    return pairs


def match_whisper(lines, whisper):
    """Whisper start time for each lyric word (None where it heard something else)"""
    flat = [norm(w) for l in lines for w in l['words']]
    rec = [(norm(w), s) for w, s, _ in whisper if norm(w)]
    pairs = align_words(flat, [r[0] for r in rec])
    return [rec[pairs[k]][1] if k in pairs else None for k in range(len(flat))]


def section_windows(lines, wh, duration):
    """where each [section] is sung, padded but kept clear of the neighbouring sections' words"""
    sec_of = [l['section'] for l in lines for _ in l['words']]
    secs = []
    for l in lines:
        if l['section'] not in secs: secs.append(l['section'])
    span = {}
    for s_ in secs:
        ts = [t for t, s2 in zip(wh, sec_of) if s2 == s_ and t is not None]
        if len(ts) < 2:
            raise SystemExit(f'could not find [{s_}] in the transcript; check lyrics.txt against the song')
        span[s_] = (min(ts), max(ts))
    win = {}
    for k, s_ in enumerate(secs):
        a, b = span[s_]
        lo = a - 2.0 if k == 0 else max(a - 2.0, span[secs[k - 1]][1] + 1.0)
        hi = duration if k == len(secs) - 1 else min(b + 3.0, span[secs[k + 1]][0])
        win[s_] = [round(max(0.0, lo), 2), round(min(duration, hi), 2)]
    return secs, win


# ------------------------------------------------------------------ 4. CTC forced alignment
def ctc_align(audio, jobs, bundle_name):
    """jobs: [(section, [a, b], [words])] -> {section: [[word, start, end, [[char_start, char_end], ...]], ...]}"""
    import torch, torchaudio
    torch.set_num_threads(max(1, os.cpu_count() or 1))
    bundle = getattr(torchaudio.pipelines, bundle_name)
    mms = bundle_name == 'MMS_FA'
    print(f'CTC alignment with {bundle_name} ...', file=sys.stderr)
    model = bundle.get_model().eval()
    dic = bundle.get_dict() if mms else {c: i for i, c in enumerate(bundle.get_labels())}
    sr = bundle.sample_rate
    x = torch.from_numpy(decode(audio, sr))
    out = {}
    for sec, (a, b), words in jobs:
        with torch.inference_mode():
            em, _ = model(x[int(a * sr):int(b * sr)][None])
            em = torch.log_softmax(em, -1)
        fdur = (b - a) / em.shape[1]
        tk, owner = [], []
        for wi, w in enumerate(words):
            if not mms and wi > 0:
                tk.append(dic['|']); owner.append(-1)
            for c in (re.sub(r"[^a-z]", '', w.lower()) if mms else re.sub(r"[^A-Z']", '', w.upper())):
                if c in dic: tk.append(dic[c]); owner.append(wi)
        ali, sc = torchaudio.functional.forced_align(em, torch.tensor([tk], dtype=torch.int32), blank=0)
        spans = torchaudio.functional.merge_tokens(ali[0], sc[0].exp())
        chars = [[] for _ in words]
        for ti, sp in enumerate(spans):
            if owner[ti] >= 0:
                chars[owner[ti]].append([round(a + sp.start * fdur, 3), round(a + sp.end * fdur, 3)])
        out[sec] = [[w, cs[0][0], cs[-1][1], cs] for w, cs in zip(words, chars)]
    return out


# ------------------------------------------------------------------ 5. consensus
def consensus(lines, secs, main, second, wh):
    """[start, end, agreed] per lyric word, in order"""
    res, k = [], 0
    for s_ in secs:
        m_, s2 = main[s_], second[s_]
        bad = sum(1 for a, b in zip(m_, s2) if abs(a[1] - b[1]) > 1.0)
        healthy = bad < 0.2 * len(m_)          # MMS loses its place on some long sections
        for j, (w, st, en, chars) in enumerate(m_):
            ref = s2[j][1] if healthy else wh[k]
            if len(chars) >= 3 and chars[1][0] - chars[0][1] > 0.35 and ref is not None:
                # first letter stranded ahead of the rest (it latched onto the previous held note):
                # keep whichever start the second opinion supports
                alt = chars[1][0] - 0.08
                if abs(alt - ref) < abs(st - ref):
                    st = alt
            if ref is None: ok = False
            elif healthy: ok = abs(st - ref) < 0.35
            else: ok = -1.2 < ref - st < 0.35       # Whisper alone runs early after held notes
            res.append([st, en, ok])
            k += 1
    for k in range(1, len(res)):               # starts in order
        res[k][0] = max(res[k][0], res[k - 1][0] + 0.04)
    for k in range(len(res)):                  # ends: at least 0.12 s, at most 3 s, never past the next word
        nxt = res[k + 1][0] if k + 1 < len(res) else res[k][1]
        res[k][1] = round(min(max(res[k][1], res[k][0] + 0.12), res[k][0] + 3.0, max(nxt, res[k][0] + 0.12)), 3)
        res[k][0] = round(res[k][0], 3)
    return res


def build_timing(lines, words_t):
    out, k = [], 0
    for l in lines:
        ws = []
        for w in l['words']:
            ws.append([w, words_t[k][0], words_t[k][1]]); k += 1
        agree = sum(1 for j in range(k - len(ws), k) if words_t[j][2]) / len(ws)
        out.append(dict(section=l['section'], text=l['text'], kind=kind_of(l), card=card_word(l),
                        start=round(max(0.0, ws[0][1] - LEAD), 3), end=None, sung_end=ws[-1][2],
                        agreement=round(agree, 2), words=ws))
    for k, e in enumerate(out):
        nxt = out[k + 1]['start'] if k + 1 < len(out) else None
        e['end'] = nxt if (nxt is not None and nxt - e['sung_end'] < 2.5) else round(e['sung_end'] + HOLD, 3)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--song', default=os.path.join(HERE, 'song.mp3'))
    ap.add_argument('--vocals', help='a separated vocal track (skips demucs)')
    ap.add_argument('--lyrics', default=os.path.join(HERE, 'lyrics.txt'))
    ap.add_argument('--out', default=os.path.join(HERE, 'lyrics_timing.json'))
    ap.add_argument('--model', default='large-v3', help='Whisper model (large-v3 hears sung words best)')
    ap.add_argument('--reuse', action='store_true', help='reuse alignment/*.json instead of running the models')
    args = ap.parse_args()
    lines = parse_lyrics(args.lyrics)
    os.makedirs(CACHE, exist_ok=True)
    os.makedirs(os.path.join(HERE, 'build'), exist_ok=True)
    cpath = lambda n: os.path.join(CACHE, n)
    duration = float(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', args.song],
                                    capture_output=True, text=True, check=True).stdout)
    vocals = None
    def get_vocals():
        nonlocal vocals
        vocals = vocals or args.vocals or separate_vocals(args.song)
        return vocals
    if args.reuse and os.path.exists(cpath('whisper_words.json')):
        whisper = [tuple(w) for w in json.load(open(cpath('whisper_words.json')))]
    else:
        whisper = whisper_words(get_vocals(), args.model)
        json.dump(whisper, open(cpath('whisper_words.json'), 'w'), indent=0)
    wh = match_whisper(lines, whisper)
    secs, win = section_windows(lines, wh, duration)
    jobs = [(s_, win[s_], [w for l in lines if l['section'] == s_ for w in l['words']]) for s_ in secs]
    ctc = {}
    for name in (MAIN, SECOND):
        p = cpath(f'ctc_{name.lower()}.json')
        cached = json.load(open(p)) if os.path.exists(p) else None
        if args.reuse and cached and cached['lyrics'] == [l['text'] for l in lines] and cached['windows'] == win:
            ctc[name] = cached['words']
        else:
            ctc[name] = ctc_align(get_vocals(), jobs, name)
            json.dump(dict(lyrics=[l['text'] for l in lines], windows=win, words=ctc[name]), open(p, 'w'), indent=0)
    timed = build_timing(lines, consensus(lines, secs, ctc[MAIN], ctc[SECOND], wh))
    json.dump(dict(source=f'faster-whisper {args.model} + wav2vec2 CTC forced alignment on the demucs vocal (align_lyrics.py)',
                   lines=timed), open(args.out, 'w'), indent=1)
    print(f'{"start":>7} {"end":>7} agree  line')
    for e in timed:
        flag = '' if e['agreement'] >= 0.5 else '   <-- check'
        print(f"{e['start']:7.2f} {e['end']:7.2f} {e['agreement']:.2f}  [{e['section']}] {e['text']}{flag}")
    print(f'wrote {args.out}')


if __name__ == '__main__':
    main()
