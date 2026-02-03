package xyz.kd5ujc.shared_data.examples

import java.util.UUID

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_data.testkit.{DataStateTestOps, FiberBuilder, TestImports}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser.{decode, parse}
import weaver.SimpleIOSuite

object TicTacToeGameSuite extends SimpleIOSuite {

  import DataStateTestOps._
  import TestImports.optionFiberRecordOps

  // Inline state machine definition with dynamic oracle CID via string interpolation.
  // This replaces the static resource file which had a hardcoded oracle UUID.
  private def stateMachineJson(oracleFiberId: UUID): String =
    s"""{
    "states": {
      "setup": { "id": {"value": "setup"}, "isFinal": false },
      "playing": { "id": {"value": "playing"}, "isFinal": false },
      "finished": { "id": {"value": "finished"}, "isFinal": true },
      "cancelled": { "id": {"value": "cancelled"}, "isFinal": true }
    },
    "initialState": {"value": "setup"},
    "transitions": [
      {
        "from": {"value": "setup"},
        "to": {"value": "playing"},
        "eventName": "start_game",
        "guard": {
          "and": [
            {"!!": [{"var": "event.playerX"}]},
            {"!!": [{"var": "event.playerO"}]},
            {"!!": [{"var": "event.gameId"}]}
          ]
        },
        "effect": {
          "_oracleCall": {
            "fiberId": {"var": "state.oracleFiberId"},
            "method": "initialize",
            "args": {
              "playerX": {"var": "event.playerX"},
              "playerO": {"var": "event.playerO"},
              "gameId": {"var": "event.gameId"}
            }
          },
          "gameId": {"var": "event.gameId"},
          "playerX": {"var": "event.playerX"},
          "playerO": {"var": "event.playerO"},
          "status": "initialized"
        },
        "dependencies": []
      },
      {
        "from": {"value": "playing"},
        "to": {"value": "playing"},
        "eventName": "make_move",
        "guard": {
          "===": [{"var": "scripts.${oracleFiberId}.state.status"}, "InProgress"]
        },
        "effect": {
          "_oracleCall": {
            "fiberId": {"var": "state.oracleFiberId"},
            "method": "makeMove",
            "args": {
              "player": {"var": "event.player"},
              "cell": {"var": "event.cell"}
            }
          },
          "lastMove": {
            "player": {"var": "event.player"},
            "cell": {"var": "event.cell"}
          }
        },
        "dependencies": ["${oracleFiberId}"]
      },
      {
        "from": {"value": "playing"},
        "to": {"value": "finished"},
        "eventName": "make_move",
        "guard": {
          "or": [
            {"===": [{"var": "scripts.${oracleFiberId}.state.status"}, "Won"]},
            {"===": [{"var": "scripts.${oracleFiberId}.state.status"}, "Draw"]}
          ]
        },
        "effect": {
          "_oracleCall": {
            "fiberId": {"var": "state.oracleFiberId"},
            "method": "makeMove",
            "args": {
              "player": {"var": "event.player"},
              "cell": {"var": "event.cell"}
            }
          },
          "finalStatus": {"var": "scripts.${oracleFiberId}.state.status"},
          "winner": {"var": "scripts.${oracleFiberId}.state.winner"},
          "finalBoard": {"var": "scripts.${oracleFiberId}.state.board"},
          "_emit": [
            {
              "name": "game_completed",
              "data": {
                "gameId": {"var": "state.gameId"},
                "winner": {"var": "scripts.${oracleFiberId}.state.winner"},
                "status": {"var": "scripts.${oracleFiberId}.state.status"},
                "moveCount": {"var": "scripts.${oracleFiberId}.state.moveCount"}
              }
            }
          ]
        },
        "dependencies": ["${oracleFiberId}"]
      },
      {
        "from": {"value": "playing"},
        "to": {"value": "playing"},
        "eventName": "reset_board",
        "guard": {
          "or": [
            {"===": [{"var": "scripts.${oracleFiberId}.state.status"}, "Won"]},
            {"===": [{"var": "scripts.${oracleFiberId}.state.status"}, "Draw"]}
          ]
        },
        "effect": {
          "_oracleCall": {
            "fiberId": {"var": "state.oracleFiberId"},
            "method": "resetGame",
            "args": {}
          },
          "roundCount": {"+": [{"var": "state.roundCount"}, 1]}
        },
        "dependencies": ["${oracleFiberId}"]
      },
      {
        "from": {"value": "playing"},
        "to": {"value": "cancelled"},
        "eventName": "cancel_game",
        "guard": {"==": [1, 1]},
        "effect": {
          "_oracleCall": {
            "fiberId": {"var": "state.oracleFiberId"},
            "method": "cancelGame",
            "args": {
              "requestedBy": {"var": "event.requestedBy"},
              "reason": {"var": "event.reason"}
            }
          },
          "cancelledBy": {"var": "event.requestedBy"},
          "cancelReason": {"var": "event.reason"}
        },
        "dependencies": []
      },
      {
        "from": {"value": "setup"},
        "to": {"value": "cancelled"},
        "eventName": "cancel_game",
        "guard": {"==": [1, 1]},
        "effect": {
          "cancelledBy": {"var": "event.requestedBy"},
          "cancelReason": {"var": "event.reason"}
        },
        "dependencies": []
      }
    ]
  }"""

  /**
   * Shared setup: creates oracle + state machine and returns the initial DataState
   * along with the generated CIDs.
   */
  private def setupGame(
    ordinal:  SnapshotOrdinal,
    registry: ParticipantRegistry[IO],
    combiner: CombinerService
  )(implicit
    _s:    SecurityProvider[IO],
    l0ctx: L0NodeContext[IO]
  ): IO[(DataState[OnChain, CalculatedState], UUID, UUID)] =
    for {
      oracleFiberId  <- UUIDGen.randomUUID[IO]
      machineFiberId <- UUIDGen.randomUUID[IO]

      // Load oracle definition from resource (no hardcoded UUIDs)
      oracleDefJson <- IO {
        val stream = getClass.getResourceAsStream("/tictactoe/script-definition.json")
        scala.io.Source.fromInputStream(stream).mkString
      }
      oracleDefParsed <- IO.fromEither(parse(oracleDefJson))
      oracleScript    <- IO.fromEither(oracleDefParsed.hcursor.downField("scriptProgram").as[JsonLogicExpression])
      oracleInitialState <- IO.fromEither(
        oracleDefParsed.hcursor.downField("initialState").as[Option[JsonLogicValue]]
      )

      // Create oracle via combiner
      createOracle = Updates.CreateScript(
        fiberId = oracleFiberId,
        scriptProgram = oracleScript,
        initialState = oracleInitialState,
        accessControl = AccessControlPolicy.Public
      )
      oracleProof <- registry.generateProofs(createOracle, Set(Alice))
      stateAfterOracle <- combiner.insert(
        DataState(OnChain.genesis, CalculatedState.genesis),
        Signed(createOracle, oracleProof)
      )

      // Create state machine with dynamic oracle CID
      machineDef <- IO.fromEither(
        decode[StateMachineDefinition](stateMachineJson(oracleFiberId)).left.map(err =>
          new RuntimeException(s"Failed to decode state machine JSON: $err")
        )
      )

      machineFiber <- FiberBuilder(machineFiberId, ordinal, machineDef)
        .withState("setup")
        .withData(
          "oracleFiberId" -> StrValue(oracleFiberId.toString),
          "status"        -> StrValue("waiting"),
          "roundCount"    -> IntValue(0)
        )
        .ownedBy(registry, Alice, Bob)
        .build[IO]

      stateAfterMachine <- stateAfterOracle.withRecord[IO](machineFiberId, machineFiber)
    } yield (stateAfterMachine, oracleFiberId, machineFiberId)

  // Type alias for readability
  private type CombinerService =
    io.constellationnetwork.metagraph_sdk.lifecycle.CombinerService[
      IO,
      Updates.OttochainMessage,
      OnChain,
      CalculatedState
    ]

  test("tic-tac-toe: complete game flow - X wins") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner                                      <- Combiner.make[IO]().pure[IO]
        (initialState, oracleFiberId, machineFiberId) <- setupGame(fixture.ordinal, registry, combiner)

        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        // Start game
        state1 <- initialState.transition(
          machineFiberId,
          "start_game",
          MapValue(
            Map(
              "playerX" -> StrValue(aliceAddr.toString),
              "playerO" -> StrValue(bobAddr.toString),
              "gameId"  -> StrValue("test-game-001")
            )
          ),
          Alice
        )(registry, combiner)

        machineAfterStart = state1.fiberRecord(machineFiberId)
        _ = expect.all(
          machineAfterStart.isDefined,
          machineAfterStart.map(_.currentState).contains(StateId("playing"))
        )

        // X wins with top row (0, 1, 2)
        state2 <- state1.transition(
          machineFiberId,
          "make_move",
          MapValue(Map("player" -> StrValue("X"), "cell" -> IntValue(0))),
          Alice
        )(registry, combiner)
        state3 <- state2.transition(
          machineFiberId,
          "make_move",
          MapValue(Map("player" -> StrValue("O"), "cell" -> IntValue(3))),
          Bob
        )(registry, combiner)
        state4 <- state3.transition(
          machineFiberId,
          "make_move",
          MapValue(Map("player" -> StrValue("X"), "cell" -> IntValue(1))),
          Alice
        )(registry, combiner)
        state5 <- state4.transition(
          machineFiberId,
          "make_move",
          MapValue(Map("player" -> StrValue("O"), "cell" -> IntValue(4))),
          Bob
        )(registry, combiner)
        finalState <- state5.transition(
          machineFiberId,
          "make_move",
          MapValue(Map("player" -> StrValue("X"), "cell" -> IntValue(2))),
          Alice
        )(registry, combiner)

        finalMachine = finalState.fiberRecord(machineFiberId)
        finalOracle = finalState.oracleRecord(oracleFiberId)

        oracleStatus = finalOracle.flatMap { o =>
          o.stateData match {
            case Some(MapValue(m)) => m.get("status").collect { case StrValue(s) => s }
            case _                 => None
          }
        }
        oracleWinner = finalOracle.flatMap { o =>
          o.stateData match {
            case Some(MapValue(m)) => m.get("winner").collect { case StrValue(w) => w }
            case _                 => None
          }
        }

      } yield expect.all(
        finalMachine.isDefined,
        finalMachine.map(_.currentState).contains(StateId("playing")),
        finalMachine
          .flatMap(m =>
            m.stateData match {
              case MapValue(map) =>
                map.get("lastMove").collect { case MapValue(m) => m.get("cell").contains(IntValue(2)) }
              case _ => None
            }
          )
          .getOrElse(false),
        finalOracle.isDefined,
        finalOracle.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(6L)),
        oracleStatus.contains("Won"),
        oracleWinner.contains("X"),
        finalOracle.flatMap(_.stateData).exists {
          case MapValue(m) =>
            m.get("board") match {
              case Some(ArrayValue(List(StrValue("X"), StrValue("X"), StrValue("X"), _, _, _, _, _, _))) => true
              case _                                                                                     => false
            }
          case _ => false
        }
      )
    }
  }

  test("tic-tac-toe: draw scenario") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner                                      <- Combiner.make[IO]().pure[IO]
        (initialState, oracleFiberId, machineFiberId) <- setupGame(fixture.ordinal, registry, combiner)

        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        state1 <- initialState.transition(
          machineFiberId,
          "start_game",
          MapValue(
            Map(
              "playerX" -> StrValue(aliceAddr.toString),
              "playerO" -> StrValue(bobAddr.toString),
              "gameId"  -> StrValue("test-game-draw")
            )
          ),
          Alice
        )(registry, combiner)

        drawMoves = List(
          ("X", 0, Alice),
          ("O", 1, Bob),
          ("X", 2, Alice),
          ("O", 4, Bob),
          ("X", 3, Alice),
          ("O", 5, Bob),
          ("X", 7, Alice),
          ("O", 6, Bob),
          ("X", 8, Alice)
        )

        finalState <- drawMoves.foldLeftM(state1) { case (currentState, (player, cell, signer)) =>
          currentState.transition(
            machineFiberId,
            "make_move",
            MapValue(Map("player" -> StrValue(player), "cell" -> IntValue(cell))),
            signer
          )(registry, combiner)
        }

        finalMachine = finalState.fiberRecord(machineFiberId)
        finalOracle = finalState.oracleRecord(oracleFiberId)

        oracleStatus = finalOracle.flatMap { o =>
          o.stateData match {
            case Some(MapValue(m)) => m.get("status").collect { case StrValue(s) => s }
            case _                 => None
          }
        }

      } yield expect.all(
        finalMachine.isDefined,
        finalMachine.map(_.currentState).contains(StateId("playing")),
        finalOracle.isDefined,
        finalOracle.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(10L)),
        oracleStatus.contains("Draw")
      )
    }
  }

  test("tic-tac-toe: invalid move rejected") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner                                      <- Combiner.make[IO]().pure[IO]
        (initialState, oracleFiberId, machineFiberId) <- setupGame(fixture.ordinal, registry, combiner)

        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        state1 <- initialState.transition(
          machineFiberId,
          "start_game",
          MapValue(
            Map(
              "playerX" -> StrValue(aliceAddr.toString),
              "playerO" -> StrValue(bobAddr.toString),
              "gameId"  -> StrValue("test-game-invalid")
            )
          ),
          Alice
        )(registry, combiner)

        // X plays cell 0
        state2 <- state1.transition(
          machineFiberId,
          "make_move",
          MapValue(Map("player" -> StrValue("X"), "cell" -> IntValue(0))),
          Alice
        )(registry, combiner)

        // O tries same cell — should fail
        state3 <- state2.transition(
          machineFiberId,
          "make_move",
          MapValue(Map("player" -> StrValue("O"), "cell" -> IntValue(0))),
          Bob
        )(registry, combiner)

        machineAfterInvalid = state3.fiberRecord(machineFiberId)
        oracleAfterInvalid = state3.oracleRecord(oracleFiberId)

      } yield expect.all(
        machineAfterInvalid.isDefined,
        machineAfterInvalid.exists(m => m.lastReceipt.exists(!_.success)),
        oracleAfterInvalid.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(2L)),
        oracleAfterInvalid.flatMap(_.stateData).exists {
          case MapValue(m) => m.get("moveCount").contains(IntValue(1))
          case _           => false
        }
      )
    }
  }

  test("tic-tac-toe: reset and play another round") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner                                      <- Combiner.make[IO]().pure[IO]
        (initialState, oracleFiberId, machineFiberId) <- setupGame(fixture.ordinal, registry, combiner)

        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        state1 <- initialState.transition(
          machineFiberId,
          "start_game",
          MapValue(
            Map(
              "playerX" -> StrValue(aliceAddr.toString),
              "playerO" -> StrValue(bobAddr.toString),
              "gameId"  -> StrValue("test-game-reset")
            )
          ),
          Alice
        )(registry, combiner)

        // Quick win for X
        quickWin = List(("X", 0, Alice), ("O", 3, Bob), ("X", 1, Alice), ("O", 4, Bob), ("X", 2, Alice))

        stateAfterWin <- quickWin.foldLeftM(state1) { case (currentState, (player, cell, signer)) =>
          currentState.transition(
            machineFiberId,
            "make_move",
            MapValue(Map("player" -> StrValue(player), "cell" -> IntValue(cell))),
            signer
          )(registry, combiner)
        }

        machineAfterWin = stateAfterWin.fiberRecord(machineFiberId)
        _ = expect.all(
          machineAfterWin.isDefined,
          machineAfterWin.map(_.currentState).contains(StateId("finished"))
        )

        // Reset for round 2
        stateAfterReset <- stateAfterWin.transition(
          machineFiberId,
          "reset_board",
          MapValue(Map.empty[String, JsonLogicValue]),
          Alice
        )(registry, combiner)

        machineAfterReset = stateAfterReset.fiberRecord(machineFiberId)
        roundCount = machineAfterReset.extractInt("roundCount")

        oracleAfterReset = stateAfterReset.oracleRecord(oracleFiberId)
        oracleStatusAfterReset = oracleAfterReset.flatMap { o =>
          o.stateData match {
            case Some(MapValue(m)) => m.get("status").collect { case StrValue(s) => s }
            case _                 => None
          }
        }

      } yield expect.all(
        machineAfterReset.isDefined,
        machineAfterReset.map(_.currentState).contains(StateId("playing")),
        roundCount.contains(BigInt(1)),
        oracleAfterReset.isDefined,
        oracleStatusAfterReset.contains("InProgress")
      )
    }
  }
}
