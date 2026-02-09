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
  /** Entity path (e.g., state-machines/{id} or oracles/{id}) */
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
  /** Maximum total time to wait in ms (default: 300000 = 5 min) */
  maxTotalTimeMs?: number;
  /** Label for logging */
  label: string;
  /** Optional logger for buffered output */
  log?: { write: (s: string) => void };
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
 * Algorithm:
 * 1. Record start ordinal
 * 2. Poll entity state every pollIntervalMs
 * 3. If predicate satisfied → success
 * 4. If (currentOrdinal - startOrdinal) >= ordinalThreshold:
 *    - Resubmit the transaction
 *    - Reset startOrdinal to currentOrdinal
 *    - Increment resubmit counter
 * 5. If resubmits exhausted or maxTotalTime exceeded → fail
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
    maxTotalTimeMs = 300000,
    label,
    log,
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

  while (true) {
    // Check timeout
    if (Date.now() - startTime > maxTotalTimeMs) {
      w(' ✗ (timeout)\n');
      throw new Error(
        `${TAG} Confirmation timed out for ${label} after ${maxTotalTimeMs}ms ` +
          `(${resubmitCount} resubmits)`
      );
    }

    // Check entity state
    const satisfied = await checkEntity(ml0BaseUrl, entityPath, predicate);
    if (satisfied) {
      w(' ✓\n');
      return;
    }

    // Check current ordinal
    const currentSnapshot = await getCurrentOrdinal(ml0BaseUrl);
    if (currentSnapshot) {
      const ordinalDelta = currentSnapshot.ordinal - startSnapshot.ordinal;

      if (ordinalDelta >= ordinalThreshold) {
        // Ordinals passed without our tx appearing — resubmit
        if (resubmitCount >= maxResubmits) {
          w(' ✗ (resubmits exhausted)\n');
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
    await new Promise((r) => setTimeout(r, pollIntervalMs));
  }
}

/**
 * Simple helper to get current ordinal (for external use).
 */
export async function getML0Ordinal(ml0BaseUrl: string): Promise<number | null> {
  const snapshot = await getCurrentOrdinal(ml0BaseUrl);
  return snapshot?.ordinal ?? null;
}
