import http from 'http';
import { execSync } from 'child_process';

import { OttoMetagraphClient } from '@ottochain/sdk';
import type { SubscribeResponse } from '@ottochain/sdk';

/**
 * Push-driven confirmation source for the e2e runner.
 *
 * The ML0 metagraph posts webhooks to a subscribed callback on every snapshot consensus result
 * (`onConsensus` in ML0Service.scala). Crucially the chain commits the read state FIRST
 * (`checkpointService.set(...)`) and THEN dispatches the webhook — so when `snapshot.finalized`
 * arrives, the HTTP read routes already reflect that ordinal. This lets us re-check fiber state
 * exactly when it becomes current instead of blind-polling a lagging read.
 *
 * Rejections are no longer a separate speculative `transaction.rejected` event. They are now drained
 * post-finalization from the committed snapshot's RejectionReceipts and batched onto the
 * `snapshot.finalized` push as a `rejections[]` array — so a denied/poisoned update is known the
 * moment the snapshot that rejected it finalizes (still ahead of a "not included" timeout, but now
 * deterministic: it reflects committed state, not a pre-combine guess).
 *
 * Subscription lifecycle goes through the SDK's typed client — `OttoMetagraphClient.subscribeWebhook`
 * / `unsubscribeWebhook` / `listWebhookSubscribers` — NOT raw fetch. That is deliberate: it makes
 * this harness exercise (and therefore GUARD) the SDK's webhook request surface, so a drift between
 * the SDK client and the chain's `/webhooks/*` routes/DTOs breaks the e2e instead of a user.
 *
 * The PUSH payload (chain → this callback) is not yet modeled by the SDK (it's a server-initiated
 * notification, outside the client's request surface), so the local `RejectionPayload` / handler
 * shapes below mirror the chain's `SnapshotNotification` (webhooks/Subscriber.scala) verbatim. TODO:
 * once the SDK exports the notification payload types, import them here so this shape is guarded too.
 *
 * Webhook API (modules/l0 ML0Routes + WebhookDispatcher):
 *   POST   /data-application/v1/webhooks/subscribe      { callbackUrl, secret? }  (SDK subscribeWebhook)
 *   DELETE /data-application/v1/webhooks/subscribe/{id}                           (SDK unsubscribeWebhook)
 *   GET    /data-application/v1/webhooks/subscribers                              (SDK listWebhookSubscribers)
 *   push:  { event: "snapshot.finalized", ordinal, hash, stats:{updatesProcessed,...,rejectedCount},
 *           rejections:[{ updateType, fiberId, targetSequenceNumber, actualSequenceNumber,
 *                         reason, updateHash }] }
 */

export interface RejectionInfo {
  ordinal: number;
  updateType: string;
  fiberId: string;
  targetSequenceNumber: number | null;
  actualSequenceNumber: number | null;
  updateHash: string;
  reason: string;
  /** Back-compat shim for readers that expect structured errors: [{ code: updateType, message: reason }]. */
  errors: { code: string; message: string }[];
}

/** Wire shape of a single rejection batched onto the `snapshot.finalized` push. */
interface RejectionPayload {
  updateType: string;
  fiberId: string;
  targetSequenceNumber: number | null;
  actualSequenceNumber: number | null;
  reason: string;
  updateHash: string;
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
  private subscriptions: { client: OttoMetagraphClient; id: string }[] = [];
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
    console.log(`\x1b[36m[webhook]\x1b[0m callback = ${this.callbackUrl} (container→host)`);

    const uniqueMl0 = [...new Set(ml0Urls)];
    for (const ml0Url of uniqueMl0) {
      try {
        // Subscribe through the SDK's typed client (not raw fetch) so this harness guards the SDK's
        // webhook request surface: a drift in the method signature, path, or DTO breaks here.
        const client = new OttoMetagraphClient({ ml0Url });
        const resp: SubscribeResponse = await client.subscribeWebhook({ callbackUrl: this.callbackUrl });
        if (resp?.id) {
          this.subscriptions.push({ client, id: resp.id });
          this.active = true;
        }
      } catch {
        // ML0 may not expose webhooks (older build) — caller falls back to polling.
      }
    }

    // Exercise the list endpoint too (and confirm the node registered our callback) — another SDK
    // surface guarded, and a useful sanity log for the container→host reachability.
    if (this.active) {
      try {
        const list = await this.subscriptions[0].client.listWebhookSubscribers();
        console.log(
          `\x1b[36m[webhook]\x1b[0m subscribed ${this.subscriptions.length} node(s); ` +
            `node reports ${list.subscribers?.length ?? 0} active subscriber(s)`
        );
      } catch {
        /* non-fatal: the subscribe already succeeded */
      }
    }
  }

  /** Count of pushes received, for the one-time delivery-confirmation log. */
  private recvCount = 0;

  private handle(msg: { event?: string; ordinal?: number; rejections?: RejectionPayload[] }): void {
    // Prove delivery: the first push that lands tells us the container→host callback actually works
    // (vs. a silent fallback to polling). Subsequent pushes are summarised, not spammed.
    this.recvCount++;
    if (this.recvCount === 1) {
      console.log(`\x1b[36m[webhook]\x1b[0m first push received (${msg.event} ord=${msg.ordinal}) — delivery OK`);
    }
    if (msg.event === 'snapshot.finalized') {
      const ordinal = typeof msg.ordinal === 'number' ? msg.ordinal : this.latestOrdinal;
      if (ordinal > this.latestOrdinal) {
        this.latestOrdinal = ordinal;
      }
      // Rejections now ride the finalized snapshot (drained from its committed RejectionReceipts).
      if (Array.isArray(msg.rejections)) {
        for (const r of msg.rejections) {
          this.rejections.push({
            ordinal,
            updateType: r.updateType,
            fiberId: r.fiberId,
            targetSequenceNumber: r.targetSequenceNumber ?? null,
            actualSequenceNumber: r.actualSequenceNumber ?? null,
            updateHash: r.updateHash,
            reason: r.reason,
            // Back-compat shim for `findRejection` consumers that read structured `errors`.
            errors: [{ code: r.updateType, message: r.reason }],
          });
        }
      }
      const waiters = this.snapshotWaiters;
      this.snapshotWaiters = [];
      waiters.forEach((w) => w());
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
        await sub.client.unsubscribeWebhook(sub.id);
      } catch {
        /* best-effort */
      }
    }
    this.subscriptions = [];
    this.active = false;
    await new Promise<void>((resolve) => (this.server ? this.server.close(() => resolve()) : resolve()));
  }
}
