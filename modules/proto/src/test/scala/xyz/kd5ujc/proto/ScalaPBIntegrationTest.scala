package xyz.kd5ujc.proto

import cats.effect.IO

import io.constellationnetwork.currency.dataApplication.DataUpdate

import ottochain.v1.{CreateStateMachine, TransitionStateMachine}
import weaver.SimpleIOSuite

object ScalaPBIntegrationTest extends SimpleIOSuite {

  test("Generated CreateStateMachine extends DataUpdate") {
    IO {
      val createSM = CreateStateMachine(
        fiberId = "test-fiber-id",
        definition = None,
        initialData = None,
        parentFiberId = None
      )

      // This should compile successfully if DataUpdate mixin works
      val dataUpdate: DataUpdate = createSM

      expect(createSM.fiberId == "test-fiber-id") &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }

  test("Generated TransitionStateMachine extends DataUpdate") {
    IO {
      val transitionSM = TransitionStateMachine(
        fiberId = "test-fiber-id",
        eventName = "test-event",
        payload = None,
        targetSequenceNumber = None
      )

      // This should compile successfully if DataUpdate mixin works
      val dataUpdate: DataUpdate = transitionSM

      expect(transitionSM.fiberId == "test-fiber-id") &&
      expect(transitionSM.eventName == "test-event") &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }
}
