// A participant reclaims their staked instance: ONE whole _transferAsset back to their wallet, and the
// stakeAssetIds entry is unset. H4: the transfer reads stakeAssetIds[agent] against PRE-MERGE state even
// though a sibling key unsets it. SETTLED/CLOSED self-loop. R1 re-checks the pool actually holds the asset.
export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const eventData = context.eventData as Record<string, unknown> | undefined;
  const keys = Object.keys(wallets);
  const who = (eventData?.wallet as string) || keys[1] || keys[0];
  const w = wallets[who] || wallets[keys[0]];
  return {
    eventName: "withdraw_stake",
    payload: { agent: w.address },
  };
};
