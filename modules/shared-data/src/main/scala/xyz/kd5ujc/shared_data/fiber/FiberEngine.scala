package xyz.kd5ujc.shared_data.fiber

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicValue, NullValue}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.fiber.FiberLogEntry.{EventReceipt, OracleInvocation}
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.core.FiberTInstances._
import xyz.kd5ujc.shared_data.fiber.core._
import xyz.kd5ujc.shared_data.fiber.evaluation._
import xyz.kd5ujc.shared_data.fiber.spawning.SpawnProcessor
import xyz.kd5ujc.shared_data.fiber.triggers.TriggerDispatcher
import xyz.kd5ujc.shared_data.syntax.calculatedState._

/**
 * Top-level orchestrator for fiber processing using FiberT monad transformer.
 *
 * Composes FiberEvaluator, TriggerDispatcher, and SpawnProcessor
 * to handle complete event/invocation processing including cascades.
 *
 * Processing flow:
 * 1. Create FiberContext with ordinal, limits, gas config
 * 2. Lookup fiber by ID and validate it's active
 * 3. Evaluate fiber (guards/effects for SM, script for Oracle)
 * 4. On success:
 *    a. Validate and process spawns (creates child fibers)
 *    b. Build effective state with spawns visible to triggers
 *    c. Process triggers (cascading evaluations)
 * 5. Commit or abort based on trigger results
 */
trait FiberEngine[F[_]] {

  def process(
    fiberId: UUID,
    input:   FiberInput,
    proofs:  List[SignatureProof]
  ): F[TransactionResult]
}

object FiberEngine {

  // Default values for optional context fields (used in tests)
  private val DefaultSnapshotHash: Hash = Hash.empty

  private val DefaultEpochProgress: EpochProgress = EpochProgress(
    eu.timepit.refined.types.numeric.NonNegLong.unsafeFrom(0L)
  )

  def make[F[_]: Async: SecurityProvider](
    calculatedState:  CalculatedState,
    ordinal:          SnapshotOrdinal,
    limits:           ExecutionLimits = ExecutionLimits(),
    lastSnapshotHash: Hash = DefaultSnapshotHash,
    epochProgress:    EpochProgress = DefaultEpochProgress,
    gasConfig:        GasConfig = GasConfig.Default,
    fiberGasConfig:   FiberGasConfig = FiberGasConfig.Default
  ): FiberEngine[F] = {
    new FiberEngine[F] {

      def process(
        fiberId: UUID,
        input:   FiberInput,
        proofs:  List[SignatureProof]
      ): F[TransactionResult] =
        processInternal(fiberId, input, proofs)
          .run(FiberContext(ordinal, lastSnapshotHash, epochProgress, limits, gasConfig, fiberGasConfig))
          .runA(ExecutionState.initial)

      private def processInternal(
        fiberId: UUID,
        input:   FiberInput,
        proofs:  List[SignatureProof]
      ): FiberT[F, TransactionResult] =
        calculatedState.getFiber(fiberId) match {
          case None =>
            abortWithReason(FailureReason.FiberNotFound(fiberId))

          case Some(fiber) if fiber.status != FiberStatus.Active =>
            abortWithReason(FailureReason.FiberNotActive(fiberId, fiber.status.toString))

          case Some(fiber) =>
            processActiveFiber(fiber, input, proofs)
        }

      private def abortWithReason(reason: FailureReason): FiberT[F, TransactionResult] =
        (TransactionResult.Aborted(reason, 0L): TransactionResult).pureFiber[F]

      private def processActiveFiber(
        fiber:  Records.FiberRecord,
        input:  FiberInput,
        proofs: List[SignatureProof]
      ): FiberT[F, TransactionResult] =
        FiberEvaluator
          .make[F, FiberT[F, *]](calculatedState)
          .evaluate(fiber, input, proofs)
          .flatMap {
            case FiberResult.Success(newStateData, newStateId, fiberTriggers, spawns, returnValue, emittedEvents) =>
              fiber match {
                case sm: Records.StateMachineFiberRecord =>
                  processStateMachineSuccess(sm, input, newStateData, newStateId, fiberTriggers, spawns, emittedEvents)

                case oracle: Records.ScriptFiberRecord =>
                  processOracleSuccess(oracle, input, newStateData, returnValue)
              }

            case FiberResult.GuardFailed(attemptedCount) =>
              handleGuardFailed(fiber, input, attemptedCount)

            case FiberResult.Failed(reason) =>
              ExecutionOps
                .getGasUsed[FiberT[F, *]]
                .map(
                  TransactionResult.Aborted(reason, _): TransactionResult
                )
          }

      private def handleGuardFailed(
        fiber:          Records.FiberRecord,
        input:          FiberInput,
        attemptedCount: Int
      ): FiberT[F, TransactionResult] =
        for {
          gasUsed <- ExecutionOps.getGasUsed[FiberT[F, *]]
        } yield fiber match {
          case sm: Records.StateMachineFiberRecord =>
            TransactionResult.Aborted(
              FailureReason.NoGuardMatched(sm.currentState, input.key, attemptedCount),
              gasUsed
            ): TransactionResult

          case other =>
            TransactionResult.Aborted(
              FailureReason.FiberInputMismatch(other.fiberId, FiberKind.Script, InputKind.Transition),
              gasUsed
            ): TransactionResult
        }

      private def processStateMachineSuccess(
        sm:            Records.StateMachineFiberRecord,
        input:         FiberInput,
        newStateData:  JsonLogicValue,
        newStateId:    Option[StateId],
        triggers:      List[FiberTrigger],
        spawns:        List[SpawnDirective],
        emittedEvents: List[EmittedEvent]
      ): FiberT[F, TransactionResult] =
        for {
          hash    <- newStateData.computeDigest.liftFiber
          gasUsed <- ExecutionOps.getGasUsed[FiberT[F, *]]

          receipt = EventReceipt.success(
            sm = sm,
            eventName = input.key,
            ordinal = ordinal,
            gasUsed = gasUsed,
            newStateId = newStateId,
            triggers = triggers,
            emittedEvents = emittedEvents
          )

          _ <- ExecutionOps.appendLog[FiberT[F, *]](receipt)

          updatedFiber = sm.copy(
            previousUpdateOrdinal = sm.latestUpdateOrdinal,
            latestUpdateOrdinal = ordinal,
            currentState = newStateId.getOrElse(sm.currentState),
            stateData = newStateData,
            stateDataHash = hash,
            sequenceNumber = sm.sequenceNumber.next,
            lastReceipt = Some(receipt)
          )

          spawnResult <- processSpawnsValidated(spawns, updatedFiber, input)

          result <- spawnResult match {
            case Left(errors) =>
              for {
                currentGas <- ExecutionOps.getGasUsed[FiberT[F, *]]
              } yield TransactionResult.Aborted(errors.head, currentGas): TransactionResult

            case Right(spawnedFibers) =>
              completeStateMachineTransaction(sm, updatedFiber, spawnedFibers, triggers)
          }
        } yield result

      private def processSpawnsValidated(
        spawns:       List[SpawnDirective],
        updatedFiber: Records.StateMachineFiberRecord,
        input:        FiberInput
      ): FiberT[F, Either[NonEmptyList[FailureReason], List[Records.StateMachineFiberRecord]]] =
        spawns.isEmpty
          .pure[FiberT[F, *]]
          .ifM(
            ifTrue = List
              .empty[Records.StateMachineFiberRecord]
              .asRight[NonEmptyList[FailureReason]]
              .pureFiber[F],
            ifFalse = {
              val processor = SpawnProcessor.make[F, FiberT[F, *]]
              for {
                currentOrdinal <- ExecutionOps.askOrdinal[FiberT[F, *]]
                snapshotHash   <- ExecutionOps.askSnapshotHash[FiberT[F, *]]
                epochProgress  <- ExecutionOps.askEpochProgress[FiberT[F, *]]
                contextData <- ContextProvider
                  .make[F](calculatedState, currentOrdinal, snapshotHash, epochProgress)
                  .buildTriggerContext(updatedFiber, input)
                  .liftFiber
                knownFibers = calculatedState.stateMachines.keySet ++ calculatedState.scripts.keySet
                result <- processor.processSpawnsValidated(spawns, updatedFiber, contextData, knownFibers)
              } yield result
            }
          )

      private def completeStateMachineTransaction(
        originalFiber: Records.StateMachineFiberRecord,
        updatedFiber:  Records.StateMachineFiberRecord,
        spawnedFibers: List[Records.StateMachineFiberRecord],
        triggers:      List[FiberTrigger]
      ): FiberT[F, TransactionResult] = {
        val parentWithChildren = updatedFiber.copy(
          childFiberIds = updatedFiber.childFiberIds ++ spawnedFibers.map(_.fiberId)
        )

        val stateWithSpawns = spawnedFibers.foldLeft(
          calculatedState.updateFiber(parentWithChildren)
        ) { case (state, child) =>
          state.updateFiber(child)
        }

        triggers.isEmpty
          .pure[FiberT[F, *]]
          .ifM(
            ifTrue = commitWithoutTriggers(originalFiber.fiberId, parentWithChildren, spawnedFibers),
            ifFalse = dispatchTriggers(
              originalFiber.fiberId,
              spawnedFibers,
              triggers,
              stateWithSpawns
            )
          )
      }

      private def commitWithoutTriggers(
        primaryFiberId: UUID,
        updatedFiber:   Records.StateMachineFiberRecord,
        spawnedFibers:  List[Records.StateMachineFiberRecord]
      ): FiberT[F, TransactionResult] =
        for {
          gasUsed    <- ExecutionOps.getGasUsed[FiberT[F, *]]
          depth      <- ExecutionOps.getDepth[FiberT[F, *]]
          logEntries <- ExecutionOps.getLogs[FiberT[F, *]]
        } yield {
          val allMachines = Map(primaryFiberId -> updatedFiber) ++ spawnedFibers.map(f => f.fiberId -> f).toMap
          TransactionResult.Committed(
            updatedStateMachines = allMachines,
            updatedOracles = Map.empty,
            logEntries = logEntries.toList,
            totalGasUsed = gasUsed,
            maxDepth = depth
          ): TransactionResult
        }

      private def dispatchTriggers(
        primaryFiberId:  UUID,
        spawnedFibers:   List[Records.StateMachineFiberRecord],
        triggers:        List[FiberTrigger],
        stateWithSpawns: CalculatedState
      ): FiberT[F, TransactionResult] =
        TriggerDispatcher
          .make[F, FiberT[F, *]]
          .dispatch(triggers, stateWithSpawns)
          .flatMap {
            case TransactionResult.Committed(machines, oracles, _, totalGas, maxDepth, opCount) =>
              ExecutionOps.getLogs[FiberT[F, *]].map { logs =>
                val allMachines = spawnedFibers.map(f => f.fiberId -> f).toMap ++ machines
                TransactionResult.Committed(
                  updatedStateMachines = allMachines,
                  updatedOracles = oracles,
                  logEntries = logs.toList,
                  totalGasUsed = totalGas,
                  maxDepth = maxDepth,
                  operationCount = opCount
                ): TransactionResult
              }

            case aborted: TransactionResult.Aborted =>
              (aborted: TransactionResult).pureFiber[F]
          }

      private def processOracleSuccess(
        oracle:       Records.ScriptFiberRecord,
        input:        FiberInput,
        newStateData: JsonLogicValue,
        returnValue:  Option[JsonLogicValue]
      ): FiberT[F, TransactionResult] =
        for {
          gasUsed <- ExecutionOps.getGasUsed[FiberT[F, *]]
          depth   <- ExecutionOps.getDepth[FiberT[F, *]]

          newHash <- newStateData.some.traverse(_.computeDigest).liftFiber

          (method, args, caller) <- input match {
            case FiberInput.MethodCall(m, a, c) =>
              (m, a, c).pureFiber[F]
            case FiberInput.Transition(et, _) =>
              Async[F]
                .raiseError[(String, JsonLogicValue, Address)](
                  new RuntimeException(
                    s"Oracle ${oracle.fiberId} received Transition input (event: ${et}). Oracles only support MethodCall input."
                  )
                )
                .liftFiber
          }

          invocation = OracleInvocation(
            fiberId = oracle.fiberId,
            method = method,
            args = args,
            result = returnValue.getOrElse(NullValue),
            gasUsed = gasUsed,
            invokedAt = ordinal,
            invokedBy = caller
          )

          _          <- ExecutionOps.appendLog[FiberT[F, *]](invocation)
          logEntries <- ExecutionOps.getLogs[FiberT[F, *]]

          updatedOracle = oracle.copy(
            stateData = Some(newStateData),
            stateDataHash = newHash,
            latestUpdateOrdinal = ordinal,
            sequenceNumber = oracle.sequenceNumber.next,
            lastInvocation = Some(invocation)
          )
        } yield TransactionResult.Committed(
          updatedStateMachines = Map.empty,
          updatedOracles = Map(oracle.fiberId -> updatedOracle),
          logEntries = logEntries.toList,
          totalGasUsed = gasUsed,
          maxDepth = depth
        )
    }
  }
}
