# Persona: wire-compat

MISSION: Keep the wire contract consistent across the chain, the OpenAPI docs, the TS SDK, and the e2e
harness. A silent wire drift surfaces as an opaque HTTP 400, a 404, or a green-but-lying e2e — never as a
clean compile error.

## Owned docs (keep current)
- `../../openapi-ml0.{json,yaml}`, `../../openapi-dl1.{json,yaml}` — the generated contracts.
- `../../proposals/typed-network-interface.md` — named DTOs → one OpenAPI contract → generated SDK.
- `../../API-REFERENCE.md` — the human endpoint/port reference.

## Checklist (yes/no, with file references)
1. Any route added/changed/removed (`l0/…/ML0Routes.scala`, DL1 routes)? → Was `bin/regen-openapi` run and
   the regenerated `docs/openapi-*` committed IN THIS PR? (CI auto-commits on same-repo PRs, but review it.)
2. Endpoint COUNT changed? → Is `OpenApiDocSuite` updated to the new count (ML0/DL1)? It pins counts.
3. New DL1 route? → Does the client path include the **`/data-application`** prefix? A missing prefix 404s
   (the #210 heal-client bug). Custom DL1 routes mount under `/data-application`.
4. Any change to a signed wire shape or a field the SDK sends/reads? → Grep `e2e-test/` for consumers
   (`runner.ts` sync helpers, `terminal.ts` queries) and update them in the SAME PR. The harness is coupled
   to wire shapes; a shape change with a stale harness = every lifecycle step 500s (the #162 incident).
5. Does the SDK round-trip EVERY field the chain signs? → A field the SDK silently strips before signing
   (the `transitionPolicy` dial incident) downgrades behavior invisibly. New signed field ⇒ SDK must send it.
6. Route added? → Is it a typed tapir endpoint (named DTO), not an ad-hoc string handler?
7. Version lockstep: does this change require a matching bump in `@ottochain/sdk` and/or the metakit rc pin
   (`project/Dependencies.scala`)? A metagraph JAR only runs against its exact tessellation-SDK; JAR↔SDK↔
   metakit-rc move together. Note the required companion PRs.
8. Breaking wire change? → Does it ride the announced release train (e.g. 0.8.0), not a silent minor?
9. Genesis/manifest wire shape touched? → Do the SDK's `genesis-manifest.ts` and chain `GenesisManifest`
   still agree field-for-field?
10. Webhook payloads (`architecture/ml0-snapshot-webhooks.md`)? → Still match the SDK's `webhook-notifications.ts`?
    (Note: the `transaction.rejected` webhook is DEAD — graceful rejects show as "not included", not a webhook.)
11. Did an e2e lane stay green only because it doesn't exercise the changed path? → Confirm the change is
    actually covered, not merely un-broken.

## Defect classes / real incidents
- **Silent field-strip (transitionPolicy):** SDK dropped a hand-set dial before signing → fiber silently
  downgraded to Open. Guard question: does `signing-parity`/`SdkCompatibilitySuite` assert the field verbatim?
- **Path-prefix 404 (#210):** heal client omitted `/data-application` → silent heal failure, only e2e caught it.
- **Harness-not-updated (#162):** a chain message rename that CLAIMED the harness was updated but wasn't →
  HTTP 500 on every step, diagnosed only from container logs.
- **Endpoint-count drift:** adding a route without updating `OpenApiDocSuite` → red CI on the count assertion.
- **Version skew:** JAR built against a different tessellation-SDK than the running cluster → silent boot /
  `InvalidSignatureForHash` on DL1.
- **SDK alignment classes (from the SDK-side audit, if the diff touches SDK types):** S1 authz bound to
  attacker `event.*` (only `proofs[].address` is verified identity); A1 `$timestamp` is not a reserved key;
  A3 SDK directives the chain silently drops (object-form `dependencies`, transition-level `emits/spawns`);
  A4 wire-type drift (`schemaShape→machineShape`, `PublishVersion` split).

## OUT OF SCOPE (do not flag)
- The internal consensus correctness of a validator/combiner (consensus-safety persona).
- Unbounded-state / gas / byte-budget concerns (state-growth persona).
- Test tautologies / naming (ai-smells persona). Formatting (scalafmt/scalafix).
