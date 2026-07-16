package xyz.kd5ujc.shared_data.lifecycle

import xyz.kd5ujc.schema.api.CommitIndexResponse

/**
 * DL1-side heal transport (onchain-incrementals RFC §3.3): fetch the full recreated commit index
 * from an ML0 node's `GET /v1/commit-index`.
 *
 * Used by `Validator`'s commit-index cache whenever the per-batch delta fold cannot proceed —
 * first sync after start, or any ordinal gap (the fold is only sound at `checkpoint.ordinal + 1`;
 * folding across a gap loses writes and the structural gate FAILS OPEN for unknown ids).
 *
 * The trait is transport-agnostic; the http4s implementation lives in the data_l1 module (it
 * targets the same `--l0-peer` the node is already configured against). Batch-proof verification
 * of the healed payload against the signed breadcrumb `mptRoot` is the hardening follow-up
 * (RFC §3.3 step 2) — until then the heal trusts the configured ML0 peer, which already feeds
 * this node its snapshots; the combiner remains the authoritative stateful gate regardless.
 */
trait CommitIndexHealClient[F[_]] {

  /** Fetch the ML0's current full commit index and its ordinal. */
  def fetch: F[CommitIndexResponse]
}
