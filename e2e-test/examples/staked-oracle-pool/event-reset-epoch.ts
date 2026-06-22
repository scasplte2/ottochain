// Authority advances to the next epoch: SETTLED -> COLLECTING. Clears submissions, inConsensus, and
// claimed; increments epoch and re-stamps epochStartedAt. Stakes/participants persist across epochs.
export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const authority = wallets[Object.keys(wallets)[0]];
  return {
    eventName: "reset_epoch",
    payload: { agent: authority.address },
  };
};
