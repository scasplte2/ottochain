package xyz.kd5ujc.shared_data

import cats.effect.IO

import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps

import xyz.kd5ujc.schema.GenesisManifest
import xyz.kd5ujc.schema.registry.{RegistryName, RegistryShape, RegistryTarget}
import xyz.kd5ujc.shared_data.genesis.GenesisManifestLoader

import io.circe.parser.decode
import weaver.SimpleIOSuite

object GenesisManifestLoaderSuite extends SimpleIOSuite {

  // A one-package manifest in the exact contract shape the SDK exporter (#46) emits: content only
  // (schemaShape + JSON-Logic definition), no consensus hashes. definition is the proven timeLock fixture.
  private val manifestJson: String =
    """{"version":1,"packages":[{"name":"std.identity.package","semver":"1.0.0","strict":false,"metadata":{},""" +
    """"machineShape":{"stateMessage":{"typeName":"ottochain.apps.identity.v1.Identity",""" +
    """"fields":[{"name":"id","number":1,"typeName":"string","repeated":false,"optional":false}]},"commands":{}},""" +
    """"definition":{"states":{"locked":{"id":"locked","isFinal":false},"unlocked":{"id":"unlocked","isFinal":true}},""" +
    """"initialState":"locked","transitions":[{"from":"locked","to":"unlocked","eventName":"unlock",""" +
    """"guard":{">=":[{"var":"event.currentTime"},{"var":"state.unlockTime"}]},"effect":[["unlocked",true]],""" +
    """"dependencies":[]}]}}]}"""

  private val idName = RegistryName.unsafe("std.identity.package")

  test("the manifest contract decodes and fromManifest pre-registers each package") {
    for {
      manifest <- IO.fromEither(decode[GenesisManifest](manifestJson))
      genesis  <- GenesisManifestLoader.fromManifest[IO](manifest)
    } yield expect(manifest.packages.size == 1) and
    expect(genesis.calculated.registry.contains(idName)) and
    expect(genesis.calculated.registryCommits.contains(idName)) and
    expect(
      genesis.calculated.registry
        .get(idName)
        .exists(_.target match {
          case RegistryTarget.SchemaPackage(lineage) =>
            lineage.head.exists(_.shape match {
              case RegistryShape.Machine(ms) => ms.stateMessage.typeName == "ottochain.apps.identity.v1.Identity"
              case _                         => false
            })
          case _ => false
        })
    )
  }

  test("schemaHash/logicHash are chain-computed (identical to the content's own computeDigest)") {
    for {
      manifest <- IO.fromEither(decode[GenesisManifest](manifestJson))
      genesis  <- GenesisManifestLoader.fromManifest[IO](manifest)
      pkg = manifest.packages.head
      expectedLogic  <- pkg.definition.computeDigest
      expectedSchema <- pkg.machineShape.computeDigest
      registered = genesis.calculated.registry
        .get(idName)
        .flatMap(_.target match {
          case RegistryTarget.SchemaPackage(lineage) => lineage.head
          case _                                     => None
        })
    } yield expect(registered.exists(_.logicHash == expectedLogic)) and
    expect(registered.exists(_.schemaHash == expectedSchema))
  }
}
