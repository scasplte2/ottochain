package xyz.kd5ujc.shared_data

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, OnChain}

import io.circe.parser.decode
import io.circe.syntax._
import weaver.SimpleIOSuite

object RegistryCodecSuite extends SimpleIOSuite {

  private val shape: SchemaShape =
    SchemaShape(
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
            schemaShape = shape,
            status = RegistryStatus.Active,
            registeredAt = SnapshotOrdinal.MinValue
          )
        )
      )
    )

  test("RegistryEntry round-trips through JSON") {
    val json = entry.asJson.noSpaces
    IO.pure(expect(decode[RegistryEntry](json) == Right(entry)))
  }

  test("CalculatedState carrying a registry entry round-trips") {
    val cs = CalculatedState.genesis.copy(registry = SortedMap(entry.name -> entry))
    val json = cs.asJson.noSpaces
    IO.pure(expect(decode[CalculatedState](json) == Right(cs)))
  }

  test("OnChain carrying a registry commit round-trips") {
    val oc = OnChain.genesis.copy(registryCommits = SortedMap(entry.name -> Hash("entryhash")))
    val json = oc.asJson.noSpaces
    IO.pure(expect(decode[OnChain](json) == Right(oc)))
  }
}
