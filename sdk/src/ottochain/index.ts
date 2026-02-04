/**
 * Ottochain SDK
 *
 * Domain-specific types and clients for the ottochain metagraph.
 *
 * @packageDocumentation
 */

// Re-export generated protobuf types
export * as proto from '../generated/index.js';

// Legacy manual types (deprecated - use proto.* instead)
export type {
  // Primitive / value types
  FiberOrdinal,
  SnapshotOrdinal,
  StateId,
  Address,
  HashValue,
  JsonLogicValue,
  JsonLogicExpression,

  // Enums
  FiberStatus,

  // Access control
  AccessControlPolicy,

  // State machine definition
  StateMachineDefinition,

  // Log entries
  EmittedEvent,
  EventReceipt,
  OracleInvocation,
  FiberLogEntry,

  // Fiber records
  StateMachineFiberRecord,
  ScriptOracleFiberRecord,
  FiberRecord,

  // On-chain state
  FiberCommit,
  OnChain,

  // Calculated state
  CalculatedState,

  // Message types
  CreateStateMachine,
  TransitionStateMachine,
  ArchiveStateMachine,
  CreateScriptOracle,
  InvokeScriptOracle,
  OttochainMessage,
} from './types.js';

// Snapshot decoder
export type { CurrencySnapshotResponse } from './snapshot.js';
export {
  decodeOnChainState,
  getSnapshotOnChainState,
  getLatestOnChainState,
  getLogsForFiber,
  getEventReceipts,
  getOracleInvocations,
  extractOnChainState,
} from './snapshot.js';

// Metagraph client
export type { Checkpoint, MetagraphClientConfig } from './metagraph-client.js';
export { MetagraphClient } from './metagraph-client.js';
