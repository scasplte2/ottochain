package xyz.kd5ujc.schema.fiber

import cats.Applicative

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue

sealed trait FiberResult

object FiberResult {

  implicit class FailureReasonOps(private val reason: FailureReason) extends AnyVal {
    def pureOutcome[F[_]: Applicative]: F[FiberResult] = Applicative[F].pure(Failed(reason))
    def asOutcome: FiberResult = Failed(reason)
  }

  /**
   * Successful fiber evaluation.
   *
   * Gas is tracked via StateT (ExecutionState) — not carried in this result.
   *
   * @param newStateData Updated state data
   * @param newStateId New state ID (Some for state machines, None for scripts)
   * @param triggers Triggered events for other fibers
   * @param spawns Child fibers to create (state machines only)
   * @param returnValue Return value (Some for scripts, None for state machines)
   * @param emittedEvents User-defined events emitted for external consumption
   * @param assetTransfers Fiber-held asset custody transfers (`_transferAsset`, asset-model.md §10). A safe
   *                       `= List.empty` default is fine ONLY because `FiberResult` is an in-process engine
   *                       type, never a signed canonical (signing-canonical invariant #1 governs signed
   *                       messages only). The combiner re-checks holder-ownership before applying any of these.
   */
  final case class Success(
    newStateData:        JsonLogicValue,
    newStateId:          Option[StateId],
    triggers:            List[FiberTrigger],
    spawns:              List[SpawnDirective],
    returnValue:         Option[JsonLogicValue],
    emittedEvents:       List[EmittedEvent] = List.empty,
    assetTransfers:      List[FiberEffect.AssetTransferred] = List.empty,
    dependencyMutations: List[FiberEffect.DependencyMutated] = List.empty,
    // `_consumeNullifier` consumptions (protocol-nullifier-set.md). Same in-process-only default rationale
    // as assetTransfers; the combiner (NullifierCombiner) is the sole enforcement site.
    nullifierConsumptions: List[FiberEffect.NullifierConsumed] = List.empty
  ) extends FiberResult

  /**
   * No guard matched (state machines only).
   *
   * Gas consumed during guard evaluation is tracked via StateT (ExecutionState).
   *
   * @param attemptedCount Number of guards evaluated before giving up
   */
  final case class GuardFailed(attemptedCount: Int) extends FiberResult

  /** Evaluation failed with reason */
  final case class Failed(reason: FailureReason) extends FiberResult
}
