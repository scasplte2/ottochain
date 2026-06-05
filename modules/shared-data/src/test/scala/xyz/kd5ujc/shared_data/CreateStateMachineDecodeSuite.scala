package xyz.kd5ujc.shared_data

import cats.effect.IO

import scala.io.Source

import io.constellationnetwork.metagraph_sdk.std.JsonBinaryCodec

import xyz.kd5ujc.schema.Updates
import xyz.kd5ujc.schema.Updates.OttochainMessage

import io.circe.parser.decode
import weaver.SimpleIOSuite

/**
 * Reproduces the e2e 400: decode the EXACT createStateMachine payload the harness sends (real token-escrow
 * definition + initial-data, no schemaRef, parentFiberId: null) through both the direct CreateStateMachine
 * decoder (to surface the real per-field error the OttochainMessage fall-through swallows) and the wrapped
 * OttochainMessage decoder.
 */
object CreateStateMachineDecodeSuite extends SimpleIOSuite {

  private def resource(name: String): String = {
    val src = Source.fromResource(s"repro/$name")
    try src.mkString
    finally src.close()
  }

  private val definition = resource("definition.json")
  private val initialData = resource("initial-data.json")

  private val inner =
    s"""{"fiberId":"00000000-0000-0000-0000-000000000001","definition":$definition,"initialData":$initialData,"parentFiberId":null}"""
  private val wrapped = s"""{"CreateStateMachine":$inner}"""

  test("harness createStateMachine inner object decodes as Updates.CreateStateMachine") {
    IO.pure(
      decode[Updates.CreateStateMachine](inner)
        .fold(e => failure(s"INNER decode failed: $e"), _ => success)
    )
  }

  test("harness createStateMachine wrapped object decodes as OttochainMessage") {
    IO.pure(
      decode[OttochainMessage](wrapped)
        .fold(e => failure(s"WRAPPED decode failed: $e"), _ => success)
    )
  }

  test("canonical signing form: does the chain include schemaRef:null when None?") {
    for {
      csm   <- IO.fromEither(decode[Updates.CreateStateMachine](inner))
      bytes <- JsonBinaryCodec[IO, Updates.CreateStateMachine].serialize(csm)
      canonical = new String(bytes, "UTF-8")
    } yield
      if (canonical.contains("\"schemaRef\""))
        failure(
          s"canonical INCLUDES schemaRef when None -> a signer without it produces different bytes -> sig mismatch -> 400. canonical=$canonical"
        )
      else success
  }
}
