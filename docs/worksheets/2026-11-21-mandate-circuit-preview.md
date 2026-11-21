# Mandate circuit preview

Base `fecafae` from PR #218. Build the 20-second payee-refusal scene for visual review. Evaluate the active authorize guard and its individual clauses against the captured pre-state, request, signer addresses and ordinal. Check the receipt and stable budget against the two captured records. Render a local film with readable captions and keep the original recording.

- `node demo/mandate-circuit/replay.mjs` verified both record checkpoints and returned 7/8 local JLVM clauses passing. The signed $20 request named “other”. Its receipt reports failure at 189. The authenticated record at 190 retains $80.
- `npm run render -- ../../.workspace/mandate-circuit/mandate-refusal.mp4` with Euler’s installed Chromium headless shell produced 480 frames, 1920×1080 H.264 and AAC, both 20.000 seconds. FFmpeg decoded both streams and a five-frame inspection covered the opening, wire animation, refusal, clause detail and closing proof inset.
- SHA-256 of the local preview is `0b40b6c239358079d76e48274d09b178a3a1c329828c3fd72fff01d5209b0988`. The model previously declined the disallowed invoice. The animation explicitly labels the recorded signer fixture as a simulated compromised agent. Independent-node verification and a live expired transaction are outside this cut. Founder narration is provided as a timed script for recording.
