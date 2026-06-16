package xyz.kd5ujc.shared_data.lifecycle.combine

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.syntax.all._

import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Combiner for asset operations (asset-model.md §5/§6).
 *
 * Phase 3 scope: `CreateAssetPolicy` is IMPLEMENTED — it reuses the registry [[VersionLineage]] machinery
 * verbatim (npm-publish semantics: first publish claims the `.asset` name and makes the signer the owner),
 * exactly like `RegistryCombiner.publishMachineVersion`, because an asset policy is governed identically to a
 * schema package and there is no stateful-asset logic involved. The stateful asset ops — `MintAsset`,
 * `ApplyMorphism`, `AuthorizeCompose` — are GRACEFULLY rejected as `CombineRejected` ("phase 4"): the
 * combine fold catches them, records a `RejectionReceipt`, and continues (the snapshot is never aborted).
 * The real stateful asset engine (`meet`/codomain/holder transfer/mint into `CalculatedState.assets`/nonce
 * consumption + the `OnChain.assetCommits` write-back) is Phase 4.
 *
 * CLAUDE.md invariant #3: all stateful policy/asset/nonce lineage reads are combiner-only and graceful; they
 * NEVER appear in `validateSignedUpdate` (a TOCTOU block-poisoning hazard).
 */
class AssetCombiner[F[_]: Async: SecurityProvider](
  current:        DataState[OnChain, CalculatedState],
  ctx:            L0NodeContext[F],
  maxBundleBytes: Long
) {

  /**
   * Publish an asset-policy package version (asset-model.md §5a). Mirrors
   * `RegistryCombiner.publishMachineVersion`, but builds a [[RegistryTarget.AssetPolicyPackage]] whose
   * versions carry a [[RegistryShape.AssetPolicy]] shape. There is no protobuf descriptor or JSON-Logic
   * definition for a policy, so `schemaHash` and `logicHash` are both the canonical digest of the typed
   * policy shape (the registered "logic" of an asset policy IS its behavior/supply/morphisms/stateShape).
   */
  def createAssetPolicy(update: Signed[Updates.CreateAssetPolicy]): F[DataState[OnChain, CalculatedState]] = {
    val cap = update.value
    val shape: RegistryShape.AssetPolicy =
      RegistryShape.AssetPolicy(cap.behavior, cap.supply, cap.morphisms, cap.stateShape)
    for {
      currentOrdinal <- ctx.getCurrentOrdinal
      signers        <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)
      _ <- Async[F]
        .raiseError[Unit](CombineRejected(s"asset policy name ${cap.name.render} uses a reserved label"))
        .whenA(RegistryName.isReserved(cap.name))
      _ <- Async[F]
        .raiseError[Unit](CombineRejected(s"asset policy ${cap.name.render} must use the .asset TLD"))
        .whenA(cap.name.tld != NameTld.Asset)
      _ <- RegistryMetadata
        .validate(cap.metadata.getOrElse(SortedMap.empty[String, String]))
        .fold(
          e => Async[F].raiseError[Unit](CombineRejected(s"invalid metadata for ${cap.name.render}: $e")),
          _ => Async[F].unit
        )
      // No descriptor / no JSON-Logic definition: the policy shape is the registered artifact, so both the
      // schema commitment and the verified-binding logic anchor are the digest of the policy shape.
      shapeHash <- shape.computeDigest
      rv = RegisteredVersion(
        version = cap.version,
        schemaHash = shapeHash,
        logicHash = shapeHash,
        shape = shape,
        status = RegistryStatus.Active,
        registeredAt = currentOrdinal,
        strict = false
      )
      updatedEntry <- current.calculated.registry.get(cap.name) match {
        case None =>
          (RegistryEntry(
            cap.name,
            signers,
            RegistryTarget.AssetPolicyPackage(VersionLineage.of(rv)),
            cap.metadata.getOrElse(SortedMap.empty[String, String])
          ): RegistryEntry).pure[F]
        case Some(entry) =>
          if (!signers.exists(entry.owner.contains))
            Async[F].raiseError[RegistryEntry](CombineRejected(s"unauthorized publish to ${cap.name.render}"))
          else
            entry.target match {
              case RegistryTarget.AssetPolicyPackage(lineage) =>
                lineage
                  .publish(rv)
                  .fold(
                    e =>
                      Async[F]
                        .raiseError[RegistryEntry](CombineRejected(s"publish rejected for ${cap.name.render}: $e")),
                    l => entry.copy(target = RegistryTarget.AssetPolicyPackage(l)).pure[F]
                  )
              case other =>
                Async[F].raiseError[RegistryEntry](
                  CombineRejected(
                    s"${cap.name.render} is not an asset-policy package (${other.getClass.getSimpleName})"
                  )
                )
            }
      }
      result <- current.withRegistryEntry[F](cap.name, updatedEntry)
      _ <- Slf4jLogger.getLogger[F].info(s"[asset-policy-publish] applied ${cap.name.render}@${cap.version.render}")
    } yield {
      val _ = maxBundleBytes
      result
    }
  }

  /** Phase-4 stub: mint into `CalculatedState.assets` + the `OnChain.assetCommits` write-back is not yet wired. */
  def mintAsset(update: Signed[Updates.MintAsset]): F[DataState[OnChain, CalculatedState]] = {
    val _ = update
    Async[F].raiseError(CombineRejected("asset combiner not yet implemented — phase 4 (MintAsset)"))
  }

  /** Phase-4 stub: the stateful morphism engine (meet/codomain/holder transfer/nonce) is not yet wired. */
  def applyMorphism(update: Signed[Updates.ApplyMorphism]): F[DataState[OnChain, CalculatedState]] = {
    val _ = update
    Async[F].raiseError(CombineRejected("asset combiner not yet implemented — phase 4 (ApplyMorphism)"))
  }

  /** Phase-4 stub: the commit-reveal nonce ledger (`CalculatedState.usedNonces`) is not yet wired. */
  def authorizeCompose(update: Signed[Updates.AuthorizeCompose]): F[DataState[OnChain, CalculatedState]] = {
    val _ = update
    Async[F].raiseError(CombineRejected("asset combiner not yet implemented — phase 4 (AuthorizeCompose)"))
  }
}

object AssetCombiner {

  def apply[F[_]: Async: SecurityProvider](
    current:        DataState[OnChain, CalculatedState],
    ctx:            L0NodeContext[F],
    maxBundleBytes: Long
  ): AssetCombiner[F] =
    new AssetCombiner[F](current, ctx, maxBundleBytes)
}
