package xyz.kd5ujc.schema.fiber

/**
 * Execution limits (immutable configuration).
 *
 * @param maxDepth          Maximum trigger chain depth
 * @param maxGas            Maximum gas for entire transaction
 * @param maxStateSizeBytes Maximum size of resulting state after effect execution
 * @param maxAssetMutations Maximum number of fiber-held `_transferAsset` mutations applied per transition
 *                          (asset-model.md §9, R20). Bounds the asset return channel INDEPENDENTLY of
 *                          `maxDepth`: cascade cycle-detection keys on `(fiberId, eventName)`, so re-entering
 *                          the same asset-holding fiber with a different event is not a cycle and `maxDepth`
 *                          alone would permit ~10 mutations; this cap is the explicit bound.
 * @param maxActiveDependencies Maximum number of ACTIVE dynamic dependencies a fiber may hold. Bounds the
 *                          size of the `machines` context built each transition (each active dep is an
 *                          O(state-size) summary lookup), so it is the primary anti-DoS cap.
 * @param maxDependencyLedger Maximum number of DISTINCT fibers in the append-only dynamic-dependency ledger
 *                          (active + inactive). Because the ledger is never pruned (deactivation only flips a
 *                          flag), this bounds its growth; once full, no NEW dependency may be added.
 */
final case class ExecutionLimits(
  maxDepth:              Int = 10,
  maxGas:                Long = 10_000_000L,
  maxStateSizeBytes:     Int = 1_048_576, // 1MB
  maxAssetMutations:     Int = 32,
  maxActiveDependencies: Int = 64,
  maxDependencyLedger:   Int = 256
)
