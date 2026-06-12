import type { OttochainMessage, SetVersionStatus, RegistryStatus, CalculatedState } from '@ottochain/sdk/core';
import type { StatesMap } from '../types.ts';

/**
 * Change a registered version's lifecycle status (Active <-> Deprecated -> Yanked). Owner-gated (#26).
 * `status` is the uppercase RegistryStatus enum: "ACTIVE" | "DEPRECATED" | "YANKED".
 */
export interface SetVersionStatusOptions {
  name: string;
  version: string;
  status: RegistryStatus;
}

export const generator = ({ options }: { cid?: string; wallets?: unknown; options: SetVersionStatusOptions }): OttochainMessage => {
  const msg: SetVersionStatus = { name: options.name, version: options.version, status: options.status };
  return { SetVersionStatus: msg };
};

export const validator = ({ statesMap, options }: { cid?: string; statesMap: StatesMap; options: SetVersionStatusOptions; wallets?: unknown }) => {
  for (const [url, { final }] of Object.entries(statesMap)) {
    const state = (final as { state?: CalculatedState } | null)?.state;
    const target = state?.registry?.[options.name]?.target as
      | { SchemaPackage?: { versions?: { versions?: Record<string, { status?: string }> } } }
      | undefined;
    const rv = target?.SchemaPackage?.versions?.versions?.[options.version];
    if (!rv) {
      throw new Error(`\x1b[33m[setVersionStatus.validator]\x1b[0m ${options.name}@${options.version} not found at ${url}`);
    }
    if (rv.status !== options.status) {
      throw new Error(
        `\x1b[33m[setVersionStatus.validator]\x1b[0m expected status ${options.status} but found ${rv.status} for ${options.name}@${options.version} at ${url}`
      );
    }
    console.log(`\x1b[33m[setVersionStatus.validator]\x1b[32m ${options.name}@${options.version} -> ${options.status} at ${url}\x1b[0m`);
  }
};
