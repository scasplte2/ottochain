package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext, L1NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, Validator}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * Settles riverdale-economy ergonomics finding F7: is a primary state-machine TRANSITION owner-gated?
 *
 * The answer is DIVERGENT BY CODE PATH — which is the bug/smell this test pins:
 *   - `Validator.validateSignedUpdate` ENFORCES `owners ∪ authorizedSigners`
 *     (FiberValidator.processEvent -> FiberRules.L0.updateSignedByOwnerOrParticipant) -> a non-owner is Invalid.
 *   - `Combiner.insert` (the live apply path) does NOT check owners -> a non-owner's transition APPLIES;
 *     the transition GUARD is the only gate.
 *
 * The riverdale-economy e2e observed the COMBINER behavior (a bob-signed transition on an alice-owned fiber
 * ADVANCED the fiber), so in production transitions are effectively guard-only-gated: the validator's owner
 * gate is not reached/enforced before the combiner applies the transition. The pre-existing
 * `MultiPartyTransitionSigningSuite` already encodes BOTH halves separately ("counterparty can sign … they
 * didn't create" via the combiner; "unauthorized third party CANNOT sign" via the validator); this test
 * asserts both in ONE case so the divergence is explicit and regression-guarded. See
 * docs/proposals/fiber-ergonomics/03-cross-fiber-and-authorization.md.
 */
object TransitionOwnerGateDivergenceSuite extends SimpleIOSuite {

  test(
    "F7: the validator REJECTS a non-owner transition, but the combiner APPLIES it (gate not enforced on the apply path)"
  ) {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        defJson =
          """
          {
            "states": {
              "s0": { "id": "s0", "isFinal": false },
              "s1": { "id": "s1", "isFinal": false }
            },
            "initialState": "s0",
            "transitions": [
              {
                "from": "s0",
                "to": "s1",
                "eventName": "ping",
                "guard": true,
                "effect": { "status": "s1" },
                "dependencies": []
              }
            ]
          }
          """
        machineDef <- IO.fromEither(decode[StateMachineDefinition](defJson))

        // Alice creates the fiber -> owners = {Alice}, NO participants declared (authorizedSigners empty).
        create = Updates.CreateStateMachine(fiberId, machineDef, MapValue(Map.empty[String, JsonLogicValue]))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        afterCreate <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(create, createProof)
        )

        // Bob — NOT an owner, NOT a participant — signs a transition.
        ping = Updates.TransitionStateMachine(
          fiberId,
          "ping",
          MapValue(Map.empty[String, JsonLogicValue]),
          FiberOrdinal.MinValue
        )
        bobProof <- fixture.registry.generateProofs(ping, Set(Bob))

        // (a) the VALIDATOR rejects Bob (the owner gate IS enforced in validateSignedUpdate)
        validatorResult <- validator.validateSignedUpdate(afterCreate, Signed(ping, bobProof))

        // (b) but the COMBINER APPLIES Bob's transition (no owner check on the apply path — guard is the gate)
        afterPing <- combiner.insert(afterCreate, Signed(ping, bobProof))
        applied = afterPing.calculated.stateMachines
          .get(fiberId)
          .collect { case r: Records.StateMachineFiberRecord => r }

      } yield expect(validatorResult.isInvalid) and // validator WOULD reject a non-owner
      expect(applied.map(_.currentState).contains(StateId("s1"))) and // ...but the combiner applied it anyway
      expect(applied.exists(_.sequenceNumber > FiberOrdinal.MinValue))
    }
  }
}
