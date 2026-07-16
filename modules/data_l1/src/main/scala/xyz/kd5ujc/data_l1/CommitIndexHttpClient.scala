package xyz.kd5ujc.data_l1

import cats.effect.Async

import xyz.kd5ujc.schema.api.CommitIndexResponse
import xyz.kd5ujc.shared_data.lifecycle.CommitIndexHealClient

import org.http4s.Uri
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client

/**
 * http4s implementation of the DL1 heal transport (onchain-incrementals RFC §3.3): fetches the
 * full recreated commit index from the ML0 node's `GET /v1/commit-index` — the same `--l0-peer`
 * this node is already configured against for snapshots. Trust note in [[CommitIndexResponse]];
 * batch-proof verification against the signed breadcrumb is the tracked hardening follow-up.
 */
object CommitIndexHttpClient {

  def make[F[_]: Async](client: Client[F], ml0Base: Uri): CommitIndexHealClient[F] =
    new CommitIndexHealClient[F] {

      def fetch: F[CommitIndexResponse] =
        // the framework mounts an app's custom routes under /data-application, ML0Routes adds /v1
        client.expect[CommitIndexResponse](ml0Base / "data-application" / "v1" / "commit-index")
    }
}
