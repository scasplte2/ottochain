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
  val ORACLE_CALL = "_oracleCall"
  val EMIT = "_emit"

  // Oracle Return Convention Keys - Used in extractStateAndResult for oracle results
  val ORACLE_STATE = "_state"
  val ORACLE_RESULT = "_result"

  // Trigger Event Keys - Used in extractTriggerEvents for cross-machine event firing
  val TARGET_MACHINE_ID = "targetMachineId"
  val EVENT_NAME = "eventName"
  val PAYLOAD = "payload"

  // Oracle Call Keys - Used in extractOracleCall for oracle invocation
  val FIBER_ID = "fiberId"
  val METHOD = "method"
  val ARGS = "args"

  // Spawn Directive Keys - Used in extractSpawnDirectivesFromExpression for child machine creation
  val CHILD_ID = "childId"
  val DEFINITION = "definition"
  val INITIAL_DATA = "initialData"
  val OWNERS = "owners"

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
  val PROOFS = "proofs"
  val ADDRESS = "address"
  val ID = "id"
  val SIGNATURE = "signature"
  val MACHINES = "machines"
  val PARENT = "parent"
  val CHILDREN = "children"
  val SCRIPT_ORACLES = "scripts"

  // Emitted Event Keys - Used in parseEmittedEvent for user-defined event emission
  val NAME = "name"
  val DATA = "data"
  val DESTINATION = "destination"

  // Oracle Invocation Log Keys
  val RESULT = "result"
  val GAS_USED = "gasUsed"
  val INVOKED_AT = "invokedAt"
  val INVOKED_BY = "invokedBy"
  val STATUS = "status"
  val LAST_INVOCATION = "lastInvocation"

  def isInternal(key: String): Boolean = key.startsWith("_")
}
