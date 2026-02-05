// Bootstrap script to kickstart CL1 block production
const { dag4 } = require('@stardust-collective/dag4');

const SEED_PHRASE = 'right off artist rare copy zebra shuffle excite evidence mercy isolate raise';
const DEST_ADDRESS = 'DAG87hragrbzrEQEz6VC5B7hvtm4wAemS7Zg8KFj';

async function main() {
  const cl1Url = process.argv[2] || 'http://localhost:9300';
  const gl0Url = process.argv[3] || 'http://localhost:9000';
  
  console.log(`Connecting to CL1: ${cl1Url}`);
  console.log(`Connecting to GL0: ${gl0Url}`);
  
  dag4.account.connect({
    networkVersion: '2.0',
    l0Url: gl0Url,
    l1Url: cl1Url,
  });
  
  await dag4.account.loginSeedPhrase(SEED_PHRASE);
  console.log(`Wallet: ${dag4.account.address}`);
  
  // Get balance from GL0
  try {
    const balance = await dag4.network.getAddressBalance(dag4.account.address);
    console.log(`Balance: ${balance?.balance || 0}`);
  } catch (e) {
    console.log(`Balance check failed: ${e.message}`);
  }
  
  // Send transaction
  console.log(`Sending 1 DATUM to ${DEST_ADDRESS}...`);
  try {
    const txHash = await dag4.account.transferDag(DEST_ADDRESS, 1, 0);
    console.log(`Transaction hash: ${JSON.stringify(txHash)}`);
    console.log('\nWait ~30s for CL1 to produce a currency block...');
  } catch (e) {
    console.log(`Transaction failed: ${e.message}`);
  }
}

main().catch(console.error);
