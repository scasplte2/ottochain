# AGENTS.md — the router for agents working in ottochain

OttoChain is a Constellation metagraph (Scala 2.13, `xyz.kd5ujc`) that turns signed JSON into
executable multi-party workflows: **state-machine fibers** and **scripts** running on the JLVM.
Consensus is all-or-nothing per block; a determinism bug forks the chain. Move carefully.

## READ FIRST (non-negotiable)
1. `CLAUDE.md` — the 3 data-application invariants. They are load-bearing. Do not violate them.
2. `docs/signing-canonical-and-validation.md` — full rationale for those invariants + the
   InvalidSignature / block-poisoning mechanics. Read before touching any signed message or validator.
3. `docs/INDEX.md` — the map of every doc. Navigate the codebase's knowledge through it.

## Route by the minimum tier that can safely do the job
Do not "let the smart model do everything." Match the work to the cheapest tier that is safe:

| Tier | Scope | Authority |
|------|-------|-----------|
| **T0–T1** | tests (new), docs, `bin/`, worksheets, comments, OpenAPI regen | **Do freely.** |
| **T2** | routes, `handlers/`, the e2e harness (`e2e-test/`), non-consensus wiring | **Review-first.** Small commits, preflight, a persona pass. |
| **T3+** | anything in `docs/agents/blast-radius.md` (signed schemas, validators, combiners, fiber engine, genesis) | **Cheap models PROPOSE, never decide.** Needs a senior-model session or human sign-off recorded in the worksheet. |

`docs/agents/blast-radius.md` is the authoritative file:why list. If a change touches it, the PR
description must name each blast-radius file changed and why it is safe.

## Standard workflow
```
git fetch origin && git checkout -b <type>/<slug> origin/main   # always branch off fresh origin/main
bin/worksheet <slug>          # open a worksheet FIRST (docs/worksheets/), update it at every stop
# ... small, logical commits (conventional, lowercase subject <=72 — MEASURE, see conventions.md) ...
bin/preflight                 # every CI gate, locally, in CI's order — must exit 0
git push -u origin HEAD
gh pr create ...              # PR title obeys commitlint too (lowercase, <=72, no `release` scope)
bin/pr-watch <pr#>            # a PR is NOT done until this exits 0
# append a docs/agents/feedback.md entry, then stop
```
**Agents NEVER merge.** On `scasplte2/ottochain` the agent identity pushes branches + opens PRs;
James merges. On canonical Constellation-Labs repos the agent identity is pull-only.

## Tools
- `bin/` toolbelt — `preflight`, `test`, `regen-openapi`, `pr-watch`, `tasks`, `agent-review`,
  `wire-size`, `worksheet`. Each has `--help`. See `docs/agents/tools.md`.
- `just` recipes — `just test`, `just test-only <suite>`, `just build`, `just e2e-up`, `just e2e`,
  `just e2e-down`, `just health`. bin/ wraps/delegates to these; do not duplicate them.
- Skills — `tessellation-cluster` (run a local cluster — use this, there is no `bin/cluster`),
  `pr-workflow`, plus repo skills in `.claude/skills/`: `night-shift`, `commit-sweep`, `test-audit`.
- Local cluster: `just e2e-up` (resolves the tessellation version from the metakit pin). E2E env
  knobs that MUST ride any repro: `DATA_PEERS_COUNT=2`, `DATA_L1_TIME_TRIGGER_INTERVAL="8 seconds"`,
  distinct wallets via `E2E_WALLETS` (multi-party flows fail confusingly on a single wallet).

## Terminology
The two fiber kinds are **state machines** and **scripts**. "oracle" is DEPRECATED — never put it in
new code, routes, namespaces, or docs (except a real-world domain oracle noun). Consensus-visible
names are baked into MPT roots and are near-immutable once shipped.

## Review personas
Five self-contained persona files live in `docs/agents/review-personas/`. Each is a checklist + defect
classes a cheap model can apply to a diff with only that file for context. Run one with
`bin/agent-review <persona>`. Personas own docs and keep them current (see `docs/agents/README.md`).

## Feedback loop
At the end of every session append an entry to `docs/agents/feedback.md` (what slowed you down +
the proposed workflow fix). It compounds. When you type a non-obvious incantation twice, script it
into `bin/` (`docs/agents/tools.md`).
