package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates
import xyz.kd5ujc.schema.fiber.{AccessControlPolicy, FiberOrdinal}
import xyz.kd5ujc.shared_data.fee.{FeeConfig, FeeEstimator, FeeValidator}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.parser
import weaver.SimpleIOSuite

/**
 * Tests for OttoChain fee processing: FeeConfig, FeeEstimator, FeeValidator.
 *
 * Spec: docs/specs/fee-schedule-spec.md
 * Trello: 69a254593c794d44d09d247e
 */
object FeeProcessingSuite extends SimpleIOSuite {

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private val simpleProgram: JsonLogicExpression =
    parser
      .parse("""{"if": [true, {"value": 1}, {"value": 0}]}""")
      .flatMap(_.as[JsonLogicExpression])
      .getOrElse(throw new RuntimeException("Failed to parse test program"))

  private def makeCreateScript(fiberId: UUID = UUID.randomUUID()): Updates.CreateScript =
    Updates.CreateScript(
      fiberId = fiberId,
      scriptProgram = simpleProgram,
      initialState = Some(MapValue(Map("value" -> IntValue(0)))),
      accessControl = AccessControlPolicy.Public
    )

  private def makeInvokeScript(fiberId: UUID = UUID.randomUUID()): Updates.InvokeScript =
    Updates.InvokeScript(
      fiberId = fiberId,
      method = "increment",
      args = MapValue(Map.empty),
      targetSequenceNumber = FiberOrdinal.MinValue
    )

  private def makeArchiveStateMachine(fiberId: UUID = UUID.randomUUID()): Updates.ArchiveStateMachine =
    Updates.ArchiveStateMachine(
      fiberId = fiberId,
      targetSequenceNumber = FiberOrdinal.MinValue
    )

  // ── Group A: Base fee by operation type ──────────────────────────────────────

  test("A1 — CreateScript operation → base fee 1,000,000 datum") {
    IO.pure(FeeEstimator.estimateFee(FeeConfig.disabled)(makeCreateScript()))
      .map(fee => expect(fee >= 1_000_000L))
  }

  test("A2 — InvokeScript operation → base fee 100,000 datum") {
    IO.pure(FeeEstimator.estimateFee(FeeConfig.disabled)(makeInvokeScript()))
      .map(fee => expect(fee >= 100_000L && fee < 200_000L))
  }

  test("A3 — ArchiveStateMachine operation → fee is 0") {
    IO.pure(FeeEstimator.estimateFee(FeeConfig.disabled)(makeArchiveStateMachine()))
      .map(fee => expect(fee == 0L))
  }

  // ── Group B: Payload size surcharge ──────────────────────────────────────────

  test("B1 — small payload (≤ 4KB) → no surcharge") {
    val small = makeCreateScript()
    IO.pure(FeeEstimator.estimateFee(FeeConfig.disabled)(small))
      .map(fee => expect(fee == 1_000_000L))
  }

  test("B2 — large payload (> 4KB) → surcharge applied") {
    // Stuff a large string into the initial state to exceed 4KB baseline
    val largeStr = "x" * 6000
    val large = Updates.CreateScript(
      fiberId = UUID.randomUUID(),
      scriptProgram = simpleProgram,
      initialState = Some(StrValue(largeStr)),
      accessControl = AccessControlPolicy.Public
    )
    IO.pure(FeeEstimator.estimateFee(FeeConfig.disabled)(large))
      .map(fee => expect(fee > 1_000_000L))
  }

  // ── Group C: FeeConfig factory methods ────────────────────────────────────────

  test("C1 — FeeConfig.disabled → enabled=false, no treasury address") {
    IO.pure(FeeConfig.disabled).map { cfg =>
      expect(cfg.enabled == false) &&
      expect(cfg.treasuryAddress.isEmpty)
    }
  }

  test("C2 — FeeConfig.enabled → enabled=true, treasury address set") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      val treasury = fixture.registry(Charlie).address
      val cfg = FeeConfig.enabled(treasury)
      IO.pure(
        expect(cfg.enabled == true) &&
        expect(cfg.required == true) &&
        expect(cfg.treasuryAddress.contains(treasury))
      )
    }
  }

  test("C3 — FeeConfig.fromConfig with missing treasury → Left error") {
    val result = FeeConfig.fromConfig(enabled = true, required = true, treasuryAddress = None)
    IO.pure(
      expect(result.isLeft) &&
      expect(result.left.toOption.exists(_.contains("treasury")))
    )
  }

  test("C4 — FeeConfig.fromConfig with treasury → Right config") {
    TestFixture.resource(Set(Charlie)).use { fixture =>
      val treasury = fixture.registry(Charlie).address
      val result = FeeConfig.fromConfig(enabled = true, required = true, treasuryAddress = Some(treasury))
      IO.pure(expect(result.isRight))
    }
  }

  // ── Group D: FeeValidator — grace period (enabled=false) ────────────────────

  test("D1 — grace period: missing fee accepted") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider

      val update = makeCreateScript()
      for {
        proofs <- fixture.registry.generateProofs(update, Set(Alice))
        signed = Signed(update, proofs)
        result <- FeeValidator.validateFee[IO](FeeConfig.disabled)(signed, None)
      } yield expect(result.isValid)
    }
  }

  test("D2 — grace period: voluntary fee accepted (no enforcement)") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider

      val update = makeCreateScript()
      for {
        proofs <- fixture.registry.generateProofs(update, Set(Alice))
        signed = Signed(update, proofs)
        updateHash <- (update: Updates.OttochainMessage).computeDigest
        fee = FeeTransaction(
          source = fixture.registry(Alice).address,
          destination = fixture.registry(Charlie).address,
          amount = Amount(NonNegLong.unsafeFrom(1_500_000L)),
          dataUpdateRef = updateHash
        )
        // Reuse proofs set for the fee wrapper (FeeValidator doesn't re-verify fee sigs)
        signedFee = Signed(fee, proofs)
        result <- FeeValidator.validateFee[IO](FeeConfig.disabled)(signed, Some(signedFee))
      } yield expect(result.isValid)
    }
  }

  // ── Group E: FeeValidator — enforcement mode ─────────────────────────────────

  test("E1 — enforcement mode: missing fee rejected") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider

      val treasury = fixture.registry(Charlie).address
      val cfg = FeeConfig.enabled(treasury)
      val update = makeCreateScript()

      for {
        proofs <- fixture.registry.generateProofs(update, Set(Alice))
        signed = Signed(update, proofs)
        result <- FeeValidator.validateFee[IO](cfg)(signed, None)
      } yield expect(result.isInvalid)
    }
  }

  test("E2 — enforcement mode: insufficient fee rejected") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider

      val treasury = fixture.registry(Charlie).address
      val cfg = FeeConfig.enabled(treasury)
      val update = makeCreateScript()

      for {
        proofs <- fixture.registry.generateProofs(update, Set(Alice))
        signed = Signed(update, proofs)
        updateHash <- (update: Updates.OttochainMessage).computeDigest
        fee = FeeTransaction(
          source = fixture.registry(Alice).address,
          destination = treasury,
          amount = Amount(NonNegLong.unsafeFrom(500_000L)), // too low!
          dataUpdateRef = updateHash
        )
        signedFee = Signed(fee, proofs)
        result <- FeeValidator.validateFee[IO](cfg)(signed, Some(signedFee))
      } yield expect(result.isInvalid)
    }
  }

  test("E3 — enforcement mode: wrong destination rejected") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider

      val treasury = fixture.registry(Charlie).address
      val cfg = FeeConfig.enabled(treasury)
      val update = makeCreateScript()

      for {
        proofs <- fixture.registry.generateProofs(update, Set(Alice))
        signed = Signed(update, proofs)
        updateHash <- (update: Updates.OttochainMessage).computeDigest
        fee = FeeTransaction(
          source = fixture.registry(Alice).address,
          destination = fixture.registry(Bob).address, // wrong destination!
          amount = Amount(NonNegLong.unsafeFrom(1_500_000L)),
          dataUpdateRef = updateHash
        )
        signedFee = Signed(fee, proofs)
        result <- FeeValidator.validateFee[IO](cfg)(signed, Some(signedFee))
      } yield expect(result.isInvalid)
    }
  }

  test("E4 — enforcement mode: valid fee accepted") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider

      val treasury = fixture.registry(Charlie).address
      val cfg = FeeConfig.enabled(treasury)
      val update = makeCreateScript()

      for {
        proofs <- fixture.registry.generateProofs(update, Set(Alice))
        signed = Signed(update, proofs)
        updateHash <- (update: Updates.OttochainMessage).computeDigest
        fee = FeeTransaction(
          source = fixture.registry(Alice).address,
          destination = treasury,
          amount = Amount(NonNegLong.unsafeFrom(1_500_000L)), // above 1M minimum
          dataUpdateRef = updateHash
        )
        signedFee = Signed(fee, proofs)
        result <- FeeValidator.validateFee[IO](cfg)(signed, Some(signedFee))
      } yield expect(result.isValid)
    }
  }

  test("E5 — enforcement mode: wrong dataUpdateRef rejected") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider

      val treasury = fixture.registry(Charlie).address
      val cfg = FeeConfig.enabled(treasury)
      val update = makeCreateScript()
      val differentUpdate = makeCreateScript() // different fiberId → different hash

      for {
        proofs <- fixture.registry.generateProofs(update, Set(Alice))
        signed = Signed(update, proofs)
        wrongHash <- (differentUpdate: Updates.OttochainMessage).computeDigest // hash of a DIFFERENT update
        fee = FeeTransaction(
          source = fixture.registry(Alice).address,
          destination = treasury,
          amount = Amount(NonNegLong.unsafeFrom(1_500_000L)),
          dataUpdateRef = wrongHash // references wrong update!
        )
        signedFee = Signed(fee, proofs)
        result <- FeeValidator.validateFee[IO](cfg)(signed, Some(signedFee))
      } yield expect(result.isInvalid)
    }
  }

  // ── Group F: End-to-end estimate → create → validate ────────────────────────

  test("F1 — end-to-end: estimate fee → construct fee tx → validate → accepted") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val sp: SecurityProvider[IO] = fixture.securityProvider

      val treasury = fixture.registry(Charlie).address
      val cfg = FeeConfig.enabled(treasury)
      val update = makeCreateScript()

      for {
        // 1. Estimate fee (pure)
        estimatedAmount <- IO.pure(FeeEstimator.estimateFee(cfg)(update))
        // 2. Sign the update
        proofs <- fixture.registry.generateProofs(update, Set(Alice))
        signed = Signed(update, proofs)
        // 3. Hash update for fee reference
        updateHash <- (update: Updates.OttochainMessage).computeDigest
        // 4. Construct fee transaction using estimated amount
        fee = FeeTransaction(
          source = fixture.registry(Alice).address,
          destination = treasury,
          amount = Amount(NonNegLong.unsafeFrom(estimatedAmount)),
          dataUpdateRef = updateHash
        )
        signedFee = Signed(fee, proofs)
        // 5. Validate
        result <- FeeValidator.validateFee[IO](cfg)(signed, Some(signedFee))
      } yield expect(result.isValid) &&
      expect(estimatedAmount == 1_000_000L) // base fee for CreateScript, tiny payload
    }
  }
}
