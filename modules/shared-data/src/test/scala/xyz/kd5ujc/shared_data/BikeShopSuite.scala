package xyz.kd5ujc.shared_data

import java.nio.file.{Files, Paths}
import java.util.UUID

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.Updates._
import xyz.kd5ujc.schema.asset._
import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.{Participant, TestFixture}

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax._
import weaver.SimpleIOSuite

/**
 * Combiner-level proof of the `bike-shop` e2e example (e2e-test/examples/bike-shop): the purchasing mandate
 * pays a supplier's order-desk fiber with a held store-credit piece, the order-desk spawns a delivery tracker,
 * the supplier dispatches goods to the shop owner's wallet, and the delivery signals back to the mandate.
 *
 * Exercises the mechanics the film narrates that no other suite composes: a DYNAMIC `heldAssets` path
 * (`var` over `cat`), a `_transferAsset` recipient and `_triggers` target taken from the same `event.payee`,
 * a `_spawn` inside a TRIGGERED transition, wallet→fiber custody via ApplyMorphism Transfer, and a
 * `$caller`-gated signal-back from a spawned child.
 *
 * Roles: Alice = shop owner, Bob = purchasing agent, Charlie = supplier, Dave = stranger.
 */
object BikeShopSuite extends SimpleIOSuite {
  private type State = DataState[OnChain, CalculatedState]

  private val example = Iterator
    .iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .map(_.resolve("e2e-test/examples/bike-shop"))
    .find(Files.isDirectory(_))
    .get

  private val mandateId = UUID.fromString("b1ce0000-0000-4000-8000-00000000a001")
  private val deskId = UUID.fromString("b1ce0000-0000-4000-8000-00000000d001")
  private val deliveryId = UUID.fromString("b1ce0000-0000-4000-8000-00000000de01")
  private val creditId = UUID.fromString("b1ce0000-0000-4000-8000-00000000c000")
  private val shards = (1 to 5).toList.map(i => UUID.fromString(f"b1ce0000-0000-4000-8000-00000000c0$i%02d"))
  private val padsId = UUID.fromString("b1ce0000-0000-4000-8000-00000000ba01")

  private def asset(n: String) = RegistryName.unsafe(s"$n.asset")

  private val stateShape =
    MessageShape("Asset.State", List(FieldShape("amount", 1, "int64", repeated = false, optional = false)))

  private val openSupply =
    SupplyPolicy(None, Some(ConstExpression(BoolValue(true))), Some(ConstExpression(BoolValue(true))), Some(0))

  private val morphisms: SortedMap[MorphismKind, MorphismSpec] = SortedMap(
    MorphismKind.Transfer      -> MorphismSpec(MorphismVisibility.Public, None, None, None),
    MorphismKind.Fractionalize -> MorphismSpec(MorphismVisibility.Public, None, None, None),
    MorphismKind.Compose       -> MorphismSpec(MorphismVisibility.Public, None, None, None)
  )

  private class Harness(fixture: TestFixture) {
    implicit private val security: SecurityProvider[IO] = fixture.securityProvider
    implicit private val context: L0NodeContext[IO] = fixture.l0Context
    private val combiner = Combiner.make[IO]()

    def addr(p:    Participant): String = fixture.registry.addresses(p).value.value
    def sm(s:      State, id: UUID) = s.calculated.stateMachines.get(id)
    def holder(s:  State, id: UUID) = s.calculated.assets.get(id).map(_.holder)
    def field(s:   State, id: UUID, f: String) = sm(s, id).flatMap(_.stateData.asJson.hcursor.downField(f).focus)
    def receipt(s: State, id: UUID) = sm(s, id).flatMap(_.lastReceipt)

    def apply(s: State, msg: OttochainMessage, by: Participant*): IO[State] =
      fixture.registry.generateProofs(msg, by.toSet).flatMap(p => combiner.insert(s, Signed(msg, p)))

    private def definition(file: String) =
      IO.fromEither(decode[StateMachineDefinition](Files.readString(example.resolve(file))))
    private def data(j: Json) = IO.fromEither(j.as[JsonLogicValue])

    def setup: IO[State] = for {
      mandateDef <- definition("mandate.definition.json")
      deskDef    <- definition("order-desk.definition.json")
      mandateData <- data(
        Json.obj(
          "owner"              -> addr(Alice).asJson,
          "agent"              -> addr(Bob).asJson,
          "approvedSuppliers"  -> List(deskId.toString).asJson,
          "perOrderLimitCents" -> 2500.asJson,
          "remainingCents"     -> 10000.asJson,
          "expiresAt"          -> 100000.asJson,
          "lastRequestNumber"  -> 0.asJson
        )
      )
      deskData <- data(
        Json.obj("supplier" -> addr(Charlie).asJson, "ordersReceived" -> 0.asJson, "dispatched" -> 0.asJson)
      )
      s0 = DataState(OnChain.genesis, CalculatedState.genesis)
      s1 <- apply(
        s0,
        CreateAssetPolicy(
          asset("shop-credit"),
          SemVer(1, 0, 0),
          TokenBehavior.Fungible,
          openSupply,
          morphisms,
          stateShape
        ),
        Alice
      )
      s2 <- apply(
        s1,
        CreateAssetPolicy(
          asset("brake-pads"),
          SemVer(1, 0, 0),
          TokenBehavior.fromBits(20),
          openSupply,
          morphisms,
          stateShape
        ),
        Charlie
      )
      s3 <- apply(s2, CreateStateMachine(mandateId, mandateDef, mandateData), Alice)
      s4 <- apply(s3, CreateStateMachine(deskId, deskDef, deskData), Charlie)
      s5 <- apply(
        s4,
        MintAsset(
          creditId,
          SchemaRef(asset("shop-credit"), VersionReq.Latest),
          AssetHolder.Wallet(fixture.registry.addresses(Alice)),
          10000L
        ),
        Alice
      )
      s6 <- apply(
        s5,
        ApplyMorphism(creditId, MorphismKind.Fractionalize, FiberOrdinal.MinValue, shardIds = Some(shards)),
        Alice
      )
      s7 <- apply(
        s6,
        ApplyMorphism(
          shards.head,
          MorphismKind.Transfer,
          FiberOrdinal.MinValue,
          recipient = Some(AssetHolder.Fiber(mandateId))
        ),
        Alice
      )
      s8 <- apply(
        s7,
        ApplyMorphism(
          shards(1),
          MorphismKind.Transfer,
          FiberOrdinal.MinValue,
          recipient = Some(AssetHolder.Fiber(mandateId))
        ),
        Alice
      )
      s9 <- apply(
        s8,
        MintAsset(padsId, SchemaRef(asset("brake-pads"), VersionReq.Latest), AssetHolder.Fiber(deskId), 1L),
        Charlie
      )
    } yield s9

    def authorize(s: State, n: Int, cents: Int, payee: UUID, piece: UUID, item: String = "brake pads") =
      TransitionStateMachine(
        mandateId,
        "authorize",
        MapValue(
          Map(
            "requestNumber" -> IntValue(n),
            "amountCents"   -> IntValue(cents),
            "payee"         -> StrValue(payee.toString),
            "creditId"      -> StrValue(piece.toString),
            "deliveryId"    -> StrValue(deliveryId.toString),
            "item"          -> StrValue(item)
          )
        ),
        sm(s, mandateId).get.sequenceNumber
      )

    def event(s: State, id: UUID, name: String, payload: Map[String, JsonLogicValue] = Map.empty) =
      TransitionStateMachine(id, name, MapValue(payload), sm(s, id).get.sequenceNumber)
  }

  test("probe: fractionalized credit lands in the mandate fiber via wallet→fiber Transfer") {
    TestFixture.resource(Set(Alice, Bob, Charlie, Dave)).use { fixture =>
      val h = new Harness(fixture)
      h.setup.map { s =>
        expect(h.holder(s, shards.head).contains(AssetHolder.Fiber(mandateId))) and
        expect(s.calculated.assets.get(shards.head).map(_.amount).contains(2000L)) and
        expect(h.holder(s, padsId).contains(AssetHolder.Fiber(deskId)))
      }
    }
  }

  test("authorize pays the desk and triggers receive_order atomically") {
    TestFixture.resource(Set(Alice, Bob, Charlie, Dave)).use { fixture =>
      val h = new Harness(fixture)
      for {
        s   <- h.setup
        out <- h.apply(s, h.authorize(s, 1, 2000, deskId, shards.head), Bob)
        _   <- IO.println(s"authorize receipt: ${h.receipt(out, mandateId)}; desk: ${h.receipt(out, deskId)}")
      } yield expect(h.holder(out, shards.head).contains(AssetHolder.Fiber(deskId))) and
      expect(h.field(out, mandateId, "remainingCents").contains(8000.asJson)) and
      expect(h.field(out, deskId, "ordersReceived").contains(1.asJson)) and
      expect(h.sm(out, deliveryId).isEmpty) // cascaded transitions never spawn (TriggerHandler); dispatch does
    }
  }

  test("probe: poisoned invoice (unlisted payee, $500) and a mismatched credit piece are refused, nothing moves") {
    TestFixture.resource(Set(Alice, Bob, Charlie, Dave)).use { fixture =>
      val h = new Harness(fixture)
      val elsewhere = UUID.fromString("b1ce0000-0000-4000-8000-0000000bad01")
      for {
        s        <- h.setup
        poisoned <- h.apply(s, h.authorize(s, 1, 50000, elsewhere, shards.head), Bob)
        mismatch <- h.apply(s, h.authorize(s, 1, 1500, deskId, shards.head), Bob)
        stranger <- h.apply(s, h.authorize(s, 1, 2000, deskId, shards.head), Dave)
      } yield List(poisoned, mismatch, stranger)
        .map { after =>
          expect(h.sm(after, mandateId).map(_.stateData) == h.sm(s, mandateId).map(_.stateData)) and
          expect(h.holder(after, shards.head).contains(AssetHolder.Fiber(mandateId))) and
          expect(h.field(after, deskId, "ordersReceived").contains(0.asJson)) and
          expect(h.sm(after, deliveryId).isEmpty)
        }
        .reduce(_ and _)
    }
  }

  test("probe: dispatch ships goods to the owner wallet; deliver signals back to the mandate; revoke ends it") {
    TestFixture.resource(Set(Alice, Bob, Charlie, Dave)).use { fixture =>
      val h = new Harness(fixture)
      for {
        s       <- h.setup
        ordered <- h.apply(s, h.authorize(s, 1, 2000, deskId, shards.head), Bob)
        forged  <- h.apply(ordered, h.event(ordered, mandateId, "delivery_received"), Dave)
        dispatch = h.event(
          ordered,
          deskId,
          "dispatch",
          Map("deliveryId" -> StrValue(deliveryId.toString), "goodsId" -> StrValue(padsId.toString))
        )
        shipped   <- h.apply(ordered, dispatch, Charlie)
        delivered <- h.apply(shipped, h.event(shipped, deliveryId, "deliver"), Charlie)
        revoked   <- h.apply(delivered, h.event(delivered, mandateId, "revoke"), Alice)
        retry     <- h.apply(revoked, h.authorize(revoked, 2, 2000, deskId, shards(1)), Bob)
        _ <- IO.println(
          s"dispatch desk receipt: ${h.receipt(shipped, deskId)}; deliver: ${h.receipt(delivered, deliveryId)}"
        )
      } yield expect(h.field(forged, mandateId, "lastDelivery").isEmpty) and
      expect(h.holder(shipped, padsId).contains(AssetHolder.Wallet(fixture.registry.addresses(Alice)))) and
      expect(h.sm(shipped, deliveryId).map(_.currentState).contains(StateId("in_transit"))) and
      expect(h.sm(shipped, deliveryId).map(_.owners) == h.sm(shipped, deskId).map(_.owners)) and
      expect(h.sm(delivered, deliveryId).map(_.currentState).contains(StateId("delivered"))) and
      expect(
        h.field(delivered, mandateId, "lastDelivery")
          .flatMap(_.hcursor.get[String]("deliveryId").toOption)
          .contains(deliveryId.toString)
      ) and
      expect(h.sm(revoked, mandateId).map(_.currentState).contains(StateId("revoked"))) and
      expect(h.holder(retry, shards(1)).contains(AssetHolder.Fiber(mandateId)))
    }
  }

  test("repair kit: pads (T|C) composed with an expiring voucher (T|C|E) is the meet (22) and stays transferable") {
    TestFixture.resource(Set(Alice, Bob, Charlie, Dave)).use { fixture =>
      val h = new Harness(fixture)
      val voucherId = UUID.fromString("b1ce0000-0000-4000-8000-00000000f001")
      val kitId = UUID.fromString("b1ce0000-0000-4000-8000-00000000c1d1")
      val alice = AssetHolder.Wallet(fixture.registry.addresses(Alice))
      val rosa = AssetHolder.Wallet(fixture.registry.addresses(Dave))
      for {
        s0 <- h.setup
        s1 <- h.apply(
          s0,
          CreateAssetPolicy(
            asset("tune-up-voucher"),
            SemVer(1, 0, 0),
            TokenBehavior.fromBits(22),
            openSupply,
            morphisms,
            stateShape
          ),
          Charlie
        )
        s2 <- h
          .apply(s1, MintAsset(voucherId, SchemaRef(asset("tune-up-voucher"), VersionReq.Latest), alice, 1L), Charlie)
        s3 <- h.apply(
          s2,
          MintAsset(
            UUID.fromString("b1ce0000-0000-4000-8000-00000000ba02"),
            SchemaRef(asset("brake-pads"), VersionReq.Latest),
            alice,
            1L
          ),
          Charlie
        )
        pads2 = UUID.fromString("b1ce0000-0000-4000-8000-00000000ba02")
        s4 <- h.apply(
          s3,
          ApplyMorphism(
            pads2,
            MorphismKind.Compose,
            FiberOrdinal.MinValue,
            otherAssetIds = Some(List(voucherId)),
            compositeId = Some(kitId)
          ),
          Alice
        )
        kitSeq = s4.calculated.assets.get(kitId).map(_.sequenceNumber).getOrElse(FiberOrdinal.MinValue)
        s5 <- h.apply(s4, ApplyMorphism(kitId, MorphismKind.Transfer, kitSeq, recipient = Some(rosa)), Alice)
        _ <- IO
          .println(s"kit: ${s4.calculated.assets.get(kitId).map(a => (a.behavior, a.amount))}")
      } yield expect(s4.calculated.assets.get(kitId).map(_.behavior.bits).contains(22)) and
      expect(s4.calculated.assets.get(pads2).isEmpty) and expect(s4.calculated.assets.get(voucherId).isEmpty) and
      expect(h.holder(s5, kitId).contains(rosa))
    }
  }
}
