package xyz.kd5ujc.shared_data

import java.util.UUID

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry.SemVer

import weaver.FunSuite

/**
 * Pure unit tests for the FiberPolicy TIGHTEN-ONLY partial order over the REQUIRED, NAMED ADT and the
 * smart-constructor canonicalization (`Constrained`-all-empty ⇒ `Unconstrained`). One case per dial per direction:
 * tighten OK, loosen rejected, None→Some OK, Some→None rejected where applicable.
 */
object FiberPolicyTightensSuite extends FunSuite {

  private def uuid(n: Int): UUID = new UUID(0L, n.toLong)
  private val a = uuid(1)
  private val b = uuid(2)

  private def ok(old: FiberPolicy, neu: FiberPolicy) =
    expect(FiberPolicy.tightens(old, neu).isRight)

  private def rejected(old: FiberPolicy, neu: FiberPolicy, dial: String) =
    expect(FiberPolicy.tightens(old, neu) == Left(dial))

  // ── smart constructor (canonical "unconstrained" form) ─────────────────────────────────────────────

  test("constrained: an all-empty Constrained collapses to Unconstrained; a partial policy is preserved") {
    expect(FiberPolicy.constrained(FiberPolicy.Constrained()) == FiberPolicy.Unconstrained) and
    expect(FiberPolicy.constrained() == FiberPolicy.Unconstrained) and
    expect(FiberPolicy.constrained(selfReproducing = Some(true)).isInstanceOf[FiberPolicy.Constrained])
  }

  test("tightens: an Unconstrained old policy ⇒ ANY successor is a valid tightening from unconstrained") {
    val anything = FiberPolicy.constrained(
      selfReproducing = Some(true),
      allowedEffects = Some(Set(EffectKind.Emit)),
      sealedStates = Some(Set(StateId("done"))),
      maxGenerations = Some(1)
    )
    expect(FiberPolicy.tightens(FiberPolicy.Unconstrained, anything).isRight) and
    // an all-empty Constrained is canonically Unconstrained, so it is also BOTTOM
    expect(FiberPolicy.tightens(FiberPolicy.constrained(FiberPolicy.Constrained()), anything).isRight) and
    // identity tightening (same policy) is always OK
    expect(FiberPolicy.tightens(anything, anything).isRight)
  }

  test("tightens: a Constrained old ⇒ Unconstrained new is a LOOSENING (back to unconstrained) and is rejected") {
    val old = FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Emit)))
    expect(FiberPolicy.tightens(old, FiberPolicy.Unconstrained).isLeft)
  }

  // ── selfReproducing (one-way latch) ──────────────────────────────────────────────────────────────

  test("selfReproducing: OFF→ON OK; ON→OFF latched (rejected); ON→ON OK") {
    ok(FiberPolicy.Unconstrained, FiberPolicy.constrained(selfReproducing = Some(true))) and
    ok(FiberPolicy.constrained(selfReproducing = Some(true)), FiberPolicy.constrained(selfReproducing = Some(true))) and
    rejected(
      FiberPolicy.constrained(selfReproducing = Some(true)),
      FiberPolicy.constrained(selfReproducing = Some(false)),
      "selfReproducing"
    ) and
    // dropping the field entirely (⇒ Unconstrained) also clears the latch ⇒ rejected
    rejected(FiberPolicy.constrained(selfReproducing = Some(true)), FiberPolicy.Unconstrained, "selfReproducing")
  }

  // ── allowedEffects (set shrinks) ─────────────────────────────────────────────────────────────────

  test("allowedEffects: None→Some OK; subset OK; superset rejected; Some→None rejected") {
    ok(FiberPolicy.Unconstrained, FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Emit)))) and
    ok(
      FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Emit, EffectKind.Trigger))),
      FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Emit)))
    ) and
    rejected(
      FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Emit))),
      FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Emit, EffectKind.Spawn))),
      "allowedEffects"
    ) and
    rejected(
      FiberPolicy.constrained(allowedEffects = Some(Set(EffectKind.Emit))),
      FiberPolicy.Unconstrained,
      "allowedEffects"
    )
  }

  // ── spawnOwnerPolicy (lattice rank up only) ──────────────────────────────────────────────────────

  test("spawnOwnerPolicy: Explicit→SubsetOfParent→InheritParent OK; reverse rejected") {
    ok(
      FiberPolicy.constrained(spawnOwnerPolicy = Some(SpawnOwnerPolicy.Explicit)),
      FiberPolicy.constrained(spawnOwnerPolicy = Some(SpawnOwnerPolicy.InheritParent))
    ) and
    ok(
      FiberPolicy.constrained(spawnOwnerPolicy = Some(SpawnOwnerPolicy.SubsetOfParent)),
      FiberPolicy.constrained(spawnOwnerPolicy = Some(SpawnOwnerPolicy.InheritParent))
    ) and
    rejected(
      FiberPolicy.constrained(spawnOwnerPolicy = Some(SpawnOwnerPolicy.InheritParent)),
      FiberPolicy.constrained(spawnOwnerPolicy = Some(SpawnOwnerPolicy.Explicit)),
      "spawnOwnerPolicy"
    )
  }

  // ── numeric caps (shrink only) ───────────────────────────────────────────────────────────────────

  test("maxGenerations / maxSpawnFanout: shrink OK; grow rejected; Some→None rejected") {
    ok(FiberPolicy.constrained(maxGenerations = Some(5)), FiberPolicy.constrained(maxGenerations = Some(3))) and
    rejected(
      FiberPolicy.constrained(maxGenerations = Some(3)),
      FiberPolicy.constrained(maxGenerations = Some(5)),
      "maxGenerations"
    ) and
    rejected(FiberPolicy.constrained(maxGenerations = Some(3)), FiberPolicy.Unconstrained, "maxGenerations") and
    ok(FiberPolicy.constrained(maxSpawnFanout = Some(4)), FiberPolicy.constrained(maxSpawnFanout = Some(2))) and
    rejected(
      FiberPolicy.constrained(maxSpawnFanout = Some(2)),
      FiberPolicy.constrained(maxSpawnFanout = Some(4)),
      "maxSpawnFanout"
    )
  }

  // ── acceptedCallers (set shrinks) ────────────────────────────────────────────────────────────────

  test("acceptedCallers: None→Some OK; subset OK; superset rejected") {
    ok(FiberPolicy.Unconstrained, FiberPolicy.constrained(acceptedCallers = Some(Set(a)))) and
    ok(
      FiberPolicy.constrained(acceptedCallers = Some(Set(a, b))),
      FiberPolicy.constrained(acceptedCallers = Some(Set(a)))
    ) and
    rejected(
      FiberPolicy.constrained(acceptedCallers = Some(Set(a))),
      FiberPolicy.constrained(acceptedCallers = Some(Set(a, b))),
      "acceptedCallers"
    )
  }

  // ── sealedStates (set GROWS — opposite direction) ────────────────────────────────────────────────

  test("sealedStates: the sealed set may only GROW; shrinking is rejected") {
    ok(
      FiberPolicy.constrained(sealedStates = Some(Set(StateId("a")))),
      FiberPolicy.constrained(sealedStates = Some(Set(StateId("a"), StateId("b"))))
    ) and
    rejected(
      FiberPolicy.constrained(sealedStates = Some(Set(StateId("a"), StateId("b")))),
      FiberPolicy.constrained(sealedStates = Some(Set(StateId("a")))),
      "sealedStates"
    ) and
    rejected(
      FiberPolicy.constrained(sealedStates = Some(Set(StateId("a")))),
      FiberPolicy.Unconstrained,
      "sealedStates"
    )
  }

  // ── transferPolicy (recipient allowlists shrink) ─────────────────────────────────────────────────

  test("transferPolicy: recipient allowlists may only shrink") {
    ok(
      FiberPolicy.constrained(transferPolicy = Some(TransferPolicy(allowedRecipientFibers = Some(Set(a, b))))),
      FiberPolicy.constrained(transferPolicy = Some(TransferPolicy(allowedRecipientFibers = Some(Set(a)))))
    ) and
    rejected(
      FiberPolicy.constrained(transferPolicy = Some(TransferPolicy(allowedRecipientFibers = Some(Set(a))))),
      FiberPolicy.constrained(transferPolicy = Some(TransferPolicy(allowedRecipientFibers = Some(Set(a, b))))),
      "transferPolicy.allowedRecipientFibers"
    )
  }

  // ── dependencyPolicy (mode rank up; allowlist shrinks) ───────────────────────────────────────────

  test("dependencyPolicy: Open→Allowlist→Frozen OK; reverse rejected; Allowlist shrinks") {
    ok(
      FiberPolicy.constrained(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Open))),
      FiberPolicy.constrained(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Frozen)))
    ) and
    rejected(
      FiberPolicy.constrained(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Frozen))),
      FiberPolicy.constrained(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist))),
      "dependencyPolicy.mode"
    ) and
    ok(
      FiberPolicy.constrained(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist, Some(Set(a, b))))),
      FiberPolicy.constrained(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist, Some(Set(a)))))
    ) and
    rejected(
      FiberPolicy.constrained(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist, Some(Set(a))))),
      FiberPolicy.constrained(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist, Some(Set(a, b))))),
      "dependencyPolicy.allowed"
    )
  }

  // ── upgradePolicy (lattice rank up only; absent ≡ Arbitrary) ─────────────────────────────────────

  test("upgradePolicy: rank may only INCREASE; absent is Arbitrary (rank 0)") {
    // Arbitrary→AppendOnly→Governed→Immutable all tighten (rank up)
    ok(
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.Arbitrary)),
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.AppendOnly))
    ) and
    ok(
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.AppendOnly)),
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.Immutable))
    ) and
    // Unconstrained (≡ Arbitrary)→AppendOnly OK
    ok(FiberPolicy.Unconstrained, FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.AppendOnly))) and
    // Immutable→Governed LOOSENS (rank 3→2) ⇒ rejected. Authority is identified by a registry-fiber Role here
    // (no Address needed in this pure suite); contents are irrelevant to the rank lattice.
    rejected(
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.Immutable)),
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.Governed(MigrationAuthority.Role(a, "admins")))),
      "upgradePolicy"
    ) and
    // AppendOnly→Unconstrained (≡ Arbitrary, rank 1→0) LOOSENS ⇒ rejected
    rejected(
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.AppendOnly)),
      FiberPolicy.Unconstrained,
      "upgradePolicy"
    ) and
    // Governed→Governed (same rank, different authority) is a valid same-rank move (rotation gated at UpgradeGate)
    ok(
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.Governed(MigrationAuthority.Role(a, "admins")))),
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.Governed(MigrationAuthority.Role(b, "admins"))))
    )
  }

  // ── version (must advance) ───────────────────────────────────────────────────────────────────────

  test("version: may only ADVANCE; None→Some OK; Some→None rejected; regress rejected") {
    ok(
      FiberPolicy.constrained(version = Some(SemVer(1, 0, 0))),
      FiberPolicy.constrained(version = Some(SemVer(1, 0, 1)))
    ) and
    ok(
      FiberPolicy.constrained(version = Some(SemVer(1, 2, 3))),
      FiberPolicy.constrained(version = Some(SemVer(1, 2, 3)))
    ) and
    ok(FiberPolicy.Unconstrained, FiberPolicy.constrained(version = Some(SemVer(1, 0, 0)))) and
    rejected(
      FiberPolicy.constrained(version = Some(SemVer(2, 0, 0))),
      FiberPolicy.constrained(version = Some(SemVer(1, 9, 9))),
      "version"
    ) and
    rejected(FiberPolicy.constrained(version = Some(SemVer(1, 0, 0))), FiberPolicy.Unconstrained, "version")
  }

  // ── interfaces (set GROWS — a consumer must not lose an advertised capability) ───────────────────

  test("interfaces: the advertised set may only GROW; shrinking/dropping is rejected") {
    ok(
      FiberPolicy.constrained(interfaces = Some(Set("ITransfer"))),
      FiberPolicy.constrained(interfaces = Some(Set("ITransfer", "IPause")))
    ) and
    ok(FiberPolicy.Unconstrained, FiberPolicy.constrained(interfaces = Some(Set("ITransfer")))) and
    rejected(
      FiberPolicy.constrained(interfaces = Some(Set("ITransfer", "IPause"))),
      FiberPolicy.constrained(interfaces = Some(Set("ITransfer"))),
      "interfaces"
    ) and
    rejected(FiberPolicy.constrained(interfaces = Some(Set("ITransfer"))), FiberPolicy.Unconstrained, "interfaces")
  }

  // ── enum wire strings (cross-language contract with the SDK builder) ─────────────────────────────

  test("enum entry names are UPPERCASE (the SDK string contract)") {
    expect(EffectKind.Trigger.entryName == "TRIGGER") and
    expect(EffectKind.Spawn.entryName == "SPAWN") and
    expect(EffectKind.Emit.entryName == "EMIT") and
    expect(EffectKind.Transfer.entryName == "TRANSFER") and
    expect(EffectKind.Dependency.entryName == "DEPENDENCY") and
    expect(SpawnOwnerPolicy.InheritParent.entryName == "INHERITPARENT") and
    expect(SpawnOwnerPolicy.SubsetOfParent.entryName == "SUBSETOFPARENT") and
    expect(SpawnOwnerPolicy.Explicit.entryName == "EXPLICIT") and
    expect(DependencyMode.Open.entryName == "OPEN") and
    expect(DependencyMode.Allowlist.entryName == "ALLOWLIST") and
    expect(DependencyMode.Frozen.entryName == "FROZEN")
  }

  // ── Immutable (the named preset = upgradePolicy Immutable; near the top of the lattice) ──────────────

  test("tightens: Immutable is reachable from Unconstrained but cannot loosen out") {
    ok(FiberPolicy.Unconstrained, FiberPolicy.Immutable) and // bottom → Immutable: valid tightening
    ok(FiberPolicy.Immutable, FiberPolicy.Immutable) and // idempotent
    // Immutable → anything that drops the Immutable tier LOOSENS upgradePolicy ⇒ rejected
    rejected(FiberPolicy.Immutable, FiberPolicy.Unconstrained, "upgradePolicy") and
    rejected(
      FiberPolicy.Immutable,
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.AppendOnly)),
      "upgradePolicy"
    )
  }
}
