/**
 * MintAsset body — mint 1000 RVD (the fungible currency) into carol's WALLET.
 *
 * Holder is `{ Wallet: { address } }` (AssetHolder.Wallet wire form). carol's DAG address is resolved
 * dynamically from the runner `context.wallets.carol.address`.
 *
 * Signed-message discipline (CLAUDE.md #1): `expiresAt`/`provenance`/`witness` omitted (Option).
 */
import { RVD_ASSET_ID } from './ids.ts';

export default (context: {
  wallets: Record<string, { address: string }>;
}): Record<string, unknown> => ({
  assetId: RVD_ASSET_ID,
  policyRef: { name: 'rvd.asset', version: { Exact: { version: '1.0.0' } } },
  holder: { Wallet: { address: context.wallets.carol.address } },
  amount: 1000,
});
