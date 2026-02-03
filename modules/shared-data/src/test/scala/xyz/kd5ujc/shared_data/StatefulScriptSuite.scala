package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser
import weaver.SimpleIOSuite

object StatefulOracleSuite extends SimpleIOSuite {

  test("oracle state initialization: null initial state") {
    val oracleScript = """{"method":{"var":"method"},"result":42}"""

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        prog    <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = fiberId,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        state <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createOracle, createProof))

        oracle = state.calculated.scripts.get(fiberId)
      } yield expect(oracle.isDefined) and
      expect(oracle.map(_.stateData).contains(None)) and
      expect(oracle.map(_.stateDataHash).contains(None)) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue))
    }
  }

  test("oracle state transformation: explicit _state and _result") {
    val oracleScript = """|{
                          |  "_state": {"counter": 5},
                          |  "_result": {"success": true, "newValue": 5}
                          |}""".stripMargin

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        prog    <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        initialData = MapValue(Map("counter" -> IntValue(0)))

        createOracle = Updates.CreateScript(
          fiberId = fiberId,
          scriptProgram = prog,
          initialState = Some(initialData),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = fiberId,
          method = "increment",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scripts.get(fiberId)
        expectedState = MapValue(Map("counter" -> IntValue(5)))
        expectedResult = MapValue(Map("success" -> BoolValue(true), "newValue" -> IntValue(5)))
      } yield expect(oracle.isDefined) and
      expect(oracle.flatMap(_.stateData).contains(expectedState)) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(oracle.flatMap(_.lastInvocation.map(_.result)).contains(expectedResult))
    }
  }

  test("oracle state transformation: simple return value becomes state") {
    val oracleScript = """{"counter": 1}"""

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        prog    <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = fiberId,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = fiberId,
          method = "increment",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scripts.get(fiberId)
        expectedState = MapValue(Map("counter" -> IntValue(1)))
      } yield expect(oracle.isDefined) and
      expect(oracle.flatMap(_.stateData).contains(expectedState)) and
      expect(oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(oracle.flatMap(_.lastInvocation.map(_.result)).contains(expectedState))
    }
  }

  test("oracle state transformation: _state only (result is full response)") {
    val oracleScript = """|{
                          |  "_state": {"visits": 1},
                          |  "message": "Visit recorded"
                          |}""".stripMargin

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        prog    <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        initialData = MapValue(Map("visits" -> IntValue(0)))

        createOracle = Updates.CreateScript(
          fiberId = fiberId,
          scriptProgram = prog,
          initialState = Some(initialData),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = fiberId,
          method = "visit",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- fixture.registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.calculated.scripts.get(fiberId)
        expectedState = MapValue(Map("visits" -> IntValue(1)))
        expectedResult = MapValue(
          Map(
            "_state"  -> MapValue(Map("visits" -> IntValue(1))),
            "message" -> StrValue("Visit recorded")
          )
        )
      } yield expect(oracle.isDefined) and
      expect(oracle.flatMap(_.stateData).contains(expectedState)) and
      expect(oracle.flatMap(_.lastInvocation.map(_.result)).contains(expectedResult))
    }
  }

  test("oracle state persistence: multiple invocations maintain state") {
    val oracleScript = """|{
                          |  "_state": {"callCount": 99},
                          |  "_result": "incremented"
                          |}""".stripMargin

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        prog    <- IO.fromEither(parser.parse(oracleScript).flatMap(_.as[JsonLogicExpression]))

        initialData = MapValue(Map("callCount" -> IntValue(0)))

        createOracle = Updates.CreateScript(
          fiberId = fiberId,
          scriptProgram = prog,
          initialState = Some(initialData),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createOracle, Set(Alice))
        state0 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        // First invocation
        invoke1 = Updates.InvokeScript(fiberId, "inc", MapValue(Map.empty), FiberOrdinal.MinValue)
        proof1 <- fixture.registry.generateProofs(invoke1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(invoke1, proof1))

        oracle1 = state1.calculated.scripts.get(fiberId)

        // Second invocation
        invoke2 = Updates
          .InvokeScript(
            fiberId,
            "inc",
            MapValue(Map.empty),
            state1.calculated.scripts(fiberId).sequenceNumber
          )
        proof2 <- fixture.registry.generateProofs(invoke2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(invoke2, proof2))

        oracle2 = state2.calculated.scripts.get(fiberId)

      } yield expect(oracle1.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(oracle1.flatMap(_.stateData).contains(MapValue(Map("callCount" -> IntValue(99))))) and
      expect(oracle2.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(2L))) and
      expect(oracle2.flatMap(_.stateData).contains(MapValue(Map("callCount" -> IntValue(99))))) and
      expect(oracle2.map(_.lastInvocation.isDefined).contains(true))
    }
  }
}
