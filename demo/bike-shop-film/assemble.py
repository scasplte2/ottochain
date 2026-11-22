"""Assemble the selected act takes into the film narration: tighten each silent run to its natural ceiling by
script punctuation, match loudness per act, lay acts out with breathing room, and derive per-LINE timings from
the script-aligned word timestamps (for captions and animation cues).
    <voxcpm-venv>/bin/python assemble.py script.json OUT_DIR   → OUT_DIR/narration.wav, OUT_DIR/timing.json, OUT_DIR/acts-final/
"""
import json, os, sys
import numpy as np
import soundfile as sf

SR = 48000
CEIL = {'mid': 0.08, 'comma': 0.28, 'colon': 0.38, 'sentence': 0.55}
LEAD_IN, ACT_GAP, TAIL, TARGET_DB = 4.0, 1.15, 3.0, -21.0
script, out = json.load(open(sys.argv[1])), sys.argv[2]
sel = json.load(open(os.path.join(out, 'selection.json')))
os.makedirs(os.path.join(out, 'acts-final'), exist_ok=True)

def rms_db(x): return 20 * np.log10(max(1e-9, float(np.sqrt(np.mean(np.square(x))))))

def tighten(w, runs):
    """Cut the middle of each over-long silent run (keeps natural decay/onset), return audio + a time-map."""
    cuts = []  # (at_seconds_in_original, removed_seconds)
    pieces, pos = [], 0
    for r in sorted(runs, key=lambda r: r['t0']):
        ceil = CEIL[r['kind']]
        if r['dur'] <= ceil + 0.02: continue
        keep = ceil / 2
        a, b = int((r['t0'] + keep) * SR), int((r['t1'] - keep) * SR)
        if b <= a: continue
        fade = int(0.004 * SR)
        seg = w[pos:a].copy()
        if len(seg) > fade: seg[-fade:] *= np.linspace(1, 0, fade)
        pieces.append(seg)
        nxt = w[b:b + fade].copy()
        w = w.copy(); w[b:b + fade] = nxt * np.linspace(0, 1, len(nxt))
        cuts.append((r['t0'] + keep, (b - a) / SR)); pos = b
    pieces.append(w[pos:])
    return np.concatenate(pieces), cuts

def remap(t, cuts):
    return t - sum(d for at, d in cuts if t > at)

cursor, chunks, timing = LEAD_IN, [np.zeros(int(LEAD_IN * SR), np.float32)], {'acts': []}
for a in script['acts']:
    if not a['lines']:
        timing['acts'].append({'id': a['id'], 'label': a['label'], 'start': 0.0, 'end': LEAD_IN, 'lines': []}); continue
    s = sel[a['id']]
    w, sr = sf.read(os.path.join(out, 'acts', s['take']), dtype='float32'); assert sr == SR
    w, cuts = tighten(w, s['runs'])
    # trim leading/trailing silence (keep 60 ms)
    idx = np.where(np.abs(w) > 0.004)[0]
    lead = max(0, idx[0] - int(0.06 * SR)); tail = min(len(w), idx[-1] + int(0.06 * SR))
    w = w[lead:tail]
    shift = lead / SR
    n = int(0.02 * SR)
    sp = [w[i:i + n] for i in range(0, len(w) - n, n) if rms_db(w[i:i + n]) > -40]
    w = np.clip(w * 10 ** ((TARGET_DB - rms_db(np.concatenate(sp))) / 20), -0.98, 0.98).astype(np.float32)
    sf.write(os.path.join(out, 'acts-final', a['id'] + '.wav'), w, SR, subtype='FLOAT')
    # line timings from token timings
    toks = s['tokens']; known = [i for i, t in enumerate(toks) if t]
    def tok_t(i, which):
        if toks[i]: return toks[i][which]
        near = min(known, key=lambda k: abs(k - i)); return toks[near][which]
    act = {'id': a['id'], 'label': a['label'], 'start': round(cursor, 3), 'lines': []}
    base = 0
    for li, line in enumerate(a['lines']):
        nt = len(line.split())
        t0 = remap(tok_t(base, 0), cuts) - shift
        t1 = remap(tok_t(base + nt - 1, 1), cuts) - shift
        act['lines'].append({'text': line, 'start': round(cursor + max(0, t0), 3), 'end': round(cursor + min(len(w) / SR, t1), 3)})
        base += nt
    chunks.append(w); cursor += len(w) / SR
    chunks.append(np.zeros(int(ACT_GAP * SR), np.float32)); cursor += ACT_GAP
    act['end'] = round(cursor, 3)
    timing['acts'].append(act)
chunks.append(np.zeros(int(TAIL * SR), np.float32)); cursor += TAIL
timing['acts'][-1]['end'] = round(cursor, 3)
timing['duration'] = round(cursor, 3)
sf.write(os.path.join(out, 'narration.wav'), np.concatenate(chunks), SR, subtype='PCM_24')
json.dump(timing, open(os.path.join(out, 'timing.json'), 'w'), indent=1)
print(f"narration {cursor:.1f}s, {sum(len(a['lines']) for a in timing['acts'])} lines timed")
