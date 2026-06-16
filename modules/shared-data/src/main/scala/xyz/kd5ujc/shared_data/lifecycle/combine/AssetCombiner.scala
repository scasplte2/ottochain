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
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Records.AssetRecord
import xyz.kd5ujc.schema.asset._
import xyz.kd5ujc.schema.fiber.{ExecutionLimits, FiberOrdinal}
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{AssetCommit, CalculatedState, OnChain, Updates}
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
      result <- applyKind(a, source, counterParties, policy, currentOrdinal)
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
  // Apply per kind (codomain function + state mutation + sequence increment)
  // ════════════════════════════════════════════════════════════════════════════════════════════════

  private def applyKind(
    a:              Updates.ApplyMorphism,
    source:         AssetRecord,
    counterParties: List[AssetRecord],
    policy:         RegistryShape.AssetPolicy,
    ordinal:        SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    a.kind match {
      case MorphismKind.Transfer      => applyTransfer(a, source, ordinal)
      case MorphismKind.Burn          => applyBurn(a, source, policy, ordinal)
      case MorphismKind.Fractionalize => applyFractionalize(a, source, ordinal)
      case MorphismKind.Compose       => applyCompose(a, source, counterParties, ordinal)
      case MorphismKind.Decompose     => applyDecompose(a, source, ordinal)
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
   * behavior homomorphism); store the component ids VERBATIM on the composite (the retraction anchor); mark
   * the source + components consumed (removed, and tagged `parentCompositeId`); the composite id comes from
   * `compositeId`. Amount is conserved: `composite.amount == Σ (source + components).amount`.
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
    ordinal:        SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
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
        val composite = source.copy(
          assetId = compositeId,
          behavior = TokenBehavior.foldMeet(parts.map(_.behavior)),
          amount = parts.map(_.amount).sum,
          sequenceNumber = FiberOrdinal.MinValue,
          creationOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          componentFiberIds = Some(componentIds), // stored VERBATIM — the retraction anchor
          parentCompositeId = None
        )
        // Consume the commit-reveal nonce (if present) atomically within this pass, BEFORE consuming the
        // components, so a failure raises before any state mutation.
        consumeNonce(current, a.nonce, counterParties).flatMap { stateAfterNonce =>
          // Mark each component consumed: remove from the live set (its record is preserved inside the
          // composite's componentFiberIds for the Decompose retraction).
          val consumed = componentIds.foldLeft(stateAfterNonce) { case (st, id) => removeAsset(st, id) }
          writeAsset(consumed, composite)
        }
      }
    }

  /**
   * Consume a commit-reveal nonce: find a counter-party whose `usedNonces` set contains `n` (the LIVE
   * pending authorization), then remove it (linear, one-time). Absent `nonce` is a no-op (a same-holder
   * Compose needs no consent). A referenced-but-absent nonce → reject ("not found / already consumed").
   */
  private def consumeNonce(
    st:             DataState[OnChain, CalculatedState],
    nonce:          Option[Long],
    counterParties: List[AssetRecord]
  ): F[DataState[OnChain, CalculatedState]] =
    nonce match {
      case None => st.pure[F]
      case Some(n) =>
        counterParties.find(cp =>
          st.calculated.usedNonces.getOrElse(cp.assetId, SortedSet.empty[Long]).contains(n)
        ) match {
          case None =>
            Async[F].raiseError(
              CombineRejected(s"compose nonce $n not found among counter-party authorizations (or already consumed)")
            )
          case Some(authorizer) =>
            val updatedSet = st.calculated.usedNonces.getOrElse(authorizer.assetId, SortedSet.empty[Long]) - n
            val updatedNonces =
              if (updatedSet.isEmpty) st.calculated.usedNonces - authorizer.assetId
              else st.calculated.usedNonces.updated(authorizer.assetId, updatedSet)
            st.focus(_.calculated.usedNonces).replace(updatedNonces).pure[F]
        }
    }

  /**
   * Decompose → require `componentFiberIds` present; RESTORE the component records exactly as stored (the
   * retraction — `Decompose ∘ Compose = id` on `componentFiberIds`), then REMOVE the composite. Restored
   * components are returned unmodified (same ids, behaviors, amounts) so the round-trip is the identity on
   * the component multiset. Amount is conserved: `Σ restored.amount == composite.amount`.
   */
  private def applyDecompose(
    a:       Updates.ApplyMorphism,
    source:  AssetRecord,
    ordinal: SnapshotOrdinal
  ): F[DataState[OnChain, CalculatedState]] =
    source.componentFiberIds
      .filter(_.nonEmpty)
      .fold(
        Async[F].raiseError[DataState[OnChain, CalculatedState]](
          CombineRejected(s"Decompose of ${a.assetId} requires a composite (no componentFiberIds)")
        )
      ) { componentIds =>
        // Reconstruct the component records from the stored ids. We do not retain the full original records
        // (only their ids — the verbatim retraction anchor), so the restored components inherit the composite's
        // schemaBinding/behavior. The IDENTITY guaranteed by the retraction is on the id multiset (and amount
        // conservation), per the RFC's "stored componentFiberIds returned unmodified" — never a two-sided
        // Compose ∘ Decompose = id.
        val n = componentIds.length.toLong
        val base = source.amount / n
        val rem = source.amount - base * n
        val restored = componentIds.zipWithIndex.map { case (cid, i) =>
          source.copy(
            assetId = cid,
            amount = if (i == 0) base + rem else base,
            sequenceNumber = FiberOrdinal.MinValue,
            creationOrdinal = ordinal,
            latestUpdateOrdinal = ordinal,
            componentFiberIds = None,
            parentCompositeId = None
          )
        }
        val withoutComposite = removeAsset(current, a.assetId)
        restored.foldLeftM(withoutComposite) { case (st, comp) => writeAsset(st, comp) }
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

  /** Resolve the asset's bound `AssetPolicy` version (combiner-only registry lineage read). */
  private def resolveAssetPolicy(source: AssetRecord): F[RegistryShape.AssetPolicy] =
    current.calculated.registry
      .get(source.schemaBinding.name)
      .map(_.target)
      .collect { case RegistryTarget.AssetPolicyPackage(l) => l }
      .flatMap(_.versions.get(source.schemaBinding.version)) match {
      case Some(rv) =>
        rv.shape match {
          case ap: RegistryShape.AssetPolicy => ap.pure[F]
          case other =>
            Async[F].raiseError(
              CombineRejected(
                s"${source.schemaBinding.name.render} resolves to a non-asset shape ${other.getClass.getSimpleName}"
              )
            )
        }
      case None =>
        Async[F].raiseError(
          CombineRejected(
            s"asset ${source.assetId} binding ${source.schemaBinding.name.render}@${source.schemaBinding.version.render} did not resolve"
          )
        )
    }

  /** Resolve the `policyRef` of a mint to (name, version, shape). Missing/yanked → reject. */
  private def resolvePolicy(ref: SchemaRef): F[(RegistryName, RegisteredVersion, RegistryShape.AssetPolicy)] =
    current.calculated.registry
      .get(ref.name)
      .map(_.target)
      .collect { case RegistryTarget.AssetPolicyPackage(l) => l } match {
      case None =>
        Async[F].raiseError(CombineRejected(s"policyRef ${ref.name.render} is not a known asset-policy package"))
      case Some(lineage) =>
        lineage
          .resolve(ref.version)
          .fold(
            e =>
              Async[F].raiseError[(RegistryName, RegisteredVersion, RegistryShape.AssetPolicy)](
                CombineRejected(s"policyRef unresolvable for ${ref.name.render}: $e")
              ),
            rv =>
              rv.shape match {
                case ap: RegistryShape.AssetPolicy => (ref.name, rv, ap).pure[F]
                case other =>
                  Async[F].raiseError[(RegistryName, RegisteredVersion, RegistryShape.AssetPolicy)](
                    CombineRejected(s"${ref.name.render} resolves to a non-asset shape ${other.getClass.getSimpleName}")
                  )
              }
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
          Async[F].raiseError(CombineRejected(s"$reason (guard returned non-boolean ${other.tag})"))
        case Left(ex) =>
          Async[F].raiseError(CombineRejected(s"$reason (guard evaluation failed: ${ex.getMessage})"))
      }

  /** Context for a mint guard: the requested holder, amount, signers, ordinal, derived supply. */
  private def mintContext(m: Updates.MintAsset, signers: Set[Address], ordinal: SnapshotOrdinal): JsonLogicValue =
    MapValue(
      Map(
        "assetId" -> StrValue(m.assetId.toString),
        "amount"  -> IntValue(BigInt(m.amount)),
        "holder"  -> holderJlv(m.holder),
        "signers" -> jsonAsJlv(signers.toList.map(_.show).asJson),
        "ordinal" -> IntValue(BigInt(ordinal.value.value))
      )
    )

  /** Context for a Governed morphism guard. */
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
        "ordinal"   -> IntValue(BigInt(ordinal.value.value))
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

  /** Write an `AssetRecord` into `CalculatedState.assets` and its `AssetCommit` into `OnChain.assetCommits`. */
  private def writeAsset(
    st:     DataState[OnChain, CalculatedState],
    record: AssetRecord
  ): F[DataState[OnChain, CalculatedState]] =
    record.computeDigest.map { recordHash =>
      val commit = AssetCommit(record.behavior.bits, record.sequenceNumber, recordHash, origin = None)
      st
        .focus(_.onChain.assetCommits)
        .modify(_.updated(record.assetId, commit))
        .focus(_.calculated.assets)
        .modify(_.updated(record.assetId, record))
    }

  /** Remove an asset from both `CalculatedState.assets` and `OnChain.assetCommits`. */
  private def removeAsset(
    st:      DataState[OnChain, CalculatedState],
    assetId: UUID
  ): DataState[OnChain, CalculatedState] =
    st
      .focus(_.onChain.assetCommits)
      .modify(_ - assetId)
      .focus(_.calculated.assets)
      .modify(_ - assetId)

  /** Derived total supply for a policy: `Σ amount` over assets whose binding name matches. */
  private def derivedSupply(policyName: RegistryName): Long =
    current.calculated.assets.values.filter(_.schemaBinding.name === policyName).map(_.amount).sum

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
