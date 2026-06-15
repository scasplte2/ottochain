package xyz.kd5ujc.metagraph_l0.handlers

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.lifecycle.committed.{CommitKey, CommittedReader}

import xyz.kd5ujc.schema.CalculatedState

import io.circe.Json
import io.circe.syntax.EncoderOps
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.{Response, Status}

/**
 * Field/record state proofs sourced from the COMMITTED MPT — the committed-state successor to the
 * hand-rolled per-fiber tries in #117. A fiber/script's whole record is committed at `fiber/<id>` /
 * `script/<id>`, and `proveKey` returns a Merkle-Patricia inclusion proof against the MPT root whose
 * combined hash IS the snapshot's `calculatedStateProof`. So a light client verifies the proof
 * against the root the snapshot consensus signed, then reads any field straight off the proven
 * record — #117's two-level field→fiberRoot→metagraphRoot collapses to one level here, anchored to a
 * real consensus root rather than an off-chain one. `?field=` is a convenience that also surfaces the
 * named `stateData` field of the proven record.
 */
class StateProofHandler[F[_]: Async](reader: CommittedReader[F, CalculatedState]) {

  def stateMachine(id: UUID, field: Option[String]): F[Response[F]] =
    proofFor(s"fiber/$id", field)(_.stateMachines.get(id).map(_.asJson))

  def script(id: UUID, field: Option[String]): F[Response[F]] =
    proofFor(s"script/$id", field)(_.scripts.get(id).map(_.asJson))

  private def proofFor(keyStr: String, field: Option[String])(
    recordOf: CalculatedState => Option[Json]
  ): F[Response[F]] = {
    val key = CommitKey.unsafe(keyStr)
    reader.committed.flatMap { c =>
      recordOf(c.state) match {
        case None =>
          Response[F](Status.NotFound).withEntity(Json.obj("error" -> s"no committed record at $keyStr".asJson)).pure[F]

        case Some(record) =>
          c.proveKey(key).map {
            case Left(err) =>
              Response[F](Status.InternalServerError).withEntity(Json.obj("error" -> err.getMessage.asJson))

            case Right(proof) =>
              val base = Json.obj(
                "key"           -> key.value.asJson,
                "ordinal"       -> c.ordinal.asJson,
                "committedRoot" -> c.roots.combinedHash.asJson,
                "mptRoot"       -> c.roots.mptRoot.asJson,
                "record"        -> record,
                "proof"         -> proof.asJson
              )
              val body = field.fold(base) { f =>
                val fieldValue = record.hcursor.downField("stateData").downField(f).focus.getOrElse(Json.Null)
                base.deepMerge(Json.obj("field" -> f.asJson, "fieldValue" -> fieldValue))
              }
              Response[F](Status.Ok).withEntity(body)
          }
      }
    }
  }
}
