package xyz.kd5ujc.schema.fiber

import cats.Applicative

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue

sealed trait FiberResult

object FiberResult {

  implicit class FailureReasonOps(private val reason: FailureReason) extends AnyVal {
    def pureOutcome[F[_]: Applicative]: F[FiberResult] = Applicative[F].pure(Failed(reason))
    def asOutcome: FiberResult = Failed(reason)
  }

  /**
   * Successful fiber evaluation.
   *
   * Gas is tracked via StateT (ExecutionState) — not carried in this result.
   *
   * @param newStateData Updated state data
   * @param newStateId New state ID (Some for state machines, None for scripts)
   * @param triggers Triggered events for other fibers
   * @param spawns Child fibers to create (state machines only)
   * @param returnValue Return value (Some for scripts, None for state machines)
   */
  final case class Success(
    newStateData:  JsonLogicValue,
    newStateId:    Option[StateId],
    triggers:      List[FiberTrigger],
    spawns:        List[SpawnDirective],
    returnValue:   Option[JsonLogicValue],
    emittedEvents: List[EmittedEvent] = List.empty
  ) extends FiberResult

  /**
   * No guard matched (state machines only).
   *
   * Gas consumed during guard evaluation is tracked via StateT (ExecutionState).
   *
   * @param attemptedCount Number of guards evaluated before giving up
   */
  final case class GuardFailed(attemptedCount: Int) extends FiberResult

  /** Evaluation failed with reason */
  final case class Failed(reason: FailureReason) extends FiberResult
}
