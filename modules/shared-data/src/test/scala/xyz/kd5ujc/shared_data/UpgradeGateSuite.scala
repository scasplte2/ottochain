package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.syntax.show._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.metagraph_sdk.json_logic.{BoolValue, JsonLogicValue, MapValue}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.UpgradeGate

import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.FunSuite

/**
 * Pure unit tests for the version & compatibility-family migration gate. Drives [[UpgradeGate.check]] directly
 * with hand-built records/registry so each branch (Immutable / Governed.Signers / Governed.Role / AppendOnly /
 * compatBridge) is exercised in isolation, asserting fail-closed on every malformed/unauthorized input.
 */
object UpgradeGateSuite extends FunSuite {

  private val ord: SnapshotOrdinal = SnapshotOrdinal(NonNegLong.unsafeFrom(1L))
  private val pkg: RegistryName = RegistryName.unsafe("x.package")

  private def addr(s: String): Address =
    refineV[DAGAddressRefined].apply[String](s) match {
      case Right(v) => Address(v)
      case Left(e)  => sys.error(s"bad test address: $e")
    }

  // Two well-formed (checksum-valid) DAG addresses for authority tests.
  private val alice = addr("DAG2BAUcXKujRhzk4XZ6RDYL2ifXWMgfw1v7YxZu")
  private val bob = addr("DAG5VpYPJCqdv4K3VnpNrpTABvC8RjqrfZN8rUvE")

  private def uuid(n: Int): UUID = new UUID(0L, n.toLong)

  private val baseDef: StateMachineDefinition =
    StateMachineDefinition(
      states = Map(StateId("init") -> State(StateId("init"), isFinal = false)),
      initialState = StateId("init"),
      transitions = List.empty
    )

  private def binding(v: SemVer): SchemaBinding =
    SchemaBinding(pkg, v, Hash("schemaHash"), Hash("logicHash"))

  private def smRecord(
    policy:  Option[FiberPolicy],
    state:   JsonLogicValue = MapValue(Map.empty),
    binding: Option[SchemaBinding] = None,
    id:      UUID = uuid(1)
  ): Records.StateMachineFiberRecord =
    Records.StateMachineFiberRecord(
      fiberId = id,
      creationOrdinal = ord,
      previousUpdateOrdinal = ord,
      latestUpdateOrdinal = ord,
      definition = baseDef.copy(policy = policy),
      currentState = StateId("init"),
      stateData = state,
      stateDataHash = Hash("h"),
      sequenceNumber = FiberOrdinal.MinValue,
      owners = Set.empty,
      status = FiberStatus.Active,
      schemaBinding = binding
    )

  private def stateOf(recs: Records.StateMachineFiberRecord*): CalculatedState =
    CalculatedState(SortedMap.from(recs.map(r => r.fiberId -> r)), SortedMap.empty)

  private def isPolicyViolation(o: Option[FailureReason], dial: String): Boolean = o match {
    case Some(FailureReason.PolicyViolation(d, _)) => d == dial
    case _                                         => false
  }

  private def msg(fields: FieldShape*): MessageShape = MessageShape("X.State", fields.toList)

  // ── Arbitrary / absent ───────────────────────────────────────────────────────────────────────────

  test("absent upgradePolicy (≡ Arbitrary) admits any migration") {
    val old = smRecord(None)
    expect(UpgradeGate.check(old, baseDef, binding(SemVer(2, 0, 0)), stateOf(old), Set(alice)).isEmpty)
  }

  test("explicit Arbitrary admits any migration") {
    val old = smRecord(Some(FiberPolicy(upgradePolicy = Some(UpgradePolicy.Arbitrary))))
    expect(UpgradeGate.check(old, baseDef, binding(SemVer(2, 0, 0)), stateOf(old), Set(alice)).isEmpty)
  }

  // ── Immutable ────────────────────────────────────────────────────────────────────────────────────

  test("Immutable denies ALL migrations (even an identity re-bind with verified signers)") {
    val pol = Some(FiberPolicy(upgradePolicy = Some(UpgradePolicy.Immutable)))
    val old = smRecord(pol)
    val r = UpgradeGate.check(old, baseDef.copy(policy = pol), binding(SemVer(2, 0, 0)), stateOf(old), Set(alice))
    expect(isPolicyViolation(r, "upgradePolicy"))
  }

  test("Immutable cannot be escaped by a migration that drops the dial (tighten-only also denies)") {
    val old = smRecord(Some(FiberPolicy(upgradePolicy = Some(UpgradePolicy.Immutable))))
    // newDefinition tries to loosen to Arbitrary; gateByUpgradePolicy denies first regardless.
    val newDef = baseDef.copy(policy = None)
    expect(UpgradeGate.check(old, newDef, binding(SemVer(2, 0, 0)), stateOf(old), Set(alice)).isDefined)
  }

  // ── Governed.Signers ─────────────────────────────────────────────────────────────────────────────

  test("Governed.Signers admits when a verified signer is in the authority set; denies otherwise") {
    val pol = Some(
      FiberPolicy(upgradePolicy = Some(UpgradePolicy.Governed(MigrationAuthority.Signers(Set(alice)))))
    )
    val old = smRecord(pol)
    val ok = UpgradeGate.check(old, baseDef.copy(policy = pol), binding(SemVer(2, 0, 0)), stateOf(old), Set(alice))
    val no = UpgradeGate.check(old, baseDef.copy(policy = pol), binding(SemVer(2, 0, 0)), stateOf(old), Set(bob))
    expect(ok.isEmpty) and expect(isPolicyViolation(no, "upgradePolicy"))
  }

  test("Governed.Signers with an empty authority set denies everyone (soft-Immutable)") {
    val pol = Some(FiberPolicy(upgradePolicy = Some(UpgradePolicy.Governed(MigrationAuthority.Signers(Set.empty)))))
    val old = smRecord(pol)
    expect(
      isPolicyViolation(
        UpgradeGate.check(old, baseDef.copy(policy = pol), binding(SemVer(2, 0, 0)), stateOf(old), Set(alice)),
        "upgradePolicy"
      )
    )
  }

  // ── Governed.Role (flat role map, fail-closed) ───────────────────────────────────────────────────

  test("Governed.Role admits when a verified signer keys the role map; fail-closed on missing fiber/map") {
    val registryId = uuid(99)
    val pol = Some(
      FiberPolicy(upgradePolicy = Some(UpgradePolicy.Governed(MigrationAuthority.Role(registryId, "admins"))))
    )
    val old = smRecord(pol, id = uuid(1))
    // registry fiber whose stateData.admins = { <alice.show>: true } (the gate keys on the Base58 `show`)
    val roleMap: JsonLogicValue =
      MapValue(Map("admins" -> MapValue(Map(alice.show -> BoolValue(true)))))
    val registryFiber = smRecord(None, state = roleMap, id = registryId)

    val present = stateOf(old, registryFiber)
    val ok = UpgradeGate.check(old, baseDef.copy(policy = pol), binding(SemVer(2, 0, 0)), present, Set(alice))
    val notRole = UpgradeGate.check(old, baseDef.copy(policy = pol), binding(SemVer(2, 0, 0)), present, Set(bob))
    // registry fiber absent ⇒ fail-closed deny
    val missing = UpgradeGate.check(old, baseDef.copy(policy = pol), binding(SemVer(2, 0, 0)), stateOf(old), Set(alice))

    expect(ok.isEmpty) and
    expect(isPolicyViolation(notRole, "upgradePolicy")) and
    expect(isPolicyViolation(missing, "upgradePolicy"))
  }

  test("Governed.Role denies when the role field is missing or not a map (fail-closed)") {
    val registryId = uuid(99)
    val pol = Some(
      FiberPolicy(upgradePolicy = Some(UpgradePolicy.Governed(MigrationAuthority.Role(registryId, "admins"))))
    )
    val old = smRecord(pol, id = uuid(1))
    val noRoleField = smRecord(None, state = MapValue(Map("other" -> BoolValue(true))), id = registryId)
    val nonMapState = smRecord(None, state = BoolValue(true), id = registryId)
    val r1 = UpgradeGate.check(
      old,
      baseDef.copy(policy = pol),
      binding(SemVer(2, 0, 0)),
      stateOf(old, noRoleField),
      Set(alice)
    )
    val r2 = UpgradeGate.check(
      old,
      baseDef.copy(policy = pol),
      binding(SemVer(2, 0, 0)),
      stateOf(old, nonMapState),
      Set(alice)
    )
    expect(isPolicyViolation(r1, "upgradePolicy")) and expect(isPolicyViolation(r2, "upgradePolicy"))
  }

  // ── AppendOnly (additive schema delta) ───────────────────────────────────────────────────────────

  test("AppendOnly admits an additive field (new number) and denies a dropped/retyped/non-strict delta") {
    val v1 = SemVer(1, 0, 0)
    val v2 = SemVer(2, 0, 0)
    val pol = Some(FiberPolicy(upgradePolicy = Some(UpgradePolicy.AppendOnly)))
    val old = smRecord(pol, binding = Some(binding(v1)))

    val oldShape = MachineShape(
      msg(FieldShape("balance", 1, "int64", repeated = false, optional = false)),
      SortedMap.empty
    )
    // additive: keeps field #1 identical, adds field #2.
    val additiveShape = MachineShape(
      msg(
        FieldShape("balance", 1, "int64", repeated = false, optional = false),
        FieldShape("note", 2, "string", repeated = false, optional = false)
      ),
      SortedMap.empty
    )
    // non-additive: field #1's type changed.
    val retypedShape = MachineShape(
      msg(FieldShape("balance", 1, "string", repeated = false, optional = false)),
      SortedMap.empty
    )
    // non-additive: field #1 dropped.
    val droppedShape = MachineShape(msg(), SortedMap.empty)

    // Build a registry that has BOTH v1 (old) and the candidate v2 strict-bound.
    def registryWith(newShape: MachineShape): CalculatedState = {
      def rv(v: SemVer, shape: MachineShape) = RegisteredVersion(
        v,
        Hash("schemaHash"),
        Hash("logicHash"),
        RegistryShape.Machine(shape),
        RegistryStatus.Active,
        ord,
        strict = true
      )
      val lineage = VersionLineage(SortedMap(v1 -> rv(v1, oldShape), v2 -> rv(v2, newShape)))
      val entry = RegistryEntry(pkg, Set(alice), RegistryTarget.SchemaPackage(lineage))
      CalculatedState(SortedMap(old.fiberId -> old), SortedMap.empty, registry = SortedMap(pkg -> entry))
    }

    val okAdd = UpgradeGate.check(old, baseDef.copy(policy = pol), binding(v2), registryWith(additiveShape), Set(alice))
    val noRetype =
      UpgradeGate.check(old, baseDef.copy(policy = pol), binding(v2), registryWith(retypedShape), Set(alice))
    val noDrop = UpgradeGate.check(old, baseDef.copy(policy = pol), binding(v2), registryWith(droppedShape), Set(alice))
    // both versions must be strict-bound; with an unbound OLD fiber it fails closed
    val unboundOld = smRecord(pol, binding = None)
    val noStrict =
      UpgradeGate.check(unboundOld, baseDef.copy(policy = pol), binding(v2), registryWith(additiveShape), Set(alice))

    expect(okAdd.isEmpty) and
    expect(isPolicyViolation(noRetype, "upgradePolicy")) and
    expect(isPolicyViolation(noDrop, "upgradePolicy")) and
    expect(isPolicyViolation(noStrict, "upgradePolicy"))
  }

  test("AppendOnly denies when a command message is removed") {
    val v1 = SemVer(1, 0, 0)
    val v2 = SemVer(2, 0, 0)
    val pol = Some(FiberPolicy(upgradePolicy = Some(UpgradePolicy.AppendOnly)))
    val old = smRecord(pol, binding = Some(binding(v1)))

    val stateMsg = msg(FieldShape("x", 1, "int64", repeated = false, optional = false))
    val oldShape =
      MachineShape(stateMsg, SortedMap("go" -> msg(FieldShape("a", 1, "int64", repeated = false, optional = false))))
    val newShape = MachineShape(stateMsg, SortedMap.empty) // command "go" removed

    def rv(v: SemVer, shape: MachineShape) = RegisteredVersion(
      v,
      Hash("schemaHash"),
      Hash("logicHash"),
      RegistryShape.Machine(shape),
      RegistryStatus.Active,
      ord,
      strict = true
    )
    val lineage = VersionLineage(SortedMap(v1 -> rv(v1, oldShape), v2 -> rv(v2, newShape)))
    val entry = RegistryEntry(pkg, Set(alice), RegistryTarget.SchemaPackage(lineage))
    val st = CalculatedState(SortedMap(old.fiberId -> old), SortedMap.empty, registry = SortedMap(pkg -> entry))

    expect(
      isPolicyViolation(
        UpgradeGate.check(old, baseDef.copy(policy = pol), binding(v2), st, Set(alice)),
        "upgradePolicy"
      )
    )
  }

  // ── compatBridge ─────────────────────────────────────────────────────────────────────────────────

  test("compatBridge: a target inside the OLD declared window admits; outside denies") {
    val pol = Some(
      FiberPolicy(compatibleWith = Some(VersionRange(min = Some(SemVer(2, 0, 0)), max = Some(SemVer(3, 0, 0)))))
    )
    val old = smRecord(pol)
    val inside = UpgradeGate.check(old, baseDef.copy(policy = pol), binding(SemVer(2, 5, 0)), stateOf(old), Set(alice))
    val below = UpgradeGate.check(old, baseDef.copy(policy = pol), binding(SemVer(1, 9, 0)), stateOf(old), Set(alice))
    val atMax = UpgradeGate.check(
      old,
      baseDef.copy(policy = pol),
      binding(SemVer(3, 0, 0)),
      stateOf(old),
      Set(alice)
    ) // exclusive
    expect(inside.isEmpty) and
    expect(isPolicyViolation(below, "compatibleWith")) and
    expect(isPolicyViolation(atMax, "compatibleWith"))
  }

  // ── tighten-only (also enforced inside the gate) ─────────────────────────────────────────────────

  test("gate rejects a loosening migration via the tighten lattice") {
    val oldPol = Some(FiberPolicy(allowedEffects = Some(Set(EffectKind.Emit))))
    val old = smRecord(oldPol)
    // new drops allowedEffects ⇒ loosening
    val r = UpgradeGate.check(old, baseDef.copy(policy = None), binding(SemVer(2, 0, 0)), stateOf(old), Set(alice))
    expect(isPolicyViolation(r, "tighten"))
  }
}
