# Spec: Integration Tests — Verify Generated Proto Types with Fiber Engine

**Card:** `699621e4` — Integration Tests: Verify generated types with fiber engine  
**Epic:** Proto-first model unification (`6989337392c6da204c4e0987`)  
**Branch:** `test/proto-integration-types-tdd`  
**Stage:** Test Definition (TDD — failing tests BEFORE implementation)  
**Author:** @think — 2026-02-24  

---

## Problem Statement

The `modules/proto` ScalaPB codegen produces Scala classes from `messages.proto`, `records.proto`, `fiber.proto`, and `common.proto`. These generated types must:

1. **Round-trip cleanly** via proto binary (on-chain serialization format)
2. **Remain compatible** with the hand-written `models/` types that the fiber engine currently uses
3. **Interoperate** with TypeScript SDK proto types across the wire

Without verified integration tests, the migration from `models/` to generated types in `modules/proto` is a leap of faith. This spec defines the **failing tests** that prove correctness before `ProtoAdapters.scala` is implemented.

---

## Architecture Context

### Wire Format Boundary (CRITICAL)

**@research confirmed a BREAKING CHANGE risk** in wire format:

| Layer | Format | How |
|-------|---------|-----|
| DL1 JSON API (`/data`, etc.) | **Circe discriminated union** | `{"CreateStateMachine":{...}}` (PascalCase `getSimpleName`) |
| Proto binary (on-chain storage) | **proto3 binary** | `StateMachineFiberRecord.toByteArray` |
| Proto JSON (debug/cross-lang) | **proto3 JSON** | `{"createStateMachine":{...}}` (camelCase) |

**Rule:** The DL1 JSON API MUST continue using Circe encoding. Proto types are for **on-chain binary storage only**. Integration tests MUST NOT test JSON round-trips for on-chain types — only **binary** round-trips.

The existing `JsonCodecs.scala` (`protoEncoder`/`protoDecoder`) produces **proto3 JSON** format, NOT Circe format. These two are incompatible for `OttochainMessage` variants. Tests must explicitly test binary (`toByteArray` / `parseFrom`), not JSON.

### Key Compatibility Facts (@research findings)

| Mapping | Compatibility | Notes |
|---------|--------------|-------|
| `JsonLogicValue` ↔ `google.protobuf.Value` | **PERFECT** | `scalapb-circe` (v0.15.1 already in deps) bridges via identical Circe ↔ proto3 JSON mapping |
| `StateMachineDefinition.states` ↔ `Struct` | **LOSSLESS** | `StateId` (AnyVal + keyEncoder) → plain string key; state/transition fields JSON-compatible |
| `FunctionValue` (JsonLogicValue variant) | **null (acceptable)** | Runtime-only; never stored on-chain |
| `FiberOrdinal` (refined `Long`) ↔ `FiberOrdinal` (proto) | Type mismatch — conversion needed |
| `UUID` (fiberId) ↔ `String` (proto) | `UUID.fromString` can throw — `Either` return |

---

## Pre-conditions for Tests to Pass

The tests defined here will **fail** until `ProtoAdapters.scala` is implemented. This is intentional (TDD). Implementation requirements:

### ProtoAdapters.scala — Must Implement

```scala
package xyz.kd5ujc.proto

object ProtoAdapters {

  /** Convert hand-written StateMachineFiberRecord → proto StateMachineFiberRecord.
   *  Currently missing: definition, stateData, stateDataHash, lastReceipt, status mapping.
   */
  def toProtoSMRecord(r: Records.StateMachineFiberRecord): proto.StateMachineFiberRecord

  /** Convert proto StateMachineFiberRecord → hand-written.
   *  Returns Left[String] on: invalid UUID, unrecognized FiberStatus enum,
   *  Address parsing failure, fromProto conversion errors.
   */
  def fromProtoSMRecord(r: proto.StateMachineFiberRecord): Either[String, Records.StateMachineFiberRecord]

  /** Convert hand-written ScriptFiberRecord → proto ScriptFiberRecord.
   *  Currently missing: scriptProgram, stateData, stateDataHash, accessControl, lastInvocation.
   */
  def toProtoScriptRecord(r: Records.ScriptFiberRecord): proto.ScriptFiberRecord

  /** Convert proto ScriptFiberRecord → hand-written. */
  def fromProtoScriptRecord(r: proto.ScriptFiberRecord): Either[String, Records.ScriptFiberRecord]
}
```

### Missing toProto Fields

Based on code audit of `Records.scala` vs `records.proto`:

**toProtoSMRecord — missing 5 fields:**
| Hand-written field | Proto field | Conversion |
|-------------------|-------------|-----------|
| `definition: StateMachineDefinition` | `definition: StateMachineDefinition` | Nested: states→Struct, transitions→Struct list |
| `stateData: JsonLogicValue` | `state_data: Value` | `scalapb_circe.JsonFormat.toJson(v).as[com.google.protobuf.Value]` |
| `stateDataHash: Hash` | `state_data_hash: HashValue` | `HashValue(value = r.stateDataHash.value)` |
| `lastReceipt: Option[EventReceipt]` | `last_receipt: optional EventReceipt` | Nested EventReceipt conversion |
| `status: FiberStatus` | `status: FiberStatus` | Enum mapping (ACTIVE→1, ARCHIVED→2, FAILED→3) |

**toProtoScriptRecord — missing 5 fields:**
| Hand-written field | Proto field | Conversion |
|-------------------|-------------|-----------|
| `scriptProgram: JsonLogicExpression` | `script_program: Value` | Via scalapb-circe JSON bridge |
| `stateData: Option[JsonLogicValue]` | `state_data: optional Value` | Optional Value conversion |
| `stateDataHash: Option[Hash]` | `state_data_hash: optional HashValue` | Optional HashValue |
| `accessControl: AccessControlPolicy` | `access_control: AccessControlPolicy` | Oneof mapping |
| `lastInvocation: Option[OracleInvocation]` | `last_invocation: optional ScriptInvocation` | Nested conversion |

---

## Test Specification

### File Location
```
modules/proto/src/test/scala/xyz/kd5ujc/proto/ProtoAdaptersIntegrationTest.scala
```

### Group A: Binary Round-Trip — StateMachineFiberRecord (5 tests)

**A1: Minimal StateMachineFiberRecord round-trips via proto binary**
```
Given a minimal StateMachineFiberRecord with:
  - fiberId: random UUID
  - empty definition (single "initial" state, no transitions)  
  - stateData: JsonLogicValue.Null
  - sequenceNumber: FiberOrdinal(1L)
  - owners: Set(one Address)
  - status: FiberStatus.Active

When toProtoSMRecord(record).toByteArray is called
And ProtoStateMachineFiberRecord.parseFrom(bytes) is called  
And fromProtoSMRecord(parsed) is called

Then result == Right(record)
Assert: fiberId preserved (UUID ↔ String ↔ UUID)
Assert: sequenceNumber preserved (FiberOrdinal value)
Assert: status preserved (Active ↔ FIBER_STATUS_ACTIVE)
Assert: owners preserved (all Address hex values)
```

**A2: Full StateMachineFiberRecord with complex stateData round-trips**
```
Given a StateMachineFiberRecord with:
  - stateData: JsonLogicValue.Map(Map(
      "counter" → JsonLogicValue.Integer(42),
      "active"  → JsonLogicValue.Bool(true),
      "name"    → JsonLogicValue.Str("test"),
      "scores"  → JsonLogicValue.Array(List(JsonLogicValue.Integer(1), JsonLogicValue.Integer(2)))
    ))
  - definition with 3 states and 2 transitions (including guard expression)
  - lastReceipt: Some(EventReceipt with emitted events)
  - parentFiberId: Some(random UUID)
  - childFiberIds: Set(2 UUIDs)

When round-tripped via binary proto

Then result == Right(original)
Assert: stateData map entries preserved with correct types
Assert: definition.states losslessly encoded as Struct
Assert: lastReceipt preserved (all EventReceipt fields)
Assert: parentFiberId and childFiberIds preserved as UUID strings
```

**A3: StateMachineDefinition transitions encode losslessly**
```
Given a StateMachineDefinition with:
  - states: { "IDLE" → State(...), "ACTIVE" → State(...), "DONE" → State(...) }
  - initialState: StateId("IDLE")
  - transitions: List(
      Transition(from="IDLE", to="ACTIVE", eventName="start", guard=Some(json_logic_guard)),
      Transition(from="ACTIVE", to="DONE", eventName="complete", guard=None)
    )
  - metadata: Some(JsonLogicValue.Map(Map("version" → JsonLogicValue.Integer(1))))

When definition is encoded to StateMachineDefinition proto (field 5 of SMRecord)
And round-tripped via binary

Then states: Struct has 3 keys ("IDLE", "ACTIVE", "DONE")
And initialState.value == "IDLE"  
And transitions: Seq[Struct] has length 2
And guard JsonLogicExpression round-trips as Value
And metadata Struct round-trips losslessly
```

**A4: FiberStatus enum maps bidirectionally**
```
Test ALL valid FiberStatus values:
  FiberStatus.Active   ↔ FIBER_STATUS_ACTIVE   (value=1)
  FiberStatus.Archived ↔ FIBER_STATUS_ARCHIVED  (value=2)
  FiberStatus.Failed   ↔ FIBER_STATUS_FAILED    (value=3)

Assert: proto FiberStatus value 0 (UNSPECIFIED) returns Left("Unrecognized FiberStatus: 0")
```

**A5: Optional fields absent in proto map to None in hand-written types**
```
Context: PR #93 introduced magnolia customizable codecs (@derive(customizableEncoder, customizableDecoder)
with useDefaults = true) to handle absent JSON keys by falling back to Scala default values.
ProtoAdapters must handle the same "absent field" semantics for proto binary encoding.

Given a proto.StateMachineFiberRecord with ONLY required fields set:
  - fiberId: valid UUID string
  - sequenceNumber: Some(FiberOrdinal(1L))
  - owners: Seq(valid hex address)
  - status: FIBER_STATUS_ACTIVE
  (parentFiberId NOT set — optional String, proto default = "")
  (lastReceipt NOT set — optional EventReceipt, proto default = None)
  (childFiberIds NOT set — repeated field, proto default = empty Seq)

When fromProtoSMRecord(minimalProto) is called

Then result.isRight == true
Assert: result.value.parentFiberId.isEmpty == true   (proto "" → Scala None, not Some(""))
Assert: result.value.lastReceipt.isEmpty == true      (proto absent → Scala None)
Assert: result.value.childFiberIds.isEmpty == true    (proto empty Seq → Scala Set.empty)
```

---

### Group B: Binary Round-Trip — ScriptFiberRecord (3 tests)

**B1: Minimal ScriptFiberRecord round-trips via proto binary**
```
Given a ScriptFiberRecord with:
  - fiberId: random UUID
  - scriptProgram: JsonLogicExpression({"if": [{"var": "x"}, "yes", "no"]})
  - stateData: None
  - stateDataHash: None
  - accessControl: AccessControlPolicy.Public
  - sequenceNumber: FiberOrdinal(1L)
  - owners: Set(one Address)
  - status: FiberStatus.Active
  - lastInvocation: None

When round-tripped via binary proto

Then result == Right(record)
Assert: scriptProgram preserved as JsonLogicExpression
Assert: accessControl.Public maps to proto oneof public
```

**B2: ScriptFiberRecord with Whitelist access control round-trips**
```
Given a ScriptFiberRecord with:
  - accessControl: AccessControlPolicy.Whitelist(Set(addr1, addr2))
  - stateData: Some(JsonLogicValue.Map(...))
  - stateDataHash: Some(Hash("abc123..."))
  - lastInvocation: Some(OracleInvocation with args and result)

When round-tripped via binary proto

Then accessControl.Whitelist has addresses preserved
And stateData Option preserved
And stateDataHash preserved
And lastInvocation (ScriptInvocation) preserved (all fields)
```

**B3: FiberOwnedAccess access control maps correctly**
```
Given accessControl: AccessControlPolicy.FiberOwned(fiberRef: UUID)

When round-tripped via binary

Then AccessControlPolicy.FiberOwned preserved
And fiberRef UUID preserved as String in proto
```

---

### Group C: Cross-Language Compatibility (2 tests)

**C1: Scala binary output can be decoded by TypeScript SDK**
```
Given: Scala generates a StateMachineFiberRecord proto binary
When: Binary is base64-encoded and passed to TypeScript test harness
Then: TypeScript `StateMachineFiberRecord.decode(bytes)` succeeds
And: fiberId, current_state, sequence_number match Scala values
And: state_data Value matches the JsonLogicValue structure

Note: This test requires a TypeScript test harness script (see §Implementation Notes)
Implementation: Run ts-node script via Process, assert JSON output
```

**C2: TypeScript binary output can be decoded by Scala**
```
Given: TypeScript generates a proto binary from StateMachineFiberRecord fixture
When: Binary is read by Scala ProtoStateMachineFiberRecord.parseFrom(bytes)
Then: fromProtoSMRecord succeeds (Right)
And: All primitive fields match TypeScript fixture values

Note: Fixture binary committed to test/resources/fixtures/proto-compat/
```

---

### Group D: Error Handling — fromProto Failures (4 tests)

**D1: fromProtoSMRecord returns Left on invalid fiberId**
```
Given a proto StateMachineFiberRecord with fiber_id = "not-a-uuid"
When fromProtoSMRecord is called
Then result == Left(error containing "Invalid UUID: not-a-uuid")
```

**D2: fromProtoSMRecord returns Left on UNRECOGNIZED FiberStatus**
```
Given a proto record with status = FIBER_STATUS_UNSPECIFIED (0)
When fromProtoSMRecord is called
Then result == Left(error containing "Unrecognized FiberStatus")

Note: UNSPECIFIED (0) is proto3's default; a record reaching storage should never have this
```

**D3: fromProtoScriptRecord returns Left on invalid owner Address**
```
Given a proto ScriptFiberRecord with owners containing a malformed address bytes
When fromProtoScriptRecord is called
Then result == Left(error containing "Invalid Address")
```

**D4: fromProto does NOT throw exceptions**
```
Fuzz test: generate 100 random byte arrays of various lengths
When parseFrom(bytes) succeeds (proto is robust to partial parses)
When fromProtoSMRecord(parsed) is called
Then: No exception is thrown (only Left values)
Assert: All results are Either[String, _] — no thrown exceptions
```

---

### Group E: Pipeline Integration (2 tests)

**E1: Fiber engine accepts a CreateStateMachine (generated type) and stores SMRecord**
```
Pre-condition: fiber engine uses ProtoAdapters for on-chain storage

Given: A valid CreateStateMachine (generated type) submitted via TestFixture
When: ML0 validates and combines the update
Then: CalculatedState contains a StateMachineFiberRecord for the fiberId
And: fromProtoSMRecord(record) == Right(expected_hand_written_record)
And: Binary serialization of the stored record produces < 10KB

Note: Uses existing SharedDataSuite test infrastructure
```

**E2: FiberRecord binary storage is idempotent across snapshot boundaries**
```
Given: A fiber that has been through 3 successful transitions (sequenceNumber=3)
When: The fiber record is serialized to proto binary (simulating snapshot storage)
And: Deserialized in the next snapshot
Then: fromProtoSMRecord succeeds
And: sequenceNumber == FiberOrdinal(3L)
And: currentState, stateData, lastReceipt are all preserved
```

---

## Implementation Notes

### Test Infrastructure

```scala
// Proto binary round-trip helper
def binaryRoundTrip[A <: GeneratedMessage : GeneratedMessageCompanion](msg: A): A =
  companion.parseFrom(msg.toByteArray)

// SMRecord round-trip helper
def smRoundTrip(record: Records.StateMachineFiberRecord): Either[String, Records.StateMachineFiberRecord] =
  ProtoAdapters.fromProtoSMRecord(
    binaryRoundTrip(ProtoAdapters.toProtoSMRecord(record))
  )
```

### Cross-Language Test Harness (C1/C2)

Create `modules/proto/src/test/resources/cross-lang/` with:
- `decode_sm_record.ts` — reads proto binary from stdin, outputs JSON to stdout
- `sm_record_fixture.bin` — committed TypeScript-generated binary fixture

The Scala test invokes:
```scala
Process(s"npx ts-node decode_sm_record.ts") #< inputStream
```

### scalapb-circe Value Bridge

For `JsonLogicValue` ↔ `google.protobuf.Value`:
```scala
import scalapb_circe.JsonFormat
import io.circe.syntax._

// JsonLogicValue → Value: via Circe JSON
val json: io.circe.Json = jsonLogicValue.asJson
val value: com.google.protobuf.Value = JsonFormat.fromJson[com.google.protobuf.Value](json)

// Value → JsonLogicValue: reverse
val json2: io.circe.Json = JsonFormat.toJson(value)  
val jlv: JsonLogicValue = json2.as[JsonLogicValue].getOrElse(JsonLogicValue.Null)
```

`FunctionValue` case: produces `null` Value (acceptable — FunctionValue is runtime-only, never stored on-chain).

---

## Acceptance Criteria

| # | Criterion | Owner |
|---|-----------|-------|
| AC1 | All 16 tests are written as **failing** weaver tests in `ProtoAdaptersIntegrationTest.scala` | @code |
| AC2 | `ProtoAdapters.toProtoSMRecord` maps all 14 fields of `StateMachineFiberRecord` | @work |
| AC3 | `ProtoAdapters.fromProtoSMRecord` returns `Either[String, StateMachineFiberRecord]` — no throws | @work |
| AC4 | `ProtoAdapters.toProtoScriptRecord` maps all 11 fields of `ScriptFiberRecord` | @work |
| AC5 | `ProtoAdapters.fromProtoScriptRecord` returns `Either[String, ScriptFiberRecord]` | @work |
| AC6 | DL1 JSON API continues to use Circe encoding (no changes to `@derive(encoder, decoder)` classes) | @work |
| AC7 | `StateMachineDefinition` encodes as proto `Struct` losslessly (Group A test A3) | @work |
| AC8 | `JsonLogicValue` ↔ `google.protobuf.Value` via scalapb-circe bridge (AC2/AC4 sub-requirement) | @work |
| AC9 | Cross-language binary compatibility verified (C1/C2 — TypeScript can decode Scala proto output) | @code |
| AC10 | `sbt proto/test` runs all 16 tests in CI — no new CI job needed (PR #96 already added `proto/test`) | @work |
| AC11 | `FiberStatus.UNSPECIFIED` (0) returns `Left` from `fromProto` — never silently accepted | @code |

---

## Dependencies

| Dependency | Status |
|-----------|--------|
| `scalapb-circe` v0.15.1 | ✅ Already in `project/Dependencies.scala` |
| Proto CI step (`sbt proto/compile proto/test`) | ✅ Added by PR #96 |
| `modules/proto/src/main/scala/xyz/kd5ujc/proto/JsonCodecs.scala` | ✅ Exists on develop |
| `ProtoAdapters.scala` implementation | ❌ Not yet implemented — **tests will fail until this exists** |
| TypeScript proto-decode test harness | ❌ To be created by @code |
| `feat/multi-party-signing` merged (for OttochainMessage types) | ❌ Not required for this card (scope: Records only) |

---

## Scope Boundary

**In scope (this card):**
- `StateMachineFiberRecord` proto ↔ hand-written round-trip
- `ScriptFiberRecord` proto ↔ hand-written round-trip
- `StateMachineDefinition` nested encoding
- `EventReceipt` and `ScriptInvocation` nested encoding
- Cross-language binary compatibility

**Out of scope (separate card `699621e1d2651cedf586849f` — Migrate: Remove hand-written models module):**
- Removing `models/` module
- Migrating fiber engine to use generated types directly
- `OttochainMessage` fromProto (deferred — needs Phase 2 ProtoAdapters)
- Runtime-only types (`FiberGasConfig`, `ExecutionLimits`, `FiberContext`, `FiberInput`) — NO proto equivalents

---

## 🧠 @think Perspective

**Decomposition:**
This card validates ONE assumption at the heart of the proto migration: that the hand-written ↔ generated type boundary can be cleanly crossed without data loss. The natural split is:
1. Tests first (this spec) → 2. Implement ProtoAdapters → 3. Migrate engine to use generated types

**Edge cases to watch:**
- `FiberOrdinal` is a refined `Long` (`Long Refined Positive`) — proto uses plain `FiberOrdinal` message (wrapping `value: uint64`). The refinement validation should be skipped in `fromProto` (trust the on-chain data).
- `Set[UUID] childFiberIds` → `repeated string child_fiber_ids`: order is not guaranteed. Tests must use `Set` comparison, not sequence equality.
- Empty `StateMachineDefinition` (no states, no transitions) is valid for scripts/delegations — test A1 should explicitly verify this edge case.
- `JsonLogicExpression` (for `scriptProgram`) is a recursive structure — deep nesting should round-trip via the same scalapb-circe bridge as `JsonLogicValue`.

**Key risk:** If `JsonLogicExpression` Circe encoding doesn't match `google.protobuf.Value` proto3 JSON encoding exactly, the scriptProgram bridge will silently corrupt data. Test B1 must use a non-trivial expression (nested `if`/`var`) to catch this.
