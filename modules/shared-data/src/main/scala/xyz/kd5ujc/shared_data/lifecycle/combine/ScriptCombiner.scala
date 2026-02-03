package xyz.kd5ujc.shared_data.lifecycle.combine

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_data.fiber.evaluation.ScriptProcessor
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Combiner operations for scripts.
 *
 * Handles creation and invocation of scripts.
 * Uses the syntax extensions for atomic state updates.
 *
 * @param current The current DataState to operate on
 * @param ctx     The L0NodeContext for accessing snapshot ordinals
 */
class ScriptCombiner[F[_]: Async: SecurityProvider](
  current:         DataState[OnChain, CalculatedState],
  ctx:             L0NodeContext[F],
  executionLimits: ExecutionLimits
) {

  /**
   * Creates a new script from the update.
   *
   * Delegates to ScriptProcessor for the actual creation logic.
   */
  def createScript(
    update: Signed[Updates.CreateScript]
  ): CombineResult[F] = for {
    currentOrdinal <- ctx.getCurrentOrdinal
    result         <- ScriptProcessor.createScript(current, update, currentOrdinal)
  } yield result

  /**
   * Invokes a script method.
   *
   * Delegates to FiberOrchestrator for consistent gas metering and
   * unified processing semantics with state machine transitions.
   */
  def invokeScript(
    update: Signed[Updates.InvokeScript]
  ): CombineResult[F] = for {
    currentOrdinal <- ctx.getCurrentOrdinal

    // Verify oracle exists and sequence number matches before processing
    oracleRecord <- current.calculated.scripts
      .get(update.fiberId)
      .fold(
        Async[F].raiseError[Records.ScriptFiberRecord](
          new RuntimeException(s"Oracle ${update.fiberId} not found")
        )
      )(_.pure[F])

    // Defense-in-depth: reject stale sequence numbers
    _ <- Async[F]
      .raiseError(
        new RuntimeException(
          s"Sequence number mismatch: target=${update.targetSequenceNumber}, actual=${oracleRecord.sequenceNumber}"
        )
      )
      .whenA(oracleRecord.sequenceNumber =!= update.targetSequenceNumber)

    caller <- update.proofs.toList.headOption
      .fold(Async[F].raiseError[Address](new RuntimeException("No proof provided")))(
        _.id.toAddress
      )

    // Delegate to FiberOrchestrator for consistent gas metering
    orchestrator = FiberEngine.make[F](
      current.calculated,
      currentOrdinal,
      executionLimits
    )

    input = FiberInput.MethodCall(
      method = update.method,
      args = update.args,
      caller = caller
    )

    outcome <- orchestrator.process(update.fiberId, input, update.proofs.toList)

    newState <- outcome match {
      case TransactionResult.Committed(_, updatedOracles, logEntries, _, _, _) =>
        updatedOracles.get(update.fiberId) match {
          case Some(updatedOracle) =>
            current.withRecord[F](update.fiberId, updatedOracle).map(_.appendLogs(logEntries))

          case None =>
            Async[F].raiseError(new RuntimeException(s"Oracle ${update.fiberId} not found in orchestrator result"))
        }

      case TransactionResult.Aborted(reason, _, _) =>
        Async[F].raiseError(new RuntimeException(s"Oracle invocation failed: ${reason.toMessage}"))
    }
  } yield newState

}

object ScriptCombiner {

  /**
   * Creates a new ScriptCombiner instance.
   */
  def apply[F[_]: Async: SecurityProvider](
    current:         DataState[OnChain, CalculatedState],
    ctx:             L0NodeContext[F],
    executionLimits: ExecutionLimits
  ): ScriptCombiner[F] =
    new ScriptCombiner[F](current, ctx, executionLimits)
}
