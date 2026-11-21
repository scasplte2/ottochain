import { getDagAddressFromPublicKey } from '@constellation-network/metagraph-sdk';
import { OttoMetagraphClient, verify } from '@ottochain/sdk';
import type { Signed } from '@constellation-network/metagraph-sdk';
import type { StateMachineFiberRecord } from '@ottochain/sdk/core';
import { isDeepStrictEqual } from 'node:util';

type LedgerBlock = { value: { dataTransactions: Signed<unknown>[][] } };
export function hasSignedUpdate(blocks: number[][], expected: unknown, addresses: string[]) {
  return blocks.some(bytes => {
    const block: LedgerBlock = JSON.parse(Buffer.from(bytes).toString('utf8'));
    return block.value.dataTransactions.flat().some(update => {
      if (!isDeepStrictEqual(update.value, expected)) return false;
      const result = verify(update, true);
      return result.isValid && result.invalidProofs.length === 0 &&
        isDeepStrictEqual(result.validProofs.map(p => getDagAddressFromPublicKey(p.id)).sort(), [...addresses].sort());
    });
  });
}

export async function waitForRefusedEvent(base: string, before: StateMachineFiberRecord, message: unknown,
  eventName: string, addresses: string[], retries: number, delayMs: number) {
  const client = new OttoMetagraphClient({ ml0Url: base });
  for (let i = 0; i < retries; i++) {
    const after = await client.getStateMachine(before.fiberId);
    const receipt = after?.lastReceipt;
    if (after && receipt && receipt.ordinal > before.latestUpdateOrdinal && receipt.eventName === eventName) {
      const res = await fetch(base + '/snapshots/' + receipt.ordinal, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(10000) });
      if (res.ok) {
        const ledger = await res.json();
        if (ledger.value.ordinal === receipt.ordinal && hasSignedUpdate(ledger.value.dataApplication.blocks, message, addresses)) {
          if (receipt.success !== false || !receipt.errorMessage || after.sequenceNumber !== before.sequenceNumber ||
              !isDeepStrictEqual(after.stateData, before.stateData)) throw new Error('Refusal or unchanged business state missing');
          return receipt;
        }
      }
    }
    await new Promise(r => setTimeout(r, delayMs));
  }
  throw new Error('No fresh failure receipt correlated with the signed request in its snapshot');
}
