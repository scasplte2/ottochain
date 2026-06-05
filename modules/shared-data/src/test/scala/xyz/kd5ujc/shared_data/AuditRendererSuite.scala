package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic.{MapValue, NullValue}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber.{AuditRenderer, FiberFingerprint, FiberKind, FiberLogEntry, FiberOrdinal, StateId}
import xyz.kd5ujc.schema.registry.{RegistryName, SchemaBinding, SemVer}

import eu.timepit.refined.refineV
import weaver.SimpleIOSuite

/**
 * Tests the human-readable audit rendering (#30): each FiberLogEntry variant renders with its proquint
 * fingerprint + the key facts, and the embedded fingerprint round-trips to the fiber UUID.
 */
object AuditRendererSuite extends SimpleIOSuite {

  private val fiberA = UUID.fromString("11111111-1111-4111-8111-111111111111")
  private val oracleId = UUID.fromString("22222222-2222-4222-8222-222222222222")
  private val ord = SnapshotOrdinal.MinValue
  private val v1 = SchemaBinding(RegistryName.unsafe("escrow"), SemVer(1, 0, 0), Hash("sh1"), Hash("lh1"))
  private val v2 = SchemaBinding(RegistryName.unsafe("escrow"), SemVer(2, 0, 0), Hash("sh2"), Hash("lh2"))

  private val caller: Address =
    refineV[DAGAddressRefined].apply[String]("DAG2BAUcXKujRhzk4XZ6RDYL2ifXWMgfw1v7YxZu") match {
      case Right(v) => Address(v)
      case Left(e)  => sys.error(s"bad test address: $e")
    }

  private val creation =
    FiberLogEntry.CreationReceipt(fiberA, ord, StateId("initial"), Set.empty, Some(v1), None)

  private val upgrade =
    FiberLogEntry.UpgradeReceipt(fiberA, ord, Some(v1), v2, gasUsed = 100L, migrated = true)

  private val event = FiberLogEntry.EventReceipt(
    fiberA,
    FiberOrdinal.MinValue,
    "deposit",
    ord,
    StateId("open"),
    StateId("funded"),
    success = true,
    gasUsed = 50L,
    triggersFired = 1
  )

  private val invocation =
    FiberLogEntry.OracleInvocation(oracleId, "compute", MapValue.empty, NullValue, gasUsed = 30L, ord, caller)

  test("renders each log entry with its fingerprint and the key facts") {
    val mFp = FiberFingerprint.of(fiberA, FiberKind.StateMachine)
    val sFp = FiberFingerprint.of(oracleId, FiberKind.Script)
    IO.pure(
      expect(AuditRenderer.render(creation).contains(mFp)) and
      expect(AuditRenderer.render(creation).contains("created in state 'initial'")) and
      expect(AuditRenderer.render(creation).contains("bound to escrow@1.0.0")) and
      expect(AuditRenderer.render(upgrade).contains("upgraded escrow@1.0.0 -> escrow@2.0.0")) and
      expect(AuditRenderer.render(upgrade).contains("state migrated")) and
      expect(AuditRenderer.render(event).contains("'deposit'")) and
      expect(AuditRenderer.render(event).contains("open -> funded")) and
      expect(AuditRenderer.render(event).contains("trigger")) and
      expect(AuditRenderer.render(invocation).contains(sFp)) and
      expect(AuditRenderer.render(invocation).contains(".compute()")) and
      expect(AuditRenderer.render(invocation).contains("DAG2BAUcXKujRhzk4XZ6RDYL2ifXWMgfw1v7YxZu"))
    )
  }

  test("a failed event renders FAILED + reason; renderAll preserves order") {
    val failed = FiberLogEntry.EventReceipt(
      fiberA,
      FiberOrdinal.MinValue,
      "withdraw",
      ord,
      StateId("open"),
      StateId("open"),
      success = false,
      gasUsed = 10L,
      triggersFired = 0,
      errorMessage = Some("guard failed")
    )
    val lines = AuditRenderer.renderAll(List(creation, event))
    IO.pure(
      expect(AuditRenderer.render(failed).contains("FAILED: guard failed")) and
      expect(lines.size == 2) and
      expect(lines.head.contains("created")) and
      expect(lines(1).contains("'deposit'"))
    )
  }

  test("the fingerprint embedded in a rendered line round-trips to the fiber UUID") {
    IO.pure(
      expect(FiberFingerprint.decode(FiberFingerprint.of(fiberA, FiberKind.StateMachine)) == Right(fiberA)) and
      expect(AuditRenderer.render(creation).contains(FiberFingerprint.of(fiberA, FiberKind.StateMachine)))
    )
  }
}
