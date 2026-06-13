package xyz.kd5ujc.metagraph_l0.handlers

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.shared_data.fiber.FiberGasEstimator
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.FiberRules

import io.circe.Json
import io.circe.syntax.EncoderOps

/**
 * Static, execution-free fee/gas estimate logic, backed by [[FiberGasEstimator]] (no JLVM run):
 * a pre-flight quote of how much gas an operation may charge. The authoritative charge is always
 * the metered evaluation in combine.
 */
class EstimateHandler[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
) {

  // worst executed path = sum(candidate guards) + max(candidate effect)
  def transition(fiberId: UUID, eventName: String): F[Either[DataApplicationValidationError, Json]] =
    checkpointService.get.map { case Checkpoint(_, state) =>
      state.stateMachines.get(fiberId) match {
        case None =>
          (FiberRules.Errors.FiberNotFound(fiberId): DataApplicationValidationError).asLeft[Json]
        case Some(fiber) =>
          val est = FiberGasEstimator.estimateTransition(fiber.definition, fiber.currentState, eventName)
          val candidates = fiber.definition.transitionMap.getOrElse((fiber.currentState, eventName), Nil).size
          Json
            .obj(
              "fiberId"              -> fiberId.asJson,
              "currentState"         -> fiber.currentState.value.asJson,
              "event"                -> eventName.asJson,
              "gasEstimate"          -> est.cost.amount.asJson,
              "opCount"              -> est.opCount.asJson,
              "maxDepth"             -> est.depth.asJson,
              "candidateTransitions" -> candidates.asJson,
              "note" -> "static gas estimate (exact for non-scaling ops, floor where ops scale); authoritative charge is metered at execution".asJson
            )
            .asRight[DataApplicationValidationError]
      }
    }

  def script(scriptId: UUID): F[Either[DataApplicationValidationError, Json]] =
    checkpointService.get.map { case Checkpoint(_, state) =>
      state.scripts.get(scriptId) match {
        case None =>
          (FiberRules.Errors.FiberNotFound(scriptId): DataApplicationValidationError).asLeft[Json]
        case Some(script) =>
          val est = FiberGasEstimator.estimateScript(script.scriptProgram)
          Json
            .obj(
              "scriptId"    -> scriptId.asJson,
              "gasEstimate" -> est.cost.amount.asJson,
              "opCount"     -> est.opCount.asJson,
              "maxDepth"    -> est.depth.asJson,
              "note"        -> "static gas estimate; authoritative charge is metered at execution".asJson
            )
            .asRight[DataApplicationValidationError]
      }
    }
}
