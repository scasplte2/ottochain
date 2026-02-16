package xyz.kd5ujc.shared_data.delegation

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import io.constellationnetwork.schema.address.Address
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import scala.concurrent.duration._

class BatchOperationsSpec extends AsyncWordSpec with AsyncIOSpec with Matchers {

  def setupTestEnvironment: Resource[IO, (BatchProcessor, SessionKeyValidatorImpl, GasMonitorImpl)] =
    Resource.eval {
      for {
        signatureAggregator <- IO.pure(new BLSSignatureAggregator)
        sessionKeyValidator <- IO.pure(new SessionKeyValidatorImpl)
        gasMonitor          <- IO.pure(new GasMonitorImpl)
        batchProcessor      <- IO.pure(new BatchProcessor(signatureAggregator, sessionKeyValidator, gasMonitor))
      } yield (batchProcessor, sessionKeyValidator, gasMonitor)
    }

  def createTestSessionKey(keyId: String = "test-key-1"): SessionKey =
    SessionKey(
      keyId = keyId,
      publicKey = s"pubkey-$keyId",
      owner = Address("test-owner-address"),
      permissions = DelegationPermissions(
        contracts = Set("market", "governance"),
        operations = Set("transfer", "update_state", "batch_update"),
        limits = OperationLimits(
          maxAmount = Some(BigDecimal(1000)),
          timeWindowHours = 24,
          maxOperationsPerWindow = 100,
          gasLimitPerOperation = 500000L
        )
      ),
      expiryTime = Instant.now().plusSeconds(3600), // 1 hour from now
      createdAt = Instant.now()
    )

  def createTestBatchOperation(
    operationId:   String,
    sessionKeyId:  String = "test-key-1",
    operationType: String = "transfer",
    gasEstimate:   Long = 100000L
  ): BatchOperation =
    BatchOperation(
      operationId = operationId,
      sessionKeyId = sessionKeyId,
      operationType = operationType,
      payload = Map(
        "target" -> "test-target",
        "amount" -> 50.0
      ),
      gasEstimate = gasEstimate,
      timestamp = Instant.now()
    )

  def createTestBatchRequest(
    batchId:     String = "batch-001",
    operations:  List[BatchOperation] = List.empty,
    maxGasLimit: Long = 2000000L
  ): BatchRequest =
    BatchRequest(
      batchId = batchId,
      operations = operations,
      aggregatedSignature = None,
      submitter = Address("test-submitter"),
      maxGasLimit = maxGasLimit,
      deadline = Instant.now().plusSeconds(300) // 5 minutes from now
    )

  "BatchOperations" should {

    "successfully process a simple batch request" in {
      setupTestEnvironment.use { case (batchProcessor, sessionValidator, gasMonitor) =>
        for {
          sessionKey <- IO.pure(createTestSessionKey())
          _          <- sessionValidator.registerSessionKey(sessionKey)

          operation1 <- IO.pure(createTestBatchOperation("op-1"))
          operation2 <- IO.pure(createTestBatchOperation("op-2"))

          batchRequest <- IO.pure(
            createTestBatchRequest(
              operations = List(operation1, operation2)
            )
          )

          result <- batchProcessor.submitBatch(batchRequest)
        } yield {
          result should be a 'right
          result.right.get should equal("batch-001")
        }
      }
    }

    "achieve gas optimization savings target (<20% overhead)" in {
      setupTestEnvironment.use { case (batchProcessor, sessionValidator, gasMonitor) =>
        for {
          sessionKey <- IO.pure(createTestSessionKey())
          _          <- sessionValidator.registerSessionKey(sessionKey)

          // Create 10 operations for better optimization
          operations <- IO.pure((1 to 10).map(i => createTestBatchOperation(s"op-$i", gasEstimate = 100000L)).toList)

          batchRequest <- IO.pure(
            createTestBatchRequest(
              batchId = "optimization-test",
              operations = operations,
              maxGasLimit = 2000000L
            )
          )

          _      <- batchProcessor.submitBatch(batchRequest)
          result <- batchProcessor.getBatchResult("optimization-test")
        } yield {
          result should be(defined)
          val batchResult = result.get
          batchResult.gasOptimizationSavings should be > 20.0 // Should achieve >20% savings
          batchResult.gasOptimizationSavings should be < 70.0 // Sanity check - shouldn't be too high
        }
      }
    }

    "coalesce repeated operations for state savings" in {
      setupTestEnvironment.use { case (batchProcessor, sessionValidator, gasMonitor) =>
        for {
          sessionKey <- IO.pure(createTestSessionKey())
          _          <- sessionValidator.registerSessionKey(sessionKey)

          // Create multiple operations of same type to same target
          operations <- IO.pure(
            List(
              createTestBatchOperation("op-1", operationType = "transfer"),
              createTestBatchOperation("op-2", operationType = "transfer"), // Same type, should coalesce
              createTestBatchOperation("op-3", operationType = "update_state") // Different type
            )
          )

          batchRequest <- IO.pure(
            createTestBatchRequest(
              batchId = "coalesce-test",
              operations = operations
            )
          )

          _      <- batchProcessor.submitBatch(batchRequest)
          result <- batchProcessor.getBatchResult("coalesce-test")
        } yield {
          result should be(defined)
          val batchResult = result.get
          // Should have 2 operations after coalescing: 1 coalesced transfer + 1 update_state
          batchResult.executedOperations.size should be <= 2
          batchResult.executedOperations should contain("update_state")
        }
      }
    }

    "reject batch with invalid session key" in {
      setupTestEnvironment.use { case (batchProcessor, sessionValidator, gasMonitor) =>
        for {
          operation    <- IO.pure(createTestBatchOperation("op-1", sessionKeyId = "invalid-key"))
          batchRequest <- IO.pure(createTestBatchRequest(operations = List(operation)))

          result <- batchProcessor.submitBatch(batchRequest)
        } yield {
          result should be a 'left
          result.left.get should include("Invalid session key")
        }
      }
    }

    "reject batch exceeding gas limits" in {
      setupTestEnvironment.use { case (batchProcessor, sessionValidator, gasMonitor) =>
        for {
          sessionKey <- IO.pure(createTestSessionKey())
          _          <- sessionValidator.registerSessionKey(sessionKey)

          operation <- IO.pure(createTestBatchOperation("op-1", gasEstimate = 1000000L))
          batchRequest <- IO.pure(
            createTestBatchRequest(
              operations = List(operation),
              maxGasLimit = 500000L // Less than operation gas estimate
            )
          )

          result <- batchProcessor.submitBatch(batchRequest)
        } yield {
          result should be a 'left
          result.left.get should include("Total gas estimate")
          result.left.get should include("exceeds limit")
        }
      }
    }

    "reject expired batch request" in {
      setupTestEnvironment.use { case (batchProcessor, sessionValidator, gasMonitor) =>
        for {
          sessionKey <- IO.pure(createTestSessionKey())
          _          <- sessionValidator.registerSessionKey(sessionKey)

          operation <- IO.pure(createTestBatchOperation("op-1"))
          expiredBatchRequest <- IO.pure(
            createTestBatchRequest(operations = List(operation))
              .copy(deadline = Instant.now().minusSeconds(60))
          ) // Deadline in the past

          result <- batchProcessor.submitBatch(expiredBatchRequest)
        } yield {
          result should be a 'left
          result.left.get should include("deadline has passed")
        }
      }
    }

    "enforce operation permissions" in {
      setupTestEnvironment.use { case (batchProcessor, sessionValidator, gasMonitor) =>
        for {
          sessionKey <- IO.pure(createTestSessionKey())
          _          <- sessionValidator.registerSessionKey(sessionKey)

          // Create operation with type not in permissions
          operation <- IO.pure(
            createTestBatchOperation(
              "op-1",
              operationType = "unauthorized_operation" // Not in session key permissions
            )
          )
          batchRequest <- IO.pure(createTestBatchRequest(operations = List(operation)))

          result <- batchProcessor.submitBatch(batchRequest)
        } yield {
          result should be a 'left
          result.left.get should include("not permitted for session key")
        }
      }
    }

    "enforce amount limits" in {
      setupTestEnvironment.use { case (batchProcessor, sessionValidator, gasMonitor) =>
        for {
          sessionKey <- IO.pure(createTestSessionKey()) // Max amount is 1000
          _          <- sessionValidator.registerSessionKey(sessionKey)

          // Create operation exceeding amount limit
          operation <- IO.pure(
            createTestBatchOperation("op-1").copy(
              payload = Map("target" -> "test-target", "amount" -> 2000.0) // Exceeds 1000 limit
            )
          )
          batchRequest <- IO.pure(createTestBatchRequest(operations = List(operation)))

          result <- batchProcessor.submitBatch(batchRequest)
        } yield {
          result should be a 'left
          result.left.get should include("not permitted for session key")
        }
      }
    }

    "process signature aggregation" in {
      setupTestEnvironment.use { case (batchProcessor, sessionValidator, gasMonitor) =>
        for {
          signatures <- IO.pure(List("sig1", "sig2", "sig3"))
          aggregator <- IO.pure(new BLSSignatureAggregator)

          aggregated <- aggregator.aggregateSignatures(signatures)
          isValid <- aggregator.verifyAggregatedSignature(
            aggregated,
            List("msg1", "msg2", "msg3"),
            List("pk1", "pk2", "pk3")
          )
        } yield {
          aggregated should include(":aggregated:")
          isValid should be(true)
        }
      }
    }
  }

  "SessionKeyValidator" should {

    "validate active session keys" in {
      val validator = new SessionKeyValidatorImpl

      for {
        sessionKey <- IO.pure(createTestSessionKey())
        _          <- validator.registerSessionKey(sessionKey)

        isValid  <- validator.validateSessionKey(sessionKey.keyId)
        isActive <- validator.isSessionKeyActive(sessionKey.keyId)
      } yield {
        isValid should be(true)
        isActive should be(true)
      }
    }

    "reject expired session keys" in {
      val validator = new SessionKeyValidatorImpl

      for {
        expiredKey <- IO.pure(
          createTestSessionKey().copy(
            expiryTime = Instant.now().minusSeconds(3600) // Expired 1 hour ago
          )
        )
        _ <- validator.registerSessionKey(expiredKey)

        isValid <- validator.validateSessionKey(expiredKey.keyId)
      } yield isValid should be(false)
    }

    "reject revoked session keys" in {
      val validator = new SessionKeyValidatorImpl

      for {
        sessionKey <- IO.pure(createTestSessionKey())
        _          <- validator.registerSessionKey(sessionKey)
        _          <- validator.revokeSessionKey(sessionKey.keyId)

        isValid <- validator.validateSessionKey(sessionKey.keyId)
      } yield isValid should be(false)
    }
  }

  "AttackPrevention" should {

    "reject oversized batches" in {
      val oversizedOperations = (1 to 150).map(i => createTestBatchOperation(s"op-$i")).toList

      val request = createTestBatchRequest(operations = oversizedOperations)
      val result = AttackPrevention.validateBatchSecurity(request)

      result should be a 'left
      result.left.get should include("exceeds maximum")
    }

    "reject batches with excessive gas limits" in {
      val operation = createTestBatchOperation("op-1")
      val request = createTestBatchRequest(
        operations = List(operation),
        maxGasLimit = 20000000L // Exceeds 10M limit
      )

      val result = AttackPrevention.validateBatchSecurity(request)

      result should be a 'left
      result.left.get should include("exceeds maximum")
    }

    "reject operations exceeding individual gas limits" in {
      val operation = createTestBatchOperation(
        "op-1",
        operationType = "transfer",
        gasEstimate = 200000L // Exceeds 100K limit for transfers
      )
      val request = createTestBatchRequest(operations = List(operation))

      val result = AttackPrevention.validateBatchSecurity(request)

      result should be a 'left
      result.left.get should include("exceed gas limits")
    }

    "detect suspicious patterns" in {
      val suspiciousOperations =
        (1 to 60).map(i => createTestBatchOperation(s"op-$i", operationType = "transfer")).toList

      val request = createTestBatchRequest(operations = suspiciousOperations)
      val result = AttackPrevention.validateBatchSecurity(request)

      result should be a 'left
      result.left.get should include("Suspicious pattern")
    }
  }

  "GasMonitor" should {

    "track gas usage correctly" in {
      val gasMonitor = new GasMonitorImpl

      for {
        _        <- gasMonitor.startBatchMonitoring("batch-1", 1000000L)
        _        <- gasMonitor.recordGasUsage("batch-1", 250000L)
        _        <- gasMonitor.recordGasUsage("batch-1", 300000L)
        totalGas <- gasMonitor.getGasUsed("batch-1")
        _        <- gasMonitor.stopBatchMonitoring("batch-1")
      } yield totalGas should equal(550000L)
    }

    "enforce gas baselines correctly" in {
      val gasMonitor = new GasMonitorImpl

      for {
        // Test within baseline (should pass)
        withinBaseline <- gasMonitor.enforceGasBaseline(100000L, 4) // 4 ops * 21K + 20% = ~101K

        // Test exceeding baseline (should fail)
        exceedingBaseline <- gasMonitor.enforceGasBaseline(200000L, 4) // Way over baseline
      } yield {
        withinBaseline should be(true)
        exceedingBaseline should be(false)
      }
    }
  }
}
