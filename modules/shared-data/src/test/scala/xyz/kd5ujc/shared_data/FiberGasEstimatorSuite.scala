package xyz.kd5ujc.shared_data

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicExpression

import xyz.kd5ujc.schema.fiber.{StateId, StateMachineDefinition}
import xyz.kd5ujc.shared_data.fiber.FiberGasEstimator

import io.circe.parser.decode
import weaver.SimpleIOSuite

object FiberGasEstimatorSuite extends SimpleIOSuite {

  private def parseDef(s: String): StateMachineDefinition =
    decode[StateMachineDefinition](s).fold(e => throw new RuntimeException(s"parse def: $e"), identity)

  // pending --finish--> done : trivial effect
  // pending --bump----> pending : heavier effect (nested arithmetic over state + a constant)
  private val defn = parseDef("""
    {
      "states": {"pending":{"id":"pending","isFinal":false},"done":{"id":"done","isFinal":true}},
      "initialState": "pending",
      "transitions": [
        {"from":"pending","to":"done","eventName":"finish","guard":{"==":[1,1]},"effect":{"status":"done"},"dependencies":[]},
        {"from":"pending","to":"pending","eventName":"bump","guard":{"==":[1,1]},"effect":{"n":{"+":[{"var":"state.n"},{"*":[2,3]}]}},"dependencies":[]}
      ]
    }
  """)

  test("estimateTransition: a matching event yields a positive estimate") {
    IO.pure(expect(FiberGasEstimator.estimateTransition(defn, StateId("pending"), "finish").cost.amount > 0L))
  }

  test("estimateTransition: a heavier effect estimates higher than a cheaper one") {
    val cheap = FiberGasEstimator.estimateTransition(defn, StateId("pending"), "finish").cost.amount
    val heavy = FiberGasEstimator.estimateTransition(defn, StateId("pending"), "bump").cost.amount
    IO.pure(expect(heavy > cheap))
  }

  test("estimateTransition: no matching transition -> zero (would be rejected, not charged)") {
    IO.pure(expect(FiberGasEstimator.estimateTransition(defn, StateId("done"), "finish").cost.amount == 0L))
  }

  test("estimateScript: the program expression is estimated") {
    val prog =
      decode[JsonLogicExpression]("""{"+":[{"var":"x"},1]}""").fold(e => throw new RuntimeException(s"$e"), identity)
    IO.pure(expect(FiberGasEstimator.estimateScript(prog).cost.amount > 0L))
  }
}
