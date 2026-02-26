package xyz.kd5ujc.schema.fiber

import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

case class StateId(value: String) extends AnyVal

object StateId {
  implicit val encoder: Encoder[StateId] = Encoder.encodeString.contramap(_.value)
  implicit val decoder: Decoder[StateId] = Decoder.decodeString.map(StateId(_))
  implicit val keyEncoder: KeyEncoder[StateId] = KeyEncoder.encodeKeyString.contramap(_.value)
  implicit val keyDecoder: KeyDecoder[StateId] = KeyDecoder.decodeKeyString.map(StateId(_))
}
