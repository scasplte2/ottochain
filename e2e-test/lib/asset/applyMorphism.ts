/**
 * Apply a typed morphism (Transfer/Burn/Fractionalize/Stake/…) to an asset instance (asset-model §7).
 * Plain-JSON message (see createAssetPolicy.ts). Sequenced by `(assetId, targetSequenceNumber)`; the
 * runner fills `targetSequenceNumber` from the asset's current committed sequence before sending.
 * The morphism body — `{ assetId, kind, recipient?, shardIds?, … }` — is supplied by the step file.
 */
export interface ApplyMorphismOptions {
  /** The morphism body sans sequence (assetId, kind, and per-kind directives). */
  morphism: Record<string, unknown>;
  /** Current committed sequence number of the asset; the next morphism targets it. */
  targetSequenceNumber?: number;
}

export const generator = ({
  options,
}: {
  cid?: string;
  wallets?: unknown;
  options: ApplyMorphismOptions;
}): { ApplyMorphism: Record<string, unknown> } => ({
  ApplyMorphism: {
    ...options.morphism,
    targetSequenceNumber: options.targetSequenceNumber ?? 0,
  },
});
