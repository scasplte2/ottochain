// Authority finalizes the epoch once quorum is met and the submit window has elapsed: COLLECTING ->
// SETTLED. Aggregates the submissions array with a trimmed mean (drop single min + single max), filters
// to the in-consensus set (|value - center| <= outlierBound), and publishes result.value + inConsensus.
// ZERO asset transfers. The outlier (dave=500) is excluded from inConsensus → cannot claim a reward.
export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const authority = wallets[Object.keys(wallets)[0]];
  return {
    eventName: "finalize",
    payload: { agent: authority.address },
  };
};
