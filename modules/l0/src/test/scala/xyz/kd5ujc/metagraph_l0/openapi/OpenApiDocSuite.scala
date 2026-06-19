package xyz.kd5ujc.metagraph_l0.openapi

import weaver.SimpleIOSuite

/**
 * Guards the OpenAPI contract: that it generates, is OpenAPI 3.x, names a path per route family, and
 * exposes the precise DTO component schemas. The list here must track [[ApiEndpoints.all]] — a missing
 * path means the contract no longer describes the served surface (the drift this whole effort prevents).
 */
object OpenApiDocSuite extends SimpleIOSuite {

  private val doc = ApiEndpoints.openApiJson

  pureTest("generates an OpenAPI 3.x document") {
    expect.all(doc.contains("\"openapi\""), doc.contains("3.1") || doc.contains("3.0"))
  }

  pureTest("documents every custom path family") {
    val expectedPaths = List(
      "/v1/version",
      "/v1/util/hash",
      "/v1/onchain",
      "/v1/checkpoint",
      "/v1/state-machines",
      "/v1/state-machines/{id}/estimate-fee",
      "/v1/state-machines/{id}/state-proof",
      "/v1/scripts",
      "/v1/scripts/{id}/invocations",
      "/v1/assets/{id}/state-proof",
      "/v1/registry",
      "/v1/registry/reverse/{id}",
      "/v1/webhooks/subscribe",
      "/v1/webhooks/subscribers"
    )
    expectedPaths.map(p => expect(doc.contains(p))).reduce(_ and _)
  }

  pureTest("exposes precise schemas for the flat DTOs") {
    expect.all(
      doc.contains("VersionInfo"),
      doc.contains("TransitionFeeEstimate"),
      doc.contains("ScriptFeeEstimate"),
      doc.contains("SubscribeResponse"),
      doc.contains("SubscriberList")
    )
  }

  pureTest("endpoint catalog matches the documented count") {
    expect.eql(22, ApiEndpoints.all.size)
  }
}
