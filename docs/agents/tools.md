# Tools — the toolbelt and how to grow it

**Standing instruction: any non-obvious incantation you type twice, script it into `bin/`.** The
toolbelt is meant to grow. A script you write once saves every future cheap-model session the cost of
rediscovering the incantation — and encodes the recovery steps for when it goes wrong.

## How to write a bin/ script
- **bash, `set -euo pipefail`.** Absolute or repo-root-relative paths (`ROOT="$(git rev-parse --show-toplevel)"; cd "$ROOT"`).
- **Every script has `--help`** as its first behavior. The pattern used across `bin/`:
  `case "${1:-}" in -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;; esac` — the header
  comment IS the help text.
- **Self-contained, no new deps.** Only tools already in the repo/user environment (git, sbt, gh, node,
  just). If a script needs sbt, guard the toolchain: `command -v sbt` first, source SDKMAN only as a
  fallback (and wrap the `source` in `set +e … set -e` — SDKMAN's init trips `errexit`).
- **Delegate to `just` where a recipe exists** — don't duplicate `just test` / `just build` / `just e2e-up`.
  Wrap them (add module mapping, drift checks, recovery hints) rather than reimplement.
- **Fail loudly with the fix.** On a detectable failure, print the exact recovery commands (see
  `bin/test`'s stale-server hint, `bin/preflight`'s OpenAPI-drift instructions).
- `chmod +x`, then verify: `bash -n bin/<script>` and `bin/<script> --help`.

## bin/ (this repo)
| script | what |
|---|---|
| `bin/preflight [--fast]` | every CI gate locally, in CI's order. Must exit 0 before you push. |
| `bin/test <module\|all> [glob]` | module tests by friendly name (l0/l1/dl1/shared/models), batch mode. |
| `bin/regen-openapi` | regenerate the OpenAPI contracts + report drift. |
| `bin/pr-watch <pr#>` | poll a PR's checks to a terminal state; dump failing logs. A PR isn't done until this exits 0. |
| `bin/tasks [label]` | list open issues by label (agent-ready, cheap-model-ok, needs-senior, blast-radius). |
| `bin/agent-review <persona> [range]` | run one review persona over a diff (`--dry-run` prints the prompt). |
| `bin/wire-size` | run the OnChain wire-size suite + print byte probes. |
| `bin/worksheet <slug>` | start a dated worksheet from the template; print trailer + tag reminders. |

## just recipes (delegate to these)
`just test` · `just test-only <suite>` · `just build` (currencyL0/currencyL1/dataL1 assembly) ·
`just e2e-up` / `just e2e` / `just e2e-down` / `just e2e-health` · `just up`/`down`/`health`/`logs`
(docker-compose local) · `just resolve-tess-version` · `just keygen` / `just show-address`.
Run `just` (or `just --list`) to see them all.

## ~/bin ecosystem (the user's cross-repo scripts worth knowing)
- `git-start-branch.sh <repo> <branch>` — ALWAYS starts from fresh `origin/main` (fetch → reset →
  new branch). The never-work-on-stale-state rule, scripted. Prefer it (or its manual equivalent).
- `prs` — open PRs across all ottochain repos, split into Upstream (need James to merge) / Fork-reviewed
  / Fork-needs-review. Use it to see what is waiting on a human.
- `ecosystem-state.sh` — cron snapshot of every remote + open PR into `~/repos/.state/`; also polls the
  live cluster status endpoint. Read its output instead of re-fetching by hand.

## sbt gotchas (these cost cycles)
- **Project keys are not the directory names:** `modules/l0 → currencyL0`, `modules/l1 → currencyL1`,
  `modules/data_l1 → dataL1`, plus `models`, `sharedData`, `sharedTest`. `sbt "l0/compile"` is a silent
  no-op → scalafix runs on a stale SemanticDB → CI `--check` fails though local looked clean. `bin/test`
  maps the friendly names for you.
- **Thin-client death under concurrent worktrees:** "server was not detected / failed to connect". Recover:
  `pkill -f 'sbt.*server'; rm -f project/target/active.json`; use batch `sbt "cmd"` (what `bin/test` does).
- **Unused imports are NOT fatal** (`-Wconf:cat=unused:info`, no `-Xfatal-warnings`). Don't chase them as
  errors — but run `scalafixAll scalafmtAll` before commit; `scalafmtOnCompile`/`scalafixOnCompile` are on
  locally, and CI re-checks in check-mode (which does not lie about a stale cache).
- **sbt-dynver throws in a linked `git worktree`** (NoWorkTreeException) — for version-sensitive tasks build
  from a normal checkout.
- **`assembly / mainClass` is pinned on currencyL0** because `GenerateOpenApi` also defines a `main`; without
  the pin sbt-assembly writes no Main-Class and `ml0.jar` won't boot.
