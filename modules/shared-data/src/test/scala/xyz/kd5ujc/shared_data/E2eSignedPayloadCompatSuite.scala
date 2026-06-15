package xyz.kd5ujc.shared_data

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.UUID

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.std.{JsonBinaryCodec, JsonCanonicalizer}

import xyz.kd5ujc.schema.Updates._
import xyz.kd5ujc.schema.{OnChain, Updates}
import xyz.kd5ujc.shared_data.lifecycle.validate.{FiberValidator, ScriptValidator}

import io.circe.parser.parse
import io.circe.{Json, JsonObject}
import org.bouncycastle.util.encoders.Base64
import weaver.SimpleIOSuite

/**
 * Reproduces / guards the e2e DL1 submit path at the byte level.
 *
 * The e2e client (e2e-test/lib + @constellation-network/metagraph-sdk) signs DataUpdates over:
 *
 *   RFC 8785 canonical JSON -> UTF-8 -> Base64 -> "\\u0019Constellation Signed Data:\n<len>\n<b64>" -> UTF-8
 *
 * The DL1 node verifies signature proofs against the SAME construction, but produced by
 * metakit's `JsonBinaryCodec.deriveDataUpdate.serialize` (via `serializeUpdate` in
 * DataApplicationRoutes). Since metakit 1.8.0 (PR #27), `serialize` DROPS NULL OBJECT FIELDS
 * before canonicalizing. A client that signs over JSON containing explicit nulls (e.g.
 * `"parentFiberId": null`, or `"metadata": null` inside fixture definitions) therefore
 * produces a different hash than the node, and every submit is rejected with HTTP 400
 * (InvalidSignature).
 *
 * These tests pin the contract:
 *   - the actual e2e fixture create payloads decode and pass L1 validation (no other 400 source);
 *   - signing bytes computed over null-containing JSON DIVERGE from the node's bytes
 *     (documents the metakit 1.7 -> 1.8 break);
 *   - signing bytes computed over drop-nulls JSON MATCH the node's bytes
 *     (the contract the e2e client must follow — see e2e-test/lib/sendDataTransaction.ts).
 */
object E2eSignedPayloadCompatSuite extends SimpleIOSuite {

  // ─── Fixture loading ─────────────────────────────────────────────────────────

  /** Walks up from cwd to find the repo root (the directory containing e2e-test/). */
  private lazy val repoRoot: File = {
    @scala.annotation.tailrec
    def loop(dir: File): File =
      if (new File(dir, "e2e-test/examples").isDirectory) dir
      else
        Option(dir.getParentFile) match {
          case Some(parent) => loop(parent)
          case None         => throw new RuntimeException("Could not locate repo root containing e2e-test/examples")
        }
    loop(new File(".").getCanonicalFile)
  }

  private def loadFixture(relPath: String): Json = {
    val path = Paths.get(repoRoot.getAbsolutePath, relPath)
    val raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    parse(raw).fold(err => throw new RuntimeException(s"Unparseable fixture $relPath: $err"), identity)
  }

  private val fiberId: UUID = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")

  /** CreateStateMachine payload built exactly like e2e-test/lib/state-machine/createFiber.ts. */
  private def votingCreateJson: Json = Json.obj(
    "CreateStateMachine" -> Json.obj(
      "fiberId"       -> Json.fromString(fiberId.toString),
      "definition"    -> loadFixture("e2e-test/examples/voting/definition.json"),
      "initialData"   -> loadFixture("e2e-test/examples/voting/initial-data.json"),
      "parentFiberId" -> Json.Null // createFiber.ts: `parentFiberId: options.parentFiberId ?? null`
    )
  )

  /** CreateScript payload built exactly like e2e-test/lib/script/createScript.ts. */
  private def counterCreateScriptJson: Json = {
    val scriptDef = loadFixture("e2e-test/examples/counter-script/definition.json")
    val cursor = scriptDef.hcursor
    Json.obj(
      "CreateScript" -> Json.obj(
        "fiberId"       -> Json.fromString(fiberId.toString),
        "scriptProgram" -> cursor.downField("scriptProgram").focus.getOrElse(Json.Null),
        "initialState"  -> cursor.downField("initialState").focus.getOrElse(Json.Null),
        "accessControl" -> cursor.downField("accessControl").focus.getOrElse(Json.Null)
      )
    )
  }

  // ─── Client / node byte construction ────────────────────────────────────────

  /** Mirrors @constellation-network/metagraph-sdk `toBytes(data, true)`: NO null-dropping. */
  private def clientSigningBytes(rawJson: Json): IO[Array[Byte]] =
    JsonCanonicalizer.canonicalizeJson[IO](rawJson).map { canonical =>
      val b64 = Base64.toBase64String(canonical.value.getBytes(StandardCharsets.UTF_8))
      s"\u0019Constellation Signed Data:\n${b64.length}\n$b64".getBytes(StandardCharsets.UTF_8)
    }

  /** Mirrors @ottochain/sdk `dropNulls`: removes null object fields, preserves array nulls. */
  private def dropNulls(json: Json): Json =
    json.arrayOrObject(
      json,
      arr => Json.fromValues(arr.map(dropNulls)),
      obj =>
        Json.fromJsonObject(
          JsonObject.fromIterable(obj.toIterable.collect { case (k, v) if !v.isNull => k -> dropNulls(v) })
        )
    )

  /** The exact bytes the DL1 node hashes when verifying proofs (serializeUpdate). */
  private def nodeVerificationBytes(update: OttochainMessage): IO[Array[Byte]] =
    JsonBinaryCodec.deriveDataUpdate[IO, OttochainMessage].serialize(update)

  private def decodeMessage(rawJson: Json): OttochainMessage =
    rawJson
      .as[Updates.OttochainMessage]
      .fold(err => throw new RuntimeException(s"Decode failed: $err"), identity)

  // ─── Tests ───────────────────────────────────────────────────────────────────

  test("e2e voting create payload decodes and passes L1 validation (no non-signature 400 source)") {
    val update = decodeMessage(votingCreateJson)
    update match {
      case u: CreateStateMachine =>
        new FiberValidator.L1Validator[IO](OnChain.genesis).createFiber(u).map { result =>
          expect(
            result.isValid,
            s"L1 validation failed: ${result.fold(_.toNonEmptyList.toList.map(_.message).mkString("; "), _ => "")}"
          )
        }
      case other => IO.pure(failure(s"Expected CreateStateMachine, got $other"))
    }
  }

  test("e2e counter createScript payload decodes and passes L1 validation") {
    val update = decodeMessage(counterCreateScriptJson)
    update match {
      case u: CreateScript =>
        new ScriptValidator.L1Validator[IO](OnChain.genesis).createScript(u).map { result =>
          expect(
            result.isValid,
            s"L1 validation failed: ${result.fold(_.toNonEmptyList.toList.map(_.message).mkString("; "), _ => "")}"
          )
        }
      case other => IO.pure(failure(s"Expected CreateScript, got $other"))
    }
  }

  test("ROOT CAUSE: signing over null-containing JSON diverges from node serializeUpdate bytes") {
    val raw = votingCreateJson
    val update = decodeMessage(raw)
    for {
      clientBytes <- clientSigningBytes(raw)
      nodeBytes   <- nodeVerificationBytes(update)
    } yield expect(
      !java.util.Arrays.equals(clientBytes, nodeBytes),
      "Expected divergence: metakit 1.8 drops nulls in serializeUpdate, raw fixture JSON contains explicit nulls"
    )
  }

  test("FIX CONTRACT: signing over drop-nulls JSON matches node serializeUpdate bytes (CreateStateMachine)") {
    val raw = votingCreateJson
    val update = decodeMessage(raw)
    for {
      clientBytes <- clientSigningBytes(dropNulls(raw))
      nodeBytes   <- nodeVerificationBytes(update)
    } yield expect(
      java.util.Arrays.equals(clientBytes, nodeBytes),
      s"client=${new String(clientBytes, StandardCharsets.UTF_8)
          .take(200)} node=${new String(nodeBytes, StandardCharsets.UTF_8).take(200)}"
    )
  }

  test("FIX CONTRACT: signing over drop-nulls JSON matches node serializeUpdate bytes (CreateScript)") {
    val raw = counterCreateScriptJson
    val update = decodeMessage(raw)
    for {
      clientBytes <- clientSigningBytes(dropNulls(raw))
      nodeBytes   <- nodeVerificationBytes(update)
    } yield expect(
      java.util.Arrays.equals(clientBytes, nodeBytes),
      s"client=${new String(clientBytes, StandardCharsets.UTF_8)
          .take(200)} node=${new String(nodeBytes, StandardCharsets.UTF_8).take(200)}"
    )
  }

  test("node round-trips the drop-nulls wire value (deserialize after client-side dropNulls)") {
    // After the fix, the client submits the drop-nulls'd value; the node must decode it
    // identically to the null-containing original (Option fields -> None either way).
    val raw = votingCreateJson
    val cleaned = dropNulls(raw)
    IO.pure(
      expect(decodeMessage(cleaned) == decodeMessage(raw))
    )
  }
}
