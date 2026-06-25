// An in-consensus, not-yet-claimed participant pulls ONE whole reward instance the pool holds, to their
// wallet (_transferAsset; per-claim on a shared fungible). Marks claimed[addr]=true. SETTLED/CLOSED
// self-loop. An outlier (not in inConsensus) or a second claim from the same addr is rejected by the guard.
import crypto from "crypto";
export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const eventData = context.eventData as Record<string, unknown> | undefined;
  const keys = Object.keys(wallets);
  const claimer = (eventData?.wallet as string) || keys[1] || keys[0];
  const w = wallets[claimer] || wallets[keys[0]];
  return {
    eventName: "claim_reward",
    payload: {
      agent: w.address,
      rewardAssetId: (eventData?.rewardAssetId as string) || crypto.randomUUID(),
    },
  };
};
