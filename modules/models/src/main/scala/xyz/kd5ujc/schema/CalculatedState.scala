package xyz.kd5ujc.schema

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.metagraph_sdk.lifecycle.committed.{CommitKey, CommittedView}
import io.constellationnetwork.security.hash.Hash

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
  reverseNames: SortedMap[UUID, RegistryName] = SortedMap.empty,
  // Asset instances (asset-model §5b): UUID -> its AssetRecord (dedicated record, NOT a fiber).
  assets: SortedMap[UUID, Records.AssetRecord] = SortedMap.empty,
  // Used commit-reveal nonces per asset (asset-model §8/§5e), BOUNDED — pruned past expiresAt in combine.
  usedNonces: SortedMap[UUID, SortedSet[Long]] = SortedMap.empty,
  // Cumulative L1 fast-path commit maps, moved here from OnChain v1 (onchain-incrementals RFC):
  // CalculatedState is rooted (committed MPT), never wire-shipped, so unbounded growth is safe here.
  // Written by the SAME DataStateOps/AssetCombiner folds that write the per-batch OnChain.touched*
  // deltas — the two cannot diverge. Projected as commit/f|a|r/<id> leaves for provable DL1 healing.
  fiberCommits:    SortedMap[UUID, FiberCommit] = SortedMap.empty,
  assetCommits:    SortedMap[UUID, AssetCommit] = SortedMap.empty,
  registryCommits: SortedMap[RegistryName, Hash] = SortedMap.empty
) extends DataCalculatedState

object CalculatedState {

  val genesis: CalculatedState =
    CalculatedState(
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty
    )

  /**
   * Projects the FULL calculated state into metakit's committed dictionary, so the two-tier
   * committed root (MPT state-dict + SMT epoch catalog) commits to all of it. That root becomes the
   * currency snapshot's `calculatedStateProof` — see docs/proposals/committed-state-migration.md.
   *
   * Keys are lowercase, slash-namespaced CommitKeys:
   *   - `fiber/<uuid>`    from `stateMachines`
   *   - `script/<uuid>`   from `scripts`
   *   - `registry/<name>` from `registry` (the name renders lowercase; an over-long name that would
   *                        overflow a 64-char CommitKey segment falls back to `registry/h/<sha256>`)
   *   - `reverse/<uuid>`  from `reverseNames`
   *   - `asset/<uuid>`    from `assets` (asset-model §5b — instance custody, light-client provable)
   *   - `nonce/<uuid>`    from `usedNonces`; the `SortedSet[Long]` value is committed as a JSON array of
   *                        its sorted elements (total, deterministic)
   *   - `commit/f/<uuid>` from `fiberCommits`  — the L1 fast-path triple (onchain-incrementals RFC §3.1);
   *   - `commit/a/<uuid>` from `assetCommits`     these small leaves are what a DL1 heals its CommitIndex
   *   - `commit/r/<name>` from `registryCommits`  from, batch-proof-verified against the breadcrumb mptRoot
   *                        (over-long names fall back to `commit/r/h/<sha256>` like `registry/`)
   *
   * Asset POLICIES need no projection of their own — they live in `registry` as `RegistryEntry`s
   * (`AssetPolicyPackage`) and are already covered by the `registry/<name>` key.
   *
   * Key derivation is TOTAL — `entries` has no error channel, and a non-total key would throw inside
   * combine (a consensus halt). UUIDs always fit a segment; only an over-long registry name takes
   * the hashed fallback. The default structural `delta` is used for now; a combiner-driven delta is
   * a performance follow-up.
   */
  implicit val committedView: CommittedView[CalculatedState] =
    new CommittedView[CalculatedState] {

      def entries(s: CalculatedState): SortedMap[CommitKey, Json] = {
        val fibers = s.stateMachines.toList.map { case (id, r) => CommitKey.unsafe(s"fiber/$id") -> r.asJson }
        val scripts = s.scripts.toList.map { case (id, r) => CommitKey.unsafe(s"script/$id") -> r.asJson }
        val registry = s.registry.toList.map { case (n, e) => registryKey(n) -> e.asJson }
        val reverse = s.reverseNames.toList.map { case (id, n) =>
          CommitKey.unsafe(s"reverse/$id") -> Json.fromString(n.render)
        }
        val assets = s.assets.toList.map { case (id, r) => CommitKey.unsafe(s"asset/$id") -> r.asJson }
        val nonces = s.usedNonces.toList.map { case (id, ns) =>
          CommitKey.unsafe(s"nonce/$id") -> Json.fromValues(ns.toList.map(Json.fromLong))
        }
        val fiberCommits = s.fiberCommits.toList.map { case (id, c) => CommitKey.unsafe(s"commit/f/$id") -> c.asJson }
        val assetCommits = s.assetCommits.toList.map { case (id, c) => CommitKey.unsafe(s"commit/a/$id") -> c.asJson }
        val registryCommits = s.registryCommits.toList.map { case (n, h) => commitRegistryKey(n) -> h.asJson }
        SortedMap.from(
          fibers ::: scripts ::: registry ::: reverse ::: assets ::: nonces :::
          fiberCommits ::: assetCommits ::: registryCommits
        )
      }
    }

  /** `registry/<name>`, or `registry/h/<sha256hex(name)>` when the name overflows a CommitKey segment. */
  private def registryKey(name: RegistryName): CommitKey =
    CommitKey.from(s"registry/${name.render}").getOrElse(CommitKey.unsafe(s"registry/h/${sha256Hex(name.render)}"))

  /** `commit/r/<name>`, with the same hashed fallback as `registryKey` (key derivation must stay TOTAL). */
  private def commitRegistryKey(name: RegistryName): CommitKey =
    CommitKey.from(s"commit/r/${name.render}").getOrElse(CommitKey.unsafe(s"commit/r/h/${sha256Hex(name.render)}"))

  private def sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString
}
