package xyz.kd5ujc.shared_data.fiber.evaluation

import java.util.UUID

import cats.effect.Async
import cats.mtl.{Ask, Stateful}
import cats.syntax.all._
import cats.{Monad, ~>}

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.{BoolValue, StrValue}
import io.constellationnetwork.metagraph_sdk.json_logic.gas._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryCodec
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.fiber.FiberResult.FailureReasonOps
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Unified evaluator for both state machine and oracle fibers.
 *
 * Operates in G[_] with Stateful[G, ExecutionState] for consistent gas tracking via StateT.
 *
 * Dispatches to appropriate evaluation strategy based on fiber type:
 * - StateMachineFiberRecord + Transition → guard/effect evaluation
 * - ScriptFiberRecord + MethodCall → script evaluation
 *
 * Invalid combinations (SM + MethodCall, Oracle + Transition) return Failed.
 */
trait FiberEvaluator[G[_]] {

  def evaluate(
    fiber:  Records.FiberRecord,
    input:  FiberInput,
    proofs: List[SignatureProof]
  ): G[FiberResult]
}

object FiberEvaluator {

  /**
   * Create FiberEvaluator with an explicit calculatedState (evolving during trigger processing).
   * Used by TriggerHandler where calculatedState changes as each trigger modifies fibers.
   * All other config is still read from FiberContext via Ask.
   */
  def make[F[_]: Async: SecurityProvider, G[_]: Monad](
    calculatedState: CalculatedState
  )(implicit
    S:    Stateful[G, ExecutionState],
    A:    Ask[G, FiberContext],
    lift: F ~> G
  ): FiberEvaluator[G] =
    new FiberEvaluator[G] {

      def evaluate(
        fiber:  Records.FiberRecord,
        input:  FiberInput,
        proofs: List[SignatureProof]
      ): G[FiberResult] = (fiber, input) match {
        case (sm: Records.StateMachineFiberRecord, FiberInput.Transition(eventType, payload)) =>
          evaluateStateMachine(sm, eventType, payload, proofs)

        case (oracle: Records.ScriptFiberRecord, FiberInput.MethodCall(method, args, caller)) =>
          evaluateOracle(oracle, method, args, caller)

        case (sm: Records.StateMachineFiberRecord, _: FiberInput.MethodCall) =>
          FailureReason
            .FiberInputMismatch(sm.fiberId, FiberKind.StateMachine, InputKind.MethodCall)
            .pureOutcome[G]

        case (oracle: Records.ScriptFiberRecord, _: FiberInput.Transition) =>
          FailureReason
            .FiberInputMismatch(oracle.fiberId, FiberKind.Script, InputKind.Transition)
            .pureOutcome[G]
      }

      // ──────────────────────────────────────────────────────────────────────────
      // State Machine Evaluation
      // ──────────────────────────────────────────────────────────────────────────

      private def evaluateStateMachine(
        fiber:     Records.StateMachineFiberRecord,
        eventName: String,
        payload:   JsonLogicValue,
        proofs:    List[SignatureProof]
      ): G[FiberResult] = {
        val input = FiberInput.Transition(eventName, payload)

        fiber.definition.transitionMap
          .get((fiber.currentState, eventName))
          .fold(
            FailureReason.NoTransitionFound(fiber.currentState, eventName).pureOutcome[G]
          )(
            tryTransitions(fiber, input, proofs, _, attemptedGuards = 0)
          )
      }

      private def tryTransitions(
        fiber:           Records.StateMachineFiberRecord,
        input:           FiberInput.Transition,
        proofs:          List[SignatureProof],
        transitions:     List[Transition],
        attemptedGuards: Int
      ): G[FiberResult] =
        transitions match {
          case Nil => (FiberResult.GuardFailed(attemptedGuards): FiberResult).pure[G]
          case transition :: rest =>
            for {
              ordinal         <- ExecutionOps.askOrdinal[G]
              snapshotHash    <- ExecutionOps.askSnapshotHash[G]
              epochProgress   <- ExecutionOps.askEpochProgress[G]
              contextProvider <- ContextProvider.make[F](calculatedState, ordinal, snapshotHash, epochProgress).pure[G]
              contextData <- contextProvider
                .buildContext(
                  fiber,
                  input,
                  proofs,
                  transition.dependencies
                )
                .liftTo[G]
              result <- evaluateGuardAndApply(
                fiber,
                transition,
                input,
                contextData,
                proofs,
                rest,
                attemptedGuards
              )
            } yield result
        }

      private def evaluateGuardAndApply(
        fiber:           Records.StateMachineFiberRecord,
        transition:      Transition,
        input:           FiberInput.Transition,
        contextData:     JsonLogicValue,
        proofs:          List[SignatureProof],
        rest:            List[Transition],
        attemptedGuards: Int
      ): G[FiberResult] =
        for {
          remainingGas <- ExecutionOps.remainingGas[G]
          gasConfig    <- ExecutionOps.askGasConfig[G]
          evalResult <- JsonLogicEvaluator
            .tailRecursive[F]
            .evaluateWithGas(transition.guard, contextData, None, GasLimit(remainingGas), gasConfig)
            .liftTo[G]
          result <- evalResult match {
            case Right(EvaluationResult(BoolValue(true), guardGasUsed, _, _)) =>
              ExecutionOps.chargeGas[G](guardGasUsed.amount) >>
              executeEffect(fiber, transition, contextData)

            case Right(EvaluationResult(BoolValue(false), guardGasUsed, _, _)) =>
              ExecutionOps.chargeGas[G](guardGasUsed.amount) >>
              tryTransitions(fiber, input, proofs, rest, attemptedGuards + 1)

            case Right(EvaluationResult(other, _, _, _)) =>
              FailureReason
                .EvaluationError(
                  GasExhaustionPhase.Guard,
                  s"Guard returned non-boolean: ${other.getClass.getSimpleName}"
                )
                .pureOutcome[G]

            case Left(ex) =>
              ex.toFailureReason[G](GasExhaustionPhase.Guard).map(_.asOutcome)
          }
        } yield result

      // ──────────────────────────────────────────────────────────────────────────
      // Effect Execution
      // ──────────────────────────────────────────────────────────────────────────

      private def executeEffect(
        fiber:       Records.StateMachineFiberRecord,
        transition:  Transition,
        contextData: JsonLogicValue
      ): G[FiberResult] =
        fiber.stateData match {
          case currentMap: MapValue =>
            evaluateEffectExpression(transition, contextData).flatMap {
              case Left(reason) => reason.pureOutcome[G]
              case Right(effectResult) =>
                processEffectResult(fiber.fiberId, currentMap, transition, effectResult, contextData)
            }

          case _ =>
            FailureReason.EvaluationError(GasExhaustionPhase.Effect, "State data must be MapValue").pureOutcome[G]
        }

      private def evaluateEffectExpression(
        transition:  Transition,
        contextData: JsonLogicValue
      ): G[Either[FailureReason, JsonLogicValue]] =
        for {
          remainingGas <- ExecutionOps.remainingGas[G]
          gasConfig    <- ExecutionOps.askGasConfig[G]
          evalResult <- JsonLogicEvaluator
            .tailRecursive[F]
            .evaluateWithGas(transition.effect, contextData, None, GasLimit(remainingGas), gasConfig)
            .liftTo[G]
          result <- evalResult match {
            case Right(EvaluationResult(effectResult, effectGasUsed, _, _)) =>
              ExecutionOps.chargeGas[G](effectGasUsed.amount).as(effectResult.asRight[FailureReason])

            case Left(ex) =>
              ex.toFailureReason[G](GasExhaustionPhase.Effect).map(_.asLeft[JsonLogicValue])
          }
        } yield result

      private def processEffectResult(
        fiberId:      UUID,
        currentMap:   MapValue,
        transition:   Transition,
        effectResult: JsonLogicValue,
        contextData:  JsonLogicValue
      ): G[FiberResult] =
        for {
          limits    <- ExecutionOps.askLimits[G]
          sizeCheck <- validateStateSize(effectResult, limits).liftTo[G]
          result <- sizeCheck match {
            case Left(reason) => reason.pureOutcome[G]
            case Right(_) =>
              buildSuccessOutcome(fiberId, currentMap, transition, effectResult, contextData)
          }
        } yield result

      private def validateStateSize(
        effectResult: JsonLogicValue,
        limits:       ExecutionLimits
      ): F[Either[FailureReason, Unit]] =
        JsonBinaryCodec[F, JsonLogicValue]
          .serialize(effectResult)
          .map { bytes =>
            val size = bytes.length
            if (size <= limits.maxStateSizeBytes) ().asRight
            else FailureReason.StateSizeTooLarge(size, limits.maxStateSizeBytes).asLeft
          }

      private def buildSuccessOutcome(
        fiberId:      UUID,
        currentMap:   MapValue,
        transition:   Transition,
        effectResult: JsonLogicValue,
        contextData:  JsonLogicValue
      ): G[FiberResult] =
        for {
          fiberGasConfig <- A.reader(_.fiberGasConfig)
          limits         <- ExecutionOps.askLimits[G]

          spawnMachines = EffectExtractor.extractSpawnDirectivesFromExpression(transition.effect)
          triggers <- EffectExtractor.extractTriggerEvents[F, G](
            effectResult,
            contextData,
            fiberId
          )
          oracleCall <- EffectExtractor.extractOracleCall[F, G](
            effectResult,
            contextData,
            fiberId
          )
          emittedEvents = EffectExtractor.extractEmittedEvents(effectResult)
          allTriggers = triggers ++ oracleCall.toList

          // Charge orchestration overhead
          _ <- ExecutionOps.chargeGas[G](fiberGasConfig.contextBuild.amount)
          _ <- ExecutionOps.chargeGas[G](allTriggers.size.toLong * fiberGasConfig.triggerEvent.amount)
          _ <- ExecutionOps.chargeGas[G](spawnMachines.size.toLong * fiberGasConfig.spawnDirective.amount)

          // Get total gas for result
          totalGasUsed <- ExecutionOps.getGasUsed[G]

          // Check if we exceeded limits
          result <-
            if (totalGasUsed > limits.maxGas)
              FailureReason
                .GasExhaustedFailure(totalGasUsed, limits.maxGas, GasExhaustionPhase.Effect)
                .pureOutcome[G]
            else
              StateMerger.make[F].mergeEffectIntoState(currentMap, effectResult).liftTo[G].map[FiberResult] {
                case Right(newStateData) =>
                  FiberResult.Success(
                    newStateData = newStateData,
                    newStateId = Some(transition.to),
                    triggers = allTriggers,
                    spawns = spawnMachines,
                    returnValue = None,
                    emittedEvents = emittedEvents
                  )
                case Left(reason) => reason.asOutcome
              }
        } yield result

      // ──────────────────────────────────────────────────────────────────────────
      // Oracle Evaluation
      // ──────────────────────────────────────────────────────────────────────────

      private def evaluateOracle(
        oracle: Records.ScriptFiberRecord,
        method: String,
        args:   JsonLogicValue,
        caller: io.constellationnetwork.schema.address.Address
      ): G[FiberResult] =
        for {
          result <- ScriptProcessor
            .validateAccess[F](oracle.accessControl, caller, oracle.fiberId, calculatedState)
            .liftTo[G]
            .flatMap {
              case Left(reason) => reason.pureOutcome[G]

              case Right(_) =>
                val inputData = MapValue(
                  Map(
                    ReservedKeys.METHOD -> StrValue(method),
                    ReservedKeys.ARGS   -> args,
                    ReservedKeys.STATE  -> oracle.stateData.getOrElse(NullValue)
                  )
                )

                for {
                  remainingGas <- ExecutionOps.remainingGas[G]
                  gasConfig    <- ExecutionOps.askGasConfig[G]
                  evalResult <- JsonLogicEvaluator
                    .tailRecursive[F]
                    .evaluateWithGas(oracle.scriptProgram, inputData, None, GasLimit(remainingGas), gasConfig)
                    .liftTo[G]
                  r <- evalResult match {
                    case Right(EvaluationResult(evaluationResult, gasUsed, _, _)) =>
                      for {
                        _              <- ExecutionOps.chargeGas[G](gasUsed.amount)
                        stateAndResult <- ScriptProcessor.extractStateAndResult[F](evaluationResult).liftTo[G]
                        (newStateData, returnValue) = stateAndResult
                      } yield FiberResult.Success(
                        newStateData = newStateData.getOrElse(oracle.stateData.getOrElse(NullValue)),
                        newStateId = None,
                        triggers = List.empty,
                        spawns = List.empty,
                        returnValue = Some(returnValue)
                      ): FiberResult

                    case Left(ex) =>
                      ex.toFailureReason[G](GasExhaustionPhase.Oracle).map(_.asOutcome)
                  }
                } yield r
            }
        } yield result
    }
}
