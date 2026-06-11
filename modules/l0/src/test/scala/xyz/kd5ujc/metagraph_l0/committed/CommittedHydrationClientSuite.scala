package xyz.kd5ujc.metagraph_l0.committed

import cats.effect.{IO, Ref}

import scala.collection.immutable.SortedMap

import io.constellationnetwork.metagraph_sdk.lifecycle.committed.CatalogContents

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.SimpleIOSuite

/**
 * Drives [[CommittedHydrationClient]] against an in-process stub of the `/committed/...` routes:
 * the no-op on a hydrated cell, the peer fallback chain, and the verify-gate rejection path
 * (the stub's `/committed/hydrate` plays the role of `CommittedState.hydrate`'s root check).
 */
object CommittedHydrationClientSuite extends SimpleIOSuite {

  private val selfUri = uri"http://self:9200/data-application"
  private val goodPeer = uri"http://good-peer:9200/data-application"
  private val badPeer = uri"http://bad-peer:9200/data-application"

  private val contents = CatalogContents(65536, SortedMap.empty, SortedMap.empty, SortedMap.empty)

  private def withLogger[A](run: SelfAwareStructuredLogger[IO] => IO[A]): IO[A] =
    Slf4jLogger.create[IO].flatMap(run)

  /** A stub network: `self` root/hydrate behavior is parameterized, peers serve (or fail) the catalog. */
  private def stubClient(
    selfHydrated:  Boolean,
    acceptPayload: Boolean,
    hydrateCalls:  Ref[IO, Int]
  ): Client[IO] = {
    def pathOf(req: Request[IO]): String = req.uri.path.renderString
    def hostOf(req: Request[IO]): Option[String] = req.uri.host.map(_.value)

    val routes = HttpRoutes.of[IO] {
      case req if req.method == Method.GET && pathOf(req).endsWith("/committed/root") && hostOf(req).contains("self") =>
        Ok(Json.obj("ordinal" -> 5L.asJson, "hydrated" -> selfHydrated.asJson))

      case req if req.method == Method.GET && pathOf(req).endsWith("/committed/catalog") =>
        hostOf(req) match {
          case Some("good-peer") => Ok(contents.asJson)
          case _                 => InternalServerError(Json.obj("error" -> "boom".asJson))
        }

      case req if req.method == Method.POST && pathOf(req).endsWith("/committed/hydrate") =>
        hydrateCalls.update(_ + 1) >>
        req.as[CatalogContents].flatMap { _ =>
          if (acceptPayload) Ok(Json.obj("ordinal" -> 5L.asJson, "catalogRoot" -> "abc".asJson))
          else BadRequest(Json.obj("error" -> "hydration contents recompose to a different root".asJson))
        }
    }
    Client.fromHttpApp(routes.orNotFound)
  }

  test("no-op when the own cell is already hydrated") {
    withLogger { implicit logger =>
      for {
        calls <- Ref.of[IO, Int](0)
        client = new CommittedHydrationClient[IO](stubClient(selfHydrated = true, acceptPayload = true, calls))
        outcome   <- client.hydrateOnce(selfUri, List(goodPeer))
        attempted <- calls.get
      } yield expect(outcome == CommittedHydrationClient.Outcome.AlreadyHydrated(5L)) and
      expect(attempted == 0)
    }
  }

  test("seeded cell: falls past a failing peer and hydrates from the first serving peer") {
    withLogger { implicit logger =>
      for {
        calls <- Ref.of[IO, Int](0)
        client = new CommittedHydrationClient[IO](stubClient(selfHydrated = false, acceptPayload = true, calls))
        outcome   <- client.hydrateOnce(selfUri, List(badPeer, goodPeer))
        attempted <- calls.get
      } yield expect(attempted == 1) and
      expect(outcome match {
        case CommittedHydrationClient.Outcome.Hydrated(peer, _) => peer == goodPeer
        case _                                                  => false
      })
    }
  }

  test("verify-gate: a payload the own node rejects is a per-peer failure, not a success") {
    withLogger { implicit logger =>
      for {
        calls <- Ref.of[IO, Int](0)
        client = new CommittedHydrationClient[IO](stubClient(selfHydrated = false, acceptPayload = false, calls))
        outcome <- client.hydrateOnce(selfUri, List(goodPeer))
      } yield expect(outcome match {
        case CommittedHydrationClient.Outcome.NotHydrated(failures) =>
          failures.map(_._1) == List(goodPeer) && failures.head._2.contains("rejected")
        case _ => false
      })
    }
  }

  test("no serving peers: all failures reported in order") {
    withLogger { implicit logger =>
      for {
        calls <- Ref.of[IO, Int](0)
        client = new CommittedHydrationClient[IO](stubClient(selfHydrated = false, acceptPayload = true, calls))
        outcome <- client.hydrateOnce(selfUri, List(badPeer, badPeer))
      } yield expect(outcome match {
        case CommittedHydrationClient.Outcome.NotHydrated(failures) => failures.size == 2
        case _                                                      => false
      })
    }
  }
}
