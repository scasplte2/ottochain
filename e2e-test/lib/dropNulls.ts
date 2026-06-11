/**
 * Recursively drop null/undefined OBJECT fields (array entries preserved),
 * matching metakit JsonBinaryCodec.dropNulls — the content-hash rule the
 * chain applies before RFC 8785 canonicalization. Local copy: the vendored
 * @ottochain/sdk does not yet export dropNulls (ships with sdk PR #197).
 */
export function dropNulls(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(dropNulls);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .filter(([, v]) => v !== null && v !== undefined)
        .map(([k, v]) => [k, dropNulls(v)])
    );
  }
  return value;
}
