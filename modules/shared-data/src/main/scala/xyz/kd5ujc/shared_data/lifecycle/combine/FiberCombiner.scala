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
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, Updates}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
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
      parentFiberId = update.parentFiberId
    )

    result <- current.withRecord[F](update.fiberId, record)
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
          new RuntimeException(s"Fiber ${update.fiberId} not found")
        )
      )(_.pure[F])
    _ <- Async[F]
      .raiseError(
        new RuntimeException(
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
          new RuntimeException(s"Fiber ${update.fiberId} not found")
        )
      )(_.pure[F])

    // Defense-in-depth: reject stale sequence numbers
    _ <- Async[F]
      .raiseError(
        new RuntimeException(
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

  // ============================================================================
  // Private Helpers
  // ============================================================================

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
        Async[F].raiseError(new RuntimeException(s"Fiber $fiberId not found"))
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
