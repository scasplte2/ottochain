# Narrated mandate walkthrough

The eleven scenes follow the recorded mandate from issuance through revocation and offline supplier verification. The local model’s two invoices are separate runs. The compromised-agent submission is the signed four-wallet fixture. Read `script.json` for the complete spoken copy. The display uses the same sentence boundaries as the voice track.

## Local playback and render

Euler’s review copy is `.workspace/mandate-circuit/full/ottochain-mandate-narrated.mp4`. The adjacent `narration.wav` is the isolated, 24 kHz voice track. The recorded video uses Motion Canvas, 1920 × 1080 at 24 fps, H.264 and AAC. Replay the receipt, snapshot and tamper checks on Node 24 before exporting.

```sh
# From the repo root
npm ci --prefix e2e-test --no-audit --no-fund
npm ci --prefix demo/mandate-circuit --no-audit --no-fund
node demo/mandate-circuit/film/capture.mjs
cd demo/mandate-circuit
# Point CHROMIUM_PATH at the local headless Chromium binary if needed.
node render.mjs ../../.workspace/mandate-circuit/full/review-copy.mp4 --film ../../.workspace/mandate-circuit/full
```

The second directory argument holds `narration.wav` and its matching `timing.json`. The exporter streams frames to FFmpeg, mixes the local narration with the gate sounds and checks the frame count. Use a fresh output filename.

## Regenerate the narration

The stock US English voice is `af_heart`. Model and voice files were downloaded from the ONNX conversion at revision `1939ad2a8e416c0acfeecc08a694d14ef25f2231`. Euler keeps these reusable files in `/home/euler/models/kokoro-82m-narration-1939ad2`. [Original Kokoro weights](https://huggingface.co/hexgrad/Kokoro-82M) · [Pinned ONNX files](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/tree/1939ad2a8e416c0acfeecc08a694d14ef25f2231)

Use Python 3.12 with `onnxruntime-gpu==1.32.0`, `espeakng-loader==0.2.4`, `phonemizer-fork==3.3.2`, `numpy==2.5.5` and `soundfile==0.14.0`. This Linux build needs CUDA 13 and a loadable cuDNN 9 library. Euler used cuDNN 9.28.1.4. The generator checks that CUDA is enabled and times each cue from its actual waveform.

```sh
# From the repo root, with the narration environment active
python demo/mandate-circuit/film/narrate.py /home/euler/models/kokoro-82m-narration-1939ad2 .workspace/mandate-circuit/new-voice
cp .workspace/mandate-circuit/new-voice/timing.json demo/mandate-circuit/film/timing.json
```

Use that new voice directory in the render command. The renderer compares its timings to the scene timeline before encoding.
