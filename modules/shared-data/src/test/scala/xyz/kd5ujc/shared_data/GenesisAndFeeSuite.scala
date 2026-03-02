package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication.{
  DataApplicationValidationErrorOr,
  FeeTransaction,
  L1NodeContext
}
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * TDD tests for fee-based data transactions functionality.
 *
 * These tests validate the fee processing framework per tessellation's DL1 fee model.
 * Tests are written to FAIL initially - implementations of FeeConfig, FeeValidator,
 * and SDK methods will make them pass.
 *
 * Based on spec: Fee-based data transactions (69a254593c794d44d09d247e)
 */
object GenesisAndFeeSuite extends SimpleIOSuite {

  // Test Group A: Basic fee requirement logic

  test("fee disabled → no fee required (should pass)") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context

      for {
        // Create a simple OttochainMessage
        update <- IO.pure(
          OttochainMessage(
            parentReference = UpdateReference.empty,
            operation = "test",
            data = Map("message" -> "hello world")
          )
        )

        // FeeConfig with disabled fees - THIS WILL FAIL because FeeConfig doesn't exist yet
        feeConfig <- IO.pure(???) // FeeConfig.Disabled

        // Validate with disabled fees - should pass regardless of presence/absence of fee
        result <- IO.pure(???) // FeeValidator.validateFee(update, feeConfig)

      } yield expect(result.isValid)
    }
  }

  test("fee required → update without fee rejected (should return FeeRequired error)") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context

      for {
        update <- IO.pure(
          OttochainMessage(
            parentReference = UpdateReference.empty,
            operation = "test",
            data = Map("message" -> "requires fee")
          )
        )

        // FeeConfig with required fees - THIS WILL FAIL because FeeConfig doesn't exist yet
        treasuryAddress <- IO.pure(fixture.registry(Charlie).address)
        feeConfig <- IO.pure(???) // FeeConfig(enabled = true, required = true, minimumAmount = 1000L, treasuryAddress)

        // Mock L1 context returns empty fee transactions map (no fee provided)
        result <- IO.pure(???) // FeeValidator.validateFee(update, feeConfig)

      } yield expect(result.isInvalid) and
      expect(result.fold(_.toList.head, _ => "").contains("FeeRequired"))
    }
  }

  // Test Group B: Fee transaction validation

  test("valid fee (correct dest + amount ≥ min) should pass") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context

      for {
        update <- IO.pure(
          OttochainMessage(
            parentReference = UpdateReference.empty,
            operation = "test",
            data = Map("message" -> "with valid fee")
          )
        )

        updateHash      <- (update: OttochainMessage).computeDigest
        treasuryAddress <- IO.pure(fixture.registry(Charlie).address)
        payerAddress    <- IO.pure(fixture.registry(Alice).address)

        // Valid fee transaction
        feeTransaction <- IO.pure(
          FeeTransaction(
            source = payerAddress,
            destination = treasuryAddress,
            amount = Amount(2000L), // Above minimum
            dataUpdateRef = updateHash
          )
        )

        // Sign the fee transaction - THIS WILL FAIL because signing method doesn't exist yet
        signedFeeTx <- IO.pure(???) // fixture.registry(Alice).signFeeTransaction(feeTransaction)

        feeConfig <- IO.pure(???) // FeeConfig(enabled = true, required = false, minimumAmount = 1000L, treasuryAddress)

        // Mock L1 context to return this fee transaction
        // THIS WILL FAIL because the mocking setup doesn't exist yet
        _ <- IO.pure(???) // fixture.l1Context.setSnapshotFeeTransactions(Map(updateHash -> signedFeeTx))

        result <- IO.pure(???) // FeeValidator.validateFee(update, feeConfig)

      } yield expect(result.isValid)
    }
  }

  test("fee with wrong destination should return FeeBadDestination error") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context

      for {
        update <- IO.pure(
          OttochainMessage(
            parentReference = UpdateReference.empty,
            operation = "test",
            data = Map("message" -> "bad destination fee")
          )
        )

        updateHash       <- (update: OttochainMessage).computeDigest
        treasuryAddress  <- IO.pure(fixture.registry(Charlie).address)
        wrongDestination <- IO.pure(fixture.registry(Bob).address) // Wrong destination!

        feeTransaction <- IO.pure(
          FeeTransaction(
            source = fixture.registry(Alice).address,
            destination = wrongDestination,
            amount = Amount(2000L),
            dataUpdateRef = updateHash
          )
        )

        signedFeeTx <- IO.pure(???) // fixture.registry(Alice).signFeeTransaction(feeTransaction)
        feeConfig <- IO.pure(???) // FeeConfig(enabled = true, required = false, minimumAmount = 1000L, treasuryAddress)

        _      <- IO.pure(???) // fixture.l1Context.setSnapshotFeeTransactions(Map(updateHash -> signedFeeTx))
        result <- IO.pure(???) // FeeValidator.validateFee(update, feeConfig)

      } yield expect(result.isInvalid) and
      expect(result.fold(_.toList.head, _ => "").contains("FeeBadDestination"))
    }
  }

  test("fee below minimum should return FeeTooLow error") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context

      for {
        update <- IO.pure(
          OttochainMessage(
            parentReference = UpdateReference.empty,
            operation = "test",
            data = Map("message" -> "low fee")
          )
        )

        updateHash      <- (update: OttochainMessage).computeDigest
        treasuryAddress <- IO.pure(fixture.registry(Charlie).address)

        feeTransaction <- IO.pure(
          FeeTransaction(
            source = fixture.registry(Alice).address,
            destination = treasuryAddress,
            amount = Amount(500L), // Below minimum of 1000L
            dataUpdateRef = updateHash
          )
        )

        signedFeeTx <- IO.pure(???) // fixture.registry(Alice).signFeeTransaction(feeTransaction)
        feeConfig <- IO.pure(???) // FeeConfig(enabled = true, required = false, minimumAmount = 1000L, treasuryAddress)

        _      <- IO.pure(???) // fixture.l1Context.setSnapshotFeeTransactions(Map(updateHash -> signedFeeTx))
        result <- IO.pure(???) // FeeValidator.validateFee(update, feeConfig)

      } yield expect(result.isInvalid) and
      expect(result.fold(_.toList.head, _ => "").contains("FeeTooLow"))
    }
  }

  test("fee with wrong dataUpdateRef should return FeeRefMismatch error") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context

      for {
        update <- IO.pure(
          OttochainMessage(
            parentReference = UpdateReference.empty,
            operation = "test",
            data = Map("message" -> "ref mismatch")
          )
        )

        updateHash      <- (update: OttochainMessage).computeDigest
        treasuryAddress <- IO.pure(fixture.registry(Charlie).address)

        // Create different update to get wrong hash
        wrongUpdate <- IO.pure(
          OttochainMessage(
            parentReference = UpdateReference.empty,
            operation = "different",
            data = Map("message" -> "different data")
          )
        )
        wrongHash <- (wrongUpdate: OttochainMessage).computeDigest

        feeTransaction <- IO.pure(
          FeeTransaction(
            source = fixture.registry(Alice).address,
            destination = treasuryAddress,
            amount = Amount(2000L),
            dataUpdateRef = wrongHash // Wrong reference!
          )
        )

        signedFeeTx <- IO.pure(???) // fixture.registry(Alice).signFeeTransaction(feeTransaction)
        feeConfig <- IO.pure(???) // FeeConfig(enabled = true, required = false, minimumAmount = 1000L, treasuryAddress)

        _      <- IO.pure(???) // fixture.l1Context.setSnapshotFeeTransactions(Map(updateHash -> signedFeeTx))
        result <- IO.pure(???) // FeeValidator.validateFee(update, feeConfig)

      } yield expect(result.isInvalid) and
      expect(result.fold(_.toList.head, _ => "").contains("FeeRefMismatch"))
    }
  }

  // Test Group C: Optional fees

  test("fee optional + fee present → accepted (should pass)") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l1ctx: L1NodeContext[IO] = fixture.l1Context

      for {
        update <- IO.pure(
          OttochainMessage(
            parentReference = UpdateReference.empty,
            operation = "test",
            data = Map("message" -> "optional fee present")
          )
        )

        updateHash      <- (update: OttochainMessage).computeDigest
        treasuryAddress <- IO.pure(fixture.registry(Charlie).address)

        feeTransaction <- IO.pure(
          FeeTransaction(
            source = fixture.registry(Alice).address,
            destination = treasuryAddress,
            amount = Amount(1500L),
            dataUpdateRef = updateHash
          )
        )

        signedFeeTx <- IO.pure(???) // fixture.registry(Alice).signFeeTransaction(feeTransaction)

        // Fee config with required = false (optional)
        feeConfig <- IO.pure(???) // FeeConfig(enabled = true, required = false, minimumAmount = 1000L, treasuryAddress)

        _      <- IO.pure(???) // fixture.l1Context.setSnapshotFeeTransactions(Map(updateHash -> signedFeeTx))
        result <- IO.pure(???) // FeeValidator.validateFee(update, feeConfig)

      } yield expect(result.isValid)
    }
  }

  // Test Group D: SDK functionality

  test("SDK: createFeeTransaction() produces valid signed transaction") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        update <- IO.pure(
          OttochainMessage(
            parentReference = UpdateReference.empty,
            operation = "test",
            data = Map("message" -> "sdk test")
          )
        )

        updateHash      <- (update: OttochainMessage).computeDigest
        treasuryAddress <- IO.pure(fixture.registry(Charlie).address)
        payerAddress    <- IO.pure(fixture.registry(Alice).address)

        // THIS WILL FAIL because SDK method doesn't exist yet
        signedFeeTx <- IO.pure(???) /*
        SDKFeeTransaction.create(
          source = payerAddress,
          destination = treasuryAddress,
          amount = 2000L,
          dataUpdateRef = updateHash,
          privateKey = fixture.registry(Alice).keyPair.getPrivate
        )
         */

        // Verify signature is valid
        isValidSig <- IO.pure(???) // signedFeeTx.proofs.head.verify(signedFeeTx.value)

        // Verify structure is correct
        feeTx <- IO.pure(???) // signedFeeTx.value

      } yield expect(isValidSig) and
      expect(feeTx.source == payerAddress) and
      expect(feeTx.destination == treasuryAddress) and
      expect(feeTx.amount.value == 2000L) and
      expect(feeTx.dataUpdateRef == updateHash)
    }
  }

  test("SDK: postDataWithFee() sends correct JSON shape") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        update <- IO.pure(
          OttochainMessage(
            parentReference = UpdateReference.empty,
            operation = "test",
            data = Map("message" -> "sdk post test")
          )
        )

        // Sign the update
        signedUpdate <- fixture.registry(Alice).proof(update).map { proof =>
          Signed(update, SortedSet(proof))
        }

        updateHash      <- (update: OttochainMessage).computeDigest
        treasuryAddress <- IO.pure(fixture.registry(Charlie).address)

        feeTransaction <- IO.pure(
          FeeTransaction(
            source = fixture.registry(Alice).address,
            destination = treasuryAddress,
            amount = Amount(1500L),
            dataUpdateRef = updateHash
          )
        )

        signedFeeTx <- fixture
          .registry(Alice)
          .proof(feeTransaction.asInstanceOf[OttochainMessage])
          .map(proof => Signed(feeTransaction, SortedSet(proof)))

        // THIS WILL FAIL because SDK client and method don't exist yet
        response <- IO.pure(???) /*
        DataL1Client.postDataWithFee(
          signedData = signedUpdate,
          feeTransaction = signedFeeTx
        )
         */

      } yield expect(response.status == 200) // Should be HTTP 200 when posted successfully
    }
  }
}
