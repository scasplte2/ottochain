// The film's "change one cent" beat: the genuine mandate bundle verifies; editing the recorded balance
// by one cent breaks the record → trie-leaf digest link and is rejected. Prints one line per case.
import { readFileSync } from 'node:fs';
import { verify } from '../agent-mandate/verify-mandate.ts';

const load = (n: string) => JSON.parse(readFileSync(new URL('./evidence/' + n, import.meta.url), 'utf8'));
const bundle = load('mandate.bundle.json'), pin = load('mandate.pin.json');
const genuine = verify(bundle, pin);
const cents = Number((genuine.data as { remainingCents: number }).remainingCents);
console.log(`PASS genuine record · remaining $${cents / 100} at ${genuine.ordinal} · ${genuine.checks.join(', ')}`);
const edited = structuredClone(bundle);
edited.proof.record.stateData.remainingCents += 1;
try {
  verify(edited, pin);
  throw new Error('one-cent edit was accepted');
} catch (e) {
  if ((e as Error).message === 'one-cent edit was accepted') throw e;
  console.log(`REJECT edited record ($${(cents + 1) / 100}) · ${(e as Error).message}`);
}
