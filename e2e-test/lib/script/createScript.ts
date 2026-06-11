import fs from 'fs';
import path from 'path';
import type { OttochainMessage } from '@ottochain/sdk';
import type { StatesMap } from '../types.ts';

export interface CreateOracleOptions {
  oracle?: string;
  oracleDefinition?: {
    scriptProgram: unknown;
    accessControl: unknown;
    initialState?: unknown;
  };
}

export const generator = ({ cid, options }: { cid: string; wallets?: unknown; options: CreateOracleOptions }): OttochainMessage => {
  let oracleDefinition: { scriptProgram: unknown; accessControl: unknown; initialState?: unknown };

  if (options.oracleDefinition && typeof options.oracleDefinition === 'object') {
    oracleDefinition = options.oracleDefinition;
  } else if (typeof options.oracle === 'string') {
    const oraclePath = path.resolve(options.oracle);
    if (!fs.existsSync(oraclePath)) {
      throw new Error(`Oracle definition file not found: ${oraclePath}`);
    }
    oracleDefinition = JSON.parse(fs.readFileSync(oraclePath, 'utf8'));
  } else {
    throw new Error(
      'Either options.oracle (path) or options.oracleDefinition (object) must be provided'
    );
  }

  // Build the CreateScript message.
  // Note: since metakit 1.8.0 the server DROPS null object fields before
  // canonicalizing (JsonBinaryCodec.dropNulls), and sendDataTransaction
  // applies the SDK's dropNulls before signing to match. An explicit
  // "initialState": null here is therefore equivalent to omitting the field.
  const createMsg: Record<string, unknown> = {
    fiberId: cid,
    scriptProgram: oracleDefinition.scriptProgram,
    initialState: oracleDefinition.initialState ?? null,
    accessControl: oracleDefinition.accessControl,
  };

  return { CreateScript: createMsg } as unknown as OttochainMessage;
};

export const validator = ({ cid, statesMap }: { cid: string; statesMap: StatesMap }) => {
  for (const [url, { final }] of Object.entries(statesMap)) {
    const finalRecord = final?.state?.scripts?.[cid];

    if (!finalRecord) {
      throw new Error(
        `\x1b[33m[createScript.validator]\x1b[0m No script oracle found for fiberId = ${cid} in final state from ${url}.`
      );
    }

    if (finalRecord.status !== 'ACTIVE') {
      throw new Error(
        `\x1b[33m[createScript.validator]\x1b[0m Expected oracle status "ACTIVE" but found "${finalRecord.status}" for fiberId = ${cid} at ${url}.`
      );
    }

    // US-7: invocationCount → sequenceNumber
    if (finalRecord.sequenceNumber !== 0) {
      throw new Error(
        `\x1b[33m[createScript.validator]\x1b[0m Expected sequenceNumber 0 for new oracle but found ${finalRecord.sequenceNumber} for fiberId = ${cid} at ${url}.`
      );
    }

    console.log(
      `\x1b[33m[createScript.validator]\x1b[32m Oracle created successfully for fiberId = ${cid} at ${url}!\x1b[0m`
    );
  }
};
