package xyz.kd5ujc.shared_data.examples

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_data.testkit.{DataStateTestOps, FiberBuilder, TestImports}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

object JsonEncodedStateMachineSuite extends SimpleIOSuite {

  import DataStateTestOps._
  import TestImports.optionFiberRecordOps

  test("json-encoded: time lock contract") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      val ordinal = fixture.ordinal

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        // Define time lock contract in JSON format
        // Uses standard JSON Logic format with operator tags
        timeLockJson =
          """{
          "states": {
            "locked": {
              "id": { "value": "locked" },
              "isFinal": false
            },
            "unlocked": {
              "id": { "value": "unlocked" },
              "isFinal": true
            }
          },
          "initialState": { "value": "locked" },
          "transitions": [
            {
              "from": { "value": "locked" },
              "to": { "value": "unlocked" },
              "eventName": "unlock",
              "guard": {
                ">=": [
                  { "var": "event.currentTime" },
                  { "var": "state.unlockTime" }
                ]
              },
              "effect": [
                ["unlocked", true],
                ["unlockedAt", { "var": "event.currentTime" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Parse JSON into StateMachineDefinition
        parsedDef <- IO.fromEither(
          decode[StateMachineDefinition](timeLockJson).left
            .map(err => new RuntimeException(s"Failed to decode JSON: $err"))
        )

        // Verify the parsed definition structure
        _ = expect.all(
          parsedDef.states.size == 2,
          parsedDef.initialState == StateId("locked"),
          parsedDef.transitions.size == 1,
          parsedDef.transitions.head.from == StateId("locked"),
          parsedDef.transitions.head.to == StateId("unlocked"),
          parsedDef.transitions.head.eventName == "unlock"
        )

        // Create time lock fiber with unlock time set to timestamp 1000
        lockfiberId <- UUIDGen.randomUUID[IO]
        lockData = MapValue(
          Map(
            "unlockTime"  -> IntValue(1000),
            "amount"      -> IntValue(500),
            "beneficiary" -> StrValue("beneficiary-address")
          )
        )

        lockFiber <- FiberBuilder(lockfiberId, ordinal, parsedDef)
          .withState("locked")
          .withDataValue(lockData)
          .ownedBy(registry, Alice, Bob)
          .build[IO]

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](lockfiberId, lockFiber)

        // Try to unlock BEFORE time (should fail)
        earlyResult <- inState
          .transition(
            lockfiberId,
            "unlock",
            MapValue(Map("currentTime" -> IntValue(900))),
            Alice
          )(registry, combiner)
          .attempt

        // Verify early unlock fails
        _ = expect(earlyResult.isLeft)

        // Try to unlock AFTER time (should succeed)
        finalState <- inState.transition(
          lockfiberId,
          "unlock",
          MapValue(Map("currentTime" -> IntValue(1500))),
          Alice
        )(registry, combiner)

        unlockedFiber = finalState.fiberRecord(lockfiberId)

      } yield expect.all(
        unlockedFiber.isDefined,
        unlockedFiber.map(_.currentState).contains(StateId("unlocked")),
        unlockedFiber.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next),
        unlockedFiber.extractBool("unlocked").contains(true),
        unlockedFiber.extractInt("unlockedAt").contains(BigInt(1500))
      )
    }
  }

  test("json-encoded: hash time-locked contract (HTLC) with Alice and Bob") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      val ordinal = fixture.ordinal

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        // HTLC: Alice locks funds for Bob with a secret hash
        // Bob can claim with correct preimage before timeout
        // Alice can refund after timeout
        htlcJson =
          """{
          "states": {
            "pending": {
              "id": { "value": "pending" },
              "isFinal": false
            },
            "claimed": {
              "id": { "value": "claimed" },
              "isFinal": true
            },
            "refunded": {
              "id": { "value": "refunded" },
              "isFinal": true
            }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "claimed" },
              "eventName": "claim",
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "event.secretHash" },
                      { "var": "state.hashLock" }
                    ]
                  },
                  {
                    "<": [
                      { "var": "event.currentTime" },
                      { "var": "state.timeout" }
                    ]
                  }
                ]
              },
              "effect": [
                ["claimed", true],
                ["claimedBy", { "var": "event.claimant" }],
                ["claimedAt", { "var": "event.currentTime" }],
                ["secret", { "var": "event.secret" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "pending" },
              "to": { "value": "refunded" },
              "eventName": "refund",
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "event.refunder" },
                      { "var": "state.sender" }
                    ]
                  },
                  {
                    ">=": [
                      { "var": "event.currentTime" },
                      { "var": "state.timeout" }
                    ]
                  }
                ]
              },
              "effect": [
                ["refunded", true],
                ["refundedAt", { "var": "event.currentTime" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        parsedDef <- IO.fromEither(
          decode[StateMachineDefinition](htlcJson).left
            .map(err => new RuntimeException(s"Failed to decode HTLC JSON: $err"))
        )

        // Alice creates HTLC with secret hash
        // Secret: "opensesame", Hash: "secret123hash"
        htlcfiberId <- UUIDGen.randomUUID[IO]
        aliceAddr = registry.addresses(Alice)
        bobAddr = registry.addresses(Bob)

        htlcData = MapValue(
          Map(
            "sender"    -> StrValue(aliceAddr.toString),
            "recipient" -> StrValue(bobAddr.toString),
            "amount"    -> IntValue(1000),
            "hashLock"  -> StrValue("secret123hash"), // Hash of "opensesame"
            "timeout"   -> IntValue(2000) // Expires at time 2000
          )
        )

        htlcFiber <- FiberBuilder(htlcfiberId, ordinal, parsedDef)
          .withState("pending")
          .withDataValue(htlcData)
          .ownedBy(registry, Alice, Bob)
          .build[IO]

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](htlcfiberId, htlcFiber)

        // Test 1: Bob tries to claim with WRONG secret (should fail)
        wrongClaimResult <- inState
          .transition(
            htlcfiberId,
            "claim",
            MapValue(
              Map(
                "secret"      -> StrValue("wrongsecret"),
                "secretHash"  -> StrValue("wronghash"),
                "claimant"    -> StrValue(bobAddr.toString),
                "currentTime" -> IntValue(1000)
              )
            ),
            Bob
          )(registry, combiner)
          .attempt

        _ = expect(wrongClaimResult.isLeft)

        // Test 2: Bob claims with CORRECT secret BEFORE timeout (should succeed)
        claimedState <- inState.transition(
          htlcfiberId,
          "claim",
          MapValue(
            Map(
              "secret"      -> StrValue("opensesame"),
              "secretHash"  -> StrValue("secret123hash"),
              "claimant"    -> StrValue(bobAddr.toString),
              "currentTime" -> IntValue(1500)
            )
          ),
          Bob
        )(registry, combiner)

        claimedFiber = claimedState.fiberRecord(htlcfiberId)

        // Verify Bob successfully claimed
        _ = expect.all(
          claimedFiber.isDefined,
          claimedFiber.map(_.currentState).contains(StateId("claimed")),
          claimedFiber.extractBool("claimed").contains(true),
          claimedFiber.extractString("claimedBy").contains(bobAddr.toString),
          claimedFiber.extractString("secret").contains("opensesame")
        )

        // Test 3: Alice tries to refund AFTER timeout on a NEW HTLC (should succeed)
        htlcCid2 <- UUIDGen.randomUUID[IO]
        htlcData2 = MapValue(
          Map(
            "sender"    -> StrValue(aliceAddr.toString),
            "recipient" -> StrValue(bobAddr.toString),
            "amount"    -> IntValue(500),
            "hashLock"  -> StrValue("anotherhash"),
            "timeout"   -> IntValue(2000)
          )
        )

        htlcFiber2 <- FiberBuilder(htlcCid2, ordinal, parsedDef)
          .withState("pending")
          .withDataValue(htlcData2)
          .ownedBy(registry, Alice, Bob)
          .build[IO]

        inState2 <- DataState(OnChain.genesis, CalculatedState.genesis).withRecord[IO](htlcCid2, htlcFiber2)

        // Alice tries to refund BEFORE timeout (should fail)
        earlyRefundResult <- inState2
          .transition(
            htlcCid2,
            "refund",
            MapValue(
              Map(
                "refunder"    -> StrValue(aliceAddr.toString),
                "currentTime" -> IntValue(1500)
              )
            ),
            Alice
          )(registry, combiner)
          .attempt

        _ = expect(earlyRefundResult.isLeft)

        // Alice refunds AFTER timeout (should succeed)
        refundedState <- inState2.transition(
          htlcCid2,
          "refund",
          MapValue(
            Map(
              "refunder"    -> StrValue(aliceAddr.toString),
              "currentTime" -> IntValue(2500)
            )
          ),
          Alice
        )(registry, combiner)

        refundedFiber = refundedState.fiberRecord(htlcCid2)

      } yield expect.all(
        // Verify claim succeeded
        claimedFiber.isDefined,
        claimedFiber.map(_.currentState).contains(StateId("claimed")),
        claimedFiber.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next),
        claimedFiber.extractBool("claimed").contains(true),
        claimedFiber.extractString("claimedBy").contains(bobAddr.toString),
        claimedFiber.extractString("secret").contains("opensesame"),
        // Verify refund succeeded
        refundedFiber.isDefined,
        refundedFiber.map(_.currentState).contains(StateId("refunded")),
        refundedFiber.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next),
        refundedFiber.extractBool("refunded").contains(true),
        refundedFiber.extractInt("refundedAt").contains(BigInt(2500))
      )
    }
  }

  test("json-encoded: supply chain with escrow, inspection, and insurance") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      val ordinal = fixture.ordinal

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        orderfiberId      <- UUIDGen.randomUUID[IO]
        escrowfiberId     <- UUIDGen.randomUUID[IO]
        shippingfiberId   <- UUIDGen.randomUUID[IO]
        inspectionfiberId <- UUIDGen.randomUUID[IO]
        insurancefiberId  <- UUIDGen.randomUUID[IO]

        orderJson =
          s"""{
          "states": {
            "placed": { "id": { "value": "placed" }, "isFinal": false },
            "funded": { "id": { "value": "funded" }, "isFinal": false },
            "shipped": { "id": { "value": "shipped" }, "isFinal": false },
            "in_transit": { "id": { "value": "in_transit" }, "isFinal": false },
            "delivered": { "id": { "value": "delivered" }, "isFinal": false },
            "inspecting": { "id": { "value": "inspecting" }, "isFinal": false },
            "completed": { "id": { "value": "completed" }, "isFinal": true },
            "disputed": { "id": { "value": "disputed" }, "isFinal": true }
          },
          "initialState": { "value": "placed" },
          "transitions": [
            {
              "from": { "value": "placed" },
              "to": { "value": "funded" },
              "eventName": "confirm_funding",
              "guard": {
                "===": [
                  { "var": "machines.${escrowfiberId}.state.status" },
                  "locked"
                ]
              },
              "effect": [
                ["status", "funded"],
                ["fundedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${escrowfiberId}"]
            },
            {
              "from": { "value": "funded" },
              "to": { "value": "shipped" },
              "eventName": "ship",
              "guard": true,
              "effect": [
                ["status", "shipped"],
                ["shippedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "shipped" },
              "to": { "value": "in_transit" },
              "eventName": "accept_shipment",
              "guard": {
                "===": [
                  { "var": "machines.${shippingfiberId}.state.status" },
                  "picked_up"
                ]
              },
              "effect": [
                ["status", "in_transit"],
                ["transitStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${shippingfiberId}"]
            },
            {
              "from": { "value": "in_transit" },
              "to": { "value": "delivered" },
              "eventName": "confirm_delivery",
              "guard": {
                "===": [
                  { "var": "machines.${shippingfiberId}.state.status" },
                  "delivered"
                ]
              },
              "effect": [
                ["status", "delivered"],
                ["deliveredAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${shippingfiberId}"]
            },
            {
              "from": { "value": "delivered" },
              "to": { "value": "inspecting" },
              "eventName": "start_inspection",
              "guard": {
                "===": [
                  { "var": "machines.${inspectionfiberId}.state.status" },
                  "scheduled"
                ]
              },
              "effect": [
                ["status", "inspecting"],
                ["inspectionStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionfiberId}"]
            },
            {
              "from": { "value": "inspecting" },
              "to": { "value": "completed" },
              "eventName": "complete_order",
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "machines.${inspectionfiberId}.state.result" },
                      "passed"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${escrowfiberId}.state.status" },
                      "released"
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "completed"],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionfiberId}", "${escrowfiberId}"]
            },
            {
              "from": { "value": "inspecting" },
              "to": { "value": "disputed" },
              "eventName": "dispute",
              "guard": {
                "===": [
                  { "var": "machines.${inspectionfiberId}.state.result" },
                  "failed"
                ]
              },
              "effect": [
                ["status", "disputed"],
                ["disputedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionfiberId}"]
            }
          ]
        }"""

        escrowJson =
          s"""{
          "states": {
            "empty": { "id": { "value": "empty" }, "isFinal": false },
            "locked": { "id": { "value": "locked" }, "isFinal": false },
            "held": { "id": { "value": "held" }, "isFinal": false },
            "releasing": { "id": { "value": "releasing" }, "isFinal": false },
            "released": { "id": { "value": "released" }, "isFinal": true },
            "refunding": { "id": { "value": "refunding" }, "isFinal": false },
            "refunded": { "id": { "value": "refunded" }, "isFinal": true }
          },
          "initialState": { "value": "empty" },
          "transitions": [
            {
              "from": { "value": "empty" },
              "to": { "value": "locked" },
              "eventName": "lock_funds",
              "guard": {
                ">=": [
                  { "var": "event.amount" },
                  { "var": "state.requiredAmount" }
                ]
              },
              "effect": [
                ["status", "locked"],
                ["lockedAmount", { "var": "event.amount" }],
                ["lockedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "locked" },
              "to": { "value": "held" },
              "eventName": "hold",
              "guard": {
                "===": [
                  { "var": "machines.${shippingfiberId}.state.status" },
                  "picked_up"
                ]
              },
              "effect": [
                ["status", "held"],
                ["holdUntil", { "+": [{ "var": "event.timestamp" }, 86400] }]
              ],
              "dependencies": ["${shippingfiberId}"]
            },
            {
              "from": { "value": "held" },
              "to": { "value": "releasing" },
              "eventName": "release",
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "machines.${orderfiberId}.state.status" },
                      "inspecting"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${inspectionfiberId}.state.result" },
                      "passed"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${shippingfiberId}.state.condition" },
                      "good"
                    ]
                  },
                  {
                    ">": [
                      { "var": "event.timestamp" },
                      { "var": "state.holdUntil" }
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${insurancefiberId}.state.hasClaim" },
                      false
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "releasing"],
                ["releaseInitiatedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${orderfiberId}", "${inspectionfiberId}", "${shippingfiberId}", "${insurancefiberId}"]
            },
            {
              "from": { "value": "releasing" },
              "to": { "value": "released" },
              "eventName": "finalize_release",
              "guard": true,
              "effect": [
                ["status", "released"],
                ["releasedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "held" },
              "to": { "value": "refunding" },
              "eventName": "refund",
              "guard": {
                "or": [
                  {
                    "===": [
                      { "var": "machines.${inspectionfiberId}.state.result" },
                      "failed"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${shippingfiberId}.state.status" },
                      "lost"
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "refunding"],
                ["refundInitiatedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionfiberId}", "${shippingfiberId}"]
            },
            {
              "from": { "value": "refunding" },
              "to": { "value": "refunded" },
              "eventName": "finalize_refund",
              "guard": true,
              "effect": [
                ["status", "refunded"],
                ["refundedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        shippingJson =
          s"""{
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "picked_up": { "id": { "value": "picked_up" }, "isFinal": false },
            "in_transit": { "id": { "value": "in_transit" }, "isFinal": false },
            "customs": { "id": { "value": "customs" }, "isFinal": false },
            "out_for_delivery": { "id": { "value": "out_for_delivery" }, "isFinal": false },
            "delivered": { "id": { "value": "delivered" }, "isFinal": true },
            "lost": { "id": { "value": "lost" }, "isFinal": true },
            "damaged": { "id": { "value": "damaged" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "picked_up" },
              "eventName": "pickup",
              "guard": true,
              "effect": [
                ["status", "picked_up"],
                ["pickedUpAt", { "var": "event.timestamp" }],
                ["condition", "good"],
                ["lastGPS", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "picked_up" },
              "to": { "value": "in_transit" },
              "eventName": "checkpoint",
              "guard": true,
              "effect": [
                ["status", "in_transit"],
                ["lastGPS", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "in_transit" },
              "to": { "value": "customs" },
              "eventName": "enter_customs",
              "guard": true,
              "effect": [
                ["status", "customs"],
                ["enteredCustomsAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "customs" },
              "to": { "value": "out_for_delivery" },
              "eventName": "clear_customs",
              "guard": {
                "===": [
                  { "var": "machines.${insurancefiberId}.state.status" },
                  "active"
                ]
              },
              "effect": [
                ["status", "out_for_delivery"],
                ["clearedCustomsAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${insurancefiberId}"]
            },
            {
              "from": { "value": "out_for_delivery" },
              "to": { "value": "delivered" },
              "eventName": "deliver",
              "guard": true,
              "effect": [
                ["status", "delivered"],
                ["deliveredAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "in_transit" },
              "to": { "value": "lost" },
              "eventName": "report_lost",
              "guard": {
                ">": [
                  { "-": [{ "var": "event.timestamp" }, { "var": "state.lastGPS" }] },
                  172800
                ]
              },
              "effect": [
                ["status", "lost"],
                ["lostAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "in_transit" },
              "to": { "value": "damaged" },
              "eventName": "report_damage",
              "guard": {
                "===": [
                  { "var": "event.tampered" },
                  true
                ]
              },
              "effect": [
                ["status", "damaged"],
                ["damagedAt", { "var": "event.timestamp" }],
                ["tampered", true]
              ],
              "dependencies": []
            }
          ]
        }"""

        inspectionJson =
          """{
          "states": {
            "inactive": { "id": { "value": "inactive" }, "isFinal": false },
            "scheduled": { "id": { "value": "scheduled" }, "isFinal": false },
            "in_progress": { "id": { "value": "in_progress" }, "isFinal": false },
            "passed": { "id": { "value": "passed" }, "isFinal": true },
            "failed": { "id": { "value": "failed" }, "isFinal": true }
          },
          "initialState": { "value": "inactive" },
          "transitions": [
            {
              "from": { "value": "inactive" },
              "to": { "value": "scheduled" },
              "eventName": "schedule",
              "guard": true,
              "effect": [
                ["status", "scheduled"],
                ["scheduledAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "scheduled" },
              "to": { "value": "in_progress" },
              "eventName": "begin_inspection",
              "guard": true,
              "effect": [
                ["status", "in_progress"],
                ["beganAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "in_progress" },
              "to": { "value": "passed" },
              "eventName": "complete_inspection",
              "guard": {
                "and": [
                  {
                    ">=": [
                      { "var": "event.qualityScore" },
                      7
                    ]
                  },
                  {
                    "===": [
                      { "var": "event.damaged" },
                      false
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "passed"],
                ["result", "passed"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "in_progress" },
              "to": { "value": "failed" },
              "eventName": "complete_inspection",
              "guard": {
                "or": [
                  {
                    "<": [
                      { "var": "event.qualityScore" },
                      7
                    ]
                  },
                  {
                    "===": [
                      { "var": "event.damaged" },
                      true
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "failed"],
                ["result", "failed"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["damageScore", { "var": "event.damageScore" }],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        insuranceJson =
          s"""{
          "states": {
            "active": { "id": { "value": "active" }, "isFinal": false },
            "claim_filed": { "id": { "value": "claim_filed" }, "isFinal": false },
            "investigating": { "id": { "value": "investigating" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": false },
            "denied": { "id": { "value": "denied" }, "isFinal": true },
            "settled": { "id": { "value": "settled" }, "isFinal": true }
          },
          "initialState": { "value": "active" },
          "transitions": [
            {
              "from": { "value": "active" },
              "to": { "value": "claim_filed" },
              "eventName": "file_claim",
              "guard": {
                "or": [
                  {
                    "===": [
                      { "var": "machines.${shippingfiberId}.state.status" },
                      "lost"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${shippingfiberId}.state.status" },
                      "damaged"
                    ]
                  },
                  {
                    "===": [
                      { "var": "machines.${inspectionfiberId}.state.result" },
                      "failed"
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "claim_filed"],
                ["hasClaim", true],
                ["claimFiledAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${shippingfiberId}", "${inspectionfiberId}"]
            },
            {
              "from": { "value": "claim_filed" },
              "to": { "value": "investigating" },
              "eventName": "investigate",
              "guard": true,
              "effect": [
                ["status", "investigating"],
                ["investigatingAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "investigating" },
              "to": { "value": "approved" },
              "eventName": "decide",
              "guard": {
                "or": [
                  {
                    "and": [
                      {
                        "===": [
                          { "var": "machines.${shippingfiberId}.state.tampered" },
                          true
                        ]
                      },
                      {
                        "<": [
                          { "-": [{ "var": "event.timestamp" }, { "var": "machines.${shippingfiberId}.state.lastGPS" }] },
                          86400
                        ]
                      }
                    ]
                  },
                  {
                    "and": [
                      {
                        "===": [
                          { "var": "machines.${inspectionfiberId}.state.result" },
                          "failed"
                        ]
                      },
                      {
                        ">=": [
                          { "var": "machines.${inspectionfiberId}.state.damageScore" },
                          7
                        ]
                      }
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "approved"],
                ["approvedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${shippingfiberId}", "${inspectionfiberId}"]
            },
            {
              "from": { "value": "investigating" },
              "to": { "value": "denied" },
              "eventName": "decide",
              "guard": {
                "!": [
                  {
                    "or": [
                      {
                        "and": [
                          {
                            "===": [
                              { "var": "machines.${shippingfiberId}.state.tampered" },
                              true
                            ]
                          },
                          {
                            "<": [
                              { "-": [{ "var": "event.timestamp" }, { "var": "machines.${shippingfiberId}.state.lastGPS" }] },
                              86400
                            ]
                          }
                        ]
                      },
                      {
                        "and": [
                          {
                            "===": [
                              { "var": "machines.${inspectionfiberId}.state.result" },
                              "failed"
                            ]
                          },
                          {
                            ">=": [
                              { "var": "machines.${inspectionfiberId}.state.damageScore" },
                              7
                            ]
                          }
                        ]
                      }
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "denied"],
                ["deniedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${shippingfiberId}", "${inspectionfiberId}"]
            },
            {
              "from": { "value": "approved" },
              "to": { "value": "settled" },
              "eventName": "settle",
              "guard": true,
              "effect": [
                ["status", "settled"],
                ["settledAt", { "var": "event.timestamp" }],
                ["payoutAmount", { "var": "event.payoutAmount" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        orderDef <- IO.fromEither(
          decode[StateMachineDefinition](orderJson).left
            .map(err => new RuntimeException(s"Failed to decode order JSON: $err"))
        )

        escrowDef <- IO.fromEither(
          decode[StateMachineDefinition](escrowJson).left
            .map(err => new RuntimeException(s"Failed to decode escrow JSON: $err"))
        )

        shippingDef <- IO.fromEither(
          decode[StateMachineDefinition](shippingJson).left
            .map(err => new RuntimeException(s"Failed to decode shipping JSON: $err"))
        )

        inspectionDef <- IO.fromEither(
          decode[StateMachineDefinition](inspectionJson).left
            .map(err => new RuntimeException(s"Failed to decode inspection JSON: $err"))
        )

        insuranceDef <- IO.fromEither(
          decode[StateMachineDefinition](insuranceJson).left
            .map(err => new RuntimeException(s"Failed to decode insurance JSON: $err"))
        )

        orderFiber <- FiberBuilder(orderfiberId, ordinal, orderDef)
          .withState("placed")
          .withData(
            "orderId" -> StrValue("ORDER-001"),
            "buyer"   -> StrValue(registry.addresses(Bob).toString),
            "seller"  -> StrValue(registry.addresses(Alice).toString),
            "amount"  -> IntValue(5000),
            "status"  -> StrValue("placed")
          )
          .ownedBy(registry, Alice, Bob)
          .build[IO]

        escrowFiber <- FiberBuilder(escrowfiberId, ordinal, escrowDef)
          .withState("empty")
          .withData(
            "requiredAmount" -> IntValue(5000),
            "status"         -> StrValue("empty")
          )
          .ownedBy(registry, Alice, Bob)
          .build[IO]

        shippingFiber <- FiberBuilder(shippingfiberId, ordinal, shippingDef)
          .withState("pending")
          .withData(
            "trackingNumber" -> StrValue("TRACK-12345"),
            "carrier"        -> StrValue("FastShip"),
            "status"         -> StrValue("pending")
          )
          .ownedBy(registry, Alice)
          .build[IO]

        inspectionFiber <- FiberBuilder(inspectionfiberId, ordinal, inspectionDef)
          .withState("inactive")
          .withData(
            "inspector" -> StrValue(registry.addresses(Charlie).toString),
            "status"    -> StrValue("inactive")
          )
          .ownedBy(registry, Charlie)
          .build[IO]

        insuranceFiber <- FiberBuilder(insurancefiberId, ordinal, insuranceDef)
          .withState("active")
          .withData(
            "policyNumber"   -> StrValue("INS-999"),
            "coverageAmount" -> IntValue(5000),
            "status"         -> StrValue("active"),
            "hasClaim"       -> BoolValue(false)
          )
          .ownedBy(registry, Alice, Bob)
          .build[IO]

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(
            orderfiberId      -> orderFiber,
            escrowfiberId     -> escrowFiber,
            shippingfiberId   -> shippingFiber,
            inspectionfiberId -> inspectionFiber,
            insurancefiberId  -> insuranceFiber
          )
        )

        // Step 1: Lock funds in escrow
        state1 <- inState.transition(
          escrowfiberId,
          "lock_funds",
          MapValue(Map("amount" -> IntValue(5000), "timestamp" -> IntValue(1000))),
          Bob
        )(registry, combiner)

        // Step 2: Confirm funding on order
        state2 <- state1.transition(
          orderfiberId,
          "confirm_funding",
          MapValue(Map("timestamp" -> IntValue(1100))),
          Alice
        )(registry, combiner)

        // Step 3: Ship order
        state3 <- state2.transition(
          orderfiberId,
          "ship",
          MapValue(Map("timestamp" -> IntValue(1200))),
          Alice
        )(registry, combiner)

        // Step 4: Pickup shipping
        state4 <- state3.transition(
          shippingfiberId,
          "pickup",
          MapValue(Map("timestamp" -> IntValue(1300))),
          Alice
        )(registry, combiner)

        // Step 5: Hold escrow
        state5 <- state4.transition(
          escrowfiberId,
          "hold",
          MapValue(Map("timestamp" -> IntValue(1400))),
          Alice
        )(registry, combiner)

        // Step 6: Accept shipment on order
        state6 <- state5.transition(
          orderfiberId,
          "accept_shipment",
          MapValue(Map("timestamp" -> IntValue(1500))),
          Alice
        )(registry, combiner)

        // Step 7: Checkpoint shipping
        state7 <- state6.transition(
          shippingfiberId,
          "checkpoint",
          MapValue(Map("timestamp" -> IntValue(1600))),
          Alice
        )(registry, combiner)

        // Step 8: Enter customs
        state8 <- state7.transition(
          shippingfiberId,
          "enter_customs",
          MapValue(Map("timestamp" -> IntValue(2000))),
          Alice
        )(registry, combiner)

        // Step 9: Clear customs
        state9 <- state8.transition(
          shippingfiberId,
          "clear_customs",
          MapValue(Map("timestamp" -> IntValue(3000))),
          Alice
        )(registry, combiner)

        // Step 10: Deliver
        state10 <- state9.transition(
          shippingfiberId,
          "deliver",
          MapValue(Map("timestamp" -> IntValue(4000))),
          Alice
        )(registry, combiner)

        // Step 11: Confirm delivery on order
        state11 <- state10.transition(
          orderfiberId,
          "confirm_delivery",
          MapValue(Map("timestamp" -> IntValue(4100))),
          Alice
        )(registry, combiner)

        // Step 12: Schedule inspection
        state12 <- state11.transition(
          inspectionfiberId,
          "schedule",
          MapValue(Map("timestamp" -> IntValue(4200))),
          Charlie
        )(registry, combiner)

        // Step 13: Start inspection on order
        state13 <- state12.transition(
          orderfiberId,
          "start_inspection",
          MapValue(Map("timestamp" -> IntValue(4300))),
          Alice
        )(registry, combiner)

        // Step 14: Begin inspection
        state14 <- state13.transition(
          inspectionfiberId,
          "begin_inspection",
          MapValue(Map("timestamp" -> IntValue(4400))),
          Charlie
        )(registry, combiner)

        // Step 15: Complete inspection (passed)
        state15 <- state14.transition(
          inspectionfiberId,
          "complete_inspection",
          MapValue(
            Map(
              "timestamp"    -> IntValue(5000),
              "qualityScore" -> IntValue(9),
              "damaged"      -> BoolValue(false)
            )
          ),
          Charlie
        )(registry, combiner)

        // Step 16: Release escrow
        state16 <- state15.transition(
          escrowfiberId,
          "release",
          MapValue(Map("timestamp" -> IntValue(90000))),
          Alice
        )(registry, combiner)

        // Step 17: Finalize release
        state17 <- state16.transition(
          escrowfiberId,
          "finalize_release",
          MapValue(Map("timestamp" -> IntValue(90100))),
          Alice
        )(registry, combiner)

        // Step 18: Complete order
        finalState <- state17.transition(
          orderfiberId,
          "complete_order",
          MapValue(Map("timestamp" -> IntValue(90200))),
          Alice
        )(registry, combiner)

        finalOrder = finalState.fiberRecord(orderfiberId)
        finalEscrow = finalState.fiberRecord(escrowfiberId)
        finalShipping = finalState.fiberRecord(shippingfiberId)
        finalInspection = finalState.fiberRecord(inspectionfiberId)
        finalInsurance = finalState.fiberRecord(insurancefiberId)

      } yield expect.all(
        finalOrder.isDefined,
        finalOrder.map(_.currentState).contains(StateId("completed")),
        finalOrder.extractString("status").contains("completed"),
        finalEscrow.isDefined,
        finalEscrow.map(_.currentState).contains(StateId("released")),
        finalEscrow.extractString("status").contains("released"),
        finalShipping.isDefined,
        finalShipping.map(_.currentState).contains(StateId("delivered")),
        finalInspection.isDefined,
        finalInspection.map(_.currentState).contains(StateId("passed")),
        finalInspection.extractString("result").contains("passed"),
        finalInsurance.isDefined,
        finalInsurance.map(_.currentState).contains(StateId("active"))
      )
    }
  }

  test("json-encoded: multi-stream governance with voting and actions") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      val ordinal = fixture.ordinal

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        // Get proposal CID early so we can reference it in action stream
        proposalfiberId <- UUIDGen.randomUUID[IO]

        // Define Proposal state machine (the main management/governance stream)
        proposalJson =
          """{
          "states": {
            "proposed": {
              "id": { "value": "proposed" },
              "isFinal": false
            },
            "open": {
              "id": { "value": "open" },
              "isFinal": false
            },
            "closing": {
              "id": { "value": "closing" },
              "isFinal": false
            },
            "finalized": {
              "id": { "value": "finalized" },
              "isFinal": true
            },
            "aborted": {
              "id": { "value": "aborted" },
              "isFinal": true
            }
          },
          "initialState": { "value": "proposed" },
          "transitions": [
            {
              "from": { "value": "proposed" },
              "to": { "value": "open" },
              "eventName": "collect",
              "guard": { "var": "state.hasQuorum" },
              "effect": [
                ["status", "open"],
                ["openedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "open" },
              "to": { "value": "closing" },
              "eventName": "close",
              "guard": {
                ">=": [
                  { "var": "event.timestamp" },
                  { "var": "state.votingDeadline" }
                ]
              },
              "effect": [
                ["status", "closing"],
                ["closedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "closing" },
              "to": { "value": "finalized" },
              "eventName": "collect",
              "guard": {
                ">=": [
                  { "var": "state.yesVotes" },
                  { "var": "state.requiredVotes" }
                ]
              },
              "effect": [
                ["status", "finalized"],
                ["finalizedAt", { "var": "event.timestamp" }],
                ["result", "passed"]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "proposed" },
              "to": { "value": "aborted" },
              "eventName": "abort",
              "guard": true,
              "effect": [
                ["status", "aborted"],
                ["abortedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Define Action stream - can only accept submissions when proposal is "open"
        actionJson =
          s"""{
          "states": {
            "pending": {
              "id": { "value": "pending" },
              "isFinal": false
            },
            "submitted": {
              "id": { "value": "submitted" },
              "isFinal": false
            },
            "rejected": {
              "id": { "value": "rejected" },
              "isFinal": true
            }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "submitted" },
              "eventName": "submit",
              "guard": {
                "===": [
                  { "var": "machines.${proposalfiberId}.state.status" },
                  "open"
                ]
              },
              "effect": [
                ["submitted", true],
                ["submittedAt", { "var": "event.timestamp" }],
                ["submitter", { "var": "event.submitter" }],
                ["actionData", { "var": "event.actionData" }]
              ],
              "dependencies": ["${proposalfiberId}"]
            },
            {
              "from": { "value": "pending" },
              "to": { "value": "rejected" },
              "eventName": "submit",
              "guard": {
                "!==": [
                  { "var": "machines.${proposalfiberId}.state.status" },
                  "open"
                ]
              },
              "effect": [
                ["rejected", true],
                ["reason", "proposal not open"]
              ],
              "dependencies": ["${proposalfiberId}"]
            }
          ]
        }"""

        // Define Voter state machine (individual vote streams)
        voterJson =
          """{
          "states": {
            "idle": {
              "id": { "value": "idle" },
              "isFinal": false
            },
            "voted": {
              "id": { "value": "voted" },
              "isFinal": false
            }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "voted" },
              "eventName": "vote",
              "guard": true,
              "effect": [
                ["hasVoted", true],
                ["vote", { "var": "event.vote" }],
                ["votedAt", { "var": "event.timestamp" }],
                ["proposalId", { "var": "event.proposalId" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        proposalDef <- IO.fromEither(
          decode[StateMachineDefinition](proposalJson).left
            .map(err => new RuntimeException(s"Failed to decode proposal JSON: $err"))
        )

        voterDef <- IO.fromEither(
          decode[StateMachineDefinition](voterJson).left
            .map(err => new RuntimeException(s"Failed to decode voter JSON: $err"))
        )

        actionDef <- IO.fromEither(
          decode[StateMachineDefinition](actionJson).left
            .map(err => new RuntimeException(s"Failed to decode action JSON: $err"))
        )

        proposalFiber <- FiberBuilder(proposalfiberId, ordinal, proposalDef)
          .withState("proposed")
          .withData(
            "title"          -> StrValue("Increase treasury allocation"),
            "hasQuorum"      -> BoolValue(true),
            "votingDeadline" -> IntValue(2000),
            "requiredVotes"  -> IntValue(2),
            "yesVotes"       -> IntValue(0),
            "noVotes"        -> IntValue(0),
            "status"         -> StrValue("proposed")
          )
          .ownedBy(registry, Alice)
          .build[IO]

        // Create voter fibers for Alice, Bob, and Charlie
        alicefiberId <- UUIDGen.randomUUID[IO]

        aliceVoterFiber <- FiberBuilder(alicefiberId, ordinal, voterDef)
          .withState("idle")
          .withData(
            "voter"       -> StrValue(registry.addresses(Alice).toString),
            "votingPower" -> IntValue(1)
          )
          .ownedBy(registry, Alice)
          .build[IO]

        bobfiberId <- UUIDGen.randomUUID[IO]

        bobVoterFiber <- FiberBuilder(bobfiberId, ordinal, voterDef)
          .withState("idle")
          .withData(
            "voter"       -> StrValue(registry.addresses(Bob).toString),
            "votingPower" -> IntValue(1)
          )
          .ownedBy(registry, Bob)
          .build[IO]

        charliefiberId <- UUIDGen.randomUUID[IO]

        charlieVoterFiber <- FiberBuilder(charliefiberId, ordinal, voterDef)
          .withState("idle")
          .withData(
            "voter"       -> StrValue(registry.addresses(Charlie).toString),
            "votingPower" -> IntValue(1)
          )
          .ownedBy(registry, Charlie)
          .build[IO]

        // Create first action fiber (for early submission test - will be rejected)
        earlyActionfiberId <- UUIDGen.randomUUID[IO]

        earlyActionFiber <- FiberBuilder(earlyActionfiberId, ordinal, actionDef)
          .withState("pending")
          .withData(
            "proposalId" -> StrValue(proposalfiberId.toString),
            "submitter"  -> StrValue(registry.addresses(Charlie).toString)
          )
          .ownedBy(registry, Charlie)
          .build[IO]

        // Create second action fiber (for valid submission test)
        validActionfiberId <- UUIDGen.randomUUID[IO]

        validActionFiberInit <- FiberBuilder(validActionfiberId, ordinal, actionDef)
          .withState("pending")
          .withData(
            "proposalId" -> StrValue(proposalfiberId.toString),
            "submitter"  -> StrValue(registry.addresses(Charlie).toString)
          )
          .ownedBy(registry, Charlie)
          .build[IO]

        // Initial state with all machines (proposal, voters, and both action fibers)
        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(
            proposalfiberId    -> proposalFiber,
            alicefiberId       -> aliceVoterFiber,
            bobfiberId         -> bobVoterFiber,
            charliefiberId     -> charlieVoterFiber,
            earlyActionfiberId -> earlyActionFiber,
            validActionfiberId -> validActionFiberInit
          )
        )

        // Step 0: Try to submit action BEFORE proposal is open (should transition to rejected)
        stateAfterEarlySubmit <- inState.transition(
          earlyActionfiberId,
          "submit",
          MapValue(
            Map(
              "timestamp"  -> IntValue(900),
              "submitter"  -> StrValue(registry.addresses(Charlie).toString),
              "actionData" -> StrValue("early submission data")
            )
          ),
          Charlie
        )(registry, combiner)

        earlyActionFiberResult = stateAfterEarlySubmit.fiberRecord(earlyActionfiberId)

        // Verify early submission was rejected
        _ = expect.all(
          earlyActionFiberResult.isDefined,
          earlyActionFiberResult.map(_.currentState).contains(StateId("rejected")),
          earlyActionFiberResult.extractBool("rejected").contains(true),
          earlyActionFiberResult.extractString("reason").contains("proposal not open")
        )

        // Step 1: Open the proposal (proposed -> open)
        state1 <- inState.transition(
          proposalfiberId,
          "collect",
          MapValue(Map("timestamp" -> IntValue(1000))),
          Alice
        )(registry, combiner)

        proposalAfterOpen = state1.fiberRecord(proposalfiberId)

        // Step 1.5: Submit action AFTER proposal is open (should succeed)
        state1_5 <- state1.transition(
          validActionfiberId,
          "submit",
          MapValue(
            Map(
              "timestamp"  -> IntValue(1050),
              "submitter"  -> StrValue(registry.addresses(Charlie).toString),
              "actionData" -> StrValue("valid submission data")
            )
          ),
          Charlie
        )(registry, combiner)

        validActionFiberResult = state1_5.fiberRecord(validActionfiberId)

        // Step 2: Alice votes YES
        state2 <- state1_5.transition(
          alicefiberId,
          "vote",
          MapValue(
            Map(
              "vote"       -> StrValue("yes"),
              "timestamp"  -> IntValue(1100),
              "proposalId" -> StrValue(proposalfiberId.toString)
            )
          ),
          Alice
        )(registry, combiner)

        aliceAfterVote = state2.fiberRecord(alicefiberId)

        // Step 3: Bob votes YES
        state3 <- state2.transition(
          bobfiberId,
          "vote",
          MapValue(
            Map(
              "vote"       -> StrValue("yes"),
              "timestamp"  -> IntValue(1200),
              "proposalId" -> StrValue(proposalfiberId.toString)
            )
          ),
          Bob
        )(registry, combiner)

        bobAfterVote = state3.fiberRecord(bobfiberId)

        // Step 4: Manually update proposal with vote counts (simulating aggregation)
        // In a real system, this would happen via cross-machine dependencies
        proposalWithVotes = state3
          .fiberRecord(proposalfiberId)
          .map { p =>
            val updatedData = MapValue(
              Map(
                "title"          -> StrValue("Increase treasury allocation"),
                "hasQuorum"      -> BoolValue(true),
                "votingDeadline" -> IntValue(2000),
                "requiredVotes"  -> IntValue(2),
                "yesVotes"       -> IntValue(2), // Updated: Alice + Bob
                "noVotes"        -> IntValue(0),
                "status"         -> StrValue("open")
              )
            )
            p.copy(stateData = updatedData)
          }

        state4 <- proposalWithVotes.fold(state3.pure[IO]) { p =>
          for {
            h      <- p.stateData.computeDigest
            result <- state3.withRecord[IO](proposalfiberId, p.copy(stateDataHash = h))
          } yield result
        }

        // Step 5: Close voting (open -> closing)
        state5 <- state4.transition(
          proposalfiberId,
          "close",
          MapValue(Map("timestamp" -> IntValue(2100))),
          Alice
        )(registry, combiner)

        proposalAfterClose = state5.fiberRecord(proposalfiberId)

        // Step 6: Finalize (closing -> finalized)
        finalState <- state5.transition(
          proposalfiberId,
          "collect",
          MapValue(Map("timestamp" -> IntValue(2200))),
          Alice
        )(registry, combiner)

        finalProposal = finalState.fiberRecord(proposalfiberId)

      } yield expect.all(
        // Verify valid action submission succeeded when proposal was open
        validActionFiberResult.isDefined,
        validActionFiberResult.map(_.currentState).contains(StateId("submitted")),
        validActionFiberResult.extractBool("submitted").contains(true),
        // Verify proposal opened
        proposalAfterOpen.isDefined,
        proposalAfterOpen.map(_.currentState).contains(StateId("open")),
        // Verify Alice voted
        aliceAfterVote.isDefined,
        aliceAfterVote.map(_.currentState).contains(StateId("voted")),
        aliceAfterVote.extractString("vote").contains("yes"),
        // Verify Bob voted
        bobAfterVote.isDefined,
        bobAfterVote.map(_.currentState).contains(StateId("voted")),
        bobAfterVote.extractString("vote").contains("yes"),
        // Verify proposal closed
        proposalAfterClose.isDefined,
        proposalAfterClose.map(_.currentState).contains(StateId("closing")),
        // Verify proposal finalized with passing result
        finalProposal.isDefined,
        finalProposal.map(_.currentState).contains(StateId("finalized")),
        finalProposal.extractString("result").contains("passed")
      )
    }
  }

}
