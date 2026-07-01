package xyz.kd5ujc.metagraph_l0.openapi

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/**
 * Writes the OpenAPI contracts to disk so they can be committed as build artifacts and consumed by the SDK
 * codegen. One document PER LAYER — OpenAPI keys by (path, method), so the ML0 and DL1 surfaces (which share
 * `/data-application/v1/...` paths) cannot live in one document. For each layer we emit both JSON and YAML:
 *
 *   <outDir>/openapi-ml0.json  <outDir>/openapi-ml0.yaml   ([[ApiEndpoints]])
 *   <outDir>/openapi-dl1.json  <outDir>/openapi-dl1.yaml   ([[DataL1ApiEndpoints]])
 *
 * `outDir` defaults to `docs`. Run:
 * `sbt "currencyL0/runMain xyz.kd5ujc.metagraph_l0.openapi.GenerateOpenApi [outDir]"`
 */
object GenerateOpenApi {

  final private case class Spec(baseName: String, endpoints: Int, json: String, yaml: String)

  private def specs: List[Spec] = List(
    Spec("openapi-ml0", ApiEndpoints.all.size, ApiEndpoints.openApiJson, ApiEndpoints.openApiYaml),
    Spec("openapi-dl1", DataL1ApiEndpoints.all.size, DataL1ApiEndpoints.openApiJson, DataL1ApiEndpoints.openApiYaml)
  )

  def main(args: Array[String]): Unit = {
    val dir: Path = Paths.get(if (args.nonEmpty) args(0) else "docs")
    Files.createDirectories(dir)
    specs.foreach { s =>
      val jsonOut = dir.resolve(s.baseName + ".json")
      val yamlOut = dir.resolve(s.baseName + ".yaml")
      Files.write(jsonOut, s.json.getBytes(StandardCharsets.UTF_8))
      Files.write(yamlOut, s.yaml.getBytes(StandardCharsets.UTF_8))
      println(s"Wrote ${s.baseName} (${s.endpoints} endpoints, json+yaml) to ${dir.toAbsolutePath}")
    }
  }
}
