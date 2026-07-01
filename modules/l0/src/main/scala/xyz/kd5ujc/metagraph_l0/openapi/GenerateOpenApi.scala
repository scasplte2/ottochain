package xyz.kd5ujc.metagraph_l0.openapi

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/**
 * Writes the OpenAPI document to disk so it can be committed as a build artifact and consumed by the SDK
 * codegen (Phase 3). The JSON goes to `outPath` (default `docs/openapi.json`) and a YAML rendering of the
 * same document is written alongside it (the `.json` extension swapped for `.yaml`, or `.yaml` appended).
 *
 * Run: `sbt "currencyL0/runMain xyz.kd5ujc.metagraph_l0.openapi.GenerateOpenApi [outPath]"`
 */
object GenerateOpenApi {

  /** `foo/openapi.json` -> `foo/openapi.yaml`; anything else gets `.yaml` appended. */
  private def yamlSibling(json: Path): Path = {
    val name = json.getFileName.toString
    val yamlName = if (name.endsWith(".json")) name.dropRight(".json".length) + ".yaml" else name + ".yaml"
    Option(json.getParent).map(_.resolve(yamlName)).getOrElse(Paths.get(yamlName))
  }

  def main(args: Array[String]): Unit = {
    val jsonOut = Paths.get(if (args.nonEmpty) args(0) else "docs/openapi.json")
    val yamlOut = yamlSibling(jsonOut)
    Option(jsonOut.getParent).foreach(Files.createDirectories(_))
    Files.write(jsonOut, ApiEndpoints.openApiJson.getBytes(StandardCharsets.UTF_8))
    Files.write(yamlOut, ApiEndpoints.openApiYaml.getBytes(StandardCharsets.UTF_8))
    println(
      s"Wrote OpenAPI (${ApiEndpoints.all.size} endpoints) to ${jsonOut.toAbsolutePath} and ${yamlOut.toAbsolutePath}"
    )
  }
}
