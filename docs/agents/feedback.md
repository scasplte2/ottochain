# Agent feedback log

Append-only. Every session ends with an entry. This compounds: recurring entries become new `bin/`
scripts, new lint rules, or new items in `process-observations.md`. Newest at the bottom.

**Entry format**
```
### YYYY-MM-DD — <short title>
- Worksheet: docs/worksheets/<file>   (or session ref)
- What slowed me down: <the friction, with evidence — a PR#, a command, a log line>
- Proposed fix: <concrete: a bin/ script, a lint rule, a doc, a process change>
- Status: proposed | done | wont-fix
```

---

### 2026-07-11 — a PR shipped red and nobody was watching
- Worksheet: docs/worksheets/2026-07-11-onchain-v2.md
- What slowed me down: PR #210 merged the OnChain-v2 work with all five e2e lanes red for ~30 min. The
  agent moved on after opening the PR; James caught the failure, not the agent. "PR opened" was treated
  as "done."
- Proposed fix: `bin/pr-watch <pr#>` — poll checks to a terminal state, dump failing logs, exit code
  mirrors outcome. Make "watched to green" part of the definition of done; encode it as a night-shift
  stop condition.
- Status: done (bin/pr-watch added; see process-observations #2)

### 2026-07-11 — commitlint subject-case failure found in CI, not locally
- Worksheet: docs/worksheets/2026-07-11-onchain-v2.md
- What slowed me down: commitlint rejects uppercase-start subjects and headers > 72 chars. The pre-push
  mirror is opt-in, so violations surfaced only in CI (as they did for #162 at 87 chars and #207 at 73).
  Eyeballing 73-vs-72 does not work.
- Proposed fix: `bin/preflight` runs the commitlint range check over `origin/main..HEAD` unconditionally,
  reading `header-max-length` live from `.commitlintrc.json`. Conventions doc mandates measuring every
  subject with `s="…"; printf '%d %s\n' "${#s}" "$s"`.
- Status: done (bin/preflight step 4; conventions.md)

### 2026-07-11 — sbt thin-client died under concurrent worktree use
- Worksheet: docs/worksheets/2026-07-11-agent-infra-scaffold.md
- What slowed me down: a parallel session in a second worktree shared the sbt thin client → "server was
  not detected / failed to connect", killing a test run mid-flight.
- Proposed fix: `bin/test` runs batch mode (no `--client`) and, on detecting the connect-failure string,
  prints the recovery steps (`pkill -f 'sbt.*server'; rm -f project/target/active.json`).
- Status: done (bin/test)

### 2026-11-21 — capture the parent ordinal before the historical proof

- Worksheet: docs/worksheets/2026-11-20-sentient-agent-mandate.md
- An ordinal query at the current head returned a valid absence. The catalog records the parent. Capturing the record witness first, then anchoring a later catalog to its own snapshot, made the historical check reproducible.
- The current Tessellation launcher performs host dependency setup and Docker cleanup with shared names. Give the local e2e recipe an isolated project, preflight-only dependency check and explicit teardown of recorded resource IDs.

### 2026-11-21 — explain a refusal from the committed guard

- Worksheet: docs/worksheets/2026-11-21-mandate-circuit-preview.md
- Preserve the preceding verified record and signer identities when recording a refused request. They let the supplier reproduce each clause in the pinned JLVM. The receipt gives the ledger verdict and the local replay identifies the failed predicate.

### 2026-11-21 — time circuit captions against generated speech

- Worksheet: docs/worksheets/2026-11-21-mandate-narrated-film.md
- Render the voice first and derive each cue from its actual PCM length. Transcribing the mixed track caught an ambiguous pronunciation of co-signer. Preserve the dry narration alongside a local review film for future edits.

### 2026-11-21 — diagnose Tessellation release resolution

- In a JAR download failure, record the Maven version and the final release tag together. The rule110 log resolved 4.0.0 but generated the cache key `tessellation-jars-v`. One resolver for the warmer and E2E lanes makes the failure reproducible from either workflow.

### 2026-11-21 — compare matrix lanes at the first failed step

- The same pinned POM returned a 404 to two workers and resolved for their peers. Checking the first failing HTTP request across lanes identified the dependency on individual runner responses. The E2E prepare job now resolves one tag for the matrix.

### 2026-11-21 — probe novel mechanics at combiner level before the cluster run

- Worksheet: docs/worksheets/2026-11-21-bike-shop-film.md
- A 5-case combiner suite (seconds per run) found two engine behaviours before any devnet time: cascaded `_spawn` is silently dropped (#222) and a callee's `heldAssets` misses same-cascade transfers (#223). Restage around them, then record once.
- The runner confirmed Fractionalize/Compose on ML0 but raced DL1's commit index (AssetNotFound on the next morphism). It now gates on DL1 ingesting morphism-created assets — reuse that for any consuming morphism.
- tessellation's `just up` runs install_dependencies.sh, which appends to ~/.bashrc and installs nvm; call `docker/bin/compose-runner.sh --up …` directly. Its cleanup script removes generic container names and the shared `tessellation_common` network — remove task containers by exact name instead (see ~/devnets reset script pattern in the worksheet).
- Walk snapshots by ordinal for evidence (`latestLogs` clears each snapshot) and expect each request twice (fan-out to 3 DL1s → a graceful duplicate rejection one ordinal later); use the first applied ordinal.

### 2026-11-21 — fix a narration script's register before tuning its audio

- Worksheet: docs/worksheets/2026-11-21-bike-shop-film.md
- Three rounds of pause detection, smoothing and take selection on the cloned voice did not fix narration the reviewer called unnatural. Rewriting the script in a spoken register fixed it on the first generation: contractions, no colons read aloud, no slogan fragments, bridges between acts, and callbacks that restate what they refer to.
- When rewording narration, keep the number of lines per act. Film cues are line fractions (`LF()`), so a fixed line structure keeps every cue on the same or an equivalent word. Check by listing the word spoken at each cue before and after the change.
- A wait loop that ran `pgrep -f "<job cmdline>"` matched its own shell and never finished. Background the job itself, or match on a PID.
