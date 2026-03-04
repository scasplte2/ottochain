package xyz.kd5ujc.metagraph_l0

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataApplicationValidationError, L0NodeContext}
import io.constellationnetwork.ext.http4s.error.RefinedRequestApplicationDecoder
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.metagraph_sdk.crypto.mpt.MerklePatriciaInclusionProof
import io.constellationnetwork.metagraph_sdk.crypto.mpt.api.MerklePatriciaProducer
import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicValue, MapValue}
import io.constellationnetwork.metagraph_sdk.lifecycle.CheckpointService
import io.constellationnetwork.metagraph_sdk.std.Checkpoint
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.metagraph_sdk.syntax.all.L0ContextOps
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.buildinfo.BuildInfo
import xyz.kd5ujc.metagraph_l0.webhooks.{SubscribeRequest, SubscribeResponse, SubscriberRegistry}
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber.FiberLogEntry.{EventReceipt, OracleInvocation}
import xyz.kd5ujc.schema.fiber.FiberStatus
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.server.Router
import org.http4s.{HttpRoutes, QueryParamDecoder, Response, Status}

class ML0CustomRoutes[F[_]: Async](
  checkpointService:  CheckpointService[F, CalculatedState],
  subscriberRegistry: SubscriberRegistry[F]
)(implicit
  context: L0NodeContext[F]
) extends MetagraphPublicRoutes[F] {

  implicit val fiberStatusDecoder: QueryParamDecoder[FiberStatus] =
    QueryParamDecoder[String].emap { s =>
      FiberStatus.withNameOption(s).toRight(org.http4s.ParseFailure(s, s"Invalid FiberStatus: $s"))
    }

  object StatusQueryParam extends OptionalQueryParamDecoderMatcher[FiberStatus]("status")
  object FieldQueryParam extends OptionalQueryParamDecoderMatcher[String]("field")

  private val v1Routes: HttpRoutes[F] = HttpRoutes.of[F] {

    // Version endpoint for monitoring integration
    case GET -> Root / "version" =>
      Ok(
        Json.obj(
          "service"             -> "ottochain-ml0".asJson,
          "version"             -> BuildInfo.version.asJson,
          "name"                -> BuildInfo.name.asJson,
          "scalaVersion"        -> BuildInfo.scalaVersion.asJson,
          "sbtVersion"          -> BuildInfo.sbtVersion.asJson,
          "gitCommit"           -> BuildInfo.gitCommit.asJson,
          "buildTime"           -> BuildInfo.buildTime.asJson,
          "tessellationVersion" -> io.constellationnetwork.BuildInfo.version.asJson
        )
      )

    case req @ POST -> Root / "util" / "hash" =>
      req.asR[Signed[OttochainMessage]] { msg =>
        msg.value.computeDigest.flatMap { digest =>
          Ok(Json.obj("protocol message hash" -> digest.asJson, "protocol message" -> msg.value.asJson))
        }
      }

    case GET -> Root / "onchain" =>
      context.getOnChainState[OnChain].toResponse

    case GET -> Root / "checkpoint" =>
      checkpointService.get
        .map(_.asRight[DataApplicationValidationError])
        .toResponse

    case GET -> Root / "state-machines" :? StatusQueryParam(statusOpt) =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        statusOpt
          .fold(state.stateMachines) { status =>
            state.stateMachines.filter { case (_, fiber) => fiber.status == status }
          }
          .asRight[DataApplicationValidationError]
      }.toResponse

    case GET -> Root / "state-machines" / UUIDVar(fiberId) =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        state.stateMachines.get(fiberId).asRight[DataApplicationValidationError]
      }.toResponse

    // =========================================================================
    // Phase 1B: MPT State Proof Endpoint
    // =========================================================================

    /**
     * Generate a two-level Merkle Patricia Trie inclusion proof.
     *
     * GET /v1/state-machines/:fiberId/state-proof
     *   → Lists available fields + roots (no proof generated)
     *
     * GET /v1/state-machines/:fiberId/state-proof?field=<fieldName>
     *   → Returns:
     *       fiberProof:      proves fieldName ∈ fiber.stateRoot
     *       metagraphProof:  proves fiberId.stateRoot ∈ metagraphStateRoot
     *
     * The two-level chain allows a verifier to prove that a specific field value
     * was committed to in the on-chain metagraphStateRoot, without trusting any
     * intermediate service.
     */
    case GET -> Root / "state-machines" / UUIDVar(fiberId) / "state-proof" :? FieldQueryParam(fieldOpt) =>
      checkpointService.get.flatMap { case Checkpoint(_, state) =>
        state.stateMachines.get(fiberId) match {

          case None =>
            Response[F](Status.NotFound)
              .withEntity(Json.obj("error" -> s"Fiber $fiberId not found".asJson))
              .pure[F]

          case Some(fiber) =>
            fiber.stateRoot match {

              // Fiber exists but stateRoot hasn't been computed yet (non-Map stateData)
              case None =>
                Ok(
                  Json.obj(
                    "fiberId"            -> fiberId.asJson,
                    "stateRoot"          -> Json.Null,
                    "metagraphStateRoot" -> state.metagraphStateRoot.asJson,
                    "message"            -> "No state root — fiber stateData is not a Map or is empty".asJson
                  )
                )

              case Some(fiberRoot) =>
                fieldOpt match {

                  // No field requested: return metadata + available fields
                  case None =>
                    val fields = fiber.stateData match {
                      case MapValue(fs) => fs.keys.toList.sorted
                      case _            => List.empty
                    }
                    Ok(
                      Json.obj(
                        "fiberId"            -> fiberId.asJson,
                        "fields"             -> fields.asJson,
                        "fiberStateRoot"     -> fiberRoot.asJson,
                        "metagraphStateRoot" -> state.metagraphStateRoot.asJson
                      )
                    )

                  // Field requested: build proof chain
                  case Some(field) =>
                    fiber.stateData match {

                      case MapValue(fields) if fields.contains(field) =>
                        val fieldHex = fieldNameToHex(field)

                        // Build the same per-fiber MPT as FiberCombiner.computeStateRoot
                        val fiberEntries: Map[Hex, JsonLogicValue] = fields.map { case (k, v) =>
                          fieldNameToHex(k) -> v
                        }

                        for {
                          fiberTrie   <- MerklePatriciaProducer.stateless[F].create(fiberEntries)
                          fiberProver <- MerklePatriciaProducer.stateless[F].getProver(fiberTrie)
                          fiberResult <- fiberProver.attestPath(fieldHex)

                          // Build metagraph-level MPT (same as ML0Service.computeMetagraphStateRoot)
                          metaEntries = state.stateMachines.collect {
                            case (id, sm) if sm.stateRoot.isDefined =>
                              Hex(id.toString.replace("-", "")) -> sm.stateRoot.get
                          }.toMap

                          metagraphProofOpt <- buildMetagraphProof(metaEntries, fiberId, state.metagraphStateRoot)

                          resp <- fiberResult match {
                            case Right(fiberProof) =>
                              Ok(
                                Json.obj(
                                  "fiberId"            -> fiberId.asJson,
                                  "field"              -> field.asJson,
                                  "fieldPath"          -> fieldHex.asJson,
                                  "fieldValue"         -> fields(field).asJson,
                                  "fiberStateRoot"     -> fiberRoot.asJson,
                                  "metagraphStateRoot" -> state.metagraphStateRoot.asJson,
                                  "fiberProof"         -> fiberProof.asJson,
                                  "metagraphProof"     -> metagraphProofOpt.asJson
                                )
                              )
                            case Left(err) =>
                              Response[F](Status.InternalServerError)
                                .withEntity(
                                  Json.obj("error" -> s"Failed to generate fiber proof: ${err.getMessage}".asJson)
                                )
                                .pure[F]
                          }
                        } yield resp

                      case _ =>
                        Response[F](Status.NotFound)
                          .withEntity(
                            Json.obj("error" -> s"Field '$field' not found in fiber stateData".asJson)
                          )
                          .pure[F]
                    }
                }
            }
        }
      }

    case GET -> Root / "oracles" :? StatusQueryParam(statusOpt) =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        statusOpt
          .fold(state.scripts) { status =>
            state.scripts.filter { case (_, oracle) => oracle.status == status }
          }
          .asRight[DataApplicationValidationError]
      }.toResponse

    case GET -> Root / "oracles" / UUIDVar(oracleId) =>
      checkpointService.get.map { case Checkpoint(_, state) =>
        state.scripts.get(oracleId).asRight[DataApplicationValidationError]
      }.toResponse

    case GET -> Root / "state-machines" / UUIDVar(fiberId) / "events" =>
      context
        .getOnChainState[OnChain]
        .map(_.map { onChain =>
          onChain.latestLogs
            .getOrElse(fiberId, List.empty)
            .collect { case r: EventReceipt => r }
        })
        .toResponse

    case GET -> Root / "oracles" / UUIDVar(oracleId) / "invocations" =>
      context
        .getOnChainState[OnChain]
        .map(_.map { onChain =>
          onChain.latestLogs
            .getOrElse(oracleId, List.empty)
            .collect { case i: OracleInvocation => i }
        })
        .toResponse

    // =========================================================================
    // Webhook Management Endpoints
    // =========================================================================

    /**
     * Register a new webhook subscriber
     * POST /v1/webhooks/subscribe
     * Body: { "callbackUrl": "https://...", "secret": "optional" }
     */
    case req @ POST -> Root / "webhooks" / "subscribe" =>
      req.decode[SubscribeRequest] { request =>
        subscriberRegistry.register(request.callbackUrl, request.secret).flatMap { subscriber =>
          Response[F](Status.Created)
            .withEntity(SubscribeResponse.fromSubscriber(subscriber).asJson)
            .pure[F]
        }
      }

    /**
     * Unregister a webhook subscriber
     * DELETE /v1/webhooks/subscribe/:id
     */
    case DELETE -> Root / "webhooks" / "subscribe" / subscriberId =>
      subscriberRegistry.unregister(subscriberId).flatMap { deleted =>
        if (deleted) {
          Response[F](Status.NoContent).pure[F]
        } else {
          Response[F](Status.NotFound)
            .withEntity(Json.obj("error" -> "Subscriber not found".asJson))
            .pure[F]
        }
      }

    /**
     * List all webhook subscribers
     * GET /v1/webhooks/subscribers
     */
    case GET -> Root / "webhooks" / "subscribers" =>
      subscriberRegistry.list.flatMap { subscribers =>
        // Hide secrets in response
        val sanitized = subscribers.map(s => s.copy(secret = s.secret.map(_ => "***")))
        Ok(Json.obj("subscribers" -> sanitized.asJson))
      }
  }

  // ============================================================================
  // Private Helpers
  // ============================================================================

  /** Encode a field name to its hex key (UTF-8 bytes as lowercase hex string). */
  private def fieldNameToHex(fieldName: String): Hex =
    Hex(fieldName.getBytes("UTF-8").map("%02x".format(_)).mkString)

  /**
   * Build the metagraph-level MPT proof for a given fiberId.
   *
   * Replicates `computeMetagraphStateRoot` from ML0Service to reconstruct
   * the trie and generate the inclusion proof.
   *
   * Returns None if:
   *   - no fibers have a stateRoot yet (empty trie)
   *   - metagraphStateRoot is not set
   *   - the fiberId has no stateRoot (not a leaf in the trie)
   */
  private def buildMetagraphProof(
    metaEntries:        Map[Hex, Hash],
    fiberId:            UUID,
    metagraphStateRoot: Option[Hash]
  ): F[Option[MerklePatriciaInclusionProof]] = {
    val fiberHex = Hex(fiberId.toString.replace("-", ""))

    if (metaEntries.isEmpty || metagraphStateRoot.isEmpty || !metaEntries.contains(fiberHex)) {
      Option.empty[io.constellationnetwork.metagraph_sdk.crypto.mpt.MerklePatriciaInclusionProof].pure[F]
    } else {
      (for {
        trie   <- MerklePatriciaProducer.stateless[F].create(metaEntries)
        prover <- MerklePatriciaProducer.stateless[F].getProver(trie)
        result <- prover.attestPath(fiberHex)
      } yield result.toOption)
        .handleError(_ => None)
    }
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
