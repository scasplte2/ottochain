package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.registry.SemVer

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive
import enumeratum.EnumEntry.Uppercase
import enumeratum.{CirceEnum, Enum, EnumEntry}
import io.circe.{Decoder, DecodingFailure, Encoder, Json}

/**
 * The 5 directive families [[xyz.kd5ujc.shared_data.fiber.evaluation.EffectExtractor]] scrapes from a
 * transition's effect result. A fiber's [[FiberPolicy.Constrained.allowedEffects]] (when set) restricts which of
 * these its transitions may produce. Entry names are UPPERCASE on the wire (`"TRIGGER"`, `"SPAWN"`, …) — the
 * cross-language string contract with the SDK builder.
 */
sealed trait EffectKind extends EnumEntry with Uppercase

object EffectKind extends Enum[EffectKind] with CirceEnum[EffectKind] {
  val values: IndexedSeq[EffectKind] = findValues

  case object Trigger extends EffectKind // _triggers + _scriptCall
  case object Spawn extends EffectKind // _spawn
  case object Emit extends EffectKind // _emit
  case object Transfer extends EffectKind // _transferAsset
  case object Dependency extends EffectKind // _addDependency / _setDependencyActive
}

/**
 * Constrains the `owners` a transition may assign to a `_spawn`ed child relative to the parent's owners.
 * Ordered from loosest (`Explicit`, today's behaviour) to tightest (`InheritParent`); the tighten-only
 * lattice may only move DOWN this order across a migration.
 */
sealed trait SpawnOwnerPolicy extends EnumEntry with Uppercase { def rank: Int }

object SpawnOwnerPolicy extends Enum[SpawnOwnerPolicy] with CirceEnum[SpawnOwnerPolicy] {
  val values: IndexedSeq[SpawnOwnerPolicy] = findValues

  case object Explicit extends SpawnOwnerPolicy { val rank = 0 } // any owners (current behaviour; loosest)
  case object SubsetOfParent extends SpawnOwnerPolicy { val rank = 1 } // child.owners ⊆ parent.owners
  case object InheritParent extends SpawnOwnerPolicy { val rank = 2 } // child.owners forced == parent.owners
}

/**
 * Dynamic-dependency posture. Ordered from loosest (`Open`, today's behaviour) to tightest (`Frozen`).
 * The tighten-only lattice may only move DOWN this order across a migration.
 */
sealed trait DependencyMode extends EnumEntry with Uppercase { def rank: Int }

object DependencyMode extends Enum[DependencyMode] with CirceEnum[DependencyMode] {
  val values: IndexedSeq[DependencyMode] = findValues

  case object Open extends DependencyMode { val rank = 0 } // any _addDependency target permitted (loosest)
  case object Allowlist extends DependencyMode { val rank = 1 } // only `allowed` fiberIds may be added
  case object Frozen extends DependencyMode { val rank = 2 } // no NEW dependency fiberIds; existing may toggle
}

/**
 * Who may submit a `TransitionStateMachine` for this fiber — the WALLET/owner authorization axis, enforced on
 * the AUTHORITATIVE apply path (the combiner) as a graceful `CombineRejected`
 * (03-cross-fiber-and-authorization.md §3). Orthogonal to [[FiberPolicy.Constrained.acceptedCallers]] (the
 * FIBER-origin `$caller` axis); the two compose. Ordered loosest→tightest by `rank`; the tighten-only lattice
 * may only move UP (Open → OwnersOrParticipants → Owners), never loosen — so a fiber can never launder itself
 * from `Owners` down to `Open`. An ABSENT dial is [[TransitionPolicy.Open]] (rank 0) — today's LIVE,
 * guard-only behaviour — so every pre-dial fiber is UNCHANGED and apps opt UP explicitly (the §3.4 / §6 Q1
 * default-Open decision; tightening the default is a separate, maintainer-reserved security call).
 */
sealed trait TransitionPolicy { def rank: Int }

object TransitionPolicy {

  /** Any signer; the transition GUARD is the sole gate (today's live behaviour). LOOSEST — the absent default. */
  case object Open extends TransitionPolicy { val rank = 0 }

  /** The verified signer(s) must be in `owners ∪ authorizedSigners` (the create-time participant allowlist). */
  case object OwnersOrParticipants extends TransitionPolicy { val rank = 1 }

  /** The verified signer(s) must be in `owners` only. TIGHTEST. */
  case object Owners extends TransitionPolicy { val rank = 2 }

  /** The default tier for an absent dial — `Open` (today's live guard-only behaviour). Use everywhere `None`. */
  val default: TransitionPolicy = Open

  // Bare, self-documenting string tags (mirrors UpgradePolicy's bare-object branch + FiberPolicy.Immutable's
  // "Immutable"): total, fail-closed. The canonical/signing paths `dropNulls`, so an absent dial is stripped
  // and a pre-dial definition stays byte-identical (rule #1); a set dial round-trips through the tag.
  implicit val encoder: Encoder[TransitionPolicy] = Encoder.instance {
    case Open                 => Json.fromString("Open")
    case OwnersOrParticipants => Json.fromString("OwnersOrParticipants")
    case Owners               => Json.fromString("Owners")
  }

  implicit val decoder: Decoder[TransitionPolicy] =
    Decoder.decodeString.emap {
      case "Open"                 => Right(Open)
      case "OwnersOrParticipants" => Right(OwnersOrParticipants)
      case "Owners"               => Right(Owners)
      case other                  => Left(s"unknown transitionPolicy '$other'")
    }
}

/**
 * Recipient allowlist for `_transferAsset`. Carries NO conservation/amount notion: `AssetTransferred` is a
 * whole-record custody move with no amount, so the only meaningful dial is WHO may receive. `None` on either
 * field ⇒ that recipient class is unconstrained.
 */
@derive(customizableEncoder, customizableDecoder)
final case class TransferPolicy(
  allowedRecipientFibers:  Option[Set[UUID]] = None,
  allowedRecipientWallets: Option[Set[Address]] = None
)

/**
 * Dynamic-dependency policy: `mode` is the posture; `allowed` is meaningful only when `mode == Allowlist`.
 */
@derive(customizableEncoder, customizableDecoder)
final case class DependencyPolicy(
  mode:    DependencyMode = DependencyMode.Open,
  allowed: Option[Set[UUID]] = None
)

// ════════════════════════════════════════════════════════════════════════════════════════════════════
// VERSION & COMPATIBILITY FAMILY (fiber-policy.md `version-compat-family` stream)
// ════════════════════════════════════════════════════════════════════════════════════════════════════

/**
 * Who may authorize a migration when [[UpgradePolicy.Governed]] is in force. Read from the OLD (hash-pinned)
 * definition's policy at the version being upgraded FROM — NEVER re-suppliable in `newDefinition` — which
 * closes the self-authorizing `Role(registryFiberId = attacker-fiber)` hole (spec §3.2 / I3).
 *
 * Prior art: cw2 `ensure_from_older_version` + migrate-admin; OZ proxy admin / `Ownable`; SPL freeze authority.
 */
sealed trait MigrationAuthority

object MigrationAuthority {

  /** Permit iff at least one VERIFIED signer address of the UpgradeFiber update is in `addresses`. */
  @derive(customizableEncoder, customizableDecoder)
  final case class Signers(addresses: Set[Address]) extends MigrationAuthority

  /**
   * Permit iff any VERIFIED signer address is a key of the flat per-role map at `roleField` in the registry
   * fiber `registryFiberId`'s state (`{<address>: true}`, the `signerHasRoleVia` shape). `registryFiberId` is
   * pinned to the OLD metadata only; total/fail-closed — a missing fiber / missing map / non-map ⇒ DENY.
   */
  @derive(customizableEncoder, customizableDecoder)
  final case class Role(registryFiberId: UUID, roleField: String) extends MigrationAuthority

  // Signers encodes as {"addresses":[...]}, Role as {"registryFiberId":..,"roleField":..} — disjoint field
  // sets act as a natural discriminator (same pattern as RegistryShape).
  implicit val encoder: Encoder[MigrationAuthority] = Encoder.instance {
    case s: Signers => Encoder[Signers].apply(s)
    case r: Role    => Encoder[Role].apply(r)
  }

  implicit val decoder: Decoder[MigrationAuthority] =
    Decoder[Signers].map[MigrationAuthority](identity).or(Decoder[Role].map[MigrationAuthority](identity))
}

/**
 * The upgrade-path constitution: how (if at all) the fiber's hash-pinned definition may change. Ordered by
 * `rank` so the tighten-only lattice may only move UP (Immutable > Governed > AppendOnly > Arbitrary); a
 * migration can never launder a stricter tier down to a looser one. `None` on [[FiberPolicy.Constrained.upgradePolicy]]
 * is exactly [[Arbitrary]] (rank 0, today's behaviour) at every site.
 *
 * Prior art: Aptos `upgrade_policy` (immutable / compatible / arbitrary); Sui `UpgradeCap`; CosmWasm cw2
 * migrate-admin; Substrate additive `StorageVersion`; protobuf additive+reserved.
 */
sealed trait UpgradePolicy { def rank: Int }

object UpgradePolicy {

  /** Aptos `immutable` / frozen Sui `UpgradeCap`: reject ALL migrations. Terminal — tighten-only cannot leave. */
  case object Immutable extends UpgradePolicy { val rank = 3 }

  /** Aptos `compatible` + signer / cw2 migrate-admin: migrate only with the `migrationAuthority`'s consent. */
  @derive(customizableEncoder, customizableDecoder)
  final case class Governed(authority: MigrationAuthority) extends UpgradePolicy { val rank = 2 }

  /** Substrate additive `StorageVersion` / protobuf additive: the schema delta must be additive-only. */
  case object AppendOnly extends UpgradePolicy { val rank = 1 }

  /** Aptos `arbitrary`: today's unconstrained migrate. Identical to an absent `upgradePolicy`. */
  case object Arbitrary extends UpgradePolicy { val rank = 0 }

  /** The default tier for an absent dial — `Arbitrary`. Use everywhere `upgradePolicy` is `None`. */
  val default: UpgradePolicy = Arbitrary

  // Governed encodes as {"authority":{...}}; the bare objects encode as their tag string so the SDK's
  // exact-string contract holds. A bare-string OR a Governed object both decode total/fail-closed.
  implicit val encoder: Encoder[UpgradePolicy] = Encoder.instance {
    case Immutable   => Encoder.encodeString("immutable")
    case AppendOnly  => Encoder.encodeString("appendOnly")
    case Arbitrary   => Encoder.encodeString("arbitrary")
    case g: Governed => Encoder[Governed].apply(g)
  }

  implicit val decoder: Decoder[UpgradePolicy] =
    Decoder[Governed]
      .map[UpgradePolicy](identity)
      .or(Decoder.decodeString.emap {
        case "immutable"  => Right(Immutable)
        case "appendOnly" => Right(AppendOnly)
        case "arbitrary"  => Right(Arbitrary)
        case other        => Left(s"unknown upgradePolicy '$other'")
      })
}

/**
 * An inclusive-min / exclusive-max SemVer window: the successor versions THIS definition declares it will
 * bridge a migration TO (spec §5.3 — the bridge direction is the ONLY meaning; the consumer's compat
 * assertion is the runtime `depVersionAtLeast` guard, never a second decorative field meaning). `min`/`max`
 * are each optional; an unset bound is unconstrained on that side. Prior art: protobuf-semver compat window;
 * Cargo/npm `^`/`~`.
 */
@derive(customizableEncoder, customizableDecoder)
final case class VersionRange(
  min: Option[SemVer] = None,
  max: Option[SemVer] = None // exclusive
) {

  /** `min <= v < max`, treating an unset bound as unconstrained. */
  def contains(v: SemVer): Boolean =
    min.forall(lo => SemVer.ordering.lteq(lo, v)) && max.forall(hi => SemVer.ordering.lt(v, hi))
}

/**
 * The fiber's hash-pinned constitution (fiber-policy.md, `fiberpolicy-dials` stream), as a REQUIRED, NAMED
 * ADT. Every definition has exactly one of two constitutions:
 *
 *   - [[FiberPolicy.Unconstrained]] — the named default: NO surrendered capability, today's legacy behaviour.
 *     Replaces the old `None`/`Some(empty)`. [[StateMachineDefinition.policy]] defaults to it, so a pre-policy
 *     definition is hash-identical to one that explicitly declares `Unconstrained`.
 *   - [[FiberPolicy.Constrained]] — at least one of the 15 dials is set. A fiber voluntarily surrenders a capability
 *     in return for a guarantee any external observer can verify by resolving ONE `logicHash`-anchored field.
 *
 * Every dial of [[Constrained]] is `Option`/defaulted so a partial policy survives `dropNulls` byte-stably. A
 * `Constrained` with ALL 15 dials empty is SEMANTICALLY identical to `Unconstrained`; the smart constructor
 * [[FiberPolicy.constrained]] (and the decoder) collapse it to `Unconstrained` so there is exactly ONE canonical
 * representation of "unconstrained". This is the internal-determinism rule the verified re-bind
 * (`definition.computeDigest === logicHash`) relies on: an absent policy key, `Unconstrained`, and an
 * all-empty `Constrained` ALL encode to the same canonical bytes (field default + smart constructor + dropNulls).
 *
 * Enforcement is FAIL-CLOSED everywhere: a dial breach aborts the whole transition (total discard) or rejects
 * the update via [[FailureReason.PolicyViolation]] / `CombineRejected` — no dial ever silently strips a
 * directive. Across a migration a policy may only TIGHTEN (see [[FiberPolicy.tightens]]).
 *
 * SCOPE: the version/compatibility family (`upgradePolicy`/`version`/`compatibleWith`/`interfaces`/
 * `migrationAuthority`) is enforced at the migrate boundary by [[xyz.kd5ujc.shared_data.fiber.UpgradeGate]].
 * The pause/freeze runtime ops remain DEFERRED to a later wave and are intentionally absent here.
 */
sealed trait FiberPolicy {

  /** Convenience: this dial is ON only when explicitly set to `true`. `Unconstrained` is never reproducing. */
  def isSelfReproducing: Boolean = this match {
    case FiberPolicy.Unconstrained  => false
    case FiberPolicy.Immutable      => false
    case c: FiberPolicy.Constrained => c.selfReproducing.contains(true)
  }

  /** The effective upgrade tier — an absent dial (or `Unconstrained`) is [[UpgradePolicy.Arbitrary]] (legacy). */
  def effectiveUpgradePolicy: UpgradePolicy =
    dials.flatMap(_.upgradePolicy).getOrElse(UpgradePolicy.default)

  /**
   * The dial bundle when this is a [[FiberPolicy.Constrained]], else `None` (an `Unconstrained` policy has NO dials
   * set). Keeps consumer call sites that read a single dial readable: `policy.dials.flatMap(_.sealedStates)`
   * replaces the old `policy.flatMap(_.sealedStates)` over an `Option[FiberPolicy]`, with the identical shape.
   */
  def dials: Option[FiberPolicy.Constrained] = this match {
    case FiberPolicy.Unconstrained => None
    // Immutable projects to its single-dial equivalent (upgradePolicy=Immutable) so consumers
    // (effectiveUpgradePolicy, tightens, the engine's reads) see it as a Constrained with nothing else set.
    case FiberPolicy.Immutable      => Some(FiberPolicy.Constrained(upgradePolicy = Some(UpgradePolicy.Immutable)))
    case c: FiberPolicy.Constrained => Some(c)
  }
}

object FiberPolicy {

  /**
   * The named default: NO surrendered capability — today's unconstrained/legacy behaviour. Replaces the old
   * `None` / `Some(empty)`. Encodes to JSON null, which `dropNulls` strips, so the `policy` key is OMITTED.
   */
  case object Unconstrained extends FiberPolicy

  /**
   * A named preset: the definition is permanently LOCKED — semantically `upgradePolicy = Immutable` with no
   * other dial set. A first-class peer of [[Unconstrained]] / [[Constrained]] that encodes to the bare string
   * `"Immutable"`. The smart constructor [[FiberPolicy.constrained]] (and the decoder) collapse a `Constrained`
   * that sets ONLY `upgradePolicy = Immutable` to this, so there is exactly ONE canonical form.
   */
  case object Immutable extends FiberPolicy

  /**
   * A constitution that sets at least one of the 15 dials. Constructed ONLY via the smart constructor
   * [[FiberPolicy.constrained]] (or the decoder), which collapses an all-empty `Constrained` to [[Unconstrained]] so
   * there is exactly ONE canonical "unconstrained" form, and `constrained` further collapses an only-
   * upgradePolicy=Immutable bundle to [[Immutable]]. Encodes as its bare dials object `{<set dials, after
   * dropNulls>}` (no wrapper).
   */
  @derive(customizableEncoder, customizableDecoder)
  final case class Constrained(
    selfReproducing:  Option[Boolean] = None, // Dial #1: a _spawn child's definition must hash-equal the parent's
    allowedEffects:   Option[Set[EffectKind]] = None, // None ⇒ all families (legacy)
    spawnOwnerPolicy: Option[SpawnOwnerPolicy] = None,
    maxGenerations:   Option[Int] = None, // same-definition-hash spawn-lineage depth cap
    maxSpawnFanout:   Option[Int] = None, // children per single transition (tighter than ExecutionLimits cap)
    acceptedCallers:  Option[Set[UUID]] = None, // fiber-origin ($caller) allowlist; user/wallet origin unaffected
    transitionPolicy: Option[TransitionPolicy] = None, // wallet/owner transition-auth axis; None ⇒ Open (legacy)
    sealedStates:     Option[Set[StateId]] = None, // states from which NO transition may fire (terminal/halted)
    transferPolicy:   Option[TransferPolicy] = None,
    dependencyPolicy: Option[DependencyPolicy] = None,
    // ── version & compatibility family ──
    upgradePolicy:      Option[UpgradePolicy] = None, // None ⇒ Arbitrary (legacy); gates migrate at UpgradeGate
    version:            Option[SemVer] = None, // self-declared semantic version (TRUST-LAYER; gate uses schemaBinding)
    compatibleWith:     Option[VersionRange] = None, // bridge window: which successor versions migrate may target
    interfaces:         Option[Set[String]] = None, // ERC-165 interface ids the fiber advertises (self-declared)
    migrationAuthority: Option[MigrationAuthority] = None // who may authorize a Governed migration
  ) extends FiberPolicy {

    /** An all-default `Constrained` is semantically equivalent to `Unconstrained`. */
    def isEmpty: Boolean =
      selfReproducing.isEmpty && allowedEffects.isEmpty && spawnOwnerPolicy.isEmpty &&
      maxGenerations.isEmpty && maxSpawnFanout.isEmpty && acceptedCallers.isEmpty &&
      transitionPolicy.isEmpty && sealedStates.isEmpty && transferPolicy.isEmpty &&
      dependencyPolicy.isEmpty && upgradePolicy.isEmpty && version.isEmpty &&
      compatibleWith.isEmpty && interfaces.isEmpty && migrationAuthority.isEmpty

    /** Exactly `upgradePolicy = Immutable` and nothing else — semantically equal to [[FiberPolicy.Immutable]]. */
    def isImmutable: Boolean =
      upgradePolicy.contains(UpgradePolicy.Immutable) && copy(upgradePolicy = None).isEmpty
  }

  /**
   * SMART CONSTRUCTOR (the determinism rule — load-bearing). Returns [[Unconstrained]] when every dial is
   * empty, otherwise the given [[Constrained]]. This is the ONLY way a `Constrained` should be built, and the codec
   * routes through it on decode, so an all-empty `Constrained` (typed OR on the wire as `{"Constrained":{}}`) is
   * indistinguishable from `Unconstrained`. Two definitions that mean the same thing MUST hash the same,
   * regardless of which client (chain, SDK, third-party) wrote them.
   */
  def constrained(c: Constrained): FiberPolicy =
    if (c.isEmpty) Unconstrained
    else if (c.isImmutable) Immutable // exactly upgradePolicy=Immutable ⇒ the named preset (one canonical form)
    else c

  /**
   * Named, all-defaulted convenience for the common case — the same dials as [[Constrained]], collapsed through the
   * smart constructor. Lets call sites write `FiberPolicy.constrained(allowedEffects = Some(...))`.
   */
  def constrained(
    selfReproducing:    Option[Boolean] = None,
    allowedEffects:     Option[Set[EffectKind]] = None,
    spawnOwnerPolicy:   Option[SpawnOwnerPolicy] = None,
    maxGenerations:     Option[Int] = None,
    maxSpawnFanout:     Option[Int] = None,
    acceptedCallers:    Option[Set[UUID]] = None,
    transitionPolicy:   Option[TransitionPolicy] = None,
    sealedStates:       Option[Set[StateId]] = None,
    transferPolicy:     Option[TransferPolicy] = None,
    dependencyPolicy:   Option[DependencyPolicy] = None,
    upgradePolicy:      Option[UpgradePolicy] = None,
    version:            Option[SemVer] = None,
    compatibleWith:     Option[VersionRange] = None,
    interfaces:         Option[Set[String]] = None,
    migrationAuthority: Option[MigrationAuthority] = None
  ): FiberPolicy =
    constrained(
      Constrained(
        selfReproducing,
        allowedEffects,
        spawnOwnerPolicy,
        maxGenerations,
        maxSpawnFanout,
        acceptedCallers,
        transitionPolicy,
        sealedStates,
        transferPolicy,
        dependencyPolicy,
        upgradePolicy,
        version,
        compatibleWith,
        interfaces,
        migrationAuthority
      )
    )

  // ── Codec ──────────────────────────────────────────────────────────────────────────────────────────
  // The named variant lives in CODE, not the bytes. `Unconstrained` encodes to JSON null — which the
  // canonical path's `dropNulls` strips, so the `policy` key is OMITTED on the wire. Absence == Unconstrained,
  // which is exactly what every pre-policy definition and the e2e/SDK already produce → client↔chain byte
  // parity for FREE, with no sentinel to coordinate. `Constrained` encodes as its bare dials object (its own
  // derived codec; `dropNulls` then strips the unset dials). Mirrors the UpgradePolicy / MigrationAuthority
  // constrained ADT codecs in this file. NOTE: the canonical/signing/hash paths all `dropNulls`, so a `policy:null`
  // never reaches the wire; a non-dropNulls encode would carry `"policy":null`, which still decodes back to
  // Unconstrained.
  private val constrainedEncoder: Encoder[Constrained] = Encoder[Constrained] // derevo-derived dials-object encoder
  private val constrainedDecoder: Decoder[Constrained] = Decoder[Constrained]

  implicit val encoder: Encoder[FiberPolicy] = Encoder.instance {
    case Unconstrained  => Json.Null
    case Immutable      => Json.fromString("Immutable") // named preset ⇒ a bare, self-documenting string
    case c: Constrained => constrainedEncoder(c)
  }

  // null/absent ⇒ Unconstrained; the bare string "Immutable" ⇒ Immutable; an object ⇒ Constrained routed
  // through the smart constructor — so an all-empty `{}` collapses to Unconstrained and an only-upgradePolicy=
  // Immutable object collapses to Immutable. ONE canonical form in BOTH directions.
  implicit val decoder: Decoder[FiberPolicy] = Decoder.instance { c =>
    val j = c.value
    if (j.isNull) Right(Unconstrained)
    else
      j.asString match {
        case Some("Immutable") => Right(Immutable)
        case Some(other)       => Left(DecodingFailure(s"unknown FiberPolicy variant '$other'", c.history))
        case None              => constrainedDecoder(c).map(constrained)
      }
  }

  // ────────────────────────────────────────────────────────────────────────────────────────────────
  // TIGHTEN-ONLY partial order (the trust anchor)
  // ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * `tightens(old, neu)` succeeds iff `neu` is at least as restrictive as `old` on EVERY dial — the only
   * direction a policy may move across a migration. [[Unconstrained]] is BOTTOM (loosest): an `Unconstrained`
   * `old` is fully-unconstrained, hence ANY `neu` is a valid tightening from "anything goes". A `Constrained` `old`
   * with a `Unconstrained` `neu` LOOSENS every set dial back to "anything goes" and is rejected (Left naming
   * the first loosened dial). Returns `Left(dial)` naming the first dial that LOOSENS.
   *
   * Per-dial order (`neu` must be ≥ `old` in restrictiveness):
   *   - selfReproducing: one-way latch — once ON, may never turn OFF (a fiber cannot graduate out).
   *   - allowedEffects:  the permitted set may only SHRINK (`neu ⊆ old`); `None`→`Some` OK, `Some`→`None` no.
   *   - spawnOwnerPolicy: authority may only tighten (rank may only increase / move down the lattice).
   *   - maxGenerations / maxSpawnFanout: caps may only SHRINK (`neu ≤ old`); `None`→`Some` OK, `Some`→`None` no.
   *   - acceptedCallers: the caller allowlist may only SHRINK (`neu ⊆ old`); `None`→`Some` OK.
   *   - sealedStates:   the sealed set may only GROW (`neu ⊇ old`) — opposite direction (more states locked).
   *   - transferPolicy: each recipient allowlist may only SHRINK (`neu ⊆ old`); `None`→`Some` OK.
   *   - dependencyPolicy: mode may only tighten (rank up: Open ⊐ Allowlist ⊐ Frozen); an Allowlist may only shrink.
   *   - upgradePolicy:   tier rank may only INCREASE (Immutable 3 > Governed 2 > AppendOnly 1 > Arbitrary 0);
   *                      an absent dial is Arbitrary (rank 0). You can never launder a stricter tier downward.
   *   - version:         the self-declared version may only ADVANCE (`neu.version >= old.version`); `None`→`Some`
   *                      OK, `Some`→`None` rejected (a published version may not be retracted).
   *   - interfaces:      the advertised interface set may only GROW (`neu ⊇ old`) — a consumer that relied on
   *                      an advertised capability must not have it silently dropped. `None`→`Some` OK.
   *
   * NOTE: `compatibleWith` (the migration bridge window) is intentionally NOT part of the tighten lattice —
   * it is enforced directionally at the migrate boundary by [[xyz.kd5ujc.shared_data.fiber.UpgradeGate]]
   * (the OLD definition's window must contain the NEW version), not as a monotone successor constraint.
   * `migrationAuthority` is likewise consulted at the gate (rotation under the same Governed rank is allowed,
   * matching cw2 admin-rotation), so it is not constrained here either.
   */
  def tightens(old: FiberPolicy, neu: FiberPolicy): Either[String, Unit] =
    old.dials match {
      case None    => Right(()) // Unconstrained (bottom) ⇒ any successor is a valid tightening
      case Some(o) =>
        // Project the successor to its dial-set: Unconstrained ⇒ all-None (loosens any set dial); Immutable ⇒
        // upgradePolicy=Immutable; Constrained ⇒ itself. Run the per-dial order so the FIRST loosened dial is named.
        val n = neu.dials.getOrElse(Constrained())
        for {
          _ <- latchOn("selfReproducing", o.selfReproducing, n.selfReproducing)
          _ <- subset("allowedEffects", o.allowedEffects, n.allowedEffects)
          _ <- rankUp("spawnOwnerPolicy", o.spawnOwnerPolicy.map(_.rank), n.spawnOwnerPolicy.map(_.rank))
          _ <- capShrinks("maxGenerations", o.maxGenerations, n.maxGenerations)
          _ <- capShrinks("maxSpawnFanout", o.maxSpawnFanout, n.maxSpawnFanout)
          _ <- subset("acceptedCallers", o.acceptedCallers, n.acceptedCallers)
          // transitionPolicy: the auth posture may only TIGHTEN (rank up: Open ⊐ OwnersOrParticipants ⊐
          // Owners); an absent dial is Open (rank 0), so None→Some(stricter) is OK and a stricter-old→None
          // loosens (rejected). Never launder Owners down to Open across a migration.
          _ <- rankUp("transitionPolicy", o.transitionPolicy.map(_.rank), n.transitionPolicy.map(_.rank))
          _ <- superset("sealedStates", o.sealedStates, n.sealedStates)
          _ <- transferTightens(o.transferPolicy, n.transferPolicy)
          _ <- dependencyTightens(o.dependencyPolicy, n.dependencyPolicy)
          // version & compatibility family
          _ <- upgradeTierUp(o.upgradePolicy, n.upgradePolicy)
          _ <- versionAdvances(o.version, n.version)
          _ <- superset("interfaces", o.interfaces, n.interfaces)
        } yield ()
    }

  /**
   * The upgrade tier may only become STRICTER (rank may only increase). An absent dial is `Arbitrary` (rank
   * 0), so `None`→`Some(stricter)` is a valid tightening and a stricter-old→`None`(neu) LOOSENS (rejected).
   * Governed→Governed (same rank) is permitted here — authority rotation is gated at [[UpgradeGate]] instead.
   */
  private def upgradeTierUp(old: Option[UpgradePolicy], neu: Option[UpgradePolicy]): Either[String, Unit] = {
    val oldRank = old.getOrElse(UpgradePolicy.default).rank
    val newRank = neu.getOrElse(UpgradePolicy.default).rank
    if (newRank >= oldRank) Right(()) else Left("upgradePolicy")
  }

  /** A published `version` may only ADVANCE: `neu >= old`; `None`(old) ⇒ any OK; `Some`(old)→`None`(neu) loosens. */
  private def versionAdvances(old: Option[SemVer], neu: Option[SemVer]): Either[String, Unit] =
    old match {
      case None => Right(())
      case Some(o) =>
        neu.fold[Either[String, Unit]](Left("version"))(n =>
          if (SemVer.ordering.gteq(n, o)) Right(()) else Left("version")
        )
    }

  /** A boolean latch: once `old == Some(true)`, `neu` must also be `Some(true)`. */
  private def latchOn(dial: String, old: Option[Boolean], neu: Option[Boolean]): Either[String, Unit] =
    if (old.contains(true) && !neu.contains(true)) Left(dial) else Right(())

  /** `neu ⊆ old`; `None` (old) ⇒ unconstrained-prior so any `neu` is OK; `Some`(old) → `None`(neu) loosens. */
  private def subset[A](dial: String, old: Option[Set[A]], neu: Option[Set[A]]): Either[String, Unit] =
    old match {
      case None    => Right(())
      case Some(o) => neu.fold[Either[String, Unit]](Left(dial))(n => if (n.subsetOf(o)) Right(()) else Left(dial))
    }

  /** `neu ⊇ old`; `old` absent ⇒ unconstrained, any `neu` (incl. None) OK; `old` present requires `neu ⊇ old`. */
  private def superset[A](dial: String, old: Option[Set[A]], neu: Option[Set[A]]): Either[String, Unit] =
    old match {
      case None    => Right(())
      case Some(o) => neu.fold[Either[String, Unit]](Left(dial))(n => if (o.subsetOf(n)) Right(()) else Left(dial))
    }

  /** A numeric cap may only shrink: `neu ≤ old`; `None`(old) ⇒ any `neu` OK; `Some`(old) → `None`(neu) loosens. */
  private def capShrinks(dial: String, old: Option[Int], neu: Option[Int]): Either[String, Unit] =
    old match {
      case None    => Right(())
      case Some(o) => neu.fold[Either[String, Unit]](Left(dial))(n => if (n <= o) Right(()) else Left(dial))
    }

  /** Lattice rank may only increase: `neu.rank ≥ old.rank`; `None`(old) ⇒ any OK; `Some`(old) → `None`(neu) loosens. */
  private def rankUp(dial: String, old: Option[Int], neu: Option[Int]): Either[String, Unit] =
    old match {
      case None    => Right(())
      case Some(o) => neu.fold[Either[String, Unit]](Left(dial))(n => if (n >= o) Right(()) else Left(dial))
    }

  private def transferTightens(old: Option[TransferPolicy], neu: Option[TransferPolicy]): Either[String, Unit] =
    old match {
      case None => Right(())
      case Some(o) =>
        neu match {
          case None => Left("transferPolicy")
          case Some(n) =>
            for {
              _ <- subset("transferPolicy.allowedRecipientFibers", o.allowedRecipientFibers, n.allowedRecipientFibers)
              _ <- subset(
                "transferPolicy.allowedRecipientWallets",
                o.allowedRecipientWallets,
                n.allowedRecipientWallets
              )
            } yield ()
        }
    }

  private def dependencyTightens(old: Option[DependencyPolicy], neu: Option[DependencyPolicy]): Either[String, Unit] =
    old match {
      case None => Right(())
      case Some(o) =>
        neu match {
          case None => Left("dependencyPolicy")
          case Some(n) =>
            for {
              _ <- rankUp("dependencyPolicy.mode", Some(o.mode.rank), Some(n.mode.rank))
              // An Allowlist→Allowlist tightening may only shrink the allowed set. When the new mode is
              // Frozen (stricter), the allowed set is moot, so only constrain when BOTH are Allowlist.
              _ <-
                if (o.mode == DependencyMode.Allowlist && n.mode == DependencyMode.Allowlist)
                  subset("dependencyPolicy.allowed", o.allowed, n.allowed)
                else Right(())
            } yield ()
        }
    }
}
