package xyz.kd5ujc.schema.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicValue
import io.constellationnetwork.schema.address.Address

/**
 * Unified input for fiber processing.
 * Supports both state machine transitions and script method calls.
 */
sealed trait FiberInput {
  val key: String
  val content: JsonLogicValue
}

object FiberInput {

  /**
   * State machine transition input.
   *
   * @param event Event type to trigger the transitionupdate F
   * @param payload Payload data for the event
   */
  final case class Transition(
    event:   String,
    payload: JsonLogicValue
  ) extends FiberInput {
    val key: String = event
    val content: JsonLogicValue = payload
  }

  /**
   * Script method call input.
   *
   * @param method Method name to invoke
   * @param args Arguments for the method
   * @param caller Address of the caller
   */
  final case class MethodCall(
    method: String,
    args:   JsonLogicValue,
    caller: Address
  ) extends FiberInput {
    val key: String = method
    val content: JsonLogicValue = args
  }
}
