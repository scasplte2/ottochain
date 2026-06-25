// A participant joins the pool: reputation-gated (signerHasReputationVia) + stake-custody verified (H5).
// The participant must have Transferred their whole stake instance into Fiber(poolId) BEFORE this event;
// the guard re-verifies via heldAssets[stakeAssetId].amount >= stakeAmount. FORMING/COLLECTING self-loop.
//
// eventData picks which wallet joins (default: the second wallet) and the staked asset instance id.
import crypto from "crypto";
export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const eventData = context.eventData as Record<string, unknown> | undefined;
  const keys = Object.keys(wallets);
  const joiner = (eventData?.wallet as string) || keys[1] || keys[0];
  const w = wallets[joiner] || wallets[keys[0]];
  return {
    eventName: "stake_and_join",
    payload: {
      agent: w.address,
      stakeAmount: (eventData?.stakeAmount as number) ?? 1000,
      stakeAssetId: (eventData?.stakeAssetId as string) || crypto.randomUUID(),
    },
  };
};
