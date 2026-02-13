package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.hash.Hash

/**
 * Read-only context available via ReaderT.
 *
 * @param ordinal Current snapshot ordinal
 * @param lastSnapshotHash Hash of the parent snapshot (for randomness, verification)
 * @param epochProgress Current epoch progress
 * @param limits Execution limits (depth, gas)
 * @param jlvmGasConfig JsonLogic VM gas configuration for expression evaluation
 * @param fiberGasConfig Fiber engine gas configuration for orchestration operations
 */
final case class FiberContext(
  ordinal:          SnapshotOrdinal,
  lastSnapshotHash: Hash,
  epochProgress:    EpochProgress,
  limits:           ExecutionLimits,
  jlvmGasConfig:    GasConfig,
  fiberGasConfig:   FiberGasConfig
)
