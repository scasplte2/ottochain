# docs/worksheets — committed, resumable session traces

A worksheet is the running record of one unit of work. It is **committed** (not gitignored `.workspace/`
scratch, which died with the machine and cost re-work) so any agent — or you next week — can resume
exactly where the last session stopped.

## The rule
**Open a worksheet FIRST**, before writing code. Update it at every stopping point: after a decision,
before a break, when blocked, when a CI run is in flight. A worksheet that is only filled in at the end
is a report, not a trace — the point is that a fresh session can pick it up mid-flight.

## Start one
```
bin/worksheet <slug>      # copies TEMPLATE.md → docs/worksheets/YYYY-MM-DD-<slug>.md, prints reminders
```

## Naming
`docs/worksheets/YYYY-MM-DD-<slug>.md`. Slug is short and kebab-case: `onchain-v2`, `agent-infra-scaffold`.

## Commit trailer convention
Every commit that belongs to a worksheet's work carries:
```
Worksheet: docs/worksheets/YYYY-MM-DD-<slug>.md
```
This lets `git log` reconstruct which commits belong to which session trace.

## Tag convention
Milestone tags are namespaced `ws/<slug>`:
```
git tag ws/onchain-v2
```
The `ws/` namespace is deliberate: `release.yml` fires ONLY on `v*` tags (verified), so a `ws/*` tag
never triggers a release.

## Frontmatter taxonomy (reuse, don't fork)
Match the user's existing memory taxonomy: `metadata.type: feedback|reference|project|user`. Worksheets
are `type: project`. `[[wikilinks]]` to other worksheets/docs are fine.

## What goes in one
See `TEMPLATE.md`. In short: goal, context links (PRs/issues/RFCs), plan, a running state-of-play log,
blockers, decisions that need a human or senior model, a pre-commit checklist, handoff notes, outcome,
and the feedback entry you'll append to `../agents/feedback.md`.
