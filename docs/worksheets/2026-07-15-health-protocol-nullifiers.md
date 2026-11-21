# 2026-07-15 — health-protocol-nullifiers

<!-- metadata.type: project -->
<!-- Started from bin/worksheet. Update at EVERY stopping point, not just at the end. -->

## Goal
PR-4 of the protocol-nullifier-set rollout: migrate the riverdale-health e2e lane from
app-authored `stateData` nullifier maps to the protocol nullifier set (`_consumeNullifier` →
`NullifierCombiner`), add the `assertNullifier` runner action, and keep the lane green.

## Context links
- RFCs / docs: docs/proposals/protocol-nullifier-set.md (§2 "what this buys riverdale-health",
  §4 rollout PR-4)
- Chain subsystem: #214 (protocol nullifier set — state/effect token/surface, on main)
- Health lane: #213 (riverdale-health ZK medical-privacy lane, on main)
- SDK: sdk#262 (verifyStateProof already ports the absence arm; presence used here)
- Branch: feat/health-protocol-nullifiers (branched off fresh origin/main @ 7ada01f)
- Tier: T2 (e2e defs + runner + docs; no consensus-surface change)

## Plan
- [x] migrate record.definition.json (drop `!has` guard clause + nullifiers stateData; emit
      `_consumeNullifier` with the pv nullifier word)
- [x] migrate dispense.definition.json (drop `!has` guard + spentNullifiers; emit
      `_consumeNullifier` with the ring nullifier's x-coordinate)
- [x] drop `nullifiers`/`spentNullifiers` from the initial-data files
- [x] confirm allowedEffects: no dial needed (FiberPolicy default None ⇒ all families)
- [x] add `assertNullifier` runner action (nf / nfFromEvent / nfFromEventField; expectSpent)
- [x] wire assertNullifier steps into example.json
- [x] update README + riverdale-health-e2e-design.md guarantee tables + caveats
- [x] MetaHandler checkpoint monocle-focus cleanup (separate commit)
- [x] tsc --noEmit (node 20) + sbt scalafmtCheckAll + sharedData/test + currencyL0/compile
- [x] full local e2e green

## State of play (running log — newest at the bottom, timestamped)
- Migration is fixture-neutral: M5 exprHash binds the IN-GUEST effect, event payloads unchanged.
  Verified the chain extractor accepts array-item sub-expressions and NullifierHex strips an
  optional 0x — so `cat("0x", substr(pv, 66, 64))` (record) and `substr(event.nullifier, 2, 64)`
  (dispense x-only) are both valid nf items.
- x-only dispense consumption also kills ±Nf malleability (negated G1 point shares x → collides).
- Guarantee tables + caveats flipped to "protocol-enforced (NullifierCombiner)"; the
  "no protocol nullifier set" caveat (privacy-handoff P0.1) marked RESOLVED with the residual
  Phase-B absence-proof gap named.
- `tsc --noEmit` clean; `sbt scalafmtCheckAll "currencyL0/compile" "sharedData/test"` → 668/668.
- Local cluster via the OTTOCHAIN runbook (throwaway ./tessellation clone, compose-runner,
  DATA_PEERS_COUNT=2 + DATA_L1_TIME_TRIGGER_INTERVAL="8 seconds"). Lane run:
  E2E_CONCURRENCY=1 E2E_ORDINAL_THRESHOLD=30 E2E_MAX_RESUBMITS=4 → **PASS 1/1, 20 steps, 147.9s**.
  Both spent presence proofs verified client-side (record 12 / record 59 = spend ordinals),
  the never-consumed nf held 404 across the budget. Cluster torn down.

## Blockers
- None.

## Decisions needing a human or senior model
- None (no blast-radius file touched; validators/combiner unchanged — defs + runner + docs only).

## Handoff notes (so a fresh session can resume)
- 3 commits on feat/health-protocol-nullifiers, NOT pushed, NO PR (per task):
  1. feat(e2e): assertNullifier runner action — verified nullifier proofs
  2. feat(e2e): riverdale-health consumes the protocol nullifier set
  3. refactor(l0): monocle focus for the checkpoint nullifier slim
- assertNullifier queries GET /v1/nullifiers/{domain}/{nf}; domain = the consuming fiber's alias.
  expectSpent=true verifies verifyStateProof(resp, `nullifier/${domain}/${nf}`); expectSpent=false
  requires a 404 across a short fixed budget (absence proofs = Phase B).
- Local infra clones (tessellation/, tess-jars/) are in .git/info/exclude — do NOT commit them.

## Outcome
- Migration complete, lane green locally. Commits local-only per instructions; James merges/pushes.
