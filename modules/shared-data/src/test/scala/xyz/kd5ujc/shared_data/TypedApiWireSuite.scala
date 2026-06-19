package xyz.kd5ujc.shared_data

import java.time.Instant
import java.util.UUID

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.Updates.{ArchiveStateMachine, OttochainMessage}
import xyz.kd5ujc.schema.api._
import xyz.kd5ujc.schema.api.webhooks._
import xyz.kd5ujc.schema.fiber.FiberOrdinal

import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax._
import weaver.SimpleIOSuite

/**
 * Golden wire-shape tests for the typed HTTP DTOs (`xyz.kd5ujc.schema.api`).
 *
 * These pin the EXACT JSON field names each response serializes to — the names are the contract the
 * TypeScript SDK binds to, so a silent rename here would break clients. Mirrors the intent of
 * [[PublishVersionSigningCanonicalSuite]] for signed messages, but for the (unsigned) transport DTOs.
 * Add a case for any new response DTO. See `docs/proposals/typed-network-interface.md`.
 */
object TypedApiWireSuite extends SimpleIOSuite {

  private def keysOf(j: Json): Set[String] = j.asObject.map(_.keys.toSet).getOrElse(Set.empty)

  pureTest("VersionInfo — wire keys") {
    val keys = keysOf(VersionInfo("ottochain-ml0", "v", "n", "s", "sb", "g", "b", "t").asJson)
    expect(
      keys == Set(
        "service",
        "version",
        "name",
        "scalaVersion",
        "sbtVersion",
        "gitCommit",
        "buildTime",
        "tessellationVersion"
      )
    )
  }

  pureTest("HashResult — preserves the space-keys and round-trips") {
    val msg: OttochainMessage =
      ArchiveStateMachine(UUID.fromString("00000000-0000-0000-0000-000000000001"), FiberOrdinal.unsafeApply(1L))
    val dto = HashResult(Hash("0" * 64), msg)
    val j = dto.asJson
    expect.all(
      keysOf(j) == Set("protocol message hash", "protocol message"),
      decode[HashResult](j.noSpaces) == Right(dto)
    )
  }

  pureTest("TransitionFeeEstimate — wire keys") {
    val keys = keysOf(TransitionFeeEstimate(UUID.randomUUID(), "s", "e", 1L, 2, 3, 4, "note").asJson)
    expect(
      keys == Set(
        "fiberId",
        "currentState",
        "event",
        "gasEstimate",
        "opCount",
        "maxDepth",
        "candidateTransitions",
        "note"
      )
    )
  }

  pureTest("ScriptFeeEstimate — wire keys") {
    val keys = keysOf(ScriptFeeEstimate(UUID.randomUUID(), 1L, 2, 3, "note").asJson)
    expect(keys == Set("scriptId", "gasEstimate", "opCount", "maxDepth", "note"))
  }

  pureTest("StateProofResponse — omits field/fieldValue when no field requested") {
    val dto = StateProofResponse(
      "fiber/x",
      SnapshotOrdinal.MinValue,
      Json.fromString("c"),
      Json.fromString("m"),
      Json.obj("a" -> 1.asJson),
      Json.arr()
    )
    expect(keysOf(dto.asJson) == Set("key", "ordinal", "committedRoot", "mptRoot", "record", "proof"))
  }

  pureTest("StateProofResponse — includes field/fieldValue when a field is requested") {
    // committedRoot/mptRoot/record/proof are never null in production (hash strings / objects), which is
    // why the derived encoder's top-level null-drop only ever omits the optional field/fieldValue.
    val dto = StateProofResponse(
      "fiber/x",
      SnapshotOrdinal.MinValue,
      Json.fromString("c"),
      Json.fromString("m"),
      Json.obj("a" -> 1.asJson),
      Json.arr(),
      Some("balance"),
      Some(Json.fromInt(5))
    )
    expect(
      keysOf(dto.asJson) == Set("key", "ordinal", "committedRoot", "mptRoot", "record", "proof", "field", "fieldValue")
    )
  }

  pureTest("ErrorResponse — wire shape") {
    val j = ErrorResponse("boom").asJson
    expect.all(keysOf(j) == Set("error"), j.hcursor.get[String]("error") == Right("boom"))
  }

  pureTest("SubscriberList — wraps under `subscribers`") {
    val sub = Subscriber("sub_1", "https://x", None, active = true, Instant.EPOCH, None, 0)
    expect(keysOf(SubscriberList(List(sub)).asJson) == Set("subscribers"))
  }

  pureTest("Subscriber — wire keys") {
    val sub = Subscriber("sub_1", "https://x", Some("s"), active = true, Instant.EPOCH, Some(Instant.EPOCH), 3)
    val keys = keysOf(sub.asJson)
    expect(keys == Set("id", "callbackUrl", "secret", "active", "createdAt", "lastDeliveryAt", "failCount"))
  }

  pureTest("SubscribeResponse — wire keys") {
    val keys = keysOf(SubscribeResponse("sub_1", "https://x", Instant.EPOCH).asJson)
    expect(keys == Set("id", "callbackUrl", "createdAt"))
  }
}
