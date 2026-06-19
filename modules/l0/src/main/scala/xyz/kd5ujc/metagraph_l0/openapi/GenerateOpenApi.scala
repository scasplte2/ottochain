package xyz.kd5ujc.metagraph_l0.openapi

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/**
 * Writes the OpenAPI document to disk so it can be committed as a build artifact and consumed by the SDK
 * codegen (Phase 3). Default target `docs/openapi.json`.
 *
 * Run: `sbt "currencyL0/runMain xyz.kd5ujc.metagraph_l0.openapi.GenerateOpenApi [outPath]"`
 */
object GenerateOpenApi {

  def main(args: Array[String]): Unit = {
    val out = Paths.get(if (args.nonEmpty) args(0) else "docs/openapi.json")
    Option(out.getParent).foreach(Files.createDirectories(_))
    Files.write(out, ApiEndpoints.openApiJson.getBytes(StandardCharsets.UTF_8))
    println(s"Wrote OpenAPI (${ApiEndpoints.all.size} endpoints) to ${out.toAbsolutePath}")
  }
}
