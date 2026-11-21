# 2026-11-21 — E2E proof import and Tessellation release resolution

## Goal

Repair the published-SDK typecheck and the rule110 JAR download in [main run 44933465040](https://github.com/scasplte2/ottochain/actions/runs/44933465040) at `9b13b0e`. The branch is `fix/e2e-proof-and-release-resolution` (T2).

## Diagnosis and changes

- The spent-nullifier assertion calls the SDK verifier directly. Restore its value import from `@ottochain/sdk/core` in the runner.
- The rule110 resolver found SDK 4.0.0 in metakit 1.8.0-rc.7, then replaced it with an empty fallback. The download retried a URL under `/releases/download/v/`.
- Use the existing JAR mapping to `v4.0.0-rc.10` for SDK 4.0.0 before probing GitHub. Both the E2E lanes and the default-branch cache warmer call one resolver. Other versions retain the same-series fallback on a missing release. Failed lookups and empty results stop before any cache key is emitted.

## Verification

- `npm ci` and `npm run typecheck` in `e2e-test` passed.
- The real Maven POM produced `tessellation=4.0.0` and `tess_tag=v4.0.0-rc.10`. The GitHub release lists cl-node, cl-dag-l1, cl-keytool and cl-wallet JARs.
- Eight isolated resolver cases covered the known mapping, exact override, latest same-series fallback, fallback to a mapped version, missing series, HTTP 403, malformed override and missing POM dependency. Every error left the Actions output file empty.
- `actionlint -shellcheck= .github/workflows/{e2e,warm-tess-cache}.yml` passed.
