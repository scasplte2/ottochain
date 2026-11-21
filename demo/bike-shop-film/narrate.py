"""Generate the film narration line-by-line with VoxCPM2 voice cloning, then lay it out on the film timeline.

    <voxcpm-venv>/bin/python narrate.py script.json OUT_DIR REF_WAV REF_TRANSCRIPT [--only ACT_ID:LINE_IDX] [--seed-bump N]

Writes OUT_DIR/lines/<act>-<i>.wav (48 kHz) and, once every line exists, OUT_DIR/narration.wav plus
OUT_DIR/timing.json (act + line start/end seconds). The reference recording is private: pass its path at
run time; nothing about the speaker is stored in the repository. Each line is generated in ONE pass (its
sentences share prosody) with a fixed per-line seed, so a re-run reproduces it; `--only` + `--seed-bump`
regenerates a single line that failed the ASR check.
"""
import argparse, json, os, random, time
import numpy as np
import soundfile as sf

SEED = 2026          # James picked take 2 (seed 2026) in the blind A/B
LEAD_IN = 4.0        # cold open: visuals only
LINE_GAP = 0.38      # pause between lines inside an act
ACT_GAP = 1.1        # breathing room between acts (visual transitions)
TAIL = 3.0           # end card after the last line

ap = argparse.ArgumentParser()
ap.add_argument('script'); ap.add_argument('out'); ap.add_argument('ref_wav'); ap.add_argument('ref_text')
ap.add_argument('--only'); ap.add_argument('--seed-bump', type=int, default=0)
args = ap.parse_args()
script = json.load(open(args.script))
ref_text = open(args.ref_text).read().strip()
lines_dir = os.path.join(args.out, 'lines'); os.makedirs(lines_dir, exist_ok=True)

def seed_all(s):
    import torch
    random.seed(s); np.random.seed(s); torch.manual_seed(s); torch.cuda.manual_seed_all(s)

todo = []
for a in script['acts']:
    for i, text in enumerate(a['lines']):
        key = f"{a['id']}-{i}"
        path = os.path.join(lines_dir, key + '.wav')
        if args.only and args.only != f"{a['id']}:{i}":
            continue
        if args.only or not os.path.exists(path):
            todo.append((key, text, path, hash(key) % 1000))

if todo:
    from voxcpm import VoxCPM
    model = VoxCPM.from_pretrained('openbmb/VoxCPM2', load_denoiser=False, optimize=False)
    sr = model.tts_model.sample_rate
    assert sr == 48000, sr
    for key, text, path, _ in todo:
        seed_all(SEED + sum(map(ord, key)) + 7919 * args.seed_bump)
        t = time.time()
        w = model.generate(text=text, prompt_wav_path=args.ref_wav, prompt_text=ref_text,
                           reference_wav_path=args.ref_wav, cfg_value=2.0, inference_timesteps=10)
        w = np.asarray(w, dtype=np.float32).reshape(-1)
        sf.write(path, w, sr, subtype='FLOAT')
        print(f"{key}: {len(w)/sr:.2f}s audio in {time.time()-t:.1f}s", flush=True)

# Lay out the timeline once every line exists.
sr = 48000
missing = [f"{a['id']}-{i}" for a in script['acts'] for i, _ in enumerate(a['lines'])
           if not os.path.exists(os.path.join(lines_dir, f"{a['id']}-{i}.wav"))]
if missing:
    print('missing lines:', missing); raise SystemExit(0)

def trim(w, thresh=0.004, pad=0.05):
    idx = np.where(np.abs(w) > thresh)[0]
    if not len(idx): return w
    a, b = max(0, idx[0] - int(pad * sr)), min(len(w), idx[-1] + int(pad * sr))
    return w[a:b]

cursor, chunks, timing = LEAD_IN, [np.zeros(int(LEAD_IN * sr), np.float32)], {'acts': []}
for ai, a in enumerate(script['acts']):
    act = {'id': a['id'], 'label': a['label'], 'start': round(cursor, 3), 'lines': []}
    for i, text in enumerate(a['lines']):
        w, wsr = sf.read(os.path.join(lines_dir, f"{a['id']}-{i}.wav"), dtype='float32')
        assert wsr == sr
        w = trim(w)
        act['lines'].append({'text': text, 'start': round(cursor, 3), 'end': round(cursor + len(w) / sr, 3)})
        chunks.append(w); cursor += len(w) / sr
        gap = LINE_GAP if i < len(a['lines']) - 1 else ACT_GAP
        chunks.append(np.zeros(int(gap * sr), np.float32)); cursor += gap
    if not a['lines']:
        act['start'] = 0.0
    act['end'] = round(cursor, 3)
    timing['acts'].append(act)
chunks.append(np.zeros(int(TAIL * sr), np.float32)); cursor += TAIL
timing['acts'][-1]['end'] = round(cursor, 3)
timing['duration'] = round(cursor, 3)
wav = np.concatenate(chunks)
sf.write(os.path.join(args.out, 'narration.wav'), wav, sr, subtype='PCM_24')
json.dump(timing, open(os.path.join(args.out, 'timing.json'), 'w'), indent=1)
print(f"narration.wav {cursor:.1f}s; timing.json written")
