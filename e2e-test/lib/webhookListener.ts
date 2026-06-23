import http from 'http';
import { execSync } from 'child_process';

/**
 * Push-driven confirmation source for the e2e runner.
 *
 * The ML0 metagraph posts webhooks to a subscribed callback on every snapshot consensus result
 * (`onConsensus` in ML0Service.scala). Crucially the chain commits the read state FIRST
 * (`checkpointService.set(...)`) and THEN dispatches the webhook — so when `snapshot.finalized`
 * arrives, the HTTP read routes already reflect that ordinal. This lets us re-check fiber state
 * exactly when it becomes current instead of blind-polling a lagging read, and it surfaces explicit
 * `transaction.rejected` events (fiberId + targetSequenceNumber + errors) so a denied/poisoned update
 * is known immediately rather than via a "not included" timeout.
 *
 * Webhook API (modules/l0 ML0Routes + WebhookDispatcher):
 *   POST   /data-application/v1/webhooks/subscribe      { callbackUrl, secret? }
 *   DELETE /data-application/v1/webhooks/subscribe/{id}
 *   push:  { event: "snapshot.finalized",   ordinal, hash, stats:{updatesProcessed,...} }
 *   push:  { event: "transaction.rejected",  ordinal, rejection:{ updateType, fiberId,
 *                                                                  targetSequenceNumber, errors[], updateHash } }
 */

export interface RejectionInfo {
  ordinal: number;
  updateType: string;
  fiberId: string;
  targetSequenceNumber: number | null;
  updateHash: string;
  errors: { code: string; message: string }[];
}

/**
 * Resolve a host:port the ML0 *container* can use to reach this process (the runner, on the host).
 * The container reaches the host through its docker network gateway; we read it off the ml0 container,
 * then fall back to host.docker.internal / the default bridge gateway.
 */
function resolveCallbackHost(): string {
  if (process.env.E2E_WEBHOOK_HOST) return process.env.E2E_WEBHOOK_HOST;
  for (const name of ['ml0-0', 'metagraph-l0-1', 'global-l0-1']) {
    try {
      const gw = execSync(
        `docker inspect ${name} --format '{{range .NetworkSettings.Networks}}{{.Gateway}} {{end}}'`,
        { stdio: ['ignore', 'pipe', 'ignore'] }
      )
        .toString()
        .trim()
        .split(/\s+/)
        .filter(Boolean)[0];
      if (gw) return gw;
    } catch {
      // container not found under this name — try the next
    }
  }
  return 'host.docker.internal';
}

export class WebhookListener {
  private server?: http.Server;
  private subscriptions: { ml0Url: string; id: string }[] = [];
  private snapshotWaiters: Array<() => void> = [];
  private rejections: RejectionInfo[] = [];
  /** Highest finalized ordinal seen via `snapshot.finalized`. */
  latestOrdinal = 0;
  /** True once at least one ML0 accepted the subscription (so callers can fall back to polling). */
  active = false;
  private callbackUrl = '';

  /** Start the callback server and subscribe to every ML0 node. Never throws — falls back gracefully. */
  async start(ml0Urls: string[]): Promise<void> {
    const host = resolveCallbackHost();
    await new Promise<void>((resolve) => {
      this.server = http.createServer((req, res) => {
        if (req.method !== 'POST') {
          res.writeHead(405).end();
          return;
        }
        let body = '';
        req.on('data', (c) => (body += c));
        req.on('end', () => {
          res.writeHead(200).end();
          try {
            this.handle(JSON.parse(body));
          } catch {
            /* ignore malformed bodies */
          }
        });
      });
      this.server.on('error', () => resolve());
      this.server.listen(0, '0.0.0.0', () => resolve());
    });

    const addr = this.server?.address();
    if (!addr || typeof addr === 'string') return;
    this.callbackUrl = `http://${host}:${addr.port}/`;

    const uniqueMl0 = [...new Set(ml0Urls)];
    for (const ml0Url of uniqueMl0) {
      try {
        const r = await fetch(`${ml0Url}/data-application/v1/webhooks/subscribe`, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ callbackUrl: this.callbackUrl }),
        });
        const id = (await r.json().catch(() => ({})))?.id as string | undefined;
        if (r.ok && id) {
          this.subscriptions.push({ ml0Url, id });
          this.active = true;
        }
      } catch {
        // ML0 may not expose webhooks (older build) — caller falls back to polling.
      }
    }
  }

  private handle(msg: { event?: string; ordinal?: number; rejection?: RejectionInfo }): void {
    if (msg.event === 'snapshot.finalized') {
      if (typeof msg.ordinal === 'number' && msg.ordinal > this.latestOrdinal) {
        this.latestOrdinal = msg.ordinal;
      }
      const waiters = this.snapshotWaiters;
      this.snapshotWaiters = [];
      waiters.forEach((w) => w());
    } else if (msg.event === 'transaction.rejected' && msg.rejection) {
      this.rejections.push({ ...msg.rejection, ordinal: msg.ordinal ?? this.latestOrdinal });
    }
  }

  /** Resolve on the next `snapshot.finalized` push, or after `timeoutMs` (whichever first). */
  waitNextSnapshot(timeoutMs: number): Promise<void> {
    if (!this.active) return new Promise((r) => setTimeout(r, timeoutMs));
    return new Promise((resolve) => {
      let done = false;
      const fire = () => {
        if (done) return;
        done = true;
        resolve();
      };
      this.snapshotWaiters.push(fire);
      setTimeout(fire, timeoutMs);
    });
  }

  /** A rejection for this fiber's target seq (or update hash) seen at/after `sinceOrdinal`, if any. */
  findRejection(opts: {
    fiberId: string;
    targetSeq?: number | null;
    updateHash?: string;
    sinceOrdinal: number;
  }): RejectionInfo | undefined {
    return this.rejections.find(
      (r) =>
        r.ordinal >= opts.sinceOrdinal &&
        ((opts.updateHash && r.updateHash === opts.updateHash) ||
          (r.fiberId === opts.fiberId &&
            (opts.targetSeq == null || r.targetSequenceNumber === opts.targetSeq)))
    );
  }

  /** Unsubscribe from every ML0 and close the callback server. Best-effort. */
  async stop(): Promise<void> {
    for (const sub of this.subscriptions) {
      try {
        await fetch(`${sub.ml0Url}/data-application/v1/webhooks/subscribe/${sub.id}`, { method: 'DELETE' });
      } catch {
        /* best-effort */
      }
    }
    this.subscriptions = [];
    this.active = false;
    await new Promise<void>((resolve) => (this.server ? this.server.close(() => resolve()) : resolve()));
  }
}
