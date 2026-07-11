# Git hooks

Opt-in, version-controlled hooks. Enable them once per clone:

```sh
git config core.hooksPath .githooks
```

(Disable with `git config --unset core.hooksPath`; skip on a single push with
`git push --no-verify`.)

## `pre-push`

Local mirror of the CI **Validate Conventional Commits** gate
(`wagoid/commitlint-github-action`, rules in [`.commitlintrc.json`](../.commitlintrc.json)).
It lints the commits you're about to push and blocks the push if any subject
violates the rules — so a bad message is caught here instead of failing CI
after the round-trip.

Zero dependencies: pure bash, no node/npm/docker. It checks the rules that
actually bite — `header-max-length` (read live from `.commitlintrc.json`, so it
can't drift), the `type` enum + lower-case, no trailing period, and the
`type(scope): subject` shape — and skips merge/revert/fixup commits exactly like
commitlint's default ignores. It is not a full commitlint reimplementation; CI
remains the source of truth.
