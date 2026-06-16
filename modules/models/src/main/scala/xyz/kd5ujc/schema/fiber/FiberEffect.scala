package xyz.kd5ujc.schema.fiber

import java.util.UUID

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.asset.AssetHolder

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * A side effect produced by evaluating a state-machine transition effect, represented as data.
 *
 * The fiber engine extracts these from an effect result's reserved keys (`_triggers`, `_scriptCall`,
 * `_spawn`, `_emit`, `_transferAsset`) into a single ordered `List[FiberEffect]` rather than scraping each
 * key into a separate field. Representing effects as data is what lets the engine route them uniformly — e.g.
 * a `Triggered` whose target fiber lives in another shard can become a cross-shard message instead of an
 * in-process dispatch (see docs/proposals/sharded-ml0-and-commitments.md).
 */
sealed trait FiberEffect

object FiberEffect {

  /** A cross-fiber trigger. Covers both `_triggers` and `_scriptCall` (both target a fiber by id). */
  final case class Triggered(trigger: FiberTrigger) extends FiberEffect

  /** A child state machine to spawn (`_spawn`). */
  final case class Spawned(directive: SpawnDirective) extends FiberEffect

  /** A user-defined event emitted for external consumption (`_emit`). */
  final case class Emitted(event: EmittedEvent) extends FiberEffect

  /**
   * A custody transfer of a fiber-held asset (`_transferAsset`, asset-model.md §10). Carries RESOLVED
   * values — the directive's JSON-Logic (`assetId`/`recipient` expressions) is evaluated against the
   * transition context DURING extraction (like `Triggered`/`Spawned`), not stored as raw logic.
   *
   * SECURITY (asset-model.md §9, R1): this effect carries NO authorization on its own. `EffectExtractor`
   * scrapes the reserved key verbatim; the only upstream gate is that the transition ran. The holder
   * defense (`asset.holder == AssetHolder.Fiber(emittingFiberId)` ∧ `behavior.transferable`) lives in
   * `AssetCombiner.applyFiberTransfer`, which NEVER trusts the extracted effect. `_transferAsset` effects
   * are applied ONLY from a fiber transition result, never from a raw `OttochainMessage` payload.
   */
  @derive(customizableEncoder, customizableDecoder)
  final case class AssetTransferred(assetId: UUID, recipient: AssetHolder) extends FiberEffect
}
