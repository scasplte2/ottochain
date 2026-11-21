// Derive the film's data from the recorded bike-shop run — nothing on screen is hand-typed.
//   node derive.mjs <timeline.jsonl>   →  film-data.json
// Inputs: the flight-recorder timeline (every ML0 snapshot, no gaps) and the verifier bundles captured
// while the chain was live (e2e-test/examples/bike-shop/evidence). The poisoned invoice's gate verdicts are
// recomputed clause-by-clause with the pinned TypeScript JLVM against the recorded pre-request state, and
// must agree with the ledger's refusal receipt.
import assert from 'node:assert/strict';
import { createReadStream, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { createInterface } from 'node:readline';
import { verify } from '../../e2e-test/examples/agent-mandate/verify-mandate.ts';

const timelinePath = process.argv[2];
assert.ok(timelinePath, 'usage: node derive.mjs <timeline.jsonl>');
const ex = new URL('../../e2e-test/examples/bike-shop/', import.meta.url);
const { jsonLogic } = createRequire(new URL('../../e2e-test/package.json', import.meta.url))('@constellation-network/metagraph-sdk-jlvm');
const load = name => JSON.parse(readFileSync(new URL(name, ex), 'utf8'));

// ── verifier bundles (also give us the fiber ids) ──────────────────────────────────────────────
const verified = Object.fromEntries(['mandate', 'desk', 'delivery', 'trade'].map(n => [n, verify(load(`evidence/${n}.bundle.json`), load(`evidence/${n}.pin.json`))]));
const F = Object.fromEntries(Object.entries(verified).map(([n, v]) => [n, v.fiber]));
const roleOf = {};
roleOf[verified.mandate.data.owner] = 'maya';
roleOf[verified.mandate.data.agent] = 'agent';
roleOf[verified.desk.data.supplier] = 'spoke';
const fiberName = Object.fromEntries(Object.entries(F).map(([n, id]) => [id, n]));

// ── walk the timeline ──────────────────────────────────────────────────────────────────────────
const events = [];
const holders = {}; // assetId -> [{ordinal, holder, amount, behavior}]
let firstOrdinal, lastOrdinal, prevSample;
const rl = createInterface({ input: createReadStream(timelinePath), crlfDelay: Infinity });
for await (const line of rl) {
  const row = JSON.parse(line);
  firstOrdinal ??= row.ordinal;
  lastOrdinal = row.ordinal;
  const logs = row.onChain?.inner?.latestLogs ?? {};
  // FiberLogEntry is tagged on the wire: {"EventReceipt": {...}} / {"RejectionReceipt": {...}} / {"CreationReceipt": {...}}.
  const unwrap = r => { const [kind, body] = Object.entries(r)[0]; return { kind, ...body }; };
  const receipts = Object.entries(logs).flatMap(([fid, list]) => (fiberName[fid] || fid.startsWith('b1ce') ? list.map(r => ({ fiber: fiberName[fid] ?? fid, ...unwrap(r) })) : []));
  const updates = row.updates.filter(u => JSON.stringify(u.message).includes('b1ce') || Object.keys(fiberName).some(id => JSON.stringify(u.message).includes(id)) ||
    /purchasing-mandate|spoke-and-sprocket|shop-credit|brake-pads|tune-up|warranty/.test(JSON.stringify(u.message)));
  if (row.checkpoint?.assets) {
    for (const [id, a] of Object.entries(row.checkpoint.assets)) {
      const h = holders[id] ??= [];
      const holder = a.holder?.Fiber ? (fiberName[a.holder.Fiber.fiberId] ?? a.holder.Fiber.fiberId) : roleOf[a.holder?.Wallet?.address] ?? a.holder?.Wallet?.address;
      if (!h.length || h[h.length - 1].holder !== holder || h[h.length - 1].amount !== a.amount)
        h.push({ ordinal: row.ordinal, holder, amount: a.amount, behavior: a.behavior });
    }
  }
  if (receipts.length || updates.length) {
    events.push({
      ordinal: row.ordinal,
      calculatedStateProof: row.calculatedStateProof,
      receipts: receipts.map(r => ({ fiber: r.fiber, kind: r.kind, event: r.eventName ?? r.updateType, success: r.success ?? false, from: r.fromState?.value ?? r.fromState, to: r.toState?.value ?? r.toState,
        error: r.errorMessage ?? r.reason ?? null, gas: r.gasUsed ?? 0, source: r.sourceFiberId ? (fiberName[r.sourceFiberId] ?? r.sourceFiberId) : null })),
      updates: updates.map(u => ({ type: Object.keys(u.message)[0], signers: u.signers.map(a => roleOf[a] ?? a), valid: u.valid,
        summary: summarize(Object.values(u.message)[0]) })),
      state: sampleFor(row.checkpoint),
    });
  }
  if (row.checkpoint?.stateMachines) prevSample = row.checkpoint;
}
function summarize(m) {
  if (!m || typeof m !== 'object') return m;
  const pick = ['name', 'version', 'eventName', 'payload', 'assetId', 'kind', 'amount', 'cid', 'fiberId', 'compositeId', 'shardIds', 'recipient', 'otherAssetIds', 'targetFiberId'];
  return Object.fromEntries(Object.entries(m).filter(([k]) => pick.includes(k)));
}
function sampleFor(cp) {
  if (!cp?.stateMachines) return undefined;
  return Object.fromEntries(Object.entries(F).flatMap(([n, id]) => {
    const r = cp.stateMachines[id];
    return r ? [[n, { state: r.currentState?.value ?? r.currentState, seq: r.sequenceNumber, data: r.stateData }]] : [];
  }));
}

// ── poisoned invoice: per-clause JLVM replay against the recorded pre-request state ────────────
const poisoned = events.find(e => e.receipts.some(r => r.fiber === 'mandate' && r.event === 'authorize' && !r.success && r.from === 'active'));
assert.ok(poisoned, 'no refused authorize from active found');
const poisonedUpdate = poisoned.updates.find(u => u.summary?.eventName === 'authorize');
assert.ok(poisonedUpdate, 'refused request not found in its snapshot');
const before = [...events].reverse().find(e => e.ordinal < poisoned.ordinal && e.state?.mandate)?.state.mandate.data;
assert.ok(before, 'no recorded mandate state before the poisoned request');
const heldAtRequest = Object.fromEntries(Object.entries(holders).flatMap(([id, h]) => {
  const at = [...h].reverse().find(x => x.ordinal <= poisoned.ordinal);
  return at && at.holder === 'mandate' ? [[id, { amount: at.amount, behavior: at.behavior, expiresAt: null }]] : [];
}));
const guard = load('mandate.definition.json').transitions.find(t => t.from === 'active' && t.eventName === 'authorize').guard;
const inputs = { state: before, event: poisonedUpdate.summary.payload, proofs: [{ address: verified.mandate.data.agent }], $ordinal: poisoned.ordinal, heldAssets: heldAtRequest };
const v = n => ({ var: n });
const amount = v('event.amountCents');
const signed = role => ({ in: [v('state.' + role), { map: [v('proofs'), v('address')] }] });
const labels = [
  [signed('agent'), 'Agent signed', 'signature'],
  [{ '===': [{ typeof: [amount] }, 'int'] }, 'Whole cents', 'amount'],
  [{ '>': [amount, 0] }, 'Positive amount', 'amount'],
  [{ '<=': [amount, v('state.perOrderLimitCents')] }, 'Within $25 per order', 'amount'],
  [{ '<=': [amount, v('state.remainingCents')] }, 'Within budget', 'amount'],
  [{ in: [v('event.payee'), v('state.approvedSuppliers')] }, 'Approved supplier', 'payee'],
  [{ '<': [v('$ordinal'), v('state.expiresAt')] }, 'Before expiry', 'logic'],
  [{ '===': [v('event.requestNumber'), { '+': [v('state.lastRequestNumber'), 1] }] }, 'Next request', 'logic'],
  [{ '===': [{ var: { cat: ['heldAssets.', v('event.creditId'), '.amount'] } }, amount] }, 'Credit matches amount', 'amount'],
];
const gates = guard.and.map(clause => {
  const m = labels.find(([shape]) => JSON.stringify(shape) === JSON.stringify(clause));
  assert.ok(m, 'Unrecognized guard clause: ' + JSON.stringify(clause));
  const pass = jsonLogic.apply(clause, inputs);
  assert.equal(typeof pass, 'boolean');
  return { label: m[1], wire: m[2], pass };
});
assert.equal(jsonLogic.apply(guard, inputs), false, 'replayed guard must refuse, as the ledger did');

const out = {
  source: { firstOrdinal, lastOrdinal, snapshots: lastOrdinal - firstOrdinal + 1, operator: 'local devnet, one operator' },
  fibers: F,
  roles: roleOf,
  events,
  assets: holders,
  poisoned: { ordinal: poisoned.ordinal, request: poisonedUpdate.summary.payload, signers: poisonedUpdate.signers, before, gates,
    receipt: poisoned.receipts.find(r => r.fiber === 'mandate') },
  verified: Object.fromEntries(Object.entries(verified).map(([n, r]) => [n, { ordinal: r.ordinal, state: r.state, checks: r.checks, trust: r.trust, data: r.data }])),
};
writeFileSync(new URL('./film-data.json', import.meta.url), JSON.stringify(out, null, 2));
console.log(`film-data.json: ${events.length} events over ordinals ${firstOrdinal}–${lastOrdinal}; poisoned @${poisoned.ordinal}: ` +
  gates.map(g => (g.pass ? '✓ ' : '✗ ') + g.label).join(' · '));
