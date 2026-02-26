import { batchSign, generateKeyPair, HttpClient } from '@ottochain/sdk';

const keyPair = generateKeyPair();
console.log('Address:', keyPair.address);

const message = {
  CreateStateMachine: {
    fiberId: 'direct-' + Date.now(),
    definition: { 
      metadata: { name: 'Test' }, 
      states: { init: { id: 'init', isFinal: false, metadata: null } }, 
      transitions: [], 
      initialState: 'init' 
    },
    initialData: { status: 'PROPOSED' },
    parentFiberId: null,
  },
};

async function main() {
  const signed = await batchSign(message, [keyPair.privateKey], { isDataUpdate: true });
  
  console.log('Signed payload (first 300 chars):', JSON.stringify(signed).substring(0, 300));
  
  const client = new HttpClient('http://5.78.90.207:9400');
  try {
    const response = await client.post('/data', signed);
    console.log('Success:', response);
  } catch (err: any) {
    console.log('Error:', err.message);
    // Try with fetch to see full response
    const res = await fetch('http://5.78.90.207:9400/data', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(signed),
    });
    console.log('Fetch status:', res.status);
    console.log('Fetch response:', await res.text());
  }
}

main();
