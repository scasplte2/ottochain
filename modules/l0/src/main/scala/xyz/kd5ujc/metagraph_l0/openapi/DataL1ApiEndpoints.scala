package xyz.kd5ujc.metagraph_l0.openapi

import xyz.kd5ujc.metagraph_l0.openapi.DomainSchemas._
import xyz.kd5ujc.schema.OnChain
import xyz.kd5ujc.schema.api.{CommitIndexResponse, VersionInfo}

import io.circe.Json
import io.circe.syntax._
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe._
import sttp.apispec.openapi.circe.yaml._
import sttp.tapir._
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/**
 * The DL1 (Data L1) custom HTTP surface, mirroring [[xyz.kd5ujc.data_l1.DataL1CustomRoutes]]. DL1 serves a
 * small monitoring/echo subset at the same `/data-application/v1/...` mount as ML0, but on the DL1 port and
 * with `service: "ottochain-dl1"`.
 *
 * WHY A SEPARATE DOCUMENT: OpenAPI keys operations by (path, method), so the ML0 and DL1 copies of
 * `/data-application/v1/{version,util/hash,onchain}` cannot coexist in one document — they would collide.
 * Each layer therefore gets its own contract (`openapi-ml0.*` / `openapi-dl1.*`), differentiated by the
 * `ml0`/`dl1` tag and the filename (no `servers` block — consumers supply the base URL/port).
 *
 * The framework's update-submission endpoint on DL1 is intentionally OUT OF SCOPE here (documented
 * elsewhere); this contract covers only the app's own custom routes.
 */
object DataL1ApiEndpoints {

  /** Opaque JSON body, documented — for the handful of bodies intentionally left untyped. */
  private def opaqueJson(desc: String): EndpointIO.Body[String, Json] =
    jsonBody[Json].description(desc)

  val version: PublicEndpoint[Unit, Unit, VersionInfo, Any] =
    endpoint.get
      .in("data-application" / "v1" / "version")
      .out(jsonBody[VersionInfo])
      .summary("Service identity + build metadata")
      .tag("meta")

  val utilHash: PublicEndpoint[Json, Unit, Json, Any] =
    endpoint.post
      .in("data-application" / "v1" / "util" / "hash")
      .in(opaqueJson("Signed[OttochainMessage]"))
      .out(opaqueJson("""HashResult: { "messageHash": <hash>, "message": <message> }"""))
      .summary("Canonical hash of a signed message")
      .tag("meta")

  val onchain: PublicEndpoint[Unit, Unit, OnChain, Any] =
    endpoint.get
      .in("data-application" / "v1" / "onchain")
      .out(jsonBody[OnChain])
      .summary("Current on-chain state (OnChain v2: this batch's delta — cumulative view is /commit-index)")
      .tag("state")

  val commitIndex: PublicEndpoint[Unit, Unit, CommitIndexResponse, Any] =
    endpoint.get
      .in("data-application" / "v1" / "commit-index")
      .out(jsonBody[CommitIndexResponse])
      .summary(
        "This node's folded/healed cumulative commit maps — the DL1-sync surface " +
        "(reading it drives the same refresh the ingestion gate uses)"
      )
      .tag("state")

  /** Deterministic ON PURPOSE — see [[ApiEndpoints.contractVersion]]. */
  val contractVersion = "1.0.0"

  /** Every DL1 custom endpoint, in route order. */
  val all: List[AnyEndpoint] = List(version, utilHash, onchain, commitIndex)

  private def openApiDoc: OpenAPI =
    OpenAPIDocsInterpreter().toOpenAPI(all.map(_.tag("dl1")), "OttoChain Data L1 API", contractVersion)

  def openApiJson: String =
    openApiDoc.asJson.deepDropNullValues.spaces2

  def openApiYaml: String =
    openApiDoc.toYaml
}
