package xyz.kd5ujc.schema

import java.util.UUID

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.{CommitDelta, CommitKey, CommittedView}

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.registry.{RegistryEntry, RegistryName}

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive
import io.circe.Json
import io.circe.syntax.EncoderOps

@derive(customizableEncoder, customizableDecoder)
case class CalculatedState(
  stateMachines: SortedMap[UUID, Records.StateMachineFiberRecord],
  scripts:       SortedMap[UUID, Records.ScriptFiberRecord],
  registry:      SortedMap[RegistryName, RegistryEntry] = SortedMap.empty,
  // Reverse records (#29): fiber UUID -> its canonical registered name, for human-readable audit trails.
  reverseNames: SortedMap[UUID, RegistryName] = SortedMap.empty
) extends DataCalculatedState

object CalculatedState {

  val genesis: CalculatedState =
    CalculatedState(SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty)

  /**
   * Projection into the committed state dictionary (metakit `lifecycle/committed`).
   *
   * Namespaces (registered in metakit's `docs/committed-namespaces.md`):
   *   - `fiber/<uuid>`    -- state-machine fiber records (`UUID.toString` is lowercase hyphenated,
   *                          which is valid `CommitKey` segment grammar)
   *   - `script/<id>`     -- script records
   *   - `registry/<name>` + `reverse/<uuid>` -- the registry + reverse-name maps ARE now part of
   *                          `CalculatedState` (versionable-contracts merge) but are NOT YET projected
   *                          into the committed root. FOLLOW-UP: extend this view to cover them (needs a
   *                          RegistryName-keyed projection + a check that CommitKey grammar permits the
   *                          dotted `labels.tld` form) so registry state is included in the commitment.
   *
   * Values are the records' canonical circe projections; the metakit committed layer
   * canonicalizes (RFC 8785) and hashes them when building the MPT, so byte-determinism does not
   * depend on field ordering here. `SortedMap` (required by [[CommittedView]]) makes the
   * enumeration order canonical for deltas/snapshots.
   */
  implicit val committedView: CommittedView[CalculatedState] = new CommittedView[CalculatedState] {

    private def fiberKey(id:  UUID): CommitKey = CommitKey.unsafe(s"fiber/$id")
    private def scriptKey(id: UUID): CommitKey = CommitKey.unsafe(s"script/$id")

    def entries(s: CalculatedState): SortedMap[CommitKey, Json] =
      SortedMap.from(
        s.stateMachines.iterator.map { case (id, r) => fiberKey(id) -> r.asJson } ++
        s.scripts.iterator.map { case (id, r) => scriptKey(id) -> r.asJson }
      )

    /**
     * Delta fast-path: diff the record maps by case-class equality first and only serialize the
     * records that actually changed -- equal records always project to equal Json, so this is
     * exactly the default `entries`-level structural diff, minus the redundant encoding work.
     */
    override def delta(prev: CalculatedState, next: CalculatedState): CommitDelta = {
      def changed[R](p: SortedMap[UUID, R], n: SortedMap[UUID, R], key: UUID => CommitKey)(
        encode: R => Json
      ): (Iterator[(CommitKey, Json)], Iterator[CommitKey]) =
        (
          n.iterator.collect { case (id, r) if !p.get(id).contains(r) => key(id) -> encode(r) },
          p.keysIterator.filterNot(n.contains).map(key)
        )

      val (fiberUpserts, fiberRemoves) = changed(prev.stateMachines, next.stateMachines, fiberKey)(_.asJson)
      val (scriptUpserts, scriptRemoves) = changed(prev.scripts, next.scripts, scriptKey)(_.asJson)

      CommitDelta(
        SortedMap.from(fiberUpserts ++ scriptUpserts),
        SortedSet.from(fiberRemoves ++ scriptRemoves)
      )
    }
  }
}
