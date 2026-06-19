package xyz.kd5ujc.schema.api

import java.util.UUID

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * Static, execution-free fee quote for a state-machine transition (`GET …/state-machines/{id}/estimate-fee`).
 * The authoritative charge is always the metered evaluation in combine; this is a pre-flight floor.
 */
@derive(customizableEncoder, customizableDecoder)
final case class TransitionFeeEstimate(
  fiberId:              UUID,
  currentState:         String,
  event:                String,
  gasEstimate:          Long,
  opCount:              Int,
  maxDepth:             Int,
  candidateTransitions: Int,
  note:                 String
)

/** Static fee quote for a script invocation (`GET …/scripts/{id}/estimate-fee`). */
@derive(customizableEncoder, customizableDecoder)
final case class ScriptFeeEstimate(
  scriptId:    UUID,
  gasEstimate: Long,
  opCount:     Int,
  maxDepth:    Int,
  note:        String
)

/** The advisory disclaimer strings embedded in fee-estimate responses — documented once here. */
object FeeNotes {

  val transition =
    "static gas estimate (exact for non-scaling ops, floor where ops scale); authoritative charge is metered at execution"
  val script = "static gas estimate; authoritative charge is metered at execution"
}
