package xyz.kd5ujc.metagraph_l0.handlers

import java.util.UUID

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.lifecycle.committed.{CommitKey, CommittedReader}

import xyz.kd5ujc.schema.CalculatedState
import xyz.kd5ujc.schema.api.{ErrorResponse, StateProofResponse}

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

  /** Custody proof for an asset instance — mirrors `stateMachine`/`script` against the `asset/<id>` key. */
  def asset(id: UUID, field: Option[String]): F[Response[F]] =
    proofFor(s"asset/$id", field)(_.assets.get(id).map(_.asJson))

  private def proofFor(keyStr: String, field: Option[String])(
    recordOf: CalculatedState => Option[Json]
  ): F[Response[F]] = {
    val key = CommitKey.unsafe(keyStr)
    reader.committed.flatMap { c =>
      recordOf(c.state) match {
        case None =>
          Response[F](Status.NotFound).withEntity(ErrorResponse(s"no committed record at $keyStr")).pure[F]

        case Some(record) =>
          c.proveKey(key).map {
            case Left(err) =>
              Response[F](Status.InternalServerError).withEntity(ErrorResponse(err.getMessage))

            case Right(proof) =>
              val resp = StateProofResponse(
                key = key.value,
                ordinal = c.ordinal,
                committedRoot = c.roots.combinedHash.asJson,
                mptRoot = c.roots.mptRoot.asJson,
                record = record,
                proof = proof.asJson,
                field = field,
                fieldValue =
                  field.map(f => record.hcursor.downField("stateData").downField(f).focus.getOrElse(Json.Null))
              )
              Response[F](Status.Ok).withEntity(resp)
          }
      }
    }
  }
}
