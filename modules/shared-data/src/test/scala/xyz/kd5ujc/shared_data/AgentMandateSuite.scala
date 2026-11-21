package xyz.kd5ujc.shared_data

import java.nio.file.{Files, Paths}
import java.util.UUID

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.{Participant, TestFixture}

import io.circe.parser.{decode, parse}
import io.circe.syntax._
import weaver.SimpleIOSuite

object AgentMandateSuite extends SimpleIOSuite {
  private type State = DataState[OnChain, CalculatedState]

  private val example = Iterator
    .iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .map(_.resolve("e2e-test/examples/agent-mandate"))
    .find(Files.isDirectory(_))
    .get

  private def exercise(expiry: Option[Long])(check: (Harness, State) => IO[weaver.Expectations]) =
    TestFixture.resource(Set(Alice, Bob, Charlie, Dave)).use { fixture =>
      for {
        id <- UUIDGen.randomUUID[IO]
        harness = new Harness(id, fixture)
        initial <- harness.create(expiry)
        result  <- check(harness, initial)
      } yield result
    }

  private class Harness(id: UUID, fixture: TestFixture) {
    implicit private val security: SecurityProvider[IO] = fixture.securityProvider
    implicit private val context: L0NodeContext[IO] = fixture.l0Context
    private val combiner = Combiner.make[IO]()
    def record(s:    State) = s.calculated.stateMachines(id)
    def remaining(s: State) = record(s).stateData.asJson.hcursor.get[Int]("remainingCents").toOption

    def refused(before: State, after: State) =
      expect(record(after).stateData == record(before).stateData) and
      expect(record(after).sequenceNumber == record(before).sequenceNumber) and
      expect(record(after).lastReceipt.exists(r => !r.success && r.errorMessage.exists(_.contains("No guard matched"))))

    def create(expiry: Option[Long]): IO[State] = for {
      definition <- IO.fromEither(decode[StateMachineDefinition](Files.readString(example.resolve("definition.json"))))
      rules      <- IO.fromEither(parse(Files.readString(example.resolve("rules.json"))))
      roles = Map("owner" -> Alice, "agent" -> Bob, "cosigner" -> Charlie).map { case (role, wallet) =>
        role -> fixture.registry.addresses(wallet).value.value.asJson
      }
      data <- IO.fromEither(
        rules
          .deepMerge(roles.asJson)
          .deepMerge(expiry.map(n => Map("expiresAt" -> n).asJson).getOrElse(Map.empty[String, Long].asJson))
          .as[JsonLogicValue]
      )
      create = Updates.CreateStateMachine(id, definition, data)
      proofs <- fixture.registry.generateProofs(create, Set(Alice))
      state  <- combiner.insert(DataState(OnChain.genesis, CalculatedState.genesis), Signed(create, proofs))
    } yield state

    def payment(s: State, cents: JsonLogicValue, number: Int, payee: String = "supplier-a") =
      Updates.TransitionStateMachine(
        id,
        "authorize",
        MapValue(Map("amountCents" -> cents, "requestNumber" -> IntValue(number), "payee" -> StrValue(payee))),
        record(s).sequenceNumber
      )
    def revoke(s: State) = Updates.TransitionStateMachine(id, "revoke", MapValue(Map.empty), record(s).sequenceNumber)

    def sign(update: Updates.TransitionStateMachine, parties: Set[Participant]) =
      fixture.registry.generateProofs(update, parties).map(Signed(update, _))

    def applySigned(s: State, action: Signed[Updates.TransitionStateMachine]): IO[State] =
      combiner.insert(s, action)

    def submit(s: State, update: Updates.TransitionStateMachine, parties: Participant*): IO[State] =
      sign(update, parties.toSet).flatMap(applySigned(s, _))
  }

  test("signed mandate spends 10000 to 8000 to 4000 only after co-signing and rejects replay and revoked use") {
    exercise(None) { (h, initial) =>
      for {
        paid       <- h.submit(initial, h.payment(initial, IntValue(2000), 1), Bob)
        wrongPayee <- h.submit(paid, h.payment(paid, IntValue(2000), 2, "other"), Bob)
        stranger   <- h.submit(wrongPayee, h.payment(wrongPayee, IntValue(2000), 2), Dave)
        high = h.payment(stranger, IntValue(4000), 2)
        missingCosigner <- h.submit(stranger, high, Bob)
        wrongCosigner   <- h.submit(missingCosigner, high, Bob, Dave)
        signed          <- h.sign(high, Set(Bob, Charlie))
        cosigned        <- h.applySigned(wrongCosigner, signed)
        replay          <- h.applySigned(cosigned, signed)
        excess          <- h.submit(replay, h.payment(replay, IntValue(4001), 3), Bob, Charlie)
        deniedRevoke    <- h.submit(excess, h.revoke(excess), Bob)
        revoked         <- h.submit(deniedRevoke, h.revoke(deniedRevoke), Alice)
        after           <- h.submit(revoked, h.payment(revoked, IntValue(1000), 3), Bob)
        _ <- IO.println(
          s"Signed combiner decisions: ${List(initial, paid, wrongPayee, stranger, missingCosigner, cosigned, after)
              .map(h.remaining(_).get)
              .mkString(" -> ")} cents; ${h.record(wrongPayee).lastReceipt.flatMap(_.errorMessage)}"
        )
      } yield expect(h.remaining(initial).contains(10000)) and expect(h.remaining(paid).contains(8000)) and
      h.refused(paid, wrongPayee) and h.refused(wrongPayee, stranger) and h.refused(stranger, missingCosigner) and h
        .refused(missingCosigner, wrongCosigner) and
      expect(h.remaining(cosigned).contains(4000)) and expect(h.record(replay) == h.record(cosigned)) and
      h.refused(replay, excess) and h.refused(excess, deniedRevoke) and
      expect(h.record(revoked).currentState == StateId("revoked")) and h.refused(revoked, after)
    }
  }

  test("positive whole cents and the inclusive solo threshold") {
    exercise(None) { (h, s) =>
      for {
        fraction <- IO.fromEither(decode[JsonLogicValue]("2.5"))
        bad <- List[JsonLogicValue](IntValue(0), IntValue(-1), fraction, StrValue("20"), IntValue(2501))
          .traverse(amount => h.submit(s, h.payment(s, amount, 1), Bob))
        ok <- h.submit(s, h.payment(s, IntValue(2500), 1, "supplier-b"), Bob)
      } yield bad.map(h.refused(s, _)).reduce(_ and _) and expect(h.remaining(ok).contains(7500))
    }
  }

  test("expiry at the current snapshot ordinal denies even a co-signed request") {
    exercise(Some(1001L)) { (h, s) =>
      h.submit(s, h.payment(s, IntValue(4000), 1), Bob, Charlie).map(h.refused(s, _))
    }
  }
}
