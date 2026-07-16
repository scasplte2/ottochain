package xyz.kd5ujc.schema.api

import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.CommitIndex

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

/**
 * Response of `GET /v1/commit-index` (onchain-incrementals RFC §3.4): the full recreated
 * cumulative commit maps at `ordinal` — the back-compat surface for consumers that previously
 * read the v1 cumulative `/v1/onchain`, and the DL1 heal transport
 * (`CommitIndexHealClient`) for re-seeding its folded index after an ordinal gap or restart.
 *
 * TRUST NOTE: served from the ML0's committed calculated state. A healing DL1 currently trusts
 * the ML0 peer it is already configured against (the same peer that feeds it snapshots);
 * batch-proof verification of this payload against the signed breadcrumb `mptRoot` (via the
 * `commit/` MPT namespace) is the hardening step tracked in RFC §3.3 step 2.
 */
final case class CommitIndexResponse(
  ordinal: SnapshotOrdinal,
  index:   CommitIndex
)

object CommitIndexResponse {
  implicit val decoder: Decoder[CommitIndexResponse] = deriveDecoder[CommitIndexResponse]
  implicit val encoder: Encoder[CommitIndexResponse] = deriveEncoder[CommitIndexResponse]
}
