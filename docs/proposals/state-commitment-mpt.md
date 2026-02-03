# Advanced State Commitment and Cross-Metagraph Communication Protocol

**Version**: 1.0
**Date**: 2025-10-16
**Status**: Planning Document

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Architecture Overview](#architecture-overview)
3. [Phase 1: Merkle Patricia Trie State Commitment](#phase-1-merkle-patricia-trie-state-commitment)
4. [Phase 2: State Rent and Economic Sustainability](#phase-2-state-rent-and-economic-sustainability)
5. [Phase 3: Cross-Metagraph Inclusion Proofs](#phase-3-cross-metagraph-inclusion-proofs)
6. [Phase 4: Tessellation Integration Layer](#phase-4-tessellation-integration-layer)
7. [Phase 5: Reward Emission and Economic Primitives](#phase-5-reward-emission-and-economic-primitives)
8. [Phase 6: Advanced Features](#phase-6-advanced-features)
9. [Implementation Roadmap](#implementation-roadmap)
10. [Technical Specifications](#technical-specifications)

---

## Executive Summary

This document outlines the integration of **Merkle Patricia Trie (MPT) state commitment**, **cross-metagraph communication**, **state rent economics**, and **Tessellation data application primitives** into the Ottochain fiber orchestration framework.

### Key Innovations

1. **MPT State Commitment**: Every fiber's `stateData` is committed to a Merkle Patricia Trie, producing a cryptographic root hash
2. **Hypergraph Anchoring**: Metagraph state roots are published to the hypergraph (L0) for cross-chain verification
3. **Inclusion Proof Protocol**: Light client verification enables trustless cross-metagraph data reads
4. **State Rent Economics**: Incentive-driven state expiry prevents blockchain bloat
5. **Tessellation Integration**: Fibers emit rewards, tokens, and artifacts via `SharedArtifact` mechanism
6. **JSON Logic VM Extensions**: Native operations for cryptographic primitives and proof verification

### Value Proposition

This architecture enables:
- ✅ **Provable cross-metagraph communication** via inclusion proofs
- ✅ **Economic sustainability** through state rent
- ✅ **Automated reward distribution** from fiber state machines
- ✅ **Trust-minimized bridges** between metagraphs
- ✅ **Composable metagraphs** sharing data securely
- ✅ **AI-agent friendly** JSON-native execution with LLM-readable state

---

## Architecture Overview

### Three-Layer State Commitment Model

```
┌─────────────────────────────────────────────────────────────────┐
│ Hypergraph (L0 - Global Consensus)                              │
│ ──────────────────────────────────────────────────────────────── │
│ GlobalSnapshot { ordinal: 12345 }                                │
│ ├─ Metagraph A StateRoot: Hash("0xabc123...")                   │
│ ├─ Metagraph B StateRoot: Hash("0xdef456...")                   │
│ └─ Metagraph C StateRoot: Hash("0x789ghi...")                   │
│                                                                   │
│ Published via: CurrencySnapshotInfo.calculatedStateHash          │
└───────────────────────────────────────────────────────────────────┘
              ▲                                    ▲
              │                                    │
    ┌─────────┴──────────┐              ┌─────────┴─────────┐
    │ Metagraph A (L1)   │              │ Metagraph B (L1)  │
    │ ──────────────────  │              │ ─────────────────  │
    │ State Root:         │              │ State Root:        │
    │   0xabc123...       │              │   0xdef456...      │
    │                     │              │                    │
    │ MPT of Fiber Roots: │              │ MPT of Fibers:     │
    │ ├─ UUID-1 → 0x1a2b  │              │ ├─ UUID-X → 0x9z8y│
    │ └─ UUID-2 → 0x3c4d  │              │ └─ UUID-Y → 0x7x6w│
    └─────────────────────┘              └────────────────────┘
              ▲                                    ▲
              │                                    │
    ┌─────────┴──────────┐              ┌─────────┴─────────┐
    │ Fiber UUID-1       │              │ Oracle UUID-X     │
    │ ──────────────────  │              │ ─────────────────  │
    │ State Root:         │              │ State Root:        │
    │   0x1a2b3c...       │              │   0x9z8y7x...      │
    │                     │              │                    │
    │ MPT of State Fields:│              │ MPT of Data:       │
    │ ├─ "balance" → 1000 │              │ ├─ "price" → 100  │
    │ ├─ "locked" → 250   │              │ └─ "volume" → 1k  │
    │ └─ "updated" → t    │              │                    │
    └─────────────────────┘              └────────────────────┘
```

### Data Flow: Cross-Metagraph Read

```
Metagraph B wants to read Metagraph A's fiber state:

1. Query Hypergraph for Metagraph A root at ordinal N
   ↓
2. Get inclusion proof from Metagraph A:
   - Proof 1: Fiber UUID-1 exists in Metagraph A state
   - Proof 2: Field "balance" = 1000 in Fiber UUID-1 state
   ↓
3. Verify proofs in Metagraph B's JSON Logic VM:
   - verifyInclusionProof(proof1, hypergraphRoot)
   - verifyInclusionProof(proof2, fiberRoot)
   ↓
4. Use verified data in Metagraph B state transition
```

---

## Phase 1: Merkle Patricia Trie State Commitment

### 1.1 Fiber-Level State Commitment

#### Data Model Extension

```scala
// modules/models/src/main/scala/xyz/kd5ujc/schema/Records.scala

case class StateMachineFiberRecord(
  cid: UUID,
  definition: StateMachineDefinition,
  currentState: StateId,
  stateData: JsonLogicValue,
  owners: Set[Address],
  eventLog: List[EventReceipt],
  parentFiberId: Option[UUID],
  childFiberIds: Set[UUID],

  // NEW: State commitment fields
  stateRoot: Hash,                          // MPT root of stateData fields
  lastStateUpdate: SnapshotOrdinal,         // When state last changed
  stateRentPaidUntil: SnapshotOrdinal,      // Expiry ordinal for rent
  storageBytesUsed: Long                    // For rent calculation
)
```

#### State Commitment Algorithm

```scala
// modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/lifecycle/StateCommitment.scala

trait StateCommitment[F[_]] {

  /**
   * Compute MPT root hash for a fiber's stateData
   */
  def computeStateRoot(
    fiberId: UUID,
    stateData: JsonLogicValue
  ): F[Hash]

  /**
   * Generate inclusion proof for a specific field
   */
  def generateFieldProof(
    fiberId: UUID,
    fieldPath: String
  ): F[MerklePatriciaInclusionProof]

  /**
   * Calculate storage bytes used
   */
  def calculateStorageSize(
    stateData: JsonLogicValue
  ): F[Long]
}

object StateCommitment {

  def make[F[_]: JsonBinaryHasher: MonadThrow]: StateCommitment[F] =
    new StateCommitment[F] {

      def computeStateRoot(
        fiberId: UUID,
        stateData: JsonLogicValue
      ): F[Hash] = {
        // Flatten JSON state into key-value pairs
        val flattenedState: Map[String, JsonLogicValue] = flattenJson(stateData)

        // Convert to MPT paths
        val mptPaths: Map[Hex, JsonLogicValue] = flattenedState.map {
          case (key, value) =>
            val path = Hex(Hash(key).value)
            path -> value
        }

        // Build MPT
        for {
          trie <- MerklePatriciaTrie.make[F, JsonLogicValue](mptPaths)
        } yield trie.rootNode.digest
      }

      def generateFieldProof(
        fiberId: UUID,
        fieldPath: String
      ): F[MerklePatriciaInclusionProof] = {
        // Retrieve fiber's current state trie
        for {
          fiber <- getFiber(fiberId)
          trie <- rebuildTrie(fiber.stateData)
          path = Hex(Hash(fieldPath).value)
          prover = MerklePatriciaProver.make(trie)
          proof <- prover.attestPath(path).flatMap {
            case Right(proof) => proof.pure[F]
            case Left(error) => MonadThrow[F].raiseError(error)
          }
        } yield proof
      }

      def calculateStorageSize(stateData: JsonLogicValue): F[Long] = {
        JsonBinaryHasher[F]
          .computeDigest(stateData.asJson)
          .map(_.value.length.toLong)
      }

      private def flattenJson(
        value: JsonLogicValue,
        prefix: String = ""
      ): Map[String, JsonLogicValue] = value match {
        case JsonLogicValue.Object(fields) =>
          fields.flatMap {
            case (key, nested: JsonLogicValue.Object) =>
              flattenJson(nested, s"$prefix$key.")
            case (key, leaf) =>
              Map(s"$prefix$key" -> leaf)
          }
        case leaf => Map(prefix -> leaf)
      }
    }
}
```

### 1.2 Metagraph-Level State Commitment

#### Calculated State Extension

```scala
// modules/models/src/main/scala/xyz/kd5ujc/schema/CalculatedState.scala

case class CalculatedState(
  fibers: Map[UUID, StateMachineFiberRecord],
  oracles: Map[UUID, ScriptFiberRecord],

  // NEW: State commitment aggregation
  fiberStateRoots: Map[UUID, Hash],         // Per-fiber MPT roots
  oracleStateRoots: Map[UUID, Hash],        // Per-oracle MPT roots
  metagraphStateRoot: Hash,                 // MPT of all fiber/oracle roots
  totalStorageBytesUsed: Long,              // Total storage across all fibers

  // Existing fields
  lastUpdate: SnapshotOrdinal,
  eventProcessingStatus: Map[UUID, EventProcessingStatus]
) extends DataCalculatedState
```

#### Metagraph State Root Computation

```scala
// modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/lifecycle/Combiner.scala

trait Combiner[F[_]] {

  def combine(
    updates: NonEmptySet[Signed[Updates]],
    state: DataState
  )(implicit context: L0NodeContext[F]): F[DataState]
}

object Combiner {

  def make[F[_]: Async: JsonBinaryHasher: StateCommitment]: Combiner[F] =
    new Combiner[F] {

      def combine(
        updates: NonEmptySet[Signed[Updates]],
        state: DataState
      )(implicit context: L0NodeContext[F]): F[DataState] = {

        for {
          // Process all updates
          newState <- processUpdates(updates, state)

          // Compute fiber state roots
          fiberRoots <- newState.calculated.fibers.toList.traverse {
            case (uuid, fiber) =>
              StateCommitment[F]
                .computeStateRoot(uuid, fiber.stateData)
                .map(root => uuid -> root)
          }.map(_.toMap)

          // Compute oracle state roots
          oracleRoots <- newState.calculated.oracles.toList.traverse {
            case (uuid, oracle) =>
              StateCommitment[F]
                .computeStateRoot(uuid, oracle.stateData)
                .map(root => uuid -> root)
          }.map(_.toMap)

          // Aggregate into metagraph state root
          allRoots = fiberRoots ++ oracleRoots
          metagraphRoot <- computeMetagraphRoot(allRoots)

          // Calculate total storage
          totalStorage <- (newState.calculated.fibers.values ++
                           newState.calculated.oracles.values)
            .toList
            .traverse(f => StateCommitment[F].calculateStorageSize(f.stateData))
            .map(_.sum)

          // Update calculated state
          updatedCalculated = newState.calculated.copy(
            fiberStateRoots = fiberRoots,
            oracleStateRoots = oracleRoots,
            metagraphStateRoot = metagraphRoot,
            totalStorageBytesUsed = totalStorage
          )

        } yield newState.copy(calculated = updatedCalculated)
      }

      private def computeMetagraphRoot(
        allRoots: Map[UUID, Hash]
      ): F[Hash] = {
        val paths: Map[Hex, Hash] = allRoots.map {
          case (uuid, hash) => Hex(uuid.toString.getBytes) -> hash
        }

        MerklePatriciaTrie
          .make[F, Hash](paths)
          .map(_.rootNode.digest)
      }
    }
}
```

### 1.3 Hypergraph Anchoring

#### Integration with Tessellation

```scala
// modules/l0/src/main/scala/xyz/kd5ujc/metagraph_l0/ML0Service.scala

trait ML0Service[F[_]] extends BaseDataApplicationL0Service[F] {

  override def hashCalculatedState(
    state: DataCalculatedState
  )(implicit context: L0NodeContext[F]): F[Hash] = {
    state match {
      case cs: CalculatedState =>
        // Return the metagraph state root for hypergraph anchoring
        cs.metagraphStateRoot.pure[F]

      case _ =>
        MonadThrow[F].raiseError(new Exception("Invalid state type"))
    }
  }

  override def onSnapshotConsensusResult(
    snapshot: Hashed[CurrencyIncrementalSnapshot]
  ): F[Unit] = {
    for {
      _ <- Logger[F].info(
        s"Snapshot ${snapshot.ordinal} committed with state root: ${snapshot.calculatedStateHash}"
      )

      // The calculatedStateHash is now the metagraph state root
      // This gets published to the hypergraph via global snapshot

    } yield ()
  }
}
```

When the currency snapshot is created, the `calculatedStateHash` (which is the metagraph MPT root) is included in the snapshot and propagates to the hypergraph via the global snapshot mechanism.

---

## Phase 2: State Rent and Economic Sustainability

### 2.1 State Rent Economics

#### Problem Statement

Blockchain state grows unbounded without economic pressure. Ethereum's state is >100GB. We need **state rent** to:
1. Charge ongoing fees for state storage
2. Incentivize state cleanup
3. Enable state expiry for abandoned fibers
4. Make storage costs explicit

#### Rent Model: Pay-Per-Byte-Per-Epoch

```
Rent Formula:
  rentDue = storageBytesUsed × rentRatePerBytePerEpoch × epochsElapsed

Default Parameters:
  rentRatePerBytePerEpoch = 0.00001 DAG per byte per epoch
  gracePeriodEpochs = 1000 epochs (~7 days at 10 min epochs)

Example:
  Fiber with 10KB state (10,000 bytes)
  Rent for 1000 epochs = 10,000 × 0.00001 × 1000 = 100 DAG
```

#### State Rent State Machine

```scala
// modules/models/src/main/scala/xyz/kd5ujc/schema/StateMachine.scala

case class StateRentStatus(
  lastRentPayment: SnapshotOrdinal,
  rentPaidUntil: SnapshotOrdinal,
  rentDebtAccrued: Amount,
  storageBytesUsed: Long,
  isDelinquent: Boolean,
  gracePeriodEnds: Option[SnapshotOrdinal]
)

case class StateMachineFiberRecord(
  // ... existing fields ...

  // Rent tracking
  rentStatus: StateRentStatus
)
```

#### Rent Collection Machine

**Design Philosophy**: Incentive-driven, not execution-driven

- Rent is **not** automatically deducted
- State becomes **read-only** after grace period
- Economic incentive: Anyone can "reap" expired state and claim bounty
- Owner can always pay rent to revive fiber

```scala
// State Rent Machine Definition (JSON-encoded)
val stateRentMachineDefinition = StateMachineDefinition(
  states = Map(
    StateId("active") -> State(StateId("active"), isFinal = false),
    StateId("rent_due") -> State(StateId("rent_due"), isFinal = false),
    StateId("grace_period") -> State(StateId("grace_period"), isFinal = false),
    StateId("expired") -> State(StateId("expired"), isFinal = false),
    StateId("reaped") -> State(StateId("reaped"), isFinal = true)
  ),
  initialState = StateId("active"),
  transitions = List(

    // Active → Rent Due
    Transition(
      from = StateId("active"),
      to = StateId("rent_due"),
      eventType = EventType("check_rent"),
      guard = json"""{
        ">": [
          {"var": "state.currentOrdinal"},
          {"var": "state.rentStatus.rentPaidUntil"}
        ]
      }""",
      effect = json"""{
        "rentStatus.isDelinquent": true,
        "rentStatus.gracePeriodEnds": {
          "+": [
            {"var": "state.currentOrdinal"},
            1000
          ]
        },
        "_outputs": [{
          "type": "notification",
          "message": {"cat": [
            "Rent overdue for fiber ",
            {"var": "state.cid"},
            ". Grace period ends at ordinal ",
            {"var": "rentStatus.gracePeriodEnds"}
          ]}
        }]
      }"""
    ),

    // Rent Due → Active (pay rent)
    Transition(
      from = StateId("rent_due"),
      to = StateId("active"),
      eventType = EventType("pay_rent"),
      guard = json"""{
        "and": [
          {">=": [
            {"var": "event.paymentAmount"},
            {"var": "state.rentStatus.rentDebtAccrued"}
          ]},
          {"in": [
            {"var": "event.signer"},
            {"var": "state.owners"}
          ]}
        ]
      }""",
      effect = json"""{
        "rentStatus.isDelinquent": false,
        "rentStatus.lastRentPayment": {"var": "state.currentOrdinal"},
        "rentStatus.rentPaidUntil": {
          "+": [
            {"var": "state.currentOrdinal"},
            {"/": [
              {"var": "event.paymentAmount"},
              {"*": [
                {"var": "state.rentStatus.storageBytesUsed"},
                0.00001
              ]}
            ]}
          ]
        },
        "rentStatus.rentDebtAccrued": 0,
        "_triggers": [{
          "targetFiberId": {"var": "state.cid"},
          "eventType": "rent_paid",
          "payloadExpr": {
            "paidUntil": {"var": "rentStatus.rentPaidUntil"}
          }
        }]
      }"""
    ),

    // Rent Due → Grace Period (automatic after check)
    Transition(
      from = StateId("rent_due"),
      to = StateId("grace_period"),
      eventType = EventType("check_rent"),
      guard = json"""{
        ">": [
          {"var": "state.currentOrdinal"},
          {"+": [
            {"var": "state.rentStatus.rentPaidUntil"},
            100
          ]}
        ]
      }""",
      effect = json"""{}"""
    ),

    // Grace Period → Expired
    Transition(
      from = StateId("grace_period"),
      to = StateId("expired"),
      eventType = EventType("check_expiry"),
      guard = json"""{
        ">=": [
          {"var": "state.currentOrdinal"},
          {"var": "state.rentStatus.gracePeriodEnds"}
        ]
      }""",
      effect = json"""{
        "_outputs": [{
          "type": "state_expired",
          "fiberId": {"var": "state.cid"},
          "expiryOrdinal": {"var": "state.currentOrdinal"},
          "reapBounty": {"*": [
            {"var": "state.rentStatus.storageBytesUsed"},
            0.00005
          ]}
        }]
      }"""
    ),

    // Expired → Reaped (anyone can reap)
    Transition(
      from = StateId("expired"),
      to = StateId("reaped"),
      eventType = EventType("reap_expired_state"),
      guard = json"""{
        ">=": [
          {"var": "state.currentOrdinal"},
          {"var": "state.rentStatus.gracePeriodEnds"}
        ]
      }""",
      effect = json"""{
        "_rewards": [{
          "destination": {"var": "event.reaper"},
          "amount": {"*": [
            {"var": "state.rentStatus.storageBytesUsed"},
            0.00005
          ]},
          "reason": "state_reaping_bounty"
        }],
        "_outputs": [{
          "type": "fiber_reaped",
          "fiberId": {"var": "state.cid"},
          "reaper": {"var": "event.reaper"},
          "storageReclaimed": {"var": "state.rentStatus.storageBytesUsed"}
        }]
      }"""
    ),

    // Expired → Active (resurrection)
    Transition(
      from = StateId("expired"),
      to = StateId("active"),
      eventType = EventType("resurrect"),
      guard = json"""{
        "and": [
          {"in": [
            {"var": "event.signer"},
            {"var": "state.owners"}
          ]},
          {">=": [
            {"var": "event.paymentAmount"},
            {"*": [
              {"var": "state.rentStatus.rentDebtAccrued"},
              1.5
            ]}
          ]}
        ]
      }""",
      effect = json"""{
        "rentStatus.isDelinquent": false,
        "rentStatus.lastRentPayment": {"var": "state.currentOrdinal"},
        "rentStatus.rentPaidUntil": {
          "+": [
            {"var": "state.currentOrdinal"},
            1000
          ]
        },
        "rentStatus.gracePeriodEnds": null,
        "_outputs": [{
          "type": "notification",
          "message": {"cat": [
            "Fiber ",
            {"var": "state.cid"},
            " resurrected with 50% penalty"
          ]}
        }]
      }"""
    )
  )
)
```

### 2.2 Rent Automation: Incentive-Driven Execution

#### Keepers / Reaper Bots

Third parties run **keeper bots** that:
1. Monitor fibers approaching rent expiry
2. Send `check_rent` events to trigger transitions
3. Reap expired fibers to claim bounty

```scala
// Example keeper bot pseudocode
for {
  fibers <- getAllFibers()

  expiringSoon = fibers.filter { fiber =>
    fiber.rentStatus.rentPaidUntil < currentOrdinal + 100
  }

  _ <- expiringSoon.traverse { fiber =>
    submitEvent(
      fiberId = fiber.cid,
      event = Event(
        eventType = EventType("check_rent"),
        payload = JsonLogicValue.Object(Map(
          "currentOrdinal" -> currentOrdinal
        ))
      )
    )
  }

  expired = fibers.filter { fiber =>
    fiber.rentStatus.isDelinquent &&
    currentOrdinal >= fiber.rentStatus.gracePeriodEnds.get
  }

  _ <- expired.traverse { fiber =>
    submitEvent(
      fiberId = fiber.cid,
      event = Event(
        eventType = EventType("reap_expired_state"),
        payload = JsonLogicValue.Object(Map(
          "reaper" -> myAddress
        ))
      )
    )
  }
} yield ()
```

**Economic Alignment**:
- Keepers earn bounties for reaping expired state
- Metagraph benefits from reduced state bloat
- Fiber owners have incentive to pay rent
- No centralized automation required

### 2.3 Rent Integration with Tessellation

```scala
// modules/l0/src/main/scala/xyz/kd5ujc/metagraph_l0/ML0CustomRoutes.scala

object ML0CustomRoutes {

  def rentRoutes[F[_]: Async](implicit context: L0NodeContext[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {

      // Get rent status for a fiber
      case GET -> Root / "rent" / "fiber" / UUIDVar(fiberId) =>
        for {
          state <- context.getLastCurrencySnapshotCombined
          fiber = state.calculated.fibers.get(fiberId)
          response <- fiber match {
            case Some(f) => Ok(f.rentStatus.asJson)
            case None => NotFound(s"Fiber $fiberId not found")
          }
        } yield response

      // Get all fibers expiring soon
      case GET -> Root / "rent" / "expiring" :? OrdinalQueryParamMatcher(withinOrdinals) =>
        for {
          state <- context.getLastCurrencySnapshotCombined
          currentOrdinal = state._1.ordinal

          expiring = state.calculated.fibers.values.filter { fiber =>
            fiber.rentStatus.rentPaidUntil <= currentOrdinal + withinOrdinals
          }

          response <- Ok(expiring.toList.asJson)
        } yield response

      // Pay rent for a fiber (submit signed transaction)
      case req @ POST -> Root / "rent" / "pay" =>
        for {
          paymentTx <- req.as[Signed[RentPaymentUpdate]]

          // Submit to data application
          result <- submitDataUpdate(paymentTx)

          response <- Ok(result.asJson)
        } yield response
    }
}
```

---

## Phase 3: Cross-Metagraph Inclusion Proofs

### 3.1 Proof Data Structures

```scala
// modules/models/src/main/scala/xyz/kd5ujc/schema/CrossMetagraphProof.scala

case class FiberFieldProof(
  fiberId: UUID,
  fieldPath: String,
  fieldValue: JsonLogicValue,
  proof: MerklePatriciaInclusionProof,
  fiberStateRoot: Hash
)

case class MetagraphInclusionProof(
  sourceMetagraph: String,
  hypergraphOrdinal: SnapshotOrdinal,
  metagraphStateRoot: Hash,                // From hypergraph
  fiberProof: MerklePatriciaInclusionProof, // Fiber in metagraph
  fieldProof: FiberFieldProof               // Field in fiber
)

case class CrossMetagraphMessage(
  sourceMetagraph: String,
  targetMetagraph: String,
  proof: MetagraphInclusionProof,
  payload: JsonLogicValue
)
```

### 3.2 JSON Logic VM Extensions

#### New Operations

```scala
// modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/jlvm/CrossMetagraphOps.scala

case object VerifyInclusionProofOp extends JsonLogicOp("verifyInclusionProof") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val proof = args(0).as[MerklePatriciaInclusionProof]
    val root = args(1).as[Hash]

    MerklePatriciaVerifier
      .make[F](root)
      .confirm(proof)
      .map {
        case Right(_) => JsonLogicValue.Boolean(true)
        case Left(_) => JsonLogicValue.Boolean(false)
      }
  }
}

case object ExtractProofDataOp extends JsonLogicOp("extractProofData") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val proof = args(0).as[MerklePatriciaInclusionProof]

    // Extract leaf data from proof's last commitment
    proof.witness.last match {
      case MerklePatriciaCommitment.Leaf(_, dataDigest) =>
        ctx.proofData
          .get(dataDigest)
          .map(_.pure[F])
          .getOrElse(JsonLogicValue.Null.pure[F])
      case _ => JsonLogicValue.Null.pure[F]
    }
  }
}

case object GetForeignStateRootOp extends JsonLogicOp("getForeignStateRoot") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val metagraphId = args(0).as[String]
    val ordinal = args(1).as[SnapshotOrdinal]

    ctx.txContext.foreignStateRoots
      .get(metagraphId)
      .map(hash => JsonLogicValue.String(hash.value))
      .getOrElse(JsonLogicValue.Null)
      .pure[F]
  }
}

// Cryptographic primitives
case object Keccak256Op extends JsonLogicOp("keccak256") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val input = args(0).as[String]
    Hash
      .fromBytes(Keccak256.hash(input.getBytes))
      .map(hash => JsonLogicValue.String(hash.value))
      .pure[F]
  }
}

case object Blake2bOp extends JsonLogicOp("blake2b") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val input = args(0).as[String]
    JsonBinaryHasher[F]
      .computeDigest(input.getBytes)
      .map(hash => JsonLogicValue.String(hash.value))
  }
}

case object VerifySignatureOp extends JsonLogicOp("verifySignature") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val message = args(0).as[String]
    val signature = args(1).as[String]
    val publicKey = args(2).as[String]

    ctx.securityProvider
      .verify(
        message.getBytes,
        Signature.fromHex(signature),
        PublicKey.fromHex(publicKey)
      )
      .map(JsonLogicValue.Boolean)
  }
}
```

#### Usage in Guards/Effects

```json
{
  "guard": {
    "and": [
      {
        "verifyInclusionProof": [
          {"var": "event.metagraphAProof"},
          {"getForeignStateRoot": ["metagraphA", {"var": "state.hypergraphOrdinal"}]}
        ]
      },
      {
        ">=": [
          {"extractProofData": [{"var": "event.metagraphAProof"}]},
          1000
        ]
      }
    ]
  },
  "effect": {
    "externalBalance": {"extractProofData": [{"var": "event.metagraphAProof"}]},
    "lastProofVerified": {"var": "state.currentOrdinal"}
  }
}
```

### 3.3 Cross-Metagraph Trigger Protocol

#### Proof-Carrying Triggers

```scala
// modules/models/src/main/scala/xyz/kd5ujc/schema/StateMachine.scala

case class TriggerEvent(
  targetMachineId: UUID,
  eventType: EventType,
  payloadExpr: JsonLogicExpression,

  // NEW: Optional inclusion proof
  inclusionProof: Option[MetagraphInclusionProof] = None
)
```

#### Example: Cross-Metagraph Token Bridge

```json
{
  "from": "locked",
  "to": "released",
  "eventType": "release_tokens",
  "guard": {
    "and": [
      {
        "verifyInclusionProof": [
          {"var": "event.lockProof.fiberProof"},
          {"getForeignStateRoot": [
            "sourceMetagraph",
            {"var": "event.lockProof.hypergraphOrdinal"}
          ]}
        ]
      },
      {
        "verifyInclusionProof": [
          {"var": "event.lockProof.fieldProof.proof"},
          {"var": "event.lockProof.fiberStateRoot"}
        ]
      },
      {
        "==": [
          {"extractProofData": [{"var": "event.lockProof.fieldProof.proof"}]},
          "locked"
        ]
      }
    ]
  },
  "effect": {
    "releasedAmount": {"var": "event.amount"},
    "releasedTo": {"var": "event.recipient"},
    "_rewards": [{
      "destination": {"var": "event.recipient"},
      "amount": {"var": "event.amount"},
      "reason": "cross_metagraph_release"
    }]
  }
}
```

### 3.4 Proof Generation Service

```scala
// modules/l0/src/main/scala/xyz/kd5ujc/metagraph_l0/ProofGenerationService.scala

trait ProofGenerationService[F[_]] {

  def generateFiberFieldProof(
    fiberId: UUID,
    fieldPath: String,
    atOrdinal: Option[SnapshotOrdinal] = None
  ): F[FiberFieldProof]

  def generateMetagraphInclusionProof(
    fiberId: UUID,
    fieldPath: String,
    atOrdinal: SnapshotOrdinal
  ): F[MetagraphInclusionProof]

  def generateCrossMetagraphMessage(
    targetMetagraph: String,
    proof: MetagraphInclusionProof,
    payload: JsonLogicValue
  ): F[CrossMetagraphMessage]
}

object ProofGenerationService {

  def make[F[_]: Async: StateCommitment](
    implicit context: L0NodeContext[F]
  ): ProofGenerationService[F] = new ProofGenerationService[F] {

    def generateFiberFieldProof(
      fiberId: UUID,
      fieldPath: String,
      atOrdinal: Option[SnapshotOrdinal]
    ): F[FiberFieldProof] = {
      for {
        // Get snapshot at ordinal (or latest)
        snapshot <- atOrdinal match {
          case Some(ord) => context.getCurrencySnapshot(ord)
          case None => context.getLastCurrencySnapshot
        }

        state = snapshot.get.signed.value.state.calculated
        fiber = state.fibers(fiberId)

        // Generate proof
        fieldProof <- StateCommitment[F].generateFieldProof(fiberId, fieldPath)

        // Extract field value
        fieldValue = extractField(fiber.stateData, fieldPath)

      } yield FiberFieldProof(
        fiberId = fiberId,
        fieldPath = fieldPath,
        fieldValue = fieldValue,
        proof = fieldProof,
        fiberStateRoot = fiber.stateRoot
      )
    }

    def generateMetagraphInclusionProof(
      fiberId: UUID,
      fieldPath: String,
      atOrdinal: SnapshotOrdinal
    ): F[MetagraphInclusionProof] = {
      for {
        // Get snapshot
        snapshot <- context.getCurrencySnapshot(atOrdinal)
        state = snapshot.get.signed.value.state.calculated

        // Generate fiber proof (fiber exists in metagraph)
        fiberPath = Hex(fiberId.toString.getBytes)
        metagraphTrie <- rebuildMetagraphTrie(state)
        fiberProof <- MerklePatriciaProver
          .make(metagraphTrie)
          .attestPath(fiberPath)
          .flatMap {
            case Right(p) => p.pure[F]
            case Left(e) => MonadThrow[F].raiseError(e)
          }

        // Generate field proof
        fieldProof <- generateFiberFieldProof(fiberId, fieldPath, Some(atOrdinal))

        // Get metagraph state root (from hypergraph)
        metagraphStateRoot = snapshot.get.calculatedStateHash

        // Get source metagraph ID
        metagraphId <- context.getCurrencyId.map(_.value)

      } yield MetagraphInclusionProof(
        sourceMetagraph = metagraphId,
        hypergraphOrdinal = atOrdinal,
        metagraphStateRoot = metagraphStateRoot,
        fiberProof = fiberProof,
        fieldProof = fieldProof
      )
    }

    def generateCrossMetagraphMessage(
      targetMetagraph: String,
      proof: MetagraphInclusionProof,
      payload: JsonLogicValue
    ): F[CrossMetagraphMessage] = {
      for {
        metagraphId <- context.getCurrencyId.map(_.value)
      } yield CrossMetagraphMessage(
        sourceMetagraph = metagraphId,
        targetMetagraph = targetMetagraph,
        proof = proof,
        payload = payload
      )
    }
  }
}
```

---

## Phase 4: Tessellation Integration Layer

### 4.1 Understanding Tessellation Data Application

#### Key Concepts

1. **DataUpdate**: User-submitted transactions to the metagraph
2. **DataOnChainState**: Consensus-validated state stored on L0
3. **DataCalculatedState**: Off-chain derived state (for indexing/queries)
4. **SharedArtifact**: Emissions propagated to hypergraph (rewards, token unlocks)
5. **Combine**: State transition function (updates + state → new state)
6. **Validate**: Validation function (updates + state → valid/invalid)

#### Current Flow

```
L1 (Data-L1):
  User → DataUpdate → validateUpdate() → Forward to L0

L0 (Metagraph-L0):
  Consensus Round → Collect DataUpdates → validateData() → combine() → New State

  combine(updates, state) => DataState {
    onChain: DataOnChainState,
    calculated: DataCalculatedState,
    sharedArtifacts: SortedSet[SharedArtifact]
  }

Hypergraph:
  Receive CurrencySnapshot with:
    - onChainState (hash committed)
    - calculatedStateHash
    - sharedArtifacts (TokenUnlock, etc.)
```

### 4.2 Fiber as DataOnChainState

#### Architecture

```scala
// modules/models/src/main/scala/xyz/kd5ujc/schema/DataState.scala

case class DataOnChainState(
  fibers: Map[UUID, StateMachineFiberRecord],
  oracles: Map[UUID, ScriptFiberRecord],
  lastUpdate: SnapshotOrdinal
) extends xyz.kd5ujc.currency.dataApplication.DataOnChainState

case class CalculatedState(
  // ... existing fields ...

  // State commitment
  fiberStateRoots: Map[UUID, Hash],
  metagraphStateRoot: Hash,
  totalStorageBytesUsed: Long,

  // Indexing/queries
  fibersByOwner: Map[Address, Set[UUID]],
  fibersByType: Map[String, Set[UUID]],
  eventIndex: Map[EventType, List[(UUID, EventReceipt)]]
) extends xyz.kd5ujc.currency.dataApplication.DataCalculatedState
```

#### Updates as DataUpdate

```scala
// modules/models/src/main/scala/xyz/kd5ujc/schema/Updates.scala

sealed trait Update extends xyz.kd5ujc.currency.dataApplication.DataUpdate

case class FiberEventUpdate(
  fiberId: UUID,
  event: Event
) extends Update

case class FiberCreationUpdate(
  fiberId: UUID,
  definition: StateMachineDefinition,
  initialData: JsonLogicValue,
  owners: Set[Address]
) extends Update

case class RentPaymentUpdate(
  fiberId: UUID,
  paymentAmount: Amount,
  paidUntil: SnapshotOrdinal
) extends Update

case class CrossMetagraphUpdate(
  message: CrossMetagraphMessage
) extends Update
```

### 4.3 Reward Emission from JSON Logic VM

#### Architecture: `_rewards` Special Field

When a fiber effect returns `_rewards`, the combiner converts them to `SharedArtifact` for hypergraph emission.

```scala
// Effect JSON
{
  "balance": {"-": [{"var": "state.balance"}, 100]},
  "rewardsPaid": {"+": [{"var": "state.rewardsPaid"}, 100]},

  "_rewards": [{
    "destination": {"var": "event.recipient"},
    "amount": 100,
    "reason": "task_completion"
  }]
}
```

#### Reward Processing in Combiner

```scala
// modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/lifecycle/Combiner.scala

case class RewardEmission(
  destination: Address,
  amount: Amount,
  reason: String,
  sourceFiberId: UUID,
  ordinal: SnapshotOrdinal
)

def extractRewards(
  result: StateMachineSuccess,
  fiberId: UUID,
  ordinal: SnapshotOrdinal
): List[RewardEmission] = {

  result.newStateData match {
    case JsonLogicValue.Object(fields) =>
      fields.get("_rewards") match {
        case Some(JsonLogicValue.Array(rewards)) =>
          rewards.flatMap { reward =>
            reward match {
              case JsonLogicValue.Object(r) =>
                for {
                  dest <- r.get("destination").map(_.as[Address])
                  amt <- r.get("amount").map(a => Amount(BigInt(a.as[Long])))
                  reason <- r.get("reason").map(_.as[String])
                } yield RewardEmission(dest, amt, reason, fiberId, ordinal)
              case _ => None
            }
          }.toList
        case _ => List.empty
      }
    case _ => List.empty
  }
}

def combine(
  updates: NonEmptySet[Signed[Updates]],
  state: DataState
)(implicit context: L0NodeContext[F]): F[DataState] = {

  for {
    // Process all fiber events
    results <- processAllUpdates(updates, state)

    // Extract rewards from all results
    allRewards = results.flatMap {
      case (fiberId, success: StateMachineSuccess) =>
        extractRewards(success, fiberId, state.onChain.lastUpdate)
    }

    // Convert to SharedArtifacts (TokenUnlock for rewards)
    rewardArtifacts <- allRewards.traverse { reward =>
      TokenUnlock(
        lockHash = Hash.fromBytes(reward.sourceFiberId.toString.getBytes),
        amount = reward.amount,
        currencyId = None, // Native currency
        destination = reward.destination
      ).toHashed
    }

    // Add to state
    newState = state.copy(
      sharedArtifacts = state.sharedArtifacts ++ rewardArtifacts.toSortedSet
    )

  } yield newState
}
```

### 4.4 Advanced Emission Primitives

#### Extended Shared Artifacts

```scala
// New artifact types for fiber emissions

case class StateProofArtifact(
  fiberId: UUID,
  proof: MetagraphInclusionProof,
  timestamp: Long
) extends SharedArtifact

case class CrossMetagraphMessageArtifact(
  message: CrossMetagraphMessage,
  timestamp: Long
) extends SharedArtifact

case class FiberMetricsArtifact(
  totalFibers: Long,
  totalEvents: Long,
  totalGasUsed: Long,
  totalRewardsEmitted: Amount,
  timestamp: Long
) extends SharedArtifact
```

#### Effect Special Fields

```json
{
  "effect": {
    "balance": 1000,

    "_rewards": [{
      "destination": "DAG1234...",
      "amount": 100,
      "reason": "completion"
    }],

    "_tokenUnlocks": [{
      "lockHash": "0xabc...",
      "amount": 500,
      "destination": "DAG5678..."
    }],

    "_proofs": [{
      "fiberId": "uuid",
      "fieldPath": "balance",
      "publish": true
    }],

    "_crossMetagraphMessages": [{
      "targetMetagraph": "metagraphB",
      "proof": "...",
      "payload": {"action": "mint", "amount": 100}
    }],

    "_metrics": {
      "eventsProcessed": {"+": [{"var": "state.metrics.eventsProcessed"}, 1]},
      "gasUsed": {"+": [{"var": "state.metrics.gasUsed"}, {"var": "gasUsed"}]}
    }
  }
}
```

### 4.5 Automated Data Application Hooks

#### Hook System

```scala
// modules/l0/src/main/scala/xyz/kd5ujc/metagraph_l0/ML0Service.scala

trait ML0Service[F[_]] extends BaseDataApplicationL0Service[F] {

  override def onSnapshotConsensusResult(
    snapshot: Hashed[CurrencyIncrementalSnapshot]
  ): F[Unit] = {
    for {
      // Extract state
      state = snapshot.signed.value.state.calculated

      // Hook 1: Rent enforcement
      _ <- checkRentExpiry(state, snapshot.ordinal)

      // Hook 2: Automated cross-metagraph proof generation
      _ <- generateScheduledProofs(state, snapshot.ordinal)

      // Hook 3: Metrics aggregation
      _ <- aggregateMetrics(state, snapshot.ordinal)

      // Hook 4: State garbage collection
      _ <- pruneOldEvents(state, snapshot.ordinal)

    } yield ()
  }

  override def onGlobalSnapshotPull(
    snapshot: Hashed[GlobalIncrementalSnapshot],
    context: GlobalSnapshotInfo
  ): F[Unit] = {
    for {
      // Hook: Sync foreign state roots
      _ <- syncForeignStateRoots(snapshot, context)

      // Hook: Process cross-metagraph messages
      _ <- processPendingCrossMetagraphMessages(snapshot)

    } yield ()
  }

  private def checkRentExpiry(
    state: CalculatedState,
    ordinal: SnapshotOrdinal
  ): F[Unit] = {
    val expiring = state.fibers.values.filter { fiber =>
      fiber.rentStatus.rentPaidUntil <= ordinal
    }

    expiring.toList.traverse_ { fiber =>
      // Submit check_rent event
      submitEvent(
        fiberId = fiber.cid,
        event = Event(
          eventType = EventType("check_rent"),
          payload = JsonLogicValue.Object(Map(
            "currentOrdinal" -> JsonLogicValue.Number(ordinal.value)
          ))
        )
      )
    }
  }

  private def syncForeignStateRoots(
    snapshot: Hashed[GlobalIncrementalSnapshot],
    context: GlobalSnapshotInfo
  ): F[Unit] = {
    // Extract state roots from all metagraphs in global snapshot
    val foreignRoots = snapshot.signed.value.stateChannelSnapshots.map {
      case (metagraphId, channelSnapshot) =>
        metagraphId.value -> channelSnapshot.calculatedStateHash
    }

    // Store for use in cross-metagraph verification
    storeForeignStateRoots(snapshot.ordinal, foreignRoots)
  }
}
```

---

## Phase 5: Reward Emission and Economic Primitives

### 5.1 Reward Types

#### 1. Direct Rewards (Minting)

```json
{
  "_rewards": [{
    "destination": "DAG1234...",
    "amount": 100,
    "reason": "task_completion",
    "tokenType": "native"
  }]
}
```

Converted to `TokenUnlock` artifact and emitted to hypergraph.

#### 2. Token Locks and Unlocks

```json
{
  "_tokenLocks": [{
    "source": "DAG1234...",
    "amount": 1000,
    "unlockEpoch": 12345,
    "currencyId": null
  }],

  "_tokenUnlocks": [{
    "lockHash": "0xabc...",
    "amount": 1000,
    "destination": "DAG5678..."
  }]
}
```

#### 3. Fee Collection

```json
{
  "_fees": [{
    "source": {"var": "event.signer"},
    "amount": 10,
    "destination": "treasury",
    "reason": "transaction_fee"
  }]
}
```

### 5.2 Treasury Management Fiber

```json
{
  "states": {
    "active": {"isFinal": false}
  },
  "initialState": "active",
  "transitions": [{
    "from": "active",
    "to": "active",
    "eventType": "collect_fees",
    "guard": {">=": [{"var": "event.feeAmount"}, 0]},
    "effect": {
      "totalFeesCollected": {
        "+": [
          {"var": "state.totalFeesCollected"},
          {"var": "event.feeAmount"}
        ]
      },
      "feesThisEpoch": {
        "+": [
          {"var": "state.feesThisEpoch"},
          {"var": "event.feeAmount"}
        ]
      }
    }
  }, {
    "from": "active",
    "to": "active",
    "eventType": "distribute_rewards",
    "guard": {
      "and": [
        {">=": [{"var": "state.feesThisEpoch"}, 1000]},
        {"in": [{"var": "event.signer"}, {"var": "state.treasuryManagers"}]}
      ]
    },
    "effect": {
      "feesThisEpoch": 0,
      "lastDistribution": {"var": "state.currentOrdinal"},

      "_rewards": {
        "map": [
          {"var": "state.stakeholders"},
          {
            "destination": {"var": "stakeholder.address"},
            "amount": {
              "*": [
                {"var": "state.feesThisEpoch"},
                {"var": "stakeholder.share"}
              ]
            },
            "reason": "fee_distribution"
          }
        ]
      }
    }
  }]
}
```

### 5.3 Staking Fiber

```json
{
  "states": {
    "open": {"isFinal": false},
    "closed": {"isFinal": false}
  },
  "initialState": "open",
  "transitions": [{
    "from": "open",
    "to": "open",
    "eventType": "stake",
    "guard": {
      "and": [
        {">=": [{"var": "event.amount"}, 100]},
        {"verifySignature": [
          {"var": "event.payload"},
          {"var": "event.signature"},
          {"var": "event.staker"}
        ]}
      ]
    },
    "effect": {
      "stakers": {
        "merge": [
          {"var": "state.stakers"},
          {
            "{{event.staker}}": {
              "amount": {
                "+": [
                  {"var": "state.stakers[event.staker].amount"},
                  {"var": "event.amount"}
                ]
              },
              "stakedAt": {"var": "state.currentOrdinal"}
            }
          }
        ]
      },
      "totalStaked": {
        "+": [
          {"var": "state.totalStaked"},
          {"var": "event.amount"}
        ]
      },

      "_tokenLocks": [{
        "source": {"var": "event.staker"},
        "amount": {"var": "event.amount"},
        "unlockEpoch": {
          "+": [
            {"var": "state.currentEpoch"},
            100
          ]
        }
      }]
    }
  }, {
    "from": "open",
    "to": "open",
    "eventType": "distribute_staking_rewards",
    "guard": {">=": [{"var": "state.rewardsPool"}, 0]},
    "effect": {
      "rewardsDistributed": {
        "+": [
          {"var": "state.rewardsDistributed"},
          {"var": "state.rewardsPool"}
        ]
      },
      "rewardsPool": 0,

      "_rewards": {
        "map": [
          {"var": "state.stakers"},
          {
            "destination": {"var": "staker.address"},
            "amount": {
              "*": [
                {"var": "state.rewardsPool"},
                {"/": [
                  {"var": "staker.amount"},
                  {"var": "state.totalStaked"}
                ]}
              ]
            },
            "reason": "staking_reward"
          }
        ]
      }
    }
  }]
}
```

### 5.4 Economic Primitives Operations

```scala
// New JSON Logic operations for economic primitives

case object LockTokensOp extends JsonLogicOp("lockTokens") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val source = args(0).as[Address]
    val amount = args(1).as[Amount]
    val unlockEpoch = args(2).as[Long]

    // Add to pending token locks
    ctx.pendingTokenLocks.append(
      TokenLock(source, amount, unlockEpoch, None)
    )

    JsonLogicValue.Boolean(true).pure[F]
  }
}

case object UnlockTokensOp extends JsonLogicOp("unlockTokens") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val lockHash = args(0).as[Hash]
    val destination = args(1).as[Address]
    val amount = args(2).as[Amount]

    // Add to pending token unlocks
    ctx.pendingTokenUnlocks.append(
      TokenUnlock(lockHash, amount, None, destination)
    )

    JsonLogicValue.Boolean(true).pure[F]
  }
}

case object EmitRewardOp extends JsonLogicOp("emitReward") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val destination = args(0).as[Address]
    val amount = args(1).as[Amount]
    val reason = args(2).as[String]

    // Add to pending rewards
    ctx.pendingRewards.append(
      RewardEmission(destination, amount, reason, ctx.fiberId, ctx.ordinal)
    )

    JsonLogicValue.Boolean(true).pure[F]
  }
}
```

Usage:

```json
{
  "effect": {
    "balance": {"-": [{"var": "state.balance"}, 100]},

    "lockResult": {
      "lockTokens": [
        {"var": "event.staker"},
        {"var": "event.amount"},
        {"+": [{"var": "state.currentEpoch"}, 100]}
      ]
    },

    "rewardResult": {
      "emitReward": [
        {"var": "event.recipient"},
        100,
        "task_completion"
      ]
    }
  }
}
```

---

## Phase 6: Advanced Features

### 6.1 Light Client Operations

#### Batch Proof Verification

```scala
case object VerifyBatchProofsOp extends JsonLogicOp("verifyBatchProofs") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val proofs = args(0).as[List[MerklePatriciaInclusionProof]]
    val roots = args(1).as[List[Hash]]

    proofs.zip(roots).traverse {
      case (proof, root) =>
        MerklePatriciaVerifier
          .make[F](root)
          .confirm(proof)
          .map(_.isRight)
    }.map { results =>
      JsonLogicValue.Boolean(results.forall(identity))
    }
  }
}
```

#### Sparse Merkle Tree Proof (Non-Inclusion)

For proving a key does NOT exist:

```scala
case object VerifyNonInclusionOp extends JsonLogicOp("verifyNonInclusion") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val path = args(0).as[Hex]
    val root = args(1).as[Hash]
    val proof = args(2).as[MerklePatriciaInclusionProof]

    // Verify proof terminates at a branch without the path
    MerklePatriciaVerifier
      .make[F](root)
      .confirm(proof)
      .map {
        case Left(PathNotFound(_)) => JsonLogicValue.Boolean(true)
        case _ => JsonLogicValue.Boolean(false)
      }
  }
}
```

### 6.2 Multi-Hop Proof Chaining

For A → B → C communication:

```scala
case class MultiHopProof(
  hops: List[MetagraphInclusionProof],
  finalPayload: JsonLogicValue
)

case object VerifyMultiHopProofOp extends JsonLogicOp("verifyMultiHopProof") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val multiHopProof = args(0).as[MultiHopProof]

    // Verify each hop
    multiHopProof.hops.foldLeftM(true) {
      case (acc, proof) if acc =>
        VerifyInclusionProofOp(
          List(
            JsonLogicValue.fromProof(proof.fiberProof),
            JsonLogicValue.fromHash(proof.metagraphStateRoot)
          ),
          ctx
        ).map(_.as[Boolean])
      case (false, _) => false.pure[F]
    }.map(JsonLogicValue.Boolean)
  }
}
```

### 6.3 Optimistic Verification with Fraud Proofs

#### Optimistic Bridge Pattern

```json
{
  "from": "pending",
  "to": "optimistic_confirmed",
  "eventType": "optimistic_confirm",
  "guard": {
    ">=": [
      {"-": [{"var": "state.currentOrdinal"}, {"var": "state.submittedAt"}]},
      100
    ]
  },
  "effect": {
    "status": "optimistic_confirmed",
    "challengeDeadline": {
      "+": [{"var": "state.currentOrdinal"}, 1000]
    }
  }
}
```

```json
{
  "from": "optimistic_confirmed",
  "to": "challenged",
  "eventType": "submit_fraud_proof",
  "guard": {
    "and": [
      {"<": [{"var": "state.currentOrdinal"}, {"var": "state.challengeDeadline"}]},
      {"!": [
        {"verifyInclusionProof": [
          {"var": "state.originalProof"},
          {"var": "state.stateRoot"}
        ]}
      ]}
    ]
  },
  "effect": {
    "status": "fraudulent",
    "_rewards": [{
      "destination": {"var": "event.challenger"},
      "amount": {"var": "state.bondAmount"},
      "reason": "fraud_proof_bounty"
    }]
  }
}
```

### 6.4 State Channels and Fiber-to-Fiber Communication

#### Off-Chain State Channel Fiber

```json
{
  "states": {
    "open": {},
    "updating": {},
    "disputing": {},
    "closed": {"isFinal": true}
  },
  "transitions": [{
    "from": "open",
    "to": "updating",
    "eventType": "submit_state_update",
    "guard": {
      "and": [
        {"verifySignature": [
          {"var": "event.stateUpdate"},
          {"var": "event.signature1"},
          {"var": "state.participant1"}
        ]},
        {"verifySignature": [
          {"var": "event.stateUpdate"},
          {"var": "event.signature2"},
          {"var": "state.participant2"}
        ]},
        {">": [
          {"var": "event.stateUpdate.nonce"},
          {"var": "state.currentNonce"}
        ]}
      ]
    },
    "effect": {
      "latestState": {"var": "event.stateUpdate"},
      "currentNonce": {"var": "event.stateUpdate.nonce"},
      "lastUpdate": {"var": "state.currentOrdinal"}
    }
  }, {
    "from": "open",
    "to": "closed",
    "eventType": "cooperative_close",
    "guard": {
      "and": [
        {"verifySignature": [...]},
        {"verifySignature": [...]}
      ]
    },
    "effect": {
      "finalState": {"var": "event.finalState"},
      "_rewards": {
        "map": [
          {"var": "event.finalState.balances"},
          {
            "destination": {"var": "participant.address"},
            "amount": {"var": "participant.balance"},
            "reason": "channel_settlement"
          }
        ]
      }
    }
  }]
}
```

---

## Implementation Roadmap

### Phase 1: Foundation (Weeks 1-4)

**Milestone 1.1: MPT State Commitment (Weeks 1-2)**
- [ ] Implement `StateCommitment` trait
- [ ] Add `stateRoot` to `StateMachineFiberRecord`
- [ ] Implement `computeStateRoot` function
- [ ] Add tests for state commitment
- [ ] Benchmark MPT performance

**Milestone 1.2: Metagraph Aggregation (Weeks 3-4)**
- [ ] Extend `CalculatedState` with state roots
- [ ] Implement metagraph root computation in `Combiner`
- [ ] Integrate with Tessellation `hashCalculatedState`
- [ ] Test hypergraph anchoring
- [ ] Add state root to snapshot display

### Phase 2: State Rent (Weeks 5-8)

**Milestone 2.1: Rent Data Model (Week 5)**
- [ ] Add `StateRentStatus` to fiber records
- [ ] Implement storage size calculation
- [ ] Add rent parameters to configuration
- [ ] Create rent status routes

**Milestone 2.2: Rent State Machine (Weeks 6-7)**
- [ ] Implement rent state machine definition
- [ ] Add rent transitions (check, pay, expire, reap)
- [ ] Test rent lifecycle
- [ ] Document keeper bot interface

**Milestone 2.3: Economic Integration (Week 8)**
- [ ] Implement rent payment processing
- [ ] Add reaper bounty rewards
- [ ] Test economic incentives
- [ ] Deploy keeper bot example

### Phase 3: Cross-Metagraph Proofs (Weeks 9-13)

**Milestone 3.1: Proof Generation (Weeks 9-10)**
- [ ] Implement `ProofGenerationService`
- [ ] Add `generateFiberFieldProof`
- [ ] Add `generateMetagraphInclusionProof`
- [ ] Test proof generation
- [ ] Add proof generation routes

**Milestone 3.2: JSON Logic VM Extensions (Weeks 11-12)**
- [ ] Implement `VerifyInclusionProofOp`
- [ ] Implement `ExtractProofDataOp`
- [ ] Implement `GetForeignStateRootOp`
- [ ] Add cryptographic ops (keccak256, blake2b)
- [ ] Add signature verification ops
- [ ] Test all operations

**Milestone 3.3: Proof-Carrying Triggers (Week 13)**
- [ ] Extend `TriggerEvent` with proofs
- [ ] Implement cross-metagraph message handling
- [ ] Test proof verification in guards
- [ ] Document cross-metagraph protocol

### Phase 4: Tessellation Integration (Weeks 14-17)

**Milestone 4.1: Data Application Alignment (Week 14)**
- [ ] Align `DataOnChainState` with fibers
- [ ] Align `DataCalculatedState` with calculated state
- [ ] Implement `Updates` as `DataUpdate`
- [ ] Test L0/L1 integration

**Milestone 4.2: Reward Emission (Weeks 15-16)**
- [ ] Implement `_rewards` extraction
- [ ] Convert rewards to `TokenUnlock`
- [ ] Test reward emission to hypergraph
- [ ] Add reward tracking/metrics

**Milestone 4.3: Advanced Artifacts (Week 17)**
- [ ] Implement `_tokenLocks` / `_tokenUnlocks`
- [ ] Implement `_proofs` emission
- [ ] Implement `_crossMetagraphMessages`
- [ ] Test all artifact types

### Phase 5: Economic Primitives (Weeks 18-20)

**Milestone 5.1: Treasury & Staking (Weeks 18-19)**
- [ ] Implement treasury fiber
- [ ] Implement staking fiber
- [ ] Test fee collection/distribution
- [ ] Document economic primitives

**Milestone 5.2: Economic Operations (Week 20)**
- [ ] Implement `lockTokens` / `unlockTokens` ops
- [ ] Implement `emitReward` op
- [ ] Test token lock lifecycle
- [ ] Add economic monitoring

### Phase 6: Advanced Features (Weeks 21-24)

**Milestone 6.1: Light Client (Week 21)**
- [ ] Implement batch proof verification
- [ ] Implement non-inclusion proofs
- [ ] Test light client operations

**Milestone 6.2: Multi-Hop Proofs (Week 22)**
- [ ] Implement multi-hop proof structure
- [ ] Implement multi-hop verification
- [ ] Test A → B → C communication

**Milestone 6.3: Optimistic Verification (Week 23)**
- [ ] Implement optimistic bridge pattern
- [ ] Implement fraud proof mechanism
- [ ] Test challenge/response flow

**Milestone 6.4: Polish & Documentation (Week 24)**
- [ ] Comprehensive documentation
- [ ] Example applications
- [ ] Performance benchmarks
- [ ] Security audit preparation

---

## Technical Specifications

### Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| **State Root Computation** | <100ms per fiber | For 10KB state |
| **Metagraph Root Computation** | <1s | For 1000 fibers |
| **Proof Generation** | <200ms | Single field proof |
| **Proof Verification** | <50ms | Single proof |
| **Batch Proof Verification** | <500ms | 10 proofs |
| **Rent Check** | <10ms per fiber | For rent expiry check |
| **State Size Growth** | <1MB per 1000 fibers | With rent enforcement |

### Storage Requirements

| Component | Size | Growth Rate |
|-----------|------|-------------|
| **Fiber State** | ~10KB avg | O(n) fibers |
| **State Root** | 32 bytes | O(n) fibers |
| **Metagraph Root** | 32 bytes | O(1) per snapshot |
| **Inclusion Proof** | ~1-5KB | O(log n) tree depth |
| **Event Log** | ~1KB per event | O(n) events |

### Security Considerations

1. **State Commitment**:
   - Use cryptographically secure hash (Blake2b)
   - MPT provides collision resistance
   - State roots are deterministic

2. **Cross-Metagraph Proofs**:
   - Verify hypergraph root source
   - Validate proof chain integrity
   - Check ordinal freshness

3. **State Rent**:
   - Prevent rent payment overflow
   - Secure reaping bounty calculation
   - Grace period enforcement

4. **Reward Emission**:
   - Validate reward amounts
   - Prevent double-spending
   - Check destination addresses

### Gas Cost Model

| Operation | Gas Cost | Notes |
|-----------|----------|-------|
| **computeStateRoot** | 1000 + 10 per field | MPT construction |
| **verifyInclusionProof** | 500 + 50 per depth | Proof verification |
| **extractProofData** | 100 | Data extraction |
| **keccak256** | 200 | Hash computation |
| **verifySignature** | 3000 | Ed25519 verification |
| **emitReward** | 1000 | Reward emission |
| **lockTokens** | 2000 | Token lock creation |

---

## Conclusion

This comprehensive architecture integrates:

1. **Merkle Patricia Trie state commitment** for cryptographic state provability
2. **Hypergraph anchoring** for cross-metagraph trust anchors
3. **State rent economics** for sustainable blockchain storage
4. **Tessellation integration** for native reward/token emission
5. **Cross-metagraph proofs** for trustless data sharing
6. **Economic primitives** for staking, treasury, and fee management

The result is a **novel blockchain architecture** that:
- Goes beyond EVM with multi-machine atomic coordination
- Maintains JSON-native execution (LLM-friendly)
- Enables trustless cross-metagraph communication
- Provides economic sustainability via state rent
- Integrates seamlessly with Tessellation

This positions Ottochain as a **next-generation blockchain application layer** suitable for AI agents, complex multi-party workflows, and cross-chain DeFi.