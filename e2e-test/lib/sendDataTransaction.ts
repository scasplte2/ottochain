import { HttpClient, batchSign, dropNulls } from '@ottochain/sdk';
import type { KeyPair } from '@ottochain/sdk';

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

  const errorMessages = responses
    .map((r) => {
      if (r.status === 'rejected') {
        const err = r.reason as Error & { response?: string };
        if (err.response) {
          console.log(`\x1b[33m[sendDataTransaction]\x1b[31m Error response body:\x1b[0m ${err.response}`);
        }
        return err.message;
      }
      return '';
    })
    .join('; ');
  throw new Error(`All requests failed. Errors: ${errorMessages}`);
}
