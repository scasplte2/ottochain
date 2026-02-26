import fs from 'fs';
import path from 'path';
import type { TransitionStateMachine, OttochainMessage } from '@ottochain/sdk';
import type { StatesMap } from '../types.ts';
import { validateEventLogs } from '../validateLogs.ts';

export interface ProcessEventOptions {
  event?: string;
  eventData?: { eventName?: string; payload?: unknown; eventType?: string; [key: string]: unknown };
  expectedState?: string;
  targetSequenceNumber?: number;
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
      console.log(
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
  }

  // US-8: Mandatory log endpoint validation
  if (ml0Urls && ml0Urls.length > 0) {
    const eventName =
      options.eventData?.eventName ??
      options.eventData?.eventType as string | undefined;
    await validateEventLogs({ ml0Urls, fiberId: cid }, eventName);
  }
};
