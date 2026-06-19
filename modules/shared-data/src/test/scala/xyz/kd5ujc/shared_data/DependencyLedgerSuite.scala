package xyz.kd5ujc.shared_data

import java.util.UUID

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.fiber.{DynamicDependency, ExecutionLimits, FailureReason, FiberEffect}
import xyz.kd5ujc.shared_data.fiber.core.DependencyLedger

import weaver.FunSuite

/**
 * Pure unit tests for the append-only dynamic-dependency ledger (#24): upsert semantics + DoS bounds.
 */
object DependencyLedgerSuite extends FunSuite {

  private val ord0 = SnapshotOrdinal.MinValue
  private val ord1 = ord0.next

  private def uuid(n: Int): UUID = new UUID(0L, n.toLong)
  private def add(id: UUID): FiberEffect.DependencyMutated = FiberEffect.DependencyMutated(id, active = true)
  private def set(id: UUID, a: Boolean): FiberEffect.DependencyMutated = FiberEffect.DependencyMutated(id, a)

  test("add appends a new active entry stamped with the current ordinal") {
    expect(
      DependencyLedger.applyMutations(Nil, List(add(uuid(1))), ord0, ExecutionLimits()) ==
        Right(List(DynamicDependency(uuid(1), active = true, ord0)))
    )
  }

  test("set upserts in place: flips active and PRESERVES the original addedAt") {
    val start = List(DynamicDependency(uuid(1), active = true, ord0))
    expect(
      DependencyLedger.applyMutations(start, List(set(uuid(1), a = false)), ord1, ExecutionLimits()) ==
        Right(List(DynamicDependency(uuid(1), active = false, ord0))) // addedAt stays ord0, not ord1
    )
  }

  test("re-adding a deactivated dep reactivates the SAME entry (no duplicate, addedAt preserved)") {
    val start = List(DynamicDependency(uuid(1), active = false, ord0))
    expect(
      DependencyLedger.applyMutations(start, List(add(uuid(1))), ord1, ExecutionLimits()) ==
        Right(List(DynamicDependency(uuid(1), active = true, ord0)))
    )
  }

  test("ledger cap blocks a NEW distinct fiber beyond maxDependencyLedger") {
    val limits = ExecutionLimits(maxDependencyLedger = 2)
    expect(
      DependencyLedger.applyMutations(Nil, List(add(uuid(1)), add(uuid(2)), add(uuid(3))), ord0, limits) ==
        Left(FailureReason.DependencyLimitExceeded("ledger", 3, 2))
    )
  }

  test("inactive entries STILL count toward the ledger cap (append-only, never pruned)") {
    val limits = ExecutionLimits(maxDependencyLedger = 2, maxActiveDependencies = 64)
    val muts = List(add(uuid(1)), add(uuid(2)), set(uuid(2), a = false), add(uuid(3)))
    expect(
      DependencyLedger.applyMutations(Nil, muts, ord0, limits) ==
        Left(FailureReason.DependencyLimitExceeded("ledger", 3, 2))
    )
  }

  test("active cap blocks too many ACTIVE deps") {
    val limits = ExecutionLimits(maxActiveDependencies = 2)
    expect(
      DependencyLedger.applyMutations(Nil, List(add(uuid(1)), add(uuid(2)), add(uuid(3))), ord0, limits) ==
        Left(FailureReason.DependencyLimitExceeded("active", 3, 2))
    )
  }

  test("toggling a dep off within the batch keeps the final active-count within cap") {
    val limits = ExecutionLimits(maxActiveDependencies = 1)
    val muts = List(add(uuid(1)), add(uuid(2)), set(uuid(2), a = false)) // net: only #1 active
    expect(DependencyLedger.applyMutations(Nil, muts, ord0, limits).map(_.count(_.active)) == Right(1))
  }

  test("activeIds returns only the active subset") {
    val ledger = List(
      DynamicDependency(uuid(1), active = true, ord0),
      DynamicDependency(uuid(2), active = false, ord0),
      DynamicDependency(uuid(3), active = true, ord0)
    )
    expect(DependencyLedger.activeIds(ledger) == Set(uuid(1), uuid(3)))
  }
}
