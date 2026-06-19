package xyz.kd5ujc.metagraph_l0.openapi

import cats.effect.Async

import org.http4s.HttpRoutes
import sttp.tapir._
import sttp.tapir.server.http4s.Http4sServerInterpreter

/**
 * Live documentation routes, interpreted from tapir to http4s by `Http4sServerInterpreter` — so the
 * running node publishes the very contract described in [[ApiEndpoints]]:
 *   - `GET /openapi.json` → the OpenAPI 3.1 document
 *   - `GET /docs`         → Swagger-UI (loads `/openapi.json`)
 *
 * Mounted additively alongside `ML0Routes` via `extraRoutes`; it touches none of the business endpoints'
 * response semantics. Interpreting those endpoints from `ApiEndpoints` as well (replacing the hand-written
 * router) is the remaining step to fully close the single-source loop — deferred because replicating the
 * SDK's `.toResponse` error rendering across all 22 endpoints needs end-to-end validation.
 */
object OpenApiRoutes {

  private val openApiSpec: PublicEndpoint[Unit, Unit, String, Any] =
    endpoint.get
      .in("openapi.json")
      .out(stringJsonBody)
      .summary("This metagraph's OpenAPI 3.1 contract")

  private val docs: PublicEndpoint[Unit, Unit, String, Any] =
    endpoint.get
      .in("docs")
      .out(htmlBodyUtf8)
      .summary("Swagger-UI for this metagraph's API")

  private val swaggerHtml: String =
    """<!DOCTYPE html>
      |<html lang="en">
      |<head>
      |  <meta charset="UTF-8"/>
      |  <title>OttoChain API</title>
      |  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist/swagger-ui.css"/>
      |</head>
      |<body>
      |  <div id="swagger-ui"></div>
      |  <script src="https://unpkg.com/swagger-ui-dist/swagger-ui-bundle.js" crossorigin></script>
      |  <script>window.onload = () => { SwaggerUIBundle({ url: '/openapi.json', dom_id: '#swagger-ui' }); };</script>
      |</body>
      |</html>""".stripMargin

  def routes[F[_]: Async]: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        openApiSpec.serverLogicSuccess[F](_ => Async[F].pure(ApiEndpoints.openApiJson)),
        docs.serverLogicSuccess[F](_ => Async[F].pure(swaggerHtml))
      )
    )
}
