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

/**
 * F7 fix (03-cross-fiber-and-authorization.md §3): transition signer-authorization is now enforced on the
 * AUTHORITATIVE apply path (the combiner) via the opt-in `transitionPolicy` dial, as a graceful
 * `CombineRejected` (rule #2). The ABSENT-dial default is `Open` (today's live guard-only behaviour), so every
 * existing fiber is UNCHANGED — `TransitionValidatorGateSuite` and `MultiPartyTransitionSigningSuite`
 * stay green. Apps opt UP explicitly: `OwnersOrParticipants` or `Owners`. The validator no longer owner-gates
 * a transition at all (#205, `TransitionValidatorGateSuite`); the combiner is the sole binding signer gate.
 */
object TransitionPolicyEnforcementSuite extends SimpleIOSuite {

  private val defJson: String =
    """
    {
      "states": {
        "s0": { "id": "s0", "isFinal": false },
        "s1": { "id": "s1", "isFinal": false }
      },
      "initialState": "s0",
      "transitions": [
        { "from": "s0", "to": "s1", "eventName": "ping", "guard": true, "effect": { "status": "s1" }, "dependencies": [] }
      ]
    }
    """

  private def mkPing(fiberId: java.util.UUID): Updates.TransitionStateMachine =
    Updates.TransitionStateMachine(
      fiberId,
      "ping",
      MapValue(Map.empty[String, JsonLogicValue]),
      FiberOrdinal.MinValue
    )

  private def fiberOf(
    state:   DataState[OnChain, CalculatedState],
    fiberId: java.util.UUID
  ): Option[Records.StateMachineFiberRecord] =
    state.calculated.stateMachines.get(fiberId).collect { case r: Records.StateMachineFiberRecord => r }

  test("default (absent) transitionPolicy is Open: a non-owner's transition APPLIES via the combiner") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner   <- Combiner.make[IO]().pure[IO]
        fiberId    <- UUIDGen.randomUUID[IO]
        machineDef <- IO.fromEither(decode[StateMachineDefinition](defJson)) // policy = Unconstrained (absent dial)

        create = Updates.CreateStateMachine(fiberId, machineDef, MapValue(Map.empty[String, JsonLogicValue]))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        afterCreate <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(create, createProof))

        // Bob — NOT an owner, NOT a participant — signs; under Open the guard is the sole gate, so it APPLIES.
        bobProof  <- fixture.registry.generateProofs(mkPing(fiberId), Set(Bob))
        afterPing <- combiner.insert(afterCreate, Signed(mkPing(fiberId), bobProof))
        applied = fiberOf(afterPing, fiberId)
      } yield expect(applied.map(_.currentState).contains(StateId("s1"))) and
      expect(applied.exists(_.sequenceNumber > FiberOrdinal.MinValue))
    }
  }

  test(
    "transitionPolicy = Owners: a non-owner's transition is CombineRejected (state UNCHANGED), owner still applies"
  ) {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]
        baseDef  <- IO.fromEither(decode[StateMachineDefinition](defJson))
        machineDef = baseDef.copy(policy = FiberPolicy.constrained(transitionPolicy = Some(TransitionPolicy.Owners)))

        create = Updates.CreateStateMachine(fiberId, machineDef, MapValue(Map.empty[String, JsonLogicValue]))
        createProof <- fixture.registry.generateProofs(create, Set(Alice)) // owners = {Alice}
        afterCreate <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(create, createProof))

        // Bob (non-owner) is REJECTED by the combiner now — the fiber must NOT advance.
        bobProof <- fixture.registry.generateProofs(mkPing(fiberId), Set(Bob))
        afterBob <- combiner.insert(afterCreate, Signed(mkPing(fiberId), bobProof))
        afterBobRec = fiberOf(afterBob, fiberId)

        // Alice (owner) IS permitted — applies on the unchanged post-create state.
        aliceProof <- fixture.registry.generateProofs(mkPing(fiberId), Set(Alice))
        afterAlice <- combiner.insert(afterCreate, Signed(mkPing(fiberId), aliceProof))
        afterAliceRec = fiberOf(afterAlice, fiberId)
      } yield
      // rejected: unchanged
      expect(afterBobRec.map(_.currentState).contains(StateId("s0"))) and
      expect(afterBobRec.exists(_.sequenceNumber === FiberOrdinal.MinValue)) and
      // owner: applied
      expect(afterAliceRec.map(_.currentState).contains(StateId("s1"))) and
      expect(afterAliceRec.exists(_.sequenceNumber > FiberOrdinal.MinValue))
    }
  }

  test("transitionPolicy = OwnersOrParticipants: a declared participant is accepted, a stranger is rejected") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]
        fiberId  <- UUIDGen.randomUUID[IO]
        baseDef  <- IO.fromEither(decode[StateMachineDefinition](defJson))
        machineDef = baseDef
          .copy(policy = FiberPolicy.constrained(transitionPolicy = Some(TransitionPolicy.OwnersOrParticipants)))

        bobAddr = fixture.registry.addresses(Bob)
        // Alice creates; Bob is a DECLARED participant (authorizedSigners), Charlie is a stranger.
        create = Updates.CreateStateMachine(
          fiberId,
          machineDef,
          MapValue(Map.empty[String, JsonLogicValue]),
          participants = Some(Set(bobAddr))
        )
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        afterCreate <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(create, createProof))

        // Bob (participant) — accepted, applies.
        bobProof <- fixture.registry.generateProofs(mkPing(fiberId), Set(Bob))
        afterBob <- combiner.insert(afterCreate, Signed(mkPing(fiberId), bobProof))
        bobRec = fiberOf(afterBob, fiberId)

        // Charlie (stranger) — rejected, unchanged.
        charlieProof <- fixture.registry.generateProofs(mkPing(fiberId), Set(Charlie))
        afterCharlie <- combiner.insert(afterCreate, Signed(mkPing(fiberId), charlieProof))
        charlieRec = fiberOf(afterCharlie, fiberId)
      } yield expect(bobRec.map(_.currentState).contains(StateId("s1"))) and
      expect(bobRec.exists(_.sequenceNumber > FiberOrdinal.MinValue)) and
      expect(charlieRec.map(_.currentState).contains(StateId("s0"))) and
      expect(charlieRec.exists(_.sequenceNumber === FiberOrdinal.MinValue))
    }
  }
}
