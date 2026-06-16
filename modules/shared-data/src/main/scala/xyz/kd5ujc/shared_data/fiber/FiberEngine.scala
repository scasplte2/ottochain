package xyz.kd5ujc.shared_data.fiber

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue, MapValue, NullValue}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.fiber.FiberLogEntry.{EventReceipt, ScriptInvocation, UpgradeReceipt}
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry.SchemaBinding
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
 * 3. Evaluate fiber (guards/effects for SM, script for Script)
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

  /**
   * Upgrade a state machine fiber to `newDefinition`/`newBinding`, optionally transforming its state via
   * `migration`. Runs through the same metered `FiberT` boundary as [[process]] (no direct metakit call):
   * the migration is evaluated via [[xyz.kd5ujc.shared_data.fiber.core.MeteredEvaluator]] with gas charged
   * to `ExecutionState`. Preserves the current state id (which must exist in `newDefinition`).
   */
  def migrate(
    fiberId:       UUID,
    newDefinition: StateMachineDefinition,
    newBinding:    SchemaBinding,
    migration:     Option[JsonLogicExpression]
  ): F[TransactionResult]

  /**
   * Upgrade a script fiber to `newProgram`/`newBinding`, optionally transforming its stateData via
   * `migration`. Metered through the same `FiberT` boundary. Scripts have no state-machine state IDs, so
   * no current-state-in-new-program check is performed.
   */
  def migrateScript(
    fiberId:    UUID,
    newProgram: JsonLogicExpression,
    newBinding: SchemaBinding,
    migration:  Option[JsonLogicExpression]
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

      def migrate(
        fiberId:       UUID,
        newDefinition: StateMachineDefinition,
        newBinding:    SchemaBinding,
        migration:     Option[JsonLogicExpression]
      ): F[TransactionResult] =
        migrateInternal(fiberId, newDefinition, newBinding, migration)
          .run(FiberContext(ordinal, lastSnapshotHash, epochProgress, limits, gasConfig, fiberGasConfig))
          .runA(ExecutionState.initial)

      def migrateScript(
        fiberId:    UUID,
        newProgram: JsonLogicExpression,
        newBinding: SchemaBinding,
        migration:  Option[JsonLogicExpression]
      ): F[TransactionResult] =
        migrateScriptInternal(fiberId, newProgram, newBinding, migration)
          .run(FiberContext(ordinal, lastSnapshotHash, epochProgress, limits, gasConfig, fiberGasConfig))
          .runA(ExecutionState.initial)

      private def migrateInternal(
        fiberId:       UUID,
        newDefinition: StateMachineDefinition,
        newBinding:    SchemaBinding,
        migration:     Option[JsonLogicExpression]
      ): FiberT[F, TransactionResult] =
        calculatedState.getFiber(fiberId) match {
          case None =>
            abortWithReason(FailureReason.FiberNotFound(fiberId))

          case Some(fiber) if fiber.status != FiberStatus.Active =>
            abortWithReason(FailureReason.FiberNotActive(fiberId, fiber.status.toString))

          case Some(sm: Records.StateMachineFiberRecord) =>
            migrateStateMachine(sm, newDefinition, newBinding, migration)

          case Some(other) =>
            abortWithReason(FailureReason.FiberInputMismatch(other.fiberId, FiberKind.Script, InputKind.Transition))
        }

      private def migrateScriptInternal(
        fiberId:    UUID,
        newProgram: JsonLogicExpression,
        newBinding: SchemaBinding,
        migration:  Option[JsonLogicExpression]
      ): FiberT[F, TransactionResult] =
        calculatedState.getFiber(fiberId) match {
          case None =>
            abortWithReason(FailureReason.FiberNotFound(fiberId))

          case Some(fiber) if fiber.status != FiberStatus.Active =>
            abortWithReason(FailureReason.FiberNotActive(fiberId, fiber.status.toString))

          case Some(script: Records.ScriptFiberRecord) =>
            migrateScriptFiber(script, newProgram, newBinding, migration)

          case Some(other) =>
            abortWithReason(
              FailureReason.FiberInputMismatch(other.fiberId, FiberKind.StateMachine, InputKind.MethodCall)
            )
        }

      private def migrateScriptFiber(
        script:     Records.ScriptFiberRecord,
        newProgram: JsonLogicExpression,
        newBinding: SchemaBinding,
        migration:  Option[JsonLogicExpression]
      ): FiberT[F, TransactionResult] = {
        val currentState = script.stateData.getOrElse(NullValue)
        val evalMigration: FiberT[F, Either[FailureReason, JsonLogicValue]] =
          migration match {
            case None       => (currentState: JsonLogicValue).asRight[FailureReason].pure[FiberT[F, *]]
            case Some(expr) => MeteredEvaluator.eval[F, FiberT[F, *]](expr, currentState, GasExhaustionPhase.Migration)
          }

        evalMigration.flatMap {
          case Left(reason)     => aborted(reason)
          case Right(NullValue) =>
            // Migration produced null: clear the state
            for {
              gasUsed <- ExecutionOps.getGasUsed[FiberT[F, *]]
              receipt = UpgradeReceipt(
                fiberId = script.fiberId,
                ordinal = ordinal,
                fromBinding = script.schemaBinding,
                toBinding = newBinding,
                gasUsed = gasUsed,
                migrated = migration.isDefined
              )
              _ <- ExecutionOps.appendLog[FiberT[F, *]](receipt)
              updated = script.copy(
                latestUpdateOrdinal = ordinal,
                scriptProgram = newProgram,
                stateData = None,
                stateDataHash = None,
                sequenceNumber = script.sequenceNumber.next,
                schemaBinding = Some(newBinding)
              )
              logs <- ExecutionOps.getLogs[FiberT[F, *]]
            } yield TransactionResult.Committed(
              updatedStateMachines = Map.empty,
              updatedScripts = Map(script.fiberId -> updated),
              logEntries = logs.toList,
              totalGasUsed = gasUsed
            ): TransactionResult
          case Right(newState) =>
            for {
              hash    <- newState.computeDigest.liftFiber
              gasUsed <- ExecutionOps.getGasUsed[FiberT[F, *]]
              receipt = UpgradeReceipt(
                fiberId = script.fiberId,
                ordinal = ordinal,
                fromBinding = script.schemaBinding,
                toBinding = newBinding,
                gasUsed = gasUsed,
                migrated = migration.isDefined
              )
              _ <- ExecutionOps.appendLog[FiberT[F, *]](receipt)
              updated = script.copy(
                latestUpdateOrdinal = ordinal,
                scriptProgram = newProgram,
                stateData = Some(newState),
                stateDataHash = Some(hash),
                sequenceNumber = script.sequenceNumber.next,
                schemaBinding = Some(newBinding)
              )
              logs <- ExecutionOps.getLogs[FiberT[F, *]]
            } yield TransactionResult.Committed(
              updatedStateMachines = Map.empty,
              updatedScripts = Map(script.fiberId -> updated),
              logEntries = logs.toList,
              totalGasUsed = gasUsed
            ): TransactionResult
        }
      }

      private def aborted(reason: FailureReason): FiberT[F, TransactionResult] =
        ExecutionOps.getGasUsed[FiberT[F, *]].map(TransactionResult.Aborted(reason, _): TransactionResult)

      private def migrateStateMachine(
        sm:            Records.StateMachineFiberRecord,
        newDefinition: StateMachineDefinition,
        newBinding:    SchemaBinding,
        migration:     Option[JsonLogicExpression]
      ): FiberT[F, TransactionResult] = {
        // The migration is metered through the same boundary as every other JLVM evaluation (no direct
        // metakit call); identity (None) leaves the state untouched.
        val evalMigration: FiberT[F, Either[FailureReason, JsonLogicValue]] =
          migration match {
            case None       => sm.stateData.asRight[FailureReason].pure[FiberT[F, *]]
            case Some(expr) => MeteredEvaluator.eval[F, FiberT[F, *]](expr, sm.stateData, GasExhaustionPhase.Migration)
          }

        evalMigration.flatMap {
          case Left(reason) => aborted(reason)

          case Right(migrated) =>
            (migrated, newDefinition.states.contains(sm.currentState)) match {
              case (m: MapValue, true)
                  if ConformanceChecker.violationsFor(Some(newBinding), calculatedState, m).isEmpty =>
                for {
                  hash    <- (m: JsonLogicValue).computeDigest.liftFiber
                  gasUsed <- ExecutionOps.getGasUsed[FiberT[F, *]]
                  receipt = UpgradeReceipt(
                    fiberId = sm.fiberId,
                    ordinal = ordinal,
                    fromBinding = sm.schemaBinding,
                    toBinding = newBinding,
                    gasUsed = gasUsed,
                    migrated = migration.isDefined
                  )
                  _ <- ExecutionOps.appendLog[FiberT[F, *]](receipt)
                  updated = sm.copy(
                    previousUpdateOrdinal = sm.latestUpdateOrdinal,
                    latestUpdateOrdinal = ordinal,
                    definition = newDefinition,
                    stateData = m,
                    stateDataHash = hash,
                    sequenceNumber = sm.sequenceNumber.next,
                    schemaBinding = Some(newBinding)
                  )
                  logs <- ExecutionOps.getLogs[FiberT[F, *]]
                } yield TransactionResult.Committed(
                  updatedStateMachines = Map(sm.fiberId -> updated),
                  updatedScripts = Map.empty,
                  logEntries = logs.toList,
                  totalGasUsed = gasUsed
                ): TransactionResult

              case (m: MapValue, true) =>
                aborted(
                  FailureReason.ValidationFailed(
                    s"conformance violation: ${ConformanceChecker.violationsFor(Some(newBinding), calculatedState, m).mkString("; ")}",
                    ordinal
                  )
                )

              case (_: MapValue, false) =>
                aborted(
                  FailureReason.ValidationFailed(
                    s"current state ${sm.currentState.value} is not present in the new definition",
                    ordinal
                  )
                )

              case _ =>
                aborted(FailureReason.ValidationFailed("migration did not produce a map state", ordinal))
            }
        }
      }

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
            case FiberResult.Success(
                  newStateData,
                  newStateId,
                  fiberTriggers,
                  spawns,
                  returnValue,
                  emittedEvents,
                  assetTransfers
                ) =>
              fiber match {
                case sm: Records.StateMachineFiberRecord =>
                  processStateMachineSuccess(
                    sm,
                    input,
                    newStateData,
                    newStateId,
                    fiberTriggers,
                    spawns,
                    emittedEvents,
                    assetTransfers
                  )

                case script: Records.ScriptFiberRecord =>
                  // Scripts have no _transferAsset channel (assetTransfers is always empty here).
                  processScriptSuccess(script, input, newStateData, returnValue)
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
        sm:             Records.StateMachineFiberRecord,
        input:          FiberInput,
        newStateData:   JsonLogicValue,
        newStateId:     Option[StateId],
        triggers:       List[FiberTrigger],
        spawns:         List[SpawnDirective],
        emittedEvents:  List[EmittedEvent],
        assetTransfers: List[FiberEffect.AssetTransferred]
      ): FiberT[F, TransactionResult] =
        // #33 runtime conformance gate: a strict-bound fiber's produced state must conform, else abort.
        ConformanceChecker.violationsFor(sm.schemaBinding, calculatedState, newStateData) match {
          case violations if violations.nonEmpty =>
            aborted(FailureReason.ValidationFailed(s"conformance violation: ${violations.mkString("; ")}", ordinal))
          case _ =>
            commitStateMachineSuccess(
              sm,
              input,
              newStateData,
              newStateId,
              triggers,
              spawns,
              emittedEvents,
              assetTransfers
            )
        }

      private def commitStateMachineSuccess(
        sm:             Records.StateMachineFiberRecord,
        input:          FiberInput,
        newStateData:   JsonLogicValue,
        newStateId:     Option[StateId],
        triggers:       List[FiberTrigger],
        spawns:         List[SpawnDirective],
        emittedEvents:  List[EmittedEvent],
        assetTransfers: List[FiberEffect.AssetTransferred]
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
              completeStateMachineTransaction(sm, updatedFiber, spawnedFibers, triggers, assetTransfers)
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
        originalFiber:  Records.StateMachineFiberRecord,
        updatedFiber:   Records.StateMachineFiberRecord,
        spawnedFibers:  List[Records.StateMachineFiberRecord],
        triggers:       List[FiberTrigger],
        assetTransfers: List[FiberEffect.AssetTransferred]
      ): FiberT[F, TransactionResult] = {
        val parentWithChildren = updatedFiber.copy(
          childFiberIds = updatedFiber.childFiberIds ++ spawnedFibers.map(_.fiberId)
        )

        val stateWithSpawns = spawnedFibers.foldLeft(
          calculatedState.updateFiber(parentWithChildren)
        ) { case (state, child) =>
          state.updateFiber(child)
        }

        // The primary fiber's _transferAsset effects, keyed by the EMITTING fiber id (the holder-defense key
        // in the combiner — R1). Cascading triggers contribute their own per-fiber maps via dispatchTriggers.
        val primaryAssetTransfers: Map[UUID, List[FiberEffect.AssetTransferred]] =
          if (assetTransfers.isEmpty) Map.empty
          else Map(originalFiber.fiberId -> assetTransfers)

        triggers.isEmpty
          .pure[FiberT[F, *]]
          .ifM(
            ifTrue =
              commitWithoutTriggers(originalFiber.fiberId, parentWithChildren, spawnedFibers, primaryAssetTransfers),
            ifFalse = dispatchTriggers(
              originalFiber.fiberId,
              spawnedFibers,
              triggers,
              stateWithSpawns,
              primaryAssetTransfers
            )
          )
      }

      private def commitWithoutTriggers(
        primaryFiberId: UUID,
        updatedFiber:   Records.StateMachineFiberRecord,
        spawnedFibers:  List[Records.StateMachineFiberRecord],
        assetTransfers: Map[UUID, List[FiberEffect.AssetTransferred]]
      ): FiberT[F, TransactionResult] =
        for {
          gasUsed    <- ExecutionOps.getGasUsed[FiberT[F, *]]
          depth      <- ExecutionOps.getDepth[FiberT[F, *]]
          logEntries <- ExecutionOps.getLogs[FiberT[F, *]]
        } yield {
          val allMachines = Map(primaryFiberId -> updatedFiber) ++ spawnedFibers.map(f => f.fiberId -> f).toMap
          TransactionResult.Committed(
            updatedStateMachines = allMachines,
            updatedScripts = Map.empty,
            logEntries = logEntries.toList,
            totalGasUsed = gasUsed,
            maxDepth = depth,
            assetTransfers = assetTransfers
          ): TransactionResult
        }

      private def dispatchTriggers(
        primaryFiberId:        UUID,
        spawnedFibers:         List[Records.StateMachineFiberRecord],
        triggers:              List[FiberTrigger],
        stateWithSpawns:       CalculatedState,
        primaryAssetTransfers: Map[UUID, List[FiberEffect.AssetTransferred]]
      ): FiberT[F, TransactionResult] = {
        val _ = primaryFiberId
        TriggerDispatcher
          .make[F, FiberT[F, *]]
          .dispatch(triggers, stateWithSpawns)
          .flatMap {
            case TransactionResult.Committed(machines, scripts, _, totalGas, maxDepth, opCount, cascadeTransfers) =>
              ExecutionOps.getLogs[FiberT[F, *]].map { logs =>
                val allMachines = spawnedFibers.map(f => f.fiberId -> f).toMap ++ machines
                // Merge the primary fiber's transfers with the cascade's per-fiber maps. The keys are the
                // EMITTING fiber ids; a fiber that emits in both its own transition and a re-entered cascade
                // gets its lists concatenated (single combiner pass still applies them once each — R20).
                val mergedTransfers = mergeAssetTransfers(primaryAssetTransfers, cascadeTransfers)
                TransactionResult.Committed(
                  updatedStateMachines = allMachines,
                  updatedScripts = scripts,
                  logEntries = logs.toList,
                  totalGasUsed = totalGas,
                  maxDepth = maxDepth,
                  operationCount = opCount,
                  assetTransfers = mergedTransfers
                ): TransactionResult
              }

            case aborted: TransactionResult.Aborted =>
              (aborted: TransactionResult).pureFiber[F]
          }
      }

      /** Merge two emitting-fiber-keyed transfer maps by concatenating per-fiber lists. */
      private def mergeAssetTransfers(
        a: Map[UUID, List[FiberEffect.AssetTransferred]],
        b: Map[UUID, List[FiberEffect.AssetTransferred]]
      ): Map[UUID, List[FiberEffect.AssetTransferred]] =
        b.foldLeft(a) { case (acc, (fid, ts)) =>
          acc.updated(fid, acc.getOrElse(fid, List.empty) ++ ts)
        }

      private def processScriptSuccess(
        script:       Records.ScriptFiberRecord,
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
                    s"Script ${script.fiberId} received Transition input (event: ${et}). Scripts only support MethodCall input."
                  )
                )
                .liftFiber
          }

          invocation = ScriptInvocation(
            fiberId = script.fiberId,
            method = method,
            args = args,
            result = returnValue.getOrElse(NullValue),
            gasUsed = gasUsed,
            invokedAt = ordinal,
            invokedBy = caller
          )

          _          <- ExecutionOps.appendLog[FiberT[F, *]](invocation)
          logEntries <- ExecutionOps.getLogs[FiberT[F, *]]

          updatedScript = script.copy(
            stateData = Some(newStateData),
            stateDataHash = newHash,
            latestUpdateOrdinal = ordinal,
            sequenceNumber = script.sequenceNumber.next,
            lastInvocation = Some(invocation)
          )
        } yield TransactionResult.Committed(
          updatedStateMachines = Map.empty,
          updatedScripts = Map(script.fiberId -> updatedScript),
          logEntries = logEntries.toList,
          totalGasUsed = gasUsed,
          maxDepth = depth
        )
    }
  }
}
