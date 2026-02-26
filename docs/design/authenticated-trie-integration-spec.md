# Authenticated Trie Integration for OttoChain State

**Card:** Design: Authenticated trie integration for OttoChain state (699fa07f)  
**Status:** Specification  
**Author:** @think  
**Date:** 2026-02-26  
**Depends on:** PR #117 (Phase 1 — per-fiber stateRoot + metagraphStateRoot)  
**Related:** Phase 1B card (69a04ae3), SDK authenticated trie card (69963015), PR #61 (feat/authenticated-tries)

---

## 1. Problem Statement

OttoChain fiber state is managed by ML0's `CalculatedState`. Clients (bridges, SDK, external verifiers) currently have no way to prove that a fiber's `stateData` matches what the metagraph committed to in a snapshot — they must trust the bridge entirely.

**Goal:** Enable trustless verification of fiber state by:
1. Exposing Merkle Patricia Trie (MPT) inclusion proofs for individual fiber state fields
2. Anchoring those per-fiber proofs to the `metagraphStateRoot` committed in `hashCalculatedState`
3. Giving TypeScript clients a verifier that needs only the snapshot hash + proof (no full state download)

### What Phase 1 (PR #117) Already Added

PR #117 established the data layer:
- `StateMachineFiberRecord.stateRoot: Option[Hash]` — per-fiber MPT root of `stateData` fields
- `CalculatedState.metagraphStateRoot: Option[Hash]` — MPT root over `{fiberIdHex → stateRoot}` map
- `hashCalculatedState` returns `metagraphStateRoot` when present (making it the snapshot commitment)

This spec covers **Phase 1B**: the proof-generation API that makes those roots useful to clients.

---

## 2. Architecture Overview

```
Client wants to verify fiber X, field "balance"
         │
         ▼
GET /v1/state-machines/{fiberId}/state-proof?field=balance
         │
         ▼  [ML0CustomRoutes.scala]
         │  1. Get stateData from CheckpointService
         │  2. Build 5-leaf trie: stateData field → JSON value
         │  3. attestPath(hexEncode("balance")) → field-level proof
         │  4. Build metagraph trie: fiberIdHex → stateRoot
         │  5. attestPath(fiberIdHex) → metagraph-level proof
         │  6. Return {fieldProof, metagraphProof, stateRoot, metagraphStateRoot}
         │
         ▼
Client reconstructs:
  - Hashes stateData[field] → compares against Leaf.dataDigest in fieldProof
  - Verifies fieldProof against stateRoot
  - Verifies metagraphProof against metagraphStateRoot
  - Compares metagraphStateRoot against known snapshot hash (from global snapshot)
```

### Why ML0 (Not Bridge)

Proof generation requires `CheckpointService` access (live `CalculatedState` with all `stateData` fields). Only ML0 has this. The bridge proxies the endpoint transparently — no business logic in the bridge.

### Why Stateless (Not Persistent LevelDB)

Phase 1 uses `StatelessMerklePatriciaProducer` (recomputes the trie from `stateData` on each request). For the 5-field trie typical of OttoChain fibers, this takes **<5ms** — no meaningful latency. LevelDB adds operational complexity (disk management, per-fiber files) with zero benefit at current scale. If Phase 3 requires historical proofs, the Indexer's stored `stateData` history enables reconstruction without LevelDB on ML0.

---

## 3. Proof Data Model

### 3.1 Request

```
GET /v1/state-machines/{fiberId}/state-proof?field={fieldName}
```

**Path param:** `fiberId` — UUID of the fiber (e.g. `550e8400-e29b-41d4-a716-446655440000`)  
**Query param:** `field` — dot-path into `stateData` JSON (e.g. `balance`, `owner`, `status`)  
**Auth:** Public (no authentication required — proofs contain no sensitive data)

**Omit `?field`:** Returns only the metagraph-level proof (proves the fiber's stateRoot is committed, without exposing stateData contents). Useful for existence proofs.

### 3.2 Success Response (200 OK)

```json
{
  "fiberId": "550e8400-e29b-41d4-a716-446655440000",
  "field": "balance",
  "value": {"var": 1000},
  "stateRoot": "abc123...",
  "metagraphStateRoot": "def456...",
  "fieldProof": {
    "path": "62616c616e6365",
    "witness": [
      { "type": "Leaf", "contents": { "remaining": [6, 2, ...], "dataDigest": "sha256hash..." } }
    ]
  },
  "metagraphProof": {
    "path": "550e8400e29b41d4a716446655440000",
    "witness": [
      { "type": "Branch", "contents": { "pathsDigest": {...} } },
      { "type": "Leaf", "contents": { "remaining": [...], "dataDigest": "sha256hash..." } }
    ]
  }
}
```

**Field descriptions:**

| Field | Type | Description |
|-------|------|-------------|
| `fiberId` | string | UUID of the fiber |
| `field` | string | The requested field name |
| `value` | JSON | The current value of `stateData[field]` |
| `stateRoot` | Hex | SHA-256 MPT root of this fiber's stateData |
| `metagraphStateRoot` | Hex | SHA-256 MPT root of all fiber stateRoots |
| `fieldProof` | object | MPT inclusion proof: `stateData[field]` in fiber trie |
| `metagraphProof` | object | MPT inclusion proof: `stateRoot` in metagraph trie |

### 3.3 MPT Key Encoding

**Field-level trie keys:**  
`hex(utf8Bytes(fieldName))` — e.g., `"balance"` → `62616c616e6365`

**Metagraph-level trie keys:**  
`fiberIdUUID.toString.replace("-", "")` — e.g., UUID `550e8400-e29b-41d4-a716-446655440000` → `550e8400e29b41d4a716446655440000`

(This matches the encoding already used in `ML0Service.computeMetagraphStateRoot`)

### 3.4 Error Responses

| HTTP | Error | Condition |
|------|-------|-----------|
| 404 | `fiber_not_found` | UUID exists but no fiber in CalculatedState |
| 404 | `field_not_found` | fiber exists but `stateData[field]` is absent |
| 404 | `no_state_root` | fiber exists but `stateRoot` is None (pre-PR #117) |
| 400 | `invalid_uuid` | fiberId is not a valid UUID |
| 500 | `proof_error` | MPT proof generation failed (should not happen) |

```json
{
  "error": "fiber_not_found",
  "message": "No fiber found with id: 550e8400-e29b-41d4-a716-446655440000"
}
```

---

## 4. ML0 Implementation Spec

### 4.1 New Route in `ML0CustomRoutes.scala`

```scala
case req @ GET -> Root / "state-machines" / UUIDVar(fiberId) / "state-proof" :?
    OptionalQueryParamDecoderMatcher[String]("field")(fieldOpt) =>
  generateStateProof(fiberId, fieldOpt)
```

### 4.2 `generateStateProof` Logic

```scala
private def generateStateProof(fiberId: UUID, fieldOpt: Option[String]): F[Response[F]] = {
  checkpointService.get.flatMap { case Checkpoint(_, state) =>
    state.stateMachines.get(fiberId) match {
      case None =>
        NotFound(Json.obj("error" -> "fiber_not_found".asJson, ...))
      
      case Some(fiber) if fiber.stateRoot.isEmpty =>
        NotFound(Json.obj("error" -> "no_state_root".asJson, ...))
      
      case Some(fiber) =>
        val stateData: Map[String, Json] = fiber.stateData  // from StateMachineFiberRecord
        val stateRoot: Hash = fiber.stateRoot.get
        
        // Build field-level trie (same key encoding as FiberCombiner)
        val fieldKeys: Map[Hex, Json] = stateData.map { case (k, v) =>
          Hex(k.getBytes("UTF-8").map("%02x".format(_)).mkString) -> v
        }
        
        for {
          // Field-level proof
          fieldProofOpt <- fieldOpt.traverse { field =>
            stateData.get(field) match {
              case None => NotFound(Json.obj("error" -> "field_not_found".asJson, ...)).pure[F]
              case Some(_) =>
                val fieldHex = Hex(field.getBytes("UTF-8").map("%02x".format(_)).mkString)
                MerklePatriciaProducer.stateless[F]
                  .create(fieldKeys)
                  .flatMap(trie => MerklePatriciaProver.make(trie).attestPath(fieldHex))
            }
          }
          
          // Metagraph-level proof (fiberId → stateRoot)
          metagraphKeys: Map[Hex, Hash] = state.stateMachines.collect {
            case (id, f) if f.stateRoot.isDefined =>
              Hex(id.toString.replace("-", "")) -> f.stateRoot.get
          }
          metagraphProof <- MerklePatriciaProducer.stateless[F]
            .create(metagraphKeys)
            .flatMap(trie =>
              MerklePatriciaProver.make(trie)
                .attestPath(Hex(fiberId.toString.replace("-", "")))
            )
          
          response = buildProofResponse(fiberId, fieldOpt, fiber, stateRoot, 
                                        state.metagraphStateRoot.get, fieldProofOpt, metagraphProof)
          result <- Ok(response)
        } yield result
    }
  }
}
```

### 4.3 Bridge Proxy

The bridge routes `GET /fiber/:fiberId/state-proof?field=X` → ML0 `GET /v1/state-machines/:fiberId/state-proof?field=X` transparently. No validation logic in the bridge — it just forwards and returns the response.

New bridge route in `routes/stateProof.ts`:
```typescript
router.get('/fiber/:fiberId/state-proof', async (req, res) => {
  const { fiberId } = req.params;
  const { field } = req.query;
  const url = `${ML0_URL}/v1/state-machines/${fiberId}/state-proof${field ? `?field=${field}` : ''}`;
  const response = await fetch(url);
  const data = await response.json();
  res.status(response.status).json(data);
});
```

---

## 5. Canonicalization Note for Verifiers

**Critical for TypeScript implementors:**

The MPT leaf data digest is computed via `JsonBinaryHasher.computeDigest` → `JsonBinaryCodec.serialize` → `JsonCanonicalizer.canonicalizeJson` which uses **RFC 8785** with **UTF-16BE key sort order**.

This is **not** the same as `JSON.stringify` with `Object.keys().sort()` for non-ASCII keys.

For all-ASCII field names (which OttoChain stateData uses), simple lexicographic key sort produces the same result. But TypeScript verifiers MUST NOT assume this — they should use a proper RFC 8785 implementation (e.g., the `canonicalize` npm package or `JSON.stringify` with `replacer` + `Array.sort()` with UTF-16 code unit comparison).

**SHA-256 prefix bytes** (from `MerklePatriciaNode`):
- Leaf nodes: prefix `0x00`  
- Branch nodes: prefix `0x01`  
- Extension nodes: prefix `0x02`

These must be prepended before hashing when reimplementing the verifier.

---

## 6. TypeScript Client Verification

### 6.1 `verifyStateProof()` — ~30 lines

```typescript
import { createHash } from 'crypto'; // or SubtleCrypto in browsers

type Proof = {
  path: string;           // hex-encoded path
  witness: Commitment[];  // ordered leaf-to-root
};

type Commitment = 
  | { type: 'Leaf';      contents: { remaining: number[]; dataDigest: string } }
  | { type: 'Branch';    contents: { pathsDigest: Record<string, string> } }
  | { type: 'Extension'; contents: { shared: number[]; childDigest: string } };

const PREFIX = { Leaf: Buffer.from([0x00]), Branch: Buffer.from([0x01]), Extension: Buffer.from([0x02]) };

function hexToNibbles(hex: string): number[] {
  return hex.split('').map(c => parseInt(c, 16));
}

async function computeNodeDigest(commitment: Commitment): Promise<string> {
  const json = JSON.stringify(commitment.contents); // all-ASCII keys: safe to use stringify
  const data = Buffer.concat([PREFIX[commitment.type], Buffer.from(json)]);
  return createHash('sha256').update(data).digest('hex');
}

export async function verifyStateProof(
  proof: Proof,
  expectedRoot: string
): Promise<boolean> {
  const nibbles = hexToNibbles(proof.path);
  let currentDigest = expectedRoot;
  let remainingPath = nibbles;

  for (const commitment of [...proof.witness].reverse()) {
    const computedDigest = await computeNodeDigest(commitment);
    if (computedDigest !== currentDigest) return false;

    if (commitment.type === 'Leaf') {
      return commitment.contents.remaining.join('') === remainingPath.join('');
    } else if (commitment.type === 'Extension') {
      remainingPath = remainingPath.slice(commitment.contents.shared.length);
      currentDigest = commitment.contents.childDigest;
    } else if (commitment.type === 'Branch') {
      const nibble = remainingPath[0].toString(16);
      currentDigest = commitment.contents.pathsDigest[nibble];
      remainingPath = remainingPath.slice(1);
    }
  }
  return false;
}
```

### 6.2 End-to-End Verification Pattern

```typescript
// Get proof from bridge
const proof = await fetch(`/fiber/${fiberId}/state-proof?field=balance`).then(r => r.json());

// Step 1: Verify field value matches leaf
const valueJson = JSON.stringify(proof.value);
const valueDigest = sha256(valueJson);
// (Leaf.dataDigest in fieldProof should match valueDigest)

// Step 2: Verify field inclusion in per-fiber trie
const fieldValid = await verifyStateProof(proof.fieldProof, proof.stateRoot);

// Step 3: Verify per-fiber stateRoot in metagraph trie
const metagraphValid = await verifyStateProof(proof.metagraphProof, proof.metagraphStateRoot);

// Step 4: Verify metagraphStateRoot against known snapshot
// (From global snapshot's calculatedStateHash — fetched from L0 API)
const knownHash = await getSnapshotCalculatedStateHash(latestSnapshotOrdinal);
const rootValid = proof.metagraphStateRoot === knownHash;

return fieldValid && metagraphValid && rootValid;
```

---

## 7. Relationship to Existing Work

### PR #61 (feat/authenticated-tries)

PR #61 is an early-stage prototype exploring authenticated trie infrastructure. Phase 1B (this spec) is the production implementation path using metakit's `StatelessMerklePatriciaProducer` which is already in the codebase. 

**Decision:** Do not merge or depend on PR #61. Proceed with Phase 1B implementation directly on `develop`. PR #61 can be closed once Phase 1B merges.

### SDK Card (69963015 — Feasibility)

The SDK card covers TypeScript-side authenticated trie management for **multiple dimensions** (stateData, owners, children, etc.). That is Phase 2. Phase 1B covers only the bridge-proxied proof endpoint for `stateData` fields.

### Phase Structure

| Phase | Scope | Blocked on |
|-------|-------|------------|
| Phase 1 | Per-fiber stateRoot + metagraphStateRoot computation | ✅ PR #117 |
| **Phase 1B** | `GET /state-proof` endpoint (this spec) | PR #117 merge |
| Phase 2 | Historical proofs (Indexer stateData history) | Phase 1B |
| Phase 3 | Multi-dimensional tries (owners, children, scripts) | Phase 2 + SDK card |

---

## 8. Open Questions (Spec-Level — Not Blockers)

These are design decisions to resolve during implementation or James review:

1. **Batch proofs**: Support `?fields=balance,owner` to prove multiple fields in one request? Adds complexity; defer to Phase 2 unless needed immediately.

2. **Auth on proof endpoint**: Public access is fine (proofs contain no secrets). But should we rate-limit? Bridge could add rate limiting if needed.

3. **PR #61 disposition**: Confirm PR #61 should be closed once Phase 1B merges (or keep open for Phase 3 exploration?).

---

## 9. TDD Test Cases

**22 tests in 5 groups.** Tests are for the Phase 1B implementation — blocked on PR #117 merge, which adds `stateRoot` and `metagraphStateRoot` to the data model.

### Group 1: Route Registration (3 tests)

```
T1.1 — GET /v1/state-machines/{uuid}/state-proof returns 200 for valid fiber with stateRoot
T1.2 — GET /v1/state-machines/not-a-uuid/state-proof returns 400 (invalid UUID)
T1.3 — GET /v1/state-machines/{uuid}/state-proof without ?field omits fieldProof (returns metagraph proof only)
```

### Group 2: Error Cases (4 tests)

```
T2.1 — Unknown fiberId → 404 with error: "fiber_not_found"
T2.2 — Known fiberId, unknown field → 404 with error: "field_not_found"
T2.3 — Known fiberId with stateRoot: None → 404 with error: "no_state_root"
T2.4 — Empty CalculatedState (no fibers) → 404 for any fiberId
```

### Group 3: Proof Format (5 tests)

```
T3.1 — Response contains fiberId, field, value, stateRoot, metagraphStateRoot, fieldProof, metagraphProof
T3.2 — fieldProof.path = hex(utf8("fieldName"))
T3.3 — metagraphProof.path = fiberId.toString.replace("-", "")
T3.4 — fieldProof.witness is non-empty List[MerklePatriciaCommitment] (Leaf/Branch/Extension)
T3.5 — metagraphProof.witness is non-empty List[MerklePatriciaCommitment]
```

### Group 4: Proof Correctness — Scala Round-Trip (5 tests)

```
T4.1 — fieldProof verifies against stateRoot using MerklePatriciaVerifier.make(stateRoot).confirm(fieldProof)
T4.2 — metagraphProof verifies against metagraphStateRoot using MerklePatriciaVerifier.make(metagraphStateRoot).confirm(metagraphProof)
T4.3 — stateRoot returned in response matches fiber.stateRoot from CalculatedState (same value PR #117 computed)
T4.4 — metagraphStateRoot returned matches state.metagraphStateRoot (same value hashCalculatedState returns)
T4.5 — Tampered stateData field → MerklePatriciaVerifier.confirm returns Left (proof invalid)
```

### Group 5: TypeScript Verifier (5 tests)

```
T5.1 — verifyStateProof(fieldProof, stateRoot) returns true for proof from Scala endpoint
T5.2 — verifyStateProof(metagraphProof, metagraphStateRoot) returns true for proof from Scala endpoint
T5.3 — verifyStateProof with wrong expectedRoot returns false
T5.4 — verifyStateProof with tampered witness (changed dataDigest) returns false
T5.5 — Full chain: field proof → stateRoot → metagraphProof → metagraphStateRoot matches hashCalculatedState output
```

**Test file locations:**
- Scala: `modules/l0/src/test/scala/xyz/kd5ujc/metagraph_l0/StateProofRouteSuite.scala`
- TypeScript: `ottochain-sdk/src/__tests__/state-proof-verifier.test.ts`

**Pre-conditions:**
- PR #117 merged (stateRoot + metagraphStateRoot fields available)
- `MerklePatriciaProducer.stateless` available in l0 module (already is)
- `MerklePatriciaProver.make` available (already is)
- `MerklePatriciaVerifier.make` available (already is)

---

## 10. Acceptance Criteria

- [ ] **AC-1:** `GET /v1/state-machines/{fiberId}/state-proof?field={field}` returns 200 with proof JSON
- [ ] **AC-2a:** `fieldProof` validates against `stateRoot` using `MerklePatriciaVerifier`
- [ ] **AC-2b:** `metagraphProof` validates against `metagraphStateRoot` using `MerklePatriciaVerifier`
- [ ] **AC-3:** `metagraphStateRoot` in response equals `hashCalculatedState` output (snapshot commitment)
- [ ] **AC-4:** Request with unknown `fiberId` returns 404
- [ ] **AC-5:** Request with unknown `field` returns 404
- [ ] **AC-6:** Request without `?field` returns 200 with metagraph proof only (no `fieldProof`)
- [ ] **AC-7:** Bridge proxy `GET /fiber/:fiberId/state-proof` forwards to ML0 transparently
- [ ] **AC-8:** TypeScript `verifyStateProof()` passes T5.1–T5.5 cross-language tests
- [ ] **AC-9:** All 22 TDD tests pass before implementation is considered complete
- [ ] **AC-10:** Canonicalization note documented in SDK (TypeScript verifier README)

---

## 11. Implementation Checklist

```
Pre-conditions:
  ☐ PR #117 merged to develop

Phase 1B implementation (~3h @work):
  ☐ Add OptionalQueryParamDecoderMatcher[String]("field") to ML0CustomRoutes
  ☐ Add generateStateProof() private method
  ☐ Add new GET route: /state-machines/{uuid}/state-proof
  ☐ Add proof JSON response encoder
  ☐ Add bridge proxy route: GET /fiber/:fiberId/state-proof → ML0

Tests (@code):
  ☐ StateProofRouteSuite.scala — 17 Scala tests (Groups 1-4)
  ☐ state-proof-verifier.test.ts — 5 TypeScript tests (Group 5)

Documentation:
  ☐ SDK README: verifyStateProof() usage + canonicalization warning
  ☐ Update ML0CustomRoutes route table in docs/
```
