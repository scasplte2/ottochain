package xyz.kd5ujc.shared_data.examples

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.testkit.DataStateTestOps
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser
import weaver.SimpleIOSuite

/**
 * Unit tests for the counter script (stateful with non-null initialState).
 *
 * NOTE: The e2e definition uses "count" as a field name, but "count" is a reserved
 * JLVM operation (see metakit JsonLogicOp.scala line 55). Single-key objects like
 * { "count": {...} } are interpreted as operation calls, not object literals.
 *
 * Solution: Use "value" instead of "count" to avoid the reserved operation name.
 */
object CounterScriptSuite extends SimpleIOSuite {

  import DataStateTestOps._

  private val counterScript =
    """|{
       |  "if": [
       |    { "==": [{ "var": "method" }, "increment"] },
       |    { "merge": [{ "var": "state" }, { "value": { "+": [{ "var": "state.value" }, 1] } }] },
       |    { "==": [{ "var": "method" }, "decrement"] },
       |    { "merge": [{ "var": "state" }, { "value": { "-": [{ "var": "state.value" }, 1] } }] },
       |    { "==": [{ "var": "method" }, "reset"] },
       |    { "value": 0 },
       |    { "var": "state" }
       |  ]
       |}""".stripMargin

  private val counterInitialState = MapValue(Map("value" -> IntValue(0)))

  test("creation with initialState") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createScript, Set(Alice))
        state <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createScript, createProof))

        script = state.scriptRecord(cid)
      } yield expect.all(
        script.isDefined,
        script.flatMap(_.stateData).contains(counterInitialState),
        script.flatMap(_.stateDataHash).isDefined,
        script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue)
      )
    }
  }

  test("increment operation (0 -> 1)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createScript, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        invokeScript = Updates.InvokeScript(
          fiberId = cid,
          method = "increment",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeScript, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeScript, invokeProof))

        script = state2.scriptRecord(cid)
        expectedState = MapValue(Map("value" -> IntValue(1)))
      } yield expect.all(
        script.isDefined,
        script.flatMap(_.stateData).contains(expectedState),
        script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)
      )
    }
  }

  test("decrement operation (0 -> -1)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createScript, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        invokeScript = Updates.InvokeScript(
          fiberId = cid,
          method = "decrement",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeScript, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeScript, invokeProof))

        script = state2.scriptRecord(cid)
        expectedState = MapValue(Map("value" -> IntValue(-1)))
      } yield expect.all(
        script.isDefined,
        script.flatMap(_.stateData).contains(expectedState),
        script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)
      )
    }
  }

  test("reset operation (any -> 0)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        // Start with non-zero initial state
        nonZeroInitial = MapValue(Map("value" -> IntValue(42)))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(nonZeroInitial),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createScript, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        invokeScript = Updates.InvokeScript(
          fiberId = cid,
          method = "reset",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeScript, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeScript, invokeProof))

        script = state2.scriptRecord(cid)
        expectedState = MapValue(Map("value" -> IntValue(0)))
      } yield expect.all(
        script.isDefined,
        script.flatMap(_.stateData).contains(expectedState),
        script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)
      )
    }
  }

  test("multiple increments maintain state correctly") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createScript, Set(Alice))
        state0 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        // First increment (0 -> 1)
        invoke1 = Updates.InvokeScript(cid, "increment", MapValue(Map.empty), FiberOrdinal.MinValue)
        proof1 <- registry.generateProofs(invoke1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(invoke1, proof1))

        script1 = state1.scriptRecord(cid)

        // Second increment (1 -> 2)
        invoke2 = Updates.InvokeScript(
          cid,
          "increment",
          MapValue(Map.empty),
          state1.calculated.scripts(cid).sequenceNumber
        )
        proof2 <- registry.generateProofs(invoke2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(invoke2, proof2))

        script2 = state2.scriptRecord(cid)

        // Third increment (2 -> 3)
        invoke3 = Updates.InvokeScript(
          cid,
          "increment",
          MapValue(Map.empty),
          state2.calculated.scripts(cid).sequenceNumber
        )
        proof3 <- registry.generateProofs(invoke3, Set(Alice))
        state3 <- combiner.insert(state2, Signed(invoke3, proof3))

        script3 = state3.scriptRecord(cid)
      } yield expect.all(
        script1.flatMap(_.stateData).contains(MapValue(Map("value" -> IntValue(1)))),
        script2.flatMap(_.stateData).contains(MapValue(Map("value" -> IntValue(2)))),
        script3.flatMap(_.stateData).contains(MapValue(Map("value" -> IntValue(3)))),
        script3.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(3L)),
        script3.flatMap(_.lastInvocation).isDefined
      )
    }
  }

  test("mixed operations (increment, increment, decrement)") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createScript, Set(Alice))
        state0 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        // First increment (0 -> 1)
        invoke1 = Updates.InvokeScript(cid, "increment", MapValue(Map.empty), FiberOrdinal.MinValue)
        proof1 <- registry.generateProofs(invoke1, Set(Alice))
        state1 <- combiner.insert(state0, Signed(invoke1, proof1))

        // Second increment (1 -> 2)
        invoke2 = Updates.InvokeScript(
          cid,
          "increment",
          MapValue(Map.empty),
          state1.calculated.scripts(cid).sequenceNumber
        )
        proof2 <- registry.generateProofs(invoke2, Set(Alice))
        state2 <- combiner.insert(state1, Signed(invoke2, proof2))

        // Decrement (2 -> 1)
        invoke3 = Updates.InvokeScript(
          cid,
          "decrement",
          MapValue(Map.empty),
          state2.calculated.scripts(cid).sequenceNumber
        )
        proof3 <- registry.generateProofs(invoke3, Set(Alice))
        state3 <- combiner.insert(state2, Signed(invoke3, proof3))

        script = state3.scriptRecord(cid)
        expectedState = MapValue(Map("value" -> IntValue(1)))
      } yield expect.all(
        script.isDefined,
        script.flatMap(_.stateData).contains(expectedState),
        script.map(_.sequenceNumber).contains(FiberOrdinal.unsafeApply(3L))
      )
    }
  }

  test("works with non-zero initial value") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        // Start with non-zero value
        initialState = MapValue(Map("value" -> IntValue(5)))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(initialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createScript, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        invokeScript = Updates.InvokeScript(
          fiberId = cid,
          method = "increment",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeScript, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeScript, invokeProof))

        script = state2.scriptRecord(cid)
        expectedState = MapValue(Map("value" -> IntValue(6)))
      } yield expect.all(
        script.isDefined,
        script.flatMap(_.stateData).contains(expectedState),
        script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)
      )
    }
  }

  test("state hash changes after invocation") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createScript, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        scriptBefore = state1.scriptRecord(cid)
        hashBefore = scriptBefore.flatMap(_.stateDataHash)

        invokeScript = Updates.InvokeScript(
          fiberId = cid,
          method = "increment",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeScript, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeScript, invokeProof))

        scriptAfter = state2.scriptRecord(cid)
        hashAfter = scriptAfter.flatMap(_.stateDataHash)
      } yield expect.all(
        hashBefore.isDefined,
        hashAfter.isDefined,
        hashBefore != hashAfter,
        scriptBefore.flatMap(_.stateData) != scriptAfter.flatMap(_.stateData)
      )
    }
  }

  test("signature verification with multiple signers") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        // Create with both Alice and Bob signing
        createProof <- registry.generateProofs(createScript, Set(Alice, Bob))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        script = state1.scriptRecord(cid)
      } yield expect.all(
        script.isDefined,
        script.flatMap(_.stateData).contains(counterInitialState),
        script.map(_.owners.size).contains(2)
      )
    }
  }

  test("invocation by different signer than creator") {
    TestFixture.resource(Set(Alice, Bob)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        // Alice creates the script
        createProof <- registry.generateProofs(createScript, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        invokeScript = Updates.InvokeScript(
          fiberId = cid,
          method = "increment",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        // Bob invokes the script (should work for Public access control)
        invokeProof <- registry.generateProofs(invokeScript, Set(Bob))
        state2      <- combiner.insert(state1, Signed(invokeScript, invokeProof))

        script = state2.scriptRecord(cid)
        expectedState = MapValue(Map("value" -> IntValue(1)))
      } yield expect.all(
        script.isDefined,
        script.flatMap(_.stateData).contains(expectedState),
        script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)
      )
    }
  }

  test("verifies onChain hash is updated correctly") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        cid  <- IO.randomUUID
        prog <- IO.fromEither(parser.parse(counterScript).flatMap(_.as[JsonLogicExpression]))

        createScript = Updates.CreateScript(
          fiberId = cid,
          scriptProgram = prog,
          initialState = Some(counterInitialState),
          accessControl = AccessControlPolicy.Public
        )

        createProof <- registry.generateProofs(createScript, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createScript, createProof)
        )

        // Check onChain has hashes for this script
        initialOnChainHashes = state1.calculated.fiberCommits.get(cid)
        initialStateHash = state1.scriptRecord(cid).flatMap(_.stateDataHash)

        invokeScript = Updates.InvokeScript(
          fiberId = cid,
          method = "increment",
          args = MapValue(Map.empty),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- registry.generateProofs(invokeScript, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeScript, invokeProof))

        // Check onChain hashes were updated
        updatedOnChainHashes = state2.calculated.fiberCommits.get(cid)
        updatedStateHash = state2.scriptRecord(cid).flatMap(_.stateDataHash)
      } yield expect.all(
        initialOnChainHashes.isDefined,
        initialStateHash.isDefined,
        initialOnChainHashes.flatMap(_.stateDataHash) == initialStateHash,
        updatedOnChainHashes.isDefined,
        updatedStateHash.isDefined,
        updatedOnChainHashes.flatMap(_.stateDataHash) == updatedStateHash,
        initialOnChainHashes != updatedOnChainHashes
      )
    }
  }
}
