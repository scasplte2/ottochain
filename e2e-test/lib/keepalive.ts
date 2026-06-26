import crypto from 'crypto';
import type { KeyPair } from '@ottochain/sdk';
import sendSignedUpdate from './sendDataTransaction.ts';
import { generator as createFiber } from './state-machine/createFiber.ts';

/**
 * Background chain keepalive.
 *
 * == Why this exists ==
 *
 * The metagraph processes the e2e's update burst in one short window, then — once every flow pauses
 * to wait on a confirmation — the data pipeline goes idle and DEADLOCKS: the data-L1 stops forming
 * blocks, so the metagraph's finalized reference freezes at the last data ordinal, so the data-L1
 * can never advance its tip, so block formation stays dead. The chain produces only empty idle
 * snapshots forever after; any update sent into that window is validated but NEVER combined, so its
 * flow's confirmation can never land (it neither applies nor rejects). The flows that lose are
 * exactly the ones whose sequential steps (create → invoke → invoke …) extend past the burst.
 *
 * The fix is to never let the chain idle into that deadlock. This loop sends a steady trickle of
 * guaranteed-NOVEL data updates — a fresh throwaway state-machine fiber per tick (random CID) — so
 * the data-L1 mempool is never empty and keeps forming blocks (event-trigger paced). Stuck flow
 * updates ride along in those blocks and get combined; the finalized reference keeps advancing, so
 * the deadlock never triggers. Each create is independent and disposable — we never confirm it.
 *
 * Self-paced via a setTimeout chain (not setInterval) so a slow send never overlaps the next tick.
 */
export class ChainKeepalive {
  private running = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private ticks = 0;
  private errors = 0;
  private confirmedAlive = false;

  constructor(
    private readonly wallets: Record<string, KeyPair>,
    private readonly dl1Urls: string[],
    private readonly options: { definition: string | object; initialData: string | object },
    /** Cadence between keepalive creates. Must stay well under the data-L1 idle TimeTrigger (8s) so
     *  the mempool is never seen empty. */
    private readonly intervalMs = 3000
  ) {}

  start(): void {
    if (this.running) return;
    this.running = true;
    this.timer = setTimeout(() => void this.tick(), this.intervalMs);
  }

  private async tick(): Promise<void> {
    if (!this.running) return;
    try {
      const message = createFiber({ cid: crypto.randomUUID(), options: this.options });
      await sendSignedUpdate(message, this.wallets, this.dl1Urls, { quiet: true });
      this.ticks++;
      // Quiet by default: the keepalive is pure background plumbing, so its per-tick chatter buries
      // the flow output (50+ lines over a 20-min run). Gate ALL keepalive progress logs behind
      // E2E_VERBOSE; the one-line stop() summary always prints so the run still reports it was fed.
      if (!this.confirmedAlive) {
        this.confirmedAlive = true;
        if (process.env.E2E_VERBOSE) {
          console.log('\x1b[36m[keepalive]\x1b[0m feeding the chain — first novel update accepted');
        }
      }
      // Heartbeat every ~30s so a verbose run log shows the chain is being kept fed, without spamming.
      if (process.env.E2E_VERBOSE && this.ticks % 10 === 0) {
        console.log(`\x1b[36m[keepalive]\x1b[0m ${this.ticks} updates sent (${this.errors} failed)`);
      }
    } catch {
      this.errors++;
    } finally {
      if (this.running) this.timer = setTimeout(() => void this.tick(), this.intervalMs);
    }
  }

  stop(): { ticks: number; errors: number } {
    this.running = false;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    const mode = process.env.E2E_VERBOSE ? 'verbose' : 'silent';
    console.log(`\x1b[36m[keepalive]\x1b[0m ${mode} · ${this.ticks} sent, ${this.errors} failed`);
    return { ticks: this.ticks, errors: this.errors };
  }
}
