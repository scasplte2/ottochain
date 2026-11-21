# 2026-11-21 — bike-shop-film

<!-- metadata.type: project -->
<!-- Started from bin/worksheet. Update at EVERY stopping point, not just at the end. -->

## Goal
A ~3-minute 16:9 film (plus a 60-s 9:16 cut and an interactive web board) that tells the bike-shop
story and showcases the registry, agent mandates, typed assets, cross-fiber triggers/spawn and ZK —
every on-screen mechanic replayed from ONE recorded e2e run (`e2e-test/examples/bike-shop/`).
Narration in James's cloned voice (local only; reference audio + model never committed or uploaded).

## Context links
- PRs: #218 (agent-mandate, merged), #219 (mandate-circuit demo, merged)
- Prior films: ottochain-mandate-circuit worktree (Codex, Kokoro stock voice, Motion Canvas)
- Branch: feat/bike-shop-film (worktree ../ottochain-bike-shop-film, off origin/main 3019b9a)
- Tier: T2 (e2e example + runner tweaks + demo dir). No consensus/blast-radius files planned.

## Decisions (James, 2026-11-21)
- Voice: zero-shot clone from 3–5 min reference in ~/voice-reference/ (outside repos). Disclose AI narration.
- Otto the otter: on-screen guide at act transitions; never the fooled agent.
- Deliverables: 3-min hero 16:9, 60-s vertical cut, interactive web board.
- Evidence: one recorded bike-shop run (no stitched examples).

## Design — recorded run (draft, pre-advisor)
Wallets: maya (shop owner), agent (purchasing agent), spoke (supplier), guild (template author), rosa (customer).
Fibers: mandate (maya; bound to `purchasing-mandate.machine@1.0.0`), order-desk (spoke),
delivery (spawned child of order-desk), trade-account (zk-eligibility pattern).
Assets: shop-credit (28 Fungible), brake-pads (20 T|C), tune-up voucher (22 T|C|E), warranty (0 Soulbound).

Acts → steps:
1. Registry: guild publishes purchasing-mandate 1.0.0; maya creates mandate pinned to it.
2. Assets in: mint $100 credit to maya wallet → FRACTIONALIZE → transfer pieces into mandate fiber
   (fiber custody moves only whole instances via `_transferAsset` — R1).
3. Mandate: agent `authorize` $20 to spoke — guard binds `heldAssets[piece].amount == amount`;
   effect `_transferAsset` piece → order-desk + `_triggers` order-desk `receive_order`.
4. Cross-fiber: order-desk `receive_order` `_spawn`s delivery; spoke `dispatch` moves pads+voucher to
   maya wallet; delivery `deliver` `_triggers` mandate `delivery_received`.
5. Poisoned invoice: $500 to unlisted payee → refused; order-desk unchanged.
6. ZK: maya proves score ≥ 700 on trade-account (reuse zk-eligibility SP1 Groth16 fixture as-is).
7. Asset types: maya COMPOSEs pads+voucher → kit (meet = 22, inherits E); transfers kit to rosa;
   rosa's warranty (soulbound) transfer → refused.
8. Registry: guild publishes 1.1.0; mandate stays pinned at 1.0.0.
9. Revoke; agent retry refused. Verifier (reuse agent-mandate verify-mandate.ts) on the order record.
Flight recorder: poll ML0 each snapshot during the run → evidence/timeline.json for the film.

## Open risks to verify early
- wallet→fiber Transfer allowed? (AssetCombiner.applyTransfer recipient)
- can a TRIGGERED transition `_spawn`?
- runner has no COMPOSE confirm path (needs compositeId existence poll)
- zk-eligibility fixture still valid at current metakit pin (is it in a green CI lane?)

## State of play
- 08:55 — worktree + worksheet created. Inspected riverdale-economy (runner actions: create/publishVersion/
  createAssetPolicy/mintAsset/applyMorphism/processEvent/upgradeFiber/assert*), ContextProvider
  heldAssets {behavior, amount, expiresAt}, ApplyMorphism fields (otherAssetIds, compositeId, shardIds).
- Voice-model research delegated to a background agent (licence of WEIGHTS must allow commercial use).

- 14:50 — `sbt "sharedData/testOnly *BikeShopSuite"` 5/5 PASS (combiner level, real definition files):
  wallet→fiber Transfer OK; Fractionalize = even split; dynamic `{"var":{"cat":["heldAssets.",…,".amount"]}}`
  works; authorize→receive_order cascade is ATOMIC (failed callee guard rolls back the payer); dispatch
  spawns delivery (owners inherited) and moves pads to owner wallet; deliver `_triggers` mandate
  `delivery_received` gated on `$caller`; Compose pads(20)+voucher(22) → kit bits 22, transferable.
- FINDING A: `_spawn` inside a CASCADED (triggered) transition is silently ignored
  (TriggerHandler.scala:116 documents it; no rejection/receipt). Restaged: spawn moved to spoke-signed
  `dispatch`. Candidate issue: make it loud (rejection) per EffectDirectiveLoudness principle.
- FINDING B: a callee's `heldAssets` does not see an asset `_transferAsset`ed by its caller in the same
  cascade. Restaged: payment-custody check moved to `dispatch` (a later primary transition).
- Soulbound Transfer is refused at the L1 structural gate (AssetOpCombinerSuite:130) → on a cluster it
  is a DL1 rejection (never enters a block), not a RejectionReceipt. Label it so in the film.
- Voice research (subagent, primary-source licences): shortlist VoxCPM2 (Apache-2.0), Qwen3-TTS 1.7B
  Base (Apache-2.0), Ming-3.0-tts-0.5B (MIT); excluded NC weights (XTTS, F5, Fish S1/S2, Spark…);
  Chatterbox retired Aug 2026. Plan: blind A/B on James's clip; LoRA (VoxCPM2) if needed.

- 15:05 — CHECKPOINT 2 (style frame, done before the cluster run per advisor sequencing):
  `demo/bike-shop-film/` (Motion Canvas 3.17.2, Inter/JetBrains Mono via @fontsource 5.3.0 OFL).
  `node render.mjs output/styleframe-v3.mp4 20 30 output/styleframe-sfx.wav` with
  `CHROMIUM_PATH=~/.cache/ms-playwright/chromium_headless_shell-1248/*/chrome-headless-shell` → 600 frames,
  ~80 s render. Data = #218's replay-verified `demo/mandate-circuit/scene-data.json`. `sfx.mjs` synthesises
  the sound bed with FFmpeg expressions (no samples). Output dir gitignored.
- Voice reference received: `~/voice-reference/james-2026-11-21.m4a` (4:56, 48 kHz, peak −4.6 dB). Private
  dir (700), never committed/uploaded. Needs transcript (local Whisper) for VoxCPM2/Qwen3 reference text.

- 15:15–16:05 — DEVNET (task-owned, ~/devnets/bike-shop): tessellation develop 20aa1c6 clone (infra only,
  `compose-runner.sh` patched to reuse an existing `tessellation_common` net — that network pre-existed,
  created 2026-09-18, 0 containers, NOT ours, never removed), release JARs v4.0.0-rc.10 sha256-verified,
  metagraph JARs from this branch. Skipped `just up` (its install_dependencies.sh edits ~/.bashrc and
  installs nvm). `SKIP_STREAMING=true`, API ports on 127.0.0.1. Reset = ~/devnets/bike-shop/reset.sh
  (removes ONLY gl0-0/ml0-0/dl1-{0,1,2} after checking their compose working dir).
- Baseline at this HEAD: zk-eligibility 2/2 PASS, agent-mandate 15/15 PASS (Node 20 tsx runner).
- Runner fix: morphism-CREATED assets (Fractionalize shards, Compose composite) are now gated on DL1
  commit-index ingestion before the next step (was: AssetNotFound at DL1 on a fresh shard). COMPOSE
  confirms via source removal like BURN/DECOMPOSE.
- RUN 3 PASS: `runner.ts --target local --only bike-shop --wallets maya,agent,spoke,guild,rosa
  --parallel false` → 55/55 in 631.7 s. Poisoned $500 refused (receipt ordinal 177); revoked refusal 394;
  soulbound resale DL1-rejected `MorphismNotPermitted`. Flight recorder: ordinals 1–399, no gaps (raw 38 MB
  kept OUT of repo at ~/devnets/bike-shop/evidence-raw/timeline-run3.jsonl). Verifier bundles captured
  live for mandate/desk/delivery/trade (evidence/*.bundle|pin.json); offline replay passes 4 checks.
- `demo/bike-shop-film/derive.mjs` → film-data.json (51 events). Poisoned invoice per-clause JLVM replay:
  ✗ per-order limit, ✗ budget, ✗ approved supplier, ✗ credit match (5 pass) — James chose "several checks
  fail" for the film (keep $500).
- Issues filed: #222 (cascade `_spawn` silently dropped), #223 (proposal: fiber split-and-send +
  cascade heldAssets visibility).
- Voice A/B (subagent): ~/voice-lab/ab/{A,B,C}(-take2).wav + reference-clip.wav, blind key in
  KEY-do-not-open.txt. VoxCPM2 / Qwen3-TTS 1.7B / Ming-3.0-tts 0.5B all ran (torch 2.8 cu128). ~23 GB
  under ~/voice-lab (losers' venvs ~13 GB to remove after James picks).

## Next
1. James reviews the style frame (look/pace/Otto).
2. Cluster baseline (zk-eligibility, riverdale-economy, agent-mandate) then the `bike-shop` example run.
3. Voice A/B: VoxCPM2 vs Qwen3-TTS vs Ming-3.0 on the same 20–30 s slice + paragraph.

## Blockers
- Voice reference recording from James (needed only at narration stage).

## PR / watch
- [ ] PR opened: #___
- [ ] `bin/pr-watch <pr#>` exited 0
- Agents never merge — merge is James's.

## Cleanup ledger (task-owned resources to remove at the end)
- (none yet)
