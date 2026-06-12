import fs from 'fs';
import path from 'path';
import type {
  OttochainMessage,
  PublishVersion,
  SchemaShape,
  StateMachineDefinition,
  CalculatedState,
} from '@ottochain/sdk/core';
import type { StatesMap } from '../types.ts';

/**
 * Publish (create-or-append) a registry schema-package version (#23/#26).
 *
 * - `name`        full "labels.tld" (e.g. "order.package"); the chain derives the routing id (not on wire).
 * - `version`     SemVer string "MAJOR.MINOR.PATCH".
 * - `schemaB64`   base64 of the proto FileDescriptorSet. The chain only base64-validates + hashes it, then
 *                 DROPS the bytes — a deterministic placeholder is fine for the e2e.
 * - `schemaShape` the typed proto projection (advisory).
 * - `definition`  the StateMachineDefinition — hashed into `logicHash`; a fiber verified-binds to this.
 */
export interface PublishVersionOptions {
  name: string;
  version: string;
  definition: string | StateMachineDefinition;
  schemaShape: string | SchemaShape;
  schemaB64?: string;
  strict?: boolean;
  metadata?: Record<string, string>;
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

export const generator = ({ options }: { cid?: string; wallets?: unknown; options: PublishVersionOptions }): OttochainMessage => {
  const msg: PublishVersion = {
    name: options.name,
    version: options.version,
    schemaB64: options.schemaB64 ?? Buffer.from(`descriptor:${options.name}:${options.version}`).toString('base64'),
    schemaShape: loadMaybe<SchemaShape>(options.schemaShape),
    definition: loadMaybe<StateMachineDefinition>(options.definition),
    // `strict` is required on the chain (no default) — always send it so the signed canonical matches
    // what the chain re-derives. `metadata` is Option (omit-safe), so it stays conditional.
    strict: options.strict ?? false,
    ...(options.metadata ? { metadata: options.metadata } : {}),
  };
  return { PublishVersion: msg };
};

export const validator = ({ statesMap, options }: { cid?: string; statesMap: StatesMap; options: PublishVersionOptions; wallets?: unknown }) => {
  for (const [url, { final }] of Object.entries(statesMap)) {
    const state = (final as { state?: CalculatedState } | null)?.state;
    const entry = state?.registry?.[options.name];
    if (!entry) {
      throw new Error(`\x1b[33m[publishVersion.validator]\x1b[0m no registry entry for "${options.name}" at ${url}`);
    }
    // RegistryTarget double-nesting: target.SchemaPackage.versions (VersionLineage) .versions (the SemVer map)
    const target = entry.target as { SchemaPackage?: { versions?: { versions?: Record<string, unknown> } } };
    const versions = target?.SchemaPackage?.versions?.versions;
    if (!versions || !(options.version in versions)) {
      throw new Error(
        `\x1b[33m[publishVersion.validator]\x1b[0m version ${options.version} not in lineage for "${options.name}" at ${url} (have: ${versions ? Object.keys(versions).join(',') : 'none'})`
      );
    }
    if (!Array.isArray(entry.owner) || entry.owner.length === 0) {
      throw new Error(`\x1b[33m[publishVersion.validator]\x1b[0m entry "${options.name}" has no owner at ${url}`);
    }
    console.log(`\x1b[33m[publishVersion.validator]\x1b[32m ${options.name}@${options.version} registered (owner set) at ${url}\x1b[0m`);
  }
};
