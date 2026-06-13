package xyz.kd5ujc.metagraph_l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.shared_data.fiber.FiberGasEstimator
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.FiberRules

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s.HttpRoutes
import org.http4s.server.Router

/**
 * Static, execution-free fee/gas estimate routes — kept as their own focused route class rather than
 * bolted onto [[ML0CustomRoutes]]. Backed by [[FiberGasEstimator]] (no JLVM run): a pre-flight quote
 * of how much gas an operation may charge, so a client can size a fee before submitting. The
 * authoritative charge is always the metered evaluation in combine.
 */
class ML0EstimateRoutes[F[_]: Async](
  checkpointService: CheckpointService[F, CalculatedState]
) extends MetagraphPublicRoutes[F] {

  object EventQueryParam extends QueryParamDecoderMatcher[String]("event")

  private val v1Routes: HttpRoutes[F] = HttpRoutes.of[F] {

    // GET /v1/state-machines/{id}/estimate-fee?event=<eventName>
    // worst executed path = sum(candidate guards) + max(candidate effect)
    case GET -> Root / "state-machines" / UUIDVar(fiberId) / "estimate-fee" :? EventQueryParam(eventName) =>
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
      }.toResponse

    // GET /v1/scripts/{id}/estimate-fee — the script program expression
    case GET -> Root / "scripts" / UUIDVar(scriptId) / "estimate-fee" =>
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
      }.toResponse
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
