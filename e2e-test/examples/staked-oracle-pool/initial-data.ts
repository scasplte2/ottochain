// Initial state for the staked-oracle-pool e2e.
//
// `authority` is set DYNAMICALLY to the first wallet's DAG address — the signer of the
// authority-gated events (open_first_epoch, finalize). The on-chain guard checks that
// `state.authority` is among the update's proof addresses, so a hardcoded DID string can never
// match a real signer (the original "did:otto:authority" made those guards unsatisfiable). The
// runner signs every update with all wallets, so the first wallet's address is always in proofs.
//
// Stake-custody (heldAssets) and the identity-registry/reputation gate are NOT exercised here —
// like sigma-mixer scopes out economic custody, this e2e covers the oracle MECHANICS (stake/join,
// quorum, trimmed-mean finalize, dedup, participant-gating). Those guards are relaxed in
// definition.json for the e2e and remain covered by the chain unit tests.
export default (context: Record<string, unknown>) => {
  const wallets = context.wallets as Record<string, { address: string }>;
  const authority = wallets[Object.keys(wallets)[0]].address;
  return {
    authority,
    registryId: '00000000-0000-0000-0000-0000000000re',
    minReputation: 50,
    stakePolicy: 'staked-pool-stake-v1.asset',
    stakeAmount: 1000,
    quorum: 3,
    epochLength: 3,
    outlierBound: 10,
    rewardPerEpoch: 4,
    rewardPolicy: 'staked-pool-reward-v1.asset',
    status: 'FORMING',
    epoch: 0,
    epochStartedAt: 0,
    participants: {},
    stakes: {},
    stakeAssetIds: {},
    submissions: [],
    inConsensus: [],
    claimed: {},
    result: null,
  };
};
