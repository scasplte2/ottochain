// Generic MintAsset body driven by step `eventData` { assetId, policy, holderFiber | holderWallet, amount }.
// Signed-message discipline (CLAUDE.md #1): only present fields; optional ones omitted, never null.
export default ({ wallets, session, eventData }: {
  wallets: Record<string, { address: string }>;
  session: { fibers: Record<string, string> };
  eventData: { assetId: string; policy: string; holderFiber?: string; holderWallet?: string; amount: number };
}) => {
  const fiberId = eventData.holderFiber ? session.fibers[eventData.holderFiber] : undefined;
  const address = eventData.holderWallet ? wallets[eventData.holderWallet]?.address : undefined;
  if (!fiberId && !address) throw new Error('mint.ts: unknown holder');
  const holder = fiberId ? { Fiber: { fiberId } } : { Wallet: { address } };
  return {
    assetId: eventData.assetId,
    policyRef: { name: eventData.policy, version: { Exact: { version: '1.0.0' } } },
    holder,
    amount: eventData.amount,
  };
};
