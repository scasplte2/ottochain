/**
 * ApplyMorphism body — FRACTIONALIZE the RVD instance carol holds in her WALLET into three shards.
 *
 * `AssetCombiner.applyFractionalize` requires the source behavior to be splittable (S=1; rvd behavior
 * 28 = T|S|C has it), creates one shard `AssetRecord` per `shardIds` entry with `combinable:=false`,
 * partitions `amount` across them (remainder to the first), and CONSUMES the source. R1: the signer
 * must hold the source, so this is signed by `carol`. ApplyMorphism shape (quoted from
 * modules/models/.../Updates.scala): `final case class ApplyMorphism(assetId: UUID, kind: MorphismKind,
 * targetSequenceNumber: FiberOrdinal, recipient: Option[AssetHolder] = None, ..., shardIds:
 * Option[List[UUID]] = None, ...)` — FRACTIONALIZE uses `shardIds` (the runner fills targetSequenceNumber).
 *
 * ── DEFERRED (not wired as a live step in example.ts) ──
 * Fractionalize is a CONSUMING morphism: it REMOVES the source record (`RVD_FRAC_ID`) and writes new
 * shard ids. The runner confirms an `applyMorphism` step by polling the SOURCE asset's `state-proof` for
 * a sequence ADVANCE (runner.ts ~L699-713) — a removed source can never satisfy that, so the step would
 * time out. This body + ids are shipped so the demo activates the instant the runner gains
 * consuming-morphism confirmation (e.g. confirm via shard existence). See example.ts P12.
 */
import { RVD_FRAC_ID, RVD_FRAC_A_ID, RVD_FRAC_B_ID, RVD_FRAC_C_ID } from './ids.ts';

export default (): Record<string, unknown> => ({
  assetId: RVD_FRAC_ID,
  kind: 'FRACTIONALIZE',
  shardIds: [RVD_FRAC_A_ID, RVD_FRAC_B_ID, RVD_FRAC_C_ID],
});
