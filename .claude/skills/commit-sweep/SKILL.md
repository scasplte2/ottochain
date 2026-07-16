---
name: commit-sweep
description: Audit a range of recent ottochain commits, one review-persona lens per pass, and file findings as GitHub issues + a worksheet. Never fixes inline. Use to retro-review merged/landed work for defects the original review missed.
---

# commit-sweep

Retro-review landed commits through each review persona in turn. Output is findings (issues + a
worksheet), NOT fixes — a fix rides its own PR with its own review.

## Input
A since-ref or range: `--since 2026-07-01`, or `origin/main~20..origin/main`, or a PR number's merge range.

## Sequence
1. `bin/worksheet commit-sweep-<date>` — open a worksheet to hold findings.
2. List the range: `git log --oneline --since=<date>` (or the explicit range). Note the diffs that touch
   blast-radius files (`docs/agents/blast-radius.md`) — those get the most scrutiny.
3. **One persona lens per pass.** For each of the five personas, sweep the whole range with ONLY that
   persona's checklist in mind:
   - `bin/agent-review consensus-safety <range> --dry-run` (or run it) — signed messages, validators,
     combiners, determinism, the C1–C3/H1–H2/M1–M3/L-class defects.
   - `bin/agent-review wire-compat <range>` — OpenAPI/SDK/e2e drift, version lockstep, path prefixes.
   - `bin/agent-review state-growth-determinism <range>` — unbounded state, 512KB cap, gas, SortedMap.
   - `bin/agent-review asset-economics <range>` — conservation, consent, custody, nonce linearity.
   - `bin/agent-review ai-smells-test-integrity <range>` — tautological/self-regenerating tests, claim-vs-reality.
   Record each pass's findings in the worksheet under a per-persona heading.
4. For each real finding, file a GitHub issue: `gh issue create` with a clear title (lowercase, ≤72),
   the persona label, the commit/file:line, the defect class, and why it matters. Add `blast-radius` or
   `needs-senior` labels where warranted.
5. Link the issues back into the worksheet. Append a `docs/agents/feedback.md` entry if the sweep revealed
   a systemic gap (e.g. a missing guard suite).

## Rules
- **Never fix inline.** A sweep produces findings; fixes are separate, reviewed PRs.
- Prefer false-positive-tolerant reporting: note uncertain findings as questions, not assertions — a human
  triages. But do not manufacture findings to look thorough; "range is clean against persona X" is a valid result.
- The claim-vs-reality check (ai-smells) is the highest-value pass: verify that each commit message's
  claims are backed by the diff and a runnable command (the #162 lesson).
