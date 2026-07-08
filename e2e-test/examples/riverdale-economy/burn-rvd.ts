/**
 * ApplyMorphism body — BURN the RVD instance frank holds in his WALLET.
 *
 * `AssetCombiner.applyBurn` evaluates the policy's `supply.burnPolicy` (None → "burning is closed" →
 * reject; rvd-policy.json declares `burnPolicy: {"==":[1,1]}`), then REMOVES the record + commit
 * (terminal). R1: the signer must hold the source, so this is signed by `frank`. ApplyMorphism shape
 * (quoted from modules/models/.../Updates.scala): `final case class ApplyMorphism(assetId: UUID, kind:
 * MorphismKind, targetSequenceNumber: FiberOrdinal, ...)` — BURN needs only `assetId` + `kind` (no
 * recipient/shardIds; the runner fills targetSequenceNumber).
 *
 * ── DEFERRED (not wired as a live step in example.ts) ──
 * Burn is TERMINAL: it removes the asset record. The runner confirms `applyMorphism` by polling the
 * source's `state-proof` for a sequence ADVANCE — a removed record can never satisfy that (and absence
 * is not assertable via `assertAsset`). This body + id are shipped for when the runner gains
 * consuming/terminal-morphism confirmation. See example.ts P12.
 */
import { RVD_BURN_ID } from './ids.ts';

export default (): Record<string, unknown> => ({
  assetId: RVD_BURN_ID,
  kind: 'BURN',
});
