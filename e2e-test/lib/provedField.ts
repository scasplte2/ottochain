import { verifyStateProof } from '@ottochain/sdk/core';
import { isDeepStrictEqual } from 'node:util';

type Proof = Parameters<typeof verifyStateProof>[0];

export function provedField(response: Proof, fiber: string, field: string): unknown {
  const verdict = verifyStateProof(response, 'fiber/' + fiber);
  if (!verdict.ok) throw new Error(verdict.reason);
  const data = (response.record as { stateData?: Record<string, unknown> })?.stateData;
  if (!data || !Object.hasOwn(data, field)) throw new Error('Missing proved field ' + field);
  if (response.field !== field || !isDeepStrictEqual(data[field], response.fieldValue))
    throw new Error('Projected field disagrees with proved record');
  return data[field];
}
