import { HttpClient, batchSign, dropNulls } from '@ottochain/sdk';
import type { KeyPair } from '@ottochain/sdk';

/**
 * Sign a message with multiple wallets and POST to the DL1 /data endpoint.
 *
 * Uses the SDK's batchSign (RFC 8785 canonicalize → DataUpdate sign)
 * to produce a Signed<T> with one proof per wallet, then submits to all
 * DL1 nodes.
 *
 * IMPORTANT: null object fields are dropped from the message BEFORE signing
 * (and the null-free value is what gets submitted). Since metakit 1.8.0 the
 * node's `serializeUpdate` drops null object fields before canonicalizing
 * (JsonBinaryCodec.dropNulls), so signatures must be computed over the same
 * null-free canonical form or the DL1 rejects every update with HTTP 400
 * (InvalidSignature). Pinned by the Scala-side
 * E2eSignedPayloadCompatSuite (modules/shared-data).
 */
export default async function sendSignedUpdate(
  message: unknown,
  wallets: Record<string, KeyPair>,
  dl1Urls: string[]
): Promise<{ hash: string }[]> {
  const privateKeys = Object.values(wallets).map((w) => w.privateKey);

  // Match the node's canonical form: metakit drops null object fields
  // before hashing/verifying, so we must sign (and send) the null-free value.
  const cleaned = dropNulls(message);

  const signed = await batchSign(cleaned, privateKeys, { isDataUpdate: true });

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
