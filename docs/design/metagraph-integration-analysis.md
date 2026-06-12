# Analysis: Constellation Metagraph Integration Validation

**Status:** ✅ Validated — Phase 1 Ready for Implementation  
**Authors:** @research (feasibility) + @think (specification)  
**Date:** 2026-02-25  
**Trello Card:** Analysis: Validate Constellation metagraph integration approach (6996301a4dba20da34b4fc9e)  
**Refs:** `docs/proposals/state-commitment-mpt.md` (comprehensive 6-phase proposal)

---

## Executive Summary

The asset model integration with Constellation metagraph semantics is **validated and production-ready for Phase 1**. All required primitives exist in the current codebase:

| Primitive | Status | Location |
|-----------|--------|----------|
| `OttochainMessage extends DataUpdate` wire format | ✅ Live in production | `modules/models/src/…/Updates.scala` |
| `hashCalculatedState` → L0 state commitment | ✅ Live | `modules/l0/…/ML0Service.scala` |
| MPT implementation | ✅ In metakit | `io.constellationnetwork.metagraph_sdk.crypto.mpt.*` |
| ML0 rejection handling | ✅ Live (PR #92) | `WebhookDispatcher.dispatchRejection` |
| `onGlobalSnapshotPull` hook | ✅ Real (Phase 3) | Tessellation `DataApplicationL0Service` |

**Phase 1 is implementation-ready with zero new library dependencies.**

Phase 2 (reward emission via `getTokenUnlocks`) requires Constellation team confirmation before speccing — `TokenUnlock` requires a prior lock, not free-mint. Phases 3–6 depend on Phase 1 and Phase 2 decisions.

---

## 1. Integration Point Validation

### 1.1 Wire Format — No Tessellation Changes Required

`OttochainMessage extends DataUpdate` is the established and proven pattern. Any new `AssetUpdate` variants follow the same JSON discriminated-union wire format:

```scala
// Current (works today):
sealed trait OttochainMessage extends DataUpdate
case class CreateStateMachine(...) extends OttochainMessage
case class TransitionStateMachine(...) extends OttochainMessage

// Future AssetUpdate additions follow same pattern:
case class CreateAsset(...) extends OttochainMessage
case class TransferAsset(...) extends OttochainMessage
```

DL1 endpoint (`POST /data`) accepts any `DataUpdate` subtype unchanged. **No Tessellation changes required for new asset operation types.**

### 1.2 State Commitment — Already Wired

The commit pathway is already complete:

```
hashCalculatedState(state: CalculatedState): F[Hash]
  → state.computeDigest                          // Circe JSON hash
  → CurrencyIncrementalSnapshot.calculatedStateHash
  → GlobalIncrementalSnapshot                    // anchored in L0
```

Current implementation in `ML0Service.scala`:
```scala
override def hashCalculatedState(state: CalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
  state.computeDigest
```

The `computeDigest` implementation is Circe-based. **Phase 1 replaces this with an MPT root hash**, providing cryptographic verifiability of individual fiber states without changing the commitment pathway.

### 1.3 MPT Implementation — Zero New Dependencies

`io.constellationnetwork.metagraph_sdk.crypto.mpt` is fully implemented in `metakit`:

```scala
// Available today, no new dependencies:
MerklePatriciaProducer.inMemory[F, K, V]   // for Phase 1 (per consensus round)
MerklePatriciaProducer.stateless[F, K, V]  // same, rebuilds each time
MerklePatriciaProducer.withLevelDB[F, K, V] // for Phase 2 (persistent, incremental)
MerklePatriciaProver[F, K, V]              // generates inclusion proofs
MerklePatriciaVerifier[K, V]               // stateless proof verification
```

### 1.4 ML0 Rejection Handling — Already Works for New Variants

`WebhookDispatcher.dispatchRejection` (PR #92) handles per-update rejection at ML0 for any `DataUpdate` subtype. Any future `AssetUpdate` variant that fails validation gets:
1. Rejected from the snapshot
2. Webhook notification dispatched (if `WEBHOOK_URL` configured)
3. Rejection stored in indexer via existing pipeline

No additional rejection handling code needed for asset operations.

---

## 2. Phase 1 Specification: MPT State Commitment

### 2.1 Scope

Add per-fiber `stateRoot` and a metagraph-level `metagraphStateRoot` to the OttoChain state, computed in the `Combiner` after each snapshot round, and used in `hashCalculatedState` as the canonical state digest.

This enables:
- Cryptographic proof that any fiber had a specific state at a given ordinal
- Light-client verification without downloading full state
- Foundation for Phase 3 cross-metagraph proofs

### 2.2 Schema Changes

#### 2.2.1 `StateMachineFiberRecord` — Add `stateRoot`

**File:** `modules/models/src/main/scala/xyz/kd5ujc/schema/Records.scala`

```scala
// Before:
final case class StateMachineFiberRecord(
  fiberId:               UUID,
  creationOrdinal:       SnapshotOrdinal,
  previousUpdateOrdinal: SnapshotOrdinal,
  latestUpdateOrdinal:   SnapshotOrdinal,
  definition:            StateMachineDefinition,
  currentState:          StateId,
  stateData:             JsonLogicValue,
  stateDataHash:         Hash,
  sequenceNumber:        FiberOrdinal,
  owners:                Set[Address],
  // ...
)

// After (add stateRoot):
final case class StateMachineFiberRecord(
  fiberId:               UUID,
  creationOrdinal:       SnapshotOrdinal,
  previousUpdateOrdinal: SnapshotOrdinal,
  latestUpdateOrdinal:   SnapshotOrdinal,
  definition:            StateMachineDefinition,
  currentState:          StateId,
  stateData:             JsonLogicValue,
  stateDataHash:         Hash,
  stateRoot:             Hash,           // ← NEW: MPT root of stateData fields
  sequenceNumber:        FiberOrdinal,
  owners:                Set[Address],
  // ...
)
```

The `stateRoot` is computed from the fiber's `stateData` fields via MPT:
```
key   = UTF-8 bytes of field path (e.g., "balance", "status", "owner")
value = Circe JSON bytes of field value
```

#### 2.2.2 `CalculatedState` — Add `metagraphStateRoot`

**File:** `modules/models/src/main/scala/xyz/kd5ujc/schema/CalculatedState.scala`

```scala
// Before:
case class CalculatedState(
  stateMachines: SortedMap[UUID, Records.StateMachineFiberRecord],
  scripts:       SortedMap[UUID, Records.ScriptFiberRecord]
) extends DataCalculatedState

// After (add metagraphStateRoot):
case class CalculatedState(
  stateMachines:       SortedMap[UUID, Records.StateMachineFiberRecord],
  scripts:             SortedMap[UUID, Records.ScriptFiberRecord],
  metagraphStateRoot:  Hash    // ← NEW: MPT root over all fiber stateRoots
) extends DataCalculatedState

object CalculatedState {
  // genesis still valid — use Hash.empty or Hash.computeFrom("")
  val genesis: CalculatedState = CalculatedState(SortedMap.empty, SortedMap.empty, Hash.empty)
}
```

### 2.3 Combiner Changes

**File:** `modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/lifecycle/combine/FiberCombiner.scala`

After each fiber is updated in `FiberCombiner`, compute the fiber's `stateRoot`:

```scala
import io.constellationnetwork.metagraph_sdk.crypto.mpt.MerklePatriciaProducer

def computeFiberStateRoot[F[_]: Sync](stateData: JsonLogicValue): F[Hash] = {
  val fields: Map[String, Json] = stateData.asObject.getOrElse(JsonObject.empty).toMap

  MerklePatriciaProducer.inMemory[F, String, Json].flatMap { mpt =>
    fields.toList.traverse_ { case (k, v) =>
      mpt.update(k, v)
    } >> mpt.commit.map(_.rootHash)
  }
}
```

After all fiber updates for a snapshot are complete, compute the `metagraphStateRoot` in the top-level combiner:

```scala
def computeMetagraphStateRoot[F[_]: Sync](
  stateMachines: SortedMap[UUID, Records.StateMachineFiberRecord]
): F[Hash] = {
  MerklePatriciaProducer.inMemory[F, String, Hash].flatMap { mpt =>
    stateMachines.toList.traverse_ { case (uuid, record) =>
      mpt.update(uuid.toString, record.stateRoot)
    } >> mpt.commit.map(_.rootHash)
  }
}
```

### 2.4 `hashCalculatedState` Override

**File:** `modules/l0/src/main/scala/xyz/kd5ujc/metagraph_l0/ML0Service.scala`

```scala
// Phase 1: return MPT root instead of Circe digest
override def hashCalculatedState(state: CalculatedState)(implicit context: L0NodeContext[F]): F[Hash] =
  Sync[F].pure(state.metagraphStateRoot)
```

This replaces the Circe-based `computeDigest` with the deterministic MPT root. The L0 anchoring pathway is unchanged — it still flows through `CurrencyIncrementalSnapshot.calculatedStateHash`.

### 2.5 ML0 API: Inclusion Proof Endpoint

**File:** `modules/l0/src/main/scala/xyz/kd5ujc/metagraph_l0/ML0CustomRoutes.scala`

Add a new route for clients to request inclusion proofs:

```
GET /state-proof/:fiberId
```

Response:
```json
{
  "fiberId": "uuid",
  "ordinal": 12345,
  "stateRoot": "0xabc...",
  "metagraphStateRoot": "0xdef...",
  "proof": {
    "path": [...],
    "witness": [...]
  }
}
```

Implementation uses `MerklePatriciaProver` to generate the proof from the current `CalculatedState`.

---

## 3. Open Questions (Blocking Phases 2+)

These questions must be answered before speccing Phase 2+. They do NOT block Phase 1.

| # | Question | Blocking | Who |
|---|----------|---------|-----|
| OQ-1 | Does `getTokenUnlocks` require a prior `TokenLock`, or can it emit native DAG as a free mint? | Phase 2 (reward emission) | Constellation team |
| OQ-2 | What fields are in `GlobalSnapshotInfo` passed to `onGlobalSnapshotPull`? Specifically: does it include per-metagraph `calculatedStateHash` values for foreign state root lookup? | Phase 3 (cross-metagraph proofs) | Tessellation source audit |
| OQ-3 | Should `stateRoot` and `metagraphStateRoot` be added to the proto `StateMachineFiberRecord` in the same PR as the hand-written changes, or as a followup after the proto migration card (699621e1) completes? | Phase 1 timing only | James's call |

---

## 4. TDD Test Cases (15 tests in 5 groups)

### Group A: Fiber State Root (5 tests)

**T-A1:** `computeFiberStateRoot` produces deterministic hash for same stateData
```
Input: stateData = { "balance": 1000, "status": "ACTIVE", "owner": "dag1..." }
Expected: same hash on repeated calls, no randomness
```

**T-A2:** `computeFiberStateRoot` produces different hash when stateData changes
```
Input 1: { "balance": 1000 }
Input 2: { "balance": 999 }
Expected: hash(input1) ≠ hash(input2)
```

**T-A3:** `stateRoot` is updated in `StateMachineFiberRecord` after each transition
```
Given: fiber with stateRoot=H1
When: transition changes stateData
Then: fiber.stateRoot ≠ H1
```

**T-A4:** Inclusion proof verifies against fiber's `stateRoot`
```
Given: fiber with stateRoot R, stateData = {"balance": 1000}
When: generate inclusion proof for key "balance"
Then: MerklePatriciaVerifier.verify(R, "balance", 1000, proof) = true
```

**T-A5:** Inclusion proof fails for field not in stateData
```
Given: fiber with stateRoot R, stateData = {"balance": 1000}
When: generate inclusion proof for key "nonexistent"
Then: proof is None (non-inclusion)
```

### Group B: Metagraph State Root (4 tests)

**T-B1:** `metagraphStateRoot` is deterministic for same set of fibers
```
Input: CalculatedState with 3 fibers (same stateRoots, same UUIDs)
Expected: metagraphStateRoot is the same on repeated computation
```

**T-B2:** `metagraphStateRoot` changes when any fiber's stateRoot changes
```
Given: metagraphStateRoot = R1
When: one fiber transitions (stateRoot changes)
Then: metagraphStateRoot ≠ R1
```

**T-B3:** `metagraphStateRoot` changes when a new fiber is added
```
Given: CalculatedState with 2 fibers, metagraphStateRoot = R1
When: 3rd fiber is created
Then: metagraphStateRoot ≠ R1
```

**T-B4:** Empty CalculatedState has deterministic empty root
```
Input: CalculatedState.genesis (no fibers)
Expected: metagraphStateRoot = Hash.empty (or other deterministic sentinel)
```

### Group C: hashCalculatedState Integration (2 tests)

**T-C1:** `hashCalculatedState` returns `metagraphStateRoot` (not Circe digest)
```
Given: CalculatedState with metagraphStateRoot = R
Expected: ML0Service.hashCalculatedState(state) = F.pure(R)
```

**T-C2:** `hashCalculatedState` changes after any fiber update
```
Given: hash1 = hashCalculatedState(state1)
When: fiber transitions (state1 → state2)
Then: hash2 = hashCalculatedState(state2) ≠ hash1
```

### Group D: ML0 Proof API (2 tests)

**T-D1:** `GET /state-proof/:fiberId` returns valid inclusion proof
```
Given: fiber with known stateData
Expected: response contains stateRoot, metagraphStateRoot, and proof
         MerklePatriciaVerifier.verify(proof) = true
```

**T-D2:** `GET /state-proof/:fiberId` returns 404 for unknown fiberId
```
Input: fiberId = random UUID not in CalculatedState
Expected: HTTP 404
```

### Group E: Backward Compatibility (2 tests)

**T-E1:** `CalculatedState.genesis` remains valid (metagraphStateRoot = Hash.empty)
```
Expected: CalculatedState.genesis deserializes without error
          genesis.metagraphStateRoot = Hash.empty (or sentinel value)
```

**T-E2:** Existing `StateMachineFiberRecord` test fixtures still compile
```
All 20+ test suites using `OnChain.genesis` and `CalculatedState.genesis`
Expected: compile and pass with no changes (stateRoot has default = Hash.empty)
```

---

## 5. Implementation Plan

| Step | Owner | Effort | Depends on |
|------|-------|--------|-----------|
| Add `stateRoot` to `StateMachineFiberRecord` | @work | 1h | — |
| Add `metagraphStateRoot` to `CalculatedState` | @work | 30m | Step 1 |
| Implement `computeFiberStateRoot` in `FiberCombiner` | @work | 2h | Steps 1–2 |
| Implement `computeMetagraphStateRoot` in top-level combiner | @work | 1h | Step 3 |
| Override `hashCalculatedState` in `ML0Service` | @work | 30m | Step 4 |
| Add `GET /state-proof/:fiberId` ML0 route | @work | 2h | Steps 1–4 |
| Write 15 failing TDD tests | @code | 2h | This spec |
| Verify all existing 20+ suites still pass | @work | 30m | Step 6 |

**Total estimated effort:** ~9.5 hours

**Repo:** `scasplte2/ottochain` (metagraph Scala changes)  
**Branch pattern:** `feat/mpt-state-commitment`

---

## 6. Relationship to `docs/proposals/state-commitment-mpt.md`

The proposal document covers 6 phases (MPT, state rent, cross-metagraph proofs, Tessellation integration, reward emission, advanced features). This analysis focuses on **Phase 1 only** and validates which parts of the proposal are feasible without changes to Tessellation.

| Proposal Phase | Feasibility | Blocked On |
|---------------|-------------|-----------|
| Phase 1: MPT State Commitment | ✅ READY NOW | This spec → @work |
| Phase 2: State Rent Economics | ⚠️ NEEDS DESIGN | Out of scope for this card |
| Phase 3: Cross-Metagraph Proofs | ✅ Hook exists | Phase 1 first, then OQ-2 |
| Phase 4: Tessellation Integration | ✅ `getTokenUnlocks` real | OQ-1 blocks reward emission spec |
| Phase 5: Reward Emission | ⚠️ NEEDS CONFIRMATION | OQ-1 (TokenUnlock semantics) |
| Phase 6: Advanced JLVM Extensions | 🔮 FUTURE | Phases 1–4 complete |

---

## 7. 5-Type Trie Mapping Clarification

The original tokenized-streams protocol proposed 5 trie dimensions (Permissions, Relationships, Activities, Assets, Group). In OttoChain fiber architecture these map as **application-layer state inside `stateData: JsonLogicValue`**, not as separate metagraph-layer tries.

The correct mapping:

```
tokenized-streams          OttoChain
─────────────────          ──────────────
Permissions trie    →      stateData["permissions"] (JSON object)
Relationships trie  →      stateData["parties"] (JSON array)
Activities trie     →      FiberLogEntry history (on-chain)
Assets trie         →      stateData["holdings"] (JSON object)
Group trie          →      parent/child fiber relationships
```

**Per-fiber MPT** commits all `stateData` fields into a single trie. Apps wanting per-dimension proofs generate:
```
MerklePatriciaProver.prove(fiberId, "permissions.writeAccess")
```

The metagraph root aggregates all fiber roots. This is the correct abstraction for Constellation anchoring — no structural change to the metagraph's data model is needed.

---

*🧠 @think perspective: The key insight from @research is that this is **not a research question** — it's an implementation task. All the pieces exist. The only genuine unknowns are TokenUnlock semantics (Phase 2) and `GlobalSnapshotInfo` contents (Phase 3). Phase 1 can and should proceed now while those questions are answered in parallel. The main risk is the `stateRoot` field timing vs the proto migration card (699621e1) — if we add it to the hand-written `StateMachineFiberRecord` now, we'll need to add it to proto too. Recommend coordinating with James (OQ-3) before opening the PR to avoid double migration work.*
