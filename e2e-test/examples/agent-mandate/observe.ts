import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { capture } from './verify-mandate.ts';

const [fiber, out, primary = 'http://127.0.0.1:9200', secondary] = process.argv.slice(2);
if (!fiber || !out) throw new Error('Usage: observe FIBER NEW_DIRECTORY [ML0 [SECOND_ML0]]');
mkdirSync(out, { recursive: false });
let previous = -1;
let seen = 0;
const deadline = Date.now() + 30 * 60_000;
while (Date.now() < deadline) {
  const read = await fetch(primary + '/data-application/v1/state-machines/' + fiber);
  if (read.ok) {
    const latest = await read.json();
    if (latest && latest.latestUpdateOrdinal > previous) {
      const { bundle, pin, result, limitation } = await capture(fiber, primary, secondary);
      const record = bundle.proof.record as { latestUpdateOrdinal: number; lastReceipt?: { success: boolean } };
      if (record.latestUpdateOrdinal > previous) {
        previous = record.latestUpdateOrdinal;
        const label = String(++seen).padStart(2, '0') + '-' + result.state + '-' + result.data.remainingCents;
        for (const [suffix, data] of [['bundle', bundle], ['pin', pin]] as const)
          writeFileSync(path.join(out, label + '.' + suffix + '.json'), JSON.stringify(data) + '\n', { flag: 'wx' });
        console.log(label, JSON.stringify(result), limitation ?? '');
        if (result.state === 'revoked' && record.lastReceipt?.success === false) process.exit(0);
      }
    }
  } else if (read.status !== 404) { throw new Error('ML0 read HTTP ' + read.status); }
  await new Promise(r => setTimeout(r, 350));
}
throw new Error('Observation timed out before post-revocation refusal');
