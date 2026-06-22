package xyz.kd5ujc.schema.fiber

import java.util.UUID

import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive
import enumeratum.EnumEntry.Uppercase
import enumeratum.{CirceEnum, Enum, EnumEntry}

/**
 * The 5 directive families [[xyz.kd5ujc.shared_data.fiber.evaluation.EffectExtractor]] scrapes from a
 * transition's effect result. A fiber's [[FiberPolicy.allowedEffects]] (when set) restricts which of these
 * its transitions may produce. Entry names are UPPERCASE on the wire (`"TRIGGER"`, `"SPAWN"`, …) — the
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

/**
 * The fiber's hash-pinned, opt-in constitution (fiber-policy.md, `fiberpolicy-dials` stream).
 *
 * A fiber voluntarily surrenders a capability in return for a guarantee any external observer can verify by
 * resolving ONE `logicHash`-anchored field. Every dial is `Option`/defaulted so a partial policy survives
 * `dropNulls` byte-stably and an all-default [[FiberPolicy]] is canonically identical to an absent one
 * (`Some(empty) ⇒ None`, see [[FiberPolicy.normalize]]). Default `policy = None` on
 * [[StateMachineDefinition]] is exactly today's unconstrained behaviour, hash-identical to a pre-policy
 * definition.
 *
 * Enforcement is FAIL-CLOSED everywhere: a dial breach aborts the whole transition (total discard) or rejects
 * the update via [[FailureReason.PolicyViolation]] / `CombineRejected` — no dial ever silently strips a
 * directive. Across a migration a policy may only TIGHTEN (see [[FiberPolicy.tightens]]).
 *
 * SCOPE: the version/compatibility family (`upgradePolicy`/`version`/`compatibleWith`/`interfaces`) and the
 * pause/freeze runtime ops are DEFERRED to later waves and intentionally absent here.
 */
@derive(customizableEncoder, customizableDecoder)
final case class FiberPolicy(
  selfReproducing:  Option[Boolean] = None, // Dial #1: a _spawn child's definition must hash-equal the parent's
  allowedEffects:   Option[Set[EffectKind]] = None, // None ⇒ all families (legacy)
  spawnOwnerPolicy: Option[SpawnOwnerPolicy] = None,
  maxGenerations:   Option[Int] = None, // same-definition-hash spawn-lineage depth cap
  maxSpawnFanout:   Option[Int] = None, // children per single transition (tighter than ExecutionLimits cap)
  acceptedCallers:  Option[Set[UUID]] = None, // fiber-origin ($caller) allowlist; user/wallet origin unaffected
  sealedStates:     Option[Set[StateId]] = None, // states from which NO transition may fire (terminal/halted)
  transferPolicy:   Option[TransferPolicy] = None,
  dependencyPolicy: Option[DependencyPolicy] = None
) {

  /** An all-default policy is semantically equivalent to no policy at all. */
  def isEmpty: Boolean =
    selfReproducing.isEmpty && allowedEffects.isEmpty && spawnOwnerPolicy.isEmpty &&
    maxGenerations.isEmpty && maxSpawnFanout.isEmpty && acceptedCallers.isEmpty &&
    sealedStates.isEmpty && transferPolicy.isEmpty && dependencyPolicy.isEmpty

  /** Convenience: this dial is ON only when explicitly set to `true`. */
  def isSelfReproducing: Boolean = selfReproducing.contains(true)
}

object FiberPolicy {

  val empty: FiberPolicy = FiberPolicy()

  /**
   * Normalize `Some(empty) ⇒ None` so an all-default policy hashes identically to an absent one. This is the
   * internal-determinism rule the verified re-bind (`definition.computeDigest === logicHash`) relies on,
   * independent of any back-compat concern: two definitions that mean the same thing MUST hash the same,
   * regardless of which client (chain, SDK, third-party) wrote them.
   */
  def normalize(p: Option[FiberPolicy]): Option[FiberPolicy] = p.filterNot(_.isEmpty)

  // ────────────────────────────────────────────────────────────────────────────────────────────────
  // TIGHTEN-ONLY partial order (the trust anchor)
  // ────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * `tightens(old, neu)` succeeds iff `neu` is at least as restrictive as `old` on EVERY dial — the only
   * direction a policy may move across a migration. Both sides are normalized first, so `Some(empty)` is
   * treated as `None`; an absent `old` is fully-unconstrained, hence ANY `neu` is a valid tightening from
   * "anything goes". Returns `Left(dial)` naming the first dial that LOOSENS.
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
   */
  def tightens(old: Option[FiberPolicy], neu: Option[FiberPolicy]): Either[String, Unit] =
    normalize(old) match {
      case None => Right(()) // unconstrained prior ⇒ any successor is a valid tightening
      case Some(o) =>
        val n = normalize(neu).getOrElse(empty)
        for {
          _ <- latchOn("selfReproducing", o.selfReproducing, n.selfReproducing)
          _ <- subset("allowedEffects", o.allowedEffects, n.allowedEffects)
          _ <- rankUp("spawnOwnerPolicy", o.spawnOwnerPolicy.map(_.rank), n.spawnOwnerPolicy.map(_.rank))
          _ <- capShrinks("maxGenerations", o.maxGenerations, n.maxGenerations)
          _ <- capShrinks("maxSpawnFanout", o.maxSpawnFanout, n.maxSpawnFanout)
          _ <- subset("acceptedCallers", o.acceptedCallers, n.acceptedCallers)
          _ <- superset("sealedStates", o.sealedStates, n.sealedStates)
          _ <- transferTightens(o.transferPolicy, n.transferPolicy)
          _ <- dependencyTightens(o.dependencyPolicy, n.dependencyPolicy)
        } yield ()
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
