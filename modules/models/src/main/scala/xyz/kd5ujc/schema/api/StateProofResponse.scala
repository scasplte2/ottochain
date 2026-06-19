package xyz.kd5ujc.schema.api

import io.constellationnetwork.schema.SnapshotOrdinal

import io.circe.generic.semiauto._
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}

/**
 * Response of the `…/state-proof` endpoints: a Merkle-Patricia inclusion proof of a committed record
 * against the snapshot's committed root, optionally surfacing one named `stateData` field.
 *
 * `committedRoot` / `mptRoot` / `record` / `proof` are carried as raw `Json` for now — they serialize via
 * the committed-state types' own encoders, byte-identical to the prior hand-built body; tightening them to
 * typed hash/record/proof models is a follow-up (RFC §3). `field` / `fieldValue` are present only when a
 * field was requested (`?field=`), matching the prior conditional shape — hence the hand-written encoder
 * (the second quarantined exception, RFC §6).
 */
final case class StateProofResponse(
  key:           String,
  ordinal:       SnapshotOrdinal,
  committedRoot: Json,
  mptRoot:       Json,
  record:        Json,
  proof:         Json,
  field:         Option[String] = None,
  fieldValue:    Option[Json] = None
)

object StateProofResponse {
  implicit val decoder: Decoder[StateProofResponse] = deriveDecoder[StateProofResponse]

  implicit val encoder: Encoder[StateProofResponse] = Encoder.instance { r =>
    val base = Json.obj(
      "key"           -> r.key.asJson,
      "ordinal"       -> r.ordinal.asJson,
      "committedRoot" -> r.committedRoot,
      "mptRoot"       -> r.mptRoot,
      "record"        -> r.record,
      "proof"         -> r.proof
    )
    r.field.fold(base) { f =>
      base.deepMerge(Json.obj("field" -> f.asJson, "fieldValue" -> r.fieldValue.getOrElse(Json.Null)))
    }
  }
}
