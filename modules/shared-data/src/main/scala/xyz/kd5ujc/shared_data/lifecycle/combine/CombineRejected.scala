package xyz.kd5ujc.shared_data.lifecycle.combine

/**
 * Marker for a DETERMINISTIC, per-update business rejection raised inside a combiner (e.g. unauthorized,
 * non-monotonic, sequence-number mismatch, conformance violation, reserved label). The combine fold catches
 * ONLY this type — it records a `RejectionReceipt` log entry and CONTINUES, so one rejected update can never
 * abort the whole snapshot's combine.
 *
 * Crucially, NON-deterministic / infrastructure failures (transient context/IO errors, OOM, bugs) must NOT be
 * reported as `CombineRejected`: they propagate and abort, because turning a transient local error into a
 * committed rejection on one node while another node succeeds is exactly the consensus divergence we avoid.
 * The `reason` string must be deterministic (it is committed in the receipt and hashed into state).
 */
final case class CombineRejected(reason: String) extends RuntimeException(reason)
