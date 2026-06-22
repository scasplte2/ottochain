package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * A user-defined event emitted by a fiber transition's `_emit` directive (engine-default-fixes Fix 1).
 *
 * The trailing two fields are ENGINE-STAMPED at extraction ([[xyz.kd5ujc.shared_data.fiber.evaluation.EffectExtractor]])
 * and are NEVER user-supplied — a guard/effect author cannot set or forge them. They make every emitted
 * event attributable to the fiber that produced it; without them an observer reading
 * `OnChain.latestLogs[*].emittedEvents` could not tell which fiber authored an event, and attribution was
 * forgeable.
 *
 * @param name           User-defined event name.
 * @param data           User-supplied event payload.
 * @param destination    Optional user-supplied routing hint.
 * @param emitterFiberId The fiber whose transition ran `_emit`. This is the EMITTER, distinct from the
 *                       cross-fiber CALLER recorded as `EventReceipt.sourceFiberId`: on the cascaded path a
 *                       fiber A→B trigger where B emits has `emitterFiberId == B` while the parent receipt's
 *                       `sourceFiberId == A`. The two never collide because they are named/typed distinctly.
 * @param emissionIndex  Position within the RAW `_emit` array at authoring time. Indices are SPARSE: a
 *                       malformed sibling that `parseEmittedEvent` drops leaves a gap, so a survivor keeps
 *                       its original authoring-time position. `(emitterFiberId, receipt.ordinal,
 *                       receipt.fromState, receipt.toState, emissionIndex)` is the unique per-event key.
 */
@derive(customizableEncoder, customizableDecoder)
final case class EmittedEvent(
  name:        String,
  data:        JsonLogicValue,
  destination: Option[String] = None,
  // ── engine-stamped, always-on, never user-supplied ──
  emitterFiberId: UUID,
  emissionIndex:  Int
)
