package xyz.kd5ujc.schema.fiber

/**
 * A side effect produced by evaluating a state-machine transition effect, represented as data.
 *
 * The fiber engine extracts these from an effect result's reserved keys (`_triggers`, `_scriptCall`,
 * `_spawn`, `_emit`) into a single ordered `List[FiberEffect]` rather than scraping each key into a
 * separate field. Representing effects as data is what lets the engine route them uniformly — e.g. a
 * `Triggered` whose target fiber lives in another shard can become a cross-shard message instead of an
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
}
