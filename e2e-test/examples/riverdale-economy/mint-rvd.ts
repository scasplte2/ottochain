/**
 * Generic MintAsset body, driven entirely by the step's `eventData`. ONE mint file serves every RVD
 * (and capped-policy) mint in the flow; the step supplies the instance id, holder, and amount via
 * `eventData`, e.g.:
 *
 *   { action: 'mintAsset', mint: 'mint-rvd.ts', signers: ['alice'],
 *     eventData: { assetId: RVD_LOAN_ID, holderFiber: 'bank', amount: 10000 } }
 *   { action: 'mintAsset', mint: 'mint-rvd.ts', signers: ['alice'],
 *     eventData: { assetId: RVD_STAKE_ID, holderWallet: 'dave', amount: 200 } }
 *
 * `eventData` fields:
 *   - assetId       (required) the fixed instance id (from ids.ts).
 *   - holderFiber   (alias)  ⇒ holder = AssetHolder.Fiber, resolved from session.fibers[alias].
 *   - holderWallet  (name)   ⇒ holder = AssetHolder.Wallet, resolved from wallets[name].address.
 *     (exactly one of holderFiber / holderWallet must be set.)
 *   - amount        (required) the minted amount.
 *   - policyName    (optional, default 'rvd.asset') ⇒ policyRef name (capped mints pass 'capped.asset').
 *
 * Minting into a Fiber holder IS allowed (AssetCombiner.mintAsset). Holder wire forms:
 *   { Fiber: { fiberId } } / { Wallet: { address } }. policyRef is a SchemaRef with VersionReq.Exact.
 * Signed-message discipline (CLAUDE.md #1): only required + present fields — expiresAt/provenance/
 * witness are omitted (Option ⇒ dropNulls keeps the signed canonical aligned).
 */
interface MintContext {
  wallets: Record<string, { address: string }>;
  session: { fibers: Record<string, string> };
  eventData?: {
    assetId: string;
    holderFiber?: string;
    holderWallet?: string;
    amount: number;
    policyName?: string;
  };
}

export default (context: MintContext): Record<string, unknown> => {
  const ed = context.eventData;
  if (!ed) throw new Error('mint-rvd.ts requires the step `eventData` ({ assetId, holderFiber|holderWallet, amount })');
  if (!ed.assetId) throw new Error('mint-rvd.ts eventData.assetId is required');

  let holder: Record<string, unknown>;
  if (ed.holderFiber) {
    const fiberId = context.session.fibers[ed.holderFiber];
    if (!fiberId) throw new Error(`mint-rvd.ts: unknown fiber alias "${ed.holderFiber}"`);
    holder = { Fiber: { fiberId } };
  } else if (ed.holderWallet) {
    const address = context.wallets[ed.holderWallet]?.address;
    if (!address) throw new Error(`mint-rvd.ts: unknown wallet "${ed.holderWallet}"`);
    holder = { Wallet: { address } };
  } else {
    throw new Error('mint-rvd.ts eventData must set exactly one of holderFiber / holderWallet');
  }

  return {
    assetId: ed.assetId,
    policyRef: { name: ed.policyName ?? 'rvd.asset', version: { Exact: { version: '1.0.0' } } },
    holder,
    amount: ed.amount,
  };
};
