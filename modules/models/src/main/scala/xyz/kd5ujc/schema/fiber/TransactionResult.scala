package xyz.kd5ujc.schema.fiber

import java.util.UUID

import xyz.kd5ujc.schema.Records

/**
 * Final outcome of a complete transaction (including all cascading triggers).
 */
sealed trait TransactionResult

object TransactionResult {

  /**
   * Transaction committed successfully.
   * All state changes should be persisted.
   *
   * @param updatedStateMachines State machines modified during transaction
   * @param updatedScripts Scripts modified during transaction
   * @param logEntries Log entries (receipts + invocations) for each fiber touched during transaction
   * @param totalGasUsed Total gas consumed by all operations
   * @param maxDepth Maximum trigger chain depth reached
   * @param operationCount Total number of JsonLogic operations executed
   * @param assetTransfers Fiber-held asset custody transfers keyed by the EMITTING fiber id — the
   *                       `_transferAsset` return channel (asset-model.md §9/§10, R2). The combiner
   *                       (`AssetCombiner.applyFiberTransfer`) enforces the holder-ownership defense (R1)
   *                       against the emitting fiber before applying any of these. Cascading triggers merge
   *                       their own maps (`dispatchTriggers`). `= Map.empty` default is safe: `TransactionResult`
   *                       is in-process engine data, never a signed canonical.
   */
  final case class Committed(
    updatedStateMachines: Map[UUID, Records.StateMachineFiberRecord],
    updatedScripts:       Map[UUID, Records.ScriptFiberRecord],
    logEntries:           List[FiberLogEntry],
    totalGasUsed:         Long,
    maxDepth:             Int = 0,
    operationCount:       Long = 0L,
    assetTransfers:       Map[UUID, List[FiberEffect.AssetTransferred]] = Map.empty
  ) extends TransactionResult

  /**
   * Transaction aborted.
   * No state changes should be persisted.
   *
   * @param reason Why the transaction was aborted
   * @param gasUsed Gas consumed before abort
   * @param depth Trigger chain depth when aborted
   */
  final case class Aborted(
    reason:  FailureReason,
    gasUsed: Long,
    depth:   Int = 0
  ) extends TransactionResult
}
