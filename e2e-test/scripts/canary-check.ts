/**
 * Pre-flight canary check for E2E tests.
 *
 * Submits a minimal state machine fiber to ML0 via DL1 and waits for it
 * to appear in ML0 state. If this fails, consensus is broken and running
 * the full test suite would waste time.
 *
 * Usage: npx tsx scripts/canary-check.ts [--maxRetries 30] [--retryDelay 5]
 */
import 'dotenv/config';
import crypto from 'crypto';

import generateWallet from '../lib/generateWallet.ts';
import sendSignedUpdate from '../lib/sendDataTransaction.ts';
import getMetagraphEnv from '../lib/metagraphEnv.ts';
import { HttpClient } from '@ottochain/sdk';
import type { OttochainMessage, CreateStateMachine } from '@ottochain/sdk';

// ---------------------------------------------------------------------------
// CLI args
// ---------------------------------------------------------------------------

function parseArgs() {
  const args = process.argv.slice(2);
  const opts: Record<string, string> = {
    target: 'local',
    maxRetries: '30',
    retryDelay: '5',
  };

  for (let i = 0; i < args.length; i++) {
    if (args[i].startsWith('--') && i + 1 < args.length) {
      opts[args[i].slice(2)] = args[i + 1];
      i++;
    }
  }

  return opts;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  const opts = parseArgs();
  const maxRetries = parseInt(opts.maxRetries);
  const retryDelayMs = parseInt(opts.retryDelay) * 1000;

  console.log('\x1b[36m=== Pre-flight Canary Check ===\x1b[0m');

  const env = getMetagraphEnv(opts.target);
  const wallet = generateWallet(env.globalL0Url, 'canary');
  const wallets = { canary: wallet };

  const canaryId = `canary-${crypto.randomUUID()}`;

  // Minimal state machine definition — single state, no transitions
  const definition = {
    initialState: 'alive',
    states: {
      alive: { transitions: {} },
    },
  };

  const message: OttochainMessage = {
    CreateStateMachine: {
      fiberId: canaryId,
      definition,
      initialData: {},
      parentFiberId: null,
    } as CreateStateMachine,
  };

  const dl1Urls = [env.node1DataL1, env.node2DataL1, env.node3DataL1];
  const ml0Url = env.node1ML0;

  // Send canary transaction to DL1
  console.log(`  Submitting canary fiber: ${canaryId.slice(0, 16)}…`);

  try {
    await sendSignedUpdate(message, wallets, dl1Urls);
  } catch (err) {
    console.error(`\x1b[31m  ✗ Failed to submit canary transaction\x1b[0m`);
    console.error(`  ${(err as Error).message}`);
    process.exit(1);
  }

  // Poll ML0 for confirmation
  const entityUrl = `${ml0Url}/data-application/v1/state-machines/${canaryId}`;
  const client = new HttpClient(entityUrl);

  process.stdout.write('  Waiting for ML0 confirmation');

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const data = (await client.get<unknown>('')) as {
        status?: string;
      } | null;

      if (data?.status === 'Active') {
        console.log(` ✓ (${attempt} attempts)`);
        console.log('\x1b[32m  ✓ Canary confirmed — ML0 consensus is healthy\x1b[0m\n');
        process.exit(0);
      }
    } catch {
      // Entity doesn't exist yet — keep polling
    }

    process.stdout.write('.');
    if (attempt < maxRetries) {
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
    }
  }

  console.log(' ✗');
  console.error(
    `\x1b[31m  ✗ Canary transaction not confirmed after ${maxRetries} attempts (${(maxRetries * retryDelayMs) / 1000}s)\x1b[0m`
  );
  console.error('  ML0 consensus may be stalled. Aborting test suite.');
  process.exit(1);
}

main().catch((err) => {
  console.error('\x1b[31mCanary check failed:\x1b[0m', err.message);
  process.exit(1);
});
