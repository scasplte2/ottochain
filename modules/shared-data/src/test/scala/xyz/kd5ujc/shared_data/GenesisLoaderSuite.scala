package xyz.kd5ujc.shared_data

import cats.effect.IO

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.DataState
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, GenesisData, OnChain}
import xyz.kd5ujc.shared_data.genesis.GenesisLoader

import fs2.io.file.{Files, Path}
import fs2.{Stream, text}
import io.circe.syntax._
import weaver.SimpleIOSuite

object GenesisLoaderSuite extends SimpleIOSuite {

  private val shape: MachineShape =
    MachineShape(
      stateMessage =
        MessageShape("App.State", List(FieldShape("balance", 1, "int64", repeated = false, optional = false))),
      commands = SortedMap.empty
    )

  private val entry: RegistryEntry =
    RegistryEntry(
      name = RegistryName.unsafe("identity.package"),
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

  private val populatedState: DataState[OnChain, CalculatedState] =
    DataState(
      OnChain.genesis.copy(registryCommits = SortedMap(entry.name -> Hash("entryhash"))),
      CalculatedState.genesis.copy(registry = SortedMap(entry.name -> entry))
    )

  private val emptyState: DataState[OnChain, CalculatedState] =
    DataState(OnChain.genesis, CalculatedState.genesis)

  /** Write `content` to a fresh temp file and run `f` against its path. */
  private def withTempFile[A](content: String)(f: Path => IO[A]): IO[A] =
    Files[IO].tempFile.use { path =>
      Stream
        .emit(content)
        .through(text.utf8.encode)
        .through(Files[IO].writeAll(path))
        .compile
        .drain *> f(path)
    }

  test("load(None) yields the empty genesis") {
    GenesisLoader.load[IO](None).map { ds =>
      expect(ds == emptyState) and
      expect(ds.calculated.registry.isEmpty) and
      expect(ds.onChain.registryCommits.isEmpty)
    }
  }

  test("load(Some(path)) round-trips a serialized DataState") {
    val json = GenesisData.from(populatedState).asJson.noSpaces
    withTempFile(json) { path =>
      GenesisLoader.load[IO](Some(path.toString)).map(ds => expect(ds == populatedState))
    }
  }

  test("load(Some(nonexistent)) raises") {
    GenesisLoader
      .load[IO](Some("/nonexistent/does-not-exist.json"))
      .attempt
      .map(result => expect(result.isLeft))
  }

  test("load(Some(garbage)) raises") {
    withTempFile("this is not valid json {{{") { path =>
      GenesisLoader
        .load[IO](Some(path.toString))
        .attempt
        .map(result => expect(result.isLeft))
    }
  }
}
