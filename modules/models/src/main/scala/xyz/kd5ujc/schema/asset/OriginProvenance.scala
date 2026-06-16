package xyz.kd5ujc.schema.asset

import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.CodecConfiguration._

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

/**
 * Cross-chain provenance for a bridged-in (wrapped) asset — the IBC denom-trace analogue. Carried as an
 * `Option[OriginProvenance]` forward-ref on [[xyz.kd5ujc.schema.Records.AssetRecord]] (D2, core-first):
 * `None` for natively-issued assets. See docs/proposals/asset-interop-functor.md §6.3.
 *
 * This is the minimal-but-stable Phase 2 shape. The full interop functor `F : Ext -> Otto` (canonical
 * `policyId = derive(originChainId, originAssetRef)`, the forward-prepend/backward-trim hop rule, and the
 * structured `Hop` path) is Phase 6; `fullPath` is modelled as `List[String]` (each hop rendered as a
 * stable string) so the later structured `Hop` upgrade is additive.
 *
 *   - `originChainId`   — the source chain id, e.g. `"eip155:1"`, `"cardano-mainnet"`, `"cosmoshub-4"`.
 *   - `originAssetRef`  — the source asset reference: `contract+tokenId` / `(PolicyID,AssetName)` / denom-base.
 *   - `fullPath`        — the ordered denom-trace path (prepend forward, trim backward).
 *   - `attestationHash` — commitment to the inbound lock/burn attestation.
 */
@derive(customizableEncoder, customizableDecoder)
final case class OriginProvenance(
  originChainId:   String,
  originAssetRef:  String,
  fullPath:        List[String],
  attestationHash: Hash
)
