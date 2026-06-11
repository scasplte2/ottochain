package xyz.kd5ujc.shared_data

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic.{IntValue, JsonLogicValue, MapValue, StrValue}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.{CreateStateMachine, PublishVersion, RegisterAlias, SetVersionStatus, UpgradeFiber}
import xyz.kd5ujc.schema.fiber.{FiberLogEntry, FiberOrdinal, State, StateId, StateMachineDefinition}
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records}
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, Validator}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * Integration tests for the registry combine + validate path (closes the #23/#36 stub).
 * Exercises both the authoritative combiner (create/append/ownership/monotonic/status, abort-on-reject)
 * and the now-real RegistryValidator (rejects non-owner publish and non-monotonic versions).
 */
object RegistryCombinerSuite extends SimpleIOSuite {

  // A deterministic business rejection no longer raises out of the combiner — it records a RejectionReceipt
  // and leaves state unmutated (so one bad update can't abort the whole batch). Negative tests assert that a
  // RejectionReceipt was emitted instead of catching an exception.
  private def wasRejected(state: DataState[OnChain, CalculatedState]): Boolean =
    state.onChain.latestLogs.values.flatten.exists {
      case _: FiberLogEntry.RejectionReceipt => true
      case _                                 => false
    }

  private def b64(s: String): String = Base64.getEncoder.encodeToString(s.getBytes(StandardCharsets.UTF_8))

  // Package names now carry the `.package` TLD (Option B): a name is `<labels>.<tld>`.
  private def pkg(n: String): RegistryName = RegistryName.unsafe(s"$n.package")

  private val shape: SchemaShape =
    SchemaShape(
      stateMessage = MessageShape("App.State", List(FieldShape("balance", 1, "int64"))),
      commands = SortedMap("start" -> MessageShape("App.Start", List(FieldShape("amount", 1, "int64"))))
    )

  // The registered logic. Verified binding admits a fiber only if its definition hashes to the registered
  // logicHash, so the publish helper and the matching-fiber test share `minimalDef`.
  private val minimalDef: StateMachineDefinition = {
    val s = StateId("initial")
    StateMachineDefinition(states = Map(s -> State(s, isFinal = false)), initialState = s, transitions = Nil)
  }

  // A DIFFERENT definition (distinct initialState) — hashes to a different logicHash than `minimalDef`.
  private val otherDef: StateMachineDefinition = {
    val s = StateId("different")
    StateMachineDefinition(states = Map(s -> State(s, isFinal = false)), initialState = s, transitions = Nil)
  }

  private val emptyData: JsonLogicValue = MapValue(Map.empty[String, JsonLogicValue])
  private val fiberA = UUID.fromString("11111111-1111-4111-8111-111111111111")

  private def publish(name: String, v: SemVer): PublishVersion =
    PublishVersion(
      name = pkg(name),
      version = v,
      schemaB64 = b64(s"schema-$name-${v.render}"),
      schemaShape = shape,
      definition = minimalDef
    )

  // A v2 definition that RETAINS the "initial" state (so an upgrade preserving currentState is valid) but
  // adds a state, giving it a distinct logicHash from minimalDef.
  private val v2Def: StateMachineDefinition = {
    val s0 = StateId("initial")
    val s1 = StateId("active")
    StateMachineDefinition(
      states = Map(s0 -> State(s0, isFinal = false), s1 -> State(s1, isFinal = false)),
      initialState = s0,
      transitions = Nil
    )
  }

  private def publishWith(name: String, v: SemVer, definition: StateMachineDefinition): PublishVersion =
    PublishVersion(pkg(name), v, b64(s"schema-$name-${v.render}"), shape, definition)

  private val genesis = DataState(OnChain.genesis, CalculatedState.genesis)

  private def versionsOf(state: DataState[OnChain, CalculatedState], name: String): Option[Set[SemVer]] =
    state.calculated.registry
      .get(pkg(name))
      .map(_.target)
      .collect { case RegistryTarget.SchemaPackage(l) => l.versions.keySet }

  private def statusOf(state: DataState[OnChain, CalculatedState], name: String, v: SemVer): Option[RegistryStatus] =
    state.calculated.registry
      .get(pkg(name))
      .map(_.target)
      .collect { case RegistryTarget.SchemaPackage(l) => l.versions.get(v).map(_.status) }
      .flatten

  test("publish creates an owned entry; the owner can append a higher version") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0))
      val p2 = publish("escrow", SemVer(1, 1, 0))
      for {
        pr1 <- fixture.registry.generateProofs(p1, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(p1, pr1))
        pr2 <- fixture.registry.generateProofs(p2, Set(Alice))
        s2  <- combiner.insert(s1, Signed(p2, pr2))
      } yield expect(versionsOf(s2, "escrow").contains(Set(SemVer(1, 0, 0), SemVer(1, 1, 0)))) and
      expect(s2.onChain.registryCommits.contains(pkg("escrow")))
    }
  }

  test("publish by a non-owner to an existing entry is rejected (validator invalid + combiner aborts)") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0)) // Alice creates + owns
      val p2 = publish("escrow", SemVer(1, 1, 0)) // Bob (not an owner) tries to publish
      for {
        validator     <- Validator.make[IO]
        pr1           <- fixture.registry.generateProofs(p1, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(p1, pr1))
        pr2           <- fixture.registry.generateProofs(p2, Set(Bob))
        valid         <- validator.validateSignedUpdate(s1, Signed(p2, pr2))
        combineFailed <- combiner.insert(s1, Signed(p2, pr2)).map(wasRejected)
      } yield expect(valid.isInvalid) and expect(combineFailed)
    }
  }

  test("non-monotonic publish is rejected (validator invalid + combiner aborts)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0))
      val pLow = publish("escrow", SemVer(0, 9, 0))
      for {
        validator     <- Validator.make[IO]
        pr1           <- fixture.registry.generateProofs(p1, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(p1, pr1))
        prLow         <- fixture.registry.generateProofs(pLow, Set(Alice))
        valid         <- validator.validateSignedUpdate(s1, Signed(pLow, prLow))
        combineFailed <- combiner.insert(s1, Signed(pLow, prLow)).map(wasRejected)
      } yield expect(valid.isInvalid) and expect(combineFailed)
    }
  }

  test("owner can deprecate then yank; an illegal Yanked->Active transition aborts") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val name = "escrow"
      val v = SemVer(1, 0, 0)
      val p1 = publish(name, v)
      val deprecate = SetVersionStatus(pkg(name), v, RegistryStatus.Deprecated)
      val yank = SetVersionStatus(pkg(name), v, RegistryStatus.Yanked)
      val unyank = SetVersionStatus(pkg(name), v, RegistryStatus.Active)
      for {
        pr1          <- fixture.registry.generateProofs(p1, Set(Alice))
        s1           <- combiner.insert(genesis, Signed(p1, pr1))
        prD          <- fixture.registry.generateProofs(deprecate, Set(Alice))
        s2           <- combiner.insert(s1, Signed(deprecate, prD))
        prY          <- fixture.registry.generateProofs(yank, Set(Alice))
        s3           <- combiner.insert(s2, Signed(yank, prY))
        prU          <- fixture.registry.generateProofs(unyank, Set(Alice))
        unyankFailed <- combiner.insert(s3, Signed(unyank, prU)).map(wasRejected)
      } yield expect(statusOf(s2, name, v).contains(RegistryStatus.Deprecated)) and
      expect(statusOf(s3, name, v).contains(RegistryStatus.Yanked)) and
      expect(unyankFailed)
    }
  }

  test("creating a fiber with a resolvable schemaRef pins the SchemaBinding") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0))
      val create = CreateStateMachine(
        fiberA,
        minimalDef,
        emptyData,
        schemaRef = Some(SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
      )
      for {
        pr1 <- fixture.registry.generateProofs(p1, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(p1, pr1))
        prC <- fixture.registry.generateProofs(create, Set(Alice))
        s2  <- combiner.insert(s1, Signed(create, prC))
        binding = s2.calculated.stateMachines
          .get(fiberA)
          .collect { case r: Records.StateMachineFiberRecord => r }
          .flatMap(_.schemaBinding)
      } yield expect(binding.map(_.name).contains(pkg("escrow"))) and
      expect(binding.map(_.version).contains(SemVer(1, 0, 0)))
    }
  }

  test("creating a fiber whose definition does not match the registered logicHash is rejected (verified binding)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0)) // registers minimalDef's logicHash
      val create = CreateStateMachine(
        fiberA,
        otherDef, // different logic -> different digest than the registered logicHash
        emptyData,
        schemaRef = Some(SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
      )
      for {
        validator     <- Validator.make[IO]
        pr1           <- fixture.registry.generateProofs(p1, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(p1, pr1))
        prC           <- fixture.registry.generateProofs(create, Set(Alice))
        valid         <- validator.validateSignedUpdate(s1, Signed(create, prC))
        combineFailed <- combiner.insert(s1, Signed(create, prC)).map(wasRejected)
      } yield expect(valid.isInvalid) and expect(combineFailed)
    }
  }

  test("creating a fiber with a schemaRef to an unknown name is rejected (validator invalid + combiner aborts)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val create = CreateStateMachine(
        fiberA,
        minimalDef,
        emptyData,
        schemaRef = Some(SchemaRef(pkg("ghost"), VersionReq.Latest))
      )
      for {
        validator     <- Validator.make[IO]
        prC           <- fixture.registry.generateProofs(create, Set(Alice))
        valid         <- validator.validateSignedUpdate(genesis, Signed(create, prC))
        combineFailed <- combiner.insert(genesis, Signed(create, prC)).map(wasRejected)
      } yield expect(valid.isInvalid) and expect(combineFailed)
    }
  }

  test("creating a fiber emits a CreationReceipt recording the resolved binding") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0))
      val create = CreateStateMachine(
        fiberA,
        minimalDef,
        emptyData,
        schemaRef = Some(SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
      )
      for {
        pr1 <- fixture.registry.generateProofs(p1, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(p1, pr1))
        prC <- fixture.registry.generateProofs(create, Set(Alice))
        s2  <- combiner.insert(s1, Signed(create, prC))
        receipt = s2.onChain.latestLogs
          .getOrElse(fiberA, Nil)
          .collectFirst { case r: FiberLogEntry.CreationReceipt => r }
      } yield expect(receipt.isDefined) and
      expect(receipt.flatMap(_.schemaBinding).map(_.name).contains(pkg("escrow"))) and
      expect(receipt.flatMap(_.schemaBinding).map(_.version).contains(SemVer(1, 0, 0))) and
      expect(receipt.map(_.initialState).contains(minimalDef.initialState))
    }
  }

  test("upgrading a bound fiber to a new version re-binds, migrates, and emits an UpgradeReceipt") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0)) // definition = minimalDef
      val p2 = publishWith("escrow", SemVer(2, 0, 0), v2Def) // new logic, same package
      val create = CreateStateMachine(
        fiberA,
        minimalDef,
        emptyData,
        schemaRef = Some(SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
      )
      val upgrade = UpgradeFiber(
        fiberA,
        SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(2, 0, 0))),
        v2Def,
        migration = None,
        targetSequenceNumber = FiberOrdinal.MinValue
      )
      for {
        pr1 <- fixture.registry.generateProofs(p1, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(p1, pr1))
        pr2 <- fixture.registry.generateProofs(p2, Set(Alice))
        s2  <- combiner.insert(s1, Signed(p2, pr2))
        prC <- fixture.registry.generateProofs(create, Set(Alice))
        s3  <- combiner.insert(s2, Signed(create, prC))
        prU <- fixture.registry.generateProofs(upgrade, Set(Alice))
        s4  <- combiner.insert(s3, Signed(upgrade, prU))
        sm = s4.calculated.stateMachines.get(fiberA)
        receipt = s4.onChain.latestLogs
          .getOrElse(fiberA, Nil)
          .collectFirst { case r: FiberLogEntry.UpgradeReceipt => r }
      } yield expect(sm.flatMap(_.schemaBinding).map(_.version).contains(SemVer(2, 0, 0))) and
      expect(sm.map(_.definition).contains(v2Def)) and
      expect(receipt.map(_.toBinding.version).contains(SemVer(2, 0, 0))) and
      expect(receipt.map(_.migrated).contains(false))
    }
  }

  test("upgrading with a definition that does not match the target version's logicHash is rejected") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0)) // logicHash = minimalDef
      val create = CreateStateMachine(
        fiberA,
        minimalDef,
        emptyData,
        schemaRef = Some(SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
      )
      // target 1.0.0 (logicHash = minimalDef) but supply v2Def -> hash mismatch
      val badUpgrade = UpgradeFiber(
        fiberA,
        SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(1, 0, 0))),
        v2Def,
        migration = None,
        targetSequenceNumber = FiberOrdinal.MinValue
      )
      for {
        validator     <- Validator.make[IO]
        pr1           <- fixture.registry.generateProofs(p1, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(p1, pr1))
        prC           <- fixture.registry.generateProofs(create, Set(Alice))
        s2            <- combiner.insert(s1, Signed(create, prC))
        prU           <- fixture.registry.generateProofs(badUpgrade, Set(Alice))
        valid         <- validator.validateSignedUpdate(s2, Signed(badUpgrade, prU))
        combineFailed <- combiner.insert(s2, Signed(badUpgrade, prU)).map(wasRejected)
      } yield expect(valid.isInvalid) and expect(combineFailed)
    }
  }

  test("a mid-batch rejection records a receipt and does NOT abort the rest of the batch") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0)) // ok
      val pBad = publish("escrow", SemVer(0, 9, 0)) // non-monotonic -> rejected (not applied)
      val p3 = publish("escrow", SemVer(1, 1, 0)) // ok, monotonic over 1.0.0, AFTER the rejection
      for {
        pr1 <- fixture.registry.generateProofs(p1, Set(Alice))
        prB <- fixture.registry.generateProofs(pBad, Set(Alice))
        pr3 <- fixture.registry.generateProofs(p3, Set(Alice))
        batch = List(Signed(p1, pr1), Signed(pBad, prB), Signed(p3, pr3))
        // foldLeft is the batch combine: a CombineRejected mid-fold must not short-circuit the rest.
        result <- combiner.foldLeft(genesis, batch)
      } yield
      // p3 (after the rejected pBad) still applied -> the batch did not abort; pBad was rejected-logged.
      expect(versionsOf(result, "escrow").contains(Set(SemVer(1, 0, 0), SemVer(1, 1, 0)))) and
      expect(wasRejected(result))
    }
  }

  test("a downgrade upgrade is rejected (a fiber's bound version is monotonic)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0)) // logicHash = minimalDef
      val p2 = publishWith("escrow", SemVer(2, 0, 0), v2Def) // logicHash = v2Def
      val create = CreateStateMachine(
        fiberA,
        v2Def,
        emptyData,
        schemaRef = Some(SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(2, 0, 0))))
      )
      // Attempt 2.0.0 -> 1.0.0. newDefinition=minimalDef hashes to 1.0.0's logicHash, so only the
      // monotonicity guard (not the verified-binding check) can reject it.
      val downgrade = UpgradeFiber(
        fiberA,
        SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(1, 0, 0))),
        minimalDef,
        migration = None,
        targetSequenceNumber = FiberOrdinal.MinValue
      )
      for {
        pr1      <- fixture.registry.generateProofs(p1, Set(Alice))
        s1       <- combiner.insert(genesis, Signed(p1, pr1))
        pr2      <- fixture.registry.generateProofs(p2, Set(Alice))
        s2       <- combiner.insert(s1, Signed(p2, pr2))
        prC      <- fixture.registry.generateProofs(create, Set(Alice))
        s3       <- combiner.insert(s2, Signed(create, prC))
        prU      <- fixture.registry.generateProofs(downgrade, Set(Alice))
        rejected <- combiner.insert(s3, Signed(downgrade, prU)).map(wasRejected)
        sm = s3.calculated.stateMachines.get(fiberA)
      } yield expect(rejected) and
      expect(sm.flatMap(_.schemaBinding).map(_.version).contains(SemVer(2, 0, 0)))
    }
  }

  // ── #33 runtime conformance gate (opt-in via the version's `strict` flag) ─────────────────────

  test("a strict version aborts a create whose initial state does not conform, accepts a conforming one") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      // strict v1; `shape` declares state field "balance: int64"
      val pStrict =
        PublishVersion(
          pkg("escrow"),
          SemVer(1, 0, 0),
          b64("schema-escrow-1.0.0"),
          shape,
          minimalDef,
          strict = true
        )
      val ref = Some(SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
      val badData: JsonLogicValue = MapValue(Map("balance" -> StrValue("not-an-int"))) // wrong type
      val goodData: JsonLogicValue = MapValue(Map("balance" -> IntValue(0)))
      val createBad = CreateStateMachine(fiberA, minimalDef, badData, schemaRef = ref)
      val createGood = CreateStateMachine(fiberA, minimalDef, goodData, schemaRef = ref)
      for {
        pr1           <- fixture.registry.generateProofs(pStrict, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(pStrict, pr1))
        prB           <- fixture.registry.generateProofs(createBad, Set(Alice))
        combineFailed <- combiner.insert(s1, Signed(createBad, prB)).map(wasRejected)
        prG           <- fixture.registry.generateProofs(createGood, Set(Alice))
        s2            <- combiner.insert(s1, Signed(createGood, prG))
      } yield expect(combineFailed) and expect(s2.calculated.stateMachines.contains(fiberA))
    }
  }

  test("upgrading to a strict version whose conformance fails aborts the migration (fiber stays on the old version)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p1 = publish("escrow", SemVer(1, 0, 0)) // NON-strict v1 -> create with an extra field is allowed
      val p2strict =
        PublishVersion(
          pkg("escrow"),
          SemVer(2, 0, 0),
          b64("schema-escrow-2.0.0"),
          shape,
          v2Def,
          strict = true
        )
      val initData: JsonLogicValue = MapValue(Map("balance" -> IntValue(0), "extra" -> IntValue(9)))
      val create = CreateStateMachine(
        fiberA,
        minimalDef,
        initData,
        schemaRef = Some(SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
      )
      val upgrade = UpgradeFiber(
        fiberA,
        SchemaRef(pkg("escrow"), VersionReq.Exact(SemVer(2, 0, 0))),
        v2Def,
        migration = None, // identity keeps {balance, extra}; "extra" is undeclared in the strict v2 shape
        targetSequenceNumber = FiberOrdinal.MinValue
      )
      for {
        pr1 <- fixture.registry.generateProofs(p1, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(p1, pr1))
        pr2 <- fixture.registry.generateProofs(p2strict, Set(Alice))
        s2  <- combiner.insert(s1, Signed(p2strict, pr2))
        prC <- fixture.registry.generateProofs(create, Set(Alice))
        s3  <- combiner.insert(s2, Signed(create, prC)) // non-strict v1 create with extra field -> ok
        prU <- fixture.registry.generateProofs(upgrade, Set(Alice))
        s4 <- combiner.insert(
          s3,
          Signed(upgrade, prU)
        ) // strict v2 conformance fails -> engine aborts (failure receipt, no re-bind)
        sm = s4.calculated.stateMachines.get(fiberA)
      } yield expect(s3.calculated.stateMachines.contains(fiberA)) and
      expect(
        sm.flatMap(_.schemaBinding).map(_.version).contains(SemVer(1, 0, 0))
      ) and // still v1; upgrade did not apply
      expect(sm.flatMap(_.lastReceipt).exists(!_.success)) // failure receipt recorded
    }
  }

  // ── #29 fiber-name aliases (RegisterAlias) ────────────────────────────────────────────────────

  test("registering a .machine alias for an owned fiber sets the forward entry + reverse record") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val create = CreateStateMachine(fiberA, minimalDef, emptyData) // ad-hoc fiber; Alice owns
      val name = RegistryName.unsafe("my-escrow.machine")
      val alias = RegisterAlias(name, fiberA)
      for {
        prC <- fixture.registry.generateProofs(create, Set(Alice))
        s1  <- combiner.insert(genesis, Signed(create, prC))
        prA <- fixture.registry.generateProofs(alias, Set(Alice))
        s2  <- combiner.insert(s1, Signed(alias, prA))
        target = s2.calculated.registry.get(name).map(_.target)
      } yield expect(target.exists { case RegistryTarget.InstanceAlias(f) => f == fiberA; case _ => false }) and
      expect(s2.calculated.reverseNames.get(fiberA).contains(name))
    }
  }

  test("a .package TLD is rejected as a fiber alias (validator invalid + combiner aborts)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val create = CreateStateMachine(fiberA, minimalDef, emptyData)
      val alias = RegisterAlias(pkg("escrow"), fiberA) // .package TLD is not a fiber alias
      for {
        validator     <- Validator.make[IO]
        prC           <- fixture.registry.generateProofs(create, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(create, prC))
        prA           <- fixture.registry.generateProofs(alias, Set(Alice))
        valid         <- validator.validateSignedUpdate(s1, Signed(alias, prA))
        combineFailed <- combiner.insert(s1, Signed(alias, prA)).map(wasRejected)
      } yield expect(valid.isInvalid) and expect(combineFailed)
    }
  }

  test("a .script alias for a state-machine fiber is rejected (kind mismatch)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val create = CreateStateMachine(fiberA, minimalDef, emptyData) // a state machine
      val alias = RegisterAlias(RegistryName.unsafe("oracle.script"), fiberA) // .script wants a script fiber
      for {
        validator     <- Validator.make[IO]
        prC           <- fixture.registry.generateProofs(create, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(create, prC))
        prA           <- fixture.registry.generateProofs(alias, Set(Alice))
        valid         <- validator.validateSignedUpdate(s1, Signed(alias, prA))
        combineFailed <- combiner.insert(s1, Signed(alias, prA)).map(wasRejected)
      } yield expect(valid.isInvalid) and expect(combineFailed)
    }
  }

  test("a non-owner cannot register an alias for someone else's fiber") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val create = CreateStateMachine(fiberA, minimalDef, emptyData) // Alice owns
      val alias = RegisterAlias(RegistryName.unsafe("hostile.machine"), fiberA)
      for {
        validator     <- Validator.make[IO]
        prC           <- fixture.registry.generateProofs(create, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(create, prC))
        prB           <- fixture.registry.generateProofs(alias, Set(Bob)) // Bob, not an owner, signs
        valid         <- validator.validateSignedUpdate(s1, Signed(alias, prB))
        combineFailed <- combiner.insert(s1, Signed(alias, prB)).map(wasRejected)
      } yield expect(valid.isInvalid) and expect(combineFailed)
    }
  }

  // ── reserved labels + metadata schema ─────────────────────────────────────────────────────────

  test("RegistryMetadata.validate bounds free-form notes (count, key/value length, no control chars)") {
    IO.pure(
      // free-form: any key is allowed within the bounds (semantics are validated off-chain at the Bridge)
      expect(RegistryMetadata.validate(Map("repo" -> "https://github.com/acme/escrow", "anything" -> "ok")).isRight) and
      expect(RegistryMetadata.validate(Map("k" -> ("x" * 200))).isLeft) and // value > 128
      expect(RegistryMetadata.validate((1 to 20).map(i => s"k$i" -> "v").toMap).isLeft) and // > 8 entries
      expect(RegistryMetadata.validate(Map(("k" * 50) -> "v")).isLeft) and // key > 32
      expect(RegistryMetadata.validate(Map("k" -> "a\tb")).isLeft) and // control char
      expect(RegistryMetadata.validate(Map.empty[String, String]).isRight)
    )
  }

  test("publishing under a reserved label (std) is rejected (validator invalid + combiner aborts)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val p = publish("std", SemVer(1, 0, 0)) // -> std.package; "std" is reserved
      for {
        validator     <- Validator.make[IO]
        pr            <- fixture.registry.generateProofs(p, Set(Alice))
        valid         <- validator.validateSignedUpdate(genesis, Signed(p, pr))
        combineFailed <- combiner.insert(genesis, Signed(p, pr)).map(wasRejected)
      } yield expect(valid.isInvalid) and expect(combineFailed)
    }
  }

  test("publish with valid metadata stores it on the entry; over-long metadata is rejected") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0: L0NodeContext[IO] = fixture.l0Context
      val combiner = Combiner.make[IO]()
      val meta = SortedMap("repo" -> "https://github.com/acme/escrow", "license" -> "MIT")
      val good =
        PublishVersion(
          pkg("escrow"),
          SemVer(1, 0, 0),
          b64("schema"),
          shape,
          minimalDef,
          strict = false,
          metadata = meta
        )
      val bad = PublishVersion(
        pkg("widget"),
        SemVer(1, 0, 0),
        b64("schema"),
        shape,
        minimalDef,
        strict = false,
        metadata = SortedMap("note" -> ("x" * 200)) // value exceeds 128 chars
      )
      for {
        validator     <- Validator.make[IO]
        prG           <- fixture.registry.generateProofs(good, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(good, prG))
        prB           <- fixture.registry.generateProofs(bad, Set(Alice))
        validBad      <- validator.validateSignedUpdate(genesis, Signed(bad, prB))
        combineFailed <- combiner.insert(genesis, Signed(bad, prB)).map(wasRejected)
      } yield expect(s1.calculated.registry.get(pkg("escrow")).map(_.metadata).contains(meta)) and
      expect(validBad.isInvalid) and
      expect(combineFailed)
    }
  }
}
