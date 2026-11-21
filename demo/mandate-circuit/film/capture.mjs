// Verify each ledger checkpoint and collect only the fields the camera uses.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync,writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import {verify} from '../../../e2e-test/examples/agent-mandate/verify-mandate.ts';

const example = new URL('../../../e2e-test/examples/agent-mandate/',import.meta.url);
const load = file => JSON.parse(readFileSync(new URL(file,example),'utf8'));
const transcript = file => readFileSync(new URL('evidence/'+file,example),'utf8').trim().split('\n').map(JSON.parse);
const {jsonLogic} = createRequire(example.href+'replay.cjs')('@constellation-network/metagraph-sdk-jlvm');
const records = {};
let previous;
for (const row of load('evidence/ledger.json')) {
  const bundle = load('evidence/'+row.label+'.bundle.json');
  const current = verify(bundle,load('evidence/'+row.label+'.pin.json'));
  assert.equal(current.fiber,row.fiber);
  assert.equal(current.ordinal,row.checkpoint);
  assert.equal(current.data.remainingCents,row.remainingAfter);
  if(previous) assert.equal(previous.data.remainingCents,row.remainingBefore);
  let clauses=[];
  if(row.event==='authorize'){
    const g=load('definition.json').transitions.find(tr=>tr.from===previous.state && tr.eventName==='authorize').guard;
    const inputs={state:previous.data,event:row.payload,$ordinal:row.ordinal,proofs:row.addresses.map(address=>({address}))};
    assert.equal(jsonLogic.apply(g,inputs),current.receipt.success);
    assert.equal(current.receipt.ordinal,row.ordinal);
    clauses=(g.and??[g]).map(clause=>jsonLogic.apply(clause,inputs));
  }
  records[row.label]={ordinal:row.ordinal,checkpoint:current.ordinal,before:row.remainingBefore/100,after:row.remainingAfter/100,
    payload:row.payload,roles:row.roles,clauses,state:current.state};
  previous=current;
}
const run=file=>execFileSync(process.execPath,[fileURLToPath(new URL(file,example))],{encoding:'utf8'}).trim().split('\n').map(s=>s.trim());
const decision=run('decision.ts'), tamper=run('tamper.ts');
const model=transcript('approved-invoice.jsonl');
const result=JSON.parse(model.find(m=>m.tool_name==='submit_payment').content);
const declined=transcript('invoice.jsonl');
assert(!declined.some(m=>m.tool_name==='submit_payment'));
assert(result.receipt.success);
const payeeGuard=load('definition.json').transitions[0].guard.and.find(g=>g.in?.[0]?.var==='event.payee');
assert(payeeGuard);
const output={records,decision,tamper,payeeGuard,model:{name:model[0].model,ordinal:result.receipt.ordinal,after:result.budgetAfter/100},suppliers:previous.data.suppliers,soloLimit:previous.data.soloLimitCents/100};
writeFileSync(new URL('evidence.json',import.meta.url),JSON.stringify(output,null,2)+'\n');
