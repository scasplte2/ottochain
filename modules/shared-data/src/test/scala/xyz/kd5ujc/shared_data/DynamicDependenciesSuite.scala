package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * Integration test for runtime-updatable (dynamic) dependencies (#24): a fiber adds a dependency at
 * runtime via `_addDependency`, and a SUBSEQUENT transition reads that dependency's state through the
 * `machines.<dep>.state` context — proving the per-fiber ACTIVE dynamic deps are merged into the
 * machines context alongside the static `Transition.dependencies`.
 */
object DynamicDependenciesSuite extends SimpleIOSuite {

  test("_addDependency binds a fiber at runtime; a later transition reads machines.<dep>.state (#24)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        depId      <- UUIDGen.randomUUID[IO]
        consumerId <- UUIDGen.randomUUID[IO]

        // --- dependency target fiber B: a static fiber holding { value: 42 } ---
        depJson = """
        {
          "states": { "ACTIVE": { "id": "ACTIVE", "isFinal": false } },
          "initialState": "ACTIVE",
          "transitions": []
        }
        """
        depDef <- IO.fromEither(decode[StateMachineDefinition](depJson))
        depData = MapValue(Map("value" -> IntValue(42)))
        createDep = Updates.CreateStateMachine(depId, depDef, depData)
        depProof <- fixture.registry.generateProofs(createDep, Set(Alice))
        s1       <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createDep, depProof))

        // --- consumer fiber A: `bind` adds B as a dynamic dep; `read` copies B.value into A.seen ---
        consumerJson = s"""
        {
          "states": {
            "init":  { "id": "init",  "isFinal": false },
            "bound": { "id": "bound", "isFinal": false },
            "read":  { "id": "read",  "isFinal": false }
          },
          "initialState": "init",
          "transitions": [
            {
              "from": "init", "to": "bound", "eventName": "bind", "guard": true,
              "effect": { "_addDependency": [ { "fiberId": "$depId" } ] },
              "dependencies": []
            },
            {
              "from": "bound", "to": "read", "eventName": "read", "guard": true,
              "effect": {
                "merge": [ { "var": "state" }, { "seen": { "var": "machines.$depId.state.value" } } ]
              },
              "dependencies": []
            }
          ]
        }
        """
        consumerDef <- IO.fromEither(decode[StateMachineDefinition](consumerJson))
        consumerData = MapValue(Map("seen" -> IntValue(0)))
        createConsumer = Updates.CreateStateMachine(consumerId, consumerDef, consumerData)
        consumerProof <- fixture.registry.generateProofs(createConsumer, Set(Alice))
        s2            <- combiner.insert(s1, Signed(createConsumer, consumerProof))

        // fire `bind` → A's dynamic-dependency ledger should now hold B (active)
        bindEvent = Updates.TransitionStateMachine(consumerId, "bind", MapValue(Map.empty), FiberOrdinal.MinValue)
        bindProof <- fixture.registry.generateProofs(bindEvent, Set(Alice))
        s3        <- combiner.insert(s2, Signed(bindEvent, bindProof))

        boundConsumer = s3.calculated.stateMachines
          .get(consumerId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        // fire `read` → A.seen should become B.value (42), proving machines.<dep> was injected from the
        // ACTIVE dynamic dep added in the previous transition
        readEvent = Updates.TransitionStateMachine(consumerId, "read", MapValue(Map.empty), FiberOrdinal.MinValue.next)
        readProof <- fixture.registry.generateProofs(readEvent, Set(Alice))
        s4        <- combiner.insert(s3, Signed(readEvent, readProof))

        readConsumer = s4.calculated.stateMachines
          .get(consumerId)
          .collect { case r: Records.StateMachineFiberRecord => r }

        seenValue = readConsumer.flatMap { c =>
          c.stateData match {
            case MapValue(m) => m.get("seen").collect { case IntValue(v) => v }
            case _           => None
          }
        }
      } yield expect(boundConsumer.exists(_.dynamicDependencies.exists(d => d.fiberId == depId && d.active))) and
      expect(boundConsumer.map(_.currentState).contains(StateId("bound"))) and
      expect(readConsumer.map(_.currentState).contains(StateId("read"))) and
      expect(seenValue.contains(BigInt(42)))
    }
  }
}
