import fs from 'fs';
import path from 'path';
import type { OttochainMessage } from '@ottochain/sdk';
import type { StatesMap } from '../types.ts';
import { validateOracleLogs } from '../validateLogs.ts';

export interface InvokeOracleOptions {
  method: string;
  args?: string | object;
  argsData?: object;
  expectedResult?: string | unknown;
  targetSequenceNumber?: number;
}

export const generator = ({ cid, options }: { cid: string; wallets?: unknown; options: InvokeOracleOptions }): OttochainMessage => {
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

export const validator = async ({ cid, statesMap, options, ml0Urls }: { cid: string; statesMap: StatesMap; options: InvokeOracleOptions; wallets?: unknown; ml0Urls?: string[] }) => {
  for (const [url, { initial, final }] of Object.entries(statesMap)) {
    const initialRecord = initial?.state?.scripts?.[cid];
    const finalRecord = final?.state?.scripts?.[cid];

    if (!initialRecord) {
      throw new Error(
        `\x1b[33m[invokeScript.validator]\x1b[0m No initial script oracle found for fiberId = ${cid} from ${url}.`
      );
    }

    if (!finalRecord) {
      throw new Error(
        `\x1b[33m[invokeScript.validator]\x1b[0m No final script oracle found for fiberId = ${cid} from ${url}.`
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
      console.log(
        `\x1b[33m[invokeScript.validator]\x1b[32m Oracle invoked successfully for fiberId = ${cid} at ${url}.`
      );
      console.log(
        `\x1b[33m[invokeScript.validator]\x1b[0m   Method: ${latestInvocation.method}`
      );
      console.log(
        `\x1b[33m[invokeScript.validator]\x1b[0m   Result: ${JSON.stringify(latestInvocation.result)}`
      );
      console.log(
        `\x1b[33m[invokeScript.validator]\x1b[0m   Gas Used: ${latestInvocation.gasUsed}`
      );

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
        `\x1b[33m[invokeScript.validator]\x1b[33m Oracle sequenceNumber increased but no lastInvocation found for fiberId = ${cid} at ${url}.\x1b[0m`
      );
    }
  }

  // US-8: Mandatory log endpoint validation
  if (ml0Urls && ml0Urls.length > 0) {
    await validateOracleLogs({ ml0Urls, fiberId: cid }, options.method);
  }
};
