import type { OttochainMessage, RegisterAlias, CalculatedState } from '@ottochain/sdk/core';
import type { StatesMap } from '../types.ts';

/**
 * Register a human-readable nickname for an existing fiber (#29). The name's TLD must match the target
 * fiber's kind (.machine -> state machine, .script -> script); the signer must own the target fiber. Sets
 * the forward alias (name -> fiber) and the fiber's canonical reverse record (fiber -> name).
 */
export interface RegisterAliasOptions {
  name: string;
  targetFiberId: string;
  metadata?: Record<string, string>;
}

export const generator = ({ options }: { cid?: string; wallets?: unknown; options: RegisterAliasOptions }): OttochainMessage => {
  const msg: RegisterAlias = {
    name: options.name,
    targetFiberId: options.targetFiberId,
    ...(options.metadata ? { metadata: options.metadata } : {}),
  };
  return { RegisterAlias: msg };
};

export const validator = ({ statesMap, options }: { cid?: string; statesMap: StatesMap; options: RegisterAliasOptions; wallets?: unknown }) => {
  for (const [url, { final }] of Object.entries(statesMap)) {
    const state = (final as { state?: CalculatedState } | null)?.state;
    // Forward: the alias name now resolves to an entry.
    if (!state?.registry?.[options.name]) {
      throw new Error(`\x1b[33m[registerAlias.validator]\x1b[0m forward alias "${options.name}" missing at ${url}`);
    }
    // Reverse: the fiber's canonical name (#29) points back at this alias.
    const reverse = state?.reverseNames?.[options.targetFiberId];
    if (reverse !== options.name) {
      throw new Error(
        `\x1b[33m[registerAlias.validator]\x1b[0m reverse record for ${options.targetFiberId} = "${reverse}" (expected "${options.name}") at ${url}`
      );
    }
    console.log(`\x1b[33m[registerAlias.validator]\x1b[32m alias "${options.name}" <-> ${options.targetFiberId} (forward + reverse) at ${url}\x1b[0m`);
  }
};
