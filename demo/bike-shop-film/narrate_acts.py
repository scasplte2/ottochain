"""Generate narration ONE ACT PER PASS (prosody carries across sentences, like the take the narrator picked), N takes per act.
    <voxcpm-venv>/bin/python narrate_acts.py script.json OUT_DIR REF_WAV REF_TRANSCRIPT [--takes 3] [--only ACT_ID]
Writes OUT_DIR/acts/<act>-take<k>.wav (48 kHz). Scoring/selection is select_takes.py; assembly is assemble.py.
"""
import argparse, json, os, random, time
import numpy as np
import soundfile as sf

ap = argparse.ArgumentParser()
ap.add_argument('script'); ap.add_argument('out'); ap.add_argument('ref_wav'); ap.add_argument('ref_text')
ap.add_argument('--takes', type=int, default=3); ap.add_argument('--only')
args = ap.parse_args()
script = json.load(open(args.script))
ref_text = open(args.ref_text).read().strip()
os.makedirs(os.path.join(args.out, 'acts'), exist_ok=True)
SEEDS = [2026, 1895, 7741, 3313, 9001]

def seed_all(s):
    import torch
    random.seed(s); np.random.seed(s); torch.manual_seed(s); torch.cuda.manual_seed_all(s)

from voxcpm import VoxCPM
model = VoxCPM.from_pretrained('openbmb/VoxCPM2', load_denoiser=False, optimize=False)
sr = model.tts_model.sample_rate
assert sr == 48000
for a in script['acts']:
    if not a['lines'] or (args.only and a['id'] != args.only):
        continue
    text = ' '.join(a['lines'])
    for k in range(args.takes):
        path = os.path.join(args.out, 'acts', f"{a['id']}-take{k}.wav")
        if os.path.exists(path) and not args.only:
            continue
        seed_all(SEEDS[k % len(SEEDS)] + sum(map(ord, a['id'])))
        t = time.time()
        w = model.generate(text=text, prompt_wav_path=args.ref_wav, prompt_text=ref_text, reference_wav_path=args.ref_wav,
                           cfg_value=2.0, inference_timesteps=10)
        w = np.asarray(w, dtype=np.float32).reshape(-1)
        sf.write(path, w, sr, subtype='FLOAT')
        print(f"{a['id']} take{k}: {len(w)/sr:.1f}s audio in {time.time()-t:.1f}s", flush=True)
