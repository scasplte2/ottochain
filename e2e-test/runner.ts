/**
 * Ottochain E2E Test Runner
 *
 * Discovers all examples with testFlows and runs them automatically.
 * Reports pass/fail per flow with step-level detail.
 * Exits with non-zero code on any failure (CI-friendly).
 *
 * Usage: npx tsx runner.ts [--target local|ci|remote] [--wallets alice,bob]
 */
import 'dotenv/config';

import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import generateWallet from './lib/generateWallet.ts';
import sendSignedUpdate from './lib/sendDataTransaction.ts';
import getMetagraphEnv from './lib/metagraphEnv.ts';
import type { StatesMap, Wallets, GeneratorFn, ValidatorFn } from './lib/types.ts';
import { HttpClient } from '@ottochain/sdk';
import { waitForOrdinalConfirmation, waitForOrdinalAdvance } from './lib/ordinalConfirmation.ts';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const examplesDir = path.join(__dirname, 'examples');

// ---------------------------------------------------------------------------
// CLI arguments (minimal — no commander needed)
// ---------------------------------------------------------------------------

function parseArgs() {
  const args = process.argv.slice(2);
  const opts: Record<string, string> = {
    target: 'local',
    wallets: 'alice',
    waitTime: '5',
    retryDelay: '5',
    // maxRetries × retryDelay(5s) = per ML0-confirmation / DL1-sync / validation wait budget.
    // The one-time `waitForOrdinalAdvance` warmup gate (below, #169) pays the cluster cold-start —
    // a fresh metakit jar (cold cache) + JVM warmup slowing first-block production — BEFORE the
    // timed flows (on its own wall-clock budget), so this per-flow budget is mostly a cushion for a
    // slow/contended CI runner. 40 (~200s) keeps that cushion (was 20 ≈ 100s; intermittent
    // `DL1 sync timed out … (seqNum=…)` flake, #167/#168).
    maxRetries: '40',
    parallel: 'true',
  };

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--sequential') {
      opts.parallel = 'false';
    } else if (args[i].startsWith('--') && i + 1 < args.length) {
      opts[args[i].slice(2)] = args[i + 1];
      i++;
    }
  }

  return opts;
}

// ---------------------------------------------------------------------------
// Buffered logger — captures output per flow to avoid interleaved output
// ---------------------------------------------------------------------------

class FlowLogger {
  private lines: string[] = [];
  private currentLine = '';
  public readonly tag: string;

  constructor(tag: string) {
    this.tag = tag;
  }

  write(text: string): void {
    this.currentLine += text;
    if (text.includes('\n')) {
      const parts = this.currentLine.split('\n');
      // All but the last part are complete lines
      for (let i = 0; i < parts.length - 1; i++) {
        this.lines.push(parts[i]);
      }
      this.currentLine = parts[parts.length - 1];
    }
  }

  log(...args: unknown[]): void {
    const text = args.map(String).join(' ');
    if (this.currentLine) {
      this.lines.push(this.currentLine + text);
      this.currentLine = '';
    } else {
      this.lines.push(text);
    }
  }

  flush(): void {
    if (this.currentLine) {
      this.lines.push(this.currentLine);
      this.currentLine = '';
    }
    if (this.lines.length > 0) {
      console.log(this.lines.join('\n'));
      this.lines = [];
    }
  }
}

// ---------------------------------------------------------------------------
// Shared helpers (same as terminal.ts)
// ---------------------------------------------------------------------------

async function loadFileOrModule(
  filePath: string,
  context: Record<string, unknown> = {}
): Promise<unknown> {
  const hasJsExt = filePath.endsWith('.js');
  const hasJsonExt = filePath.endsWith('.json');
  const hasTsExt = filePath.endsWith('.ts');
  const hasNoExt = !hasJsExt && !hasJsonExt && !hasTsExt;

  let tsPath: string, jsPath: string, jsonPath: string;
  if (hasNoExt) {
    tsPath = filePath + '.ts';
    jsPath = filePath + '.js';
    jsonPath = filePath + '.json';
  } else if (hasTsExt) {
    tsPath = filePath;
    jsPath = filePath.replace(/\.ts$/, '.js');
    jsonPath = filePath.replace(/\.ts$/, '.json');
  } else if (hasJsExt) {
    tsPath = filePath.replace(/\.js$/, '.ts');
    jsPath = filePath;
    jsonPath = filePath.replace(/\.js$/, '.json');
  } else {
    tsPath = filePath.replace(/\.json$/, '.ts');
    jsPath = filePath.replace(/\.json$/, '.js');
    jsonPath = filePath;
  }

  for (const candidate of [tsPath, jsPath]) {
    if (fs.existsSync(candidate)) {
      const mod = await import(candidate);
      const exported = mod.default ?? mod;
      return typeof exported === 'function' ? exported(context) : exported;
    }
  }

  if (fs.existsSync(jsonPath)) {
    return JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
  }

  throw new Error(`File not found: ${filePath} (tried .ts, .js, .json)`);
}

async function getApplicationState(url: string): Promise<unknown> {
  const client = new HttpClient(url);
  return client.get<unknown>('');
}

async function getInitialStates(urls: string[]): Promise<StatesMap> {
  const initialStates = await Promise.all(
    urls.map((url) => getApplicationState(url))
  );

  const statesMap: StatesMap = {};
  for (let i = 0; i < urls.length; i++) {
    statesMap[urls[i]] = {
      initial: initialStates[i],
      final: null,
    };
  }

  return statesMap;
}

async function updateFinalStates(statesMap: StatesMap): Promise<StatesMap> {
  const urls = Object.keys(statesMap);
  const finalStates = await Promise.all(
    urls.map((url) => getApplicationState(url))
  );

  for (let i = 0; i < urls.length; i++) {
    statesMap[urls[i]].final = finalStates[i];
  }

  return statesMap;
}

async function validateWithRetries(
  txValidation: ValidatorFn,
  cid: string,
  statesMap: StatesMap,
  options: Record<string, unknown>,
  wallets: Wallets,
  maxRetries: number,
  retryDelayMs: number,
  ml0Urls: string[],
  attempt = 1
): Promise<void> {
  try {
    const updated = await updateFinalStates(statesMap);
    await txValidation({ cid, statesMap: updated, options, wallets, ml0Urls });
  } catch (err) {
    if (attempt >= maxRetries) {
      throw err;
    }
    await new Promise((resolve) => setTimeout(resolve, retryDelayMs));

    // Refresh final states for retry
    let refreshed: StatesMap;
    try {
      refreshed = await updateFinalStates(statesMap);
    } catch {
      throw err; // Can't refresh, re-throw original error
    }
    return validateWithRetries(
      txValidation, cid, refreshed, options, wallets,
      maxRetries, retryDelayMs, ml0Urls, attempt + 1
    );
  }
}

/**
 * Poll an ML0 endpoint until a condition is met.
 * Uses the ML0 custom routes (e.g. /v1/state-machines/{id}, /v1/scripts/{id}).
 */
async function waitForMl0Confirmation(
  ml0BaseUrl: string,
  entityPath: string,
  predicate: (data: unknown) => boolean,
  maxRetries: number,
  retryDelayMs: number,
  label: string,
  log?: FlowLogger
): Promise<void> {
  const url = `${ml0BaseUrl}/data-application/v1/${entityPath}`;
  const client = new HttpClient(url);
  const w = log ? (s: string) => log.write(s) : (s: string) => process.stdout.write(s);
  w(`\n      ⏳ ML0 confirm ${label}`);

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const data = await client.get<unknown>('');
      if (predicate(data)) {
        w(' ✓\n');
        return;
      }
    } catch {
      // Entity may not exist yet — continue polling
    }
    w('.');
    if (attempt < maxRetries) {
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
    }
  }

  w(' ✗\n');
  throw new Error(
    `ML0 confirmation timed out for ${label} at ${url} after ${maxRetries} attempts`
  );
}

/**
 * Wait until a DL1 node's OnChain state reflects a fiber commit that matches
 * the expected sequence number (or simply exists, for create steps).
 *
 * Compares the DL1's /v1/onchain fiberCommits against what ML0 has already
 * confirmed, ensuring snapshot propagation (ML0 → GL0 → DL1) has completed
 * before the runner sends the next step.
 */
async function waitForDl1Sync(
  dl1BaseUrl: string,
  fiberId: string,
  expectedSeqNum: number | null,
  maxRetries: number,
  retryDelayMs: number,
  label: string,
  log?: FlowLogger
): Promise<void> {
  const url = `${dl1BaseUrl}/data-application/v1/onchain`;
  const client = new HttpClient(url);
  const seqLabel = expectedSeqNum === null ? 'exists' : `seq≥${expectedSeqNum}`;
  const w = log ? (s: string) => log.write(s) : (s: string) => process.stdout.write(s);
  w(`      ⏳ DL1 sync ${fiberId.slice(0, 8)}… (${seqLabel})`);

  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const onChain = (await client.get<unknown>('')) as {
        fiberCommits?: Record<string, { sequenceNumber?: number }>;
      } | null;

      if (onChain?.fiberCommits) {
        const commit = onChain.fiberCommits[fiberId];
        if (commit) {
          if (expectedSeqNum === null) {
            w(' ✓\n');
            return;
          }
          if (commit.sequenceNumber !== undefined && commit.sequenceNumber >= expectedSeqNum) {
            w(' ✓\n');
            return;
          }
        }
      }
    } catch {
      // DL1 may not be ready yet — continue polling
    }
    w('.');
    if (attempt < maxRetries) {
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs));
    }
  }

  w(' ✗\n');
  throw new Error(
    `DL1 sync timed out for ${label} at ${url} after ${maxRetries} attempts ` +
      `(waiting for fiberId=${fiberId} seqNum=${expectedSeqNum ?? 'exists'})`
  );
}

// ---------------------------------------------------------------------------
// Example discovery
// ---------------------------------------------------------------------------

interface TestStep {
  action: string;
  definition?: string;
  initialData?: string;
  event?: string;
  eventData?: Record<string, unknown>;
  expectedState?: string;
  method?: string;
  args?: string;
  expectedResult?: unknown;
  [key: string]: unknown;
}

interface TestFlow {
  name: string;
  description: string;
  steps: TestStep[];
}

interface Example {
  dir: string;
  name: string;
  description: string;
  type: string;
  scriptFiberId?: string;
  testFlows: TestFlow[];
  [key: string]: unknown;
}

async function discoverExamples(): Promise<Example[]> {
  const dirs = fs
    .readdirSync(examplesDir)
    .filter((f) => fs.statSync(path.join(examplesDir, f)).isDirectory());

  const examples = await Promise.all(
    dirs.map(async (dir): Promise<Example | null> => {
      try {
        const example = (await loadFileOrModule(
          path.join(examplesDir, dir, 'example'),
          {}
        )) as Record<string, unknown>;

        if (!Array.isArray(example.testFlows) || example.testFlows.length === 0) {
          return null;
        }

        return { dir, ...example } as Example;
      } catch {
        return null;
      }
    })
  );

  return examples.filter((e): e is Example => e !== null);
}

// ---------------------------------------------------------------------------
// Flow execution
// ---------------------------------------------------------------------------

async function runFlow(
  example: Example,
  flow: TestFlow,
  env: ReturnType<typeof getMetagraphEnv>,
  wallets: Wallets,
  ml0Urls: string[],
  ml0Env: string[],
  dl1Urls: string[],
  maxRetries: number,
  retryDelayMs: number,
  waitTimeMs: number,
  log?: FlowLogger
): Promise<{ passed: boolean; error?: string; failedStep?: number }> {
  const w = log ? (s: string) => log.write(s) : (s: string) => process.stdout.write(s);
  const l = log ? (...a: unknown[]) => log.log(...a) : (...a: unknown[]) => console.log(...a);
  const session = {
    cid: crypto.randomUUID(),
    scriptFiberId: null as string | null,
  };

  for (let i = 0; i < flow.steps.length; i++) {
    const step = flow.steps[i];
    const stepLabel = `[Step ${i + 1}/${flow.steps.length}]`;
    w(`  ${stepLabel} ${step.action}...`);

    try {
      let generator: GeneratorFn;
      let validator: ValidatorFn;
      let message: unknown;
      let stepOptions: Record<string, unknown>;

      // ---- Registry ops (publishVersion / setVersionStatus / registerAlias) ----
      // Non-sequenced: confirm via /registry/{name}; no seq number or DL1-fiberCommit sync.
      const REGISTRY_LIBS: Record<string, string> = {
        publishVersion: './lib/registry/publishVersion.ts',
        setVersionStatus: './lib/registry/setVersionStatus.ts',
        registerAlias: './lib/registry/registerAlias.ts',
      };
      if (REGISTRY_LIBS[step.action]) {
        const regLib = await import(REGISTRY_LIBS[step.action]);
        const regName = step.name as string;
        const regOptions: Record<string, unknown> = {
          ...step,
          targetFiberId: (step.targetFiberId as string) ?? session.cid,
          ...(step.definition ? { definition: path.join(examplesDir, example.dir, step.definition) } : {}),
          ...(step.schemaShape ? { schemaShape: path.join(examplesDir, example.dir, step.schemaShape as string) } : {}),
        };
        const regMessage = regLib.generator({ cid: session.cid, wallets, options: regOptions });
        const regPath = `registry/${encodeURIComponent(regName)}`;
        // Did the op land in the registry? (per-action predicate on the /registry/{name} response)
        const landed = (d: unknown): boolean => {
          const e = d as { target?: { SchemaPackage?: { versions?: { versions?: Record<string, { status?: string }> } } } } | null;
          const vs = e?.target?.SchemaPackage?.versions?.versions;
          if (step.action === 'publishVersion') return !!vs && (step.version as string) in vs;
          if (step.action === 'setVersionStatus') return vs?.[step.version as string]?.status === step.status;
          return e != null; // registerAlias
        };

        if (step.expectRejected === 'dl1') {
          // Structural reject (e.g. reserved name) -> the /data POST should fail with HTTP 400.
          let rejected = false;
          try {
            await sendSignedUpdate(regMessage, wallets, dl1Urls);
          } catch (err) {
            rejected = (err as Error).message.includes('400');
          }
          if (!rejected) throw new Error(`expected DL1 to reject ${step.action} ${regName} (HTTP 400), but it was accepted`);
          l(' \x1b[32mOK (DL1 rejected)\x1b[0m');
          continue;
        }
        if (step.expectRejected === 'ml0') {
          // Admitted by DL1 (structurally valid) but rejected at ML0 combine -> never lands.
          await sendSignedUpdate(regMessage, wallets, dl1Urls).catch(() => undefined);
          const client = new HttpClient(`${ml0Urls[0]}/data-application/v1/${regPath}`);
          for (let attempt = 0; attempt < 8; attempt++) {
            await new Promise((r) => setTimeout(r, retryDelayMs));
            let data: unknown = null;
            try {
              data = await client.get<unknown>('');
            } catch {
              /* entry may not exist — fine */
            }
            if (landed(data)) throw new Error(`expected ML0 to reject ${step.action} ${regName}, but it landed`);
          }
          l(' \x1b[32mOK (ML0 rejected, state unchanged)\x1b[0m');
          continue;
        }

        const regInitial = await getInitialStates(ml0Env);
        await sendSignedUpdate(regMessage, wallets, dl1Urls);
        await waitForMl0Confirmation(ml0Urls[0], regPath, landed, maxRetries, retryDelayMs, `${step.action} ${regName}`, log);
        await validateWithRetries(regLib.validator, session.cid, regInitial, regOptions, wallets, maxRetries, retryDelayMs, ml0Urls);
        l(' \x1b[32mOK\x1b[0m');
        continue;
      }

      const loadContext = {
        wallets,
        session,
        eventData: step.eventData,
      };

      // Determine which fiber this step targets and fetch its current sequence number
      // Script actions (createScript, invokeScript, invoke) use the /scripts/ endpoint
      const isScriptStep =
        (step.action as string).includes('Script') ||
        (step.action as string).includes('Script') ||
        step.action === 'invoke';
      const isCreateStep =
        step.action === 'create' ||
        step.action === 'createStateMachine' ||
        step.action === 'createScript';

      // For createScript, scriptFiberId is assigned inside the switch below,
      // so we defer activeCid/entityPath until after the switch for create steps.
      let activeCid = isScriptStep ? session.scriptFiberId! : session.cid;
      let entityPath = isScriptStep
        ? `scripts/${activeCid}`
        : `state-machines/${activeCid}`;

      let preSendSeqNum = -1;
      if (!isCreateStep) {
        try {
          const ml0Client = new HttpClient(
            `${ml0Urls[0]}/data-application/v1/${entityPath}`
          );
          const existing = (await ml0Client.get<unknown>('')) as Record<string, unknown> | null;
          if (existing && typeof existing === 'object') {
            preSendSeqNum = (existing as { sequenceNumber?: number }).sequenceNumber ?? -1;
          }
        } catch {
          // Entity doesn't exist yet (expected for create steps)
        }
      }

      switch (step.action) {
        case 'create':
        case 'createStateMachine': {
          const definition = await loadFileOrModule(
            path.join(examplesDir, example.dir, step.definition!),
            loadContext
          );
          const initialData = await loadFileOrModule(
            path.join(examplesDir, example.dir, step.initialData!),
            loadContext
          );

          // schemaRef (optional) binds the new fiber to a registered package version (verified binding).
          stepOptions = { definition, initialData, schemaRef: step.schemaRef };

          const libModule = await import('./lib/state-machine/createFiber.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: session.cid,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'createScript': {
          session.scriptFiberId = (example.scriptFiberId as string) || crypto.randomUUID();
          const definition = await loadFileOrModule(
            path.join(examplesDir, example.dir, step.definition!),
            loadContext
          );

          stepOptions = { scriptDefinition: definition };

          const libModule = await import('./lib/script/createScript.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: session.scriptFiberId,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'processEvent': {
          let eventData = (await loadFileOrModule(
            path.join(examplesDir, example.dir, step.event!),
            loadContext
          )) as Record<string, unknown>;

          if (step.eventData) {
            eventData = {
              ...eventData,
              payload: {
                ...(eventData.payload as Record<string, unknown>),
                ...(step.eventData as Record<string, unknown>),
              },
            };
          }

          stepOptions = {
            eventData,
            expectedState: step.expectedState,
            targetSequenceNumber: preSendSeqNum >= 0 ? preSendSeqNum : 0,
          };

          const libModule = await import('./lib/state-machine/processEvent.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: session.cid,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'invoke': {
          let args: unknown = null;
          if (step.args) {
            args = await loadFileOrModule(
              path.join(examplesDir, example.dir, step.args),
              loadContext
            );
          }

          stepOptions = {
            method: step.method,
            args,
            expectedResult: step.expectedResult,
            targetSequenceNumber: preSendSeqNum >= 0 ? preSendSeqNum : 0,
          };

          const libModule = await import('./lib/script/invokeScript.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({
            cid: session.scriptFiberId!,
            wallets,
            options: stepOptions,
          });
          break;
        }

        case 'upgradeFiber': {
          // Upgrade the session fiber to another registered version of the same package (#27),
          // optionally migrating prior state. Sequenced: bumps the fiber's sequence number.
          stepOptions = {
            targetRef: step.targetRef,
            newDefinition: path.join(examplesDir, example.dir, step.newDefinition as string),
            ...(step.migration ? { migration: path.join(examplesDir, example.dir, step.migration as string) } : {}),
            targetSequenceNumber: preSendSeqNum >= 0 ? preSendSeqNum : 0,
          };
          const libModule = await import('./lib/state-machine/upgradeFiber.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({ cid: session.cid, wallets, options: stepOptions });
          break;
        }

        case 'archiveStateMachine': {
          // Sequenced, but archive flips status to ARCHIVED without bumping the sequence number
          // (see the confirmation predicate below, which special-cases this).
          stepOptions = { targetSequenceNumber: preSendSeqNum >= 0 ? preSendSeqNum : 0 };
          const libModule = await import('./lib/state-machine/archiveFiber.ts');
          generator = libModule.generator;
          validator = libModule.validator;
          message = generator({ cid: session.cid, wallets, options: stepOptions });
          break;
        }

        default:
          throw new Error(`Unknown action: ${step.action}`);
      }

      // Re-compute activeCid/entityPath after the switch — createScript assigns
      // session.scriptFiberId inside the switch, so the pre-switch values may be stale.
      if (isCreateStep) {
        activeCid = isScriptStep ? session.scriptFiberId! : session.cid;
        entityPath = isScriptStep
          ? `scripts/${activeCid}`
          : `state-machines/${activeCid}`;
      }

      // Snapshot initial state before sending
      const initialStates = await getInitialStates(ml0Env);

      // Send transaction and wait for ML0 confirmation using ordinal-based tracking.
      // If N ordinals pass without the transaction appearing in state, automatically
      // regenerate and resubmit. This adapts to ML0's actual consensus speed.
      const sendToNodes = async (msg: unknown) => {
        await sendSignedUpdate(msg, wallets, dl1Urls);
      };

      // Helper to regenerate message with fresh sequence number
      const regenerateMessage = async () => {
        if (!isCreateStep) {
          try {
            const ml0Client = new HttpClient(
              `${ml0Urls[0]}/data-application/v1/${entityPath}`
            );
            const existing = (await ml0Client.get<unknown>('')) as Record<string, unknown> | null;
            if (existing && typeof existing === 'object') {
              const freshSeqNum = (existing as { sequenceNumber?: number }).sequenceNumber ?? -1;
              if (freshSeqNum >= 0) {
                (stepOptions as Record<string, unknown>).targetSequenceNumber = freshSeqNum;
              }
            }
          } catch {
            // Entity might not exist yet — keep current options
          }
        }
        return generator!({
          cid: activeCid,
          wallets,
          options: stepOptions!,
        });
      };

      // Initial send (with basic retry for immediate DL1 rejections)
      let currentMessage = message;
      let sendSuccess = false;
      for (let sendAttempt = 0; sendAttempt < 3; sendAttempt++) {
        try {
          await sendToNodes(currentMessage);
          sendSuccess = true;
          break;
        } catch (sendErr) {
          const errMsg = (sendErr as Error).message;
          if (!errMsg.includes('400') || sendAttempt >= 2) {
            throw sendErr;
          }
          w(` (send retry)...`);
          await new Promise((r) => setTimeout(r, 1000));
          currentMessage = await regenerateMessage();
        }
      }

      if (!sendSuccess) {
        throw new Error(`Failed to send transaction for ${step.action}`);
      }

      // Ordinal-based confirmation with auto-resubmit
      await waitForOrdinalConfirmation({
        ml0BaseUrl: ml0Urls[0],
        entityPath,
        predicate: (data) => {
          if (!data || typeof data !== 'object') return false;
          const record = data as { sequenceNumber?: number; status?: string };
          if (isCreateStep) {
            return record.status === 'ACTIVE';
          }
          // Archive flips status to ARCHIVED without bumping the sequence number.
          if (step.action === 'archiveStateMachine') {
            return record.status === 'ARCHIVED';
          }
          // For transitions/invocations/upgrades: sequenceNumber must have increased
          return (record.sequenceNumber ?? -1) > preSendSeqNum;
        },
        resubmit: async () => {
          const freshMessage = await regenerateMessage();
          await sendToNodes(freshMessage);
        },
        ordinalThreshold: 5,
        maxResubmits: 3,
        pollIntervalMs: 2000,
        // No fixed wall-clock cap: a slow-but-advancing chain gets its full ordinal budget
        // (5 × 4 = 20 ordinals). The stall gate fails fast (120s) only if ML0 stops producing
        // snapshots entirely — i.e. genuine consensus death, not mere slowness under CI load.
        stallTimeoutMs: 120000,
        label: `${step.action} on ${activeCid}`,
        log: log ? { write: (s: string) => log.write(s) } : undefined,
      });

      // Wait for DL1 OnChain state to catch up with ML0.
      // After ML0 confirms the step, the snapshot still needs to propagate
      // through GL0 → DL1 before the DL1's validation cache reflects the
      // new fiber commit. Without this, the next step's DL1 send may fail
      // with FiberIdNotFound (create) or SequenceNumberMismatch (mutation).
      {
        // Fetch the expected sequence number from ML0 (the source of truth)
        let expectedSeqNum: number | null = null;
        try {
          const ml0Client = new HttpClient(
            `${ml0Urls[0]}/data-application/v1/${entityPath}`
          );
          const record = (await ml0Client.get<unknown>('')) as {
            sequenceNumber?: number;
          } | null;
          expectedSeqNum = record?.sequenceNumber ?? null;
        } catch {
          // If we can't fetch from ML0, fall back to existence check only
        }

        await waitForDl1Sync(
          dl1Urls[0],
          activeCid,
          expectedSeqNum,
          maxRetries,
          retryDelayMs,
          `${step.action} DL1 sync for ${activeCid}`,
          log
        );
      }

      // Validate
      const validationOptions = {
        ...stepOptions!,
        expectedState: step.expectedState,
      };
      await validateWithRetries(
        validator!,
        activeCid,
        initialStates,
        validationOptions,
        wallets,
        maxRetries,
        retryDelayMs,
        ml0Urls
      );

      l(' \x1b[32mOK\x1b[0m');
    } catch (error) {
      l(' \x1b[31mFAIL\x1b[0m');
      return {
        passed: false,
        error: (error as Error).message,
        failedStep: i + 1,
      };
    }
  }

  return { passed: true };
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

type FlowResult = {
  example: string;
  flow: string;
  steps: number;
  passed: boolean;
  error?: string;
  failedStep?: number;
  durationMs: number;
};

async function main() {
  const opts = parseArgs();
  const parallel = opts.parallel !== 'false';

  console.log('\x1b[36m=== Ottochain E2E Test Runner ===\x1b[0m');
  console.log(`Mode: ${parallel ? 'parallel' : 'sequential'}\n`);

  // Setup environment
  const env = getMetagraphEnv(opts.target);
  const walletNames = [...new Set(opts.wallets.split(','))];
  const wallets = walletNames.reduce((acc: Wallets, user) => {
    acc[user] = generateWallet(env.globalL0Url, user);
    return acc;
  }, {});

  const ml0Urls = [env.node1ML0, env.node2ML0, env.node3ML0];
  const ml0Env = ml0Urls.map((a) => a + '/data-application/v1/checkpoint');
  const dl1Urls = [env.node1DataL1, env.node2DataL1, env.node3DataL1];
  const maxRetries = parseInt(opts.maxRetries);
  const retryDelayMs = parseInt(opts.retryDelay) * 1000;
  const waitTimeMs = parseInt(opts.waitTime) * 1000;

  // Discover examples
  const examples = await discoverExamples();

  if (examples.length === 0) {
    console.error('\x1b[31mNo examples with test flows found\x1b[0m');
    process.exit(1);
  }

  // Collect all (example, flow) pairs
  const flowPairs: Array<{ example: Example; flow: TestFlow }> = [];
  for (const example of examples) {
    for (const flow of example.testFlows) {
      flowPairs.push({ example, flow });
    }
  }

  console.log(
    `Found ${examples.length} example(s), ${flowPairs.length} flow(s): ${examples.map((e) => e.dir).join(', ')}\n`
  );

  // One-time warmup: wait for ML0 block production to pass the cold-start (fresh-jar / JVM-warmup
  // first-snapshot lag) ONCE before timing flows, so the per-flow sync budgets aren't each charged it.
  try {
    await waitForOrdinalAdvance(ml0Urls[0], 2, { label: 'cluster warmup', maxTotalTimeMs: 300_000 });
  } catch (err) {
    console.error(`\x1b[31mCluster failed to warm up: ${(err as Error).message}\x1b[0m`);
    console.error('Aborting — ML0 is not producing snapshots (consensus not live).');
    process.exit(1);
  }

  const startTime = Date.now();
  let results: FlowResult[];

  if (parallel) {
    // -----------------------------------------------------------------------
    // Parallel mode: run all flows concurrently with buffered output
    // -----------------------------------------------------------------------
    console.log(`Launching ${flowPairs.length} flows in parallel…\n`);

    const promises = flowPairs.map(async ({ example, flow }) => {
      const tag = `${example.dir}/${flow.name}`;
      const logger = new FlowLogger(tag);
      logger.log(
        `\x1b[36m[${example.dir}]\x1b[0m Running: ${flow.name} (${flow.steps.length} steps)`
      );

      const flowStart = Date.now();
      const result = await runFlow(
        example,
        flow,
        env,
        wallets,
        ml0Urls,
        ml0Env,
        dl1Urls,
        maxRetries,
        retryDelayMs,
        waitTimeMs,
        logger
      );
      const durationMs = Date.now() - flowStart;

      if (result.passed) {
        logger.log(`  \x1b[32mPASS: ${flow.name}\x1b[0m (${(durationMs / 1000).toFixed(1)}s)`);
      } else {
        logger.log(
          `  \x1b[31mFAIL: ${flow.name}\x1b[0m (step ${result.failedStep}, ${(durationMs / 1000).toFixed(1)}s)`
        );
        logger.log(`  Error: ${result.error}`);
      }

      // Flush buffered output as a single block (avoids interleaving)
      logger.flush();

      return {
        example: example.dir,
        flow: flow.name,
        steps: flow.steps.length,
        durationMs,
        ...result,
      } satisfies FlowResult;
    });

    results = await Promise.all(promises);
  } else {
    // -----------------------------------------------------------------------
    // Sequential mode: same as original behavior (no buffering needed)
    // -----------------------------------------------------------------------
    results = [];

    for (const { example, flow } of flowPairs) {
      console.log(
        `\x1b[36m[${example.dir}]\x1b[0m Running: ${flow.name} (${flow.steps.length} steps)`
      );

      const flowStart = Date.now();
      const result = await runFlow(
        example,
        flow,
        env,
        wallets,
        ml0Urls,
        ml0Env,
        dl1Urls,
        maxRetries,
        retryDelayMs,
        waitTimeMs
      );
      const durationMs = Date.now() - flowStart;

      results.push({
        example: example.dir,
        flow: flow.name,
        steps: flow.steps.length,
        durationMs,
        ...result,
      });

      if (result.passed) {
        console.log(`  \x1b[32mPASS: ${flow.name}\x1b[0m (${(durationMs / 1000).toFixed(1)}s)\n`);
      } else {
        console.log(
          `  \x1b[31mFAIL: ${flow.name}\x1b[0m (step ${result.failedStep})`
        );
        console.log(`  Error: ${result.error}\n`);
      }
    }
  }

  const totalDurationMs = Date.now() - startTime;

  // Summary
  const passed = results.filter((r) => r.passed).length;
  const failed = results.filter((r) => !r.passed).length;
  const total = results.length;

  console.log('\n\x1b[36m=== Results ===\x1b[0m');
  console.log(`Mode:   ${parallel ? 'parallel' : 'sequential'}`);
  console.log(`Total:  ${(totalDurationMs / 1000).toFixed(1)}s`);
  console.log(`Passed: ${passed}/${total}`);
  console.log(`Failed: ${failed}/${total}`);

  if (failed > 0) {
    console.log('\n\x1b[31mFailed flows:\x1b[0m');
    results
      .filter((r) => !r.passed)
      .forEach((r) => {
        console.log(`  - [${r.example}] ${r.flow} (step ${r.failedStep}): ${r.error}`);
      });
  }

  console.log(`\nExit code: ${failed > 0 ? 1 : 0}`);
  process.exit(failed > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error('\x1b[31mRunner failed:\x1b[0m', err.message);
  process.exit(1);
});
