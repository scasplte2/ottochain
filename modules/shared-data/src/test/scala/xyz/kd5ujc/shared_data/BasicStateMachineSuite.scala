package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object BasicStateMachineSuite extends SimpleIOSuite with Checkers {

  def createCounterStateMachine(): StateMachineDefinition = {
    val waitingState = StateId("waiting")
    val countingState = StateId("counting")
    val doneState = StateId("done")

    StateMachineDefinition(
      states = Map(
        waitingState  -> State(waitingState, isFinal = false),
        countingState -> State(countingState, isFinal = false),
        doneState     -> State(doneState, isFinal = true)
      ),
      initialState = waitingState,
      transitions = List(
        Transition(
          from = waitingState,
          to = countingState,
          eventName = "start",
          guard = ConstExpression(BoolValue(true)),
          effect = ConstExpression(
            MapValue(
              Map(
                "counter" -> IntValue(0),
                "active"  -> BoolValue(true)
              )
            )
          ),
          dependencies = Set.empty
        ),
        Transition(
          from = countingState,
          to = countingState,
          eventName = "increment",
          guard = ApplyExpression(
            Lt,
            List(
              VarExpression(Left("state.counter")),
              ConstExpression(IntValue(10))
            )
          ),
          effect = ArrayExpression(
            List(
              ArrayExpression(
                List(
                  ConstExpression(StrValue("counter")),
                  ApplyExpression(
                    AddOp,
                    List(
                      VarExpression(Left("state.counter")),
                      ConstExpression(IntValue(1))
                    )
                  )
                )
              ),
              ArrayExpression(
                List(
                  ConstExpression(StrValue("active")),
                  ConstExpression(BoolValue(true))
                )
              )
            )
          ),
          dependencies = Set.empty
        ),
        Transition(
          from = countingState,
          to = doneState,
          eventName = "finish",
          guard = ConstExpression(BoolValue(true)),
          effect = ConstExpression(
            ArrayValue(
              List(
                ArrayValue(
                  List(
                    StrValue("counter"),
                    IntValue(99)
                  )
                ),
                ArrayValue(
                  List(
                    StrValue("active"),
                    BoolValue(false)
                  )
                )
              )
            )
          ),
          dependencies = Set.empty
        )
      )
    )
  }

  test("create state machine fiber with initial state") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        update = Updates.CreateStateMachine(fiberId, definition, initialData)
        updateProof <- fixture.registry.generateProofs(update, Set(Alice, Bob))

        inState = DataState(OnChain.genesis, CalculatedState.genesis)
        outState <- combiner.insert(inState, Signed(update, updateProof))

        fiber = outState.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(fiber.isDefined) and
      expect(fiber.map(_.currentState).contains(StateId("waiting"))) and
      expect(fiber.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)) and
      expect(fiber.map(_.status).contains(FiberStatus.Active))
    }
  }

  test("process 'start' event transitions from waiting to counting") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()
        initialData = MapValue(Map.empty[String, JsonLogicValue])

        createUpdate = Updates.CreateStateMachine(fiberId, definition, initialData)
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice, Bob))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createUpdate, createProof)
        )

        seqAfterCreate = stateAfterCreate.calculated.stateMachines(fiberId).sequenceNumber
        processUpdate = Updates.TransitionStateMachine(
          fiberId,
          "start",
          MapValue(Map.empty[String, JsonLogicValue]),
          seqAfterCreate
        )
        processProof    <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        stateAfterStart <- combiner.insert(stateAfterCreate, Signed(processUpdate, processProof))

        fiber = stateAfterStart.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        counterValue: Option[BigInt] = fiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
            case _           => None
          }
        }
      } yield expect(fiber.isDefined) and
      expect(fiber.map(_.currentState).contains(StateId("counting"))) and
      expect(fiber.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(counterValue.contains(BigInt(0)))
    }
  }

  test("process 'increment' event increases counter") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()

        initialData = MapValue(
          Map(
            "counter" -> IntValue(5),
            "active"  -> BoolValue(true)
          )
        )
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](fiberId, fiber)

        processUpdate = Updates.TransitionStateMachine(
          fiberId,
          "increment",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.MinValue
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        outState     <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = outState.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        counterValue: Option[BigInt] = updatedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
            case _           => None
          }
        }
        activeValue: Option[Boolean] = updatedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("active").collect { case BoolValue(a) => a }
            case _           => None
          }
        }
      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateId("counting"))) and
      expect(updatedFiber.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(counterValue.contains(BigInt(6))) and
      expect(activeValue.contains(true))
    }
  }

  test("process 'finish' event transitions to done state") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()

        initialData = MapValue(
          Map(
            "counter" -> IntValue(7),
            "active"  -> BoolValue(true)
          )
        )
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](fiberId, fiber)

        processUpdate = Updates.TransitionStateMachine(
          fiberId,
          "finish",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.MinValue
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        outState     <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = outState.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        activeValue: Option[Boolean] = updatedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("active").collect { case BoolValue(a) => a }
            case _           => None
          }
        }
        counterValue: Option[BigInt] = updatedFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
            case _           => None
          }
        }
      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateId("done"))) and
      expect(updatedFiber.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(activeValue.contains(false)) and
      expect(counterValue.contains(BigInt(99)))
    }
  }

  test("guard condition prevents transition when counter >= 10") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()

        initialData = MapValue(
          Map(
            "counter" -> IntValue(10),
            "active"  -> BoolValue(true)
          )
        )
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](fiberId, fiber)

        processUpdate = Updates.TransitionStateMachine(
          fiberId,
          "increment",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.MinValue
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))

        result <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = result.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateId("counting"))) and // State unchanged
      expect(updatedFiber.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)) and // Sequence not incremented
      expect(updatedFiber.exists(_.lastReceipt.exists(r => !r.success && r.errorMessage.exists(_.contains("guard")))))
    }
  }

  test("archive fiber changes status to archived") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()
        initialData = MapValue(Map.empty[String, JsonLogicValue])
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("waiting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](fiberId, fiber)

        archiveUpdate = Updates.ArchiveStateMachine(fiberId, FiberOrdinal.MinValue)
        archiveProof <- fixture.registry.generateProofs(archiveUpdate, Set(Alice))
        outState     <- combiner.insert(inState, Signed(archiveUpdate, archiveProof))

        archivedFiber = outState.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(archivedFiber.isDefined) and
      expect(archivedFiber.map(_.status).contains(FiberStatus.Archived))
    }
  }

  test("archived fiber rejects events") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()
        initialData = MapValue(Map.empty[String, JsonLogicValue])
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("waiting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](fiberId, fiber)

        // Archive the fiber first
        archiveUpdate = Updates.ArchiveStateMachine(fiberId, FiberOrdinal.MinValue)
        archiveProof  <- fixture.registry.generateProofs(archiveUpdate, Set(Alice))
        archivedState <- combiner.insert(inState, Signed(archiveUpdate, archiveProof))

        // Attempt to process an event on the archived fiber
        seqAfterArchive = archivedState.calculated.stateMachines(fiberId).sequenceNumber
        processUpdate = Updates.TransitionStateMachine(
          fiberId,
          "start",
          MapValue(Map.empty),
          seqAfterArchive
        )
        eventProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))

        // The event should record failure because fiber is archived
        eventResult <- combiner.insert(archivedState, Signed(processUpdate, eventProof))

        // Get the updated fiber to check its status
        updatedFiber = eventResult.calculated.stateMachines.get(fiberId)

      } yield updatedFiber match {
        case Some(fiber) =>
          // Archived fiber should have a failed receipt with "not active" message
          fiber.lastReceipt match {
            case Some(receipt) if !receipt.success =>
              val reason = receipt.errorMessage.getOrElse("")
              expect(reason.toLowerCase.contains("not active"), s"Expected 'not active' in reason, got: $reason")
            case other =>
              failure(s"Expected failed receipt, got: $other")
          }
        case None =>
          failure(s"Fiber $fiberId not found in result state")
      }
    }
  }

  test("sequence number increments correctly") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]
        definition = createCounterStateMachine()

        initialData = MapValue(
          Map(
            "counter" -> IntValue(0),
            "active"  -> BoolValue(true)
          )
        )
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("counting"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice, Bob).map(fixture.registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](fiberId, fiber)

        processUpdate = Updates.TransitionStateMachine(
          fiberId,
          "increment",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.MinValue
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        finalState   <- combiner.insert(inState, Signed(processUpdate, processProof))

        finalFiber = finalState.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        counterValue: Option[BigInt] = finalFiber.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
            case _           => None
          }
        }
      } yield expect(finalFiber.isDefined) and
      expect(finalFiber.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(counterValue.contains(BigInt(1)))
    }
  }

  test("event payload is accessible in guard condition") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]

        definition = StateMachineDefinition(
          states = Map(
            StateId("open")   -> State(StateId("open"), isFinal = false),
            StateId("locked") -> State(StateId("locked"), isFinal = false)
          ),
          initialState = StateId("open"),
          transitions = List(
            Transition(
              from = StateId("open"),
              to = StateId("locked"),
              eventName = "lock",
              guard = ApplyExpression(
                EqStrictOp,
                List(
                  VarExpression(Left("event.authorized")),
                  ConstExpression(BoolValue(true))
                )
              ),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "locked" -> BoolValue(true)
                  )
                )
              ),
              dependencies = Set.empty
            )
          )
        )

        initialData = MapValue(Map.empty[String, JsonLogicValue])
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("open"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(fixture.registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](fiberId, fiber)

        processUpdate = Updates.TransitionStateMachine(
          fiberId,
          "lock",
          MapValue(Map("authorized" -> BoolValue(true))),
          FiberOrdinal.MinValue
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        outState     <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = outState.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateId("locked")))
    }
  }

  test("unauthorized event payload fails guard condition") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- UUIDGen.randomUUID[IO]

        definition = StateMachineDefinition(
          states = Map(
            StateId("open")   -> State(StateId("open"), isFinal = false),
            StateId("locked") -> State(StateId("locked"), isFinal = false)
          ),
          initialState = StateId("open"),
          transitions = List(
            Transition(
              from = StateId("open"),
              to = StateId("locked"),
              eventName = "lock",
              guard = ApplyExpression(
                EqStrictOp,
                List(
                  VarExpression(Left("event.authorized")),
                  ConstExpression(BoolValue(true))
                )
              ),
              effect = ConstExpression(MapValue(Map("locked" -> BoolValue(true)))),
              dependencies = Set.empty
            )
          )
        )

        initialData = MapValue(Map.empty[String, JsonLogicValue])
        initialHash <- (initialData: JsonLogicValue).computeDigest

        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = fixture.ordinal,
          previousUpdateOrdinal = fixture.ordinal,
          latestUpdateOrdinal = fixture.ordinal,
          definition = definition,
          currentState = StateId("open"),
          stateData = initialData,
          stateDataHash = initialHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(fixture.registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](fiberId, fiber)

        processUpdate = Updates.TransitionStateMachine(
          fiberId,
          "lock",
          MapValue(Map("authorized" -> BoolValue(false))),
          FiberOrdinal.MinValue
        )
        processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
        result       <- combiner.insert(inState, Signed(processUpdate, processProof))

        updatedFiber = result.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(updatedFiber.isDefined) and
      expect(updatedFiber.map(_.currentState).contains(StateId("open"))) and // State unchanged
      expect(updatedFiber.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)) and // Sequence not incremented
      expect(updatedFiber.exists(_.lastReceipt.exists(r => !r.success && r.errorMessage.exists(_.contains("guard")))))
    }
  }

  test("multiple sequential increments") {
    forall(Gen.choose(1, 9)) { increments =>
      TestFixture.resource().use { fixture =>
        implicit val s: SecurityProvider[IO] = fixture.securityProvider
        implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
        for {
          combiner <- Combiner.make[IO]().pure[IO]

          fiberId <- UUIDGen.randomUUID[IO]
          definition = createCounterStateMachine()

          initialData = MapValue(
            Map(
              "counter" -> IntValue(0),
              "active"  -> BoolValue(true)
            )
          )
          initialHash <- (initialData: JsonLogicValue).computeDigest

          fiber = Records.StateMachineFiberRecord(
            fiberId = fiberId,
            creationOrdinal = fixture.ordinal,
            previousUpdateOrdinal = fixture.ordinal,
            latestUpdateOrdinal = fixture.ordinal,
            definition = definition,
            currentState = StateId("counting"),
            stateData = initialData,
            stateDataHash = initialHash,
            sequenceNumber = FiberOrdinal.MinValue,
            owners = Set(Alice, Bob).map(fixture.registry.addresses),
            status = FiberStatus.Active
          )

          inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](fiberId, fiber)

          finalState <- (1 to increments).toList.foldLeftM(inState) { (state, _) =>
            val seqNum = state.calculated.stateMachines(fiberId).sequenceNumber
            val processUpdate = Updates.TransitionStateMachine(
              fiberId,
              "increment",
              MapValue(Map.empty[String, JsonLogicValue]),
              seqNum
            )
            for {
              processProof <- fixture.registry.generateProofs(processUpdate, Set(Alice))
              newState     <- combiner.insert(state, Signed(processUpdate, processProof))
            } yield newState
          }

          finalFiber = finalState.calculated.stateMachines
            .get(fiberId)
            .collect { case r: Records.StateMachineFiberRecord => r }

          counterValue: Option[BigInt] = finalFiber.flatMap { f =>
            f.stateData match {
              case MapValue(m) => m.get("counter").collect { case IntValue(c) => c }
              case _           => None
            }
          }
        } yield expect(finalFiber.isDefined) and
        expect(finalFiber.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(increments.toLong))) and
        expect(counterValue.contains(BigInt(increments)))
      }
    }
  }
}
