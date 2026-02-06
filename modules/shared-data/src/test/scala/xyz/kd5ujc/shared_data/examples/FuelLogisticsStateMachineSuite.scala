package xyz.kd5ujc.shared_data.examples

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
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

object FuelLogisticsStateMachineSuite extends SimpleIOSuite {

  import DataStateTestOps._
  import TestImports.optionFiberRecordOps

  test("fuel logistics: complete contract lifecycle with GPS tracking") {
    TestFixture.resource(Set(Alice, Bob, Charlie, Dave)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      val ordinal = fixture.ordinal

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        contractfiberId   <- UUIDGen.randomUUID[IO]
        gpsTrackerfiberId <- UUIDGen.randomUUID[IO]
        supplierfiberId   <- UUIDGen.randomUUID[IO]
        inspectionfiberId <- UUIDGen.randomUUID[IO]

        // FuelContract: Main contract state machine
        // States: draft -> supplier_review -> supplier_approved -> gps_ready ->
        //         in_transit -> delivered -> quality_check -> inspected -> settling -> settled
        contractJson =
          s"""{
          "states": {
            "draft": { "id": { "value": "draft" }, "isFinal": false },
            "supplier_review": { "id": { "value": "supplier_review" }, "isFinal": false },
            "supplier_approved": { "id": { "value": "supplier_approved" }, "isFinal": false },
            "gps_ready": { "id": { "value": "gps_ready" }, "isFinal": false },
            "in_transit": { "id": { "value": "in_transit" }, "isFinal": false },
            "delivered": { "id": { "value": "delivered" }, "isFinal": false },
            "quality_check": { "id": { "value": "quality_check" }, "isFinal": false },
            "inspected": { "id": { "value": "inspected" }, "isFinal": false },
            "settling": { "id": { "value": "settling" }, "isFinal": false },
            "settled": { "id": { "value": "settled" }, "isFinal": true },
            "REJECTED": { "id": { "value": "REJECTED" }, "isFinal": true }
          },
          "initialState": { "value": "draft" },
          "transitions": [
            {
              "from": { "value": "draft" },
              "to": { "value": "supplier_review" },
              "eventName": "submit_for_approval",
              "guard": {
                "and": [
                  { ">=": [ { "var": "state.fuelQuantity" }, 1000 ] },
                  { ">=": [ { "var": "state.pricePerLiter" }, 0 ] }
                ]
              },
              "effect": [
                ["status", "supplier_review"],
                ["submittedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "supplier_review" },
              "to": { "value": "supplier_approved" },
              "eventName": "supplier_approved",
              "guard": {
                "===": [
                  { "var": "machines.${supplierfiberId}.state.status" },
                  "approved"
                ]
              },
              "effect": [
                ["status", "supplier_approved"],
                ["supplierApprovedAt", { "var": "event.timestamp" }],
                ["approvedSupplier", { "var": "machines.${supplierfiberId}.state.supplierName" }]
              ],
              "dependencies": ["${supplierfiberId}"]
            },
            {
              "from": { "value": "supplier_approved" },
              "to": { "value": "gps_ready" },
              "eventName": "prepare_shipment",
              "guard": {
                "===": [
                  { "var": "machines.${gpsTrackerfiberId}.state.status" },
                  "ACTIVE"
                ]
              },
              "effect": [
                ["status", "gps_ready"],
                ["shipmentPreparedAt", { "var": "event.timestamp" }],
                ["vehicleId", { "var": "event.vehicleId" }],
                ["driverId", { "var": "event.driverId" }]
              ],
              "dependencies": ["${gpsTrackerfiberId}"]
            },
            {
              "from": { "value": "gps_ready" },
              "to": { "value": "in_transit" },
              "eventName": "begin_transit",
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "machines.${gpsTrackerfiberId}.state.status" },
                      "tracking"
                    ]
                  },
                  {
                    ">": [
                      { "var": "machines.${gpsTrackerfiberId}.state.dataPointCount" },
                      0
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "in_transit"],
                ["transitStartedAt", { "var": "event.timestamp" }],
                ["estimatedArrival", { "var": "event.estimatedArrival" }]
              ],
              "dependencies": ["${gpsTrackerfiberId}"]
            },
            {
              "from": { "value": "in_transit" },
              "to": { "value": "delivered" },
              "eventName": "confirm_delivery",
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "machines.${gpsTrackerfiberId}.state.status" },
                      "stopped"
                    ]
                  },
                  {
                    ">=": [
                      { "var": "machines.${gpsTrackerfiberId}.state.dataPointCount" },
                      3
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "delivered"],
                ["deliveredAt", { "var": "event.timestamp" }],
                ["totalDistance", { "var": "machines.${gpsTrackerfiberId}.state.totalDistance" }],
                ["gpsDataPoints", { "var": "machines.${gpsTrackerfiberId}.state.dataPointCount" }]
              ],
              "dependencies": ["${gpsTrackerfiberId}"]
            },
            {
              "from": { "value": "delivered" },
              "to": { "value": "quality_check" },
              "eventName": "initiate_inspection",
              "guard": {
                "===": [
                  { "var": "machines.${inspectionfiberId}.state.status" },
                  "scheduled"
                ]
              },
              "effect": [
                ["status", "quality_check"],
                ["inspectionInitiatedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${inspectionfiberId}"]
            },
            {
              "from": { "value": "quality_check" },
              "to": { "value": "inspected" },
              "eventName": "inspection_complete",
              "guard": {
                "and": [
                  {
                    "===": [
                      { "var": "machines.${inspectionfiberId}.state.status" },
                      "passed"
                    ]
                  },
                  {
                    ">=": [
                      { "var": "machines.${inspectionfiberId}.state.qualityScore" },
                      85
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "inspected"],
                ["inspectedAt", { "var": "event.timestamp" }],
                ["qualityScore", { "var": "machines.${inspectionfiberId}.state.qualityScore" }],
                ["qualityReport", { "var": "machines.${inspectionfiberId}.state.reportId" }]
              ],
              "dependencies": ["${inspectionfiberId}"]
            },
            {
              "from": { "value": "quality_check" },
              "to": { "value": "REJECTED" },
              "eventName": "inspection_complete",
              "guard": {
                "or": [
                  {
                    "===": [
                      { "var": "machines.${inspectionfiberId}.state.status" },
                      "failed"
                    ]
                  },
                  {
                    "<": [
                      { "var": "machines.${inspectionfiberId}.state.qualityScore" },
                      85
                    ]
                  }
                ]
              },
              "effect": [
                ["status", "REJECTED"],
                ["rejectedAt", { "var": "event.timestamp" }],
                ["rejectionReason", "quality inspection failed"],
                ["qualityScore", { "var": "machines.${inspectionfiberId}.state.qualityScore" }]
              ],
              "dependencies": ["${inspectionfiberId}"]
            },
            {
              "from": { "value": "inspected" },
              "to": { "value": "settling" },
              "eventName": "initiate_settlement",
              "guard": true,
              "effect": [
                ["status", "settling"],
                ["settlementInitiatedAt", { "var": "event.timestamp" }],
                ["totalAmount", { "*": [ { "var": "state.fuelQuantity" }, { "var": "state.pricePerLiter" } ] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "settling" },
              "to": { "value": "settled" },
              "eventName": "finalize_settlement",
              "guard": {
                ">=": [
                  { "var": "event.paymentConfirmation" },
                  { "*": [ { "var": "state.fuelQuantity" }, { "var": "state.pricePerLiter" } ] }
                ]
              },
              "effect": [
                ["status", "settled"],
                ["settledAt", { "var": "event.timestamp" }],
                ["paymentConfirmation", { "var": "event.paymentConfirmation" }],
                ["contractCompleted", true]
              ],
              "dependencies": []
            }
          ]
        }"""

        // GPSTracker: Tracks vehicle location during transit
        // States: inactive -> active -> tracking -> stopped -> archived
        gpsTrackerJson =
          """{
          "states": {
            "inactive": { "id": { "value": "inactive" }, "isFinal": false },
            "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false },
            "tracking": { "id": { "value": "tracking" }, "isFinal": false },
            "stopped": { "id": { "value": "stopped" }, "isFinal": false },
            "archived": { "id": { "value": "archived" }, "isFinal": true }
          },
          "initialState": { "value": "inactive" },
          "transitions": [
            {
              "from": { "value": "inactive" },
              "to": { "value": "ACTIVE" },
              "eventName": "activate",
              "guard": true,
              "effect": [
                ["status", "ACTIVE"],
                ["activatedAt", { "var": "event.timestamp" }],
                ["vehicleId", { "var": "event.vehicleId" }],
                ["dataPointCount", 0],
                ["totalDistance", 0]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "ACTIVE" },
              "to": { "value": "tracking" },
              "eventName": "start_tracking",
              "guard": true,
              "effect": [
                ["status", "tracking"],
                ["trackingStartedAt", { "var": "event.timestamp" }],
                ["originLat", { "var": "event.latitude" }],
                ["originLon", { "var": "event.longitude" }],
                ["lastLat", { "var": "event.latitude" }],
                ["lastLon", { "var": "event.longitude" }],
                ["dataPointCount", 1]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "tracking" },
              "to": { "value": "tracking" },
              "eventName": "log_position",
              "guard": {
                "<": [
                  { "var": "state.dataPointCount" },
                  100
                ]
              },
              "effect": [
                ["lastLat", { "var": "event.latitude" }],
                ["lastLon", { "var": "event.longitude" }],
                ["lastUpdate", { "var": "event.timestamp" }],
                ["dataPointCount", { "+": [ { "var": "state.dataPointCount" }, 1 ] }],
                ["totalDistance", { "+": [ { "var": "state.totalDistance" }, { "var": "event.distanceDelta" } ] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "tracking" },
              "to": { "value": "stopped" },
              "eventName": "stop_tracking",
              "guard": {
                ">=": [
                  { "var": "state.dataPointCount" },
                  1
                ]
              },
              "effect": [
                ["status", "stopped"],
                ["stoppedAt", { "var": "event.timestamp" }],
                ["finalLat", { "var": "state.lastLat" }],
                ["finalLon", { "var": "state.lastLon" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "stopped" },
              "to": { "value": "archived" },
              "eventName": "archive",
              "guard": true,
              "effect": [
                ["status", "archived"],
                ["archivedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // SupplierApproval: Supplier vetting and approval
        // States: pending -> reviewing -> approved / rejected
        supplierApprovalJson =
          """{
          "states": {
            "PENDING": { "id": { "value": "PENDING" }, "isFinal": false },
            "reviewing": { "id": { "value": "reviewing" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": false },
            "REJECTED": { "id": { "value": "REJECTED" }, "isFinal": true }
          },
          "initialState": { "value": "PENDING" },
          "transitions": [
            {
              "from": { "value": "PENDING" },
              "to": { "value": "reviewing" },
              "eventName": "begin_review",
              "guard": true,
              "effect": [
                ["status", "reviewing"],
                ["reviewStartedAt", { "var": "event.timestamp" }],
                ["reviewer", { "var": "event.reviewer" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "reviewing" },
              "to": { "value": "approved" },
              "eventName": "approve",
              "guard": {
                "and": [
                  { ">=": [ { "var": "event.complianceScore" }, 75 ] },
                  { "===": [ { "var": "event.licenseValid" }, true ] }
                ]
              },
              "effect": [
                ["status", "approved"],
                ["approvedAt", { "var": "event.timestamp" }],
                ["complianceScore", { "var": "event.complianceScore" }],
                ["approvedBy", { "var": "event.approver" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "reviewing" },
              "to": { "value": "REJECTED" },
              "eventName": "approve",
              "guard": {
                "or": [
                  { "<": [ { "var": "event.complianceScore" }, 75 ] },
                  { "===": [ { "var": "event.licenseValid" }, false ] }
                ]
              },
              "effect": [
                ["status", "REJECTED"],
                ["rejectedAt", { "var": "event.timestamp" }],
                ["rejectionReason", "compliance or licensing issues"]
              ],
              "dependencies": []
            }
          ]
        }"""

        // QualityInspection: Post-delivery quality verification
        // States: pending -> scheduled -> inspecting -> passed / failed
        qualityInspectionJson =
          """{
          "states": {
            "PENDING": { "id": { "value": "PENDING" }, "isFinal": false },
            "scheduled": { "id": { "value": "scheduled" }, "isFinal": false },
            "inspecting": { "id": { "value": "inspecting" }, "isFinal": false },
            "passed": { "id": { "value": "passed" }, "isFinal": true },
            "failed": { "id": { "value": "failed" }, "isFinal": true }
          },
          "initialState": { "value": "PENDING" },
          "transitions": [
            {
              "from": { "value": "PENDING" },
              "to": { "value": "scheduled" },
              "eventName": "schedule",
              "guard": true,
              "effect": [
                ["status", "scheduled"],
                ["scheduledAt", { "var": "event.timestamp" }],
                ["inspector", { "var": "event.inspector" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "scheduled" },
              "to": { "value": "inspecting" },
              "eventName": "begin_inspection",
              "guard": true,
              "effect": [
                ["status", "inspecting"],
                ["inspectionStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "inspecting" },
              "to": { "value": "passed" },
              "eventName": "complete",
              "guard": {
                "and": [
                  { ">=": [ { "var": "event.qualityScore" }, 85 ] },
                  { "===": [ { "var": "event.contaminationDetected" }, false ] }
                ]
              },
              "effect": [
                ["status", "passed"],
                ["completedAt", { "var": "event.timestamp" }],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["reportId", { "var": "event.reportId" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "inspecting" },
              "to": { "value": "failed" },
              "eventName": "complete",
              "guard": {
                "or": [
                  { "<": [ { "var": "event.qualityScore" }, 85 ] },
                  { "===": [ { "var": "event.contaminationDetected" }, true ] }
                ]
              },
              "effect": [
                ["status", "failed"],
                ["completedAt", { "var": "event.timestamp" }],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["failureReason", { "var": "event.failureReason" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        contractDef <- IO.fromEither(
          decode[StateMachineDefinition](contractJson).left
            .map(err => new RuntimeException(s"Failed to decode contract JSON: $err"))
        )
        gpsTrackerDef <- IO.fromEither(
          decode[StateMachineDefinition](gpsTrackerJson).left
            .map(err => new RuntimeException(s"Failed to decode GPS tracker JSON: $err"))
        )
        supplierDef <- IO.fromEither(
          decode[StateMachineDefinition](supplierApprovalJson).left
            .map(err => new RuntimeException(s"Failed to decode supplier JSON: $err"))
        )
        inspectionDef <- IO.fromEither(
          decode[StateMachineDefinition](qualityInspectionJson).left
            .map(err => new RuntimeException(s"Failed to decode inspection JSON: $err"))
        )

        // Build fiber records using FiberBuilder
        contractFiber <- FiberBuilder(contractfiberId, ordinal, contractDef)
          .withState("draft")
          .withData(
            "contractId"    -> StrValue("FC-2025-001"),
            "buyer"         -> StrValue(registry.addresses(Alice).toString),
            "fuelType"      -> StrValue("Diesel"),
            "fuelQuantity"  -> IntValue(5000),
            "pricePerLiter" -> IntValue(2),
            "status"        -> StrValue("draft")
          )
          .ownedBy(registry, Alice, Bob)
          .build[IO]

        gpsTrackerFiber <- FiberBuilder(gpsTrackerfiberId, ordinal, gpsTrackerDef)
          .withData("trackerId" -> StrValue("GPS-TRACKER-001"), "status" -> StrValue("inactive"))
          .ownedBy(registry, Alice)
          .build[IO]

        supplierFiber <- FiberBuilder(supplierfiberId, ordinal, supplierDef)
          .withData(
            "supplierName" -> StrValue("Global Fuel Co"),
            "supplierId"   -> StrValue("SUP-001"),
            "status"       -> StrValue("PENDING")
          )
          .ownedBy(registry, Bob)
          .build[IO]

        inspectionFiber <- FiberBuilder(inspectionfiberId, ordinal, inspectionDef)
          .withData("contractRef" -> StrValue("FC-2025-001"), "status" -> StrValue("PENDING"))
          .ownedBy(registry, Charlie)
          .build[IO]

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(
            contractfiberId   -> contractFiber,
            gpsTrackerfiberId -> gpsTrackerFiber,
            supplierfiberId   -> supplierFiber,
            inspectionfiberId -> inspectionFiber
          )
        )

        // STEP 1: Submit contract for approval
        state1 <- inState.transition(
          contractfiberId,
          "submit_for_approval",
          MapValue(Map("timestamp" -> IntValue(1000))),
          Alice
        )(registry, combiner)

        // STEP 2: Begin supplier review
        state2 <- state1.transition(
          supplierfiberId,
          "begin_review",
          MapValue(
            Map(
              "timestamp" -> IntValue(1100),
              "reviewer"  -> StrValue(registry.addresses(Bob).toString)
            )
          ),
          Bob
        )(registry, combiner)

        // STEP 3: Approve supplier
        state3 <- state2.transition(
          supplierfiberId,
          "approve",
          MapValue(
            Map(
              "timestamp"       -> IntValue(1200),
              "complianceScore" -> IntValue(90),
              "licenseValid"    -> BoolValue(true),
              "approver"        -> StrValue(registry.addresses(Bob).toString)
            )
          ),
          Bob
        )(registry, combiner)

        // STEP 4: Contract receives supplier approval
        state4 <- state3.transition(
          contractfiberId,
          "supplier_approved",
          MapValue(Map("timestamp" -> IntValue(1300))),
          Alice
        )(registry, combiner)

        // STEP 5: Activate GPS tracker
        state5 <- state4.transition(
          gpsTrackerfiberId,
          "activate",
          MapValue(Map("timestamp" -> IntValue(1400), "vehicleId" -> StrValue("TRUCK-42"))),
          Alice
        )(registry, combiner)

        // STEP 6: Prepare shipment (contract checks GPS is active)
        state6 <- state5.transition(
          contractfiberId,
          "prepare_shipment",
          MapValue(
            Map(
              "timestamp" -> IntValue(1500),
              "vehicleId" -> StrValue("TRUCK-42"),
              "driverId"  -> StrValue(registry.addresses(Dave).toString)
            )
          ),
          Alice
        )(registry, combiner)

        // STEP 7: Start GPS tracking
        state7 <- state6.transition(
          gpsTrackerfiberId,
          "start_tracking",
          MapValue(
            Map(
              "timestamp" -> IntValue(1600),
              "latitude"  -> IntValue(40_7128),
              "longitude" -> IntValue(-74_0060)
            )
          ),
          Alice
        )(registry, combiner)

        // STEP 8: Begin transit (contract checks GPS is tracking)
        state8 <- state7.transition(
          contractfiberId,
          "begin_transit",
          MapValue(Map("timestamp" -> IntValue(1700), "estimatedArrival" -> IntValue(3000))),
          Alice
        )(registry, combiner)

        // STEPS 9-11: Log GPS positions during transit
        state9 <- state8.transition(
          gpsTrackerfiberId,
          "log_position",
          MapValue(
            Map(
              "timestamp"     -> IntValue(1800),
              "latitude"      -> IntValue(40_7580),
              "longitude"     -> IntValue(-73_9855),
              "distanceDelta" -> IntValue(5)
            )
          ),
          Alice
        )(registry, combiner)

        state10 <- state9.transition(
          gpsTrackerfiberId,
          "log_position",
          MapValue(
            Map(
              "timestamp"     -> IntValue(2000),
              "latitude"      -> IntValue(40_8500),
              "longitude"     -> IntValue(-73_8700),
              "distanceDelta" -> IntValue(8)
            )
          ),
          Alice
        )(registry, combiner)

        state11 <- state10.transition(
          gpsTrackerfiberId,
          "log_position",
          MapValue(
            Map(
              "timestamp"     -> IntValue(2200),
              "latitude"      -> IntValue(41_0000),
              "longitude"     -> IntValue(-73_7500),
              "distanceDelta" -> IntValue(12)
            )
          ),
          Alice
        )(registry, combiner)

        // STEP 12: Stop GPS tracking
        state12 <- state11.transition(
          gpsTrackerfiberId,
          "stop_tracking",
          MapValue(Map("timestamp" -> IntValue(2500))),
          Alice
        )(registry, combiner)

        // STEP 13: Confirm delivery (contract checks GPS stopped and has data)
        state13 <- state12.transition(
          contractfiberId,
          "confirm_delivery",
          MapValue(Map("timestamp" -> IntValue(2600))),
          Alice
        )(registry, combiner)

        // STEP 14: Schedule quality inspection
        state14 <- state13.transition(
          inspectionfiberId,
          "schedule",
          MapValue(
            Map(
              "timestamp" -> IntValue(2700),
              "inspector" -> StrValue(registry.addresses(Charlie).toString)
            )
          ),
          Charlie
        )(registry, combiner)

        // STEP 15: Contract initiates inspection
        state15 <- state14.transition(
          contractfiberId,
          "initiate_inspection",
          MapValue(Map("timestamp" -> IntValue(2800))),
          Alice
        )(registry, combiner)

        // STEP 16: Begin inspection
        state16 <- state15.transition(
          inspectionfiberId,
          "begin_inspection",
          MapValue(Map("timestamp" -> IntValue(2900))),
          Charlie
        )(registry, combiner)

        // STEP 17: Complete inspection (passed)
        state17 <- state16.transition(
          inspectionfiberId,
          "complete",
          MapValue(
            Map(
              "timestamp"             -> IntValue(3000),
              "qualityScore"          -> IntValue(95),
              "contaminationDetected" -> BoolValue(false),
              "reportId"              -> StrValue("QC-REPORT-001")
            )
          ),
          Charlie
        )(registry, combiner)

        // STEP 18: Contract receives inspection completion
        state18 <- state17.transition(
          contractfiberId,
          "inspection_complete",
          MapValue(Map("timestamp" -> IntValue(3100))),
          Alice
        )(registry, combiner)

        // STEP 19: Initiate settlement
        state19 <- state18.transition(
          contractfiberId,
          "initiate_settlement",
          MapValue(Map("timestamp" -> IntValue(3200))),
          Alice
        )(registry, combiner)

        // STEP 20: Finalize settlement
        finalState <- state19.transition(
          contractfiberId,
          "finalize_settlement",
          MapValue(
            Map(
              "timestamp"           -> IntValue(3300),
              "paymentConfirmation" -> IntValue(10000)
            )
          ),
          Alice
        )(registry, combiner)

        // Verify final state using fiberRecord lookups and FiberExtractors
        finalContract = finalState.fiberRecord(contractfiberId)
        finalGpsTracker = finalState.fiberRecord(gpsTrackerfiberId)
        finalSupplier = finalState.fiberRecord(supplierfiberId)
        finalInspection = finalState.fiberRecord(inspectionfiberId)

      } yield expect.all(
        finalContract.isDefined,
        finalContract.map(_.currentState).contains(StateId("settled")),
        finalContract.extractString("status").contains("settled"),
        finalContract.extractBool("contractCompleted").contains(true),
        finalGpsTracker.isDefined,
        finalGpsTracker.map(_.currentState).contains(StateId("stopped")),
        finalGpsTracker.extractInt("dataPointCount").contains(BigInt(4)),
        finalGpsTracker.extractInt("totalDistance").contains(BigInt(25)),
        finalSupplier.isDefined,
        finalSupplier.map(_.currentState).contains(StateId("approved")),
        finalInspection.isDefined,
        finalInspection.map(_.currentState).contains(StateId("passed")),
        finalInspection.extractInt("qualityScore").contains(BigInt(95))
      )
    }
  }
}
