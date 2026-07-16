package xyz.kd5ujc.schema.fiber

import enumeratum.{Enum, EnumEntry}

/**
 * Reserved keys used throughout the fiber processing engine.
 *
 * These constants define the contract between JsonLogic expressions and the fiber processor.
 * Keys starting with underscore (_) are "internal" and used for side effects that are
 * extracted from effect results but not merged into state.
 */
object ReservedKeys {
  // Effect Result Keys - Used in extracting side effects from transition results. The FiberDirective enum
  // (the engine's directive registry) is the single source for the directive SET; these named constants are
  // the per-key references the extractor dispatch + FiberDirective itself use.
  val TRIGGERS = "_triggers"
  val SPAWN = "_spawn"
  val SCRIPT_CALL = "_scriptCall"
  val EMIT = "_emit"
  val TRANSFER_ASSET = "_transferAsset" // Fiber-held asset custody transfer (asset-model.md §10)
  val ADD_DEPENDENCY = "_addDependency" // Runtime-add/re-activate a dynamic cross-fiber dependency (append-only ledger)
  val SET_DEPENDENCY_ACTIVE = "_setDependencyActive" // Toggle a dynamic dependency's active flag (never removed)

  val CONSUME_NULLIFIER =
    "_consumeNullifier" // Protocol nullifier consumption (protocol-nullifier-set.md); items are bare nf values

  /** Every reserved directive key — derived from [[FiberDirective]] so a new directive updates all consumers. */
  val directiveKeys: Set[String] = FiberDirective.values.flatMap(_.keys).toSet

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

  // FiberPolicy version & compatibility family (fiber-policy.md `version-compat-family`): the read-only
  // projection of a depended-on fiber's policy surfaced into machines.<id>._policy, so a consumer guard can
  // assert a VERIFIED version floor (`depVersionAtLeast`) or an advertised interface (`depSupportsInterface`).
  // ALWAYS present + well-typed (fail-closed): `version` is a MAP {major,minor,patch} (D2 — JLVM has no
  // integer-indexed `get`), empty {} when the producer is unbound; `interfaces` is ALWAYS an Array.
  val POLICY = "_policy"
  val POLICY_VERSION = "version"
  val POLICY_INTERFACES = "interfaces"
  val POLICY_MAJOR = "major"
  val POLICY_MINOR = "minor"
  val POLICY_PATCH = "patch"
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

  /**
   * All `_`-prefixed keys the engine RECOGNIZES (i.e. never a typo): the [[FiberDirective]] directive keys
   * plus the script-return convention (`_state`/`_result`) and the cross-fiber policy projection (`_policy`).
   * Derived from `directiveKeys`, so a new directive updates it automatically.
   */
  val recognizedInternalKeys: Set[String] = directiveKeys ++ Set(SCRIPT_STATE, SCRIPT_RESULT, POLICY)

  def isInternal(key: String): Boolean = key.startsWith("_")
}

/**
 * The first-segment context ROOTS the engine injects into a state-machine transition's evaluation context
 * (`ContextProvider.buildStateMachineContext`). A `{"var":"X.…"}` whose first segment `X` is not one of these
 * can only ever resolve to `null`. This enum is the single source of the root set ([[FiberContextRoot.keys]]),
 * so tooling (e.g. the offline linter) derives it instead of hand-maintaining a duplicate. Each entry names an
 * existing `ReservedKeys` constant — the roots are multi-purpose strings, so this DECLARES which keys are
 * roots rather than re-homing the constants.
 */
sealed abstract class FiberContextRoot(val key: String) extends EnumEntry

object FiberContextRoot extends Enum[FiberContextRoot] {
  case object State extends FiberContextRoot(ReservedKeys.STATE)
  case object Event extends FiberContextRoot(ReservedKeys.EVENT)
  case object EventName extends FiberContextRoot(ReservedKeys.EVENT_NAME)
  case object MachineId extends FiberContextRoot(ReservedKeys.MACHINE_ID)
  case object CurrentStateId extends FiberContextRoot(ReservedKeys.CURRENT_STATE_ID)
  case object SequenceNumber extends FiberContextRoot(ReservedKeys.SEQUENCE_NUMBER)
  case object Ordinal extends FiberContextRoot(ReservedKeys.ORDINAL)
  case object LastSnapshotHash extends FiberContextRoot(ReservedKeys.LAST_SNAPSHOT_HASH)
  case object EpochProgress extends FiberContextRoot(ReservedKeys.EPOCH_PROGRESS)
  case object Caller extends FiberContextRoot(ReservedKeys.CALLER)
  case object Proofs extends FiberContextRoot(ReservedKeys.PROOFS)
  case object Machines extends FiberContextRoot(ReservedKeys.MACHINES)
  case object Parent extends FiberContextRoot(ReservedKeys.PARENT)
  case object Children extends FiberContextRoot(ReservedKeys.CHILDREN)
  case object Scripts extends FiberContextRoot(ReservedKeys.SCRIPTS)
  case object HeldAssets extends FiberContextRoot(ReservedKeys.HELD_ASSETS)

  override val values: IndexedSeq[FiberContextRoot] = findValues

  /** Every context-root key — the single source for "is this a valid first-segment var root". */
  val keys: Set[String] = values.map(_.key).toSet
}
