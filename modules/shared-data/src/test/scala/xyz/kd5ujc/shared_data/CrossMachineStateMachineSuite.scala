package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

object CrossMachineStateMachineSuite extends SimpleIOSuite {

  test("cross-machine: escrow with seller dependency") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      val ordinal = fixture.ordinal
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        sellerfiberId <- UUIDGen.randomUUID[IO]
        sellerDef = StateMachineDefinition(
          states = Map(
            StateId("holding")  -> State(StateId("holding")),
            StateId("released") -> State(StateId("released"))
          ),
          initialState = StateId("holding"),
          transitions = List.empty
        )

        sellerData = MapValue(
          Map(
            "hasItem"  -> BoolValue(true),
            "itemName" -> StrValue("widget")
          )
        )
        sellerHash <- (sellerData: JsonLogicValue).computeDigest

        sellerFiber = Records.StateMachineFiberRecord(
          fiberId = sellerfiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = sellerDef,
          currentState = StateId("holding"),
          stateData = sellerData,
          stateDataHash = sellerHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Alice).map(registry.addresses),
          status = FiberStatus.Active
        )

        buyerfiberId <- UUIDGen.randomUUID[IO]
        buyerDef = StateMachineDefinition(
          states = Map(
            StateId("PENDING")   -> State(StateId("PENDING")),
            StateId("purchased") -> State(StateId("purchased"))
          ),
          initialState = StateId("PENDING"),
          transitions = List(
            Transition(
              from = StateId("PENDING"),
              to = StateId("purchased"),
              eventName = "buy",
              guard = ApplyExpression(
                AndOp,
                List(
                  VarExpression(Left(s"machines.$sellerfiberId.state.hasItem")),
                  ApplyExpression(
                    Geq,
                    List(
                      VarExpression(Left("state.balance")),
                      ConstExpression(IntValue(100))
                    )
                  )
                )
              ),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "balance"   -> IntValue(0),
                    "purchased" -> BoolValue(true)
                  )
                )
              ),
              dependencies = Set(sellerfiberId)
            )
          )
        )

        buyerData = MapValue(
          Map(
            "balance" -> IntValue(150)
          )
        )
        buyerHash <- (buyerData: JsonLogicValue).computeDigest

        buyerFiber = Records.StateMachineFiberRecord(
          fiberId = buyerfiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = buyerDef,
          currentState = StateId("PENDING"),
          stateData = buyerData,
          stateDataHash = buyerHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set(Bob).map(registry.addresses),
          status = FiberStatus.Active
        )

        inState <- DataState(OnChain.genesis, CalculatedState.genesis)
          .withRecord[IO](sellerfiberId, sellerFiber)
          .flatMap(_.withRecord[IO](buyerfiberId, buyerFiber))

        buyUpdate = Updates
          .TransitionStateMachine(
            buyerfiberId,
            "buy",
            MapValue(Map.empty[String, JsonLogicValue]),
            FiberOrdinal.MinValue
          )
        buyProof   <- registry.generateProofs(buyUpdate, Set(Bob))
        finalState <- combiner.insert(inState, Signed(buyUpdate, buyProof))

        updatedBuyer = finalState.calculated.stateMachines
          .get(buyerfiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        buyerBalance: Option[BigInt] = updatedBuyer.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("balance").collect { case IntValue(b) => b }
            case _           => None
          }
        }
        buyerPurchased: Option[Boolean] = updatedBuyer.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("purchased").collect { case BoolValue(p) => p }
            case _           => None
          }
        }
      } yield expect(updatedBuyer.isDefined) and
      expect(updatedBuyer.map(_.currentState).contains(StateId("purchased"))) and
      expect(buyerBalance.contains(BigInt(0))) and
      expect(buyerPurchased.contains(true))
    }
  }
}
