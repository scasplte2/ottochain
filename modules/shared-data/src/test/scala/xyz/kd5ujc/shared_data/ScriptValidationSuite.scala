package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.registry.{RegistryName, SchemaRef, VersionReq}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.{Combiner, Validator}
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

  // audit C3 / CLAUDE.md rule #3: `validateSignedUpdate` (the block-acceptance gate) no longer reads the
  // registry version lineage for a bound CreateScript. A ref that does not resolve PASSES the gate — a
  // concurrent third-party publish/yank must NOT be able to flip a once-valid CreateScript to Invalid and
  // poison the WHOLE DL1 block (tessellation all-or-nothing). The ref+hash bind is re-verified GRACEFULLY in
  // `ScriptCombiner.resolveScriptBinding` (CombineRejected -> RejectionReceipt); the script is not created.
  test("C3: bound CreateScript with an unresolvable registry ref passes validateSignedUpdate; combiner rejects") {
    TestFixture.resource(Set(Alice)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      for {
        combiner  <- Combiner.make[IO]().pure[IO]
        validator <- Validator.make[IO]()
        fiberId   <- IO.randomUUID

        createUpdate = Updates.CreateScript(
          fiberId = fiberId,
          scriptProgram = ConstExpression(MapValue(Map("result" -> IntValue(1)))),
          initialState = None,
          accessControl = AccessControlPolicy.Public,
          // points at a registry name that does not exist in state — combiner will reject the bind
          schemaRef = Some(SchemaRef(RegistryName.unsafe("ghost.script"), VersionReq.Latest))
        )
        createProof <- fixture.registry.generateProofs(createUpdate, Set(Alice))
        genesis = DataState(OnChain.genesis, CalculatedState.genesis)

        // ML0 gate: does NOT resolve the ref (no lineage read) -> valid, block not poisoned
        gateResult <- validator.validateSignedUpdate(genesis, Signed(createUpdate, createProof))
        // combiner (authoritative): unresolvable bind -> graceful CombineRejected, script not created
        combined <- combiner.insert(genesis, Signed(createUpdate, createProof))
        rejected = combined.onChain.latestLogs.values.flatten.exists {
          case _: FiberLogEntry.RejectionReceipt => true
          case _                                 => false
        }
      } yield expect(gateResult.isValid) and
      expect(combined.calculated.scripts.get(fiberId).isEmpty) and
      expect(rejected)
    }
  }
}
