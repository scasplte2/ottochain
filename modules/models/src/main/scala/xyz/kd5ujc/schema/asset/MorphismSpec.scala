package xyz.kd5ujc.schema.asset

import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicExpression

import xyz.kd5ujc.schema.CodecConfiguration._
import xyz.kd5ujc.schema.registry.RegistryName

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * The per-morphism specification on an asset policy version — what is *permitted* for one [[MorphismKind]],
 * layered on top of the morphism's *structural* domain guard (which is fixed in-protocol; see
 * docs/proposals/asset-model.md §4/§8). The Scala layer enforces what is structurally possible; this spec
 * plus the optional JSON-Logic `guard` enforce what is permitted by policy in context.
 *
 * Signing-canonical invariant #1 (docs/signing-canonical-and-validation.md): `visibility` is REQUIRED (no
 * default) so a client must always send it; the three allowlist/guard refinements are `Option` (omit-safe,
 * `None` -> `null` -> dropped by `dropNulls`). No non-`Option` field carries a default.
 *
 * @param visibility      access-control level for this morphism (REQUIRED).
 * @param allowedPolicies counter-party policy allowlist (e.g. for `Compose`/`Wrap`); `None` = any policy.
 * @param allowedTypes    counter-party behavior-bitmask allowlist (the packed [[TokenBehavior.bits]] Int);
 *                        `None` = any behavior.
 * @param guard           optional extra JSON-Logic predicate, evaluated by the metered evaluator at
 *                        morphism time; `None` = no extra guard beyond visibility + structural domain.
 */
@derive(customizableEncoder, customizableDecoder)
final case class MorphismSpec(
  visibility:      MorphismVisibility,
  allowedPolicies: Option[Set[RegistryName]],
  allowedTypes:    Option[Set[Int]],
  guard:           Option[JsonLogicExpression]
)
