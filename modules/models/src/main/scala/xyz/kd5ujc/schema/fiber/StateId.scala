package xyz.kd5ujc.schema.fiber

import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

case class StateId(value: String) extends AnyVal

object StateId {
  // Plain string is the one canonical wire form, on both encode and decode. A StateId is part of the
  // signed CreateStateMachine canonical; accepting an alternate input shape (e.g. {"value":"state"}) would
  // let a client sign one shape that the chain silently re-encodes to another -> divergent bytes ->
  // InvalidSignature. So decode is strict: only the plain string the encoder produces is accepted.
  implicit val encoder: Encoder[StateId] = Encoder.encodeString.contramap(_.value)
  implicit val decoder: Decoder[StateId] = Decoder.decodeString.map(StateId(_))

  implicit val keyEncoder: KeyEncoder[StateId] = KeyEncoder.encodeKeyString.contramap(_.value)
  implicit val keyDecoder: KeyDecoder[StateId] = KeyDecoder.decodeKeyString.map(StateId(_))
}
