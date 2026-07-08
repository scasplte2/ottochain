/**
 * Mint an asset INSTANCE against a resolved policy version (asset-model §7). Plain-JSON message (see
 * createAssetPolicy.ts for why no SDK builder is needed). The mint body — `{ assetId, policyRef,
 * holder, amount, expiresAt? }` — is supplied by the step file; a `.ts` body file receives the
 * runner `context` so `holder` can be resolved from `context.wallets[...]` (e.g. mint RVD to carol).
 */
export interface MintAssetOptions {
  /** The full MintAsset body, already shaped per the on-wire schema. */
  mint: Record<string, unknown>;
}

export const generator = ({
  options,
}: {
  cid?: string;
  wallets?: unknown;
  options: MintAssetOptions;
}): { MintAsset: Record<string, unknown> } => ({ MintAsset: options.mint });
