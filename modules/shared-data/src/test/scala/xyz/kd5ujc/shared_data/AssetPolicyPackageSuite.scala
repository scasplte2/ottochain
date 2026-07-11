package xyz.kd5ujc.shared_data

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.asset._
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

import io.circe.parser.decode
import io.circe.syntax._
import weaver.SimpleIOSuite

/**
 * Phase 1 of the asset model (docs/proposals/asset-model.md §5): `AssetPolicy` is a versioned registry
 * package. This suite proves
 *   (a) the new [[RegistryTarget.AssetPolicyPackage]] / [[RegistryShape.AssetPolicy]] variants flow through
 *       the EXISTING [[VersionLineage]] machinery (publish / resolve / setStatus) unchanged, and
 *   (b) every new value type round-trips through circe, including [[MorphismKind]] as a `SortedMap` KEY with
 *       deterministic sorted key order (the signing-canonical-safe wire form).
 */
object AssetPolicyPackageSuite extends SimpleIOSuite {

  private val ord = SnapshotOrdinal.MinValue

  private val stateShape: MessageShape =
    MessageShape(
      "Asset.State",
      List(
        FieldShape("amount", 1, "int64", repeated = false, optional = false),
        FieldShape("holder", 2, "string", repeated = false, optional = false),
        FieldShape("expiresAt", 3, "int64", repeated = false, optional = true)
      )
    )

  private val supply: SupplyPolicy =
    SupplyPolicy(maxSupply = Some(1000000L), mintPolicy = None, burnPolicy = None, decimals = Some(8))

  // Deliberately list the morphism specs OUT of sorted order to prove the SortedMap normalises key order.
  private val morphisms: SortedMap[MorphismKind, MorphismSpec] =
    SortedMap(
      MorphismKind.Transfer -> MorphismSpec(MorphismVisibility.Public, None, None, None),
      MorphismKind.Burn     -> MorphismSpec(MorphismVisibility.Governed, None, None, None),
      MorphismKind.Compose -> MorphismSpec(
        MorphismVisibility.Public,
        // NameTld.Asset is a later phase (§7); the lineage machinery is TLD-agnostic, so the
        // allowlist/policy names use the existing `.package` TLD here.
        allowedPolicies = Some(Set(RegistryName.unsafe("usd.package"))),
        allowedTypes = Some(Set(28, 16)),
        guard = None
      ),
      MorphismKind.Stake -> MorphismSpec(MorphismVisibility.Disabled, None, None, None)
    )

  private val assetShape: RegistryShape =
    RegistryShape.AssetPolicy(
      behavior = TokenBehavior.Fungible,
      supply = supply,
      morphisms = morphisms,
      stateShape = stateShape
    )

  private def rv(
    major:  Int,
    minor:  Int,
    patch:  Int,
    status: RegistryStatus = RegistryStatus.Active
  ): RegisteredVersion =
    RegisteredVersion(
      version = SemVer(major, minor, patch),
      schemaHash = Hash(s"schema-$major.$minor.$patch"),
      logicHash = Hash(s"logic-$major.$minor.$patch"),
      shape = assetShape,
      status = status,
      registeredAt = ord,
      strict = false
    )

  // NameTld.Asset arrives in a later phase (asset-model.md §7); the registry-package machinery
  // (VersionLineage / RegistryEntry) is TLD-agnostic, so Phase 1 carries an AssetPolicyPackage under the
  // existing `.package` TLD. TLD<->target consistency is a combiner concern (out of Phase-1 scope).
  private val policyEntry: RegistryEntry =
    RegistryEntry(
      name = RegistryName.unsafe("gold.acme.package"),
      owner = Set.empty[Address],
      target = RegistryTarget.AssetPolicyPackage(VersionLineage.of(rv(1, 0, 0)))
    )

  // ── (a) the new variants flow through the existing lineage machinery unchanged ────────────────

  test("AssetPolicyPackage publishes/resolves/yanks through VersionLineage verbatim") {
    val lineage = VersionLineage.empty
      .publish(rv(1, 0, 0))
      .flatMap(_.publish(rv(1, 1, 0)))
      .flatMap(_.publish(rv(2, 0, 0)))

    val resolvedLatest = lineage.flatMap(_.resolve(VersionReq.Latest)).map(_.version)
    val resolvedCaret = lineage.flatMap(_.resolve(VersionReq.Caret(SemVer(1, 0, 0)))).map(_.version)
    val dup = lineage.flatMap(_.publish(rv(2, 0, 0)))
    val yankedLatest = lineage
      .flatMap(_.setStatus(SemVer(2, 0, 0), RegistryStatus.Yanked))
      .flatMap(_.resolve(VersionReq.Latest))
      .map(_.version)
    // shape survives the lineage and is the asset variant (not a Machine/Script)
    val shapeKind = lineage.flatMap(_.resolve(VersionReq.Latest)).map(_.shape).map {
      case _: RegistryShape.AssetPolicy => "AssetPolicy"
      case _: RegistryShape.Machine     => "Machine"
      case _: RegistryShape.Script      => "Script"
    }

    IO.pure(
      expect(resolvedLatest == Right(SemVer(2, 0, 0))) and
      expect(resolvedCaret == Right(SemVer(1, 1, 0))) and
      expect(dup == Left(RegistryError.VersionExists(SemVer(2, 0, 0)))) and
      expect(yankedLatest == Right(SemVer(1, 1, 0))) and
      expect(shapeKind == Right("AssetPolicy"))
    )
  }

  test("AssetPolicyPackage RegistryEntry round-trips through JSON") {
    val json = policyEntry.asJson.noSpaces
    IO.pure(expect(decode[RegistryEntry](json) == Right(policyEntry)))
  }

  test("CalculatedState carrying an AssetPolicyPackage entry round-trips") {
    val cs = CalculatedState.genesis.copy(registry = SortedMap(policyEntry.name -> policyEntry))
    val json = cs.asJson.noSpaces
    IO.pure(expect(decode[CalculatedState](json) == Right(cs)))
  }

  test("OnChain carrying an asset-policy registry commit round-trips") {
    val oc = OnChain.genesis.copy(touchedRegistryCommits = SortedMap(policyEntry.name -> Hash("assethash")))
    val json = oc.asJson.noSpaces
    IO.pure(expect(decode[OnChain](json) == Right(oc)))
  }

  // ── (b) circe round-trips for each new value type ─────────────────────────────────────────────

  test("RegistryShape.AssetPolicy round-trips and decodes as the AssetPolicy variant") {
    val json = assetShape.asJson.noSpaces
    IO.pure(
      expect(decode[RegistryShape](json) == Right(assetShape)) and
      // disjoint-field-name discriminator: must NOT decode an AssetPolicy as Machine/Script
      expect(decode[RegistryShape](json).exists(_.isInstanceOf[RegistryShape.AssetPolicy]))
    )
  }

  test("RegistryTarget.AssetPolicyPackage round-trips") {
    val target: RegistryTarget = RegistryTarget.AssetPolicyPackage(VersionLineage.of(rv(1, 0, 0)))
    val json = target.asJson.noSpaces
    IO.pure(expect(decode[RegistryTarget](json) == Right(target)))
  }

  test("MorphismKind round-trips as a value and as a sorted SortedMap key") {
    // value codec (CirceEnum) — uppercase entry name
    val kindRoundTrips = MorphismKind.values.forall(k => decode[MorphismKind](k.asJson.noSpaces) == Right(k))
    // KeyEncoder/KeyDecoder via a SortedMap[MorphismKind, Int]
    val asMap: SortedMap[MorphismKind, Int] =
      SortedMap(MorphismKind.Stake -> 3, MorphismKind.Burn -> 1, MorphismKind.Transfer -> 2)
    val mapJson = asMap.asJson.noSpaces
    val mapBack = decode[SortedMap[MorphismKind, Int]](mapJson)
    // deterministic SORTED key order on the wire (by uppercase entry name: BURN < STAKE < TRANSFER)
    val keyOrder = io.circe.parser.parse(mapJson).toOption.flatMap(_.asObject).map(_.keys.toList)

    IO.pure(
      expect(kindRoundTrips) and
      expect(mapBack == Right(asMap)) and
      expect(keyOrder == Some(List("BURN", "STAKE", "TRANSFER")))
    )
  }

  test("the morphisms SortedMap serialises with deterministic sorted MorphismKind keys") {
    val morphismsJson = morphisms.asJson.noSpaces
    val keys = io.circe.parser.parse(morphismsJson).toOption.flatMap(_.asObject).map(_.keys.toList)
    IO.pure(
      expect(decode[SortedMap[MorphismKind, MorphismSpec]](morphismsJson) == Right(morphisms)) and
      // BURN < COMPOSE < STAKE < TRANSFER (lexicographic on uppercase entry names)
      expect(keys == Some(List("BURN", "COMPOSE", "STAKE", "TRANSFER")))
    )
  }

  test("MorphismVisibility round-trips for every variant") {
    val ok = MorphismVisibility.values.forall(v => decode[MorphismVisibility](v.asJson.noSpaces) == Right(v))
    IO.pure(expect(ok))
  }

  test("MorphismSpec round-trips (required visibility, Option refinements)") {
    val full = MorphismSpec(
      MorphismVisibility.Governed,
      allowedPolicies = Some(Set(RegistryName.unsafe("a.package"), RegistryName.unsafe("b.package"))),
      allowedTypes = Some(Set(1, 28)),
      guard = None
    )
    val minimal = MorphismSpec(MorphismVisibility.Public, None, None, None)
    IO.pure(
      expect(decode[MorphismSpec](full.asJson.noSpaces) == Right(full)) and
      expect(decode[MorphismSpec](minimal.asJson.noSpaces) == Right(minimal))
    )
  }

  test("SupplyPolicy round-trips (all Option fields)") {
    val full = SupplyPolicy(Some(42L), None, None, Some(6))
    val empty = SupplyPolicy(None, None, None, None)
    IO.pure(
      expect(decode[SupplyPolicy](full.asJson.noSpaces) == Right(full)) and
      expect(decode[SupplyPolicy](empty.asJson.noSpaces) == Right(empty))
    )
  }
}
