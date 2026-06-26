package xyz.kd5ujc.shared_data

import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO

import xyz.kd5ujc.schema.Updates
import xyz.kd5ujc.schema.Updates.OttochainMessage
import xyz.kd5ujc.schema.fiber.{FiberPolicy, StateMachineDefinition, TransitionPolicy}
import xyz.kd5ujc.shared_data.lifecycle.validate.RegistryValidator

import io.circe.parser.{decode, parse}
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import weaver.SimpleIOSuite

/**
 * Regression for the PublishVersion signing canonical (the DL1-400 behind PR #155's e2e):
 * a client signs `JCS(dropNulls(payload))`; the chain verifies over `JCS(dropNulls(encode(decode(payload))))`.
 * If decode->encode injects a field the client omitted, `dropNulls` cannot reconcile it, the canonicals
 * diverge, and every PublishVersion is rejected `InvalidSignature`.
 *
 * The invariant this guards: a signed-message field is either `Option[T]` (None -> null -> dropped, omit-safe)
 * or REQUIRED (no default, the client must send it). A non-`Option` field with a default (e.g. the old
 * `repeated/optional/strict: Boolean = false`, or `metadata: SortedMap = empty`) re-encodes to a concrete
 * value the client omitted and breaks the signature. `harnessJson` is exactly what
 * e2e-test/lib/registry/publishVersion.ts now sends for order-v1.
 */
object PublishVersionSigningCanonicalSuite extends SimpleIOSuite {

  private val harnessJson: String =
    """{"PublishMachineVersion":{
      |  "name":"order.package",
      |  "version":"1.0.0",
      |  "schemaB64":"ZGVzY3JpcHRvcjpvcmRlci5wYWNrYWdlOjEuMC4w",
      |  "machineShape":{
      |    "stateMessage":{"typeName":"Order","fields":[
      |      {"name":"orderId","number":1,"typeName":"string","repeated":false,"optional":false},
      |      {"name":"customer","number":2,"typeName":"string","repeated":false,"optional":false},
      |      {"name":"total","number":3,"typeName":"int64","repeated":false,"optional":false},
      |      {"name":"trackingNumber","number":4,"typeName":"string","repeated":false,"optional":true},
      |      {"name":"deliveredAt","number":5,"typeName":"string","repeated":false,"optional":true},
      |      {"name":"cancelReason","number":6,"typeName":"string","repeated":false,"optional":true}
      |    ]},
      |    "commands":{
      |      "confirm":{"typeName":"Confirm","fields":[]},
      |      "ship":{"typeName":"Ship","fields":[{"name":"trackingNumber","number":1,"typeName":"string","repeated":false,"optional":false}]},
      |      "deliver":{"typeName":"Deliver","fields":[{"name":"timestamp","number":1,"typeName":"string","repeated":false,"optional":false}]},
      |      "cancel":{"typeName":"Cancel","fields":[{"name":"reason","number":1,"typeName":"string","repeated":false,"optional":false}]}
      |    }
      |  },
      |  "definition":{
      |    "states":{
      |      "pending":{"id":"pending","isFinal":false},
      |      "confirmed":{"id":"confirmed","isFinal":false},
      |      "shipped":{"id":"shipped","isFinal":false},
      |      "delivered":{"id":"delivered","isFinal":true},
      |      "cancelled":{"id":"cancelled","isFinal":true}
      |    },
      |    "initialState":"pending",
      |    "transitions":[
      |      {"from":"pending","to":"confirmed","eventName":"confirm","guard":{"==":[1,1]},"effect":{},"dependencies":[]},
      |      {"from":"confirmed","to":"shipped","eventName":"ship","guard":{"==":[1,1]},"effect":{"trackingNumber":{"var":"event.trackingNumber"}},"dependencies":[]},
      |      {"from":"shipped","to":"delivered","eventName":"deliver","guard":{"==":[1,1]},"effect":{"deliveredAt":{"var":"event.timestamp"}},"dependencies":[]},
      |      {"from":"pending","to":"cancelled","eventName":"cancel","guard":{"==":[1,1]},"effect":{"cancelReason":{"var":"event.reason"}},"dependencies":[]}
      |    ]
      |  },
      |  "strict":false
      |}}""".stripMargin

  /** Mirrors metakit JsonBinaryCodec.dropNulls: drop null object fields (array entries preserved). */
  private def dropNulls(j: Json): Json =
    j.arrayOrObject(
      j,
      arr => Json.fromValues(arr.map(dropNulls)),
      obj =>
        Json.fromJsonObject(
          JsonObject.fromIterable(obj.toIterable.collect { case (k, v) if !v.isNull => k -> dropNulls(v) })
        )
    )

  test("harness PublishVersion decodes against the chain OttochainMessage decoder") {
    IO.pure(decode[OttochainMessage](harnessJson) match {
      case Right(_)  => success
      case Left(err) => failure(s"DECODE FAILED: $err")
    })
  }

  test("chain verify-canonical == harness signed-canonical (no injected defaults)") {
    val parsed = parse(harnessJson).toOption.get
    val reencoded = decode[OttochainMessage](harnessJson).toOption.get.asJson
    IO.pure(expect.same(dropNulls(reencoded), dropNulls(parsed)))
  }

  test("harness PublishVersion passes DL1 (L1) stateless validation") {
    decode[OttochainMessage](harnessJson) match {
      case Right(pv: Updates.PublishMachineVersion) =>
        new RegistryValidator.L1Validator[IO].publishMachineVersion(pv).map {
          case Valid(_)      => success
          case Invalid(errs) => failure(s"L1 REJECTED: ${errs.toNonEmptyList.toList.map(_.toString).mkString(" | ")}")
        }
      case Right(other) => IO.pure(failure(s"decoded to unexpected type: ${other.getClass.getSimpleName}"))
      case Left(err)    => IO.pure(failure(s"DECODE FAILED: $err"))
    }
  }

  // ── F7 transitionPolicy dial — rule #1 canonical guards (03-cross-fiber-and-authorization.md §5) ──────
  // The dial is a new signed-message surface inside StateMachineDefinition.policy (FiberPolicy.Constrained).
  // It MUST be Option/omit-safe: an ABSENT dial encodes byte-identically to the pre-change canonical (None →
  // null → stripped by dropNulls); a SET dial round-trips through its bare string tag.

  // A definition exactly as a pre-dial client wrote it — no `policy` key, no `transitionPolicy`. Guards
  // ({"==":[1,1]}) + empty effect are the round-trip-proven shapes from `harnessJson`.
  private val defNoPolicyJson: String =
    """{
      |  "states":{"s0":{"id":"s0","isFinal":false},"s1":{"id":"s1","isFinal":false}},
      |  "initialState":"s0",
      |  "transitions":[{"from":"s0","to":"s1","eventName":"ping","guard":{"==":[1,1]},"effect":{},"dependencies":[]}]
      |}""".stripMargin

  test("absent transitionPolicy: decode->encode injects nothing (byte-identical canonical)") {
    val parsed = parse(defNoPolicyJson).toOption.get
    val reencoded = decode[StateMachineDefinition](defNoPolicyJson).toOption.get.asJson
    IO.pure(
      expect.same(dropNulls(reencoded), dropNulls(parsed)) and
      expect(!dropNulls(reencoded).noSpaces.contains("transitionPolicy")) and
      expect(!dropNulls(reencoded).noSpaces.contains("policy"))
    )
  }

  test("set transitionPolicy = Owners round-trips and emits its bare string tag") {
    val base = decode[StateMachineDefinition](defNoPolicyJson).toOption.get
    val withDial = base.copy(policy = FiberPolicy.constrained(transitionPolicy = Some(TransitionPolicy.Owners)))
    val encoded = withDial.asJson
    IO.pure(
      expect(decode[StateMachineDefinition](encoded.noSpaces) == Right(withDial)) and
      expect(dropNulls(encoded).noSpaces.contains("\"transitionPolicy\":\"Owners\"")) and
      // a SET dial does NOT collapse to Unconstrained — the `policy` key is present
      expect(dropNulls(encoded).noSpaces.contains("\"policy\""))
    )
  }
}
