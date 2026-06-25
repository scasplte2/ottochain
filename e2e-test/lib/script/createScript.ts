import fs from 'fs';
import path from 'path';
import type { OttochainMessage } from '@ottochain/sdk/core';
import type { StatesMap } from '../types.ts';
import { vlog } from '../verbose.ts';

export interface CreateScriptOptions {
  script?: string;
  scriptDefinition?: {
    scriptProgram: unknown;
    accessControl: unknown;
    initialState?: unknown;
  };
}

export const generator = ({ cid, options }: { cid: string; wallets?: unknown; options: CreateScriptOptions }): OttochainMessage => {
  let scriptDefinition: { scriptProgram: unknown; accessControl: unknown; initialState?: unknown };

  if (options.scriptDefinition && typeof options.scriptDefinition === 'object') {
    scriptDefinition = options.scriptDefinition;
  } else if (typeof options.script === 'string') {
    const scriptPath = path.resolve(options.script);
    if (!fs.existsSync(scriptPath)) {
      throw new Error(`Script definition file not found: ${scriptPath}`);
    }
    scriptDefinition = JSON.parse(fs.readFileSync(scriptPath, 'utf8'));
  } else {
    throw new Error(
      'Either options.script (path) or options.scriptDefinition (object) must be provided'
    );
  }

  // Build the CreateScript message.
  // metakit drops null object fields before canonicalizing (JsonBinaryCodec.dropNulls), and
  // sendDataTransaction applies the same dropNulls before signing — so an explicit
  // "initialState": null is equivalent to omitting it (the field is dropped before the signature
  // is computed either way). The `?? null` below is just an explicit default, not a signing
  // requirement.
  const createMsg: Record<string, unknown> = {
    fiberId: cid,
    scriptProgram: scriptDefinition.scriptProgram,
    initialState: scriptDefinition.initialState ?? null,
    accessControl: scriptDefinition.accessControl,
  };

  return { CreateScript: createMsg } as unknown as OttochainMessage;
};

export const validator = ({ cid, statesMap }: { cid: string; statesMap: StatesMap }) => {
  for (const [url, { final }] of Object.entries(statesMap)) {
    const finalRecord = final?.state?.scripts?.[cid];

    if (!finalRecord) {
      throw new Error(
        `\x1b[33m[createScript.validator]\x1b[0m No script script found for fiberId = ${cid} in final state from ${url}.`
      );
    }

    if (finalRecord.status !== 'ACTIVE') {
      throw new Error(
        `\x1b[33m[createScript.validator]\x1b[0m Expected script status "ACTIVE" but found "${finalRecord.status}" for fiberId = ${cid} at ${url}.`
      );
    }

    // US-7: invocationCount → sequenceNumber
    if (finalRecord.sequenceNumber !== 0) {
      throw new Error(
        `\x1b[33m[createScript.validator]\x1b[0m Expected sequenceNumber 0 for new script but found ${finalRecord.sequenceNumber} for fiberId = ${cid} at ${url}.`
      );
    }

    vlog(
      `\x1b[33m[createScript.validator]\x1b[32m Script created successfully for fiberId = ${cid} at ${url}!\x1b[0m`
    );
  }
};
