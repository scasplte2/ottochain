import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { verify } from '../../e2e-test/examples/agent-mandate/verify-mandate.ts';

const example = new URL('../../e2e-test/examples/agent-mandate/', import.meta.url);
const { jsonLogic } = createRequire(example.href + 'replay.cjs')('@constellation-network/metagraph-sdk-jlvm');
const load = name => JSON.parse(readFileSync(new URL(name, example), 'utf8'));
const record = label => {
  const bundle = load('evidence/' + label + '.bundle.json');
  const result = verify(bundle, load('evidence/' + label + '.pin.json'));
  return {result, raw: bundle.proof.record};
};
const ledger = load('evidence/ledger.json');
const request = ledger.find(row => row.label === 'payee-refused');
const before = record('solo'), after = record(request.label);
const {receipt, data} = after.result;
assert.equal(request.fiber, after.result.fiber);
assert.equal(before.result.fiber, request.fiber);
assert.equal(after.result.ordinal, request.checkpoint);
assert.equal(receipt.ordinal, request.ordinal);
assert.equal(receipt.success, false);
assert.equal(receipt.errorMessage, request.receipt);
assert.equal(before.result.data.remainingCents, request.remainingBefore);
assert.equal(data.remainingCents, request.remainingAfter);
assert.deepEqual(data, before.result.data);
const active = def => def.transitions.find(t => t.from === before.result.state && t.eventName === request.event).guard;
const guard = active(load('definition.json'));
assert.deepEqual(guard, active(before.raw.definition));
assert.deepEqual(guard, active(after.raw.definition));
const inputs = {state: before.result.data, event: request.payload,
  proofs: request.addresses.map(address => ({address})), $ordinal: receipt.ordinal};
const evaluate = clause => {
  const result = jsonLogic.apply(clause, inputs);
  assert.equal(typeof result, 'boolean');
  return result;
};

// Match exact AST shapes to human labels. An edited, unfamiliar rule fails the render.
const variable = name => ({var: name});
const amount = variable('event.amountCents');
const signatures = {map: [variable('proofs'), variable('address')]};
const signed = role => ({in: [variable('state.' + role), signatures]});
const solo = {'<=': [amount, variable('state.soloLimitCents')]};
const cosigned = {and: [{'!==': [variable('state.agent'), variable('state.cosigner')]}, signed('cosigner')]};
const labels = [
  [signed('agent'), 'Agent signed', 'signature'],
  [{'===': [{typeof: [amount]}, 'int']}, 'Whole cents', 'amount'],
  [{'>': [amount, 0]}, 'Positive amount', 'amount'],
  [{'<=': [amount, variable('state.remainingCents')]}, 'Within budget', 'amount'],
  [{in: [variable('event.payee'), variable('state.suppliers')]}, 'Listed supplier', 'payee'],
  [{'<': [variable('$ordinal'), variable('state.expiresAt')]}, 'Before expiry', 'logic'],
  [{'===': [variable('event.requestNumber'), {'+': [variable('state.lastRequestNumber'), 1]}]}, 'Next request', 'logic'],
  [{or: [solo, cosigned]}, 'Solo or co-signed', 'signature'],
];
const gates = guard.and.map(clause => {
  const match = labels.find(([shape]) => JSON.stringify(shape) === JSON.stringify(clause));
  assert.ok(match, 'Unrecognized guard clause: ' + JSON.stringify(clause));
  return {label: match[1], wire: match[2], pass: evaluate(clause)};
});
assert.equal(evaluate(guard), receipt.success);
assert.deepEqual(gates.filter(g => !g.pass).map(g => g.wire), ['payee']);
const terminal = [
  'receipt  #' + receipt.ordinal + '  refused',
  'JLVM     ' + gates.filter(g => g.pass).length + '/' + gates.length + ' clauses pass',
  'record   #' + after.result.ordinal + '  $' + data.remainingCents / 100,
];
console.log(terminal.join('\n'));
console.log(receipt.errorMessage);
const scene = {gates, terminal, previousPayee:before.result.data.lastAuthorization.payee, request: request.payload, ordinal: receipt.ordinal,
  checkpoint: after.result.ordinal, previous: before.result.ordinal, balance: data.remainingCents / 100,
  startBudget: ledger.find(r => r.label === 'created').remainingAfter / 100,
  suppliers: data.suppliers, receipt: receipt.errorMessage};
if (process.argv[2]) writeFileSync(process.argv[2], JSON.stringify(scene, null, 2) + '\n');
