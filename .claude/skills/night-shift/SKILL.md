---
name: night-shift
description: Autonomously pick up and ship low-tier ottochain tasks unattended — pick a cheap-model-ok issue, worksheet-first, small commits, preflight, PR, watch to green, never merge. Use for unattended/batch task execution in the ottochain repo.
---

# night-shift

Ship small, safe ottochain work unattended. You are a bounded execution worker, not an architect. Route
by the minimum tier that can safely do the job and STOP the moment the work exceeds it.

## Guardrails (read first)
- **Read `AGENTS.md`, `CLAUDE.md`, `docs/agents/blast-radius.md` before touching anything.**
- **Only pick `cheap-model-ok` tasks** (T0–1: tests, docs, `bin/`, worksheets) unless explicitly told
  otherwise. Never start a `needs-senior` or `blast-radius` task on the night shift.
- **NEVER merge.** Agents push branches and open PRs; James merges. On Constellation-Labs repos the
  identity is pull-only.

## Sequence
1. `bin/tasks cheap-model-ok` — pick ONE issue you can finish. If none fit your tier, stop.
2. `bin/worksheet <slug>` — open the worksheet FIRST. Fill goal + context links + plan.
3. `git fetch origin && git checkout -b <type>/<slug> origin/main` — always off fresh origin/main.
4. Do the work in **small, logical commits**. Before each commit measure the subject:
   `s="..."; printf '%d %s\n' "${#s}" "$s"` (≤72, lowercase start, no trailing period). Add trailers
   (Co-Authored-By, Claude-Session, `Worksheet: docs/worksheets/<file>`). Update the worksheet's running log.
5. `bin/preflight` — must exit 0. (`--fast` while iterating; full before the PR.)
6. `git push -u origin HEAD` && `gh pr create` (title obeys commitlint too). Name any blast-radius files
   in the PR body with a safety rationale — but ideally night-shift work touches none.
7. `bin/pr-watch <pr#>` — **a PR is NOT done until this exits 0.** On failure, read the dumped logs, fix,
   push, watch again.
8. Append a `docs/agents/feedback.md` entry (what slowed you down + a fix). Commit the worksheet + feedback.

## Stop conditions (write worksheet state, then STOP)
- 2 consecutive failed fix attempts on the same failure.
- ANY blast-radius judgment call, or the task turns out to need a T3 change.
- A decision that needs a human or senior model (record it in the worksheet's decisions section).
- Session/time budget exhausted.
On every stop: update the worksheet's handoff notes so a fresh session can resume, then stop cleanly. Do
not merge, do not force questionable changes to make CI pass.

## End of shift
`bin/preflight` green + `bin/pr-watch` exited 0 on every PR you opened + a `feedback.md` entry appended.
An unwatched PR is an unfinished shift.
