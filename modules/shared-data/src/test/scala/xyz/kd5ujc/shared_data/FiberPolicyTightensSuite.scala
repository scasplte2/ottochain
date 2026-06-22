package xyz.kd5ujc.shared_data

import java.util.UUID

import xyz.kd5ujc.schema.fiber._

import weaver.FunSuite

/**
 * Pure unit tests for the FiberPolicy TIGHTEN-ONLY partial order and the empty-policy normalization. One case
 * per dial per direction: tighten OK, loosen rejected, None→Some OK, Some→None rejected where applicable.
 */
object FiberPolicyTightensSuite extends FunSuite {

  private def uuid(n: Int): UUID = new UUID(0L, n.toLong)
  private val a = uuid(1)
  private val b = uuid(2)

  private def ok(old: FiberPolicy, neu: FiberPolicy) =
    expect(FiberPolicy.tightens(Some(old), Some(neu)).isRight)

  private def rejected(old: FiberPolicy, neu: FiberPolicy, dial: String) =
    expect(FiberPolicy.tightens(Some(old), Some(neu)) == Left(dial))

  // ── normalize ────────────────────────────────────────────────────────────────────────────────────

  test("normalize: Some(empty) ⇒ None; a partial policy is preserved") {
    expect(FiberPolicy.normalize(Some(FiberPolicy.empty)).isEmpty) and
    expect(FiberPolicy.normalize(Some(FiberPolicy(selfReproducing = Some(true)))).isDefined) and
    expect(FiberPolicy.normalize(None).isEmpty)
  }

  test("tightens: an absent (or empty) old policy ⇒ ANY successor is a valid tightening from unconstrained") {
    val anything = FiberPolicy(
      selfReproducing = Some(true),
      allowedEffects = Some(Set(EffectKind.Emit)),
      sealedStates = Some(Set(StateId("done"))),
      maxGenerations = Some(1)
    )
    expect(FiberPolicy.tightens(None, Some(anything)).isRight) and
    expect(FiberPolicy.tightens(Some(FiberPolicy.empty), Some(anything)).isRight) and
    // identity tightening (same policy) is always OK
    expect(FiberPolicy.tightens(Some(anything), Some(anything)).isRight)
  }

  test("tightens: a non-empty old ⇒ None new is a LOOSENING (back to unconstrained) and is rejected") {
    val old = FiberPolicy(allowedEffects = Some(Set(EffectKind.Emit)))
    expect(FiberPolicy.tightens(Some(old), None).isLeft)
  }

  // ── selfReproducing (one-way latch) ──────────────────────────────────────────────────────────────

  test("selfReproducing: OFF→ON OK; ON→OFF latched (rejected); ON→ON OK") {
    ok(FiberPolicy(), FiberPolicy(selfReproducing = Some(true))) and
    ok(FiberPolicy(selfReproducing = Some(true)), FiberPolicy(selfReproducing = Some(true))) and
    rejected(
      FiberPolicy(selfReproducing = Some(true)),
      FiberPolicy(selfReproducing = Some(false)),
      "selfReproducing"
    ) and
    // dropping the field entirely also clears the latch ⇒ rejected
    rejected(FiberPolicy(selfReproducing = Some(true)), FiberPolicy(), "selfReproducing")
  }

  // ── allowedEffects (set shrinks) ─────────────────────────────────────────────────────────────────

  test("allowedEffects: None→Some OK; subset OK; superset rejected; Some→None rejected") {
    ok(FiberPolicy(), FiberPolicy(allowedEffects = Some(Set(EffectKind.Emit)))) and
    ok(
      FiberPolicy(allowedEffects = Some(Set(EffectKind.Emit, EffectKind.Trigger))),
      FiberPolicy(allowedEffects = Some(Set(EffectKind.Emit)))
    ) and
    rejected(
      FiberPolicy(allowedEffects = Some(Set(EffectKind.Emit))),
      FiberPolicy(allowedEffects = Some(Set(EffectKind.Emit, EffectKind.Spawn))),
      "allowedEffects"
    ) and
    rejected(FiberPolicy(allowedEffects = Some(Set(EffectKind.Emit))), FiberPolicy(), "allowedEffects")
  }

  // ── spawnOwnerPolicy (lattice rank up only) ──────────────────────────────────────────────────────

  test("spawnOwnerPolicy: Explicit→SubsetOfParent→InheritParent OK; reverse rejected") {
    ok(
      FiberPolicy(spawnOwnerPolicy = Some(SpawnOwnerPolicy.Explicit)),
      FiberPolicy(spawnOwnerPolicy = Some(SpawnOwnerPolicy.InheritParent))
    ) and
    ok(
      FiberPolicy(spawnOwnerPolicy = Some(SpawnOwnerPolicy.SubsetOfParent)),
      FiberPolicy(spawnOwnerPolicy = Some(SpawnOwnerPolicy.InheritParent))
    ) and
    rejected(
      FiberPolicy(spawnOwnerPolicy = Some(SpawnOwnerPolicy.InheritParent)),
      FiberPolicy(spawnOwnerPolicy = Some(SpawnOwnerPolicy.Explicit)),
      "spawnOwnerPolicy"
    )
  }

  // ── numeric caps (shrink only) ───────────────────────────────────────────────────────────────────

  test("maxGenerations / maxSpawnFanout: shrink OK; grow rejected; Some→None rejected") {
    ok(FiberPolicy(maxGenerations = Some(5)), FiberPolicy(maxGenerations = Some(3))) and
    rejected(FiberPolicy(maxGenerations = Some(3)), FiberPolicy(maxGenerations = Some(5)), "maxGenerations") and
    rejected(FiberPolicy(maxGenerations = Some(3)), FiberPolicy(), "maxGenerations") and
    ok(FiberPolicy(maxSpawnFanout = Some(4)), FiberPolicy(maxSpawnFanout = Some(2))) and
    rejected(FiberPolicy(maxSpawnFanout = Some(2)), FiberPolicy(maxSpawnFanout = Some(4)), "maxSpawnFanout")
  }

  // ── acceptedCallers (set shrinks) ────────────────────────────────────────────────────────────────

  test("acceptedCallers: None→Some OK; subset OK; superset rejected") {
    ok(FiberPolicy(), FiberPolicy(acceptedCallers = Some(Set(a)))) and
    ok(FiberPolicy(acceptedCallers = Some(Set(a, b))), FiberPolicy(acceptedCallers = Some(Set(a)))) and
    rejected(
      FiberPolicy(acceptedCallers = Some(Set(a))),
      FiberPolicy(acceptedCallers = Some(Set(a, b))),
      "acceptedCallers"
    )
  }

  // ── sealedStates (set GROWS — opposite direction) ────────────────────────────────────────────────

  test("sealedStates: the sealed set may only GROW; shrinking is rejected") {
    ok(
      FiberPolicy(sealedStates = Some(Set(StateId("a")))),
      FiberPolicy(sealedStates = Some(Set(StateId("a"), StateId("b"))))
    ) and
    rejected(
      FiberPolicy(sealedStates = Some(Set(StateId("a"), StateId("b")))),
      FiberPolicy(sealedStates = Some(Set(StateId("a")))),
      "sealedStates"
    ) and
    rejected(
      FiberPolicy(sealedStates = Some(Set(StateId("a")))),
      FiberPolicy(),
      "sealedStates"
    )
  }

  // ── transferPolicy (recipient allowlists shrink) ─────────────────────────────────────────────────

  test("transferPolicy: recipient allowlists may only shrink") {
    ok(
      FiberPolicy(transferPolicy = Some(TransferPolicy(allowedRecipientFibers = Some(Set(a, b))))),
      FiberPolicy(transferPolicy = Some(TransferPolicy(allowedRecipientFibers = Some(Set(a)))))
    ) and
    rejected(
      FiberPolicy(transferPolicy = Some(TransferPolicy(allowedRecipientFibers = Some(Set(a))))),
      FiberPolicy(transferPolicy = Some(TransferPolicy(allowedRecipientFibers = Some(Set(a, b))))),
      "transferPolicy.allowedRecipientFibers"
    )
  }

  // ── dependencyPolicy (mode rank up; allowlist shrinks) ───────────────────────────────────────────

  test("dependencyPolicy: Open→Allowlist→Frozen OK; reverse rejected; Allowlist shrinks") {
    ok(
      FiberPolicy(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Open))),
      FiberPolicy(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Frozen)))
    ) and
    rejected(
      FiberPolicy(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Frozen))),
      FiberPolicy(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist))),
      "dependencyPolicy.mode"
    ) and
    ok(
      FiberPolicy(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist, Some(Set(a, b))))),
      FiberPolicy(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist, Some(Set(a)))))
    ) and
    rejected(
      FiberPolicy(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist, Some(Set(a))))),
      FiberPolicy(dependencyPolicy = Some(DependencyPolicy(DependencyMode.Allowlist, Some(Set(a, b))))),
      "dependencyPolicy.allowed"
    )
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
}
