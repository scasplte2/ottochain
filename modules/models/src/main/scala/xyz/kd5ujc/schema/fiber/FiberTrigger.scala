package xyz.kd5ujc.schema.fiber

import java.util.UUID

/**
 * Trigger that can target any fiber type.
 *
 * @param targetFiberId The fiber to trigger
 * @param input The input to send to the fiber
 * @param sourceFiberId The fiber that initiated this trigger (for script access control)
 */
final case class FiberTrigger(
  targetFiberId: UUID,
  input:         FiberInput,
  sourceFiberId: Option[UUID] = None
)
