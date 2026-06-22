package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

@derive(customizableEncoder, customizableDecoder)
sealed trait FailureReason {

  def toMessage: String = this match {
    case FailureReason.NoTransitionFound(fromState, eventName) =>
      s"No transition found from ${fromState.value} for event ${eventName}"
    case FailureReason.NoGuardMatched(fromState, eventName, attemptedGuards) =>
      s"No guard matched from ${fromState.value} on ${eventName} ($attemptedGuards guards tried)"
    case FailureReason.EvaluationError(phase, msg) =>
      s"${phase.entryName} evaluation error: $msg"
    case FailureReason.CycleDetected(fiberId, eventName) =>
      s"Cycle detected: fiber $fiberId, event ${eventName}"
    case FailureReason.ValidationFailed(msg, ordinal) =>
      s"Validation failed at ordinal ${ordinal.value.value}: $msg"
    case FailureReason.AccessDenied(caller, resourceId, policyType, details) =>
      s"Access denied: caller $caller not authorized for resource $resourceId (policy: $policyType)${details.fold("")(d => s" - $d")}"
    case FailureReason.TriggerTargetNotFound(targetId, sourceId) =>
      s"Trigger target fiber $targetId not found${sourceId.fold("")(s => s" (source: $s)")}"
    case FailureReason.GasExhaustedFailure(gasUsed, gasLimit, phase) =>
      s"Gas exhausted during ${phase.entryName}: $gasUsed of $gasLimit units consumed"
    case FailureReason.FiberInputMismatch(fiberId, fiberKind, inputKind) =>
      s"Cannot use ${inputKind.entryName} input with ${fiberKind.entryName} fiber $fiberId"
    case FailureReason.FiberNotFound(fiberId) =>
      s"Fiber $fiberId not found"
    case FailureReason.FiberNotActive(fiberId, status) =>
      s"Fiber $fiberId is not active (status: $status)"
    case FailureReason.DepthExceeded(depth, maxDepth) =>
      s"Trigger cascade depth exceeded: $depth (max: $maxDepth)"
    case FailureReason.ScriptInvocationFailed(scriptId, method, errorMsg) =>
      s"Script $scriptId method '$method' failed${errorMsg.fold("")(e => s": $e")}"
    case FailureReason.CallerResolutionFailed(scriptId, sourceId) =>
      s"Unable to determine caller for script $scriptId${sourceId.fold("")(s => s" (source fiber: $s)")}"
    case FailureReason.MissingProof(fiberId, operation) =>
      s"No signature proof provided for $operation on fiber $fiberId"
    case FailureReason.StateSizeTooLarge(actualBytes, maxBytes) =>
      s"Resulting state size $actualBytes bytes exceeds maximum $maxBytes bytes"
    case FailureReason.InvalidChildIdFormat(expression, error) =>
      s"Child ID expression '$expression' did not return valid UUID: $error"
    case FailureReason.DuplicateChildId(childId, count) =>
      s"Duplicate child ID $childId appears $count times in spawn batch"
    case FailureReason.ChildIdCollision(childId) =>
      s"Child ID $childId already exists as a fiber"
    case FailureReason.InvalidOwnersExpression(error) =>
      s"Owners expression invalid: $error"
    case FailureReason.InvalidOwnerAddress(address, error) =>
      s"Invalid owner address '$address': $error"
    case FailureReason.ChildIdEvaluationFailed(error) =>
      s"Failed to evaluate child ID expression: $error"
    case FailureReason.OwnersEvaluationFailed(error) =>
      s"Failed to evaluate owners expression: $error"
    case FailureReason.DependencyLimitExceeded(kind, current, max) =>
      s"Dynamic dependency $kind limit exceeded: $current (max: $max)"
    case FailureReason.SpawnLimitExceeded(attempted, max) =>
      s"Spawn fan-out limit exceeded: $attempted (max: $max)"
  }
}

object FailureReason {
  case class NoTransitionFound(fromState: StateId, eventName: String) extends FailureReason
  case class NoGuardMatched(fromState: StateId, eventName: String, attemptedGuards: Int) extends FailureReason
  case class EvaluationError(phase: GasExhaustionPhase, message: String) extends FailureReason
  case class CycleDetected(fiberId: UUID, eventName: String) extends FailureReason
  case class ValidationFailed(message: String, attemptedAt: SnapshotOrdinal) extends FailureReason
  case class TriggerTargetNotFound(targetFiberId: UUID, sourceFiberId: Option[UUID]) extends FailureReason

  case class AccessDenied(caller: String, resourceId: UUID, policyType: String, details: Option[String])
      extends FailureReason
  case class GasExhaustedFailure(gasUsed: Long, gasLimit: Long, phase: GasExhaustionPhase) extends FailureReason
  case class FiberInputMismatch(fiberId: UUID, fiberKind: FiberKind, inputKind: InputKind) extends FailureReason
  case class FiberNotFound(fiberId: UUID) extends FailureReason
  case class FiberNotActive(fiberId: UUID, currentStatus: String) extends FailureReason
  case class DepthExceeded(depth: Int, maxDepth: Int) extends FailureReason

  case class ScriptInvocationFailed(scriptId: UUID, method: String, errorMessage: Option[String]) extends FailureReason
  case class CallerResolutionFailed(targetScriptId: UUID, sourceFiberId: Option[UUID]) extends FailureReason
  case class MissingProof(fiberId: UUID, operation: String) extends FailureReason
  case class StateSizeTooLarge(actualBytes: Int, maxBytes: Int) extends FailureReason
  case class InvalidChildIdFormat(expression: String, error: String) extends FailureReason
  case class DuplicateChildId(childId: UUID, count: Int) extends FailureReason
  case class ChildIdCollision(childId: UUID) extends FailureReason
  case class InvalidOwnersExpression(error: String) extends FailureReason
  case class InvalidOwnerAddress(address: String, error: String) extends FailureReason
  case class ChildIdEvaluationFailed(error: String) extends FailureReason
  case class OwnersEvaluationFailed(error: String) extends FailureReason

  /** A dynamic-dependency mutation would breach an ExecutionLimits cap (`kind` = "active" | "ledger"). */
  case class DependencyLimitExceeded(kind: String, current: Int, max: Int) extends FailureReason

  /**
   * A primary transition's `_spawn` fan-out would breach `ExecutionLimits.maxSpawnsPerTransition`
   * (engine-default-fixes Fix 3). Fail-closed: the whole transition aborts (total discard) before any child
   * record is constructed and before per-spawn `initialData` gas is burned.
   */
  case class SpawnLimitExceeded(attempted: Int, max: Int) extends FailureReason
}
