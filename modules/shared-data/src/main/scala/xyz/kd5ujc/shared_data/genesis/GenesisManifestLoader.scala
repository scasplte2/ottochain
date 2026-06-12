package xyz.kd5ujc.shared_data.genesis

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.{CalculatedState, GenesisManifest, OnChain}

/**
 * Turns an SDK-produced [[GenesisManifest]] (#46) into a genesis `DataState`, computing each package's
 * `schemaHash` + `logicHash` with the chain's OWN `computeDigest` and then delegating to [[GenesisBuilder]].
 *
 * This is the chain-authoritative bridge: because the chain hashes the manifest's `definition` itself, the
 * registered `logicHash` equals what a fiber's `definition.computeDigest` yields at bind time — identical
 * by construction, so there is no hash-parity coupling with the SDK (the SDK ships content, never consensus
 * hashes). `at` stamps the genesis ordinal; `owner` is the std-package owner (e.g. a governance address).
 */
object GenesisManifestLoader {

  def fromManifest[F[_]: Async](
    manifest: GenesisManifest,
    owner:    Set[Address] = Set.empty,
    at:       SnapshotOrdinal = SnapshotOrdinal.MinValue
  ): F[DataState[OnChain, CalculatedState]] =
    manifest.packages
      .traverse { p =>
        for {
          logicHash  <- p.definition.computeDigest
          schemaHash <- p.machineShape.computeDigest
        } yield GenesisBuilder.PackageSpec(
          name = p.name,
          version = p.semver,
          schemaHash = schemaHash,
          logicHash = logicHash,
          machineShape = p.machineShape,
          owner = owner,
          strict = p.strict,
          metadata = p.metadata
        )
      }
      .flatMap(specs => GenesisBuilder.withPackages[F](specs, at))
}
