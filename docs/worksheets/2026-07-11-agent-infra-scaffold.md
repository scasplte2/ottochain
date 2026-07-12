# 2026-07-11 — agent-infra-scaffold

<!-- metadata.type: project -->

## Goal
Make less-capable models productive and safe in ottochain by externalizing context into docs, executable
`bin/` scripts, review personas, worksheets, and skills. Fix the dangling `docs/signing-canonical-and-
validation.md` reference in CLAUDE.md. Do NOT touch `modules/` or `.github/` — scaffold only.

## Context links
- Branch: chore/agent-infra (off fresh origin/main @ #209)
- Seeds: scratchpad seed-pack.md + three mining reports (ottochain, workflow, sdk)
- Related worksheets: [[2026-07-11-onchain-v2]] (the source of several session-hot lessons)
- Tier: T0-1 (docs/bin/worksheets/skills only — no consensus code touched)

## Plan
- [x] branch off origin/main; read the 4 ground-truth files
- [x] AGENTS.md router + minimal CLAUDE.md edit (invariants byte-identical)
- [x] docs/signing-canonical-and-validation.md (fixes the dangling ref)
- [x] docs/INDEX.md
- [x] docs/agents/{README, blast-radius, conventions, tools, process-observations, feedback}.md
- [x] docs/agents/review-personas/{consensus-safety, wire-compat, state-growth-determinism, asset-economics, ai-smells-test-integrity}.md
- [x] docs/worksheets/{README, TEMPLATE} + this + the onchain-v2 retro
- [x] bin/ (8 scripts, chmod +x, --help each)
- [x] .claude/skills/{night-shift, commit-sweep, test-audit}/SKILL.md
- [x] verification: bash -n, --help, regen-openapi no-op, CommittedViewSuite, preflight --fast

## The mining phase
Three parallel research agents produced the seed pack + three reports (repo mining: gotchas, defect
classes, blast-radius, CI inventory, doc inventory; workflow mining: preferences, tier vocabulary, ~/bin
idioms, inefficiency evidence; SDK mining: cross-repo references). This scaffold is the synthesis; the
personas and process-observations cite those reports' evidence directly.

## State of play (running log)
- Confirmed the ground truth against the repo: origin/main HEAD is #209. **OnChain-v2 / CommitIndex
  (#210/#211) is NOT merged yet** — `OnChain` still holds cumulative `fiberCommits`/`registryCommits`/
  `assetCommits`; `CommitIndex.scala` and `OnChainWireSizeSuite` do not exist on this branch (only
  `CommittedViewSuite`). Docs reflect the current state and mark the v2 world as incoming (PR #210).
- Verified `ExecutionLimits.scala` constants for the limits table (maxDepth=10 … maxSpawnsPerTransition=16).
- bin/ scripts: the SDKMAN source under `set -euo pipefail` exited the shell (errexit inside the sourced
  init). Fixed: only source SDKMAN as a fallback when `sbt` is absent, wrapped in `set +e … set -e`. The
  `~/bin/sbt` wrapper is self-sufficient, so the fallback is rarely hit.

## Open items (deliberately not done here — follow-ups)
- The SDK-repo agent-infra scaffold runs in a PARALLEL session (ottochain-sdk is greenfield: no
  CLAUDE.md/AGENTS.md yet; needs the S1/S2/A1–A4 personas, a pnpm preflight, `node scripts/lint-apps.mjs`).
- Propagation to `-services` / `-explorer` later (their own bin/ + personas).
- stryker4s mutation-testing spike (process-observations #4) — proposed, not built.
- gas-regression suite (process-observations #5) — proposed, not built.
- night-shift dry-run / publish-dry-run CI gate (process-observations #6) — proposed, not built.
- Re-word the signing doc's "OnChain-v2 note" and blast-radius `CommitIndex` entries once #210 merges.

## Decisions needing a human or senior model
- None — pure T0-1 scaffold. But: the process-observations doc makes several proposals (default-on hooks
  for agents, auto-merge-on-green for T0-1 PRs, scheduled dep-bump night-shift) that are James's calls.

## Pre-commit checklist
- [x] every commit subject measured ≤ 72, lowercase, no trailing period
- [x] bin/preflight --fast run end-to-end
- [x] no changes under modules/ or .github/
- [x] trailers present

## Outcome
Scaffold complete on branch chore/agent-infra. Not pushed (per instructions). Handoff: parent agent
relays SHAs + verification; James reviews and merges.

## Feedback entry (appended to docs/agents/feedback.md)
- sbt thin-client death under concurrent worktree use (logged).


**PRs:** scasplte2/ottochain#212, ottobot-ai/ottochain-sdk#253
