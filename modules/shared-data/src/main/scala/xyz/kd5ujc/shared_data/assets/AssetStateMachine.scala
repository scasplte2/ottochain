package xyz.kd5ujc.shared_data.assets

import io.constellationnetwork.currency.dataApplication.DataUpdate

import xyz.kd5ujc.shared_data.app.JsonLogicValidation

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder, Json}

/**
 * DFA State Machine + JSON Logic Integration for Asset Lifecycle Management
 *
 * This module integrates Deterministic Finite Automata (DFA) state machines with OttoChain's
 * JSON Logic Virtual Machine for declarative business logic in asset lifecycle management.
 *
 * Key Features:
 * - DFA state machine definitions compatible with JLVM
 * - State transition validation using JSON Logic predicates
 * - Asset lifecycle templates for common patterns
 * - Producer-validator coordination for state transitions
 * - Debugging and visualization support
 */
object AssetStateMachine {

  // ---------------------------------------------------------------------------
  // Core Types
  // ---------------------------------------------------------------------------

  type StateId = String
  type TransitionId = String
  type EventType = String
  type AssetId = String
  type MachineId = String
  type InstanceId = String

  /**
   * JSON Logic expression for guards and effects
   */
  case class JSONLogicExpression(value: Json) {
    def asJsonString: String = value.noSpaces
  }

  object JSONLogicExpression {
    def fromJson(json: Json): JSONLogicExpression = JSONLogicExpression(json)

    def parse(jsonString: String): Either[String, JSONLogicExpression] =
      io.circe.parser.parse(jsonString).map(JSONLogicExpression(_)).left.map(_.message)

    implicit val encoder: Encoder[JSONLogicExpression] = Encoder.instance(_.value)
    implicit val decoder: Decoder[JSONLogicExpression] = Decoder[Json].map(JSONLogicExpression(_))
  }

  /**
   * Visualization metadata for states and transitions
   */
  case class VisualizationMetadata(
    position: Option[Position] = None,
    color:    String = "#E3F2FD",
    shape:    String = "circle",
    label:    Option[String] = None,
    style:    String = "solid"
  )

  case class Position(x: Double, y: Double)

  /**
   * State definition in a DFA state machine
   */
  case class DFAState(
    id:            StateId,
    name:          String,
    description:   String,
    isInitial:     Boolean = false,
    isFinal:       Boolean = false,
    dataSchema:    Option[Json] = None,
    onEntry:       List[JSONLogicExpression] = List.empty,
    onExit:        List[JSONLogicExpression] = List.empty,
    visualization: VisualizationMetadata = VisualizationMetadata()
  )

  /**
   * State transition definition with JSON Logic guards
   */
  case class DFATransition(
    id:                       TransitionId,
    fromState:                StateId,
    toState:                  StateId,
    eventType:                EventType,
    name:                     String,
    description:              String = "",
    guards:                   List[JSONLogicExpression] = List.empty,
    effects:                  List[JSONLogicExpression] = List.empty,
    requiredCapabilities:     List[String] = List.empty,
    requiredValidatorDomains: List[String] = List.empty,
    minValidatorAuthority:    Int = 0,
    requiresCoordination:     Boolean = false,
    timeoutSeconds:           Option[Long] = None,
    visualization:            VisualizationMetadata = VisualizationMetadata()
  )

  /**
   * Complete DFA state machine definition
   */
  case class DFAStateMachine(
    id:              MachineId,
    name:            String,
    description:     String,
    version:         String,
    assetType:       String,
    states:          List[DFAState],
    transitions:     List[DFATransition],
    initialState:    StateId,
    terminalStates:  List[StateId] = List.empty,
    globalVariables: Map[String, Json] = Map.empty,
    metadata:        MachineMetadata
  ) {

    /**
     * Validate this state machine definition
     */
    def validate: ValidationResult = {
      val errors = collection.mutable.ListBuffer[String]()
      val warnings = collection.mutable.ListBuffer[String]()

      // Basic validation
      if (states.isEmpty) {
        errors += "State machine must have at least one state"
      }

      if (transitions.isEmpty) {
        warnings += "State machine has no transitions"
      }

      // Check for initial state
      val initialStates = states.filter(_.isInitial)
      if (initialStates.isEmpty) {
        errors += "State machine must have exactly one initial state"
      } else if (initialStates.length > 1) {
        errors += "State machine can have only one initial state"
      }

      // Check that specified initial state exists
      if (!states.exists(_.id == initialState)) {
        errors += s"Initial state '$initialState' not found in states"
      }

      // Check terminal states exist
      val stateIds = states.map(_.id).toSet
      val invalidTerminalStates = terminalStates.filterNot(stateIds.contains)
      if (invalidTerminalStates.nonEmpty) {
        errors += s"Terminal states not found: ${invalidTerminalStates.mkString(", ")}"
      }

      // Check transition validity
      for (transition <- transitions) {
        if (!stateIds.contains(transition.fromState)) {
          errors += s"Transition '${transition.id}' fromState '${transition.fromState}' not found"
        }
        if (!stateIds.contains(transition.toState)) {
          errors += s"Transition '${transition.id}' toState '${transition.toState}' not found"
        }
      }

      // Check reachability
      val reachableStates = computeReachableStates(this)
      val unreachableStates = stateIds -- reachableStates
      if (unreachableStates.nonEmpty) {
        warnings += s"Unreachable states detected: ${unreachableStates.mkString(", ")}"
      }

      // Check for deadlock states
      val deadlockStates = stateIds.filterNot { stateId =>
        terminalStates.contains(stateId) || transitions.exists(_.fromState == stateId)
      }
      if (deadlockStates.nonEmpty) {
        warnings += s"Potential deadlock states: ${deadlockStates.mkString(", ")}"
      }

      ValidationResult(
        isValid = errors.isEmpty,
        errors = errors.toList,
        warnings = warnings.toList,
        stateCount = states.length,
        transitionCount = transitions.length,
        reachableStates = reachableStates.toList,
        unreachableStates = unreachableStates.toList,
        deadlockStates = deadlockStates.toList,
        hasInitialState = initialStates.length == 1,
        hasTerminalStates = terminalStates.nonEmpty
      )
    }

    /**
     * Find transitions from a specific state
     */
    def transitionsFrom(stateId: StateId): List[DFATransition] =
      transitions.filter(_.fromState == stateId)

    /**
     * Find transitions to a specific state
     */
    def transitionsTo(stateId: StateId): List[DFATransition] =
      transitions.filter(_.toState == stateId)

    /**
     * Find transition by ID
     */
    def findTransition(transitionId: TransitionId): Option[DFATransition] =
      transitions.find(_.id == transitionId)

    /**
     * Find state by ID
     */
    def findState(stateId: StateId): Option[DFAState] =
      states.find(_.id == stateId)
  }

  case class MachineMetadata(
    createdAt: Long,
    createdBy: String,
    tags:      List[String] = List.empty,
    category:  String = "asset_management"
  )

  /**
   * Runtime state machine instance
   */
  case class DFAInstance(
    instanceId:        InstanceId,
    assetId:           AssetId,
    machineId:         MachineId,
    currentState:      StateId,
    stateData:         Json,
    createdAt:         Long,
    updatedAt:         Long,
    transitionHistory: List[TransitionExecution] = List.empty,
    variables:         Map[String, Json] = Map.empty
  ) {

    /**
     * Get the current state data as a Map for easier access
     */
    def currentStateData: Map[String, Json] =
      stateData.asObject.map(_.toMap).getOrElse(Map.empty)

    /**
     * Update state data with new values
     */
    def updateStateData(updates: Map[String, Json]): DFAInstance = {
      val currentData = currentStateData
      val mergedData = currentData ++ updates
      val newStateData = Json.fromJsonObject(io.circe.JsonObject.fromMap(mergedData))

      copy(
        stateData = newStateData,
        updatedAt = System.currentTimeMillis() / 1000
      )
    }

    /**
     * Record a transition execution
     */
    def recordTransition(execution: TransitionExecution): DFAInstance =
      copy(
        currentState = execution.toState,
        transitionHistory = transitionHistory :+ execution,
        updatedAt = execution.executedAt
      )
  }

  /**
   * Event that can trigger state transitions
   */
  case class StateEvent(
    eventId:    String,
    eventType:  EventType,
    data:       Json,
    producerId: String,
    timestamp:  Long,
    signature:  String
  )

  /**
   * Record of a transition execution
   */
  case class TransitionExecution(
    executionId:   String,
    transitionId:  TransitionId,
    triggerEvent:  StateEvent,
    producerId:    String,
    validatorIds:  List[String],
    executedAt:    Long,
    guardResults:  List[GuardEvaluation],
    effectResults: List[EffectExecution],
    fromState:     StateId,
    toState:       StateId,
    stateChanges:  Map[String, Json]
  )

  /**
   * Guard condition evaluation result
   */
  case class GuardEvaluation(
    guard:       JSONLogicExpression,
    result:      Boolean,
    context:     Map[String, Json],
    error:       Option[String] = None,
    evaluatedAt: Long
  )

  /**
   * Effect execution result
   */
  case class EffectExecution(
    effect:       JSONLogicExpression,
    result:       Json,
    stateChanges: Map[String, Json],
    error:        Option[String] = None,
    executedAt:   Long
  )

  /**
   * State machine validation result
   */
  case class ValidationResult(
    isValid:           Boolean,
    errors:            List[String],
    warnings:          List[String],
    stateCount:        Int,
    transitionCount:   Int,
    reachableStates:   List[StateId],
    unreachableStates: List[StateId],
    deadlockStates:    List[StateId],
    hasInitialState:   Boolean,
    hasTerminalStates: Boolean
  )

  // ---------------------------------------------------------------------------
  // DataUpdate Integration
  // ---------------------------------------------------------------------------

  case class AssetStateMachineUpdate(machine: DFAStateMachine) extends DataUpdate
  case class AssetInstanceUpdate(instance: DFAInstance) extends DataUpdate

  case class StateTransitionUpdate(
    instanceId:   InstanceId,
    transitionId: TransitionId,
    event:        StateEvent,
    execution:    TransitionExecution
  ) extends DataUpdate

  // ---------------------------------------------------------------------------
  // JSON Logic Integration
  // ---------------------------------------------------------------------------

  object StateMachineValidation extends JsonLogicValidation {

    /**
     * Evaluate guard conditions for a transition
     */
    def evaluateGuards(
      transition: DFATransition,
      context:    Map[String, Json],
      instance:   DFAInstance
    ): List[GuardEvaluation] = {
      val timestamp = System.currentTimeMillis() / 1000

      transition.guards.map { guard =>
        try {
          // Create evaluation context with state, event, and instance variables
          val evaluationContext = context ++ Map(
            "state"           -> instance.stateData,
            "machineId"       -> Json.fromString(instance.machineId),
            "currentStateId"  -> Json.fromString(instance.currentState),
            "instanceId"      -> Json.fromString(instance.instanceId),
            "$" + "timestamp" -> Json.fromLong(timestamp)
          ) ++ instance.variables

          // Note: In real implementation, integrate with actual JLVM
          // For now, we'll do basic validation
          val result = evaluateJsonLogic(guard.value, evaluationContext)

          GuardEvaluation(
            guard = guard,
            result = result.fold(false)(_.asBoolean.getOrElse(false)),
            context = evaluationContext,
            evaluatedAt = timestamp
          )
        } catch {
          case ex: Exception =>
            GuardEvaluation(
              guard = guard,
              result = false,
              context = context,
              error = Some(ex.getMessage),
              evaluatedAt = timestamp
            )
        }
      }
    }

    /**
     * Execute effect expressions for a transition
     */
    def executeEffects(
      transition: DFATransition,
      context:    Map[String, Json],
      instance:   DFAInstance
    ): List[EffectExecution] = {
      val timestamp = System.currentTimeMillis() / 1000

      transition.effects.map { effect =>
        try {
          // Create evaluation context
          val evaluationContext = context ++ Map(
            "state"           -> instance.stateData,
            "machineId"       -> Json.fromString(instance.machineId),
            "currentStateId"  -> Json.fromString(instance.currentState),
            "instanceId"      -> Json.fromString(instance.instanceId),
            "$" + "timestamp" -> Json.fromLong(timestamp)
          ) ++ instance.variables

          // Execute effect (integrate with actual JLVM)
          val result = evaluateJsonLogic(effect.value, evaluationContext)

          // Extract state changes from result
          val stateChanges = extractStateChanges(result.getOrElse(Json.Null))

          EffectExecution(
            effect = effect,
            result = result.getOrElse(Json.Null),
            stateChanges = stateChanges,
            executedAt = timestamp
          )
        } catch {
          case ex: Exception =>
            EffectExecution(
              effect = effect,
              result = Json.Null,
              stateChanges = Map.empty,
              error = Some(ex.getMessage),
              executedAt = timestamp
            )
        }
      }
    }

    /**
     * Basic JSON Logic evaluation (placeholder for full JLVM integration)
     */
    override def evaluateJsonLogic(expression: Json, context: Map[String, Json]): Option[Json] =
      // This is a simplified version - in real implementation,
      // integrate with the full JSON Logic VM

      expression.asObject match {
        case Some(obj) if obj.contains("==") =>
          obj("==").flatMap(_.asArray) match {
            case Some(array) if array.length == 2 =>
              for {
                left  <- evaluateExpression(array(0), context)
                right <- evaluateExpression(array(1), context)
              } yield Json.fromBoolean(left == right)
            case _ => Some(Json.fromBoolean(false))
          }

        case Some(obj) if obj.contains("var") =>
          obj("var").flatMap(_.asString).flatMap(varName => context.get(varName).orElse(Some(Json.Null)))

        case Some(obj) if obj.contains("merge") =>
          obj("merge").flatMap(_.asArray) match {
            case Some(array) if array.length == 2 =>
              for {
                base    <- evaluateExpression(array(0), context)
                updates <- evaluateExpression(array(1), context)
                merged  <- mergeJsonObjects(base, updates)
              } yield merged
            case _ => Some(Json.Null)
          }

        case _ => Some(expression)
      }

    /**
     * Evaluate an expression (variable lookup, literal, etc.)
     */
    private def evaluateExpression(expr: Json, context: Map[String, Json]): Option[Json] =
      expr.asObject match {
        case Some(obj) if obj.contains("var") =>
          obj("var").flatMap(_.asString).flatMap(context.get)
        case _ => Some(expr)
      }

    /**
     * Merge two JSON objects
     */
    private def mergeJsonObjects(base: Json, updates: Json): Option[Json] =
      for {
        baseObj    <- base.asObject
        updatesObj <- updates.asObject
      } yield Json.fromJsonObject(baseObj.deepMerge(updatesObj))

    /**
     * Extract state changes from effect result
     */
    private def extractStateChanges(result: Json): Map[String, Json] =
      result.asObject.map(_.toMap).getOrElse(Map.empty)
  }

  // ---------------------------------------------------------------------------
  // Asset Lifecycle Templates
  // ---------------------------------------------------------------------------

  object AssetLifecycleTemplates {

    /**
     * Basic asset creation → active → transfer → burn lifecycle
     */
    lazy val BASIC_ASSET_LIFECYCLE: DFAStateMachine = DFAStateMachine(
      id = "basic_asset_lifecycle",
      name = "Basic Asset Lifecycle",
      description = "Simple creation → activation → transfer → burn cycle for standard assets",
      version = "1.0.0",
      assetType = "generic",
      states = List(
        DFAState(
          id = "created",
          name = "Created",
          description = "Asset has been created but is not yet active",
          isInitial = true,
          onEntry =
            List(JSONLogicExpression.parse("""{"log": "Asset created"}""").getOrElse(JSONLogicExpression(Json.Null))),
          visualization = VisualizationMetadata(
            position = Some(Position(100, 100)),
            color = "#E3F2FD",
            shape = "circle"
          )
        ),
        DFAState(
          id = "active",
          name = "Active",
          description = "Asset is active and can be operated on",
          onEntry =
            List(JSONLogicExpression.parse("""{"log": "Asset activated"}""").getOrElse(JSONLogicExpression(Json.Null))),
          visualization = VisualizationMetadata(
            position = Some(Position(300, 100)),
            color = "#E8F5E8",
            shape = "circle"
          )
        ),
        DFAState(
          id = "transferred",
          name = "Transferred",
          description = "Asset has been transferred to a new owner",
          onEntry = List(
            JSONLogicExpression.parse("""{"log": "Asset transferred"}""").getOrElse(JSONLogicExpression(Json.Null))
          ),
          visualization = VisualizationMetadata(
            position = Some(Position(500, 100)),
            color = "#FFF3E0",
            shape = "circle"
          )
        ),
        DFAState(
          id = "burned",
          name = "Burned",
          description = "Asset has been permanently destroyed",
          isFinal = true,
          onEntry = List(
            JSONLogicExpression
              .parse("""{"log": "Asset burned - permanent destruction"}""")
              .getOrElse(JSONLogicExpression(Json.Null))
          ),
          visualization = VisualizationMetadata(
            position = Some(Position(300, 300)),
            color = "#FFEBEE",
            shape = "square"
          )
        )
      ),
      transitions = List(
        DFATransition(
          id = "activate",
          fromState = "created",
          toState = "active",
          eventType = "activation_request",
          name = "Activate Asset",
          guards = List(
            JSONLogicExpression
              .parse("""{">=": [{"var": "validator.authorityLevel"}, 10]}""")
              .getOrElse(JSONLogicExpression(Json.Null)),
            JSONLogicExpression
              .parse("""{"!=": [{"var": "asset.status"}, "suspended"]}""")
              .getOrElse(JSONLogicExpression(Json.Null))
          ),
          effects = List(
            JSONLogicExpression
              .parse("""{
              "merge": [
                {"var": "state"},
                {
                  "activatedAt": {"var": "$" + "timestamp"},
                  "activatedBy": {"var": "validator.id"}, 
                  "status": "active"
                }
              ]
            }""")
              .getOrElse(JSONLogicExpression(Json.Null))
          ),
          requiredCapabilities = List("state_management"),
          requiredValidatorDomains = List("asset_management"),
          minValidatorAuthority = 10,
          visualization = VisualizationMetadata(label = Some("Activate"), color = "#4CAF50")
        ),
        DFATransition(
          id = "transfer",
          fromState = "active",
          toState = "transferred",
          eventType = "transfer_request",
          name = "Transfer Asset",
          guards = List(
            JSONLogicExpression.parse("""{"!!": [{"var": "event.to"}]}""").getOrElse(JSONLogicExpression(Json.Null)),
            JSONLogicExpression
              .parse("""{"!==": [{"var": "event.to"}, {"var": "state.owner"}]}""")
              .getOrElse(JSONLogicExpression(Json.Null)),
            JSONLogicExpression
              .parse("""{">=": [{"var": "validator.authorityLevel"}, 5]}""")
              .getOrElse(JSONLogicExpression(Json.Null))
          ),
          effects = List(
            JSONLogicExpression
              .parse("""{
              "merge": [
                {"var": "state"},
                {
                  "owner": {"var": "event.to"},
                  "transferredAt": {"var": "$" + "timestamp"}, 
                  "transferredBy": {"var": "producer.id"},
                  "previousOwner": {"var": "state.owner"}
                }
              ]
            }""")
              .getOrElse(JSONLogicExpression(Json.Null))
          ),
          requiredCapabilities = List("asset_transfer"),
          requiredValidatorDomains = List("transfer_approval"),
          minValidatorAuthority = 5,
          visualization = VisualizationMetadata(label = Some("Transfer"), color = "#FF9800")
        ),
        DFATransition(
          id = "burn_from_active",
          fromState = "active",
          toState = "burned",
          eventType = "burn_request",
          name = "Burn from Active",
          guards = List(
            JSONLogicExpression
              .parse("""{">=": [{"var": "validator.authorityLevel"}, 20]}""")
              .getOrElse(JSONLogicExpression(Json.Null)),
            JSONLogicExpression
              .parse("""{"===": [{"var": "event.producer"}, {"var": "state.owner"}]}""")
              .getOrElse(JSONLogicExpression(Json.Null))
          ),
          effects = List(
            JSONLogicExpression
              .parse("""{
              "merge": [
                {"var": "state"},
                {
                  "burnedAt": {"var": "$" + "timestamp"},
                  "burnedBy": {"var": "producer.id"},
                  "status": "burned",
                  "finalState": true
                }
              ]
            }""")
              .getOrElse(JSONLogicExpression(Json.Null))
          ),
          requiredCapabilities = List("asset_destruction"),
          requiredValidatorDomains = List("destruction_approval"),
          minValidatorAuthority = 20,
          visualization = VisualizationMetadata(label = Some("Burn"), color = "#F44336")
        )
      ),
      initialState = "created",
      terminalStates = List("burned"),
      globalVariables = Map(
        "maxTransfersPerDay"        -> Json.fromInt(10),
        "requiredBurnConfirmations" -> Json.fromInt(2)
      ),
      metadata = MachineMetadata(
        createdAt = System.currentTimeMillis() / 1000,
        createdBy = "system",
        tags = List("basic", "asset", "lifecycle", "standard"),
        category = "asset_management"
      )
    )

    /**
     * Get all available templates
     */
    def getAvailableTemplates: Map[String, DFAStateMachine] = Map(
      "basic_asset_lifecycle" -> BASIC_ASSET_LIFECYCLE
    )

    /**
     * Get template by ID
     */
    def getTemplate(templateId: String): Option[DFAStateMachine] =
      getAvailableTemplates.get(templateId)
  }

  // ---------------------------------------------------------------------------
  // Utility Functions
  // ---------------------------------------------------------------------------

  /**
   * Compute reachable states from initial state
   */
  def computeReachableStates(machine: DFAStateMachine): Set[StateId] = {
    val reachable = collection.mutable.Set[StateId]()
    val queue = collection.mutable.Queue[StateId](machine.initialState)

    while (queue.nonEmpty) {
      val currentState = queue.dequeue()
      if (!reachable.contains(currentState)) {
        reachable += currentState

        // Find all transitions from current state
        machine.transitions.filter(_.fromState == currentState).foreach { transition =>
          if (!reachable.contains(transition.toState)) {
            queue.enqueue(transition.toState)
          }
        }
      }
    }

    reachable.toSet
  }

  /**
   * Generate DOT notation for state machine visualization
   */
  def generateDOTVisualization(machine: DFAStateMachine): String = {
    val sb = new StringBuilder()
    sb.append(s"""digraph "${machine.id}" {\n""")
    sb.append(s"""  label="${machine.name}";\n""")
    sb.append("""  rankdir=LR;\n""")
    sb.append("""  node [shape=circle];\n\n""")

    // Add states
    machine.states.foreach { state =>
      val shape = if (state.isFinal) "doublecircle" else "circle"
      val fillcolor = if (state.isInitial) "#4CAF50" else state.visualization.color

      sb.append(s"""  ${state.id} [label="${state.name}", shape=$shape, style=filled, fillcolor="$fillcolor"];\n""")
    }

    sb.append("\n")

    // Add transitions
    machine.transitions.foreach { transition =>
      val label = transition.visualization.label.getOrElse(transition.name)
      val color = transition.visualization.color
      val style = transition.visualization.style

      sb.append(
        s"""  ${transition.fromState} -> ${transition.toState} [label="$label", color="$color", style=$style];\n"""
      )
    }

    sb.append("}\n")
    sb.toString
  }

  // ---------------------------------------------------------------------------
  // Circe JSON Encoders/Decoders
  // ---------------------------------------------------------------------------

  implicit val positionEncoder: Encoder[Position] = deriveEncoder
  implicit val positionDecoder: Decoder[Position] = deriveDecoder

  implicit val visualizationEncoder: Encoder[VisualizationMetadata] = deriveEncoder
  implicit val visualizationDecoder: Decoder[VisualizationMetadata] = deriveDecoder

  implicit val dfaStateEncoder: Encoder[DFAState] = deriveEncoder
  implicit val dfaStateDecoder: Decoder[DFAState] = deriveDecoder

  implicit val dfaTransitionEncoder: Encoder[DFATransition] = deriveEncoder
  implicit val dfaTransitionDecoder: Decoder[DFATransition] = deriveDecoder

  implicit val machineMetadataEncoder: Encoder[MachineMetadata] = deriveEncoder
  implicit val machineMetadataDecoder: Decoder[MachineMetadata] = deriveDecoder

  implicit val dfaStateMachineEncoder: Encoder[DFAStateMachine] = deriveEncoder
  implicit val dfaStateMachineDecoder: Decoder[DFAStateMachine] = deriveDecoder

  implicit val stateEventEncoder: Encoder[StateEvent] = deriveEncoder
  implicit val stateEventDecoder: Decoder[StateEvent] = deriveDecoder

  implicit val guardEvaluationEncoder: Encoder[GuardEvaluation] = deriveEncoder
  implicit val guardEvaluationDecoder: Decoder[GuardEvaluation] = deriveDecoder

  implicit val effectExecutionEncoder: Encoder[EffectExecution] = deriveEncoder
  implicit val effectExecutionDecoder: Decoder[EffectExecution] = deriveDecoder

  implicit val transitionExecutionEncoder: Encoder[TransitionExecution] = deriveEncoder
  implicit val transitionExecutionDecoder: Decoder[TransitionExecution] = deriveDecoder

  implicit val dfaInstanceEncoder: Encoder[DFAInstance] = deriveEncoder
  implicit val dfaInstanceDecoder: Decoder[DFAInstance] = deriveDecoder

  implicit val validationResultEncoder: Encoder[ValidationResult] = deriveEncoder
  implicit val validationResultDecoder: Decoder[ValidationResult] = deriveDecoder
}
