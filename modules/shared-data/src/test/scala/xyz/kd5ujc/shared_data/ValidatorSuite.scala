package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext, L1NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.JsonLogicOp
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, Validator}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

object ValidatorSuite extends SimpleIOSuite {

  // ============== Test Fixtures ==============

  object Fixtures {

    def minimalDefinition(): StateMachineDefinition = {
      val initial = StateId("initial")
      StateMachineDefinition(
        states = Map(initial -> State(initial, isFinal = false)),
        initialState = initial,
        transitions = List.empty
      )
    }

    def simpleDefinitionWithTransition(): StateMachineDefinition = {
      val stateA = StateId("stateA")
      val stateB = StateId("stateB")
      StateMachineDefinition(
        states = Map(
          stateA -> State(stateA, isFinal = false),
          stateB -> State(stateB, isFinal = false)
        ),
        initialState = stateA,
        transitions = List(
          Transition(
            from = stateA,
            to = stateB,
            eventName = "advance",
            guard = ConstExpression(BoolValue(true)),
            effect = ConstExpression(MapValue(Map("moved" -> BoolValue(true)))),
            dependencies = Set.empty
          )
        )
      )
    }

    def definitionWithStates(count: Int): StateMachineDefinition = {
      val states = (1 to count).map(i => StateId(s"state$i"))
      val stateMap = states.map(s => s -> State(s, isFinal = false)).toMap
      StateMachineDefinition(
        states = stateMap,
        initialState = states.head,
        transitions = List.empty
      )
    }

    def definitionWithTransitions(count: Int): StateMachineDefinition = {
      // Distribute transitions across multiple states to stay within MaxTransitionsPerState limit (20)
      val maxPerState = 20
      val numStates = Math.max(2, (count / maxPerState) + 2)
      val states = (1 to numStates).map(i => StateId(s"state$i"))
      val stateMap = states.map(s => s -> State(s, isFinal = false)).toMap

      val transitions = (1 to count).map { i =>
        val fromIdx = ((i - 1) / maxPerState) % (numStates - 1)
        val toIdx = (fromIdx + 1)             % numStates
        Transition(
          from = states(fromIdx),
          to = states(toIdx),
          eventName = s"event$i",
          guard = ConstExpression(BoolValue(true)),
          effect = ConstExpression(MapValue(Map.empty)),
          dependencies = Set.empty
        )
      }.toList

      StateMachineDefinition(
        states = stateMap,
        initialState = states.head,
        transitions = transitions
      )
    }

    def definitionWithTransitionsPerState(perState: Int): StateMachineDefinition = {
      val state1 = StateId("state1")
      val state2 = StateId("state2")
      val transitions = (1 to perState).map { i =>
        Transition(
          from = state1,
          to = state2,
          eventName = s"event$i",
          guard = ConstExpression(BoolValue(true)),
          effect = ConstExpression(MapValue(Map.empty)),
          dependencies = Set.empty
        )
      }.toList
      StateMachineDefinition(
        states = Map(state1 -> State(state1, isFinal = false), state2 -> State(state2, isFinal = false)),
        initialState = state1,
        transitions = transitions
      )
    }

    def emptyDefinition(): StateMachineDefinition =
      StateMachineDefinition(
        states = Map.empty,
        initialState = StateId("nonexistent"),
        transitions = List.empty
      )

    def invalidInitialStateDefinition(): StateMachineDefinition = {
      val existing = StateId("existing")
      StateMachineDefinition(
        states = Map(existing -> State(existing, isFinal = false)),
        initialState = StateId("nonexistent"),
        transitions = List.empty
      )
    }

    def invalidTransitionFromDefinition(): StateMachineDefinition = {
      val stateA = StateId("stateA")
      StateMachineDefinition(
        states = Map(stateA -> State(stateA, isFinal = false)),
        initialState = stateA,
        transitions = List(
          Transition(
            from = StateId("nonexistent"),
            to = stateA,
            eventName = "test",
            guard = ConstExpression(BoolValue(true)),
            effect = ConstExpression(MapValue(Map.empty)),
            dependencies = Set.empty
          )
        )
      )
    }

    def invalidTransitionToDefinition(): StateMachineDefinition = {
      val stateA = StateId("stateA")
      StateMachineDefinition(
        states = Map(stateA -> State(stateA, isFinal = false)),
        initialState = stateA,
        transitions = List(
          Transition(
            from = stateA,
            to = StateId("nonexistent"),
            eventName = "test",
            guard = ConstExpression(BoolValue(true)),
            effect = ConstExpression(MapValue(Map.empty)),
            dependencies = Set.empty
          )
        )
      )
    }

    def duplicateTransitionsDefinition(): StateMachineDefinition = {
      val stateA = StateId("stateA")
      val stateB = StateId("stateB")
      val transition = Transition(
        from = stateA,
        to = stateB,
        eventName = "test",
        guard = ConstExpression(BoolValue(true)),
        effect = ConstExpression(MapValue(Map.empty)),
        dependencies = Set.empty
      )
      StateMachineDefinition(
        states = Map(stateA -> State(stateA, isFinal = false), stateB -> State(stateB, isFinal = false)),
        initialState = stateA,
        transitions = List(transition, transition)
      )
    }

    def ambiguousTransitionsDefinition(): StateMachineDefinition = {
      val stateA = StateId("stateA")
      val stateB = StateId("stateB")
      val stateC = StateId("stateC")
      StateMachineDefinition(
        states = Map(
          stateA -> State(stateA, isFinal = false),
          stateB -> State(stateB, isFinal = false),
          stateC -> State(stateC, isFinal = false)
        ),
        initialState = stateA,
        transitions = List(
          Transition(
            from = stateA,
            to = stateB,
            eventName = "test",
            guard = ConstExpression(BoolValue(true)),
            effect = ConstExpression(MapValue(Map.empty)),
            dependencies = Set.empty
          ),
          Transition(
            from = stateA,
            to = stateC,
            eventName = "test",
            guard = ConstExpression(BoolValue(true)),
            effect = ConstExpression(MapValue(Map.empty)),
            dependencies = Set.empty
          )
        )
      )
    }

    def simpleScript(): JsonLogicExpression =
      ConstExpression(MapValue(Map("result" -> IntValue(42))))

    /** Definition with a reserved operator name used as a field in guard */
    def definitionWithReservedOperatorInGuard(): StateMachineDefinition = {
      val stateA = StateId("stateA")
      val stateB = StateId("stateB")
      StateMachineDefinition(
        states = Map(
          stateA -> State(stateA, isFinal = false),
          stateB -> State(stateB, isFinal = false)
        ),
        initialState = stateA,
        transitions = List(
          Transition(
            from = stateA,
            to = stateB,
            eventName = "test",
            // Using "count" as a field name - this collides with the count operator
            guard = ApplyExpression(
              JsonLogicOp.Geq,
              List(
                MapExpression(Map("count" -> VarExpression(Left("items")))),
                ConstExpression(IntValue(2))
              )
            ),
            effect = ConstExpression(MapValue(Map.empty)),
            dependencies = Set.empty
          )
        )
      )
    }

    /** Definition with a reserved operator name used as a field in effect */
    def definitionWithReservedOperatorInEffect(): StateMachineDefinition = {
      val stateA = StateId("stateA")
      val stateB = StateId("stateB")
      StateMachineDefinition(
        states = Map(
          stateA -> State(stateA, isFinal = false),
          stateB -> State(stateB, isFinal = false)
        ),
        initialState = stateA,
        transitions = List(
          Transition(
            from = stateA,
            to = stateB,
            eventName = "test",
            guard = ConstExpression(BoolValue(true)),
            // Using "merge" as a field name - this collides with the merge operator
            effect = MapExpression(Map("merge" -> VarExpression(Left("data")))),
            dependencies = Set.empty
          )
        )
      )
    }
  }

  // ============== fiberId Not Used Tests (L1) ==============

  test("fiberIdNotUsed: new fiber ID accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.minimalDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("fiberIdNotUsed: duplicate fiber ID rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates.CreateStateMachine(fiberId, Fixtures.minimalDefinition(), MapValue(Map.empty))
        proof <- fixture.registry.generateProofs(createUpdate, Set(Alice))

        inState = DataState(OnChain.genesis, CalculatedState.genesis)
        stateAfterCreate <- combiner.insert(inState, Signed(createUpdate, proof))

        // Now try to validate creating the same fiber again - L0 level with existing state
        result <- validator.validateSignedUpdate(stateAfterCreate, Signed(createUpdate, proof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("already exists"))))
    }
  }

  // ============== Valid State Machine Definition Tests (L1) ==============

  test("validStateMachineDefinition: empty definition rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.emptyDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("no states"))))
    }
  }

  test("validStateMachineDefinition: invalid initial state rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.invalidInitialStateDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("initial state"))))
    }
  }

  test("validStateMachineDefinition: invalid transition 'from' state rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.invalidTransitionFromDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("invalid from state"))))
    }
  }

  test("validStateMachineDefinition: invalid transition 'to' state rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.invalidTransitionToDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("invalid to state"))))
    }
  }

  test("validStateMachineDefinition: duplicate transitions rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.duplicateTransitionsDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("duplicate"))))
    }
  }

  test("validStateMachineDefinition: ambiguous transitions rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.ambiguousTransitionsDefinition(), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("ambiguous"))))
    }
  }

  // ============== Reserved Operator Field Name Tests (L1) ==============

  test("noReservedOperatorFieldNames: reserved operator in guard rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(
          fiberId,
          Fixtures.definitionWithReservedOperatorInGuard(),
          MapValue(Map.empty)
        )
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("reserved")))) and
      expect(result.swap.exists(_.exists(_.message.contains("count"))))
    }
  }

  test("noReservedOperatorFieldNames: reserved operator in effect rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(
          fiberId,
          Fixtures.definitionWithReservedOperatorInEffect(),
          MapValue(Map.empty)
        )
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("reserved")))) and
      expect(result.swap.exists(_.exists(_.message.contains("merge"))))
    }
  }

  test("noReservedOperatorFieldNames: valid field names accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        // simpleDefinitionWithTransition uses "moved" as a field name which is not reserved
        update = Updates.CreateStateMachine(
          fiberId,
          Fixtures.simpleDefinitionWithTransition(),
          MapValue(Map.empty)
        )
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  // ============== Initial Data Is MapValue Tests (L1) ==============

  test("initialDataIsMapValue: MapValue accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates
          .CreateStateMachine(fiberId, Fixtures.minimalDefinition(), MapValue(Map("key" -> StrValue("value"))))
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("initialDataIsMapValue: non-MapValue rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.minimalDefinition(), ArrayValue(List(IntValue(1))))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("mapvalue"))))
    }
  }

  // ============== fiberId Is Found Tests (L1) ==============

  test("fiberIdIsFound: existing fiber ID accepted for ProcessFiberEvent") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachine(fiberId, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isValid)
    }
  }

  test("fiberIdIsFound: non-existent fiber ID rejected for ProcessFiberEvent") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        processUpdate = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result <- validator
          .validateSignedUpdate(
            DataState(OnChain.genesis, CalculatedState.genesis),
            Signed(processUpdate, processProof)
          )
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("not found"))))
    }
  }

  // ============== Event Payload Is Valid Tests (L1) ==============

  test("eventPayloadIsValid: non-null payload accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachine(fiberId, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates
          .TransitionStateMachine(fiberId, "advance", MapValue(Map("data" -> IntValue(123))), FiberOrdinal.MinValue)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isValid)
    }
  }

  test("eventPayloadIsValid: null payload rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachine(fiberId, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates.TransitionStateMachine(fiberId, "advance", NullValue, FiberOrdinal.MinValue)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("null"))))
    }
  }

  // ============== Fiber Is Active Tests (L0) ==============

  test("fiberIsActive: active fiber accepts events") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachine(fiberId, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isValid)
    }
  }

  // audit M1 / CLAUDE.md rule #3: the block-acceptance gate no longer reads the mutable fiber status
  // (`fiberIsActive`). A transition against an archived fiber PASSES `validateSignedUpdate` — otherwise a
  // concurrent same-fiber archive would flip it Invalid at ML0 re-validation and poison the WHOLE DL1 block.
  // The combiner is the authoritative gate: the engine aborts the non-active fiber and does not advance it.
  test("archived fiber: transition passes validateSignedUpdate (no block poisoning); combiner does not advance it") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachine(fiberId, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        archiveUpdate = Updates.ArchiveStateMachine(fiberId, FiberOrdinal.MinValue)
        archiveProof      <- fixture.registry.generateProofs(archiveUpdate, Set(Alice))
        stateAfterArchive <- combiner.insert(stateAfterCreate, Signed(archiveUpdate, archiveProof))

        processUpdate = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        // ML0 block-acceptance gate: no longer rejects on mutable status (would poison the block)
        gateResult <- validator.validateSignedUpdate(stateAfterArchive, Signed(processUpdate, processProof))
        // combiner (authoritative): engine aborts the archived fiber — status stays Archived, no state advance
        combined <- combiner.insert(stateAfterArchive, Signed(processUpdate, processProof))
        fiber = combined.calculated.stateMachines.get(fiberId)
      } yield expect(gateResult.isValid) and
      expect(fiber.map(_.status).contains(FiberStatus.Archived)) and
      expect(fiber.map(_.currentState).contains(StateId("stateA")))
    }
  }

  // ============== Update Signed By Owners Tests (L0) ==============

  test("updateSignedByOwners: owner signature accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachine(fiberId, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isValid)
    }
  }

  test("updateSignedByOwners: non-owner signature rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachine(fiberId, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Bob))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("owner"))))
    }
  }

  // ============== Transition Exists Tests (L0) ==============

  test("transitionExists: valid transition accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachine(fiberId, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
      } yield expect(result.isValid)
    }
  }

  // audit M1: the block-acceptance gate no longer reads `transitionExists` (a mutable read — a concurrent
  // same-fiber state advance can change which `(currentState,event)` resolves). An undefined-transition event
  // PASSES `validateSignedUpdate`; the combiner's engine aborts it (NoTransitionFound) without advancing.
  test("undefined transition: passes validateSignedUpdate (no block poisoning); combiner does not advance it") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachine(fiberId, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        processUpdate = Updates
          .TransitionStateMachine(fiberId, "nonexistent", MapValue(Map.empty), FiberOrdinal.MinValue)
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        gateResult   <- validator.validateSignedUpdate(stateAfterCreate, Signed(processUpdate, processProof))
        combined     <- combiner.insert(stateAfterCreate, Signed(processUpdate, processProof))
        fiber = combined.calculated.stateMachines.get(fiberId)
      } yield expect(gateResult.isValid) and
      expect(fiber.map(_.currentState).contains(StateId("stateA")))
    }
  }

  // audit M1: a STALE-sequence transition (target seq behind the fiber's current seq) must PASS the gate —
  // a concurrent same-fiber advance that lands first would otherwise flip it Invalid and poison the block.
  // Replay protection is preserved by the combiner's exact-sequence check + atomic bump (graceful
  // CombineRejected -> RejectionReceipt), which leaves the fiber untouched.
  test("stale-sequence transition: passes validateSignedUpdate (no block poisoning); combiner rejects gracefully") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        createUpdate = Updates
          .CreateStateMachine(fiberId, Fixtures.simpleDefinitionWithTransition(), MapValue(Map.empty))
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        // advance once: fiber moves stateA -> stateB and its sequence number bumps past MinValue
        advance = Updates.TransitionStateMachine(fiberId, "advance", MapValue(Map.empty), FiberOrdinal.MinValue)
        advanceProof      <- fixture.registry.generateProofs(advance, Set(Alice))
        stateAfterAdvance <- combiner.insert(stateAfterCreate, Signed(advance, advanceProof))

        // replay the SAME (now stale, target=MinValue) transition against the advanced state
        gateResult <- validator.validateSignedUpdate(stateAfterAdvance, Signed(advance, advanceProof))
        combined   <- combiner.insert(stateAfterAdvance, Signed(advance, advanceProof))
        rejections = combined.onChain.latestLogs.values.flatten.collect { case r: FiberLogEntry.RejectionReceipt =>
          r.reason
        }.toList
        fiber = combined.calculated.stateMachines.get(fiberId)
      } yield expect(gateResult.isValid) and
      expect(rejections.exists(_.toLowerCase.contains("sequence"))) and
      expect(fiber.map(_.currentState).contains(StateId("stateB")))
    }
  }

  // ============== Script Initial State Tests (L1) ==============

  test("initialStateIsMapValueOrNull: None accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates
          .CreateScript(fiberId, Fixtures.simpleScript(), None, AccessControlPolicy.Public)
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("initialStateIsMapValueOrNull: MapValue accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateScript(
          fiberId,
          Fixtures.simpleScript(),
          Some(MapValue(Map("counter" -> IntValue(0)))),
          AccessControlPolicy.Public
        )
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("initialStateIsMapValueOrNull: non-MapValue rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateScript(
          fiberId,
          Fixtures.simpleScript(),
          Some(ArrayValue(List(IntValue(1)))),
          AccessControlPolicy.Public
        )
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("mapvalue"))))
    }
  }

  // ============== Parent Fiber Validation Tests ==============

  test("parentFiberExistsInOnChain: non-existent parent rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        parentId  <- UUIDGen.randomUUID[IO]

        update = Updates.CreateStateMachine(fiberId, Fixtures.minimalDefinition(), MapValue(Map.empty), Some(parentId))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.contains("Parent fiber"))))
    }
  }

  test("parentFiberActive: archived parent rejected") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        parentId  <- UUIDGen.randomUUID[IO]
        childId   <- UUIDGen.randomUUID[IO]

        createParent = Updates.CreateStateMachine(parentId, Fixtures.minimalDefinition(), MapValue(Map.empty))
        createParentProof <- fixture.registry.generateProofs(createParent, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createParent, createParentProof))

        archiveParent = Updates.ArchiveStateMachine(parentId, FiberOrdinal.MinValue)
        archiveProof      <- fixture.registry.generateProofs(archiveParent, Set(Alice))
        stateAfterArchive <- combiner.insert(stateAfterCreate, Signed(archiveParent, archiveProof))

        createChild = Updates
          .CreateStateMachine(childId, Fixtures.minimalDefinition(), MapValue(Map.empty), Some(parentId))
        createChildProof <- fixture.registry.generateProofs(createChild, Set(Alice))
        result           <- validator.validateSignedUpdate(stateAfterArchive, Signed(createChild, createChildProof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("not active"))))
    }
  }

  test("parentFiberActive: active parent accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        parentId  <- UUIDGen.randomUUID[IO]
        childId   <- UUIDGen.randomUUID[IO]

        createParent = Updates.CreateStateMachine(parentId, Fixtures.minimalDefinition(), MapValue(Map.empty))
        createParentProof <- fixture.registry.generateProofs(createParent, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createParent, createParentProof))

        createChild = Updates
          .CreateStateMachine(childId, Fixtures.minimalDefinition(), MapValue(Map.empty), Some(parentId))
        createChildProof <- fixture.registry.generateProofs(createChild, Set(Alice))
        result           <- validator.validateSignedUpdate(stateAfterCreate, Signed(createChild, createChildProof))
      } yield expect(result.isValid)
    }
  }

  // ============== Script Access Control Tests (L0) ==============

  test("scriptAccessControlCheck: public policy allows any caller") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        scriptId  <- UUIDGen.randomUUID[IO]

        createScript = Updates
          .CreateScript(scriptId, Fixtures.simpleScript(), None, AccessControlPolicy.Public)
        createProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createScript, createProof))

        invokeScript = Updates.InvokeScript(scriptId, "test", MapValue(Map.empty), FiberOrdinal.MinValue)
        invokeProof <- fixture.registry.generateProofs(invokeScript, Set(Bob))
        result      <- validator.validateSignedUpdate(stateAfterCreate, Signed(invokeScript, invokeProof))
      } yield expect(result.isValid)
    }
  }

  test("scriptAccessControlCheck: whitelist allows authorized caller") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        scriptId  <- UUIDGen.randomUUID[IO]
        bobAddr   <- fixture.registry.addresses(Bob).pure[IO]

        createScript = Updates.CreateScript(
          scriptId,
          Fixtures.simpleScript(),
          None,
          AccessControlPolicy.Whitelist(Set(bobAddr))
        )
        createProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createScript, createProof))

        invokeScript = Updates.InvokeScript(scriptId, "test", MapValue(Map.empty), FiberOrdinal.MinValue)
        invokeProof <- fixture.registry.generateProofs(invokeScript, Set(Bob))
        result      <- validator.validateSignedUpdate(stateAfterCreate, Signed(invokeScript, invokeProof))
      } yield expect(result.isValid)
    }
  }

  test("scriptAccessControlCheck: whitelist denies unauthorized caller") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        scriptId  <- UUIDGen.randomUUID[IO]
        bobAddr   <- fixture.registry.addresses(Bob).pure[IO]

        createScript = Updates.CreateScript(
          scriptId,
          Fixtures.simpleScript(),
          None,
          AccessControlPolicy.Whitelist(Set(bobAddr))
        )
        createProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        stateAfterCreate <- combiner
          .insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createScript, createProof))

        invokeScript = Updates.InvokeScript(scriptId, "test", MapValue(Map.empty), FiberOrdinal.MinValue)
        invokeProof <- fixture.registry.generateProofs(invokeScript, Set(Charlie))
        result      <- validator.validateSignedUpdate(stateAfterCreate, Signed(invokeScript, invokeProof))
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("access denied"))))
    }
  }

  // ============== Definition Size Limit Tests (L1) ==============

  test("definitionWithinLimits: too many states rejected (>100)") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.definitionWithStates(101), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("too many states"))))
    }
  }

  test("definitionWithinLimits: exactly 100 states accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.definitionWithStates(100), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("definitionWithinLimits: too many transitions rejected (>500)") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.definitionWithTransitions(501), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("too many transitions"))))
    }
  }

  test("definitionWithinLimits: exactly 500 transitions accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates.CreateStateMachine(fiberId, Fixtures.definitionWithTransitions(500), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }

  test("definitionWithinLimits: too many transitions per state rejected (>20)") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates
          .CreateStateMachine(fiberId, Fixtures.definitionWithTransitionsPerState(21), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isInvalid) and
      expect(result.swap.exists(_.exists(_.message.toLowerCase.contains("too many transitions per state"))))
    }
  }

  test("definitionWithinLimits: exactly 20 transitions per state accepted") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val _l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]
        update = Updates
          .CreateStateMachine(fiberId, Fixtures.definitionWithTransitionsPerState(20), MapValue(Map.empty))
        result <- validator.validateUpdate(update)
      } yield expect(result.isValid)
    }
  }
}
