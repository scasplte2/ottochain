package xyz.kd5ujc.shared_data

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.metagraph_sdk.json_logic._

import xyz.kd5ujc.schema.registry.{FieldShape, MachineShape, MessageShape}
import xyz.kd5ujc.shared_data.fiber.ConformanceChecker
import xyz.kd5ujc.shared_data.lifecycle.validate.rules.RegistryRules

import weaver.SimpleIOSuite

/**
 * "Standard testing" against the REAL ottochain-sdk app state schemas (identity / governance / markets,
 * from proto/ottochain/apps/&#42;/v1). Proves the registry's on-chain shape validation
 * ([[RegistryRules.L1.schemaShapeWellFormed]]) and the #33 [[ConformanceChecker]] gate work with
 * production-shaped schemas — scalars, int64/int32, enums, nested + repeated messages, well-known
 * Timestamp/Struct — not just toy fixtures.
 *
 * SCOPE: this is the on-chain MACHINERY check. The full integration — real FileDescriptorSet descriptors,
 * buf compatibility/evolution checks, the Bridge registration ceremony, and cross-version classification —
 * is the SDK lib's job (#34). Here we model each app's state message as the MachineShape the Bridge would
 * derive, and exercise it on-chain.
 */
object StandardAppsConformanceSuite extends SimpleIOSuite {

  // ── Faithful MachineShape projections of the SDK app state messages ────────────────────────────

  // ottochain.apps.identity.v1.Identity
  val identity: MessageShape = MessageShape(
    "ottochain.apps.identity.v1.Identity",
    List(
      FieldShape("id", 1, "string", repeated = false, optional = false),
      FieldShape("address", 2, "string", repeated = false, optional = false),
      FieldShape("public_key", 3, "string", repeated = false, optional = false),
      FieldShape("display_name", 4, "string", repeated = false, optional = false),
      FieldShape("identity_type", 5, "ottochain.apps.identity.v1.IdentityType", repeated = false, optional = false),
      FieldShape("state", 6, "ottochain.apps.identity.v1.IdentityState", repeated = false, optional = false),
      FieldShape("reputation", 7, "ottochain.apps.identity.v1.Reputation", repeated = false, optional = false),
      FieldShape("stake", 8, "int64", repeated = false, optional = false),
      FieldShape("domains", 9, "string", repeated = true, optional = false),
      FieldShape("platform_links", 10, "ottochain.apps.identity.v1.PlatformLink", repeated = true, optional = false),
      FieldShape("penalty_history", 11, "ottochain.apps.identity.v1.PenaltyEvent", repeated = true, optional = false),
      FieldShape("created_at", 12, "google.protobuf.Timestamp", repeated = false, optional = false),
      FieldShape("updated_at", 13, "google.protobuf.Timestamp", repeated = false, optional = false)
    )
  )

  // ottochain.apps.governance.v1.Proposal
  val proposal: MessageShape = MessageShape(
    "ottochain.apps.governance.v1.Proposal",
    List(
      FieldShape("id", 1, "string", repeated = false, optional = false),
      FieldShape("title", 2, "string", repeated = false, optional = false),
      FieldShape("description", 3, "string", repeated = false, optional = false),
      FieldShape("action_type", 4, "string", repeated = false, optional = false),
      FieldShape("payload", 5, "google.protobuf.Struct", repeated = false, optional = false),
      FieldShape("proposer", 6, "string", repeated = false, optional = false),
      FieldShape("proposed_at", 7, "google.protobuf.Timestamp", repeated = false, optional = false),
      FieldShape("deadline", 8, "google.protobuf.Timestamp", repeated = false, optional = false),
      FieldShape("queued_at", 9, "google.protobuf.Timestamp", repeated = false, optional = false),
      FieldShape("executable_at", 10, "google.protobuf.Timestamp", repeated = false, optional = false)
    )
  )

  // ottochain.apps.markets.v1.Market
  val market: MessageShape = MessageShape(
    "ottochain.apps.markets.v1.Market",
    List(
      FieldShape("id", 1, "string", repeated = false, optional = false),
      FieldShape("market_type", 2, "ottochain.apps.markets.v1.MarketType", repeated = false, optional = false),
      FieldShape("creator", 3, "string", repeated = false, optional = false),
      FieldShape("title", 4, "string", repeated = false, optional = false),
      FieldShape("terms", 5, "google.protobuf.Struct", repeated = false, optional = false),
      FieldShape("deadline", 6, "google.protobuf.Timestamp", repeated = false, optional = false),
      FieldShape("threshold", 7, "int64", repeated = false, optional = false),
      FieldShape("commitments", 8, "ottochain.apps.markets.v1.Commitment", repeated = true, optional = false),
      FieldShape("oracles", 9, "string", repeated = true, optional = false),
      FieldShape("quorum", 10, "int32", repeated = false, optional = false),
      FieldShape("resolutions", 11, "ottochain.apps.markets.v1.Resolution", repeated = true, optional = false),
      FieldShape("status", 12, "ottochain.apps.markets.v1.MarketState", repeated = false, optional = false),
      FieldShape("created_at", 13, "google.protobuf.Timestamp", repeated = false, optional = false),
      FieldShape("updated_at", 14, "google.protobuf.Timestamp", repeated = false, optional = false)
    )
  )

  private def shapeOf(state: MessageShape): MachineShape = MachineShape(state, SortedMap.empty)

  test("every standard-app MachineShape is well-formed (valid + unique proto field numbers, named)") {
    for {
      i <- RegistryRules.L1.machineShapeWellFormed[IO](shapeOf(identity))
      g <- RegistryRules.L1.machineShapeWellFormed[IO](shapeOf(proposal))
      m <- RegistryRules.L1.machineShapeWellFormed[IO](shapeOf(market))
    } yield expect(i.isValid) and expect(g.isValid) and expect(m.isValid)
  }

  test("a realistic Identity state conforms; wrong-type / undeclared / scalar-for-repeated do not") {
    val ok = MapValue(
      Map(
        "id"             -> StrValue("did:otto:agent-1"),
        "address"        -> StrValue("DAG7abc"),
        "display_name"   -> StrValue("Agent Smith"),
        "identity_type"  -> StrValue("IDENTITY_TYPE_AGENT"), // enum as string -> shallow accepts
        "state"          -> StrValue("IDENTITY_STATE_ACTIVE"),
        "reputation"     -> MapValue(Map("score" -> IntValue(80))), // nested message -> map -> shallow accepts
        "stake"          -> IntValue(1000),
        "domains"        -> ArrayValue(List(StrValue("defi"), StrValue("trading"))),
        "platform_links" -> ArrayValue(List(MapValue(Map("verified" -> BoolValue(true))))),
        "created_at"     -> StrValue("2026-01-01T00:00:00Z") // Timestamp as RFC3339 -> shallow accepts
      )
    )
    val wrongType = MapValue(Map("stake" -> StrValue("lots"))) // int64 declared, got string
    val undeclared = MapValue(Map("admin" -> BoolValue(true))) // not in Identity
    val scalarForRepeated = MapValue(Map("domains" -> StrValue("defi"))) // repeated declared, got scalar
    IO.pure(
      expect(ConformanceChecker.check(identity, ok).isEmpty) and
      expect(ConformanceChecker.check(identity, wrongType).nonEmpty) and
      expect(ConformanceChecker.check(identity, undeclared).nonEmpty) and
      expect(ConformanceChecker.check(identity, scalarForRepeated).nonEmpty)
    )
  }

  test("a realistic Market state conforms; an int32 quorum given as a string does not") {
    val ok = MapValue(
      Map(
        "id"          -> StrValue("mkt-1"),
        "market_type" -> StrValue("MARKET_TYPE_PREDICTION"),
        "creator"     -> StrValue("DAG7abc"),
        "title"       -> StrValue("Will X happen by EOY?"),
        "terms"       -> MapValue(Map("resolves" -> StrValue("2026-12-31"))), // Struct -> map -> shallow accepts
        "threshold"   -> IntValue(100),
        "commitments" -> ArrayValue(List.empty),
        "oracles"     -> ArrayValue(List(StrValue("DAG8def"))),
        "quorum"      -> IntValue(3),
        "status"      -> StrValue("MARKET_STATE_OPEN")
      )
    )
    val badQuorum = MapValue(Map("quorum" -> StrValue("three")))
    IO.pure(
      expect(ConformanceChecker.check(market, ok).isEmpty) and
      expect(ConformanceChecker.check(market, badQuorum).nonEmpty)
    )
  }

  test("a realistic governance Proposal state conforms") {
    val ok = MapValue(
      Map(
        "id"          -> StrValue("prop-1"),
        "title"       -> StrValue("Raise the gas cap"),
        "description" -> StrValue("..."),
        "action_type" -> StrValue("PARAM_CHANGE"),
        "payload"     -> MapValue(Map("gasCap" -> IntValue(20000000))), // Struct -> map -> shallow accepts
        "proposer"    -> StrValue("DAG7abc"),
        "proposed_at" -> StrValue("2026-06-01T00:00:00Z")
      )
    )
    IO.pure(expect(ConformanceChecker.check(proposal, ok).isEmpty))
  }
}
