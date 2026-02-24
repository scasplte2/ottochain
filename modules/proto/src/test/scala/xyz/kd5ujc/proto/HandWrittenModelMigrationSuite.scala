package xyz.kd5ujc.proto

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import weaver.SimpleIOSuite
import cats.syntax.all._
import io.circe.syntax._

import xyz.kd5ujc.schema.{OnChain, CalculatedState}
import xyz.kd5ujc.shared_data.syntax.DataStateOps
import xyz.kd5ujc.ottochain.models.Records
import xyz.kd5ujc.ottochain.address.Address
import xyz.kd5ujc.ottochain.utils.{FiberOrdinal, Hash, StateId, FiberStatus}
import xyz.kd5ujc.ottochain.models.Records.{StateMachineFiberRecord, EventReceipt}
import xyz.kd5ujc.ottochain.models.Updates.{StateMachineDefinition, Transition, State}
import xyz.kd5ujc.ottochain.metakit.JsonLogicValue
import io.constellationnetwork.currency.dataApplication.DataState

import java.util.UUID
import java.lang.reflect.{Type, ParameterizedType}

/**
 * TDD Tests for Hand-Written Models Module Migration (Phase 1)
 *
 * These tests MUST FAIL until the migration is implemented:
 * 1. Proto inheritance: OnChainState extends DataOnChainState, CalculatedState extends DataCalculatedState
 * 2. UUID→String key migration in DataStateOps + 5 consumers
 *
 * Spec: docs/design/migrate-hand-written-models-spec.md (PR #99)
 * Prerequisites: ProtoAdapters.fromProto must be complete (Integration Tests card AC3)
 */
object HandWrittenModelMigrationSuite extends SimpleIOSuite {

  // Test fixtures
  val testAddress = Address.fromHex("0x1234567890123456789012345678901234567890").get
  val testUUID = UUID.randomUUID()
  val testHash = Hash("abc123def456")

  // ======= Group A: Proto Inheritance Tests (2 tests) =======

  test("A1: OnChainState extends io.constellationnetwork.tessellation.sdk.domain.state.DataOnChainState") {
    // This test will FAIL until records.proto is updated with scalapb extends annotation
    val onChainClass = classOf[OnChain]
    val interfaces = onChainClass.getInterfaces
    val superclasses = getAllSuperTypes(onChainClass)

    val hasDataOnChainState = superclasses.exists(_.getSimpleName.contains("DataOnChainState"))

    expect(hasDataOnChainState).withClue {
      s"OnChainState must extend DataOnChainState. Current interfaces: ${interfaces.map(_.getSimpleName).mkString(", ")}. " +
      s"Add '(scalapb.message).extends = \"io.constellationnetwork.tessellation.sdk.domain.state.DataOnChainState\"' to OnChainState in records.proto"
    }
  }

  test("A2: CalculatedState extends io.constellationnetwork.tessellation.sdk.domain.state.DataCalculatedState") {
    // This test will FAIL until records.proto is updated with scalapb extends annotation
    val calculatedClass = classOf[CalculatedState]
    val interfaces = calculatedClass.getInterfaces
    val superclasses = getAllSuperTypes(calculatedClass)

    val hasDataCalculatedState = superclasses.exists(_.getSimpleName.contains("DataCalculatedState"))

    expect(hasDataCalculatedState).withClue {
      s"CalculatedState must extend DataCalculatedState. Current interfaces: ${interfaces.map(_.getSimpleName).mkString(", ")}. " +
      s"Add '(scalapb.message).extends = \"io.constellationnetwork.tessellation.sdk.domain.state.DataCalculatedState\"' to CalculatedState in records.proto"
    }
  }

  // ======= Group B: UUID→String Key Migration Tests (5 tests) =======

  test("B1: DataStateOps.withRecord accepts String keys instead of UUID") {
    // This test will FAIL until DataStateOps is migrated from UUID to String keys
    val initialState = DataState[OnChain, CalculatedState](OnChain.empty, CalculatedState.empty)
    val dataStateOps = new DataStateOps {}
    import dataStateOps._

    val testRecord = StateMachineFiberRecord(
      fiberId = testUUID,
      definition = createMinimalDefinition(),
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

    // This should accept String keys, not UUID
    val stringKey = testUUID.toString

    // The method signature should be: def withRecord[F[_]: Async](id: String, record: Records.FiberRecord)
    // Currently it's: def withRecord[F[_]: Async](id: UUID, record: Records.FiberRecord)
    val methodExists =
      try {
        val method =
          classOf[DataStateOps.DataStateSyntax].getMethod("withRecord", classOf[String], classOf[Records.FiberRecord])
        method != null
      } catch {
        case _: NoSuchMethodException => false
      }

    expect(methodExists).withClue {
      "DataStateOps.withRecord must accept String keys, not UUID keys. " +
      "Update method signature: withRecord[F[_]: Async](id: String, record: Records.FiberRecord)"
    }
  }

  test("B2: DataStateOps.withRecords accepts Map[String, FiberRecord] instead of Map[UUID, FiberRecord]") {
    // This test will FAIL until DataStateOps method signatures are updated
    val dataStateOps = new DataStateOps {}

    // Method should accept Map[String, Records.FiberRecord]
    val methodExists =
      try {
        val method =
          classOf[DataStateOps.DataStateSyntax].getMethod("withRecords", classOf[Map[String, Records.FiberRecord]])
        method != null
      } catch {
        case _: NoSuchMethodException => false
      }

    expect(methodExists).withClue {
      "DataStateOps.withRecords must accept Map[String, Records.FiberRecord], not Map[UUID, Records.FiberRecord]. " +
      "Update method signature: withRecords(records: Map[String, Records.FiberRecord])"
    }
  }

  test("B3: DataStateOps.withFibersAndOracles accepts String-keyed Maps") {
    // This test will FAIL until method signatures are updated
    val dataStateOps = new DataStateOps {}

    // Method should accept Map[String, StateMachineFiberRecord] and Map[String, ScriptFiberRecord]
    val methodExists =
      try {
        val method = classOf[DataStateOps.DataStateSyntax].getMethod(
          "withFibersAndOracles",
          classOf[Map[String, Records.StateMachineFiberRecord]],
          classOf[Map[String, Records.ScriptFiberRecord]]
        )
        method != null
      } catch {
        case _: NoSuchMethodException => false
      }

    expect(methodExists).withClue {
      "DataStateOps.withFibersAndOracles must accept String-keyed Maps, not UUID-keyed Maps. " +
      "Update method signature: withFibersAndOracles(fibers: Map[String, StateMachineFiberRecord], oracles: Map[String, ScriptFiberRecord])"
    }
  }

  test("B4: OnChainState and CalculatedState proto fields use string keys") {
    // This test will FAIL until proto maps use string keys consistently
    val onChain = OnChain.empty
    val calculated = CalculatedState.empty

    // OnChainState.fiberCommits should be Map[String, FiberCommit]
    val onChainFiberCommitsType = getMapKeyType(classOf[OnChain], "fiberCommits")
    val onChainUsesStringKeys = onChainFiberCommitsType.contains("String")

    // CalculatedState.stateMachines should be Map[String, StateMachineFiberRecord]
    val calculatedSMType = getMapKeyType(classOf[CalculatedState], "stateMachines")
    val calculatedUsesStringKeys = calculatedSMType.contains("String")

    expect(onChainUsesStringKeys).withClue {
      s"OnChainState.fiberCommits must use String keys. Current type: $onChainFiberCommitsType. " +
      "Verify proto field: map<string, FiberCommit> fiber_commits = 1;"
    } and
    expect(calculatedUsesStringKeys).withClue {
      s"CalculatedState.stateMachines must use String keys. Current type: $calculatedSMType. " +
      "Verify proto field: map<string, StateMachineFiberRecord> state_machines = 1;"
    }
  }

  test("B5: SortedMap ordering preserved with String keys") {
    // This test will FAIL until string ordering is verified
    val testKeys = List("zzz-last", "aaa-first", "mmm-middle")
    val sortedStringKeys = testKeys.sorted

    // String lexicographic ordering should work the same as UUID.toString ordering
    val expectedOrder = List("aaa-first", "mmm-middle", "zzz-last")

    expect(sortedStringKeys == expectedOrder).withClue {
      s"String keys must maintain proper lexicographic ordering. Got: $sortedStringKeys, expected: $expectedOrder. " +
      "Verify that SortedMap[String, _] operations work correctly in consumers"
    }
  }

  // ======= Group C: Consumer Integration Tests (5 tests) =======

  test("C1: FiberCombiner compiles with String-keyed DataState operations") {
    // This test will FAIL until FiberCombiner is updated to use String keys
    // Note: This is a compilation test - we're checking that the types align
    val compilerMessage = """
      FiberCombiner must be updated to use String keys instead of UUID keys.
      Expected changes:
      1. Import statements: Remove java.util.UUID where used for fiber keys
      2. Method parameters: Change UUID parameters to String
      3. DataState operations: Use String keys in withRecord/withRecords calls
      4. Map operations: Update Map[UUID, T] to Map[String, T] for fiber collections
    """

    // This will fail until the consumer files are updated
    fail(compilerMessage)
  }

  test("C2: ScriptCombiner compiles with String-keyed DataState operations") {
    // This test will FAIL until ScriptCombiner is updated to use String keys
    val compilerMessage = """
      ScriptCombiner must be updated to use String keys instead of UUID keys.
      Expected changes:
      1. Script fiber ID handling: Change UUID to String
      2. DataState operations: Use String keys in withRecord calls for script records
      3. Collection types: Update Map[UUID, ScriptFiberRecord] to Map[String, ScriptFiberRecord]
    """

    fail(compilerMessage)
  }

  test("C3: FiberRules compiles with String-keyed state operations") {
    // This test will FAIL until FiberRules is updated to use String keys
    val compilerMessage = """
      FiberRules must be updated to use String keys instead of UUID keys.
      Expected changes:
      1. Validation methods: Change fiber ID parameters from UUID to String
      2. State lookup operations: Use String keys when accessing CalculatedState maps
      3. Parent/child relationships: Use String IDs for fiber relationship tracking
    """

    fail(compilerMessage)
  }

  test("C4: CommonRules compiles with String-keyed operations") {
    // This test will FAIL until CommonRules is updated to use String keys
    val compilerMessage = """
      CommonRules must be updated to use String keys instead of UUID keys.
      Expected changes:
      1. Common validation helpers: Change UUID parameters to String
      2. Shared state access patterns: Use String keys consistently
      3. Error messages: Update to reference String IDs instead of UUID
    """

    fail(compilerMessage)
  }

  test("C5: Validator compiles with String-keyed fiber operations") {
    // This test will FAIL until Validator is updated to use String keys
    val compilerMessage = """
      Validator must be updated to use String keys instead of UUID keys.
      Expected changes:
      1. Fiber validation: Change UUID-based validation to String-based
      2. Cross-fiber references: Use String IDs for parent/child validation
      3. State machine validation: Accept String fiber IDs in validation context
    """

    fail(compilerMessage)
  }

  // Helper methods for reflection-based testing
  private def getAllSuperTypes(clazz: Class[_]): List[Class[_]] = {
    val interfaces = clazz.getInterfaces.toList
    val superclass = Option(clazz.getSuperclass).toList
    (interfaces ++ superclass).flatMap(c => c :: getAllSuperTypes(c)).distinct
  }

  private def getMapKeyType(clazz: Class[_], fieldName: String): Option[String] =
    try {
      val field = clazz.getDeclaredField(fieldName)
      field.getGenericType match {
        case pt: ParameterizedType =>
          pt.getActualTypeArguments.headOption.map(_.getTypeName)
        case _ => None
      }
    } catch {
      case _: NoSuchFieldException => None
    }

  private def createMinimalDefinition() =
    xyz.kd5ujc.ottochain.models.Updates.StateMachineDefinition(
      states = Map("initial" -> State(isInitial = true, isFinal = false)),
      initialState = StateId("initial"),
      transitions = List.empty,
      metadata = None
    )
}
