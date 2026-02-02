package xyz.kd5ujc.shared_data.testkit

import io.constellationnetwork.currency.dataApplication.DataState

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records}
import xyz.kd5ujc.shared_test.FiberExtractors

/**
 * Convenience re-exports for test utilities.
 *
 * Single import to get all test helpers:
 * {{{
 * import xyz.kd5ujc.shared_data.testkit.TestImports._
 * }}}
 *
 * This brings into scope:
 *  - `FiberBuilder` — fluent fiber construction
 *  - `DataStateOps` — transition/lookup extension methods on DataState
 *  - `StateMachineFiberRecordOps` — field extraction on fiber records
 *  - `OptionFiberRecordOps` — field extraction on Option[fiber records]
 */
object TestImports {

  // Re-export DataState extension methods
  implicit def dataStateTestOps(
    state: DataState[OnChain, CalculatedState]
  ): DataStateTestOps.DataStateOps = new DataStateTestOps.DataStateOps(state)

  // Re-export FiberExtractors from shared-test
  implicit def fiberRecordOps(
    fiber: Records.StateMachineFiberRecord
  ): FiberExtractors.StateMachineFiberRecordOps =
    new FiberExtractors.StateMachineFiberRecordOps(fiber)

  implicit def optionFiberRecordOps(
    maybeFiber: Option[Records.StateMachineFiberRecord]
  ): FiberExtractors.OptionFiberRecordOps =
    new FiberExtractors.OptionFiberRecordOps(maybeFiber)
}
