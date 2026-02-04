package xyz.kd5ujc.proto

import io.circe.{Decoder, Encoder, Json}
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import scalapb_circe.JsonFormat

/**
 * Circe JSON codecs for ScalaPB-generated protobuf messages.
 *
 * Import these implicits to get automatic JSON encoding/decoding
 * for any protobuf message type.
 *
 * Usage:
 * {{{
 * import xyz.kd5ujc.proto.JsonCodecs._
 * import ottochain.v1._
 *
 * val msg = CreateStateMachine(fiberId = "test-123", ...)
 * val json: Json = msg.asJson
 * val decoded: Either[DecodingFailure, CreateStateMachine] = json.as[CreateStateMachine]
 * }}}
 */
object JsonCodecs {

  /** Encoder for any ScalaPB GeneratedMessage */
  implicit def protoEncoder[T <: GeneratedMessage]: Encoder[T] =
    Encoder.instance { msg =>
      JsonFormat.toJson(msg)
    }

  /** Decoder for any ScalaPB GeneratedMessage with a companion object */
  implicit def protoDecoder[T <: GeneratedMessage](implicit
    companion: GeneratedMessageCompanion[T]
  ): Decoder[T] =
    Decoder.instance { cursor =>
      cursor.as[Json].flatMap { json =>
        try
          Right(JsonFormat.fromJson[T](json))
        catch {
          case e: Exception =>
            Left(io.circe.DecodingFailure(s"Failed to decode protobuf: ${e.getMessage}", cursor.history))
        }
      }
    }
}
