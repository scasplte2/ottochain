package xyz.kd5ujc.shared_test

import java.util.UUID

import cats.effect.IO

import scala.io.Source

import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.signature.SignatureProof

import xyz.kd5ujc.schema.Updates
import xyz.kd5ujc.schema.fiber.AccessControlPolicy
import xyz.kd5ujc.shared_test.Participant._

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.parser
import weaver.SimpleIOSuite

/**
 * Tests verifying:
 * 1. Static participants match genesis.csv (addresses are deterministic)
 * 2. FeeTransaction can be constructed alongside data updates
 *    in the format tessellation's DL1 expects
 */
object GenesisAndFeeSuite extends SimpleIOSuite {

  /** Parse genesis.csv from test resources */
  private def loadGenesis: Map[String, Long] = {
    val stream = getClass.getClassLoader.getResourceAsStream("participants/genesis.csv")
    val lines = Source.fromInputStream(stream).getLines().toList
    stream.close()
    lines.map { line =>
      val parts = line.split(",")
      parts(0) -> parts(1).toLong
    }.toMap
  }

  test("genesis.csv contains all 26 participant addresses") {
    IO {
      val genesis = loadGenesis
      expect.all(
        genesis.size == 26,
        genesis.values.forall(_ == 100000000000000000L)
      )
    }
  }

  test("fromResources addresses match genesis.csv") {
    SecurityProvider.forAsync[IO].use { implicit sp =>
      ParticipantRegistry.fromResources[IO]().map { registry =>
        val genesis = loadGenesis
        val addresses = registry.addresses

        val allMatch = addresses.forall { case (_, addr) =>
          genesis.contains(addr.value.value)
        }

        expect.all(
          allMatch,
          addresses(Participant.Alice).value.value == "DAG6HdXmFyEwgKKdaEyAjU6SJPxGNAjUSbHgiRct",
          addresses(Participant.Zoe).value.value == "DAG8a3BV4YvGYgnL6wMF6Lc6mx37EtbRG9PQYGRs"
        )
      }
    }
  }

  test("genesis balances can be loaded as tessellation Balance map") {
    SecurityProvider.forAsync[IO].use { implicit sp =>
      ParticipantRegistry.fromResources[IO]().map { registry =>
        val genesis = loadGenesis
        val addresses = registry.addresses

        val balances: Map[Address, Balance] = addresses.map { case (_, addr) =>
          val amount = genesis(addr.value.value)
          addr -> Balance(NonNegLong.unsafeFrom(amount))
        }

        expect.all(
          balances.size == 26,
          balances.values.forall(_.value.value == 100000000000000000L)
        )
      }
    }
  }

  test("FeeTransaction can be constructed and signed alongside a DataUpdate") {
    SecurityProvider.forAsync[IO].use { implicit sp =>
      ParticipantRegistry.fromResources[IO]().flatMap { registry =>
        val alice = registry(Participant.Alice)
        val bob = registry(Participant.Bob)

        val fiberId = UUID.randomUUID()

        // Use a simple parsed JSON expression for the script program
        val progJson = parser
          .parse("""{"if": [true, {"value": 1}, {"value": 0}]}""")
          .flatMap(_.as[JsonLogicExpression])
          .toOption
          .get

        val dataUpdate: Updates.OttochainMessage = Updates.CreateScript(
          fiberId = fiberId,
          scriptProgram = progJson,
          initialState = Some(core.MapValue(Map("value" -> core.IntValue(0)))),
          accessControl = AccessControlPolicy.Public
        )

        for {
          // Sign the data update
          dataProof <- alice.proof(dataUpdate)

          // Compute hash of data update (fee must reference this)
          dataUpdateHash <- dataUpdate.computeDigest

          // Construct a FeeTransaction per tessellation's format:
          //   FeeTransaction(source, destination, amount, dataUpdateRef)
          feeAmount = Amount(NonNegLong.unsafeFrom(1000000L)) // 0.01 tokens
          feeTransaction = io.constellationnetwork.currency.dataApplication.FeeTransaction(
            source = alice.address,
            destination = bob.address,
            amount = feeAmount,
            dataUpdateRef = dataUpdateHash
          )

          // Sign the fee transaction with payer's key
          feeProof <- SignatureProof.fromHash(alice.keyPair, dataUpdateHash)

        } yield expect.all(
          // Data update is properly signed
          dataProof.id.hex.value.nonEmpty,
          // Fee references the correct data update
          feeTransaction.dataUpdateRef == dataUpdateHash,
          // Fee source = payer
          feeTransaction.source == alice.address,
          // Fee destination = collector
          feeTransaction.destination == bob.address,
          // Fee amount set
          feeTransaction.amount.value.value == 1000000L,
          // Fee proof signed
          feeProof.id.hex.value.nonEmpty
        )
      }
    }
  }
}
