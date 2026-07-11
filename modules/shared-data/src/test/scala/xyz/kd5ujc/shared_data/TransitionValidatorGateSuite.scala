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
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, Validator}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser._
import weaver.SimpleIOSuite

/**
 * Pins the #205 invariant: a `TransitionStateMachine` has NO signer gate at the ML0 block-acceptance
 * validator (`Validator.validateSignedUpdate`). Transition signer-authorization is enforced WHOLLY on the
 * authoritative apply path — the combiner's F7 gate (`FiberCombiner.processFiberEvent` →
 * `TransitionPolicy.authorizes`) — as a graceful `CombineRejected`.
 *
 * WHY the validator gate was removed (superseding the old `TransitionOwnerGateDivergenceSuite`, which pinned
 * the divergence as a smell): it was the ONE block-acceptance check with no DL1 non-fatal pre-filter behind it
 * (DL1 has no proofs), so a non-owner transition reached ML0, went `Invalid`, and dropped the ENTIRE
 * all-or-nothing block; and it could never honour `transitionPolicy = Open` without reading the
 * upgrade-MUTABLE `definition.policy` at block-acceptance (TOCTOU → block poison, CLAUDE.md rule #3). The two
 * cases below assert the validator is structural-only for a transition REGARDLESS of policy, so it never
 * poisons a block — the combiner alone decides admission.
 */
object TransitionValidatorGateSuite extends SimpleIOSuite {

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

  test(
    "default (Open): validateSignedUpdate ADMITS a non-owner transition and the combiner APPLIES it — they AGREE"
  ) {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        machineDef <- IO.fromEither(decode[StateMachineDefinition](defJson)) // policy absent ⇒ Open

        // Alice creates ⇒ owners = {Alice}, no participants (authorizedSigners empty).
        create = Updates.CreateStateMachine(fiberId, machineDef, MapValue(Map.empty[String, JsonLogicValue]))
        createProof <- fixture.registry.generateProofs(create, Set(Alice))
        afterCreate <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(create, createProof))

        // Bob — NOT an owner, NOT a participant — signs a transition.
        bobProof <- fixture.registry.generateProofs(mkPing(fiberId), Set(Bob))

        // (a) the validator ADMITS Bob — no owner gate at block-acceptance (#205)
        validatorResult <- validator.validateSignedUpdate(afterCreate, Signed(mkPing(fiberId), bobProof))
        // (b) and under default-Open the combiner APPLIES it (guard is the only gate)
        afterPing <- combiner.insert(afterCreate, Signed(mkPing(fiberId), bobProof))
        applied = fiberOf(afterPing, fiberId)
      } yield expect(validatorResult.isValid) and
      expect(applied.map(_.currentState).contains(StateId("s1"))) and
      expect(applied.exists(_.sequenceNumber > FiberOrdinal.MinValue))
    }
  }

  test(
    "Owners: validateSignedUpdate STILL admits the non-owner transition (structural-only, no block poison); the combiner is the sole gate"
  ) {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]
        fiberId   <- UUIDGen.randomUUID[IO]

        baseDef <- IO.fromEither(decode[StateMachineDefinition](defJson))
        machineDef = baseDef.copy(policy = FiberPolicy.constrained(transitionPolicy = Some(TransitionPolicy.Owners)))

        create = Updates.CreateStateMachine(fiberId, machineDef, MapValue(Map.empty[String, JsonLogicValue]))
        createProof <- fixture.registry.generateProofs(create, Set(Alice)) // owners = {Alice}
        afterCreate <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(create, createProof))

        bobProof <- fixture.registry.generateProofs(mkPing(fiberId), Set(Bob))

        // (a) the validator is STRUCTURAL-ONLY: it must NOT reject Bob (an Invalid here would drop the block)
        validatorResult <- validator.validateSignedUpdate(afterCreate, Signed(mkPing(fiberId), bobProof))
        // (b) the combiner is the sole gate: Bob (non-owner) is gracefully rejected — the fiber stays put
        afterBob <- combiner.insert(afterCreate, Signed(mkPing(fiberId), bobProof))
        bobRec = fiberOf(afterBob, fiberId)
      } yield expect(validatorResult.isValid) and
      expect(bobRec.map(_.currentState).contains(StateId("s0"))) and
      expect(bobRec.exists(_.sequenceNumber === FiberOrdinal.MinValue))
    }
  }
}
