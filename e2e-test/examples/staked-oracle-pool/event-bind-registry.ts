// Authority binds the identity registry as a runtime dependency (#24). Must run FIRST, before any
// reputation-gated stake_and_join — the reputation gate reads machines.<registryId>, which only exists
// after this _addDependency. FORMING -> FORMING. Sign with the authority wallet (first wallet).
export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const authority = wallets[Object.keys(wallets)[0]];
  return {
    eventName: "bind_registry",
    payload: { agent: authority.address },
  };
};
