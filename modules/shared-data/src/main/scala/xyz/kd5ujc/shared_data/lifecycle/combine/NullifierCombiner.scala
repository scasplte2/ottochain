package xyz.kd5ujc.shared_data.lifecycle.combine

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber.{ExecutionLimits, FiberEffect}
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.syntax.all._

import monocle.Monocle.toAppliedFocusOps
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Combiner for the protocol nullifier set (docs/proposals/protocol-nullifier-set.md) — the AUTHORITATIVE,
 * deterministic enforcement site and the SOLE writer of `CalculatedState.nullifiers`. Applied from
 * [[FiberCombiner.handleCommittedOutcome]] within the SAME combiner pass as the fiber-state mutation, after
 * the asset transfers, mirroring [[AssetCombiner.applyFiberTransfers]].
 *
 * ALL-OR-NOTHING per transition: any hit — an already-present nf (double-spend), a per-emitter cap breach —
 * raises a graceful [[CombineRejected]], which `Combiner.insert` turns into a single `RejectionReceipt` for
 * the whole update and discards the partial mutation (rule #2; the #154 lesson — never a snapshot abort).
 * The check must NEVER appear in `validateSignedUpdate` (a stateful read at block validity is the rule-#3
 * TOCTOU block-poisoning hazard); sequential combine gives intra-batch double-spend protection for free.
 *
 * DOMAIN ISOLATION: the map is keyed by the EMITTING fiber id (stamped by the engine, never authored), so a
 * fiber can only ever consume into its OWN `nullifier/<domain>/<nf>` namespace — the cross-app griefing
 * vector is closed by construction. Writes touch ONLY `calculated.nullifiers` — never `onChain` (decision
 * #6: no OnChain / CommitIndex change; DL1 never reads nullifiers).
 */
class NullifierCombiner[F[_]: Async: SecurityProvider](ctx: L0NodeContext[F]) {

  /**
   * Apply the `_consumeNullifier` effects a committed fiber transition produced. `consumptionsByEmitter` keys
   * the per-fiber nf lists by the EMITTING fiber id — the nullifier DOMAIN. Deterministic order: by emitter
   * UUID, preserving each list's emission order. The per-emitter cap
   * (`ExecutionLimits.maxNullifierConsumptions`) is checked up front; every nf is then absent-checked and
   * inserted with the CURRENT snapshot ordinal (the spent-at receipt, decision #7) in a single sequential
   * fold, so an intra-transition duplicate hits its own earlier insert and rejects.
   */
  def applyConsumptions(
    st:                    DataState[OnChain, CalculatedState],
    consumptionsByEmitter: Map[UUID, List[FiberEffect.NullifierConsumed]]
  ): F[DataState[OnChain, CalculatedState]] = {
    val ordered: List[(UUID, List[FiberEffect.NullifierConsumed])] =
      consumptionsByEmitter.toList.sortBy(_._1)

    val maxConsumptions = ExecutionLimits().maxNullifierConsumptions
    for {
      _ <- ordered.traverse_ { case (domain, ns) =>
        raiseRejected(
          ns.size <= maxConsumptions,
          s"fiber $domain emitted ${ns.size} nullifier consumptions, exceeding maxNullifierConsumptions $maxConsumptions"
        )
      }
      currentOrdinal <- ctx.getCurrentOrdinal
      result <- ordered.flatMap { case (domain, ns) => ns.map(domain -> _) }.foldLeftM(st) {
        case (acc, (domain, consumption)) => applyConsumption(acc, domain, consumption, currentOrdinal)
      }
    } yield result
  }

  /**
   * Apply ONE nullifier consumption: reject if `nf` is already present under `domain` (the double-spend
   * gate), else insert `nf -> ordinal`. The nf value is guaranteed normalized 64-hex by the extractor
   * (`NullifierHex`), which is what keeps the committed `nullifier/<domain>/<nf>` key derivation total.
   */
  private def applyConsumption(
    st:          DataState[OnChain, CalculatedState],
    domain:      UUID,
    consumption: FiberEffect.NullifierConsumed,
    ordinal:     SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] = {
    val nf = consumption.nullifier
    val domainSet = st.calculated.nullifiers.getOrElse(domain, SortedMap.empty[Hash, SnapshotOrdinal])
    if (domainSet.contains(nf))
      Async[F].raiseError(CombineRejected(s"nullifier already consumed (double-spend): $domain/${nf.value}"))
    else
      Slf4jLogger
        .getLogger[F]
        .info(s"[nullifier-consume] $domain/${nf.value} at ordinal ${ordinal.value.value}")
        .as(st.focus(_.calculated.nullifiers).modify(_.updated(domain, domainSet.updated(nf, ordinal))))
  }

  /** Raise a graceful `CombineRejected(reason)` unless `cond` holds. */
  private def raiseRejected(cond: Boolean, reason: => String): F[Unit] =
    Async[F].raiseError(CombineRejected(reason)).unlessA(cond)
}

object NullifierCombiner {

  def apply[F[_]: Async: SecurityProvider](ctx: L0NodeContext[F]): NullifierCombiner[F] =
    new NullifierCombiner[F](ctx)
}
