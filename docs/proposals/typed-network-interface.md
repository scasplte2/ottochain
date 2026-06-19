# Typed Network Interface — named DTOs, one OpenAPI contract, generated SDK

**Status:** draft / design. **Date:** 2026-06-19. **Branch:** `explore/http-typed-models` (worktree off `main`).
**Goal (from the design discussion):** *strictly type the network interface so the TypeScript SDK and
ottochain stay aligned* — the SDK should never again reverse-engineer a response shape, and a field
rename on the chain should surface as a compile error (or a CI diff) on the client, not a silent
runtime break.

> **Scope note / premise correction.** An earlier framing was "make the HTTP layer equivalent to the
> gRPC portions." There are **no gRPC or WebSocket interfaces owned by this repo** — it is HTTP-only,
> plus an *outbound* webhook (fire-and-forget HTTP POST). gRPC's internal use by tessellation for node
> consensus is not ours to model. So the target isn't "mirror gRPC"; it's to give HTTP the one thing
> gRPC gives for free — **every request and response is a named message in a single source-of-truth
> contract, and clients are generated from it.** That is exactly what keeps the SDK and the chain aligned.

> **Relationship to [`strong-typing-and-conformance.md`].** That proposal types the *on-chain domain*
> (proto schema ↔ JLVM definition ↔ registry). **This proposal types the *transport*** — the HTTP
> request/response bodies and the webhook delivery payload. They are orthogonal and complementary:
> one makes the *contract a fiber runs* verifiable; this one makes the *contract a client speaks* verifiable.

---

## 0. The one-paragraph thesis

The metagraph's public API is ~23 HTTP endpoints + one outbound webhook. **About 60% of responses are
already typed** (magnolia-derived circe codecs over domain case classes — `OnChain`, `Checkpoint`,
fiber/script/registry records, `SubscribeResponse`). **The remaining ~40% are hand-built `Json.obj(...)`**
with field names that exist only as string literals inside handler bodies (version, `util/hash` response,
both `estimate-fee` shapes, all three `state-proof` shapes, the webhook list, and two different ad-hoc
error shapes). Those literals are the alignment hazard: the SDK hard-codes the same strings on the other
side, and nothing connects the two. The fix is three moves — **(1)** give every hand-built body a named
case class with a derived codec and a golden field-name test; **(2)** describe every endpoint *once* (we
recommend [tapir]) so an **OpenAPI document falls out as a build artifact**; **(3)** generate the SDK's
types from that OpenAPI and add a CI gate that fails on drift. After that, the chain *is* the schema and
the SDK is downstream of it.

---

## 1. Current surface audit

### 1.1 Already typed (keep — these are the model)
Derived circe codecs over domain case classes; they serialize/deserialize structurally and will land in
OpenAPI automatically once schemas are derived.

| Endpoint | Response type |
|---|---|
| `GET /v1/onchain` (ML0 + DL1) | `OnChain` |
| `GET /v1/checkpoint` | `Checkpoint[CalculatedState]` |
| `GET /v1/state-machines` `?status=` / `/{id}` / `/{id}/events` | `SortedMap[UUID, StateMachineFiberRecord]` / `Option[…]` / `List[EventReceipt]` |
| `GET /v1/scripts` … / `/{id}/invocations` | `…ScriptFiberRecord` / `List[ScriptInvocation]` |
| `GET /v1/registry` / `/reverse/{id}` / `/{name}` | `SortedMap[RegistryName, RegistryEntry]` / `Option[RegistryName]` / `Option[RegistryEntry]` |
| `POST /v1/webhooks/subscribe` | request `SubscribeRequest` → response `SubscribeResponse` |
| `POST /v1/util/hash` **request** | `Signed[OttochainMessage]` |

### 1.2 Hand-built `Json.obj(...)` (the gap — this is what we type)
Field names below are the **exact current wire keys** (file:line in this worktree). Preserving them is a
hard constraint for SDK compatibility — see §6.

| # | Endpoint | Current keys | Source |
|---|---|---|---|
| 1 | `GET /v1/version` (ML0) | `service, version, name, scalaVersion, sbtVersion, gitCommit, buildTime, tessellationVersion` | `handlers/MetaHandler.scala:26` |
| 2 | `GET /v1/version` (DL1) | same, `service="ottochain-dl1"` | `data_l1/DataL1CustomRoutes.scala:32` |
| 3 | `POST /v1/util/hash` **response** | `"protocol message hash", "protocol message"` ⚠ keys contain spaces | `MetaHandler.scala:40`, `DataL1CustomRoutes.scala:49` |
| 4 | `GET …/state-machines/{id}/estimate-fee` | `fiberId, currentState, event, gasEstimate, opCount, maxDepth, candidateTransitions, note` | `handlers/EstimateHandler.scala:37` |
| 5 | `GET …/scripts/{id}/estimate-fee` | `scriptId, gasEstimate, opCount, maxDepth, note` | `EstimateHandler.scala:59` |
| 6 | `GET …/{kind}/{id}/state-proof` (state-machine, script, asset) | `key, ordinal, committedRoot, mptRoot, record, proof` (+ `field, fieldValue` when `?field=`) | `handlers/StateProofHandler.scala:54` |
| 7 | `GET /v1/webhooks/subscribers` | `{ "subscribers": [Subscriber…] }` (secret redacted to `"***"`) | `handlers/WebhookHandler.scala:33` |
| 8 | error (state-proof 404/500, webhook 404) | `{ "error": "…" }` | `StateProofHandler.scala:46`, `WebhookHandler.scala:26` |
| 9 | error (validation, via SDK `.toResponse`) | `{ code, message, retriable, details }` (tessellation shape) | SDK `DataApplicationValidationError` |

### 1.3 The outbound webhook (the "push" interface)
`SnapshotNotification` is POSTed to each subscriber on every accepted snapshot
(`webhooks/Subscriber.scala:70`): `event, ordinal, hash, timestamp, metagraphId, stats{ updatesProcessed,
stateMachinesActive, scriptsActive }`. **It is already a typed case class** but is *not* part of any
published contract, so a subscriber building a receiver re-derives the shape by hand. It belongs in the
contract too — it is the most "interface-like" of the push surfaces.

### 1.4 Symptoms this audit confirms
- **Two error shapes** coexist (`{error}` vs `{code,message,retriable,details}`) — no client can branch on
  errors reliably.
- **Keys with spaces** (`"protocol message hash"`) cannot be a Scala/TS field name without a codec
  override — a direct cost of hand-building JSON.
- **Docs already drift:** `docs/API-REFERENCE.md` lists `FiberStatus` values `ACTIVE, COMPLETED,
  SUSPENDED, FAILED`, but the enum (`schema/fiber/FiberStatus.scala`) is `Active, Archived, Failed` →
  `ACTIVE, ARCHIVED, FAILED`. Hand-maintained docs rot; a generated OpenAPI cannot.
- **The SDK reverse-engineers shapes inline** — `client.get<unknown>(...) as { fiberCommits?: … }`,
  `client.post<{ hash: string }>('/data', …)` in `e2e-test/`. Every one of these is an un-checked guess.

---

## 2. Target model (what "strictly typed" means here)

1. **Every request and response body is a named case class** in a dedicated `api` (or `http`) package per
   module — no `Json.obj(...)` in handlers. Encoders/decoders are **derived**, never hand-written
   (`Encoder.instance(x => Json.obj(...))` is exactly what we're removing). This matches the standing
   preference: *derive codecs; field names are signature/contract-load-bearing; guard them with golden
   tests.*
2. **One shared response envelope and one error type.** All errors serialize to a single shape (§5).
3. **One OpenAPI document** is emitted as a build artifact (`openapi.json`), derived from the same endpoint
   descriptions that produce the routes — so it cannot describe an endpoint the server doesn't serve.
4. **The SDK's types are generated** from that OpenAPI (`openapi-typescript`), and **CI fails on drift**
   between the committed OpenAPI and the routes. This is the actual mechanism that keeps SDK ↔ chain aligned.

The DTOs are **transport** types. They are *not* signed messages — so CLAUDE.md rule #1 (signed fields are
`Option`/required-no-default) does **not** govern them, and we get no `InvalidSignature` exposure from
adding response DTOs. The one place to be careful: `util/hash`'s response **echoes the signed
`OttochainMessage`** — its DTO must embed the message via the *exact existing* `OttochainMessage` encoder
(reuse, don't re-derive), so the echoed canonical is unchanged. The signed *request* canonical is out of
scope and untouched.

---

## 3. The concrete DTOs (proposed, wire-preserving)

Sketches; field names match §1.2 exactly so v1 stays byte-compatible. Codecs `@derive`d with the existing
`CodecConfiguration` (magnolia + `withDefaults`).

```scala
// service meta — shared by ML0 + DL1, distinguished by `service`
final case class VersionInfo(
  service: String, version: String, name: String,
  scalaVersion: String, sbtVersion: String, gitCommit: String,
  buildTime: String, tessellationVersion: String
)

// util/hash response. ⚠ wire keys have spaces — see §6 for the v1-compat vs v2-clean decision.
final case class HashResult(messageHash: Hash, message: OttochainMessage)
//   v1 codec maps messageHash -> "protocol message hash", message -> "protocol message"
//   message is encoded with the canonical OttochainMessage encoder (unchanged).

final case class TransitionFeeEstimate(
  fiberId: UUID, currentState: String, event: String,
  gasEstimate: Long, opCount: Int, maxDepth: Int,
  candidateTransitions: Int, note: String
)
final case class ScriptFeeEstimate(
  scriptId: UUID, gasEstimate: Long, opCount: Int, maxDepth: Int, note: String
)

// state-proof — `record`/`proof` reference the real domain + MPT types (already schema-able)
final case class StateProof(
  key: String, ordinal: SnapshotOrdinal,
  committedRoot: Hash, mptRoot: Hash,
  record: Json,                 // proven record (typed per kind in a later refinement)
  proof: MerkleProof,
  field: Option[String] = None, fieldValue: Option[Json] = None
)

final case class SubscriberView(            // secret already redacted by the handler
  id: String, callbackUrl: String, secret: Option[String],
  active: Boolean, createdAt: Instant,
  lastDeliveryAt: Option[Instant], failCount: Int
)
final case class SubscriberList(subscribers: List[SubscriberView])

// already typed — promote into the contract as-is
// SubscribeRequest, SubscribeResponse, SnapshotNotification, NotificationStats
```

`note` strings (advisory disclaimers) move from inline literals to named constants so they are documented
once and identical across endpoints.

---

## 4. How to get "one contract" — architecture options

### Option A — **tapir** (recommended)
Describe each endpoint *once* as a tapir `Endpoint[I, E, O, Any]` (path, query, request body, response
body, error). One description yields **all three artifacts**:
- **Server:** `Http4sServerInterpreter[F].toRoutes(endpoints)` → `HttpRoutes[F]`, which slots straight into
  the existing `protected val routes: HttpRoutes[F]` of `MetagraphPublicRoutes[F]` (drop-in; the SDK base
  trait is unaffected).
- **Contract:** `OpenAPIDocsInterpreter().toOpenAPI(endpoints, …)` → `openapi.json`, emitted by an sbt task.
- **Client (optional):** the same description can interpret to an sttp client and a Swagger-UI route.

Pros: the contract *cannot* drift from the routes (same source); errors and query params are typed; this is
the idiomatic Scala equivalent of a `.proto`. Cons: a new dependency set (`tapir-core`, `-json-circe`,
`-http4s-server`, `-openapi-docs`, `-openapi-circe-yaml`) and a one-time endpoint-description refactor.
Mitigated by staging (§7): tapir lands *after* the DTOs, endpoint by endpoint, behind unchanged paths.

### Option B — http4s stays, OpenAPI authored/derived separately
Keep `HttpRoutes.of` + circe; add the §3 DTOs; produce OpenAPI either hand-authored or via a JSON-Schema
derivation step, then `openapi-typescript` for the SDK. Pros: no routing refactor. Cons: the OpenAPI is a
*second* source of truth that must be kept in sync by discipline/CI rather than by construction — i.e. it
reintroduces exactly the drift we're eliminating. **Not recommended** beyond Phase 1.

**Recommendation:** A, reached in stages — Phase 1 delivers the DTOs (immediate SDK win, zero new deps);
Phase 2 introduces tapir to make the contract structural; Phase 3 generates + gates the SDK.

---

## 5. The error envelope (one decision, applied everywhere)

Collapse the two shapes into one. Recommended: adopt the tessellation validation shape as the canonical
envelope, since `.toResponse` already emits it for the typed half of the API:

```jsonc
{ "code": <int>, "message": <string>, "retriable": <bool>, "details": { … } }
```

Migrate the ad-hoc `{ "error": "…" }` responses (state-proof 404/500, webhook 404) onto it
(`message` carries the text; `code`/`retriable` set per case). One `ApiError` case class, one encoder, one
documented shape in OpenAPI. *(Alternative: define a fresh `ApiError` and adapt both — more churn, no real
benefit. Flagged as a decision in §9.)*

---

## 6. Wire compatibility & the space-key wart

v1 must stay byte-compatible so the deployed SDK keeps working during rollout. Two keys are problematic
because they contain spaces (`"protocol message hash"`, `"protocol message"`):

- **v1-compat (default):** keep the ugly keys via explicit codec field-mapping on `HashResult`. Zero client
  breakage. The ugliness is quarantined to one codec instead of smeared across handlers.
- **v2-clean (opt-in breaking):** rename to `messageHash` / `message` and serve under a `/v2` path or a
  versioned media type, retiring `/v1/util/hash` on the SDK's schedule.

Recommendation: **v1-compat now**, capture v2-clean as a follow-up the SDK opts into (you have previously
accepted deliberate breaking renames — e.g. oracle→script — when the SDK moves in lockstep). Either way,
golden tests pin the exact wire keys so a rename is never silent.

---

## 7. Staged plan

1. **Phase 1 — DTOs + envelope (Scala only, no new deps).** Introduce the §3 case classes; replace every
   `Json.obj(...)` in handlers with a typed value; unify errors per §5. Add **golden field-name +
   round-trip tests** (in the spirit of `PublishVersionSigningCanonicalSuite`) asserting the exact wire
   keys for each DTO. *Outcome:* no anonymous JSON remains; the chain side is fully typed. Immediate SDK
   benefit: stable shapes to target.
2. **Phase 2 — tapir + OpenAPI.** Re-express endpoints as tapir descriptions interpreted to the same
   `HttpRoutes[F]`; emit `openapi.json` via an sbt task; serve Swagger-UI (optional). *Outcome:* a single
   source-of-truth contract published as a build artifact.
3. **Phase 3 — SDK generation + drift gate.** In `ottochain-sdk` (separate repo, editable at
   `~/repos/ottochain-sdk`), generate types from `openapi.json` (`openapi-typescript`); replace the inline
   `as {…}` shapes; add a CI check that regenerates the OpenAPI from the routes and fails if it differs
   from the committed copy. *Outcome:* a rename on the chain breaks the SDK build, not production.

Each phase is independently shippable and behind unchanged paths.

---

## 8. Risks & guardrails

- **Signed-canonical safety.** Response DTOs aren't signed → no `InvalidSignature` risk. The one echo
  (`util/hash`) reuses the canonical `OttochainMessage` encoder. The signed *request* canonical is untouched.
- **Validation-gate / combiner invariants.** This work touches only HTTP serialization; it adds no
  validators and reads no `CalculatedState.registry` in `validateSignedUpdate` (CLAUDE.md rule #3 N/A).
- **Hand-written codecs forbidden.** All DTO codecs are derived; the only field-mapping override allowed is
  the quarantined `HashResult` space-key map (§6), itself golden-tested.
- **No silent doc drift.** `API-REFERENCE.md` becomes generated (or is deleted in favor of Swagger-UI) once
  Phase 2 lands; until then, fix the stale `FiberStatus` values.

---

## 9. Open decisions for you

1. **Error envelope (§5):** adopt the tessellation `{code,message,retriable,details}` shape everywhere
   (recommended), or define a fresh `ApiError`?
2. **`util/hash` keys (§6):** v1-compat keep-the-space-keys now (recommended), or break to clean keys under `/v2`?
3. **tapir adoption (§4):** approve tapir as the contract engine (recommended), or stay http4s + author
   OpenAPI separately (Option B)?
4. **Swagger-UI:** expose a live `/docs` Swagger-UI on ML0/DL1, or ship `openapi.json` as an artifact only?

[`strong-typing-and-conformance.md`]: ./strong-typing-and-conformance.md
[tapir]: https://tapir.softwaremill.com/
