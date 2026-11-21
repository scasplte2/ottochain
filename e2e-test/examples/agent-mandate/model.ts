import { openSync, writeSync, closeSync } from 'node:fs';
import { keyPairFromPrivateKey } from '@ottochain/sdk';
import { generator as transition } from '../../lib/state-machine/processEvent.ts';
import send from '../../lib/sendDataTransaction.ts';
import { capture } from './verify-mandate.ts';

const [fiber, invoiceNumber, output, supplier = 'supplier-c'] = process.argv.slice(2);
const invoice = { requestNumber: Number(invoiceNumber), payee: supplier, amountCents: 2000 };
if (!fiber || !Number.isSafeInteger(invoice.requestNumber) || invoice.requestNumber < 1 || !output)
  throw new Error('Usage: model FIBER REQUEST_NUMBER NEW_TRANSCRIPT');
const fd = openSync(output, 'wx', 0o600);
const log = (value: unknown) => writeSync(fd, JSON.stringify(value) + '\n');
const primary = 'http://127.0.0.1:9200';
const model = process.env.MANDATE_MODEL ?? 'qwen-turbo:q6';
const ollama = 'http://127.0.0.1:11434';
const wallet = keyPairFromPrivateKey(process.env.MANDATE_AGENT_TEST_KEY ?? '');
delete process.env.MANDATE_AGENT_TEST_KEY;
async function read() {
  const { result } = await capture(fiber, primary);
  if (result.data.agent !== wallet.address || result.owners.length !== 1 || result.owners[0] !== result.data.owner)
    throw new Error('Unexpected agent or owner in mandate');
  return result;
}
type Call = { function: { name: string; arguments: Record<string, unknown> } };
type Message = { role: string; content: string; tool_name?: string; tool_calls?: Call[] };
const messages: Message[] = [
  { role: 'system', content: 'Read your mandate before processing the invoice. Submit the supplied invoice only if authorized. Explain your decision. All amounts are synthetic test authorizations.' },
  { role: 'user', content: 'Please process this synthetic invoice: ' + JSON.stringify(invoice) },
];
const tools = [
  { type: 'function', function: { name: 'read_mandate', description: 'Read the verified current local mandate', parameters: { type: 'object', properties: {} } } },
  { type: 'function', function: { name: 'submit_payment', description: 'Submit this exact synthetic invoice with the agent test signature', parameters: { type: 'object', properties: { requestNumber: { type: 'integer' }, payee: { type: 'string' }, amountCents: { type: 'integer' } }, required: Object.keys(invoice) } } },
];
let attempted = false;
let readOnce = false;
try {
  const inventory = await (await fetch(ollama + '/api/tags')).json();
  const installed = inventory.models.find((m: {name: string}) => m.name === model);
  if (!installed) throw new Error('Model is not installed');
  log({ model, digest: installed.digest, fiber, invoice, messages, tools });
  for (let turn = 0; turn < 6; turn++) {
    const response = await fetch(ollama + '/api/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ model, messages, tools, stream: false, think: false, options: { temperature: 0 } }), signal: AbortSignal.timeout(120000) });
    if (!response.ok) throw new Error('Ollama HTTP ' + response.status);
    const chat = await response.json();
    const message: Message = { role: 'assistant', content: chat.message.content, tool_calls: chat.message.tool_calls };
    log({ message, runtimeNs: chat.total_duration });
    messages.push(message);
    if (!message.tool_calls?.length) {
      if (!readOnce) throw new Error('Model ended before reading mandate');
      break;
    }
    for (const call of message.tool_calls) {
      const { name, arguments: args } = call.function;
      let result: unknown;
      if (name === 'read_mandate') { result = await read(); readOnce = true; }
      else if (name === 'submit_payment' && readOnce && !attempted && Object.keys(args).length === 3 &&
        args.requestNumber === invoice.requestNumber && args.amountCents === invoice.amountCents && args.payee === invoice.payee) {
        attempted = true;
        const { bundle, result: current } = await capture(fiber, primary);
        if (current.data.agent !== wallet.address) throw new Error('Agent role changed');
        const record = bundle.proof.record as { sequenceNumber: number; latestUpdateOrdinal: number };
        const update = transition({ cid: fiber, options: { targetSequenceNumber: record.sequenceNumber, eventData: {eventName: 'authorize', payload: invoice} } });
        const admission = await send(update, { agent: wallet }, [9400,9410,9420].map(p => 'http://127.0.0.1:' + p));
        result = { admission, status: 'admitted; receipt pending', budgetBefore: current.data.remainingCents };
        for (let i = 0; i < 60; i++) {
          const after = await read();
          const receipt = after.receipt as { eventName?: string; ordinal?: number } | undefined;
          if (receipt?.eventName === 'authorize' && (receipt.ordinal ?? 0) > record.latestUpdateOrdinal) {
            result = { admission, receipt, budgetAfter: after.data.remainingCents }; break;
          }
          await new Promise(r => setTimeout(r, 1500));
        }
      } else { throw new Error('Unknown tool, repeated submission, or invoice arguments changed'); }
      const toolMessage = { role: 'tool', tool_name: name, content: JSON.stringify(result) };
      messages.push(toolMessage); log(toolMessage);
    }
    if (turn === 5) throw new Error('Model reached the tool-call limit');
  }
} finally { closeSync(fd); }
