package xyz.kd5ujc.metagraph_l0.openapi

import java.util.UUID

import xyz.kd5ujc.schema.api._
import xyz.kd5ujc.schema.api.webhooks.{SubscribeRequest, SubscribeResponse, SubscriberList}

import io.circe.Json
import io.circe.syntax._
import sttp.apispec.openapi.circe._
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._

/**
 * The single source-of-truth description of OttoChain's custom HTTP surface, expressed as tapir
 * endpoints. From this one list we derive the OpenAPI document ([[openApiJson]]) — and, in a later pass,
 * the http4s server routes — so the published contract cannot drift from what is served.
 *
 * Schema fidelity (this pass): the flat response DTOs (`VersionInfo`, fee estimates, `SubscribeResponse`,
 * `SubscriberList`, `ErrorResponse`) carry a PRECISE derived schema. The rich domain bodies (`OnChain`,
 * checkpoint, fiber/script/registry records, state proofs, the echoed signed message) are described as
 * an opaque JSON object for now — paths, methods, params and the typed DTOs are exact; tightening the
 * heavy bodies to typed schemas is the follow-up (RFC §3). DL1 serves the {version, util/hash, onchain}
 * subset on port 9400 with these same shapes.
 *
 * PATH PREFIX: the framework mounts a data application's custom routes under `/data-application`, and
 * `ML0Routes` adds `/v1` — so the public path is `/data-application/v1/...` (what the e2e harness and SDK
 * actually hit). The endpoints below carry the full `data-application/v1/...` path so the contract matches
 * reality byte-for-byte. The live docs (`OpenApiRoutes`) ride the same mount, i.e. `/data-application/docs`
 * and `/data-application/openapi.json`.
 */
object ApiEndpoints {

  /** Opaque JSON body, documented — for the rich domain types not yet given a precise schema. */
  private def opaqueJson(desc: String): EndpointIO.Body[String, Json] =
    jsonBody[Json].description(desc)

  private val statusQuery = query[Option[String]]("status")
    .description("Filter by fiber status: ACTIVE | ARCHIVED | FAILED")

  private val fieldQuery = query[Option[String]]("field")
    .description("Optional: also surface this named `stateData` field of the proven record")

  // ---- service meta ----
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

  // ---- raw state ----
  val onchain: PublicEndpoint[Unit, Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "onchain")
      .out(opaqueJson("OnChain"))
      .summary("Current on-chain state")
      .tag("state")

  val checkpoint: PublicEndpoint[Unit, Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "checkpoint")
      .out(opaqueJson("Checkpoint[CalculatedState]"))
      .summary("Current checkpoint (calculated state snapshot)")
      .tag("state")

  // ---- state machines ----
  val stateMachinesList: PublicEndpoint[Option[String], Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "state-machines")
      .in(statusQuery)
      .out(opaqueJson("SortedMap[UUID, StateMachineFiberRecord]"))
      .summary("List state machines (optionally filtered by status)")
      .tag("state-machines")

  val stateMachineGet: PublicEndpoint[UUID, Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "state-machines" / path[UUID]("id"))
      .out(opaqueJson("Option[StateMachineFiberRecord]"))
      .summary("Get a state machine by id")
      .tag("state-machines")

  val stateMachineEvents: PublicEndpoint[UUID, Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "state-machines" / path[UUID]("id") / "events")
      .out(opaqueJson("List[EventReceipt]"))
      .summary("Event receipts for a state machine")
      .tag("state-machines")

  val stateMachineAudit: PublicEndpoint[UUID, Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "state-machines" / path[UUID]("id") / "audit")
      .out(opaqueJson("Rendered audit trail"))
      .summary("Audit trail for a state machine")
      .tag("state-machines")

  val stateMachineEstimateFee: PublicEndpoint[(UUID, String), Unit, TransitionFeeEstimate, Any] =
    endpoint.get
      .in("data-application" / "v1" / "state-machines" / path[UUID]("id") / "estimate-fee")
      .in(query[String]("event"))
      .out(jsonBody[TransitionFeeEstimate])
      .summary("Static fee estimate for a transition")
      .tag("state-machines")

  val stateMachineStateProof: PublicEndpoint[(UUID, Option[String]), Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "state-machines" / path[UUID]("id") / "state-proof")
      .in(fieldQuery)
      .out(opaqueJson("StateProofResponse"))
      .summary("Committed-state Merkle proof for a state machine")
      .tag("proofs")

  // ---- scripts ----
  val scriptsList: PublicEndpoint[Option[String], Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "scripts")
      .in(statusQuery)
      .out(opaqueJson("SortedMap[UUID, ScriptFiberRecord]"))
      .summary("List scripts (optionally filtered by status)")
      .tag("scripts")

  val scriptGet: PublicEndpoint[UUID, Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "scripts" / path[UUID]("id"))
      .out(opaqueJson("Option[ScriptFiberRecord]"))
      .summary("Get a script by id")
      .tag("scripts")

  val scriptInvocations: PublicEndpoint[UUID, Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "scripts" / path[UUID]("id") / "invocations")
      .out(opaqueJson("List[ScriptInvocation]"))
      .summary("Invocation history for a script")
      .tag("scripts")

  val scriptEstimateFee: PublicEndpoint[UUID, Unit, ScriptFeeEstimate, Any] =
    endpoint.get
      .in("data-application" / "v1" / "scripts" / path[UUID]("id") / "estimate-fee")
      .out(jsonBody[ScriptFeeEstimate])
      .summary("Static fee estimate for a script invocation")
      .tag("scripts")

  val scriptStateProof: PublicEndpoint[(UUID, Option[String]), Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "scripts" / path[UUID]("id") / "state-proof")
      .in(fieldQuery)
      .out(opaqueJson("StateProofResponse"))
      .summary("Committed-state Merkle proof for a script")
      .tag("proofs")

  // ---- assets ----
  val assetStateProof: PublicEndpoint[(UUID, Option[String]), Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "assets" / path[UUID]("id") / "state-proof")
      .in(fieldQuery)
      .out(opaqueJson("StateProofResponse"))
      .summary("Custody (committed-state) Merkle proof for an asset")
      .tag("proofs")

  // ---- registry ----
  val registryAll: PublicEndpoint[Unit, Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "registry")
      .out(opaqueJson("SortedMap[RegistryName, RegistryEntry]"))
      .summary("All registered package versions")
      .tag("registry")

  val registryReverse: PublicEndpoint[UUID, Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "registry" / "reverse" / path[UUID]("id"))
      .out(opaqueJson("Option[RegistryName]"))
      .summary("Reverse-lookup a registry name by id")
      .tag("registry")

  val registryByName: PublicEndpoint[String, Unit, Json, Any] =
    endpoint.get
      .in("data-application" / "v1" / "registry" / path[String]("name"))
      .out(opaqueJson("Option[RegistryEntry]"))
      .summary("Resolve a registry entry by name")
      .tag("registry")

  // ---- webhooks ----
  val webhookSubscribe: PublicEndpoint[SubscribeRequest, Unit, SubscribeResponse, Any] =
    endpoint.post
      .in("data-application" / "v1" / "webhooks" / "subscribe")
      .in(jsonBody[SubscribeRequest])
      .out(jsonBody[SubscribeResponse].description("201 Created"))
      .summary("Register a webhook subscriber")
      .tag("webhooks")

  val webhookUnsubscribe: PublicEndpoint[String, Unit, Unit, Any] =
    endpoint.delete
      .in("data-application" / "v1" / "webhooks" / "subscribe" / path[String]("id"))
      .out(statusCode(StatusCode.NoContent))
      .summary("Unregister a webhook subscriber")
      .tag("webhooks")

  val webhookSubscribers: PublicEndpoint[Unit, Unit, SubscriberList, Any] =
    endpoint.get
      .in("data-application" / "v1" / "webhooks" / "subscribers")
      .out(jsonBody[SubscriberList])
      .summary("List webhook subscribers (secrets redacted)")
      .tag("webhooks")

  /**
   * The contract version. Deterministic ON PURPOSE — bump it intentionally when the surface changes
   * (NOT tied to the build version, whose git-describe timestamp would make the committed `openapi.json`
   * differ on every build and break the drift gate).
   */
  val contractVersion = "1.0.0"

  /** Every custom endpoint, in route order. Add new endpoints here. */
  val all: List[AnyEndpoint] = List(
    version,
    utilHash,
    onchain,
    checkpoint,
    stateMachinesList,
    stateMachineGet,
    stateMachineEvents,
    stateMachineAudit,
    stateMachineEstimateFee,
    stateMachineStateProof,
    scriptsList,
    scriptGet,
    scriptInvocations,
    scriptEstimateFee,
    scriptStateProof,
    assetStateProof,
    registryAll,
    registryReverse,
    registryByName,
    webhookSubscribe,
    webhookUnsubscribe,
    webhookSubscribers
  )

  def openApiJson: String =
    OpenAPIDocsInterpreter()
      .toOpenAPI(all, "OttoChain Metagraph API", contractVersion)
      .asJson
      .deepDropNullValues
      .spaces2
}
