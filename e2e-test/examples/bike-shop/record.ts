/**
 * Flight recorder for the film: walks EVERY ML0 snapshot ordinal (no gaps — `latestLogs` is cleared each
 * snapshot, so polling "latest" could skip one) and appends one JSON line per ordinal with:
 *   - the consensus-signed `calculatedStateProof` and the snapshot's signer ids,
 *   - the decoded OnChain (`latestLogs` receipts/refusals, touched commits, roots breadcrumb),
 *   - every signed data update in the snapshot's blocks (message + verified signer addresses),
 *   - a checkpoint sample of the bike-shop fibers/assets (best effort; state is read at "now").
 *
 * Usage: node record.ts <out.jsonl> [ml0=http://127.0.0.1:9200] [fromOrdinal]
 * Stops on SIGTERM/SIGINT (send it to the recorder's exact PID) after flushing.
 */
import { appendFileSync } from 'node:fs';
import { getDagAddressFromPublicKey } from '@constellation-network/metagraph-sdk';
import { verify } from '@ottochain/sdk';
import type { Signed } from '@constellation-network/metagraph-sdk';

const [out, ml0 = 'http://127.0.0.1:9200', fromArg] = process.argv.slice(2);
if (!out) throw new Error('usage: record.ts <out.jsonl> [ml0] [fromOrdinal]');
const PREFIX = 'b1ce0000-';
let stop = false;
process.on('SIGTERM', () => (stop = true));
process.on('SIGINT', () => (stop = true));

const getJson = async (path: string) => {
  const r = await fetch(ml0 + path, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(10000) });
  if (!r.ok) throw new Error(`${path} → ${r.status}`);
  return r.json();
};
const bytesJson = (b: number[]) => JSON.parse(Buffer.from(b).toString('utf8'));
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

async function sampleCheckpoint() {
  try {
    const cp = await getJson('/data-application/v1/checkpoint');
    const st = cp.state ?? cp;
    const pick = (m: Record<string, unknown> | undefined) =>
      Object.fromEntries(Object.entries(m ?? {}).filter(([k]) => k.startsWith(PREFIX) || true).slice(0, 64));
    return { ordinal: cp.ordinal, stateMachines: pick(st.stateMachines), assets: Object.fromEntries(Object.entries(st.assets ?? {}).filter(([k]) => k.startsWith(PREFIX))) };
  } catch (e) {
    return { error: String(e) };
  }
}

let next = fromArg ? Number(fromArg) : (await getJson('/snapshots/latest')).value.ordinal;
console.log(`recording from ordinal ${next} → ${out}`);
while (!stop) {
  let latest: number;
  try { latest = (await getJson('/snapshots/latest')).value.ordinal; } catch { await sleep(1000); continue; }
  while (next <= latest && !stop) {
    try {
      const snap = await getJson('/snapshots/' + next);
      const da = snap.value.dataApplication;
      const updates = (da?.blocks ?? []).flatMap((b: number[]) => {
        const block = bytesJson(b);
        return (block.value?.dataTransactions ?? []).flat().map((u: Signed<unknown>) => {
          const v = verify(u, true);
          return { message: u.value, signers: v.validProofs.map(p => getDagAddressFromPublicKey(p.id)), valid: v.isValid };
        });
      });
      const line = {
        ordinal: next, capturedAt: new Date().toISOString(),
        calculatedStateProof: da?.calculatedStateProof ?? null,
        snapshotSigners: (snap.proofs ?? []).map((p: { id: string }) => p.id),
        onChain: da?.onChainState ? bytesJson(da.onChainState) : null,
        updates,
        checkpoint: updates.length || (da?.onChainState && Object.keys(bytesJson(da.onChainState).inner?.latestLogs ?? {}).length) ? await sampleCheckpoint() : undefined,
      };
      appendFileSync(out, JSON.stringify(line) + '\n');
      next++;
    } catch {
      await sleep(500);
    }
  }
  await sleep(400);
}
console.log(`stopped at ordinal ${next - 1}`);
