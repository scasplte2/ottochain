package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry.SemVer

import io.circe.parser._
import io.circe.syntax._
import weaver.SimpleIOSuite

/**
 * Canonical-determinism for the REQUIRED, NAMED FiberPolicy ADT. The internal-determinism rule the verified
 * re-bind (`computeDigest === logicHash`) relies on: an ABSENT policy key, an explicit `Unconstrained`, and an
 * all-empty `Constrained` must ALL encode to the SAME canonical bytes (the field default = `Unconstrained`, the
 * smart constructor that collapses an empty `Constrained` to `Unconstrained`, and `dropNulls` together guarantee
 * it), so two definitions that mean the same thing hash the same regardless of which client wrote them. A SET
 * dial must hash-distinct from `Unconstrained` and round-trip.
 */
object FiberPolicyHashStabilitySuite extends SimpleIOSuite {

  private val baseDef = StateMachineDefinition(
    states = Map(StateId("init") -> State(StateId("init"), isFinal = false)),
    initialState = StateId("init"),
    transitions = List.empty
  ) // policy defaults to FiberPolicy.Unconstrained

  test("Unconstrained (default) and an all-empty Constrained produce IDENTICAL digests (canonical collapse)") {
    for {
      unconstrained <- baseDef.computeDigest
      // FiberPolicy.constrained(Constrained()) collapses to Unconstrained, so this is the same canonical form
      empty <- baseDef.copy(policy = FiberPolicy.constrained(FiberPolicy.Constrained())).computeDigest
    } yield expect(unconstrained === empty) and
    expect(FiberPolicy.constrained(FiberPolicy.Constrained()) == FiberPolicy.Unconstrained)
  }

  test("a legacy JSON (no policy key) decodes to Unconstrained and hashes identically to the typed default") {
    val legacyJson = """{
      "states": { "init": { "id": "init", "isFinal": false } },
      "initialState": "init",
      "transitions": []
    }"""
    for {
      decoded    <- IO.fromEither(decode[StateMachineDefinition](legacyJson))
      legacyHash <- decoded.computeDigest
      typedHash  <- baseDef.computeDigest
    } yield expect(decoded.policy == FiberPolicy.Unconstrained) and expect(legacyHash === typedHash)
  }

  test("a wire bare empty object (policy:{}) collapses to Unconstrained and hashes identically") {
    val emptyObjJson = """{
      "states": { "init": { "id": "init", "isFinal": false } },
      "initialState": "init",
      "transitions": [],
      "policy": {}
    }"""
    for {
      decoded   <- IO.fromEither(decode[StateMachineDefinition](emptyObjJson))
      emptyHash <- decoded.computeDigest
      typedHash <- baseDef.computeDigest
    } yield expect(decoded.policy == FiberPolicy.Unconstrained) and expect(emptyHash === typedHash)
  }

  test("a wire null policy (policy:null) decodes to Unconstrained and hashes identically") {
    val nullPolicyJson = """{
      "states": { "init": { "id": "init", "isFinal": false } },
      "initialState": "init",
      "transitions": [],
      "policy": null
    }"""
    for {
      decoded   <- IO.fromEither(decode[StateMachineDefinition](nullPolicyJson))
      uHash     <- decoded.computeDigest
      typedHash <- baseDef.computeDigest
    } yield expect(decoded.policy == FiberPolicy.Unconstrained) and expect(uHash === typedHash)
  }

  test("a non-empty Constrained produces a DIFFERENT digest (the constitution is hash-pinned) and round-trips") {
    val withPolicy = baseDef.copy(policy = FiberPolicy.constrained(selfReproducing = Some(true)))
    for {
      baseHash   <- baseDef.computeDigest
      policyHash <- withPolicy.computeDigest
      reDecoded  <- IO.fromEither(decode[StateMachineDefinition](withPolicy.asJson.noSpaces))
    } yield expect(baseHash =!= policyHash) and
    expect(reDecoded.policy.dials.flatMap(_.selfReproducing).contains(true))
  }

  test("the version-compat family round-trips through circe (ADT codecs) and stays hash-distinct") {
    val vcPolicy = FiberPolicy.constrained(
      upgradePolicy = Some(UpgradePolicy.Governed(MigrationAuthority.Role(new java.util.UUID(0L, 7L), "admins"))),
      version = Some(SemVer(1, 2, 3)),
      compatibleWith = Some(VersionRange(min = Some(SemVer(2, 0, 0)), max = Some(SemVer(3, 0, 0)))),
      interfaces = Some(Set("ITransfer", "IPause"))
    )
    val withVc = baseDef.copy(policy = vcPolicy)
    for {
      baseHash  <- baseDef.computeDigest
      vcHash    <- withVc.computeDigest
      reDecoded <- IO.fromEither(decode[StateMachineDefinition](withVc.asJson.noSpaces))
    } yield expect(baseHash =!= vcHash) and
    expect(reDecoded.policy.dials.flatMap(_.version).contains(SemVer(1, 2, 3))) and
    expect(reDecoded.policy.dials.flatMap(_.upgradePolicy) == vcPolicy.dials.flatMap(_.upgradePolicy)) and
    expect(reDecoded.policy.dials.flatMap(_.interfaces).contains(Set("ITransfer", "IPause"))) and
    expect(reDecoded.policy.dials.flatMap(_.compatibleWith) == vcPolicy.dials.flatMap(_.compatibleWith))
  }

  test("a bare-tag upgradePolicy (immutable/appendOnly/arbitrary) round-trips as a string") {
    val immutable = baseDef.copy(policy = FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.Immutable)))
    for {
      reDecoded <- IO.fromEither(decode[StateMachineDefinition](immutable.asJson.noSpaces))
    } yield expect(reDecoded.policy.dials.flatMap(_.upgradePolicy).contains(UpgradePolicy.Immutable))
  }

  test("the canonical unconstrained form OMITS the policy key (Unconstrained ⇒ null ⇒ dropNulls strips it)") {
    // The named variant lives in CODE, not the bytes: `Unconstrained` encodes to JSON null, which the
    // canonical/signing path's dropNulls removes — so an unconstrained definition carries NO policy key on
    // the wire. Absence == Unconstrained, exactly what every pre-policy / e2e / SDK payload already produces,
    // so client↔chain byte parity is free (no sentinel to coordinate). The policy field is therefore either
    // absent or null in the raw encoding; either way it is gone after dropNulls.
    val policyField = baseDef.asJson.hcursor.downField("policy").focus
    IO.pure(expect(policyField.forall(_.isNull)))
  }

  test("Immutable encodes to the bare string \"Immutable\" and round-trips") {
    val immutableDef = baseDef.copy(policy = FiberPolicy.Immutable)
    val policyField = immutableDef.asJson.hcursor.downField("policy").focus
    for {
      reDecoded <- IO.fromEither(decode[StateMachineDefinition](immutableDef.asJson.noSpaces))
    } yield expect(policyField.contains(io.circe.Json.fromString("Immutable"))) and
    expect(reDecoded.policy == FiberPolicy.Immutable)
  }

  test("a Constrained setting ONLY upgradePolicy=Immutable collapses to Immutable (one canonical form)") {
    val collapsed = FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.Immutable))
    val notImmutable =
      FiberPolicy.constrained(upgradePolicy = Some(UpgradePolicy.Immutable), maxGenerations = Some(3))
    IO.pure(
      expect(collapsed == FiberPolicy.Immutable) and
      expect(notImmutable.isInstanceOf[FiberPolicy.Constrained])
    )
  }

  test("a wire dials object {upgradePolicy:immutable} also decodes to Immutable (collapse on decode)") {
    val dialsJson = """{
      "states": { "init": { "id": "init", "isFinal": false } },
      "initialState": "init",
      "transitions": [],
      "policy": { "upgradePolicy": "immutable" }
    }"""
    for {
      fromDials <- IO.fromEither(decode[StateMachineDefinition](dialsJson))
      fromString <- IO.fromEither(
        decode[StateMachineDefinition](baseDef.copy(policy = FiberPolicy.Immutable).asJson.noSpaces)
      )
      dialsHash  <- fromDials.computeDigest
      stringHash <- fromString.computeDigest
    } yield expect(fromDials.policy == FiberPolicy.Immutable) and expect(dialsHash === stringHash)
  }
}
