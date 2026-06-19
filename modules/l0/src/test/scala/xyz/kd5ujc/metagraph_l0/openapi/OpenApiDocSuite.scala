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
      "/data-application/v1/version",
      "/data-application/v1/util/hash",
      "/data-application/v1/onchain",
      "/data-application/v1/checkpoint",
      "/data-application/v1/state-machines",
      "/data-application/v1/state-machines/{id}/estimate-fee",
      "/data-application/v1/state-machines/{id}/state-proof",
      "/data-application/v1/scripts",
      "/data-application/v1/scripts/{id}/invocations",
      "/data-application/v1/assets/{id}/state-proof",
      "/data-application/v1/registry",
      "/data-application/v1/registry/reverse/{id}",
      "/data-application/v1/webhooks/subscribe",
      "/data-application/v1/webhooks/subscribers"
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
