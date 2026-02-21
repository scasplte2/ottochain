ThisBuild / scalaVersion := "2.13.8"

// L0 Module Build Configuration for Rejection Webhook Tests
// 
// Card: 🌐 Bridge: Dispatch rejection webhook events per-update (#69962948b9229744fe0f7609)
// Context: @think spec requires commonTestSettings + sharedTest dep for webhook tests

lazy val l0 = (project in file("."))
  .settings(
    name := "ottochain-l0",
    organization := "com.ottochain",
    
    // Common test settings for webhook rejection tests
    commonTestSettings,
    
    libraryDependencies ++= Seq(
      // Core dependencies
      "org.typelevel" %% "cats-effect" % "3.4.8",
      "org.http4s" %% "http4s-dsl" % "0.23.18",
      "org.http4s" %% "http4s-client" % "0.23.18",
      "org.http4s" %% "http4s-circe" % "0.23.18",
      "io.circe" %% "circe-core" % "0.14.5",
      "io.circe" %% "circe-generic" % "0.14.5",
      "io.circe" %% "circe-syntax" % "0.14.5",
      
      // Test dependencies
      "org.scalatest" %% "scalatest" % "3.2.15" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.4.0" % Test,
      "org.scalatestplus" %% "mockito-4-6" % "3.2.15.0" % Test,
      
      // Shared test utilities (for E2E helpers)
      "com.ottochain" %% "shared-test" % "0.1.0" % Test
    ),
    
    // Test configuration
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
    Test / parallelExecution := false,
    Test / fork := true,
    
    // Integration test configuration for webhook E2E tests
    IntegrationTest / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
    IntegrationTest / parallelExecution := false,
    IntegrationTest / fork := true
  )
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)

// Common test settings imported from parent build
lazy val commonTestSettings = Seq(
  // Webhook test specific settings
  Test / envVars := Map(
    "WEBHOOK_URL" -> "http://localhost:3030/webhooks/rejections",
    "WEBHOOK_TIMEOUT" -> "30s",
    "TEST_MODE" -> "true"
  ),
  
  // Integration test environment
  IntegrationTest / envVars := Map(
    "WEBHOOK_URL" -> "http://localhost:3030/webhooks/rejections",
    "METAGRAPH_URL" -> "http://localhost:4000",
    "INDEXER_URL" -> "http://localhost:3031",
    "BRIDGE_URL" -> "http://localhost:3030",
    "TEST_MODE" -> "true"
  ),
  
  // Test resource management
  Test / testGrouping := {
    val tests = (Test / definedTests).value
    val webhookTests = tests.filter(_.name.contains("Webhook"))
    val regularTests = tests.filterNot(_.name.contains("Webhook"))
    
    // Run webhook tests sequentially to avoid port conflicts
    Seq(
      Tests.Group("webhook-tests", webhookTests, Tests.SubProcess(ForkOptions())),
      Tests.Group("regular-tests", regularTests, Tests.SubProcess(ForkOptions()))
    )
  }
)

// Shared test dependency for E2E helpers
// This would be defined in the parent build.sbt or shared module
lazy val sharedTest = ProjectRef(file("../shared-test"), "shared-test")

// Task to run only webhook-related tests
lazy val testWebhooks = taskKey[Unit]("Run webhook rejection tests")
testWebhooks := {
  (Test / test).toTask(" -- -n WebhookTest").value
}

// Task to run E2E integration tests
lazy val testE2E = taskKey[Unit]("Run E2E integration tests")
testE2E := {
  (IntegrationTest / test).toTask(" -- com.ottochain.integration.RejectionWebhookE2ESuite").value
}