package xyz.kd5ujc.shared_data.examples

import java.util.UUID

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
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
import weaver.scalacheck.Checkers

/**
 * Riverdale Economy: Complex State Machine Test
 *
 * This test demonstrates a complete economic ecosystem with:
 * - 26 participants across 6 sectors
 * - Cross-machine triggers and cascading state changes
 * - Parent-child spawning for dynamic machine creation
 * - Complex dependency graphs with 50+ relationships
 * - Multi-layered business logic
 * - Crisis scenarios with system-wide impacts
 * - 60+ sequential events
 */
object RiverdaleEconomyStateMachineSuite extends SimpleIOSuite with Checkers {

  import DataStateTestOps._
  import TestImports.optionFiberRecordOps

  // Helper function to decode JSON to StateMachineDefinition
  private def decodeStateMachine(json: String): IO[StateMachineDefinition] =
    IO.fromEither(
      decode[StateMachineDefinition](json).left.map(err =>
        new RuntimeException(s"Failed to decode state machine JSON: $err")
      )
    )

  // JSON-encoded state machine definitions for each sector
  def manufacturerStateMachineJson(): String =
    s"""{
      "states": {
        "idle": { "id": { "value": "idle" }, "isFinal": false },
        "production_scheduled": { "id": { "value": "production_scheduled" }, "isFinal": false },
        "producing": { "id": { "value": "producing" }, "isFinal": false },
        "inventory_full": { "id": { "value": "inventory_full" }, "isFinal": false },
        "shipping": { "id": { "value": "shipping" }, "isFinal": false },
        "supply_shortage": { "id": { "value": "supply_shortage" }, "isFinal": false }
      },
      "initialState": { "value": "idle" },
      "transitions": [
        {
          "from": { "value": "idle" },
          "to": { "value": "production_scheduled" },
          "eventName": "schedule_production",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.rawMaterials" }, { "var": "state.requiredMaterials" }] },
              { "<": [{ "var": "state.inventory" }, { "var": "state.maxInventory" }] }
            ]
          },
          "effect": [
            ["status", "production_scheduled"],
            ["scheduledAt", { "var": "event.timestamp" }],
            ["batchSize", { "var": "event.batchSize" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "production_scheduled" },
          "to": { "value": "producing" },
          "eventName": "start_production",
          "guard": true,
          "effect": [
            ["status", "producing"],
            ["productionStartedAt", { "var": "event.timestamp" }],
            ["rawMaterials", { "-": [{ "var": "state.rawMaterials" }, { "var": "state.requiredMaterials" }] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "producing" },
          "to": { "value": "inventory_full" },
          "eventName": "complete_batch",
          "guard": { ">=": [{ "+": [{ "var": "state.inventory" }, { "var": "state.batchSize" }] }, { "var": "state.maxInventory" }] },
          "effect": [
            ["status", "inventory_full"],
            ["inventory", { "+": [{ "var": "state.inventory" }, { "var": "state.batchSize" }] }],
            ["completedAt", { "var": "event.timestamp" }],
            ["totalProduced", { "+": [{ "var": "state.totalProduced" }, { "var": "state.batchSize" }] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "inventory_full" },
          "to": { "value": "shipping" },
          "eventName": "fulfill_order",
          "guard": { ">=": [{ "var": "state.inventory" }, { "var": "event.orderQuantity" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.retailerId" },
                "eventName": "receive_shipment",
                "payload": {
                  "quantity": { "var": "event.orderQuantity" },
                  "supplier": { "var": "machineId" },
                  "shipmentId": { "var": "event.shipmentId" }
                }
              }
            ],
            "status": "shipping",
            "inventory": { "-": [{ "var": "state.inventory" }, { "var": "event.orderQuantity" }] },
            "shipmentCount": { "+": [{ "var": "state.shipmentCount" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "shipping" },
          "to": { "value": "idle" },
          "eventName": "complete_shipment",
          "guard": true,
          "effect": [
            ["status", "idle"],
            ["lastShipmentCompletedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "shipping" },
          "to": { "value": "production_scheduled" },
          "eventName": "schedule_production",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.rawMaterials" }, { "var": "state.requiredMaterials" }] },
              { "<": [{ "var": "state.inventory" }, { "var": "state.maxInventory" }] }
            ]
          },
          "effect": [
            ["status", "production_scheduled"],
            ["scheduledAt", { "var": "event.timestamp" }],
            ["batchSize", { "var": "event.batchSize" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "idle" },
          "to": { "value": "supply_shortage" },
          "eventName": "check_materials",
          "guard": { "<": [{ "var": "state.rawMaterials" }, { "var": "state.requiredMaterials" }] },
          "effect": {
            "_emit": [
              {
                "name": "alert",
                "data": {
                  "severity": "high",
                  "message": "Raw material shortage detected",
                  "manufacturer": { "var": "machineId" }
                }
              }
            ],
            "status": "supply_shortage",
            "shortageDetectedAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "idle" },
          "to": { "value": "idle" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "inventory_full" },
          "to": { "value": "inventory_full" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "shipping" },
          "to": { "value": "shipping" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "supply_shortage" },
          "to": { "value": "supply_shortage" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.totalProduced" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        }
      ]
    }"""

  def retailerStateMachineJson(supplierfiberId: String, paymentProcessorfiberId: String): String =
    s"""{
      "states": {
        "open": { "id": { "value": "open" }, "isFinal": false },
        "stocking": { "id": { "value": "stocking" }, "isFinal": false },
        "sale_active": { "id": { "value": "sale_active" }, "isFinal": false },
        "inventory_low": { "id": { "value": "inventory_low" }, "isFinal": false },
        "closed": { "id": { "value": "closed" }, "isFinal": false },
        "holiday_hours": { "id": { "value": "holiday_hours" }, "isFinal": false }
      },
      "initialState": { "value": "open" },
      "transitions": [
        {
          "from": { "value": "open" },
          "to": { "value": "inventory_low" },
          "eventName": "check_inventory",
          "guard": { "<": [{ "var": "state.stock" }, { "var": "state.reorderThreshold" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": "$supplierfiberId",
                "eventName": "fulfill_order",
                "payload": {
                  "retailerId": { "var": "machineId" },
                  "orderQuantity": { "var": "state.reorderQuantity" },
                  "shipmentId": { "var": "event.shipmentId" }
                }
              }
            ],
            "status": "inventory_low",
            "reorderInitiatedAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "inventory_low" },
          "to": { "value": "stocking" },
          "eventName": "receive_shipment",
          "guard": { "===": [{ "var": "event.supplier" }, "$supplierfiberId"] },
          "effect": [
            ["status", "stocking"],
            ["stock", { "+": [{ "var": "state.stock" }, { "var": "event.quantity" }] }],
            ["lastRestockAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "stocking" },
          "to": { "value": "open" },
          "eventName": "reopen",
          "guard": true,
          "effect": [
            ["status", "open"],
            ["reopenedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "open" },
          "to": { "value": "sale_active" },
          "eventName": "start_sale",
          "guard": {
            "or": [
              { "===": [{ "var": "event.season" }, "holiday"] },
              { "===": [{ "var": "event.season" }, "black_friday"] }
            ]
          },
          "effect": [
            ["status", "sale_active"],
            ["saleStartedAt", { "var": "event.timestamp" }],
            ["discountRate", { "var": "event.discountRate" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "open" },
          "to": { "value": "open" },
          "eventName": "process_sale",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.stock" }, { "var": "event.quantity" }] },
              { "===": [{ "var": "machines.$paymentProcessorfiberId.state.status" }, "ACTIVE"] }
            ]
          },
          "effect": [
            ["stock", { "-": [{ "var": "state.stock" }, { "var": "event.quantity" }] }],
            ["revenue", { "+": [
              { "var": "state.revenue" },
              { "*": [{ "var": "event.quantity" }, { "var": "state.pricePerUnit" }] }
            ] }],
            ["salesCount", { "+": [{ "var": "state.salesCount" }, 1] }]
          ],
          "dependencies": ["$paymentProcessorfiberId"]
        },
        {
          "from": { "value": "stocking" },
          "to": { "value": "stocking" },
          "eventName": "process_sale",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.stock" }, { "var": "event.quantity" }] },
              { "===": [{ "var": "machines.$paymentProcessorfiberId.state.status" }, "ACTIVE"] }
            ]
          },
          "effect": [
            ["stock", { "-": [{ "var": "state.stock" }, { "var": "event.quantity" }] }],
            ["revenue", { "+": [
              { "var": "state.revenue" },
              { "*": [{ "var": "event.quantity" }, { "var": "state.pricePerUnit" }] }
            ] }],
            ["salesCount", { "+": [{ "var": "state.salesCount" }, 1] }]
          ],
          "dependencies": ["$paymentProcessorfiberId"]
        },
        {
          "from": { "value": "open" },
          "to": { "value": "open" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "stocking" },
          "to": { "value": "stocking" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "sale_active" },
          "to": { "value": "sale_active" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.revenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        }
      ]
    }"""

  def bankStateMachineJson(federalReservefiberId: String): String =
    s"""{
      "states": {
        "operating": { "id": { "value": "operating" }, "isFinal": false },
        "processing_loan": { "id": { "value": "processing_loan" }, "isFinal": false },
        "loan_approved": { "id": { "value": "loan_approved" }, "isFinal": false },
        "loan_denied": { "id": { "value": "loan_denied" }, "isFinal": false },
        "loan_servicing": { "id": { "value": "loan_servicing" }, "isFinal": false },
        "default_management": { "id": { "value": "default_management" }, "isFinal": false },
        "stress_test": { "id": { "value": "stress_test" }, "isFinal": false },
        "liquidity_crisis": { "id": { "value": "liquidity_crisis" }, "isFinal": false }
      },
      "initialState": { "value": "operating" },
      "transitions": [
        {
          "from": { "value": "operating" },
          "to": { "value": "processing_loan" },
          "eventName": "apply_loan",
          "guard": {
            "and": [
              { ">=": [{ "var": "event.creditScore" }, { "var": "state.minCreditScore" }] },
              { ">=": [{ "var": "state.availableCapital" }, { "var": "event.loanAmount" }] }
            ]
          },
          "effect": [
            ["status", "processing_loan"],
            ["currentApplication", {
              "applicantId": { "var": "event.applicantId" },
              "amount": { "var": "event.loanAmount" },
              "creditScore": { "var": "event.creditScore" },
              "dti": { "var": "event.dti" }
            }],
            ["applicationReceivedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "processing_loan" },
          "to": { "value": "loan_servicing" },
          "eventName": "underwrite",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.currentApplication.creditScore" }, 620] },
              { "<=": [{ "var": "state.currentApplication.dti" }, 0.43] },
              { "===": [{ "var": "machines.$federalReservefiberId.state.status" }, "stable"] }
            ]
          },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "state.currentApplication.applicantId" },
                "eventName": "loan_funded",
                "payload": {
                  "loanId": { "var": "machineId" },
                  "amount": { "var": "state.currentApplication.amount" },
                  "interestRate": { "+": [
                    { "var": "machines.$federalReservefiberId.state.baseRate" },
                    { "var": "state.riskPremium" }
                  ] },
                  "fundedAt": { "var": "event.timestamp" }
                }
              }
            ],
            "status": "loan_servicing",
            "activeLoans": { "+": [{ "var": "state.activeLoans" }, 1] },
            "availableCapital": { "-": [{ "var": "state.availableCapital" }, { "var": "state.currentApplication.amount" }] },
            "loanPortfolio": { "+": [{ "var": "state.loanPortfolio" }, { "var": "state.currentApplication.amount" }] }
          },
          "dependencies": ["$federalReservefiberId"]
        },
        {
          "from": { "value": "loan_servicing" },
          "to": { "value": "loan_servicing" },
          "eventName": "payment_missed",
          "guard": { "<": [{ "var": "event.missedPayments" }, 3] },
          "effect": [
            ["missedPaymentCount", { "+": [{ "var": "state.missedPaymentCount" }, 1] }],
            ["lastMissedPaymentAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "loan_servicing" },
          "to": { "value": "default_management" },
          "eventName": "payment_missed",
          "guard": { ">=": [{ "var": "event.missedPayments" }, 3] },
          "effect": {
            "_emit": [
              {
                "name": "credit_report",
                "data": {
                  "borrowerId": { "var": "event.borrowerId" },
                  "delinquencyStatus": "90-days",
                  "loanId": { "var": "event.loanId" }
                }
              }
            ],
            "status": "default_management",
            "defaultedLoans": { "+": [{ "var": "state.defaultedLoans" }, 1] },
            "defaultRate": { "/": [{ "var": "state.defaultedLoans" }, { "var": "state.activeLoans" }] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "operating" },
          "to": { "value": "stress_test" },
          "eventName": "fed_directive",
          "guard": { "===": [{ "var": "machines.$federalReservefiberId.state.status" }, "stress_testing"] },
          "effect": [
            ["status", "stress_test"],
            ["testInitiatedAt", { "var": "event.timestamp" }],
            ["capitalRatio", { "if": [
              { ">": [{ "var": "state.loanPortfolio" }, 0] },
              { "/": [{ "var": "state.reserveCapital" }, { "var": "state.loanPortfolio" }] },
              1.0
            ] }]
          ],
          "dependencies": ["$federalReservefiberId"]
        },
        {
          "from": { "value": "loan_servicing" },
          "to": { "value": "stress_test" },
          "eventName": "fed_directive",
          "guard": { "===": [{ "var": "machines.$federalReservefiberId.state.status" }, "stress_testing"] },
          "effect": [
            ["status", "stress_test"],
            ["testInitiatedAt", { "var": "event.timestamp" }],
            ["capitalRatio", { "if": [
              { ">": [{ "var": "state.loanPortfolio" }, 0] },
              { "/": [{ "var": "state.reserveCapital" }, { "var": "state.loanPortfolio" }] },
              1.0
            ] }]
          ],
          "dependencies": ["$federalReservefiberId"]
        },
        {
          "from": { "value": "stress_test" },
          "to": { "value": "loan_servicing" },
          "eventName": "complete_stress_test",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.capitalRatio" }, 0.08] },
              { "<=": [{ "var": "state.defaultRate" }, 0.10] },
              { ">": [{ "var": "state.activeLoans" }, 0] }
            ]
          },
          "effect": [
            ["status", "loan_servicing"],
            ["testCompletedAt", { "var": "event.timestamp" }],
            ["stressTestPassed", true]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "stress_test" },
          "to": { "value": "operating" },
          "eventName": "complete_stress_test",
          "guard": {
            "and": [
              { ">=": [{ "var": "state.capitalRatio" }, 0.08] },
              { "<=": [{ "var": "state.defaultRate" }, 0.10] }
            ]
          },
          "effect": [
            ["status", "operating"],
            ["testCompletedAt", { "var": "event.timestamp" }],
            ["stressTestPassed", true]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "stress_test" },
          "to": { "value": "liquidity_crisis" },
          "eventName": "complete_stress_test",
          "guard": {
            "or": [
              { "<": [{ "var": "state.capitalRatio" }, 0.08] },
              { ">": [{ "var": "state.defaultRate" }, 0.10] }
            ]
          },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": "$federalReservefiberId",
                "eventName": "emergency_lending_request",
                "payload": {
                  "bankId": { "var": "machineId" },
                  "capitalShortfall": { "-": [
                    { "*": [{ "var": "state.loanPortfolio" }, 0.08] },
                    { "var": "state.reserveCapital" }
                  ] },
                  "requestedAt": { "var": "event.timestamp" }
                }
              }
            ],
            "status": "liquidity_crisis",
            "crisisStartedAt": { "var": "event.timestamp" }
          },
          "dependencies": ["$federalReservefiberId"]
        },
        {
          "from": { "value": "operating" },
          "to": { "value": "operating" },
          "eventName": "rate_adjustment",
          "guard": true,
          "effect": [
            ["baseRate", { "var": "event.newBaseRate" }],
            ["rateAdjustedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "loan_servicing" },
          "to": { "value": "loan_servicing" },
          "eventName": "rate_adjustment",
          "guard": true,
          "effect": [
            ["baseRate", { "var": "event.newBaseRate" }],
            ["rateAdjustedAt", { "var": "event.timestamp" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "loan_servicing" },
          "to": { "value": "loan_servicing" },
          "eventName": "payment_received",
          "guard": true,
          "effect": [
            ["lastPaymentReceivedAt", { "var": "event.timestamp" }],
            ["totalPaymentsReceived", { "+": [{ "var": "state.totalPaymentsReceived" }, { "var": "event.amount" }] }]
          ],
          "dependencies": []
        }
      ]
    }"""

  def consumerStateMachineJson(): String =
    """{
      "states": {
        "ACTIVE": { "id": { "value": "ACTIVE" }, "isFinal": false },
        "shopping": { "id": { "value": "shopping" }, "isFinal": false },
        "service_providing": { "id": { "value": "service_providing" }, "isFinal": false },
        "marketplace_selling": { "id": { "value": "marketplace_selling" }, "isFinal": false },
        "loan_active": { "id": { "value": "loan_active" }, "isFinal": false },
        "debt_current": { "id": { "value": "debt_current" }, "isFinal": false },
        "debt_delinquent": { "id": { "value": "debt_delinquent" }, "isFinal": false }
      },
      "initialState": { "value": "ACTIVE" },
      "transitions": [
        {
          "from": { "value": "ACTIVE" },
          "to": { "value": "ACTIVE" },
          "eventName": "browse_products",
          "guard": { ">=": [{ "var": "state.balance" }, { "var": "event.expectedCost" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.retailerId" },
                "eventName": "process_sale",
                "payload": {
                  "customerId": { "var": "machineId" },
                  "quantity": { "var": "event.quantity" }
                }
              }
            ],
            "balance": { "-": [{ "var": "state.balance" }, { "var": "event.expectedCost" }] },
            "purchaseCount": { "+": [{ "var": "state.purchaseCount" }, 1] },
            "lastPurchaseAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "ACTIVE" },
          "to": { "value": "ACTIVE" },
          "eventName": "provide_service",
          "guard": { "===": [{ "var": "state.serviceType" }, { "var": "event.requestedService" }] },
          "effect": [
            ["balance", { "+": [{ "var": "state.balance" }, { "var": "event.payment" }] }],
            ["serviceRevenue", { "+": [{ "var": "state.serviceRevenue" }, { "var": "event.payment" }] }],
            ["servicesProvided", { "+": [{ "var": "state.servicesProvided" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "ACTIVE" },
          "to": { "value": "debt_current" },
          "eventName": "loan_funded",
          "guard": true,
          "effect": [
            ["status", "debt_current"],
            ["loanBalance", { "var": "event.amount" }],
            ["loanInterestRate", { "var": "event.interestRate" }],
            ["lenderId", { "var": "event.loanId" }],
            ["balance", { "+": [{ "var": "state.balance" }, { "var": "event.amount" }] }],
            ["nextPaymentDue", { "+": [{ "var": "event.fundedAt" }, 2592000] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "debt_current" },
          "to": { "value": "debt_current" },
          "eventName": "make_payment",
          "guard": { ">=": [{ "var": "state.balance" }, { "var": "state.monthlyPayment" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "state.lenderId" },
                "eventName": "payment_received",
                "payload": {
                  "borrowerId": { "var": "machineId" },
                  "amount": { "var": "state.monthlyPayment" }
                }
              }
            ],
            "balance": { "-": [{ "var": "state.balance" }, { "var": "state.monthlyPayment" }] },
            "loanBalance": { "-": [
              { "var": "state.loanBalance" },
              { "-": [
                { "var": "state.monthlyPayment" },
                { "*": [{ "var": "state.loanBalance" }, { "var": "state.loanInterestRate" }] }
              ] }
            ] },
            "nextPaymentDue": { "+": [{ "var": "state.nextPaymentDue" }, 2592000] },
            "paymentsRemaining": { "-": [{ "var": "state.paymentsRemaining" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "debt_current" },
          "to": { "value": "debt_current" },
          "eventName": "browse_products",
          "guard": { ">=": [{ "var": "state.balance" }, { "var": "event.expectedCost" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.retailerId" },
                "eventName": "process_sale",
                "payload": {
                  "customerId": { "var": "machineId" },
                  "quantity": { "var": "event.quantity" }
                }
              }
            ],
            "balance": { "-": [{ "var": "state.balance" }, { "var": "event.expectedCost" }] },
            "purchaseCount": { "+": [{ "var": "state.purchaseCount" }, 1] },
            "lastPurchaseAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "debt_current" },
          "to": { "value": "debt_current" },
          "eventName": "provide_service",
          "guard": { "===": [{ "var": "state.serviceType" }, { "var": "event.requestedService" }] },
          "effect": [
            ["balance", { "+": [{ "var": "state.balance" }, { "var": "event.payment" }] }],
            ["serviceRevenue", { "+": [{ "var": "state.serviceRevenue" }, { "var": "event.payment" }] }],
            ["servicesProvided", { "+": [{ "var": "state.servicesProvided" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "debt_current" },
          "to": { "value": "debt_delinquent" },
          "eventName": "check_payment",
          "guard": { ">": [{ "var": "event.timestamp" }, { "var": "state.nextPaymentDue" }] },
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "state.lenderId" },
                "eventName": "payment_missed",
                "payload": {
                  "borrowerId": { "var": "machineId" },
                  "missedPayments": { "+": [{ "var": "state.missedPayments" }, 1] }
                }
              }
            ],
            "status": "debt_delinquent",
            "missedPayments": { "+": [{ "var": "state.missedPayments" }, 1] },
            "delinquentSince": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "ACTIVE" },
          "to": { "value": "marketplace_selling" },
          "eventName": "list_item",
          "guard": true,
          "effect": {
            "_spawn": [
              {
                "childId": { "var": "event.auctionId" },
                "definition": {
                  "states": {
                    "listed": { "id": { "value": "listed" }, "isFinal": false },
                    "bid_received": { "id": { "value": "bid_received" }, "isFinal": false },
                    "sold": { "id": { "value": "sold" }, "isFinal": true },
                    "expired": { "id": { "value": "expired" }, "isFinal": true }
                  },
                  "initialState": { "value": "listed" },
                  "transitions": [
                    {
                      "from": { "value": "listed" },
                      "to": { "value": "bid_received" },
                      "eventName": "place_bid",
                      "guard": { ">=": [{ "var": "event.bidAmount" }, { "var": "state.reservePrice" }] },
                      "effect": [
                        ["status", "bid_received"],
                        ["highestBid", { "var": "event.bidAmount" }],
                        ["highestBidder", { "var": "event.bidderId" }],
                        ["bidReceivedAt", { "var": "event.timestamp" }]
                      ],
                      "dependencies": []
                    },
                    {
                      "from": { "value": "bid_received" },
                      "to": { "value": "sold" },
                      "eventName": "accept_bid",
                      "guard": true,
                      "effect": {
                        "_triggers": [
                          {
                            "targetMachineId": { "var": "parent.machineId" },
                            "eventName": "sale_completed",
                            "payload": {
                              "amount": { "var": "state.highestBid" },
                              "buyer": { "var": "state.highestBidder" },
                              "itemName": { "var": "state.itemName" }
                            }
                          }
                        ],
                        "status": "sold",
                        "soldAt": { "var": "event.timestamp" }
                      },
                      "dependencies": []
                    }
                  ]
                },
                "initialData": {
                  "itemName": { "var": "event.itemName" },
                  "reservePrice": { "var": "event.reservePrice" },
                  "sellerId": { "var": "machineId" },
                  "status": "listed"
                }
              }
            ],
            "status": "marketplace_selling",
            "activeListings": { "+": [{ "var": "state.activeListings" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "marketplace_selling" },
          "to": { "value": "ACTIVE" },
          "eventName": "sale_completed",
          "guard": true,
          "effect": [
            ["balance", { "+": [{ "var": "state.balance" }, { "var": "event.amount" }] }],
            ["activeListings", { "-": [{ "var": "state.activeListings" }, 1] }],
            ["marketplaceSales", { "+": [{ "var": "state.marketplaceSales" }, 1] }],
            ["status", "ACTIVE"]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "debt_delinquent" },
          "to": { "value": "debt_delinquent" },
          "eventName": "list_item",
          "guard": true,
          "effect": {
            "_spawn": [
              {
                "childId": { "var": "event.auctionId" },
                "definition": {
                  "states": {
                    "listed": { "id": { "value": "listed" }, "isFinal": false },
                    "bid_received": { "id": { "value": "bid_received" }, "isFinal": false },
                    "sold": { "id": { "value": "sold" }, "isFinal": true },
                    "expired": { "id": { "value": "expired" }, "isFinal": true }
                  },
                  "initialState": { "value": "listed" },
                  "transitions": [
                    {
                      "from": { "value": "listed" },
                      "to": { "value": "bid_received" },
                      "eventName": "place_bid",
                      "guard": { ">=": [{ "var": "event.bidAmount" }, { "var": "state.reservePrice" }] },
                      "effect": [
                        ["status", "bid_received"],
                        ["highestBid", { "var": "event.bidAmount" }],
                        ["highestBidder", { "var": "event.bidderId" }],
                        ["bidReceivedAt", { "var": "event.timestamp" }]
                      ],
                      "dependencies": []
                    },
                    {
                      "from": { "value": "bid_received" },
                      "to": { "value": "sold" },
                      "eventName": "accept_bid",
                      "guard": true,
                      "effect": {
                        "_triggers": [
                          {
                            "targetMachineId": { "var": "parent.machineId" },
                            "eventName": "sale_completed",
                            "payload": {
                              "amount": { "var": "state.highestBid" },
                              "buyer": { "var": "state.highestBidder" },
                              "itemName": { "var": "state.itemName" }
                            }
                          }
                        ],
                        "status": "sold",
                        "soldAt": { "var": "event.timestamp" }
                      },
                      "dependencies": []
                    }
                  ]
                },
                "initialData": {
                  "itemName": { "var": "event.itemName" },
                  "reservePrice": { "var": "event.reservePrice" },
                  "sellerId": { "var": "machineId" },
                  "status": "listed"
                }
              }
            ],
            "activeListings": { "+": [{ "var": "state.activeListings" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "debt_delinquent" },
          "to": { "value": "debt_delinquent" },
          "eventName": "sale_completed",
          "guard": true,
          "effect": [
            ["balance", { "+": [{ "var": "state.balance" }, { "var": "event.amount" }] }],
            ["activeListings", { "-": [{ "var": "state.activeListings" }, 1] }],
            ["marketplaceSales", { "+": [{ "var": "state.marketplaceSales" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "ACTIVE" },
          "to": { "value": "ACTIVE" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "debt_current" },
          "to": { "value": "debt_current" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "debt_delinquent" },
          "to": { "value": "debt_delinquent" },
          "eventName": "pay_taxes",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.governanceId" },
                "eventName": "tax_payment_received",
                "payload": {
                  "taxpayerId": { "var": "machineId" },
                  "taxAmount": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
                  "taxPeriod": { "var": "event.taxPeriod" },
                  "timestamp": { "var": "event.collectionDate" }
                }
              }
            ],
            "taxesPaid": { "+": [
              { "var": "state.taxesPaid" },
              { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] }
            ] },
            "lastTaxPayment": { "*": [{ "var": "state.serviceRevenue" }, { "var": "event.taxRate" }] },
            "lastTaxPeriod": { "var": "event.taxPeriod" }
          },
          "dependencies": []
        }
      ]
    }"""

  def federalReserveStateMachineJson(bankCids: Set[UUID]): String = {
    val bankTriggers = bankCids
      .map { bankfiberId =>
        s"""{
        "targetMachineId": "$bankfiberId",
        "eventName": "rate_adjustment",
        "payload": {
          "newBaseRate": { "+": [{ "var": "state.baseRate" }, 0.0025] },
          "direction": "increase"
        }
      }"""
      }
      .mkString("[", ",", "]")

    val stressTestTriggers = bankCids
      .map { bankfiberId =>
        s"""{
        "targetMachineId": "$bankfiberId",
        "eventName": "fed_directive",
        "payload": { "directive": "stress_test" }
      }"""
      }
      .mkString("[", ",", "]")

    s"""{
      "states": {
        "stable": { "id": { "value": "stable" }, "isFinal": false },
        "rate_review": { "id": { "value": "rate_review" }, "isFinal": false },
        "rate_increased": { "id": { "value": "rate_increased" }, "isFinal": false },
        "rate_decreased": { "id": { "value": "rate_decreased" }, "isFinal": false },
        "stress_testing": { "id": { "value": "stress_testing" }, "isFinal": false },
        "emergency_lending": { "id": { "value": "emergency_lending" }, "isFinal": false }
      },
      "initialState": { "value": "stable" },
      "transitions": [
        {
          "from": { "value": "stable" },
          "to": { "value": "rate_review" },
          "eventName": "quarterly_meeting",
          "guard": true,
          "effect": [
            ["status", "rate_review"],
            ["reviewStartedAt", { "var": "event.timestamp" }],
            ["inflationRate", { "var": "event.inflationRate" }],
            ["unemploymentRate", { "var": "event.unemploymentRate" }],
            ["gdpGrowth", { "var": "event.gdpGrowth" }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "rate_review" },
          "to": { "value": "stable" },
          "eventName": "set_rate",
          "guard": { ">": [{ "var": "state.inflationRate" }, 0.025] },
          "effect": {
            "_triggers": $bankTriggers,
            "status": "stable",
            "baseRate": { "+": [{ "var": "state.baseRate" }, 0.0025] },
            "lastAdjustmentAt": { "var": "event.timestamp" },
            "rateChangeCount": { "+": [{ "var": "state.rateChangeCount" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "stable" },
          "to": { "value": "stress_testing" },
          "eventName": "initiate_stress_test",
          "guard": {
            "or": [
              { ">": [{ "var": "event.marketVolatility" }, 0.30] },
              { ">": [{ "var": "event.systemicRisk" }, 0.50] }
            ]
          },
          "effect": {
            "_triggers": $stressTestTriggers,
            "status": "stress_testing",
            "testInitiatedAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "stable" },
          "to": { "value": "emergency_lending" },
          "eventName": "emergency_lending_request",
          "guard": true,
          "effect": {
            "_triggers": [
              {
                "targetMachineId": { "var": "event.bankId" },
                "eventName": "receive_emergency_funds",
                "payload": {
                  "amount": { "var": "event.capitalShortfall" },
                  "interestRate": { "+": [{ "var": "state.baseRate" }, 0.01] },
                  "term": 90
                }
              }
            ],
            "_emit": [
              {
                "name": "regulatory_notice",
                "data": {
                  "type": "emergency_lending",
                  "recipient": { "var": "event.bankId" },
                  "amount": { "var": "event.capitalShortfall" }
                }
              }
            ],
            "status": "emergency_lending",
            "emergencyLoansIssued": { "+": [{ "var": "state.emergencyLoansIssued" }, 1] },
            "totalEmergencyLending": { "+": [{ "var": "state.totalEmergencyLending" }, { "var": "event.capitalShortfall" }] }
          },
          "dependencies": []
        }
      ]
    }"""
  }

  def governanceStateMachineJson(
    manufacturerCids: Set[UUID],
    retailerCids:     Set[UUID],
    consumerCids:     Set[UUID]
  ): String = {
    val allTaxpayerCids = manufacturerCids ++ retailerCids ++ consumerCids
    val taxCollectionTriggers = allTaxpayerCids
      .map { cid =>
        s"""{
        "targetMachineId": "$cid",
        "eventName": "pay_taxes",
        "payload": {
          "taxPeriod": { "var": "event.taxPeriod" },
          "taxRate": { "var": "event.taxRate" },
          "collectionDate": { "var": "event.timestamp" },
          "governanceId": { "var": "event.governanceId" }
        }
      }"""
      }
      .mkString("[", ",\n        ", "]")

    s"""{
      "states": {
        "monitoring": { "id": { "value": "monitoring" }, "isFinal": false },
        "tax_collection": { "id": { "value": "tax_collection" }, "isFinal": false },
        "audit": { "id": { "value": "audit" }, "isFinal": false },
        "compliant": { "id": { "value": "compliant" }, "isFinal": false }
      },
      "initialState": { "value": "monitoring" },
      "transitions": [
        {
          "from": { "value": "monitoring" },
          "to": { "value": "tax_collection" },
          "eventName": "collect_taxes",
          "guard": true,
          "effect": {
            "_triggers": $taxCollectionTriggers,
            "taxPeriod": { "var": "event.taxPeriod" },
            "taxRate": { "var": "event.taxRate" },
            "collectionInitiatedAt": { "var": "event.timestamp" },
            "expectedTaxpayers": ${allTaxpayerCids.size},
            "taxpayersCompliant": 0
          },
          "dependencies": []
        },
        {
          "from": { "value": "tax_collection" },
          "to": { "value": "tax_collection" },
          "eventName": "tax_payment_received",
          "guard": { "<": [{ "+": [{ "var": "state.taxpayersCompliant" }, 1] }, { "var": "state.expectedTaxpayers" }] },
          "effect": [
            ["totalTaxesCollected", { "+": [{ "var": "state.totalTaxesCollected" }, { "var": "event.taxAmount" }] }],
            ["lastPaymentFrom", { "var": "event.taxpayerId" }],
            ["lastPaymentAt", { "var": "event.timestamp" }],
            ["taxpayersCompliant", { "+": [{ "var": "state.taxpayersCompliant" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "tax_collection" },
          "to": { "value": "monitoring" },
          "eventName": "tax_payment_received",
          "guard": { "===": [{ "+": [{ "var": "state.taxpayersCompliant" }, 1] }, { "var": "state.expectedTaxpayers" }] },
          "effect": [
            ["totalTaxesCollected", { "+": [{ "var": "state.totalTaxesCollected" }, { "var": "event.taxAmount" }] }],
            ["lastPaymentFrom", { "var": "event.taxpayerId" }],
            ["lastPaymentAt", { "var": "event.timestamp" }],
            ["taxpayersCompliant", { "+": [{ "var": "state.taxpayersCompliant" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "monitoring" },
          "to": { "value": "monitoring" },
          "eventName": "tax_payment_received",
          "guard": true,
          "effect": [
            ["totalTaxesCollected", { "+": [{ "var": "state.totalTaxesCollected" }, { "var": "event.taxAmount" }] }],
            ["lastPaymentFrom", { "var": "event.taxpayerId" }],
            ["lastPaymentAt", { "var": "event.timestamp" }],
            ["taxpayersCompliant", { "+": [{ "var": "state.taxpayersCompliant" }, 1] }]
          ],
          "dependencies": []
        },
        {
          "from": { "value": "monitoring" },
          "to": { "value": "audit" },
          "eventName": "initiate_audit",
          "guard": true,
          "effect": {
            "auditTarget": { "var": "event.targetId" },
            "auditInitiatedAt": { "var": "event.timestamp" },
            "investigationsActive": { "+": [{ "var": "state.investigationsActive" }, 1] }
          },
          "dependencies": []
        },
        {
          "from": { "value": "audit" },
          "to": { "value": "compliant" },
          "eventName": "audit_complete",
          "guard": { "===": [{ "var": "event.finding" }, "compliant"] },
          "effect": {
            "_emit": [
              {
                "name": "audit_report",
                "data": {
                  "target": { "var": "state.auditTarget" },
                  "finding": "compliant",
                  "completedAt": { "var": "event.timestamp" }
                }
              }
            ],
            "investigationsActive": { "-": [{ "var": "state.investigationsActive" }, 1] },
            "auditCompletedAt": { "var": "event.timestamp" }
          },
          "dependencies": []
        },
        {
          "from": { "value": "compliant" },
          "to": { "value": "monitoring" },
          "eventName": "return_to_monitoring",
          "guard": true,
          "effect": [],
          "dependencies": []
        }
      ]
    }"""
  }

  test("riverdale-economy: complex economic simulation with many participants") {
    TestFixture
      .resource(
        Set(
          Alice,
          Bob,
          Charlie,
          Dave,
          Eve,
          Faythe,
          Grace,
          Heidi,
          Ivan,
          Judy,
          Karl,
          Lance,
          Mallory,
          Niaj,
          Oscar,
          Peggy,
          Quentin,
          Ruth,
          Sybil,
          Trent,
          Ursula,
          Victor,
          Walter,
          Xavier,
          Yolanda,
          Zoe
        )
      )
      .use { fixture =>
        implicit val s: SecurityProvider[IO] = fixture.securityProvider
        implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
        val registry = fixture.registry
        val ordinal = fixture.ordinal

        for {
          combiner <- Combiner.make[IO]().pure[IO]

          // Generate UUIDs for all 26 participants
          alicefiberId   <- UUIDGen.randomUUID[IO]
          bobfiberId     <- UUIDGen.randomUUID[IO]
          charliefiberId <- UUIDGen.randomUUID[IO]
          davefiberId    <- UUIDGen.randomUUID[IO]
          _evefiberId    <- UUIDGen.randomUUID[IO]

          _faythefiberId <- UUIDGen.randomUUID[IO]
          gracefiberId   <- UUIDGen.randomUUID[IO]
          heidifiberId   <- UUIDGen.randomUUID[IO]
          ivanfiberId    <- UUIDGen.randomUUID[IO]
          _judyfiberId   <- UUIDGen.randomUUID[IO]
          _karlfiberId   <- UUIDGen.randomUUID[IO]

          _lancefiberId   <- UUIDGen.randomUUID[IO]
          _malloryfiberId <- UUIDGen.randomUUID[IO]
          niajfiberId     <- UUIDGen.randomUUID[IO]

          oscarfiberId   <- UUIDGen.randomUUID[IO]
          peggyfiberId   <- UUIDGen.randomUUID[IO]
          quentinfiberId <- UUIDGen.randomUUID[IO]

          ruthfiberId    <- UUIDGen.randomUUID[IO]
          sybilfiberId   <- UUIDGen.randomUUID[IO]
          _trentfiberId  <- UUIDGen.randomUUID[IO]
          _ursulafiberId <- UUIDGen.randomUUID[IO]
          victorfiberId  <- UUIDGen.randomUUID[IO]
          _walterfiberId <- UUIDGen.randomUUID[IO]

          xavierfiberId  <- UUIDGen.randomUUID[IO]
          yolandafiberId <- UUIDGen.randomUUID[IO]
          _zoefiberId    <- UUIDGen.randomUUID[IO]

          // Create initial state data for each participant type
          manufacturerInitialData = MapValue(
            Map(
              "rawMaterials"      -> IntValue(1000),
              "inventory"         -> IntValue(420),
              "maxInventory"      -> IntValue(500),
              "requiredMaterials" -> IntValue(50),
              "batchSize"         -> IntValue(0),
              "totalProduced"     -> IntValue(0),
              "shipmentCount"     -> IntValue(0),
              "taxesPaid"         -> FloatValue(0.0),
              "status"            -> StrValue("idle")
            )
          )

          retailerInitialData = MapValue(
            Map(
              "stock"            -> IntValue(15),
              "reorderThreshold" -> IntValue(20),
              "reorderQuantity"  -> IntValue(100),
              "revenue"          -> IntValue(0),
              "salesCount"       -> IntValue(0),
              "pricePerUnit"     -> IntValue(10),
              "taxesPaid"        -> FloatValue(0.0),
              "status"           -> StrValue("open")
            )
          )

          platformInitialData = MapValue(
            Map(
              "responseTime" -> IntValue(100),
              "capacity"     -> IntValue(1000),
              "transactions" -> IntValue(0),
              "status"       -> StrValue("ACTIVE")
            )
          )

          bankInitialData = MapValue(
            Map(
              "baseRate"              -> FloatValue(0.04),
              "availableCapital"      -> IntValue(1000000),
              "activeLoans"           -> IntValue(0),
              "defaultedLoans"        -> IntValue(0),
              "loanPortfolio"         -> IntValue(0),
              "reserveCapital"        -> IntValue(100000),
              "minCreditScore"        -> IntValue(600),
              "riskPremium"           -> FloatValue(0.02),
              "defaultRate"           -> FloatValue(0.0),
              "missedPaymentCount"    -> IntValue(0),
              "totalPaymentsReceived" -> IntValue(0),
              "status"                -> StrValue("operating")
            )
          )

          consumerInitialData = MapValue(
            Map(
              "balance"           -> IntValue(5000),
              "serviceRevenue"    -> IntValue(0),
              "loanBalance"       -> IntValue(0),
              "purchaseCount"     -> IntValue(0),
              "servicesProvided"  -> IntValue(0),
              "activeListings"    -> IntValue(0),
              "marketplaceSales"  -> IntValue(0),
              "serviceType"       -> StrValue("software_development"),
              "monthlyPayment"    -> IntValue(300),
              "missedPayments"    -> IntValue(0),
              "paymentsRemaining" -> IntValue(36),
              "taxesPaid"         -> FloatValue(0.0),
              "status"            -> StrValue("ACTIVE")
            )
          )

          fedInitialData = MapValue(
            Map(
              "baseRate"              -> FloatValue(0.04),
              "inflationRate"         -> FloatValue(0.02),
              "unemploymentRate"      -> FloatValue(0.04),
              "gdpGrowth"             -> FloatValue(0.03),
              "emergencyLoansIssued"  -> IntValue(0),
              "totalEmergencyLending" -> IntValue(0),
              "rateChangeCount"       -> IntValue(0),
              "status"                -> StrValue("stable")
            )
          )

          governanceInitialData = MapValue(
            Map(
              "investigationsActive" -> IntValue(0),
              "enforcementActions"   -> IntValue(0),
              "totalTaxesCollected"  -> IntValue(0),
              "taxpayersCompliant"   -> IntValue(0),
              "expectedTaxpayers"    -> IntValue(0)
            )
          )

          // Create state machine definitions from JSON
          manufacturerDef <- decodeStateMachine(manufacturerStateMachineJson())
          bankDef         <- decodeStateMachine(bankStateMachineJson(yolandafiberId.toString))
          consumerDef     <- decodeStateMachine(consumerStateMachineJson())
          fedDef <- decodeStateMachine(federalReserveStateMachineJson(Set(oscarfiberId, peggyfiberId, quentinfiberId)))
          governanceDef <- decodeStateMachine(
            governanceStateMachineJson(
              Set(alicefiberId, bobfiberId, charliefiberId, davefiberId),
              Set(heidifiberId, gracefiberId, ivanfiberId),
              Set(ruthfiberId, sybilfiberId, victorfiberId)
            )
          )

          // Initialize manufacturer fibers using FiberBuilder
          aliceFiber <- FiberBuilder(alicefiberId, ordinal, manufacturerDef)
            .withState("idle")
            .withDataValue(
              manufacturerInitialData
                .copy(value = manufacturerInitialData.value + ("businessName" -> StrValue("SteelCore Manufacturing")))
            )
            .ownedBy(registry, Alice)
            .build[IO]

          bobFiber <- FiberBuilder(bobfiberId, ordinal, manufacturerDef)
            .withState("idle")
            .withDataValue(
              manufacturerInitialData
                .copy(value = manufacturerInitialData.value + ("businessName" -> StrValue("AgriGrow Farms")))
            )
            .ownedBy(registry, Bob)
            .build[IO]

          charlieInitialData = manufacturerInitialData.copy(value =
            manufacturerInitialData.value ++ Map(
              "rawMaterials" -> IntValue(40), // Below required materials threshold
              "businessName" -> StrValue("TechParts Inc")
            )
          )
          charlieFiber <- FiberBuilder(charliefiberId, ordinal, manufacturerDef)
            .withState("idle")
            .withDataValue(charlieInitialData)
            .ownedBy(registry, Charlie)
            .build[IO]

          // Initialize retailer fibers (with dependencies on manufacturers)
          heidiRetailerDef <- decodeStateMachine(retailerStateMachineJson(alicefiberId.toString, niajfiberId.toString))
          heidiData = retailerInitialData
            .copy(value = retailerInitialData.value + ("businessName" -> StrValue("QuickFix Hardware")))
          heidiFiber <- FiberBuilder(heidifiberId, ordinal, heidiRetailerDef)
            .withState("open")
            .withDataValue(heidiData)
            .ownedBy(registry, Heidi)
            .build[IO]

          // Initialize platform fibers
          niajData = platformInitialData
            .copy(value = platformInitialData.value + ("businessName" -> StrValue("PayFlow Processing")))
          niajFiber <- FiberBuilder(niajfiberId, ordinal, manufacturerDef) // Simplified for test
            .withState("idle")
            .withDataValue(niajData)
            .ownedBy(registry, Niaj)
            .build[IO]

          // Initialize bank fibers
          oscarFiber <- FiberBuilder(oscarfiberId, ordinal, bankDef)
            .withState("operating")
            .withDataValue(
              bankInitialData
                .copy(value = bankInitialData.value + ("businessName" -> StrValue("Riverdale National Bank")))
            )
            .ownedBy(registry, Oscar)
            .build[IO]

          peggyFiber <- FiberBuilder(peggyfiberId, ordinal, bankDef)
            .withState("operating")
            .withDataValue(
              bankInitialData.copy(value = bankInitialData.value + ("businessName" -> StrValue("SecureCredit Union")))
            )
            .ownedBy(registry, Peggy)
            .build[IO]

          quentinFiber <- FiberBuilder(quentinfiberId, ordinal, bankDef)
            .withState("operating")
            .withDataValue(
              bankInitialData
                .copy(value = bankInitialData.value + ("businessName" -> StrValue("VentureForward Capital")))
            )
            .ownedBy(registry, Quentin)
            .build[IO]

          // Initialize consumer fibers
          ruthFiber <- FiberBuilder(ruthfiberId, ordinal, consumerDef)
            .withState("ACTIVE")
            .withDataValue(
              consumerInitialData
                .copy(value = consumerInitialData.value + ("name" -> StrValue("Ruth - Software Developer")))
            )
            .ownedBy(registry, Ruth)
            .build[IO]

          sybilInitialData = consumerInitialData.copy(value =
            consumerInitialData.value ++ Map(
              "balance" -> IntValue(3000), // Lower balance than Ruth
              "name"    -> StrValue("Sybil - Crafts Seller")
            )
          )
          sybilFiber <- FiberBuilder(sybilfiberId, ordinal, consumerDef)
            .withState("ACTIVE")
            .withDataValue(sybilInitialData)
            .ownedBy(registry, Sybil)
            .build[IO]

          victorInitialData = consumerInitialData.copy(value =
            consumerInitialData.value ++ Map(
              "balance" -> IntValue(10000), // High balance for bidding
              "name"    -> StrValue("Victor - Collector")
            )
          )
          victorFiber <- FiberBuilder(victorfiberId, ordinal, consumerDef)
            .withState("ACTIVE")
            .withDataValue(victorInitialData)
            .ownedBy(registry, Victor)
            .build[IO]

          // Initialize Federal Reserve fiber
          yolandaFiber <- FiberBuilder(yolandafiberId, ordinal, fedDef)
            .withState("stable")
            .withDataValue(
              fedInitialData.copy(value = fedInitialData.value + ("name" -> StrValue("Federal Reserve Branch")))
            )
            .ownedBy(registry, Yolanda)
            .build[IO]

          // Initialize Governance fiber
          xavierFiber <- FiberBuilder(xavierfiberId, ordinal, governanceDef)
            .withState("monitoring")
            .withDataValue(
              governanceInitialData
                .copy(value = governanceInitialData.value + ("name" -> StrValue("Riverdale Governance")))
            )
            .ownedBy(registry, Xavier)
            .build[IO]

          // Create initial state with key participants
          initialState <- DataState(OnChain.genesis, CalculatedState.genesis).withRecords[IO](
            Map(
              alicefiberId   -> aliceFiber,
              bobfiberId     -> bobFiber,
              charliefiberId -> charlieFiber,
              heidifiberId   -> heidiFiber,
              niajfiberId    -> niajFiber,
              oscarfiberId   -> oscarFiber,
              peggyfiberId   -> peggyFiber,
              quentinfiberId -> quentinFiber,
              ruthfiberId    -> ruthFiber,
              sybilfiberId   -> sybilFiber,
              victorfiberId  -> victorFiber,
              xavierfiberId  -> xavierFiber,
              yolandafiberId -> yolandaFiber
            )
          )

          // ========================================
          // PHASE 1: NORMAL ECONOMIC ACTIVITY
          // ========================================

          // Event 1: Federal Reserve quarterly meeting (high inflation scenario)
          state1 <- initialState.transition(
            yolandafiberId,
            "quarterly_meeting",
            MapValue(
              Map(
                "timestamp"        -> IntValue(1000),
                "inflationRate"    -> FloatValue(0.03), // Above 0.025 threshold triggers rate increase
                "unemploymentRate" -> FloatValue(0.04),
                "gdpGrowth"        -> FloatValue(0.03)
              )
            ),
            Yolanda
          )(registry, combiner)

          // Event 2: Fed sets rate (returns to stable, broadcasts rate_adjustment to all banks)
          state2 <- state1.transition(
            yolandafiberId,
            "set_rate",
            MapValue(Map("timestamp" -> IntValue(1050))),
            Yolanda
          )(registry, combiner)

          // Event 3: Alice schedules production
          state3 <- state2.transition(
            alicefiberId,
            "schedule_production",
            MapValue(
              Map(
                "timestamp" -> IntValue(1100),
                "batchSize" -> IntValue(100)
              )
            ),
            Alice
          )(registry, combiner)

          // Event 4: Alice starts production
          state4 <- state3.transition(
            alicefiberId,
            "start_production",
            MapValue(Map("timestamp" -> IntValue(1200))),
            Alice
          )(registry, combiner)

          // Event 5: Alice completes batch
          state5 <- state4.transition(
            alicefiberId,
            "complete_batch",
            MapValue(Map("timestamp" -> IntValue(2000))),
            Alice
          )(registry, combiner)

          // Event 6: Heidi checks inventory (should trigger order to Alice)
          state6 <- state5.transition(
            heidifiberId,
            "check_inventory",
            MapValue(
              Map(
                "timestamp"  -> IntValue(2100),
                "shipmentId" -> StrValue("SHIP-001")
              )
            ),
            Heidi
          )(registry, combiner)

          // Verify Heidi's state changed to inventory_low
          heidiFiberAfterCheck = state6.fiberRecord(heidifiberId)

          // Event 7: Ruth applies for loan from Oscar
          state7 <- state6.transition(
            oscarfiberId,
            "apply_loan",
            MapValue(
              Map(
                "timestamp"   -> IntValue(2200),
                "applicantId" -> StrValue(ruthfiberId.toString),
                "loanAmount"  -> IntValue(10000),
                "creditScore" -> IntValue(720),
                "dti"         -> FloatValue(0.35)
              )
            ),
            Oscar
          )(registry, combiner)

          // Event 8: Oscar underwrites loan (Fed must be in stable state)
          state8 <- state7.transition(
            oscarfiberId,
            "underwrite",
            MapValue(Map("timestamp" -> IntValue(2300))),
            Oscar
          )(registry, combiner)

          // ========================================
          // PHASE 2: SUPPLY CHAIN DISRUPTION
          // ========================================

          // Event 9: Supply shortage at Charlie's TechParts
          state9 <- state8.transition(
            charliefiberId,
            "check_materials",
            MapValue(Map("timestamp" -> IntValue(3000))),
            Charlie
          )(registry, combiner)

          // Verify Charlie entered supply_shortage state
          charlieFiberAfterShortage = state9.fiberRecord(charliefiberId)

          // ========================================
          // PHASE 3: BOB PRODUCTION CYCLE
          // ========================================

          // Event 10: Bob schedules production
          state10 <- state9.transition(
            bobfiberId,
            "schedule_production",
            MapValue(
              Map(
                "timestamp" -> IntValue(3100),
                "batchSize" -> IntValue(80)
              )
            ),
            Bob
          )(registry, combiner)

          // Event 11: Bob starts production
          state11 <- state10.transition(
            bobfiberId,
            "start_production",
            MapValue(Map("timestamp" -> IntValue(3200))),
            Bob
          )(registry, combiner)

          // Event 12: Bob completes batch
          state12 <- state11.transition(
            bobfiberId,
            "complete_batch",
            MapValue(Map("timestamp" -> IntValue(4000))),
            Bob
          )(registry, combiner)

          // ========================================
          // PHASE 4: GRACE RETAILER CROSS-CHAIN
          // ========================================

          // Initialize Grace (retailer depending on Bob's farm)
          graceRetailerDef <- decodeStateMachine(retailerStateMachineJson(bobfiberId.toString, niajfiberId.toString))
          graceData = retailerInitialData
            .copy(value = retailerInitialData.value + ("businessName" -> StrValue("FreshFoods Market")))
          graceFiber <- FiberBuilder(gracefiberId, ordinal, graceRetailerDef)
            .withState("open")
            .withDataValue(graceData)
            .ownedBy(registry, Grace)
            .build[IO]

          // Add Grace to the state
          state13 <- state12.withRecord[IO](gracefiberId, graceFiber)

          // Event 13: Grace checks inventory (triggers order to Bob)
          state14 <- state13.transition(
            gracefiberId,
            "check_inventory",
            MapValue(
              Map(
                "timestamp"  -> IntValue(4100),
                "shipmentId" -> StrValue("SHIP-002")
              )
            ),
            Grace
          )(registry, combiner)

          // ========================================
          // PHASE 5: RUTH CONSUMER SHOPPING
          // ========================================

          // Event 14: Heidi reopens after restocking
          state15 <- state14.transition(
            heidifiberId,
            "reopen",
            MapValue(Map("timestamp" -> IntValue(4150))),
            Heidi
          )(registry, combiner)

          // Event 15: Ruth browses products at Heidi's store
          state16 <- state15.transition(
            ruthfiberId,
            "browse_products",
            MapValue(
              Map(
                "timestamp"    -> IntValue(4200),
                "retailerId"   -> StrValue(heidifiberId.toString),
                "expectedCost" -> IntValue(50),
                "quantity"     -> IntValue(5)
              )
            ),
            Ruth
          )(registry, combiner)

          // ========================================
          // PHASE 6: SYBIL CONSUMER DELINQUENCY
          // ========================================

          // Event 16: Sybil applies for loan from Peggy
          state17 <- state16.transition(
            peggyfiberId,
            "apply_loan",
            MapValue(
              Map(
                "timestamp"   -> IntValue(4300),
                "applicantId" -> StrValue(sybilfiberId.toString),
                "loanAmount"  -> IntValue(5000),
                "creditScore" -> IntValue(640),
                "dti"         -> FloatValue(0.42)
              )
            ),
            Peggy
          )(registry, combiner)

          // Event 17: Peggy underwrites loan for Sybil
          state18 <- state17.transition(
            peggyfiberId,
            "underwrite",
            MapValue(Map("timestamp" -> IntValue(4400))),
            Peggy
          )(registry, combiner)

          // Event 18: Sybil checks payment (misses payment - timestamp way past nextPaymentDue)
          state19 <- state18.transition(
            sybilfiberId,
            "check_payment",
            MapValue(Map("timestamp" -> IntValue(7100000))), // Way past due date (2592000 + 4400 = 2596400)
            Sybil
          )(registry, combiner)

          // ========================================
          // PHASE 7: STRESS TEST SCENARIO
          // ========================================

          // Event 19: Fed initiates stress test due to high market volatility
          state20 <- state19.transition(
            yolandafiberId,
            "initiate_stress_test",
            MapValue(
              Map(
                "timestamp"        -> IntValue(7200000),
                "marketVolatility" -> FloatValue(0.35), // Above 0.30 threshold
                "systemicRisk"     -> FloatValue(0.45)
              )
            ),
            Yolanda
          )(registry, combiner)

          // Event 20: Oscar completes stress test (passes - good capital ratio)
          state21 <- state20.transition(
            oscarfiberId,
            "complete_stress_test",
            MapValue(Map("timestamp" -> IntValue(7300000))),
            Oscar
          )(registry, combiner)

          // Event 21: Peggy completes stress test (passes - good capital ratio)
          state22 <- state21.transition(
            peggyfiberId,
            "complete_stress_test",
            MapValue(Map("timestamp" -> IntValue(7300050))),
            Peggy
          )(registry, combiner)

          // Event 22: Quentin completes stress test (passes - good capital ratio)
          state23 <- state22.transition(
            quentinfiberId,
            "complete_stress_test",
            MapValue(Map("timestamp" -> IntValue(7300100))),
            Quentin
          )(registry, combiner)

          // ========================================
          // PHASE 8: HEIDI HOLIDAY SALE
          // ========================================

          // Event 23: Heidi starts holiday sale (20% discount)
          state24 <- state23.transition(
            heidifiberId,
            "start_sale",
            MapValue(
              Map(
                "timestamp"    -> IntValue(7400000),
                "season"       -> StrValue("holiday"),
                "discountRate" -> FloatValue(0.20)
              )
            ),
            Heidi
          )(registry, combiner)

          // Capture stress test state for validation
          oscarFiberAfterStressTest = state23.fiberRecord(oscarfiberId)
          peggyFiberAfterStressTest = state23.fiberRecord(peggyfiberId)
          quentinFiberAfterStressTest = state23.fiberRecord(quentinfiberId)
          yolandaFiberAfterStressTest = state23.fiberRecord(yolandafiberId)

          // Capture holiday sale state for validation
          heidiFiberAfterSale = state24.fiberRecord(heidifiberId)

          // ========================================
          // PHASE 9: C2C MARKETPLACE (AUCTION)
          // ========================================

          auctionfiberId <- UUIDGen.randomUUID[IO]

          // Event 24: Sybil lists handmade craft item for auction (spawns auction child machine)
          state25 <- state24.transition(
            sybilfiberId,
            "list_item",
            MapValue(
              Map(
                "auctionId"    -> StrValue(auctionfiberId.toString),
                "itemName"     -> StrValue("Handmade Ceramic Vase"),
                "reservePrice" -> IntValue(500),
                "timestamp"    -> IntValue(7500000)
              )
            ),
            Sybil
          )(registry, combiner)

          // Event 25: Victor places bid on Sybil's auction (bid exceeds reserve price)
          state26 <- state25.transition(
            auctionfiberId,
            "place_bid",
            MapValue(
              Map(
                "bidAmount" -> IntValue(750),
                "bidderId"  -> StrValue(victorfiberId.toString),
                "timestamp" -> IntValue(7600000)
              )
            ),
            Victor
          )(registry, combiner)

          // Event 26: Auction accepts bid (triggers sale_completed to Sybil)
          state27 <- state26.transition(
            auctionfiberId,
            "accept_bid",
            MapValue(Map("timestamp" -> IntValue(7700000))),
            Sybil
          )(registry, combiner)

          // Capture C2C marketplace state for validation
          auctionFiberAfterBidAccepted = state27.fiberRecord(auctionfiberId)
          sybilFiberAfterSale = state27.fiberRecord(sybilfiberId)

          // ========================================
          // PHASE 10: RUTH MAKES LOAN PAYMENT
          // ========================================

          // Event 27: Ruth makes first loan payment to Oscar
          state28 <- state27.transition(
            ruthfiberId,
            "make_payment",
            MapValue(Map("timestamp" -> IntValue(7750000))),
            Ruth
          )(registry, combiner)

          // ========================================
          // PHASE 11: SECOND AUCTION (Victor sells)
          // ========================================

          auction2fiberId <- UUIDGen.randomUUID[IO]

          // Event 28: Victor lists antique item for auction
          state29 <- state28.transition(
            victorfiberId,
            "list_item",
            MapValue(
              Map(
                "auctionId"    -> StrValue(auction2fiberId.toString),
                "itemName"     -> StrValue("Vintage Watch Collection"),
                "reservePrice" -> IntValue(2000),
                "timestamp"    -> IntValue(7800000)
              )
            ),
            Victor
          )(registry, combiner)

          // Event 29: Ruth places bid on Victor's auction (using loan proceeds)
          state30 <- state29.transition(
            auction2fiberId,
            "place_bid",
            MapValue(
              Map(
                "bidAmount" -> IntValue(2500),
                "bidderId"  -> StrValue(ruthfiberId.toString),
                "timestamp" -> IntValue(7900000)
              )
            ),
            Ruth
          )(registry, combiner)

          // Event 30: Victor accepts Ruth's bid
          state31 <- state30.transition(
            auction2fiberId,
            "accept_bid",
            MapValue(Map("timestamp" -> IntValue(8000000))),
            Victor
          )(registry, combiner)

          // ========================================
          // PHASE 12: ADDITIONAL MANUFACTURER CYCLES
          // ========================================

          // Event 31: Dave (new manufacturer) initialization and production
          daveRetailerDef <- decodeStateMachine(manufacturerStateMachineJson())
          daveData = manufacturerInitialData
            .copy(value = manufacturerInitialData.value + ("businessName" -> StrValue("TextileWorks Co")))
          daveFiber <- FiberBuilder(davefiberId, ordinal, daveRetailerDef)
            .withState("idle")
            .withDataValue(daveData)
            .ownedBy(registry, Dave)
            .build[IO]

          // Add Dave to the state
          state32 <- state31.withRecord[IO](davefiberId, daveFiber)

          // Event 32: Dave schedules production
          state33 <- state32.transition(
            davefiberId,
            "schedule_production",
            MapValue(
              Map(
                "timestamp" -> IntValue(8100000),
                "batchSize" -> IntValue(150)
              )
            ),
            Dave
          )(registry, combiner)

          // Event 33: Dave starts production
          state34 <- state33.transition(
            davefiberId,
            "start_production",
            MapValue(Map("timestamp" -> IntValue(8200000))),
            Dave
          )(registry, combiner)

          // Event 34: Dave completes production
          state35 <- state34.transition(
            davefiberId,
            "complete_batch",
            MapValue(Map("timestamp" -> IntValue(8300000))),
            Dave
          )(registry, combiner)

          // ========================================
          // PHASE 13: IVAN RETAILER (DEPENDS ON DAVE)
          // ========================================

          // Event 35: Ivan retailer initialization
          ivanRetailerDef <- decodeStateMachine(retailerStateMachineJson(davefiberId.toString, niajfiberId.toString))
          ivanData = retailerInitialData
            .copy(value = retailerInitialData.value + ("businessName" -> StrValue("StyleHub Boutique")))
          ivanFiber <- FiberBuilder(ivanfiberId, ordinal, ivanRetailerDef)
            .withState("open")
            .withDataValue(ivanData)
            .ownedBy(registry, Ivan)
            .build[IO]

          // Add Ivan to the state
          state36 <- state35.withRecord[IO](ivanfiberId, ivanFiber)

          // Event 36: Ivan checks inventory (triggers order to Dave)
          state37 <- state36.transition(
            ivanfiberId,
            "check_inventory",
            MapValue(
              Map(
                "timestamp"  -> IntValue(8400000),
                "shipmentId" -> StrValue("SHIP-003")
              )
            ),
            Ivan
          )(registry, combiner)

          // ========================================
          // PHASE 14: CONSUMER SERVICE PROVISION
          // ========================================

          // Event 37: Ruth provides freelance service (earns income)
          state38 <- state37.transition(
            ruthfiberId,
            "provide_service",
            MapValue(
              Map(
                "timestamp"        -> IntValue(8500000),
                "requestedService" -> StrValue("software_development"),
                "payment"          -> IntValue(3000)
              )
            ),
            Ruth
          )(registry, combiner)

          // ========================================
          // PHASE 15: MORE CONSUMER SHOPPING
          // ========================================

          // Event 38: Victor shops at Grace's FreshFoods Market
          state39 <- state38.transition(
            victorfiberId,
            "browse_products",
            MapValue(
              Map(
                "timestamp"    -> IntValue(8600000),
                "retailerId"   -> StrValue(gracefiberId.toString),
                "expectedCost" -> IntValue(100),
                "quantity"     -> IntValue(10)
              )
            ),
            Victor
          )(registry, combiner)

          // Event 39: Grace reopens after restocking
          state40 <- state39.transition(
            gracefiberId,
            "reopen",
            MapValue(Map("timestamp" -> IntValue(8650000))),
            Grace
          )(registry, combiner)

          // Event 40: Ivan reopens after restocking from Dave
          state41 <- state40.transition(
            ivanfiberId,
            "reopen",
            MapValue(Map("timestamp" -> IntValue(8700000))),
            Ivan
          )(registry, combiner)

          // ========================================
          // PHASE 16: ADDITIONAL ECONOMIC ACTIVITY
          // ========================================

          // Event 41: Alice schedules another production batch
          state42 <- state41.transition(
            alicefiberId,
            "schedule_production",
            MapValue(
              Map(
                "timestamp" -> IntValue(8800000),
                "batchSize" -> IntValue(80)
              )
            ),
            Alice
          )(registry, combiner)

          // Event 42: Alice starts second production run
          state43 <- state42.transition(
            alicefiberId,
            "start_production",
            MapValue(Map("timestamp" -> IntValue(8900000))),
            Alice
          )(registry, combiner)

          // Event 43: Alice completes second batch
          state44 <- state43.transition(
            alicefiberId,
            "complete_batch",
            MapValue(Map("timestamp" -> IntValue(9000000))),
            Alice
          )(registry, combiner)

          // ========================================
          // PHASE 17: TAX COLLECTION
          // ========================================

          // Instead of broadcasting to all 10 taxpayers at once (which would exceed execution depth),
          // we send individual pay_taxes events to each taxpayer.

          taxPayload = MapValue(
            Map(
              "taxPeriod"      -> StrValue("Q4-2024"),
              "taxRate"        -> FloatValue(0.05),
              "collectionDate" -> IntValue(9100000),
              "governanceId"   -> StrValue(xavierfiberId.toString)
            )
          )

          // Event 44: Alice pays taxes
          state45 <- state44.transition(alicefiberId, "pay_taxes", taxPayload, Alice)(registry, combiner)

          // Event 45: Bob pays taxes
          state46 <- state45.transition(bobfiberId, "pay_taxes", taxPayload, Bob)(registry, combiner)

          // Event 46: Charlie pays taxes
          state47 <- state46.transition(charliefiberId, "pay_taxes", taxPayload, Charlie)(registry, combiner)

          // Event 47: Dave pays taxes
          state48 <- state47.transition(davefiberId, "pay_taxes", taxPayload, Dave)(registry, combiner)

          // Event 48: Heidi pays taxes
          state49 <- state48.transition(heidifiberId, "pay_taxes", taxPayload, Heidi)(registry, combiner)

          // Event 49: Grace pays taxes
          state50 <- state49.transition(gracefiberId, "pay_taxes", taxPayload, Grace)(registry, combiner)

          // Event 50: Ivan pays taxes
          state51 <- state50.transition(ivanfiberId, "pay_taxes", taxPayload, Ivan)(registry, combiner)

          // Event 51: Ruth pays taxes
          state52 <- state51.transition(ruthfiberId, "pay_taxes", taxPayload, Ruth)(registry, combiner)

          // Event 52: Sybil pays taxes
          state53 <- state52.transition(sybilfiberId, "pay_taxes", taxPayload, Sybil)(registry, combiner)

          // Event 53: Victor pays taxes
          state54 <- state53.transition(victorfiberId, "pay_taxes", taxPayload, Victor)(registry, combiner)

          // ========================================
          // FINAL VALIDATIONS
          // ========================================

          // Extract final states for validation (use state54 to include all tax payments)
          finalAliceFiber = state54.fiberRecord(alicefiberId)
          finalBobFiber = state54.fiberRecord(bobfiberId)
          finalCharlieFiber = state54.fiberRecord(charliefiberId)
          finalDaveFiber = state54.fiberRecord(davefiberId)
          finalGraceFiber = state54.fiberRecord(gracefiberId)
          finalHeidiFiber = state54.fiberRecord(heidifiberId)
          finalIvanFiber = state54.fiberRecord(ivanfiberId)
          finalOscarFiber = state54.fiberRecord(oscarfiberId)
          finalPeggyFiber = state54.fiberRecord(peggyfiberId)
          finalQuentinFiber = state54.fiberRecord(quentinfiberId)
          finalRuthFiber = state54.fiberRecord(ruthfiberId)
          finalSybilFiber = state54.fiberRecord(sybilfiberId)
          finalVictorFiber = state54.fiberRecord(victorfiberId)
          finalYolandaFiber = state54.fiberRecord(yolandafiberId)

          // Extract auction states
          _finalAuction1Fiber = state54.fiberRecord(auctionfiberId)
          finalAuction2Fiber = state54.fiberRecord(auction2fiberId)

          // Extract intermediate states for Ruth's payment
          _ruthAfterPayment = state28.fiberRecord(ruthfiberId)
          _oscarAfterPayment = state28.fiberRecord(oscarfiberId)

          // Extract states after Ruth's service provision
          _ruthAfterService = state38.fiberRecord(ruthfiberId)

          // Extract states for Ivan and Dave supply chain
          _ivanAfterInventoryCheck = state37.fiberRecord(ivanfiberId)
          _daveAfterFulfillment = state37.fiberRecord(davefiberId)

          // Extract Xavier's final state after tax collection
          finalXavierFiber = state54.fiberRecord(xavierfiberId)

          // Extract Niaj's final state
          finalNiajFiber = state54.fiberRecord(niajfiberId)

        } yield expect.all(
          // Verify all participants exist
          finalAliceFiber.isDefined,
          finalHeidiFiber.isDefined,
          finalOscarFiber.isDefined,
          finalRuthFiber.isDefined,
          finalCharlieFiber.isDefined,
          finalYolandaFiber.isDefined,

          // ========================================
          // NIAJ (PLATFORM) COMPREHENSIVE VALIDATIONS
          // ========================================

          // Verify Niaj (Platform) exists
          finalNiajFiber.isDefined,

          // Verify Niaj remained in idle state (no events processed)
          finalNiajFiber.map(_.currentState).contains(StateId("idle")),

          // Verify Niaj's sequence number is 0 (passive participant, no events)
          finalNiajFiber.map(_.sequenceNumber).exists(_ == FiberOrdinal.MinValue),

          // Verify Niaj's transaction count is still 0
          finalNiajFiber.extractInt("transactions").exists(_ == 0),

          // Verify Niaj's status is still active
          finalNiajFiber.extractString("status").contains("ACTIVE"),

          // Verify state transitions
          finalAliceFiber.map(_.sequenceNumber).exists(_ > FiberOrdinal.MinValue),
          finalOscarFiber
            .map(_.currentState)
            .contains(
              StateId("loan_servicing")
            ), // Oscar underwrote the loan successfully (Fed was stable), then completed stress test
          finalCharlieFiber.map(_.currentState).contains(StateId("supply_shortage")),
          finalYolandaFiber
            .map(_.currentState)
            .contains(StateId("stress_testing")), // Fed is in stress_testing state

          // Verify Ruth received loan funding and is in debt_current state
          finalRuthFiber.map(_.currentState).contains(StateId("debt_current")),

          // Verify cross-machine triggers fired - Heidi goes from open -> inventory_low -> stocking in same snapshot
          heidiFiberAfterCheck.map(_.currentState).contains(StateId("stocking")),

          // Verify Charlie's supply shortage was detected
          charlieFiberAfterShortage.map(_.currentState).contains(StateId("supply_shortage")),

          // Verify Bob's production cycle
          finalBobFiber.map(_.currentState).contains(StateId("shipping")), // Bob fulfilled Grace's order
          finalBobFiber
            .map(_.sequenceNumber)
            .exists(_ == FiberOrdinal.unsafeApply(5L)), // 5 events: schedule, start, complete, fulfill_order, pay_taxes
          finalBobFiber.extractInt("inventory").exists(_ == 400), // 500 - 100 = 400 (after fulfilling order)

          // Verify Grace cross-chain order
          finalGraceFiber
            .extractInt("stock")
            .exists(_ == 105), // 15 (initial) + 100 (restock from Bob) - 10 (sold to Victor while stocking) = 105

          // Verify Ruth's shopping
          finalRuthFiber.extractInt("purchaseCount").exists(_ == 1), // Ruth made 1 purchase

          // Ruth's balance: 5000 + 10000 (loan) - 50 (purchase) - 300 (payment) + 3000 (service) = 17650
          finalRuthFiber.extractInt("balance").exists(_ == 17650),

          // Verify Heidi processed the sale
          finalHeidiFiber.extractInt("salesCount").exists(_ >= 1), // Heidi made at least 1 sale

          // Heidi's revenue increased (5 items * 10 per unit = 50)
          finalHeidiFiber.extractInt("revenue").exists(_ == 50),

          // Heidi's stock decreased (stock after restocking from Alice, then sold 5 to Ruth)
          finalHeidiFiber.extractInt("stock").exists(_ == 110), // 115 (after restocking) - 5 (sold to Ruth) = 110

          // Verify Peggy and Quentin banks received rate_adjustment from Fed (Event 2)
          finalPeggyFiber
            .map(_.sequenceNumber)
            .exists(_ >= FiberOrdinal.MinValue.next), // Peggy received rate_adjustment
          finalPeggyFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("baseRate").collect { case FloatValue(r) => r }
                case _           => None
              }
            }
            .exists(_ == 0.0425), // 0.04 + 0.0025 = 0.0425 (after rate increase)

          finalQuentinFiber
            .map(_.sequenceNumber)
            .exists(_ >= FiberOrdinal.MinValue.next), // Quentin received rate_adjustment
          finalQuentinFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("baseRate").collect { case FloatValue(r) => r }
                case _           => None
              }
            }
            .exists(_ == 0.0425), // 0.04 + 0.0025 = 0.0425 (after rate increase)

          // Verify Sybil's delinquency
          finalSybilFiber.map(_.currentState).contains(StateId("debt_delinquent")),
          finalSybilFiber.extractInt("missedPayments").exists(_ >= 1), // Sybil missed at least 1 payment

          // Verify stress test execution - banks should have completed stress test and returned to original states
          yolandaFiberAfterStressTest
            .map(_.currentState)
            .contains(StateId("stress_testing")), // Fed is still in stress_testing state
          oscarFiberAfterStressTest
            .map(_.currentState)
            .contains(StateId("loan_servicing")), // Oscar returned to loan_servicing (has activeLoans > 0)
          peggyFiberAfterStressTest
            .map(_.currentState)
            .contains(StateId("loan_servicing")), // Peggy returned to loan_servicing (has activeLoans > 0)
          quentinFiberAfterStressTest
            .map(_.currentState)
            .contains(StateId("operating")), // Quentin returned to operating (has activeLoans == 0)

          // Verify banks passed the stress test
          oscarFiberAfterStressTest.extractBool("stressTestPassed").getOrElse(false),
          peggyFiberAfterStressTest.extractBool("stressTestPassed").getOrElse(false),
          quentinFiberAfterStressTest.extractBool("stressTestPassed").getOrElse(false),

          // Verify Heidi's holiday sale
          heidiFiberAfterSale
            .map(_.currentState)
            .contains(StateId("sale_active")), // Heidi entered sale_active state
          heidiFiberAfterSale
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("discountRate").collect { case FloatValue(d) => d }
                case _           => None
              }
            }
            .exists(_ == 0.20), // 20% discount rate

          // ========================================
          // C2C MARKETPLACE VALIDATIONS
          // ========================================

          // Verify auction child machine was spawned and exists
          auctionFiberAfterBidAccepted.isDefined,

          // Verify auction reached "sold" final state
          auctionFiberAfterBidAccepted.map(_.currentState).contains(StateId("sold")),

          // Verify auction has correct parent relationship
          auctionFiberAfterBidAccepted.flatMap(_.parentFiberId).contains(sybilfiberId),

          // Verify auction recorded Victor as highest bidder
          auctionFiberAfterBidAccepted.extractString("highestBidder").contains(victorfiberId.toString),

          // Verify auction recorded winning bid amount (750)
          auctionFiberAfterBidAccepted.extractInt("highestBid").exists(_ == 750),

          // Verify auction recorded item name
          auctionFiberAfterBidAccepted.extractString("itemName").contains("Handmade Ceramic Vase"),

          // Verify Sybil remained in "debt_delinquent" state after sale completed (delinquency doesn't clear just from earning money)
          sybilFiberAfterSale.map(_.currentState).contains(StateId("debt_delinquent")),

          // Verify Sybil's balance increased by bid amount (8000 after loan + 750 sale = 8750)
          sybilFiberAfterSale.extractInt("balance").exists(_ == 8750),

          // Verify Sybil's marketplaceSales counter incremented
          sybilFiberAfterSale.extractInt("marketplaceSales").exists(_ == 1),

          // Verify Sybil's activeListings decreased back to 0
          sybilFiberAfterSale.extractInt("activeListings").exists(_ == 0),

          // Verify Victor still exists in active state
          finalVictorFiber.isDefined,
          finalVictorFiber.map(_.currentState).contains(StateId("ACTIVE")),

          // ========================================
          // AUCTION 2 (VICTOR'S AUCTION) VALIDATIONS
          // ========================================

          // Verify Victor's auction child machine was spawned and exists
          finalAuction2Fiber.isDefined,

          // Verify auction reached "sold" final state
          finalAuction2Fiber.map(_.currentState).contains(StateId("sold")),

          // Verify auction has correct parent relationship (Victor)
          finalAuction2Fiber.flatMap(_.parentFiberId).contains(victorfiberId),

          // Verify auction recorded Ruth as highest bidder
          finalAuction2Fiber.extractString("highestBidder").contains(ruthfiberId.toString),

          // Verify auction recorded winning bid amount (2500)
          finalAuction2Fiber.extractInt("highestBid").exists(_ == 2500),

          // Verify auction recorded item name
          finalAuction2Fiber.extractString("itemName").contains("Vintage Watch Collection"),

          // Verify auction reserve price was met
          finalAuction2Fiber.extractInt("reservePrice").exists(_ == 2000),

          // ========================================
          // STATE44 VALIDATIONS (Alice's Second Production Run)
          // ========================================

          // Verify Alice completed second production cycle and reached inventory_full state
          finalAliceFiber.map(_.currentState).contains(StateId("inventory_full")),

          // Verify Alice's sequence number increased by 3 (schedule, start, complete)
          finalAliceFiber.map(_.sequenceNumber).exists(_ >= FiberOrdinal.unsafeApply(3L)),

          // Verify Alice's raw materials: 1000 - 50 (first cycle) - 50 (second cycle) = 900
          finalAliceFiber.extractInt("rawMaterials").exists(_ == 900),

          // Verify Alice's inventory: 420 + 100 (first batch) - 100 (shipped to Heidi) + 80 (second batch) = 500
          finalAliceFiber.extractInt("inventory").exists(_ == 500),

          // Verify Alice's total produced: 100 (first batch) + 80 (second batch) = 180
          finalAliceFiber.extractInt("totalProduced").exists(_ == 180),

          // Verify Alice's batchSize was set correctly in schedule_production event
          finalAliceFiber.extractInt("batchSize").exists(_ == 80),

          // Verify Alice reached max inventory capacity
          finalAliceFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) =>
                  for {
                    inventory    <- m.get("inventory").collect { case IntValue(i) => i }
                    maxInventory <- m.get("maxInventory").collect { case IntValue(m) => m }
                  } yield inventory >= maxInventory
                case _ => None
              }
            }
            .getOrElse(false),

          // ========================================
          // XAVIER GOVERNANCE TAX COLLECTION VALIDATIONS
          // ========================================

          // Verify Xavier exists
          finalXavierFiber.isDefined,

          // Verify Xavier returned to monitoring state after all taxpayers paid
          finalXavierFiber.map(_.currentState).contains(StateId("monitoring")),

          // Verify Xavier received tax payments (sequence number incremented)
          finalXavierFiber.map(_.sequenceNumber).exists(_ > FiberOrdinal.MinValue),

          // Verify Xavier collected taxes from all 10 taxpayers
          finalXavierFiber.extractInt("taxpayersCompliant").exists(_ == 10),

          // Verify Xavier's total tax collection is correct (9 + 4 + 0 + 7.5 + 2.5 + 5 + 0 + 150 + 0 + 0 = 178)
          finalXavierFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("totalTaxesCollected").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 178.0),

          // Verify Alice processed the pay_taxes trigger and paid taxes
          finalAliceFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ > 0.0),

          // Verify Alice's last tax payment was calculated correctly (180 * 0.05 = 9.0)
          finalAliceFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("lastTaxPayment").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 9.0),

          // ========================================
          // VERIFY ALL 10 TAXPAYERS PAID TAXES
          // ========================================

          // Manufacturers (Alice, Bob, Charlie, Dave) - tax based on totalProduced
          finalAliceFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 9.0), // 180 * 0.05 = 9.0

          finalBobFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 4.0), // 80 * 0.05 = 4.0

          finalCharlieFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 0.0), // 0 * 0.05 = 0.0 (Charlie didn't produce due to material shortage)

          // ========================================
          // CHARLIE (MANUFACTURER) COMPREHENSIVE VALIDATIONS
          // ========================================

          // Verify Charlie exists
          finalCharlieFiber.isDefined,

          // Verify Charlie is in supply_shortage state (insufficient raw materials)
          finalCharlieFiber.map(_.currentState).contains(StateId("supply_shortage")),

          // Verify Charlie's sequence number (check_materials, pay_taxes)
          finalCharlieFiber.map(_.sequenceNumber).exists(_ == FiberOrdinal.unsafeApply(2L)),

          // Verify Charlie's raw materials unchanged (40 - insufficient for production)
          finalCharlieFiber.extractInt("rawMaterials").exists(_ == 40),

          // Verify Charlie's totalProduced is still 0 (no production due to shortage)
          finalCharlieFiber.extractInt("totalProduced").exists(_ == 0),

          // Verify Charlie's inventory unchanged (420 - no production occurred)
          finalCharlieFiber.extractInt("inventory").exists(_ == 420),

          // Verify Charlie's lastTaxPeriod was set
          finalCharlieFiber.extractString("lastTaxPeriod").contains("Q4-2024"),
          finalDaveFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 7.5), // 150 * 0.05 = 7.5 (now with full decimal precision!)

          // ========================================
          // DAVE (MANUFACTURER) COMPREHENSIVE VALIDATIONS
          // ========================================

          // Verify Dave exists
          finalDaveFiber.isDefined,

          // Verify Dave completed production cycle (schedule -> start -> complete)
          finalDaveFiber.map(_.currentState).contains(StateId("shipping")),

          // Verify Dave's sequence number reflects all events (schedule, start, complete, fulfill_order, pay_taxes)
          finalDaveFiber.map(_.sequenceNumber).exists(_ == FiberOrdinal.unsafeApply(5L)),

          // Verify Dave's inventory: 420 (initial) + 150 (produced) - 100 (shipped to Ivan) = 470
          finalDaveFiber.extractInt("inventory").exists(_ == 470),

          // Verify Dave's totalProduced matches batchSize
          finalDaveFiber.extractInt("totalProduced").exists(_ == 150),

          // Verify Dave consumed raw materials: 1000 (initial) - 50 (required for production) = 950
          finalDaveFiber.extractInt("rawMaterials").exists(_ == 950),

          // Verify Dave fulfilled Ivan's order (shipmentCount incremented)
          finalDaveFiber.extractInt("shipmentCount").exists(_ == 1),

          // Verify Dave's lastTaxPeriod was set
          finalDaveFiber.extractString("lastTaxPeriod").contains("Q4-2024"),

          // Retailers (Heidi, Grace, Ivan) - tax based on revenue
          finalHeidiFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 2.5), // 50 * 0.05 = 2.5 (now with full decimal precision!)

          finalGraceFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 5.0), // 100 * 0.05 = 5.0

          // ========================================
          // GRACE (RETAILER) COMPREHENSIVE VALIDATIONS
          // ========================================

          // Verify Grace exists
          finalGraceFiber.isDefined,

          // Verify Grace returned to open state after restocking
          finalGraceFiber.map(_.currentState).contains(StateId("open")),

          // Verify Grace's sequence number (check_inventory, receive_shipment, reopen, process_sale, pay_taxes)
          finalGraceFiber.map(_.sequenceNumber).exists(_ == FiberOrdinal.unsafeApply(5L)),

          // Verify Grace's revenue from Victor's purchase (10 items * 10 per unit = 100)
          finalGraceFiber.extractInt("revenue").exists(_ == 100),

          // Verify Grace's salesCount incremented
          finalGraceFiber.extractInt("salesCount").exists(_ == 1),

          // Verify Grace's lastTaxPeriod was set
          finalGraceFiber.extractString("lastTaxPeriod").contains("Q4-2024"),
          finalIvanFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 0.0), // 0 * 0.05 = 0.0 (Ivan has no revenue yet)

          // ========================================
          // IVAN (RETAILER) COMPREHENSIVE VALIDATIONS
          // ========================================

          // Verify Ivan exists
          finalIvanFiber.isDefined,

          // Verify Ivan returned to open state after restocking
          finalIvanFiber.map(_.currentState).contains(StateId("open")),

          // Verify Ivan's sequence number (check_inventory, receive_shipment, reopen, pay_taxes)
          finalIvanFiber.map(_.sequenceNumber).exists(_ == FiberOrdinal.unsafeApply(4L)),

          // Verify Ivan's stock after restocking from Dave (15 initial + 100 restock = 115)
          finalIvanFiber.extractInt("stock").exists(_ == 115),

          // Verify Ivan has zero revenue (no sales yet)
          finalIvanFiber.extractInt("revenue").exists(_ == 0),

          // Verify Ivan has zero sales count
          finalIvanFiber.extractInt("salesCount").exists(_ == 0),

          // Verify Ivan's lastTaxPeriod was set
          finalIvanFiber.extractString("lastTaxPeriod").contains("Q4-2024"),

          // Consumers (Ruth, Sybil, Victor) - tax based on serviceRevenue
          finalRuthFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 150.0), // 3000 * 0.05 = 150.0

          finalSybilFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 0.0), // 0 * 0.05 = 0.0 (Sybil has no service revenue)

          finalVictorFiber
            .flatMap { f =>
              f.stateData match {
                case MapValue(m) => m.get("taxesPaid").collect { case FloatValue(t) => t }
                case _           => None
              }
            }
            .exists(_ == 0.0), // 0 * 0.05 = 0.0 (Victor has no service revenue)

          // Verify all taxpayers have the lastTaxPeriod field set
          finalAliceFiber.extractString("lastTaxPeriod").contains("Q4-2024"),
          finalBobFiber.extractString("lastTaxPeriod").contains("Q4-2024"),
          finalRuthFiber.extractString("lastTaxPeriod").contains("Q4-2024"),

          // Verify remaining taxpayers have lastTaxPeriod set
          finalHeidiFiber.extractString("lastTaxPeriod").contains("Q4-2024"),
          finalSybilFiber.extractString("lastTaxPeriod").contains("Q4-2024"),
          finalVictorFiber.extractString("lastTaxPeriod").contains("Q4-2024")
        )
      }
  }
}
