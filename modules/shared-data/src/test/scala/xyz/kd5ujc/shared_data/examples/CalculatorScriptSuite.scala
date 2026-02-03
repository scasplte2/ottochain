package xyz.kd5ujc.shared_data.examples

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
import xyz.kd5ujc.shared_data.testkit.DataStateTestOps
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser
import weaver.SimpleIOSuite

/**
 * Unit tests for the calculator oracle (stateless - initialState: null).
 * Based on e2e-test/examples/calculator-oracle/definition.json
 */
object CalculatorOracleSuite extends SimpleIOSuite {

  import DataStateTestOps._

  // Calculator Oracle definition (stateless) - matches e2e definition
  private val calculatorScript =
    """|{
       |  "if": [
       |    { "==": [{ "var": "method" }, "add"] },
       |    { "+": [{ "var": "args.a" }, { "var": "args.b" }] },
       |    { "==": [{ "var": "method" }, "subtract"] },
       |    { "-": [{ "var": "args.a" }, { "var": "args.b" }] },
       |    { "==": [{ "var": "method" }, "multiply"] },
       |    { "*": [{ "var": "args.a" }, { "var": "args.b" }] },
       |    { "==": [{ "var": "method" }, "divide"] },
       |    { "/": [{ "var": "args.a" }, { "var": "args.b" }] },
       |    null
       |  ]
       |}""".stripMargin

  test("add operation (10 + 5 = 15)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = cid,
          method = "add",
          args = MapValue(Map("a" -> IntValue(10), "b" -> IntValue(5))),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.oracleRecord(cid)
        result = oracle.flatMap(_.lastInvocation.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(IntValue(15)),
        oracle.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)
      )
    }
  }

  test("subtract operation (20 - 8 = 12)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = cid,
          method = "subtract",
          args = MapValue(Map("a" -> IntValue(20), "b" -> IntValue(8))),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.oracleRecord(cid)
        result = oracle.flatMap(_.lastInvocation.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(IntValue(12))
      )
    }
  }

  test("multiply operation (7 * 6 = 42)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = cid,
          method = "multiply",
          args = MapValue(Map("a" -> IntValue(7), "b" -> IntValue(6))),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.oracleRecord(cid)
        result = oracle.flatMap(_.lastInvocation.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(IntValue(42))
      )
    }
  }

  test("divide operation (100 / 4 = 25)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = cid,
          method = "divide",
          args = MapValue(Map("a" -> IntValue(100), "b" -> IntValue(4))),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.oracleRecord(cid)
        result = oracle.flatMap(_.lastInvocation.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(IntValue(25))
      )
    }
  }

  test("unknown method returns null") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = cid,
          method = "unknownMethod",
          args = MapValue(Map("a" -> IntValue(1), "b" -> IntValue(2))),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeOracle, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.oracleRecord(cid)
        result = oracle.flatMap(_.lastInvocation.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(NullValue)
      )
    }
  }

  test("multiple invocations are logged") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state0 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        // First invocation: add
        invoke1 = Updates.InvokeScript(
          cid,
          "add",
          MapValue(Map("a" -> IntValue(1), "b" -> IntValue(2))),
          FiberOrdinal.MinValue
        )
        proof1 <- registry.generateProofs(invoke1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(invoke1, proof1))

        // Second invocation: multiply
        invoke2 = Updates.InvokeScript(
          cid,
          "multiply",
          MapValue(Map("a" -> IntValue(3), "b" -> IntValue(4))),
          state1.calculated.scripts(cid).sequenceNumber
        )
        proof2 <- registry.generateProofs(invoke2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(invoke2, proof2))

        // Third invocation: subtract
        invoke3 = Updates.InvokeScript(
          cid,
          "subtract",
          MapValue(Map("a" -> IntValue(10), "b" -> IntValue(5))),
          state2.calculated.scripts(cid).sequenceNumber
        )
        proof3 <- registry.generateProofs(invoke3, Set(Alice))
        state3 <- combiner.insert(state2, Signed(invoke3, proof3))

        oracle = state3.oracleRecord(cid)
      } yield expect.all(
        oracle.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(3L)),
        oracle.flatMap(_.lastInvocation).isDefined
      )
    }
  }

  test("invocation by different signer (Public access)") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(calculatorScript).flatMap(_.as[JsonLogicExpression]))

        createOracle = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        // Alice creates
        createProof <- registry.generateProofs(createOracle, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createOracle, createProof)
        )

        invokeOracle = Updates.InvokeScript(
          fiberId = cid,
          method = "add",
          args = MapValue(Map("a" -> IntValue(5), "b" -> IntValue(3))),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        // Bob invokes
        invokeProof <- registry.generateProofs(invokeOracle, Set(Bob))
        state2      <- combiner.insert(state1, Signed(invokeOracle, invokeProof))

        oracle = state2.oracleRecord(cid)
        result = oracle.flatMap(_.lastInvocation.map(_.result))
      } yield expect.all(
        oracle.isDefined,
        result.contains(IntValue(8))
      )
    }
  }
}
