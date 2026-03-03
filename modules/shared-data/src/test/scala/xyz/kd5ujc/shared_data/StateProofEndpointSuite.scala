package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.dataApplication.L0NodeContext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.json_logic.runtime.JsonLogicEvaluator
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.{CalculatedState, Records}
import xyz.kd5ujc.shared_data.fiber.FiberEngine
import xyz.kd5ujc.shared_test.TestFixture

import io.circe.Json
import weaver.SimpleIOSuite

/**
 * TDD tests for GET /v1/state-machines/:fiberId/state-proof endpoint
 *
 * Phase 1B feature: Merkle proof generation for state machine fields
 * Spec: Must be on ML0, uses stateless MerklePatriciaProducer, <5ms for 5-leaf
 * 
 * This test suite covers:
 * - Basic proof generation for state machine fields
 * - Error handling (missing fiber, invalid field)
 * - Proof verification and validation
 * - Two-level proof chain (field→stateRoot + fiberId→metagraphStateRoot)
 * - Performance requirements (<5ms)
 * - RFC 8785 canonicalization compliance
 * 
 * ALL TESTS WILL FAIL until the endpoint is implemented - this is TDD!
 */
object StateProofEndpointSuite extends SimpleIOSuite {

  // =========================================================================
  // Basic Proof Generation Tests
  // =========================================================================

  test("GET /v1/state-machines/:fiberId/state-proof?field=X returns valid proof structure") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider  
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val ordinal = fixture.ordinal
      
      for {
        fiberId <- UUIDGen.randomUUID[IO]
        
        // Create state machine with multiple fields for proof
        stateData = MapValue(Map(
          "balance" -> IntValue(1000),
          "owner" -> StrValue("alice"),
          "status" -> StrValue("active"),
          "metadata" -> MapValue(Map(
            "created" -> IntValue(1640995200), 
            "version" -> IntValue(2)
          ))
        ))
        stateHash <- (stateData: JsonLogicValue).computeDigest

        definition = StateMachineDefinition(
          states = Map(StateId("active") -> State(StateId("active"))),
          initialState = StateId("active"),
          transitions = List.empty
        )
        
        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal, 
          definition = definition,
          currentState = StateId("active"),
          stateData = stateData,
          stateDataHash = stateHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        // TODO: This will fail until ML0CustomRoutes implements state-proof endpoint
        // Expected response structure from the spec:
        // {
        //   "field": "balance", 
        //   "value": 1000,
        //   "proof": {
        //     "merkleRoot": "hash...",
        //     "siblings": ["hash1", "hash2", ...],
        //     "path": "01011...",
        //     "leafData": {...}
        //   },
        //   "fiberStateRoot": "hash...",
        //   "metagraphStateRoot": "hash..." 
        // }
        
        // This HTTP call will fail with 404 until endpoint exists
        result <- IO.raiseError[Json](
          new RuntimeException(
            "GET /v1/state-machines/:fiberId/state-proof endpoint not implemented yet"
          )
        )
        
      } yield {
        // Test proof structure validation (will fail until implemented)
        val proof = result.hcursor.downField("proof")
        expect(proof.downField("merkleRoot").as[String].isRight) and
        expect(proof.downField("siblings").as[List[String]].isRight) and  
        expect(proof.downField("path").as[String].isRight) and
        expect(result.hcursor.downField("field").as[String].contains("balance")) and
        expect(result.hcursor.downField("value").as[Int].contains(1000))
      }
    }
  }

  test("state proof for nested field returns correct merkle path") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val ordinal = fixture.ordinal
      
      for {
        fiberId <- UUIDGen.randomUUID[IO]
        
        stateData = MapValue(Map(
          "user" -> MapValue(Map(
            "profile" -> MapValue(Map(
              "name" -> StrValue("alice"),
              "email" -> StrValue("alice@example.com") 
            )),
            "settings" -> MapValue(Map(
              "theme" -> StrValue("dark"),
              "notifications" -> BoolValue(true)
            ))
          ))
        ))
        
        // TODO: Will fail until endpoint supports nested field queries
        // Should support field=user.profile.name syntax
        result <- IO.raiseError[Json](
          new RuntimeException(
            "Nested field proof queries not implemented yet" 
          )
        )
        
      } yield {
        expect(result.hcursor.downField("field").as[String].contains("user.profile.name")) and
        expect(result.hcursor.downField("value").as[String].contains("alice"))
      }
    }
  }

  // =========================================================================  
  // Error Handling Tests
  // =========================================================================

  test("state proof for non-existent fiber returns 404") {
    TestFixture.resource().use { fixture =>
      for {
        nonExistentId <- UUIDGen.randomUUID[IO]
        
        // TODO: Will fail until endpoint exists 
        // Should return 404 for missing fiber
        result <- IO.raiseError[Exception](
          new RuntimeException("Endpoint returns 404 for missing fiber")
        )
        
      } yield failure("Should return 404 status")
    }
  }

  test("state proof for non-existent field returns 400") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val ordinal = fixture.ordinal
      
      for {
        fiberId <- UUIDGen.randomUUID[IO]
        
        stateData = MapValue(Map("existing" -> StrValue("value")))
        stateHash <- (stateData: JsonLogicValue).computeDigest

        definition = StateMachineDefinition(
          states = Map(StateId("active") -> State(StateId("active"))),
          initialState = StateId("active"), 
          transitions = List.empty
        )
        
        fiber = Records.StateMachineFiberRecord(
          fiberId = fiberId,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("active"),
          stateData = stateData, 
          stateDataHash = stateHash,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )
        
        // TODO: Query for field=nonexistent should return 400
        result <- IO.raiseError[Exception](
          new RuntimeException("Should return 400 for non-existent field")
        )
        
      } yield failure("Should return 400 status")
    }
  }

  test("state proof without field query parameter returns 400") {
    TestFixture.resource().use { fixture =>
      for {
        fiberId <- UUIDGen.randomUUID[IO]
        
        // TODO: GET /v1/state-machines/:fiberId/state-proof (no ?field=X)
        // Should require field parameter
        result <- IO.raiseError[Exception](
          new RuntimeException("Should require field parameter")
        )
        
      } yield failure("Should return 400 status for missing field parameter")
    }
  }

  // =========================================================================
  // Two-Level Proof Chain Tests  
  // =========================================================================

  test("state proof includes both fiber-level and metagraph-level proof components") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val ordinal = fixture.ordinal
      
      for {
        fiberId1 <- UUIDGen.randomUUID[IO] 
        fiberId2 <- UUIDGen.randomUUID[IO]
        
        // Create multiple fibers to test metagraph-level proof
        data1 = MapValue(Map("field1" -> IntValue(100)))
        data2 = MapValue(Map("field2" -> IntValue(200)))
        
        hash1 <- (data1: JsonLogicValue).computeDigest
        hash2 <- (data2: JsonLogicValue).computeDigest

        definition = StateMachineDefinition(
          states = Map(StateId("active") -> State(StateId("active"))),
          initialState = StateId("active"),
          transitions = List.empty
        )
        
        fiber1 = Records.StateMachineFiberRecord(
          fiberId = fiberId1,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal, 
          latestUpdateOrdinal = ordinal,
          definition = definition,
          currentState = StateId("active"),
          stateData = data1,
          stateDataHash = hash1,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active
        )

        fiber2 = Records.StateMachineFiberRecord(
          fiberId = fiberId2,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal, 
          definition = definition,
          currentState = StateId("active"),
          stateData = data2,
          stateDataHash = hash2,
          sequenceNumber = FiberOrdinal.MinValue,
          owners = Set.empty,
          status = FiberStatus.Active  
        )
        
        calculatedState = CalculatedState(
          SortedMap(fiberId1 -> fiber1, fiberId2 -> fiber2),
          SortedMap.empty
        )
        
        // TODO: Will fail until two-level proof is implemented
        // Should return both:
        // 1. Field proof within fiber state (field1 → fiber1's stateRoot)
        // 2. Fiber proof within metagraph (fiberId1 → metagraphStateRoot)
        result <- IO.raiseError[Json](
          new RuntimeException("Two-level proof chain not implemented")
        )
        
      } yield {
        val fiberProof = result.hcursor.downField("fiberProof")
        val metagraphProof = result.hcursor.downField("metagraphProof") 
        
        expect(fiberProof.downField("merkleRoot").as[String].isRight) and
        expect(metagraphProof.downField("merkleRoot").as[String].isRight) and
        expect(result.hcursor.downField("chainValid").as[Boolean].contains(true))
      }
    }
  }

  // =========================================================================
  // Performance Tests
  // =========================================================================

  test("state proof generation completes within 5ms for 5-leaf trie") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val ordinal = fixture.ordinal
      
      for {
        fiberId <- UUIDGen.randomUUID[IO]
        
        // Create 5-field state for performance test
        stateData = MapValue(Map(
          "field1" -> StrValue("value1"),
          "field2" -> StrValue("value2"), 
          "field3" -> StrValue("value3"),
          "field4" -> StrValue("value4"),
          "field5" -> StrValue("value5")
        ))
        
        startTime <- IO.realTime
        
        // TODO: Will fail until endpoint exists
        result <- IO.raiseError[Json](
          new RuntimeException("Performance test requires implemented endpoint")
        )
        
        endTime <- IO.realTime
        duration = endTime - startTime
        
      } yield {
        expect(duration.toMillis < 5L) and
        expect(result.hcursor.downField("field").as[String].isRight)
      }
    }
  }

  // =========================================================================  
  // Canonicalization Tests
  // ========================================================================= 

  test("state proofs use RFC 8785 canonical JSON serialization") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val ordinal = fixture.ordinal
      
      for {
        fiberId <- UUIDGen.randomUUID[IO]
        
        // Test with unordered map fields 
        stateData = MapValue(Map(
          "z_field" -> StrValue("last"),
          "a_field" -> StrValue("first"),
          "m_field" -> StrValue("middle")
        ))
        
        // TODO: Will fail until canonicalization is verified
        // Proof should be deterministic regardless of field order
        result1 <- IO.raiseError[Json](
          new RuntimeException("Canonicalization test requires endpoint")
        )
        
        result2 <- IO.raiseError[Json](
          new RuntimeException("Canonicalization test requires endpoint") 
        )
        
      } yield {
        // Same state should produce identical proof
        expect(result1 == result2) 
      }
    }
  }

  // =========================================================================
  // Proof Verification Tests
  // =========================================================================

  test("returned proof can be cryptographically verified against state root") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val ordinal = fixture.ordinal
      
      for {
        fiberId <- UUIDGen.randomUUID[IO]
        
        stateData = MapValue(Map(
          "verified" -> StrValue("data"),
          "amount" -> IntValue(42)
        ))
        
        // TODO: Will fail until proof verification is implemented
        // Should include all data needed for independent verification
        result <- IO.raiseError[Json](
          new RuntimeException("Proof verification requires endpoint")
        )
        
        // TODO: Extract proof components and verify
        isValid <- IO.pure(false) // Will fail until verification implemented
        
      } yield expect(isValid)
    }
  }

  test("malformed field queries return appropriate error responses") {
    TestFixture.resource().use { fixture =>
      for {
        fiberId <- UUIDGen.randomUUID[IO]
        
        // TODO: Test various malformed queries:
        // - field=invalid..syntax
        // - field=
        // - field=deeply.nested.non.existent.path
        
        result <- IO.raiseError[Exception](
          new RuntimeException("Malformed query handling not implemented")
        )
        
      } yield failure("Should handle malformed queries gracefully")
    }
  }
}