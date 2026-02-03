import 'dotenv/config';

import { Command } from 'commander';
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import generateWallet from './lib/generateWallet.ts';
import sendSignedUpdate from './lib/sendDataTransaction.ts';
import getMetagraphEnv from './lib/metagraphEnv.ts';
import type { StatesMap, Wallets, GeneratorFn, ValidatorFn } from './lib/types.ts';
import { HttpClient } from '@ottochain/sdk';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const program = new Command()
  .name('ottochain-terminal')
  .description(
    'Interactive CLI for testing Ottochain state machines and script oracles'
  )
  .option(
    '--address <address>',
    'Choose a fixed UUID for the resource. Defaults to random UUID if not provided'
  )
  .option(
    '--target <target>',
    'Target environment: [local, remote, ci]. Default: "local"',
    'local'
  )
  .option(
    '--mode <mode>',
    "Operation mode: ['send', 'validate', 'send+validate'] (default: 'send+validate')",
    'send+validate'
  )
  .option(
    '--waitTime <N>',
    'Seconds to wait after sending before validation. Default: 5',
    '5'
  )
  .option(
    '--retryDelay <N>',
    'Seconds between validation retries. Default: 3',
    '3'
  )
  .option(
    '--maxRetries <N>',
    'Max validation retry attempts. Default: 5',
    '5'
  )
  .option(
    '--wallets <wallets>',
    'Comma-separated list of signers [alice, bob, charlie, diane, james]. Default: alice',
    'alice'
  );

// ---------------------------------------------------------------------------
// File loading
// ---------------------------------------------------------------------------

/**
 * Load a file that can be JSON, JS, or TS module.
 * JS/TS files should export a function or default export that receives a context object.
 * JSON files are parsed and returned as-is.
 */
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

  // Prefer .ts, then .js, then .json
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

  throw new Error(
    `File not found: ${filePath} (tried ${tsPath}, ${jsPath}, and ${jsonPath})`
  );
}

// ---------------------------------------------------------------------------
// Core helpers
// ---------------------------------------------------------------------------

const exitOnError = (contextMessage: string, err: unknown): never => {
  console.error(contextMessage, err);
  process.exit(1);
};

// sendSignedUpdate from lib/sendDataTransaction.ts handles signing + posting via SDK

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
      final: {},
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

const validate = async (
  txValidation: ValidatorFn,
  cid: string,
  statesMap: StatesMap,
  options: Record<string, unknown>,
  wallets: Wallets,
  maxRetries: number,
  retryDelayMs: number,
  ml0Urls?: string[]
) => {
  const validateWithRetries = async (
    currentAttempt: number,
    statesMap: StatesMap
  ): Promise<void> => {
    console.error(
      `\x1b[33m[validate]\x1b[0m Initiating attempt (${currentAttempt}/${maxRetries})...`
    );
    try {
      await txValidation({ cid, statesMap, options, wallets, ml0Urls });
    } catch (err) {
      if (currentAttempt >= maxRetries) {
        console.error(
          `\x1b[31m[validate]\x1b[0m Max retries (${maxRetries}) reached. Last error: ${(err as Error).message}`
        );
        throw err;
      } else {
        console.error(
          `\x1b[31m[validate]\x1b[0m Attempt ${currentAttempt} failed (${currentAttempt}/${maxRetries})\n${(err as Error).message}\nRetrying in ${retryDelayMs}ms...`
        );

        await new Promise((resolve) => setTimeout(resolve, retryDelayMs));

        let updatedStates: StatesMap;
        try {
          updatedStates = await updateFinalStates(statesMap);
          console.log(
            '\x1b[33m[validate]\x1b[0m Updated final states in map for next retry.'
          );
        } catch (refreshErr) {
          console.error(
            `\x1b[31m[validate]\x1b[0m Unable to refresh final states: ${(refreshErr as Error).message}`
          );
          throw refreshErr;
        }

        return validateWithRetries(currentAttempt + 1, updatedStates);
      }
    }
  };

  const _statesMap = await updateFinalStates(statesMap);
  await validateWithRetries(1, _statesMap);
};

// ---------------------------------------------------------------------------
// State Machine Commands
// ---------------------------------------------------------------------------

const stateMachineCmd = new Command('state-machine')
  .alias('sm')
  .description('Manage state machine fibers');

stateMachineCmd
  .command('create')
  .description('Create a new state machine fiber')
  .requiredOption(
    '--definition <path>',
    'Path to state machine definition JSON file'
  )
  .requiredOption('--initialData <path>', 'Path to initial data JSON file')
  .option('--parentFiberId <uuid>', 'Optional parent fiber ID')
  .action(async (cmdOptions) => {
    await executeCommand('state-machine', 'create', cmdOptions);
  });

stateMachineCmd
  .command('process-event')
  .alias('event')
  .description('Process an event on an existing state machine fiber')
  .requiredOption('--event <path>', 'Path to event JSON file')
  .option('--expectedState <state>', 'Expected resulting state (for validation)')
  .action(async (cmdOptions) => {
    await executeCommand('state-machine', 'processEvent', cmdOptions);
  });

stateMachineCmd
  .command('archive')
  .description('Archive a state machine fiber')
  .action(async (cmdOptions) => {
    await executeCommand('state-machine', 'archive', cmdOptions);
  });

// ---------------------------------------------------------------------------
// Oracle Commands
// ---------------------------------------------------------------------------

const oracleCmd = new Command('oracle')
  .alias('or')
  .description('Manage script oracles');

oracleCmd
  .command('create')
  .description('Create a new script oracle')
  .requiredOption('--oracle <path>', 'Path to oracle definition JSON file')
  .action(async (cmdOptions) => {
    await executeCommand('oracle', 'create', cmdOptions);
  });

oracleCmd
  .command('invoke')
  .description('Invoke a method on an existing script oracle')
  .requiredOption('--method <name>', 'Method name to invoke')
  .option('--args <path>', 'Path to arguments JSON file')
  .option('--expectedResult <json>', 'Expected result (for validation)')
  .action(async (cmdOptions) => {
    await executeCommand('oracle', 'invoke', cmdOptions);
  });

// ---------------------------------------------------------------------------
// Query Commands — US-9: All paths now use /v1/
// ---------------------------------------------------------------------------

const queryCmd = new Command('query')
  .alias('q')
  .description('Query metagraph state from ML0 endpoints');

queryCmd
  .command('checkpoint')
  .description('Get current checkpoint')
  .option('--node <number>', 'Node number (1, 2, or 3). Default: 1', '1')
  .action(async (cmdOptions) => {
    await executeQuery('checkpoint', cmdOptions);
  });

queryCmd
  .command('onchain')
  .description('Get onchain state')
  .option('--node <number>', 'Node number (1, 2, or 3). Default: 1', '1')
  .action(async (cmdOptions) => {
    await executeQuery('onchain', cmdOptions);
  });

queryCmd
  .command('state-machines')
  .alias('sm')
  .description('List all state machines')
  .option('--node <number>', 'Node number (1, 2, or 3). Default: 1', '1')
  .option('--status <status>', 'Filter by status: [Active, Archived]')
  .option('--fiberId <uuid>', 'Get specific fiber by ID')
  .option('--events', 'Get event log (requires --fiberId)')
  .action(async (cmdOptions) => {
    await executeQuery('state-machines', cmdOptions);
  });

queryCmd
  .command('oracles')
  .alias('or')
  .description('List all oracles')
  .option('--node <number>', 'Node number (1, 2, or 3). Default: 1', '1')
  .option('--status <status>', 'Filter by status: [Active, Archived]')
  .option('--oracleId <uuid>', 'Get specific oracle by ID')
  .option('--invocations', 'Get invocation log (requires --oracleId)')
  .action(async (cmdOptions) => {
    await executeQuery('oracles', cmdOptions);
  });

async function executeQuery(
  endpoint: string,
  cmdOptions: Record<string, unknown>
) {
  const globalOpts = program.opts();
  const options = { ...globalOpts, ...cmdOptions };

  try {
    const env = getMetagraphEnv(options.target as string);
    const nodeMap: Record<string, string> = {
      '1': env.node1ML0,
      '2': env.node2ML0,
      '3': env.node3ML0,
    };
    const baseUrl = nodeMap[options.node as string] || env.node1ML0;

    // US-9: All custom routes now use /data-application/v1/ prefix
    let apiPath: string;
    switch (endpoint) {
      case 'checkpoint':
        apiPath = '/data-application/v1/checkpoint';
        break;
      case 'onchain':
        apiPath = '/data-application/v1/onchain';
        break;
      case 'state-machines':
        if (options.fiberId && options.events) {
          apiPath = `/data-application/v1/state-machines/${options.fiberId}/events`;
        } else if (options.fiberId) {
          apiPath = `/data-application/v1/state-machines/${options.fiberId}`;
        } else {
          apiPath = '/data-application/v1/state-machines';
          if (options.status) apiPath += `?status=${options.status}`;
        }
        break;
      case 'oracles':
        if (options.oracleId && options.invocations) {
          apiPath = `/data-application/v1/oracles/${options.oracleId}/invocations`;
        } else if (options.oracleId) {
          apiPath = `/data-application/v1/oracles/${options.oracleId}`;
        } else {
          apiPath = '/data-application/v1/oracles';
          if (options.status) apiPath += `?status=${options.status}`;
        }
        break;
      default:
        throw new Error(`Unknown query endpoint: ${endpoint}`);
    }

    const url = `${baseUrl}${apiPath}`;
    console.log(`\x1b[33m[query]\x1b[36m Fetching from:\x1b[0m ${url}`);

    const client = new HttpClient(baseUrl);
    const response = await client.get<unknown>(apiPath);
    console.log(JSON.stringify(response, null, 2));
    process.exit(0);
  } catch (error) {
    exitOnError('\x1b[31m[query]\x1b[0m Query failed:', error);
  }
}

// ---------------------------------------------------------------------------
// List Command
// ---------------------------------------------------------------------------

program
  .command('list')
  .description('List available examples')
  .option(
    '--type <type>',
    'Filter by type: [state-machines, oracles, all]',
    'all'
  )
  .option('--example <name>', 'Show detailed info for a specific example')
  .action(async (cmdOptions) => {
    const examplesDir = path.join(__dirname, 'examples');

    if (cmdOptions.example) {
      const example = (await loadFileOrModule(
        path.join(examplesDir, cmdOptions.example, 'example'),
        {}
      )) as Record<string, unknown>;

      console.log(`\x1b[36m\n=== ${example.name} ===\x1b[0m`);
      console.log(`\x1b[33mType:\x1b[0m ${example.type}`);
      console.log(`\x1b[33mDescription:\x1b[0m ${example.description}\n`);

      console.log('\x1b[33mFiles:\x1b[0m');
      const files = fs
        .readdirSync(path.join(examplesDir, cmdOptions.example))
        .filter(
          (f: string) =>
            (f.endsWith('.json') || f.endsWith('.ts')) &&
            !f.startsWith('example.')
        );
      files.forEach((f: string) =>
        console.log(`  - examples/${cmdOptions.example}/${f}`)
      );

      if (Array.isArray(example.events)) {
        console.log('\n\x1b[33mEvents:\x1b[0m');
        (example.events as Array<Record<string, string>>).forEach((e) =>
          console.log(`  - ${e.name}: ${e.description} (${e.from} → ${e.to})`)
        );
      }

      if (Array.isArray(example.methods)) {
        console.log('\n\x1b[33mMethods:\x1b[0m');
        (example.methods as Array<Record<string, string>>).forEach((m) =>
          console.log(`  - ${m.name}: ${m.description}`)
        );
      }

      if (Array.isArray(example.testFlows)) {
        console.log('\n\x1b[33mTest Flows:\x1b[0m');
        (example.testFlows as Array<Record<string, string>>).forEach((flow) => {
          console.log(`  ${flow.name}: ${flow.description}`);
        });
      }

      console.log('\n');
      return;
    }

    console.log('\x1b[36m\n=== Available Examples ===\x1b[0m\n');

    const examples = (
      await Promise.all(
        fs
          .readdirSync(examplesDir)
          .filter((f) => fs.statSync(path.join(examplesDir, f)).isDirectory())
          .map(async (dir) => {
            try {
              const example = (await loadFileOrModule(
                path.join(examplesDir, dir, 'example'),
                {}
              )) as Record<string, unknown>;
              return { dir, ...example };
            } catch {
              return null;
            }
          })
      )
    ).filter((e): e is Record<string, unknown> & { dir: string } => e !== null);

    const stateMachines = examples.filter((e) => e.type === 'state-machine');
    const oracles = examples.filter((e) => e.type === 'oracle');
    const combined = examples.filter((e) => e.type === 'combined');

    if (cmdOptions.type === 'all' || cmdOptions.type === 'state-machines') {
      console.log('\x1b[33mState Machine Examples:\x1b[0m');
      stateMachines.forEach((e) => {
        console.log(`  \x1b[36m${e.dir}\x1b[0m - ${e.name}`);
        console.log(`    ${e.description}`);
      });
      console.log('');
    }

    if (cmdOptions.type === 'all' || cmdOptions.type === 'oracles') {
      console.log('\x1b[33mOracle Examples:\x1b[0m');
      oracles.forEach((e) => {
        console.log(`  \x1b[36m${e.dir}\x1b[0m - ${e.name}`);
        console.log(`    ${e.description}`);
      });
      console.log('');
    }

    if (cmdOptions.type === 'all' && combined.length > 0) {
      console.log('\x1b[33mCombined Examples (Oracle + State Machine):\x1b[0m');
      combined.forEach((e) => {
        console.log(`  \x1b[36m${e.dir}\x1b[0m - ${e.name}`);
        console.log(`    ${e.description}`);
      });
      console.log('');
    }

    console.log(
      'Use \x1b[36m--example <name>\x1b[0m to see detailed information about an example'
    );
    console.log(
      'Use \x1b[36mrun --example <name> --list\x1b[0m to see available test flows\n'
    );
  });

// ---------------------------------------------------------------------------
// Execute Command (send + validate orchestration)
// ---------------------------------------------------------------------------

async function executeCommand(
  category: string,
  operation: string,
  cmdOptions: Record<string, unknown>
) {
  const globalOpts = program.opts();
  const options = { ...globalOpts, ...cmdOptions };

  try {
    const env = getMetagraphEnv(options.target as string);

    const cid = (options.address as string) || crypto.randomUUID();
    const wallets = [...new Set((options.wallets as string).split(','))].reduce(
      (acc: Wallets, user) => {
        acc[user] = generateWallet(env.globalL0Url, user);
        return acc;
      },
      {}
    );

    const operationFileMap: Record<string, Record<string, string>> = {
      'state-machine': {
        create: 'createFiber',
        processEvent: 'processEvent',
        archive: 'archiveFiber',
      },
      oracle: {
        create: 'createScript',
        invoke: 'invokeScript',
      },
    };

    const fileName = operationFileMap[category]?.[operation] || operation;
    const libModule = await import(`./lib/${category}/${fileName}.ts`);
    const { generator, validator } = libModule;
    const message = generator({ cid, wallets, options });

    const maxRetries = parseInt(options.maxRetries as string);
    const retryDelayMs = parseInt(options.retryDelay as string) * 1000;
    const waitTimeMs = parseInt(options.waitTime as string) * 1000;

    // US-8: ML0 base URLs for log validation
    const ml0Urls = [env.node1ML0, env.node2ML0, env.node3ML0];
    // US-9: checkpoint endpoint now uses /v1/
    const ml0Env = ml0Urls.map(
      (a) => a + '/data-application/v1/checkpoint'
    );
    const dl1Urls = [env.node1DataL1, env.node2DataL1, env.node3DataL1];

    switch (options.mode) {
      case 'send': {
        await sendSignedUpdate(message, wallets, dl1Urls);
        console.log(
          `\x1b[32m\nTransaction sent successfully! CID: ${cid}\x1b[0m`
        );
        process.exit(0);
        break;
      }

      case 'validate': {
        const statesMap = await getInitialStates(ml0Env);
        await validate(
          validator,
          cid,
          statesMap,
          options,
          wallets,
          maxRetries,
          retryDelayMs,
          ml0Urls
        );
        console.log(
          `\x1b[32m\nValidation completed successfully! CID: ${cid}\x1b[0m`
        );
        process.exit(0);
        break;
      }

      case 'send+validate':
      default: {
        console.log(
          '\x1b[33m[terminal]\x1b[0m Fetching initial states from ML0 endpoints...'
        );
        const initialStates = await getInitialStates(ml0Env);

        console.log(
          `\x1b[33m[terminal]\x1b[0m Sending transaction with CID: ${cid}...`
        );
        await sendSignedUpdate(message, wallets, dl1Urls);

        console.log(
          `\x1b[33m[terminal]\x1b[0m Waiting ${options.waitTime} seconds before validation...`
        );
        await new Promise((resolve) => setTimeout(resolve, waitTimeMs));

        console.log('\x1b[33m[terminal]\x1b[0m Starting validation...');
        await validate(
          validator,
          cid,
          initialStates,
          options,
          wallets,
          maxRetries,
          retryDelayMs,
          ml0Urls
        );

        console.log(
          `\x1b[32m\n✓ Operation completed successfully! CID: ${cid}\x1b[0m`
        );
        process.exit(0);
        break;
      }
    }
  } catch (error) {
    exitOnError(
      `\x1b[31m[terminal]\x1b[0m ${category}/${operation} failed:`,
      error
    );
  }
}

// ---------------------------------------------------------------------------
// Interactive Mode
// ---------------------------------------------------------------------------

program
  .command('interactive')
  .alias('i')
  .description('Interactive mode with guided prompts')
  .action(async () => {
    const readline = await import('readline');

    const rl = readline.createInterface({
      input: process.stdin,
      output: process.stdout,
    });

    const question = (query: string): Promise<string> =>
      new Promise((resolve) => rl.question(query, resolve));

    try {
      console.log('\x1b[36m\n=== Ottochain Interactive Terminal ===\x1b[0m\n');

      const action = await question(
        'What would you like to do?\n  1) Create state machine\n  2) Process event on state machine\n  3) Archive state machine\n  4) Create oracle\n  5) Invoke oracle\n  6) Query state\n\nChoice (1-6): '
      );

      if (action === '6') {
        const queryType = await question(
          '\nWhat would you like to query?\n  1) Checkpoint\n  2) Onchain state\n  3) All state machines\n  4) Specific state machine\n  5) All oracles\n  6) Specific oracle\n\nChoice (1-6): '
        );

        const node =
          (await question(
            '\nWhich node? (1, 2, or 3) [default: 1]: '
          )) || '1';
        const target =
          (await question(
            'Environment? (local/remote/ci) [default: local]: '
          )) || 'local';

        rl.close();

        const cmdOptions: Record<string, unknown> = { node, target };

        if (queryType === '1') {
          await executeQuery('checkpoint', cmdOptions);
        } else if (queryType === '2') {
          await executeQuery('onchain', cmdOptions);
        } else if (queryType === '3') {
          const status = await question(
            'Filter by status? (Active/Archived) [press enter for all]: '
          );
          cmdOptions.status = status || undefined;
          await executeQuery('state-machines', cmdOptions);
        } else if (queryType === '4') {
          const fiberId = await question('Enter fiber ID: ');
          const showEvents = await question(
            'Show event log? (y/n) [default: n]: '
          );
          cmdOptions.fiberId = fiberId;
          cmdOptions.events = showEvents.toLowerCase() === 'y';
          await executeQuery('state-machines', cmdOptions);
        } else if (queryType === '5') {
          const status = await question(
            'Filter by status? (Active/Archived) [press enter for all]: '
          );
          cmdOptions.status = status || undefined;
          await executeQuery('oracles', cmdOptions);
        } else if (queryType === '6') {
          const oracleId = await question('Enter oracle ID: ');
          const showInvocations = await question(
            'Show invocation log? (y/n) [default: n]: '
          );
          cmdOptions.oracleId = oracleId;
          cmdOptions.invocations = showInvocations.toLowerCase() === 'y';
          await executeQuery('oracles', cmdOptions);
        }
        return;
      }

      const examplesDir = path.join(__dirname, 'examples');
      let category: string = '';
      let operation: string = '';
      const cmdOptions: Record<string, unknown> = {};

      if (action === '1') {
        category = 'state-machine';
        operation = 'create';

        const smExamples = fs
          .readdirSync(examplesDir)
          .filter((dir) => {
            try {
              const examplePath = path.join(examplesDir, dir, 'example.json');
              if (!fs.existsSync(examplePath)) return false;
              const example = JSON.parse(
                fs.readFileSync(examplePath, 'utf8')
              );
              return example.type === 'state-machine';
            } catch {
              return false;
            }
          });

        console.log('\n\x1b[33mAvailable state machine examples:\x1b[0m');
        smExamples.forEach((dir, i) => {
          const example = JSON.parse(
            fs.readFileSync(
              path.join(examplesDir, dir, 'example.json'),
              'utf8'
            )
          );
          console.log(`  ${i + 1}) ${dir} - ${example.name}`);
        });

        const exampleChoice = await question(
          '\nChoose example (number or directory name): '
        );
        const exampleDir = isNaN(Number(exampleChoice))
          ? exampleChoice
          : smExamples[parseInt(exampleChoice) - 1];
        cmdOptions.definition = `examples/${exampleDir}/definition.json`;
        cmdOptions.initialData = `examples/${exampleDir}/initial-data.json`;

        const parentId = await question(
          '\nParent fiber ID (optional, press enter to skip): '
        );
        if (parentId) cmdOptions.parentFiberId = parentId;
      } else if (action === '2') {
        category = 'state-machine';
        operation = 'processEvent';

        const fiberId = await question('\nEnter fiber ID (CID): ');
        cmdOptions.address = fiberId;

        const smExamples = fs
          .readdirSync(examplesDir)
          .filter((dir) => {
            try {
              const examplePath = path.join(examplesDir, dir, 'example.json');
              if (!fs.existsSync(examplePath)) return false;
              const example = JSON.parse(
                fs.readFileSync(examplePath, 'utf8')
              );
              return example.type === 'state-machine';
            } catch {
              return false;
            }
          });

        console.log('\n\x1b[33mSelect example to choose events from:\x1b[0m');
        smExamples.forEach((dir, i) => {
          const example = JSON.parse(
            fs.readFileSync(
              path.join(examplesDir, dir, 'example.json'),
              'utf8'
            )
          );
          console.log(`  ${i + 1}) ${dir} - ${example.name}`);
        });

        const exampleChoice = await question(
          '\nChoose example (number or directory name): '
        );
        const exampleDir = isNaN(Number(exampleChoice))
          ? exampleChoice
          : smExamples[parseInt(exampleChoice) - 1];

        const eventFiles = fs
          .readdirSync(path.join(examplesDir, exampleDir))
          .filter(
            (f) =>
              f.startsWith('event-') &&
              (f.endsWith('.json') || f.endsWith('.ts'))
          );

        console.log('\n\x1b[33mAvailable events:\x1b[0m');
        eventFiles.forEach((f, i) => console.log(`  ${i + 1}) ${f}`));

        const eventChoice = await question(
          '\nChoose event (number or filename): '
        );
        const eventFile = isNaN(Number(eventChoice))
          ? eventChoice
          : eventFiles[parseInt(eventChoice) - 1];
        cmdOptions.event = `examples/${exampleDir}/${eventFile}`;

        const expectedState = await question(
          '\nExpected state after event (optional): '
        );
        if (expectedState) cmdOptions.expectedState = expectedState;
      } else if (action === '3') {
        category = 'state-machine';
        operation = 'archive';

        const fiberId = await question('\nEnter fiber ID to archive: ');
        cmdOptions.address = fiberId;
      } else if (action === '4') {
        category = 'oracle';
        operation = 'create';

        const oracleExamples = fs
          .readdirSync(examplesDir)
          .filter((dir) => {
            try {
              const examplePath = path.join(examplesDir, dir, 'example.json');
              if (!fs.existsSync(examplePath)) return false;
              const example = JSON.parse(
                fs.readFileSync(examplePath, 'utf8')
              );
              return example.type === 'oracle';
            } catch {
              return false;
            }
          });

        console.log('\n\x1b[33mAvailable oracle examples:\x1b[0m');
        oracleExamples.forEach((dir, i) => {
          const example = JSON.parse(
            fs.readFileSync(
              path.join(examplesDir, dir, 'example.json'),
              'utf8'
            )
          );
          console.log(`  ${i + 1}) ${dir} - ${example.name}`);
        });

        const exampleChoice = await question(
          '\nChoose example (number or directory name): '
        );
        const exampleDir = isNaN(Number(exampleChoice))
          ? exampleChoice
          : oracleExamples[parseInt(exampleChoice) - 1];
        cmdOptions.oracle = `examples/${exampleDir}/definition.json`;
      } else if (action === '5') {
        category = 'oracle';
        operation = 'invoke';

        const oracleId = await question('\nEnter oracle ID (CID): ');
        cmdOptions.address = oracleId;

        const oracleExamples = fs
          .readdirSync(examplesDir)
          .filter((dir) => {
            try {
              const examplePath = path.join(examplesDir, dir, 'example.json');
              if (!fs.existsSync(examplePath)) return false;
              const example = JSON.parse(
                fs.readFileSync(examplePath, 'utf8')
              );
              return example.type === 'oracle';
            } catch {
              return false;
            }
          });

        console.log('\n\x1b[33mSelect oracle example:\x1b[0m');
        oracleExamples.forEach((dir, i) => {
          const example = JSON.parse(
            fs.readFileSync(
              path.join(examplesDir, dir, 'example.json'),
              'utf8'
            )
          );
          console.log(`  ${i + 1}) ${dir} - ${example.name}`);
        });

        const exampleChoice = await question(
          '\nChoose example (number or directory name): '
        );
        const exampleDir = isNaN(Number(exampleChoice))
          ? exampleChoice
          : oracleExamples[parseInt(exampleChoice) - 1];

        const example = JSON.parse(
          fs.readFileSync(
            path.join(examplesDir, exampleDir, 'example.json'),
            'utf8'
          )
        );
        console.log('\n\x1b[33mAvailable methods:\x1b[0m');
        example.methods.forEach(
          (m: { name: string; description: string }, i: number) =>
            console.log(`  ${i + 1}) ${m.name} - ${m.description}`)
        );

        const methodChoice = await question(
          '\nChoose method (number or name): '
        );
        const method = isNaN(Number(methodChoice))
          ? methodChoice
          : example.methods[parseInt(methodChoice) - 1].name;
        cmdOptions.method = method;

        const selectedMethod = example.methods.find(
          (m: { name: string }) => m.name === method
        );
        if (selectedMethod && selectedMethod.args) {
          cmdOptions.args = `examples/${exampleDir}/${selectedMethod.args}`;
        }

        const expectedResult = await question(
          '\nExpected result (optional, JSON string): '
        );
        if (expectedResult) cmdOptions.expectedResult = expectedResult;
      }

      const target =
        (await question(
          '\nEnvironment? (local/remote/ci) [default: local]: '
        )) || 'local';
      const mode =
        (await question(
          'Mode? (send/validate/send+validate) [default: send+validate]: '
        )) || 'send+validate';
      const wallets =
        (await question(
          'Wallets (comma-separated: alice,bob,charlie,diane,james) [default: alice]: '
        )) || 'alice';

      rl.close();

      console.log('\n\x1b[36m=== Executing Command ===\x1b[0m\n');

      // Set global options for executeCommand
      program.setOptionValue('target', target);
      program.setOptionValue('mode', mode);
      program.setOptionValue('wallets', wallets);

      await executeCommand(category, operation, cmdOptions);
    } catch (error) {
      rl.close();
      exitOnError('\x1b[31m[interactive]\x1b[0m Error:', error);
    }
  });

// ---------------------------------------------------------------------------
// Run Command — Execute predefined test flows
// ---------------------------------------------------------------------------

const runCmd = new Command('run')
  .description('Execute predefined test flows from examples')
  .option(
    '-e, --example <name>',
    'Example to run (e.g., simple-order, counter-script, tictactoe)'
  )
  .option('-f, --flow <number|name>', 'Test flow by number (1, 2, ...) or name')
  .option('-l, --list', 'List available test flows')
  .addHelpText(
    'after',
    `
Examples:
  $ npx tsx terminal.ts run -l                    # List all examples and their test flows
  $ npx tsx terminal.ts run -e simple-order       # Run the default flow for simple-order
  $ npx tsx terminal.ts run -e simple-order -l    # List flows for simple-order only
  $ npx tsx terminal.ts run -e simple-order -f 2  # Run flow #2
  $ npx tsx terminal.ts run -e simple-order -f cancel   # Match flow by name
`
  )
  .action(async (cmdOptions) => {
    const globalOpts = program.opts();
    const examplesDir = path.join(__dirname, 'examples');

    const loadExamples = async () => {
      const dirs = fs
        .readdirSync(examplesDir)
        .filter((f) => fs.statSync(path.join(examplesDir, f)).isDirectory());

      const examples = await Promise.all(
        dirs.map(async (dir): Promise<(Record<string, unknown> & { dir: string }) | null> => {
          try {
            const example = (await loadFileOrModule(
              path.join(examplesDir, dir, 'example'),
              {}
            )) as Record<string, unknown>;
            return { dir, ...example };
          } catch {
            return null;
          }
        })
      );

      return examples.filter(
        (
          e
        ): e is Record<string, unknown> & {
          dir: string;
          testFlows: Array<Record<string, unknown>>;
        } =>
          e !== null &&
          Array.isArray(e.testFlows) &&
          e.testFlows.length > 0
      );
    };

    if (!cmdOptions.example) {
      const examples = await loadExamples();

      if (examples.length === 0) {
        console.error('\x1b[31mNo examples with test flows found\x1b[0m');
        process.exit(1);
      }

      console.log('\x1b[36m\nAvailable Test Flows:\x1b[0m\n');
      examples.forEach((example) => {
        console.log(`\x1b[33m${example.dir}\x1b[0m - ${example.name}`);
        console.log(`  ${example.description}`);
        (example.testFlows as Array<{ name: string; steps: unknown[] }>).forEach(
          (flow, i) => {
            console.log(`    ${i + 1}) ${flow.name} (${flow.steps.length} steps)`);
          }
        );
        console.log('');
      });

      console.log(
        'Run a flow with: \x1b[36mnpx tsx terminal.ts run --example <name>\x1b[0m\n'
      );
      process.exit(0);
    }

    let example: Record<string, unknown>;
    try {
      example = (await loadFileOrModule(
        path.join(examplesDir, cmdOptions.example, 'example'),
        {}
      )) as Record<string, unknown>;
    } catch {
      console.error(
        `\x1b[31mExample "${cmdOptions.example}" not found\x1b[0m`
      );
      console.log('\nAvailable examples:');
      fs.readdirSync(examplesDir)
        .filter((f) => fs.statSync(path.join(examplesDir, f)).isDirectory())
        .forEach((dir) => console.log(`  - ${dir}`));
      process.exit(1);
    }

    const testFlows = example.testFlows as Array<{
      name: string;
      description: string;
      steps: Array<Record<string, unknown>>;
    }>;

    if (!testFlows || testFlows.length === 0) {
      console.error(
        `\x1b[31mNo test flows defined for "${cmdOptions.example}"\x1b[0m`
      );
      process.exit(1);
    }

    if (cmdOptions.list) {
      console.log(`\x1b[36m\nTest flows for ${example.name}:\x1b[0m\n`);
      testFlows.forEach((flow, i) => {
        console.log(`  ${i + 1}) \x1b[33m${flow.name}\x1b[0m`);
        console.log(`     ${flow.description}`);
        console.log(`     Steps: ${flow.steps.length}`);
      });
      console.log('');
      process.exit(0);
    }

    // Find the flow to run
    let flow: (typeof testFlows)[0];
    if (cmdOptions.flow) {
      const flowNum = parseInt(cmdOptions.flow);
      if (!isNaN(flowNum) && flowNum >= 1 && flowNum <= testFlows.length) {
        flow = testFlows[flowNum - 1];
      } else {
        const found = testFlows.find((f) =>
          f.name.toLowerCase().includes(cmdOptions.flow.toLowerCase())
        );
        if (!found) {
          console.error(`\x1b[31mFlow "${cmdOptions.flow}" not found\x1b[0m`);
          console.log('\nAvailable flows:');
          testFlows.forEach((f, i) => console.log(`  ${i + 1}) ${f.name}`));
          process.exit(1);
        }
        flow = found;
      }
    } else {
      flow = testFlows[0];
    }

    console.log(`\x1b[36m\n=== Running: ${flow.name} ===\x1b[0m`);
    console.log(`${flow.description}\n`);

    try {
      const env = getMetagraphEnv(globalOpts.target as string);

      const wallets = [
        ...new Set((globalOpts.wallets as string).split(',')),
      ].reduce((acc: Wallets, user) => {
        acc[user] = generateWallet(env.globalL0Url, user);
        return acc;
      }, {});

      const maxRetries = parseInt(globalOpts.maxRetries as string);
      const retryDelayMs = parseInt(globalOpts.retryDelay as string) * 1000;
      const waitTimeMs = parseInt(globalOpts.waitTime as string) * 1000;

      // US-8: ML0 base URLs for log validation
      const ml0Urls = [env.node1ML0, env.node2ML0, env.node3ML0];
      // US-9: checkpoint endpoint now uses /v1/
      const ml0Env = ml0Urls.map(
        (a) => a + '/data-application/v1/checkpoint'
      );
      const dl1Urls = [env.node1DataL1, env.node2DataL1, env.node3DataL1];

      // Session state to track CIDs between steps
      const session = {
        cid: (globalOpts.address as string) || crypto.randomUUID(),
        oracleFiberId: null as string | null,
      };

      for (let i = 0; i < flow.steps.length; i++) {
        const step = flow.steps[i];
        console.log(
          `\x1b[33m[Step ${i + 1}/${flow.steps.length}]\x1b[0m ${step.action}...`
        );

        let generator: GeneratorFn;
        let validator: ValidatorFn;
        let message: unknown;
        let stepOptions: Record<string, unknown>;

        const loadContext = {
          wallets,
          session,
          eventData: step.eventData,
        };

        switch (step.action) {
          case 'create':
          case 'createStateMachine': {
            const definition = await loadFileOrModule(
              path.join(
                examplesDir,
                cmdOptions.example,
                step.definition as string
              ),
              loadContext
            );
            const initialData = await loadFileOrModule(
              path.join(
                examplesDir,
                cmdOptions.example,
                step.initialData as string
              ),
              loadContext
            );

            stepOptions = { definition, initialData };

            const libModule = await import(
              './lib/state-machine/createFiber.ts'
            );
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
            session.oracleFiberId =
              (example.oracleFiberId as string) || crypto.randomUUID();
            const definition = await loadFileOrModule(
              path.join(
                examplesDir,
                cmdOptions.example,
                step.definition as string
              ),
              loadContext
            );

            stepOptions = { oracleDefinition: definition };

            const libModule = await import('./lib/script/createScript.ts');
            generator = libModule.generator;
            validator = libModule.validator;
            message = generator({
              cid: session.oracleFiberId,
              wallets,
              options: stepOptions,
            });
            console.log(`   Oracle CID: ${session.oracleFiberId}`);
            break;
          }

          case 'processEvent': {
            let eventData = (await loadFileOrModule(
              path.join(
                examplesDir,
                cmdOptions.example,
                step.event as string
              ),
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
            };

            const libModule = await import(
              './lib/state-machine/processEvent.ts'
            );
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
                path.join(
                  examplesDir,
                  cmdOptions.example,
                  step.args as string
                ),
                loadContext
              );
            }

            stepOptions = {
              method: step.method,
              args,
              expectedResult: step.expectedResult,
            };

            const libModule = await import('./lib/script/invokeScript.ts');
            generator = libModule.generator;
            validator = libModule.validator;
            message = generator({
              cid: session.oracleFiberId,
              wallets,
              options: stepOptions,
            });
            break;
          }

          default:
            console.error(`\x1b[31mUnknown action: ${step.action}\x1b[0m`);
            process.exit(1);
        }

        // Send and validate
        const initialStates = await getInitialStates(ml0Env);
        await sendSignedUpdate(message, wallets, dl1Urls);
        await new Promise((resolve) => setTimeout(resolve, waitTimeMs));
        const validationOptions = {
          ...stepOptions!,
          expectedState: step.expectedState,
        };
        const useOracleCid =
          (step.action as string).includes('Oracle') ||
          step.action === 'invoke';
        await validate(
          validator!,
          useOracleCid ? session.oracleFiberId! : session.cid,
          initialStates,
          validationOptions,
          wallets,
          maxRetries,
          retryDelayMs,
          ml0Urls
        );

        console.log('\x1b[32m   ✓ Step completed\x1b[0m');
      }

      console.log('\x1b[32m\n✓ Flow completed successfully!\x1b[0m');
      console.log(`  State Machine CID: ${session.cid}`);
      if (session.oracleFiberId) {
        console.log(`  Oracle CID: ${session.oracleFiberId}`);
      }
      console.log('');
      process.exit(0);
    } catch (error) {
      exitOnError('\x1b[31m[run]\x1b[0m Flow execution failed:', error);
    }
  });

program.addCommand(stateMachineCmd);
program.addCommand(oracleCmd);
program.addCommand(queryCmd);
program.addCommand(runCmd);

program.parse(process.argv);
