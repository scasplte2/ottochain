import sbt._

object Dependencies {

  object V {
    val cats = "2.13.0"
    val catsEffect = "3.6.3"
    val catsMtl = "1.3.1"
    val enumeratum = "1.7.5"
    val decline = "2.5.0"
    val metakit = "1.7.0-rc.9"
    val pureConfig = "0.17.5"
    val weaver = "0.10.1"

    val betterMonadicFor = "0.3.1"
    val kindProjector = "0.13.4"
    val semanticDB = "4.14.2"

  }

  def decline(artifact: Option[String], ver: String): ModuleID = "com.monovore" %% {if (artifact.isEmpty) "decline" else s"decline-${artifact.get}"} % ver

  object Libraries {
    val cats = "org.typelevel" %% "cats-core" % V.cats
    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
    val catsEffectTestkit = "org.typelevel" %% "cats-effect-testkit" % V.catsEffect
    val catsMtl = "org.typelevel" %% "cats-mtl" % V.catsMtl

    val enumeratum = "com.beachape" %% "enumeratum" % V.enumeratum
    val enumeratumCirce = "com.beachape" %% "enumeratum-circe" % V.enumeratum

    val declineCore = decline(None, V.decline)
    val declineEffect = decline(Some("effect"), V.decline)
    val declineRefined = decline(Some("refined"), V.decline)

    val metakit = "io.constellationnetwork" %% "metakit" % V.metakit

    val pureconfigCore = "com.github.pureconfig" %% "pureconfig" % V.pureConfig
    val pureconfigCats = "com.github.pureconfig" %% "pureconfig-cats-effect" % V.pureConfig

    val weaverCats = "org.typelevel" %% "weaver-cats" % V.weaver
    val weaverDiscipline = "org.typelevel" %% "weaver-discipline" % V.weaver
    val weaverScalaCheck = "org.typelevel" %% "weaver-scalacheck" % V.weaver

  }

  object CompilerPlugin {
    val betterMonadicFor = compilerPlugin("com.olegpy" %% "better-monadic-for" % V.betterMonadicFor)
    val kindProjector = compilerPlugin(("org.typelevel" % "kind-projector" % V.kindProjector).cross(CrossVersion.full))
    val semanticDB = compilerPlugin(("org.scalameta" % "semanticdb-scalac" % V.semanticDB).cross(CrossVersion.full))
  }
}
