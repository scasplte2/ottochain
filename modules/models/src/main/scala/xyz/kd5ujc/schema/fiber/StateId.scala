package xyz.kd5ujc.schema.fiber

import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

case class StateId(value: String) extends AnyVal

object StateId {
  // Encode as plain string (canonical wire format)
  implicit val encoder: Encoder[StateId] = Encoder.encodeString.contramap(_.value)

  // Accept both plain string ("state") and wrapped object ({"value": "state"})
  // The wrapped format was used in TDD test fixtures; both are valid on input.
  implicit val decoder: Decoder[StateId] =
    Decoder.decodeString
      .map(StateId(_))
      .or(
        Decoder.forProduct1("value")(StateId(_))
      )

  implicit val keyEncoder: KeyEncoder[StateId] = KeyEncoder.encodeKeyString.contramap(_.value)
  implicit val keyDecoder: KeyDecoder[StateId] = KeyDecoder.decodeKeyString.map(StateId(_))
}
