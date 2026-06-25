import type { OttochainMessage } from '@ottochain/sdk/core';
import type { StatesMap } from '../types.ts';
import { vlog } from '../verbose.ts';

export interface ArchiveFiberOptions {
  targetSequenceNumber?: number;
}

export const generator = ({ cid, options }: { cid: string; wallets?: unknown; options?: ArchiveFiberOptions }): OttochainMessage => {
  return {
    ArchiveStateMachine: {
      fiberId: cid,
      // ArchiveStateMachine is Sequenced — the chain requires the fiber's current sequence number.
      targetSequenceNumber: options?.targetSequenceNumber ?? 0,
    },
  };
};

export const validator = ({ cid, statesMap }: { cid: string; statesMap: StatesMap }) => {
  for (const [url, { initial, final }] of Object.entries(statesMap)) {
    const initialRecord = initial?.state?.stateMachines?.[cid];
    const finalRecord = final?.state?.stateMachines?.[cid];

    if (!initialRecord) {
      throw new Error(
        `\x1b[33m[archiveFiber.validator]\x1b[0m No initial state machine fiber found for fiberId = ${cid} from ${url}.`
      );
    }

    if (!finalRecord) {
      throw new Error(
        `\x1b[33m[archiveFiber.validator]\x1b[0m No final state machine fiber found for fiberId = ${cid} from ${url}.`
      );
    }

    if (finalRecord.status !== 'ARCHIVED') {
      throw new Error(
        `\x1b[33m[archiveFiber.validator]\x1b[0m Expected fiber status "ARCHIVED" but found "${finalRecord.status}" for fiberId = ${cid} at ${url}.`
      );
    }

    vlog(
      `\x1b[33m[archiveFiber.validator]\x1b[32m Fiber archived successfully for fiberId = ${cid} at ${url}!\x1b[0m`
    );
  }
};
