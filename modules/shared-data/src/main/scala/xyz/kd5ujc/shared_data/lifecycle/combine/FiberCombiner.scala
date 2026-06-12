package xyz.kd5ujc.shared_data.lifecycle.combine

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.FiberLogEntry.EventReceipt
import xyz.kd5ujc.schema.fiber.{FiberLogEntry, FiberOrdinal, _}
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.{ConformanceChecker, FiberEngine}
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Combiner operations for state machine fibers.
 *
 * Handles creation, event processing, and archiving of fiber state machines.
 * Uses the syntax extensions for atomic state updates.
 *
 * @param current The current DataState to operate on
 * @param ctx     The L0NodeContext for accessing snapshot ordinals
 */
class FiberCombiner[F[_]: Async: SecurityProvider](
  current:         DataState[OnChain, CalculatedState],
  ctx:             L0NodeContext[F],
  executionLimits: ExecutionLimits
) {

  /**
   * Creates a new state machine fiber from the update.
   *
   * Initializes the fiber record with:
   * - Initial state from definition
   * - Owners from signature proofs
   * - Active status
   */
  def createStateMachineFiber(
    update: Signed[Updates.CreateStateMachine]
  ): CombineResult[F] = for {
    currentOrdinal  <- ctx.getCurrentOrdinal
    owners          <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)
    initialDataHash <- update.initialData.computeDigest
    binding         <- resolveBinding(update.schemaRef, update.definition)

    // #33 runtime conformance gate: if bound to a strict version, the initial state must conform.
    _ <- ConformanceChecker.violationsFor(binding, current.calculated, update.initialData) match {
      case Nil => Async[F].unit
      case violations =>
        Async[F].raiseError[Unit](
          CombineRejected(s"initial state does not conform to the strict schema: ${violations.mkString("; ")}")
        )
    }

    // participants declared in CreateStateMachine become authorized signers for transitions
    authorizedSigners = update.participants.getOrElse(Set.empty)

    record = Records.StateMachineFiberRecord(
      fiberId = update.fiberId,
      creationOrdinal = currentOrdinal,
      previousUpdateOrdinal = currentOrdinal,
      latestUpdateOrdinal = currentOrdinal,
      definition = update.definition,
      currentState = update.definition.initialState,
      stateData = update.initialData,
      stateDataHash = initialDataHash,
      sequenceNumber = FiberOrdinal.MinValue,
      owners = owners,
      status = FiberStatus.Active,
      parentFiberId = update.parentFiberId,
      schemaBinding = binding,
      authorizedSigners = authorizedSigners
    )

    creationReceipt = FiberLogEntry.CreationReceipt(
      fiberId = update.fiberId,
      ordinal = currentOrdinal,
      initialState = update.definition.initialState,
      owners = owners,
      schemaBinding = binding,
      parentFiberId = update.parentFiberId
    )
    result <- current.withRecord[F](update.fiberId, record).map(_.appendLogs(List(creationReceipt)))
  } yield result

  /**
   * Processes a fiber event through the fiber orchestrator.
   *
   * Handles both successful transitions and failures:
   * - Committed: Applies all fiber and oracle updates
   * - Aborted: Records failure receipt on the fiber
   */
  def processFiberEvent(
    update: Signed[Updates.TransitionStateMachine]
  ): CombineResult[F] = for {
    currentOrdinal   <- ctx.getCurrentOrdinal
    lastSnapshotHash <- ctx.getLastSnapshotHash
    epochProgress    <- ctx.getEpochProgress

    // Defense-in-depth: reject stale sequence numbers that slipped through validation
    fiberRecord <- current.calculated.stateMachines
      .get(update.fiberId)
      .fold(
        Async[F].raiseError[Records.StateMachineFiberRecord](
          CombineRejected(s"Fiber ${update.fiberId} not found")
        )
      )(_.pure[F])
    _ <- Async[F]
      .raiseError(
        CombineRejected(
          s"Sequence number mismatch: target=${update.targetSequenceNumber}, actual=${fiberRecord.sequenceNumber}"
        )
      )
      .whenA(fiberRecord.sequenceNumber =!= update.targetSequenceNumber)

    input = FiberInput.Transition(update.eventName, update.payload)
    proofsList = update.proofs.toList

    orchestrator = FiberEngine.make[F](
      calculatedState = current.calculated,
      ordinal = currentOrdinal,
      limits = executionLimits,
      lastSnapshotHash = lastSnapshotHash,
      epochProgress = epochProgress
    )

    outcome <- orchestrator.process(update.fiberId, input, proofsList)

    newState <- outcome match {
      case TransactionResult.Committed(updatedFibers, updatedOracles, logEntries, _, _, _) =>
        handleCommittedOutcome(updatedFibers, updatedOracles, logEntries)

      case TransactionResult.Aborted(reason, gasUsed, _) =>
        handleAbortedOutcome(update.fiberId, update.eventName, reason, gasUsed, currentOrdinal)
    }
  } yield newState

  /**
   * Archives a fiber, setting its status to Archived.
   *
   * Archived fibers cannot process events but remain in state for reference.
   */
  def archiveFiber(
    update: Signed[Updates.ArchiveStateMachine]
  ): CombineResult[F] = for {
    currentOrdinal <- ctx.getCurrentOrdinal

    fiberRecord <- current.calculated.stateMachines
      .get(update.fiberId)
      .collect { case r: Records.StateMachineFiberRecord => r }
      .fold(
        Async[F].raiseError[Records.StateMachineFiberRecord](
          CombineRejected(s"Fiber ${update.fiberId} not found")
        )
      )(_.pure[F])

    // Defense-in-depth: reject stale sequence numbers
    _ <- Async[F]
      .raiseError(
        CombineRejected(
          s"Sequence number mismatch: target=${update.targetSequenceNumber}, actual=${fiberRecord.sequenceNumber}"
        )
      )
      .whenA(fiberRecord.sequenceNumber =!= update.targetSequenceNumber)

    updatedFiber = fiberRecord.copy(
      previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
      latestUpdateOrdinal = currentOrdinal,
      status = FiberStatus.Archived
    )

    result <- current.withRecord[F](update.fiberId, updatedFiber)
  } yield result

  /**
   * Upgrades a fiber to a new registered version of the same package (#27): re-binds (verifying the new
   * definition's hash against the target version), migrates state through the engine's metered evaluator,
   * and emits an UpgradeReceipt. Aborts (raises) if the fiber is unbound, the target is a different
   * package, the target/hash does not resolve, or the sequence number is stale.
   */
  def upgradeFiber(
    update: Signed[Updates.UpgradeFiber]
  ): CombineResult[F] = for {
    currentOrdinal   <- ctx.getCurrentOrdinal
    lastSnapshotHash <- ctx.getLastSnapshotHash
    epochProgress    <- ctx.getEpochProgress

    fiberRecord <- current.calculated.stateMachines
      .get(update.fiberId)
      .fold(
        Async[F].raiseError[Records.StateMachineFiberRecord](
          CombineRejected(s"Fiber ${update.fiberId} not found")
        )
      )(_.pure[F])

    _ <- Async[F]
      .raiseError(
        CombineRejected(
          s"Sequence number mismatch: target=${update.targetSequenceNumber}, actual=${fiberRecord.sequenceNumber}"
        )
      )
      .whenA(fiberRecord.sequenceNumber =!= update.targetSequenceNumber)

    // Must currently be bound, and the upgrade must target the SAME package (no cross-package switch).
    _ <- fiberRecord.schemaBinding match {
      case Some(b) if b.name === update.targetRef.name => Async[F].unit
      case Some(b) =>
        Async[F].raiseError[Unit](
          CombineRejected(
            s"cannot upgrade ${b.name.render} fiber to a different package ${update.targetRef.name.render}"
          )
        )
      case None =>
        Async[F].raiseError[Unit](CombineRejected(s"fiber ${update.fiberId} has no binding to upgrade"))
    }

    // Resolve the target version and verify the new definition's hash (verified re-bind, reuses resolveBinding).
    maybeBinding <- resolveBinding(Some(update.targetRef), update.newDefinition)
    newBinding <- maybeBinding.fold(
      Async[F].raiseError[SchemaBinding](
        CombineRejected(s"upgrade target ${update.targetRef.name.render} did not resolve")
      )
    )(_.pure[F])

    // Monotonic upgrade: a fiber's bound version may only ADVANCE — no downgrade. To change a published
    // interface again, publish a NEW HIGHER version (a "revert" is just a higher version that restores prior
    // behavior). This keeps every fiber's version history a forward-only chain.
    _ <- fiberRecord.schemaBinding match {
      case Some(b) if SemVer.ordering.gt(newBinding.version, b.version) => Async[F].unit
      case Some(b) =>
        Async[F].raiseError[Unit](
          CombineRejected(
            s"cannot downgrade ${b.name.render} fiber from ${b.version.render} to ${newBinding.version.render}; " +
            s"publish a higher version to revert"
          )
        )
      case None => Async[F].unit // unreachable: binding presence is checked above
    }

    orchestrator = FiberEngine.make[F](
      calculatedState = current.calculated,
      ordinal = currentOrdinal,
      limits = executionLimits,
      lastSnapshotHash = lastSnapshotHash,
      epochProgress = epochProgress
    )

    outcome <- orchestrator.migrate(update.fiberId, update.newDefinition, newBinding, update.migration)

    newState <- outcome match {
      case TransactionResult.Committed(updatedFibers, updatedOracles, logEntries, _, _, _) =>
        handleCommittedOutcome(updatedFibers, updatedOracles, logEntries)

      case TransactionResult.Aborted(reason, gasUsed, _) =>
        handleAbortedOutcome(update.fiberId, "__upgrade__", reason, gasUsed, currentOrdinal)
    }
  } yield newState

  // ============================================================================
  // Private Helpers
  // ============================================================================

  /**
   * Resolve an optional schema reference against the current registry, returning the pinned binding.
   * Aborts (raises) if the referenced name/version cannot be resolved OR if the fiber's `definition` does
   * not hash to the registered `logicHash` (#37 VERIFIED binding). The RegistryRules.L0 preview mirrors
   * both checks for early, structured rejection at validation.
   */
  private def resolveBinding(
    ref:        Option[SchemaRef],
    definition: StateMachineDefinition
  ): F[Option[SchemaBinding]] =
    ref match {
      case None => none[SchemaBinding].pure[F]
      case Some(SchemaRef(name, versionReq)) =>
        current.calculated.registry
          .get(name)
          .map(_.target)
          .collect { case RegistryTarget.SchemaPackage(l) => l } match {
          case None =>
            Async[F].raiseError[Option[SchemaBinding]](
              CombineRejected(s"schemaRef refers to unknown registry name ${name.render}")
            )
          case Some(lineage) =>
            lineage
              .resolve(versionReq)
              .fold(
                e =>
                  Async[F].raiseError[Option[SchemaBinding]](
                    CombineRejected(s"schemaRef unresolvable for ${name.render}: $e")
                  ),
                rv =>
                  definition.computeDigest.flatMap { digest =>
                    if (digest === rv.logicHash)
                      SchemaBinding(name, rv.version, rv.schemaHash, rv.logicHash).some.pure[F]
                    else
                      Async[F].raiseError[Option[SchemaBinding]](
                        CombineRejected(
                          s"schemaRef logic mismatch for ${name.render}@${rv.version.render}: definition hash " +
                          s"${digest.value} != registered logicHash ${rv.logicHash.value}"
                        )
                      )
                  }
              )
        }
    }

  /**
   * Handles a committed transaction outcome.
   *
   * Applies fiber/oracle record updates, then appends log entries to OnChain.latestLogs.
   */
  private def handleCommittedOutcome(
    updatedFibers:  Map[UUID, Records.StateMachineFiberRecord],
    updatedOracles: Map[UUID, Records.ScriptFiberRecord],
    logEntries:     List[FiberLogEntry]
  ): F[DataState[OnChain, CalculatedState]] =
    current.withFibersAndOracles[F](updatedFibers, updatedOracles).map(_.appendLogs(logEntries))

  /**
   * Handles an aborted transaction outcome.
   *
   * Builds a failure EventReceipt and records it on the fiber.
   */
  private def handleAbortedOutcome(
    fiberId:        UUID,
    eventName:      String,
    reason:         FailureReason,
    gasUsed:        Long,
    currentOrdinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    current.calculated.stateMachines.get(fiberId) match {
      case Some(fiberRecord) =>
        val failureReceipt = EventReceipt.failure(
          sm = fiberRecord,
          eventName = eventName,
          ordinal = currentOrdinal,
          gasUsed = gasUsed,
          reason = reason
        )

        val failedFiber = fiberRecord.copy(
          previousUpdateOrdinal = fiberRecord.latestUpdateOrdinal,
          latestUpdateOrdinal = currentOrdinal,
          lastReceipt = Some(failureReceipt)
        )

        current.withRecord[F](fiberId, failedFiber).map(_.appendLogs(List(failureReceipt)))

      case None =>
        Async[F].raiseError(CombineRejected(s"Fiber $fiberId not found"))
    }
}

object FiberCombiner {

  /**
   * Creates a new FiberCombiner instance.
   */
  def apply[F[_]: Async: SecurityProvider](
    current:         DataState[OnChain, CalculatedState],
    ctx:             L0NodeContext[F],
    executionLimits: ExecutionLimits
  ): FiberCombiner[F] =
    new FiberCombiner[F](current, ctx, executionLimits)
}
