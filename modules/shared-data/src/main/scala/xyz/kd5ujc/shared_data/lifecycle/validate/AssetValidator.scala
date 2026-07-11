package xyz.kd5ujc.shared_data.lifecycle.validate

import cats.Applicative

import xyz.kd5ujc.schema.CommitIndex
import xyz.kd5ujc.schema.Updates.{ApplyMorphism, AuthorizeCompose, CreateAssetPolicy, MintAsset}
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.AssetRules

/**
 * Validators for asset operations (asset-model.md §6), composing [[AssetRules]] — mirrors `RegistryValidator`.
 *
 * Phase 3 ships ONLY the L1 STRUCTURAL layer. There is deliberately NO `L0Validator` here: per CLAUDE.md
 * invariant #3 (docs/signing-canonical-and-validation.md §2) every asset stateful check (policy lineage,
 * allowlists, supply cap, mint policy, nonce uniqueness, composite membership, `meet`/codomain) reads
 * `CalculatedState.registry`/`assets`/`usedNonces` and is a TOCTOU block-poisoning hazard if run in the
 * block-acceptance gate — those checks live ONLY in the `AssetCombiner` (Phase 4) as graceful
 * `CombineRejected`. `Validator.validateSignedUpdate` therefore dispatches asset ops to THIS L1-only path,
 * exactly as it does for registry ops.
 */
object AssetValidator {

  /** L1 structural checks (recreated CommitIndex only; no registry/asset lineage). */
  class L1Validator[F[_]: Applicative](index: CommitIndex) {

    def createAssetPolicy(update: CreateAssetPolicy): F[ValidationResult] =
      AssetRules.L1.createPolicyStructural(update)

    def mintAsset(update: MintAsset): F[ValidationResult] =
      AssetRules.L1.mintStructural(update)

    def applyMorphism(update: ApplyMorphism): F[ValidationResult] =
      AssetRules.L1.applyMorphismStructural(update, index)

    def authorizeCompose(update: AuthorizeCompose): F[ValidationResult] =
      AssetRules.L1.authorizeComposeStructural(update)
  }
}
