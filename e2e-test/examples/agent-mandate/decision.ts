import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { getDagAddressFromPublicKey } from '@constellation-network/metagraph-sdk';
import { verify as verifySignatures } from '@ottochain/sdk';
import { verify } from './verify-mandate.ts';

const load = (name: string) => JSON.parse(readFileSync(new URL('./evidence/' + name, import.meta.url), 'utf8'));
const bundle = load('authorized.bundle.json');
const pin = load('authorized.pin.json');
const signed = load('cosigned-request.json');
const revoked = verify(load('revoked.bundle.json'), load('revoked.pin.json'));
const active = verify(bundle, pin);
const owner = 'DAG3nJQJnDpsFGd4DN1qHsK1Zno4YD24LSpXhbd5';
assert.deepEqual(active.owners, [owner]);
assert.equal(active.data.owner, owner);
assert.equal(active.state, 'active');
assert.equal(active.fiber, revoked.fiber);
assert.equal(pin.network, load('revoked.pin.json').network);
assert(revoked.ordinal > active.ordinal && revoked.state === 'revoked');
const request = signed.value.TransitionStateMachine;
assert.equal(request.fiberId, active.fiber);
assert.equal(request.eventName, 'authorize');
assert.equal(request.targetSequenceNumber + 1, bundle.proof.record.sequenceNumber);
assert.deepEqual(request.payload, { requestNumber: 2, payee: 'supplier-a', amountCents: 4000 });
const signatures = verifySignatures(signed, true);
assert(signatures.isValid && signatures.invalidProofs.length === 0);
const signers = signatures.validProofs.map(p => getDagAddressFromPublicKey(p.id)).sort();
assert.equal(new Set(signers).size, 2);
assert.deepEqual(signers, [active.data.agent, active.data.cosigner].sort());
const auth = bundle.proof.record.stateData.lastAuthorization;
assert.deepEqual([...auth.signers].sort(), signers);
assert.equal(auth.ordinal, bundle.proof.record.lastReceipt.ordinal);
assert.equal(bundle.proof.record.lastReceipt.success, true);
for (const key of ['amountCents', 'payee', 'requestNumber']) assert.equal(auth[key], request.payload[key]);
assert.equal(active.data.remainingCents, 4000);
assert.equal(revoked.data.remainingCents, 4000);
console.log(`VERIFIED  Request 2 · supplier-a · $40.00 test authorization
          Agent and co-signer signed identical request bytes
          Committed action at ${auth.ordinal} · $40.00 remaining at ${active.ordinal}
          Revoked by ${revoked.ordinal} · $40.00 remaining
          ${active.snapshotNodes} ML0 snapshot source · one local operator
          Trusted capture pins · validator threshold unchecked`);
