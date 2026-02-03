package xyz.kd5ujc.shared_data.testkit

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.lifecycle.CombinerService
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber.FiberOrdinal
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_test.Participant
import xyz.kd5ujc.shared_test.Participant.ParticipantRegistry

/**
 * Extension methods for `DataState` to simplify test transitions and fiber lookups.
 *
 * == Usage ==
 * {{{
 * import xyz.kd5ujc.shared_data.testkit.DataStateTestOps._
 *
 * // Single transition:
 * val nextState <- state.transition(fiberId, "submit", payload, Alice)(registry, combiner)
 *
 * // Lookup a fiber record:
 * val fiber: Option[Records.StateMachineFiberRecord] = state.fiberRecord(fiberId)
 *
 * // Get the current sequence number for a fiber:
 * val seqNum = state.seqNum(fiberId)
 * }}}
 */
object DataStateTestOps {

  implicit class DataStateOps(private val state: DataState[OnChain, CalculatedState]) extends AnyVal {

    /**
     * Perform a state machine transition: create update, sign, and insert in one step.
     *
     * Automatically reads the current sequence number for the target fiber.
     *
     * @param fiberId       Target fiber UUID
     * @param event     Event name to trigger
     * @param payload   Event payload data
     * @param signers   One or more participants who sign the transaction
     * @param registry  Participant registry for proof generation
     * @param combiner  Combiner service for state insertion
     * @return New DataState after the transition
     */
    def transition[F[_]: Async](
      fiberId: UUID,
      event:   String,
      payload: JsonLogicValue,
      signers: Participant*
    )(
      registry: ParticipantRegistry[F],
      combiner: CombinerService[F, OttochainMessage, OnChain, CalculatedState]
    )(implicit l0ctx: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] = {
      val seqNum = state.calculated.stateMachines
        .get(fiberId)
        .map(_.sequenceNumber)
        .getOrElse(FiberOrdinal.MinValue)

      val update = Updates.TransitionStateMachine(
        fiberId = fiberId,
        eventName = event,
        payload = payload,
        targetSequenceNumber = seqNum
      )

      for {
        proof    <- registry.generateProofs(update, signers.toSet)
        newState <- combiner.insert(state, Signed(update, proof))
      } yield newState
    }

    /**
     * Perform a transition with a MapValue payload built from key-value pairs.
     *
     * @param fiberId     Target fiber UUID
     * @param event   Event name to trigger
     * @param signers Participants who sign the transaction
     * @param fields  Payload fields as key-value pairs
     */
    def transitionWith[F[_]: Async](
      fiberId: UUID,
      event:   String,
      signers: Set[Participant],
      fields:  (String, JsonLogicValue)*
    )(
      registry: ParticipantRegistry[F],
      combiner: CombinerService[F, OttochainMessage, OnChain, CalculatedState]
    )(implicit l0ctx: L0NodeContext[F]): F[DataState[OnChain, CalculatedState]] =
      transition(fiberId, event, MapValue(fields.toMap), signers.toSeq: _*)(registry, combiner)

    /**
     * Lookup a state machine fiber record by CID.
     *
     * @return Some(record) if the fiber exists and is a StateMachineFiberRecord, None otherwise
     */
    def fiberRecord(fiberId: UUID): Option[Records.StateMachineFiberRecord] =
      state.calculated.stateMachines.get(fiberId).collect { case r: Records.StateMachineFiberRecord =>
        r
      }

    /**
     * Lookup a script fiber record by CID.
     *
     * @return Some(record) if the oracle exists, None otherwise
     */
    def oracleRecord(fiberId: UUID): Option[Records.ScriptFiberRecord] =
      state.calculated.scripts.get(fiberId)

    /**
     * Get the current sequence number for a fiber.
     *
     * @return The fiber's sequence number, or MinValue if the fiber doesn't exist
     */
    def seqNum(fiberId: UUID): FiberOrdinal =
      state.calculated.stateMachines
        .get(fiberId)
        .map(_.sequenceNumber)
        .getOrElse(FiberOrdinal.MinValue)
  }
}
