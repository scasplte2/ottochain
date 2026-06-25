package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

// `policy` is a REQUIRED, NAMED ADT (fiber-policy.md): every definition has exactly one constitution. It
// defaults to FiberPolicy.Unconstrained (today's legacy/unconstrained behaviour). The codec derives normally
// (honouring useDefaults via CodecConfiguration), so an absent `policy` key decodes to Unconstrained. The
// old hand-rolled `Some(empty) ≡ None` normalization is GONE: there is no Option to collapse. The single
// canonical "unconstrained" form is now guaranteed by the FiberPolicy codec itself (its decoder routes an
// all-empty `Custom` through the smart constructor to Unconstrained), so an absent key, an explicit
// `Unconstrained`, and an all-empty `Custom` all encode to the SAME canonical bytes everywhere
// `computeDigest` is taken (verified re-bind #37, spawn child-hash, etc.), regardless of which client wrote
// the definition.
@derive(customizableEncoder, customizableDecoder)
case class StateMachineDefinition(
  states:       Map[StateId, State],
  initialState: StateId,
  transitions:  List[Transition],
  metadata:     Option[JsonLogicValue] = None,
  policy:       FiberPolicy = FiberPolicy.Unconstrained // opt-in, hash-pinned constitution (fiber-policy.md)
) {

  // Helper to get transitions by current state + event type
  // Returns list to support multiple transitions with guards (first-match-wins)
  lazy val transitionMap: Map[(StateId, String), List[Transition]] =
    transitions.groupBy(t => (t.from, t.eventName))
}
