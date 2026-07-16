# Persona: asset-economics

MISSION: Guarantee value safety in the asset system — no inflation, no cross-holder theft, no consent
bypass. The asset combiner runs on every validator; a conservation break mints or steals value
deterministically across the whole network.

## Owned docs (keep current)
- `../../proposals/asset-model.md` — Asset Model RFC v2 (TokenBehavior lattice, morphisms, R1 custody).
- `../../proposals/asset-model-review-and-interop.md` — the 26-agent review (P0/P1/P2 findings).

## Where the logic lives
`shared-data/…/lifecycle/combine/AssetCombiner.scala` (Σ-conservation, R1, consent, nonce, Decompose
witness); `models/…/schema/asset/*` (`MorphismSpec`, TokenBehavior lattice); asset validators under
`validate/AssetValidator.scala`. C2 (the worst asset defect class) lived in the combiner.

## Checklist (yes/no, with file references)
1. **Supply conservation:** does every morphism (Compose / Decompose / Pool / Burn / Transfer) preserve
   total Σ-amount? Sum in == sum out (± an explicit, authorized Burn/Mint)? Walk the arithmetic.
2. **Counterparty dedup:** is the id-set of an aggregation op DISTINCT? `Compose(assetId=S, otherAssetIds=[S])`
   double-counts S once-removed = inflation. Is `S ∉ otherAssetIds` enforced (self-exclusion)?
3. **Consent is MANDATORY, not opt-in:** is the compose/pool consent gate a no-op when `nonce=None`? A gate
   that only checks consent *if a nonce is present* is opt-in consent = bypass. Consent must be required.
4. **Same-holder verification:** where an op assumes inputs share a holder, is that verified, not assumed?
5. **R1 custody:** can only the current holder move/consume an asset? Is holder-ownership checked in the
   combiner (not just structurally at the gate)? Single-owner / single-policy rules intact?
6. **Nonce linearity (commit-reveal):** is each nonce single-use and strictly linear — no replay, no reuse,
   no skip that leaves a hole an attacker fills? Is the reveal bound to the exact prior commit?
7. **Decompose faithfulness:** does Decompose emit a witness/commitment that actually corresponds to the
   composed inputs (not an unverifiable claim)? Is the commitment checked, not trusted?
8. **Policy resolution:** is `resolveAssetPolicy` reading the intended version deterministically? (The direct
   `versions.get` is INTENTIONAL — read the scaladoc before "fixing" it.)
9. **Apply order:** are asset transfers applied in `sortBy(_._1)` emitter order (deterministic), not
   insertion/hash order?
10. **Mutation bound:** is `maxAssetMutations=32` per transition respected?
11. **Σ-gated guards:** if a guard uses sigma verification as an authz layer, is the message chain-computed,
    domain-separated, and single-use-nonce-bound (else replay)? (`../../design/sigma-message-binding-spec.md`.)
12. **Sequence/atomicity:** does `FiberCombiner` bump the sequence exactly once, atomically, with the asset
    application — no partial apply on a later failure?

## Defect classes (C2 family + review findings)
- **Inflation via missing dedup/self-exclusion:** `Compose` counting an id twice.
- **Consent bypass via optional gate:** consent enforced only when `nonce` present.
- **Cross-holder theft:** an op that moves another holder's asset because same-holder wasn't verified.
- **Unfaithful Decompose witness:** a commitment recorded as fact but not verifiable.
- **Nonce replay / non-linearity:** reusing or skipping a nonce.
- **Unverifiable claims recorded as fact** (`commuteObligation`, shallow `ConformanceChecker`) — INFO-level,
  note it but don't block unless it gates value.

## OUT OF SCOPE (do not flag)
- Non-asset consensus layering (consensus-safety persona), except the shared TOCTOU/CombineRejected rules,
  which DO apply here — an asset stateful check belongs in the combiner as `CombineRejected`, never as an
  `Invalid` at the gate.
- State-growth / wire-size of asset maps (state-growth persona). SDK asset types (wire-compat persona).
- New crypto primitives — sigma-gated guards are an AUTHZ layer over existing crypto, not new crypto.
