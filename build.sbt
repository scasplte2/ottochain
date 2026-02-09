import Dependencies.*
import sbt.*
import sbt.Keys.*

ThisBuild / organization := "xyz.kd5ujc"
ThisBuild / scalaVersion := "2.13.18"
ThisBuild / evictionErrorLevel := Level.Warn

ThisBuild / assemblyMergeStrategy := {
  case "logback.xml" => MergeStrategy.first
  case "dag-l1.conf" => MergeStrategy.first
  case x if x.contains("io.netty.versions.properties") => MergeStrategy.discard
  case PathList("xyz", "kd5ujc", "buildinfo", xs @ _*) => MergeStrategy.first
  case PathList(xs@_*) if xs.last == "module-info.class" => MergeStrategy.first
  case PathList("META-INF", "versions", _, "OSGI-INF", _ @_*)    => MergeStrategy.discard
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

// Coverage configuration
ThisBuild / coverageMinimumStmtTotal := 70
ThisBuild / coverageFailOnMinimum := false
ThisBuild / coverageExcludedPackages := ".*\\.proto\\..*;.*BuildInfo.*"

lazy val commonSettings = Seq(
  scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info", "-language:reflectiveCalls"),
  scalafmtOnCompile := true,
  scalafixOnCompile := true,
  resolvers ++= Seq(
    Resolver.mavenLocal
  ),
  libraryDependencies ++= Seq(
    CompilerPlugin.kindProjector,
    CompilerPlugin.betterMonadicFor,
    CompilerPlugin.semanticDB,
    Libraries.cats,
    Libraries.catsEffect,
    Libraries.catsMtl,
    Libraries.enumeratum,
    Libraries.enumeratumCirce,
    Libraries.declineCore,
    Libraries.declineEffect,
    Libraries.declineRefined,
    Libraries.metakit,
    Libraries.pureconfigCore,
    Libraries.pureconfigCats
  )
)

lazy val commonTestSettings = Seq(
  testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  libraryDependencies ++= Seq(
      Libraries.catsEffectTestkit,
      Libraries.weaverCats,
      Libraries.weaverDiscipline,
      Libraries.weaverScalaCheck
  ).map(_ % Test)
)

lazy val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    version,
    scalaVersion,
    sbtVersion,
    BuildInfoKey.action("gitCommit") {
      scala.util.Try(
        scala.sys.process.Process("git rev-parse --short HEAD").!!.trim
      ).getOrElse("unknown")
    },
    BuildInfoKey.action("buildTime") {
      java.time.Instant.now().toString
    }
  ),
  buildInfoPackage := "xyz.kd5ujc.buildinfo"
)

lazy val root = (project in file("."))
  .settings(
    name := "ottochain"
  ).aggregate(proto, models, sharedData, currencyL0, currencyL1, dataL1)

lazy val proto = (project in file("modules/proto"))
  .settings(
    commonSettings,
    name := "ottochain-proto",
    Compile / PB.targets := Seq(
      scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "scalapb",
      scalapb.validate.gen() -> (Compile / sourceManaged).value / "scalapb"
    ),
    Compile / PB.protoSources := Seq(
      (Compile / sourceDirectory).value / "protobuf"
    ),
    libraryDependencies ++= Seq(
      Libraries.scalapbRuntime,
      Libraries.scalapbRuntime % "protobuf",
      Libraries.scalapbValidateCore,
      Libraries.scalapbValidateCore % "protobuf",
      Libraries.scalapbCirce
    )
  )

lazy val models = (project in file("modules/models"))
  .settings(
    commonSettings,
    name := "ottochain-models"
  )

lazy val sharedTest = (project in file("modules/shared-test"))
  .dependsOn(models)
  .settings(
    commonSettings,
    commonTestSettings,
    name := "ottochain-shared-test",
  )

lazy val sharedData = (project in file("modules/shared-data"))
  .dependsOn(sharedTest, models)
  .settings(
    commonSettings,
    commonTestSettings,
    name := "ottochain-shared-data",
  )

lazy val currencyL0 = (project in file("modules/l0"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(sharedData)
  .settings(
    buildInfoSettings,
    commonSettings,
    commonTestSettings,
    name := "ottochain-currency-l0"
  )

lazy val currencyL1 = (project in file("modules/l1"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(sharedData)
  .settings(
    buildInfoSettings,
    commonSettings,
    commonTestSettings,
    name := "ottochain-currency-l1"
  )

lazy val dataL1 = (project in file("modules/data_l1"))
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(sharedData)
  .settings(
    buildInfoSettings,
    commonSettings,
    commonTestSettings,
    name := "ottochain-data-l1"
  )