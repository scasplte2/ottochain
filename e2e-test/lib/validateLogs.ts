import { HttpClient } from '@ottochain/sdk';

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
    const client = new HttpClient(
      `${ml0Url}/data-application/v1/state-machines/${ctx.fiberId}`
    );

    const fiber = await client.get<Record<string, unknown>>('');
    if (!fiber) {
      throw new Error(
        `${TAG} State machine not found for fiberId = ${ctx.fiberId} at ${ml0Url}`
      );
    }

    const lastReceipt = fiber.lastReceipt as Record<string, unknown> | null;
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

    console.log(
      `${TAG}\x1b[32m Event receipt verified (${lastReceipt.eventName}: ${lastReceipt.fromState && lastReceipt.fromState} → ${lastReceipt.toState && lastReceipt.toState}) for fiberId = ${ctx.fiberId} at ${ml0Url}\x1b[0m`
    );
  }
}

/**
 * Validate that the latest OracleInvocation on a script oracle confirms
 * a successful invocation. Uses the ML0 custom route:
 *   GET /data-application/v1/oracles/{fiberId}
 *
 * The `lastInvocation` field on the oracle record contains the most recent
 * invocation result.
 */
export async function validateOracleLogs(
  ctx: LogValidationContext,
  expectedMethod?: string
): Promise<void> {
  for (const ml0Url of ctx.ml0Urls) {
    const client = new HttpClient(
      `${ml0Url}/data-application/v1/oracles/${ctx.fiberId}`
    );

    const oracle = await client.get<Record<string, unknown>>('');
    if (!oracle) {
      throw new Error(
        `${TAG} Oracle not found for fiberId = ${ctx.fiberId} at ${ml0Url}`
      );
    }

    const lastInvocation = oracle.lastInvocation as Record<string, unknown> | null;
    if (!lastInvocation) {
      throw new Error(
        `${TAG} No lastInvocation found on oracle for fiberId = ${ctx.fiberId} at ${ml0Url}`
      );
    }

    if (expectedMethod && lastInvocation.method !== expectedMethod) {
      throw new Error(
        `${TAG} Expected method="${expectedMethod}" but lastInvocation has "${lastInvocation.method}" for fiberId = ${ctx.fiberId} at ${ml0Url}`
      );
    }

    console.log(
      `${TAG}\x1b[32m Oracle invocation verified (method: ${lastInvocation.method}, result: ${JSON.stringify(lastInvocation.result)}) for fiberId = ${ctx.fiberId} at ${ml0Url}\x1b[0m`
    );
  }
}
