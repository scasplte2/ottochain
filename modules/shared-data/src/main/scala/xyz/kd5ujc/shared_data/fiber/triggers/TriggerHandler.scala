package xyz.kd5ujc.shared_data.fiber.triggers

import cats.data.EitherT
import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{Monad, ~>}

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.{BoolValue, StrValue}
import io.constellationnetwork.metagraph_sdk.json_logic.gas._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber.FiberLogEntry.{EventReceipt, OracleInvocation}
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.fiber.evaluation.{FiberEvaluator, OracleProcessor}
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Handles individual trigger processing in G[_] with gas tracked via StateT
 * and config read from FiberContext via Ask.
 *
 * Dispatches to appropriate handler based on fiber type:
 * - StateMachineFiberRecord: delegates to FiberEvaluator for guard/effect evaluation
 * - ScriptOracleFiberRecord: evaluates script directly with gas metering
 */
trait TriggerHandler[G[_]] {

  def handle(
    trigger: FiberTrigger,
    fiber:   Records.FiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult]
}

object TriggerHandler {

  def make[F[_]: Async: SecurityProvider, G[_]: Monad](implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): TriggerHandler[G] =
    (trigger: FiberTrigger, fiber: Records.FiberRecord, state: CalculatedState) =>
      fiber match {
        case _: Records.StateMachineFiberRecord =>
          new StateMachineTriggerHandler[F, G](state)
            .handle(trigger, fiber, state)

        case _: Records.ScriptOracleFiberRecord =>
          new OracleTriggerHandler[F, G]()
            .handle(trigger, fiber, state)
      }
}

class StateMachineTriggerHandler[F[_]: Async: SecurityProvider, G[_]: Monad](
  calculatedState: CalculatedState
)(implicit S: Stateful[G, ExecutionState], A: Ask[G, FiberContext], lift: F ~> G) {

  def handle(
    trigger: FiberTrigger,
    fiber:   Records.FiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] =
    fiber match {
      case sm: Records.StateMachineFiberRecord =>
        handleStateMachine(trigger, sm, state)
      case other =>
        (TriggerHandlerResult.Failed(
          FailureReason
            .FiberInputMismatch(other.fiberId, FiberKind.ScriptOracle, InputKind.Transition)
        ): TriggerHandlerResult).pure[G]
    }

  private def handleStateMachine(
    trigger: FiberTrigger,
    sm:      Records.StateMachineFiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] =
    for {
      ordinal <- ExecutionOps.askOrdinal[G]
      outcome <- FiberEvaluator.make[F, G](calculatedState).evaluate(sm, trigger.input, List.empty)
      result <- outcome match {
        case FiberResult.Success(newStateData, newStateId, triggers, _, _, emittedEvents) =>
          val receipt = EventReceipt.success(
            sm = sm,
            eventName = trigger.input.key,
            ordinal = ordinal,
            gasUsed = 0L,
            newStateId = newStateId,
            triggers = triggers,
            sourceFiberId = trigger.sourceFiberId,
            emittedEvents = emittedEvents
          )

          val updatedFiber = sm.copy(
            currentState = newStateId.getOrElse(sm.currentState),
            stateData = newStateData,
            sequenceNumber = sm.sequenceNumber.next,
            latestUpdateOrdinal = ordinal,
            lastReceipt = Some(receipt)
          )

          val updatedState = state.updateFiber(updatedFiber)

          ExecutionOps
            .appendLog[G](receipt)
            .as(
              TriggerHandlerResult.Success(
                updatedState = updatedState,
                cascadeTriggers = triggers
              ): TriggerHandlerResult
            )

        case FiberResult.GuardFailed(attemptedCount) =>
          (TriggerHandlerResult.Failed(
            FailureReason.NoGuardMatched(sm.currentState, trigger.input.key, attemptedCount)
          ): TriggerHandlerResult).pure[G]

        case FiberResult.Failed(reason) =>
          (TriggerHandlerResult.Failed(reason): TriggerHandlerResult).pure[G]
      }
    } yield result
}

class OracleTriggerHandler[F[_]: Async, G[_]: Monad]()(implicit
  S:    Stateful[G, ExecutionState],
  A:    Ask[G, FiberContext],
  lift: F ~> G
) {

  def handle(
    trigger: FiberTrigger,
    fiber:   Records.FiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] =
    fiber match {
      case oracle: Records.ScriptOracleFiberRecord =>
        handleOracle(trigger, oracle, state)
      case other =>
        (TriggerHandlerResult.Failed(
          FailureReason.FiberInputMismatch(other.fiberId, FiberKind.StateMachine, InputKind.MethodCall)
        ): TriggerHandlerResult).pure[G]
    }

  private def handleOracle(
    trigger: FiberTrigger,
    oracle:  Records.ScriptOracleFiberRecord,
    state:   CalculatedState
  ): G[TriggerHandlerResult] = {
    type OracleET[A] = EitherT[G, TriggerHandlerResult, A]

    val computation: OracleET[TriggerHandlerResult] = for {
      callerAddress <- EitherT.fromOption[G](
        trigger.sourceFiberId.flatMap { fiberId =>
          state.getFiber(fiberId).flatMap(_.owners.headOption)
        },
        TriggerHandlerResult.Failed(
          FailureReason.CallerResolutionFailed(oracle.fiberId, trigger.sourceFiberId)
        ): TriggerHandlerResult
      )

      _ <- EitherT[G, TriggerHandlerResult, Unit](
        OracleProcessor.validateAccess(oracle.accessControl, callerAddress, oracle.fiberId, state).liftTo[G].map {
          case Right(_)     => Right(())
          case Left(reason) => Left(TriggerHandlerResult.Failed(reason))
        }
      )

      inputData = MapValue(
        Map(
          ReservedKeys.METHOD -> StrValue(trigger.input.key),
          ReservedKeys.ARGS   -> trigger.input.content,
          ReservedKeys.STATE  -> oracle.stateData.getOrElse(NullValue)
        )
      )

      remainingGas <- EitherT.liftF[G, TriggerHandlerResult, Long](
        ExecutionOps.remainingGas[G]
      )

      gasConfig <- EitherT.liftF[G, TriggerHandlerResult, GasConfig](
        ExecutionOps.askGasConfig[G]
      )

      scriptResult <- EitherT[G, TriggerHandlerResult, EvaluationResult[JsonLogicValue]](
        JsonLogicEvaluator
          .tailRecursive[F]
          .evaluateWithGas(oracle.scriptProgram, inputData, None, GasLimit(remainingGas), gasConfig)
          .liftTo[G]
          .flatMap {
            case Right(result) =>
              ExecutionOps.chargeGas[G](result.gasUsed.amount).as(result.asRight[TriggerHandlerResult])

            case Left(ex) =>
              ex.toFailureReason[G](GasExhaustionPhase.Oracle).map { reason =>
                (TriggerHandlerResult.Failed(reason): TriggerHandlerResult)
                  .asLeft[EvaluationResult[JsonLogicValue]]
              }
          }
      )

      scriptGasUsed = scriptResult.gasUsed.amount
      evaluationResult = scriptResult.value

      stateAndResult <- EitherT.liftF[G, TriggerHandlerResult, (Option[JsonLogicValue], JsonLogicValue)](
        OracleProcessor.extractStateAndResult(evaluationResult).liftTo[G]
      )
      (newStateData, returnValue) = stateAndResult

      _ <- {
        val checkResult: Either[TriggerHandlerResult, Unit] = returnValue match {
          case BoolValue(false) =>
            Left(
              TriggerHandlerResult.Failed(
                FailureReason.OracleInvocationFailed(oracle.fiberId, trigger.input.key, Some("returned false"))
              )
            )
          case MapValue(m) if m.get("valid").contains(BoolValue(false)) =>
            val errorMsg = m.get("error").collect { case StrValue(e) => e }.getOrElse("Unknown error")
            Left(
              TriggerHandlerResult.Failed(
                FailureReason
                  .OracleInvocationFailed(oracle.fiberId, trigger.input.key, Some(s"validation failed: $errorMsg"))
              )
            )
          case _ => Right(())
        }
        EitherT.fromEither[G](checkResult)
      }

      newHash <- EitherT.liftF[G, TriggerHandlerResult, Option[io.constellationnetwork.security.hash.Hash]](
        newStateData.traverse(_.computeDigest).liftTo[G]
      )

      ordinal <- EitherT.liftF[G, TriggerHandlerResult, io.constellationnetwork.schema.SnapshotOrdinal](
        ExecutionOps.askOrdinal[G]
      )

      invocation = OracleInvocation(
        fiberId = oracle.fiberId,
        method = trigger.input.key,
        args = trigger.input.content,
        result = returnValue,
        gasUsed = scriptGasUsed,
        invokedAt = ordinal,
        invokedBy = callerAddress
      )

      _ <- EitherT.liftF[G, TriggerHandlerResult, Unit](
        ExecutionOps.appendLog[G](invocation)
      )

      updatedOracle = oracle.copy(
        stateData = newStateData,
        stateDataHash = newHash,
        latestUpdateOrdinal = ordinal,
        sequenceNumber = oracle.sequenceNumber.next,
        lastInvocation = Some(invocation)
      )

      updatedState = state.updateFiber(updatedOracle)

    } yield TriggerHandlerResult.Success(
      updatedState = updatedState,
      cascadeTriggers = List.empty
    ): TriggerHandlerResult

    computation.merge
  }
}
