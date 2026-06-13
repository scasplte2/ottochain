package xyz.kd5ujc.shared_data

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, GenesisData, OnChain}

import io.circe.parser.decode
import io.circe.syntax._
import weaver.SimpleIOSuite

object GenesisDataSuite extends SimpleIOSuite {

  private val shape: MachineShape =
    MachineShape(
      stateMessage =
        MessageShape("App.State", List(FieldShape("balance", 1, "int64", repeated = false, optional = false))),
      commands = SortedMap(
        "start" -> MessageShape("App.Start", List(FieldShape("amount", 1, "int64", repeated = false, optional = false)))
      )
    )

  private val entry: RegistryEntry =
    RegistryEntry(
      name = RegistryName.unsafe("escrow.acme.package"),
      owner = Set.empty[Address],
      target = RegistryTarget.SchemaPackage(
        VersionLineage.of(
          RegisteredVersion(
            version = SemVer(1, 0, 0),
            schemaHash = Hash("schema"),
            logicHash = Hash("logic"),
            shape = RegistryShape.Machine(shape),
            status = RegistryStatus.Active,
            registeredAt = SnapshotOrdinal.MinValue,
            strict = false
          )
        )
      )
    )

  private val nonEmptyGenesis: GenesisData =
    GenesisData(
      onChain = OnChain.genesis.copy(registryCommits = SortedMap(entry.name -> Hash("entryhash"))),
      calculated = CalculatedState.genesis.copy(registry = SortedMap(entry.name -> entry))
    )

  test("GenesisData carrying a non-empty registry round-trips through JSON") {
    val json = nonEmptyGenesis.asJson.noSpaces
    IO.pure(expect(decode[GenesisData](json) == Right(nonEmptyGenesis)))
  }

  test("toDataState / from are inverse") {
    val ds = nonEmptyGenesis.toDataState
    IO.pure(
      expect(GenesisData.from(ds) == nonEmptyGenesis) and
      expect(ds.onChain == nonEmptyGenesis.onChain) and
      expect(ds.calculated == nonEmptyGenesis.calculated)
    )
  }
}
