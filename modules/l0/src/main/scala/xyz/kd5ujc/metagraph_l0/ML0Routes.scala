package xyz.kd5ujc.metagraph_l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.http4s.error.RefinedRequestApplicationDecoder
import io.constellationnetwork.metagraph_sdk.MetagraphPublicRoutes
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.metagraph_l0.handlers._
import xyz.kd5ujc.metagraph_l0.webhooks.SubscribeRequest
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber.FiberStatus

import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.server.Router
import org.http4s.{HttpRoutes, QueryParamDecoder}

/**
 * The single ML0 public router — every endpoint under `/v1` in one place, each case delegating to a
 * concern-specific handler in the `handlers` package. Add a route here; put the logic in a handler.
 */
class ML0Routes[F[_]: Async](
  meta:         MetaHandler[F],
  stateMachine: StateMachineHandler[F],
  script:       ScriptHandler[F],
  registry:     RegistryHandler[F],
  webhook:      WebhookHandler[F],
  estimate:     EstimateHandler[F]
) extends MetagraphPublicRoutes[F] {

  implicit private val fiberStatusDecoder: QueryParamDecoder[FiberStatus] =
    QueryParamDecoder[String].emap { s =>
      FiberStatus.withNameOption(s).toRight(org.http4s.ParseFailure(s, s"Invalid FiberStatus: $s"))
    }

  private object StatusQueryParam extends OptionalQueryParamDecoderMatcher[FiberStatus]("status")
  private object EventQueryParam extends QueryParamDecoderMatcher[String]("event")

  private val v1Routes: HttpRoutes[F] = HttpRoutes.of[F] {

    // --- service meta + raw state ---
    case GET -> Root / "version" => Ok(meta.version)
    case req @ POST -> Root / "util" / "hash" =>
      req.asR[Signed[OttochainMessage]](m => meta.hash(m.value).flatMap(Ok(_)))
    case GET -> Root / "onchain"    => meta.onChain.toResponse
    case GET -> Root / "checkpoint" => meta.checkpoint.toResponse

    // --- state machines ---
    case GET -> Root / "state-machines" :? StatusQueryParam(status) => stateMachine.list(status).toResponse
    case GET -> Root / "state-machines" / UUIDVar(id)               => stateMachine.get(id).toResponse
    case GET -> Root / "state-machines" / UUIDVar(id) / "events"    => stateMachine.events(id).toResponse
    case GET -> Root / "state-machines" / UUIDVar(id) / "audit"     => stateMachine.audit(id).toResponse
    case GET -> Root / "state-machines" / UUIDVar(id) / "estimate-fee" :? EventQueryParam(event) =>
      estimate.transition(id, event).toResponse

    // --- scripts (the legacy /oracles surface is retained) ---
    case GET -> Root / "oracles" :? StatusQueryParam(status)    => script.list(status).toResponse
    case GET -> Root / "oracles" / UUIDVar(id)                  => script.get(id).toResponse
    case GET -> Root / "oracles" / UUIDVar(id) / "invocations"  => script.invocations(id).toResponse
    case GET -> Root / "scripts" / UUIDVar(id) / "estimate-fee" => estimate.script(id).toResponse

    // --- registry ---
    case GET -> Root / "registry"                           => registry.all.toResponse
    case GET -> Root / "registry" / "reverse" / UUIDVar(id) => registry.reverse(id).toResponse
    case GET -> Root / "registry" / name                    => registry.byName(name).toResponse

    // --- webhooks ---
    case req @ POST -> Root / "webhooks" / "subscribe"  => req.decode[SubscribeRequest](webhook.subscribe)
    case DELETE -> Root / "webhooks" / "subscribe" / id => webhook.unsubscribe(id)
    case GET -> Root / "webhooks" / "subscribers"       => webhook.list
  }

  protected val routes: HttpRoutes[F] = Router(
    "/v1" -> v1Routes
  )
}
