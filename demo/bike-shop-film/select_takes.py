"""Score each act's takes for smoothness and pick the best; also the QC gate over the assembled acts.
    <whisper-venv>/bin/python select_takes.py script.json OUT_DIR            → OUT_DIR/selection.json
    <whisper-venv>/bin/python select_takes.py script.json OUT_DIR --check    → gate over OUT_DIR/acts-final (exit 1 on fail)

Per take: Whisper (large-v3) words aligned to the SCRIPT's tokens (punctuation comes from the script), silent runs
from the waveform (10 ms frames, relative to the take's speech level), each run classified by the script
punctuation of the word before it: sentence (.?!) / comma (,;:) / mid. Score = transcript errors + excess pause
time over the natural budget per class + speaking-rate spread. Lower is better.
"""
import difflib, json, os, re, sys
import numpy as np
import soundfile as sf
from faster_whisper import WhisperModel

BUDGET = {'mid': 0.12, 'comma': 0.30, 'colon': 0.40, 'sentence': 0.65}   # natural pause ceilings (s)
GATE = {'mid': 0.22, 'comma': 0.34, 'colon': 0.50, 'sentence': 0.75}     # hard limits for the --check gate
script, out = json.load(open(sys.argv[1])), sys.argv[2]
check = '--check' in sys.argv
m = WhisperModel('large-v3', device='cuda', compute_type='float16')
norm = lambda t: re.sub(r"[^a-z0-9$']", '', t.lower().replace('-', ''))
# Known-harmless transcript variants (Whisper spells numbers as digits; homophones of names/words in the script).
SAME = {'autochain': 'ottochain', "rose's": "rosa's", 'breaks': 'brakes', 'approve': 'approved'}
PAIRS = {('rose', 'is'): "rosa's"}
NUM = {'$500': ['five', 'hundred', 'dollars'], '$100': ['a', 'hundred', 'dollars'], '100': ['a', 'hundred'],
       '$25': ['twentyfive', 'dollars'], '$20': ['twenty', 'dollars'], '20': ['twenty'], '25': ['twentyfive']}
def hyp_tokens(words):
    out = []  # (normalised token, index of the Whisper word it came from)
    for i, x in enumerate(words):
        t = norm(x.word); t = SAME.get(t, t)
        for e in NUM.get(t, [t]): out.append((e, i))
    return out
def ref_tokens(text):
    return [SAME.get(norm(t), norm(t)) for t in text.split()]

def analyse(path, text):
    w, sr = sf.read(path, dtype='float32')
    n = int(sr * 0.01)
    f = np.array([20 * np.log10(max(1e-9, float(np.sqrt(np.mean(np.square(w[i:i + n])))))) for i in range(0, len(w) - n, n)])
    quiet = f < np.percentile(f, 75) - 26
    w16 = w[(np.arange(int(len(w) * 16000 / sr)) * sr / 16000).astype(int)]
    segs, _ = m.transcribe(w16, language='en', beam_size=5, word_timestamps=True, condition_on_previous_text=False)
    words = [x for s in segs for x in s.words]
    stoks = text.split()
    hyp = hyp_tokens(words)
    ref = ref_tokens(text)
    # Whisper splits hyphenated words ('elbow deep') and hears "Rosa's" as 'Rose is': rejoin those pairs
    refset, merged, i = set(ref), [], 0
    while i < len(hyp):
        if i + 1 < len(hyp):
            pair = PAIRS.get((hyp[i][0], hyp[i + 1][0]), hyp[i][0] + hyp[i + 1][0])
            if pair in refset and hyp[i][0] not in refset:
                merged.append((pair, hyp[i][1])); i += 2; continue
        merged.append(hyp[i]); i += 1
    hyp = merged
    # the script says 'twenty-dollar'/'twenty-five' as one token; split ref tokens the same way digits expand
    sm = difflib.SequenceMatcher(a=[h for h, _ in hyp], b=ref, autojunk=False)
    w2s = {}
    for blk in sm.get_matching_blocks():
        for d in range(blk.size): w2s.setdefault(hyp[blk.a + d][1], blk.b + d)
    errors = sum(max(i2 - i1, j2 - j1) for op, i1, i2, j1, j2 in sm.get_opcodes() if op != 'equal')
    # script-token timing (start/end) via the alignment; unmatched tokens interpolate later
    tok = [None] * len(stoks)
    for wi, si in w2s.items(): tok[si] = [round(words[wi].start, 3), round(words[wi].end, 3)]
    runs = []
    first, last = np.argmax(~quiet), len(quiet) - 1 - np.argmax(~quiet[::-1])
    k = first
    while k <= last:
        if quiet[k]:
            j = k
            while j <= last and quiet[j]: j += 1
            t0, t1 = k * 0.01, j * 0.01
            # the silence's left neighbour can be credited to either of the last two words that started before it
            # (Whisper often starts the next word early); use the strongest script punctuation among them
            prev = [i for i, x in enumerate(words) if x.start < t0 - 0.02]
            cands = [w2s[i] for i in prev[-2:] if i in w2s]
            tails = [stoks[si][-1:] for si in cands]
            kind = ('sentence' if any(t in '.?!' for t in tails) else 'colon' if any(t == ':' for t in tails)
                    else 'comma' if any(t in ',;' for t in tails) else 'mid')
            after = stoks[cands[-1]] if cands else (words[prev[-1]].word.strip() if prev else '')
            runs.append({'t0': round(t0, 2), 't1': round(t1, 2), 'dur': round(t1 - t0, 2), 'kind': kind, 'after': after})
            k = j
        else:
            k += 1
    excess = sum(max(0, r['dur'] - BUDGET[r['kind']]) for r in runs)
    speech = (last - first) * 0.01 - sum(r['dur'] for r in runs)
    wps = len(stoks) / max(0.1, speech)
    score = 3 * errors + 4 * excess + 2 * abs(wps - 3.0)
    return {'score': round(score, 3), 'errors': errors, 'excess': round(excess, 2), 'wps': round(wps, 2), 'runs': runs, 'tokens': tok}

acts = [a for a in script['acts'] if a['lines']]
if check:
    failed = 0
    for a in acts:
        r = analyse(os.path.join(out, 'acts-final', a['id'] + '.wav'), ' '.join(a['lines']))
        bad = [{k: (float(v) if not isinstance(v, str) else v) for k, v in x.items()} for x in r['runs'] if x['dur'] > GATE[x['kind']]]
        print(f"{a['id']:10s} errors {r['errors']}  wps {r['wps']:.2f}  {'OK' if not bad else 'FAIL'} {bad if bad else ''}")
        failed += len(bad) + (1 if r['errors'] > 2 else 0)
    print('GATE', 'PASS' if not failed else f'FAIL ({failed})'); sys.exit(1 if failed else 0)

selection = {}
for a in acts:
    text = ' '.join(a['lines'])
    takes = sorted(p for p in os.listdir(os.path.join(out, 'acts')) if p.startswith(a['id'] + '-take'))
    scored = {p: analyse(os.path.join(out, 'acts', p), text) for p in takes}
    best = min(scored, key=lambda p: scored[p]['score'])
    selection[a['id']] = {'take': best, **scored[best], 'candidates': {p: {k: v for k, v in s.items() if k in ('score', 'errors', 'excess', 'wps')} for p, s in scored.items()}}
    print(f"{a['id']:10s} → {best}   " + '  '.join(f"{p.split('-take')[1][:-4]}:{s['score']:.2f}(err {s['errors']}, +{s['excess']}s, {s['wps']}wps)" for p, s in scored.items()), flush=True)
json.dump(selection, open(os.path.join(out, 'selection.json'), 'w'), indent=1)
