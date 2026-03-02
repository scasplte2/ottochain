package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.syntax.all._
import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.dataApplication.{
  DataApplicationValidationErrorOr,
  FeeTransaction
}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates._
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

/**
 * TDD tests for OttoChain fee processing functionality.
 *
 * These tests are written to FAIL initially, implementing the behavior defined in
 * docs/specs/fee-schedule-spec.md. They will pass once the following components
 * are implemented:
 * 
 * - FeeConfig case class and factory methods
 * - FeeEstimator with estimateFee method
 * - FeeValidator with validateFee method  
 * - Config parsing for fee parameters
 * - Payload size calculation and surcharge logic
 * 
 * Spec reference: Fee-based data transactions (Trello: 69a254593c794d44d09d247e)
 */
object FeeProcessingSuite extends SimpleIOSuite {

  // ===== Test Group A: Fee Estimation Logic =====

  test("CreateStateMachine operation → base fee 1,000,000 datum") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        createSM <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "test-fiber-1",
            initialState = Map("counter" -> 0),
            definition = Map("type" -> "counter")
          )
        )

        // THIS WILL FAIL - FeeEstimator doesn't exist yet
        estimatedFee <- IO.pure(???) // FeeEstimator.estimateFee(0L)(createSM)

      } yield expect(estimatedFee == 1000000L)
    }
  }

  test("TransitionStateMachine operation → base fee 100,000 datum") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        transitionSM <- IO.pure(
          TransitionStateMachine(
            parentReference = UpdateReference.empty,
            id = "existing-fiber",
            event = Map("action" -> "increment")
          )
        )

        // THIS WILL FAIL - FeeEstimator doesn't exist yet
        estimatedFee <- IO.pure(???) // FeeEstimator.estimateFee(0L)(transitionSM)

      } yield expect(estimatedFee == 100000L)
    }
  }

  test("ArchiveStateMachine operation → fee is 0 (free)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        archiveSM <- IO.pure(
          ArchiveStateMachine(
            parentReference = UpdateReference.empty,
            id = "fiber-to-cleanup"
          )
        )

        // THIS WILL FAIL - FeeEstimator doesn't exist yet
        estimatedFee <- IO.pure(???) // FeeEstimator.estimateFee(0L)(archiveSM)

      } yield expect(estimatedFee == 0L)
    }
  }

  test("CreateScript operation → base fee 1,000,000 datum") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        createScript <- IO.pure(
          CreateScript(
            parentReference = UpdateReference.empty,
            id = "test-script-1",
            program = Map("logic" -> "simple counter program"),
            initialData = Map("value" -> 0)
          )
        )

        // THIS WILL FAIL - FeeEstimator doesn't exist yet
        estimatedFee <- IO.pure(???) // FeeEstimator.estimateFee(0L)(createScript)

      } yield expect(estimatedFee == 1000000L)
    }
  }

  test("InvokeScript operation → base fee 100,000 datum") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        invokeScript <- IO.pure(
          InvokeScript(
            parentReference = UpdateReference.empty,
            id = "existing-script",
            method = "execute",
            params = Map("input" -> "test")
          )
        )

        // THIS WILL FAIL - FeeEstimator doesn't exist yet
        estimatedFee <- IO.pure(???) // FeeEstimator.estimateFee(0L)(invokeScript)

      } yield expect(estimatedFee == 100000L)
    }
  }

  // ===== Test Group B: Payload Size Surcharge =====

  test("small payload (≤ 4KB) → no surcharge applied") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        // Small CreateStateMachine under 4KB
        smallPayload <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "small",
            initialState = Map("data" -> "small payload"),
            definition = Map("type" -> "simple")
          )
        )

        // THIS WILL FAIL - payloadFee calculation doesn't exist yet
        estimatedFee <- IO.pure(???) // FeeEstimator.estimateFee(0L)(smallPayload)

        // Should be exactly base fee (1M datum) with no surcharge
      } yield expect(estimatedFee == 1000000L)
    }
  }

  test("large payload (> 4KB) → surcharge of 10,000 datum per KB") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        // Create payload that's definitely over 4KB
        largeData <- IO.pure("x" * 6000) // ~6KB of data
        
        largePayload <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "large-payload-test",
            initialState = Map("largeData" -> largeData),
            definition = Map("type" -> "large", "description" -> "test with large payload")
          )
        )

        // THIS WILL FAIL - payload size calculation doesn't exist yet
        estimatedFee <- IO.pure(???) /*
        FeeEstimator.estimateFee(0L)(largePayload)
        Expected: base (1M) + surcharge for ~2KB over baseline
        Surcharge = ceil(2048/1024) * 10000 = 2 * 10000 = 20000
        Total = 1000000 + 20000 = 1020000
        */

      } yield expect(estimatedFee > 1000000L && estimatedFee <= 1100000L) // Allow some encoding overhead
    }
  }

  // ===== Test Group C: Fee Configuration =====

  test("FeeConfig.disabled → fees.enabled = false") {
    for {
      // THIS WILL FAIL - FeeConfig case class doesn't exist yet
      config <- IO.pure(???) // FeeConfig.disabled

    } yield expect(config.enabled == false) &&
    expect(config.treasuryAddress.isEmpty)
  }

  test("FeeConfig.enabled → requires treasury address") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      for {
        treasuryAddr <- IO.pure(fixture.registry(Charlie).address)
        
        // THIS WILL FAIL - FeeConfig.enabled factory doesn't exist yet
        config <- IO.pure(???) /*
        FeeConfig.enabled(
          treasuryAddress = treasuryAddr,
          createStateMachineFee = 1000000L,
          transitionFee = 100000L,
          createScriptFee = 1000000L,
          invokeFee = 100000L,
          archiveFee = 0L,
          baselineSizeBytes = 4096,
          perKbFee = 10000L
        )
        */

      } yield expect(config.enabled == true) &&
      expect(config.treasuryAddress.contains(treasuryAddr))
    }
  }

  test("FeeConfig validation → missing treasury address should error") {
    for {
      // THIS WILL FAIL - FeeConfig validation doesn't exist yet
      result <- IO.pure(???) /*
      FeeConfig.fromConfig(
        enabled = true,
        treasuryAddress = None, // Missing!
        createStateMachineFee = 1000000L,
        // ... other params
      )
      */

    } yield expect(result.isLeft) && // Should be Left(error)
    expect(result.left.toOption.exists(_.contains("treasury")))
  }

  // ===== Test Group D: Fee Validation - Grace Period =====

  test("grace period (enabled=false) → accept missing fees with warning") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        update <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "grace-period-test",
            initialState = Map("test" -> true),
            definition = Map("type" -> "test")
          )
        )

        signedUpdate <- fixture.registry(Alice).proof(update).map { proof =>
          Signed(update, SortedSet(proof))
        }

        config <- IO.pure(???) // FeeConfig.disabled

        // No fee provided (maybeFee = None)
        // THIS WILL FAIL - FeeValidator doesn't exist yet
        result <- IO.pure(???) // FeeValidator.validateFee(0L)(signedUpdate, None)(config)

      } yield expect(result.isRight) // Should accept during grace period
    }
  }

  test("grace period → accept provided fees with info log") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        update <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "grace-period-with-fee",
            initialState = Map("test" -> true),
            definition = Map("type" -> "test")
          )
        )

        signedUpdate <- fixture.registry(Alice).proof(update).map { proof =>
          Signed(update, SortedSet(proof))
        }

        // Create voluntary fee
        feeTransaction <- IO.pure(
          FeeTransaction(
            source = fixture.registry(Alice).address,
            destination = fixture.registry(Charlie).address,
            amount = Amount(1500000L),
            dataUpdateRef = ??? // update hash - THIS WILL FAIL
          )
        )

        signedFee <- fixture.registry(Alice).proof(feeTransaction).map { proof =>
          Signed(feeTransaction, SortedSet(proof))
        }

        config <- IO.pure(???) // FeeConfig.disabled

        // Fee provided voluntarily during grace period
        // THIS WILL FAIL - FeeValidator doesn't exist yet
        result <- IO.pure(???) // FeeValidator.validateFee(0L)(signedUpdate, Some(signedFee))(config)

      } yield expect(result.isRight) // Should accept voluntary fees
    }
  }

  // ===== Test Group E: Fee Validation - Enforcement Mode =====

  test("enforcement mode → missing fee rejected") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        update <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "enforcement-test",
            initialState = Map("test" -> true),
            definition = Map("type" -> "test")
          )
        )

        signedUpdate <- fixture.registry(Alice).proof(update).map { proof =>
          Signed(update, SortedSet(proof))
        }

        treasuryAddr <- IO.pure(fixture.registry(Charlie).address)
        config <- IO.pure(???) // FeeConfig.enabled(treasuryAddr, ...)

        // No fee provided (maybeFee = None)
        // THIS WILL FAIL - FeeValidator doesn't exist yet
        result <- IO.pure(???) // FeeValidator.validateFee(0L)(signedUpdate, None)(config)

      } yield expect(result.isLeft) && // Should reject
      expect(result.left.toOption.exists(_.contains("Fee required")))
    }
  }

  test("enforcement mode → insufficient fee rejected") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        update <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "insufficient-fee-test",
            initialState = Map("test" -> true),
            definition = Map("type" -> "test")
          )
        )

        signedUpdate <- fixture.registry(Alice).proof(update).map { proof =>
          Signed(update, SortedSet(proof))
        }

        // Fee that's too low (should be 1M, only pay 500K)
        feeTransaction <- IO.pure(
          FeeTransaction(
            source = fixture.registry(Alice).address,
            destination = fixture.registry(Charlie).address,
            amount = Amount(500000L), // Too low!
            dataUpdateRef = ??? // update hash - THIS WILL FAIL
          )
        )

        signedFee <- fixture.registry(Alice).proof(feeTransaction).map { proof =>
          Signed(feeTransaction, SortedSet(proof))
        }

        treasuryAddr <- IO.pure(fixture.registry(Charlie).address)
        config <- IO.pure(???) // FeeConfig.enabled(treasuryAddr, ...)

        // THIS WILL FAIL - FeeValidator doesn't exist yet
        result <- IO.pure(???) // FeeValidator.validateFee(0L)(signedUpdate, Some(signedFee))(config)

      } yield expect(result.isLeft) && // Should reject
      expect(result.left.toOption.exists(_.contains("insufficient")))
    }
  }

  test("enforcement mode → wrong destination rejected") {
    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        update <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "wrong-dest-test",
            initialState = Map("test" -> true),
            definition = Map("type" -> "test")
          )
        )

        signedUpdate <- fixture.registry(Alice).proof(update).map { proof =>
          Signed(update, SortedSet(proof))
        }

        // Fee to wrong address (Bob instead of Charlie)
        feeTransaction <- IO.pure(
          FeeTransaction(
            source = fixture.registry(Alice).address,
            destination = fixture.registry(Bob).address, // Wrong!
            amount = Amount(1500000L), // Correct amount
            dataUpdateRef = ??? // update hash - THIS WILL FAIL
          )
        )

        signedFee <- fixture.registry(Alice).proof(feeTransaction).map { proof =>
          Signed(feeTransaction, SortedSet(proof))
        }

        treasuryAddr <- IO.pure(fixture.registry(Charlie).address) // Correct treasury
        config <- IO.pure(???) // FeeConfig.enabled(treasuryAddr, ...)

        // THIS WILL FAIL - FeeValidator doesn't exist yet
        result <- IO.pure(???) // FeeValidator.validateFee(0L)(signedUpdate, Some(signedFee))(config)

      } yield expect(result.isLeft) && // Should reject
      expect(result.left.toOption.exists(_.contains("wrong destination")))
    }
  }

  test("enforcement mode → valid fee accepted") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        update <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "valid-fee-test",
            initialState = Map("test" -> true),
            definition = Map("type" -> "test")
          )
        )

        signedUpdate <- fixture.registry(Alice).proof(update).map { proof =>
          Signed(update, SortedSet(proof))
        }

        // Valid fee (correct amount, destination, reference)
        feeTransaction <- IO.pure(
          FeeTransaction(
            source = fixture.registry(Alice).address,
            destination = fixture.registry(Charlie).address,
            amount = Amount(1500000L), // Above required 1M
            dataUpdateRef = ??? // update hash - THIS WILL FAIL
          )
        )

        signedFee <- fixture.registry(Alice).proof(feeTransaction).map { proof =>
          Signed(feeTransaction, SortedSet(proof))
        }

        treasuryAddr <- IO.pure(fixture.registry(Charlie).address)
        config <- IO.pure(???) // FeeConfig.enabled(treasuryAddr, ...)

        // THIS WILL FAIL - FeeValidator doesn't exist yet
        result <- IO.pure(???) // FeeValidator.validateFee(0L)(signedUpdate, Some(signedFee))(config)

      } yield expect(result.isRight) // Should accept valid fee
    }
  }

  // ===== Test Group F: Fee Estimation API =====

  test("POST /data/estimate-fee → returns correct JSON response") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        update <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "api-test",
            initialState = Map("test" -> "api"),
            definition = Map("type" -> "api-test")
          )
        )

        // Serialize update for POST body
        // THIS WILL FAIL - serialization doesn't exist yet
        serializedUpdate <- IO.pure(???) // Serializer.serialize(update)

        // Mock HTTP POST to estimation endpoint
        // THIS WILL FAIL - HTTP client and endpoint don't exist yet
        response <- IO.pure(???) /*
        HttpClient.post("/data/estimate-fee", serializedUpdate)
        Expected response:
        {
          "amount": 1000000,
          "treasuryAddress": "DAG...",
          "currency": "datum"
        }
        */

      } yield expect(response.status == 200) &&
      expect(response.json.get("amount").contains(1000000)) &&
      expect(response.json.get("currency").contains("datum")) &&
      expect(response.json.get("treasuryAddress").isDefined)
    }
  }

  // ===== Test Group G: Integration Tests =====

  test("end-to-end: estimate fee → create fee transaction → validate → accept") {
    TestFixture.resource(Set(Alice, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider

      for {
        // 1. Create update
        update <- IO.pure(
          CreateStateMachine(
            parentReference = UpdateReference.empty,
            id = "e2e-test",
            initialState = Map("value" -> 42),
            definition = Map("type" -> "counter")
          )
        )

        // 2. Estimate fee - THIS WILL FAIL
        estimatedAmount <- IO.pure(???) // FeeEstimator.estimateFee(0L)(update)

        // 3. Create fee transaction - THIS WILL FAIL
        feeTransaction <- IO.pure(???) /*
        FeeTransaction(
          source = fixture.registry(Alice).address,
          destination = fixture.registry(Charlie).address,
          amount = Amount(estimatedAmount),
          dataUpdateRef = update.computeDigest
        )
        */

        // 4. Sign both transactions
        signedUpdate <- fixture.registry(Alice).proof(update).map { proof =>
          Signed(update, SortedSet(proof))
        }

        signedFee <- fixture.registry(Alice).proof(feeTransaction).map { proof =>
          Signed(feeTransaction, SortedSet(proof))
        }

        // 5. Validate with enforcement enabled - THIS WILL FAIL
        treasuryAddr <- IO.pure(fixture.registry(Charlie).address)
        config <- IO.pure(???) // FeeConfig.enabled(treasuryAddr, ...)
        result <- IO.pure(???) // FeeValidator.validateFee(0L)(signedUpdate, Some(signedFee))(config)

      } yield expect(result.isRight) &&
      expect(estimatedAmount == 1000000L)
    }
  }
}