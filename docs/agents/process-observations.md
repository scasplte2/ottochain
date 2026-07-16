# Process observations

Candid, evidence-based. James asked to be pushed on this. Each item is a recurring inefficiency with
its evidence and a concrete fix. No hedging — these are calls, not musings.

## 1. Opt-in local gates guarantee repeat CI failures
The commit-subject length rule (`header-max-length:72`) has failed CI more than once — #162 (87 chars),
#207 (73, one over) — each an amend + force-push + a red run. The prettier gate in the SDK (#238) shipped
*after* style had drifted to 288 single-quote / 169 double-quote, forcing a big-bang reformat of ~70% of
the diff. The fix that would end the recurrence — `.githooks/pre-push` — is deliberately kept opt-in
because James dislikes hook bloat.
**Call:** `bin/preflight` is now the contract, and it runs the commit-subject check unconditionally.
Agents should treat `.githooks` as default-on for themselves — the AGENTS.md workflow mandates preflight
before every push — even though it stays opt-in for humans. A gate that isn't run isn't a gate.

## 2. "PR opened" is not "done"
PR #210 shipped with all five e2e lanes red for ~30 minutes before James noticed — not the agent.
**Call:** a PR is done when checks are green AND watched. `bin/pr-watch <pr#>` closes this; it must exit 0
before a session ends. Encoded in the `night-shift` skill's stop condition.

## 3. Gitignored session logs died with the machine
`.workspace/` was gitignored scratch. Context — plans, running state, why-decisions — evaporated when the
machine was lost, and later sessions re-derived it (see #4, #6).
**Call:** worksheets are now committed under `docs/worksheets/`, resumable by another agent. `.workspace/`
stays for private scratch only. Worksheet FIRST, updated at every stopping point.

## 4. 70% coverage that never fails is a dashboard, not a gate
`coverageFailOnMinimum := false` — the number is advisory. Tests can rot under it silently, and coverage %
is a weak signal anyway (a test can execute a line and assert nothing).
**Call:** either enforce a ratchet (fail if coverage drops vs main) or invest the signal budget in mutation
testing. Recommend a **stryker4s spike**: a mutation score is a real "do the tests catch bugs" gate, which
is what `test-audit` verifies by hand today. Deterministic, CI-friendly.

## 5. Prefer deterministic proxies over wall-clock CI benchmarks
On shared CI runners wall-clock timings are noisy and flaky. The wire-size suite pins byte marginals in
tight bands (deterministic serialization) and doubles as a codec-bloat tripwire.
**Call:** measure bytes, gas units, and counts in CI — all deterministic by consensus requirement. Reserve
wall-clock benchmarks for nightly. A **gas-regression suite** is the correct "perf test" for this codebase.

## 6. The publishing saga burned four release attempts
metakit-sdk rc.1→rc.4 partially failed on a different registry each time (npm `--tag`, empty cargo token,
crates TP-only 403, expired NPM_TOKEN, CDN cache lie). Each burned a version number (you can't re-publish),
forcing rc churn — and every step blocked on James doing per-package config the pull-only agent can't.
**Call:** add a `publish-dry-run` gate to CI *before* the tag (`npm pack` / `cargo publish --dry-run` /
`python -m build`). metakit-sdk already has this pattern — port it. A dry-run that passes on the PR turns a
release into a formality instead of a saga.

## 7. The dependency-bump omnibus has been done by hand 4+ times
sdk #207 / #237 / #252 and services #296 all repeated the same ritual: isolated worktree off `origin/main`,
combine N dependabot PRs onto one branch + one fresh lockfile, bump caret floors so the bot stops
re-proposing, verify all gates, close superseded PRs with a note. Pure toil, identical each time.
**Call:** this is a scheduled **night-shift task** candidate — a standing "combine dependabot into one
branch, verify, open one PR, close the rest with a note" routine. The existing `sdk-bump.yml` auto-merge
silently fails on breaking SDK changes, so the routine must run preflight, not blind-merge.

## 8. Single-reviewer bottleneck creates self-blocking states
The SDK format gate (#238) was appended to main's required checks while the PR that *defines* the
formatting was still unmerged and waiting on the only code-owner (James, `enforce_admins:true` + 1 required
review). The gate was live before its definition landed.
**Call:** flag single-reviewer bottlenecks explicitly. Consider **auto-merge-on-green for T0–1 docs/deps
PRs** (the `prs` script and `close-stale-prs.sh` exist because these pile up). Never add a required status
check before the PR that satisfies it is merged.

## 9. A commit message claimed work it didn't do
#162 renamed a chain message and the commit message CLAIMED it updated the e2e harness — it didn't. Every
lifecycle step broke with HTTP 500, diagnosed only via `gh run download` container logs.
**Call:** every claim in a commit message must be verifiable by a command listed in the worksheet. "Updated
the e2e harness" means the worksheet shows the grep that found the consumers and the `tsc --noEmit` that
passed. If you can't cite the command that proves the claim, don't make the claim. The `test-audit` and
`commit-sweep` skills exist to catch exactly this class of unverified assertion.
