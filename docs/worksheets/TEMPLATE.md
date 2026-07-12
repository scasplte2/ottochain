# YYYY-MM-DD — <slug>

<!-- metadata.type: project -->
<!-- Started from bin/worksheet. Update at EVERY stopping point, not just at the end. -->

## Goal
One or two sentences. What "done" looks like, concretely.

## Context links
- PRs: #___
- Issues: #___
- RFCs / docs: docs/proposals/___.md
- Related worksheets: [[YYYY-MM-DD-___]]
- Branch: <type>/<slug>  (branched off fresh origin/main)
- Tier: T0-1 | T2 | T3   (see docs/agents/blast-radius.md — if T3, a human/senior signs off below)

## Plan
- [ ] step 1
- [ ] step 2
- [ ] step 3

## State of play (running log — newest at the bottom, timestamped)
- HH:MM — what I did / found / decided. Cite the command or file, not a vibe.

## Blockers
- <what is blocking, and what would unblock it>

## Decisions needing a human or senior model
- <decision> — why it exceeds this tier (e.g. touches a blast-radius file). Recorded sign-off: <who/when>.

## Pre-commit checklist (every commit)
- [ ] subject measured: `s="..."; printf '%d %s\n' "${#s}" "$s"` ≤ 72, lowercase start, no trailing period
- [ ] `bin/preflight` (or `--fast`) green
- [ ] blast-radius files touched named in the PR body with a safety rationale (if any)
- [ ] trailers present (Co-Authored-By, Claude-Session, Worksheet)

## PR / watch
- [ ] PR opened: #___
- [ ] `bin/pr-watch <pr#>` exited 0  ← a PR is NOT done until this passes
- Agents never merge — merge is James's.

## Handoff notes (so a fresh session can resume)
- Where things stand, what's next, what to avoid re-doing, any local state (branches, stashes, running clusters).

## Outcome
- What shipped, PR#, final state.

## Feedback entry (copy into docs/agents/feedback.md)
- What slowed me down + the proposed workflow fix.
