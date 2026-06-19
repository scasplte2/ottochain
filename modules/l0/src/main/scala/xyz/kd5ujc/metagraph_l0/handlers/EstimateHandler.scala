package xyz.kd5ujc.metagraph_l0.handlers

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.api.{FeeNotes, ScriptFeeEstimate, TransitionFeeEstimate}
import xyz.kd5ujc.shared_data.fiber.FiberGasEstimator
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.FiberRules

/**
 * Static, execution-free fee/gas estimate logic, backed by [[FiberGasEstimator]] (no JLVM run):
 * a pre-flight quote of how much gas an operation may charge. The authoritative charge is always
 * the metered evaluation in combine.
 */
class EstimateHandler[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
) {

  // worst executed path = sum(candidate guards) + max(candidate effect)
  def transition(fiberId: UUID, eventName: String): F[Either[DataApplicationValidationError, TransitionFeeEstimate]] =
    checkpointService.get.map { case Checkpoint(_, state) =>
      state.stateMachines.get(fiberId) match {
        case None =>
          (FiberRules.Errors.FiberNotFound(fiberId): DataApplicationValidationError).asLeft[TransitionFeeEstimate]
        case Some(fiber) =>
          val est = FiberGasEstimator.estimateTransition(fiber.definition, fiber.currentState, eventName)
          val candidates = fiber.definition.transitionMap.getOrElse((fiber.currentState, eventName), Nil).size
          TransitionFeeEstimate(
            fiberId = fiberId,
            currentState = fiber.currentState.value,
            event = eventName,
            gasEstimate = est.cost.amount,
            opCount = est.opCount,
            maxDepth = est.depth,
            candidateTransitions = candidates,
            note = FeeNotes.transition
          ).asRight[DataApplicationValidationError]
      }
    }

  def script(scriptId: UUID): F[Either[DataApplicationValidationError, ScriptFeeEstimate]] =
    checkpointService.get.map { case Checkpoint(_, state) =>
      state.scripts.get(scriptId) match {
        case None =>
          (FiberRules.Errors.FiberNotFound(scriptId): DataApplicationValidationError).asLeft[ScriptFeeEstimate]
        case Some(script) =>
          val est = FiberGasEstimator.estimateScript(script.scriptProgram)
          ScriptFeeEstimate(
            scriptId = scriptId,
            gasEstimate = est.cost.amount,
            opCount = est.opCount,
            maxDepth = est.depth,
            note = FeeNotes.script
          ).asRight[DataApplicationValidationError]
      }
    }
}
