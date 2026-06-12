package xyz.kd5ujc.shared_data.lifecycle.combine

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.syntax.all._

import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Combiner for the registry. The chain enforces structural invariants — append-only / immutable /
 * strictly-monotonic via [[VersionLineage]], ownership, and a descriptor size bound — and never PARSES the
 * protobuf descriptor (it hashes `schemaB64` into `schemaHash` and drops the bytes; the typed `schemaShape`
 * projection is stored as advisory domain metadata). The JSON-Logic `definition` IS typed: it is hashed via
 * `computeDigest` into `logicHash` — the same canonical digest a fiber computes — so a referencing fiber's
 * definition can be verified on-chain (#37). `VersionLineage`'s `Either` is the authoritative invariant
 * enforcement; a rejected publish/status aborts the update (the FiberCombiner combine-time-raise pattern),
 * and `RegistryValidator` previews the same checks for early, structured rejection.
 *
 * TODO(economics): charge registrationGas = bytes * registrationGasPerByte once the fee/balance subsystem
 * exists (economics-and-state-rent.md); for now `maxBundleBytes` is the interim cost control.
 */
class RegistryCombiner[F[_]: Async: SecurityProvider](
  current:        DataState[OnChain, CalculatedState],
  ctx:            L0NodeContext[F],
  maxBundleBytes: Long
) {

  def publishVersion(update: Signed[Updates.PublishVersion]): F[DataState[OnChain, CalculatedState]] = {
    val pv = update.value
    for {
      currentOrdinal <- ctx.getCurrentOrdinal
      signers        <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)
      _ <- Async[F]
        .raiseError[Unit](CombineRejected(s"registry name ${pv.name.render} uses a reserved label"))
        .whenA(RegistryName.isReserved(pv.name))
      _ <- Async[F]
        .raiseError[Unit](
          CombineRejected(s"registry descriptor for ${pv.name.render} exceeds $maxBundleBytes bytes")
        )
        .whenA(pv.schemaB64.length.toLong > maxBundleBytes)
      _ <- RegistryMetadata
        .validate(pv.metadata.getOrElse(SortedMap.empty[String, String]))
        .fold(
          e => Async[F].raiseError[Unit](CombineRejected(s"invalid metadata for ${pv.name.render}: $e")),
          _ => Async[F].unit
        )
      schemaHash <- pv.schemaB64.computeDigest
      // logicHash is the canonical digest of the TYPED definition — identical to the digest a fiber computes
      // on its own definition — so a referencing fiber can be verified on-chain (#37 verified binding).
      logicHash <- pv.definition.computeDigest
      rv = RegisteredVersion(
        version = pv.version,
        schemaHash = schemaHash,
        logicHash = logicHash,
        schemaShape = pv.schemaShape,
        status = RegistryStatus.Active,
        registeredAt = currentOrdinal,
        strict = pv.strict
      )
      updatedEntry <- current.calculated.registry.get(pv.name) match {
        case None =>
          // First publish: the signer(s) claim the name and become owners (npm-publish semantics).
          (RegistryEntry(
            pv.name,
            signers,
            RegistryTarget.SchemaPackage(VersionLineage.of(rv)),
            pv.metadata.getOrElse(SortedMap.empty[String, String])
          ): RegistryEntry).pure[F]
        case Some(entry) =>
          if (!signers.exists(entry.owner.contains))
            Async[F].raiseError[RegistryEntry](CombineRejected(s"unauthorized publish to ${pv.name.render}"))
          else
            entry.target match {
              case RegistryTarget.SchemaPackage(lineage) =>
                lineage
                  .publish(rv)
                  .fold(
                    e =>
                      Async[F]
                        .raiseError[RegistryEntry](CombineRejected(s"publish rejected for ${pv.name.render}: $e")),
                    l => entry.copy(target = RegistryTarget.SchemaPackage(l)).pure[F]
                  )
              case other =>
                Async[F].raiseError[RegistryEntry](
                  CombineRejected(s"${pv.name.render} is not a schema package (${other.getClass.getSimpleName})")
                )
            }
      }
      result <- current.withRegistryEntry[F](pv.name, updatedEntry)
      _      <- Slf4jLogger.getLogger[F].info(s"[registry-publish] applied ${pv.name.render}@${pv.version.render}")
    } yield result
  }

  def setVersionStatus(update: Signed[Updates.SetVersionStatus]): F[DataState[OnChain, CalculatedState]] = {
    val ss = update.value
    for {
      signers <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)
      entry <- current.calculated.registry
        .get(ss.name)
        .fold(Async[F].raiseError[RegistryEntry](CombineRejected(s"unknown registry name ${ss.name.render}")))(
          _.pure[F]
        )
      _ <- Async[F]
        .raiseError[Unit](CombineRejected(s"unauthorized status change for ${ss.name.render}"))
        .whenA(!signers.exists(entry.owner.contains))
      updated <- entry.target match {
        case RegistryTarget.SchemaPackage(lineage) =>
          lineage
            .setStatus(ss.version, ss.status)
            .fold(
              e =>
                Async[F]
                  .raiseError[RegistryEntry](CombineRejected(s"status change rejected for ${ss.name.render}: $e")),
              l => entry.copy(target = RegistryTarget.SchemaPackage(l)).pure[F]
            )
        case other =>
          Async[F].raiseError[RegistryEntry](
            CombineRejected(s"${ss.name.render} is not a schema package (${other.getClass.getSimpleName})")
          )
      }
      result <- current.withRegistryEntry[F](ss.name, updated)
    } yield result
  }

  /**
   * Register a fiber alias (#29): the name's TLD must be `.machine`/`.script` and match the target fiber's
   * kind, the signer must own the target fiber, and the name must be free or owned by the signer. Sets the
   * forward entry + the canonical reverse record. Aborts (raises) on any violation.
   */
  def registerAlias(update: Signed[Updates.RegisterAlias]): F[DataState[OnChain, CalculatedState]] = {
    val ra = update.value
    for {
      signers <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)
      _ <- Async[F]
        .raiseError[Unit](CombineRejected(s"alias name ${ra.name.render} uses a reserved label"))
        .whenA(RegistryName.isReserved(ra.name))
      _ <- RegistryMetadata
        .validate(ra.metadata.getOrElse(SortedMap.empty[String, String]))
        .fold(
          e => Async[F].raiseError[Unit](CombineRejected(s"invalid metadata for ${ra.name.render}: $e")),
          _ => Async[F].unit
        )
      targetOwners <- aliasTargetOwners(ra.name.tld, ra.targetFiberId)
      _ <- Async[F]
        .raiseError[Unit](
          CombineRejected(s"signer does not own fiber ${ra.targetFiberId} for alias ${ra.name.render}")
        )
        .whenA(!signers.exists(targetOwners.contains))
      _ <- current.calculated.registry.get(ra.name) match {
        case None                                                => Async[F].unit
        case Some(entry) if signers.exists(entry.owner.contains) => Async[F].unit
        case Some(_) =>
          Async[F].raiseError[Unit](CombineRejected(s"alias name ${ra.name.render} is owned by another address"))
      }
      entry = RegistryEntry(ra.name, signers, RegistryTarget.InstanceAlias(ra.targetFiberId))
      result <- current.withAlias[F](ra.name, entry, ra.targetFiberId)
    } yield result
  }

  /** Owners of the fiber an alias targets, after checking the TLD matches the fiber's kind (aborts otherwise). */
  private def aliasTargetOwners(tld: NameTld, fiberId: UUID): F[Set[Address]] =
    tld match {
      case NameTld.Machine =>
        current.calculated.stateMachines
          .get(fiberId)
          .fold(
            Async[F].raiseError[Set[Address]](
              CombineRejected(s".machine alias target $fiberId is not a state-machine fiber")
            )
          )(_.owners.pure[F])
      case NameTld.Script =>
        current.calculated.scripts
          .get(fiberId)
          .fold(
            Async[F].raiseError[Set[Address]](
              CombineRejected(s".script alias target $fiberId is not a script fiber")
            )
          )(_.owners.pure[F])
      case NameTld.Package =>
        Async[F].raiseError[Set[Address]](
          CombineRejected("cannot register a .package name as a fiber alias (use PublishVersion)")
        )
    }
}

object RegistryCombiner {

  def apply[F[_]: Async: SecurityProvider](
    current:        DataState[OnChain, CalculatedState],
    ctx:            L0NodeContext[F],
    maxBundleBytes: Long
  ): RegistryCombiner[F] =
    new RegistryCombiner[F](current, ctx, maxBundleBytes)
}
