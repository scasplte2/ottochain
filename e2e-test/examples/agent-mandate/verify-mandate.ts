import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { isDeepStrictEqual } from 'node:util';
import { pathToFileURL } from 'node:url';
import { verifyStateProof } from '@ottochain/sdk/core';
import { verifyOrdinalCatalogProof } from '@constellation-network/metagraph-sdk-jlvm';

type Proof = Parameters<typeof verifyStateProof>[0];
type Root = { ordinal: number; mptRoot: string; catalogRoot: { value: string }; calculatedStateHash: string };
type Source = { node: string; network: string; hash: string; snapshot: string };
type Anchor = { ordinal: number; network: string; hash: string; snapshotSha256: string; nodes: string[] };
export type Pin = Anchor & { fiber: string; history: Anchor };
type Bundle = { proof: Proof; root: Root; catalog: { ordinal: number; catalogRoot: { value: string }; epochSize: number; proof: { ordinal: number } }; sources: Source[]; historyRoot: Root; historySources: Source[] };
const hash = (data: Buffer | string) => createHash('sha256').update(data).digest('hex');
function check(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}
function digest(s: string) {
  check(/^[0-9a-f]{64}$/.test(s), 'Invalid digest');
  return Buffer.from(s, 'hex');
}
function anchor(root: Root, sources: Source[], pin: Anchor) {
  const commitment = hash(Buffer.concat([digest(root.mptRoot), digest(root.catalogRoot.value)]));
  check(root.ordinal === pin.ordinal && Number.isSafeInteger(pin.ordinal) && pin.ordinal > 0, 'Checkpoint ordinal mismatch');
  check(commitment === root.calculatedStateHash, 'Combined commitment mismatch');
  check(sources.length > 0 && isDeepStrictEqual(sources.map(s => s.node), pin.nodes) && new Set(pin.nodes).size === sources.length, 'Snapshot sources mismatch');
  for (const s of sources) {
    check(s.network === pin.network && s.hash === pin.hash && hash(s.snapshot) === pin.snapshotSha256, 'Pinned ledger snapshot identity mismatch');
    const ledger = JSON.parse(s.snapshot);
    check(ledger.value.ordinal === pin.ordinal && ledger.value.dataApplication?.calculatedStateProof === commitment, 'Snapshot commitment mismatch');
  }
  return commitment;
}
export function verify(bundle: Bundle, pin: Pin) {
  const { proof, root, catalog, sources } = bundle;
  const v = verifyStateProof(proof, 'fiber/' + pin.fiber);
  check(v.ok, v.ok ? '' : v.reason);
  const r = proof.record as { fiberId: string; stateData: Record<string, unknown>; currentState: string; owners: string[]; lastReceipt?: unknown };
  check(r.fiberId === pin.fiber, 'Record fiber mismatch');
  if (proof.field != null) check(Object.hasOwn(r.stateData, proof.field) && isDeepStrictEqual(r.stateData[proof.field], proof.fieldValue), 'Unproved convenience field');
  else check(proof.fieldValue == null, 'Unbound field value');
  check(proof.ordinal === pin.ordinal && root.mptRoot === proof.mptRoot, 'Record ordinal or root mismatch');
  check(anchor(root, sources, pin) === proof.committedRoot, 'Record commitment mismatch');
  check(pin.history.network === pin.network && pin.history.ordinal > pin.ordinal, 'Historical catalog must follow record on the same ledger');
  anchor(bundle.historyRoot, bundle.historySources, pin.history);
  check(catalog.ordinal === pin.history.ordinal && catalog.catalogRoot.value === bundle.historyRoot.catalogRoot.value, 'Catalog checkpoint mismatch');
  check(catalog.proof.ordinal === pin.ordinal && catalog.epochSize === 65536, 'Catalog ordinal or epoch geometry mismatch');
  const att = verifyOrdinalCatalogProof(catalog.catalogRoot.value, catalog.proof, 65536);
  check('type' in att && att.type === 'CommittedAt' && att.ordinal === BigInt(pin.ordinal) && att.mptRoot === root.mptRoot, 'Catalog attestation failed');
  return { fiber: r.fiberId, ordinal: pin.ordinal, state: r.currentState, owners: r.owners, data: r.stateData, receipt: r.lastReceipt,
    checks: ['expected-key inclusion', 'ordinal catalog', 'combined commitment', 'pinned ledger snapshot'],
    snapshotNodes: sources.length, trust: 'Local demo operator. Validator signature threshold unchecked.' };
}

async function getText(base: string, path: string) {
  const res = await fetch(base + path, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(6000) });
  if (!res.ok) throw new Error(path + ' HTTP ' + res.status);
  return res.text();
}
const get = async (base: string, path: string) => JSON.parse(await getText(base, path));
async function source(base: string, ordinal: number): Promise<Source> {
  const [info, network, identity, snapshot] = await Promise.all([
    get(base, '/node/info'), get(base, '/snapshots/1/hash'), get(base, '/snapshots/' + ordinal + '/hash'), getText(base, '/snapshots/' + ordinal),
  ]);
  return { node: info.id, network, hash: identity, snapshot };
}
export async function capture(fiber: string, primary: string, secondary?: string) {
  let last: unknown;
  for (let attempt = 0; attempt < 8; attempt++) {
    try {
      const [proof, root] = await Promise.all([
        get(primary, '/data-application/v1/state-machines/' + fiber + '/state-proof'),
        get(primary, '/data-application/committed/root'),
      ]);
      if (proof.ordinal !== root.ordinal) throw new Error('Head advanced during record capture');
      const issues: string[] = [];
      async function evidenceAt(n: number) {
        const first = await source(primary, n);
        const sources = [first];
        if (secondary) {
          try { sources.push(await source(secondary, n)); }
          catch (e) { issues.push(String(e)); }
        }
        const anchor = { ordinal: n, network: first.network, hash: first.hash, snapshotSha256: hash(first.snapshot), nodes: sources.map(s => s.node) };
        return { sources, anchor };
      }
      const recordEvidence = await evidenceAt(proof.ordinal);
      // The catalog at N commits its parent. Preserve N's witness, then authenticate
      // the catalog containing N against a later snapshot on the same ledger.
      let historyRoot: Root;
      let catalog: Bundle['catalog'];
      const deadline = Date.now() + 120_000;
      do {
        [historyRoot, catalog] = await Promise.all([get(primary, '/data-application/committed/root'),
          get(primary, '/data-application/committed/proof-ordinal/' + proof.ordinal)]);
        if (historyRoot.ordinal > proof.ordinal && historyRoot.ordinal === catalog.ordinal) break;
        if (Date.now() >= deadline) throw new Error('No later catalog available');
        await new Promise(r => setTimeout(r, 500));
      } while (true);
      const history = await evidenceAt(historyRoot.ordinal);
      const pin = { ...recordEvidence.anchor, fiber, history: history.anchor };
      const bundle: Bundle = { proof, root, catalog, sources: recordEvidence.sources, historyRoot, historySources: history.sources };
      const limitation = issues.join('; ') || undefined;
      const result = verify(bundle, pin);
      return { bundle, pin, result, limitation };
    } catch (e) { last = e; await new Promise(r => setTimeout(r, 500)); }
  }
  throw last;
}

async function main() {
  const [mode, file, pins, fiber, primary, secondary] = process.argv.slice(2);
  if (mode === 'capture' && pins && fiber && primary) {
    const item = await capture(fiber, primary, secondary);
    writeFileSync(file, JSON.stringify(item.bundle) + '\n', { flag: 'wx' });
    writeFileSync(pins, JSON.stringify(item.pin, null, 2) + '\n', { flag: 'wx' });
    console.log(JSON.stringify(item.result), item.limitation ?? '');
  } else if (mode === 'replay' && file && pins) {
    console.log(JSON.stringify(verify(JSON.parse(readFileSync(file, 'utf8')), JSON.parse(readFileSync(pins, 'utf8'))), null, 2));
  } else { throw new Error('Usage: capture BUNDLE PIN FIBER PRIMARY [SECONDARY] | replay BUNDLE PIN'); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(e => { console.error(String(e)); process.exitCode = 1; });
}
