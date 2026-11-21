# The refused invoice

For the complete narrated film, including voice generation, the timed script and local playback path, see [the walkthrough](film/README.md).

A 20-second Motion Canvas scene for the 0:40–1:00 segment of the mandate film. The invoice, action ordinal and budget come from PR #218’s recorded four-wallet run. The simulated compromised agent is the deterministic signer in that test. In the separate model transcript Qwen declined the disallowed supplier. The clip uses captions, soft gate ticks and a refusal tone. James can record the following pickup against the cut.

| Time | Founder narration |
|---|---|
| 0–4 s | “The last twenty dollars went to an approved supplier. Now the agent signs for someone else.” |
| 4–10 s | “The signature checks out. There’s money left. But the owner also named who can get paid.” |
| 10–16 s | “This supplier is missing from that list. One false condition stops the payment.” |
| 16–20 s | “The ledger records the refusal. Eighty dollars remain.” |

## Replay the explanation

From the repository root, use Node 24 and the locked dependencies.

```sh
npm ci --prefix e2e-test --no-audit --no-fund
node demo/mandate-circuit/replay.mjs
```

The script verifies the preceding record at 152 and the refusal checkpoint at 190 against the revision’s capture pins, reads the refusal receipt at 189, and checks the recorded state on both sides. It matches the active authorize guard in `definition.json` against both proved definitions. It runs the full guard and every top-level AND clause independently with the pinned TypeScript JLVM. The gate labels follow the expression shapes and order. An unknown expression or a different failure stops generation. The animation’s pulse timing illustrates this local explanation. The ledger reports “No guard matched from active on authorize (1 guards tried)”. The inset reproduces the replay command’s stdout.

## Render locally

```sh
cd demo/mandate-circuit
npm ci --no-audit --no-fund
npx playwright-core install chromium --only-shell
npm run render -- ../../.workspace/mandate-circuit/mandate-refusal.mp4
```

Use FFmpeg with H.264 and AAC encoders. An installed Chromium headless shell can be selected with `CHROMIUM_PATH`. The export streams 480 frames directly into FFmpeg and needs a fresh output filename. To edit the scene, use `npm start` in this directory. The recorded test used one snapshot source and one operator. The expiry clause passed for this request at ordinal 189. This scene makes no claim about a live expired transaction or an injection success rate.
