import { CurrencyL1Client, createCurrencyTransaction, getTransactionReference } from '@ottochain/sdk';
import type { TransactionReference } from '@ottochain/sdk';

/**
 * Background currency-transaction pressure for the e2e cluster.
 *
 * The ML0 currency-snapshot consensus fires EventTrigger when there are pending events in its mempool
 * and falls back to a slow periodic TimeTrigger (~40s) when idle. A data-only metagraph receiving
 * sparse fiber updates sits on TimeTrigger, so every confirmation waits ~40s for the next snapshot
 * (and the GL0-finalized read trails that). Dripping a steady stream of currency token transactions
 * into the metagraph's CL1 keeps the ML0 mempool non-empty, so EventTrigger fires continuously
 * (~5s cooldown) — snapshots, and therefore confirmations, become ~8x faster and scale with pressure.
 *
 * Uses a dedicated genesis-funded fixture key (matches tessellation's tx-sender configs) that no test
 * flow ever signs with, so it cannot interfere with test wallets' nonces/balances.
 */
const SENDER = 'DAG1tE25RsKXpyHFByJwjgeG3CDLRHayNgoaENJQ';
const PRIVATE_KEY = 'f3706c6fbae826d5f3d7e25b490c0f123d00e680c33efa8d057facd1bcf997b2';
const RECIPIENT = 'DAG0eQr94qUQSUhmYGNXt6CoBKWu5K6htvRMGC6M';

export class CurrencyTxPressure {
  private running = false;
  private loop?: Promise<void>;
  /** True once the drip is actually running (CL1 reachable + first reference fetched). */
  active = false;
  private sent = 0;

  /** Start the steady drip. Never throws — if CL1 isn't reachable it logs and no-ops (the lane just
   *  stays TimeTrigger-paced). */
  async start(cl1Url: string, intervalMs = 2000): Promise<void> {
    const client = new CurrencyL1Client({ l1Url: cl1Url });

    // CL1 joins after ML0/DL1, so wait for it to answer last-reference lookups before dripping.
    let lastRef: TransactionReference | undefined;
    for (let i = 0; i < 30 && !lastRef; i++) {
      try {
        lastRef = await client.getLastReference(SENDER);
      } catch {
        await new Promise((r) => setTimeout(r, 2000));
      }
    }
    if (!lastRef) {
      console.log(
        '\x1b[33m[tx-pressure]\x1b[0m CL1 not reachable — skipping (snapshots stay TimeTrigger-paced)'
      );
      return;
    }

    this.active = true;
    this.running = true;
    console.log(
      `\x1b[36m[tx-pressure]\x1b[0m dripping currency txs to ${cl1Url} every ${intervalMs}ms ` +
        '— EventTrigger pressure on ML0'
    );

    this.loop = (async () => {
      let ref = lastRef!;
      while (this.running) {
        try {
          const tx = await createCurrencyTransaction(
            { destination: RECIPIENT, amount: 0.01, fee: 0 },
            PRIVATE_KEY,
            ref
          );
          await client.postTransaction(tx);
          // The tx's own ordinal is parent + 1; chain the next one off it.
          ref = await getTransactionReference(tx, (ref.ordinal ?? 0) + 1);
          this.sent++;
        } catch {
          // A rejected/raced tx — re-anchor on the chain's current last reference and keep going.
          try {
            ref = await client.getLastReference(SENDER);
          } catch {
            /* keep trying next tick */
          }
        }
        await new Promise((r) => setTimeout(r, intervalMs));
      }
    })();
  }

  async stop(): Promise<void> {
    this.running = false;
    if (this.loop) await this.loop.catch(() => {});
    if (this.active) console.log(`\x1b[36m[tx-pressure]\x1b[0m stopped (${this.sent} txs sent)`);
    this.active = false;
  }
}
