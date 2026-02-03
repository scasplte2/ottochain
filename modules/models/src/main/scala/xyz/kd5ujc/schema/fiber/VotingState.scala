package xyz.kd5ujc.schema.fiber

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

@derive(encoder, decoder)
case class VotingState(
  candidates: List[String],
  votes: Map[String, Int] = Map.empty
)
