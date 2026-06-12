# Multi-Party Fiber Signing — TDD Specification

**Card**: [A1] Spec: Multi-Party Fiber Signing (Metagraph)  
**Epic**: Multi-Party Signing + Client-Side Signing Refactor (`699ce1fa`)  
**Repo**: `scasplte2/ottochain`  
**Branch**: `feat/multi-party-signing`  
**Status**: Awaiting James's design approval before [A2] tests are written  
**Last Updated**: 2026-02-23

---

## Problem Statement

Tessellation's DL1 validates that a signed data update carries at least one valid signature — but it does **not** enforce who signed it. Ownership validation is entirely our metagraph's responsibility.

The `FiberRules.L0.updateSignedByOwners` rule currently rejects any `TransitionStateMachine` not signed by one of the fiber's `owners`. The `owners` set is derived solely from the **signers of `CreateStateMachine`** — typically the fiber creator alone.

This breaks multi-party state machines:

```
Alice creates a contract fiber → Alice is the only owner
Bob accepts the contract → Bob signs TransitionStateMachine("accept", ...)
Metagraph rejects: NotSignedByOwner ❌
```

Real-world use cases broken today:
- **Contracts**: Creator proposes, counterparty accepts/rejects
- **Markets**: Seller creates order, buyer fills it
- **DAOs**: Proposal creator, voters, and treasury signatories are different parties
- **Escrow**: Initiator and beneficiary both need signing rights

---

## Design Questions — Answered

### Q1: Where does DL1 signer validation happen?

**Answer**: It does **not** happen in Tessellation's DL1 at the application level. Tessellation checks cryptographic signature validity (proof format, hash integrity), but delegates *authorization* entirely to the metagraph's `validateData` / `validateUpdate` hooks.

The `updateSignedByOwners` check is 100% our code in:
```
FiberRules.L0.updateSignedByOwners  →  FiberValidator.L0Validator.processEvent
```

We have full control to extend this rule without touching Tessellation.

### Q2: Should authorized signers live in StateMachineDefinition or CreateStateMachine?

**Answer**: `CreateStateMachine` (not `StateMachineDefinition`).

Rationale:
- `StateMachineDefinition` is the **template** — the same definition can be reused for many contract instances with different parties.
- `CreateStateMachine` is the **instance creation message** — it carries instance-specific configuration (fiber ID, initial data, and now participants).
- A "two-party escrow" definition is generic; the specific Alice/Bob addresses belong to this *instance*.

### Q3: Can we use guard-level checking for signer authorization?

**Answer**: No — not cleanly for Phase 1. The `updateSignedByOwners` check runs *before* guard evaluation in `FiberValidator.L0Validator.processEvent`:

```scala
def processEvent(update: TransitionStateMachine): F[ValidationResult] =
  for {
    fiberActive   <- FiberRules.L0.fiberIsActive(...)     // first
    signedByOwner <- FiberRules.L0.updateSignedByOwners(...)  // second — before guards
    transitionOk  <- FiberRules.L0.transitionExists(...)
  } yield ...
```

Guard expressions evaluate JSON Logic against fiber `stateData` and the event payload, but signer addresses need to be checked at the validation layer — before the expensive guard execution. The validation layer doesn't run JLVM.

For **Phase 2**, we could expose the transaction signer address as a JLVM context variable (e.g., `$signer`) so guards can do fine-grained per-role authorization. That's out of scope here.

### Q4: What's the minimal change to allow counterparty transitions?

**Answer**: 3 changes across 3 files:

1. `Updates.CreateStateMachine` — add `participants: Option[Set[Address]]`
2. `Records.StateMachineFiberRecord` — add `authorizedSigners: Set[Address]`
3. `FiberRules.L0` — add `updateSignedByOwnerOrParticipant` rule
4. `FiberValidator.L0Validator.processEvent` — use new rule for transitions
5. `FiberCombiner.createStateMachineFiber` — populate `authorizedSigners`

`archiveFiber` intentionally keeps the strict `updateSignedByOwners` check — only the **owner** can permanently archive a fiber.

### Q5: How does this interact with the delegation system (PR #90)?

**Answer**: These are complementary, not redundant:
- **Delegation** (PR #90): Bob gets a `DelegationCredential` that lets him sign *on behalf of* Alice — Bob acts as Alice's relayer.
- **Multi-party signing**: Bob signs *as himself* as a legitimate authorized participant in the fiber.

The `authorizedSigners` approach is simpler and more appropriate for collaborative state machines. Delegation is better suited for "agent acts on behalf of user" scenarios.

---

## Chosen Design: `participants` Field in CreateStateMachine

### Core Principle

At fiber creation, the creator declares which additional addresses may sign transitions. These are stored as `authorizedSigners` on the fiber record. The ownership check for `TransitionStateMachine` is relaxed to accept either an owner or an authorized signer.

### Backward Compatibility

- `participants` is `Option[Set[Address]]` — defaults to `None` → `Set.empty`
- When `participants` is empty, behavior is identical to current (only owners can sign transitions)
- All existing fibers continue to work without modification
- No migration required

---

## Data Model Changes

### 1. `Updates.CreateStateMachine` — New Field

**File**: `modules/models/src/main/scala/xyz/kd5ujc/schema/Updates.scala`

```scala
// BEFORE
@derive(customizableDecoder, customizableEncoder)
final case class CreateStateMachine(
  fiberId:       UUID,
  definition:    StateMachineDefinition,
  initialData:   JsonLogicValue,
  parentFiberId: Option[UUID] = None
) extends StateMachineFiberOp
    with OttochainMessage

// AFTER
@derive(customizableDecoder, customizableEncoder)
final case class CreateStateMachine(
  fiberId:        UUID,
  definition:     StateMachineDefinition,
  initialData:    JsonLogicValue,
  parentFiberId:  Option[UUID] = None,
  participants:   Option[Set[Address]] = None  // NEW: additional authorized signers
) extends StateMachineFiberOp
    with OttochainMessage
```

**Note**: `Address` is `io.constellationnetwork.schema.address.Address` — already used throughout the codebase.

### 2. `Records.StateMachineFiberRecord` — New Field

**File**: `modules/models/src/main/scala/xyz/kd5ujc/schema/Records.scala`

```scala
// BEFORE
@derive(customizableEncoder, customizableDecoder)
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
  status:                FiberStatus,
  lastReceipt:           Option[EventReceipt] = None,
  parentFiberId:         Option[UUID] = None,
  childFiberIds:         Set[UUID] = Set.empty
) extends FiberRecord

// AFTER
@derive(customizableEncoder, customizableDecoder)
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
  status:                FiberStatus,
  lastReceipt:           Option[EventReceipt] = None,
  parentFiberId:         Option[UUID] = None,
  childFiberIds:         Set[UUID] = Set.empty,
  authorizedSigners:     Set[Address] = Set.empty  // NEW: additional transition signers
) extends FiberRecord
```

**Key distinction**:
- `owners`: Can sign **any** operation (transition + archive). Derived from `CreateStateMachine` proofs.
- `authorizedSigners`: Can sign **transitions only** (not archive). Declared in `CreateStateMachine.participants`.

### 3. Proto Schema Update

**File**: `modules/proto/src/main/protobuf/records.proto`

```protobuf
message StateMachineFiberRecord {
  // ... existing fields ...
  repeated string authorized_signers = 15;  // NEW — wallet address strings
}
```

---

## New Validation Rule

### `FiberRules.L0.updateSignedByOwnerOrParticipant`

**File**: `modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/lifecycle/validate/rules/FiberRules.scala`

```scala
object L0 {

  // ... existing rules unchanged ...

  /**
   * Validates that the update is signed by an owner OR an authorized participant.
   *
   * Used for TransitionStateMachine operations where counterparties are allowed.
   * Owners: derived from CreateStateMachine signers (can do anything)
   * AuthorizedSigners: declared in CreateStateMachine.participants (transitions only)
   *
   * Note: ArchiveStateMachine still uses updateSignedByOwners (strict — owners only).
   */
  def updateSignedByOwnerOrParticipant[F[_]: Async: SecurityProvider](
    cid:    UUID,
    proofs: NonEmptySet[SignatureProof],
    state:  CalculatedState
  ): F[ValidationResult] = (for {
    record          <- state.getFiberRecord(cid)
    signerAddresses <- EitherT.liftF(proofs.toList.traverse(_.id.toAddress))
    signerSet = signerAddresses.toSet
    
    // Authorized = owners ∪ authorizedSigners
    authorizedSet = record match {
      case sm: Records.StateMachineFiberRecord => sm.owners ++ sm.authorizedSigners
      case other                               => other.owners
    }
    
    result <- EitherT.cond[F](
      signerSet.intersect(authorizedSet).nonEmpty,
      (),
      Errors.NotSignedByAuthorizedParty: DataApplicationValidationError
    )
  } yield result).fold(
    _.invalidNec[Unit],
    _.validNec[DataApplicationValidationError]
  )
}

object Errors {
  // ... existing errors unchanged ...

  /** New error for multi-party signing rejection */
  final case object NotSignedByAuthorizedParty extends DataApplicationValidationError {
    override val message: String =
      "Update not signed by any fiber owner or authorized participant"
  }
}
```

**Note**: Keep the existing `Errors.NotSignedByOwner` for backward compatibility (still used by archive validation and the error is still useful for the strict owner-only case). Add `NotSignedByAuthorizedParty` as a distinct error for the relaxed check.

---

## Validator Changes

### `FiberValidator.L0Validator.processEvent`

**File**: `modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/lifecycle/validate/FiberValidator.scala`

```scala
/** Validates a ProcessFiberEvent update (L0 specific checks) */
def processEvent(update: TransitionStateMachine): F[ValidationResult] =
  for {
    fiberActive   <- FiberRules.L0.fiberIsActive(update.fiberId, state.calculated)
    // CHANGED: was updateSignedByOwners, now allows authorized participants too
    signedOk      <- FiberRules.L0.updateSignedByOwnerOrParticipant(update.fiberId, proofs, state.calculated)
    transitionOk  <- FiberRules.L0.transitionExists(update.fiberId, update.eventName, state.calculated)
  } yield List(fiberActive, signedOk, transitionOk).combineAll

/** Validates an ArchiveFiber update (L0 specific checks) — UNCHANGED */
def archiveFiber(update: ArchiveStateMachine): F[ValidationResult] =
  for {
    fiberActive   <- FiberRules.L0.fiberIsActive(update.fiberId, state.calculated)
    signedByOwner <- FiberRules.L0.updateSignedByOwners(update.fiberId, proofs, state.calculated)
    // ^ STRICT — only owners can archive, not participants
  } yield List(fiberActive, signedByOwner).combineAll
```

---

## Combiner Changes

### `FiberCombiner.createStateMachineFiber`

**File**: `modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/lifecycle/combine/FiberCombiner.scala`

```scala
def createStateMachineFiber(
  update: Signed[Updates.CreateStateMachine]
): CombineResult[F] = for {
  currentOrdinal  <- ctx.getCurrentOrdinal
  owners          <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)
  initialDataHash <- update.initialData.computeDigest

  // NEW: participants become authorized signers on the fiber record
  authorizedSigners = update.participants.getOrElse(Set.empty)

  record = Records.StateMachineFiberRecord(
    fiberId = update.fiberId,
    creationOrdinal = currentOrdinal,
    previousUpdateOrdinal = currentOrdinal,
    latestUpdateOrdinal = currentOrdinal,
    definition = update.definition,
    currentState = update.definition.initialState,
    stateData = update.initialData,
    stateDataHash = initialDataHash,
    sequenceNumber = FiberOrdinal.MinValue,
    owners = owners,
    status = FiberStatus.Active,
    parentFiberId = update.parentFiberId,
    authorizedSigners = authorizedSigners  // NEW
  )

  result <- current.withRecord[F](update.fiberId, record)
} yield result
```

---

## TDD Test Cases

Write these tests in `modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/MultiPartySigningSuite.scala` using the `weaver` + `TestFixture` + `ParticipantRegistry` patterns from existing suites.

**Reference patterns**: `ValidatorSuite.scala`, `SpawnMachinesSuite.scala`

### Group 1: Backward Compatibility (3 tests)

**T1.1 — Existing single-owner fiber still works**
```
setup: Create fiber with Alice as signer, no participants declared
action: Alice signs TransitionStateMachine("advance", ...)
expect: ValidationResult.Valid ✓
```

**T1.2 — Non-owner without participants still rejected**
```
setup: Create fiber with Alice as signer, no participants declared
action: Bob signs TransitionStateMachine("advance", ...)
expect: ValidationResult.Invalid(NotSignedByAuthorizedParty) ✓
```

**T1.3 — Archive still requires owner even with participants**
```
setup: Create fiber with Alice as signer, Bob as participant
action: Bob signs ArchiveStateMachine(...)
expect: ValidationResult.Invalid(NotSignedByOwner) ✓
   — Bob can transition but cannot archive
```

### Group 2: Multi-Party Transition Signing (4 tests)

**T2.1 — Authorized participant can trigger transition**
```
setup: Alice creates fiber with participants = Set(Bob.address)
action: Bob signs TransitionStateMachine("accept", ...)
expect: ValidationResult.Valid ✓
```

**T2.2 — Multiple participants declared, any one can transition**
```
setup: Alice creates fiber with participants = Set(Bob, Charlie, Dave)
action: Charlie signs TransitionStateMachine("vote", ...)
expect: ValidationResult.Valid ✓
```

**T2.3 — Non-participant still rejected even with participants present**
```
setup: Alice creates fiber with participants = Set(Bob.address)
action: Charlie (not in participants) signs TransitionStateMachine(...)
expect: ValidationResult.Invalid(NotSignedByAuthorizedParty) ✓
```

**T2.4 — Owner can still transition even with participants set**
```
setup: Alice creates fiber with participants = Set(Bob.address)
action: Alice (owner) signs TransitionStateMachine("advance", ...)
expect: ValidationResult.Valid ✓
   — adding participants doesn't remove owner rights
```

### Group 3: CreateStateMachine Encoding (2 tests)

**T3.1 — participants field serializes/deserializes correctly**
```
setup: CreateStateMachine with participants = Some(Set(Alice.address, Bob.address))
action: encode to JSON, decode back
expect: participants field round-trips correctly
   — verify Set ordering is stable (sorted by address)
```

**T3.2 — Omitted participants field defaults to empty**
```
setup: CreateStateMachine without participants field (legacy format)
action: decode from JSON {"fiberId":..., "definition":..., "initialData":...}
expect: participants = None (and fiber record authorizedSigners = Set.empty)
   — backward compatible deserialization
```

### Group 4: FiberRecord Encoding (2 tests)

**T4.1 — authorizedSigners field persists in FiberRecord**
```
setup: Create fiber with Bob as participant, advance state
action: read StateMachineFiberRecord from CalculatedState
expect: record.authorizedSigners = Set(Bob.address) ✓
```

**T4.2 — Legacy FiberRecord without authorizedSigners decodes correctly**
```
setup: JSON FiberRecord without authorizedSigners field (legacy snapshot)
action: decode
expect: authorizedSigners = Set.empty (no crash, backward compat) ✓
```

### Group 5: Full Round-Trip Integration (2 tests)

**T5.1 — Two-party contract lifecycle**
```
scenario:
  1. Alice creates contract fiber with participants = Set(Bob)
  2. Bob signs "accept" transition → Valid ✓
  3. Alice signs "release" transition → Valid ✓
  4. Charlie signs anything → Invalid ✓
  5. Bob tries to archive → Invalid (NotSignedByOwner) ✓
  6. Alice archives → Valid ✓
```

**T5.2 — Owner-also-participant: no double-counting issues**
```
setup: Alice creates fiber with participants = Set(Alice.address)
   — Alice is both owner and in participants list
action: Alice signs TransitionStateMachine
expect: Valid ✓ (no error from union of owners + authorizedSigners)
```

### Group 6: Combiner Integration (2 tests)

**T6.1 — participants populate authorizedSigners on created record**
```
setup: Alice creates fiber with participants = Some(Set(Bob.address, Charlie.address))
action: run through full Combiner.combine pipeline
expect: retrieved FiberRecord.authorizedSigners == Set(Bob.address, Charlie.address) ✓
```

**T6.2 — No participants → authorizedSigners empty, validation unchanged**
```
setup: Alice creates fiber without participants
action: Bob tries to transition, run through L0 validation
expect: NotSignedByAuthorizedParty (same as legacy NotSignedByOwner behavior) ✓
```

---

## Implementation Plan

### Phase 1 (this spec): Static Participants at Creation Time

| Step | File | Change |
|------|------|--------|
| 1 | `Updates.scala` | Add `participants: Option[Set[Address]]` to `CreateStateMachine` |
| 2 | `Records.scala` | Add `authorizedSigners: Set[Address]` to `StateMachineFiberRecord` |
| 3 | `FiberRules.scala` | Add `updateSignedByOwnerOrParticipant` + `NotSignedByAuthorizedParty` error |
| 4 | `FiberValidator.scala` | Use new rule in `processEvent` |
| 5 | `FiberCombiner.scala` | Populate `authorizedSigners` from `update.participants` |
| 6 | `records.proto` | Add `authorized_signers` field (repeated string, field 15) |

**Target release**: v0.8.0 (breaking change: new field on FiberRecord, proto schema update)

### Phase 2 (future): Dynamic Participant Management

Two approaches for future consideration:

**Option A: `AddAuthorizedSigner` / `RemoveAuthorizedSigner` OttochainMessage**
```scala
// New message variants
case class AddAuthorizedSigner(fiberId: UUID, signer: Address, targetSeq: FiberOrdinal)
case class RemoveAuthorizedSigner(fiberId: UUID, signer: Address, targetSeq: FiberOrdinal)
```
- Only owners can add/remove
- Useful for: auction bidder reveals, DAO member additions

**Option B: JLVM `$signers` context variable**
```jsonc
// Guard that checks transition-specific signer:
{
  "in": [{"var": "$signer"}, {"var": "state.approvedParties"}]
}
```
- State-data-driven authorization
- More powerful but requires guard evaluation before rejection
- Requires redesigning validation order

**Recommendation**: Phase 2 starts when integration tests (Epic C) are green and James approves the direction.

---

## Acceptance Criteria (James Review)

- [ ] **Design approved**: James agrees with the `participants` field approach vs alternatives
- [ ] **Backward compat confirmed**: No migration needed for existing fibers or snapshots
- [ ] **Scope confirmed**: Phase 1 = static participants only; dynamic management = Phase 2
- [ ] **Error naming approved**: `NotSignedByAuthorizedParty` vs reusing `NotSignedByOwner`
- [ ] **Proto field number confirmed**: field 15 for `authorized_signers` in `StateMachineFiberRecord`

---

## Open Questions for James

1. **Should participants be validated at CreateStateMachine time?** e.g., reject if `participants` contains an address that is also in `owners` (redundant)? Or silently allow duplicates (union is idempotent)?

2. **Archive permission**: Should authorized participants EVER be able to archive a fiber? (Current spec: no — only owners can archive.) Use case: Bob created a delegation on Alice's behalf, Alice is unavailable, Bob needs to clean up.

3. **Participant limit**: Should we impose a `MaxParticipants` limit (e.g., 10) similar to `MaxStates`? Prevents griefing via enormous participant sets.

4. **Bridge API**: When clients submit `CreateStateMachine` via the bridge, does the bridge need a new `participants` field in its request body? Or is this a future concern?

---

## Files to Change

```
scasplte2/ottochain
├── modules/models/src/main/scala/xyz/kd5ujc/schema/
│   ├── Updates.scala                    # Add participants field
│   └── Records.scala                   # Add authorizedSigners field
├── modules/shared-data/src/main/scala/xyz/kd5ujc/shared_data/lifecycle/
│   ├── validate/rules/FiberRules.scala  # New rule + new error
│   ├── validate/FiberValidator.scala    # Use new rule in processEvent
│   └── combine/FiberCombiner.scala     # Populate authorizedSigners
├── modules/proto/src/main/protobuf/
│   └── records.proto                   # Add authorized_signers field
└── modules/shared-data/src/test/scala/xyz/kd5ujc/shared_data/
    └── MultiPartySigningSuite.scala    # NEW — 15 TDD tests (write BEFORE implementing)
```

No changes needed in:
- `FiberEngine.scala` — evaluation layer unchanged
- `Validator.scala` — routes to validators, not affected
- DL1/ML0 layers — no Tessellation changes needed
- `ottochain-services` — bridge changes are Epic B (client-side signing)
- `ottochain-sdk` — SDK changes are Epic B (build/submit endpoints)

---

## Why NOT These Alternatives

| Alternative | Why Rejected |
|-------------|--------------|
| **Co-ownership** (all participants become owners) | Participants gain archive rights — dangerous. Semantic mismatch. |
| **Guard-level signer check** (`$signer` JLVM var) | Requires evaluation pass before rejection; more complex; Phase 2 |
| **Extend delegation system** (everyone gets a credential) | Over-engineered for simple 2-party case; delegation = relaying, not participating |
| **Tessellation fork** (modify DL1 signer validation) | Never — would break all metagraphs, requires Constellation approval |
| **Bridge-side proxy signing** (bridge holds all keys) | Security anti-pattern; defeats the purpose of client-side signing |
