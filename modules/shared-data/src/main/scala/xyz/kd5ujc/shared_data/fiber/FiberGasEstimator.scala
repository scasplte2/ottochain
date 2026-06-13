package xyz.kd5ujc.shared_data.fiber

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicExpression
import io.constellationnetwork.metagraph_sdk.json_logic.gas.{GasConfig, GasMetrics, JsonLogicGasEstimator}

import xyz.kd5ujc.schema.fiber.{StateId, StateMachineDefinition}

/**
 * Static, execution-free fee/gas estimation for fiber operations, built on metakit's
 * [[JsonLogicGasEstimator]]. It never runs the JLVM — a cheap pre-flight "how much gas might this
 * cost" quote so a client can size a fee before submitting.
 *
 * What the underlying estimate counts (and its accuracy) is documented on `JsonLogicGasEstimator`:
 * `base + depthPenalty + varCost`, exact for non-scaling ops and a floor where ops scale; lazy `if`
 * is the single worst branch. The authoritative charge is always the metered evaluation in combine;
 * this is a quote, and (since overage is kept, not refunded) callers should treat it as a ballpark
 * floor and fund a margin.
 */
object FiberGasEstimator {

  /**
   * Estimate the gas a [[xyz.kd5ujc.schema.Updates.TransitionStateMachine]] would charge.
   *
   * For a `(currentState, eventName)` the engine evaluates the candidate transitions' guards in
   * order until one passes, then runs that transition's effect. The worst executed path is therefore
   * `sum(all candidate guards) + max(candidate effects)` — every guard may run, exactly one effect does.
   * Returns [[GasMetrics.zero]] when no transition matches (the update would be rejected, not charged).
   */
  def estimateTransition(
    definition:   StateMachineDefinition,
    currentState: StateId,
    eventName:    String,
    gasConfig:    GasConfig = GasConfig.Default
  ): GasMetrics = {
    val candidates = definition.transitionMap.getOrElse((currentState, eventName), Nil)
    val guards =
      candidates.foldLeft(GasMetrics.zero)((acc, t) => acc.combine(JsonLogicGasEstimator.estimate(t.guard, gasConfig)))
    val maxEffect = candidates
      .map(t => JsonLogicGasEstimator.estimate(t.effect, gasConfig))
      .maxByOption(_.cost.amount)
      .getOrElse(GasMetrics.zero)
    guards.combine(maxEffect)
  }

  /** Estimate the gas an [[xyz.kd5ujc.schema.Updates.InvokeScript]] would charge: the script program. */
  def estimateScript(
    scriptProgram: JsonLogicExpression,
    gasConfig:     GasConfig = GasConfig.Default
  ): GasMetrics =
    JsonLogicGasEstimator.estimate(scriptProgram, gasConfig)
}
