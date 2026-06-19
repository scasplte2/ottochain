package xyz.kd5ujc.shared_data.fiber.core

import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.fiber.{DynamicDependency, ExecutionLimits, FailureReason, FiberEffect}

/**
 * Pure application of `_addDependency` / `_setDependencyActive` mutations to a fiber's append-only
 * dynamic-dependency ledger, with DoS bounds enforced. Kept pure (no effect type) so it is trivially
 * unit-testable and deterministic across nodes.
 *
 * Semantics (see [[xyz.kd5ujc.schema.fiber.DynamicDependency]]):
 *   - UPSERT by `fiberId`: a mutation targeting an existing entry flips its `active` flag and PRESERVES
 *     the original `addedAt`; a mutation targeting a new `fiberId` APPENDS a fresh entry
 *     (`addedAt = ordinal`). The ledger therefore holds at most one entry per fiber and is never pruned.
 *   - Mutations apply left-to-right; the last mutation for a given `fiberId` wins.
 *   - Bounds (fail-closed — abort the transition):
 *       * a NEW fiberId pushing the ledger past `maxDependencyLedger` ⇒ DependencyLimitExceeded("ledger")
 *       * a final ACTIVE count above `maxActiveDependencies`           ⇒ DependencyLimitExceeded("active")
 *     The active cap is checked ONCE on the resulting ledger, so toggling a dep off-then-on within a
 *     single transition nets out correctly.
 */
object DependencyLedger {

  def applyMutations(
    current:   List[DynamicDependency],
    mutations: List[FiberEffect.DependencyMutated],
    ordinal:   SnapshotOrdinal,
    limits:    ExecutionLimits
  ): Either[FailureReason, List[DynamicDependency]] = {

    def step(
      ledger: List[DynamicDependency],
      m:      FiberEffect.DependencyMutated
    ): Either[FailureReason, List[DynamicDependency]] =
      ledger.indexWhere(_.fiberId == m.fiberId) match {
        case -1 =>
          // A new distinct fiber → the total-ledger bound applies (append-only, never pruned).
          if (ledger.size >= limits.maxDependencyLedger)
            Left(FailureReason.DependencyLimitExceeded("ledger", ledger.size + 1, limits.maxDependencyLedger))
          else
            Right(ledger :+ DynamicDependency(m.fiberId, m.active, ordinal))

        case idx =>
          // Existing entry → flip active, preserve the original addedAt (append-only history).
          Right(ledger.updated(idx, ledger(idx).copy(active = m.active)))
      }

    mutations
      .foldLeft[Either[FailureReason, List[DynamicDependency]]](Right(current)) { (acc, m) =>
        acc.flatMap(step(_, m))
      }
      .flatMap { finalLedger =>
        val activeCount = finalLedger.count(_.active)
        if (activeCount > limits.maxActiveDependencies)
          Left(FailureReason.DependencyLimitExceeded("active", activeCount, limits.maxActiveDependencies))
        else
          Right(finalLedger)
      }
  }

  /** The ACTIVE dependency fiber ids — the subset injected into the `machines` context each transition. */
  def activeIds(ledger: List[DynamicDependency]): Set[java.util.UUID] =
    ledger.collect { case d if d.active => d.fiberId }.toSet
}
