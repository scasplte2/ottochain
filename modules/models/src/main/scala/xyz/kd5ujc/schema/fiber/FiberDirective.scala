package xyz.kd5ujc.schema.fiber

import enumeratum._

/**
 * The reserved `_`-directive KEYWORDS the fiber engine extracts from a transition effect, at the granularity
 * of a single EXTRACTION HANDLER (one entry per `EffectExtractor` extractor method).
 *
 * Distinct from [[EffectKind]], which is the coarser POLICY family a directive belongs to (the
 * `allowedEffects` dial): `Triggers` + `ScriptCall` share `EffectKind.Trigger`, and the two dependency
 * keywords share `EffectKind.Dependency`. This enum exists so the engine's directive dispatch is an
 * EXHAUSTIVE, typed registry — `EffectExtractor.handlerFor` is a total match over `FiberDirective`, so
 * adding a directive without wiring its handler is a COMPILE error (not a silently-missing extractor) — and
 * so the set of reserved keys has a SINGLE source of truth ([[FiberDirective.keys]] / [[resultKeys]]).
 *
 * `values` order is the engine's effect-emission order (triggers, script call, spawn, emit, transfer,
 * dependency); `EffectExtractor.extractEffects` folds the handlers in this order, so the emitted
 * `List[FiberEffect]` ordering is defined HERE.
 */
sealed abstract class FiberDirective(val keys: Set[String], val family: EffectKind) extends EnumEntry

object FiberDirective extends Enum[FiberDirective] {
  val values: IndexedSeq[FiberDirective] = findValues

  case object Triggers extends FiberDirective(Set(ReservedKeys.TRIGGERS), EffectKind.Trigger)
  case object ScriptCall extends FiberDirective(Set(ReservedKeys.SCRIPT_CALL), EffectKind.Trigger)
  case object Spawn extends FiberDirective(Set(ReservedKeys.SPAWN), EffectKind.Spawn)
  case object Emit extends FiberDirective(Set(ReservedKeys.EMIT), EffectKind.Emit)
  case object Transfer extends FiberDirective(Set(ReservedKeys.TRANSFER_ASSET), EffectKind.Transfer)

  case object Dependency
      extends FiberDirective(
        Set(ReservedKeys.ADD_DEPENDENCY, ReservedKeys.SET_DEPENDENCY_ACTIVE),
        EffectKind.Dependency
      )

  /**
   * Reserved keys honoured from the post-evaluation RESULT position — i.e. every directive EXCEPT `_spawn`,
   * which is sourced from the authored effect EXPRESSION (and so was already injection-immune). The
   * injection-immune `EffectExtractor.authoredDirectiveResult` gates the result-surfacing directive keys on
   * exactly this set.
   */
  val resultKeys: Set[String] = values.filterNot(_ == Spawn).flatMap(_.keys).toSet
}
