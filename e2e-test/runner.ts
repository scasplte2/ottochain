/**
 * Ottochain E2E Test Runner
 *
 * Discovers all examples with testFlows and runs them automatically.
 * Reports pass/fail per flow with step-level detail.
 * Exits with non-zero code on any failure (CI-friendly).
 *
 * Usage: npx tsx runner.ts [--target local|ci|remote] [--wallets alice,bob]
 */
import 'dotenv/config';

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import generateWallet from './lib/generateWallet.ts';
import sendSignedUpdate from './lib/sendDataTransaction.ts';
import getMetagraphEnv from './lib/metagraphEnv.ts';
import type { StatesMap, Wallets, GeneratorFn, ValidatorFn } from './lib/types.ts';
import { HttpClient, OttoMetagraphClient } from '@ottochain/sdk';
// JCS canonicalizer (serializeJcs ∘ dropNullFields) — the chain's content-hash rule. Used by
// assertStateProof to verify Merkle-Patricia inclusion proofs client-side (light-client check).
import { canonicalize } from '@constellation-network/metagraph-sdk';
import { createHash } from 'crypto';
// WIRE record types (from `/core` = ottochain/types) — the exact shapes the client's typed getters
// return. NOT the root's same-named exports, which resolve to the generated protobuf types.
import type { StateMachineFiberRecord, ScriptFiberRecord, RegistryEntry } from '@ottochain/sdk/core';
import { waitForOrdinalConfirmation, waitForOrdinalAdvance } from './lib/ordinalConfirmation.ts';
import { WebhookListener } from './lib/webhookListener.ts';
import { ChainKeepalive } from './lib/keepalive.ts';
import {
  deepEqual,
  selectSigners,
  holderMatches,
  pollFiberRecord,
  pollAssetRecord,
} from './lib/assertHelpers.ts';
import type { HolderRef } from './lib/assertHelpers.ts';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const examplesDir = path.join(__dirname, 'examples');

/**
 * Typed read of a fiber's current record from ML0 — a state machine or a script per `isScript`.
 * Returns the parsed record (or null on 404) so callers read typed `.sequenceNumber` / `.status`
 * (a `FiberStatus`) instead of casting an `unknown` body — a chain-side record-shape drift then fails
 * the typecheck here. Both record types carry `sequenceNumber` and `status`, so the union suffices for
 * the confirmation reads that key off those two fields.
 */
async function readFiberRecord(
  ml0Url: string,
  cid: string,
  isScript: boolean
): Promise<StateMachineFiberRecord | ScriptFiberRecord | null> {
  const client = new OttoMetagraphClient({ ml0Url });
  return isScript ? client.getScript(cid) : client.getStateMachine(cid);
}

// ---------------------------------------------------------------------------
// CLI arguments (minimal — no commander needed)
// ---------------------------------------------------------------------------

function parseArgs() {
  const args = process.argv.slice(2);
  const opts: Record<string, string> = {
    target: 'local',
    // Default single signer. Multi-participant flows (e.g. staked-oracle-pool, whose quorum needs
    // alice+bob+carol as distinct joiners) require more — set via E2E_WALLETS per CI lane. With one
    // wallet a `wallet: "bob"` step silently falls back to alice's address and fails the
    // not-already-a-participant guard. `--wallets` still overrides.
    wallets: process.env.E2E_WALLETS || 'alice',
    waitTime: '5',
    retryDelay: '5',
    // maxRetries × retryDelay(5s) = per ML0-confirmation / DL1-sync / validation wait budget.
    // The one-time `waitForOrdinalAdvance` warmup gate (below, #169) pays the cluster cold-start —
    // a fresh metakit jar (cold cache) + JVM warmup slowing first-block production — BEFORE the
    // timed flows (on its own wall-clock budget), so this per-flow budget is mostly a cushion for a
    // slow/contended CI runner. 40 (~200s) keeps that cushion (was 20 ≈ 100s; intermittent
    // `DL1 sync timed out … (seqNum=…)` flake, #167/#168).
    maxRetries: '40',
    parallel: 'true',
  };

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--sequential') {
      opts.parallel = 'false';
    } else if (args[i].startsWith('--') && i + 1 < args.length) {
      opts[args[i].slice(2)] = args[i + 1];
      i++;
    }
  }

  return opts;
}

// ---------------------------------------------------------------------------
// Buffered logger — captures output per flow to avoid interleaved output
// ---------------------------------------------------------------------------

class FlowLogger {
  private lines: string[] = [];
  private currentLine = '';
  public readonly tag: string;
  /**
   * Live (write-through) mode. When the runner is at CONCURRENCY 1 a flow CANNOT interleave with
   * another, so its output is written straight to stdout as it happens (no 20-min buffer-then-dump).
   * When > 1 flow runs in parallel we keep buffering and flush each flow as one block, so the
   * concurrent flows' lines never interleave. flush() is a no-op in live mode (nothing is buffered).
   */
  private readonly live: boolean;

  constructor(tag: string, live = false) {
    this.tag = tag;
    this.live = live;
  }

  write(text: string): void {
    if (this.live) {
      process.stdout.write(text);
      return;
    }
    this.currentLine += text;
    if (text.includes('\n')) {
      const parts = this.currentLine.split('\n');
      // All but the last part are complete lines
      for (let i = 0; i < parts.length - 1; i++) {
        this.lines.push(parts[i]);
      }
      this.currentLine = parts[parts.length - 1];
    }
  }

  log(...args: unknown[]): void {
    const text = args.map(String).join(' ');
    if (this.live) {
      // The preceding write()s already emitted the partial line; complete it with a newline.
      process.stdout.write(text + '\n');
      return;
    }
    if (this.currentLine) {
      this.lines.push(this.currentLine + text);
      this.currentLine = '';
    } else {
      this.lines.push(text);
    }
  }

  flush(): void {
    if (this.live) return; // write-through already emitted everything
    if (this.currentLine) {
      this.lines.push(this.currentLine);
      this.currentLine = '';
    }
    if (this.lines.length > 0) {
      console.log(this.lines.join('\n'));
      this.lines = [];
    }
  }
}

// ---------------------------------------------------------------------------
// Observability helpers (phase headers + economy snapshot — riverdale-economy)
// ---------------------------------------------------------------------------

/** Fixed visual width for the `── <phase> ───` and `│ economy ───` rules. */
const RULE_WIDTH = 48;

/** A phase banner: `── <label> ─────────…` padded/truncated to RULE_WIDTH (leading blank line). */
function phaseBanner(label: string): string {
  const head = `── ${label} `;
  const trimmed = head.length > RULE_WIDTH ? head.slice(0, RULE_WIDTH) : head;
  return `\n${trimmed}${'─'.repeat(Math.max(0, RULE_WIDTH - trimmed.length))}`;
}

/** One stored economy snapshot for a party, used to render deltas on the NEXT economy step. */
type PartySnapshot = {
  state?: string;
  fields: Record<string, number | string>;
  assets: Record<string, number>;
};

/** A party as declared in an `economy` step. */
type EconomyParty = { fiber?: string; wallet?: string; label: string; show?: string[] };
/** An asset id → friendly label entry as declared in an `economy` step. */
type EconomyAsset = { id: string; label: string };

/**
 * Read the ML0 checkpoint ONCE and print a compact economy table — one line per party (its fiber
 * `currentState` + the selected `show` stateData fields) plus the asset instances it holds. Values
 * that changed since the previous `economy` step render as deltas: `inventory 1000→500` for a show
 * field, `+GOODS×500` for a newly-acquired instance, `RVD-loan 10000→0` for one that left custody.
 * Observability-only and side-effect-free on the chain — it NEVER throws (a checkpoint read error
 * degrades to a single note line), so it stays a true no-op for the flow's pass/fail.
 */
async function renderEconomy(
  step: TestStep,
  ml0BaseUrl: string,
  wallets: Wallets,
  resolveFiber: (alias?: string) => string,
  prev: Record<string, PartySnapshot>,
  l: (...a: unknown[]) => void
): Promise<void> {
  type SmRec = { currentState?: string; stateData?: Record<string, unknown> };
  type AsRec = { holder?: { Fiber?: { fiberId?: string }; Wallet?: { address?: string } }; amount?: number };

  const parties = step.parties ?? [];
  const assetLabels = new Map<string, string>();
  for (const a of step.assets ?? []) assetLabels.set(a.id, a.label);

  let machines: Record<string, SmRec> = {};
  let assets: Record<string, AsRec> = {};
  try {
    // Raw read by design: this is a DISPLAY-ONLY economy table that projects the checkpoint into the
    // loose local `SmRec`/`AsRec` view-types (a subset of the typed `CalculatedState` records), and it
    // swallows any read error into a "checkpoint unavailable" line — so it is not a drift-guarding
    // confirmation read. The confirmation-critical reads above/below use the typed client.
    const cp = (await new HttpClient(`${ml0BaseUrl}/data-application/v1/checkpoint`).get<unknown>(
      ''
    )) as { state?: { stateMachines?: Record<string, SmRec>; assets?: Record<string, AsRec> } } | null;
    machines = cp?.state?.stateMachines ?? {};
    assets = cp?.state?.assets ?? {};
  } catch (e) {
    l(`    \x1b[36m│\x1b[0m economy (checkpoint unavailable: ${(e as Error).message})`);
    return;
  }

  const head = step.label ? `economy ${step.label} ` : 'economy ';
  l(`    \x1b[36m│\x1b[0m ${head}${'─'.repeat(Math.max(4, 30 - head.length))}`);

  const labelW = parties.reduce((m, p) => Math.max(m, p.label.length), 0);

  for (const party of parties) {
    const fiberId = party.fiber ? resolveFiber(party.fiber) : undefined;
    const walletAddr = party.wallet ? wallets[party.wallet]?.address : undefined;
    const rec = fiberId ? machines[fiberId] : undefined;
    const before = prev[party.label];
    const seen = before !== undefined;

    // currentState (plain — no delta; matches the confirmed preview).
    const state = rec?.currentState ?? (fiberId ? '—' : '');

    // selected stateData show-fields (delta when changed since the last economy step).
    const stateData = (rec?.stateData ?? {}) as Record<string, unknown>;
    const showFields = party.show ?? Object.keys(stateData).slice(0, 3);
    const curFields: Record<string, number | string> = {};
    const fieldParts: string[] = [];
    for (const f of showFields) {
      const raw = stateData[f];
      if (raw === undefined) continue;
      const val = typeof raw === 'number' || typeof raw === 'string' ? raw : String(raw);
      curFields[f] = val;
      const had = before?.fields[f];
      // Highlight a changed value in yellow so the eye lands on what moved this snapshot.
      fieldParts.push(had !== undefined && had !== val ? `${f} \x1b[33m${had}→${val}\x1b[0m` : `${f} ${val}`);
    }

    // held asset instances (acquired/changed/departed deltas once the party has a prior snapshot).
    const curAssets: Record<string, number> = {};
    for (const [id, arec] of Object.entries(assets)) {
      const h = arec?.holder;
      const mine =
        (fiberId !== undefined && h?.Fiber?.fiberId === fiberId) ||
        (walletAddr !== undefined && h?.Wallet?.address === walletAddr);
      if (mine) curAssets[id] = Number(arec?.amount ?? 0);
    }
    const assetParts: string[] = [];
    for (const id of new Set([...Object.keys(curAssets), ...Object.keys(before?.assets ?? {})])) {
      const name = assetLabels.get(id) ?? id.slice(0, 8);
      const cur = curAssets[id];
      const was = before?.assets[id];
      // Yellow = amount changed or instance left custody; green = newly acquired; plain = unchanged.
      if (cur !== undefined && was !== undefined && cur !== was) assetParts.push(`\x1b[33m${name} ${was}→${cur}\x1b[0m`);
      else if (cur !== undefined && was === undefined) assetParts.push(seen ? `\x1b[32m+${name}×${cur}\x1b[0m` : `${name}×${cur}`);
      else if (cur === undefined && was !== undefined && seen) assetParts.push(`\x1b[33m${name} ${was}→0\x1b[0m`);
      else if (cur !== undefined) assetParts.push(`${name}×${cur}`);
    }

    const cols = [state, fieldParts.join('  '), assetParts.join(' ')].filter(Boolean).join('   ');
    // Skip a wholly-empty row (e.g. a wallet party that holds nothing yet) so the table stays tight.
    if (cols) l(`    \x1b[36m│\x1b[0m ${party.label.padEnd(labelW)}  ${cols}`);

    prev[party.label] = { state: rec?.currentState, fields: curFields, assets: curAssets };
  }
}

// ---------------------------------------------------------------------------
// Shared helpers (same as terminal.ts)
// ---------------------------------------------------------------------------

async function loadFileOrModule(
  filePath: string,
  context: Record<string, unknown> = {}
): Promise<unknown> {
  const hasJsExt = filePath.endsWith('.js');
  const hasJsonExt = filePath.endsWith('.json');
  const hasTsExt = filePath.endsWith('.ts');
  const hasNoExt = !hasJsExt && !hasJsonExt && !hasTsExt;

  let tsPath: string, jsPath: string, jsonPath: string;
  if (hasNoExt) {
    tsPath = filePath + '.ts';
    jsPath = filePath + '.js';
    jsonPath = filePath + '.json';
  } else if (hasTsExt) {
    tsPath = filePath;
    jsPath = filePath.replace(/\.ts$/, '.js');
    jsonPath = filePath.replace(/\.ts$/, '.json');
  } else if (hasJsExt) {
    tsPath = filePath.replace(/\.js$/, '.ts');
    jsPath = filePath;
    jsonPath = filePath.replace(/\.js$/, '.json');
  } else {
    tsPath = filePath.replace(/\.json$/, '.ts');
    jsPath = filePath.replace(/\.json$/, '.js');
    jsonPath = filePath;
  }

  for (const candidate of [tsPath, jsPath]) {
    if (fs.existsSync(candidate)) {
      const mod = await import(candidate);
      const exported = mod.default ?? mod;
      return typeof exported === 'function' ? exported(context) : exported;
    }
  }

  if (fs.existsSync(jsonPath)) {
    return JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
  }

  throw new Error(`File not found: ${filePath} (tried .ts, .js, .json)`);
}

// Raw read by design: the body flows OPAQUELY into the example validator functions (compared as raw
// JSON) and into `JSON.stringify` output — none of which are typed against `Checkpoint` — so there is
// no typed shape to thread through here without retyping the whole example-validator layer.
async function getApplicationState(url: string): Promise<unknown> {
  const client = new HttpClient(url);
  return client.get<unknown>('');
}

async function getInitialStates(urls: string[]): Promise<StatesMap> {
  const initialStates = await Promise.all(
    urls.map((url) => getApplicationState(url))
  );

  const statesMap: StatesMap = {};
  for (let i = 0; i < urls.length; i++) {
    statesMap[urls[i]] = {
      initial: initialStates[i],
      final: null,
    };
  }

  return statesMap;
}

async function updateFinalStates(statesMap: StatesMap): Promise<StatesMap> {
  const urls = Object.keys(statesMap);
  const finalStates = await Promise.all(
    urls.map((url) => getApplicationState(url))
  );

  for (let i = 0; i < urls.length; i++) {
    statesMap[urls[i]].final = finalStates[i];
  }

  return statesMap;
}

async function validateWithRetries(
  txValidation: ValidatorFn,
  cid: string,
  statesMap: StatesMap,
  options: Record<string, unknown>,
  wallets: Wallets,
  maxRetries: number,
  retryDelayMs: number,
  ml0Urls: string[],
  attempt = 1
): Promise<void> {
  try {
    const updated = await updateFinalStates(statesMap);
    await txValidation({ cid, statesMap: updated, options, wallets, ml0Urls });
  } catch (err) {
    if (attempt >= maxRetries) {
      throw err;
    }
    await new Promise((resolve) => setTimeout(resolve, retryDelayMs));

    // Refresh final states for retry
    let refreshed: StatesMap;
    try {
      refreshed = await updateFinalStates(statesMap);
    } catch {
      throw err; // Can't refresh, re-throw original error
    }
    return validateWithRetries(
      txValidation, cid, refreshed, options, wallets,
      maxRetries, retryDelayMs, ml0Urls, attempt + 1
    );
  }
}

/**
 * Poll an ML0 endpoint until a condition is met.
 * Uses the ML0 custom routes (e.g. /v1/state-machines/{id}, /v1/scripts/{id}).
 */
async function waitForMl0Confirmation(
  ml0BaseUrl: string,
  entityPath: string,
  predicate: (data: unknown) => boolean,
  maxRetries: number,
  retryDelayMs: number,
  label: string,
  log?: FlowLogger
): Promise<void> {
  const url = `${ml0BaseUrl}/data-application/v1/${entityPath}`;
  // Raw read by design: `entityPath` is a caller-supplied dynamic path (fiber / script / registry /
  // asset-state-proof), so no single typed client method spans it; the predicate treats the body as
  // `unknown`. Callers that know the concrete shape (e.g. registry) cast inside their own predicate.
  const client = new HttpClient(url);
  const w = log ? (s: string) => log.write(s) : (s: string) => process.stdout.write(s);
  w(`\n      ⏳ ML0 confirm ${label}`);

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const data = await client.get<unknown>('');
      if (predicate(data)) {
        w(' ✓\n');
        return;
      }
    } catch {
      // Entity may not exist yet — continue polling
    }
    w('.');
    if (attempt < maxRetries) {
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
    }
  }

  w(' ✗\n');
  throw new Error(
    `ML0 confirmation timed out for ${label} at ${url} after ${maxRetries} attempts`
  );
}

/**
 * Minimal wire shape of `GET /v1/commit-index` (onchain-incrementals RFC §3.4) — typed locally
 * until the published SDK ships the CommitIndexResponse type (Phase-3 alignment).
 */
interface CommitIndexWire {
  fiberCommits?: Record<string, { sequenceNumber: number }>;
  assetCommits?: Record<string, unknown>;
}

/** Fetch a node's folded/healed cumulative commit maps (DL1 and ML0 serve the same route). */
async function fetchCommitIndex(baseUrl: string): Promise<CommitIndexWire> {
  const res = await fetch(`${baseUrl}/data-application/v1/commit-index`);
  if (!res.ok) throw new Error(`commit-index HTTP ${res.status}`);
  const body = (await res.json()) as { index?: CommitIndexWire };
  return body.index ?? {};
}

/**
 * Wait until a DL1 node's commit index reflects a fiber commit that matches
 * the expected sequence number (or simply exists, for create steps).
 *
 * OnChain v2 carries only per-batch deltas, so /v1/onchain is no longer a cumulative
 * surface — the DL1's ingestion view is its folded/healed CommitIndex, served at
 * /v1/commit-index (reading it drives the same fold/heal refresh the ingestion gate
 * uses, so this poll observes exactly the state the gate will validate against).
 */
async function waitForDl1Sync(
  dl1BaseUrls: string[],
  fiberId: string,
  expectedSeqNum: number | null,
  maxRetries: number,
  retryDelayMs: number,
  label: string,
  log?: FlowLogger
): Promise<void> {
  const seqLabel = expectedSeqNum === null ? 'exists' : `seq≥${expectedSeqNum}`;
  const w = log ? (s: string) => log.write(s) : (s: string) => process.stdout.write(s);
  w(`      ⏳ DL1 sync ${fiberId.slice(0, 8)}… (${seqLabel}, ${dl1BaseUrls.length} nodes)`);

  // Per-node readiness: a node is ready once its commit index reflects the prior commit.
  // ALL nodes must be ready before we return, because the NEXT sequential transition fans out to
  // every DL1 node and each validates against its OWN cache — a single node still trailing rejects
  // that next update during block consensus, and it gets excluded from every block (no apply, no
  // reject: the update simply never lands). Gating on the slowest node closes that hole.
  const ready = new Array<boolean>(dl1BaseUrls.length).fill(false);

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    await Promise.all(
      dl1BaseUrls.map(async (base, i) => {
        if (ready[i]) return;
        try {
          const commit = (await fetchCommitIndex(base)).fiberCommits?.[fiberId];
          if (!commit) return;
          if (expectedSeqNum === null) {
            ready[i] = true;
          } else if (commit.sequenceNumber >= expectedSeqNum) {
            ready[i] = true;
          }
        } catch {
          // node not ready yet — retry next attempt
        }
      })
    );

    if (ready.every(Boolean)) {
      w(' ✓\n');
      return;
    }
    w('.');
    if (attempt < maxRetries) {
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
    }
  }

  const lagging = dl1BaseUrls.filter((_, i) => !ready[i]).length;
  w(' ✗\n');
  throw new Error(
    `DL1 sync timed out for ${label} after ${maxRetries} attempts ` +
      `(waiting for fiberId=${fiberId} seqNum=${expectedSeqNum ?? 'exists'}; ${lagging}/${dl1BaseUrls.length} nodes still lagging)`
  );
}

/**
 * Wait until every DL1 node's commit index carries an `assetCommits[assetId]` entry — the asset
 * analogue of waitForDl1Sync. An `applyMorphism` is structurally validated at DL1 against the
 * recreated CommitIndex (AssetRules.applyMorphismStructural: unknown asset is a HARD reject), so a
 * `mintAsset` → `applyMorphism` on the same asset races the snapshot's ML0→GL0→DL1 propagation: the
 * morphism reaches DL1 before the mint's commit does and is rejected HTTP 400. Gating on every DL1
 * node having the commit (existence is enough — STAKE/Transfer/Wrap keep the record; the seq bump is
 * checked at combine) closes that hole, exactly as the fiber path does for fiberCommits.
 */
async function waitForDl1AssetSync(
  dl1BaseUrls: string[],
  assetId: string,
  maxRetries: number,
  retryDelayMs: number,
  label: string,
  log?: FlowLogger
): Promise<void> {
  const w = log ? (s: string) => log.write(s) : (s: string) => process.stdout.write(s);
  w(`      ⏳ DL1 asset sync ${assetId.slice(0, 8)}… (${dl1BaseUrls.length} nodes)`);
  const ready = new Array<boolean>(dl1BaseUrls.length).fill(false);
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    await Promise.all(
      dl1BaseUrls.map(async (base, i) => {
        if (ready[i]) return;
        try {
          if ((await fetchCommitIndex(base)).assetCommits?.[assetId] != null) ready[i] = true;
        } catch {
          // node not ready yet — retry next attempt
        }
      })
    );
    if (ready.every(Boolean)) {
      w(' ✓\n');
      return;
    }
    w('.');
    if (attempt < maxRetries) await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
  }
  const lagging = dl1BaseUrls.filter((_, i) => !ready[i]).length;
  w(' ✗\n');
  throw new Error(
    `DL1 asset sync timed out for ${label} after ${maxRetries} attempts ` +
      `(waiting for assetId=${assetId}; ${lagging}/${dl1BaseUrls.length} nodes still lagging)`
  );
}

// ---------------------------------------------------------------------------
// Example discovery
// ---------------------------------------------------------------------------

interface TestStep {
  action: string;
  definition?: string;
  initialData?: string;
  event?: string;
  eventData?: Record<string, unknown>;
  expectedState?: string;
  /** Assert the fiber's `stateData` deep-equals this object after a processEvent step. */
  expectedStateData?: Record<string, unknown>;
  method?: string;
  args?: string;
  expectedResult?: unknown;
  /** Assert this step is REJECTED: 'dl1' (HTTP 400, structural) or 'ml0' (admitted then combine-denied). */
  expectRejected?: 'dl1' | 'ml0';
  /**
   * Run this step concurrently with adjacent `parallel: true` steps (a maximal consecutive run forms
   * one batch). Use ONLY for steps with no inter-dependency (independent setup: registry publishes,
   * fiber creates, independent mints). Omit ⇒ the step runs sequentially with byte-for-byte the same
   * behavior as before. A non-parallel step (incl. `phase`/`economy` markers) bounds each batch.
   */
  parallel?: boolean;

  // ---- Multi-fiber / party / asset extensions (riverdale-economy) ----
  /** On a create step: name the new fiber so later steps can target it (`fiber: "<as>"`). */
  as?: string;
  /** Target a previously-named fiber (alias) — or a raw fiberId — instead of the default session fiber. */
  fiber?: string;
  /** Sign this step with only these wallet names (⇒ they own a created fiber). Omit = all wallets. */
  signers?: string[];
  /** assertState/assertAsset: minimum sequence number the record must have reached. */
  minSequenceNumber?: number;
  /** assertAsset: the asset instance to inspect. */
  assetId?: string;
  /** assertStateProof: the projected `stateData` field to prove (required for that action). */
  field?: string;
  /** assertStateProof: assert the proven projected field deep-equals this value. */
  expectedFieldValue?: unknown;
  /** assertAsset: expected custody holder, e.g. { Fiber: "retailer" } or { Wallet: "carol" }. */
  expectedHolder?: HolderRef;
  /** assertAsset: expected `amount` on the asset record. */
  expectedAmount?: number;
  /** createAssetPolicy: path to the policy body file (+ `name` for the registry confirm). */
  policy?: string;
  name?: string;
  /** mintAsset: path to the mint body file (.ts may resolve holder from context.wallets). */
  mint?: string;
  /** applyMorphism: path to the morphism body file. */
  morphism?: string;

  // ---- Observability (poll-only, no tx) — `phase` headers + `economy` snapshots ----
  /** phase: the banner text. economy: an optional section sub-label. assertAsset: friendly asset name. */
  label?: string;
  /** economy: the parties to render (each resolved by fiber alias or wallet name). */
  parties?: EconomyParty[];
  /** economy: asset id → friendly label map for rendering held instances (GOODS, RVD-loan, …). */
  assets?: EconomyAsset[];

  [key: string]: unknown;
}

interface TestFlow {
  name: string;
  description: string;
  steps: TestStep[];
}

interface Example {
  dir: string;
  name: string;
  description: string;
  type: string;
  scriptFiberId?: string;
  testFlows: TestFlow[];
  [key: string]: unknown;
}

async function discoverExamples(): Promise<Example[]> {
  const dirs = fs
    .readdirSync(examplesDir)
    .filter((f) => fs.statSync(path.join(examplesDir, f)).isDirectory());

  const examples = await Promise.all(
    dirs.map(async (dir): Promise<Example | null> => {
      try {
        const example = (await loadFileOrModule(
          path.join(examplesDir, dir, 'example'),
          {}
        )) as Record<string, unknown>;

        if (!Array.isArray(example.testFlows) || example.testFlows.length === 0) {
          return null;
        }

        return { dir, ...example } as Example;
      } catch {
        return null;
      }
    })
  );

  return examples.filter((e): e is Example => e !== null);
}

// ---------------------------------------------------------------------------
// Flow execution
// ---------------------------------------------------------------------------

async function runFlow(
  example: Example,
  flow: TestFlow,
  env: ReturnType<typeof getMetagraphEnv>,
  wallets: Wallets,
  ml0Urls: string[],
  ml0Env: string[],
  dl1Urls: string[],
  maxRetries: number,
  retryDelayMs: number,
  waitTimeMs: number,
  log?: FlowLogger,
  webhook?: WebhookListener
): Promise<{ passed: boolean; error?: string; failedStep?: number }> {
  const w = log ? (s: string) => log.write(s) : (s: string) => process.stdout.write(s);
  const l = log ? (...a: unknown[]) => log.log(...a) : (...a: unknown[]) => console.log(...a);
  const session = {
    cid: crypto.randomUUID(),
    scriptFiberId: null as string | null,
    // Named multi-fiber registry: alias → fiberId. A `create` step with `as` mints a fresh fiberId
    // and registers it here; any step with `fiber: "<alias>"` resolves to it. Single-fiber flows
    // never touch this map and keep using `session.cid` (full back-compat).
    fibers: {} as Record<string, string>,
    // Previous `economy` snapshot per party label, so the next `economy` step can render deltas
    // (e.g. `inventory 1000→500`) instead of re-printing the absolute value. Observability-only.
    economy: {} as Record<string, PartySnapshot>,
  };
  // Resolve a step's target fiber: a registered alias, else a raw fiberId pass-through (e.g. a
  // spawned child addressed by literal id), else the default session fiber.
  const resolveFiber = (alias?: string): string =>
    !alias ? session.cid : session.fibers[alias] ?? alias;

  // Tag for the per-step buffer loggers used by a `parallel` batch (mirror runOne's `${dir}/${name}`).
  const tag = log?.tag ?? `${example.dir}/${flow.name}`;

  // One step's full body, extracted so a `parallel` batch can run several of these concurrently.
  // It writes via the injected `sw`/`sl` (the flow's live logger when sequential, a per-step buffer
  // when batched) and threads `slog` into the confirmation helpers. It THROWS on failure — the
  // caller (sequential try/catch, or the batch's Promise.allSettled) decides the flow's pass/fail.
  const processStep = async (
    step: TestStep,
    i: number,
    sw: (s: string) => void,
    sl: (...a: unknown[]) => void,
    slog: FlowLogger | undefined
  ): Promise<void> => {
    // ---- Observability annotations (poll-only, no tx; rendered WITHOUT the "[Step N] action…"
    // prefix so they read as section banners / tables). `phase` is pure text; `economy` reads the
    // ML0 checkpoint ONCE and prints a compact economy table. Both early-return like assertState. ----
    if (step.action === 'phase') {
      sl(phaseBanner(String(step.label ?? '')));
      return;
    }
    if (step.action === 'economy') {
      await renderEconomy(step, ml0Urls[0], wallets, resolveFiber, session.economy, sl);
      return;
    }

    const stepLabel = `[Step ${i + 1}/${flow.steps.length}]`;
    sw(`  ${stepLabel} ${step.action}...`);

      let generator: GeneratorFn;
      let validator: ValidatorFn;
      let message: unknown;
      let stepOptions: Record<string, unknown>;

      // Per-step signer subset (party model): the proofs on a message ARE its owners/authorizers, so
      // `signers` decides which party acts. Omit ⇒ all wallets sign (today's single-fiber behavior).
      const signWallets = selectSigners(wallets, step.signers);

      // ---- Poll-only assertions (no transaction) ----
      // assertState observes a fiber changed INDIRECTLY (e.g. by another fiber's cross-fiber
      // `_triggers`); assertAsset observes real on-chain custody after a `_transferAsset` / morphism.
      // Both poll the committed read with the standard retry budget (it trails GL0 finalization).
      if (step.action === 'assertState') {
        const cid = resolveFiber(step.fiber);
        const who = step.fiber ?? cid.slice(0, 8);
        sw(`\n      ⏳ assertState ${who}`);
        const rec = await pollFiberRecord(
          ml0Urls[0],
          cid,
          (r) =>
            !!r &&
            (!step.expectedState || r.currentState === step.expectedState) &&
            (step.minSequenceNumber == null || ((r.sequenceNumber as number) ?? -1) >= step.minSequenceNumber) &&
            (!step.expectedStateData || deepEqual(r.stateData, step.expectedStateData)),
          maxRetries,
          retryDelayMs,
          (s) => sw(s)
        );
        if (!rec) throw new Error(`assertState ${who}: fiber never reached the asserted condition at ML0`);
        if (step.expectedState && rec.currentState !== step.expectedState)
          throw new Error(`assertState ${who}: expected state "${step.expectedState}" but found "${rec.currentState}"`);
        if (step.minSequenceNumber != null && ((rec.sequenceNumber as number) ?? -1) < step.minSequenceNumber)
          throw new Error(`assertState ${who}: expected seq ≥ ${step.minSequenceNumber} but found ${rec.sequenceNumber}`);
        if (step.expectedStateData && !deepEqual(rec.stateData, step.expectedStateData))
          throw new Error(`assertState ${who}: stateData mismatch — got ${JSON.stringify(rec.stateData)} want ${JSON.stringify(step.expectedStateData)}`);
        sw(' ✓');
        // Summarize what was observed (not a bare "OK") so the flow reads as a story.
        sl(` \x1b[32massertState ${who} → ${String(rec.currentState)}\x1b[0m (seq ${Number(rec.sequenceNumber)})`);
        return;
      }

      if (step.action === 'assertAsset') {
        const assetId = step.assetId as string;
        if (!assetId) throw new Error('assertAsset requires an `assetId`');
        sw(`\n      ⏳ assertAsset ${assetId.slice(0, 8)}`);
        const rec = await pollAssetRecord(
          ml0Urls[0],
          assetId,
          (r) =>
            !!r &&
            (!step.expectedHolder || holderMatches(r.holder, step.expectedHolder, resolveFiber, wallets)) &&
            (step.expectedAmount == null || Number(r.amount) === step.expectedAmount) &&
            (step.minSequenceNumber == null || ((r.sequenceNumber as number) ?? -1) >= step.minSequenceNumber),
          maxRetries,
          retryDelayMs,
          (s) => sw(s)
        );
        if (!rec) throw new Error(`assertAsset ${assetId}: no committed custody record reaching the asserted condition`);
        if (step.expectedHolder && !holderMatches(rec.holder, step.expectedHolder, resolveFiber, wallets))
          throw new Error(`assertAsset ${assetId}: holder mismatch — got ${JSON.stringify(rec.holder)} want ${JSON.stringify(step.expectedHolder)}`);
        if (step.expectedAmount != null && Number(rec.amount) !== step.expectedAmount)
          throw new Error(`assertAsset ${assetId}: expected amount ${step.expectedAmount} but found ${rec.amount}`);
        sw(' ✓');
        // Summarize the observed custody: friendly asset name (step.label) or id8, holder, amount.
        const assetName = (step.label as string | undefined) ?? assetId.slice(0, 8);
        const holderLabel = step.expectedHolder
          ? 'Fiber' in step.expectedHolder
            ? `Fiber(${step.expectedHolder.Fiber})`
            : `Wallet(${step.expectedHolder.Wallet})`
          : (() => {
              const h = rec.holder as { Fiber?: { fiberId?: string }; Wallet?: { address?: string } } | undefined;
              if (h?.Fiber?.fiberId) return `Fiber(${h.Fiber.fiberId.slice(0, 8)})`;
              if (h?.Wallet?.address) return `Wallet(${h.Wallet.address.slice(0, 8)})`;
              return '?';
            })();
        sl(` \x1b[32massertAsset ${assetName} → ${holderLabel}\x1b[0m ×${Number(rec.amount)}`);
        return;
      }

      // assertStateProof: the LIGHT-CLIENT audit check. Fetches the committed state-proof for a
      // fiber (`/state-machines/{id}/state-proof?field=`) and verifies the Merkle-Patricia
      // inclusion proof CLIENT-SIDE against `mptRoot`, whose combined hash IS the snapshot's
      // consensus-signed `calculatedStateProof` — no trust in the serving node. The fold mirrors
      // metakit's MerklePatriciaVerifier exactly: walk the witness ROOT-first (response order is
      // leaf-first), each node's digest = sha256(typePrefix ++ utf8(JCS(contents))) — the SUBTYPE (contents-only) encoding — with
      // prefixes leaf=0x00 / branch=0x01 / extension=0x02; a Branch consumes one path nibble
      // via pathsDigest, an Extension consumes its shared nibbles; the terminal Leaf must match
      // the remaining path and bind dataDigest == sha256(JCS(record)). The trie path of a
      // committed key is hex(utf8("fiber/<id>")) (CommitKey.toHex).
      // Step fields: `fiber`, `field` (required), optional `expectedFieldValue`.
      if (step.action === 'assertStateProof') {
        const cid = resolveFiber(step.fiber);
        const who = step.fiber ?? cid.slice(0, 8);
        const field = step.field as string;
        if (!field) throw new Error('assertStateProof requires a `field` (projected stateData field)');
        sw(`\n      ⏳ assertStateProof ${who}.${field}`);
        const client = new OttoMetagraphClient({ ml0Url: ml0Urls[0] });
        // Poll: the committed read trails GL0 finalization by a snapshot or two.
        let proofResp: Awaited<ReturnType<typeof client.getStateMachineStateProof>> | null = null;
        for (let attempt = 0; attempt < maxRetries; attempt++) {
          try {
            const r = await client.getStateMachineStateProof(cid, field);
            if (r?.record && r.fieldValue !== undefined && r.fieldValue !== null) { proofResp = r; break; }
          } catch { /* 404 until committed */ }
          sw('.');
          await new Promise((res) => setTimeout(res, retryDelayMs));
        }
        if (!proofResp) throw new Error(`assertStateProof ${who}: no committed state-proof with field "${field}"`);
        const jcsSha = (value: unknown, prefix?: number): string => {
          const h = createHash('sha256');
          if (prefix !== undefined) h.update(Buffer.from([prefix]));
          return h.update(Buffer.from(canonicalize(value), 'utf8')).digest('hex');
        };
        const keyHex = Buffer.from(`fiber/${cid}`, 'utf8').toString('hex');
        const rootHex = String(proofResp.mptRoot).replace(/^0x/, '').toLowerCase();
        const proof = proofResp.proof as {
          path: string;
          witness: Array<{ type: string; contents: Record<string, unknown> }>;
        };
        const fail = (why: string): never => {
          throw new Error(
            `assertStateProof ${who}: inclusion proof REJECTED (${why}) against mptRoot ${rootHex.slice(0, 16)}… (ordinal ${proofResp.ordinal})`
          );
        };
        if (proof.path.toLowerCase() !== keyHex) fail(`trie path != hex(utf8("fiber/${cid}"))`);
        // Fold root → leaf (metakit MerklePatriciaVerifier semantics).
        let digest = rootHex;
        let path = proof.path.toLowerCase();
        const nodes = [...proof.witness].reverse();
        for (const [ni, node] of nodes.entries()) {
          const isLast = ni === nodes.length - 1;
          if (node.type === 'Branch') {
            if (jcsSha(node.contents, 1) !== digest) fail(`branch commitment mismatch at depth ${ni}`);
            const child = (node.contents.pathsDigest as Record<string, string>)[path[0]];
            if (!child) fail(`branch has no child at nibble '${path[0]}' (depth ${ni})`);
            digest = child.toLowerCase();
            path = path.slice(1);
          } else if (node.type === 'Extension') {
            if (jcsSha(node.contents, 2) !== digest) fail(`extension commitment mismatch at depth ${ni}`);
            const shared = String(node.contents.shared ?? '');
            path = path.slice(shared.length);
            digest = String(node.contents.childDigest).toLowerCase();
          } else if (node.type === 'Leaf') {
            if (!isLast) fail('leaf before end of witness');
            if (jcsSha(node.contents, 0) !== digest) fail('leaf commitment mismatch');
            if (String(node.contents.remaining).toLowerCase() !== path) fail('leaf remaining-path mismatch');
            if (String(node.contents.dataDigest).toLowerCase() !== jcsSha(proofResp.record))
              fail('leaf dataDigest != sha256(JCS(record))');
          } else fail(`unknown witness node type '${node.type}'`);
        }
        if (nodes[nodes.length - 1]?.type !== 'Leaf') fail('witness does not terminate in a leaf');
        if (step.expectedFieldValue !== undefined && !deepEqual(proofResp.fieldValue, step.expectedFieldValue))
          throw new Error(
            `assertStateProof ${who}: field "${field}" mismatch — got ${JSON.stringify(proofResp.fieldValue)} want ${JSON.stringify(step.expectedFieldValue)}`
          );
        sw(' ✓');
        sl(
          ` \x1b[32massertStateProof ${who}.${field}\x1b[0m verified vs committedRoot ${String(proofResp.committedRoot).slice(0, 12)}… @ ordinal ${proofResp.ordinal}`
        );
        return;
      }

      // ---- Registry ops (publishVersion / setVersionStatus / registerAlias) ----
      // Non-sequenced: confirm via /registry/{name}; no seq number or DL1-fiberCommit sync.
      const REGISTRY_LIBS: Record<string, string> = {
        publishVersion: './lib/registry/publishVersion.ts',
        setVersionStatus: './lib/registry/setVersionStatus.ts',
        registerAlias: './lib/registry/registerAlias.ts',
      };
      if (REGISTRY_LIBS[step.action]) {
        const regLib = await import(REGISTRY_LIBS[step.action]);
        const regName = step.name as string;
        const regOptions: Record<string, unknown> = {
          ...step,
          // registerAlias can target a named fiber (`fiber: "<alias>"`); defaults to the session fiber.
          targetFiberId: (step.targetFiberId as string) ?? resolveFiber(step.fiber),
          ...(step.definition ? { definition: path.join(examplesDir, example.dir, step.definition) } : {}),
          ...(step.schemaShape ? { schemaShape: path.join(examplesDir, example.dir, step.schemaShape as string) } : {}),
        };
        const regMessage = regLib.generator({ cid: resolveFiber(step.fiber), wallets: signWallets, options: regOptions });
        const regPath = `registry/${encodeURIComponent(regName)}`;
        // Did the op land in the registry? Typed predicate over the `/registry/{name}` RegistryEntry —
        // a drift in the target/version-lineage shape fails the typecheck instead of reading undefined.
        const landed = (e: RegistryEntry | null): boolean => {
          const target = e?.target;
          // Narrow the RegistryTarget union to the SchemaPackage variant to reach its version lineage.
          const vs = target && 'SchemaPackage' in target ? target.SchemaPackage.versions.versions : undefined;
          if (step.action === 'publishVersion') return !!vs && (step.version as string) in vs;
          if (step.action === 'setVersionStatus') return vs?.[step.version as string]?.status === step.status;
          return e != null; // registerAlias
        };

        if (step.expectRejected === 'dl1') {
          // Structural reject (e.g. reserved name) -> the /data POST should fail with HTTP 400.
          let rejected = false;
          try {
            await sendSignedUpdate(regMessage, signWallets, dl1Urls);
          } catch (err) {
            rejected = (err as Error).message.includes('400');
          }
          if (!rejected) throw new Error(`expected DL1 to reject ${step.action} ${regName} (HTTP 400), but it was accepted`);
          sl(' \x1b[32mOK (DL1 rejected)\x1b[0m');
          return;
        }
        if (step.expectRejected === 'ml0') {
          // Admitted by DL1 (structurally valid) but rejected at ML0 combine -> never lands.
          await sendSignedUpdate(regMessage, signWallets, dl1Urls).catch(() => undefined);
          const client = new OttoMetagraphClient({ ml0Url: ml0Urls[0] });
          for (let attempt = 0; attempt < 8; attempt++) {
            await new Promise((r) => setTimeout(r, retryDelayMs));
            let data: RegistryEntry | null = null;
            try {
              data = await client.getRegistryEntry(regName);
            } catch {
              /* entry may not exist — fine */
            }
            if (landed(data)) throw new Error(`expected ML0 to reject ${step.action} ${regName}, but it landed`);
          }
          sl(' \x1b[32mOK (ML0 rejected, state unchanged)\x1b[0m');
          return;
        }

        const regInitial = await getInitialStates(ml0Env);
        // Registry ops are one-shot and confirmed by a poll-only read (no sequence number, no
        // resubmit). A VALID registry update dropped by an all-or-nothing data-block poison (a peer
        // fiber's invalid update voids the whole block) therefore never re-lands and the poll just
        // times out — which is what sinks registerAlias at the high-parallel-load tail of a flow
        // while the same path succeeds for the early publishVersion steps. Re-send on timeout to
        // recover, but skip the re-send once it has landed: re-publishing an applied version is
        // itself invalid (append-only / CidAlreadyExists) and would poison the next block.
        const regResubmits = Number(process.env.E2E_MAX_RESUBMITS) || 3;
        const regBudget = Math.max(4, Math.ceil(maxRetries / (regResubmits + 1)));
        let regConfirmed = false;
        for (let attempt = 0; attempt <= regResubmits && !regConfirmed; attempt++) {
          if (attempt > 0) {
            try {
              const cur = await new OttoMetagraphClient({ ml0Url: ml0Urls[0] }).getRegistryEntry(regName);
              if (landed(cur)) {
                regConfirmed = true;
                break;
              }
            } catch {
              // not in the registry yet → fall through and re-send
            }
          }
          await sendSignedUpdate(regMessage, signWallets, dl1Urls);
          try {
            await waitForMl0Confirmation(
              ml0Urls[0],
              regPath,
              // The generic entityPath poller hands back an untyped body; in this context it IS the
              // `/registry/{name}` RegistryEntry, so cast at the boundary and reuse the typed predicate.
              (d) => landed(d as RegistryEntry | null),
              regBudget,
              retryDelayMs,
              `${step.action} ${regName}`,
              slog
            );
            regConfirmed = true;
          } catch {
            // timed out this round → the loop re-sends (unless it has since landed)
          }
        }
        if (!regConfirmed) {
          throw new Error(
            `ML0 confirmation failed for ${step.action} ${regName}: not in registry after ` +
              `${regResubmits + 1} attempts`
          );
        }
        await validateWithRetries(regLib.validator, resolveFiber(step.fiber), regInitial, regOptions, wallets, maxRetries, retryDelayMs, ml0Urls);
        sl(' \x1b[32mOK\x1b[0m');
        return;
      }

      const loadContext = {
        wallets,
        session,
        eventData: step.eventData,
      };

      // ---- Asset ops (createAssetPolicy / mintAsset / applyMorphism) ----
      // First e2e to exercise REAL asset custody. Plain-JSON messages (the chain JAR decodes the
      // variants; no SDK asset builder dep). Confirmed via the registry (policy package) or the asset
      // state-proof read (instance), not a fiber sequence bump.
      const ASSET_ACTIONS = new Set(['createAssetPolicy', 'mintAsset', 'applyMorphism']);
      if (ASSET_ACTIONS.has(step.action)) {
        let assetId = step.assetId as string | undefined;
        let assetMsg: unknown;
        let morphismKind: string | undefined;
        let fracFirstShard: string | undefined;
        if (step.action === 'createAssetPolicy') {
          const policy = (await loadFileOrModule(path.join(examplesDir, example.dir, step.policy!), loadContext)) as Record<string, unknown>;
          const lib = await import('./lib/asset/createAssetPolicy.ts');
          assetMsg = lib.generator({ wallets: signWallets, options: { policy } });
        } else if (step.action === 'mintAsset') {
          const mint = (await loadFileOrModule(path.join(examplesDir, example.dir, step.mint!), loadContext)) as Record<string, unknown>;
          assetId = (mint.assetId as string) ?? assetId;
          const lib = await import('./lib/asset/mintAsset.ts');
          assetMsg = lib.generator({ wallets: signWallets, options: { mint } });
        } else {
          const morphism = (await loadFileOrModule(path.join(examplesDir, example.dir, step.morphism!), loadContext)) as Record<string, unknown>;
          assetId = (morphism.assetId as string) ?? assetId;
          morphismKind = String(morphism.kind ?? '').toUpperCase();
          fracFirstShard = Array.isArray(morphism.shardIds) ? String((morphism.shardIds as unknown[])[0]) : undefined;
          // Morphisms are sequenced by (assetId, targetSequenceNumber): target the asset's current seq.
          // Raw read by design (as are the other `assets/{id}/state-proof` reads in this asset block):
          // the whole `AssetRecord` is pulled from a FIELD-LESS state-proof, but the typed
          // `getAssetStateProof(assetId, field)` unconditionally appends `?field=` and cannot fetch the
          // bare record — so there is no drop-in typed equivalent for this endpoint shape.
          let curSeq = 0;
          try {
            const resp = (await new HttpClient(`${ml0Urls[0]}/data-application/v1/assets/${assetId}/state-proof`).get<unknown>('')) as { record?: { sequenceNumber?: number } } | null;
            curSeq = resp?.record?.sequenceNumber ?? 0;
          } catch { /* not yet committed */ }
          const lib = await import('./lib/asset/applyMorphism.ts');
          assetMsg = lib.generator({ wallets: signWallets, options: { morphism, targetSequenceNumber: curSeq } });
        }

        // Consuming morphisms REMOVE the source record, so they can't be confirmed by a source-seq
        // advance: FRACTIONALIZE confirms via the first output shard's EXISTENCE; BURN/DECOMPOSE via the
        // source's ABSENCE (handled below). Non-consuming (Stake/Transfer/Wrap) keep seq-advance.
        const fractionalize = step.action === 'applyMorphism' && morphismKind === 'FRACTIONALIZE';
        const burnAbsence = step.action === 'applyMorphism' && (morphismKind === 'BURN' || morphismKind === 'DECOMPOSE');
        const policyName = step.name as string | undefined;
        const confirmPath =
          step.action === 'createAssetPolicy'
            ? `registry/${encodeURIComponent(policyName ?? '')}`
            : fractionalize && fracFirstShard
              ? `assets/${fracFirstShard}/state-proof`
              : `assets/${assetId}/state-proof`;
        const confirmUrl = `${ml0Urls[0]}/data-application/v1/${confirmPath}`;

        // Pre-send asset sequence (applyMorphism confirms on a seq ADVANCE).
        let preAssetSeq = -1;
        if (step.action === 'applyMorphism') {
          try {
            const resp = (await new HttpClient(confirmUrl).get<unknown>('')) as { record?: { sequenceNumber?: number } } | null;
            preAssetSeq = resp?.record?.sequenceNumber ?? -1;
          } catch { /* absent */ }
        }
        const landed = (d: unknown): boolean => {
          if (step.action === 'createAssetPolicy') {
            // Variant-agnostic: any registry target carrying a non-empty versions map (AssetPolicyPackage).
            const target = (d as { target?: Record<string, unknown> } | null)?.target;
            if (!target || typeof target !== 'object') return false;
            return Object.values(target).some((v) => {
              const vs = (v as { versions?: { versions?: Record<string, unknown> } })?.versions?.versions;
              return !!vs && Object.keys(vs).length > 0;
            });
          }
          const r = (d as { record?: { sequenceNumber?: number } } | null)?.record;
          if (!r) return false;
          if (step.action === 'mintAsset') return true; // existence ⇒ minted
          if (fractionalize) return true; // confirmPath is the first shard ⇒ its existence = fractionalized
          return (r.sequenceNumber ?? -1) > preAssetSeq; // non-consuming applyMorphism ⇒ source seq advanced
        };

        if (step.expectRejected === 'dl1') {
          let rejected = false;
          try { await sendSignedUpdate(assetMsg, signWallets, dl1Urls); }
          catch (err) { rejected = (err as Error).message.includes('400'); }
          if (!rejected) throw new Error(`expected DL1 to reject ${step.action} (HTTP 400), but it was accepted`);
          sl(' \x1b[32mOK (DL1 rejected)\x1b[0m');
          return;
        }
        if (step.expectRejected === 'ml0') {
          await sendSignedUpdate(assetMsg, signWallets, dl1Urls).catch(() => undefined);
          for (let attempt = 0; attempt < 8; attempt++) {
            await new Promise((r) => setTimeout(r, retryDelayMs));
            let data: unknown = null;
            try { data = await new HttpClient(confirmUrl).get<unknown>(''); } catch { /* absent ⇒ fine */ }
            if (landed(data)) throw new Error(`expected ML0 to reject ${step.action} (${assetId ?? policyName}), but it landed`);
          }
          sl(' \x1b[32mOK (ML0 rejected, state unchanged)\x1b[0m');
          return;
        }

        if (burnAbsence) {
          // BURN/DECOMPOSE: the source record is REMOVED. Confirm by polling the source until it is
          // ABSENT — it existed pre-send (minted + asserted), so an exists→404 transition means the
          // morphism committed. (A consuming morphism can never satisfy the source-seq-advance predicate.)
          sw(`\n      ⏳ ML0 confirm ${step.action} ${(assetId ?? '').slice(0, 8)} → source removed`);
          const resubmitsB = Number(process.env.E2E_MAX_RESUBMITS) || 3;
          const budgetB = Math.max(4, Math.ceil(maxRetries / (resubmitsB + 1)));
          let gone = false;
          for (let attempt = 0; attempt <= resubmitsB && !gone; attempt++) {
            if (attempt > 0) {
              // already removed by a prior send? (don't re-burn a gone asset — it would reject)
              try { await new HttpClient(confirmUrl).get<unknown>(''); } catch { gone = true; break; }
            }
            await sendSignedUpdate(assetMsg, signWallets, dl1Urls).catch(() => undefined);
            for (let p = 0; p < budgetB && !gone; p++) {
              await new Promise((r) => setTimeout(r, retryDelayMs));
              try { await new HttpClient(confirmUrl).get<unknown>(''); sw('.'); } catch { gone = true; }
            }
          }
          if (!gone) { sw(' ✗\n'); throw new Error(`ML0 confirmation failed for ${step.action} (${assetId}): source still present (not removed)`); }
          sw(' ✓\n');
          sl(' \x1b[32mOK\x1b[0m');
          return;
        }

        // Send + confirm with a resubmit budget (committed reads trail GL0 finalization; an
        // all-or-nothing block poison can drop a valid update, so re-send until it lands).
        const resubmits = Number(process.env.E2E_MAX_RESUBMITS) || 3;
        const budget = Math.max(4, Math.ceil(maxRetries / (resubmits + 1)));
        let confirmed = false;
        for (let attempt = 0; attempt <= resubmits && !confirmed; attempt++) {
          if (attempt > 0) {
            try {
              if (landed(await new HttpClient(confirmUrl).get<unknown>(''))) { confirmed = true; break; }
            } catch { /* fall through and re-send */ }
          }
          await sendSignedUpdate(assetMsg, signWallets, dl1Urls);
          try {
            await waitForMl0Confirmation(ml0Urls[0], confirmPath, landed, budget, retryDelayMs, `${step.action} ${assetId ?? policyName ?? ''}`, slog);
            confirmed = true;
          } catch { /* re-send unless it has since landed */ }
        }
        if (!confirmed) throw new Error(`ML0 confirmation failed for ${step.action} (${assetId ?? policyName})`);
        // After a mint, gate on every DL1 node ingesting the new assetCommit before the next step — a
        // subsequent applyMorphism on this asset is structurally validated at DL1 against OnChain.assetCommits
        // and would 400 "unknown asset" if it raced ahead of the mint's ML0→GL0→DL1 propagation.
        if (step.action === 'mintAsset' && assetId) {
          await waitForDl1AssetSync(dl1Urls, assetId, maxRetries, retryDelayMs, `${step.action} ${assetId}`, slog);
        }
        sl(' \x1b[32mOK\x1b[0m');
        return;
      }

      // Determine which fiber this step targets and fetch its current sequence number
      // Script actions (createScript, invokeScript, invoke) use the /scripts/ endpoint
      const isScriptStep =
        (step.action as string).includes('Script') ||
        step.action === 'invoke';
      const isCreateStep =
        step.action === 'create' ||
        step.action === 'createStateMachine' ||
        step.action === 'createScript';

      // Resolve this step's target fiber:
      //  - createScript: scriptFiberId, assigned inside the switch (recomputed after it).
      //  - state-machine create with `as`: mint a fresh fiberId and register the alias (multi-fiber).
      //  - state-machine create without `as`: the default session fiber (single-fiber back-compat).
      //  - any other step: the `fiber` alias (or raw id), else the session fiber.
      let activeCid: string;
      if (isScriptStep) {
        activeCid = session.scriptFiberId!;
      } else if (isCreateStep && step.as) {
        activeCid = crypto.randomUUID();
        session.fibers[step.as] = activeCid;
      } else {
        activeCid = resolveFiber(step.fiber);
      }
      let entityPath = isScriptStep
        ? `scripts/${activeCid}`
        : `state-machines/${activeCid}`;

      let preSendSeqNum = -1;
      if (!isCreateStep) {
        try {
          const existing = await readFiberRecord(ml0Urls[0], activeCid, isScriptStep);
          preSendSeqNum = existing?.sequenceNumber ?? -1;
        } catch {
          // Entity doesn't exist yet (expected for create steps)
        }
      }

      switch (step.action) {
        case 'create':
        case 'createStateMachine': {
          const definition = await loadFileOrModule(
            path.join(examplesDir, example.dir, step.definition!),
            loadContext
          );
          const initialData = await loadFileOrModule(
            path.join(examplesDir, example.dir, step.initialData!),
            loadContext
          );

          // schemaRef (optional) binds the new fiber to a registered package version (verified binding).
          stepOptions = { definition, initialData, schemaRef: step.schemaRef };

          const libModule = await import('./lib/state-machine/createFiber.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: activeCid,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'createScript': {
          session.scriptFiberId = (example.scriptFiberId as string) || crypto.randomUUID();
          const definition = await loadFileOrModule(
            path.join(examplesDir, example.dir, step.definition!),
            loadContext
          );

          stepOptions = { scriptDefinition: definition };

          const libModule = await import('./lib/script/createScript.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: session.scriptFiberId,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'processEvent': {
          let eventData = (await loadFileOrModule(
            path.join(examplesDir, example.dir, step.event!),
            loadContext
          )) as Record<string, unknown>;

          if (step.eventData) {
            eventData = {
              ...eventData,
              payload: {
                ...(eventData.payload as Record<string, unknown>),
                ...(step.eventData as Record<string, unknown>),
              },
            };
          }

          stepOptions = {
            eventData,
            expectedState: step.expectedState,
            expectedStateData: step.expectedStateData,
            targetSequenceNumber: preSendSeqNum >= 0 ? preSendSeqNum : 0,
          };

          const libModule = await import('./lib/state-machine/processEvent.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: activeCid,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'invoke': {
          let args: unknown = null;
          if (step.args) {
            args = await loadFileOrModule(
              path.join(examplesDir, example.dir, step.args),
              loadContext
            );
          }

          stepOptions = {
            method: step.method,
            args,
            expectedResult: step.expectedResult,
            targetSequenceNumber: preSendSeqNum >= 0 ? preSendSeqNum : 0,
          };

          const libModule = await import('./lib/script/invokeScript.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: session.scriptFiberId!,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'upgradeFiber': {
          // Upgrade the session fiber to another registered version of the same package (#27),
          // optionally migrating prior state. Sequenced: bumps the fiber's sequence number.
          stepOptions = {
            targetRef: step.targetRef,
            newDefinition: path.join(examplesDir, example.dir, step.newDefinition as string),
            ...(step.migration ? { migration: path.join(examplesDir, example.dir, step.migration as string) } : {}),
            targetSequenceNumber: preSendSeqNum >= 0 ? preSendSeqNum : 0,
          };
          const libModule = await import('./lib/state-machine/upgradeFiber.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({ cid: activeCid, wallets, options: stepOptions });
          break;
        }

        case 'archiveStateMachine': {
          // Sequenced, but archive flips status to ARCHIVED without bumping the sequence number
          // (see the confirmation predicate below, which special-cases this).
          stepOptions = { targetSequenceNumber: preSendSeqNum >= 0 ? preSendSeqNum : 0 };
          const libModule = await import('./lib/state-machine/archiveFiber.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({ cid: activeCid, wallets, options: stepOptions });
          break;
        }

        default:
          throw new Error(`Unknown action: ${step.action}`);
      }

      // Re-compute activeCid/entityPath after the switch ONLY for createScript, which assigns
      // session.scriptFiberId inside the switch. State-machine creates already resolved activeCid
      // (a minted `as` fiber or the session fiber) before the switch, so leave those intact.
      if (isCreateStep && isScriptStep) {
        activeCid = session.scriptFiberId!;
        entityPath = `scripts/${activeCid}`;
      }

      // Snapshot initial state before sending
      const initialStates = await getInitialStates(ml0Env);

      // Send transaction and wait for ML0 confirmation using ordinal-based tracking.
      // If N ordinals pass without the transaction appearing in state, automatically
      // regenerate and resubmit. This adapts to ML0's actual consensus speed.
      const sendToNodes = async (msg: unknown) => {
        await sendSignedUpdate(msg, signWallets, dl1Urls);
      };

      // Helper to regenerate message with fresh sequence number
      const regenerateMessage = async () => {
        if (!isCreateStep) {
          try {
            const existing = await readFiberRecord(ml0Urls[0], activeCid, isScriptStep);
            const freshSeqNum = existing?.sequenceNumber ?? -1;
            if (freshSeqNum >= 0) {
              (stepOptions as Record<string, unknown>).targetSequenceNumber = freshSeqNum;
            }
          } catch {
            // Entity might not exist yet — keep current options
          }
        }
        return generator!({
          cid: activeCid,
          wallets,
          options: stepOptions!,
        });
      };

      // Initial send (with basic retry for immediate DL1 rejections)
      let currentMessage = message;
      let sendSuccess = false;
      for (let sendAttempt = 0; sendAttempt < 3; sendAttempt++) {
        try {
          await sendToNodes(currentMessage);
          sendSuccess = true;
          break;
        } catch (sendErr) {
          const errMsg = (sendErr as Error).message;
          if (!errMsg.includes('400') || sendAttempt >= 2) {
            throw sendErr;
          }
          sw(` (send retry)...`);
          await new Promise((r) => setTimeout(r, 1000));
          currentMessage = await regenerateMessage();
        }
      }

      if (!sendSuccess) {
        throw new Error(`Failed to send transaction for ${step.action}`);
      }

      // Combine-rejection path (e.g. a guard-denied processEvent): the update is admitted by DL1 but
      // rejected at ML0 combine, which leaves the fiber UNMUTATED (Combiner.scala appends a
      // RejectionReceipt to the snapshot, not the fiber). There is no sequence bump to confirm, so we
      // let several ordinals pass and assert the fiber's sequence number did NOT advance.
      if (step.expectRejected === 'ml0') {
        await waitForOrdinalAdvance(ml0Urls[0], 4, {
          label: `${step.action} reject settle on ${activeCid}`,
          log: slog ? { write: (s: string) => slog.write(s) } : undefined,
        });
        let afterSeq = -1;
        try {
          const rec = await readFiberRecord(ml0Urls[0], activeCid, isScriptStep);
          afterSeq = rec?.sequenceNumber ?? -1;
        } catch {
          // The fiber may legitimately not exist (e.g. a rejected create) — treat as unchanged.
        }
        if (afterSeq > preSendSeqNum) {
          throw new Error(
            `expected ML0 to reject ${step.action} on ${activeCid} (guard denied), but the fiber advanced past seq ${preSendSeqNum} to ${afterSeq}`
          );
        }
        sl(' \x1b[32mOK (ML0 rejected, state unchanged)\x1b[0m');
        return;
      }

      // Ordinal-based confirmation with auto-resubmit. Capture the ordinal at send time so the
      // webhook rejection match ignores any stale rejection carried over from an earlier step.
      const sentOrdinal = webhook?.latestOrdinal ?? 0;
      await waitForOrdinalConfirmation({
        ml0BaseUrl: ml0Urls[0],
        entityPath,
        predicate: (data) => {
          if (!data || typeof data !== 'object') return false;
          const record = data as { sequenceNumber?: number; status?: string };
          if (isCreateStep) {
            return record.status === 'ACTIVE';
          }
          // Archive flips status to ARCHIVED without bumping the sequence number.
          if (step.action === 'archiveStateMachine') {
            return record.status === 'ARCHIVED';
          }
          // For transitions/invocations/upgrades: sequenceNumber must have increased
          return (record.sequenceNumber ?? -1) > preSendSeqNum;
        },
        resubmit: async () => {
          // Re-sending an ALREADY-APPLIED update is the dominant cause of the parallel-flow failure:
          // the duplicate lands in a shared all-or-nothing data block as CidAlreadyExists (create),
          // NoTransitionForEvent (the fiber's state already advanced past this event), or
          // SequenceNumberMismatch (its targetSeq is now behind the fiber's commit). ANY such invalid
          // update fails the ENTIRE block — dropping every other fiber bundled in it, whose runners
          // then resubmit and re-poison: a cascade that sinks otherwise-valid flows. So only re-send
          // when the update is genuinely still in flight, never when it has already taken effect.
          //
          // Two guards, because the committed read trails GL0 finalization (ML0Service sets the read
          // route from reader.committed, so an applied update stays invisible for a few ordinals):
          //   1. committed read — catches applications old enough to have finalized.
          try {
            const rec = await readFiberRecord(ml0Urls[0], activeCid, isScriptStep);
            const landed = isCreateStep
              ? rec?.status === 'ACTIVE'
              : (rec?.sequenceNumber ?? -1) > preSendSeqNum;
            if (landed) return;
          } catch {
            // entity not found / read error → fall through to the rejection guard
          }
          //   2. rejection webhook (NON-lagging) — ML0 dispatches transaction.rejected the instant our
          //      update (or a prior resubmit of it) is rejected. For a valid flow that happens ONLY
          //      because the fiber already advanced past our target (the already-applied case). A
          //      valid update merely dropped by an OTHER fiber's poison is itself valid → no rejection
          //      → it correctly still re-sends. This is what catches the read-lag window the committed
          //      poll above misses.
          if (
            webhook?.active &&
            webhook.findRejection({ fiberId: activeCid, sinceOrdinal: sentOrdinal })
          ) {
            return;
          }
          const freshMessage = await regenerateMessage();
          await sendToNodes(freshMessage);
        },
        ordinalThreshold: Number(process.env.E2E_ORDINAL_THRESHOLD) || 5,
        maxResubmits: Number(process.env.E2E_MAX_RESUBMITS) || 3,
        pollIntervalMs: 2000,
        // No fixed wall-clock cap: a slow-but-advancing chain gets its full ordinal budget (default
        // 5 × 4 = 20 ordinals; the heavy CI lane raises it via E2E_ORDINAL_THRESHOLD/E2E_MAX_RESUBMITS).
        // The stall gate fails fast (120s) only if ML0 stops producing
        // snapshots entirely — i.e. genuine consensus death, not mere slowness under CI load.
        stallTimeoutMs: 120000,
        label: `${step.action} on ${activeCid}`,
        log: slog ? { write: (s: string) => slog.write(s) } : undefined,
        // Webhook-driven: re-check the instant a snapshot finalizes (read state is fresh then), and
        // fail fast with the chain's reason if this exact update is rejected.
        waitNextSnapshot: webhook?.active ? (ms) => webhook!.waitNextSnapshot(ms) : undefined,
        checkRejection: webhook?.active
          ? () => {
              const r = webhook!.findRejection({
                fiberId: activeCid,
                targetSeq: isCreateStep ? null : preSendSeqNum,
                sinceOrdinal: sentOrdinal,
              });
              if (!r) return undefined;
              // Redundant-rejection = success. The chain only rejected our update because the effect
              // already committed: for a create, the fiber now exists (actualSeq present); for a
              // transition, the fiber advanced past our target (actual > preSendSeqNum). This is the
              // non-lagging confirmation that beats the GL0 read lag under contention. A genuine
              // failure (or a still-missing predecessor) falls through to the existing throw.
              const alreadyApplied = isCreateStep
                ? r.actualSequenceNumber != null
                : r.actualSequenceNumber != null && r.actualSequenceNumber > preSendSeqNum;
              return { ordinal: r.ordinal, alreadyApplied, errors: r.errors };
            }
          : undefined,
      });

      // Wait for DL1 OnChain state to catch up with ML0.
      // After ML0 confirms the step, the snapshot still needs to propagate
      // through GL0 → DL1 before the DL1's validation cache reflects the
      // new fiber commit. Without this, the next step's DL1 send may fail
      // with FiberIdNotFound (create) or SequenceNumberMismatch (mutation).
      {
        // Fetch the expected sequence number from ML0 (the source of truth)
        let expectedSeqNum: number | null = null;
        try {
          const record = await readFiberRecord(ml0Urls[0], activeCid, isScriptStep);
          expectedSeqNum = record?.sequenceNumber ?? null;
        } catch {
          // If we can't fetch from ML0, fall back to existence check only
        }

        await waitForDl1Sync(
          dl1Urls,
          activeCid,
          expectedSeqNum,
          maxRetries,
          retryDelayMs,
          `${step.action} DL1 sync for ${activeCid}`,
          slog
        );
      }

      // Validate
      const validationOptions = {
        ...stepOptions!,
        expectedState: step.expectedState,
      };
      await validateWithRetries(
        validator!,
        activeCid,
        initialStates,
        validationOptions,
        wallets,
        maxRetries,
        retryDelayMs,
        ml0Urls
      );

      sl(' \x1b[32mOK\x1b[0m');
  };

  // Batch-aware driver. A maximal run of consecutive `parallel: true` steps runs concurrently to
  // cut wall-clock; every other step runs exactly as before (same w/l/log, same try/catch →
  // {passed:false, failedStep:i+1}), so sequential behavior is byte-for-byte unchanged.
  for (let i = 0; i < flow.steps.length; i++) {
    if (flow.steps[i].parallel) {
      let j = i;
      while (j < flow.steps.length && flow.steps[j].parallel) j++;
      const batch = flow.steps.slice(i, j);
      l(`  ── parallel batch: ${batch.length} steps (${batch.map((s) => s.action).join(', ')}) ──`);
      // Each step buffers to its OWN logger so concurrent output doesn't interleave; flush in step order.
      const buffers = batch.map(() => new FlowLogger(tag, false));
      const settled = await Promise.allSettled(
        batch.map((s, k) =>
          processStep(s, i + k, (x) => buffers[k].write(x), (...a) => buffers[k].log(...a), buffers[k])
        )
      );
      for (const b of buffers) b.flush(); // ordered, contiguous per-step output
      const failedAt = settled.findIndex((r) => r.status === 'rejected');
      if (failedAt >= 0) {
        const reason = (settled[failedAt] as PromiseRejectedResult).reason as Error;
        return { passed: false, error: reason.message, failedStep: i + failedAt + 1 };
      }
      i = j - 1;
    } else {
      try {
        await processStep(flow.steps[i], i, w, l, log); // sequential: the flow's live logger (UNCHANGED behavior)
      } catch (error) {
        l(' \x1b[31mFAIL\x1b[0m');
        return { passed: false, error: (error as Error).message, failedStep: i + 1 };
      }
    }
  }

  return { passed: true };
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

type FlowResult = {
  example: string;
  flow: string;
  steps: number;
  passed: boolean;
  error?: string;
  failedStep?: number;
  durationMs: number;
};

async function main() {
  const opts = parseArgs();
  const parallel = opts.parallel !== 'false';

  console.log('\x1b[36m=== Ottochain E2E Test Runner ===\x1b[0m');
  console.log(`Mode: ${parallel ? 'parallel' : 'sequential'}\n`);

  // Setup environment
  const env = getMetagraphEnv(opts.target);
  const walletNames = [...new Set(opts.wallets.split(','))];
  const wallets = walletNames.reduce((acc: Wallets, user) => {
    acc[user] = generateWallet(env.globalL0Url, user);
    return acc;
  }, {});

  const ml0Urls = [env.node1ML0, env.node2ML0, env.node3ML0];
  const ml0Env = ml0Urls.map((a) => a + '/data-application/v1/checkpoint');
  const dl1Urls = [env.node1DataL1, env.node2DataL1, env.node3DataL1];
  const maxRetries = parseInt(opts.maxRetries);
  const retryDelayMs = parseInt(opts.retryDelay) * 1000;
  const waitTimeMs = parseInt(opts.waitTime) * 1000;

  // Discover examples
  let examples = await discoverExamples();

  // Lane filters: --only / --exclude (comma-separated example dir names) let CI split the suite into a
  // fast "core" lane and a slow "heavy" lane (sigma-mixer/rule110/staked-oracle-pool). Unset = all.
  const only = (opts.only ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  const exclude = (opts.exclude ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  if (only.length) examples = examples.filter((e) => only.includes(e.dir));
  if (exclude.length) examples = examples.filter((e) => !exclude.includes(e.dir));

  if (examples.length === 0) {
    console.error('\x1b[31mNo examples with test flows found\x1b[0m');
    process.exit(1);
  }

  // Collect all (example, flow) pairs
  const flowPairs: Array<{ example: Example; flow: TestFlow }> = [];
  for (const example of examples) {
    for (const flow of example.testFlows) {
      flowPairs.push({ example, flow });
    }
  }

  console.log(
    `Found ${examples.length} example(s), ${flowPairs.length} flow(s): ${examples.map((e) => e.dir).join(', ')}\n`
  );

  // One-time warmup: wait for ML0 block production to pass the cold-start (fresh-jar / JVM-warmup
  // first-snapshot lag) ONCE before timing flows, so the per-flow sync budgets aren't each charged it.
  try {
    await waitForOrdinalAdvance(ml0Urls[0], 2, { label: 'cluster warmup', maxTotalTimeMs: 300_000 });
  } catch (err) {
    console.error(`\x1b[31mCluster failed to warm up: ${(err as Error).message}\x1b[0m`);
    console.error('Aborting — ML0 is not producing snapshots (consensus not live).');
    process.exit(1);
  }

  // Subscribe to ML0 snapshot/rejection webhooks so confirmations are driven by PUSHES — the chain
  // commits the read state before dispatching `snapshot.finalized`, so a re-check on each push sees
  // fresh state — instead of blind-polling a lagging read. Falls back to polling automatically if the
  // ML0 build doesn't expose webhooks or the callback isn't reachable.
  const webhook = new WebhookListener();
  await webhook.start(ml0Urls);
  console.log(
    webhook.active
      ? '\x1b[36m[webhook]\x1b[0m subscribed — confirmations are push-driven\n'
      : '\x1b[33m[webhook]\x1b[0m not available — falling back to ordinal polling\n'
  );

  // Keep the chain fed so it never idles into the data-L1 deadlock (see lib/keepalive.ts): once every
  // flow pauses to wait on a confirmation, the data pipeline stalls permanently and any in-flight
  // update is stranded uncombined. A steady trickle of throwaway fiber-creates keeps the data-L1
  // forming blocks for the whole run, so stuck flow updates ride along and get combined. rule110's
  // definition is a known-good, dependency-free create (no schemaRef binding).
  const keepalive = new ChainKeepalive(wallets, dl1Urls, {
    definition: JSON.parse(
      fs.readFileSync(path.join(examplesDir, 'rule110', 'definition.json'), 'utf8')
    ),
    initialData: JSON.parse(
      fs.readFileSync(path.join(examplesDir, 'rule110', 'initial-data.json'), 'utf8')
    ),
  });
  keepalive.start();

  const startTime = Date.now();
  let results: FlowResult[];

  if (parallel) {
    // -----------------------------------------------------------------------
    // Parallel mode: run all flows concurrently with buffered output
    // -----------------------------------------------------------------------
    // Concurrency: an explicit E2E_CONCURRENCY wins (0/NaN ignored, preserving the old `|| default`).
    // Otherwise an INTERACTIVE single-example run (e.g. a local `--only riverdale-economy` in a TTY)
    // defaults to 1 so its flows run serially and stream live. The isTTY gate is load-bearing: it keeps
    // CI behavior identical — the tictactoe lane is single-example with no explicit concurrency and has
    // 3 flows that MUST keep running in parallel (serializing them would ~3x its wall-clock and risk the
    // job timeout). CI has no TTY, so it always takes the `flowPairs.length` branch as before.
    const envConc = Number(process.env.E2E_CONCURRENCY);
    const CONCURRENCY =
      Number.isFinite(envConc) && envConc > 0
        ? envConc
        : examples.length === 1 && process.stdout.isTTY
          ? 1
          : flowPairs.length;
    // At CONCURRENCY 1 no two flows can interleave, so stream each flow's output live (write-through)
    // instead of buffering it until flush — the single-example local run reads in real time.
    const liveOutput = CONCURRENCY === 1;
    console.log(`Launching ${flowPairs.length} flows, ${CONCURRENCY} at a time…\n`);

    const runOne = async ({ example, flow }: (typeof flowPairs)[number]): Promise<FlowResult> => {
      const tag = `${example.dir}/${flow.name}`;
      const logger = new FlowLogger(tag, liveOutput);
      logger.log(
        `\x1b[36m[${example.dir}]\x1b[0m Running: ${flow.name} (${flow.steps.length} steps)`
      );

      const flowStart = Date.now();
      const result = await runFlow(
        example,
        flow,
        env,
        wallets,
        ml0Urls,
        ml0Env,
        dl1Urls,
        maxRetries,
        retryDelayMs,
        waitTimeMs,
        logger,
        webhook
      );
      const durationMs = Date.now() - flowStart;

      if (result.passed) {
        logger.log(`  \x1b[32mPASS: ${flow.name}\x1b[0m (${(durationMs / 1000).toFixed(1)}s)`);
      } else {
        logger.log(
          `  \x1b[31mFAIL: ${flow.name}\x1b[0m (step ${result.failedStep}, ${(durationMs / 1000).toFixed(1)}s)`
        );
        logger.log(`  Error: ${result.error}`);
      }

      // Flush buffered output as a single block (avoids interleaving)
      logger.flush();

      return {
        example: example.dir,
        flow: flow.name,
        steps: flow.steps.length,
        durationMs,
        ...result,
      } satisfies FlowResult;
    };

    // Bounded-concurrency pool: keep at most CONCURRENCY flows in flight. A heavy
    // guard (e.g. sigma_verify) on a resource-limited CI cluster slows ML0 snapshot
    // production; capping in-flight flows stops that from starving every other flow's
    // transactions of their ordinal-confirmation budget. Result order is preserved.
    results = new Array<FlowResult>(flowPairs.length);
    let cursor = 0;
    const worker = async (): Promise<void> => {
      for (;;) {
        const i = cursor++;
        if (i >= flowPairs.length) return;
        results[i] = await runOne(flowPairs[i]);
      }
    };
    await Promise.all(
      Array.from({ length: Math.min(CONCURRENCY, flowPairs.length) }, () => worker())
    );
  } else {
    // -----------------------------------------------------------------------
    // Sequential mode: same as original behavior (no buffering needed)
    // -----------------------------------------------------------------------
    results = [];

    for (const { example, flow } of flowPairs) {
      console.log(
        `\x1b[36m[${example.dir}]\x1b[0m Running: ${flow.name} (${flow.steps.length} steps)`
      );

      const flowStart = Date.now();
      const result = await runFlow(
        example,
        flow,
        env,
        wallets,
        ml0Urls,
        ml0Env,
        dl1Urls,
        maxRetries,
        retryDelayMs,
        waitTimeMs,
        undefined,
        webhook
      );
      const durationMs = Date.now() - flowStart;

      results.push({
        example: example.dir,
        flow: flow.name,
        steps: flow.steps.length,
        durationMs,
        ...result,
      });

      if (result.passed) {
        console.log(`  \x1b[32mPASS: ${flow.name}\x1b[0m (${(durationMs / 1000).toFixed(1)}s)\n`);
      } else {
        console.log(
          `  \x1b[31mFAIL: ${flow.name}\x1b[0m (step ${result.failedStep})`
        );
        console.log(`  Error: ${result.error}\n`);
      }
    }
  }

  keepalive.stop();

  const totalDurationMs = Date.now() - startTime;

  // Summary
  const passed = results.filter((r) => r.passed).length;
  const failed = results.filter((r) => !r.passed).length;
  const total = results.length;

  console.log('\n\x1b[36m=== Results ===\x1b[0m');
  console.log(`Mode:   ${parallel ? 'parallel' : 'sequential'}`);
  console.log(`Total:  ${(totalDurationMs / 1000).toFixed(1)}s`);
  console.log(`Passed: ${passed}/${total}`);
  console.log(`Failed: ${failed}/${total}`);

  if (failed > 0) {
    console.log('\n\x1b[31mFailed flows:\x1b[0m');
    results
      .filter((r) => !r.passed)
      .forEach((r) => {
        console.log(`  - [${r.example}] ${r.flow} (step ${r.failedStep}): ${r.error}`);
      });
  }

  console.log(`\nExit code: ${failed > 0 ? 1 : 0}`);
  await webhook.stop();
  process.exit(failed > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error('\x1b[31mRunner failed:\x1b[0m', err.message);
  process.exit(1);
});
