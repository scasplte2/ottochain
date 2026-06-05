import { HttpClient, batchSign } from '@ottochain/sdk';
import type { KeyPair } from '@ottochain/sdk';

/**
 * Recursively drop null-valued object fields (array elements — including nulls — are preserved).
 * Mirrors the chain's metakit `JsonBinaryCodec.dropNulls`, so the client signs the same null-free
 * canonical the chain re-derives when verifying a signature. Implemented locally rather than imported
 * from `@ottochain/sdk` so the e2e does not depend on the published SDK build exporting it.
 */
function dropNulls<T>(value: T): T {
  if (value === null || value === undefined) return value;
  if (Array.isArray(value)) return value.map(dropNulls) as unknown as T;
  if (typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (v !== null) out[k] = dropNulls(v);
    }
    return out as T;
  }
  return value;
}

/**
 * Sign a message with multiple wallets and POST to the DL1 /data endpoint.
 *
 * Uses the SDK's batchSign (RFC 8785 canonicalize → DataUpdate sign)
 * to produce a Signed<T> with one proof per wallet, then submits to all
 * DL1 nodes.
 */
export default async function sendSignedUpdate(
  message: unknown,
  wallets: Record<string, KeyPair>,
  dl1Urls: string[]
): Promise<{ hash: string }[]> {
  const privateKeys = Object.values(wallets).map((w) => w.privateKey);

  // Drop null fields before signing. metakit (rc.9) drops nulls when building the canonical
  // bytes the chain signs/verifies, so the client must sign the same null-free form. Otherwise a
  // message carrying a null (e.g. a state's `metadata: null` in a state-machine definition) is
  // signed over a different canonical than the chain re-derives, and verification fails (HTTP 400) —
  // which is why state-machine creates failed while null-free scripts passed.
  const signed = await batchSign(dropNulls(message), privateKeys, { isDataUpdate: true });

  console.log(
    `\x1b[33m[sendDataTransaction]\x1b[36m Sending to DL1:\x1b[0m ${JSON.stringify(signed).substring(0, 200)}...`
  );

  const responses = await Promise.allSettled(
    dl1Urls.map(async (url) => {
      const client = new HttpClient(url);
      const response = await client.post<{ hash: string }>('/data', signed);
      console.log(
        `\x1b[33m[sendDataTransaction]\x1b[32m Response from ${url}:\x1b[0m ${JSON.stringify(response)}`
      );
      return response;
    })
  );

  const fulfilled = responses.filter(
    (r): r is PromiseFulfilledResult<{ hash: string }> =>
      r.status === 'fulfilled'
  );

  if (fulfilled.length > 0) {
    console.log(
      `\x1b[33m[sendDataTransaction]\x1b[32m Successful responses from ${fulfilled.length} nodes\x1b[0m`
    );
    return fulfilled.map((r) => r.value);
  }

  // Surface the dl1's actual rejection: HttpClient throws a NetworkError carrying `statusCode` and
  // `response` (the raw response body). Log status + body per node (even when empty) and fold them into
  // the thrown error so the runner's "Failed flows" output shows WHY, not just "HTTP 400: Bad Request".
  const errorMessages = responses
    .map((r, i) => {
      if (r.status === 'rejected') {
        const err = (r.reason ?? {}) as { message?: string; statusCode?: number; response?: unknown };
        const url = dl1Urls[i];
        const status = err.statusCode ?? '?';
        const body =
          err.response === undefined || err.response === null || err.response === ''
            ? '(empty body)'
            : typeof err.response === 'string'
              ? err.response
              : JSON.stringify(err.response);
        console.log(
          `\x1b[33m[sendDataTransaction]\x1b[31m ${url} -> HTTP ${status}:\x1b[0m ${body} ${err.message ? `(${err.message})` : ''}`
        );
        return `${url} HTTP ${status}: ${body}`;
      }
      return '';
    })
    .filter(Boolean)
    .join(' | ');
  throw new Error(`All requests failed: ${errorMessages}`);
}
