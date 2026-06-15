package xyz.kd5ujc.shared_data

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
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.parser
import weaver.SimpleIOSuite

object ScriptValidationSuite extends SimpleIOSuite {

  test("create script with public access") {
    val scriptSource =
      """|{"if":[
         |  {"==":[{"var":"method"},"validate"]},
         |  {">=":[{"var":"args.value"},10]},
         |  false
         |]}""".stripMargin

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        prog    <- IO.fromEither(parser.parse(scriptSource).flatMap(_.as[JsonLogicExpression]))

        createUpdate = Updates.CreateScript(
          fiberId = fiberId,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        state <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(createUpdate, createProof))

        script = state.calculated.scripts.get(fiberId)
      } yield expect(script.isDefined) and
      expect(script.map(_.status).contains(FiberStatus.Active)) and
      expect(script.map(_.owners).contains(Set(fixture.registry.addresses(Alice))))
    }
  }

  test("invoke script with validation method") {
    val scriptSource =
      """|{"if":[
         |  {"==":[{"var":"method"},"validate"]},
         |  {">=":[{"var":"args.value"},10]},
         |  false
         |]}""".stripMargin

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        prog    <- IO.fromEither(parser.parse(scriptSource).flatMap(_.as[JsonLogicExpression]))

        createUpdate = Updates.CreateScript(
          fiberId = fiberId,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createUpdate, createProof)
        )

        invokeUpdate = Updates.InvokeScript(
          fiberId = fiberId,
          method = "validate",
          args = MapValue(Map("value" -> IntValue(15))),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- fixture.registry.generateProofs(invokeUpdate, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeUpdate, invokeProof))

        script = state2.calculated.scripts.get(fiberId)
        lastInvocation = script.flatMap(_.lastInvocation)

      } yield expect(script.isDefined) and
      expect(script.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next)) and
      expect(lastInvocation.isDefined) and
      expect(lastInvocation.map(_.method).contains("validate")) and
      expect(
        lastInvocation
          .flatMap(inv =>
            inv.result match {
              case BoolValue(v) => Some(v)
              case _            => None
            }
          )
          .contains(true)
      )
    }
  }

  test("invoke script validation fails when value too low") {
    val scriptSource =
      """|{"if":[
         |  {"==":[{"var":"method"},"validate"]},
         |  {">=":[{"var":"args.value"},10]},
         |  false
         |]}""".stripMargin

    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner <- Combiner.make[IO]().pure[IO]

        fiberId <- IO.randomUUID
        prog    <- IO.fromEither(parser.parse(scriptSource).flatMap(_.as[JsonLogicExpression]))

        createUpdate = Updates.CreateScript(
          fiberId = fiberId,
          scriptProgram = prog,
          initialState = None,
          accessControl = AccessControlPolicy.Public
        )

        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        state1 <- combiner.insert(
          DataState(OnChain.genesis, CalculatedState.genesis),
          Signed(createUpdate, createProof)
        )

        invokeUpdate = Updates.InvokeScript(
          fiberId = fiberId,
          method = "validate",
          args = MapValue(Map("value" -> IntValue(5))),
          targetSequenceNumber = FiberOrdinal.MinValue
        )

        invokeProof <- fixture.registry.generateProofs(invokeUpdate, Set(Alice))
        state2      <- combiner.insert(state1, Signed(invokeUpdate, invokeProof))

        lastInvocation = state2.calculated.scripts.get(fiberId).flatMap(_.lastInvocation)

      } yield expect(
        lastInvocation
          .flatMap(inv =>
            inv.result match {
              case BoolValue(v) => Some(v)
              case _            => None
            }
          )
          .contains(false)
      )
    }
  }
}
