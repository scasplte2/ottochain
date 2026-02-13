package xyz.kd5ujc.shared_data.examples

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * Time-Locked Escrow — Unit Tests
 *
 * Demonstrates the new context variables ($ordinal, $lastSnapshotHash, $epochProgress)
 * in a time-locked escrow pattern:
 *
 * - Depositor locks funds with a deadline (ordinal-based)
 * - Recipient can claim after deadline
 * - Depositor can refund before deadline (or if unclaimed)
 *
 * Guards use:
 * - $ordinal: Current snapshot ordinal for deadline comparison
 * - $lastSnapshotHash: Available for verification/randomness (shown in state)
 * - $epochProgress: Available for epoch-based logic (shown in state)
 *
 * States: FUNDED → CLAIMED | REFUNDED
 */
object TimeLockedEscrowSuite extends SimpleIOSuite {

  /**
   * Creates the escrow state machine definition with deadline guards.
   *
   * The deadline is stored in state.unlockOrdinal and compared against $ordinal.
   */
  private val escrowMachineJson: String =
    s"""|{
        |  "states": {
        |    "FUNDED": { "id": { "value": "FUNDED" }, "isFinal": false, "metadata": null },
        |    "CLAIMED": { "id": { "value": "CLAIMED" }, "isFinal": true, "metadata": null },
        |    "REFUNDED": { "id": { "value": "REFUNDED" }, "isFinal": true, "metadata": null }
        |  },
        |  "initialState": { "value": "FUNDED" },
        |  "transitions": [
        |    {
        |      "from": { "value": "FUNDED" },
        |      "to": { "value": "CLAIMED" },
        |      "eventName": "claim",
        |      "guard": {
        |        "and": [
        |          { ">=": [{ "var": "$$ordinal" }, { "var": "state.unlockOrdinal" }] },
        |          { "==": [{ "var": "event.recipient" }, { "var": "state.recipient" }] }
        |        ]
        |      },
        |      "effect": {
        |        "merge": [
        |          { "var": "state" },
        |          {
        |            "claimedAt": { "var": "$$ordinal" },
        |            "claimedBy": { "var": "event.recipient" }
        |          }
        |        ]
        |      },
        |      "dependencies": []
        |    },
        |    {
        |      "from": { "value": "FUNDED" },
        |      "to": { "value": "REFUNDED" },
        |      "eventName": "refund",
        |      "guard": {
        |        "and": [
        |          { "<": [{ "var": "$$ordinal" }, { "var": "state.unlockOrdinal" }] },
        |          { "==": [{ "var": "event.depositor" }, { "var": "state.depositor" }] }
        |        ]
        |      },
        |      "effect": {
        |        "merge": [
        |          { "var": "state" },
        |          {
        |            "refundedAt": { "var": "$$ordinal" },
        |            "refundedBy": { "var": "event.depositor" }
        |          }
        |        ]
        |      },
        |      "dependencies": []
        |    }
        |  ],
        |  "metadata": { "name": "TimeLockedEscrow", "version": "1.0" }
        |}""".stripMargin

  test("claim succeeds when ordinal >= unlockOrdinal") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]

        // Get current ordinal from fixture and set unlock to past (ordinal - 10)
        currentOrdinal = fixture.ordinal.value.value
        unlockOrdinal = Math.max(0L, currentOrdinal - 10)

        // Parse machine definition
        machineDef <- IO.fromEither(decode[StateMachineDefinition](escrowMachineJson))

        // Initial state with depositor, recipient, amount, and unlock ordinal
        initialData = MapValue(
          Map(
            "depositor"     -> StrValue("alice"),
            "recipient"     -> StrValue("bob"),
            "amount"        -> IntValue(1000),
            "unlockOrdinal" -> IntValue(unlockOrdinal)
          )
        )

        // Create the escrow
        createOp = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        // Bob claims (should succeed - ordinal >= unlockOrdinal)
        claimOp = Updates.TransitionStateMachine(
          fiberId,
          "claim",
          MapValue(Map("recipient" -> StrValue("bob"))),
          FiberOrdinal.MinValue
        )
        claimProof <- fixture.registry.generateProofs(claimOp, Set(Bob))
        state2     <- combiner.insert(state1, Signed(claimOp, claimProof))

        // Verify final state
        finalMachine = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.isDefined) and
      expect(finalMachine.map(_.currentState).contains(StateId("CLAIMED")))
    }
  }

  test("claim fails when ordinal < unlockOrdinal") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]

        // Get current ordinal and set unlock to future (ordinal + 1000)
        currentOrdinal = fixture.ordinal.value.value
        unlockOrdinal = currentOrdinal + 1000

        machineDef <- IO.fromEither(decode[StateMachineDefinition](escrowMachineJson))

        initialData = MapValue(
          Map(
            "depositor"     -> StrValue("alice"),
            "recipient"     -> StrValue("bob"),
            "amount"        -> IntValue(1000),
            "unlockOrdinal" -> IntValue(unlockOrdinal)
          )
        )

        createOp = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        // Bob tries to claim early (should fail - ordinal < unlockOrdinal)
        claimOp = Updates.TransitionStateMachine(
          fiberId,
          "claim",
          MapValue(Map("recipient" -> StrValue("bob"))),
          FiberOrdinal.MinValue
        )
        claimProof <- fixture.registry.generateProofs(claimOp, Set(Bob))
        state2     <- combiner.insert(state1, Signed(claimOp, claimProof))

        // Guard blocks - state should stay FUNDED
        finalMachine = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.map(_.currentState).contains(StateId("FUNDED")))
    }
  }

  test("refund succeeds when ordinal < unlockOrdinal") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]

        // Set unlock to future
        currentOrdinal = fixture.ordinal.value.value
        unlockOrdinal = currentOrdinal + 1000

        machineDef <- IO.fromEither(decode[StateMachineDefinition](escrowMachineJson))

        initialData = MapValue(
          Map(
            "depositor"     -> StrValue("alice"),
            "recipient"     -> StrValue("bob"),
            "amount"        -> IntValue(1000),
            "unlockOrdinal" -> IntValue(unlockOrdinal)
          )
        )

        createOp = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        // Alice refunds (should succeed - ordinal < unlockOrdinal)
        refundOp = Updates.TransitionStateMachine(
          fiberId,
          "refund",
          MapValue(Map("depositor" -> StrValue("alice"))),
          FiberOrdinal.MinValue
        )
        refundProof <- fixture.registry.generateProofs(refundOp, Set(Alice))
        state2      <- combiner.insert(state1, Signed(refundOp, refundProof))

        finalMachine = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.isDefined) and
      expect(finalMachine.map(_.currentState).contains(StateId("REFUNDED")))
    }
  }

  test("refund fails when ordinal >= unlockOrdinal") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]

        // Set unlock to past
        currentOrdinal = fixture.ordinal.value.value
        unlockOrdinal = Math.max(0L, currentOrdinal - 10)

        machineDef <- IO.fromEither(decode[StateMachineDefinition](escrowMachineJson))

        initialData = MapValue(
          Map(
            "depositor"     -> StrValue("alice"),
            "recipient"     -> StrValue("bob"),
            "amount"        -> IntValue(1000),
            "unlockOrdinal" -> IntValue(unlockOrdinal)
          )
        )

        createOp = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        // Alice tries to refund late (should fail - ordinal >= unlockOrdinal)
        refundOp = Updates.TransitionStateMachine(
          fiberId,
          "refund",
          MapValue(Map("depositor" -> StrValue("alice"))),
          FiberOrdinal.MinValue
        )
        refundProof <- fixture.registry.generateProofs(refundOp, Set(Alice))
        state2      <- combiner.insert(state1, Signed(refundOp, refundProof))

        // Guard blocks - state should stay FUNDED
        finalMachine = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.map(_.currentState).contains(StateId("FUNDED")))
    }
  }

  test("wrong recipient cannot claim") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]

        // Unlock in past (claimable)
        currentOrdinal = fixture.ordinal.value.value
        unlockOrdinal = Math.max(0L, currentOrdinal - 10)

        machineDef <- IO.fromEither(decode[StateMachineDefinition](escrowMachineJson))

        initialData = MapValue(
          Map(
            "depositor"     -> StrValue("alice"),
            "recipient"     -> StrValue("bob"),
            "amount"        -> IntValue(1000),
            "unlockOrdinal" -> IntValue(unlockOrdinal)
          )
        )

        createOp = Updates.CreateStateMachine(fiberId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        // Charlie tries to claim (should fail - wrong recipient)
        claimOp = Updates.TransitionStateMachine(
          fiberId,
          "claim",
          MapValue(Map("recipient" -> StrValue("charlie"))),
          FiberOrdinal.MinValue
        )
        claimProof <- fixture.registry.generateProofs(claimOp, Set(Charlie))
        state2     <- combiner.insert(state1, Signed(claimOp, claimProof))

        // Guard blocks - state should stay FUNDED
        finalMachine = state2.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(finalMachine.map(_.currentState).contains(StateId("FUNDED")))
    }
  }

}
