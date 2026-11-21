"""Transcribe every generated narration line locally (faster-whisper large-v3) and diff it against the script.
    <whisper-venv>/bin/python asr_check.py script.json OUT_DIR   → prints per-line WER, writes OUT_DIR/asr.json
"""
import json, os, re, sys, difflib
import soundfile as sf
from faster_whisper import WhisperModel

script, out = json.load(open(sys.argv[1])), sys.argv[2]
NUM = {'five hundred dollars': '$500', 'twenty five dollars': '$25', 'twenty dollars': '$20', 'a hundred dollars': '$100'}
def norm(t):
    t = t.lower().replace('-', ' ')
    t = re.sub(r"\$(\d+)", lambda m: {'500': 'five hundred', '25': 'twenty five', '20': 'twenty', '100': 'a hundred'}.get(m.group(1), m.group(1)) + ' dollars', t)
    t = t.replace('ai ', 'ai ').replace('a.i.', 'ai')
    return re.sub(r"[^a-z0-9' ]", ' ', t).split()
m = WhisperModel('large-v3', device='cuda', compute_type='float16')
report = []
for a in script['acts']:
    for i, text in enumerate(a['lines']):
        key = f"{a['id']}-{i}"
        w, sr = sf.read(os.path.join(out, 'lines', key + '.wav'), dtype='float32')
        if sr != 16000:
            import numpy as np
            idx = (np.arange(int(len(w) * 16000 / sr)) * sr / 16000).astype(int)
            w = w[idx]
        segs, _ = m.transcribe(w, language='en', beam_size=5, condition_on_previous_text=False)
        hyp_t = ' '.join(s.text.strip() for s in segs)
        ref, hyp = norm(text), norm(hyp_t)
        ops = [o for o in difflib.SequenceMatcher(a=ref, b=hyp).get_opcodes() if o[0] != 'equal']
        n = sum(max(i2 - i1, j2 - j1) for _, i1, i2, j1, j2 in ops)
        diffs = [(op, ' '.join(ref[i1:i2]), ' '.join(hyp[j1:j2])) for op, i1, i2, j1, j2 in ops]
        report.append({'key': key, 'wer': n / max(1, len(ref)), 'hyp': hyp_t, 'diffs': diffs})
        print(f"{'OK ' if n == 0 else 'CHK'} {key:12s} WER {n/max(1,len(ref)):5.1%}  {diffs if n else ''}", flush=True)
json.dump(report, open(os.path.join(out, 'asr.json'), 'w'), indent=1)
