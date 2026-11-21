import { readFileSync } from 'node:fs';
import type { Wallets } from '../../lib/types.ts';

export default ({ wallets, session }: { wallets: Wallets; session: { cid: string } }) => {
  const roles = ['owner', 'agent', 'cosigner', 'stranger'];
  const addresses = roles.map(role => wallets[role]?.address);
  if (addresses.some(a => !a) || new Set(addresses).size !== roles.length)
    throw new Error('The mandate requires four distinct test wallets');
  console.log('Mandate fiber ' + session.cid);
  return {
    ...JSON.parse(readFileSync(new URL('./rules.json', import.meta.url), 'utf8')),
    owner: wallets.owner.address, agent: wallets.agent.address, cosigner: wallets.cosigner.address,
  };
};
