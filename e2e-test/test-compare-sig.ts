import { batchSign, generateKeyPair } from '@ottochain/sdk';

const keyPair = generateKeyPair();
console.log('E2E Test - Address:', keyPair.address);

const message = {
  CreateStateMachine: {
    fiberId: 'compare-test-' + Date.now(),
    definition: { 
      metadata: { name: 'Market', version: '1.0.0' }, 
      states: { PROPOSED: { id: 'PROPOSED', isFinal: false } }, 
      transitions: [], 
      initialState: 'PROPOSED' 
    },
    initialData: { status: 'PROPOSED', creator: keyPair.address },
    parentFiberId: null,
  },
};

async function main() {
  const signed = await batchSign(message, [keyPair.privateKey], { isDataUpdate: true });
  
  console.log('\n=== E2E Signed Structure ===');
  console.log('Keys:', Object.keys(signed));
  console.log('Value keys:', Object.keys(signed.value));
  console.log('Proofs length:', signed.proofs.length);
  console.log('Proof[0] keys:', Object.keys(signed.proofs[0]));
  console.log('Proof[0].id length:', signed.proofs[0].id.length);
  console.log('Proof[0].signature length:', signed.proofs[0].signature.length);
  console.log('\nFull payload (first 500 chars):');
  console.log(JSON.stringify(signed).substring(0, 500));
}

main();
