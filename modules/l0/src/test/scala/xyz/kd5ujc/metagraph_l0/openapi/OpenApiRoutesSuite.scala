package xyz.kd5ujc.metagraph_l0.openapi

import cats.effect.IO

import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import weaver.SimpleIOSuite

/**
 * Proves the tapir → http4s server interpretation actually serves: the running node exposes its own
 * OpenAPI contract at `/openapi.json` and a Swagger-UI at `/docs`. This is the live counterpart to
 * [[OpenApiDocSuite]] (which only checks the generated document).
 */
object OpenApiRoutesSuite extends SimpleIOSuite {

  private val routes = OpenApiRoutes.routes[IO]

  test("GET /openapi.json serves the contract") {
    routes.orNotFound.run(Request[IO](Method.GET, uri"/openapi.json")).flatMap { resp =>
      resp.as[String].map { body =>
        expect.all(
          resp.status == Status.Ok,
          body.contains("\"openapi\""),
          body.contains("/v1/version")
        )
      }
    }
  }

  test("GET /docs serves Swagger-UI") {
    routes.orNotFound.run(Request[IO](Method.GET, uri"/docs")).flatMap { resp =>
      resp.as[String].map { body =>
        expect.all(resp.status == Status.Ok, body.contains("swagger-ui"), body.contains("/openapi.json"))
      }
    }
  }
}
