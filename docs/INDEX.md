# docs/ index

The map of OttoChain's documentation. Agents: navigate the codebase's knowledge through this index —
find the doc, read the doc, then the code. One line per file: `path — summary`. Keep it current when
you add or move a doc.

Start here: `../CLAUDE.md` (invariants) · `signing-canonical-and-validation.md` (rationale) ·
`../AGENTS.md` (workflow router) · `agents/` (the agent-infra system).

## Agent infrastructure — `docs/agents/`
- `agents/README.md` — how the agent-infra system works (personas, worksheets, feedback, bin/, tiers).
- `agents/blast-radius.md` — consensus-critical file:why list + tier map + escalation protocol.
- `agents/conventions.md` — the residue linters can't hold (codec/validation/style/commit rules).
- `agents/tools.md` — how to write bin/ scripts + the bin/ + just + ~/bin toolbelt + sbt gotchas.
- `agents/process-observations.md` — candid, evidence-based workflow critique.
- `agents/feedback.md` — append-only log of what slowed agents down + proposed fixes.
- `agents/review-personas/consensus-safety.md` — signed messages, validators, combiners, determinism.
- `agents/review-personas/wire-compat.md` — OpenAPI contract, SDK/JAR/metakit version lockstep, e2e coupling.
- `agents/review-personas/state-growth-determinism.md` — unbounded state, 512KB cap, gas, SortedMap.
- `agents/review-personas/asset-economics.md` — supply conservation, consent, custody, nonce linearity.
- `agents/review-personas/ai-smells-test-integrity.md` — dead abstractions, tautological/self-regenerating tests.

## Worksheets — `docs/worksheets/`
- `worksheets/README.md` — purpose, naming, trailer/tag conventions, worksheet-first rule.
- `worksheets/TEMPLATE.md` — the worksheet template (copy via `bin/worksheet <slug>`).
- `worksheets/2026-07-11-onchain-v2.md` — retro trace of the OnChain-v2 session (#210/#211).
- `worksheets/2026-07-11-agent-infra-scaffold.md` — this scaffold's session trace.

## Signed-message / consensus rationale (top level)
- `signing-canonical-and-validation.md` — the 3 CLAUDE.md invariants expanded + InvalidSignature / TOCTOU mechanics.

## Top-level
- `README.md` — documentation hub / start-here.
- `introduction.md` — what OttoChain is: a metagraph turning JSON into executable multi-party workflows.
- `API-REFERENCE.md` — all HTTP endpoints across the layer stack (ports, ML0/DL1 routes).
- `TOPOS-FIBER-CATEGORICAL-ASSESSMENT.md` — read-only categorical investigation of fibers-as-topos.
- `openapi-ml0.{json,yaml}`, `openapi-dl1.{json,yaml}` — generated OpenAPI 3.1 contracts (CI drift-gate output).

## Architecture — `docs/architecture/`
- `architecture/ml0-snapshot-webhooks.md` — ML0→subscriber push on snapshot-consensus completion.

## Audit — `docs/audit/`
- `audit/fiber-engine-permissionless-safety-audit-2026-07-07.md` — baseline permissionless-safety audit (C1–C3/H1–H2/M1–M3/L1–L6).
- `audit/metakit-sigma-fiat-shamir-audit-2026-06-17.md` — Fiat-Shamir/CDS soundness audit of the sigma opcodes.

## Design — `docs/design/`
- `design/authenticated-trie-integration-spec.md` — authenticated trie for committed OttoChain state.
- `design/engine-hardening-spawn-and-effects.md` — selfReproducing dial + spawn/emit/cascade effect audit.
- `design/fiber-policy.md` — FiberPolicy: a fiber's hash-pinned, externally verifiable constitution.
- `design/metagraph-integration-analysis.md` — Constellation metagraph integration feasibility.
- `design/multi-party-signing-spec.md` — TDD spec for multi-party fiber signing.
- `design/ottochain-new-app-skill-spec.md` — spec for an `ottochain-new-app` agent skill.
- `design/sigma-message-binding-spec.md` — canonical, domain-separated, nonce-bound sigma_verify message (replay cure).

## Domains — `docs/domains/`
- `domains/corporate/CORPORATE-GOVERNANCE.md` + 10 `corporate-*.json` — business-entity state machines.
- `domains/governance/GOVERNANCE.md` + 9 JSON defs — DAO→multi-branch constitutional building blocks.

## E2E — `docs/e2e/`
- `e2e/riverdale-economy-e2e-design.md` — promoting the in-process RiverdaleEconomy test to a 6-party e2e.

## Examples — `docs/examples/`
- `examples/README.md` + `clinical-trial.md`, `fuel-logistics.md`, `real-estate.md`, `riverdale-economy.md`, `tictactoe.md` — worked state-machine walkthroughs.

## Fiber engine — `docs/fiber-engine/`
- `fiber-engine/README.md` — JLVM fiber-engine internal architecture for engine maintainers.
- `fiber-engine/diagrams/` — component/sequence/state diagrams (`.mmd`/`.dot` sources + rendered PNGs).

## Guides — `docs/guides/`
- `guides/adding-new-app.md` — build a new app domain (TS + JSON Logic).
- `guides/deployment.md` — deployment to Digital Ocean + integrationnet.
- `guides/json-logic-primer.md` — JSON Logic intro for guards/effects.
- `guides/state-machine-design.md` — writing JSON state-machine defs/events.
- `guides/terminal-usage.md` — the e2e interactive terminal CLI.

## Proposals / RFCs — `docs/proposals/`
- `proposals/onchain-incrementals.md` *(arrives with PR #210 — not yet on `main`)* — THE RFC behind OnChain-v2 (deltas, DL1 fold/heal, 512KB-cap avoidance).
- `proposals/asset-model.md` — Asset Model RFC v2 (TokenBehavior lattice, morphisms, R1 custody).
- `proposals/asset-model-review-and-interop.md` — 26-agent review of the asset-model RFC (P0/P1/P2).
- `proposals/asset-model-zk-extension.md` — zk asset-model extension feasibility/roadmap.
- `proposals/asset-shielded-mode.md` — gated shielded AssetPolicy mode (confidential amounts).
- `proposals/asset-interop-functor.md` — `F: Ext→Otto` functor + per-standard adapters.
- `proposals/committed-state-migration.md` — verifiable calculated-state root rooted into the snapshot.
- `proposals/economics-and-state-rent.md` — fee/rent design; the C1 gating hurdle.
- `proposals/versionable-contracts.md` — deployed JSON-Logic programs as versioned npm-like packages.
- `proposals/genesis-and-engine-versioning.md` — pinned `std.*` package set in genesis + engine versioning.
- `proposals/schema-architecture.md` — typed, versioned, agent-discoverable contract schemas.
- `proposals/strong-typing-and-conformance.md` — proto↔JLVM↔registry type alignment.
- `proposals/sharded-ml0-and-commitments.md` — sharded ML0 + succinct state commitments.
- `proposals/state-commitment-mpt.md` — state commitment + cross-metagraph comms protocol.
- `proposals/naming-and-fingerprints.md` — human/agent-readable fiber naming + stable fingerprints.
- `proposals/trust-and-verification-handoff.md` — what the chain verifies vs hands off to curators.
- `proposals/typed-network-interface.md` — named DTOs → one OpenAPI contract → generated TS SDK.
- `proposals/jlvm-engine-foundations.md` — engine refactors (gas seam, state unification, error channel).
- `proposals/ai-agent-protocol-layer.md` — JLVM as a protocol layer for autonomous AI agents.
- `proposals/amm-proposal.md` — constant-product AMM DEX example.
- `proposals/evm-comparison-analysis.md` — rebuttal/analysis of EVM-comparison criticisms.
- `proposals/indexer-explorer-architecture.md` — off-chain indexer/explorer design.
- `proposals/p2p-prediction-markets.md` — decentralized peer-to-peer prediction markets.
- `proposals/zk-coin-audit.md` — audit of 8 live ZK/privacy systems vs the asset model.
- `proposals/fiber-ergonomics/` — README + `00`–`03`: authoring-ergonomics program (F1–F10, mostly landed #193–195).

## Reference — `docs/reference/`
- `reference/README.md` — DO-deployment doc index.
- `reference/api-reference.md` — node API endpoints + Swagger pointer.
- `reference/architecture.md` — metagraph architecture (fibers = state machines + scripts).
- `reference/deployment-guide.md` — deploy/manage on 3 DO nodes.
- `reference/jlvm-semantics.md` — full Metakit JLVM semantics (JSON→AST→eval).
- `reference/script-reference.md` — reference for deployment scripts + config.
- `reference/troubleshooting.md` — common issues/solutions + diagnostics.

## Trust graph — `docs/trust-graph/`
- `trust-graph/ARCHITECTURE.md` / `README.md` + JSON building blocks — reputation-gated coordination substrate.

## Whitepaper — `docs/whitepaper/`
- `whitepaper/ottochain-whitepaper-v0.4.md` / `.tex` / `.pdf` — current whitepaper (OttoWeb thesis).
- `whitepaper/ottochain-whitepaper-v0.4-outline-notes.md` — outline + anti-AI-writing checklist.
- `whitepaper/agent-identity-protocol.md` — decentralized identity/reputation for autonomous agents.
- `whitepaper/interview-questions.md` — founder-interview prompts.

## Archive — `docs/archive/`
- `archive/ottobot-planning/` — archived agent-bridge microservice plan (ARCHIVED.md explains why).
