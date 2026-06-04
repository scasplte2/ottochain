package xyz.kd5ujc.shared_data.fiber.core

import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{Monad, ~>}

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.gas._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator

import xyz.kd5ujc.schema.fiber.{FailureReason, FiberContext, GasExhaustionPhase}
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Single boundary between the fiber engine and the metakit JSON-Logic VM.
 *
 * metakit's `evaluateWithGas` *returns* the gas it consumed; the engine must then charge that gas
 * against `ExecutionState`. Hand-rolling "evaluate → match → chargeGas / toFailureReason" at every
 * call site is the source of the "forgot to chargeGas" footgun. This object encapsulates that
 * boundary exactly once:
 *
 *   - reads the remaining budget from `ExecutionState` + `FiberContext`,
 *   - calls metakit in `F`, lifts `F ~> G` once,
 *   - on success: charges consumed gas to `ExecutionState`, returns the value (Right),
 *   - on failure: maps the `JsonLogicException` to a `FailureReason` for the given phase (Left),
 *     charging no gas (mirrors the prior per-site behavior).
 *
 * metakit itself is unchanged — it already accepts a `GasLimit` and raises on exhaustion; this is
 * purely the host-side adapter.
 */
object MeteredEvaluator {

  /**
   * Evaluate `expr` against `context`, charging consumed gas on success.
   *
   * @param phase the gas-exhaustion phase to attribute a failure to (Guard | Effect | Oracle | Trigger | Spawn)
   * @return Right(value) with gas already charged, or Left(reason) on evaluation failure (no gas charged)
   */
  def eval[F[_]: Async, G[_]: Monad](
    expr:    JsonLogicExpression,
    context: JsonLogicValue,
    phase:   GasExhaustionPhase
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[Either[FailureReason, JsonLogicValue]] =
    for {
      remaining <- ExecutionOps.remainingGas[G]
      gasConfig <- ExecutionOps.askGasConfig[G]
      evalResult <- JsonLogicEvaluator
        .tailRecursive[F]
        .evaluateWithGas(expr, context, None, GasLimit(remaining), gasConfig)
        .liftTo[G]
      out <- evalResult match {
        case Right(EvaluationResult(value, gasUsed, _, _)) =>
          ExecutionOps.chargeGas[G](gasUsed.amount).as(value.asRight[FailureReason])
        case Left(ex) =>
          ex.toFailureReason[G](phase).map(_.asLeft[JsonLogicValue])
      }
    } yield out

  /**
   * Convenience for sites that *drop* a result on evaluation failure rather than aborting
   * (the prior `EffectExtractor` payload/args behavior). Gas is charged only on success.
   */
  def evalOpt[F[_]: Async, G[_]: Monad](
    expr:    JsonLogicExpression,
    context: JsonLogicValue,
    phase:   GasExhaustionPhase
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): G[Option[JsonLogicValue]] =
    eval[F, G](expr, context, phase).map(_.toOption)
}
