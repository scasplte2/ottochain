package xyz.kd5ujc.shared_data

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

object MultiPartyTransitionSigningSuite extends SimpleIOSuite {

  test("counterparty can sign accept transition on fiber they didn't create") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        contractFiberId <- UUIDGen.randomUUID[IO]

        // Simple contract with accept/reject transitions
        contractJson = """
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "accepted": { "id": { "value": "accepted" }, "isFinal": true },
            "rejected": { "id": { "value": "rejected" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "accepted" },
              "eventName": "accept",
              "guard": true,
              "effect": {
                "status": "accepted",
                "acceptedAt": { "var": "event.timestamp" }
              },
              "dependencies": []
            },
            {
              "from": { "value": "pending" },
              "to": { "value": "rejected" }, 
              "eventName": "reject",
              "guard": true,
              "effect": {
                "status": "rejected",
                "rejectedAt": { "var": "event.timestamp" }
              },
              "dependencies": []
            }
          ]
        }
        """

        contractDef <- IO.fromEither(decode[StateMachineDefinition](contractJson))
        initialData = MapValue(Map("counterparty" -> StrValue("Bob")))

        // Alice creates the contract
        createContract = Updates.CreateStateMachine(contractFiberId, contractDef, initialData)
        createProof <- fixture.registry.generateProofs(createContract, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createContract, createProof)
        )

        // Bob (counterparty) signs the accept transition
        acceptEvent = Updates.TransitionStateMachine(
          contractFiberId,
          "accept",
          MapValue(Map("timestamp" -> IntValue(System.currentTimeMillis()))),
          FiberOrdinal.MinValue
        )
        // This should work in the multi-party system - Bob can sign accept
        acceptProof <- fixture.registry.generateProofs(acceptEvent, Set(Bob))
        finalState <- combiner.insert(stateAfterCreate, Signed(acceptEvent, acceptProof))

        contract = finalState.calculated.stateMachines
          .get(contractFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        status = contract.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _ => None
          }
        }

      } yield expect(contract.isDefined) and
      expect(contract.map(_.currentState).contains(StateId("accepted"))) and
      expect(status.contains("accepted"))
    }
  }

  test("counterparty can sign reject transition") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        contractFiberId <- UUIDGen.randomUUID[IO]

        contractJson = """
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "accepted": { "id": { "value": "accepted" }, "isFinal": true },
            "rejected": { "id": { "value": "rejected" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "accepted" },
              "eventName": "accept",
              "guard": true,
              "effect": {
                "status": "accepted"
              },
              "dependencies": []
            },
            {
              "from": { "value": "pending" },
              "to": { "value": "rejected" }, 
              "eventName": "reject",
              "guard": true,
              "effect": {
                "status": "rejected",
                "reason": { "var": "event.reason" }
              },
              "dependencies": []
            }
          ]
        }
        """

        contractDef <- IO.fromEither(decode[StateMachineDefinition](contractJson))
        initialData = MapValue(Map("counterparty" -> StrValue("Bob")))

        // Alice creates the contract
        createContract = Updates.CreateStateMachine(contractFiberId, contractDef, initialData)
        createProof <- fixture.registry.generateProofs(createContract, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createContract, createProof)
        )

        // Bob (counterparty) signs the reject transition
        rejectEvent = Updates.TransitionStateMachine(
          contractFiberId,
          "reject",
          MapValue(Map("reason" -> StrValue("terms not acceptable"))),
          FiberOrdinal.MinValue
        )
        // This should work in the multi-party system - Bob can sign reject
        rejectProof <- fixture.registry.generateProofs(rejectEvent, Set(Bob))
        finalState <- combiner.insert(stateAfterCreate, Signed(rejectEvent, rejectProof))

        contract = finalState.calculated.stateMachines
          .get(contractFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        status = contract.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _ => None
          }
        }

        reason = contract.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("reason").collect { case StrValue(r) => r }
            case _ => None
          }
        }

      } yield expect(contract.isDefined) and
      expect(contract.map(_.currentState).contains(StateId("rejected"))) and
      expect(status.contains("rejected")) and
      expect(reason.contains("terms not acceptable"))
    }
  }

  test("unauthorized third party CANNOT sign transitions") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        contractFiberId <- UUIDGen.randomUUID[IO]

        contractJson = """
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "accepted": { "id": { "value": "accepted" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "accepted" },
              "eventName": "accept",
              "guard": true,
              "effect": {
                "status": "accepted"
              },
              "dependencies": []
            }
          ]
        }
        """

        contractDef <- IO.fromEither(decode[StateMachineDefinition](contractJson))
        initialData = MapValue(Map("counterparty" -> StrValue("Bob")))

        // Alice creates the contract
        createContract = Updates.CreateStateMachine(contractFiberId, contractDef, initialData)
        createProof <- fixture.registry.generateProofs(createContract, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createContract, createProof)
        )

        // Charlie (unauthorized third party) tries to sign accept transition
        acceptEvent = Updates.TransitionStateMachine(
          contractFiberId,
          "accept",
          MapValue(Map("timestamp" -> IntValue(System.currentTimeMillis()))),
          FiberOrdinal.MinValue
        )
        
        // This should fail - Charlie is not owner (Alice) or counterparty (Bob)
        charlieProof <- fixture.registry.generateProofs(acceptEvent, Set(Charlie))
        result <- combiner.insert(stateAfterCreate, Signed(acceptEvent, charlieProof)).attempt

        // The system should reject this transaction
        // In the current system this would pass, but in multi-party system it should fail

      } yield expect(result.isLeft) // Should fail because Charlie is not authorized
    }
  }

  test("owner can still sign all transitions") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        contractFiberId <- UUIDGen.randomUUID[IO]

        contractJson = """
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "cancelled": { "id": { "value": "cancelled" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "cancelled" },
              "eventName": "cancel",
              "guard": true,
              "effect": {
                "status": "cancelled",
                "cancelledBy": "owner"
              },
              "dependencies": []
            }
          ]
        }
        """

        contractDef <- IO.fromEither(decode[StateMachineDefinition](contractJson))
        initialData = MapValue(Map("counterparty" -> StrValue("Bob")))

        // Alice creates the contract
        createContract = Updates.CreateStateMachine(contractFiberId, contractDef, initialData)
        createProof <- fixture.registry.generateProofs(createContract, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createContract, createProof)
        )

        // Alice (owner) signs the cancel transition - should always work
        cancelEvent = Updates.TransitionStateMachine(
          contractFiberId,
          "cancel",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.MinValue
        )
        cancelProof <- fixture.registry.generateProofs(cancelEvent, Set(Alice))
        finalState <- combiner.insert(stateAfterCreate, Signed(cancelEvent, cancelProof))

        contract = finalState.calculated.stateMachines
          .get(contractFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        status = contract.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _ => None
          }
        }

      } yield expect(contract.isDefined) and
      expect(contract.map(_.currentState).contains(StateId("cancelled"))) and
      expect(status.contains("cancelled"))
    }
  }

  test("guard conditions still evaluate correctly with counterparty signer") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        contractFiberId <- UUIDGen.randomUUID[IO]

        // Contract with guard condition that must pass
        contractJson = """
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": true },
            "denied": { "id": { "value": "denied" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "approved" },
              "eventName": "approve",
              "guard": {
                ">=": [{ "var": "event.amount" }, { "var": "state.minimumAmount" }]
              },
              "effect": {
                "status": "approved",
                "finalAmount": { "var": "event.amount" }
              },
              "dependencies": []
            },
            {
              "from": { "value": "pending" },
              "to": { "value": "denied" },
              "eventName": "approve",
              "guard": {
                "<": [{ "var": "event.amount" }, { "var": "state.minimumAmount" }]
              },
              "effect": {
                "status": "denied",
                "reason": "amount too low"
              },
              "dependencies": []
            }
          ]
        }
        """

        contractDef <- IO.fromEither(decode[StateMachineDefinition](contractJson))
        initialData = MapValue(Map(
          "counterparty" -> StrValue("Bob"),
          "minimumAmount" -> IntValue(1000)
        ))

        // Alice creates the contract
        createContract = Updates.CreateStateMachine(contractFiberId, contractDef, initialData)
        createProof <- fixture.registry.generateProofs(createContract, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createContract, createProof)
        )

        // Bob approves with amount that meets guard condition
        approveEvent = Updates.TransitionStateMachine(
          contractFiberId,
          "approve",
          MapValue(Map("amount" -> IntValue(1500))),
          FiberOrdinal.MinValue
        )
        // Guard should evaluate correctly even when Bob signs instead of Alice
        approveProof <- fixture.registry.generateProofs(approveEvent, Set(Bob))
        finalState <- combiner.insert(stateAfterCreate, Signed(approveEvent, approveProof))

        contract = finalState.calculated.stateMachines
          .get(contractFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        status = contract.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _ => None
          }
        }

        finalAmount = contract.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("finalAmount").collect { case IntValue(a) => a }
            case _ => None
          }
        }

      } yield expect(contract.isDefined) and
      expect(contract.map(_.currentState).contains(StateId("approved"))) and
      expect(status.contains("approved")) and
      expect(finalAmount.contains(BigInt(1500)))
    }
  }

  test("guard condition fails correctly with counterparty signer") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        contractFiberId <- UUIDGen.randomUUID[IO]

        // Same contract as above but test the failing guard condition
        contractJson = """
        {
          "states": {
            "pending": { "id": { "value": "pending" }, "isFinal": false },
            "approved": { "id": { "value": "approved" }, "isFinal": true },
            "denied": { "id": { "value": "denied" }, "isFinal": true }
          },
          "initialState": { "value": "pending" },
          "transitions": [
            {
              "from": { "value": "pending" },
              "to": { "value": "approved" },
              "eventName": "approve",
              "guard": {
                ">=": [{ "var": "event.amount" }, { "var": "state.minimumAmount" }]
              },
              "effect": {
                "status": "approved",
                "finalAmount": { "var": "event.amount" }
              },
              "dependencies": []
            },
            {
              "from": { "value": "pending" },
              "to": { "value": "denied" },
              "eventName": "approve",
              "guard": {
                "<": [{ "var": "event.amount" }, { "var": "state.minimumAmount" }]
              },
              "effect": {
                "status": "denied",
                "reason": "amount too low"
              },
              "dependencies": []
            }
          ]
        }
        """

        contractDef <- IO.fromEither(decode[StateMachineDefinition](contractJson))
        initialData = MapValue(Map(
          "counterparty" -> StrValue("Bob"),
          "minimumAmount" -> IntValue(1000)
        ))

        // Alice creates the contract
        createContract = Updates.CreateStateMachine(contractFiberId, contractDef, initialData)
        createProof <- fixture.registry.generateProofs(createContract, Set(Alice))
        stateAfterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createContract, createProof)
        )

        // Bob approves with amount below minimum - should trigger second guard
        approveEvent = Updates.TransitionStateMachine(
          contractFiberId,
          "approve",
          MapValue(Map("amount" -> IntValue(500))), // Below minimum of 1000
          FiberOrdinal.MinValue
        )
        approveProof <- fixture.registry.generateProofs(approveEvent, Set(Bob))
        finalState <- combiner.insert(stateAfterCreate, Signed(approveEvent, approveProof))

        contract = finalState.calculated.stateMachines
          .get(contractFiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        status = contract.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("status").collect { case StrValue(s) => s }
            case _ => None
          }
        }

        reason = contract.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("reason").collect { case StrValue(r) => r }
            case _ => None
          }
        }

      } yield expect(contract.isDefined) and
      expect(contract.map(_.currentState).contains(StateId("denied"))) and
      expect(status.contains("denied")) and
      expect(reason.contains("amount too low"))
    }
  }
}