package xyz.kd5ujc.shared_data.examples

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.testkit.DataStateTestOps
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser
import weaver.SimpleIOSuite

/**
 * Unit tests for the token escrow state machine.
 */
object TokenEscrowSuite extends SimpleIOSuite {

  import DataStateTestOps._

  // Token Escrow definition - matches e2e definition
  private val tokenEscrowScript =
    """|{
       |  "states": {
       |    "pending": { "id": { "value": "pending" }, "isFinal": false, "metadata": null },
       |    "funded": { "id": { "value": "funded" }, "isFinal": false, "metadata": null },
       |    "released": { "id": { "value": "released" }, "isFinal": true, "metadata": null },
       |    "refunded": { "id": { "value": "refunded" }, "isFinal": true, "metadata": null }
       |  },
       |  "initialState": { "value": "pending" },
       |  "transitions": [
       |    {
       |      "from": { "value": "pending" },
       |      "to": { "value": "funded" },
       |      "eventName": "fund",
       |      "guard": { "==": [1, 1] },
       |      "effect": { "merge": [{ "var": "state" }, { "depositor": { "var": "event.depositor" }, "amount": { "var": "event.amount" } }] },
       |      "dependencies": []
       |    },
       |    {
       |      "from": { "value": "funded" },
       |      "to": { "value": "released" },
       |      "eventName": "release",
       |      "guard": { "==": [1, 1] },
       |      "effect": { "merge": [{ "var": "state" }, { "beneficiary": { "var": "event.beneficiary" } }] },
       |      "dependencies": []
       |    },
       |    {
       |      "from": { "value": "funded" },
       |      "to": { "value": "refunded" },
       |      "eventName": "refund",
       |      "guard": { "==": [1, 1] },
       |      "effect": { "merge": [{ "var": "state" }, {}] },
       |      "dependencies": []
       |    }
       |  ],
       |  "metadata": {
       |    "name": "TokenEscrow",
       |    "description": "A simple token escrow state machine"
       |  }
       |}""".stripMargin

  test("Happy path - release") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(tokenEscrowScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateStateMachine(
          fiberId = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        fundEvent = Updates.TransitionStateMachine(
          fiberId = cid,
          event = "fund",
          payload = MapValue(Map("depositor" -> StringValue("alice"), "amount" -> IntValue(100))),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        fundProof <- registry.generateProofs(fundEvent, Set(Alice))
        state2    <- combiner.insert(state1, Signed(fundEvent, fundProof))

        releaseEvent = Updates.TransitionStateMachine(
          fiberId = cid,
          event = "release",
          payload = MapValue(Map("beneficiary" -> StringValue("bob"))),
          targetSequenceNumber = FiberOrdinal.MinValue.next
        )

        releaseProof <- registry.generateProofs(releaseEvent, Set(Alice))
        state3       <- combiner.insert(state2, Signed(releaseEvent, releaseProof))

        oracle = state3.stateMachineRecord(cid)
        result = oracle.flatMap(_.lastInvocation.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(
          MapValue(
            Map("depositor" -> StringValue("alice"), "amount" -> IntValue(100), "beneficiary" -> StringValue("bob"))
          )
        ),
        oracle.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(2L))
      )
    }
  }

  test("Refund path") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(tokenEscrowScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateStateMachine(
          fiberId = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        fundEvent = Updates.TransitionStateMachine(
          fiberId = cid,
          event = "fund",
          payload = MapValue(Map("depositor" -> StringValue("alice"), "amount" -> IntValue(100))),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        fundProof <- registry.generateProofs(fundEvent, Set(Alice))
        state2    <- combiner.insert(state1, Signed(fundEvent, fundProof))

        refundEvent = Updates.TransitionStateMachine(
          fiberId = cid,
          event = "refund",
          payload = MapValue(Map()),
          targetSequenceNumber = FiberOrdinal.MinValue.next
        )

        refundProof <- registry.generateProofs(refundEvent, Set(Alice))
        state3      <- combiner.insert(state2, Signed(refundEvent, refundProof))

        oracle = state3.stateMachineRecord(cid)
        result = oracle.flatMap(_.lastInvocation.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(MapValue(Map("depositor" -> StringValue("alice"), "amount" -> IntValue(100)))),
        oracle.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(2L))
      )
    }
  }

}
