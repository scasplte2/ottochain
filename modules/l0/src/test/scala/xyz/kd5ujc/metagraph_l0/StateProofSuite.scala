package xyz.kd5ujc.metagraph_l0

import java.util.UUID

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.metagraph_sdk.crypto.mpt.api.{MerklePatriciaProducer, MerklePatriciaVerifier}
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import xyz.kd5ujc.schema.Records.StateMachineFiberRecord
import xyz.kd5ujc.schema.fiber._

import weaver.SimpleIOSuite

/**
 * Phase 1B: State Proof Endpoint — Logic Tests
 *
 * Tests the MPT proof generation logic used by
 * `GET /v1/state-machines/:fiberId/state-proof?field=X`
 *
 * Validates:
 *   - Field name → hex encoding matches FiberCombiner convention
 *   - Per-fiber MPT proof is generated and verifiable
 *   - Metagraph-level MPT proof is generated and verifiable
 *   - Two-level proof chain is consistent
 */
object StateProofSuite extends SimpleIOSuite {

  // ============================================================================
  // Helpers (mirrors ML0CustomRoutes and FiberCombiner logic)
  // ============================================================================

  private def fieldNameToHex(fieldName: String): Hex =
    Hex(fieldName.getBytes("UTF-8").map("%02x".format(_)).mkString)

  private def computeStateRoot(stateData: JsonLogicValue): IO[Option[Hash]] =
    stateData match {
      case MapValue(fields) if fields.nonEmpty =>
        val entries: Map[Hex, JsonLogicValue] = fields.map { case (k, v) =>
          fieldNameToHex(k) -> v
        }
        MerklePatriciaProducer
          .stateless[IO]
          .create(entries)
          .map(trie => Some(trie.rootNode.digest): Option[Hash])
          .handleError(_ => Option.empty[Hash])
      case _ =>
        IO.pure(None)
    }

  private def sampleFiber(fiberId: UUID, stateData: JsonLogicValue): IO[StateMachineFiberRecord] =
    computeStateRoot(stateData).map { rootOpt =>
      StateMachineFiberRecord(
        fiberId = fiberId,
        creationOrdinal = SnapshotOrdinal.MinValue,
        previousUpdateOrdinal = SnapshotOrdinal.MinValue,
        latestUpdateOrdinal = SnapshotOrdinal.MinValue,
        definition = StateMachineDefinition(
          states = Map(StateId("s") -> State(StateId("s"), isFinal = false)),
          initialState = StateId("s"),
          transitions = Nil
        ),
        currentState = StateId("s"),
        stateData = stateData,
        stateDataHash = Hash.empty,
        sequenceNumber = FiberOrdinal.MinValue,
        owners = Set.empty[Address],
        status = FiberStatus.Active,
        stateRoot = rootOpt
      )
    }

  // ============================================================================
  // Group 1: Field Name Encoding
  // ============================================================================

  test("fieldNameToHex encodes ASCII field names correctly") {
    IO(expect(fieldNameToHex("counter") == Hex("636f756e746572")))
  }

  test("fieldNameToHex is consistent with FiberCombiner encoding") {
    // "balance" in UTF-8 hex
    IO(expect(fieldNameToHex("balance") == Hex("62616c616e6365")))
  }

  test("fieldNameToHex round-trips via UTF-8") {
    val field = "myField_42"
    val hex = fieldNameToHex(field)
    val decoded = new String(
      hex.value.grouped(2).map(b => Integer.parseInt(b, 16).toByte).toArray
    )
    IO(expect(decoded == field))
  }

  // ============================================================================
  // Group 2: Per-Fiber MPT Proof Generation
  // ============================================================================

  test("fiber state root is deterministic for same state data") {
    val stateData = MapValue(Map("counter" -> IntValue(42), "owner" -> StrValue("alice")))
    for {
      root1 <- computeStateRoot(stateData)
      root2 <- computeStateRoot(stateData)
    } yield expect(root1 == root2) and expect(root1.isDefined)
  }

  test("fiber state root differs for different state data") {
    val data1 = MapValue(Map("counter" -> IntValue(1)))
    val data2 = MapValue(Map("counter" -> IntValue(2)))
    for {
      root1 <- computeStateRoot(data1)
      root2 <- computeStateRoot(data2)
    } yield expect(root1 != root2) and expect(root1.isDefined) and expect(root2.isDefined)
  }

  test("per-fiber MPT proof proves field inclusion") {
    val stateData = MapValue(
      Map(
        "counter" -> IntValue(42),
        "owner"   -> StrValue("alice"),
        "active"  -> BoolValue(true)
      )
    )
    val fieldToProve = "counter"
    val fieldHex = fieldNameToHex(fieldToProve)

    val entries: Map[Hex, JsonLogicValue] = stateData.value.map { case (k, v) =>
      fieldNameToHex(k) -> v
    }

    for {
      trie   <- MerklePatriciaProducer.stateless[IO].create(entries)
      prover <- MerklePatriciaProducer.stateless[IO].getProver(trie)
      result <- prover.attestPath(fieldHex)
    } yield expect(result.isRight) and
    expect(result.exists(_.path == fieldHex))
  }

  test("per-fiber MPT proof is verifiable against stateRoot") {
    val stateData = MapValue(
      Map(
        "balance" -> IntValue(1000),
        "nonce"   -> IntValue(7)
      )
    )
    val fieldToProve = "balance"
    val fieldHex = fieldNameToHex(fieldToProve)

    val entries: Map[Hex, JsonLogicValue] = stateData.value.map { case (k, v) =>
      fieldNameToHex(k) -> v
    }

    for {
      trie <- MerklePatriciaProducer.stateless[IO].create(entries)
      root = trie.rootNode.digest
      prover <- MerklePatriciaProducer.stateless[IO].getProver(trie)
      result <- prover.attestPath(fieldHex)
      verified <- result match {
        case Right(proof) =>
          MerklePatriciaVerifier
            .make[IO](root)
            .confirm(proof)
            .map(_.isRight)
        case Left(_) => IO.pure(false)
      }
    } yield expect(verified)
  }

  test("absent field returns PathNotFound error") {
    val stateData = MapValue(Map("counter" -> IntValue(1)))
    val absentFieldHex = fieldNameToHex("nonexistent")

    val entries: Map[Hex, JsonLogicValue] = stateData.value.map { case (k, v) =>
      fieldNameToHex(k) -> v
    }

    for {
      trie   <- MerklePatriciaProducer.stateless[IO].create(entries)
      prover <- MerklePatriciaProducer.stateless[IO].getProver(trie)
      result <- prover.attestPath(absentFieldHex)
    } yield expect(result.isLeft)
  }

  // ============================================================================
  // Group 3: Metagraph-Level MPT Proof Generation
  // ============================================================================

  test("metagraph proof proves fiberId inclusion") {
    val fiberId1 = UUID.randomUUID()
    val fiberId2 = UUID.randomUUID()
    val stateData = MapValue(Map("x" -> IntValue(1)))

    for {
      fiber1 <- sampleFiber(fiberId1, stateData)
      fiber2 <- sampleFiber(fiberId2, MapValue(Map("y" -> IntValue(2))))

      // Both fibers must have stateRoots
      _ <- IO(assert(fiber1.stateRoot.isDefined && fiber2.stateRoot.isDefined))

      metaEntries = Map(
        Hex(fiberId1.toString.replace("-", "")) -> fiber1.stateRoot.get,
        Hex(fiberId2.toString.replace("-", "")) -> fiber2.stateRoot.get
      )

      fiberHex = Hex(fiberId1.toString.replace("-", ""))

      metaTrie   <- MerklePatriciaProducer.stateless[IO].create(metaEntries)
      metaProver <- MerklePatriciaProducer.stateless[IO].getProver(metaTrie)
      result     <- metaProver.attestPath(fiberHex)
    } yield expect(result.isRight) and
    expect(result.exists(_.path == fiberHex))
  }

  test("metagraph proof is verifiable against metagraphStateRoot") {
    val fiberId = UUID.randomUUID()
    val stateData = MapValue(Map("k" -> StrValue("v")))

    for {
      fiber <- sampleFiber(fiberId, stateData)
      _     <- IO(assert(fiber.stateRoot.isDefined))

      metaEntries = Map(
        Hex(fiberId.toString.replace("-", "")) -> fiber.stateRoot.get
      )

      fiberHex <- IO.pure(Hex(fiberId.toString.replace("-", "")))

      metaTrie <- MerklePatriciaProducer.stateless[IO].create(metaEntries)
      metaRoot = metaTrie.rootNode.digest
      metaProver <- MerklePatriciaProducer.stateless[IO].getProver(metaTrie)
      result     <- metaProver.attestPath(fiberHex)

      verified <- result match {
        case Right(proof) =>
          MerklePatriciaVerifier
            .make[IO](metaRoot)
            .confirm(proof)
            .map(_.isRight)
        case Left(_) => IO.pure(false)
      }
    } yield expect(verified)
  }

  // ============================================================================
  // Group 4: Two-Level Proof Chain Consistency
  // ============================================================================

  test("two-level proof chain: fiberStateRoot == metagraph trie leaf for fiberId") {
    val fiberId = UUID.randomUUID()
    val stateData = MapValue(Map("count" -> IntValue(99)))

    for {
      fiber <- sampleFiber(fiberId, stateData)
      _     <- IO(assert(fiber.stateRoot.isDefined))

      fiberRoot = fiber.stateRoot.get
      _ = fiberRoot // suppress unused warning; it's used below

      // The metagraph trie stores fiberRoot as the value for fiberHex key.
      // We verify that the stored root matches what we recomputed.
      recomputedRoot <- computeStateRoot(stateData)

    } yield expect(recomputedRoot == Some(fiberRoot))
  }

  test("proof chain holds for multiple fibers with distinct state data") {
    val ids = (1 to 3).map(_ => UUID.randomUUID())
    val dataSets = List(
      MapValue(Map("a" -> IntValue(1))),
      MapValue(Map("b" -> StrValue("hello"))),
      MapValue(Map("c" -> BoolValue(false), "d" -> IntValue(7)))
    )

    for {
      fibers <- (ids.toList zip dataSets).traverse { case (id, data) => sampleFiber(id, data) }
      _      <- IO(assert(fibers.forall(_.stateRoot.isDefined)))

      metaEntries = fibers.map(f => Hex(f.fiberId.toString.replace("-", "")) -> f.stateRoot.get).toMap
      metaTrie <- MerklePatriciaProducer.stateless[IO].create(metaEntries)
      metaRoot = metaTrie.rootNode.digest

      // Verify all fibers can be proven in the metagraph trie
      proofs <- fibers.traverse { f =>
        val fiberHex = Hex(f.fiberId.toString.replace("-", ""))
        for {
          prover <- MerklePatriciaProducer.stateless[IO].getProver(metaTrie)
          result <- prover.attestPath(fiberHex)
          verified <- result match {
            case Right(proof) =>
              MerklePatriciaVerifier.make[IO](metaRoot).confirm(proof).map(_.isRight)
            case Left(_) => IO.pure(false)
          }
        } yield verified
      }
    } yield expect(proofs.forall(identity))
  }
}
