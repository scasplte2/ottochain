package xyz.kd5ujc.schema.fiber

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

@derive(encoder, decoder)
case class VotingTransition(
  from:         StateId,
  to:           StateId,
  eventName:    String,
  guard:        JsonLogicExpression,
  effect:       JsonLogicExpression,
  dependencies: List[JsonLogicExpression] = List.empty
)
