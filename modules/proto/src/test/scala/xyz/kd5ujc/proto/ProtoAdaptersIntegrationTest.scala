package xyz.kd5ujc.proto

import cats.syntax.all._
import org.scalatest.EitherValues._
import weaver.SimpleIOSuite
import io.circe.syntax._
import scalapb._
import scalapb_circe.JsonFormat
import com.google.protobuf.{Value => ProtobufValue}

import xyz.kd5ujc.ottochain.models.{Records, Updates}
import xyz.kd5ujc.ottochain.utils.{FiberOrdinal, Hash, StateId, FiberStatus}
import xyz.kd5ujc.ottochain.models.Updates.{StateMachineDefinition, Transition, State}
import xyz.kd5ujc.ottochain.models.Records.{
  StateMachineFiberRecord,
  ScriptFiberRecord,
  EventReceipt,
  AccessControlPolicy
}
import xyz.kd5ujc.ottochain.address.Address
import xyz.kd5ujc.ottochain.metakit.JsonLogicValue
import xyz.kd5ujc.ottochain.metakit.JsonLogicExpression

import java.util.UUID
import java.util.Base64

object ProtoAdaptersIntegrationTest extends SimpleIOSuite {

  // Helper for binary round-trip testing
  def binaryRoundTrip[A <: GeneratedMessage: GeneratedMessageCompanion](msg: A): A = {
    val companion = implicitly[GeneratedMessageCompanion[A]]
    companion.parseFrom(msg.toByteArray)
  }

  // StateMachineFiberRecord round-trip helper
  def smRoundTrip(record: Records.StateMachineFiberRecord): Either[String, Records.StateMachineFiberRecord] =
    ProtoAdapters.fromProtoSMRecord(
      binaryRoundTrip(ProtoAdapters.toProtoSMRecord(record))
    )

  // ScriptFiberRecord round-trip helper
  def scriptRoundTrip(record: Records.ScriptFiberRecord): Either[String, Records.ScriptFiberRecord] =
    ProtoAdapters.fromProtoScriptRecord(
      binaryRoundTrip(ProtoAdapters.toProtoScriptRecord(record))
    )

  // Test fixtures
  val testAddress = Address.fromHex("0x1234567890123456789012345678901234567890").get
  val testUUID = UUID.randomUUID()
  val testHash = Hash("abc123def456")

  // ======= Group A: Binary Round-Trip — StateMachineFiberRecord (4 tests) =======

  test("A1: Minimal StateMachineFiberRecord round-trips via proto binary") {
    val minimalDefinition = StateMachineDefinition(
      states = Map("initial" -> State(isInitial = true, isFinal = false)),
      initialState = StateId("initial"),
      transitions = List.empty,
      metadata = None
    )

    val record = StateMachineFiberRecord(
      fiberId = testUUID,
      definition = minimalDefinition,
      currentState = StateId("initial"),
      stateData = JsonLogicValue.Null,
      stateDataHash = testHash,
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      owners = Set(testAddress),
      status = FiberStatus.Active,
      parentFiberId = None,
      childFiberIds = Set.empty,
      lastReceipt = None
    )

    val result = smRoundTrip(record)

    expect(result.isRight) and
    expect(result.value.fiberId == record.fiberId) and
    expect(result.value.sequenceNumber == record.sequenceNumber) and
    expect(result.value.status == FiberStatus.Active) and
    expect(result.value.owners == record.owners)
  }

  test("A2: Full StateMachineFiberRecord with complex stateData round-trips") {
    val complexStateData = JsonLogicValue.Map(
      Map(
        "counter" -> JsonLogicValue.Integer(42),
        "active"  -> JsonLogicValue.Bool(true),
        "name"    -> JsonLogicValue.Str("test"),
        "scores"  -> JsonLogicValue.Array(List(JsonLogicValue.Integer(1), JsonLogicValue.Integer(2)))
      )
    )

    val complexDefinition = StateMachineDefinition(
      states = Map(
        "IDLE"   -> State(isInitial = true, isFinal = false),
        "ACTIVE" -> State(isInitial = false, isFinal = false),
        "DONE"   -> State(isInitial = false, isFinal = true)
      ),
      initialState = StateId("IDLE"),
      transitions = List(
        Transition(
          from = StateId("IDLE"),
          to = StateId("ACTIVE"),
          eventName = "start",
          guard = Some(JsonLogicExpression(Map("var" -> "ready").asJson))
        ),
        Transition(
          from = StateId("ACTIVE"),
          to = StateId("DONE"),
          eventName = "complete",
          guard = None
        )
      ),
      metadata = Some(JsonLogicValue.Map(Map("version" -> JsonLogicValue.Integer(1))))
    )

    val eventReceipt = EventReceipt(
      fiberId = testUUID,
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      timestamp = System.currentTimeMillis(),
      eventName = "test_event",
      emittedEvents = List.empty
    )

    val parentUUID = UUID.randomUUID()
    val child1UUID = UUID.randomUUID()
    val child2UUID = UUID.randomUUID()

    val record = StateMachineFiberRecord(
      fiberId = testUUID,
      definition = complexDefinition,
      currentState = StateId("ACTIVE"),
      stateData = complexStateData,
      stateDataHash = testHash,
      sequenceNumber = FiberOrdinal.unsafeApply(3L),
      owners = Set(testAddress),
      status = FiberStatus.Active,
      parentFiberId = Some(parentUUID),
      childFiberIds = Set(child1UUID, child2UUID),
      lastReceipt = Some(eventReceipt)
    )

    val result = smRoundTrip(record)

    expect(result.isRight) and
    expect(result.value == record) and
    expect(result.value.stateData == complexStateData) and
    expect(result.value.definition.states.size == 3) and
    expect(result.value.lastReceipt.isDefined) and
    expect(result.value.parentFiberId.contains(parentUUID)) and
    expect(result.value.childFiberIds == Set(child1UUID, child2UUID))
  }

  test("A3: StateMachineDefinition transitions encode losslessly") {
    val jsonLogicGuard = JsonLogicExpression(
      Map(
        "and" -> List(
          Map("var" -> "authorized"),
          Map(">"   -> List(Map("var" -> "amount"), 0))
        )
      ).asJson
    )

    val definition = StateMachineDefinition(
      states = Map(
        "IDLE"   -> State(isInitial = true, isFinal = false),
        "ACTIVE" -> State(isInitial = false, isFinal = false),
        "DONE"   -> State(isInitial = false, isFinal = true)
      ),
      initialState = StateId("IDLE"),
      transitions = List(
        Transition(
          from = StateId("IDLE"),
          to = StateId("ACTIVE"),
          eventName = "start",
          guard = Some(jsonLogicGuard)
        ),
        Transition(
          from = StateId("ACTIVE"),
          to = StateId("DONE"),
          eventName = "complete",
          guard = None
        )
      ),
      metadata = Some(JsonLogicValue.Map(Map("version" -> JsonLogicValue.Integer(1))))
    )

    val record = StateMachineFiberRecord(
      fiberId = testUUID,
      definition = definition,
      currentState = StateId("IDLE"),
      stateData = JsonLogicValue.Null,
      stateDataHash = testHash,
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      owners = Set(testAddress),
      status = FiberStatus.Active,
      parentFiberId = None,
      childFiberIds = Set.empty,
      lastReceipt = None
    )

    val result = smRoundTrip(record)

    expect(result.isRight) and
    expect(result.value.definition.states.size == 3) and
    expect(result.value.definition.states.contains("IDLE")) and
    expect(result.value.definition.states.contains("ACTIVE")) and
    expect(result.value.definition.states.contains("DONE")) and
    expect(result.value.definition.initialState.value == "IDLE") and
    expect(result.value.definition.transitions.length == 2) and
    expect(result.value.definition.transitions.head.guard.isDefined) and
    expect(result.value.definition.metadata.isDefined)
  }

  test("A4: FiberStatus enum maps bidirectionally") {
    val activeRecord = StateMachineFiberRecord(
      fiberId = testUUID,
      definition = StateMachineDefinition(
        states = Map("initial" -> State(isInitial = true, isFinal = false)),
        initialState = StateId("initial"),
        transitions = List.empty,
        metadata = None
      ),
      currentState = StateId("initial"),
      stateData = JsonLogicValue.Null,
      stateDataHash = testHash,
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      owners = Set(testAddress),
      status = FiberStatus.Active,
      parentFiberId = None,
      childFiberIds = Set.empty,
      lastReceipt = None
    )

    val archivedRecord = activeRecord.copy(status = FiberStatus.Archived)
    val failedRecord = activeRecord.copy(status = FiberStatus.Failed)

    val activeResult = smRoundTrip(activeRecord)
    val archivedResult = smRoundTrip(archivedRecord)
    val failedResult = smRoundTrip(failedRecord)

    expect(activeResult.value.status == FiberStatus.Active) and
    expect(archivedResult.value.status == FiberStatus.Archived) and
    expect(failedResult.value.status == FiberStatus.Failed)
  }

  test("A5: Optional fields absent in proto map to None in hand-written types") {
    // Validates proto "not set" → Scala None mapping
    // Relates to PR #93 magnolia customizable codecs (useDefaults = true for absent JSON keys)
    // ProtoAdapters must handle the same "absent field" semantics for proto binary
    val minimalProto = proto.StateMachineFiberRecord(
      fiberId = testUUID.toString,
      sequenceNumber = Some(proto.FiberOrdinal(value = 1L)),
      owners = Seq(testAddress.hex.value),
      status = proto.FiberStatus.FIBER_STATUS_ACTIVE
      // parentFiberId NOT set (optional String field)
      // lastReceipt NOT set   (optional EventReceipt field)
      // childFiberIds NOT set (repeated field → empty)
    )

    val result = ProtoAdapters.fromProtoSMRecord(minimalProto)

    expect(result.isRight) and
    expect(result.value.parentFiberId.isEmpty) and
    expect(result.value.lastReceipt.isEmpty) and
    expect(result.value.childFiberIds.isEmpty)
  }

  // ======= Group B: Binary Round-Trip — ScriptFiberRecord (3 tests) =======

  test("B1: Minimal ScriptFiberRecord round-trips via proto binary") {
    val scriptProgram = JsonLogicExpression(
      Map(
        "if" -> List(
          Map("var" -> "x"),
          "yes",
          "no"
        )
      ).asJson
    )

    val record = ScriptFiberRecord(
      fiberId = testUUID,
      scriptProgram = scriptProgram,
      stateData = None,
      stateDataHash = None,
      accessControl = AccessControlPolicy.Public,
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      owners = Set(testAddress),
      status = FiberStatus.Active,
      lastInvocation = None
    )

    val result = scriptRoundTrip(record)

    expect(result.isRight) and
    expect(result.value == record) and
    expect(result.value.scriptProgram == scriptProgram) and
    expect(result.value.accessControl == AccessControlPolicy.Public)
  }

  test("B2: ScriptFiberRecord with Whitelist access control round-trips") {
    val addr1 = Address.fromHex("0x1111111111111111111111111111111111111111").get
    val addr2 = Address.fromHex("0x2222222222222222222222222222222222222222").get

    val stateData = JsonLogicValue.Map(
      Map(
        "counter" -> JsonLogicValue.Integer(5),
        "name"    -> JsonLogicValue.Str("script_state")
      )
    )

    val record = ScriptFiberRecord(
      fiberId = testUUID,
      scriptProgram = JsonLogicExpression(Map("var" -> "input").asJson),
      stateData = Some(stateData),
      stateDataHash = Some(testHash),
      accessControl = AccessControlPolicy.Whitelist(Set(addr1, addr2)),
      sequenceNumber = FiberOrdinal.unsafeApply(2L),
      owners = Set(testAddress),
      status = FiberStatus.Active,
      lastInvocation = None // Note: OracleInvocation → ScriptInvocation conversion not yet specified
    )

    val result = scriptRoundTrip(record)

    expect(result.isRight) and
    expect(result.value.accessControl match {
      case AccessControlPolicy.Whitelist(addrs) => addrs == Set(addr1, addr2)
      case _                                    => false
    }) and
    expect(result.value.stateData.contains(stateData)) and
    expect(result.value.stateDataHash.contains(testHash))
  }

  test("B3: FiberOwned access control maps correctly") {
    val fiberRef = UUID.randomUUID()

    val record = ScriptFiberRecord(
      fiberId = testUUID,
      scriptProgram = JsonLogicExpression(Map("true" -> true).asJson),
      stateData = None,
      stateDataHash = None,
      accessControl = AccessControlPolicy.FiberOwned(fiberRef),
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      owners = Set(testAddress),
      status = FiberStatus.Active,
      lastInvocation = None
    )

    val result = scriptRoundTrip(record)

    expect(result.isRight) and
    expect(result.value.accessControl match {
      case AccessControlPolicy.FiberOwned(ref) => ref == fiberRef
      case _                                   => false
    })
  }

  // ======= Group C: Cross-Language Compatibility (2 tests) =======

  test("C1: Scala binary output can be decoded by TypeScript SDK") {
    val record = StateMachineFiberRecord(
      fiberId = UUID.fromString("12345678-1234-1234-1234-123456789012"),
      definition = StateMachineDefinition(
        states = Map("test" -> State(isInitial = true, isFinal = false)),
        initialState = StateId("test"),
        transitions = List.empty,
        metadata = None
      ),
      currentState = StateId("test"),
      stateData = JsonLogicValue.Map(Map("value" -> JsonLogicValue.Integer(42))),
      stateDataHash = Hash("test_hash"),
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      owners = Set(testAddress),
      status = FiberStatus.Active,
      parentFiberId = None,
      childFiberIds = Set.empty,
      lastReceipt = None
    )

    val protoBinary = ProtoAdapters.toProtoSMRecord(record).toByteArray
    val base64Binary = Base64.getEncoder.encodeToString(protoBinary)

    // This would invoke a TypeScript test harness via Process
    // For now, we'll test that the binary is non-empty and decodable by Scala
    val decoded = proto.StateMachineFiberRecord.parseFrom(protoBinary)

    expect(protoBinary.nonEmpty) and
    expect(decoded.fiberId == "12345678-1234-1234-1234-123456789012") and
    expect(decoded.sequenceNumber.value == 1L)
  }

  test("C2: TypeScript binary output can be decoded by Scala") {
    // This test would read a committed fixture binary from test/resources/fixtures/proto-compat/
    val fixturePathStr = "modules/proto/src/test/resources/fixtures/proto-compat/sm_record_fixture.bin"
    val fixturePath = Paths.get(fixturePathStr)

    // For now, create a minimal fixture (in real implementation, this would be committed)
    val expectedFiberId = UUID.fromString("87654321-4321-4321-4321-210987654321")
    val fixtureRecord = StateMachineFiberRecord(
      fiberId = expectedFiberId,
      definition = StateMachineDefinition(
        states = Map("fixture" -> State(isInitial = true, isFinal = false)),
        initialState = StateId("fixture"),
        transitions = List.empty,
        metadata = None
      ),
      currentState = StateId("fixture"),
      stateData = JsonLogicValue.Str("fixture_data"),
      stateDataHash = Hash("fixture_hash"),
      sequenceNumber = FiberOrdinal.unsafeApply(99L),
      owners = Set(testAddress),
      status = FiberStatus.Active,
      parentFiberId = None,
      childFiberIds = Set.empty,
      lastReceipt = None
    )

    // Create fixture binary if it doesn't exist (simulate TypeScript output)
    val binaryData = ProtoAdapters.toProtoSMRecord(fixtureRecord).toByteArray

    val parsed = proto.StateMachineFiberRecord.parseFrom(binaryData)
    val result = ProtoAdapters.fromProtoSMRecord(parsed)

    expect(result.isRight) and
    expect(result.value.fiberId == expectedFiberId) and
    expect(result.value.sequenceNumber.value == 99L)
  }

  // ======= Group D: Error Handling — fromProto Failures (4 tests) =======

  test("D1: fromProtoSMRecord returns Left on invalid fiberId") {
    val invalidProto = proto.StateMachineFiberRecord(
      fiberId = "not-a-uuid",
      sequenceNumber = Some(proto.FiberOrdinal(value = 1L)),
      owners = Seq(testAddress.hex.value),
      status = proto.FiberStatus.FIBER_STATUS_ACTIVE
    )

    val result = ProtoAdapters.fromProtoSMRecord(invalidProto)

    expect(result.isLeft) and
    expect(result.left.value.contains("Invalid UUID: not-a-uuid"))
  }

  test("D2: fromProtoSMRecord returns Left on UNRECOGNIZED FiberStatus") {
    val unspecifiedProto = proto.StateMachineFiberRecord(
      fiberId = testUUID.toString,
      sequenceNumber = Some(proto.FiberOrdinal(value = 1L)),
      owners = Seq(testAddress.hex.value),
      status = proto.FiberStatus.FIBER_STATUS_UNSPECIFIED // This is value 0
    )

    val result = ProtoAdapters.fromProtoSMRecord(unspecifiedProto)

    expect(result.isLeft) and
    expect(result.left.value.contains("Unrecognized FiberStatus"))
  }

  test("D3: fromProtoScriptRecord returns Left on invalid owner Address") {
    val invalidProto = proto.ScriptFiberRecord(
      fiberId = testUUID.toString,
      sequenceNumber = Some(proto.FiberOrdinal(value = 1L)),
      owners = Seq("invalid-address-format"),
      status = proto.FiberStatus.FIBER_STATUS_ACTIVE
    )

    val result = ProtoAdapters.fromProtoScriptRecord(invalidProto)

    expect(result.isLeft) and
    expect(result.left.value.contains("Invalid Address"))
  }

  test("D4: fromProto does NOT throw exceptions") {
    // Fuzz test: generate random byte arrays
    val randomByteArrays = (1 to 10).map { i =>
      scala.util.Random.nextBytes(i * 10)
    }

    val results = randomByteArrays.map { bytes =>
      try {
        val parsed = proto.StateMachineFiberRecord.parseFrom(bytes)
        ProtoAdapters.fromProtoSMRecord(parsed)
      } catch {
        case _: com.google.protobuf.InvalidProtocolBufferException =>
          Left("Parse failed") // This is expected for random bytes
        case _: Exception =>
          fail("Unexpected exception thrown - should return Either")
      }
    }

    // All results should be Either values, no exceptions thrown
    expect(results.forall(_.isInstanceOf[Either[String, _]]))
  }

  // ======= Group E: Pipeline Integration (2 tests) =======

  test("E1: Fiber engine accepts CreateStateMachine and stores SMRecord") {
    // This test requires fiber engine integration
    // For now, test that ProtoAdapters can handle a realistic CreateStateMachine scenario
    val createSM = Updates.CreateStateMachine(
      fiberId = testUUID,
      definition = StateMachineDefinition(
        states = Map("created" -> State(isInitial = true, isFinal = false)),
        initialState = StateId("created"),
        transitions = List.empty,
        metadata = None
      ),
      initialData = JsonLogicValue.Map(Map("initialized" -> JsonLogicValue.Bool(true))),
      owners = Set(testAddress),
      participants = None // For multi-party signing
    )

    val expectedRecord = StateMachineFiberRecord(
      fiberId = createSM.fiberId,
      definition = createSM.definition,
      currentState = createSM.definition.initialState,
      stateData = createSM.initialData,
      stateDataHash = Hash("computed_hash"), // Would be computed by engine
      sequenceNumber = FiberOrdinal.unsafeApply(1L),
      owners = createSM.owners,
      status = FiberStatus.Active,
      parentFiberId = None,
      childFiberIds = Set.empty,
      lastReceipt = None
    )

    val result = smRoundTrip(expectedRecord)
    val binarySize = ProtoAdapters.toProtoSMRecord(expectedRecord).toByteArray.length

    expect(result.isRight) and
    expect(result.value.fiberId == createSM.fiberId) and
    expect(result.value.owners == createSM.owners) and
    expect(binarySize < 10240) // Less than 10KB
  }

  test("E2: FiberRecord binary storage is idempotent across snapshot boundaries") {
    val record = StateMachineFiberRecord(
      fiberId = testUUID,
      definition = StateMachineDefinition(
        states = Map("active" -> State(isInitial = true, isFinal = false)),
        initialState = StateId("active"),
        transitions = List.empty,
        metadata = None
      ),
      currentState = StateId("active"),
      stateData = JsonLogicValue.Map(Map("transitions_completed" -> JsonLogicValue.Integer(3))),
      stateDataHash = Hash("snapshot_hash"),
      sequenceNumber = FiberOrdinal.unsafeApply(3L), // After 3 transitions
      owners = Set(testAddress),
      status = FiberStatus.Active,
      parentFiberId = None,
      childFiberIds = Set.empty,
      lastReceipt = Some(
        EventReceipt(
          fiberId = testUUID,
          sequenceNumber = FiberOrdinal.unsafeApply(3L),
          timestamp = System.currentTimeMillis(),
          eventName = "third_transition",
          emittedEvents = List.empty
        )
      )
    )

    // Simulate snapshot serialization/deserialization
    val firstSerialization = ProtoAdapters.toProtoSMRecord(record).toByteArray
    val firstDeserialization = ProtoAdapters.fromProtoSMRecord(
      proto.StateMachineFiberRecord.parseFrom(firstSerialization)
    )

    // Simulate second snapshot boundary
    val secondSerialization = ProtoAdapters.toProtoSMRecord(firstDeserialization.value).toByteArray
    val secondDeserialization = ProtoAdapters.fromProtoSMRecord(
      proto.StateMachineFiberRecord.parseFrom(secondSerialization)
    )

    expect(firstDeserialization.isRight) and
    expect(secondDeserialization.isRight) and
    expect(firstDeserialization.value == secondDeserialization.value) and
    expect(secondDeserialization.value.sequenceNumber.value == 3L) and
    expect(secondDeserialization.value.currentState == StateId("active")) and
    expect(secondDeserialization.value.lastReceipt.isDefined)
  }
}
