package xyz.kd5ujc.shared_data.examples

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider

import xyz.kd5ujc.schema.fiber.{FiberOrdinal, _}
import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_data.syntax.all._
import xyz.kd5ujc.shared_data.testkit.{DataStateTestOps, FiberBuilder, TestImports}
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

object NftMarketplaceSuite extends SimpleIOSuite {

  import DataStateTestOps._
  import TestImports.optionFiberRecordOps

  test("nft-marketplace: spawned listing triggers royalty oracle on sale") {
    import io.circe.parser._

    TestFixture.resource(Set(Alice, Bob, Charlie)).use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      val ordinal = fixture.ordinal

      for {
        combiner <- Combiner.make[IO]().pure[IO]

        // Get royalty oracle ID early to reference in NFT listing definition
        royaltyOracleId <- UUIDGen.randomUUID[IO]

        // JSON-encoded oracle program for calculating and recording royalties
        royaltyOracleJson =
          """{
            "if": [
              { "===": [{ "var": "method" }, "recordRoyalty" ] },
              {
                "_state": {
                  "totalRoyalties": {
                    "+": [
                      {
                        "if": [
                          { "var": "state.totalRoyalties" },
                          { "var": "state.totalRoyalties" },
                          0
                        ]
                      },
                      { "var": "args.royaltyAmount" }
                    ]
                  },
                  "lastRecorded": { "var": "args" }
                },
                "_result": {
                  "success": true,
                  "totalRoyalties": {
                    "+": [
                      {
                        "if": [
                          { "var": "state.totalRoyalties" },
                          { "var": "state.totalRoyalties" },
                          0
                        ]
                      },
                      { "var": "args.royaltyAmount" }
                    ]
                  }
                }
              },
              {
                "_state": { "var": "state" },
                "_result": { "error": "Unknown method" }
              }
            ]
          }"""

        royaltyProgramExpr <- IO.fromEither(
          decode[JsonLogicExpression](royaltyOracleJson).left
            .map(err => new RuntimeException(s"Failed to decode oracle JSON: $err"))
        )

        // Create royalty oracle
        royaltyOracleData = MapValue(Map("totalRoyalties" -> IntValue(0)))
        royaltyHash <- (royaltyOracleData: JsonLogicValue).computeDigest

        royaltyOracle = Records.ScriptOracleFiberRecord(
          fiberId = royaltyOracleId,
          creationOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          scriptProgram = royaltyProgramExpr,
          stateData = Some(royaltyOracleData),
          stateDataHash = Some(royaltyHash),
          accessControl = AccessControlPolicy.Public,
          owners = Set(registry.addresses(Alice)),
          status = FiberStatus.Active,
          sequenceNumber = FiberOrdinal.MinValue
        )

        // JSON-encoded NFT listing state machine with oracle trigger on sale
        nftListingJson =
          s"""{
          "states": {
            "listed": {
              "id": { "value": "listed" },
              "isFinal": false,
              "metadata": "NFT is listed for sale"
            },
            "sold": {
              "id": { "value": "sold" },
              "isFinal": true,
              "metadata": "NFT has been sold"
            },
            "cancelled": {
              "id": { "value": "cancelled" },
              "isFinal": true,
              "metadata": "Listing was cancelled"
            }
          },
          "initialState": { "value": "listed" },
          "transitions": [
            {
              "from": { "value": "listed" },
              "to": { "value": "sold" },
              "eventName": "purchase",
              "guard": {
                ">=": [
                  { "var": "event.offerPrice" },
                  { "var": "state.price" }
                ]
              },
              "effect": {
                "buyer": { "var": "event.buyer" },
                "salePrice": { "var": "event.offerPrice" },
                "soldAt": { "var": "event.timestamp" },
                "royaltyAmount": {
                  "*": [
                    { "var": "event.offerPrice" },
                    5
                  ]
                },
                "_triggers": [
                  {
                    "targetMachineId": "${royaltyOracleId}",
                    "eventName": "recordRoyalty",
                    "payload": {
                      "nftId": { "var": "state.nftId" },
                      "creator": { "var": "state.creator" },
                      "royaltyAmount": {
                        "*": [
                          { "var": "event.offerPrice" },
                          5
                        ]
                      },
                      "salePrice": { "var": "event.offerPrice" }
                    }
                  }
                ]
              },
              "dependencies": ["${royaltyOracleId}"]
            },
            {
              "from": { "value": "listed" },
              "to": { "value": "cancelled" },
              "eventName": "cancel",
              "guard": {
                "===": [
                  { "var": "event.cancelledBy" },
                  { "var": "state.seller" }
                ]
              },
              "effect": {
                "cancelledAt": { "var": "event.timestamp" }
              },
              "dependencies": []
            }
          ]
        }"""

        _nftListingDef <- IO.fromEither(
          decode[StateMachineDefinition](nftListingJson).left
            .map(err => new RuntimeException(s"Failed to decode NFT listing JSON: $err"))
        )

        // JSON-encoded marketplace state machine that spawns NFT listings
        marketplaceJson =
          s"""{
          "states": {
            "active": {
              "id": { "value": "active" },
              "isFinal": false,
              "metadata": "Marketplace is active"
            }
          },
          "initialState": { "value": "active" },
          "transitions": [
            {
              "from": { "value": "active" },
              "to": { "value": "active" },
              "eventName": "createListing",
              "guard": true,
              "effect": {
                "listingCount": {
                  "+": [
                    {
                      "if": [
                        { "var": "state.listingCount" },
                        { "var": "state.listingCount" },
                        0
                      ]
                    },
                    1
                  ]
                },
                "_spawn": [
                  {
                    "childId": { "var": "event.listingId" },
                    "definition": {
                      "states": {
                        "listed": {
                          "id": { "value": "listed" },
                          "isFinal": false,
                          "metadata": "NFT is listed for sale"
                        },
                        "sold": {
                          "id": { "value": "sold" },
                          "isFinal": true,
                          "metadata": "NFT has been sold"
                        },
                        "cancelled": {
                          "id": { "value": "cancelled" },
                          "isFinal": true,
                          "metadata": "Listing was cancelled"
                        }
                      },
                      "initialState": { "value": "listed" },
                      "transitions": [
                        {
                          "from": { "value": "listed" },
                          "to": { "value": "sold" },
                          "eventName": "purchase",
                          "guard": {
                            ">=": [
                              { "var": "event.offerPrice" },
                              { "var": "state.price" }
                            ]
                          },
                          "effect": {
                            "buyer": { "var": "event.buyer" },
                            "salePrice": { "var": "event.offerPrice" },
                            "soldAt": { "var": "event.timestamp" },
                            "royaltyAmount": {
                              "*": [
                                { "var": "event.offerPrice" },
                                5
                              ]
                            },
                            "_triggers": [
                              {
                                "targetMachineId": "${royaltyOracleId}",
                                "eventName": "recordRoyalty",
                                "payload": {
                                  "nftId": { "var": "state.nftId" },
                                  "creator": { "var": "state.creator" },
                                  "royaltyAmount": {
                                    "*": [
                                      { "var": "event.offerPrice" },
                                      5
                                    ]
                                  },
                                  "salePrice": { "var": "event.offerPrice" }
                                }
                              }
                            ]
                          },
                          "dependencies": ["${royaltyOracleId}"]
                        },
                        {
                          "from": { "value": "listed" },
                          "to": { "value": "cancelled" },
                          "eventName": "cancel",
                          "guard": {
                            "===": [
                              { "var": "event.cancelledBy" },
                              { "var": "state.seller" }
                            ]
                          },
                          "effect": {
                            "cancelledAt": { "var": "event.timestamp" }
                          },
                          "dependencies": []
                        }
                      ]
                    },
                    "initialData": {
                      "nftId": { "var": "event.nftId" },
                      "creator": { "var": "event.creator" },
                      "seller": { "var": "event.seller" },
                      "price": { "var": "event.price" },
                      "metadata": { "var": "event.metadata" },
                      "listedAt": { "var": "event.timestamp" }
                    }
                  }
                ]
              },
              "dependencies": []
            }
          ]
        }"""

        marketplaceDef <- IO.fromEither(
          decode[StateMachineDefinition](marketplaceJson).left
            .map(err => new RuntimeException(s"Failed to decode marketplace JSON: $err"))
        )

        // Create marketplace fiber
        marketplacefiberId <- UUIDGen.randomUUID[IO]
        marketplaceData = MapValue(
          Map(
            "name"         -> StrValue("Constellation NFT Marketplace"),
            "listingCount" -> IntValue(0)
          )
        )

        marketplaceFiber <- FiberBuilder(marketplacefiberId, ordinal, marketplaceDef)
          .withState("active")
          .withDataValue(marketplaceData)
          .ownedBy(registry, Alice)
          .build[IO]

        inState <- DataState(OnChain.genesis, CalculatedState.genesis)
          .withRecord[IO](marketplacefiberId, marketplaceFiber)
          .flatMap(_.withRecord[IO](royaltyOracleId, royaltyOracle))

        // Alice creates an NFT listing (spawns child)
        nftListingId1 <- UUIDGen.randomUUID[IO]
        state1 <- inState.transition(
          marketplacefiberId,
          "createListing",
          MapValue(
            Map(
              "listingId" -> StrValue(nftListingId1.toString),
              "nftId"     -> StrValue("nft-001"),
              "creator"   -> StrValue(registry.addresses(Alice).toString),
              "seller"    -> StrValue(registry.addresses(Alice).toString),
              "price"     -> IntValue(1000),
              "metadata" -> MapValue(
                Map(
                  "name"        -> StrValue("Cosmic Dragon"),
                  "description" -> StrValue("A majestic dragon soaring through the cosmos")
                )
              ),
              "timestamp" -> IntValue(1000)
            )
          ),
          Alice
        )(registry, combiner)

        // Verify marketplace state updated
        listingCount1 = state1.fiberRecord(marketplacefiberId).extractInt("listingCount")

        // Verify NFT listing was spawned
        nftListing1 = state1.fiberRecord(nftListingId1)
        nftPrice1 = nftListing1.extractInt("price")

        // Charlie creates another NFT listing
        nftListingId2 <- UUIDGen.randomUUID[IO]
        state2 <- state1.transition(
          marketplacefiberId,
          "createListing",
          MapValue(
            Map(
              "listingId" -> StrValue(nftListingId2.toString),
              "nftId"     -> StrValue("nft-002"),
              "creator"   -> StrValue(registry.addresses(Charlie).toString),
              "seller"    -> StrValue(registry.addresses(Charlie).toString),
              "price"     -> IntValue(500),
              "metadata" -> MapValue(
                Map(
                  "name"        -> StrValue("Stellar Phoenix"),
                  "description" -> StrValue("A phoenix rising from stellar flames")
                )
              ),
              "timestamp" -> IntValue(2000)
            )
          ),
          Charlie
        )(registry, combiner)

        nftListing2 = state2.fiberRecord(nftListingId2)

        // Bob purchases Alice's NFT (should trigger royalty oracle)
        state3 <- state2.transition(
          nftListingId1,
          "purchase",
          MapValue(
            Map(
              "buyer"      -> StrValue(registry.addresses(Bob).toString),
              "offerPrice" -> IntValue(1200),
              "timestamp"  -> IntValue(3000)
            )
          ),
          Bob
        )(registry, combiner)

        // Verify NFT listing transitioned to sold
        nftListing1AfterSale = state3.fiberRecord(nftListingId1)
        buyer1 = nftListing1AfterSale.extractString("buyer")
        salePrice1 = nftListing1AfterSale.extractInt("salePrice")
        royaltyAmount1 = nftListing1AfterSale.extractInt("royaltyAmount")

        // Verify royalty oracle was triggered and state updated
        oracleAfterSale1 = state3.oracleRecord(royaltyOracleId)
        totalRoyalties1: Option[BigInt] = oracleAfterSale1.flatMap { o =>
          o.stateData.flatMap {
            case MapValue(m) => m.get("totalRoyalties").collect { case IntValue(t) => t }
            case _           => None
          }
        }
        lastRecordedNft1: Option[String] = oracleAfterSale1.flatMap { o =>
          o.stateData.flatMap {
            case MapValue(m) =>
              m.get("lastRecorded").flatMap {
                case MapValue(lr) => lr.get("nftId").collect { case StrValue(nft) => nft }
                case _            => None
              }
            case _ => None
          }
        }
        lastRecordedCreator1: Option[String] = oracleAfterSale1.flatMap { o =>
          o.stateData.flatMap {
            case MapValue(m) =>
              m.get("lastRecorded").flatMap {
                case MapValue(lr) => lr.get("creator").collect { case StrValue(c) => c }
                case _            => None
              }
            case _ => None
          }
        }

        // Bob purchases Charlie's NFT at a lower price
        finalState <- state3.transition(
          nftListingId2,
          "purchase",
          MapValue(
            Map(
              "buyer"      -> StrValue(registry.addresses(Bob).toString),
              "offerPrice" -> IntValue(600),
              "timestamp"  -> IntValue(4000)
            )
          ),
          Bob
        )(registry, combiner)

        // Verify second sale triggered oracle again
        oracleFinal = finalState.oracleRecord(royaltyOracleId)
        totalRoyaltiesFinal: Option[BigInt] = oracleFinal.flatMap { o =>
          o.stateData.flatMap {
            case MapValue(m) => m.get("totalRoyalties").collect { case IntValue(t) => t }
            case _           => None
          }
        }
        lastRecordedNft2: Option[String] = oracleFinal.flatMap { o =>
          o.stateData.flatMap {
            case MapValue(m) =>
              m.get("lastRecorded").flatMap {
                case MapValue(lr) => lr.get("nftId").collect { case StrValue(nft) => nft }
                case _            => None
              }
            case _ => None
          }
        }
        oracleInvocationCount: Option[FiberOrdinal] = oracleFinal.map(_.sequenceNumber)

        // Verify marketplace still active with 2 listings
        listingCountFinal = finalState.fiberRecord(marketplacefiberId).extractInt("listingCount")

        // Verify both NFT listings are in sold state
        nftListing1Final = finalState.fiberRecord(nftListingId1)
        nftListing2Final = finalState.fiberRecord(nftListingId2)

      } yield expect.all(
        // Verify marketplace created first listing
        listingCount1.contains(BigInt(1)),
        // Verify first NFT listing was spawned correctly
        nftListing1.isDefined,
        nftListing1.map(_.currentState).contains(StateId("listed")),
        nftPrice1.contains(BigInt(1000)),
        // Verify second listing was spawned
        nftListing2.isDefined,
        nftListing2.map(_.currentState).contains(StateId("listed")),
        // Verify first NFT sale
        nftListing1AfterSale.isDefined,
        nftListing1AfterSale.map(_.currentState).contains(StateId("sold")),
        buyer1.contains(registry.addresses(Bob).toString),
        salePrice1.contains(BigInt(1200)),
        royaltyAmount1.contains(BigInt(6000)), // 1200 * 5
        // Verify royalty oracle was triggered after first sale
        oracleAfterSale1.isDefined,
        oracleAfterSale1.map(_.sequenceNumber).contains(FiberOrdinal.MinValue.next),
        totalRoyalties1.contains(BigInt(6000)),
        lastRecordedNft1.contains("nft-001"),
        lastRecordedCreator1.contains(registry.addresses(Alice).toString),
        // Verify oracle was triggered again after second sale
        oracleFinal.isDefined,
        oracleInvocationCount.contains(FiberOrdinal.unsafeApply(2L)),
        totalRoyaltiesFinal.contains(BigInt(9000)), // 6000 + 3000
        lastRecordedNft2.contains("nft-002"),
        // Verify marketplace final state
        listingCountFinal.contains(BigInt(2)),
        // Verify both listings are sold
        nftListing1Final.map(_.currentState).contains(StateId("sold")),
        nftListing2Final.map(_.currentState).contains(StateId("sold"))
      )
    }
  }
}
