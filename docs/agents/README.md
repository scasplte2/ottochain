# docs/agents — the agent-infra system

This directory externalizes the context a less-capable model needs to be productive and safe in
ottochain. Everything here is written to be consumed by cheap models: precise, imperative,
example-rich, short. `../../AGENTS.md` is the entry router; this file explains how the pieces fit.

## The pieces

**Review personas** (`review-personas/*.md`). Five self-contained files. A cheap model can be handed
ONE persona file + a diff and produce a useful review — no other context required. Each persona
**owns** a set of docs: keeping those docs current is part of the persona's job. If a review reveals
that an owned doc has drifted from the code, updating the doc is not optional side-work — it is the
work. Run one over your branch with `bin/agent-review <persona>`.

- `consensus-safety` — owns `../signing-canonical-and-validation.md`, `../../CLAUDE.md` invariants,
  the fiber-engine audit. Signed messages, validators, combiners, determinism.
- `wire-compat` — owns the OpenAPI contracts + `../proposals/typed-network-interface.md`. Version
  lockstep (JAR↔SDK↔metakit-rc), endpoint-count suites, e2e-harness coupling.
- `state-growth-determinism` — owns `../proposals/onchain-incrementals.md` (arrives with PR #210), the
  wire-size suite, `../proposals/economics-and-state-rent.md`. Unbounded state, 512KB cap, gas metering.
- `asset-economics` — owns `../proposals/asset-model.md`, `../proposals/asset-model-review-and-interop.md`.
  Supply conservation, consent, custody, nonce linearity.
- `ai-smells-test-integrity` — owns `conventions.md` + the `test-audit` skill. Dead abstractions,
  tautological tests, self-regenerating fixtures, unverified claims.

**Worksheets** (`../worksheets/`). Committed, resumable session traces. Open one FIRST
(`bin/worksheet <slug>`); update it at every stopping point. Because they are committed (not
gitignored `.workspace/` scratch that died with the machine), another agent — or you next week —
can resume exactly where the last session stopped. Commit trailer: `Worksheet: docs/worksheets/<file>`.

**Feedback** (`feedback.md`). Append-only. Every session ends with an entry: what slowed you down +
the proposed workflow fix. This compounds — recurring entries become new bin/ scripts, new lint
rules, or new process observations.

**The toolbelt** (`../../bin/` + `tools.md`). bin/ should keep growing. The standing instruction: any
non-obvious incantation you type twice, script it. bin/ wraps `just` recipes where they exist.

**blast-radius + tiers** (`blast-radius.md`). The discipline that keeps cheap models safe: route by
the minimum tier that can safely do the job. T0–1 (tests/docs/bin/worksheets) freely; T2
(routes/handlers/e2e) review-first; T3+ (the blast-radius list) propose-only — a senior model or human
decides, and the decision is recorded in the worksheet.

## The loop
1. Pick work sized to your tier (`bin/tasks`). 2. Open a worksheet. 3. Branch off fresh `origin/main`.
4. Small commits (measure the subject). 5. `bin/preflight` to green. 6. PR. 7. `bin/pr-watch` to green
— agents never merge. 8. Append a `feedback.md` entry. 9. Run a persona pass on anything non-trivial.

## Conventions this system reuses (don't reinvent)
Commit style, tier vocabulary, and the memory taxonomy (`metadata.type: feedback|reference|project|user`)
are the user's existing conventions. Worksheet/feedback frontmatter should reuse them, not fork a parallel scheme.
