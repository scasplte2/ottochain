package xyz.kd5ujc.shared_data

/**
 * Test utilities for OttoChain state machine tests.
 *
 * == Quick Start ==
 * {{{
 * import xyz.kd5ujc.shared_data.testkit._
 *
 * // Build a fiber with minimal boilerplate:
 * val fiber = FiberBuilder(fiberId, ordinal, definition)
 *   .withState("draft")
 *   .withData("status" -> StrValue("draft"), "count" -> IntValue(0))
 *   .ownedBy(registry, Alice, Bob)
 *   .build[IO]
 *
 * // Transition + sign + insert in one call:
 * val nextState = state.transition[IO](
 *   fiberId       = machineCid,
 *   event     = "submit",
 *   payload   = MapValue(Map("timestamp" -> IntValue(100))),
 *   signer    = Alice,
 *   registry  = registry,
 *   combiner  = combiner
 * )
 *
 * // Extract fields from fibers:
 * val score = state.fiberRecord(machineCid).extractInt("score")
 * val name  = state.fiberRecord(machineCid).extractString("name")
 * }}}
 *
 * == What's Included ==
 *
 *  - [[FiberBuilder]] — construct `StateMachineFiberRecord` with sensible defaults
 *  - [[DataStateOps]] — extension methods for state transitions and fiber lookups
 *  - Reexports of `FiberExtractors` from `shared-test`
 */
package object testkit
