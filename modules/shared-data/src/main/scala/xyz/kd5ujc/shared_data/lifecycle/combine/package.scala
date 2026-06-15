package xyz.kd5ujc.shared_data.lifecycle

import io.constellationnetwork.currency.dataApplication.DataState

import xyz.kd5ujc.schema.{CalculatedState, OnChain}

/**
 * Combine package for the lifecycle module.
 *
 * This package separates state combination (insert) concerns by domain:
 * - **Fiber**: State machine fiber creation, event processing, archiving
 * - **Script**: Script script creation and invocation
 *
 * The key pattern mirrors the validation module:
 * - Thin Combiner.scala facade delegates to domain-specific combiners
 * - Syntax extensions provide convenient state update methods
 */
package object combine {

  /** Standard result type for combiner operations */
  type CombineResult[F[_]] = F[DataState[OnChain, CalculatedState]]

}
