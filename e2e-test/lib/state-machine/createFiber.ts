import fs from 'fs';
import path from 'path';
import type { CreateStateMachine, OttochainMessage } from '@ottochain/sdk/core';
import type { StatesMap } from '../types.ts';
import { vlog } from '../verbose.ts';

export interface CreateFiberOptions {
  definition: string | object;
  initialData: string | object;
  parentFiberId?: string;
  /** Verified-binding reference: bind the new fiber to a registered package version (#37). */
  schemaRef?: { name: string; version: string };
}

export const generator = ({ cid, options }: { cid: string; wallets?: unknown; options: CreateFiberOptions }): OttochainMessage => {
  let definition: object;
  let initialData: unknown;

  if (typeof options.definition === 'string') {
    const definitionPath = path.resolve(options.definition);
    if (!fs.existsSync(definitionPath)) {
      throw new Error(`Definition file not found: ${definitionPath}`);
    }
    definition = JSON.parse(fs.readFileSync(definitionPath, 'utf8'));
  } else {
    definition = options.definition;
  }

  if (typeof options.initialData === 'string') {
    const initialDataPath = path.resolve(options.initialData);
    if (!fs.existsSync(initialDataPath)) {
      throw new Error(`Initial data file not found: ${initialDataPath}`);
    }
    initialData = JSON.parse(fs.readFileSync(initialDataPath, 'utf8'));
  } else {
    initialData = options.initialData;
  }

  const msg: CreateStateMachine = {
    fiberId: cid,
    definition: definition as CreateStateMachine['definition'],
    initialData,
    parentFiberId: options.parentFiberId ?? null,
    // Bind to a registered version (verified binding): definition.computeDigest must equal the
    // registered logicHash, else the chain rejects at ML0 combine. version uses VersionReq.Exact.
    ...(options.schemaRef
      ? { schemaRef: { name: options.schemaRef.name, version: { Exact: { version: options.schemaRef.version } } } }
      : {}),
  };

  return { CreateStateMachine: msg };
};

export const validator = ({ cid, statesMap, options }: { cid: string; statesMap: StatesMap; options: CreateFiberOptions; wallets?: unknown }) => {
  for (const [url, { final }] of Object.entries(statesMap)) {
    const finalRecord = final?.state?.stateMachines?.[cid];

    if (!finalRecord) {
      throw new Error(
        `\x1b[33m[createFiber.validator]\x1b[0m No state machine fiber found for fiberId = ${cid} in final state from ${url}.`
      );
    }

    if (finalRecord.status !== 'ACTIVE') {
      throw new Error(
        `\x1b[33m[createFiber.validator]\x1b[0m Expected fiber status "ACTIVE" but found "${finalRecord.status}" for fiberId = ${cid} at ${url}.`
      );
    }

    let expectedInitialState: string;
    if (typeof options.definition === 'string') {
      expectedInitialState = JSON.parse(
        fs.readFileSync(path.resolve(options.definition), 'utf8')
      ).initialState;
    } else {
      expectedInitialState = (options.definition as { initialState: string }).initialState;
    }

    if (finalRecord.currentState !== expectedInitialState) {
      throw new Error(
        `\x1b[33m[createFiber.validator]\x1b[0m Expected currentState "${expectedInitialState}" but found "${finalRecord.currentState}" for fiberId = ${cid} at ${url}.`
      );
    }

    if (finalRecord.sequenceNumber !== 0) {
      throw new Error(
        `\x1b[33m[createFiber.validator]\x1b[0m Expected sequenceNumber 0 for new fiber but found ${finalRecord.sequenceNumber} for fiberId = ${cid} at ${url}.`
      );
    }

    vlog(
      `\x1b[33m[createFiber.validator]\x1b[32m Fiber creation validated successfully for fiberId = ${cid} at ${url}!\x1b[0m`
    );
  }
};
