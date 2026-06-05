package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.shared_data.genesis.GenesisBuilder

import weaver.SimpleIOSuite

object GenesisBuilderSuite extends SimpleIOSuite {

  private val shape: SchemaShape =
    SchemaShape(
      stateMessage = MessageShape("App.State", List(FieldShape("balance", 1, "int64"))),
      commands = SortedMap.empty
    )

  private def spec(label: String): GenesisBuilder.PackageSpec =
    GenesisBuilder.PackageSpec(
      name = RegistryName.unsafe(s"$label.package"),
      version = SemVer(1, 0, 0),
      schemaHash = Hash(s"schema-$label"),
      logicHash = Hash(s"logic-$label"),
      schemaShape = shape,
      owner = Set.empty[Address]
    )

  test("withPackages pre-registers every spec with a matching registry commit") {
    val idName = RegistryName.unsafe("identity.package")
    for {
      genesis <- GenesisBuilder.withPackages[IO](List(spec("identity"), spec("governance")))
      entry = genesis.calculated.registry.get(idName)
      commit = genesis.onChain.registryCommits.get(idName)
      expectedCommit <- entry.traverse(_.computeDigest)
    } yield expect(genesis.calculated.registry.size == 2) and
    expect(genesis.onChain.registryCommits.size == 2) and
    expect(commit == expectedCommit) and
    expect(entry.exists(_.target match {
      case RegistryTarget.SchemaPackage(lineage) => lineage.versions.contains(SemVer(1, 0, 0))
      case _                                     => false
    }))
  }

  test("the initial version is Active and stamped at the given genesis ordinal") {
    val name = RegistryName.unsafe("markets.package")
    GenesisBuilder.withPackages[IO](List(spec("markets"))).map { genesis =>
      expect(
        genesis.calculated.registry
          .get(name)
          .exists(_.target match {
            case RegistryTarget.SchemaPackage(lineage) =>
              lineage.head.exists(v => v.status == RegistryStatus.Active && v.registeredAt == SnapshotOrdinal.MinValue)
            case _ => false
          })
      )
    }
  }

  test("an empty spec list yields the empty genesis") {
    GenesisBuilder.withPackages[IO](Nil).map { g =>
      expect(g.calculated.registry.isEmpty) and expect(g.onChain.registryCommits.isEmpty)
    }
  }
}
