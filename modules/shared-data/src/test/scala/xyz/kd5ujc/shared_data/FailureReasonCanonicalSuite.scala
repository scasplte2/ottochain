package xyz.kd5ujc.shared_data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.core.JsonLogicException
import io.constellationnetwork.metagraph_sdk.json_logic.gas.GasConfig
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.shared_data.fiber.core.FiberTInstances._
import xyz.kd5ujc.shared_data.fiber.core.{ExecutionState, FiberT}
import xyz.kd5ujc.shared_data.fiber.evaluation.ValueKind
import xyz.kd5ujc.shared_data.syntax.all._

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.SimpleIOSuite

/**
 * Cross-version stability guard for COMMITTED failure receipts (audit L1). Every failure the chain records into
 * hashed state — `EventReceipt.errorMessage` (== `reason.toMessage`, hashed via `lastReceipt` in
 * `CalculatedState`) and `RejectionReceipt.reason` — MUST be a pure function of OttoChain-controlled,
 * cross-version-stable data: a stable discriminator plus already-stable typed fields. It must NEVER embed a
 * metakit exception's `getMessage`, a value's `getClass.getSimpleName`, or metakit's own `JsonLogicValue.tag`.
 * If any of those leaked in, a metakit patch that rewords an exception or renames/re-tags a value class would
 * change the committed hash of a REJECTED transaction, forking a mixed-version validator set on a rejected tx.
 *
 * A future change that reintroduces version-dependent text into a committed receipt breaks a test here.
 */
object FailureReasonCanonicalSuite extends SimpleIOSuite {

  private val ctx: FiberContext =
    FiberContext(
      ordinal = SnapshotOrdinal(NonNegLong.unsafeFrom(1L)),
      lastSnapshotHash = Hash.empty,
      epochProgress = EpochProgress(NonNegLong.unsafeFrom(0L)),
      limits = ExecutionLimits(maxDepth = 10, maxGas = 100_000L),
      jlvmGasConfig = GasConfig.Default,
      fiberGasConfig = FiberGasConfig.Default
    )

  /** Run the pure exception -> FailureReason conversion in the real evaluator monad stack. */
  private def convert(ex: JsonLogicException, phase: GasExhaustionPhase): IO[FailureReason] =
    ex.toFailureReason[FiberT[IO, *]](phase).run(ctx).runA(ExecutionState.initial)

  private def sha256(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .map(b => f"$b%02x")
      .mkString

  // ── The load-bearing invariant: a metakit exception's message never reaches the committed receipt ──────────

  test("EvaluationError from two DIFFERENT exception messages (same phase) is byte-identical when committed") {
    val ex1 = new JsonLogicException("division by zero at /balance while evaluating {\"/\":[1,0]}")
    val ex2 = new JsonLogicException("completely unrelated wording added by a future metakit patch")
    for {
      r1 <- convert(ex1, GasExhaustionPhase.Guard)
      r2 <- convert(ex2, GasExhaustionPhase.Guard)
    } yield
    // identical FailureReason, identical committed string (== EventReceipt.errorMessage), identical digest
    expect(r1 == r2) and
    expect(r1.toMessage == r2.toMessage) and
    expect(sha256(r1.toMessage) == sha256(r2.toMessage)) and
    // and the raw exception text is absent from the committed rendering
    expect(!r1.toMessage.contains("division by zero")) and
    expect(!r1.toMessage.contains("future metakit patch")) and
    expect(
      r1.toMessage == s"${GasExhaustionPhase.Guard.entryName} evaluation error: ${FailureReason.EvaluationErrorCode}"
    )
  }

  test("the committed EvaluationError detail is exactly the stable OttoChain-owned code (no exception text)") {
    convert(new JsonLogicException("anything at all"), GasExhaustionPhase.Effect).map {
      case FailureReason.EvaluationError(phase, message) =>
        expect(phase == GasExhaustionPhase.Effect) and
        expect(message == FailureReason.EvaluationErrorCode)
      case other =>
        failure(s"expected EvaluationError, got ${other.reasonCode}")
    }
  }

  // ── ValueKind: OttoChain-owned, instance-independent, stable vocabulary (guards FiberEvaluator/StateMerger) ──

  pureTest("ValueKind.of maps every JLVM value type to a fixed OttoChain-owned token") {
    expect(ValueKind.of(NullValue) == "null") and
    expect(ValueKind.of(BoolValue(true)) == "bool") and
    expect(ValueKind.of(IntValue(1)) == "int") and
    expect(ValueKind.of(FloatValue(BigDecimal("1.5"))) == "float") and
    expect(ValueKind.of(StrValue("x")) == "string") and
    expect(ValueKind.of(ArrayValue(List(IntValue(1)))) == "array") and
    expect(ValueKind.of(MapValue(Map("k" -> IntValue(1)))) == "map")
  }

  pureTest("ValueKind.of depends only on the value's KIND, never its content (instance-independent)") {
    val m1 = MapValue(Map("a" -> IntValue(1)))
    val m2 = MapValue(Map("z" -> StrValue("different"), "y" -> BoolValue(false)))
    val a1 = ArrayValue(List(IntValue(1)))
    val a2 = ArrayValue(List(StrValue("q"), MapValue(Map.empty)))
    expect(ValueKind.of(m1) == ValueKind.of(m2)) and
    expect(ValueKind.of(a1) == ValueKind.of(a2))
  }

  pureTest("the non-boolean-guard / bad-effect committed rendering is content-independent (via ValueKind)") {
    // mirrors FiberEvaluator (guard non-boolean) and StateMerger (bad effect shape): two different values of the
    // same kind must produce the same committed errorMessage.
    def guardNonBool(v: JsonLogicValue): String =
      FailureReason
        .EvaluationError(GasExhaustionPhase.Guard, s"Guard returned non-boolean: ${ValueKind.of(v)}")
        .toMessage

    val m1 = MapValue(Map("a" -> IntValue(1)))
    val m2 = MapValue(Map("b" -> IntValue(2), "c" -> IntValue(3)))
    expect(guardNonBool(m1) == guardNonBool(m2)) and
    expect(sha256(guardNonBool(m1)) == sha256(guardNonBool(m2))) and
    expect(!guardNonBool(m1).contains("MapValue"))
  }

  // ── reasonCode: a stable discriminator, independent of any free-text detail ────────────────────────────────

  pureTest("reasonCode is a fixed token per kind and is independent of the free-text detail") {
    val e1 = FailureReason.EvaluationError(GasExhaustionPhase.Guard, "detail one")
    val e2 = FailureReason.EvaluationError(GasExhaustionPhase.Effect, "utterly different detail")
    expect(e1.reasonCode == "EVALUATION_ERROR") and
    expect(e1.reasonCode == e2.reasonCode) and
    expect(FailureReason.GasExhaustedFailure(1L, 2L, GasExhaustionPhase.Guard).reasonCode == "GAS_EXHAUSTED") and
    expect(FailureReason.PolicyViolation("sealedStates", "x").reasonCode == "POLICY_VIOLATION")
  }

  pureTest("reasonCode is defined (non-empty, uppercase-snake) for a representative sample of every variant group") {
    val samples: List[FailureReason] = List(
      FailureReason.NoTransitionFound(StateId("s"), "e"),
      FailureReason.NoGuardMatched(StateId("s"), "e", 1),
      FailureReason.EvaluationError(GasExhaustionPhase.Guard, "d"),
      FailureReason.CycleDetected(new java.util.UUID(0L, 1L), "e"),
      FailureReason.ValidationFailed("d", ctx.ordinal),
      FailureReason.TriggerTargetNotFound(new java.util.UUID(0L, 2L), None),
      FailureReason.AccessDenied("c", new java.util.UUID(0L, 3L), "Whitelist", None),
      FailureReason.GasExhaustedFailure(1L, 2L, GasExhaustionPhase.Guard),
      FailureReason.FiberInputMismatch(new java.util.UUID(0L, 4L), FiberKind.StateMachine, InputKind.MethodCall),
      FailureReason.FiberNotFound(new java.util.UUID(0L, 5L)),
      FailureReason.FiberNotActive(new java.util.UUID(0L, 6L), "Archived"),
      FailureReason.DepthExceeded(11, 10),
      FailureReason.ScriptInvocationFailed(new java.util.UUID(0L, 7L), "m", None),
      FailureReason.CallerResolutionFailed(new java.util.UUID(0L, 8L), None),
      FailureReason.MissingProof(new java.util.UUID(0L, 9L), "op"),
      FailureReason.StateSizeTooLarge(2, 1),
      FailureReason.InvalidChildIdFormat("expr", "err"),
      FailureReason.DuplicateChildId(new java.util.UUID(0L, 10L), 2),
      FailureReason.ChildIdCollision(new java.util.UUID(0L, 11L)),
      FailureReason.InvalidOwnersExpression("err"),
      FailureReason.InvalidOwnerAddress("addr", "err"),
      FailureReason.ChildIdEvaluationFailed("err"),
      FailureReason.OwnersEvaluationFailed("err"),
      FailureReason.DependencyLimitExceeded("active", 1, 0),
      FailureReason.SpawnLimitExceeded(17, 16),
      FailureReason.PolicyViolation("dial", "detail")
    )
    val codes = samples.map(_.reasonCode)
    expect(codes.forall(c => c.nonEmpty && c == c.toUpperCase && !c.contains(" "))) and
    // toMessage is total (never throws) for every variant
    expect(samples.forall(_.toMessage.nonEmpty))
  }
}
