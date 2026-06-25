import fs from 'fs';
import path from 'path';
import type { TransitionStateMachine, OttochainMessage } from '@ottochain/sdk/core';
import type { StatesMap } from '../types.ts';
import { validateEventLogs } from '../validateLogs.ts';
import { vlog } from '../verbose.ts';

export interface ProcessEventOptions {
  event?: string;
  eventData?: { eventName?: string; payload?: unknown; eventType?: string; [key: string]: unknown };
  expectedState?: string;
  /** When set, assert the fiber's post-transition `stateData` deep-equals this object. */
  expectedStateData?: Record<string, unknown>;
  targetSequenceNumber?: number;
}

/**
 * Recursive, key-order-independent structural equality. Used to assert
 * `expectedStateData` against the fiber's post-transition `stateData` (a plain
 * JSON object/array tree of primitives) without depending on key ordering or on
 * the chain's canonical serialization. `bigint`/`number` compare by numeric value
 * so an int that decodes to a BigInt still matches a JSON literal.
 */
function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a === 'bigint' || typeof b === 'bigint') {
    if (
      (typeof a === 'bigint' || typeof a === 'number') &&
      (typeof b === 'bigint' || typeof b === 'number')
    ) {
      return BigInt(a as never) === BigInt(b as never);
    }
    return false;
  }
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') {
    return false;
  }
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((v, i) => deepEqual(v, b[i]));
  }
  const ao = a as Record<string, unknown>;
  const bo = b as Record<string, unknown>;
  const ak = Object.keys(ao);
  const bk = Object.keys(bo);
  if (ak.length !== bk.length) return false;
  return ak.every((k) => Object.prototype.hasOwnProperty.call(bo, k) && deepEqual(ao[k], bo[k]));
}

export const generator = ({ cid, options }: { cid: string; wallets?: unknown; options: ProcessEventOptions }): OttochainMessage => {
  let eventData: Record<string, unknown>;

  if (options.eventData && typeof options.eventData === 'object') {
    eventData = options.eventData;
  } else if (typeof options.event === 'string') {
    const eventPath = path.resolve(options.event);
    if (!fs.existsSync(eventPath)) {
      throw new Error(`Event file not found: ${eventPath}`);
    }
    eventData = JSON.parse(fs.readFileSync(eventPath, 'utf8'));
  } else {
    throw new Error(
      'Either options.event (path) or options.eventData (object) must be provided'
    );
  }

  // Support both new format { eventName, payload } and old format { eventType: { value }, payload }
  const eventName: string =
    (eventData.eventName as string) ??
    eventData.eventType as string;

  if (!eventName) {
    throw new Error('Event must have either "eventName" or "eventType"');
  }

  const msg: TransitionStateMachine = {
    fiberId: cid,
    eventName,
    payload: eventData.payload,
    targetSequenceNumber: options.targetSequenceNumber ?? 0,
  };

  return { TransitionStateMachine: msg };
};

export const validator = async ({ cid, statesMap, options, ml0Urls }: { cid: string; statesMap: StatesMap; options: ProcessEventOptions; wallets?: unknown; ml0Urls?: string[] }) => {
  for (const [url, { initial, final }] of Object.entries(statesMap)) {
    const initialRecord = initial?.state?.stateMachines?.[cid];
    const finalRecord = final?.state?.stateMachines?.[cid];

    if (!initialRecord) {
      throw new Error(
        `\x1b[33m[processEvent.validator]\x1b[0m No initial state machine fiber found for fiberId = ${cid} from ${url}.`
      );
    }

    if (!finalRecord) {
      throw new Error(
        `\x1b[33m[processEvent.validator]\x1b[0m No final state machine fiber found for fiberId = ${cid} from ${url}.`
      );
    }

    if (finalRecord.sequenceNumber <= initialRecord.sequenceNumber) {
      throw new Error(
        `\x1b[33m[processEvent.validator]\x1b[0m Expected sequence number to increase. Initial: ${initialRecord.sequenceNumber}, Final: ${finalRecord.sequenceNumber} for fiberId = ${cid} at ${url}.`
      );
    }

    // US-7: Use lastReceipt instead of lastEventStatus
    if (finalRecord.lastReceipt?.success) {
      vlog(
        `\x1b[33m[processEvent.validator]\x1b[32m Event processed successfully for fiberId = ${cid} at ${url}. ` +
          `Transition: ${finalRecord.lastReceipt.fromState} → ${finalRecord.lastReceipt.toState}\x1b[0m`
      );
    } else if (finalRecord.lastReceipt?.errorMessage) {
      console.log(
        `\x1b[33m[processEvent.validator]\x1b[31m Event failed for fiberId = ${cid} at ${url}: ${finalRecord.lastReceipt.errorMessage}\x1b[0m`
      );
    } else {
      console.log(
        `\x1b[33m[processEvent.validator]\x1b[33m Event processing status: ${JSON.stringify(finalRecord.lastReceipt)}\x1b[0m`
      );
    }

    if (options.expectedState) {
      if (finalRecord.currentState !== options.expectedState) {
        throw new Error(
          `\x1b[33m[processEvent.validator]\x1b[0m Expected state "${options.expectedState}" but found "${finalRecord.currentState}" for fiberId = ${cid} at ${url}.`
        );
      }
    }

    if (options.expectedStateData) {
      const got = finalRecord.stateData;
      if (!deepEqual(got, options.expectedStateData)) {
        throw new Error(
          `\x1b[33m[processEvent.validator]\x1b[0m expectedStateData mismatch for fiberId = ${cid} at ${url}: ` +
            `got ${JSON.stringify(got)} want ${JSON.stringify(options.expectedStateData)}.`
        );
      }
    }
  }

  // US-8: Mandatory log endpoint validation
  if (ml0Urls && ml0Urls.length > 0) {
    const eventName =
      options.eventData?.eventName ??
      options.eventData?.eventType as string | undefined;
    await validateEventLogs({ ml0Urls, fiberId: cid }, eventName);
  }
};
