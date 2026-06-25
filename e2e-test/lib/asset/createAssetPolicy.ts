/**
 * Asset-policy publish (asset-model §7). Plain-JSON message: the chain JAR decodes the
 * `{ CreateAssetPolicy: {...} }` variant (asset model on main), and `batchSign` signs any object —
 * so no SDK asset builder/type dependency is needed (the published SDK doesn't yet export them).
 * The policy body (name/version/behavior/supply/morphisms/stateShape) is supplied by the step file.
 *
 * Signed-message discipline (CLAUDE.md rule #1): omit optional fields rather than setting them null —
 * `dropNulls` in the signing path strips nulls so the client canonical matches the chain's.
 */
export interface CreateAssetPolicyOptions {
  /** The full CreateAssetPolicy body, already shaped per the on-wire schema. */
  policy: Record<string, unknown>;
}

export const generator = ({
  options,
}: {
  cid?: string;
  wallets?: unknown;
  options: CreateAssetPolicyOptions;
}): { CreateAssetPolicy: Record<string, unknown> } => ({ CreateAssetPolicy: options.policy });
