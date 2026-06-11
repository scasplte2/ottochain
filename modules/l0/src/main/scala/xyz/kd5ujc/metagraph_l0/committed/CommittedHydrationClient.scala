package xyz.kd5ujc.metagraph_l0.committed

import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.metagraph_sdk.lifecycle.committed.CatalogContents

import io.circe.Json
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.client.Client
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.log4cats.SelfAwareStructuredLogger

/**
 * Catalog hydration for a breadcrumb-seeded node.
 *
 * A node that bootstraps from snapshot download seeds its committed cell from the on-chain
 * breadcrumb (`CatalogView.SeededCatalog`): it can verify `hashCalculatedState` and serve
 * state-dict proofs, but catalog proofs and consensus transitions need the full epoch-rollup
 * contents. This client closes that gap over the public routes:
 *
 *   1. `GET  <self>/committed/root` -- act only when the cell reports `hydrated: false`;
 *   2. `GET  <peer>/committed/catalog` -- fetch the [[CatalogContents]] payload from a peer;
 *   3. `POST <self>/committed/hydrate` -- install it on the own node.
 *
 * Trustless by construction: step 3 is verify-gated SERVER-side -- `CommittedState.hydrate`
 * rejects contents that do not recompose to the consensus-attested catalog root
 * (`HydrationRootMismatch`), so a malicious or stale peer can cause a retry, never a bad state.
 * Peers are tried in order until one payload is accepted.
 *
 * Base URIs must point at the node's data-application route root (the segment under which the
 * `/committed/...` routes are mounted).
 */
final class CommittedHydrationClient[F[_]: Async](
  client: Client[F]
)(implicit logger: SelfAwareStructuredLogger[F]) {

  import CommittedHydrationClient._

  /** One atomic read of a node's committed root descriptor. */
  def rootStatus(node: Uri): F[RootStatus] =
    client.expect[Json](node / "committed" / "root").flatMap { json =>
      val cursor = json.hcursor
      (cursor.get[Long]("ordinal"), cursor.get[Boolean]("hydrated"))
        .mapN(RootStatus(_, _))
        .liftTo[F]
    }

  /**
   * A single hydration pass: no-op on an already-hydrated cell, otherwise try each peer in order
   * until the own node accepts a payload.
   */
  def hydrateOnce(self: Uri, peers: List[Uri]): F[Outcome] =
    rootStatus(self).flatMap { status =>
      if (status.hydrated) (Outcome.AlreadyHydrated(status.ordinal): Outcome).pure[F]
      else tryPeers(self, peers, Nil)
    }

  /**
   * Poll [[hydrateOnce]] every `interval` until the cell is hydrated (a transition or journal
   * recovery may also hydrate it independently, which terminates the loop the same way). Errors
   * of a whole pass (e.g. own node not yet serving routes) are logged and retried.
   */
  def awaitHydrated(self: Uri, peers: List[Uri], interval: FiniteDuration): F[Outcome] =
    hydrateOnce(self, peers)
      .handleErrorWith { err =>
        logger
          .warn(s"committed hydration pass failed: ${err.getMessage}")
          .as(Outcome.NotHydrated(List(self -> err.getMessage)): Outcome)
      }
      .flatMap {
        case done @ (Outcome.AlreadyHydrated(_) | Outcome.Hydrated(_, _)) =>
          (done: Outcome).pure[F]
        case Outcome.NotHydrated(failures) =>
          logger.info(
            s"committed catalog not hydrated yet (${failures.size} attempt(s) failed); retrying in $interval"
          ) >> Async[F].sleep(interval) >> awaitHydrated(self, peers, interval)
      }

  private def tryPeers(self: Uri, peers: List[Uri], failures: List[(Uri, String)]): F[Outcome] =
    peers match {
      case Nil => (Outcome.NotHydrated(failures.reverse): Outcome).pure[F]
      case peer :: rest =>
        hydrateFrom(self, peer).flatMap {
          case Right(outcome) => (outcome: Outcome).pure[F]
          case Left(reason) =>
            logger.info(s"committed hydration from $peer failed: $reason") >>
            tryPeers(self, rest, (peer -> reason) :: failures)
        }
    }

  private def hydrateFrom(self: Uri, peer: Uri): F[Either[String, Outcome]] =
    client
      .expect[CatalogContents](peer / "committed" / "catalog")
      .attempt
      .flatMap {
        case Left(err) => s"failed to fetch /committed/catalog: ${err.getMessage}".asLeft[Outcome].pure[F]
        case Right(contents) =>
          val request = Request[F](Method.POST, self / "committed" / "hydrate").withEntity(contents)
          client.run(request).use { response =>
            response.status match {
              case Status.Ok =>
                response.as[Json].map(json => (Outcome.Hydrated(peer, json): Outcome).asRight[String])
              case status =>
                response
                  .as[Json]
                  .map(_.hcursor.get[String]("error").getOrElse(""))
                  .handleError(_ => "")
                  .map(detail => s"own node rejected hydration payload ($status): $detail".asLeft[Outcome])
            }
          }
      }
      .handleError(err => s"hydration attempt errored: ${err.getMessage}".asLeft[Outcome])
}

object CommittedHydrationClient {

  /** The fields of `GET /committed/root` the client acts on. */
  final case class RootStatus(ordinal: Long, hydrated: Boolean)

  sealed trait Outcome extends Product with Serializable

  object Outcome {

    /** The cell already holds live catalog contents (genesis node, journal recovery, or a prior pass). */
    final case class AlreadyHydrated(ordinal: Long) extends Outcome

    /** A peer's payload recomposed to the attested catalog root and was installed. */
    final case class Hydrated(peer: Uri, response: Json) extends Outcome

    /** Every peer failed this pass; `failures` records one reason per attempted peer. */
    final case class NotHydrated(failures: List[(Uri, String)]) extends Outcome
  }
}
