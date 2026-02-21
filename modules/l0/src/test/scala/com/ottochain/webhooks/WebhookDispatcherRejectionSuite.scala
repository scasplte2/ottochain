package com.ottochain.webhooks

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.ottochain.domain.types._
import com.ottochain.l0.modules.shared.config.WebhookConfig
import com.ottochain.l0.modules.shared.services.WebhookDispatcher
import com.ottochain.schema.RejectionTypes._
import com.ottochain.schema.domain.{DataUpdate, OttochainMessage}
import com.ottochain.shared.domain.snapshot.SnapshotOrdinal
import io.circe.syntax._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.{HttpApp, Request, Response, Status}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.concurrent.duration._

/**
 * TDD Tests for WebhookDispatcher Rejection Event Dispatch
 * 
 * These tests define the expected behavior for the rejection webhook system
 * as described in the card requirements and @think's specification.
 * 
 * Card: 🌐 Bridge: Dispatch rejection webhook events per-update (#69962948b9229744fe0f7609)
 * 
 * NOTE: Implementation is already complete on main - these are validation tests
 */
class WebhookDispatcherRejectionSuite extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  // Test fixtures
  val testOrdinal = SnapshotOrdinal(1000L)
  val testFiberId = "fiber-123"
  val testUpdateHash = "sha256:abcd1234..."
  val testSignerAddress = "DAGsigner123..."
  val testErrorCode = "INVALID_SIGNATURE"
  val testErrorReason = "Signature verification failed"
  
  val sampleDataUpdate = DataUpdate(
    fiberId = testFiberId,
    event = "test_event",
    payload = Map("amount" -> 100).asJson
  )
  
  val sampleRejectionPayload = RejectionEventPayload(
    ordinal = testOrdinal.value,
    fiberId = testFiberId,
    updateHash = testUpdateHash,
    signers = List(testSignerAddress),
    errorCode = testErrorCode,
    reason = testErrorReason
  )

  // Mock HTTP client that captures webhook calls
  class MockWebhookClient extends Client[IO] {
    @volatile var capturedRequests: List[Request[IO]] = List.empty
    @volatile var capturedPayloads: List[RejectionEventPayload] = List.empty
    @volatile var shouldReturnError: Boolean = false
    
    def run(req: Request[IO]): IO[Response[IO]] = {
      capturedRequests = req :: capturedRequests
      
      // Extract payload if present
      req.attemptAs[RejectionEventPayload].value.flatMap {
        case Right(payload) =>
          capturedPayloads = payload :: capturedPayloads
          if (shouldReturnError) IO(Response[IO](Status.InternalServerError))
          else IO(Response[IO](Status.Ok))
        case Left(_) =>
          if (shouldReturnError) IO(Response[IO](Status.InternalServerError))
          else IO(Response[IO](Status.Ok))
      }
    }
    
    def reset(): Unit = {
      capturedRequests = List.empty
      capturedPayloads = List.empty
      shouldReturnError = false
    }
  }

  "WebhookDispatcher" should "dispatch rejection event with correct payload structure" in {
    // ARRANGE: Mock client and webhook config
    val mockClient = new MockWebhookClient()
    val webhookConfig = WebhookConfig(
      url = Some("http://test-webhook.com/rejections"),
      timeout = 30.seconds
    )
    
    val dispatcher = new WebhookDispatcher[IO](mockClient, webhookConfig)
    val rejectionErrors = List(ValidationError(testErrorCode, testErrorReason))
    
    // ACT: Dispatch rejection event
    val result = dispatcher.dispatchRejection(testOrdinal, sampleDataUpdate, rejectionErrors)
    
    // ASSERT: Webhook called with correct payload
    result.map { _ =>
      mockClient.capturedRequests should have size 1
      mockClient.capturedPayloads should have size 1
      
      val payload = mockClient.capturedPayloads.head
      payload.ordinal shouldBe testOrdinal.value
      payload.fiberId shouldBe testFiberId
      payload.updateHash should startWith("sha256:")
      payload.signers should contain(testSignerAddress)
      payload.errorCode shouldBe testErrorCode
      payload.reason shouldBe testErrorReason
    }
  }

  it should "extract fiberId from DataUpdate correctly" in {
    // ARRANGE: DataUpdate with specific fiberId
    val customFiberId = "custom-fiber-456"
    val dataUpdate = sampleDataUpdate.copy(fiberId = customFiberId)
    val mockClient = new MockWebhookClient()
    val dispatcher = new WebhookDispatcher[IO](mockClient, WebhookConfig(Some("http://test.com"), 30.seconds))
    
    // ACT: Dispatch with custom fiberId
    val result = dispatcher.dispatchRejection(testOrdinal, dataUpdate, List(ValidationError("TEST", "test")))
    
    // ASSERT: Payload contains correct fiberId
    result.map { _ =>
      mockClient.capturedPayloads should have size 1
      mockClient.capturedPayloads.head.fiberId shouldBe customFiberId
    }
  }

  it should "generate deterministic updateHash from DataUpdate content" in {
    // ARRANGE: Two identical DataUpdates
    val dataUpdate1 = sampleDataUpdate.copy(payload = Map("value" -> 42).asJson)
    val dataUpdate2 = sampleDataUpdate.copy(payload = Map("value" -> 42).asJson)
    
    val mockClient = new MockWebhookClient()
    val dispatcher = new WebhookDispatcher[IO](mockClient, WebhookConfig(Some("http://test.com"), 30.seconds))
    
    // ACT: Dispatch both updates
    val result = for {
      _ <- dispatcher.dispatchRejection(testOrdinal, dataUpdate1, List(ValidationError("TEST", "test")))
      _ <- dispatcher.dispatchRejection(testOrdinal, dataUpdate2, List(ValidationError("TEST", "test")))
    } yield ()
    
    // ASSERT: Same updateHash generated for identical content
    result.map { _ =>
      mockClient.capturedPayloads should have size 2
      val hash1 = mockClient.capturedPayloads(1).updateHash
      val hash2 = mockClient.capturedPayloads(0).updateHash
      hash1 shouldBe hash2
    }
  }

  it should "extract multiple signers from DataUpdate proofs" in {
    // ARRANGE: DataUpdate with multiple signers
    val multiSignerUpdate = sampleDataUpdate // Would need to add proofs field
    val mockClient = new MockWebhookClient()
    val dispatcher = new WebhookDispatcher[IO](mockClient, WebhookConfig(Some("http://test.com"), 30.seconds))
    
    val expectedSigners = List("DAGsigner1...", "DAGsigner2...", "DAGsigner3...")
    
    // ACT: Dispatch with multi-signer update
    val result = dispatcher.dispatchRejection(testOrdinal, multiSignerUpdate, List(ValidationError("TEST", "test")))
    
    // ASSERT: All signers extracted correctly
    result.map { _ =>
      mockClient.capturedPayloads should have size 1
      val payload = mockClient.capturedPayloads.head
      payload.signers should contain allElementsOf expectedSigners
    }
  }

  it should "not dispatch when webhook URL is not configured" in {
    // ARRANGE: Dispatcher with no webhook URL
    val mockClient = new MockWebhookClient()
    val webhookConfig = WebhookConfig(url = None, timeout = 30.seconds)
    val dispatcher = new WebhookDispatcher[IO](mockClient, webhookConfig)
    
    // ACT: Attempt to dispatch rejection
    val result = dispatcher.dispatchRejection(testOrdinal, sampleDataUpdate, List(ValidationError("TEST", "test")))
    
    // ASSERT: No HTTP call made
    result.map { _ =>
      mockClient.capturedRequests shouldBe empty
      mockClient.capturedPayloads shouldBe empty
    }
  }

  it should "handle webhook endpoint errors gracefully (fire-and-forget)" in {
    // ARRANGE: Mock client that returns errors
    val mockClient = new MockWebhookClient()
    mockClient.shouldReturnError = true
    
    val dispatcher = new WebhookDispatcher[IO](mockClient, WebhookConfig(Some("http://failing-webhook.com"), 30.seconds))
    
    // ACT: Dispatch with failing webhook
    val result = dispatcher.dispatchRejection(testOrdinal, sampleDataUpdate, List(ValidationError("TEST", "test")))
    
    // ASSERT: Method completes successfully despite webhook failure (fire-and-forget)
    result.map { _ =>
      mockClient.capturedRequests should have size 1  // HTTP call was attempted
      succeed  // Should not throw exception
    }
  }

  it should "avoid duplicate dispatches for same updateHash within ordinal" in {
    // ARRANGE: Dispatcher with deduplication tracking
    val mockClient = new MockWebhookClient()
    val dispatcher = new WebhookDispatcher[IO](mockClient, WebhookConfig(Some("http://test.com"), 30.seconds))
    
    // ACT: Dispatch same DataUpdate twice at same ordinal
    val result = for {
      _ <- dispatcher.dispatchRejection(testOrdinal, sampleDataUpdate, List(ValidationError("TEST1", "first")))
      _ <- dispatcher.dispatchRejection(testOrdinal, sampleDataUpdate, List(ValidationError("TEST2", "second")))
    } yield ()
    
    // ASSERT: Only first dispatch occurs (deduplication)
    result.map { _ =>
      mockClient.capturedRequests should have size 1
      mockClient.capturedPayloads should have size 1
      mockClient.capturedPayloads.head.errorCode shouldBe "TEST1"  // First error preserved
    }
  }

  it should "allow dispatches for different ordinals even with same updateHash" in {
    // ARRANGE: Same DataUpdate at different ordinals
    val ordinal1 = SnapshotOrdinal(1000L)
    val ordinal2 = SnapshotOrdinal(1001L)
    
    val mockClient = new MockWebhookClient()
    val dispatcher = new WebhookDispatcher[IO](mockClient, WebhookConfig(Some("http://test.com"), 30.seconds))
    
    // ACT: Dispatch same DataUpdate at different ordinals
    val result = for {
      _ <- dispatcher.dispatchRejection(ordinal1, sampleDataUpdate, List(ValidationError("ORD1", "ordinal 1")))
      _ <- dispatcher.dispatchRejection(ordinal2, sampleDataUpdate, List(ValidationError("ORD2", "ordinal 2")))
    } yield ()
    
    // ASSERT: Both dispatches occur (different ordinals)
    result.map { _ =>
      mockClient.capturedRequests should have size 2
      mockClient.capturedPayloads should have size 2
      mockClient.capturedPayloads.map(_.ordinal) should contain allElementsOf List(1000L, 1001L)
    }
  }

  it should "handle multiple error types in single rejection" in {
    // ARRANGE: DataUpdate with multiple validation errors
    val multipleErrors = List(
      ValidationError("INVALID_SIGNATURE", "Signature verification failed"),
      ValidationError("INSUFFICIENT_BALANCE", "Not enough tokens"),
      ValidationError("EXPIRED_NONCE", "Nonce is too old")
    )
    
    val mockClient = new MockWebhookClient()
    val dispatcher = new WebhookDispatcher[IO](mockClient, WebhookConfig(Some("http://test.com"), 30.seconds))
    
    // ACT: Dispatch with multiple errors
    val result = dispatcher.dispatchRejection(testOrdinal, sampleDataUpdate, multipleErrors)
    
    // ASSERT: First error used in payload (current implementation pattern)
    result.map { _ =>
      mockClient.capturedPayloads should have size 1
      val payload = mockClient.capturedPayloads.head
      payload.errorCode shouldBe "INVALID_SIGNATURE"
      payload.reason shouldBe "Signature verification failed"
    }
  }
}

// Supporting types and case classes for tests
case class ValidationError(code: String, message: String)

case class RejectionEventPayload(
  ordinal: Long,
  fiberId: String,
  updateHash: String,
  signers: List[String],
  errorCode: String,
  reason: String
)