package xyz.kd5ujc.shared_data.genesis

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

/**
 * Builds a non-empty genesis `DataState` with a pinned set of packages pre-registered — the chain-side
 * core of genesis-prep (#39) and the e2e edge-case genesis crafting (#40). The `std.*` package data
 * (schemaHash, schemaShape, logicHash) comes from a pinned ottochain-sdk release off-chain; this assembles
 * a *consistent* genesis (registry entries + matching `OnChain.registryCommits`) the metagraph can boot
 * from instead of the empty genesis. Pure apart from the canonical hash for each entry's commitment.
 */
object GenesisBuilder {

  /** A package to pre-register at genesis (one initial version; further versions are published on-chain). */
  final case class PackageSpec(
    name:        RegistryName,
    version:     SemVer,
    schemaHash:  Hash,
    logicHash:   Hash,
    schemaShape: SchemaShape,
    owner:       Set[Address],
    strict:      Boolean = false,
    metadata:    SortedMap[String, String] = SortedMap.empty
  )

  /** Build a genesis `DataState` with `specs` pre-registered at ordinal `at` (default 0). */
  def withPackages[F[_]: Async](
    specs: List[PackageSpec],
    at:    SnapshotOrdinal = SnapshotOrdinal.MinValue
  ): F[DataState[OnChain, CalculatedState]] =
    specs
      .traverse { s =>
        val rv =
          RegisteredVersion(s.version, s.schemaHash, s.logicHash, s.schemaShape, RegistryStatus.Active, at, s.strict)
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
