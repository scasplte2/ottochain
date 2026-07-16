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
 * @param maxSpawnsPerTransition Maximum number of `_spawn` directives a single PRIMARY transition may emit
 *                          (engine-default-fixes Fix 3). Bounds storage-amplification DoS: each spawn inflates
 *                          `calculatedState.stateMachines`, and gas on cheap `initialData` is too weak a
 *                          backstop to stop a fiber minting hundreds of children in one transition. Enforced
 *                          fail-closed in `SpawnValidator.validateBatchConstraints`: an over-limit batch aborts
 *                          the whole transition (total discard) BEFORE any child record is constructed and
 *                          before per-spawn `initialData` gas is burned. Cascaded transitions ignore `_spawn`,
 *                          so the primary path is the only enforcement site needed.
 *
 *                          CONSENSUS-CONSTANT: this value decides abort-vs-commit, so every validator MUST use
 *                          the identical default or the chain forks. Ship as this hard-coded chain constant
 *                          (the same source-of-truth pattern `maxAssetMutations` etc. use), NOT per-operator
 *                          config. Value chosen: 16 (greenfield — no live pinned-constitution fibers to brick;
 *                          ordered between maxAssetMutations=32 and maxActiveDependencies=64).
 * @param maxNullifierConsumptions Maximum number of `_consumeNullifier` items applied per emitting fiber per
 *                          transition (protocol-nullifier-set.md). Bounds nullifier-set growth per transaction
 *                          independently of gas, mirroring `maxAssetMutations` (same value, same
 *                          consensus-constant discipline: enforced in `NullifierCombiner` as a graceful
 *                          CombineRejected, never at block acceptance).
 */
final case class ExecutionLimits(
  maxDepth:                 Int = 10,
  maxGas:                   Long = 10_000_000L,
  maxStateSizeBytes:        Int = 1_048_576, // 1MB
  maxAssetMutations:        Int = 32,
  maxActiveDependencies:    Int = 64,
  maxDependencyLedger:      Int = 256,
  maxSpawnsPerTransition:   Int = 16,
  maxNullifierConsumptions: Int = 32
)
