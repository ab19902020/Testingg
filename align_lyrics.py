"""Time every lyric line to the song with Whisper, for the stadium visualizer.

    pip install stable-ts            # best: forced alignment of the known lyrics
    # or: pip install faster-whisper   /   pip install openai-whisper
    python3 align_lyrics.py                      # song.mp3 + lyrics.txt -> lyrics_timing.json
    python3 align_lyrics.py --audio vocals.wav   # a separated vocal track aligns more reliably

With stable-ts the lyric text itself is aligned to the audio, so word order is known in advance.
With faster-whisper / openai-whisper the song is transcribed with word timestamps (the lyrics are
given as a prompt) and the recognised words are matched back to the lyric sheet with a fuzzy
sequence alignment, so repeated choruses land in the right place.
"""
import argparse, difflib, json, re, sys

SECTION_ORDER = ['chorus1', 'verse1', 'chorus2', 'verse2', 'chorus3', 'bridge', 'final', 'outro']


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
        words = [norm(w) for w in s.split() if norm(w)]
        lines.append(dict(section=sec, text=s, words=words))
    found = [l['section'] for l in lines]
    for s_ in SECTION_ORDER:
        if s_ not in found:
            raise SystemExit(f'lyrics.txt: missing section [{s_}]')
    return lines


def card_word(line):
    low = line['text'].lower()
    sung_chorus = line['section'].startswith('chorus') or line['section'] == 'final' or (
        line['section'] == 'outro' and ('magic' in low or 'pochettino' in low))
    if not sung_chorus:
        return None
    if 'magic' in low: return 'MAGIC'
    if 'pochettino' in low: return 'POCHETTINO'
    return None


def kind_of(line):
    s = line['section']
    if s.startswith('chorus') or s == 'final': return 'chorus'
    if s.startswith('verse'): return 'verse'
    if s == 'outro' and card_word(line): return 'chorus'
    return s


# ------------------------------------------------------------------ transcription back-ends
def transcribe(audio, text, model_size, backend):
    """returns a list of (word, start, end) in time order"""
    errors = []
    if backend in ('auto', 'stable'):
        try:
            import stable_whisper
            model = stable_whisper.load_model(model_size)
            res = model.align(audio, text, language='en')
            words = [(w.word, float(w.start), float(w.end)) for w in res.all_words()]
            print(f'stable-ts forced alignment: {len(words)} words', file=sys.stderr)
            return words
        except ImportError as e:
            errors.append(f'stable-ts: {e}')
            if backend == 'stable': raise
    prompt = ' '.join(text.split())[:800]
    if backend in ('auto', 'faster'):
        try:
            from faster_whisper import WhisperModel
            model = WhisperModel(model_size, device='auto', compute_type='int8')
            segs, _ = model.transcribe(audio, language='en', word_timestamps=True, initial_prompt=prompt,
                                       condition_on_previous_text=False, vad_filter=False)
            words = [(w.word, float(w.start), float(w.end)) for s in segs for w in (s.words or [])]
            print(f'faster-whisper: {len(words)} words', file=sys.stderr)
            return words
        except ImportError as e:
            errors.append(f'faster-whisper: {e}')
            if backend == 'faster': raise
    if backend in ('auto', 'openai'):
        try:
            import whisper
            model = whisper.load_model(model_size)
            res = model.transcribe(audio, language='en', word_timestamps=True, initial_prompt=prompt,
                                   condition_on_previous_text=False)
            words = [(w['word'], float(w['start']), float(w['end'])) for s in res['segments'] for w in s.get('words', [])]
            print(f'openai-whisper: {len(words)} words', file=sys.stderr)
            return words
        except ImportError as e:
            errors.append(f'openai-whisper: {e}')
    raise SystemExit('No Whisper package found. Install one of: stable-ts, faster-whisper, openai-whisper\n  ' + '\n  '.join(errors))


# ------------------------------------------------------------------ alignment
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
        li = lyric[i - 1]
        for j in range(1, m + 1):
            s = sim(li, rec[j - 1])
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
            if difflib.SequenceMatcher(None, lyric[i - 1], rec[j - 1]).ratio() >= 0.55:
                pairs[i - 1] = j - 1
            i, j = i - 1, j - 1
        elif k == 1: i -= 1
        else: j -= 1
    return pairs


def build_timing(lines, words, duration=None):
    lyr_tok, owner = [], []
    for li, l in enumerate(lines):
        for w in l['words']:
            lyr_tok.append(w); owner.append(li)
    rec_tok = [norm(w) for w, _, _ in words]
    keep = [k for k, w in enumerate(rec_tok) if w]
    rec = [rec_tok[k] for k in keep]
    times = [(words[k][1], words[k][2]) for k in keep]
    pairs = align_words(lyr_tok, rec)
    if len(pairs) < 3:
        raise SystemExit('Alignment failed: almost no lyric words were recognised.')
    # word start times: matched words exact, the rest interpolated between matched neighbours
    n = len(lyr_tok)
    st = [None] * n; en = [None] * n
    for a, b in pairs.items():
        st[a], en[a] = times[b]
    known = sorted(pairs)
    # an unmatched word takes its time from matched words in the same line first (about 0.32 s per word)
    for k in range(n):
        if st[k] is not None: continue
        same = [x for x in known if owner[x] == owner[k]]
        after = [x for x in same if x > k]; before = [x for x in same if x < k]
        if after and before:
            p, q = before[-1], after[0]; f = (k - p) / (q - p)
            st[k] = st[p] + f * (st[q] - st[p]); en[k] = st[k] + 0.3
        elif after:
            q = after[0]; prev_end = max([en[x] for x in known if x < k], default=-1e9)
            st[k] = max(st[q] - 0.32 * (q - k), prev_end + 0.05); en[k] = st[k] + 0.3
        elif before:
            p = before[-1]; st[k] = en[p] + 0.32 * (k - p) - 0.25; en[k] = st[k] + 0.3
    for k in range(n):
        if st[k] is not None: continue
        prev = max([x for x in known if x < k], default=None); nxt = min([x for x in known if x > k], default=None)
        if prev is not None and nxt is not None:
            f = (k - prev) / (nxt - prev)
            st[k] = st[prev] + f * (st[nxt] - st[prev]); en[k] = st[k] + 0.3
        elif prev is not None:
            st[k] = en[prev] + 0.35 * (k - prev); en[k] = st[k] + 0.3
        else:
            st[k] = st[nxt] - 0.35 * (nxt - k); en[k] = st[k] + 0.3
    out = []
    for li, l in enumerate(lines):
        idx = [k for k in range(n) if owner[k] == li]
        matched = sum(1 for k in idx if k in pairs)
        out.append(dict(section=l['section'], text=l['text'], kind=kind_of(l), card=card_word(l),
                        start=round(max(0.0, st[idx[0]] - 0.05), 3), word_end=round(en[idx[-1]], 3),
                        confidence=round(matched / max(1, len(idx)), 2)))
    for k, e in enumerate(out):
        nxt = out[k + 1]['start'] if k + 1 < len(out) else None
        if nxt is not None and nxt - e['word_end'] < 2.5:
            e['end'] = nxt
        else:
            e['end'] = None if nxt is None else round(e['word_end'] + 0.6, 3)
    return out


def sections_from(lines):
    first = {}
    for k, e in enumerate(lines):
        first.setdefault(e['section'], k)
    def start(sec, offset=0):
        return round(max(0.0, lines[first[sec] + offset]['start'] - 0.2), 3)
    sec = {s: start(s) for s in SECTION_ORDER}
    sec['chorus1'] = 0.0
    c3_last = max(k for k, e in enumerate(lines) if e['section'] == 'chorus3')
    sec['inst'] = round(lines[c3_last]['word_end'] + 0.4, 3)
    bridge_lines = [e for e in lines if e['section'] == 'bridge']
    sec['build'] = round(max(bridge_lines[min(2, len(bridge_lines) - 1)]['start'], sec['final'] - 8.0), 3)
    outro = [k for k, e in enumerate(lines) if e['section'] == 'outro']
    last_chorus = next((k for k in outro if lines[k]['card']), outro[-1])
    sec['lastchorus'] = round(max(0.0, lines[last_chorus]['start'] - 0.2), 3)
    sec['inst'] = min(sec['inst'], sec['bridge'] - 0.5)
    return sec


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--audio', default='song.mp3')
    ap.add_argument('--lyrics', default='lyrics.txt')
    ap.add_argument('--out', default='lyrics_timing.json')
    ap.add_argument('--model', default='medium', help='tiny/base/small/medium/large-v3 (bigger = better on singing)')
    ap.add_argument('--backend', default='auto', choices=['auto', 'stable', 'faster', 'openai'])
    ap.add_argument('--words-json', help='use a saved word list [[word,start,end],...] instead of running Whisper')
    ap.add_argument('--save-words', help='also save the recognised word list here')
    args = ap.parse_args()
    lines = parse_lyrics(args.lyrics)
    text = '\n'.join(l['text'] for l in lines)
    if args.words_json:
        words = [tuple(w) for w in json.load(open(args.words_json))]
    else:
        words = transcribe(args.audio, text, args.model, args.backend)
    if args.save_words:
        json.dump(words, open(args.save_words, 'w'), indent=0)
    timed = build_timing(lines, words)
    secs = sections_from(timed)
    json.dump(dict(source='whisper', sections=secs, lines=timed), open(args.out, 'w'), indent=1)
    print(f'{"start":>7} {"end":>7} conf  line')
    for e in timed:
        end = f"{e['end']:7.2f}" if e['end'] is not None else '    end'
        flag = '' if e['confidence'] >= 0.5 else '   <-- check'
        print(f"{e['start']:7.2f} {end} {e['confidence']:.2f}  [{e['section']}] {e['text']}{flag}")
    print('sections:', ', '.join(f'{k} {v:.1f}' for k, v in secs.items()))
    print(f'wrote {args.out}')


if __name__ == '__main__':
    main()
