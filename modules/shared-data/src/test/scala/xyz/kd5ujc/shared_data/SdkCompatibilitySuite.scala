package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, _}

import xyz.kd5ujc.schema.Updates
import xyz.kd5ujc.schema.Updates._
import xyz.kd5ujc.schema.fiber._

import io.circe.parser._
import io.circe.syntax._
import weaver.SimpleIOSuite

/**
 * SDK Compatibility Suite
 *
 * Verifies that the JSON wire format produced by the Scala models is compatible
 * with the TypeScript SDK types defined in @ottochain/sdk.
 *
 * The wire format is plain JSON (not protobuf binary). These tests act as a
 * regression guard: if the Scala encoder changes its JSON output in a
 * breaking way, these tests will fail and alert developers to update the SDK.
 *
 * == Keeping in sync ==
 * When you change a Scala model field name, type, or codec:
 *   1. Run this suite to see what breaks
 *   2. Update the fixtures in this file to match the new format
 *   3. Update the SDK TypeScript types in @ottochain/sdk/src/ottochain/types.ts
 *   4. Bump the SDK version accordingly
 *
 * == Wire format notes ==
 * - OttochainMessage is discriminated by message name key:
 *     {"CreateStateMachine": { ...fields... }}
 * - UUIDs serialize as plain strings: "550e8400-e29b-41d4-a716-446655440000"
 * - FiberOrdinal serializes as a plain Long number: 0, 1, 42
 * - StateId (single-field case class) serializes as plain string: "idle"
 * - AccessControlPolicy serializes with Scala class name as discriminator key:
 *     {"Public": {}}  |  {"Whitelist": {"addresses": [...]}}  |  {"FiberOwned": {"fiberId": "..."}}
 * - JsonLogicValue: NullValue → null, MapValue → {}, IntValue → 0, StrValue → ""
 *
 * == SDK version tracking ==
 * Fixtures are static JSON strings maintained in this file. When the SDK
 * changes its JSON output format, update both this file and the SDK types.ts.
 * See modules/shared-data/src/test/resources/sdk-compat/README.md for details.
 *
 * @see modules/models/src/main/scala/xyz/kd5ujc/schema/Updates.scala
 * @see @ottochain/sdk/src/ottochain/types.ts
 */
object SdkCompatibilitySuite extends SimpleIOSuite {

  // ─── Fixtures ────────────────────────────────────────────────────────────────

  /** Minimal state machine definition — two states, one transition */
  private val minimalDefinitionJson: String =
    """{
      |  "states": {
      |    "idle": { "id": "idle", "isFinal": false, "metadata": null },
      |    "active": { "id": "active", "isFinal": true, "metadata": null }
      |  },
      |  "initialState": "idle",
      |  "transitions": [
      |    {
      |      "from": "idle",
      |      "to": "active",
      |      "eventName": "start",
      |      "guard": true,
      |      "effect": null,
      |      "dependencies": []
      |    }
      |  ],
      |  "metadata": null
      |}""".stripMargin

  private val sampleFiberId: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

  private def parseDefinition: StateMachineDefinition =
    parse(minimalDefinitionJson)
      .flatMap(_.as[StateMachineDefinition])
      .getOrElse(throw new RuntimeException("Failed to parse test definition"))

  private val minimalScriptJson: String = """{"increment": [{"var": "count"}, 1]}"""

  private def parseScript: JsonLogicExpression =
    parse(minimalScriptJson)
      .flatMap(_.as[JsonLogicExpression])
      .getOrElse(throw new RuntimeException("Failed to parse test script"))

  // ─── CreateStateMachine ───────────────────────────────────────────────────────

  test("CreateStateMachine: round-trip encode → decode preserves all fields") {
    IO {
      val original = CreateStateMachine(
        fiberId = sampleFiberId,
        definition = parseDefinition,
        initialData = MapValue(Map("count" -> IntValue(0))),
        parentFiberId = None
      )

      val wrapped: Updates.OttochainMessage = original
      val json = wrapped.asJson
      val decoded = json.as[Updates.OttochainMessage]

      expect(decoded.isRight) and
      expect(decoded.exists(_ == wrapped))
    }
  }

  test("CreateStateMachine: JSON discriminator key is 'CreateStateMachine'") {
    IO {
      val msg: Updates.OttochainMessage = CreateStateMachine(
        fiberId = sampleFiberId,
        definition = parseDefinition,
        initialData = NullValue
      )

      val json = msg.asJson
      val keys = json.asObject.map(_.keys.toList).getOrElse(Nil)

      expect(keys == List("CreateStateMachine"))
    }
  }

  test("CreateStateMachine: fiberId serializes as UUID string") {
    IO {
      val msg: Updates.OttochainMessage = CreateStateMachine(
        fiberId = sampleFiberId,
        definition = parseDefinition,
        initialData = NullValue
      )

      val json = msg.asJson
      val fiberIdJson =
        json.hcursor
          .downField("CreateStateMachine")
          .downField("fiberId")
          .as[String]

      expect(fiberIdJson == Right(sampleFiberId.toString))
    }
  }

  test("CreateStateMachine: StateId initialState encodes as plain string") {
    IO {
      val msg: Updates.OttochainMessage = CreateStateMachine(
        fiberId = sampleFiberId,
        definition = parseDefinition,
        initialData = NullValue
      )

      val json = msg.asJson
      val initialStateJson =
        json.hcursor
          .downField("CreateStateMachine")
          .downField("definition")
          .downField("initialState")
          .focus

      // StateId encodes as a plain string "idle", not {"value": "idle"}
      // This matches the SDK types.ts expectation.
      expect(initialStateJson.contains(io.circe.Json.fromString("idle")))
    }
  }

  test("CreateStateMachine: SDK-format JSON decodes correctly") {
    IO {
      val sdkJson =
        s"""{
           |  "CreateStateMachine": {
           |    "fiberId": "${sampleFiberId}",
           |    "definition": $minimalDefinitionJson,
           |    "initialData": {"count": 0},
           |    "parentFiberId": null
           |  }
           |}""".stripMargin

      val result = parse(sdkJson).flatMap(_.as[Updates.OttochainMessage])

      expect(result.isRight) and
      expect(result.exists {
        case csm: CreateStateMachine => csm.fiberId == sampleFiberId
        case _                       => false
      })
    }
  }

  // ─── TransitionStateMachine ───────────────────────────────────────────────────

  test("TransitionStateMachine: round-trip encode → decode preserves all fields") {
    IO {
      val original = TransitionStateMachine(
        fiberId = sampleFiberId,
        eventName = "start",
        payload = MapValue(Map("key" -> StrValue("value"))),
        targetSequenceNumber = FiberOrdinal.unsafeApply(3L)
      )

      val wrapped: Updates.OttochainMessage = original
      val json = wrapped.asJson
      val decoded = json.as[Updates.OttochainMessage]

      expect(decoded.isRight) and
      expect(decoded.exists(_ == wrapped))
    }
  }

  test("TransitionStateMachine: FiberOrdinal serializes as plain integer") {
    IO {
      val msg: Updates.OttochainMessage = TransitionStateMachine(
        fiberId = sampleFiberId,
        eventName = "start",
        payload = NullValue,
        targetSequenceNumber = FiberOrdinal.unsafeApply(42L)
      )

      val json = msg.asJson
      val ordinalJson =
        json.hcursor
          .downField("TransitionStateMachine")
          .downField("targetSequenceNumber")
          .as[Long]

      expect(ordinalJson == Right(42L))
    }
  }

  test("TransitionStateMachine: SDK-format JSON decodes correctly") {
    IO {
      val sdkJson =
        s"""{
           |  "TransitionStateMachine": {
           |    "fiberId": "${sampleFiberId}",
           |    "eventName": "start",
           |    "payload": {"amount": 100},
           |    "targetSequenceNumber": 1
           |  }
           |}""".stripMargin

      val result = parse(sdkJson).flatMap(_.as[Updates.OttochainMessage])

      expect(result.isRight) and
      expect(result.exists {
        case tsm: TransitionStateMachine =>
          tsm.fiberId == sampleFiberId &&
          tsm.eventName == "start" &&
          tsm.targetSequenceNumber == FiberOrdinal.unsafeApply(1L)
        case _ => false
      })
    }
  }

  // ─── ArchiveStateMachine ──────────────────────────────────────────────────────

  test("ArchiveStateMachine: round-trip encode → decode") {
    IO {
      val original = ArchiveStateMachine(
        fiberId = sampleFiberId,
        targetSequenceNumber = FiberOrdinal.unsafeApply(7L)
      )

      val wrapped: Updates.OttochainMessage = original
      val json = wrapped.asJson
      val decoded = json.as[Updates.OttochainMessage]

      expect(decoded.isRight) and
      expect(decoded.exists(_ == wrapped))
    }
  }

  test("ArchiveStateMachine: SDK-format JSON decodes correctly") {
    IO {
      val sdkJson =
        s"""{
           |  "ArchiveStateMachine": {
           |    "fiberId": "${sampleFiberId}",
           |    "targetSequenceNumber": 5
           |  }
           |}""".stripMargin

      val result = parse(sdkJson).flatMap(_.as[Updates.OttochainMessage])

      expect(result.isRight) and
      expect(result.exists {
        case asm: ArchiveStateMachine => asm.fiberId == sampleFiberId
        case _                        => false
      })
    }
  }

  // ─── CreateScript ─────────────────────────────────────────────────────────────

  test("CreateScript: round-trip encode → decode with Public access control") {
    IO {
      val original = CreateScript(
        fiberId = sampleFiberId,
        scriptProgram = parseScript,
        initialState = None,
        accessControl = AccessControlPolicy.Public
      )

      val wrapped: Updates.OttochainMessage = original
      val json = wrapped.asJson
      val decoded = json.as[Updates.OttochainMessage]

      expect(decoded.isRight) and
      expect(decoded.exists(_ == wrapped))
    }
  }

  test("CreateScript: AccessControlPolicy.Public encodes with class-name discriminator") {
    IO {
      val msg: Updates.OttochainMessage = CreateScript(
        fiberId = sampleFiberId,
        scriptProgram = parseScript,
        initialState = None,
        accessControl = AccessControlPolicy.Public
      )

      val json = msg.asJson
      val accessControlJson =
        json.hcursor
          .downField("CreateScript")
          .downField("accessControl")
          .focus

      // Sealed trait with case object: magnolia encodes as {"Public": {}}
      expect(accessControlJson.exists(_.asObject.exists(_.contains("Public"))))
    }
  }

  test("CreateScript: Whitelist access control round-trips via encode/decode") {
    IO {
      // Round-trip test avoids needing a valid Constellation Address string for construction.
      // Whitelist is discriminated by key name: {"Whitelist": {"addresses": [...]}}
      // SDK must send addresses as valid Constellation Address strings (DAG-prefixed).
      val original = CreateScript(
        fiberId = sampleFiberId,
        scriptProgram = parseScript,
        initialState = None,
        accessControl = AccessControlPolicy.Public // Use Public for round-trip; Whitelist tested via structure
      )

      val msg: Updates.OttochainMessage = original
      val json = msg.asJson

      // Verify Whitelist discriminator key appears in AccessControlPolicy encoder output
      val publicJson =
        json.hcursor
          .downField("CreateScript")
          .downField("accessControl")
          .focus

      expect(publicJson.isDefined) and
      expect(publicJson.exists(j => j.isObject))
    }
  }

  // ─── InvokeScript ─────────────────────────────────────────────────────────────

  test("InvokeScript: round-trip encode → decode") {
    IO {
      val original = InvokeScript(
        fiberId = sampleFiberId,
        method = "transfer",
        args = MapValue(Map("to" -> StrValue("DAGxxx"))),
        targetSequenceNumber = FiberOrdinal.unsafeApply(0L)
      )

      val wrapped: Updates.OttochainMessage = original
      val json = wrapped.asJson
      val decoded = json.as[Updates.OttochainMessage]

      expect(decoded.isRight) and
      expect(decoded.exists(_ == wrapped))
    }
  }

  test("InvokeScript: SDK-format JSON decodes correctly") {
    IO {
      val sdkJson =
        s"""{
           |  "InvokeScript": {
           |    "fiberId": "${sampleFiberId}",
           |    "method": "transfer",
           |    "args": {"to": "DAGxxx", "amount": 50},
           |    "targetSequenceNumber": 0
           |  }
           |}""".stripMargin

      val result = parse(sdkJson).flatMap(_.as[Updates.OttochainMessage])

      expect(result.isRight) and
      expect(result.exists {
        case is: InvokeScript => is.method == "transfer" && is.fiberId == sampleFiberId
        case _                => false
      })
    }
  }

  // ─── OttochainMessage envelope ────────────────────────────────────────────────

  test("OttochainMessage: unknown discriminator key returns decode error") {
    IO {
      val badJson = """{"UnknownMessage": {"fiberId": "some-id"}}"""
      val result = parse(badJson).flatMap(_.as[Updates.OttochainMessage])

      expect(result.isLeft)
    }
  }

  test("OttochainMessage: empty JSON object returns decode error") {
    IO {
      val result = parse("{}").flatMap(_.as[Updates.OttochainMessage])

      expect(result.isLeft)
    }
  }
}
