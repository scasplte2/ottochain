package xyz.kd5ujc.shared_data

import java.nio.charset.StandardCharsets
import java.util.Base64

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.{PublishVersion, SetVersionStatus}
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
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

  private def publish(name: String, v: SemVer): PublishVersion =
    PublishVersion(
      name = RegistryName.unsafe(name),
      version = v,
      schemaB64 = b64(s"schema-$name-${v.render}"),
      definitionB64 = b64(s"logic-$name-${v.render}"),
      stateMessage = "App.State",
      commands = SortedMap("start" -> "App.Start")
    )

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
}
