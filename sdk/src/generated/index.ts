/**
 * Auto-generated protobuf types for OttoChain SDK
 * 
 * Generated from proto files in sdk/proto/ottochain/v1/
 * DO NOT EDIT - regenerate with: npm run generate:proto
 */

// Common types
export {
  FiberOrdinal,
  SnapshotOrdinal,
  StateId,
  HashValue,
  Address,
} from './ottochain/v1/common';

// Fiber types
export {
  FiberStatus,
  fiberStatusFromJSON,
  fiberStatusToJSON,
  AccessControlPolicy,
  PublicAccess,
  WhitelistAccess,
  FiberOwnedAccess,
  StateMachineDefinition,
  EmittedEvent,
  EventReceipt,
  ScriptInvocation,
  FiberLogEntry,
} from './ottochain/v1/fiber';

// Record types
export {
  StateMachineFiberRecord,
  ScriptFiberRecord,
  FiberCommit,
  OnChainState,
  FiberLogEntryList,
  CalculatedState,
} from './ottochain/v1/records';

// Message types
export {
  CreateStateMachine,
  TransitionStateMachine,
  ArchiveStateMachine,
  CreateScript,
  InvokeScript,
  OttochainMessage,
} from './ottochain/v1/messages';
