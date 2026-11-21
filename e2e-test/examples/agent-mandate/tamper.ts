import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { verify } from './verify-mandate.ts';
import { provedField } from '../../lib/provedField.ts';

const load = (name: string) => JSON.parse(readFileSync(new URL('./evidence/' + name, import.meta.url), 'utf8'));
const bundle = load('authorized.bundle.json');
const pin = load('authorized.pin.json');
verify(bundle, pin);
console.log('PASS genuine checkpoint');
function rejected(name: string, edit: (b: typeof bundle, p: typeof pin) => void) {
  const b = structuredClone(bundle), p = structuredClone(pin);
  edit(b, p);
  assert.throws(() => verify(b, p), (err: Error) => { console.log('REJECT ' + name + ' · ' + err.message); return true; });
}
rejected('changed record', b => { b.proof.record.stateData.remainingCents = 4001; });
rejected('expected fiber key', (_, p) => { p.fiber = '00000000-0000-0000-0000-000000000000'; });
rejected('snapshot identity', b => { b.sources[0].hash = '0'.repeat(64); });
rejected('snapshot bytes', b => { b.sources[0].snapshot += ' '; });
rejected('ordinal', b => { b.proof.ordinal++; });
rejected('MPT root', b => { b.root.mptRoot = '0'.repeat(64); });
rejected('catalog proof key', b => { b.catalog.proof.hot.key = '0'.repeat(64); });
rejected('network', b => { b.historySources[0].network = '0'.repeat(64); });
const projected = { ...bundle.proof, field: 'remainingCents', fieldValue: 4000 };
assert.equal(provedField(projected, pin.fiber, 'remainingCents'), 4000);
assert.throws(() => provedField({ ...projected, fieldValue: 4001 }, pin.fiber, 'remainingCents'), /disagrees/);
console.log('REJECT changed convenience field');
