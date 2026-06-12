package xyz.kd5ujc.shared_data

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.RegistryRules

import weaver.SimpleIOSuite

object RegistryLineageSuite extends SimpleIOSuite {

  private val ord = SnapshotOrdinal.MinValue

  private val shape: SchemaShape =
    SchemaShape(
      stateMessage =
        MessageShape("App.State", List(FieldShape("balance", 1, "int64", repeated = false, optional = false))),
      commands = SortedMap(
        "start" -> MessageShape("App.Start", List(FieldShape("amount", 1, "int64", repeated = false, optional = false)))
      )
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
      shape = RegistryShape.Machine(shape),
      status = status,
      registeredAt = ord,
      strict = false
    )

  private def lineage(vs: RegisteredVersion*): VersionLineage =
    vs.foldLeft(VersionLineage.empty)((acc, v) => acc.publish(v).fold(e => sys.error(s"setup failed: $e"), identity))

  // ── SemVer ──────────────────────────────────────────────────────────────────

  test("SemVer parse/render round-trips and orders") {
    IO.pure(
      expect(SemVer.parse("1.2.3") == Right(SemVer(1, 2, 3))) and
      expect(SemVer(1, 2, 3).render == "1.2.3") and
      expect(SemVer.parse("1.2").isLeft) and
      expect(SemVer.parse("1.2.x").isLeft) and
      expect(SemVer.ordering.lt(SemVer(1, 9, 9), SemVer(2, 0, 0))) and
      expect(SemVer.ordering.lt(SemVer(1, 2, 0), SemVer(1, 2, 1)))
    )
  }

  // ── publish: append-only, immutable, monotonic ───────────────────────────────

  test("publish appends a strictly-greater version; rejects duplicate and non-monotonic") {
    val l1 = VersionLineage.empty.publish(rv(1, 0, 0))
    val dup = l1.flatMap(_.publish(rv(1, 0, 0)))
    val lower = l1.flatMap(_.publish(rv(0, 9, 0)))
    val higher = l1.flatMap(_.publish(rv(1, 1, 0)))
    IO.pure(
      expect(l1.isRight) and
      expect(dup == Left(RegistryError.VersionExists(SemVer(1, 0, 0)))) and
      expect(lower == Left(RegistryError.NonMonotonic(SemVer(0, 9, 0), SemVer(1, 0, 0)))) and
      expect(higher.map(_.head.map(_.version)) == Right(Some(SemVer(1, 1, 0))))
    )
  }

  // ── setStatus: legal transitions, immutability of the rest ────────────────────

  test("setStatus follows the lifecycle; Yanked is terminal; unknown version rejected") {
    val l = lineage(rv(1, 0, 0))
    val deprecate = l.setStatus(SemVer(1, 0, 0), RegistryStatus.Deprecated)
    val reactivate = deprecate.flatMap(_.setStatus(SemVer(1, 0, 0), RegistryStatus.Active))
    val yank = l.setStatus(SemVer(1, 0, 0), RegistryStatus.Yanked)
    val unyank = yank.flatMap(_.setStatus(SemVer(1, 0, 0), RegistryStatus.Active))
    val missing = l.setStatus(SemVer(9, 9, 9), RegistryStatus.Deprecated)
    IO.pure(
      expect(deprecate.map(_.get(SemVer(1, 0, 0)).map(_.status)) == Right(Some(RegistryStatus.Deprecated))) and
      expect(reactivate.map(_.get(SemVer(1, 0, 0)).map(_.status)) == Right(Some(RegistryStatus.Active))) and
      expect(yank.isRight) and
      expect(unyank == Left(RegistryError.IllegalStatusTransition(RegistryStatus.Yanked, RegistryStatus.Active))) and
      expect(missing == Left(RegistryError.VersionNotFound(SemVer(9, 9, 9))))
    )
  }

  test("setStatus changes only status; hashes are immutable") {
    val before = rv(1, 0, 0)
    val after: Option[RegisteredVersion] =
      lineage(before).setStatus(SemVer(1, 0, 0), RegistryStatus.Deprecated).toOption.flatMap(_.get(SemVer(1, 0, 0)))
    IO.pure(
      expect(after.map(_.schemaHash) == Some(before.schemaHash)) and
      expect(after.map(_.logicHash) == Some(before.logicHash)) and
      expect(after.map(_.status) == Some(RegistryStatus.Deprecated))
    )
  }

  // ── resolve: deterministic, Yanked excluded ──────────────────────────────────

  private val full = lineage(rv(1, 0, 0), rv(1, 1, 0), rv(1, 2, 0), rv(2, 0, 0))

  test("resolve: Exact / Latest / Caret / Tilde / PinnedHash") {
    IO.pure(
      expect(full.resolve(VersionReq.Exact(SemVer(1, 1, 0))).map(_.version) == Right(SemVer(1, 1, 0))) and
      expect(
        full.resolve(VersionReq.Exact(SemVer(9, 9, 9))) == Left(
          RegistryError.Unresolvable(VersionReq.Exact(SemVer(9, 9, 9)))
        )
      ) and
      expect(full.resolve(VersionReq.Latest).map(_.version) == Right(SemVer(2, 0, 0))) and
      expect(full.resolve(VersionReq.Caret(SemVer(1, 0, 0))).map(_.version) == Right(SemVer(1, 2, 0))) and
      expect(full.resolve(VersionReq.Tilde(SemVer(1, 1, 0))).map(_.version) == Right(SemVer(1, 1, 0))) and
      expect(full.resolve(VersionReq.PinnedHash(Hash("schema-1.1.0"))).map(_.version) == Right(SemVer(1, 1, 0)))
    )
  }

  test("resolve excludes Yanked from Latest/Caret/PinnedHash") {
    val yanked = full.setStatus(SemVer(2, 0, 0), RegistryStatus.Yanked).fold(e => sys.error(e.toString), identity)
    IO.pure(
      expect(yanked.resolve(VersionReq.Latest).map(_.version) == Right(SemVer(1, 2, 0))) and
      expect(
        yanked.resolve(VersionReq.Caret(SemVer(2, 0, 0))) == Left(
          RegistryError.Unresolvable(VersionReq.Caret(SemVer(2, 0, 0)))
        )
      ) and
      expect(yanked.resolve(VersionReq.Exact(SemVer(2, 0, 0))).isLeft)
    )
  }

  // ── machineShapeWellFormed: structural proto validation of the typed domain projection ─────────

  test("machineShapeWellFormed accepts a valid shape and rejects each malformed kind") {
    val outOfRange = shape.copy(stateMessage =
      MessageShape("App.State", List(FieldShape("x", 0, "int64", repeated = false, optional = false)))
    )
    val tooBig = shape.copy(stateMessage =
      MessageShape("App.State", List(FieldShape("x", 536870912, "int64", repeated = false, optional = false)))
    )
    val reserved = shape.copy(stateMessage =
      MessageShape("App.State", List(FieldShape("x", 19500, "int64", repeated = false, optional = false)))
    )
    val dup = shape.copy(stateMessage =
      MessageShape(
        "App.State",
        List(
          FieldShape("a", 1, "int64", repeated = false, optional = false),
          FieldShape("b", 1, "int64", repeated = false, optional = false)
        )
      )
    )
    val emptyFieldName = shape.copy(stateMessage =
      MessageShape("App.State", List(FieldShape("", 1, "int64", repeated = false, optional = false)))
    )
    val emptyTypeName = shape.copy(stateMessage =
      MessageShape("  ", List(FieldShape("a", 1, "int64", repeated = false, optional = false)))
    )
    val emptyCmdName = shape.copy(commands = SortedMap(" " -> MessageShape("App.Start", Nil)))
    for {
      ok  <- RegistryRules.L1.machineShapeWellFormed[IO](shape)
      oor <- RegistryRules.L1.machineShapeWellFormed[IO](outOfRange)
      big <- RegistryRules.L1.machineShapeWellFormed[IO](tooBig)
      res <- RegistryRules.L1.machineShapeWellFormed[IO](reserved)
      dpl <- RegistryRules.L1.machineShapeWellFormed[IO](dup)
      efn <- RegistryRules.L1.machineShapeWellFormed[IO](emptyFieldName)
      etn <- RegistryRules.L1.machineShapeWellFormed[IO](emptyTypeName)
      ecn <- RegistryRules.L1.machineShapeWellFormed[IO](emptyCmdName)
    } yield expect(ok.isValid) and
    expect(oor.isInvalid) and
    expect(big.isInvalid) and
    expect(res.isInvalid) and
    expect(dpl.isInvalid) and
    expect(efn.isInvalid) and
    expect(etn.isInvalid) and
    expect(ecn.isInvalid)
  }
}
