/**
 * Helpers for the multi-fiber / asset-aware e2e harness extensions (riverdale-economy and beyond).
 *
 * The base runner is single-fiber-per-flow: it sends to one `session.cid` and validates that same
 * fiber. The riverdale economy is cross-fiber — a manufacturer's `fulfill_order` triggers a
 * retailer's `receive_shipment` and moves a GOODS asset — so a flow must be able to (a) sign as a
 * specific party, (b) poll-and-assert a fiber that was changed INDIRECTLY by a trigger, and (c)
 * poll-and-assert real asset custody. These helpers back the `signers` / `assertState` / `assertAsset`
 * runner features. All reads are poll-with-retry against ML0 (the committed read trails GL0
 * finalization, exactly like the rest of the runner).
 */
import { HttpClient } from '@ottochain/sdk';
import type { Wallets } from './types.ts';

/**
 * Recursive, key-order-independent structural equality (mirrors processEvent.ts). `bigint`/`number`
 * compare by numeric value so an int that decodes to a BigInt still matches a JSON literal.
 */
export function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a === 'bigint' || typeof b === 'bigint') {
    if (
      (typeof a === 'bigint' || typeof a === 'number') &&
      (typeof b === 'bigint' || typeof b === 'number')
    ) {
      return BigInt(a as never) === BigInt(b as never);
    }
    return false;
  }
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') {
    return false;
  }
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((v, i) => deepEqual(v, b[i]));
  }
  const ao = a as Record<string, unknown>;
  const bo = b as Record<string, unknown>;
  const ak = Object.keys(ao);
  const bk = Object.keys(bo);
  if (ak.length !== bk.length) return false;
  return ak.every((k) => Object.prototype.hasOwnProperty.call(bo, k) && deepEqual(ao[k], bo[k]));
}

/**
 * Narrow the full wallet map to the subset named by a step's `signers`. Owners of a created fiber
 * (and the authorized signer of a transition) are exactly the proofs on the message, so `signers`
 * IS the party model: omit ⇒ every wallet signs (today's behavior); `["alice"]` ⇒ only alice.
 */
export function selectSigners(all: Wallets, names?: string[]): Wallets {
  if (!names || names.length === 0) return all;
  const subset: Wallets = {};
  for (const n of names) {
    if (!all[n]) {
      throw new Error(
        `step signer "${n}" is not in the --wallets set (have: ${Object.keys(all).join(', ')})`
      );
    }
    subset[n] = all[n];
  }
  return subset;
}

/**
 * A holder reference as written in an example step — `{ Fiber: "<alias>" }` or `{ Wallet: "<name>" }`
 * — where the inner string is a fiber ALIAS (resolved via the flow's fiber map) or a WALLET name
 * (resolved to its address). Compared against the on-chain `AssetHolder` wire form
 * `{ Fiber: { fiberId } }` / `{ Wallet: { address } }`.
 */
export type HolderRef = { Fiber: string } | { Wallet: string };

/** True iff an on-chain `AssetHolder` matches the step's holder ref, given the flow's resolvers. */
export function holderMatches(
  onChain: unknown,
  ref: HolderRef,
  resolveFiber: (alias: string) => string,
  wallets: Wallets
): boolean {
  const h = onChain as { Fiber?: { fiberId?: string }; Wallet?: { address?: string } } | null;
  if (!h || typeof h !== 'object') return false;
  if ('Fiber' in ref) {
    return h.Fiber?.fiberId === resolveFiber(ref.Fiber);
  }
  const addr = wallets[ref.Wallet]?.address;
  return !!addr && h.Wallet?.address === addr;
}

type Rec = Record<string, unknown> | null;

/** Poll a per-fiber ML0 read until `predicate` holds (or retries run out). Returns the record. */
export async function pollFiberRecord(
  ml0BaseUrl: string,
  cid: string,
  predicate: (r: Rec) => boolean,
  maxRetries: number,
  retryDelayMs: number,
  write?: (s: string) => void
): Promise<Rec> {
  const client = new HttpClient(`${ml0BaseUrl}/data-application/v1/state-machines/${cid}`);
  let last: Rec = null;
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      last = (await client.get<unknown>('')) as Rec;
      if (predicate(last)) return last;
    } catch {
      /* not yet present — keep polling */
    }
    write?.('.');
    if (attempt < maxRetries) await new Promise((r) => setTimeout(r, retryDelayMs));
  }
  return last;
}

/**
 * Poll an asset's committed custody record (via the `/assets/{id}/state-proof` endpoint, whose
 * `.record` is the `AssetRecord`) until `predicate` holds. Returns the record (or null on timeout).
 */
export async function pollAssetRecord(
  ml0BaseUrl: string,
  assetId: string,
  predicate: (r: Rec) => boolean,
  maxRetries: number,
  retryDelayMs: number,
  write?: (s: string) => void
): Promise<Rec> {
  const client = new HttpClient(`${ml0BaseUrl}/data-application/v1/assets/${assetId}/state-proof`);
  let last: Rec = null;
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const resp = (await client.get<unknown>('')) as { record?: Rec } | null;
      last = (resp?.record ?? null) as Rec;
      if (predicate(last)) return last;
    } catch {
      /* asset not minted/committed yet — keep polling */
    }
    write?.('.');
    if (attempt < maxRetries) await new Promise((r) => setTimeout(r, retryDelayMs));
  }
  return last;
}
