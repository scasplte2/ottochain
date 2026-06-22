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
 * Hash-stability + empty-policy normalization (B3). The `policy = None` default must be `dropNulls`-stable so
 * every pre-policy definition's `computeDigest` is byte-identical, AND an all-default policy must hash exactly
 * like an absent one — the internal-determinism rule the verified re-bind (`computeDigest === logicHash`)
 * relies on, so two definitions that mean the same thing hash the same regardless of which client wrote them.
 */
object FiberPolicyHashStabilitySuite extends SimpleIOSuite {

  private val baseDef = StateMachineDefinition(
    states = Map(StateId("init") -> State(StateId("init"), isFinal = false)),
    initialState = StateId("init"),
    transitions = List.empty
  )

  test("policy=None and policy=Some(empty) produce IDENTICAL digests (B3 normalization)") {
    for {
      none  <- baseDef.computeDigest
      empty <- baseDef.copy(policy = Some(FiberPolicy.empty)).computeDigest
    } yield expect(none === empty)
  }

  test("a legacy JSON (no policy key) decodes to policy=None and hashes identically to the typed None") {
    val legacyJson = """{
      "states": { "init": { "id": "init", "isFinal": false } },
      "initialState": "init",
      "transitions": []
    }"""
    for {
      decoded    <- IO.fromEither(decode[StateMachineDefinition](legacyJson))
      legacyHash <- decoded.computeDigest
      typedHash  <- baseDef.computeDigest
    } yield expect(decoded.policy.isEmpty) and expect(legacyHash === typedHash)
  }

  test("a wire policy:{} (all-default object) decodes to policy=None") {
    val emptyPolicyJson = """{
      "states": { "init": { "id": "init", "isFinal": false } },
      "initialState": "init",
      "transitions": [],
      "policy": {}
    }"""
    for {
      decoded   <- IO.fromEither(decode[StateMachineDefinition](emptyPolicyJson))
      emptyHash <- decoded.computeDigest
      typedHash <- baseDef.computeDigest
    } yield expect(decoded.policy.isEmpty) and expect(emptyHash === typedHash)
  }

  test("a NON-empty policy produces a DIFFERENT digest (the constitution is hash-pinned) and round-trips") {
    val withPolicy = baseDef.copy(policy = Some(FiberPolicy(selfReproducing = Some(true))))
    for {
      baseHash   <- baseDef.computeDigest
      policyHash <- withPolicy.computeDigest
      reDecoded  <- IO.fromEither(decode[StateMachineDefinition](withPolicy.asJson.noSpaces))
    } yield expect(baseHash =!= policyHash) and
    expect(reDecoded.policy.flatMap(_.selfReproducing).contains(true))
  }

  test("the version-compat family round-trips through circe (ADT codecs) and stays hash-distinct") {
    val vcPolicy = FiberPolicy(
      upgradePolicy = Some(UpgradePolicy.Governed(MigrationAuthority.Role(new java.util.UUID(0L, 7L), "admins"))),
      version = Some(SemVer(1, 2, 3)),
      compatibleWith = Some(VersionRange(min = Some(SemVer(2, 0, 0)), max = Some(SemVer(3, 0, 0)))),
      interfaces = Some(Set("ITransfer", "IPause"))
    )
    val withVc = baseDef.copy(policy = Some(vcPolicy))
    for {
      baseHash  <- baseDef.computeDigest
      vcHash    <- withVc.computeDigest
      reDecoded <- IO.fromEither(decode[StateMachineDefinition](withVc.asJson.noSpaces))
    } yield expect(baseHash =!= vcHash) and
    expect(reDecoded.policy.flatMap(_.version).contains(SemVer(1, 2, 3))) and
    expect(reDecoded.policy.flatMap(_.upgradePolicy).contains(vcPolicy.upgradePolicy.get)) and
    expect(reDecoded.policy.flatMap(_.interfaces).contains(Set("ITransfer", "IPause"))) and
    expect(reDecoded.policy.flatMap(_.compatibleWith) == vcPolicy.compatibleWith)
  }

  test("a bare-tag upgradePolicy (immutable/appendOnly/arbitrary) round-trips as a string") {
    val immutable = baseDef.copy(policy = Some(FiberPolicy(upgradePolicy = Some(UpgradePolicy.Immutable))))
    for {
      reDecoded <- IO.fromEither(decode[StateMachineDefinition](immutable.asJson.noSpaces))
    } yield expect(reDecoded.policy.flatMap(_.upgradePolicy).contains(UpgradePolicy.Immutable))
  }

  test("encoder normalizes Some(empty) away: the policy field is absent-or-null ⇒ dropNulls-stable") {
    val emptied = baseDef.copy(policy = Some(FiberPolicy.empty))
    val json = emptied.asJson
    // Under useDefaults a None Option may serialize as an explicit `null`; what matters for hash-stability is
    // that it is null (which the serialize-time `dropNulls` removes), NOT a non-null `{}` object. So: the
    // policy field is either absent or JSON-null — never a present object.
    val policyField = json.hcursor.downField("policy").focus
    IO.pure(expect(policyField.forall(_.isNull)))
  }
}
