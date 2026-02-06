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

object ClinicalTrialStateMachineSuite extends SimpleIOSuite {

  import DataStateTestOps._
  import TestImports.optionFiberRecordOps

  test("json-encoded: clinical trial with multi-party coordination and bi-directional transitions") {
    TestFixture.resource(Set(Alice, Bob, Charlie, Dave, Eve)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      val ordinal = fixture.ordinal

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        trialfiberId        <- UUIDGen.randomUUID[IO]
        patientfiberId      <- UUIDGen.randomUUID[IO]
        labfiberId          <- UUIDGen.randomUUID[IO]
        adverseEventfiberId <- UUIDGen.randomUUID[IO]
        regulatorfiberId    <- UUIDGen.randomUUID[IO]
        insurancefiberId    <- UUIDGen.randomUUID[IO]

        // Trial Coordinator: manages overall trial phases
        trialJson =
          s"""{
          "states": {
            "recruiting": { "id": { "value": "recruiting" }, "isFinal": false },
            "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false },
            "phase_1": { "id": { "value": "phase_1" }, "isFinal": false },
            "phase_2": { "id": { "value": "phase_2" }, "isFinal": false },
            "phase_3": { "id": { "value": "phase_3" }, "isFinal": false },
            "under_review": { "id": { "value": "under_review" }, "isFinal": false },
            "COMPLETED": { "id": { "value": "COMPLETED" }, "isFinal": true },
            "SUSPENDED": { "id": { "value": "SUSPENDED" }, "isFinal": false },
            "terminated": { "id": { "value": "terminated" }, "isFinal": true }
          },
          "initialState": { "value": "recruiting" },
          "transitions": [
            {
              "from": { "value": "recruiting" },
              "to": { "value": "ACTIVE" },
              "eventName": "start_trial",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.enrolledCount" }, { "var": "state.minParticipants" }] },
                  { "===": [{ "var": "machines.${regulatorfiberId}.state.status" }, "approved"] }
                ]
              },
              "effect": [
                ["status", "ACTIVE"],
                ["startedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${regulatorfiberId}"]
            },
            {
              "from": { "value": "ACTIVE" },
              "to": { "value": "phase_1" },
              "eventName": "advance_phase",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 3] },
                  { "===": [{ "var": "machines.${adverseEventfiberId}.state.hasCriticalEvent" }, false] }
                ]
              },
              "effect": [
                ["status", "phase_1"],
                ["phase", 1],
                ["phaseStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${adverseEventfiberId}"]
            },
            {
              "from": { "value": "phase_1" },
              "to": { "value": "phase_2" },
              "eventName": "advance_phase",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 8] },
                  { "===": [{ "var": "machines.${labfiberId}.state.passRate" }, 100] },
                  { "===": [{ "var": "machines.${adverseEventfiberId}.state.hasCriticalEvent" }, false] }
                ]
              },
              "effect": [
                ["status", "phase_2"],
                ["phase", 2],
                ["phaseStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labfiberId}", "${adverseEventfiberId}"]
            },
            {
              "from": { "value": "phase_2" },
              "to": { "value": "phase_3" },
              "eventName": "advance_phase",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 15] },
                  { ">=": [{ "var": "machines.${labfiberId}.state.passRate" }, 95] }
                ]
              },
              "effect": [
                ["status", "phase_3"],
                ["phase", 3],
                ["phaseStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labfiberId}"]
            },
            {
              "from": { "value": "phase_3" },
              "to": { "value": "under_review" },
              "eventName": "submit_for_review",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.completedVisits" }, 20] },
                  { "===": [{ "var": "machines.${patientfiberId}.state.status" }, "COMPLETED"] }
                ]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${regulatorfiberId}",
                    "eventName": "begin_review",
                    "payload": {
                      "trialId": { "var": "machineId" },
                      "submittedAt": { "var": "event.timestamp" },
                      "completedVisits": { "var": "state.completedVisits" }
                    }
                  }
                ]],
                ["status", "under_review"],
                ["submittedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${patientfiberId}"]
            },
            {
              "from": { "value": "under_review" },
              "to": { "value": "COMPLETED" },
              "eventName": "finalize",
              "guard": {
                "===": [{ "var": "machines.${regulatorfiberId}.state.reviewResult" }, "approved"]
              },
              "effect": [
                ["status", "COMPLETED"],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${regulatorfiberId}"]
            },
            {
              "from": { "value": "ACTIVE" },
              "to": { "value": "SUSPENDED" },
              "eventName": "suspend",
              "guard": {
                "===": [{ "var": "machines.${adverseEventfiberId}.state.hasCriticalEvent" }, true]
              },
              "effect": [
                ["status", "SUSPENDED"],
                ["suspendedAt", { "var": "event.timestamp" }],
                ["suspensionReason", "critical_adverse_event"]
              ],
              "dependencies": ["${adverseEventfiberId}"]
            },
            {
              "from": { "value": "SUSPENDED" },
              "to": { "value": "ACTIVE" },
              "eventName": "resume",
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${adverseEventfiberId}.state.resolved" }, true] },
                  { "===": [{ "var": "machines.${regulatorfiberId}.state.allowResume" }, true] }
                ]
              },
              "effect": [
                ["status", "ACTIVE"],
                ["resumedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${adverseEventfiberId}", "${regulatorfiberId}"]
            },
            {
              "from": { "value": "SUSPENDED" },
              "to": { "value": "terminated" },
              "eventName": "terminate",
              "guard": {
                "===": [{ "var": "machines.${regulatorfiberId}.state.terminationRequired" }, true]
              },
              "effect": [
                ["status", "terminated"],
                ["terminatedAt", { "var": "event.timestamp" }],
                ["terminationReason", { "var": "event.reason" }]
              ],
              "dependencies": ["${regulatorfiberId}"]
            }
          ]
        }"""

        // Patient Enrollment: demonstrates bi-directional transitions
        patientJson =
          s"""{
          "states": {
            "screening": { "id": { "value": "screening" }, "isFinal": false },
            "enrolled": { "id": { "value": "enrolled" }, "isFinal": false },
            "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false },
            "paused": { "id": { "value": "paused" }, "isFinal": false },
            "visit_scheduled": { "id": { "value": "visit_scheduled" }, "isFinal": false },
            "visit_completed": { "id": { "value": "visit_completed" }, "isFinal": false },
            "adverse_event": { "id": { "value": "adverse_event" }, "isFinal": false },
            "COMPLETED": { "id": { "value": "COMPLETED" }, "isFinal": true },
            "WITHDRAWN": { "id": { "value": "WITHDRAWN" }, "isFinal": true }
          },
          "initialState": { "value": "screening" },
          "transitions": [
            {
              "from": { "value": "screening" },
              "to": { "value": "enrolled" },
              "eventName": "enroll",
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.age" }, 18] },
                  { "===": [{ "var": "event.consentSigned" }, true] },
                  { "===": [{ "var": "machines.${insurancefiberId}.state.coverageActive" }, true] }
                ]
              },
              "effect": [
                ["_emit", [
                  {
                    "name": "webhook",
                    "data": {
                      "event": "patient.enrolled",
                      "patientId": { "var": "machineId" },
                      "trialId": "${trialfiberId}",
                      "timestamp": { "var": "event.timestamp" }
                    },
                    "destination": "https://trial-system.example.com/webhooks"
                  }
                ]],
                ["status", "enrolled"],
                ["enrolledAt", { "var": "event.timestamp" }],
                ["patientName", { "var": "event.patientName" }],
                ["age", { "var": "event.age" }]
              ],
              "dependencies": ["${insurancefiberId}"]
            },
            {
              "from": { "value": "enrolled" },
              "to": { "value": "ACTIVE" },
              "eventName": "activate",
              "guard": {
                "===": [{ "var": "machines.${trialfiberId}.state.status" }, "ACTIVE"]
              },
              "effect": [
                ["status", "ACTIVE"],
                ["activatedAt", { "var": "event.timestamp" }],
                ["visitCount", 0]
              ],
              "dependencies": ["${trialfiberId}"]
            },
            {
              "from": { "value": "ACTIVE" },
              "to": { "value": "paused" },
              "eventName": "pause",
              "guard": true,
              "effect": [
                ["status", "paused"],
                ["pausedAt", { "var": "event.timestamp" }],
                ["pauseReason", { "var": "event.reason" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "paused" },
              "to": { "value": "ACTIVE" },
              "eventName": "resume",
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${trialfiberId}.state.status" }, "ACTIVE"] },
                  { "<": [{ "-": [{ "var": "event.timestamp" }, { "var": "state.pausedAt" }] }, 2592000] }
                ]
              },
              "effect": [
                ["status", "ACTIVE"],
                ["resumedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${trialfiberId}"]
            },
            {
              "from": { "value": "ACTIVE" },
              "to": { "value": "visit_scheduled" },
              "eventName": "schedule_visit",
              "guard": {
                "<": [{ "var": "state.visitCount" }, 20]
              },
              "effect": [
                ["status", "visit_scheduled"],
                ["nextVisitAt", { "var": "event.visitTime" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "visit_scheduled" },
              "to": { "value": "visit_completed" },
              "eventName": "complete_visit",
              "guard": {
                "===": [{ "var": "machines.${labfiberId}.state.sampleStatus" }, "collected"]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${labfiberId}",
                    "eventName": "process_sample",
                    "payload": {
                      "patientId": { "var": "machineId" },
                      "visitNumber": { "+": [{ "var": "state.visitCount" }, 1] },
                      "collectedAt": { "var": "event.timestamp" }
                    }
                  }
                ]],
                ["status", "visit_completed"],
                ["visitCount", { "+": [{ "var": "state.visitCount" }, 1] }],
                ["lastVisitAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labfiberId}"]
            },
            {
              "from": { "value": "visit_completed" },
              "to": { "value": "ACTIVE" },
              "eventName": "continue",
              "guard": {
                "and": [
                  { "===": [{ "var": "machines.${labfiberId}.state.result" }, "passed"] },
                  { "<": [{ "var": "state.visitCount" }, 20] }
                ]
              },
              "effect": [
                ["status", "ACTIVE"]
              ],
              "dependencies": ["${labfiberId}"]
            },
            {
              "from": { "value": "visit_completed" },
              "to": { "value": "adverse_event" },
              "eventName": "continue",
              "guard": {
                "or": [
                  { "===": [{ "var": "machines.${labfiberId}.state.result" }, "failed"] },
                  { "===": [{ "var": "machines.${labfiberId}.state.result" }, "critical"] }
                ]
              },
              "effect": [
                ["_triggers", [
                  {
                    "targetMachineId": "${adverseEventfiberId}",
                    "eventName": "report_event",
                    "payload": {
                      "patientId": { "var": "machineId" },
                      "severity": { "var": "machines.${labfiberId}.state.result" },
                      "detectedAt": { "var": "event.timestamp" }
                    }
                  }
                ]],
                ["status", "adverse_event"],
                ["eventDetectedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labfiberId}"]
            },
            {
              "from": { "value": "adverse_event" },
              "to": { "value": "ACTIVE" },
              "eventName": "resolve",
              "guard": {
                "===": [{ "var": "machines.${adverseEventfiberId}.state.resolved" }, true]
              },
              "effect": [
                ["status", "ACTIVE"],
                ["resolvedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${adverseEventfiberId}"]
            },
            {
              "from": { "value": "visit_completed" },
              "to": { "value": "COMPLETED" },
              "eventName": "complete_trial",
              "guard": {
                "and": [
                  { ">=": [{ "var": "state.visitCount" }, 20] },
                  { "===": [{ "var": "machines.${labfiberId}.state.result" }, "passed"] }
                ]
              },
              "effect": [
                ["_emit", [
                  {
                    "name": "email",
                    "data": {
                      "to": { "var": "state.patientEmail" },
                      "subject": "Trial Completion",
                      "body": "Congratulations on completing the clinical trial"
                    }
                  }
                ]],
                ["status", "COMPLETED"],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": ["${labfiberId}"]
            },
            {
              "from": { "value": "paused" },
              "to": { "value": "WITHDRAWN" },
              "eventName": "withdraw",
              "guard": true,
              "effect": [
                ["status", "WITHDRAWN"],
                ["withdrawnAt", { "var": "event.timestamp" }],
                ["withdrawalReason", { "var": "event.reason" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "adverse_event" },
              "to": { "value": "WITHDRAWN" },
              "eventName": "withdraw",
              "guard": {
                "===": [{ "var": "machines.${adverseEventfiberId}.state.severity" }, "critical"]
              },
              "effect": [
                ["status", "WITHDRAWN"],
                ["withdrawnAt", { "var": "event.timestamp" }],
                ["withdrawalReason", "critical_adverse_event"]
              ],
              "dependencies": ["${adverseEventfiberId}"]
            }
          ]
        }"""

        // Lab Results: demonstrates multiple outcomes from same event
        labJson =
          """{
          "states": {
            "idle": { "id": { "value": "idle" }, "isFinal": false },
            "sample_received": { "id": { "value": "sample_received" }, "isFinal": false },
            "processing": { "id": { "value": "processing" }, "isFinal": false },
            "passed": { "id": { "value": "passed" }, "isFinal": false },
            "questionable": { "id": { "value": "questionable" }, "isFinal": false },
            "failed": { "id": { "value": "failed" }, "isFinal": false },
            "critical": { "id": { "value": "critical" }, "isFinal": false },
            "retest_required": { "id": { "value": "retest_required" }, "isFinal": false }
          },
          "initialState": { "value": "idle" },
          "transitions": [
            {
              "from": { "value": "idle" },
              "to": { "value": "sample_received" },
              "eventName": "receive_sample",
              "guard": true,
              "effect": [
                ["sampleStatus", "collected"],
                ["receivedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "sample_received" },
              "to": { "value": "processing" },
              "eventName": "process_sample",
              "guard": true,
              "effect": [
                ["status", "processing"],
                ["sampleStatus", "processing"],
                ["processingStartedAt", { "var": "event.timestamp" }],
                ["patientId", { "var": "event.patientId" }],
                ["visitNumber", { "var": "event.visitNumber" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "passed" },
              "eventName": "complete",
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.qualityScore" }, 90] },
                  { "===": [{ "var": "event.contaminated" }, false] },
                  { "<": [{ "var": "event.biomarkerLevel" }, 100] }
                ]
              },
              "effect": [
                ["result", "passed"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["biomarkerLevel", { "var": "event.biomarkerLevel" }],
                ["passRate", 100],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "questionable" },
              "eventName": "complete",
              "guard": {
                "and": [
                  { ">=": [{ "var": "event.qualityScore" }, 70] },
                  { "<": [{ "var": "event.qualityScore" }, 90] },
                  { "===": [{ "var": "event.contaminated" }, false] }
                ]
              },
              "effect": [
                ["result", "questionable"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["biomarkerLevel", { "var": "event.biomarkerLevel" }],
                ["passRate", 95],
                ["completedAt", { "var": "event.timestamp" }],
                ["reviewRequired", true]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "failed" },
              "eventName": "complete",
              "guard": {
                "and": [
                  { "<": [{ "var": "event.qualityScore" }, 70] },
                  { "===": [{ "var": "event.contaminated" }, false] }
                ]
              },
              "effect": [
                ["result", "failed"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["biomarkerLevel", { "var": "event.biomarkerLevel" }],
                ["passRate", 80],
                ["completedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "critical" },
              "eventName": "complete",
              "guard": {
                ">=": [{ "var": "event.biomarkerLevel" }, 500]
              },
              "effect": [
                ["result", "critical"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["biomarkerLevel", { "var": "event.biomarkerLevel" }],
                ["passRate", 70],
                ["completedAt", { "var": "event.timestamp" }],
                ["criticalAlert", true]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "processing" },
              "to": { "value": "retest_required" },
              "eventName": "complete",
              "guard": {
                "===": [{ "var": "event.contaminated" }, true]
              },
              "effect": [
                ["result", "retest_required"],
                ["qualityScore", { "var": "event.qualityScore" }],
                ["passRate", 90],
                ["completedAt", { "var": "event.timestamp" }],
                ["contaminationReason", { "var": "event.contaminationReason" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "retest_required" },
              "to": { "value": "processing" },
              "eventName": "retest",
              "guard": true,
              "effect": [
                ["status", "processing"],
                ["sampleStatus", "processing"],
                ["retestCount", { "+": [{ "var": "state.retestCount" }, 1] }],
                ["retestStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "passed" },
              "to": { "value": "idle" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["sampleStatus", "idle"],
                ["result", null]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "questionable" },
              "to": { "value": "idle" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["sampleStatus", "idle"],
                ["result", null]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "failed" },
              "to": { "value": "idle" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["sampleStatus", "idle"],
                ["result", null]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "critical" },
              "to": { "value": "idle" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["sampleStatus", "idle"],
                ["result", null]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Adverse Event Monitor
        adverseEventJson =
          """{
          "states": {
            "monitoring": { "id": { "value": "monitoring" }, "isFinal": false },
            "event_reported": { "id": { "value": "event_reported" }, "isFinal": false },
            "investigating": { "id": { "value": "investigating" }, "isFinal": false },
            "resolved": { "id": { "value": "resolved" }, "isFinal": false },
            "escalated": { "id": { "value": "escalated" }, "isFinal": true }
          },
          "initialState": { "value": "monitoring" },
          "transitions": [
            {
              "from": { "value": "monitoring" },
              "to": { "value": "event_reported" },
              "eventName": "report_event",
              "guard": true,
              "effect": [
                ["hasCriticalEvent", { "===": [{ "var": "event.severity" }, "critical"] }],
                ["severity", { "var": "event.severity" }],
                ["patientId", { "var": "event.patientId" }],
                ["reportedAt", { "var": "event.detectedAt" }],
                ["resolved", false]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "event_reported" },
              "to": { "value": "investigating" },
              "eventName": "investigate",
              "guard": true,
              "effect": [
                ["status", "investigating"],
                ["investigationStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "investigating" },
              "to": { "value": "resolved" },
              "eventName": "resolve",
              "guard": {
                "!==": [{ "var": "state.severity" }, "critical"]
              },
              "effect": [
                ["status", "resolved"],
                ["resolved", true],
                ["hasCriticalEvent", false],
                ["resolvedAt", { "var": "event.timestamp" }],
                ["resolution", { "var": "event.resolution" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "investigating" },
              "to": { "value": "escalated" },
              "eventName": "escalate",
              "guard": {
                "===": [{ "var": "state.severity" }, "critical"]
              },
              "effect": [
                ["status", "escalated"],
                ["hasCriticalEvent", true],
                ["escalatedAt", { "var": "event.timestamp" }],
                ["escalationReason", { "var": "event.reason" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "resolved" },
              "to": { "value": "monitoring" },
              "eventName": "clear",
              "guard": true,
              "effect": [
                ["status", "monitoring"],
                ["hasCriticalEvent", false],
                ["resolved", true]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Regulatory Review
        regulatorJson =
          """{
          "states": {
            "approved": { "id": { "value": "approved" }, "isFinal": false },
            "monitoring": { "id": { "value": "monitoring" }, "isFinal": false },
            "reviewing": { "id": { "value": "reviewing" }, "isFinal": false },
            "final_approved": { "id": { "value": "final_approved" }, "isFinal": true },
            "REJECTED": { "id": { "value": "REJECTED" }, "isFinal": true }
          },
          "initialState": { "value": "approved" },
          "transitions": [
            {
              "from": { "value": "approved" },
              "to": { "value": "monitoring" },
              "eventName": "start_monitoring",
              "guard": true,
              "effect": [
                ["status", "monitoring"],
                ["allowResume", true],
                ["terminationRequired", false],
                ["monitoringStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "monitoring" },
              "to": { "value": "reviewing" },
              "eventName": "begin_review",
              "guard": true,
              "effect": [
                ["status", "reviewing"],
                ["reviewStartedAt", { "var": "event.submittedAt" }],
                ["trialId", { "var": "event.trialId" }],
                ["completedVisits", { "var": "event.completedVisits" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "reviewing" },
              "to": { "value": "final_approved" },
              "eventName": "approve",
              "guard": {
                ">=": [{ "var": "event.complianceScore" }, 90]
              },
              "effect": [
                ["reviewResult", "approved"],
                ["status", "final_approved"],
                ["approvedAt", { "var": "event.timestamp" }],
                ["complianceScore", { "var": "event.complianceScore" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "reviewing" },
              "to": { "value": "REJECTED" },
              "eventName": "approve",
              "guard": {
                "<": [{ "var": "event.complianceScore" }, 90]
              },
              "effect": [
                ["reviewResult", "REJECTED"],
                ["status", "REJECTED"],
                ["rejectedAt", { "var": "event.timestamp" }],
                ["complianceScore", { "var": "event.complianceScore" }],
                ["rejectionReason", { "var": "event.reason" }]
              ],
              "dependencies": []
            }
          ]
        }"""

        // Insurance Coverage
        insuranceJson =
          """{
          "states": {
            "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false },
            "claim_filed": { "id": { "value": "claim_filed" }, "isFinal": false },
            "claim_processing": { "id": { "value": "claim_processing" }, "isFinal": false },
            "claim_approved": { "id": { "value": "claim_approved" }, "isFinal": false },
            "claim_paid": { "id": { "value": "claim_paid" }, "isFinal": false }
          },
          "initialState": { "value": "ACTIVE" },
          "transitions": [
            {
              "from": { "value": "ACTIVE" },
              "to": { "value": "claim_filed" },
              "eventName": "file_claim",
              "guard": {
                ">=": [{ "var": "state.coverage" }, { "var": "event.claimAmount" }]
              },
              "effect": [
                ["status", "claim_filed"],
                ["claimAmount", { "var": "event.claimAmount" }],
                ["claimFiledAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "claim_filed" },
              "to": { "value": "claim_processing" },
              "eventName": "process",
              "guard": true,
              "effect": [
                ["status", "claim_processing"],
                ["processingStartedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "claim_processing" },
              "to": { "value": "claim_approved" },
              "eventName": "approve_claim",
              "guard": true,
              "effect": [
                ["status", "claim_approved"],
                ["approvedAt", { "var": "event.timestamp" }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "claim_approved" },
              "to": { "value": "claim_paid" },
              "eventName": "pay",
              "guard": true,
              "effect": [
                ["status", "claim_paid"],
                ["paidAt", { "var": "event.timestamp" }],
                ["remainingCoverage", { "-": [{ "var": "state.coverage" }, { "var": "state.claimAmount" }] }]
              ],
              "dependencies": []
            },
            {
              "from": { "value": "claim_paid" },
              "to": { "value": "ACTIVE" },
              "eventName": "reset",
              "guard": true,
              "effect": [
                ["status", "ACTIVE"]
              ],
              "dependencies": []
            }
          ]
        }"""

        trialDef <- IO.fromEither(
          decode[StateMachineDefinition](trialJson).left
            .map(err => new RuntimeException(s"Failed to decode trial JSON: $err"))
        )

        patientDef <- IO.fromEither(
          decode[StateMachineDefinition](patientJson).left
            .map(err => new RuntimeException(s"Failed to decode patient JSON: $err"))
        )

        labDef <- IO.fromEither(
          decode[StateMachineDefinition](labJson).left
            .map(err => new RuntimeException(s"Failed to decode lab JSON: $err"))
        )

        adverseEventDef <- IO.fromEither(
          decode[StateMachineDefinition](adverseEventJson).left
            .map(err => new RuntimeException(s"Failed to decode adverse event JSON: $err"))
        )

        regulatorDef <- IO.fromEither(
          decode[StateMachineDefinition](regulatorJson).left
            .map(err => new RuntimeException(s"Failed to decode regulator JSON: $err"))
        )

        insuranceDef <- IO.fromEither(
          decode[StateMachineDefinition](insuranceJson).left
            .map(err => new RuntimeException(s"Failed to decode insurance JSON: $err"))
        )

        trialFiber <- FiberBuilder(trialfiberId, ordinal, trialDef)
          .withState("recruiting")
          .withData(
            "trialName"       -> StrValue("Phase III Cancer Treatment Trial"),
            "minParticipants" -> IntValue(1),
            "enrolledCount"   -> IntValue(1),
            "completedVisits" -> IntValue(0),
            "phase"           -> IntValue(0),
            "status"          -> StrValue("recruiting")
          )
          .ownedBy(registry, Alice)
          .build[IO]

        patientFiber <- FiberBuilder(patientfiberId, ordinal, patientDef)
          .withState("screening")
          .withData(
            "patientName"  -> StrValue("John Doe"),
            "patientEmail" -> StrValue("john.doe@example.com"),
            "status"       -> StrValue("screening")
          )
          .ownedBy(registry, Bob)
          .build[IO]

        labFiber <- FiberBuilder(labfiberId, ordinal, labDef)
          .withState("idle")
          .withData(
            "labName"      -> StrValue("Central Lab"),
            "sampleStatus" -> StrValue("idle"),
            "passRate"     -> IntValue(100),
            "retestCount"  -> IntValue(0)
          )
          .ownedBy(registry, Charlie)
          .build[IO]

        adverseEventFiber <- FiberBuilder(adverseEventfiberId, ordinal, adverseEventDef)
          .withState("monitoring")
          .withData(
            "status"           -> StrValue("monitoring"),
            "hasCriticalEvent" -> BoolValue(false),
            "resolved"         -> BoolValue(true)
          )
          .ownedBy(registry, Dave)
          .build[IO]

        regulatorFiber <- FiberBuilder(regulatorfiberId, ordinal, regulatorDef)
          .withState("approved")
          .withData(
            "regulatorName"       -> StrValue("FDA"),
            "status"              -> StrValue("approved"),
            "allowResume"         -> BoolValue(true),
            "terminationRequired" -> BoolValue(false)
          )
          .ownedBy(registry, Eve)
          .build[IO]

        insuranceFiber <- FiberBuilder(insurancefiberId, ordinal, insuranceDef)
          .withState("ACTIVE")
          .withData(
            "policyNumber"   -> StrValue("POL-123456"),
            "coverage"       -> IntValue(100000),
            "coverageActive" -> BoolValue(true),
            "status"         -> StrValue("ACTIVE")
          )
          .ownedBy(registry, Alice, Bob)
          .build[IO]

        inState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
          Map(
            trialfiberId        -> trialFiber,
            patientfiberId      -> patientFiber,
            labfiberId          -> labFiber,
            adverseEventfiberId -> adverseEventFiber,
            regulatorfiberId    -> regulatorFiber,
            insurancefiberId    -> insuranceFiber
          )
        )

        // Step 1: Enroll patient
        state1 <- inState.transition(
          patientfiberId,
          "enroll",
          MapValue(
            Map(
              "timestamp"     -> IntValue(1000),
              "age"           -> IntValue(45),
              "consentSigned" -> BoolValue(true),
              "patientName"   -> StrValue("John Doe")
            )
          ),
          Bob
        )(registry, combiner)

        // Step 2: Start trial
        state2 <- state1.transition(
          trialfiberId,
          "start_trial",
          MapValue(Map("timestamp" -> IntValue(1100))),
          Alice
        )(registry, combiner)

        // Step 3: Activate patient
        state3 <- state2.transition(
          patientfiberId,
          "activate",
          MapValue(Map("timestamp" -> IntValue(1200))),
          Bob
        )(registry, combiner)

        // Step 4: Schedule visit
        state4 <- state3.transition(
          patientfiberId,
          "schedule_visit",
          MapValue(Map("visitTime" -> IntValue(2000))),
          Bob
        )(registry, combiner)

        // Step 5: Lab receives sample
        state5 <- state4.transition(
          labfiberId,
          "receive_sample",
          MapValue(Map("timestamp" -> IntValue(2100))),
          Charlie
        )(registry, combiner)

        // Step 6: Complete visit (triggers lab processing)
        state6 <- state5.transition(
          patientfiberId,
          "complete_visit",
          MapValue(Map("timestamp" -> IntValue(2200))),
          Bob
        )(registry, combiner)

        // Verify trigger fired and lab is now processing
        labAfterTrigger = state6.fiberRecord(labfiberId)

        labProcessingStatus = labAfterTrigger.extractString("status")

        // Step 7: Complete lab processing with high quality (passed)
        state7 <- state6.transition(
          labfiberId,
          "complete",
          MapValue(
            Map(
              "timestamp"      -> IntValue(2300),
              "qualityScore"   -> IntValue(95),
              "contaminated"   -> BoolValue(false),
              "biomarkerLevel" -> IntValue(50)
            )
          ),
          Charlie
        )(registry, combiner)

        labAfterComplete = state7.fiberRecord(labfiberId)
        labResult = labAfterComplete.extractString("result")

        // Step 8: Patient continues after passed result
        state8 <- state7.transition(
          patientfiberId,
          "continue",
          MapValue(Map("timestamp" -> IntValue(2400))),
          Bob
        )(registry, combiner)

        patientAfterContinue = state8.fiberRecord(patientfiberId)
        patientStatus = patientAfterContinue.extractString("status")
        patientVisitCount = patientAfterContinue.extractInt("visitCount")

        trialAfterVisit = state8.fiberRecord(trialfiberId)
        trialStatus = trialAfterVisit.extractString("status")

      } yield expect.all(
        // Verify patient enrollment succeeded
        state1.fiberRecord(patientfiberId).map(_.currentState).contains(StateId("enrolled")),
        // Verify trial started
        state2.fiberRecord(trialfiberId).map(_.currentState).contains(StateId("ACTIVE")),
        // Verify patient activated
        state3.fiberRecord(patientfiberId).map(_.currentState).contains(StateId("ACTIVE")),
        // Verify visit scheduled
        state4.fiberRecord(patientfiberId).map(_.currentState).contains(StateId("visit_scheduled")),
        // Verify lab received sample
        state5.fiberRecord(labfiberId).map(_.currentState).contains(StateId("sample_received")),
        // Verify trigger fired: lab is processing after visit completion
        labAfterTrigger.isDefined,
        labProcessingStatus.contains("processing"),
        labAfterTrigger.map(_.currentState).contains(StateId("processing")),
        // Verify lab completed with passed result (demonstrates multiple guards on same event)
        labAfterComplete.isDefined,
        labAfterComplete.map(_.currentState).contains(StateId("passed")),
        labResult.contains("passed"),
        // Verify patient continued and is back to active
        patientAfterContinue.isDefined,
        patientAfterContinue.map(_.currentState).contains(StateId("ACTIVE")),
        patientStatus.contains("ACTIVE"),
        patientVisitCount.contains(BigInt(1)),
        // Verify trial is still active
        trialAfterVisit.isDefined,
        trialStatus.contains("ACTIVE")
      )
    }
  }
}
