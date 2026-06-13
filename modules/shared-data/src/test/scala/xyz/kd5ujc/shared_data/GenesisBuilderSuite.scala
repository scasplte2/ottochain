package xyz.kd5ujc.shared_data

import java.util.UUID

import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.metagraph_sdk.json_logic.IntValue
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.fiber.StateMachineDefinition
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.shared_data.genesis.GenesisBuilder
import xyz.kd5ujc.shared_data.testkit.FiberBuilder

import io.circe.parser.decode
import weaver.SimpleIOSuite

object GenesisBuilderSuite extends SimpleIOSuite {

  private val shape: MachineShape =
    MachineShape(
      stateMessage =
        MessageShape("App.State", List(FieldShape("balance", 1, "int64", repeated = false, optional = false))),
      commands = SortedMap.empty
    )

  private def spec(label: String): GenesisBuilder.PackageSpec =
    GenesisBuilder.PackageSpec(
      name = RegistryName.unsafe(s"$label.package"),
      version = SemVer(1, 0, 0),
      schemaHash = Hash(s"schema-$label"),
      logicHash = Hash(s"logic-$label"),
      machineShape = shape,
      owner = Set.empty[Address]
    )

  // A minimal 2-state / 1-transition definition for fiber-record fixtures (guard + effect are required by
  // the chain's StateMachineDefinition decoder, unlike the TS schema where they are optional).
  private val defJson: String =
    """{"states":{"locked":{"id":"locked","isFinal":false},"unlocked":{"id":"unlocked","isFinal":true}},"initialState":"locked","transitions":[{"from":"locked","to":"unlocked","eventName":"unlock","guard":{">=":[{"var":"event.currentTime"},{"var":"state.unlockTime"}]},"effect":[["unlocked",true]],"dependencies":[]}]}"""

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

  test("aliases register an InstanceAlias entry, a commit, and a reverse record") {
    val fid = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    val aliasName = RegistryName.unsafe("bob.machine")
    GenesisBuilder.build[IO](aliases = List(GenesisBuilder.AliasSpec(aliasName, fid))).map { g =>
      expect(
        g.calculated.registry
          .get(aliasName)
          .exists(_.target match {
            case RegistryTarget.InstanceAlias(id) => id == fid
            case _                                => false
          })
      ) and
      expect(g.onChain.registryCommits.contains(aliasName)) and
      expect(g.calculated.reverseNames.get(fid).contains(aliasName))
    }
  }

  test("build composes packages, aliases (reverse records), and fiber records consistently") {
    val fid = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    val aliasName = RegistryName.unsafe("alice.machine")
    for {
      parsedDef <- IO.fromEither(decode[StateMachineDefinition](defJson))
      fiber <- FiberBuilder(fid, SnapshotOrdinal.MinValue, parsedDef)
        .withState("draft")
        .withData("x" -> IntValue(BigInt(1)))
        .build[IO]
      genesis <- GenesisBuilder.build[IO](
        packages = List(spec("identity")),
        aliases = List(GenesisBuilder.AliasSpec(aliasName, fid)),
        fibers = Map(fid -> fiber)
      )
      expectedCommit <- fiber.computeDigest
    } yield expect(genesis.calculated.registry.contains(RegistryName.unsafe("identity.package"))) and
    expect(
      genesis.calculated.registry
        .get(aliasName)
        .exists(_.target match {
          case RegistryTarget.InstanceAlias(id) => id == fid
          case _                                => false
        })
    ) and
    expect(genesis.calculated.reverseNames.get(fid).contains(aliasName)) and
    expect(genesis.calculated.stateMachines.get(fid).contains(fiber)) and
    expect(genesis.onChain.fiberCommits.get(fid).exists(_.recordHash == expectedCommit)) and
    expect(genesis.onChain.registryCommits.contains(aliasName))
  }
}
