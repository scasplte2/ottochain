/**
 * Ordinal-based ML0 confirmation with automatic resubmission.
 *
 * Instead of a fixed wall-clock timeout, this tracks ML0 snapshot ordinals.
 * If N ordinals pass without the transaction appearing in state, it resubmits.
 * This adapts to ML0's actual consensus speed rather than arbitrary timeouts.
 */
import { HttpClient } from '@ottochain/sdk';

const TAG = '\x1b[33m[ordinalConfirm]\x1b[0m';

export interface OrdinalConfirmationOptions {
  /** ML0 base URL (e.g., http://localhost:9200) */
  ml0BaseUrl: string;
  /** Entity path (e.g., state-machines/{id} or scripts/{id}) */
  entityPath: string;
  /** Predicate to check if the entity state is as expected */
  predicate: (data: unknown) => boolean;
  /** Function to resubmit the transaction (called on ordinal threshold) */
  resubmit: () => Promise<void>;
  /** Number of ordinals to wait before resubmitting (default: 5) */
  ordinalThreshold?: number;
  /** Maximum number of resubmissions (default: 3) */
  maxResubmits?: number;
  /** Polling interval in ms (default: 2000) */
  pollIntervalMs?: number;
  /**
   * Liveness gate: if the ML0 ordinal does not advance AT ALL for this long, the chain is
   * considered stalled (consensus not live) and we fail fast. This is the ONLY wall-clock gate
   * — a chain that keeps producing snapshots is given its full ordinal budget no matter how slow
   * each ordinal is. Decouples "slow chain" (keep waiting) from "dead chain" (fail). (default: 120000 = 2 min)
   */
  stallTimeoutMs?: number;
  /**
   * Optional absolute wall-clock ceiling, purely as a runaway backstop. Undefined (the default)
   * means no absolute cap: termination is guaranteed by the ordinal budget
   * (`ordinalThreshold` × (`maxResubmits` + 1) ordinals) and the stall gate.
   */
  maxTotalTimeMs?: number;
  /** Label for logging */
  label: string;
  /** Optional logger for buffered output */
  log?: { write: (s: string) => void };
  /**
   * Push-driven confirmation (webhook). When provided, the loop re-checks the entity the instant a
   * `snapshot.finalized` arrives (the chain commits read state BEFORE dispatching the webhook, so the
   * read is fresh) instead of blind-polling — and a matching `transaction.rejected` fails fast with
   * the on-chain reason instead of timing out as "not included".
   */
  waitNextSnapshot?: (timeoutMs: number) => Promise<void>;
  /** Returns the on-chain rejection for this update if one has arrived, else undefined. */
  checkRejection?: () => { ordinal: number; errors: { code: string; message: string }[] } | undefined;
}

interface OrdinalSnapshot {
  ordinal: number;
  hash: string;
}

/**
 * Fetch the current ML0 snapshot ordinal.
 */
async function getCurrentOrdinal(ml0BaseUrl: string): Promise<OrdinalSnapshot | null> {
  try {
    const client = new HttpClient(`${ml0BaseUrl}/snapshots/latest`);
    const snapshot = await client.get<unknown>('');
    if (!snapshot || typeof snapshot !== 'object') return null;

    // Handle both wrapped and unwrapped response formats
    const s = snapshot as Record<string, unknown>;
    const value = (s.value as Record<string, unknown>) ?? s;
    const ordinal = value.ordinal as number | undefined;
    const hash = (value.hash as string) ?? '';

    if (typeof ordinal !== 'number') return null;
    return { ordinal, hash };
  } catch {
    return null;
  }
}

/**
 * Check if the entity exists and satisfies the predicate.
 */
async function checkEntity(
  ml0BaseUrl: string,
  entityPath: string,
  predicate: (data: unknown) => boolean
): Promise<boolean> {
  try {
    const client = new HttpClient(`${ml0BaseUrl}/data-application/v1/${entityPath}`);
    const data = await client.get<unknown>('');
    return predicate(data);
  } catch {
    return false;
  }
}

/**
 * Wait for ML0 confirmation using ordinal-based tracking with auto-resubmit.
 *
 * The budget is measured in ML0 *ordinals*, not wall-clock: a chain that keeps producing
 * snapshots — however slowly — is given its full ordinal budget. Wall-clock only ever fails the
 * wait through the stall gate, which fires when the ordinal stops advancing entirely (consensus
 * dead). This decouples "slow chain" (keep waiting) from "dead chain" (fail fast) — the metagraph's
 * idle snapshot cadence can be tens of seconds per ordinal, so a fixed wall-clock cap used to kill
 * confirmations that were merely slow, not stuck.
 *
 * Algorithm:
 * 1. Record start ordinal; remember the last time the ordinal advanced.
 * 2. Poll entity state every pollIntervalMs.
 * 3. If predicate satisfied → success.
 * 4. If the ordinal has not advanced for `stallTimeoutMs` → fail (consensus not live). This also
 *    covers ML0 being unreachable (ordinal reads return null → no advance).
 * 5. If (currentOrdinal - startOrdinal) >= ordinalThreshold → resubmit, reset the window, bump the
 *    resubmit counter.
 * 6. If resubmits are exhausted and another full threshold passes → fail (ordinal budget spent).
 * 7. `maxTotalTimeMs`, if set, is an absolute runaway backstop only.
 *
 * Termination is guaranteed: while the chain advances, the ordinal budget is consumed; if it stops
 * advancing, the stall gate fires.
 */
export async function waitForOrdinalConfirmation(
  opts: OrdinalConfirmationOptions
): Promise<void> {
  const {
    ml0BaseUrl,
    entityPath,
    predicate,
    resubmit,
    ordinalThreshold = 5,
    maxResubmits = 3,
    pollIntervalMs = 2000,
    stallTimeoutMs = 120000,
    maxTotalTimeMs,
    label,
    log,
    waitNextSnapshot,
    checkRejection,
  } = opts;

  const w = log ? (s: string) => log.write(s) : (s: string) => process.stdout.write(s);
  const startTime = Date.now();
  let resubmitCount = 0;

  // Get initial ordinal
  let startSnapshot = await getCurrentOrdinal(ml0BaseUrl);
  if (!startSnapshot) {
    w(`\n      ⏳ ${TAG} Waiting for ML0 ordinal...`);
    // Wait a bit for ML0 to be ready
    for (let i = 0; i < 10; i++) {
      await new Promise((r) => setTimeout(r, 1000));
      startSnapshot = await getCurrentOrdinal(ml0BaseUrl);
      if (startSnapshot) break;
      w('.');
    }
    if (!startSnapshot) {
      throw new Error(`${TAG} Could not fetch ML0 ordinal for ${label}`);
    }
  }

  w(`\n      ⏳ ML0 confirm ${label} (ord=${startSnapshot.ordinal})`);

  // Liveness tracking: the highest ordinal we've seen and when we last saw it advance.
  // The stall gate keys off lack-of-progress, NOT total elapsed time, so a slow-but-live
  // chain is never killed mid-flight.
  let lastObservedOrdinal = startSnapshot.ordinal;
  let lastAdvanceTime = Date.now();

  while (true) {
    // Stall gate (also covers ML0 unreachable: ordinal never advances → fires here).
    if (Date.now() - lastAdvanceTime > stallTimeoutMs) {
      w(' ✗ (stalled)\n');
      throw new Error(
        `${TAG} ML0 ordinal stuck at ${lastObservedOrdinal} for ${stallTimeoutMs}ms for ${label} ` +
          `— consensus not live (${resubmitCount} resubmits)`
      );
    }

    // Absolute runaway backstop (opt-in).
    if (maxTotalTimeMs !== undefined && Date.now() - startTime > maxTotalTimeMs) {
      w(' ✗ (abs-timeout)\n');
      throw new Error(
        `${TAG} Confirmation hit absolute ceiling for ${label} after ${maxTotalTimeMs}ms ` +
          `(${resubmitCount} resubmits, ordinal ${lastObservedOrdinal})`
      );
    }

    // Check entity state
    const satisfied = await checkEntity(ml0BaseUrl, entityPath, predicate);
    if (satisfied) {
      w(' ✓\n');
      return;
    }

    // Explicit on-chain rejection (webhook): fail fast with the reason instead of timing out as
    // "not included". This is the deterministic signal the blind poll never had.
    const rejection = checkRejection?.();
    if (rejection) {
      w(' ✗ (rejected)\n');
      throw new Error(
        `${TAG} ${label} was REJECTED at ML0 (ordinal ${rejection.ordinal}): ` +
          rejection.errors.map((e) => `${e.code}: ${e.message}`).join('; ')
      );
    }

    // Check current ordinal
    const currentSnapshot = await getCurrentOrdinal(ml0BaseUrl);
    if (currentSnapshot) {
      // Liveness: reset the stall clock whenever the chain makes progress.
      if (currentSnapshot.ordinal > lastObservedOrdinal) {
        lastObservedOrdinal = currentSnapshot.ordinal;
        lastAdvanceTime = Date.now();
      }

      const ordinalDelta = currentSnapshot.ordinal - startSnapshot.ordinal;

      if (ordinalDelta >= ordinalThreshold) {
        // Ordinals passed without our tx surfacing — resubmit (a re-attempt for a genuinely-unlanded
        // update). The DUPLICATE-seq hazard (resubmitting an already-applied update → DL1 block with a
        // duplicate seq → SequenceNumberMismatch) is handled inside `resubmit()` itself: it re-reads
        // the fiber and skips the send if it already advanced. By now the lagging read has had a full
        // threshold window to catch up, so that check is reliable.
        if (resubmitCount >= maxResubmits) {
          w(' ✗ (ordinal budget exhausted)\n');
          throw new Error(
            `${TAG} Confirmation failed for ${label}: transaction not included after ` +
              `${ordinalThreshold} ordinals × ${maxResubmits + 1} attempts ` +
              `(final ordinal: ${currentSnapshot.ordinal})`
          );
        }

        resubmitCount++;
        w(` [resubmit #${resubmitCount} at ord=${currentSnapshot.ordinal}]`);

        try {
          await resubmit();
        } catch (err) {
          // Resubmit failed — log but continue polling in case original tx lands
          w(` (resubmit err: ${(err as Error).message.slice(0, 50)})`);
        }

        // Reset start ordinal for next threshold window
        startSnapshot = currentSnapshot;
      }
    }

    w('.');
    // Wake on the next `snapshot.finalized` push — read state is committed before the webhook fires,
    // so the next checkEntity sees fresh state — or fall back to the poll interval if no webhook.
    if (waitNextSnapshot) {
      await waitNextSnapshot(pollIntervalMs);
    } else {
      await new Promise((r) => setTimeout(r, pollIntervalMs));
    }
  }
}

export interface OrdinalAdvanceOptions {
  /** Polling interval in ms (default: 2000) */
  pollIntervalMs?: number;
  /** Maximum total time to wait in ms (default: 300000 = 5 min) */
  maxTotalTimeMs?: number;
  /** Label for logging */
  label?: string;
  /** Optional logger for buffered output */
  log?: { write: (s: string) => void };
}

/**
 * Wait for ML0 to PRODUCE new snapshots — i.e. for the snapshot ordinal to advance by `minAdvance`
 * from its first reading.
 *
 * Unlike `waitForOrdinalConfirmation`, this submits nothing and checks no entity; it is a pure
 * liveness probe on `/snapshots/latest` (which can answer while the data-application still 500s).
 * Used as a one-time cluster warmup gate: proving block production is past the cold-start (a fresh
 * metakit jar + JVM warmup slow the first snapshots) BEFORE the timed flows means the per-flow sync
 * budgets aren't each charged the cold-start.
 *
 * Resolves once the ordinal advances by `minAdvance`; throws if that does not happen within
 * `maxTotalTimeMs` (ML0 not producing → consensus not live).
 */
export async function waitForOrdinalAdvance(
  ml0BaseUrl: string,
  minAdvance: number,
  opts: OrdinalAdvanceOptions = {}
): Promise<void> {
  const {
    pollIntervalMs = 2000,
    maxTotalTimeMs = 300000,
    label = 'ordinal advance',
    log,
  } = opts;

  const w = log ? (s: string) => log.write(s) : (s: string) => process.stdout.write(s);
  const startTime = Date.now();

  w(`\x1b[36mWarming up cluster\x1b[0m — waiting for ML0 to produce ${minAdvance} snapshot(s) for ${label}`);

  let startOrdinal: number | null = null;
  let lastOrdinal: number | null = null;
  while (Date.now() - startTime <= maxTotalTimeMs) {
    const snapshot = await getCurrentOrdinal(ml0BaseUrl);
    if (snapshot) {
      if (startOrdinal === null) startOrdinal = snapshot.ordinal;
      lastOrdinal = snapshot.ordinal;
      if (snapshot.ordinal >= startOrdinal + minAdvance) {
        w(` ✓ (ordinal ${startOrdinal} → ${snapshot.ordinal})\n`);
        return;
      }
    }
    w('.');
    await new Promise((r) => setTimeout(r, pollIntervalMs));
  }

  w(' ✗\n');
  throw new Error(
    `${TAG} ML0 did not produce ${minAdvance} snapshot(s) within ${maxTotalTimeMs}ms for ${label} ` +
      `(start ordinal ${startOrdinal ?? 'none'}, last ${lastOrdinal ?? 'none'}) — consensus not live`
  );
}

/**
 * Simple helper to get current ordinal (for external use).
 */
export async function getML0Ordinal(ml0BaseUrl: string): Promise<number | null> {
  const snapshot = await getCurrentOrdinal(ml0BaseUrl);
  return snapshot?.ordinal ?? null;
}
