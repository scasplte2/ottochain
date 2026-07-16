package xyz.kd5ujc.shared_data.lifecycle.validate.rules

import java.util.UUID

import cats.Applicative
import cats.data.Validated
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataApplicationValidationError

import xyz.kd5ujc.schema.CommitIndex
import xyz.kd5ujc.schema.Updates.{ApplyMorphism, AuthorizeCompose, CreateAssetPolicy, MintAsset}
import xyz.kd5ujc.schema.asset.{MorphismKind, TokenBehavior}
import xyz.kd5ujc.schema.fiber.FiberOrdinal
import xyz.kd5ujc.shared_data.lifecycle.validate.ValidationResult

/**
 * STRUCTURAL (L1) validation rules for asset operations (asset-model.md §6). These read ONLY `OnChain`
 * (specifically `OnChain.assetCommits`, a `name -> AssetCommit` map carrying the safe 5-bit behavior bitmask
 * and a sequence number) — NEVER `CalculatedState` registry/asset/nonce lineage.
 *
 * CLAUDE.md invariant #3 (docs/signing-canonical-and-validation.md §2): the block-validity gate
 * (`validateUpdate`/`validateSignedUpdate`) does STRUCTURAL checks only; any check that needs the
 * `CalculatedState` policy lineage / asset records / used-nonces (allowlists, mint policy, supply cap, meet,
 * composite membership) is a TOCTOU block-poisoning hazard and MUST stay in the `AssetCombiner` (Phase 4) as
 * a graceful `CombineRejected`. The split here mirrors `RegistryRules.L1` / `FiberRules.L1`.
 *
 * Hard vs soft reject (asset-model.md §6, resolves v1 open-Q3 as hard-reject): structural failures that can
 * NEVER succeed at the combiner — unknown asset, a behavior bit absent for the requested morphism,
 * sequence-number regression, non-positive mint amount — HARD-reject here. Everything stateful is SOFT
 * (`CombineRejected` only).
 */
object AssetRules {

  object L1 {

    /**
     * The behavior-gated structural check for [[ApplyMorphism]]: the asset must exist in
     * `OnChain.assetCommits`, the requested [[MorphismKind]] must be geometrically possible for the asset's
     * (advisory, possibly-stale) behavior bitmask, and the target sequence number must not regress. Reads no
     * `CalculatedState` (invariant #3).
     *
     *  - `Transfer`      requires `transferable`
     *  - `Fractionalize` requires `splittable`
     *  - `Compose`       requires `combinable`
     *  - `Pool`          requires `combinable` (the lossy dual of Compose — same C=1 gate)
     *  - `Decompose`/`Wrap`/`Stake`/`Burn` are structurally OK here; the combiner checks the rest
     *    (isComposite, single-policy, single-owner, policy allowlists, supply, meet, …).
     */
    def applyMorphismStructural[F[_]: Applicative](
      update: ApplyMorphism,
      index:  CommitIndex
    ): F[ValidationResult] =
      index.assetCommits.get(update.assetId) match {
        case None =>
          (Errors.AssetNotFound(update.assetId): DataApplicationValidationError).invalidNec[Unit].pure[F]
        case Some(commit) =>
          val behavior = TokenBehavior.fromBits(commit.behavior)
          val behaviorOk: ValidationResult = update.kind match {
            case MorphismKind.Transfer =>
              Validated.condNec(
                behavior.transferable,
                (),
                Errors.MorphismNotPermittedByBehavior(
                  update.assetId,
                  "Transfer",
                  "transferable (T=0: soulbound)"
                ): DataApplicationValidationError
              )
            case MorphismKind.Fractionalize =>
              Validated.condNec(
                behavior.splittable,
                (),
                Errors.MorphismNotPermittedByBehavior(
                  update.assetId,
                  "Fractionalize",
                  "splittable (S=0: indivisible)"
                ): DataApplicationValidationError
              )
            case MorphismKind.Compose =>
              Validated.condNec(
                behavior.combinable,
                (),
                Errors.MorphismNotPermittedByBehavior(
                  update.assetId,
                  "Compose",
                  "combinable (C=0: not combinable)"
                ): DataApplicationValidationError
              )
            case MorphismKind.Pool =>
              // Pool is the lossy dual of Compose; it melts combinable fragments, so it shares the C=1 gate.
              // Single-policy / single-owner / amount-conservation are STATEFUL (need CalculatedState) and stay
              // in the combiner as graceful CombineRejected (invariant #3).
              Validated.condNec(
                behavior.combinable,
                (),
                Errors.MorphismNotPermittedByBehavior(
                  update.assetId,
                  "Pool",
                  "combinable (C=0: not combinable)"
                ): DataApplicationValidationError
              )
            // Decompose/Wrap/Stake/Burn: structurally OK; the combiner checks the rest (Phase 4).
            case _ => ().validNec[DataApplicationValidationError]
          }
          val seqOk = sequenceNumberOk(update.assetId, update.targetSequenceNumber, commit.sequenceNumber)
          List(behaviorOk, seqOk).combineAll.pure[F]
      }

    /** Sequence-number monotonicity against the commit, batching-tolerant — mirrors `FiberRules.L1.sequenceNumberMatches`. */
    def sequenceNumberOk(
      assetId:              UUID,
      targetSequenceNumber: FiberOrdinal,
      committed:            FiberOrdinal
    ): ValidationResult =
      Validated.condNec(
        committed <= targetSequenceNumber,
        (),
        Errors.AssetSequenceNumberRegression(assetId, targetSequenceNumber, committed): DataApplicationValidationError
      )

    /**
     * Structural check for [[MintAsset]]: `amount > 0`. No policy / supply-cap / mint-policy read here — those
     * need `CalculatedState` and stay in the combiner (invariant #3). The mint targets a NEW asset id, so
     * there is nothing in `OnChain.assetCommits` to look up.
     */
    def mintStructural[F[_]: Applicative](update: MintAsset): F[ValidationResult] =
      Validated
        .condNec(
          update.amount > 0L,
          (),
          Errors.NonPositiveMintAmount(update.assetId, update.amount): DataApplicationValidationError
        )
        .pure[F]

    /**
     * Structural check for [[AuthorizeCompose]]: a sequence number is structurally present (it is a required
     * field, so this is a no-op gate kept for symmetry / future bounds). Nonce uniqueness, partner-policy
     * existence, and expiry-vs-current-ordinal are stateful and stay in the combiner (invariant #3).
     */
    def authorizeComposeStructural[F[_]: Applicative](update: AuthorizeCompose): F[ValidationResult] = {
      val _ = update
      ().validNec[DataApplicationValidationError].pure[F]
    }

    /**
     * Structural check for [[CreateAssetPolicy]]: this is a registry-package publish; its structural shape
     * (name TLD, reserved labels, metadata bounds, behavior bitmask) is bounded but its lineage/ownership is
     * stateful and stays in the combiner (invariant #3, exactly as the registry path). The `behavior` is a
     * typed `TokenBehavior` (its Int form is always a valid 5-bit subset), `morphisms` is a required map, and
     * the registry name carrying the `.asset` TLD is enforced at the `RegistryName` construction boundary.
     */
    def createPolicyStructural[F[_]: Applicative](update: CreateAssetPolicy): F[ValidationResult] = {
      val _ = update
      ().validNec[DataApplicationValidationError].pure[F]
    }
  }

  object Errors {

    final case class AssetNotFound(assetId: UUID) extends DataApplicationValidationError {
      override val message: String = s"asset $assetId not found (no on-chain asset commit)"
    }

    final case class MorphismNotPermittedByBehavior(assetId: UUID, kind: String, requires: String)
        extends DataApplicationValidationError {
      override val message: String = s"morphism $kind on asset $assetId requires $requires"
    }

    final case class AssetSequenceNumberRegression(
      assetId:   UUID,
      attempted: FiberOrdinal,
      committed: FiberOrdinal
    ) extends DataApplicationValidationError {

      override val message: String =
        s"sequence number regression for asset $assetId: morphism targets ${attempted.value.value} " +
        s"but asset is at ${committed.value.value}"
    }

    final case class NonPositiveMintAmount(assetId: UUID, amount: Long) extends DataApplicationValidationError {
      override val message: String = s"mint of asset $assetId has non-positive amount $amount"
    }
  }
}
