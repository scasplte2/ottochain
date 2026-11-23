# OttoChain — a bike-shop story

A 3-minute film, a 60-second vertical cut and an interactive web board, all replaying **one recorded run**
of [`e2e-test/examples/bike-shop`](../../e2e-test/examples/bike-shop/example.ts): a registry-pinned purchasing
mandate, typed store credit, an atomic pay→order cascade, a refused \$500 invoice (4 of 9 rules fail), a
spawned delivery tracker that signals back, a zero-knowledge credit check, a composed repair kit, a soulbound
warranty, revocation, and anyone's laptop verifying the record. Nothing on screen is hand-typed: numbers,
verdicts and snapshot ordinals come from `film-data.json`, derived from the recording.

**Honesty notes (also on screen / in the board footer):** local devnet with one operator; validator-signature
checking is the next milestone; the ZK step uses a pre-generated proof bundle; narration is AI-generated in
James Aman's voice with his recorded consent.

## Pipeline

| Step | Command | Output |
|---|---|---|
| 1. Record | `runner.ts --target local --only bike-shop --wallets maya,agent,spoke,guild,rosa --parallel false` with `e2e-test/examples/bike-shop/record.ts` running alongside | gap-free snapshot timeline (kept outside the repo; ~38 MB) |
| 2. Capture proofs | `examples/agent-mandate/verify-mandate.ts capture …` per fiber, while the chain is live | `e2e-test/examples/bike-shop/evidence/*.bundle.json` |
| 3. Derive | `node derive.mjs <timeline.jsonl>` (per-clause JLVM replay of the poisoned invoice must agree with the ledger) | `film-data.json` |
| 4. Narrate | `<voxcpm-venv>/bin/python narrate_acts.py script.json output/voice-v4 <ref.wav> <ref-transcript.txt> --takes 3` (one pass per act), `<whisper-venv>/bin/python select_takes.py script.json output/voice-v4` (smoothest take per act), `<voxcpm-venv>/bin/python assemble.py script.json output/voice-v4`, then `select_takes.py … --check` (pause gate); copy `output/voice-v4/timing.json` to `timing.json` | `output/voice-v4/narration.wav`, `timing.json` |
| 5. Sound | `node sfx-film.mjs output/film-sfx.wav`, then `ffmpeg -i <narration.wav> -i output/film-sfx.wav -filter_complex "[0:a]loudnorm=I=-16:TP=-1.5:LRA=9,aresample=48000,pan=stereo\|c0=c0\|c1=c0[v];[1:a]volume=0.55[s];[v][s]amix=inputs=2:duration=longest:normalize=0,alimiter=limit=0.89[a]" -map "[a]" -ar 48000 output/film-mix.wav` | `output/film-mix.wav` |
| 6. Film | `node render.mjs output/film.mp4 <timing.json duration> 30 output/film-mix.wav` | 1920×1080 |
| 7. Vertical | `node cut.mjs output/film-mix.wav output/vertical-mix.wav` then `VERTICAL=1 node render.mjs output/vertical.mp4 <dur> 30 output/vertical-mix.wav` | 1080×1920 |
| 8. Board | `ffmpeg -i output/film-mix.wav -c:a aac output/film-mix.m4a && npx vite build --config vite.board.config.ts` | `dist/board/` (static) |

Rendering streams Motion Canvas frames from headless Chromium into FFmpeg (`CHROMIUM_PATH` selects an
installed headless shell). `npm start` opens the Motion Canvas editor. The voice reference recording and any
voice model stay on the narrator's machine; only generated narration is used.

## Files

- `script.json` / `script.md` — the narration (source of truth) and a readable copy with per-act evidence.
- `scenes/film.tsx` — the board, camera, acts and overlays; exports `drawFrame` (board page) and the
  vertical cut's edit list. `scenes/kit.ts`, `scenes/icons.ts` — drawing kit and line-art.
- `scenes/vertical.tsx` — the 60-second re-framing; `cut.mjs` applies the same edit list to the audio.
- `board.html`, `board/main.ts` — the interactive replay. `scenes/styleframe.tsx` — the approved style frame.
