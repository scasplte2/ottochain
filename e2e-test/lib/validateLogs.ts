import { OttoMetagraphClient } from '@ottochain/sdk';
import { vlog } from './verbose.ts';

const TAG = '\x1b[33m[validateLogs]\x1b[0m';

export interface LogValidationContext {
  ml0Urls: string[];
  fiberId: string;
}

/**
 * Validate that the latest EventReceipt on a state machine fiber confirms
 * a successful event processing. Uses the ML0 custom route:
 *   GET /data-application/v1/state-machines/{fiberId}
 *
 * The `lastReceipt` field on the fiber record contains the most recent
 * event processing result.
 */
export async function validateEventLogs(
  ctx: LogValidationContext,
  expectedEventName?: string
): Promise<void> {
  for (const ml0Url of ctx.ml0Urls) {
    // Typed read: `getStateMachine` returns the parsed `StateMachineFiberRecord` (or null on 404),
    // so `lastReceipt` below is a typed `EventReceipt` — a chain-side shape drift breaks here.
    const client = new OttoMetagraphClient({ ml0Url });

    const fiber = await client.getStateMachine(ctx.fiberId);
    if (!fiber) {
      throw new Error(
        `${TAG} State machine not found for fiberId = ${ctx.fiberId} at ${ml0Url}`
      );
    }

    const lastReceipt = fiber.lastReceipt;
    if (!lastReceipt) {
      throw new Error(
        `${TAG} No lastReceipt found on state machine for fiberId = ${ctx.fiberId} at ${ml0Url}`
      );
    }

    if (!lastReceipt.success) {
      throw new Error(
        `${TAG} lastReceipt indicates failure for fiberId = ${ctx.fiberId} at ${ml0Url}: ${lastReceipt.errorMessage}`
      );
    }

    if (expectedEventName && lastReceipt.eventName !== expectedEventName) {
      throw new Error(
        `${TAG} Expected eventName="${expectedEventName}" but lastReceipt has "${lastReceipt.eventName}" for fiberId = ${ctx.fiberId} at ${ml0Url}`
      );
    }

    vlog(
      `${TAG}\x1b[32m Event receipt verified (${lastReceipt.eventName}: ${lastReceipt.fromState && lastReceipt.fromState} → ${lastReceipt.toState && lastReceipt.toState}) for fiberId = ${ctx.fiberId} at ${ml0Url}\x1b[0m`
    );
  }
}

/**
 * Validate that the latest ScriptInvocation on a script script confirms
 * a successful invocation. Uses the ML0 custom route:
 *   GET /data-application/v1/scripts/{fiberId}
 *
 * The `lastInvocation` field on the script record contains the most recent
 * invocation result.
 */
export async function validateScriptLogs(
  ctx: LogValidationContext,
  expectedMethod?: string
): Promise<void> {
  for (const ml0Url of ctx.ml0Urls) {
    // Typed read: `getScript` returns the parsed `ScriptFiberRecord` (or null on 404),
    // so `lastInvocation` below is a typed `ScriptInvocation`.
    const client = new OttoMetagraphClient({ ml0Url });

    const script = await client.getScript(ctx.fiberId);
    if (!script) {
      throw new Error(
        `${TAG} Script not found for fiberId = ${ctx.fiberId} at ${ml0Url}`
      );
    }

    const lastInvocation = script.lastInvocation;
    if (!lastInvocation) {
      throw new Error(
        `${TAG} No lastInvocation found on script for fiberId = ${ctx.fiberId} at ${ml0Url}`
      );
    }

    if (expectedMethod && lastInvocation.method !== expectedMethod) {
      throw new Error(
        `${TAG} Expected method="${expectedMethod}" but lastInvocation has "${lastInvocation.method}" for fiberId = ${ctx.fiberId} at ${ml0Url}`
      );
    }

    vlog(
      `${TAG}\x1b[32m Script invocation verified (method: ${lastInvocation.method}, result: ${JSON.stringify(lastInvocation.result)}) for fiberId = ${ctx.fiberId} at ${ml0Url}\x1b[0m`
    );
  }
}
