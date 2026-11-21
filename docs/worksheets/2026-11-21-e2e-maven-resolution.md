# 2026-11-21 — E2E Maven release resolution

## Incident

[Main run 44935209129](https://github.com/scasplte2/ottochain/actions/runs/44935209129) tested `f47cc995`. The tictactoe and staked-oracle runners both received HTTP 404 fetching the metakit 1.8.0-rc.7 POM. They then gave the XML parser an empty response. Other lanes resolved the same pin and started their clusters.

## Change

The prepare job checks out the same revision as the matrix, resolves the release once, and publishes the tag for all selected lanes. The shared resolver retries HTTP 404 as well as transient network errors, tries Maven Central's other endpoint, and parses the POM only after a successful, nonempty download. Permanent fetch failures report the metakit pin and stop before emitting a tag.

## Verification

- On Euler, both Maven Central endpoints returned HTTP 200 for the exact POM. The revised resolver returned `tess_tag=v4.0.0-rc.10`.
- A loopback HTTP fixture exercised the real curl client. A primary 404 followed by 200 recovered in two requests. Persistent primary 404s recovered on the second endpoint. An empty success response also used the second endpoint. Persistent 404s on both endpoints exited nonzero with no XML traceback or Actions output. Every case removed its temporary POM.
- `bash -n bin/resolve-e2e-tessellation`, `actionlint -shellcheck= .github/workflows/{e2e,warm-tess-cache}.yml`, and `git diff --check` passed.
- Wire-compat review checked that dependency pins, the manual override, and the existing release mapping still feed the cache, download, and cluster startup.
