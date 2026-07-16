package xyz.kd5ujc.shared_data.lifecycle.combine

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.gas.{EvaluationResult, GasConfig, GasLimit}
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Records.AssetRecord
import xyz.kd5ujc.schema.asset._
import xyz.kd5ujc.schema.fiber.{ExecutionLimits, FiberEffect, FiberOrdinal, FiberStatus}
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{AssetCommit, CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.fiber.evaluation.ValueKind
import xyz.kd5ujc.shared_data.syntax.all._

import io.circe.syntax.EncoderOps
import monocle.Monocle.toAppliedFocusOps
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Combiner for asset operations (asset-model.md §4/§5/§6/§10) — the AUTHORITATIVE, deterministic gate and
 * the sole writer of `CalculatedState.assets` / `usedNonces` and `OnChain.assetCommits`.
 *
 * Phase 4 implements the stateful engine: `MintAsset`, `ApplyMorphism` (the three-layer
 * structural → policy → guard gate plus the codomain functions), and `AuthorizeCompose` (commit-reveal
 * nonce). `CreateAssetPolicy` is implemented as before (registry-package publish). Every rejection is a
 * GRACEFUL typed [[CombineRejected]] — the combine fold (`Combiner.insert`) catches ONLY that type, records a
 * `RejectionReceipt`, and continues with the unmutated previous state. NOTHING here `raiseError`s a
 * non-`CombineRejected` for a business rule — that would abort the snapshot's combine (the #154
 * consensus-defect lesson: one bad update must never drop the whole block).
 *
 * Stateful registry/policy/asset/nonce lineage reads are FINE here (CLAUDE.md invariant #3 forbids them only
 * in `validateSignedUpdate`, the block-acceptance gate — a TOCTOU block-poisoning hazard there).
 *
 * == Holder-ownership (R1, security-critical) ==
 * A wallet-signed `ApplyMorphism` is applied ONLY if the signer owns the asset:
 * `AssetHolder.Wallet(addr)` requires `signer == addr`. A `Fiber`-held asset's raw `ApplyMorphism` is
 * REJECTED here — fiber-held assets move only via the fiber-engine return channel (`_transferAsset` /
 * `FiberEffect.AssetTransferred`), which is Phase 5. Phase 4 never trusts a raw morphism on a fiber-held
 * asset.
 *
 * == JSON-Logic guard evaluation ==
 * Policy guards (`SupplyPolicy.mintPolicy`/`burnPolicy`, `MorphismSpec.guard` on `Governed` morphisms) are
 * evaluated at the metakit JSON-Logic boundary directly via
 * `JsonLogicEvaluator.tailRecursive[F].evaluateWithGas(expr, ctx, None, GasLimit(maxGas), GasConfig.Default)`
 * — the same primitive `FiberEvaluator` uses for transition guards, but WITHOUT the fiber `FiberT`/StateT
 * MTL stack (there is no fiber record to thread; the guard is a pure predicate over a context map). A
 * non-boolean / failed evaluation is a deterministic guard failure → `CombineRejected`.
 *
 * == Derived supply ==
 * Total supply for a policy is DERIVED — `Σ amount` over `assets` whose `schemaBinding.name` equals the
 * policy name — never a stored mutable counter (avoids the parallel-mint contention pitfall). `maxSupply` is
 * checked as `derived + amount <= maxSupply`.
 */
class AssetCombiner[F[_]: Async: SecurityProvider](
  current:        DataState[OnChain, CalculatedState],
  ctx:            L0NodeContext[F],
  maxBundleBytes: Long
) {

  private val maxGuardGas: Long = ExecutionLimits().maxGas

  // ────────────────────────────────────────────────────────────────────────────────────────────────
  // CreateAssetPolicy (unchanged — registry-package publish, asset-model.md §5a)
  // ────────────────────────────────────────────────────────────────────────────────────────────────

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

  // ────────────────────────────────────────────────────────────────────────────────────────────────
  // MintAsset (asset-model.md §3/§5/§10)
  // ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Mint a new asset instance against a resolved policy version. Resolves `policyRef` against the registry
   * lineage (missing/yanked → reject), evaluates the policy's `mintPolicy` guard (None → minting closed →
   * reject), enforces `maxSupply` against the DERIVED supply, gates the new asset state by the strict
   * `stateShape` conformance, then writes the `AssetRecord` (sequenceNumber 0) into `CalculatedState.assets`
   * and an `AssetCommit` into `OnChain.assetCommits`. Minting into a `Fiber` holder is allowed (the
   * `mintPolicy` may restrict it via the `holder` context).
   */
  def mintAsset(update: Signed[Updates.MintAsset]): F[DataState[OnChain, CalculatedState]] = {
    val m = update.value
    for {
      currentOrdinal <- ctx.getCurrentOrdinal
      signers        <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)

      _ <- raiseRejected(m.amount > 0L, s"mint of ${m.assetId} has non-positive amount ${m.amount}")
      _ <- raiseRejected(
        !current.calculated.assets.contains(m.assetId),
        s"mint target ${m.assetId} already exists"
      )

      // Resolve the policy PACKAGE version (registry lineage — combiner-only).
      resolved <- resolvePolicy(m.policyRef)
      (policyName, rv, policy) = resolved

      // mintPolicy guard (None == minting closed after genesis → reject).
      mintGuard <- policy.supply.mintPolicy.fold(
        Async[F].raiseError[JsonLogicExpression](
          CombineRejected(s"minting is closed for policy ${policyName.render} (no mintPolicy)")
        )
      )(_.pure[F])
      mintCtx = mintContext(m, signers, currentOrdinal)
      _ <- evalGuardOrReject(mintGuard, mintCtx, s"mintPolicy denied mint of ${m.assetId} under ${policyName.render}")

      // maxSupply against DERIVED supply (Σ amount over assets bound to this policy name).
      derived = derivedSupply(policyName)
      _ <- policy.supply.maxSupply match {
        case Some(cap) =>
          raiseRejected(
            derived + m.amount <= cap,
            s"mint of ${m.amount} exceeds maxSupply $cap for ${policyName.render} (derived=$derived)"
          )
        case None => Async[F].unit
      }

      binding = SchemaBinding(policyName, rv.version, rv.schemaHash, rv.logicHash)
      record = AssetRecord(
        assetId = m.assetId,
        schemaBinding = binding,
        behavior = policy.behavior,
        holder = m.holder,
        amount = m.amount,
        sequenceNumber = FiberOrdinal.MinValue,
        creationOrdinal = currentOrdinal,
        latestUpdateOrdinal = currentOrdinal,
        expiresAt = m.expiresAt,
        provenance = m.provenance
      )

      // Strict-version state conformance gate (asset-model.md §5d) on the produced asset state.
      _ <- conformOrReject(rv, record)

      result <- writeAsset(current, record)
      _ <- Slf4jLogger.getLogger[F].info(s"[asset-mint] ${m.assetId} amount=${m.amount} policy=${policyName.render}")
    } yield result
  }

  // ────────────────────────────────────────────────────────────────────────────────────────────────
  // ApplyMorphism (asset-model.md §4/§6/§8/§10) — three-layer gate then apply
  // ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Apply a typed morphism. The gate, in order:
   *   1. holder-ownership (R1): wallet-held → signer must be the holder; fiber-held → rejected (Phase 5).
   *   2. structural: behavior gate by kind (Transfer→T, Fractionalize→S, Compose→all C, Decompose→isComposite).
   *   3. policy: resolve the asset's bound `AssetPolicy` version; the morphism must be defined and not Disabled;
   *      counter-party allowlists (`allowedPolicies`/`allowedTypes`) checked against `otherAssetIds`.
   *   4. guard: `Governed` morphisms evaluate `MorphismSpec.guard` (false → reject).
   * Then APPLY the codomain function + state mutation + sequence increment, all graceful.
   */
  def applyMorphism(update: Signed[Updates.ApplyMorphism]): F[DataState[OnChain, CalculatedState]] = {
    val a = update.value
    for {
      currentOrdinal <- ctx.getCurrentOrdinal
      signers        <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)

      source <- current.calculated.assets
        .get(a.assetId)
        .fold(Async[F].raiseError[AssetRecord](CombineRejected(s"asset ${a.assetId} not found")))(_.pure[F])

      // Defense-in-depth sequence check (mirrors fiber/script combiners).
      _ <- raiseRejected(
        source.sequenceNumber === a.targetSequenceNumber,
        s"sequence number mismatch for ${a.assetId}: target=${a.targetSequenceNumber.value.value}, " +
        s"actual=${source.sequenceNumber.value.value}"
      )

      // (1) holder-ownership (R1).
      _ <- requireWalletHolder(source, signers)

      // (2) structural re-check (defensive; L1 already gated transferable/splittable/combinable).
      _ <- structuralOk(a.kind, source)

      // (3) policy layer (registry lineage — combiner-only).
      counterParties <- resolveCounterParties(a.otherAssetIds.getOrElse(Nil))
      policy         <- resolveAssetPolicy(source)
      spec           <- morphismSpec(policy, a.kind, source)
      _              <- allowlistsOk(a.kind, spec, counterParties)

      // (4) guard layer (Governed only).
      _ <- spec.visibility match {
        case MorphismVisibility.Governed =>
          spec.guard.fold(Async[F].unit) { g =>
            evalGuardOrReject(
              g,
              morphismContext(a, source, signers, currentOrdinal),
              s"governed guard denied ${a.kind} on ${a.assetId}"
            )
          }
        case _ => Async[F].unit
      }

      // APPLY the codomain function + state mutation.
      result <- applyKind(a, source, counterParties, policy, signers, currentOrdinal)
      _      <- Slf4jLogger.getLogger[F].info(s"[asset-morphism] ${a.kind} on ${a.assetId}")
    } yield result
  }

  // ────────────────────────────────────────────────────────────────────────────────────────────────
  // AuthorizeCompose (asset-model.md §5e/§8) — commit-reveal nonce
  // ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Record a one-time, signed authorization nonce so a later counter-party `Compose` can consume it.
   *
   * `usedNonces` shape (fixed in Phase 2 as `SortedMap[UUID, SortedSet[Long]]`): a nonce present in
   * `usedNonces(authorizerAssetId)` is an AUTHORIZED-AND-LIVE pending intent. A `Compose` that references
   * the nonce verifies it is present (exists ∧ not yet consumed), then REMOVES it — consumption is linear
   * (one-time): a second Compose with the same nonce finds it absent and is rejected. A nonce already
   * present is rejected at authorize time (no double-authorize / replay window).
   *
   * Expiry: enforced at authorize time (`currentOrdinal <= expiresAt`, inclusive — an authorization that is
   * already expired is rejected and never recorded, so a subsequent Compose finds it absent → "expired
   * nonce is rejected"). Per-nonce expiry-AFTER-authorization pruning would need an expiry stored alongside
   * each nonce; the Phase-2 `SortedSet[Long]` value cannot carry it, so that finer prune is a documented
   * Phase-2-shape limitation (a richer `usedNonces` value is a future, additive change). Bounded growth
   * (R7): the holder advances its `targetSequenceNumber` per `AuthorizeCompose`, every recorded nonce is
   * removed on consumption, and stale entries for assets that no longer exist are pruned on every
   * AuthorizeCompose pass.
   */
  def authorizeCompose(update: Signed[Updates.AuthorizeCompose]): F[DataState[OnChain, CalculatedState]] = {
    val ac = update.value
    for {
      currentOrdinal <- ctx.getCurrentOrdinal
      signers        <- update.proofs.toList.traverse(_.id.toAddress).map(Set.from)

      source <- current.calculated.assets
        .get(ac.assetId)
        .fold(Async[F].raiseError[AssetRecord](CombineRejected(s"asset ${ac.assetId} not found")))(_.pure[F])

      _ <- raiseRejected(
        source.sequenceNumber === ac.targetSequenceNumber,
        s"sequence number mismatch for ${ac.assetId}: target=${ac.targetSequenceNumber.value.value}, " +
        s"actual=${source.sequenceNumber.value.value}"
      )

      // Only the asset's wallet holder may authorize a compose against it.
      _ <- requireWalletHolder(source, signers)

      // The partner policy must exist as an asset-policy package.
      _ <- raiseRejected(
        current.calculated.registry
          .get(ac.partnerPolicyId)
          .map(_.target)
          .exists { case _: RegistryTarget.AssetPolicyPackage => true; case _ => false },
        s"partner policy ${ac.partnerPolicyId.render} is not a known asset-policy package"
      )

      // Expiry is checked inclusively against the current ordinal.
      _ <- raiseRejected(
        SnapshotOrdinal.unsafeApply(currentOrdinal.value.value) <= ac.expiresAt,
        s"authorization for ${ac.assetId} is already expired (current=${currentOrdinal.value.value}, " +
        s"expiresAt=${ac.expiresAt.value.value})"
      )

      existing = current.calculated.usedNonces.getOrElse(ac.assetId, SortedSet.empty[Long])
      _ <- raiseRejected(
        !existing.contains(ac.nonce),
        s"nonce ${ac.nonce} already authorized (or consumed) for ${ac.assetId}"
      )

      // Record the pending nonce, advance the asset's sequence number, and prune stale entries.
      pruned = pruneStaleNonces(current.calculated.usedNonces.updated(ac.assetId, existing + ac.nonce))
      bumped = source.copy(sequenceNumber = source.sequenceNumber.next, latestUpdateOrdinal = currentOrdinal)

      result <- writeAsset(current.focus(_.calculated.usedNonces).replace(pruned), bumped)
      _      <- Slf4jLogger.getLogger[F].info(s"[asset-authorize-compose] ${ac.assetId} nonce=${ac.nonce}")
    } yield result
  }

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // Phase 5 — fiber-held asset transfer return channel (asset-model.md §9/§10)
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  /**
   * Apply the `_transferAsset` effects a committed fiber transition produced, AFTER the fiber-combine pass has
   * written the transition's state mutations onto `st`. This is the authoritative landing site of the §9
   * return channel; it is the ONLY way a fiber-held asset moves (`_transferAsset` effects are `FiberEffect`s,
   * never `OttochainMessage`s — a raw morphism on a fiber-held asset is rejected in Phase 4).
   *
   * `transfersByEmitter` keys the per-fiber transfer lists by the EMITTING fiber id (the primary fiber + any
   * cascade-triggered fibers). Each transfer is applied via [[applyFiberTransfer]] with the holder defense
   * (R1) checked against THAT emitting fiber. Applied within the SAME combiner pass, single-pass, in a
   * deterministic order (emitter id, then list order). No re-entrancy (R20): applying a transfer mutates only
   * `assets`/`assetCommits` and NEVER re-triggers a fiber transition — there is no fiber dispatch here.
   *
   * ALL-OR-NOTHING per transition: any rejected transfer raises `CombineRejected`, which `Combiner.insert`
   * turns into a single `RejectionReceipt` for the whole update and discards the partial mutation (the fiber's
   * logic is faulty/malicious). The cap `ExecutionLimits.maxAssetMutations` bounds the total transfers applied.
   */
  def applyFiberTransfers(
    st:                 DataState[OnChain, CalculatedState],
    transfersByEmitter: Map[UUID, List[FiberEffect.AssetTransferred]]
  ): F[DataState[OnChain, CalculatedState]] = {
    // Deterministic order: by emitting fiber id, preserving each list's emission order.
    val ordered: List[(UUID, FiberEffect.AssetTransferred)] =
      transfersByEmitter.toList
        .sortBy(_._1)
        .flatMap { case (emitter, ts) => ts.map(emitter -> _) }

    val maxMutations = ExecutionLimits().maxAssetMutations
    for {
      _ <- raiseRejected(
        ordered.size.toLong <= maxMutations,
        s"transition emitted ${ordered.size} asset transfers, exceeding maxAssetMutations $maxMutations"
      )
      currentOrdinal <- ctx.getCurrentOrdinal
      result <- ordered.foldLeftM(st) { case (acc, (emitter, transfer)) =>
        applyFiberTransfer(acc, emitter, transfer, currentOrdinal)
      }
    } yield result
  }

  /**
   * Apply ONE fiber-held asset transfer with the R1 holder-ownership defense. The combiner NEVER trusts the
   * extracted effect:
   *   1. resolve `transfer.assetId` against `st.calculated.assets` (missing → reject),
   *   2. require `holder == AssetHolder.Fiber(emittingFiberId)` — a fiber may not transfer an asset it does
   *      not hold (the single highest-risk check, §9),
   *   3. require `behavior.transferable` (a soulbound held asset cannot leave custody),
   *   4. if the recipient is `Fiber(x)`, require it resolves to a LIVE, non-archived fiber record (§5e/§10),
   * then set `holder := recipient`, bump the sequence number, and re-commit. Graceful `CombineRejected` on any
   * failure.
   */
  def applyFiberTransfer(
    st:              DataState[OnChain, CalculatedState],
    emittingFiberId: UUID,
    transfer:        FiberEffect.AssetTransferred,
    ordinal:         SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    for {
      source <- st.calculated.assets
        .get(transfer.assetId)
        .fold(
          Async[F].raiseError[AssetRecord](
            CombineRejected(s"fiber $emittingFiberId tried to transfer unknown asset ${transfer.assetId}")
          )
        )(_.pure[F])

      // (R1) the emitting fiber MUST be the current holder.
      _ <- raiseRejected(
        source.holder == AssetHolder.Fiber(emittingFiberId),
        s"fiber $emittingFiberId does not hold asset ${transfer.assetId} (holder=${source.holder}) — transfer rejected"
      )

      // behavior gate: a non-transferable (soulbound) held asset cannot be moved out of custody.
      _ <- raiseRejected(
        source.behavior.transferable,
        s"asset ${transfer.assetId} is not transferable (T=0) — fiber $emittingFiberId cannot transfer it"
      )

      // FiberPolicy dial `transferPolicy`: the EMITTING fiber's hash-pinned recipient allowlist. Read from the
      // authoritative state (never from the extracted effect). A recipient outside the allowlist is a graceful
      // CombineRejected (whole update discarded via RejectionReceipt — never a partial apply). `None` on a
      // recipient class ⇒ that class is unconstrained; absent policy ⇒ legacy (any recipient).
      _ <- st.calculated.stateMachines
        .get(emittingFiberId)
        .flatMap(_.definition.policy.dials)
        .flatMap(_.transferPolicy)
        .fold(Async[F].unit) { tp =>
          val (permitted, who) = transfer.recipient match {
            case AssetHolder.Fiber(targetId) =>
              (tp.allowedRecipientFibers.forall(_.contains(targetId)), s"fiber $targetId")
            case AssetHolder.Wallet(addr) =>
              (tp.allowedRecipientWallets.forall(_.contains(addr)), s"wallet ${addr.show}")
          }
          raiseRejected(
            permitted,
            s"transferPolicy: fiber $emittingFiberId may not transfer asset ${transfer.assetId} to $who"
          )
        }

      // Target liveness: a Fiber recipient must be a live, non-archived fiber record (§5e/§10).
      _ <- transfer.recipient match {
        case AssetHolder.Fiber(targetId) =>
          raiseRejected(
            st.calculated.stateMachines.get(targetId).exists(_.status == FiberStatus.Active),
            s"transfer recipient fiber $targetId is not a live, non-archived state machine"
          )
        case AssetHolder.Wallet(_) => Async[F].unit
      }

      moved = source.copy(
        holder = transfer.recipient,
        sequenceNumber = source.sequenceNumber.next,
        latestUpdateOrdinal = ordinal
      )
      result <- writeAsset(st, moved)
      _ <- Slf4jLogger
        .getLogger[F]
        .info(s"[asset-fiber-transfer] ${transfer.assetId} from fiber $emittingFiberId to ${transfer.recipient}")
    } yield result

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // Apply per kind (codomain function + state mutation + sequence increment)
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  private def applyKind(
    a:              Updates.ApplyMorphism,
    source:         AssetRecord,
    counterParties: List[AssetRecord],
    policy:         RegistryShape.AssetPolicy,
    signers:        Set[Address],
    ordinal:        SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    a.kind match {
      case MorphismKind.Transfer      => applyTransfer(a, source, ordinal)
      case MorphismKind.Burn          => applyBurn(a, source, policy, ordinal)
      case MorphismKind.Fractionalize => applyFractionalize(a, source, ordinal)
      case MorphismKind.Compose       => applyCompose(a, source, counterParties, signers, ordinal)
      case MorphismKind.Decompose     => applyDecompose(a, source, ordinal)
      case MorphismKind.Pool          => applyPool(a, source, counterParties, signers, ordinal)
      case MorphismKind.Wrap          => applyWrap(a, source, ordinal)
      case MorphismKind.Stake         => applyStake(a, source, ordinal)
    }

  /** Transfer → `holder := recipient` (codomain: same behavior, new holder). */
  private def applyTransfer(
    a:       Updates.ApplyMorphism,
    source:  AssetRecord,
    ordinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    a.recipient.fold(
      Async[F].raiseError[DataState[OnChain, CalculatedState]](
        CombineRejected(s"Transfer of ${a.assetId} requires a recipient")
      )
    ) { recipient =>
      writeAsset(
        current,
        source.copy(holder = recipient, sequenceNumber = source.sequenceNumber.next, latestUpdateOrdinal = ordinal)
      )
    }

  /** Burn → evaluate `burnPolicy` (None → reject), then REMOVE the record + commit (terminal). */
  private def applyBurn(
    a:       Updates.ApplyMorphism,
    source:  AssetRecord,
    policy:  RegistryShape.AssetPolicy,
    ordinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    policy.supply.burnPolicy.fold(
      Async[F].raiseError[DataState[OnChain, CalculatedState]](
        CombineRejected(s"burning is closed for ${source.schemaBinding.name.render} (no burnPolicy)")
      )
    ) { burnGuard =>
      val ctxData = MapValue(
        Map(
          "assetId" -> StrValue(a.assetId.toString),
          "amount"  -> IntValue(BigInt(source.amount)),
          "ordinal" -> IntValue(BigInt(ordinal.value.value))
        )
      )
      evalGuardOrReject(burnGuard, ctxData, s"burnPolicy denied burn of ${a.assetId}") *>
      removeAsset(current, a.assetId).pure[F]
    }

  /**
   * Fractionalize → require `S=1`; create shard `AssetRecord`s with `behavior := source.behavior with
   * combinable=false` (`C:=0`); partition `amount` across the shards; CONSUME the source. Shard ids come
   * from `shardIds` if provided, else are derived deterministically from the source id + index. Amount is
   * conserved: `Σ shard.amount == source.amount`.
   */
  private def applyFractionalize(
    a:       Updates.ApplyMorphism,
    source:  AssetRecord,
    ordinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] = {
    val shardBehavior = source.behavior.copy(combinable = false)
    val shardIds: List[UUID] = a.shardIds.filter(_.nonEmpty).getOrElse(List(deriveShardId(a.assetId, 0)))
    val n = shardIds.length.toLong
    if (n <= 0L)
      Async[F].raiseError(CombineRejected(s"Fractionalize of ${a.assetId} requires at least one shard"))
    else {
      // Partition amount as evenly as possible; remainder goes to the first shard (deterministic).
      val base = source.amount / n
      val rem = source.amount - base * n
      val amounts = shardIds.zipWithIndex.map { case (_, i) => if (i == 0) base + rem else base }
      val shards = shardIds.zip(amounts).map { case (sid, amt) =>
        source.copy(
          assetId = sid,
          behavior = shardBehavior,
          amount = amt,
          sequenceNumber = FiberOrdinal.MinValue,
          creationOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          componentFiberIds = None,
          parentCompositeId = None
        )
      }
      val withoutSource = removeAsset(current, a.assetId)
      shards.foldLeftM(withoutSource) { case (st, shard) => writeAsset(st, shard) }
    }
  }

  /**
   * Compose → require all components `C=1`; `composite.behavior := foldMeet(source :: components)` (the
   * behavior homomorphism); store the component ids VERBATIM on the composite (the retraction anchor) AND a
   * `componentsCommitment` — `hash(canonicalWitnesses)` over the full per-component state (the FAITHFUL
   * retraction anchor, Phase-4 hardening, asset-model §4); mark the source + components consumed; the
   * composite id comes from `compositeId`. Amount is conserved: `composite.amount == Σ parts.amount`.
   *
   * The witness list captures each part's `(assetId, schemaBinding, behavior, holder, amount, expiresAt,
   * componentFiberIds, componentsCommitment, provenance)` — exactly the RESTORABLE fields — and is
   * CANONICALIZED (sorted by `assetId`) before hashing, so the commitment is independent of the order the
   * components were supplied. `Decompose` recomputes this hash from the reveal witness and matches it.
   *
   * Commit-reveal (asset-model §5e/§8): if `a.nonce` is present, this is a symmetric (cross-holder) compose;
   * the nonce must be a LIVE pending authorization recorded by one of the counter-parties (via
   * `AuthorizeCompose`). The combiner verifies it exists ∧ is not yet consumed and atomically consumes it
   * (removes it from `usedNonces`) within this single pass — linear, one-time.
   */
  private def applyCompose(
    a:              Updates.ApplyMorphism,
    source:         AssetRecord,
    counterParties: List[AssetRecord],
    signers:        Set[Address],
    ordinal:        SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    // (C2) integrity precheck: no duplicate / self counter-party ids (they would double-count amount).
    requireDistinctCounterParties(a.assetId, a.otherAssetIds.getOrElse(Nil)) *>
    a.compositeId.fold(
      Async[F].raiseError[DataState[OnChain, CalculatedState]](
        CombineRejected(s"Compose of ${a.assetId} requires a compositeId")
      )
    ) { compositeId =>
      val parts = source :: counterParties
      // All parts must be combinable (defensive — the structural layer checks the source's C bit; the
      // counter-parties are checked here, where their authoritative records are visible).
      val nonCombinable = parts.filterNot(_.behavior.combinable)
      if (nonCombinable.nonEmpty)
        Async[F].raiseError(
          CombineRejected(
            s"Compose rejected: components ${nonCombinable.map(_.assetId).mkString(", ")} are not combinable (C=0)"
          )
        )
      else if (current.calculated.assets.contains(compositeId))
        Async[F].raiseError(CombineRejected(s"composite id $compositeId already exists"))
      else {
        val componentIds = parts.map(_.assetId)
        // Build + canonicalize the per-component witnesses, then commit to them (the FAITHFUL anchor).
        val canonicalWitnesses = canonicalize(parts.map(witnessOf))
        componentsCommitment(canonicalWitnesses).flatMap { commit =>
          val composite = source.copy(
            assetId = compositeId,
            behavior = TokenBehavior.foldMeet(parts.map(_.behavior)),
            amount = parts.map(_.amount).sum,
            sequenceNumber = FiberOrdinal.MinValue,
            creationOrdinal = ordinal,
            latestUpdateOrdinal = ordinal,
            componentFiberIds = Some(componentIds), // stored VERBATIM — the id-multiset retraction anchor
            componentsCommitment = Some(commit), // the FAITHFUL component-state commitment
            parentCompositeId = None
          )
          // Establish per-counter-party consent (signer-owned OR nonce-authorized) and consume any revealed
          // nonces atomically within this pass, BEFORE consuming the components, so a failure raises before
          // any state mutation.
          consumeComposeConsent(current, a.nonce, counterParties, signers).flatMap { stateAfterNonce =>
            // Mark each component consumed: remove from the live set (its full state is committed in the
            // composite's componentsCommitment for the FAITHFUL Decompose retraction).
            val consumed = componentIds.foldLeft(stateAfterNonce) { case (st, id) => removeAsset(st, id) }
            writeAsset(consumed, composite)
          }
        }
      }
    }

  /**
   * MANDATORY per-counter-party consent for a Compose (audit C2 — cross-holder theft + amount conservation).
   *
   * A counter-party may be consumed into the composite ONLY with its holder's consent, established one of
   * two ways:
   *   (i)  SIGNER-OWNED — `cp.holder = Wallet(addr)` with `addr ∈ signers` (a same-holder compose of the
   *        signer's OWN assets); NO nonce required, OR
   *   (ii) NONCE-AUTHORIZED — the counter-party's holder recorded a live `AuthorizeCompose` nonce under
   *        `usedNonces(cp.assetId)`, which this Compose reveals via `a.nonce`. The nonce is consumed LINEARLY
   *        (removed here, one-time) — a second Compose finds it absent and is rejected.
   *
   * Consent is MANDATORY, never opt-in: a counter-party that is neither signer-owned NOR covered by a nonce
   * authorizing THAT counter-party is a graceful [[CombineRejected]]. This closes the C2 hole where the old
   * `consumeNonce(None, …)` no-op silently let a nonce-less cross-holder Compose pull a victim's asset into a
   * signer-held composite. A same-holder compose (every counter-party signer-owned) still works with NO
   * nonce, so the legitimate path is unaffected.
   *
   * Determinism: counter-parties are folded in the supplied list order, and each one looks up only its OWN
   * `usedNonces(cp.assetId)` set, so accept/reject/consumption never depends on map/set iteration order.
   */
  private def consumeComposeConsent(
    st:             DataState[OnChain, CalculatedState],
    nonce:          Option[Long],
    counterParties: List[AssetRecord],
    signers:        Set[Address]
  ): F[DataState[OnChain, CalculatedState]] =
    counterParties.foldLeftM(st) { (acc, cp) =>
      val signerOwned = cp.holder match {
        case AssetHolder.Wallet(addr) => signers.contains(addr)
        case AssetHolder.Fiber(_)     => false
      }
      if (signerOwned) acc.pure[F]
      else {
        val live = acc.calculated.usedNonces.getOrElse(cp.assetId, SortedSet.empty[Long])
        nonce
          .filter(live.contains)
          .fold(
            Async[F].raiseError[DataState[OnChain, CalculatedState]](
              CombineRejected(
                s"compose counter-party ${cp.assetId} (holder ${cp.holder}) is neither signer-owned nor " +
                s"covered by a live AuthorizeCompose nonce — cross-holder consent is required"
              )
            )
          ) { n =>
            // Consume linearly: remove the revealed nonce from THIS counter-party's authorization set.
            val updatedSet = live - n
            val updatedNonces =
              if (updatedSet.isEmpty) acc.calculated.usedNonces - cp.assetId
              else acc.calculated.usedNonces.updated(cp.assetId, updatedSet)
            acc.focus(_.calculated.usedNonces).replace(updatedNonces).pure[F]
          }
      }
    }

  /**
   * Decompose → the FAITHFUL retraction (Phase-4 hardening, choice (a) STRICT). A committed composite MUST
   * decompose faithfully — there is NO lossy fallback; a missing / mismatched / non-conserving reveal witness
   * is a graceful `CombineRejected`. The reveal witness (`a.priorComponents`) carries each original
   * component's full restorable state; the combiner verifies it against the composite's commitment and
   * restores each component EXACTLY (behavior / holder / amount / schemaBinding / expiresAt / nested
   * composite anchors / provenance), modulo the reset ordinals + sequence. Validation order:
   *
   *   1. require `source.componentsCommitment` is `Some(commit)` — a faithfully-composable composite carries
   *      a component commitment; its absence means it cannot be decomposed faithfully.
   *   2. require `a.priorComponents` is `Some(witnesses)` — the reveal is mandatory.
   *   3. canonicalize `witnesses` (sort by `assetId`), recompute the hash, require `== commit`.
   *   4. require the witness `assetId` SET == `source.componentFiberIds` SET (the right components).
   *   5. require `Σ witness.amount == source.amount` (conservation).
   *   6. restore each witness as a fresh `AssetRecord` (creation/latest = `ordinal`, sequence
   *      `FiberOrdinal.MinValue`), write each, REMOVE the composite.
   *
   * Amount is conserved by check (5); custody is restored to each witnessed `holder` (returning components to
   * their pre-Compose owners). This is the genuine `Decompose ∘ Compose = id` on component state.
   */
  private def applyDecompose(
    a:       Updates.ApplyMorphism,
    source:  AssetRecord,
    ordinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    for {
      // (1) the composite must carry a component commitment.
      commit <- source.componentsCommitment.fold(
        Async[F].raiseError[Hash](
          CombineRejected(s"composite ${a.assetId} is missing a component commitment; cannot decompose faithfully")
        )
      )(_.pure[F])

      // (2) the reveal witness is mandatory.
      witnesses <- a.priorComponents.fold(
        Async[F].raiseError[List[ComponentWitness]](
          CombineRejected(s"Decompose of ${a.assetId} requires a priorComponents witness")
        )
      )(_.pure[F])

      // (3) the canonical reveal must hash to the stored commitment.
      canonical = canonicalize(witnesses)
      recomputed <- componentsCommitment(canonical)
      _ <- raiseRejected(
        recomputed === commit,
        s"priorComponents witness for ${a.assetId} does not match the stored commitment"
      )

      // (4) the witness id set must equal the composite's component id set.
      storedIds = source.componentFiberIds.getOrElse(Nil).toSet
      witnessIds = witnesses.map(_.assetId).toSet
      _ <- raiseRejected(
        witnessIds == storedIds,
        s"priorComponents witness ids ${witnessIds.mkString("{", ",", "}")} do not match the composite " +
        s"${storedIds.mkString("{", ",", "}")}"
      )

      // (5) amounts must conserve the composite amount.
      witnessTotal = witnesses.map(_.amount).sum
      _ <- raiseRejected(
        witnessTotal === source.amount,
        s"priorComponents witness amounts ($witnessTotal) do not conserve the composite amount (${source.amount})"
      )

      // (6) restore each witness as a fresh record (reset ordinals + sequence), remove the composite.
      restored = witnesses.map(restoreWitness(_, ordinal))
      withoutComposite = removeAsset(current, a.assetId)
      result <- restored.foldLeftM(withoutComposite) { case (st, comp) => writeAsset(st, comp) }
    } yield result

  /**
   * Pool → the LOSSY, provenance-forgetting DUAL of Compose (asset-model.md §4 "Pool — the lossy dual of
   * Compose"). Where `Compose` is a RETRACTION (stores `componentFiberIds` + `componentsCommitment` so
   * `Decompose` can restore the originals), `Pool` is a COEQUALIZER / quotient-by-relabeling: it IDENTIFIES
   * the parts and keeps only the conserved scalar. It melts N fragments into ONE fungible balance, knowingly
   * trading per-voucher provenance/identity for fungibility — the holder-side complement to the interop
   * functor's canonical-`policyId` anti-fragmentation cure (preserve-by-default vs forget-by-opt-in).
   *
   * Gates (beyond the structural `C=1` of [[structuralOk]] and the policy/guard layer of [[applyMorphism]]):
   *   1. SINGLE CANONICAL POLICY — every part shares `schemaBinding.name` (so `behavior` is unambiguous and
   *      `derivedSupply` stays coherent with the canonical-`policyId` cure). Else graceful `CombineRejected`.
   *   2. SINGLE OWNER (holder-ownership R1) — the signer owns EVERY part (wallet-held → signer is the holder;
   *      a fiber-held part → reject, deferred to Phase 5 exactly as the source check is). Pool melts one's OWN
   *      fragments, so there is NO AuthorizeCompose nonce (unlike a cross-holder `Compose`).
   *   3. `compositeId` present and not already existing.
   *
   * Then write ONE output `AssetRecord(assetId = compositeId, amount = Σ parts.amount, behavior =
   * parts.head.behavior, holder = source.holder, componentFiberIds = None, componentsCommitment = None,
   * provenance = None, fresh ordinals, sequence MinValue)` and CONSUME all parts. Because Σ is preserved the
   * `derivedSupply` invariant holds (Pool cannot mint/burn); because NO witness is stored the output is NOT a
   * composite (`componentFiberIds == None`) and `Decompose` of it is rejected for free (the model already
   * rejects Decompose on a non-composite — see [[structuralOk]]/[[applyDecompose]]).
   */
  private def applyPool(
    a:              Updates.ApplyMorphism,
    source:         AssetRecord,
    counterParties: List[AssetRecord],
    signers:        Set[Address],
    ordinal:        SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    // (C2) integrity precheck: no duplicate / self counter-party ids (they would double-count amount, even
    // though every part is separately signer-owned via requireWalletHolder below).
    requireDistinctCounterParties(a.assetId, a.otherAssetIds.getOrElse(Nil)) *>
    a.compositeId.fold(
      Async[F].raiseError[DataState[OnChain, CalculatedState]](
        CombineRejected(s"Pool of ${a.assetId} requires a compositeId (the pooled-output id)")
      )
    ) { compositeId =>
      val parts = source :: counterParties
      val canonicalName = source.schemaBinding.name
      for {
        // (1) single canonical policy: every part shares the source's policy name.
        _ <- raiseRejected(
          parts.forall(_.schemaBinding.name === canonicalName),
          s"Pool requires one canonical policy (parts span " +
          s"${parts.map(_.schemaBinding.name.render).distinct.mkString(", ")})"
        )
        // (2) single owner (R1): the signer must own EVERY part; a fiber-held part is rejected (Phase 5),
        // exactly as the source holder-ownership check defers.
        _ <- parts.traverse_(requireWalletHolder(_, signers))
        // (3) the pooled-output id must be fresh.
        _ <- raiseRejected(
          !current.calculated.assets.contains(compositeId),
          s"Pool output id $compositeId already exists"
        )
        // Write ONE fungible output that FORGETS per-component identity/origin; consume all parts.
        pooled = source.copy(
          assetId = compositeId,
          behavior = parts.head.behavior, // == canonical policy behavior (all parts share the policy)
          amount = parts.map(_.amount).sum, // CONSERVATION: Σ preserved => derivedSupply invariant holds
          holder = source.holder,
          sequenceNumber = FiberOrdinal.MinValue,
          creationOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          componentFiberIds = None, // NOT a composite — no retraction anchor
          componentsCommitment = None, // no witness stored — provenance/identity deliberately forgotten
          parentCompositeId = None,
          provenance = None // FORGET origin: the melt is the point
        )
        consumed = parts.map(_.assetId).foldLeft(current) { case (st, id) => removeAsset(st, id) }
        result <- writeAsset(consumed, pooled)
      } yield result
    }

  /** Wrap → behavior unchanged (identity-preserving custody); optional recipient re-custodies. */
  private def applyWrap(
    a:       Updates.ApplyMorphism,
    source:  AssetRecord,
    ordinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    writeAsset(
      current,
      source.copy(
        holder = a.recipient.getOrElse(source.holder),
        sequenceNumber = source.sequenceNumber.next,
        latestUpdateOrdinal = ordinal
      )
    )

  /** Stake → behavior with `expirable := true` (moves DOWN the lattice). */
  private def applyStake(
    a:       Updates.ApplyMorphism,
    source:  AssetRecord,
    ordinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] = {
    val _ = a
    writeAsset(
      current,
      source.copy(
        behavior = source.behavior.copy(expirable = true),
        sequenceNumber = source.sequenceNumber.next,
        latestUpdateOrdinal = ordinal
      )
    )
  }

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // Gate helpers
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  /** Holder-ownership (R1). Wallet → signer must be the holder; Fiber → deferred to Phase 5. */
  private def requireWalletHolder(source: AssetRecord, signers: Set[Address]): F[Unit] =
    source.holder match {
      case AssetHolder.Wallet(addr) =>
        raiseRejected(signers.contains(addr), s"not asset holder of ${source.assetId}")
      case AssetHolder.Fiber(_) =>
        Async[F].raiseError(
          CombineRejected("fiber-held assets move only via fiber transitions — phase 5")
        )
    }

  /** Structural domain guard by kind, re-derived from the authoritative record (defensive, asset-model §4). */
  private def structuralOk(kind: MorphismKind, source: AssetRecord): F[Unit] =
    kind match {
      case MorphismKind.Transfer =>
        raiseRejected(source.behavior.transferable, s"Transfer requires transferable (T=0) on ${source.assetId}")
      case MorphismKind.Fractionalize =>
        raiseRejected(source.behavior.splittable, s"Fractionalize requires splittable (S=0) on ${source.assetId}")
      case MorphismKind.Compose =>
        raiseRejected(source.behavior.combinable, s"Compose requires combinable (C=0) on ${source.assetId}")
      case MorphismKind.Pool =>
        // Same C=1 gate as Compose: pooling melts combinable fragments (the lossy dual of Compose).
        raiseRejected(source.behavior.combinable, s"Pool requires combinable (C=0) on ${source.assetId}")
      case MorphismKind.Decompose =>
        raiseRejected(
          source.componentFiberIds.exists(_.nonEmpty),
          s"Decompose requires a composite (isComposite) on ${source.assetId}"
        )
      case _ => Async[F].unit // Wrap/Stake/Burn — no structural bit gate
    }

  /** Counter-party allowlists (`allowedPolicies` / `allowedTypes`) against the resolved counter-parties. */
  private def allowlistsOk(
    kind:           MorphismKind,
    spec:           MorphismSpec,
    counterParties: List[AssetRecord]
  ): F[Unit] = {
    val policyOk = spec.allowedPolicies.fold(true) { allowed =>
      counterParties.forall(cp => allowed.contains(cp.schemaBinding.name))
    }
    val typeOk = spec.allowedTypes.fold(true) { allowed =>
      counterParties.forall(cp => allowed.contains(cp.behavior.bits))
    }
    raiseRejected(policyOk, s"$kind counter-party not on allowedPolicies") *>
    raiseRejected(typeOk, s"$kind counter-party behavior not on allowedTypes")
  }

  /** Look up the [[MorphismSpec]] for `kind`; absent or `Disabled` → reject. */
  private def morphismSpec(
    policy: RegistryShape.AssetPolicy,
    kind:   MorphismKind,
    source: AssetRecord
  ): F[MorphismSpec] =
    policy.morphisms.get(kind) match {
      case None =>
        Async[F].raiseError(CombineRejected(s"$kind is not defined for policy ${source.schemaBinding.name.render}"))
      case Some(spec) if spec.visibility == MorphismVisibility.Disabled =>
        Async[F].raiseError(CombineRejected(s"$kind is Disabled for policy ${source.schemaBinding.name.render}"))
      case Some(spec) => spec.pure[F]
    }

  /**
   * Resolve the asset's bound `AssetPolicy` version (combiner-only registry lineage read).
   *
   * == INTENTIONAL & LOAD-BEARING: direct `versions.get`, NOT `resolve()` (anti-rug-pull invariant) ==
   * This looks up the EXACT pinned version (`schemaBinding.version`) via `versions.get`, which returns the
   * version REGARDLESS of its lifecycle status (Active / Deprecated / Yanked). This is deliberate and MUST
   * NOT be "fixed" to `VersionLineage.resolve` (which EXCLUDES `Yanked`). The asymmetry is the policy
   * lifecycle / anti-rug-pull invariant (asset-model.md §3/§5, "Policy lifecycle & instance semantics"):
   *
   *   - MINTING uses `resolve` (see [[resolvePolicy]]) → a yanked policy version BLOCKS new mints.
   *   - EXISTING-INSTANCE morphisms use this direct `versions.get` → already-minted assets keep working
   *     through any status change. Assets are NEVER auto-burned on yank.
   *
   * Switching this to `resolve()` would retroactively BRICK every existing instance the instant a policy
   * version is yanked (a Transfer/Burn/Decompose would suddenly fail to resolve) — i.e. a rug-pull: an owner
   * could trap holders' assets by yanking. Existing `AssetRecord`s pin their policy version via
   * `SchemaBinding` for life precisely so a status change cannot strand them. Keep the direct lookup.
   */
  private def resolveAssetPolicy(source: AssetRecord): F[RegistryShape.AssetPolicy] =
    current.calculated.registry
      .get(source.schemaBinding.name)
      .map(_.target)
      .collect { case RegistryTarget.AssetPolicyPackage(l) => l }
      .flatMap(_.versions.get(source.schemaBinding.version))
      .fold(
        Async[F].raiseError[RegistryShape.AssetPolicy](
          CombineRejected(
            s"asset ${source.assetId} binding ${source.schemaBinding.name.render}@${source.schemaBinding.version.render} did not resolve"
          )
        )
      )(rv => requireAssetPolicyShape(rv, source.schemaBinding.name))

  /** Resolve the `policyRef` of a mint to (name, version, shape). Missing/yanked → reject. */
  private def resolvePolicy(ref: SchemaRef): F[(RegistryName, RegisteredVersion, RegistryShape.AssetPolicy)] =
    for {
      lineage <- current.calculated.registry
        .get(ref.name)
        .map(_.target)
        .collect { case RegistryTarget.AssetPolicyPackage(l) => l }
        .fold(
          Async[F].raiseError[VersionLineage](
            CombineRejected(s"policyRef ${ref.name.render} is not a known asset-policy package")
          )
        )(_.pure[F])
      rv <- lineage
        .resolve(ref.version)
        .fold(
          e =>
            Async[F].raiseError[RegisteredVersion](
              CombineRejected(s"policyRef unresolvable for ${ref.name.render}: $e")
            ),
          _.pure[F]
        )
      shape <- requireAssetPolicyShape(rv, ref.name)
    } yield (ref.name, rv, shape)

  /** Narrow a resolved registry version to the [[RegistryShape.AssetPolicy]] shape; anything else → reject. */
  private def requireAssetPolicyShape(rv: RegisteredVersion, name: RegistryName): F[RegistryShape.AssetPolicy] =
    rv.shape match {
      case ap: RegistryShape.AssetPolicy => ap.pure[F]
      case other =>
        Async[F].raiseError(
          CombineRejected(s"${name.render} resolves to a non-asset shape ${other.getClass.getSimpleName}")
        )
    }

  /** Resolve all counter-party asset ids to their authoritative records; any missing → reject. */
  private def resolveCounterParties(ids: List[UUID]): F[List[AssetRecord]] =
    ids.traverse { id =>
      current.calculated.assets
        .get(id)
        .fold(
          Async[F].raiseError[AssetRecord](CombineRejected(s"counter-party asset $id not found"))
        )(_.pure[F])
    }

  /**
   * Integrity precheck for a Compose/Pool counter-party id list (audit C2 — amount conservation).
   *
   * `applyCompose`/`applyPool` consume `parts = source :: counterParties`, summing `parts.map(_.amount)`,
   * but `removeAsset` deletes each id at most ONCE. Naming the source itself as a counter-party, or listing
   * any id more than once, therefore DOUBLE-COUNTS that id's amount into the output while consuming it a
   * single time — a repeatable mint-from-nothing that breaks `Σ amount`. Reject both, naming the offending
   * id(s). Deterministic: offenders are sorted, never surfaced in map/set iteration order.
   */
  private def requireDistinctCounterParties(sourceId: UUID, otherIds: List[UUID]): F[Unit] = {
    val self = otherIds.exists(_ == sourceId)
    val dupes = otherIds.groupBy(identity).collect { case (id, occ) if occ.size > 1 => id }.toList.sorted
    raiseRejected(
      !self,
      s"compose/pool counter-parties may not include the source $sourceId (would double-count its amount)"
    ) *>
    raiseRejected(
      dupes.isEmpty,
      s"compose/pool counter-parties contain duplicate ids: ${dupes.mkString(", ")}"
    )
  }

  /** Run the strict-version conformance gate on the produced asset state (asset-model §5d). */
  private def conformOrReject(rv: RegisteredVersion, record: AssetRecord): F[Unit] =
    if (!rv.strict) Async[F].unit
    else {
      val stateShape = rv.shape match {
        case ap: RegistryShape.AssetPolicy => ap.stateShape
        case _                             => MessageShape("Asset.State", Nil)
      }
      // The asset's typed state surface for the shallow conformance check.
      val produced = MapValue(
        Map(
          "amount" -> IntValue(BigInt(record.amount))
        )
      )
      xyz.kd5ujc.shared_data.fiber.ConformanceChecker.check(stateShape, produced) match {
        case Nil => Async[F].unit
        case violations =>
          Async[F].raiseError(
            CombineRejected(s"asset state does not conform to strict schema: ${violations.mkString("; ")}")
          )
      }
    }

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // JSON-Logic guard evaluation (metakit boundary, no fiber MTL stack)
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  /** Evaluate a guard; truthy → ok, false / non-boolean / failure → graceful `CombineRejected(reason)`. */
  private def evalGuardOrReject(
    expr:    JsonLogicExpression,
    context: JsonLogicValue,
    reason:  String
  ): F[Unit] =
    JsonLogicEvaluator
      .tailRecursive[F]
      .evaluateWithGas(expr, context, None, GasLimit(maxGuardGas), GasConfig.Default)
      .flatMap {
        case Right(EvaluationResult(BoolValue(true), _, _, _)) => Async[F].unit
        case Right(EvaluationResult(BoolValue(false), _, _, _)) =>
          Async[F].raiseError(CombineRejected(reason))
        case Right(EvaluationResult(other, _, _, _)) =>
          // COMMITTED via RejectionReceipt.reason (audit L1): the value kind is rendered through OttoChain's own
          // stable `ValueKind`, not metakit's `.tag` (byte-neutral today, but pins us against a metakit re-tag).
          Async[F].raiseError(CombineRejected(s"$reason (guard returned non-boolean ${ValueKind.of(other)})"))
        case Left(ex) =>
          // The committed reason carries NO exception text (audit L1 — a metakit reword must not change a
          // rejected tx's committed hash); the raw metakit detail is preserved in LOGS only.
          Slf4jLogger.getLogger[F].warn(ex)(s"$reason (guard evaluation raised: ${ex.getMessage})") >>
          Async[F].raiseError(CombineRejected(s"$reason (guard evaluation failed)"))
      }

  /** Context for a mint guard: the requested holder, amount, signers, ordinal, derived supply. */
  private def mintContext(m: Updates.MintAsset, signers: Set[Address], ordinal: SnapshotOrdinal): JsonLogicValue =
    MapValue(
      Map(
        "assetId" -> StrValue(m.assetId.toString),
        "amount"  -> IntValue(BigInt(m.amount)),
        "holder"  -> holderJlv(m.holder),
        "signers" -> jsonAsJlv(signers.toList.map(_.show).asJson),
        "ordinal" -> IntValue(BigInt(ordinal.value.value)),
        // ZkVerify-gated mint (asset-model.md §8): the optional proof / Merkle-membership witness carried on
        // the signed `MintAsset`, exposed under the reserved `witness` key so a `mintPolicy` guard can call a
        // metakit verifier opcode over it — e.g. `{"pmt_verify":[<root>,{"var":"witness.leaf"},
        // {"var":"witness.index"},{"var":"witness.siblings"}]}` ("mint iff this inclusion proof verifies").
        "witness" -> m.witness.getOrElse(NullValue)
      )
    )

  /**
   * Context for a Governed morphism guard.
   *
   * == ZkVerify-gated morphisms (asset-model.md §8) ==
   * The reserved `witness` key exposes the optional proof / Merkle-membership witness carried on the signed
   * [[Updates.ApplyMorphism]] (`NullValue` when omitted). A `Governed` morphism's `MorphismSpec.guard` can
   * therefore REQUIRE a zk/inclusion proof by calling one of metakit's verifier opcodes over it — e.g.
   * `{"pmt_verify":[<root>,{"var":"witness.leaf"},{"var":"witness.index"},{"var":"witness.siblings"}]}` or
   * `{"groth16_verify":[<vkey>,{"var":"witness.publicValues"},{"var":"witness.proof"}]}`. The opcode runs
   * DETERMINISTICALLY in the combiner through the SAME [[evalGuardOrReject]] path every guard uses (one
   * reused verifier, gas-metered, not hand-rolled per use); a false / failed verification is a graceful
   * `CombineRejected`, never a snapshot abort.
   *
   * CAVEAT (be honest): metakit's Groth16 / Poseidon-Merkle verifier has NO public security audit. A
   * ZkVerify-gated guard is sound only up to the correctness of that verifier, so it MUST NOT be used to
   * protect real value until metakit's verifier is independently audited. See
   * docs/proposals/asset-model.md §8 ("ZkVerify-gated morphisms").
   */
  private def morphismContext(
    a:       Updates.ApplyMorphism,
    source:  AssetRecord,
    signers: Set[Address],
    ordinal: SnapshotOrdinal
  ): JsonLogicValue =
    MapValue(
      Map(
        "assetId"   -> StrValue(a.assetId.toString),
        "kind"      -> StrValue(a.kind.entryName),
        "amount"    -> IntValue(BigInt(source.amount)),
        "holder"    -> holderJlv(source.holder),
        "recipient" -> a.recipient.fold(NullValue: JsonLogicValue)(holderJlv),
        "signers"   -> jsonAsJlv(signers.toList.map(_.show).asJson),
        "ordinal"   -> IntValue(BigInt(ordinal.value.value)),
        // The proof / Merkle-membership witness for a ZkVerify-gated guard (NullValue when omitted).
        "witness" -> a.witness.getOrElse(NullValue)
      )
    )

  private def holderJlv(h: AssetHolder): JsonLogicValue = h match {
    case AssetHolder.Wallet(addr) => MapValue(Map("wallet" -> StrValue(addr.show)))
    case AssetHolder.Fiber(id)    => MapValue(Map("fiber" -> StrValue(id.toString)))
  }

  /** Bridge a circe `Json` into the `JsonLogicValue` ADT (only used for simple guard-context fragments). */
  private def jsonAsJlv(j: io.circe.Json): JsonLogicValue =
    j.as[JsonLogicValue].getOrElse(NullValue)

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // State write helpers (assets + assetCommits, atomic)
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  /**
   * Write an `AssetRecord` into `CalculatedState.assets`, its `AssetCommit` into the cumulative
   * `CalculatedState.assetCommits`, and the same commit into the per-batch
   * `OnChain.touchedAssetCommits` delta. A write also clears any same-batch burn of the id so the
   * delta stays consistent (`touched`/`burned` disjoint — see `CommitIndex.fold`).
   */
  private def writeAsset(
    st:     DataState[OnChain, CalculatedState],
    record: AssetRecord
  ): F[DataState[OnChain, CalculatedState]] =
    record.computeDigest.map { recordHash =>
      val commit = AssetCommit(record.behavior.bits, record.sequenceNumber, recordHash, origin = None)
      st
        .focus(_.onChain.touchedAssetCommits)
        .modify(_.updated(record.assetId, commit))
        .focus(_.onChain.burnedAssets)
        .modify(_ - record.assetId)
        .focus(_.calculated.assetCommits)
        .modify(_.updated(record.assetId, commit))
        .focus(_.calculated.assets)
        .modify(_.updated(record.assetId, record))
    }

  /**
   * Remove an asset (burn / compose-consume): drop it from `CalculatedState.assets` + the
   * cumulative `CalculatedState.assetCommits`, record the removal in the per-batch
   * `OnChain.burnedAssets` delta, and clear any same-batch touch of the id.
   */
  private def removeAsset(
    st:      DataState[OnChain, CalculatedState],
    assetId: UUID
  ): DataState[OnChain, CalculatedState] =
    st
      .focus(_.onChain.touchedAssetCommits)
      .modify(_ - assetId)
      .focus(_.onChain.burnedAssets)
      .modify(_ + assetId)
      .focus(_.calculated.assetCommits)
      .modify(_ - assetId)
      .focus(_.calculated.assets)
      .modify(_ - assetId)

  /** Derived total supply for a policy: `Σ amount` over assets whose binding name matches. */
  private def derivedSupply(policyName: RegistryName): Long =
    current.calculated.assets.values.filter(_.schemaBinding.name === policyName).map(_.amount).sum

  // ════════════════════════════════════════════════════════════════════════════════════════════════
  // Component-witness commitment (the FAITHFUL Compose/Decompose retraction, asset-model §4)
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  /** Snapshot a consumed component's RESTORABLE state as a [[ComponentWitness]] (excludes volatile ordinals). */
  private def witnessOf(record: AssetRecord): ComponentWitness =
    ComponentWitness(
      assetId = record.assetId,
      schemaBinding = record.schemaBinding,
      behavior = record.behavior,
      holder = record.holder,
      amount = record.amount,
      expiresAt = record.expiresAt,
      componentFiberIds = record.componentFiberIds,
      componentsCommitment = record.componentsCommitment,
      provenance = record.provenance
    )

  /**
   * Restore a [[ComponentWitness]] to a fresh [[AssetRecord]] at `ordinal`: the volatile
   * creation/latest ordinals reset to `ordinal` and the sequence to `FiberOrdinal.MinValue`; every other
   * field (behavior / holder / amount / schemaBinding / expiresAt / nested composite anchors / provenance)
   * is carried VERBATIM from the witness. `parentCompositeId` clears (the component is live again).
   */
  private def restoreWitness(w: ComponentWitness, ordinal: SnapshotOrdinal): AssetRecord =
    AssetRecord(
      assetId = w.assetId,
      schemaBinding = w.schemaBinding,
      behavior = w.behavior,
      holder = w.holder,
      amount = w.amount,
      sequenceNumber = FiberOrdinal.MinValue,
      creationOrdinal = ordinal,
      latestUpdateOrdinal = ordinal,
      expiresAt = w.expiresAt,
      componentFiberIds = w.componentFiberIds,
      componentsCommitment = w.componentsCommitment,
      parentCompositeId = None,
      provenance = w.provenance
    )

  /** Canonical ordering of a witness list — sort by `assetId` so the commitment is order-independent. */
  private def canonicalize(ws: List[ComponentWitness]): List[ComponentWitness] =
    ws.sortBy(_.assetId)

  /**
   * The component commitment — `hash(canonicalWitnesses)` over the canonical-JSON of the SORTED
   * `List[ComponentWitness]`, via the SAME digest mechanism the combiner uses everywhere
   * (`computeDigest` = `JsonBinaryHasher`/`HasherOps` over the canonical JSON). The caller passes an
   * already-`canonicalize`d list; this hashes it as-is.
   */
  private def componentsCommitment(canonicalWitnesses: List[ComponentWitness]): F[Hash] =
    canonicalWitnesses.computeDigest

  /** Drop nonce entries for assets that no longer exist (bounded growth, R7). */
  private def pruneStaleNonces(nonces: SortedMap[UUID, SortedSet[Long]]): SortedMap[UUID, SortedSet[Long]] =
    nonces.filter { case (assetId, ns) => ns.nonEmpty && current.calculated.assets.contains(assetId) }

  /** Deterministic shard id from the source id + index (used when `shardIds` is not supplied). */
  private def deriveShardId(sourceId: UUID, index: Int): UUID =
    UUID.nameUUIDFromBytes(s"shard:$sourceId:$index".getBytes(java.nio.charset.StandardCharsets.UTF_8))

  /** Raise a graceful `CombineRejected(reason)` unless `cond` holds. */
  private def raiseRejected(cond: Boolean, reason: => String): F[Unit] =
    Async[F].raiseError(CombineRejected(reason)).unlessA(cond)
}

object AssetCombiner {

  def apply[F[_]: Async: SecurityProvider](
    current:        DataState[OnChain, CalculatedState],
    ctx:            L0NodeContext[F],
    maxBundleBytes: Long
  ): AssetCombiner[F] =
    new AssetCombiner[F](current, ctx, maxBundleBytes)
}
