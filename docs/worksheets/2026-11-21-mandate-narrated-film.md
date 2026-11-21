# Full narrated agent mandate film

Worktree `ottochain-mandate-circuit` on the preview branch at `d3c4a10`, draft PR #219. James approved the visual direction and requested narration across the whole walkthrough. Output stays local.

| Gate | Invocation and result | Evidence |
|---|---|---|
| Narrator | Retrieved Kokoro-82M ONNX revision `1939ad2a8e416c0acfeecc08a694d14ef25f2231` and stock `af_heart` voice. Euler’s RTX 5090 reported 29,797 MiB free before synthesis. Installed cuDNN 9 in a task-specific Python environment after initial session creation reported a missing library. | Reusable model directory and pinned source in `demo/mandate-circuit/film/README.md`. |
| Ledger | `node demo/mandate-circuit/film/capture.mjs` verified all eight capture pins and records. The independent local JLVM replay agreed with the recorded verdicts. `decision.ts` verified both signatures, action 198 and revoked record 227. `tamper.ts` accepted genuine evidence and rejected all nine mutations. | `film/evidence.json`, generated from the previous release fixtures. One local operator, one snapshot source and no validator quorum check. |
| Speech | `narrate.py` used ONNX Runtime’s CUDA provider on Euler and generated narration for all eleven scenes. A local transcription check identified an ambiguous reading of “co-signer”. The final take says “designated second signer”. | `film/script.json`, `film/timing.json`, isolated local WAV. |
| Export | Motion Canvas full export with synchronized captions, gate ticks and narration. 2,897 frames at 24 fps. | `.workspace/mandate-circuit/full/ottochain-mandate-narrated.mp4`. |

Final export SHA-256 `d2748ca75fa4d8c54488cf923ffd53e04d7e53e8f92948a4dbf3b20ec3906efe`. FFprobe measured 120.708 s for video and audio. The final mix measured −16.4 LUFS integrated and −2.1 dB true peak. Local Whisper base.en transcribed the exported MP4 from the opening question through the closing replay instructions. Visually inspected representative frames from all eleven scenes and verified the final H.264 stream was byte-identical after audio mastering. Both streams decoded to completion. The full scene also passed a separate TypeScript check.
