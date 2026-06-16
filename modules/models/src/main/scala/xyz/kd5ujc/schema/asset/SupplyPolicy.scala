package xyz.kd5ujc.schema.asset

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicExpression

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * Supply authority for an asset policy version — orthogonal to instance [[TokenBehavior]]
 * (docs/proposals/asset-model.md §3). Mint/burn authority is a *policy* decision set at policy-creation
 * time, NOT a behavioral flag on instances; decoupling keeps supply-level decisions out of instance-level
 * type checks. Total supply is DERIVED from the record set, never stored here.
 *
 * Every field is `Option` (signing-canonical invariant #1: omit-safe, `None` -> `null` -> dropped).
 *
 * @param maxSupply  hard cap on derived total supply; `None` = uncapped.
 * @param mintPolicy JSON-Logic predicate gating new supply; `None` = minting closed after genesis.
 * @param burnPolicy JSON-Logic predicate gating destruction; `None` = no burning.
 * @param decimals   fractional precision for splittable fungibles; `None` / 0 for NFTs (interop normalization).
 */
@derive(customizableEncoder, customizableDecoder)
final case class SupplyPolicy(
  maxSupply:  Option[Long],
  mintPolicy: Option[JsonLogicExpression],
  burnPolicy: Option[JsonLogicExpression],
  decimals:   Option[Int]
)
