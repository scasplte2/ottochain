import { readFileSync } from 'node:fs';
import { verify } from './verify-mandate.ts';

const [bundleFile, pinFile, proposed] = process.argv.slice(2);
if (!bundleFile || !pinFile) throw new Error('Usage: card BUNDLE PIN [proposed cents]');
const { data, state, ordinal, snapshotNodes, receipt, fiber } = verify(
  JSON.parse(readFileSync(bundleFile, 'utf8')), JSON.parse(readFileSync(pinFile, 'utf8')));
const money = (cents: unknown) => {
  if (typeof cents !== 'number' || !Number.isSafeInteger(cents)) throw new Error('Invalid cents');
  return '$' + (cents / 100).toFixed(2);
};
const event = receipt as { success: boolean; eventName: string; errorMessage?: string } | undefined;
console.log([
  'OTTOCHAIN   /   SUPPLIER MANDATE', '',
  'Test authorization budget   ' + money(data.remainingCents),
  'Status                      ' + state + ' at snapshot ' + ordinal,
  'Agent solo limit            ' + money(data.soloLimitCents),
  'Approved suppliers          ' + (data.suppliers as string[]).join(', '),
  'Expires at snapshot         ' + data.expiresAt,
  ...(proposed === undefined ? [] : ['Proposed authorization      ' + money(Number(proposed)) + ' · ' +
    (state !== 'active' ? 'revoked' : Number(proposed) > Number(data.soloLimitCents) ? 'co-signature required' : 'within solo limit')]),
  'Recorded outcome            ' + (event ? event.eventName + ' · ' + (event.success ? 'committed' : event.errorMessage) : 'created'),
  'Fiber                       ' + fiber,
  'Evidence                    Record inclusion, catalog and pinned snapshot',
  'Snapshot sources            ' + snapshotNodes + ' on one demo operator',
].join('\n'));
