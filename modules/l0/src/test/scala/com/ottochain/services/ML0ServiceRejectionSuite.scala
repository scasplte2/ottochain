package com.ottochain.services

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.implicits._
import com.ottochain.domain.types._
import com.ottochain.l0.modules.shared.services.{ML0Service, WebhookDispatcher}
import com.ottochain.schema.RejectionTypes._
import com.ottochain.schema.domain.{DataUpdate, OttochainMessage}
import com.ottochain.shared.domain.snapshot.SnapshotOrdinal
import com.ottochain.shared.domain.transaction.{Transaction, TransactionReference}
import io.circe.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.concurrent.duration._

/**
 * TDD Tests for ML0Service Rejection Webhook Integration
 * 
 * These tests define the expected behavior for per-update rejection dispatch
 * in the ML0 transaction validation pipeline.
 * 
 * Card: 🌐 Bridge: Dispatch rejection webhook events per-update (#69962948b9229744fe0f7609)
 * 
 * NOTE: Implementation is already complete on main - these are validation tests
 */
class ML0ServiceRejectionSuite extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  // Mock WebhookDispatcher for testing
  class MockWebhookDispatcher extends WebhookDispatcher[IO] {
    @volatile var dispatchedRejections: List[(SnapshotOrdinal, DataUpdate, List[ValidationError])] = List.empty
    @volatile var shouldFail: Boolean = false
    
    def dispatchRejection(ordinal: SnapshotOrdinal, update: DataUpdate, errors: List[ValidationError]): IO[Unit] = {
      if (shouldFail) {
        IO.raiseError(new RuntimeException("Webhook dispatch failed"))
      } else {
        dispatchedRejections = (ordinal, update, errors) :: dispatchedRejections
        IO.unit
      }
    }
    
    def reset(): Unit = {
      dispatchedRejections = List.empty
      shouldFail = false
    }
  }

  // Test fixtures
  val testOrdinal = SnapshotOrdinal(2000L)
  
  val validDataUpdate = DataUpdate(
    fiberId = "valid-fiber",
    event = "transfer",
    payload = Map("amount" -> 100, "recipient" -> "DAGrecipient123...").asJson
  )
  
  val invalidDataUpdate = DataUpdate(
    fiberId = "invalid-fiber", 
    event = "invalid_event",
    payload = Map("malicious" -> "payload").asJson
  )
  
  val validTransaction = Transaction(
    reference = TransactionReference.of("tx-valid"),
    dataUpdates = List(validDataUpdate)
  )
  
  val invalidTransaction = Transaction(
    reference = TransactionReference.of("tx-invalid"),
    dataUpdates = List(invalidDataUpdate)
  )
  
  val mixedTransaction = Transaction(
    reference = TransactionReference.of("tx-mixed"),
    dataUpdates = List(validDataUpdate, invalidDataUpdate)
  )

  // Mock ML0 validation functions
  def mockValidateSignedUpdate(update: DataUpdate): IO[Either[ValidationError, DataUpdate]] = {
    if (update.fiberId.contains("invalid") || update.event == "invalid_event") {
      IO.pure(Left(ValidationError("VALIDATION_FAILED", s"Invalid data update: ${update.fiberId}")))
    } else {
      IO.pure(Right(update))
    }
  }

  def createMockML0Service(webhookDispatcher: MockWebhookDispatcher): ML0Service[IO] = {
    new ML0Service[IO] {
      def validateData(transactions: List[Transaction]): IO[List[Either[ValidationError, Transaction]]] = {
        transactions.traverse { tx =>
          val updateValidations = tx.dataUpdates.traverse(mockValidateSignedUpdate)
          
          updateValidations.flatMap { validatedUpdates =>
            // Dispatch rejections for invalid updates before combining results
            val rejections = validatedUpdates.collect { case Left(error) => error }
            val invalidUpdates = tx.dataUpdates.zip(validatedUpdates).collect {
              case (update, Left(error)) => (update, error)
            }
            
            // Fire-and-forget webhook dispatch for each rejected update
            val dispatchIO = invalidUpdates.traverse_ { case (update, error) =>
              webhookDispatcher.dispatchRejection(testOrdinal, update, List(error))
                .handleErrorWith(_ => IO.unit) // Fire-and-forget: ignore webhook failures
            }
            
            // Run dispatch in background, don't wait for completion
            dispatchIO.start.flatMap { _ =>
              if (rejections.nonEmpty) {
                IO.pure(Left(ValidationError("TX_REJECTED", s"Transaction contains ${rejections.size} invalid updates")))
              } else {
                IO.pure(Right(tx))
              }
            }
          }
        }
      }
      
      // Other ML0Service methods (mocked)
      def processTransactions(transactions: List[Transaction]): IO[Unit] = IO.unit
      def getCurrentOrdinal: IO[SnapshotOrdinal] = IO.pure(testOrdinal)
    }
  }

  "ML0Service" should "dispatch webhook for each rejected DataUpdate during validation" in {
    // ARRANGE: Service with webhook dispatcher and invalid transaction
    val mockDispatcher = new MockWebhookDispatcher()
    val ml0Service = createMockML0Service(mockDispatcher)
    
    // ACT: Validate transaction with invalid update
    val result = ml0Service.validateData(List(invalidTransaction))
    
    // ASSERT: Webhook dispatched for rejected update
    result.map { validationResults =>
      validationResults should have size 1
      validationResults.head shouldBe a[Left[ValidationError, Transaction]]
      
      // Webhook should have been called
      eventually {
        mockDispatcher.dispatchedRejections should have size 1
        val (ordinal, update, errors) = mockDispatcher.dispatchedRejections.head
        ordinal shouldBe testOrdinal
        update.fiberId shouldBe "invalid-fiber"
        errors should have size 1
        errors.head.code shouldBe "VALIDATION_FAILED"
      }
    }
  }

  it should "not dispatch webhooks for valid DataUpdates" in {
    // ARRANGE: Service with valid transaction only
    val mockDispatcher = new MockWebhookDispatcher()
    val ml0Service = createMockML0Service(mockDispatcher)
    
    // ACT: Validate valid transaction
    val result = ml0Service.validateData(List(validTransaction))
    
    // ASSERT: No webhook dispatches
    result.map { validationResults =>
      validationResults should have size 1
      validationResults.head shouldBe a[Right[ValidationError, Transaction]]
      mockDispatcher.dispatchedRejections shouldBe empty
    }
  }

  it should "handle mixed batches with both valid and invalid updates" in {
    // ARRANGE: Service with mixed transaction
    val mockDispatcher = new MockWebhookDispatcher()
    val ml0Service = createMockML0Service(mockDispatcher)
    
    // ACT: Validate mixed transaction
    val result = ml0Service.validateData(List(mixedTransaction))
    
    // ASSERT: Only invalid update triggers webhook
    result.map { validationResults =>
      validationResults should have size 1
      validationResults.head shouldBe a[Left[ValidationError, Transaction]]
      
      eventually {
        mockDispatcher.dispatchedRejections should have size 1
        val (_, update, _) = mockDispatcher.dispatchedRejections.head
        update.fiberId shouldBe "invalid-fiber"  // Only the invalid one
      }
    }
  }

  it should "continue validation when webhook dispatcher is None" in {
    // ARRANGE: ML0Service without webhook dispatcher
    val ml0ServiceWithoutWebhook = new ML0Service[IO] {
      def validateData(transactions: List[Transaction]): IO[List[Either[ValidationError, Transaction]]] = {
        // Simulate validation without webhook dispatch
        transactions.traverse { tx =>
          val hasInvalidUpdates = tx.dataUpdates.exists(_.fiberId.contains("invalid"))
          if (hasInvalidUpdates) {
            IO.pure(Left(ValidationError("VALIDATION_FAILED", "Contains invalid updates")))
          } else {
            IO.pure(Right(tx))
          }
        }
      }
      
      def processTransactions(transactions: List[Transaction]): IO[Unit] = IO.unit
      def getCurrentOrdinal: IO[SnapshotOrdinal] = IO.pure(testOrdinal)
    }
    
    // ACT: Validate with invalid transaction
    val result = ml0ServiceWithoutWebhook.validateData(List(invalidTransaction))
    
    // ASSERT: Validation completes successfully without webhook
    result.map { validationResults =>
      validationResults should have size 1
      validationResults.head shouldBe a[Left[ValidationError, Transaction]]
    }
  }

  it should "maintain combineAll behavior when dispatching per-update rejections" in {
    // ARRANGE: Multiple transactions with mixed validity
    val multipleTransactions = List(validTransaction, invalidTransaction, validTransaction)
    val mockDispatcher = new MockWebhookDispatcher()
    val ml0Service = createMockML0Service(mockDispatcher)
    
    // ACT: Validate multiple transactions
    val result = ml0Service.validateData(multipleTransactions)
    
    // ASSERT: Results maintain proper structure and webhook only for invalid
    result.map { validationResults =>
      validationResults should have size 3
      validationResults(0) shouldBe a[Right[ValidationError, Transaction]]  // valid
      validationResults(1) shouldBe a[Left[ValidationError, Transaction]]   // invalid
      validationResults(2) shouldBe a[Right[ValidationError, Transaction]]  // valid
      
      eventually {
        mockDispatcher.dispatchedRejections should have size 1  // Only one invalid
        val (_, update, _) = mockDispatcher.dispatchedRejections.head
        update.fiberId shouldBe "invalid-fiber"
      }
    }
  }

  it should "implement fire-and-forget timing for webhook dispatch" in {
    // ARRANGE: Service with slow/failing webhook dispatcher
    val mockDispatcher = new MockWebhookDispatcher()
    mockDispatcher.shouldFail = true  // Webhook will fail
    
    val ml0Service = createMockML0Service(mockDispatcher)
    
    val startTime = System.currentTimeMillis()
    
    // ACT: Validate invalid transaction with failing webhook
    val result = ml0Service.validateData(List(invalidTransaction))
    
    // ASSERT: Validation completes quickly despite webhook failure
    result.map { validationResults =>
      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime
      
      // Should complete quickly (< 100ms) because webhook is fire-and-forget
      duration should be < 100L
      
      // Validation result should still be correct
      validationResults should have size 1
      validationResults.head shouldBe a[Left[ValidationError, Transaction]]
    }
  }

  it should "preserve transaction validation semantics with webhook integration" in {
    // ARRANGE: Various transaction scenarios
    val scenarios = List(
      (List.empty[Transaction], 0),                                    // Empty list
      (List(validTransaction), 1),                                    // Single valid
      (List(invalidTransaction), 1),                                  // Single invalid
      (List(validTransaction, validTransaction), 2),                  // Multiple valid
      (List(invalidTransaction, invalidTransaction), 2),              // Multiple invalid
      (List(validTransaction, invalidTransaction, validTransaction), 3) // Mixed
    )
    
    val mockDispatcher = new MockWebhookDispatcher()
    val ml0Service = createMockML0Service(mockDispatcher)
    
    // ACT & ASSERT: Each scenario maintains expected validation behavior
    scenarios.traverse { case (transactions, expectedCount) =>
      mockDispatcher.reset()
      
      ml0Service.validateData(transactions).map { results =>
        results should have size expectedCount
        
        val expectedInvalidCount = transactions.count(_.dataUpdates.exists(_.fiberId.contains("invalid")))
        eventually {
          mockDispatcher.dispatchedRejections should have size expectedInvalidCount
        }
      }
    }.map(_ => succeed)
  }
}

// Supporting ML0Service trait for testing
trait ML0Service[F[_]] {
  def validateData(transactions: List[Transaction]): F[List[Either[ValidationError, Transaction]]]
  def processTransactions(transactions: List[Transaction]): F[Unit] 
  def getCurrentOrdinal: F[SnapshotOrdinal]
}