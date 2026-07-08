/**
 * ApplyMorphism body — STAKE the RVD instance dave holds in his WALLET.
 *
 * STAKE is a NON-consuming, in-place morphism: `AssetCombiner.applyStake` sets the source behavior's
 * `expirable := true` (moves DOWN the lattice), bumps the asset's sequenceNumber, and keeps the SAME
 * record/holder. It needs NO behavior bit (structuralOk has no Stake gate) — only the policy's
 * `morphisms` map must declare `STAKE` (rvd-policy.json does, visibility PUBLIC). R1 holder-ownership:
 * the signer MUST equal the wallet holder, so this step is signed by `dave` (who holds RVD_STAKE_ID).
 *
 * Because STAKE bumps the seq and the record survives, the runner's `applyMorphism` confirmation
 * (poll `assets/{assetId}/state-proof` for a sequence ADVANCE) is satisfiable — unlike the CONSUMING
 * morphisms (Burn/Fractionalize), which remove the source. `targetSequenceNumber` is filled by the runner.
 */
import { RVD_STAKE_ID } from './ids.ts';

export default (): Record<string, unknown> => ({
  assetId: RVD_STAKE_ID,
  kind: 'STAKE',
});
