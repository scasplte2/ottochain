package xyz.kd5ujc.schema.fiber

/**
 * Reserved keys used throughout the fiber processing engine.
 *
 * These constants define the contract between JsonLogic expressions and the fiber processor.
 * Keys starting with underscore (_) are "internal" and used for side effects that are
 * extracted from effect results but not merged into state.
 */
object ReservedKeys {
  // Effect Result Keys - Used in extracting side effects from transition results
  val TRIGGERS = "_triggers"
  val SPAWN = "_spawn"
  val SCRIPT_CALL = "_scriptCall"
  val EMIT = "_emit"
  val TRANSFER_ASSET = "_transferAsset" // Fiber-held asset custody transfer (asset-model.md §10)
  val ADD_DEPENDENCY = "_addDependency" // Runtime-add/re-activate a dynamic cross-fiber dependency (append-only ledger)
  val SET_DEPENDENCY_ACTIVE = "_setDependencyActive" // Toggle a dynamic dependency's active flag (never removed)

  // Script Return Convention Keys - Used in extractStateAndResult for script results
  val SCRIPT_STATE = "_state"
  val SCRIPT_RESULT = "_result"

  // Trigger Event Keys - Used in extractTriggerEvents for cross-machine event firing
  val TARGET_MACHINE_ID = "targetMachineId"
  val EVENT_NAME = "eventName"
  val PAYLOAD = "payload"

  // Script Call Keys - Used in extractScriptCall for script invocation
  val FIBER_ID = "fiberId"
  val METHOD = "method"
  val ARGS = "args"

  // Spawn Directive Keys - Used in extractSpawnDirectivesFromExpression for child machine creation
  val CHILD_ID = "childId"
  val DEFINITION = "definition"
  val INITIAL_DATA = "initialData"
  val OWNERS = "owners"

  // Dynamic Dependency Directive Keys - Used in extractDependencyMutations
  // (the target fiber reuses FIBER_ID above; ACTIVE is the on/off flag for _setDependencyActive)
  val ACTIVE = "active"

  // State Machine Definition Keys - Used in parseStateMachineDefinition(FromExpression)
  val STATES = "states"
  val INITIAL_STATE = "initialState"
  val TRANSITIONS = "transitions"
  val METADATA = "metadata"
  val IS_FINAL = "isFinal"
  val VALUE = "value"

  // Transition Keys - Used in parseTransitions(FromExpression)
  val FROM = "from"
  val TO = "to"
  val GUARD = "guard"
  val EFFECT = "effect"
  val DEPENDENCIES = "dependencies"

  // JsonLogic Expression Keys - Used in valueToExpression
  val VAR = "var"

  // Context Data Keys - Used in buildContextData and related methods
  val STATE = "state"
  val EVENT = "event"
  val MACHINE_ID = "machineId"
  val CURRENT_STATE_ID = "currentStateId"
  val SEQUENCE_NUMBER = "sequenceNumber"
  val ORDINAL = "$ordinal" // Current snapshot ordinal - use for deadline comparisons
  val LAST_SNAPSHOT_HASH = "$lastSnapshotHash" // Hash of parent snapshot - use for randomness, verification
  val EPOCH_PROGRESS = "$epochProgress" // Current epoch progress
  // engine-stamped cross-fiber caller (engine-default-fixes Fix 2): the EMITTING fiber id of the cross-fiber
  // trigger, surfaced to the guard context; NullValue for external/primary (wallet) triggers. Non-spoofable —
  // a fiber cannot forge being another fiber; the engine writes the id at EffectExtractor (:136/:169). A
  // self-trigger naturally yields $caller == $machineId. External-wallet authorization stays the `proofs`
  // channel, NOT $caller ($caller=null only says "some non-fiber", it cannot say WHICH wallet).
  val CALLER = "$caller"
  val PROOFS = "proofs"
  val ADDRESS = "address"
  val ID = "id"
  val SIGNATURE = "signature"
  val MACHINES = "machines"
  val PARENT = "parent"
  val CHILDREN = "children"
  val SCRIPTS = "scripts"
  val HELD_ASSETS = "heldAssets" // Assets held by this fiber (asset-model.md §10), injected into eval context

  // Asset Transfer Directive Keys - Used in extractAssetTransfers for _transferAsset
  val ASSET_ID = "assetId"
  val RECIPIENT = "recipient"

  // heldAssets summary keys (the per-asset projection injected into context)
  val BEHAVIOR = "behavior"
  val AMOUNT = "amount"
  val EXPIRES_AT = "expiresAt"

  // Emitted Event Keys - Used in parseEmittedEvent for user-defined event emission
  val NAME = "name"
  val DATA = "data"
  val DESTINATION = "destination"

  // Script Invocation Log Keys
  val RESULT = "result"
  val GAS_USED = "gasUsed"
  val INVOKED_AT = "invokedAt"
  val INVOKED_BY = "invokedBy"
  val STATUS = "status"
  val LAST_INVOCATION = "lastInvocation"

  def isInternal(key: String): Boolean = key.startsWith("_")
}
