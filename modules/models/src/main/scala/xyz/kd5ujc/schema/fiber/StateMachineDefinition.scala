package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import io.circe.{Decoder, Encoder}

case class StateMachineDefinition(
  states:       Map[StateId, State],
  initialState: StateId,
  transitions:  List[Transition],
  metadata:     Option[JsonLogicValue] = None,
  policy: Option[FiberPolicy] = None // opt-in, hash-pinned constitution (fiber-policy.md); None = legacy/unconstrained
) {

  // Helper to get transitions by current state + event type
  // Returns list to support multiple transitions with guards (first-match-wins)
  lazy val transitionMap: Map[(StateId, String), List[Transition]] =
    transitions.groupBy(t => (t.from, t.eventName))
}

object StateMachineDefinition {

  // We hand-roll the codecs (rather than @derive) so the `policy` field passes through FiberPolicy.normalize
  // on BOTH encode and decode, making Some(FiberPolicy.empty) ≡ None at the byte level. This is the
  // load-bearing B3 fix: computeDigest(def.copy(policy = Some(empty))) is byte-identical to
  // computeDigest(def.copy(policy = None)) EVERYWHERE computeDigest is taken (verified re-bind #37, spawn
  // child-hash, etc.), regardless of which client wrote the definition. The structural base reuses the SAME
  // configured derevo derivation (honouring useDefaults = true via CodecConfiguration), so an absent `policy`
  // key still decodes to None and `dropNulls` still drops a None policy on serialize.
  private val derivedDecoder: Decoder[StateMachineDefinition] = customizableDecoder.instance
  private val derivedEncoder: Encoder[StateMachineDefinition] = customizableEncoder.instance

  implicit val decoder: Decoder[StateMachineDefinition] =
    derivedDecoder.map(d => d.copy(policy = FiberPolicy.normalize(d.policy)))

  implicit val encoder: Encoder[StateMachineDefinition] =
    derivedEncoder.contramap(d => d.copy(policy = FiberPolicy.normalize(d.policy)))
}
