import fs from 'fs';
import path from 'path';
import type { OttochainMessage } from '@ottochain/sdk/core';
import type { StatesMap } from '../types.ts';
import { validateScriptLogs } from '../validateLogs.ts';
import { vlog } from '../verbose.ts';

export interface InvokeScriptOptions {
  method: string;
  args?: string | object;
  argsData?: object;
  expectedResult?: string | unknown;
  targetSequenceNumber?: number;
}

export const generator = ({ cid, options }: { cid: string; wallets?: unknown; options: InvokeScriptOptions }): OttochainMessage => {
  let args: unknown = {};

  if (options.argsData && typeof options.argsData === 'object') {
    args = options.argsData;
  } else if (options.args) {
    if (typeof options.args === 'string') {
      const argsPath = path.resolve(options.args);
      if (!fs.existsSync(argsPath)) {
        throw new Error(`Args file not found: ${argsPath}`);
      }
      args = JSON.parse(fs.readFileSync(argsPath, 'utf8'));
    } else if (typeof options.args === 'object') {
      args = options.args;
    }
  }

  // US-6: cid → fiberId
  return {
    InvokeScript: {
      fiberId: cid,
      method: options.method,
      args,
      targetSequenceNumber: options.targetSequenceNumber ?? 0,
    },
  };
};

export const validator = async ({ cid, statesMap, options, ml0Urls }: { cid: string; statesMap: StatesMap; options: InvokeScriptOptions; wallets?: unknown; ml0Urls?: string[] }) => {
  for (const [url, { initial, final }] of Object.entries(statesMap)) {
    const initialRecord = initial?.state?.scripts?.[cid];
    const finalRecord = final?.state?.scripts?.[cid];

    if (!initialRecord) {
      throw new Error(
        `\x1b[33m[invokeScript.validator]\x1b[0m No initial script script found for fiberId = ${cid} from ${url}.`
      );
    }

    if (!finalRecord) {
      throw new Error(
        `\x1b[33m[invokeScript.validator]\x1b[0m No final script script found for fiberId = ${cid} from ${url}.`
      );
    }

    // US-7: invocationCount → sequenceNumber
    if (finalRecord.sequenceNumber <= initialRecord.sequenceNumber) {
      throw new Error(
        `\x1b[33m[invokeScript.validator]\x1b[0m Expected sequenceNumber to increase. Initial: ${initialRecord.sequenceNumber}, Final: ${finalRecord.sequenceNumber} for fiberId = ${cid} at ${url}.`
      );
    }

    // US-7: invocationLog[0] → lastInvocation
    const latestInvocation = finalRecord.lastInvocation;
    if (latestInvocation) {
      vlog(
        `\x1b[33m[invokeScript.validator]\x1b[32m Script invoked successfully for fiberId = ${cid} at ${url}.`
      );
      vlog(`\x1b[33m[invokeScript.validator]\x1b[0m   Method: ${latestInvocation.method}`);
      vlog(
        `\x1b[33m[invokeScript.validator]\x1b[0m   Result: ${JSON.stringify(latestInvocation.result)}`
      );
      vlog(`\x1b[33m[invokeScript.validator]\x1b[0m   Gas Used: ${latestInvocation.gasUsed}`);

      if (options.expectedResult !== undefined) {
        const expectedResult =
          typeof options.expectedResult === 'string'
            ? JSON.parse(options.expectedResult)
            : options.expectedResult;
        const actualResult = latestInvocation.result;

        if (JSON.stringify(actualResult) !== JSON.stringify(expectedResult)) {
          throw new Error(
            `\x1b[33m[invokeScript.validator]\x1b[0m Expected result ${JSON.stringify(expectedResult)} but got ${JSON.stringify(actualResult)} for fiberId = ${cid} at ${url}.`
          );
        }
      }
    } else {
      console.log(
        `\x1b[33m[invokeScript.validator]\x1b[33m Script sequenceNumber increased but no lastInvocation found for fiberId = ${cid} at ${url}.\x1b[0m`
      );
    }
  }

  // US-8: Mandatory log endpoint validation
  if (ml0Urls && ml0Urls.length > 0) {
    await validateScriptLogs({ ml0Urls, fiberId: cid }, options.method);
  }
};
