package xyz.kd5ujc.schema.fiber

import xyz.kd5ujc.schema.CalculatedState

sealed trait TriggerHandlerResult

object TriggerHandlerResult {

  /**
   * Trigger processed successfully.
   *
   * Gas and log entries are tracked via StateT and not carried in the result.
   *
   * @param updatedState    State with the target fiber updated
   * @param cascadeTriggers Additional triggers to process
   * @param assetTransfers  Fiber-held asset custody transfers (`_transferAsset`) emitted by THIS triggered
   *                        transition. Surfaced so the dispatcher can carry them to the combiner keyed by the
   *                        emitting (triggered) fiber id, where the holder defense (R1) is enforced. Scripts
   *                        never emit transfers (always empty).
   */
  final case class Success(
    updatedState:    CalculatedState,
    cascadeTriggers: List[FiberTrigger],
    assetTransfers:  List[FiberEffect.AssetTransferred] = List.empty
  ) extends TriggerHandlerResult

  /**
   * Trigger processing failed.
   *
   * Gas consumed before failure is tracked via StateT.
   *
   * @param reason Why the trigger failed
   */
  final case class Failed(
    reason: FailureReason
  ) extends TriggerHandlerResult
}
