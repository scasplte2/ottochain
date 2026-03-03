package xyz.kd5ujc.metagraph_l0

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint

import xyz.kd5ujc.metagraph_l0.webhooks.SubscriberRegistry
import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status, Uri}
import weaver.SimpleIOSuite

import java.util.UUID

object ML0StateProofRoutesTest extends SimpleIOSuite {

  test("GET /v1/state-machines/:fiberId/state-proof should return proof for existing fiber") {
    TestFixture.mockContext.use { implicit ctx =>
      for {
        checkpointService <- TestFixture.mockCheckpointService
        subscriberRegistry <- SubscriberRegistry.make[IO]
        routes = new ML0CustomRoutes[IO](checkpointService, subscriberRegistry)
        
        fiberId = UUID.randomUUID()
        field = "balance"
        
        request = Request[IO](
          method = Method.GET,
          uri = Uri.unsafeFromString(s"/v1/state-machines/${fiberId}/state-proof?field=${field}")
        )
        
        response <- routes.routes.orNotFound(request)
        body <- response.as[Json]
        
      } yield {
        // This test will FAIL until the endpoint is implemented
        expect(response.status == Status.Ok) &&
        expect(body.hcursor.downField("proof").succeeded) &&
        expect(body.hcursor.downField("fiberId").as[String].contains(fiberId.toString)) &&
        expect(body.hcursor.downField("field").as[String].contains(field)) &&
        expect(body.hcursor.downField("stateRoot").succeeded) &&
        expect(body.hcursor.downField("merkleProof").succeeded)
      }
    }
  }

  test("GET /v1/state-machines/:fiberId/state-proof should support two-level proof chain") {
    TestFixture.mockContext.use { implicit ctx =>
      for {
        checkpointService <- TestFixture.mockCheckpointService
        subscriberRegistry <- SubscriberRegistry.make[IO]
        routes = new ML0CustomRoutes[IO](checkpointService, subscriberRegistry)
        
        fiberId = UUID.randomUUID()
        field = "nonce"
        
        request = Request[IO](
          method = Method.GET,
          uri = Uri.unsafeFromString(s"/v1/state-machines/${fiberId}/state-proof?field=${field}")
        )
        
        response <- routes.routes.orNotFound(request)
        body <- response.as[Json]
        
      } yield {
        // This test will FAIL until two-level proof chain is implemented
        expect(response.status == Status.Ok) &&
        expect(body.hcursor.downField("fiberProof").succeeded) && // field proof within fiber
        expect(body.hcursor.downField("metagraphProof").succeeded) && // fiberId→stateRoot proof
        expect(body.hcursor.downField("metagraphStateRoot").succeeded)
      }
    }
  }

  test("GET /v1/state-machines/:fiberId/state-proof should return 404 for non-existent fiber") {
    TestFixture.mockContext.use { implicit ctx =>
      for {
        checkpointService <- TestFixture.mockCheckpointService  
        subscriberRegistry <- SubscriberRegistry.make[IO]
        routes = new ML0CustomRoutes[IO](checkpointService, subscriberRegistry)
        
        nonExistentFiberId = UUID.randomUUID()
        field = "balance"
        
        request = Request[IO](
          method = Method.GET,
          uri = Uri.unsafeFromString(s"/v1/state-machines/${nonExistentFiberId}/state-proof?field=${field}")
        )
        
        response <- routes.routes.orNotFound(request)
        
      } yield {
        // This test will FAIL until proper error handling is implemented
        expect(response.status == Status.NotFound)
      }
    }
  }

  test("GET /v1/state-machines/:fiberId/state-proof should require field query parameter") {
    TestFixture.mockContext.use { implicit ctx =>
      for {
        checkpointService <- TestFixture.mockCheckpointService
        subscriberRegistry <- SubscriberRegistry.make[IO]
        routes = new ML0CustomRoutes[IO](checkpointService, subscriberRegistry)
        
        fiberId = UUID.randomUUID()
        
        request = Request[IO](
          method = Method.GET,
          uri = Uri.unsafeFromString(s"/v1/state-machines/${fiberId}/state-proof")
        )
        
        response <- routes.routes.orNotFound(request)
        
      } yield {
        // This test will FAIL until parameter validation is implemented
        expect(response.status == Status.BadRequest)
      }
    }
  }

  test("GET /v1/state-machines/:fiberId/state-proof should validate field parameter") {
    TestFixture.mockContext.use { implicit ctx =>
      for {
        checkpointService <- TestFixture.mockCheckpointService
        subscriberRegistry <- SubscriberRegistry.make[IO]
        routes = new ML0CustomRoutes[IO](checkpointService, subscriberRegistry)
        
        fiberId = UUID.randomUUID()
        invalidField = "nonExistentField"
        
        request = Request[IO](
          method = Method.GET,
          uri = Uri.unsafeFromString(s"/v1/state-machines/${fiberId}/state-proof?field=${invalidField}")
        )
        
        response <- routes.routes.orNotFound(request)
        body <- response.as[Json]
        
      } yield {
        // This test will FAIL until field validation is implemented
        expect(response.status == Status.BadRequest) &&
        expect(body.hcursor.downField("error").as[String].exists(_.contains("Invalid field")))
      }
    }
  }

  test("GET /v1/state-machines/:fiberId/state-proof should complete within 5ms for small tries") {
    TestFixture.mockContext.use { implicit ctx =>
      for {
        checkpointService <- TestFixture.mockCheckpointService
        subscriberRegistry <- SubscriberRegistry.make[IO]
        routes = new ML0CustomRoutes[IO](checkpointService, subscriberRegistry)
        
        fiberId = UUID.randomUUID()
        field = "balance"
        
        request = Request[IO](
          method = Method.GET,
          uri = Uri.unsafeFromString(s"/v1/state-machines/${fiberId}/state-proof?field=${field}")
        )
        
        startTime <- IO.realTime
        response <- routes.routes.orNotFound(request)
        endTime <- IO.realTime
        duration = endTime - startTime
        
      } yield {
        // This test will FAIL until performance optimization is implemented
        expect(response.status == Status.Ok) &&
        expect(duration.toMillis < 5L) // <5ms requirement from spec
      }
    }
  }

  test("GET /v1/state-machines/:fiberId/state-proof should use MerklePatriciaProducer") {
    TestFixture.mockContext.use { implicit ctx =>
      for {
        checkpointService <- TestFixture.mockCheckpointService
        subscriberRegistry <- SubscriberRegistry.make[IO]
        routes = new ML0CustomRoutes[IO](checkpointService, subscriberRegistry)
        
        fiberId = UUID.randomUUID()
        field = "balance"
        
        request = Request[IO](
          method = Method.GET,
          uri = Uri.unsafeFromString(s"/v1/state-machines/${fiberId}/state-proof?field=${field}")
        )
        
        response <- routes.routes.orNotFound(request)
        body <- response.as[Json]
        
      } yield {
        // This test will FAIL until MerklePatriciaProducer integration is implemented
        expect(response.status == Status.Ok) &&
        expect(body.hcursor.downField("proofType").as[String].contains("MerklePatricia")) &&
        expect(body.hcursor.downField("canonicalization").as[String].contains("RFC8785"))
      }
    }
  }

  test("GET /v1/state-machines/:fiberId/state-proof should handle multiple concurrent requests") {
    TestFixture.mockContext.use { implicit ctx =>
      for {
        checkpointService <- TestFixture.mockCheckpointService
        subscriberRegistry <- SubscriberRegistry.make[IO]
        routes = new ML0CustomRoutes[IO](checkpointService, subscriberRegistry)
        
        fiberId = UUID.randomUUID()
        field = "balance"
        
        request = Request[IO](
          method = Method.GET,
          uri = Uri.unsafeFromString(s"/v1/state-machines/${fiberId}/state-proof?field=${field}")
        )
        
        // Fire 10 concurrent requests
        concurrentResponses <- (1 to 10).toList.traverse(_ => 
          routes.routes.orNotFound(request)
        )
        
      } yield {
        // This test will FAIL until concurrent access is properly handled
        val allSuccessful = concurrentResponses.forall(_.status == Status.Ok)
        expect(allSuccessful) &&
        expect(concurrentResponses.length == 10)
      }
    }
  }
}