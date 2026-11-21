import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { generateKeyPair } from '@ottochain/sdk';
import initial from './initial.ts';
import { generator as create } from '../../lib/state-machine/createFiber.ts';
import send from '../../lib/sendDataTransaction.ts';
import { capture } from './verify-mandate.ts';

// Operator setup is a separate process. Only the agent test identity enters the tool harness.
const [output, supplier = 'supplier-c'] = process.argv.slice(2);
if (!['supplier-a', 'supplier-c'].includes(supplier)) throw new Error('Choose supplier-a or supplier-c for this demonstration');
if (!output || existsSync(output)) throw new Error('Supply a new transcript path');
const wallets = Object.fromEntries(['owner','agent','cosigner','stranger'].map(role => [role, generateKeyPair()]));
const cid = randomUUID();
const initialData = initial({ wallets, session: { cid } });
const definition = JSON.parse(readFileSync(new URL('./definition.json', import.meta.url), 'utf8'));
const urls = [9400, 9410, 9420].map(p => 'http://127.0.0.1:' + p);
await send(create({ cid, options: { initialData, definition } }), { owner: wallets.owner }, urls);
const base = 'http://127.0.0.1:9200';
for (let i = 0; i < 100; i++) {
  const response = await fetch(base + '/data-application/v1/state-machines/' + cid);
  if (response.ok && (await response.json())?.fiberId === cid) break;
  await new Promise(r => setTimeout(r, 2000));
}
const { result } = await capture(cid, base);
if (result.data.owner !== wallets.owner.address || result.state !== 'active') throw new Error('Owner creation unconfirmed');
const script = fileURLToPath(new URL('./model.ts', import.meta.url));
const child = spawn(process.execPath, [...process.execArgv, script, cid, '1', output, supplier], {
  stdio: 'inherit', env: { PATH: process.env.PATH, MANDATE_MODEL: process.env.MANDATE_MODEL, MANDATE_AGENT_TEST_KEY: wallets.agent.privateKey },
});
const code = await new Promise<number | null>((resolve, reject) => { child.once('error', reject); child.once('exit', resolve); });
if (code !== 0) throw new Error('Model harness exit ' + code);
