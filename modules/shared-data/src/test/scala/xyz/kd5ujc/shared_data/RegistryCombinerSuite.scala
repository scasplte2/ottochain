package xyz.kd5ujc.shared_data

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicValue, MapValue}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.{CreateStateMachine, PublishVersion, SetVersionStatus, UpgradeFiber}
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

  private def b64(s: String): String = Base64.getEncoder.encodeToString(s.getBytes(StandardCharsets.UTF_8))

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
      name = RegistryName.unsafe(name),
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
    PublishVersion(RegistryName.unsafe(name), v, b64(s"schema-$name-${v.render}"), shape, definition)

  private val genesis = DataState(OnChain.genesis, CalculatedState.genesis)

  private def versionsOf(state: DataState[OnChain, CalculatedState], name: String): Option[Set[SemVer]] =
    state.calculated.registry
      .get(RegistryName.unsafe(name))
      .map(_.target)
      .collect { case RegistryTarget.SchemaPackage(l) => l.versions.keySet }

  private def statusOf(state: DataState[OnChain, CalculatedState], name: String, v: SemVer): Option[RegistryStatus] =
    state.calculated.registry
      .get(RegistryName.unsafe(name))
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
      expect(s2.onChain.registryCommits.contains(RegistryName.unsafe("escrow")))
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
        combineFailed <- combiner.insert(s1, Signed(p2, pr2)).attempt.map(_.isLeft)
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
        combineFailed <- combiner.insert(s1, Signed(pLow, prLow)).attempt.map(_.isLeft)
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
      val deprecate = SetVersionStatus(RegistryName.unsafe(name), v, RegistryStatus.Deprecated)
      val yank = SetVersionStatus(RegistryName.unsafe(name), v, RegistryStatus.Yanked)
      val unyank = SetVersionStatus(RegistryName.unsafe(name), v, RegistryStatus.Active)
      for {
        pr1          <- fixture.registry.generateProofs(p1, Set(Alice))
        s1           <- combiner.insert(genesis, Signed(p1, pr1))
        prD          <- fixture.registry.generateProofs(deprecate, Set(Alice))
        s2           <- combiner.insert(s1, Signed(deprecate, prD))
        prY          <- fixture.registry.generateProofs(yank, Set(Alice))
        s3           <- combiner.insert(s2, Signed(yank, prY))
        prU          <- fixture.registry.generateProofs(unyank, Set(Alice))
        unyankFailed <- combiner.insert(s3, Signed(unyank, prU)).attempt.map(_.isLeft)
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
        schemaRef = Some(SchemaRef(RegistryName.unsafe("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
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
      } yield expect(binding.map(_.name).contains(RegistryName.unsafe("escrow"))) and
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
        schemaRef = Some(SchemaRef(RegistryName.unsafe("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
      )
      for {
        validator     <- Validator.make[IO]
        pr1           <- fixture.registry.generateProofs(p1, Set(Alice))
        s1            <- combiner.insert(genesis, Signed(p1, pr1))
        prC           <- fixture.registry.generateProofs(create, Set(Alice))
        valid         <- validator.validateSignedUpdate(s1, Signed(create, prC))
        combineFailed <- combiner.insert(s1, Signed(create, prC)).attempt.map(_.isLeft)
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
        schemaRef = Some(SchemaRef(RegistryName.unsafe("ghost"), VersionReq.Latest))
      )
      for {
        validator     <- Validator.make[IO]
        prC           <- fixture.registry.generateProofs(create, Set(Alice))
        valid         <- validator.validateSignedUpdate(genesis, Signed(create, prC))
        combineFailed <- combiner.insert(genesis, Signed(create, prC)).attempt.map(_.isLeft)
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
        schemaRef = Some(SchemaRef(RegistryName.unsafe("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
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
      expect(receipt.flatMap(_.schemaBinding).map(_.name).contains(RegistryName.unsafe("escrow"))) and
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
        schemaRef = Some(SchemaRef(RegistryName.unsafe("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
      )
      val upgrade = UpgradeFiber(
        fiberA,
        SchemaRef(RegistryName.unsafe("escrow"), VersionReq.Exact(SemVer(2, 0, 0))),
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
        schemaRef = Some(SchemaRef(RegistryName.unsafe("escrow"), VersionReq.Exact(SemVer(1, 0, 0))))
      )
      // target 1.0.0 (logicHash = minimalDef) but supply v2Def -> hash mismatch
      val badUpgrade = UpgradeFiber(
        fiberA,
        SchemaRef(RegistryName.unsafe("escrow"), VersionReq.Exact(SemVer(1, 0, 0))),
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
        combineFailed <- combiner.insert(s2, Signed(badUpgrade, prU)).attempt.map(_.isLeft)
      } yield expect(valid.isInvalid) and expect(combineFailed)
    }
  }
}
