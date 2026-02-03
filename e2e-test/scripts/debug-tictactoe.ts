/**
 * Debug script for tic-tac-toe test flow.
 * Runs the first few steps manually and queries oracle + SM state between each.
 */
import 'dotenv/config';
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import generateWallet from '../lib/generateWallet.ts';
import sendSignedUpdate from '../lib/sendDataTransaction.ts';
import getMetagraphEnv from '../lib/metagraphEnv.ts';
import { HttpClient } from '@ottochain/sdk';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const examplesDir = path.join(__dirname, '..', 'examples', 'tictactoe');

const env = getMetagraphEnv('local');
const ml0Url = env.node1ML0;
// Send to only ONE DL1 node to test if duplicate processing across nodes causes the bug
const dl1Urls = [env.node1DataL1];

const wallets: Record<string, ReturnType<typeof generateWallet>> = {
  alice: generateWallet(env.globalL0Url, 'alice'),
  bob: generateWallet(env.globalL0Url, 'bob'),
};

const smCid = crypto.randomUUID();
const oracleFiberId = crypto.randomUUID();
const session = { cid: smCid, oracleFiberId };

console.log(`SM CID: ${smCid}`);
console.log(`Oracle CID: ${oracleFiberId}`);

async function loadModule(file: string, context: Record<string, unknown> = {}) {
  const fullPath = path.join(examplesDir, file);
  if (fullPath.endsWith('.json')) {
    return JSON.parse(fs.readFileSync(fullPath, 'utf8'));
  }
  const mod = await import(fullPath);
  const exported = mod.default ?? mod;
  return typeof exported === 'function' ? exported(context) : exported;
}

async function queryEntity(entityPath: string): Promise<unknown> {
  const client = new HttpClient(`${ml0Url}/data-application/v1/${entityPath}`);
  try {
    return await client.get<unknown>('');
  } catch {
    return null;
  }
}

async function waitForSequence(entityPath: string, minSeq: number, label: string) {
  for (let i = 0; i < 30; i++) {
    const data = await queryEntity(entityPath) as Record<string, unknown> | null;
    if (data) {
      const seq = (data.sequenceNumber as number) ?? -1;
      const status = data.status as string;
      if (seq >= minSeq || (minSeq === 0 && status === 'Active')) {
        return data;
      }
    }
    await new Promise(r => setTimeout(r, 5000));
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function send(message: unknown) {
  for (let attempt = 0; attempt < 10; attempt++) {
    try {
      await sendSignedUpdate(message, wallets, dl1Urls);
      return;
    } catch (err) {
      if (attempt >= 9 || !(err as Error).message.includes('400')) throw err;
      console.log(`  Send retry ${attempt + 1}...`);
      await new Promise(r => setTimeout(r, 5000));
    }
  }
}

async function main() {
  const loadContext = { wallets, session };

  // Step 1: Create Oracle
  console.log('\n=== Step 1: Create Oracle ===');
  const oracleDef = await loadModule('script-definition.json');
  const createScriptLib = await import('../lib/script/createScript.ts');
  const oracleMsg = createScriptLib.generator({
    cid: oracleFiberId,
    wallets,
    options: { oracleDefinition: oracleDef },
  });
  console.log('Oracle message (first 300 chars):', JSON.stringify(oracleMsg).substring(0, 300));
  await send(oracleMsg);
  const oracleAfterCreate = await waitForSequence(`oracles/${oracleFiberId}`, 0, 'oracle create');
  console.log('Oracle after create:', JSON.stringify(oracleAfterCreate, null, 2).substring(0, 500));

  // Step 2: Create State Machine
  console.log('\n=== Step 2: Create State Machine ===');
  const smDef = await loadModule('sm-definition.ts', loadContext);
  const smInitData = await loadModule('sm-initial-data.ts', loadContext);
  console.log('SM initial data:', JSON.stringify(smInitData));
  console.log('SM def transitions count:', (smDef as any).transitions?.length);

  const createSmLib = await import('../lib/state-machine/createFiber.ts');
  const smMsg = createSmLib.generator({
    cid: smCid,
    wallets,
    options: { definition: smDef, initialData: smInitData },
  });
  await send(smMsg);
  const smAfterCreate = await waitForSequence(`state-machines/${smCid}`, 0, 'SM create');
  console.log('SM after create - currentState:', (smAfterCreate as any)?.currentState);
  console.log('SM after create - stateData:', JSON.stringify((smAfterCreate as any)?.stateData));

  // Step 3: start_game
  console.log('\n=== Step 3: start_game ===');
  const startGameEvent = await loadModule('event-start-game.ts', { ...loadContext, eventData: undefined });
  console.log('start_game event:', JSON.stringify(startGameEvent));

  const processEventLib = await import('../lib/state-machine/processEvent.ts');
  const startMsg = processEventLib.generator({
    cid: smCid,
    wallets,
    options: { eventData: startGameEvent, targetSequenceNumber: 0 },
  });
  console.log('start_game message:', JSON.stringify(startMsg).substring(0, 300));
  await send(startMsg);

  // Wait for SM sequence 1
  const smAfterStart = await waitForSequence(`state-machines/${smCid}`, 1, 'start_game') as Record<string, unknown>;
  console.log('SM after start_game:');
  console.log('  currentState:', JSON.stringify(smAfterStart.currentState));
  console.log('  lastReceipt:', JSON.stringify(smAfterStart.lastReceipt));
  console.log('  stateData:', JSON.stringify(smAfterStart.stateData));

  // Check oracle state after start_game
  const oracleAfterStart = await queryEntity(`oracles/${oracleFiberId}`) as Record<string, unknown>;
  console.log('Oracle after start_game:');
  console.log('  sequenceNumber:', oracleAfterStart?.sequenceNumber);
  console.log('  stateData:', JSON.stringify((oracleAfterStart as any)?.stateData)?.substring(0, 500));
  console.log('  lastInvocation:', JSON.stringify((oracleAfterStart as any)?.lastInvocation)?.substring(0, 500));

  // Step 4: make_move X:0
  console.log('\n=== Step 4: make_move (X, cell 0) ===');
  const moveEvent = await loadModule('event-move.ts', { ...loadContext, eventData: { player: 'X', cell: 0 } });
  console.log('Raw event from file:', JSON.stringify(moveEvent));

  // Merge step.eventData into payload (same as runner)
  const mergedMoveEvent = {
    ...moveEvent,
    payload: {
      ...(moveEvent as any).payload,
      player: 'X',
      cell: 0,
    },
  };
  console.log('Merged event:', JSON.stringify(mergedMoveEvent));

  const moveMsg = processEventLib.generator({
    cid: smCid,
    wallets,
    options: { eventData: mergedMoveEvent, targetSequenceNumber: 1 },
  });
  console.log('make_move message:', JSON.stringify(moveMsg));
  await send(moveMsg);

  // Wait for SM sequence 2
  const smAfterMove = await waitForSequence(`state-machines/${smCid}`, 2, 'make_move') as Record<string, unknown>;
  console.log('SM after make_move:');
  console.log('  currentState:', JSON.stringify(smAfterMove.currentState));
  console.log('  lastReceipt:', JSON.stringify(smAfterMove.lastReceipt));
  console.log('  stateData:', JSON.stringify(smAfterMove.stateData));

  // Check oracle state after make_move
  const oracleAfterMove = await queryEntity(`oracles/${oracleFiberId}`) as Record<string, unknown>;
  console.log('Oracle after make_move:');
  console.log('  sequenceNumber:', oracleAfterMove?.sequenceNumber);
  console.log('  stateData:', JSON.stringify((oracleAfterMove as any)?.stateData));
  console.log('  lastInvocation:', JSON.stringify((oracleAfterMove as any)?.lastInvocation));
}

main().catch((err) => {
  console.error('Debug script failed:', err.message);
  process.exit(1);
});
