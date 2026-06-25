import fs from 'fs';
import path from 'path';
import type {
  OttochainMessage,
  UpgradeFiber,
  SchemaRef,
  StateMachineDefinition,
  JsonLogicExpression,
  CalculatedState,
} from '@ottochain/sdk/core';
import type { StatesMap } from '../types.ts';
import { vlog } from '../verbose.ts';

/**
 * Upgrade an existing fiber to a different registered version of the SAME package (#27).
 *
 * The chain verifies `newDefinition.computeDigest == targetVersion.logicHash` (verified re-bind),
 * applies the optional `migration` (a JSON-Logic transform of the prior stateData; the output BECOMES
 * the new state — see FiberEngine.migrate), preserves the current state id (must exist in the new
 * definition), and re-pins the binding. `migration` omitted = identity (state unchanged).
 */
export interface UpgradeFiberOptions {
  targetRef: { name: string; version: string };
  newDefinition: string | StateMachineDefinition;
  migration?: string | JsonLogicExpression;
  targetSequenceNumber?: number;
}

function loadMaybe<T>(v: string | T): T {
  if (typeof v === 'string') {
    const p = path.resolve(v);
    if (!fs.existsSync(p)) throw new Error(`File not found: ${p}`);
    const parsed: T = JSON.parse(fs.readFileSync(p, 'utf8'));
    return parsed;
  }
  return v;
}

export const generator = ({ cid, options }: { cid: string; wallets?: unknown; options: UpgradeFiberOptions }): OttochainMessage => {
  const targetRef: SchemaRef = {
    name: options.targetRef.name,
    version: { Exact: { version: options.targetRef.version } },
  };
  const msg: UpgradeFiber = {
    fiberId: cid,
    targetRef,
    newDefinition: loadMaybe<StateMachineDefinition>(options.newDefinition),
    targetSequenceNumber: options.targetSequenceNumber ?? 0,
    ...(options.migration !== undefined ? { migration: loadMaybe<JsonLogicExpression>(options.migration) } : {}),
  };
  return { UpgradeFiber: msg };
};

export const validator = ({ cid, statesMap, options }: { cid: string; statesMap: StatesMap; options: UpgradeFiberOptions; wallets?: unknown }) => {
  for (const [url, { final }] of Object.entries(statesMap)) {
    const fiber = (final as { state?: CalculatedState } | null)?.state?.stateMachines?.[cid];
    if (!fiber) {
      throw new Error(`\x1b[33m[upgradeFiber.validator]\x1b[0m no fiber ${cid} at ${url}`);
    }
    const binding = fiber.schemaBinding;
    if (binding?.name !== options.targetRef.name || binding?.version !== options.targetRef.version) {
      throw new Error(
        `\x1b[33m[upgradeFiber.validator]\x1b[0m fiber ${cid} not re-bound to ${options.targetRef.name}@${options.targetRef.version} (binding=${JSON.stringify(binding)}) at ${url}`
      );
    }
    vlog(
      `\x1b[33m[upgradeFiber.validator]\x1b[32m fiber ${cid} re-bound to ${options.targetRef.name}@${options.targetRef.version} at ${url}\x1b[0m`
    );
  }
};
