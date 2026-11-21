# Full narrated agent mandate film

Worktree `ottochain-mandate-circuit` on the preview branch at `d3c4a10`, draft PR #219. James approved the visual direction and requested narration across the whole walkthrough. Output stays local.

| Gate | Invocation and result | Evidence |
|---|---|---|
| Narrator | Retrieved Kokoro-82M ONNX revision `1939ad2a8e416c0acfeecc08a694d14ef25f2231` and stock `af_heart` voice. Euler’s RTX 5090 reported 29,797 MiB free before synthesis. Installed cuDNN 9 in a task-specific Python environment after initial session creation reported a missing library. | Reusable model directory and pinned source in `demo/mandate-circuit/film/README.md`. |
| Ledger | `node demo/mandate-circuit/film/capture.mjs` verified all eight capture pins and records. The independent local JLVM replay agreed with the recorded verdicts. `decision.ts` verified both signatures, action 198 and revoked record 227. `tamper.ts` accepted genuine evidence and rejected all nine mutations. | `film/evidence.json`, generated from the previous release fixtures. One local operator, one snapshot source and no validator quorum check. |
| Speech | `narrate.py` used ONNX Runtime’s CUDA provider on Euler and generated narration for all eleven scenes. A local transcription check identified an ambiguous reading of “co-signer”. The final take says “designated second signer”. | `film/script.json`, `film/timing.json`, isolated local WAV. |
| Export | Motion Canvas full export with synchronized captions, gate ticks and narration. 2,897 frames at 24 fps. | `.workspace/mandate-circuit/full/ottochain-mandate-narrated.mp4`. |

Final export SHA-256 `d2748ca75fa4d8c54488cf923ffd53e04d7e53e8f92948a4dbf3b20ec3906efe`. FFprobe measured 120.708 s for video and audio. The final mix measured −16.4 LUFS integrated and −2.1 dB true peak. Local Whisper base.en transcribed the exported MP4 from the opening question through the closing replay instructions. Visually inspected representative frames from all eleven scenes and verified the final H.264 stream was byte-identical after audio mastering. Both streams decoded to completion. The full scene also passed a separate TypeScript check.

## Story edit

James requested the motivation before the mechanism. The extended cut starts with a customer waiting for a bike repair and the shop owner delegating a brake-pad order. The parts request appears again at the shipping desk. That supplier’s decision leads into the signed-invoice test. After revocation the film returns to the desk and answers the opening question using the verified $40 transaction and subsequent revocation. The opening two scenes are labeled as an illustrated scenario.

Kokoro generated the full 13-scene voice track on Euler’s RTX 5090. The illustrated setup runs from 0:00 to 0:24.625. The full timeline contains 3,648 frames at 24 fps. Latest local review path is `.workspace/mandate-circuit/story/ottochain-agent-mandate-story.mp4`. See the adjacent `narration.wav` for the isolated full-length narration.

Final story SHA-256 `ad2ac775deba016c312e451dedd5fd51fe666f89672c975a81f7256d082b9ec4`. Both streams decoded to completion and measured exactly 152 seconds. The mixed soundtrack measured −16.5 LUFS and −0.8 dB true peak. Inspected the repair stand, packing desk, test case, return to the supplier and closing answer. Whisper transcribed the opening, callback and conclusion from the exported MP4. A separate transcription of the tamper scene confirmed “one cent”.
