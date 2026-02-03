package xyz.kd5ujc.schema.fiber

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

@derive(encoder, decoder)
case class VotingStateMachineDefinition(
  states:       Map[StateId, VotingState],
  initialState: StateId,
  transitions:  List[VotingTransition]
) {
  lazy val transitionMap: Map[(StateId, String), List[VotingTransition]] =
    transitions.groupBy(t => (t.from, t.eventName))
}
