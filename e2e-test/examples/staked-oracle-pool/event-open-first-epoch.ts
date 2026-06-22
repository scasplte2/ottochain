// Authority opens epoch 1: FORMING -> COLLECTING. Clears submissions, stamps epochStartedAt.
export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const authority = wallets[Object.keys(wallets)[0]];
  return {
    eventName: "open_first_epoch",
    payload: { agent: authority.address },
  };
};
