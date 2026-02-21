package com.ottochain.integration

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.ottochain.e2e.helpers.{IndexerHelpers, MetagraphHelpers, WebhookHelpers}
import com.ottochain.schema.RejectionTypes._
import com.ottochain.schema.domain.{DataUpdate, Transaction}
import com.ottochain.shared.domain.snapshot.SnapshotOrdinal
import io.circe.syntax._
import org.http4s.client.Client
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * TDD End-to-End Integration Tests for Rejection Webhook System
 * 
 * These tests validate the complete rejection notification flow:
 * ML0 rejection → Webhook dispatch → Bridge → Indexer → API
 * 
 * Card: 🌐 Bridge: Dispatch rejection webhook events per-update (#69962948b9229744fe0f7609)
 * 
 * NOTE: Implementation is already complete on main - these are validation tests
 */
class RejectionWebhookE2ESuite 
  extends AsyncFlatSpec 
  with AsyncIOSpec 
  with Matchers 
  with MetagraphHelpers 
  with IndexerHelpers 
  with WebhookHelpers {

  // Test configuration
  val testTimeout = 30.seconds
  val metagraphPort = 4000
  val indexerPort = 3031
  val bridgePort = 3030
  
  // Test fixtures
  val invalidDataUpdate = DataUpdate(
    fiberId = "e2e-invalid-fiber",
    event = "malicious_event",
    payload = Map("exploit" -> "attempt", "amount" -> -1000).asJson
  )
  
  val validDataUpdate = DataUpdate(
    fiberId = "e2e-valid-fiber", 
    event = "transfer",
    payload = Map("amount" -> 100, "recipient" -> "DAGrecipient123...").asJson
  )
  
  val duplicateDataUpdate = DataUpdate(
    fiberId = "e2e-duplicate-fiber",
    event = "duplicate_event", 
    payload = Map("same" -> "content").asJson
  )

  "Rejection Webhook E2E Flow" should "propagate invalid transaction rejection to indexer" in {
    // ARRANGE: Invalid transaction that will be rejected by ML0
    val invalidTransaction = Transaction.create(List(invalidDataUpdate))
    
    // Clear any existing rejection history
    clearRejectionHistory().flatMap { _ =>
      
      // ACT: Submit invalid transaction to metagraph
      submitTransactionToMetagraph(invalidTransaction, metagraphPort).flatMap { submissionResult =>
        
        // Transaction should be rejected at ML0 level
        submissionResult.isRejected shouldBe true
        
        // Wait for webhook to propagate to bridge and then to indexer
        waitForRejectionInIndexer(
          fiberId = invalidDataUpdate.fiberId,
          timeout = testTimeout
        ).flatMap { rejectionRecord =>
          
          // ASSERT: Rejection appears in indexer with correct details
          IO {
            rejectionRecord shouldBe defined
            val rejection = rejectionRecord.get
            
            rejection.fiberId shouldBe "e2e-invalid-fiber"
            rejection.errorCode should not be empty
            rejection.reason should include("malicious_event")
            rejection.ordinal should be > 0L
            rejection.updateHash should startWith("sha256:")
            rejection.signers should not be empty
            rejection.createdAt should not be null
          }
        }
      }
    }
  }

  it should "prevent duplicate rejection entries for same updateHash within ordinal" in {
    // ARRANGE: Same invalid DataUpdate submitted multiple times
    val duplicateTransaction1 = Transaction.create(List(duplicateDataUpdate))
    val duplicateTransaction2 = Transaction.create(List(duplicateDataUpdate.copy())) // Identical content
    
    clearRejectionHistory().flatMap { _ =>
      
      // ACT: Submit identical invalid transactions rapidly  
      val submission = for {
        result1 <- submitTransactionToMetagraph(duplicateTransaction1, metagraphPort)
        result2 <- submitTransactionToMetagraph(duplicateTransaction2, metagraphPort)
      } yield (result1, result2)
      
      submission.flatMap { case (result1, result2) =>
        // Both should be rejected
        result1.isRejected shouldBe true
        result2.isRejected shouldBe true
        
        // Wait for webhook processing
        IO.sleep(5.seconds).flatMap { _ =>
          
          // Check rejection count in indexer
          getRejectionsByFiberId(duplicateDataUpdate.fiberId).flatMap { rejections =>
            IO {
              // Should only have ONE rejection entry due to deduplication
              rejections should have size 1
              
              val rejection = rejections.head
              rejection.fiberId shouldBe "e2e-duplicate-fiber" 
              rejection.updateHash should not be empty
            }
          }
        }
      }
    }
  }

  it should "preserve rejection history across ordinal boundaries" in {
    // ARRANGE: Same DataUpdate content at different ordinals
    val historicalDataUpdate = DataUpdate(
      fiberId = "e2e-historical-fiber",
      event = "recurring_invalid",
      payload = Map("persistent" -> "problem").asJson  
    )
    
    clearRejectionHistory().flatMap { _ =>
      
      // ACT: Submit same invalid content at different times (different ordinals)
      val firstSubmission = Transaction.create(List(historicalDataUpdate))
      
      submitTransactionToMetagraph(firstSubmission, metagraphPort).flatMap { firstResult =>
        firstResult.isRejected shouldBe true
        
        // Wait for ordinal to advance, then submit again
        waitForOrdinalAdvancement(currentOrdinal = firstResult.ordinal).flatMap { _ =>
          
          val secondSubmission = Transaction.create(List(historicalDataUpdate))
          submitTransactionToMetagraph(secondSubmission, metagraphPort).flatMap { secondResult =>
            secondResult.isRejected shouldBe true
            secondResult.ordinal should be > firstResult.ordinal
            
            // Wait for webhook processing
            IO.sleep(5.seconds).flatMap { _ =>
              
              // Check that BOTH rejections are preserved
              getRejectionsByFiberId(historicalDataUpdate.fiberId).flatMap { rejections =>
                IO {
                  // Should have TWO rejection entries (different ordinals)
                  rejections should have size 2
                  
                  val ordinals = rejections.map(_.ordinal).sorted
                  ordinals(0) shouldBe firstResult.ordinal
                  ordinals(1) shouldBe secondResult.ordinal
                  
                  // Same updateHash but different ordinals preserved
                  val updateHashes = rejections.map(_.updateHash)
                  updateHashes.toSet should have size 1  // Same hash
                }
              }
            }
          }
        }
      }
    }
  }

  it should "handle webhook endpoint failures gracefully without blocking ML0" in {
    // ARRANGE: Configure webhook to failing endpoint
    val originalWebhookUrl = sys.env.get("WEBHOOK_URL")
    
    configureFailingWebhook().flatMap { _ =>
      
      // Submit invalid transaction that should trigger webhook
      val invalidTransaction = Transaction.create(List(invalidDataUpdate))
      
      val startTime = System.currentTimeMillis()
      
      // ACT: Submit transaction with failing webhook
      submitTransactionToMetagraph(invalidTransaction, metagraphPort).flatMap { result =>
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        // ASSERT: Transaction processing not significantly delayed by webhook failure
        IO {
          duration should be < 5000L  // Should complete within 5 seconds
          result.isRejected shouldBe true  // ML0 validation still works
        }.flatMap { _ =>
          
          // Restore original webhook configuration
          restoreWebhookConfig(originalWebhookUrl).map { _ =>
            succeed
          }
        }
      }
    }
  }

  it should "support querying rejection history via indexer API" in {
    // ARRANGE: Multiple different invalid transactions
    val rejectionTestCases = List(
      DataUpdate("query-test-fiber-1", "invalid_event_1", Map("error" -> "type1").asJson),
      DataUpdate("query-test-fiber-2", "invalid_event_2", Map("error" -> "type2").asJson),
      DataUpdate("query-test-fiber-3", "invalid_event_3", Map("error" -> "type3").asJson)
    )
    
    clearRejectionHistory().flatMap { _ =>
      
      // Submit all invalid transactions
      val submissions = rejectionTestCases.traverse { dataUpdate =>
        submitTransactionToMetagraph(Transaction.create(List(dataUpdate)), metagraphPort)
      }
      
      submissions.flatMap { results =>
        results.foreach(_.isRejected shouldBe true)
        
        // Wait for webhook processing
        IO.sleep(5.seconds).flatMap { _ =>
          
          // ACT: Query rejections via different API endpoints
          val apiTests = for {
            // Test: Get all rejections
            allRejections <- getAllRejections()
            
            // Test: Get rejections by specific fiberId
            fiber1Rejections <- getRejectionsByFiberId("query-test-fiber-1")
            
            // Test: Get rejections by ordinal range
            ordinalRangeRejections <- getRejectionsByOrdinalRange(
              fromOrdinal = results.map(_.ordinal).min,
              toOrdinal = results.map(_.ordinal).max
            )
            
            // Test: Get rejections with pagination
            paginatedRejections <- getRejectionsPaginated(limit = 2, offset = 0)
            
          } yield (allRejections, fiber1Rejections, ordinalRangeRejections, paginatedRejections)
          
          apiTests.flatMap { case (all, byFiber, byOrdinal, paginated) =>
            IO {
              // ASSERT: API endpoints return expected data
              all should have size >= 3
              byFiber should have size 1
              byFiber.head.fiberId shouldBe "query-test-fiber-1"
              
              byOrdinal should have size >= 3
              paginated should have size 2  // Respects limit
              
              // Verify rejection payload structure
              val sampleRejection = all.head
              sampleRejection.fiberId should not be empty
              sampleRejection.updateHash should startWith("sha256:")
              sampleRejection.errorCode should not be empty
              sampleRejection.reason should not be empty
              sampleRejection.ordinal should be > 0L
              sampleRejection.signers should not be empty
            }
          }
        }
      }
    }
  }
}

// Helper traits for E2E testing
trait MetagraphHelpers {
  def submitTransactionToMetagraph(tx: Transaction, port: Int): IO[TransactionSubmissionResult]
  def waitForOrdinalAdvancement(currentOrdinal: Long): IO[Unit]
}

trait IndexerHelpers {
  def waitForRejectionInIndexer(fiberId: String, timeout: FiniteDuration): IO[Option[RejectionRecord]]
  def clearRejectionHistory(): IO[Unit]
  def getRejectionsByFiberId(fiberId: String): IO[List[RejectionRecord]]
  def getRejectionsByOrdinalRange(fromOrdinal: Long, toOrdinal: Long): IO[List[RejectionRecord]]
  def getAllRejections(): IO[List[RejectionRecord]]
  def getRejectionsPaginated(limit: Int, offset: Int): IO[List[RejectionRecord]]
}

trait WebhookHelpers {
  def configureFailingWebhook(): IO[Unit]
  def restoreWebhookConfig(originalUrl: Option[String]): IO[Unit]
}

// Test result types
case class TransactionSubmissionResult(
  isRejected: Boolean,
  ordinal: Long,
  transactionHash: String,
  errors: List[String] = List.empty
)

case class RejectionRecord(
  fiberId: String,
  updateHash: String,
  ordinal: Long,
  errorCode: String,
  reason: String,
  signers: List[String],
  createdAt: java.time.Instant
)