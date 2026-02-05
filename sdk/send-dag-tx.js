#!/usr/bin/env node
// Quick script to send a DAG transaction to kickstart CL1 block production
// This unblocks DL1 nodes stuck in SessionStarted state

const { dag4 } = require('@stardust-collective/dag4');

// Test wallet from tessellation genesis.csv
const FIRST_WALLET_SEED_PHRASE = 'right off artist rare copy zebra shuffle excite evidence mercy isolate raise';
const SECOND_WALLET_ADDRESS = 'DAG87hragrbzrEQEz6VC5B7hvtm4wAemS7Zg8KFj';

async function main() {
  const cl1Url = process.argv[2] || 'http://localhost:9300';
  
  console.log(`Connecting to CL1 at ${cl1Url}`);
  
  // Initialize dag4 with CL1 endpoint
  dag4.account.connect({
    networkVersion: '2.0',
    l1Url: cl1Url,
  });
  
  // Login with seed phrase
  await dag4.account.loginSeedPhrase(FIRST_WALLET_SEED_PHRASE);
  console.log(`Logged in as: ${dag4.account.address}`);
  
  // Check balance
  const balance = await dag4.account.getBalance();
  console.log(`Balance: ${balance}`);
  
  // Send a small transaction
  const amount = 1; // 1 DATUM (smallest unit)
  const fee = 0;
  
  console.log(`Sending ${amount} DATUM to ${SECOND_WALLET_ADDRESS}...`);
  
  const txHash = await dag4.account.transferDag(SECOND_WALLET_ADDRESS, amount, fee);
  console.log(`Transaction sent! Hash: ${txHash}`);
  
  console.log('\nThis should trigger CL1 block production, which creates currency snapshots for ML0.');
  console.log('DL1 nodes should then be able to transition from SessionStarted to ReadyToJoin.');
}

main().catch(err => {
  console.error('Error:', err.message);
  process.exit(1);
});
