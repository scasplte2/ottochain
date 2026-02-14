package xyz.kd5ujc.shared_data.assets

import cats.effect.IO
import io.circe.Json
import weaver.SimpleIOSuite
import xyz.kd5ujc.shared_data.assets.AssetStateMachine._

/**
 * Test suite for DFA State Machine + JSON Logic Integration
 */
object AssetStateMachineSuite extends SimpleIOSuite {

  // ============== Test Fixtures ==============

  object Fixtures {

    def createBasicInstance(
      instanceId:   String = "instance-001",
      assetId:      String = "asset-123",
      currentState: String = "created"
    ): DFAInstance = DFAInstance(
      instanceId = instanceId,
      assetId = assetId,
      machineId = "basic_asset_lifecycle",
      currentState = currentState,
      stateData = Json.obj(
        "owner"     -> Json.fromString("DAG123456789"),
        "createdAt" -> Json.fromLong(System.currentTimeMillis() / 1000),
        "status"    -> Json.fromString("created")
      ),
      createdAt = System.currentTimeMillis() / 1000,
      updatedAt = System.currentTimeMillis() / 1000,
      variables = Map(
        "maxTransfersPerDay" -> Json.fromInt(10)
      )
    )

    def createActivationEvent(producerId: String = "producer-001"): StateEvent = StateEvent(
      eventId = "event-001",
      eventType = "activation_request",
      data = Json.obj(
        "requestedBy" -> Json.fromString(producerId),
        "reason"      -> Json.fromString("Initial activation")
      ),
      producerId = producerId,
      timestamp = System.currentTimeMillis() / 1000,
      signature = "signature-placeholder"
    )

    def createTransferEvent(
      producerId: String = "producer-001",
      to:         String = "DAG987654321"
    ): StateEvent = StateEvent(
      eventId = "event-002",
      eventType = "transfer_request",
      data = Json.obj(
        "to"     -> Json.fromString(to),
        "amount" -> Json.fromInt(100),
        "reason" -> Json.fromString("Asset transfer")
      ),
      producerId = producerId,
      timestamp = System.currentTimeMillis() / 1000,
      signature = "signature-placeholder"
    )

    def createContext(
      validatorAuthorityLevel: Int = 15,
      producerId:              String = "producer-001",
      validatorId:             String = "validator-001"
    ): Map[String, Json] = Map(
      "validator" -> Json.obj(
        "id"             -> Json.fromString(validatorId),
        "authorityLevel" -> Json.fromInt(validatorAuthorityLevel)
      ),
      "producer" -> Json.obj(
        "id" -> Json.fromString(producerId)
      ),
      "asset" -> Json.obj(
        "status" -> Json.fromString("active")
      )
    )
  }

  // ============== Tests ==============

  test("Basic Asset Lifecycle template should be valid") {
    val template = AssetLifecycleTemplates.BASIC_ASSET_LIFECYCLE
    val validationResult = template.validate

    expect(validationResult.isValid) and
    expect(validationResult.errors.isEmpty) and
    expect(validationResult.stateCount == 4) and
    expect(validationResult.transitionCount == 3) and
    expect(validationResult.hasInitialState) and
    expect(validationResult.hasTerminalStates)
  }

  test("State machine should identify reachable states correctly") {
    val template = AssetLifecycleTemplates.BASIC_ASSET_LIFECYCLE
    val reachableStates = computeReachableStates(template)

    expect(reachableStates.contains("created")) and
    expect(reachableStates.contains("active")) and
    expect(reachableStates.contains("transferred")) and
    expect(reachableStates.contains("burned"))
  }

  test("State machine should find transitions correctly") {
    val template = AssetLifecycleTemplates.BASIC_ASSET_LIFECYCLE

    val fromCreated = template.transitionsFrom("created")
    val toActive = template.transitionsTo("active")

    expect(fromCreated.length == 1) and
    expect(fromCreated.head.id == "activate") and
    expect(toActive.length == 1) and
    expect(toActive.head.id == "activate")
  }

  test("DFA Instance should update state data correctly") {
    val instance = Fixtures.createBasicInstance()
    val updates = Map(
      "status"      -> Json.fromString("active"),
      "activatedAt" -> Json.fromLong(System.currentTimeMillis() / 1000)
    )

    val updated = instance.updateStateData(updates)

    expect(updated.currentStateData("status") == Json.fromString("active")) and
    expect(updated.currentStateData.contains("activatedAt")) and
    expect(updated.currentStateData("owner") == Json.fromString("DAG123456789")) // original data preserved
  }

  test("Guard evaluation should work with basic JSON Logic") {
    val instance = Fixtures.createBasicInstance()
    val template = AssetLifecycleTemplates.BASIC_ASSET_LIFECYCLE
    val activateTransition = template.findTransition("activate").get
    val context = Fixtures.createContext(validatorAuthorityLevel = 15)

    val guardResults = StateMachineValidation.evaluateGuards(activateTransition, context, instance)

    expect(guardResults.length == 2) and
    expect(guardResults.forall(_.result)) // All guards should pass with authority level 15
  }

  test("Guard evaluation should fail with insufficient authority") {
    val instance = Fixtures.createBasicInstance()
    val template = AssetLifecycleTemplates.BASIC_ASSET_LIFECYCLE
    val activateTransition = template.findTransition("activate").get
    val context = Fixtures.createContext(validatorAuthorityLevel = 5) // Below required level 10

    val guardResults = StateMachineValidation.evaluateGuards(activateTransition, context, instance)

    // First guard (authority level check) should fail
    expect(guardResults.head.result == false)
  }

  test("Effect execution should update state properly") {
    val instance = Fixtures.createBasicInstance()
    val template = AssetLifecycleTemplates.BASIC_ASSET_LIFECYCLE
    val activateTransition = template.findTransition("activate").get
    val context = Fixtures.createContext() ++ Map(
      "event" -> Json.obj(
        "requestedBy" -> Json.fromString("producer-001")
      )
    )

    val effectResults = StateMachineValidation.executeEffects(activateTransition, context, instance)

    expect(effectResults.length == 1) and
    expect(effectResults.head.error.isEmpty) and
    expect(effectResults.head.stateChanges.contains("status")) and
    expect(effectResults.head.stateChanges.contains("activatedAt"))
  }

  test("State event should contain required fields") {
    val event = Fixtures.createActivationEvent()

    expect(event.eventType == "activation_request") and
    expect(event.producerId == "producer-001") and
    expect(event.data.asObject.isDefined) and
    expect(event.timestamp > 0) and
    expect(event.signature.nonEmpty)
  }

  test("Transition execution should be recorded correctly") {
    val instance = Fixtures.createBasicInstance()
    val event = Fixtures.createActivationEvent()
    val execution = TransitionExecution(
      executionId = "exec-001",
      transitionId = "activate",
      triggerEvent = event,
      producerId = "producer-001",
      validatorIds = List("validator-001"),
      executedAt = System.currentTimeMillis() / 1000,
      guardResults = List.empty,
      effectResults = List.empty,
      fromState = "created",
      toState = "active",
      stateChanges = Map("status" -> Json.fromString("active"))
    )

    val updated = instance.recordTransition(execution)

    expect(updated.currentState == "active") and
    expect(updated.transitionHistory.length == 1) and
    expect(updated.transitionHistory.head.transitionId == "activate") and
    expect(updated.updatedAt == execution.executedAt)
  }

  test("DOT visualization should be generated correctly") {
    val template = AssetLifecycleTemplates.BASIC_ASSET_LIFECYCLE
    val dotOutput = generateDOTVisualization(template)

    expect(dotOutput.contains("digraph")) and
    expect(dotOutput.contains("created")) and
    expect(dotOutput.contains("active")) and
    expect(dotOutput.contains("burned")) and
    expect(dotOutput.contains("->")) and
    expect(dotOutput.contains("Activate"))
  }

  test("Template registry should provide access to templates") {
    val availableTemplates = AssetLifecycleTemplates.getAvailableTemplates
    val basicTemplate = AssetLifecycleTemplates.getTemplate("basic_asset_lifecycle")

    expect(availableTemplates.nonEmpty) and
    expect(availableTemplates.contains("basic_asset_lifecycle")) and
    expect(basicTemplate.isDefined) and
    expect(basicTemplate.get.id == "basic_asset_lifecycle")
  }

  test("JSON Logic variable lookup should work with nested paths") {
    val validation = new StateMachineValidation {}
    val context = Map(
      "state" -> Json.obj(
        "owner" -> Json.fromString("DAG123456789"),
        "metadata" -> Json.obj(
          "createdBy" -> Json.fromString("producer-001")
        )
      )
    )

    val ownerLookup = validation.evaluateJsonLogic(
      Json.obj("var" -> Json.fromString("state.owner")),
      context
    )

    val createdByLookup = validation.evaluateJsonLogic(
      Json.obj("var" -> Json.fromString("state.metadata.createdBy")),
      context
    )

    expect(ownerLookup.contains(Json.fromString("DAG123456789"))) and
    expect(createdByLookup.contains(Json.fromString("producer-001")))
  }

  test("JSON Logic merge operation should combine objects correctly") {
    val validation = new StateMachineValidation {}
    val context = Map(
      "state" -> Json.obj(
        "owner"  -> Json.fromString("DAG123456789"),
        "status" -> Json.fromString("created")
      )
    )

    val mergeResult = validation.evaluateJsonLogic(
      Json.obj(
        "merge" -> Json.fromValues(
          List(
            Json.obj("var" -> Json.fromString("state")),
            Json.obj(
              "status"      -> Json.fromString("active"),
              "activatedAt" -> Json.fromLong(1234567890)
            )
          )
        )
      ),
      context
    )

    val expected = Json.obj(
      "owner"       -> Json.fromString("DAG123456789"),
      "status"      -> Json.fromString("active"),
      "activatedAt" -> Json.fromLong(1234567890)
    )

    expect(mergeResult.contains(expected))
  }

  test("Complete transition flow should work end-to-end") {
    val template = AssetLifecycleTemplates.BASIC_ASSET_LIFECYCLE
    val instance = Fixtures.createBasicInstance()
    val event = Fixtures.createActivationEvent()
    val context = Fixtures.createContext(validatorAuthorityLevel = 15)

    // Find the activation transition
    val activateTransition = template.findTransition("activate").get

    // Evaluate guards
    val guardResults = StateMachineValidation.evaluateGuards(activateTransition, context, instance)
    val allGuardsPassed = guardResults.forall(_.result)

    // Execute effects if guards pass
    val effectResults = if (allGuardsPassed) {
      StateMachineValidation.executeEffects(activateTransition, context, instance)
    } else {
      List.empty
    }

    // Create transition execution
    val execution = TransitionExecution(
      executionId = "exec-001",
      transitionId = activateTransition.id,
      triggerEvent = event,
      producerId = event.producerId,
      validatorIds = List("validator-001"),
      executedAt = System.currentTimeMillis() / 1000,
      guardResults = guardResults,
      effectResults = effectResults,
      fromState = instance.currentState,
      toState = activateTransition.toState,
      stateChanges = effectResults.flatMap(_.stateChanges).toMap
    )

    // Update instance
    val updatedInstance = instance.recordTransition(execution)

    expect(allGuardsPassed) and
    expect(effectResults.nonEmpty) and
    expect(updatedInstance.currentState == "active") and
    expect(updatedInstance.transitionHistory.length == 1) and
    expect(execution.guardResults.forall(_.error.isEmpty)) and
    expect(execution.effectResults.forall(_.error.isEmpty))
  }

  test("State machine validation should detect invalid configurations") {
    // Create an invalid state machine with missing initial state
    val invalidMachine = DFAStateMachine(
      id = "invalid_machine",
      name = "Invalid Machine",
      description = "A machine with validation issues",
      version = "1.0.0",
      assetType = "test",
      states = List(
        DFAState(id = "state1", name = "State 1", description = "First state"),
        DFAState(id = "state2", name = "State 2", description = "Second state")
      ),
      transitions = List(
        DFATransition(
          id = "trans1",
          fromState = "nonexistent", // Invalid from state
          toState = "state2",
          eventType = "test_event",
          name = "Test Transition"
        )
      ),
      initialState = "nonexistent", // Invalid initial state
      metadata = MachineMetadata(
        createdAt = System.currentTimeMillis() / 1000,
        createdBy = "test"
      )
    )

    val validationResult = invalidMachine.validate

    expect(!validationResult.isValid) and
    expect(validationResult.errors.nonEmpty) and
    expect(validationResult.errors.exists(_.contains("Initial state"))) and
    expect(validationResult.errors.exists(_.contains("fromState")))
  }
}
