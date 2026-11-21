"""Generate sentence-aligned narration from the stock Kokoro af_heart ONNX voice."""
import argparse
import json
import os
from pathlib import Path

import espeakng_loader
import numpy as np
import onnxruntime as ort
import soundfile as sf
from phonemizer.backend import EspeakBackend
from phonemizer.backend.espeak.wrapper import EspeakWrapper

parser = argparse.ArgumentParser()
parser.add_argument("model", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
here = Path(__file__).resolve().parent
vocab = json.loads((args.model / "tokenizer.json").read_text())["model"]["vocab"]
voices = np.fromfile(args.model / "af_heart.bin", dtype=np.float32).reshape(-1, 256)
os.environ["ESPEAK_DATA_PATH"] = espeakng_loader.get_data_path()
EspeakWrapper.set_library(espeakng_loader.get_library_path())
phonemizer = EspeakBackend("en-us", preserve_punctuation=True, with_stress=True)
opts = ort.SessionOptions()
opts.intra_op_num_threads = 4
session = ort.InferenceSession(str(args.model / "model.onnx"), opts, providers=["CUDAExecutionProvider", "CPUExecutionProvider"])
assert "CUDAExecutionProvider" in session.get_providers(), "Expected the CUDA execution provider"
print("Inference providers:", session.get_providers(), flush=True)
sessions = json.loads((here / "script.json").read_text())
sr = 24000
fps = 24
samples_per_frame = sr // fps
audio = []
timing = []
frame = 0
for section in sessions:
    first = frame
    captions = []
    for line in section["lines"]:
        spoken = line.replace("OttoChain", "Otto Chain").replace("co-signer", "cosigner")
        phone = phonemizer.phonemize([spoken], strip=True)[0]
        for src, dest in [("tʃ", "ʧ"), ("dʒ", "ʤ"), ("r", "ɹ"), ("ɫ", "l"), ("g", "ɡ"), ("ʲ", "j"), ("oʊ", "O"), ("aɪ", "I"), ("aʊ", "W"), ("eɪ", "A"), ("ɔɪ", "Y"), ("’", "'")]:
            phone = phone.replace(src, dest)
        tokens = [vocab[c] for c in phone if c in vocab]
        assert 10 < len(tokens) < len(voices), line
        inputs = {"input_ids": np.array([[0, *tokens, 0]], np.int64),
                  "style": voices[len(tokens):len(tokens)+1], "speed": np.array([0.98],np.float32)}
        waveform = session.run(None, inputs)[0].reshape(-1)
        assert np.isfinite(waveform).all() and np.max(np.abs(waveform)) > 0.01
        # Trim only outer silence. Keep the model's natural internal pauses.
        voiced = np.flatnonzero(np.abs(waveform) > 0.006)
        waveform = waveform[max(0, voiced[0]-1500):voiced[-1]+1800]
        silence = 6400 if line == section["lines"][-1] else 3800
        frames = int(np.ceil((waveform.size + silence) / samples_per_frame))
        captions.append({"frame":frame, "text":line})
        audio.append(np.pad(waveform,(0,frames*samples_per_frame-waveform.size)))
        frame += frames
    timing.append({"id":section["id"], "start":first, "end":frame, "captions":captions})
    print(section["id"], round(first/fps,2), round(frame/fps,2),flush=True)
sf.write(args.output / "narration.wav", np.concatenate(audio), sr)
(args.output / "timing.json").write_text(json.dumps({"fps":fps,"frames":frame,"sections":timing},indent=2)+"\n")
