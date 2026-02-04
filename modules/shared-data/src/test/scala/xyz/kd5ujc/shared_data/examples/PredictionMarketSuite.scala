package xyz.kd5ujc.shared_data.examples

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
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
 * Prediction Market — Unit Tests
 *
 * Demonstrates P2P prediction markets combining:
 * - State Machine: Market lifecycle (open → closed → resolving → resolved)
 * - Script: Resolution oracle that aggregates votes with reputation weighting
 *
 * Flow:
 * 1. Create market state machine
 * 2. Open market, accept positions
 * 3. Close market at deadline
 * 4. Oracle agents submit outcome votes
 * 5. Resolution script calculates consensus
 * 6. Market transitions to resolved
 */
object PredictionMarketSuite extends SimpleIOSuite {

  import DataStateTestOps._

  // ---------------------------------------------------------------------
  // State Machine: Prediction Market Lifecycle
  // ---------------------------------------------------------------------
  private val predictionMarketMachine =
    """|{
       |  "states": {
       |    "proposed": { "id": { "value": "proposed" }, "isFinal": false, "metadata": null },
       |    "open": { "id": { "value": "open" }, "isFinal": false, "metadata": null },
       |    "closed": { "id": { "value": "closed" }, "isFinal": false, "metadata": null },
       |    "resolving": { "id": { "value": "resolving" }, "isFinal": false, "metadata": null },
       |    "resolved": { "id": { "value": "resolved" }, "isFinal": true, "metadata": null },
       |    "cancelled": { "id": { "value": "cancelled" }, "isFinal": true, "metadata": null }
       |  },
       |  "initialState": { "value": "proposed" },
       |  "transitions": [
       |    {
       |      "from": { "value": "proposed" },
       |      "to": { "value": "open" },
       |      "eventName": "fund",
       |      "guard": { "==": [1, 1] },
       |      "effect": {
       |        "merge": [
       |          { "var": "state" },
       |          { "escrowTx": { "var": "event.escrowTx" } }
       |        ]
       |      },
       |      "dependencies": []
       |    },
       |    {
       |      "from": { "value": "proposed" },
       |      "to": { "value": "cancelled" },
       |      "eventName": "cancel",
       |      "guard": { "==": [1, 1] },
       |      "effect": { "var": "state" },
       |      "dependencies": []
       |    },
       |    {
       |      "from": { "value": "open" },
       |      "to": { "value": "open" },
       |      "eventName": "addPosition",
       |      "guard": { "==": [1, 1] },
       |      "effect": {
       |        "merge": [
       |          { "var": "state" },
       |          {
       |            "positions": {
       |              "merge": [
       |                { "var": "state.positions" },
       |                {
       |                  "map": [
       |                    [{ "var": "event" }],
       |                    { "user": { "var": "user" }, "side": { "var": "side" }, "amount": { "var": "amount" } }
       |                  ]
       |                }
       |              ]
       |            }
       |          }
       |        ]
       |      },
       |      "dependencies": []
       |    },
       |    {
       |      "from": { "value": "open" },
       |      "to": { "value": "closed" },
       |      "eventName": "close",
       |      "guard": { ">=": [{ "count": [{ "var": "state.positions" }] }, 1] },
       |      "effect": { "var": "state" },
       |      "dependencies": []
       |    },
       |    {
       |      "from": { "value": "closed" },
       |      "to": { "value": "resolving" },
       |      "eventName": "triggerResolution",
       |      "guard": { "==": [1, 1] },
       |      "effect": {
       |        "merge": [
       |          { "var": "state" },
       |          { "oracleSubmissions": [] }
       |        ]
       |      },
       |      "dependencies": []
       |    },
       |    {
       |      "from": { "value": "resolving" },
       |      "to": { "value": "resolving" },
       |      "eventName": "submitVote",
       |      "guard": {
       |        ">=": [{ "var": "event.oracleReputation" }, { "var": "state.minReputation" }]
       |      },
       |      "effect": {
       |        "merge": [
       |          { "var": "state" },
       |          {
       |            "oracleSubmissions": {
       |              "merge": [
       |                { "var": "state.oracleSubmissions" },
       |                [{
       |                  "oracle": { "var": "event.oracleId" },
       |                  "reputation": { "var": "event.oracleReputation" },
       |                  "vote": { "var": "event.vote" }
       |                }]
       |              ]
       |            }
       |          }
       |        ]
       |      },
       |      "dependencies": []
       |    },
       |    {
       |      "from": { "value": "resolving" },
       |      "to": { "value": "resolved" },
       |      "eventName": "finalize",
       |      "guard": {
       |        ">=": [
       |          { "reduce": [{ "var": "state.oracleSubmissions" }, { "+": [{ "var": "accumulator" }, 1] }, 0] },
       |          { "var": "state.minOracles" }
       |        ]
       |      },
       |      "effect": {
       |        "merge": [
       |          { "var": "state" },
       |          { "outcome": { "var": "event.outcome" }, "confidence": { "var": "event.confidence" } }
       |        ]
       |      },
       |      "dependencies": []
       |    }
       |  ],
       |  "metadata": { "name": "PredictionMarket" }
       |}""".stripMargin

  // ---------------------------------------------------------------------
  // Script: Resolution Oracle (aggregates votes with reputation weighting)
  // ---------------------------------------------------------------------
  // Resolution script without 'let' (not supported in JLVM)
  // Calculates weighted consensus from oracle submissions
  // yesWeight = sum of reputation for YES votes
  // noWeight = sum of reputation for NO votes
  // yesRatio = yesWeight / (yesWeight + noWeight)
  // Returns YES/NO if ratio meets threshold, DISPUTED otherwise
  private val resolutionScript =
    """|{
       |  "if": [
       |    { "==": [{ "var": "method" }, "calculateOutcome"] },
       |    {
       |      "if": [
       |        { ">": [
       |          { "+": [
       |            { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |            { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "NO"] }, { "var": "current.reputation" }, 0] }] }, 0] }
       |          ]},
       |          0
       |        ]},
       |        { "if": [
       |          { ">=": [
       |            { "/": [
       |              { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |              { "+": [
       |                { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |                { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "NO"] }, { "var": "current.reputation" }, 0] }] }, 0] }
       |              ]}
       |            ]},
       |            { "var": "args.consensusThreshold" }
       |          ]},
       |          {
       |            "outcome": "YES",
       |            "confidence": { "/": [
       |              { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |              { "+": [
       |                { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |                { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "NO"] }, { "var": "current.reputation" }, 0] }] }, 0] }
       |              ]}
       |            ]}
       |          },
       |          { "if": [
       |            { ">=": [
       |              { "-": [
       |                1,
       |                { "/": [
       |                  { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |                  { "+": [
       |                    { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |                    { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "NO"] }, { "var": "current.reputation" }, 0] }] }, 0] }
       |                  ]}
       |                ]}
       |              ]},
       |              { "var": "args.consensusThreshold" }
       |            ]},
       |            {
       |              "outcome": "NO",
       |              "confidence": { "-": [
       |                1,
       |                { "/": [
       |                  { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |                  { "+": [
       |                    { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |                    { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "NO"] }, { "var": "current.reputation" }, 0] }] }, 0] }
       |                  ]}
       |                ]}
       |              ]}
       |            },
       |            {
       |              "outcome": "DISPUTED",
       |              "yesRatio": { "/": [
       |                { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |                { "+": [
       |                  { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "YES"] }, { "var": "current.reputation" }, 0] }] }, 0] },
       |                  { "reduce": [{ "var": "args.submissions" }, { "+": [{ "var": "accumulator" }, { "if": [{ "==": [{ "var": "current.vote" }, "NO"] }, { "var": "current.reputation" }, 0] }] }, 0] }
       |                ]}
       |              ]}
       |            }
       |          ]}
       |        ]},
       |        { "outcome": "DISPUTED", "yesRatio": 0.5 }
       |      ]
       |    },
       |    { "error": "unknown method" }
       |  ]
       |}""".stripMargin

  // ---------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------

  test("market lifecycle: proposed → open → closed") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        marketId <- IO.randomUUID

        machineDef <- IO.fromEither(parser.decode[StateMachineDefinition](predictionMarketMachine))

        initialData = MapValue(
          Map(
            "question"      -> StrValue("Will ETH exceed $5000 by March 2026?"),
            "minOracles"    -> IntValue(2),
            "minReputation" -> IntValue(50),
            "positions"     -> ArrayValue(List.empty)
          )
        )

        // Create market
        createOp = Updates.CreateStateMachine(marketId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOp, createProof)
        )

        market1 = state1.calculated.stateMachines.get(marketId)
        _ = expect(market1.exists(_.currentState == StateId("proposed")))

        // Fund market (transition to open)
        fundOp = Updates.TransitionStateMachine(
          marketId,
          "fund",
          MapValue(Map("escrowTx" -> StrValue("0x1234567890abcdef"))),
          FiberOrdinal.MinValue
        )
        fundProof <- fixture.registry.generateProofs(fundOp, Set(Alice))
        state2    <- combiner.insert(state1, Signed(fundOp, fundProof))

        market2 = state2.calculated.stateMachines.get(marketId)
        _ = expect(market2.exists(_.currentState == StateId("open")))

        // Add position (Alice bets YES)
        addPosOp = Updates.TransitionStateMachine(
          marketId,
          "addPosition",
          MapValue(
            Map(
              "user"   -> StrValue("alice"),
              "side"   -> StrValue("YES"),
              "amount" -> IntValue(100)
            )
          ),
          FiberOrdinal.unsafeApply(1L)
        )
        addPosProof <- fixture.registry.generateProofs(addPosOp, Set(Alice))
        state3      <- combiner.insert(state2, Signed(addPosOp, addPosProof))

        // Close market
        closeOp = Updates.TransitionStateMachine(
          marketId,
          "close",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.unsafeApply(2L)
        )
        closeProof <- fixture.registry.generateProofs(closeOp, Set(Alice))
        state4     <- combiner.insert(state3, Signed(closeOp, closeProof))

        market4 = state4.calculated.stateMachines.get(marketId)

      } yield expect(market4.exists(_.currentState == StateId("closed")))
    }
  }

  test("resolution script calculates YES consensus") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        scriptId <- IO.randomUUID

        prog <- IO.fromEither(parser.parse(resolutionScript).flatMap(_.as[JsonLogicExpression]))

        // Create resolution script
        createScript = Updates.CreateScript(
          fiberId = scriptId,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        // Invoke with oracle submissions
        // YES weight: 100 + 80 = 180
        // NO weight: 50
        // YES ratio: 180/230 = 0.78 > 0.66 threshold
        submissions = ArrayValue(
          List(
            MapValue(Map("oracle" -> StrValue("agent1"), "reputation" -> IntValue(100), "vote" -> StrValue("YES"))),
            MapValue(Map("oracle" -> StrValue("agent2"), "reputation" -> IntValue(80), "vote" -> StrValue("YES"))),
            MapValue(Map("oracle" -> StrValue("agent3"), "reputation" -> IntValue(50), "vote" -> StrValue("NO")))
          )
        )

        invokeOp = Updates.InvokeScript(
          fiberId = scriptId,
          method = "calculateOutcome",
          args = MapValue(
            Map(
              "submissions"        -> submissions,
              "consensusThreshold" -> FloatValue(0.66)
            )
          ),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- fixture.registry.generateProofs(invokeOp, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOp, invokeProof))

        result = state2.oracleRecord(scriptId).flatMap(_.lastInvocation).map(_.result)

      } yield expect.all(
        result.isDefined,
        result.exists {
          case MapValue(m) => m.get("outcome").contains(StrValue("YES"))
          case _           => false
        }
      )
    }
  }

  test("resolution script returns DISPUTED when no consensus") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        scriptId <- IO.randomUUID

        prog <- IO.fromEither(parser.parse(resolutionScript).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = scriptId,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        // 50/50 split - no consensus at 0.75 threshold
        submissions = ArrayValue(
          List(
            MapValue(Map("oracle" -> StrValue("agent1"), "reputation" -> IntValue(100), "vote" -> StrValue("YES"))),
            MapValue(Map("oracle" -> StrValue("agent2"), "reputation" -> IntValue(100), "vote" -> StrValue("NO")))
          )
        )

        invokeOp = Updates.InvokeScript(
          fiberId = scriptId,
          method = "calculateOutcome",
          args = MapValue(
            Map(
              "submissions"        -> submissions,
              "consensusThreshold" -> FloatValue(0.75)
            )
          ),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- fixture.registry.generateProofs(invokeOp, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOp, invokeProof))

        result = state2.oracleRecord(scriptId).flatMap(_.lastInvocation).map(_.result)

      } yield expect.all(
        result.isDefined,
        result.exists {
          case MapValue(m) => m.get("outcome").contains(StrValue("DISPUTED"))
          case _           => false
        }
      )
    }
  }

  test("full flow: market + resolution script integration") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        marketId <- IO.randomUUID
        scriptId <- IO.randomUUID

        // Create resolution script first
        scriptProg <- IO.fromEither(parser.parse(resolutionScript).flatMap(_.as[JsonLogicExpression]))
        createScript = Updates.CreateScript(
          fiberId = scriptId,
          scriptProgram = scriptProg,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )
        scriptProof <- fixture.registry.generateProofs(createScript, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, scriptProof)
        )

        // Create market state machine
        machineDef <- IO.fromEither(parser.decode[StateMachineDefinition](predictionMarketMachine))
        initialData = MapValue(
          Map(
            "question"         -> StrValue("Test prediction?"),
            "minOracles"       -> IntValue(2),
            "minReputation"    -> IntValue(20),
            "positions"        -> ArrayValue(List.empty),
            "resolutionScript" -> StrValue(scriptId.toString)
          )
        )

        createMarket = Updates.CreateStateMachine(marketId, machineDef, initialData)
        marketProof <- fixture.registry.generateProofs(createMarket, Set(Alice))
        state2      <- combiner.insert(state1, Signed(createMarket, marketProof))

        // Fund and add positions
        fundOp = Updates.TransitionStateMachine(
          marketId,
          "fund",
          MapValue(Map("escrowTx" -> StrValue("0xmock"))),
          FiberOrdinal.MinValue
        )
        fundProof <- fixture.registry.generateProofs(fundOp, Set(Alice))
        state3    <- combiner.insert(state2, Signed(fundOp, fundProof))

        addPos1 = Updates.TransitionStateMachine(
          marketId,
          "addPosition",
          MapValue(Map("user" -> StrValue("alice"), "side" -> StrValue("YES"), "amount" -> IntValue(100))),
          FiberOrdinal.unsafeApply(1L)
        )
        pos1Proof <- fixture.registry.generateProofs(addPos1, Set(Alice))
        state4    <- combiner.insert(state3, Signed(addPos1, pos1Proof))

        addPos2 = Updates.TransitionStateMachine(
          marketId,
          "addPosition",
          MapValue(Map("user" -> StrValue("bob"), "side" -> StrValue("NO"), "amount" -> IntValue(50))),
          FiberOrdinal.unsafeApply(2L)
        )
        pos2Proof <- fixture.registry.generateProofs(addPos2, Set(Bob))
        state5    <- combiner.insert(state4, Signed(addPos2, pos2Proof))

        // Close market
        closeOp = Updates.TransitionStateMachine(
          marketId,
          "close",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.unsafeApply(3L)
        )
        closeProof <- fixture.registry.generateProofs(closeOp, Set(Alice))
        state6     <- combiner.insert(state5, Signed(closeOp, closeProof))

        // Trigger resolution
        triggerOp = Updates.TransitionStateMachine(
          marketId,
          "triggerResolution",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.unsafeApply(4L)
        )
        triggerProof <- fixture.registry.generateProofs(triggerOp, Set(Alice))
        state7       <- combiner.insert(state6, Signed(triggerOp, triggerProof))

        // Oracles submit votes
        vote1 = Updates.TransitionStateMachine(
          marketId,
          "submitVote",
          MapValue(
            Map(
              "oracleId"         -> StrValue("oracle1"),
              "oracleReputation" -> IntValue(100),
              "vote"             -> StrValue("YES")
            )
          ),
          FiberOrdinal.unsafeApply(5L)
        )
        vote1Proof <- fixture.registry.generateProofs(vote1, Set(Alice))
        state8     <- combiner.insert(state7, Signed(vote1, vote1Proof))

        vote2 = Updates.TransitionStateMachine(
          marketId,
          "submitVote",
          MapValue(
            Map(
              "oracleId"         -> StrValue("oracle2"),
              "oracleReputation" -> IntValue(80),
              "vote"             -> StrValue("YES")
            )
          ),
          FiberOrdinal.unsafeApply(6L)
        )
        vote2Proof <- fixture.registry.generateProofs(vote2, Set(Bob))
        state9     <- combiner.insert(state8, Signed(vote2, vote2Proof))

        // Use resolution script to calculate outcome
        submissions = ArrayValue(
          List(
            MapValue(Map("oracle" -> StrValue("oracle1"), "reputation" -> IntValue(100), "vote" -> StrValue("YES"))),
            MapValue(Map("oracle" -> StrValue("oracle2"), "reputation" -> IntValue(80), "vote" -> StrValue("YES")))
          )
        )

        invokeScript = Updates.InvokeScript(
          fiberId = scriptId,
          method = "calculateOutcome",
          args = MapValue(
            Map(
              "submissions"        -> submissions,
              "consensusThreshold" -> FloatValue(0.66)
            )
          ),
          targetSequenceNumber = FiberOrdinal.MinValue
        )
        scriptInvokeProof <- fixture.registry.generateProofs(invokeScript, Set(Alice))
        state10           <- combiner.insert(state9, Signed(invokeScript, scriptInvokeProof))

        scriptResult = state10.oracleRecord(scriptId).flatMap(_.lastInvocation).map(_.result)
        outcome = scriptResult.flatMap {
          case MapValue(m) => m.get("outcome")
          case _           => None
        }

        // Finalize market with computed outcome
        finalizeOp = Updates.TransitionStateMachine(
          marketId,
          "finalize",
          MapValue(
            Map(
              "outcome"    -> StrValue("YES"),
              "confidence" -> FloatValue(1.0)
            )
          ),
          FiberOrdinal.unsafeApply(7L)
        )
        finalizeProof <- fixture.registry.generateProofs(finalizeOp, Set(Alice))
        state11       <- combiner.insert(state10, Signed(finalizeOp, finalizeProof))

        finalMarket = state11.calculated.stateMachines.get(marketId)

      } yield expect.all(
        outcome.contains(StrValue("YES")),
        finalMarket.exists(_.currentState == StateId("resolved"))
      )
    }
  }

  test("reputation guard rejects low-reputation oracle") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context

      for {
        combiner <- Combiner.make[IO]().pure[IO]
        marketId <- IO.randomUUID

        machineDef <- IO.fromEither(parser.decode[StateMachineDefinition](predictionMarketMachine))

        initialData = MapValue(
          Map(
            "question"      -> StrValue("Test?"),
            "minOracles"    -> IntValue(2),
            "minReputation" -> IntValue(50), // Minimum reputation of 50
            "positions"     -> ArrayValue(List.empty)
          )
        )

        // Create and advance market to resolving state
        createOp = Updates.CreateStateMachine(marketId, machineDef, initialData)
        createProof <- fixture.registry.generateProofs(createOp, Set(Alice))
        state1 <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createOp, createProof))

        fundOp = Updates
          .TransitionStateMachine(marketId, "fund", MapValue(Map("escrowTx" -> StrValue("0x"))), FiberOrdinal.MinValue)
        fundProof <- fixture.registry.generateProofs(fundOp, Set(Alice))
        state2    <- combiner.insert(state1, Signed(fundOp, fundProof))

        addPosOp = Updates.TransitionStateMachine(
          marketId,
          "addPosition",
          MapValue(Map("user" -> StrValue("alice"), "side" -> StrValue("YES"), "amount" -> IntValue(10))),
          FiberOrdinal.unsafeApply(1L)
        )
        addPosProof <- fixture.registry.generateProofs(addPosOp, Set(Alice))
        state3      <- combiner.insert(state2, Signed(addPosOp, addPosProof))

        closeOp = Updates.TransitionStateMachine(
          marketId,
          "close",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.unsafeApply(2L)
        )
        closeProof <- fixture.registry.generateProofs(closeOp, Set(Alice))
        state4     <- combiner.insert(state3, Signed(closeOp, closeProof))

        triggerOp = Updates.TransitionStateMachine(
          marketId,
          "triggerResolution",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.unsafeApply(3L)
        )
        triggerProof <- fixture.registry.generateProofs(triggerOp, Set(Alice))
        state5       <- combiner.insert(state4, Signed(triggerOp, triggerProof))

        // Try to submit vote with reputation below threshold (30 < 50)
        lowRepVote = Updates.TransitionStateMachine(
          marketId,
          "submitVote",
          MapValue(
            Map(
              "oracleId"         -> StrValue("lowRepOracle"),
              "oracleReputation" -> IntValue(30), // Below 50 threshold
              "vote"             -> StrValue("YES")
            )
          ),
          FiberOrdinal.unsafeApply(4L)
        )
        lowRepProof <- fixture.registry.generateProofs(lowRepVote, Set(Alice))

        // This should fail due to guard condition (30 < 50 minReputation)
        state6 <- combiner.insert(state5, Signed(lowRepVote, lowRepProof))

        // Guard failures don't throw — they record lastReceipt.success = false
        marketAfter = state6.fiberRecord(marketId)

      } yield expect.all(
        marketAfter.isDefined,
        marketAfter.exists(m => m.lastReceipt.exists(!_.success))
      )
    }
  }
}
