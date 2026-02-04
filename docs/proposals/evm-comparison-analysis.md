# Analysis of Remaining EVM Comparison Criticisms

**Date**: 2025-10-16 (original) | **Reviewed**: 2026-02-03
**Context**: Refining Ottochain architecture based on EVM feature comparison

---

## Criticisms Status

### 1. ✅ State Management & Persistence
**Status**: **ADDRESSED** in MPT state commitment proposal
- Fiber-level state roots via MPT
- Metagraph-level aggregation
- Hypergraph anchoring

---

## 2. Transaction Context: What Should We Add?

### Current State

The `L0NodeContext` provides rich hypergraph/metagraph context:

```scala
trait L0NodeContext[F[_]] {
  // Hypergraph state
  def getLastSynchronizedGlobalSnapshot: F[Option[GlobalIncrementalSnapshot]]
  def getLastSynchronizedGlobalSnapshotCombined: F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]]

  // Cross-metagraph data
  def getLastSynchronizedAllowSpends: F[Option[SortedMap[...]]]]
  def getLastSynchronizedTokenLocks: F[Option[SortedMap[...]]]]

  // Metagraph state
  def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  def getCurrencySnapshot(ordinal: SnapshotOrdinal): F[Option[...]]
  def getLastCurrencySnapshotCombined: F[Option[...]]

  // Identity/network
  def securityProvider: SecurityProvider[F]
  def getCurrencyId: F[CurrencyId]
  def getMetagraphL0Seedlist: Option[Set[SeedlistEntry]]
}
```

**Key Insight**: We have access to hypergraph ordinal, global snapshot, and cross-metagraph state!

### Recommendation: Extend JSON Logic Evaluation Context

Add a `TxContext` accessible in guards/effects:

```scala
// modules/models/src/main/scala/xyz/kd5ujc/schema/StateMachine.scala

case class TransactionContext(
  // Block/Snapshot metadata
  hypergraphOrdinal: SnapshotOrdinal,         // From GlobalIncrementalSnapshot
  metagraphOrdinal: SnapshotOrdinal,          // From CurrencyIncrementalSnapshot
  metagraphId: CurrencyId,                     // This metagraph's ID
  blockTimestamp: Long,                        // From snapshot timestamp
  epochProgress: Long,                         // From global snapshot

  // Transaction metadata
  caller: Address,                             // First proof signer
  allSigners: Set[Address],                    // All proofs
  eventType: EventType,                        // Current event

  // Cross-metagraph state roots (for proof verification)
  foreignStateRoots: Map[CurrencyId, Hash],   // Other metagraphs' state roots

  // Network info
  activePeers: Set[PeerId],                    // From seedlist

  // Fee/gas tracking
  gasUsed: Long,
  gasLimit: Long
)

case class ExecutionContext(
  depth: Int = 0,
  maxDepth: Int = 10,
  gasUsed: Int = 0,
  maxGas: Int = 1000,
  processedEvents: Set[(UUID, EventType)] = Set.empty,

  // NEW: Transaction context
  txContext: TransactionContext
)
```

#### Usage in JSON Logic

```json
{
  "guard": {
    "and": [
      {
        ">=": [
          {"var": "txContext.hypergraphOrdinal"},
          {"var": "state.requiredOrdinal"}
        ]
      },
      {
        "in": [
          {"var": "txContext.caller"},
          {"var": "state.authorizedAddresses"}
        ]
      },
      {
        "<": [
          {"var": "txContext.epochProgress"},
          {"var": "state.unlockEpoch"}
        ]
      }
    ]
  }
}
```

#### Implementation

```scala
// In DeterministicEventProcessor.processEvent()

def processEvent(
  fiber: StateMachineFiberRecord,
  event: Event,
  proofs: List[Proof],
  ordinal: SnapshotOrdinal,
  state: CalculatedState,
  executionContext: ExecutionContext,
  gasLimit: Long
)(implicit ctx: L0NodeContext[F]): F[ProcessingResult] = {

  for {
    // Build transaction context
    globalSnapshot <- ctx.getLastSynchronizedGlobalSnapshot
    metagraphId <- ctx.getCurrencyId
    seedlist = ctx.getMetagraphL0Seedlist

    // Extract signers from proofs
    caller <- proofs.headOption
      .map(_.id.toAddress)
      .fold(MonadThrow[F].raiseError[Address](...))(_.pure[F])
    allSigners <- proofs.traverse(_.id.toAddress).map(_.toSet)

    // Get foreign state roots from global snapshot
    foreignStateRoots = globalSnapshot
      .map(_.stateChannelSnapshots.map {
        case (cid, snapshot) => cid -> snapshot.calculatedStateHash
      })
      .getOrElse(Map.empty)

    txContext = TransactionContext(
      hypergraphOrdinal = globalSnapshot.map(_.ordinal).getOrElse(SnapshotOrdinal.MinValue),
      metagraphOrdinal = ordinal,
      metagraphId = metagraphId,
      blockTimestamp = System.currentTimeMillis(), // Or from snapshot
      epochProgress = globalSnapshot.map(_.epochProgress).getOrElse(0L),
      caller = caller,
      allSigners = allSigners,
      eventType = event.eventType,
      foreignStateRoots = foreignStateRoots,
      activePeers = seedlist.map(_.map(_.id)).getOrElse(Set.empty),
      gasUsed = executionContext.gasUsed,
      gasLimit = gasLimit
    )

    enrichedContext = executionContext.copy(txContext = txContext)

    // Continue with event processing using enrichedContext
    ...
  } yield result
}
```

### Recommendation: Add Special JSON Logic Ops

```scala
case object GetHypergraphOrdinalOp extends JsonLogicOp("hypergraphOrdinal") {
  override def apply(args: List[JsonLogicValue], ctx: EvaluationContext): F[JsonLogicValue] =
    JsonLogicValue.Number(ctx.txContext.hypergraphOrdinal.value).pure[F]
}

case object GetCallerOp extends JsonLogicOp("caller") {
  override def apply(args: List[JsonLogicValue], ctx: EvaluationContext): F[JsonLogicValue] =
    JsonLogicValue.String(ctx.txContext.caller.value).pure[F]
}

case object GetEpochProgressOp extends JsonLogicOp("epochProgress") {
  override def apply(args: List[JsonLogicValue], ctx: EvaluationContext): F[JsonLogicValue] =
    JsonLogicValue.Number(ctx.txContext.epochProgress).pure[F]
}
```

### Priority: **HIGH** - Enables time-locked logic and cross-metagraph awareness

---

## 3. Cryptographic Primitives: Purposeful Gap

### Current Decision: **CORRECT**

You're right to keep JSON Logic VM focused on business logic. Cryptographic ops should be added **selectively** and **only when needed**.

### Recommendation: Minimal Crypto Extension

Add **only** the most critical operations:

```scala
// High priority (for proof verification)
case object Blake2bHashOp extends JsonLogicOp("blake2b")
case object VerifyInclusionProofOp extends JsonLogicOp("verifyInclusionProof")

// Medium priority (for cross-chain)
case object VerifySignatureOp extends JsonLogicOp("verifySignature")
case object RecoverAddressOp extends JsonLogicOp("recoverAddress")

// Low priority (defer)
case object Keccak256Op extends JsonLogicOp("keccak256")  // Only if Ethereum compatibility needed
case object Sha256Op extends JsonLogicOp("sha256")        // Only if Bitcoin compatibility needed
```

### Rationale

1. **Blake2b**: Required for state commitment/proof verification
2. **VerifyInclusionProof**: Required for cross-metagraph communication
3. **VerifySignature**: Useful for multi-sig and delegation patterns
4. **Others**: Add on-demand when use cases emerge

### Priority: **MEDIUM** - Defer until MPT proofs are implemented

---

## 4. Inter-Contract Calls: Should Triggers Return Data?

### Current Architecture Analysis

**What we have**:
- `_triggers`: Fire-and-forget events to other machines
- Script oracles: Read-only queries with return values
- Cross-machine dependencies: Guards can read other machines' state

**Question**: Should triggers support return values?

### Recommendation: **NO** - But Improve Read Access

#### Why Triggers Should Stay Fire-and-Forget

1. **Atomicity**: Triggers are part of atomic transactions. Return values complicate rollback.
2. **Composability**: Sequential trigger chains are easier to reason about than nested calls.
3. **Gas Metering**: Synchronous calls require complex gas accounting.
4. **Reentrancy**: Return values invite reentrancy attacks.

#### Solution: Enhance Oracle Read Access + Async Patterns

##### Pattern 1: Oracle-Based Reads (Already Works!)

```json
{
  "guard": {
    ">=": [
      {"oracleCall": [
        "price-oracle-uuid",
        "getCurrentPrice",
        ["DAG/USD"]
      ]},
      1.50
    ]
  }
}
```

**Status**: ✅ Already implemented via `OracleCallOp`

##### Pattern 2: Request-Response Pattern

Instead of synchronous calls, use **two-phase commit**:

```json
// Step 1: Machine A triggers request to Machine B
{
  "from": "waiting",
  "to": "requested",
  "eventType": "request_data",
  "effect": {
    "requestId": {"var": "event.requestId"},
    "_triggers": [{
      "targetMachineId": "machine-b-uuid",
      "eventType": "process_request",
      "payloadExpr": {
        "requestId": {"var": "event.requestId"},
        "requesterId": {"var": "state.machineId"}
      }
    }]
  }
}

// Step 2: Machine B processes and responds
{
  "from": "idle",
  "to": "idle",
  "eventType": "process_request",
  "effect": {
    "result": {"compute": [{"var": "state.data"}]},
    "_triggers": [{
      "targetMachineId": {"var": "event.requesterId"},
      "eventType": "receive_response",
      "payloadExpr": {
        "requestId": {"var": "event.requestId"},
        "result": {"var": "result"}
      }
    }]
  }
}

// Step 3: Machine A receives response
{
  "from": "requested",
  "to": "completed",
  "eventType": "receive_response",
  "guard": {
    "==": [
      {"var": "event.requestId"},
      {"var": "state.requestId"}
    ]
  },
  "effect": {
    "receivedData": {"var": "event.result"}
  }
}
```

**Advantages**:
- Async by design (matches blockchain model)
- No reentrancy risk
- Clear state transitions
- Supports timeouts/cancellation

##### Pattern 3: Shared State via Oracles

For frequently accessed data, use **stateful oracles** as shared stores:

```json
// Multiple machines read from shared oracle
{
  "guard": {
    ">=": [
      {"oracleCall": [
        "shared-registry-uuid",
        "getUserBalance",
        [{"var": "event.userId"}]
      ]},
      100
    ]
  }
}
```

#### New Feature: Direct State Reads (Restricted)

Add **read-only** access to other machines' state in guards:

```scala
case object ReadMachineStateOp extends JsonLogicOp("readMachineState") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val machineId = args(0).as[UUID]
    val fieldPath = args(1).as[String]

    // Only allowed in guards (not effects) to prevent write conflicts
    if (ctx.phase != Phase.Guard) {
      MonadThrow[F].raiseError(new RuntimeException("readMachineState only allowed in guards"))
    } else {
      ctx.allMachines
        .get(machineId)
        .map(machine => extractField(machine.stateData, fieldPath))
        .getOrElse(JsonLogicValue.Null)
        .pure[F]
    }
  }
}
```

Usage:

```json
{
  "guard": {
    "and": [
      {"==": [
        {"readMachineState": ["other-uuid", "status"]},
        "approved"
      ]},
      {">=": [
        {"readMachineState": ["other-uuid", "balance"]},
        {"var": "event.requiredBalance"}
      ]}
    ]
  }
}
```

**Constraints**:
- ✅ Read-only (no side effects)
- ✅ Only in guards (not effects)
- ✅ Must declare dependency in transition
- ✅ Gas cost: 100 per read

### Priority: **MEDIUM** - Request-response pattern sufficient for now. Add `readMachineState` in Phase 2.

---

## 5. Events & Logging: Do We Need Standard Event Indexing?

### Current State

We have `StructuredOutput` in effects:

```json
{
  "_outputs": [{
    "type": "notification",
    "message": "Fiber expired"
  }]
}
```

### Recommendation: **YES** - Add Event Index to CalculatedState

Events are critical for:
1. **Off-chain indexers** (block explorers, analytics)
2. **Client subscriptions** (WebSocket feeds)
3. **Audit trails** (compliance, debugging)

#### Proposed Event Model

```scala
// modules/models/src/main/scala/xyz/kd5ujc/schema/Events.scala

sealed trait FiberEvent {
  def fiberId: UUID
  def ordinal: SnapshotOrdinal
  def eventType: EventType
  def timestamp: Long
}

case class StateTransitionEvent(
  fiberId: UUID,
  ordinal: SnapshotOrdinal,
  eventType: EventType,
  timestamp: Long,
  fromState: StateId,
  toState: StateId,
  triggeringEvent: Event,
  caller: Address
) extends FiberEvent

case class StructuredOutputEvent(
  fiberId: UUID,
  ordinal: SnapshotOrdinal,
  eventType: EventType,
  timestamp: Long,
  outputType: String,
  outputData: JsonLogicValue
) extends FiberEvent

case class TriggerEmittedEvent(
  fiberId: UUID,
  ordinal: SnapshotOrdinal,
  eventType: EventType,
  timestamp: Long,
  targetFiberId: UUID,
  targetEventType: EventType
) extends FiberEvent

case class RewardEmittedEvent(
  fiberId: UUID,
  ordinal: SnapshotOrdinal,
  eventType: EventType,
  timestamp: Long,
  destination: Address,
  amount: Amount,
  reason: String
) extends FiberEvent
```

#### Index in CalculatedState

```scala
case class CalculatedState(
  stateMachines: Map[UUID, StateMachineFiberRecord],
  scripts: Map[UUID, ScriptFiberRecord],

  // NEW: Event indexing
  eventLog: List[FiberEvent],              // Append-only log (last N events)
  eventsByFiber: Map[UUID, List[FiberEvent]],    // Index by fiber
  eventsByType: Map[EventType, List[FiberEvent]], // Index by event type
  eventsByOrdinal: SortedMap[SnapshotOrdinal, List[FiberEvent]], // Index by ordinal

  // Existing fields
  fiberStateRoots: Map[UUID, Hash],
  metagraphStateRoot: Hash,
  ...
)
```

#### Combiner Integration

```scala
// In processFiberEvent()

result match {
  case StateMachineSuccess(newStateId, newStateData, triggerEvents, spawnMachines, _, outputs) =>

    // Generate state transition event
    val stateEvent = StateTransitionEvent(
      fiberId = fiberId,
      ordinal = currentOrdinal,
      eventType = event.eventType,
      timestamp = System.currentTimeMillis(),
      fromState = fiberRecord.currentState,
      toState = newStateId,
      triggeringEvent = event,
      caller = caller
    )

    // Generate structured output events
    val outputEvents = outputs.map { output =>
      StructuredOutputEvent(
        fiberId = fiberId,
        ordinal = currentOrdinal,
        eventType = event.eventType,
        timestamp = System.currentTimeMillis(),
        outputType = output.outputType,
        outputData = output.data
      )
    }

    // Generate trigger emitted events
    val triggerEmittedEvents = triggerEvents.map { trigger =>
      TriggerEmittedEvent(
        fiberId = fiberId,
        ordinal = currentOrdinal,
        eventType = event.eventType,
        timestamp = System.currentTimeMillis(),
        targetFiberId = trigger.targetMachineId,
        targetEventType = trigger.eventType
      )
    }

    // Generate reward events
    val rewardEvents = extractRewards(result).map { reward =>
      RewardEmittedEvent(
        fiberId = fiberId,
        ordinal = currentOrdinal,
        eventType = event.eventType,
        timestamp = System.currentTimeMillis(),
        destination = reward.destination,
        amount = reward.amount,
        reason = reward.reason
      )
    }

    val allEvents = List(stateEvent) ++ outputEvents ++ triggerEmittedEvents ++ rewardEvents

    // Update calculated state with events
    val updatedCalculated = newCalculated.copy(
      eventLog = (allEvents ++ newCalculated.eventLog).take(10000), // Keep last 10k events
      eventsByFiber = allEvents.groupBy(_.fiberId).foldLeft(newCalculated.eventsByFiber) {
        case (acc, (fid, events)) => acc.updated(fid, events ++ acc.getOrElse(fid, List.empty))
      },
      eventsByType = allEvents.groupBy(_.eventType).foldLeft(newCalculated.eventsByType) {
        case (acc, (et, events)) => acc.updated(et, events ++ acc.getOrElse(et, List.empty))
      },
      eventsByOrdinal = newCalculated.eventsByOrdinal.updated(
        currentOrdinal,
        allEvents ++ newCalculated.eventsByOrdinal.getOrElse(currentOrdinal, List.empty)
      )
    )

    ...
}
```

#### Query API

```scala
// modules/l0/src/main/scala/xyz/kd5ujc/metagraph_l0/ML0CustomRoutes.scala

object EventRoutes {

  def routes[F[_]: Async](implicit ctx: L0NodeContext[F]): HttpRoutes[F] =
    HttpRoutes.of[F] {

      // Get all events for a fiber
      case GET -> Root / "events" / "fiber" / UUIDVar(fiberId) :? PageQueryParamMatcher(page) =>
        for {
          state <- ctx.getLastCurrencySnapshotCombined
          events = state.calculated.eventsByFiber.getOrElse(fiberId, List.empty)
          paged = events.drop(page * 100).take(100)
          response <- Ok(paged.asJson)
        } yield response

      // Get events by type
      case GET -> Root / "events" / "type" / eventType =>
        for {
          state <- ctx.getLastCurrencySnapshotCombined
          events = state.calculated.eventsByType.getOrElse(EventType(eventType), List.empty)
          response <- Ok(events.asJson)
        } yield response

      // Get events at ordinal
      case GET -> Root / "events" / "ordinal" / LongVar(ordinal) =>
        for {
          state <- ctx.getLastCurrencySnapshotCombined
          events = state.calculated.eventsByOrdinal.getOrElse(SnapshotOrdinal(ordinal), List.empty)
          response <- Ok(events.asJson)
        } yield response

      // Stream events (WebSocket)
      case GET -> Root / "events" / "stream" =>
        // WebSocket connection that streams new events
        ???
    }
}
```

### Priority: **HIGH** - Essential for production use (indexers, monitoring, debugging)

---

## 6. Access Control: Extend Beyond Basic Ownership?

### Current State

- State machines: `owners: Set[Address]`
- Oracles: Full RBAC with policies

### Analysis: Should State Machines Have RBAC?

**Oracles have**:
```scala
case class ScriptFiberRecord(
  accessControl: AccessControl
)

sealed trait AccessControl
case object OpenAccess extends AccessControl
case class RestrictedAccess(policy: AccessPolicy) extends AccessControl

sealed trait AccessPolicy
case class AllowList(addresses: Set[Address]) extends AccessPolicy
case class BlockList(addresses: Set[Address]) extends AccessPolicy
case class ThresholdPolicy(required: Int, signers: Set[Address]) extends AccessPolicy
```

**State machines have**:
```scala
case class StateMachineFiberRecord(
  owners: Set[Address]  // Simple multi-sig
)
```

### Recommendation: **YES** - Add Flexible Access Control

#### Rationale

State machines need:
1. **Role-based access** (admin, operator, user)
2. **Event-level permissions** (who can trigger which events)
3. **Time-based access** (temporary permissions)
4. **Delegation** (one address delegates to another)

#### Proposed Extension

```scala
// modules/models/src/main/scala/xyz/kd5ujc/schema/AccessControl.scala

sealed trait FiberAccessControl

case object PublicAccess extends FiberAccessControl
case class OwnerOnly(owners: Set[Address]) extends FiberAccessControl  // Current behavior
case class RoleBasedAccess(roles: Map[Role, Set[Address]]) extends FiberAccessControl
case class EventBasedAccess(permissions: Map[EventType, AccessPolicy]) extends FiberAccessControl
case class DelegatedAccess(delegations: Map[Address, Delegation]) extends FiberAccessControl

case class Role(name: String) extends AnyVal

case class Delegation(
  from: Address,
  to: Address,
  allowedEvents: Set[EventType],
  expiresAt: SnapshotOrdinal
)

// Update fiber record
case class StateMachineFiberRecord(
  cid: UUID,
  definition: StateMachineDefinition,
  currentState: StateId,
  stateData: JsonLogicValue,

  // NEW: Flexible access control
  accessControl: FiberAccessControl,

  // DEPRECATED: owners (for backward compatibility)
  owners: Set[Address],

  ...
)
```

#### Access Control in Guards

```json
{
  "guard": {
    "hasRole": ["admin", {"var": "txContext.caller"}]
  }
}

{
  "guard": {
    "or": [
      {"isOwner": [{"var": "txContext.caller"}]},
      {"hasDelegation": [
        {"var": "txContext.caller"},
        {"var": "event.eventType"}
      ]}
    ]
  }
}

{
  "guard": {
    "and": [
      {"hasPermission": [
        {"var": "txContext.caller"},
        {"var": "event.eventType"}
      ]},
      {"<": [
        {"var": "txContext.metagraphOrdinal"},
        {"var": "state.accessExpiry"}
      ]}
    ]
  }
}
```

#### JSON Logic Operations

```scala
case object HasRoleOp extends JsonLogicOp("hasRole") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val roleName = args(0).as[String]
    val address = args(1).as[Address]

    ctx.fiber.accessControl match {
      case RoleBasedAccess(roles) =>
        val hasRole = roles.get(Role(roleName)).exists(_.contains(address))
        JsonLogicValue.Boolean(hasRole).pure[F]
      case _ =>
        JsonLogicValue.Boolean(false).pure[F]
    }
  }
}

case object IsOwnerOp extends JsonLogicOp("isOwner") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val address = args(0).as[Address]
    val isOwner = ctx.fiber.owners.contains(address)
    JsonLogicValue.Boolean(isOwner).pure[F]
  }
}

case object HasPermissionOp extends JsonLogicOp("hasPermission") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val address = args(0).as[Address]
    val eventType = args(1).as[EventType]

    ctx.fiber.accessControl match {
      case EventBasedAccess(permissions) =>
        permissions.get(eventType) match {
          case Some(AllowList(addrs)) =>
            JsonLogicValue.Boolean(addrs.contains(address)).pure[F]
          case Some(BlockList(addrs)) =>
            JsonLogicValue.Boolean(!addrs.contains(address)).pure[F]
          case Some(ThresholdPolicy(required, signers)) =>
            // Check if address is in signers and enough signatures
            val hasSignature = signers.contains(address)
            val signatureCount = ctx.txContext.allSigners.intersect(signers).size
            JsonLogicValue.Boolean(hasSignature && signatureCount >= required).pure[F]
          case None =>
            JsonLogicValue.Boolean(true).pure[F]  // No policy = allow
        }
      case _ =>
        JsonLogicValue.Boolean(false).pure[F]
    }
  }
}

case object HasDelegationOp extends JsonLogicOp("hasDelegation") {
  override def apply(
    args: List[JsonLogicValue],
    ctx: EvaluationContext
  ): F[JsonLogicValue] = {
    val address = args(0).as[Address]
    val eventType = args(1).as[EventType]

    ctx.fiber.accessControl match {
      case DelegatedAccess(delegations) =>
        val hasDelegation = delegations.values.exists { delegation =>
          delegation.to == address &&
          delegation.allowedEvents.contains(eventType) &&
          delegation.expiresAt > ctx.txContext.metagraphOrdinal
        }
        JsonLogicValue.Boolean(hasDelegation).pure[F]
      case _ =>
        JsonLogicValue.Boolean(false).pure[F]
    }
  }
}
```

#### Example: DAO Governance Fiber

```scala
val governanceFiber = StateMachineFiberRecord(
  cid = UUID.randomUUID(),
  definition = governanceMachineDefinition,
  currentState = StateId("active"),
  stateData = JsonLogicValue.Object(...),

  accessControl = RoleBasedAccess(
    roles = Map(
      Role("admin") -> Set(daoMultisigAddress),
      Role("proposer") -> Set(member1, member2, member3),
      Role("voter") -> Set(member1, member2, member3, member4, member5)
    )
  ),

  owners = Set(daoMultisigAddress),  // Backward compat
  ...
)
```

Guards:

```json
{
  "from": "active",
  "to": "proposalPending",
  "eventType": "create_proposal",
  "guard": {
    "hasRole": ["proposer", {"var": "txContext.caller"}]
  }
}

{
  "from": "proposalPending",
  "to": "proposalActive",
  "eventType": "vote",
  "guard": {
    "hasRole": ["voter", {"var": "txContext.caller"}]
  }
}

{
  "from": "proposalActive",
  "to": "proposalExecuted",
  "eventType": "execute",
  "guard": {
    "and": [
      {"hasRole": ["admin", {"var": "txContext.caller"}]},
      {">=": [
        {"var": "state.yesVotes"},
        {"*": [{"var": "state.totalVoters"}, 0.66]}
      ]}
    ]
  }
}
```

### Priority: **HIGH** - Required for production DAOs, multi-party workflows

---

## Summary & Priority Ranking

| Criticism | Status | Priority | Rationale |
|-----------|--------|----------|-----------|
| 1. State Management | ✅ Addressed | N/A | MPT proposal covers this |
| 2. Transaction Context | 🟡 Extend | **HIGH** | Essential for time-locks, cross-chain |
| 3. Crypto Primitives | 🟢 Defer | **MEDIUM** | Add only when MPT proofs ready |
| 4. Inter-Contract Calls | 🟢 No Change | **MEDIUM** | Request-response pattern sufficient |
| 5. Event Indexing | 🟡 Add | **HIGH** | Critical for prod (indexers, monitoring) |
| 6. Access Control | 🟡 Extend | **HIGH** | Required for DAOs, enterprise |

---

## Implementation Order

### Phase 1 (Immediate - Weeks 1-4)
1. **Transaction Context** (2 weeks)
   - Add `TransactionContext` to `ExecutionContext`
   - Populate from `L0NodeContext` in `DeterministicEventProcessor`
   - Add JSON Logic ops: `hypergraphOrdinal`, `caller`, `epochProgress`
   - Test in guards/effects

2. **Event Indexing** (2 weeks)
   - Define event types (`StateTransitionEvent`, etc.)
   - Add event indices to `CalculatedState`
   - Emit events in `Combiner`
   - Add query routes

### Phase 2 (Weeks 5-8)
3. **Access Control** (3 weeks)
   - Define `FiberAccessControl` types
   - Add to `StateMachineFiberRecord`
   - Implement JSON Logic ops: `hasRole`, `hasPermission`, `hasDelegation`
   - Migration path for existing `owners`
   - Test with DAO example

4. **Request-Response Pattern** (1 week)
   - Document pattern
   - Create example machines
   - Add to test suite

### Phase 3 (Weeks 9-12)
5. **Crypto Primitives** (2 weeks)
   - Implement `blake2b`, `verifyInclusionProof`
   - Implement `verifySignature`, `recoverAddress`
   - Benchmark performance
   - Security audit

6. **Direct State Reads** (2 weeks)
   - Implement `readMachineState` op
   - Add dependency tracking
   - Gas accounting
   - Test cross-machine queries

---

## Conclusion

**Criticisms 2, 5, 6 should be addressed immediately** - they're foundational for production use.

**Criticisms 3, 4 can wait** - existing patterns are sufficient, add incrementally.

**Criticism 1 is solved** by the MPT state commitment proposal.

The prioritized implementation path balances **immediate utility** (transaction context, events, access control) with **architectural completeness** (crypto, inter-contract patterns).