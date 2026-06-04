package xyz.kd5ujc.shared_data.lifecycle.combine

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.syntax.all._

/**
 * Combiner for the content-agnostic registry. The chain enforces only structural invariants —
 * append-only / immutable / strictly-monotonic via [[VersionLineage]], ownership, and a size bound — and
 * NEVER parses the protobuf descriptor or JSON-Logic definition. It hashes the base64 blobs into
 * schemaHash/logicHash, commits the hashes, and drops the bytes (the bytes live in the registration
 * update's history + the Bridge store; see schema-architecture.md §4a). `VersionLineage`'s `Either` is the
 * authoritative enforcement here; a rejected publish/status aborts the update (the FiberCombiner
 * combine-time-raise pattern). Validation arms in `Validator` are a TODO refinement (#23c).
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
        .raiseError[Unit](new RuntimeException(s"registry bundle for ${pv.name.render} exceeds $maxBundleBytes bytes"))
        .whenA(pv.schemaB64.length.toLong + pv.definitionB64.length.toLong > maxBundleBytes)
      schemaHash <- pv.schemaB64.computeDigest
      logicHash  <- pv.definitionB64.computeDigest
      rv = RegisteredVersion(
        version = pv.version,
        schemaHash = schemaHash,
        logicHash = logicHash,
        stateMessage = pv.stateMessage,
        commands = pv.commands,
        status = RegistryStatus.Active,
        registeredAt = currentOrdinal
      )
      updatedEntry <- current.calculated.registry.get(pv.name) match {
        case None =>
          // First publish: the signer(s) claim the name and become owners (npm-publish semantics).
          (RegistryEntry(pv.name, signers, RegistryTarget.SchemaPackage(VersionLineage.of(rv))): RegistryEntry).pure[F]
        case Some(entry) =>
          if (!signers.exists(entry.owner.contains))
            Async[F].raiseError[RegistryEntry](new RuntimeException(s"unauthorized publish to ${pv.name.render}"))
          else
            entry.target match {
              case RegistryTarget.SchemaPackage(lineage) =>
                lineage
                  .publish(rv)
                  .fold(
                    e =>
                      Async[F]
                        .raiseError[RegistryEntry](new RuntimeException(s"publish rejected for ${pv.name.render}: $e")),
                    l => entry.copy(target = RegistryTarget.SchemaPackage(l)).pure[F]
                  )
            }
      }
      result <- current.withRegistryEntry[F](pv.name, updatedEntry)
    } yield result
  }

  def setVersionStatus(update: Signed[Updates.SetVersionStatus]): F[DataState[OnChain, CalculatedState]] = {
    val ss = update.value
    for {
      signers <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)
      entry <- current.calculated.registry
        .get(ss.name)
        .fold(Async[F].raiseError[RegistryEntry](new RuntimeException(s"unknown registry name ${ss.name.render}")))(
          _.pure[F]
        )
      _ <- Async[F]
        .raiseError[Unit](new RuntimeException(s"unauthorized status change for ${ss.name.render}"))
        .whenA(!signers.exists(entry.owner.contains))
      updated <- entry.target match {
        case RegistryTarget.SchemaPackage(lineage) =>
          lineage
            .setStatus(ss.version, ss.status)
            .fold(
              e =>
                Async[F]
                  .raiseError[RegistryEntry](new RuntimeException(s"status change rejected for ${ss.name.render}: $e")),
              l => entry.copy(target = RegistryTarget.SchemaPackage(l)).pure[F]
            )
      }
      result <- current.withRegistryEntry[F](ss.name, updated)
    } yield result
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
