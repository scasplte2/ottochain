package xyz.kd5ujc.shared_data.fiber.triggers

import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{~>, Monad}

import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.syntax.calculatedState._

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Processes cascading triggers atomically with shared ExecutionState.
 *
 * Uses TriggerHandler internally for unified trigger processing across fiber types.
 * Implements stack-safe iteration via Monad.tailRecM.
 *
 * All gas is tracked via StateT through the handler and evaluator.
 * Gas, depth, and processedInputs flow from the parent's ExecutionState —
 * no fresh state is created.
 *
 * Semantics:
 * - Processes triggers depth-first (cascade triggers prepended to queue)
 * - If any trigger fails, entire transaction aborts (all-or-nothing)
 * - Respects depth and gas limits via shared ExecutionState
 * - Detects cycles via (fiberId, inputKey) tracking (inherits parent's)
 */
trait TriggerDispatcher[G[_]] {

  def dispatch(
    triggers:  List[FiberTrigger],
    baseState: CalculatedState
  ): G[TransactionResult]
}

object TriggerDispatcher {

  /**
   * State carried through the queue-based processing loop.
   *
   * Log entries are accumulated via StateT, not carried here.
   * Pending uses List for O(1) prepend (depth-first processing).
   */
  final private case class QueueState(
    pending:  List[FiberTrigger],
    txnState: CalculatedState
  )

  def make[F[_]: Async: SecurityProvider, G[_]: Monad](implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): TriggerDispatcher[G] =
    new TriggerDispatcher[G] {

      private val logger: SelfAwareStructuredLogger[F] =
        Slf4jLogger.getLoggerFromClass(TriggerDispatcher.getClass)

      private val handler: TriggerHandler[G] =
        TriggerHandler.make[F, G]

      def dispatch(
        triggers:  List[FiberTrigger],
        baseState: CalculatedState
      ): G[TransactionResult] = {
        val initialQueue = QueueState(triggers, baseState)

        Monad[G].tailRecM(initialQueue)(processNext)
      }

      /**
       * Process next item in the queue, returning Left to continue or Right to terminate.
       */
      private def processNext(qs: QueueState): G[Either[QueueState, TransactionResult]] =
        ExecutionOps.checkLimits[G].flatMap {
          case Some(reason) =>
            for {
              gasUsed <- ExecutionOps.getGasUsed[G]
              depth   <- ExecutionOps.getDepth[G]
            } yield (TransactionResult.Aborted(reason, gasUsed, depth): TransactionResult).asRight[QueueState]

          case None =>
            qs.pending match {
              case Nil =>
                for {
                  gasUsed    <- ExecutionOps.getGasUsed[G]
                  depth      <- ExecutionOps.getDepth[G]
                  logEntries <- ExecutionOps.getLogs[G]
                } yield (TransactionResult.Committed(
                  updatedStateMachines = qs.txnState.stateMachines,
                  updatedOracles = qs.txnState.scriptOracles,
                  logEntries = logEntries.toList,
                  totalGasUsed = gasUsed,
                  maxDepth = depth
                ): TransactionResult).asRight[QueueState]

              case trigger :: rest =>
                processSingleTrigger(trigger, qs.txnState).flatMap {
                  case Right((nextState, moreTriggers)) =>
                    QueueState(
                      pending = moreTriggers ++ rest,
                      txnState = nextState
                    ).asLeft[TransactionResult].pure[G]

                  case Left(reason) =>
                    for {
                      gasUsed <- ExecutionOps.getGasUsed[G]
                      depth   <- ExecutionOps.getDepth[G]
                    } yield (TransactionResult.Aborted(reason, gasUsed, depth): TransactionResult).asRight[QueueState]
                }
            }
        }

      private type TriggerResult =
        (CalculatedState, List[FiberTrigger])

      private def processSingleTrigger(
        trigger: FiberTrigger,
        state:   CalculatedState
      ): G[Either[FailureReason, TriggerResult]] = {
        val fiberId = trigger.targetFiberId
        val inputKey = trigger.input.key

        for {
          isCycle <- ExecutionOps.checkCycle[G](fiberId, inputKey)
          result <-
            if (isCycle) {
              (FailureReason
                .CycleDetected(fiberId, inputKey): FailureReason)
                .asLeft[TriggerResult]
                .pure[G]
            } else {
              state.getFiber(fiberId) match {
                case None =>
                  logger
                    .warn(
                      s"Trigger target fiber $fiberId not found. " +
                      s"Source: ${trigger.sourceFiberId.getOrElse("external")}, " +
                      s"Input: $inputKey"
                    )
                    .liftTo[G]
                    .as(
                      (FailureReason.TriggerTargetNotFound(
                        fiberId,
                        trigger.sourceFiberId
                      ): FailureReason).asLeft[TriggerResult]
                    )

                case Some(fiber) =>
                  processWithHandler(trigger, fiber, state)
              }
            }
        } yield result
      }

      private def processWithHandler(
        trigger: FiberTrigger,
        fiber:   Records.FiberRecord,
        state:   CalculatedState
      ): G[Either[FailureReason, TriggerResult]] =
        for {
          _             <- ExecutionOps.markProcessed[G](fiber.fiberId, trigger.input.key)
          handlerResult <- handler.handle(trigger, fiber, state)
          result <- handlerResult match {
            case TriggerHandlerResult.Success(updatedState, cascadeTriggers) =>
              ExecutionOps
                .incrementDepth[G]
                .as((updatedState, cascadeTriggers).asRight[FailureReason])

            case TriggerHandlerResult.Failed(reason) =>
              reason.asLeft[TriggerResult].pure[G]
          }
        } yield result
    }
}
