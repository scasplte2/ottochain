package xyz.kd5ujc.shared_data.genesis

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records}
import xyz.kd5ujc.shared_data.syntax.DataStateOps._

/**
 * Assembles a non-empty genesis `DataState` — the chain-side core of genesis-prep (#39) and the e2e
 * edge-case genesis crafting (#40). Pre-registers packages, fiber-instance aliases (with reverse records),
 * and pre-built fiber/script records. Crucially it reuses the SAME commitment derivations the live combiner
 * uses (`DataStateOps.withAlias` / `withFibersAndScripts`, and `computeDigest` for package entries), so a
 * crafted genesis is byte-consistent with on-chain state — the node can boot from it and prove against it.
 *
 * Package/alias/fiber *content* (schemaHash, machineShape, logicHash, fiber records) comes from a pinned
 * ottochain-sdk release (genesis-prep) or a test fixture (e2e); this layer only guarantees consistency.
 */
object GenesisBuilder {

  /** A package to pre-register at genesis (one initial version; further versions are published on-chain). */
  final case class PackageSpec(
    name:         RegistryName,
    version:      SemVer,
    schemaHash:   Hash,
    logicHash:    Hash,
    machineShape: MachineShape,
    owner:        Set[Address],
    strict:       Boolean = false,
    metadata:     SortedMap[String, String] = SortedMap.empty
  )

  /** A nickname for a fiber instance to pre-register at genesis (#29): forward entry + reverse record. */
  final case class AliasSpec(
    name:          RegistryName,
    targetFiberId: UUID,
    owner:         Set[Address] = Set.empty,
    metadata:      SortedMap[String, String] = SortedMap.empty
  )

  /** Pre-register only packages (the common std-set case). */
  def withPackages[F[_]: Async](
    specs: List[PackageSpec],
    at:    SnapshotOrdinal = SnapshotOrdinal.MinValue
  ): F[DataState[OnChain, CalculatedState]] =
    build[F](packages = specs, at = at)

  /** Assemble an arbitrary genesis from packages, aliases, and pre-built fiber/script records. */
  def build[F[_]: Async](
    packages: List[PackageSpec] = Nil,
    aliases:  List[AliasSpec] = Nil,
    fibers:   Map[UUID, Records.StateMachineFiberRecord] = Map.empty,
    scripts:  Map[UUID, Records.ScriptFiberRecord] = Map.empty,
    at:       SnapshotOrdinal = SnapshotOrdinal.MinValue
  ): F[DataState[OnChain, CalculatedState]] =
    for {
      withPkgs <- packagesState[F](packages, at)
      withAls <- aliases.foldLeftM(withPkgs) { (st, a) =>
        st.withAlias[F](a.name, aliasEntry(a), a.targetFiberId)
      }
      withAll <- withAls.withFibersAndScripts[F](fibers, scripts)
    } yield withAll

  private def aliasEntry(a: AliasSpec): RegistryEntry =
    RegistryEntry(a.name, a.owner, RegistryTarget.InstanceAlias(a.targetFiberId), a.metadata)

  private def packagesState[F[_]: Async](
    specs: List[PackageSpec],
    at:    SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    specs
      .traverse { s =>
        val rv =
          RegisteredVersion(
            s.version,
            s.schemaHash,
            s.logicHash,
            RegistryShape.Machine(s.machineShape),
            RegistryStatus.Active,
            at,
            s.strict
          )
        val entry = RegistryEntry(s.name, s.owner, RegistryTarget.SchemaPackage(VersionLineage.of(rv)), s.metadata)
        entry.computeDigest.map(hash => (s.name, entry, hash))
      }
      .map { built =>
        val registry = SortedMap.from(built.map { case (n, e, _) => n -> e })
        val commits = SortedMap.from(built.map { case (n, _, h) => n -> h })
        DataState(
          OnChain.genesis.copy(registryCommits = commits),
          CalculatedState.genesis.copy(registry = registry)
        )
      }
}
