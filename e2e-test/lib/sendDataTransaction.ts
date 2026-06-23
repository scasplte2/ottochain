import { HttpClient, batchSign } from '@ottochain/sdk';
import { dropNulls } from './dropNulls';
import type { KeyPair } from '@ottochain/sdk';

/** Per-node POST timeout (ms). A hung DL1 node must not stall the whole fan-out. */
const NODE_TIMEOUT_MS = Number(process.env.DL1_TIMEOUT_MS) || 30_000;

/** One-line summary of an outbound update: its message type + a truncated target id. */
function describeUpdate(message: unknown): string {
  const value = (message as { value?: Record<string, unknown> } | null)?.value;
  if (!value || typeof value !== 'object') return 'update';
  const msgType = Object.keys(value)[0] ?? 'update';
  const inner = value[msgType] as Record<string, unknown> | undefined;
  const rawId = inner?.fiberId ?? inner?.machineId ?? inner?.name ?? '';
  const id = typeof rawId === 'string' ? rawId : String(rawId ?? '');
  const shortId = id.length > 8 ? id.slice(0, 8) : id;
  return shortId ? `${msgType} ${shortId}` : msgType;
}

/** Reject `p` if it hasn't settled within `ms`, with a timeout-shaped error. */
function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(
      () => reject(Object.assign(new Error(`timeout after ${ms}ms`), { statusCode: 'timeout', response: '' })),
      ms
    );
  });
  return Promise.race([p, timeout]).finally(() => {
    if (timer) clearTimeout(timer);
  });
}

/** Short label for a DL1 url ('http://127.0.0.1:9400' → '9400'). */
function nodeLabel(url: string): string {
  const m = url.match(/:(\d+)\/?$/);
  return m ? m[1] : url.replace(/^https?:\/\//, '');
}

type NodeOutcome =
  | { node: string; ok: true; hash: string }
  | { node: string; ok: false; status: number | string; body: string };

/**
 * Sign a message with multiple wallets and POST to every DL1 /data endpoint.
 *
 * Fans out to all N DL1 nodes concurrently (Promise.all; each request bounded by a per-node
 * timeout). The nodes are INDEPENDENT and allowed to disagree, so the result is summarised in a
 * single line that collapses ONLY on true consensus and otherwise spells out each node:
 *   [dl1] CreateStateMachine 4d23830f → 3/3 ✓ 315b679e                                  (consensus)
 *   [dl1] CreateStateMachine 4d23830f → 2/3 ⚠ 9400:315b679e 9410:315b679e 9420:timeout  (divergence)
 *   [dl1] CreateStateMachine 4d23830f → 0/3 ✗ 9400:HTTP 400 9410:HTTP 400 9420:HTTP 400  (all reject)
 * On total failure the full per-node breakdown is also folded into the thrown error.
 */
export default async function sendSignedUpdate(
  message: unknown,
  wallets: Record<string, KeyPair>,
  dl1Urls: string[]
): Promise<{ hash: string }[]> {
  const privateKeys = Object.values(wallets).map((w) => w.privateKey);

  // Drop null fields before signing. metakit drops nulls when building the canonical bytes the
  // chain signs/verifies, so the client must sign the same null-free form — otherwise a message
  // carrying a null (e.g. a state's `metadata: null`) is signed over a different canonical than the
  // chain re-derives, and verification fails (HTTP 400).
  const signed = await batchSign(dropNulls(message), privateKeys, { isDataUpdate: true });

  const tag = '\x1b[33m[dl1]\x1b[0m';
  const what = describeUpdate(message);

  // Each node resolves to its OWN outcome (never rejects), so Promise.all waits for all N and
  // preserves every node's independent verdict instead of hiding it behind allSettled.
  const outcomes: NodeOutcome[] = await Promise.all(
    dl1Urls.map(async (url): Promise<NodeOutcome> => {
      const node = nodeLabel(url);
      try {
        const res = await withTimeout(
          new HttpClient(url).post<{ hash: string }>('/data', signed),
          NODE_TIMEOUT_MS
        );
        return { node, ok: true, hash: res.hash };
      } catch (e) {
        const err = (e ?? {}) as { message?: string; statusCode?: number | string; response?: unknown };
        const status = err.statusCode ?? '?';
        const body =
          err.response === undefined || err.response === null || err.response === ''
            ? status === 'timeout'
              ? ''
              : '(empty body)'
            : typeof err.response === 'string'
              ? err.response
              : JSON.stringify(err.response);
        return { node, ok: false, status, body };
      }
    })
  );

  const ok = outcomes.filter((o): o is Extract<NodeOutcome, { ok: true }> => o.ok);
  const hashes = new Set(ok.map((o) => o.hash));
  const consensus = ok.length === dl1Urls.length && hashes.size === 1;

  if (consensus) {
    console.log(
      `${tag} ${what} → \x1b[32m${ok.length}/${dl1Urls.length}\x1b[0m ✓ ${[...hashes][0].slice(0, 8)}`
    );
  } else {
    // Divergence: spell out each node so a split hash, a slow/timed-out node, or a lone rejecter is
    // visible rather than averaged away.
    const perNode = outcomes
      .map((o) =>
        o.ok
          ? `${o.node}:${o.hash.slice(0, 8)}`
          : `${o.node}:${o.status === 'timeout' ? 'timeout' : `HTTP ${o.status}`}${o.body ? ` ${o.body}` : ''}`
      )
      .join('  ');
    const mark = ok.length > 0 ? '\x1b[33m⚠\x1b[0m' : '\x1b[31m✗\x1b[0m';
    console.log(`${tag} ${what} → ${ok.length}/${dl1Urls.length} ${mark}  ${perNode}`);
  }

  if (ok.length === 0) {
    const detail = outcomes
      .filter((o): o is Extract<NodeOutcome, { ok: false }> => !o.ok)
      .map((o) => `${o.node} ${o.status === 'timeout' ? 'timeout' : `HTTP ${o.status}: ${o.body}`}`)
      .join(' | ');
    throw new Error(`All requests failed: ${detail}`);
  }

  return ok.map((o) => ({ hash: o.hash }));
}
