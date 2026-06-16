# Asset Interop Functor — RFC

**Status:** draft / design. **Date:** 2026-06-15.

**Goal.** Define `F : Ext → Otto`, a **lax, partial, forgetful** functor that maps every external
asset standard family (`Ext`) into the OttoChain asset model (`Otto`), expressed in *the same
formalism* as the internal model. `F` carries external asset *types* to OttoChain behavior-lattice
points plus a synthesized supply policy, carries external *operations* to OttoChain's typed
morphisms, and respects both the behavior meet-semilattice and the supply law. The thesis is that
`Otto` is a *target category rich enough to receive* such a map from each surveyed standard — every
external standard is a sub-structure or a quotient of `Otto`, never a super-structure. The
load-bearing addition this RFC asks of the asset model is `Option[OriginProvenance]` plus a
deterministic canonical `policyId` derived from origin: the structural cure for the wrapped-asset
fragmentation pathology.

**Companion:** `docs/proposals/asset-model.md` (the internal asset model — `TokenBehavior`,
`AssetPolicy`, typed morphisms, `AssetRecord`, `AssetHolder`, `AssetCombiner`, `AssetCommit`).
**Invariants honored:** `docs/signing-canonical-and-validation.md` and `CLAUDE.md` rules #1
(Option-or-required signed fields), #2 (structural-only block-validity gate), #3 (no
registry/asset/policy *lineage* reads in `validateSignedUpdate`).

---

## Contents

- [1. Thesis & relationship to the asset model](#1-thesis--relationship-to-the-asset-model)
- [2. The internal model as an algebraic structure](#2-the-internal-model-as-an-algebraic-structure)
- [3. External families in the same structure](#3-external-families-in-the-same-structure)
- [4. The interop functor `F : Ext → Otto`](#4-the-interop-functor-f--ext--otto)
- [5. Per-standard adapter table](#5-per-standard-adapter-table)
- [6. Round-trip, adjointness, provenance & the wrapping hazard](#6-round-trip-adjointness-provenance--the-wrapping-hazard)
- [7. Two worked examples](#7-two-worked-examples)
- [8. Required additions to the asset model](#8-required-additions-to-the-asset-model)
- [9. Open questions](#9-open-questions)

---

## 1. Thesis & relationship to the asset model

OttoChain's asset model (the companion RFC) gives assets a 5-bit `TokenBehavior` ordered by a
meet-semilattice, a versioned `AssetPolicy`, a partial typed graph of morphisms, and a combiner that
enforces conservation and codomain laws. That richness is not accidental: it is precisely enough to
**receive a structure-preserving map from every external asset standard**. The map is `F : Ext → Otto`.

The central empirical finding — established family-by-family in §3 and §5 — is that *every* external
standard is a **sub-structure or quotient** of `Otto`:

- its behavior order `≤_k` is **coarser** than Otto's `≤` (most families collapse it to a single
  "fungible" point or a small flag set);
- its operation set `Op_k` is a **subset** of Otto's morphism kinds, plus at most one *delegation*
  primitive (allowance / operator / authz grant) that Otto folds into a `Governed`-`Transfer` guard;
- its supply law is one of three archetypes (capped, lock-mint, burn-mint), each a special case of
  Otto's derived-supply law.

No surveyed standard exposes a behavior or an operation that `Otto` cannot express. `F` therefore
maps *into* `Otto` and never *out of* its expressivity — `Otto` is the colimit, not a peer.

### A note on rigor

This RFC builds on the corrected formalism of the asset model and inherits its honesty. Four facts
are load-bearing and stated plainly so that no Scala implementation chases a structure that does not
exist:

1. **`meet` is a genuine greatest-lower-bound — but only with the order E/G-reversed.** It is *not*
   the naive Boolean cube. `(𝓑, ≤)` is the product lattice `(𝔹,≤)³ × (𝔹,≥)²`: T/S/C ordered the
   usual way, E/G **order-reversed** (`true < false`). Under this order `meet` is the componentwise
   glb, `top = 28` (Fungible), `bottom = 3` (`EG`), and `Soulbound = 0` is an **interior point**, not
   the bottom. The folk-claim "a basket with a soulbound component is soulbound" is **false**.
2. **The composite-behavior map is a strict monoid homomorphism** — the strongest categorical content
   in the model: `behavior(Compose(xs)) = foldMeet(map(behavior, xs))`, empty aggregate ↦ top.
3. **There is no "monoidal category."** What holds is a commutative *aggregation monoid* (multiset
   union of components) plus that homomorphism; `Decompose ∘ Compose = id` is a **retraction**
   (left inverse realized by stored data), explicitly *not* a tensor inverse.
4. **The morphisms are a partial typed graph, not a category** — no identity arrows, partial
   composition. "Morphism chains type-check" means typed-graph reachability.

`F` lands in a meet-semilattice, commutes with the behavior homomorphism on the Otto side, and is
partial in exactly the places the morphism graph is partial. Wherever this RFC could overclaim — a
two-sided inverse, a clean adjunction, a Galois connection — it does not.

---

## 2. The internal model as an algebraic structure

### 2.1 The behavior lattice `(𝓑, ≤, meet)`

Let `𝓑 = {0,…,31}` be the 32 `TokenBehavior` points, each a 5-tuple `(T,S,C,E,G) ∈ 𝔹⁵` with
`bits = 16·T + 8·S + 4·C + 2·E + 1·G`. The order — **stated explicitly here** because it is what
makes `meet` a glb and what makes `F`'s monotonicity meaningful:

```
a ≤ b  ⟺  (a.T ≤ b.T  ∧  a.S ≤ b.S  ∧  a.C ≤ b.C)      — T,S,C : Boolean false < true
        ∧ (a.E ≥ b.E  ∧  a.G ≥ b.G)                     — E,G   : REVERSED order true < false
```

So `(𝓑, ≤)` is `(𝔹,≤)³ × (𝔹,≥)²`. The operation

```
meet(a,b) = (a.T ∧ b.T,  a.S ∧ b.S,  a.C ∧ b.C,  a.E ∨ b.E,  a.G ∨ b.G)
```

is **literally the componentwise greatest lower bound** on that lattice (the OR on E/G is the AND of
the reversed factor). It is idempotent, commutative, associative, and `meet(a,b) ≤ a, b`.

| landmark | bits | `(T,S,C,E,G)` | meaning |
|---|---|---|---|
| **top** `⊤` | `28` | `(1,1,1,0,0)` | Fungible — most capable, least restricted |
| **bottom** `⊥` | `3` | `(0,0,0,1,1)` | `EG` — non-transferable, expirable, governable; most restricted |
| Soulbound | `0` | `(0,0,0,0,0)` | **interior point** (not the bottom) |
| NFT | `16` | `(1,0,0,0,0)` | transferable, indivisible, non-mergeable |

This is load-bearing for interop: **moving down `≤` means becoming more restrictive**, and `F`'s
classifier only ever moves an imported asset down (§4). Composing with `Soulbound = 0` forces
`T = S = C = 0` but can *acquire* `E`/`G` from the partner — see the behavior-acquisition hazard in §6.

### 2.2 Objects

An internal **asset type** is a pair

```
Obj_Otto = (β, π)        β ∈ 𝓑 (a behavior-lattice point),    π an AssetPolicy
AssetPolicy = (behavior = β, maxSupply: Option[Long], mintPolicy, burnPolicy, morphisms)
```

The behavior `β` is duplicated into `π.behavior` by construction; supply concerns (`maxSupply`,
`mint/burnPolicy`) live *only* in `π`, orthogonal to `β`. Instances of a type are `AssetRecord`s
`(policyId, behavior, holder, amount, componentFiberIds?, …)`. The **type** is the object; instances
are its elements. (In the companion RFC's registry-package model, `AssetPolicy` is a versioned
registry package over a `VersionLineage`; `β`/supply/morphisms live in a `RegistryShape.AssetPolicy`
projection. Nothing here depends on whether the policy is a free record or a registry package — only
on the `(β, π)` pairing.)

### 2.3 Morphisms — a partial typed graph

The transformations are arrows with a **domain guard** (a predicate on `β`) and a **codomain
function** (`β ↦ β'`):

| arrow | domain guard | codomain `β'` |
|---|---|---|
| `Transfer` | `T = 1` | `β` (holder changes) |
| `Burn` | — | terminal `⊥_obj` (destroyed) |
| `Fractionalize` | `S = 1` | `β` with `C := 0` |
| `Compose` | all parts `C = 1` | `meet(parts)` |
| `Decompose` | `isComposite` | restored component `β`s |
| `Wrap` | `T = 1` | `β` (identity-preserving) |
| `Stake` | `T = 1` | `β` with `E := 1` (moves *down* the lattice) |

This is **not a category**: there is no identity arrow (`Wrap`/`Transfer` change instance
identity/custody, not no-ops), and composition is **partial** — `Fractionalize` (codomain `C = 0`)
followed by `Compose` (guard `C = 1`) is structurally rejected. We call it a *typed morphism graph*;
"morphism chains type-check" means typed-graph reachability, checked by the L1 structural layer
(`AssetCommit.behavior` on `OnChain`, an O(1) bitmask check).

### 2.4 The two genuine algebraic facts `F` must preserve

1. **Aggregation monoid.** Components compose by multiset-union (`⊎`) of `componentFiberIds`; the unit
   is the empty multiset. `Compose` is union; `Decompose` is the left inverse realized by *storing the
   component ids verbatim* — a **retraction** `Decompose ∘ Compose = id`, explicitly not a two-sided
   inverse (`Compose ∘ Decompose` is *not* claimed).
2. **Behavior homomorphism.** `behavior : (AssetAggregate, ⊎) → (𝓑, meet)` is a **strict monoid
   homomorphism**: `behavior(Compose(xs)) = foldMeet(map(behavior, xs))`, with the empty aggregate
   mapping to the lattice top `28`. This is the invariant the combiner re-checks, and the only
   checkable statement about composition — never the "soulbound dominates" folk-claim.

---

## 3. External families in the same structure

To map *into* `Otto` we first give each external family `Ext_k` the same shape: a set of objects, a
set of operations with domain/codomain, an (often degenerate) behavior order `≤_k`, and a supply law
`supply_k`. The survey of seven ledger families yields a uniform picture: **every external standard
is a sub-structure or quotient of `Otto`.** Three structural archetypes recur.

**Account-ledger fungibles** — ERC-20, SPL, Cosmos `x/bank` denom, Aptos FA, Algorand ASA, Tezos
FA1.2. Objects are `(contract | mint | denom | metadata-object)` identities. The behavior order is
essentially **trivial** — a single point "fungible" (`T,S,C` all live; divisibility is `S`,
balance-merge is `C`). Operations: `transfer`, `approve`/`transferFrom` (a *delegation* refinement of
`Transfer`), `mint`, `burn`. Supply law: a **mutable counter** — the model `Otto` deliberately
rejects as ground truth but keeps as a cache.

**UTXO / value-bundle ledgers** — Cardano native assets, Sui `Coin`/object, Bitcoin Runes. Objects
are `(PolicyID, AssetName)` / object-`UID` / `RuneId`; fungibility is convention (`quantity = 1 ⇒
NFT`). The headline is that **`Transfer` carries no asset-specific code** — value conservation does
the accounting. This is the external structure *closest* to Otto's "`Transfer(T=1)` is a structural
L1 check," and Cardano's `Value = Map PolicyID (Map AssetName Qty)` bundle is the strongest precedent
for Otto's aggregation monoid.

**Per-ID multi-token & behavior-flag families** — ERC-1155/6909, Token-2022 extensions, FA2
`token_id`, Charms tag-discrimination, Move FA, RGB interfaces. Objects are *many sub-assets under one
host*; behavior is a **small flag set** (`normal/collectible`, `t/n`, Token-2022's ~19 TLV
extensions, FA2's permission-policy taxonomy). These are the families whose behavior order is
*non-trivial*, and therefore the families where `F`'s lattice-preservation is *informative* rather
than vacuous: a Token-2022 `NonTransferable` mint ↦ `T = 0`; a `DefaultAccountState = frozen` / FA2
`pauseable` policy ↦ a `Governed`-`Transfer` (`G`-flavored) refinement; a Charms `t`-tag ↦ Fungible,
an `n`-tag ↦ NFT.

Formally, for each `Ext_k` we have `(Obj_k, Op_k, ≤_k, supply_k)`. The recurring fact is that `≤_k`
is *coarser* than Otto's `≤` (most families collapse it to a point or a handful of flags), and `Op_k`
is a *subset* of Otto's morphism kinds plus a delegation primitive (allowance/operator) that Otto
folds into a `Governed`-`Transfer` guard.

---

## 4. The interop functor `F : Ext → Otto`

Define `F` on the disjoint union `Ext = ⊔_k Ext_k`.

### 4.1 On objects

`F(X) = (β_X, π_X)` where `β_X = classify(X)` is the lattice point read off `X`'s structural shape,
and `π_X` is the synthesized policy:

```
β_X            = ⊓ over readable structural flags of X     — meet, never join: foreign flags only ADD restriction
π_X.maxSupply  = supply_k(X) if X exposes a hard cap, else None     — None for lock-mint vouchers (supply tracks escrow)
π_X.mintPolicy = "mint iff a valid inbound attestation is presented"  — for wrapped; the verifier check lives here
π_X.burnPolicy = "burn ⇒ emit outbound unlock/burn message"
π_X.morphisms  = the Op_k operations, re-typed (§4.2), with foreign delegation → Governed Transfer
```

`classify` reads `X` **conservatively** and is the heart of `F`'s safety: it always *adds* restriction
(moves *down* `≤`) and never invents capability it cannot read.

```
ERC-20                       ⇒ Fungible (28)
ERC-721                      ⇒ NFT (16)
Token-2022 NonTransferable   ⇒ clear T
permanent-delegate / clawback / freeze ⇒ set G   (a governed-transfer override exists)
FA2 no-transfer policy       ⇒ Transfer Disabled
```

### 4.2 On morphisms

`F` sends each external operation to its typed-graph arrow:

```
transfer              ↦ Transfer
approve; transferFrom ↦ Transfer with visibility = Governed + delegation-record guard   (allowance/operator)
permit (EIP-2612)     ↦ Governed Transfer guarded by a one-time usedNonce               (≈ AuthorizeCompose nonce)
mint                  ↦ MintAsset under π_X.mintPolicy
burn                  ↦ Burn   (or burn-unlock: Burn + outbound message)
vault / WETH wrap     ↦ Wrap   (+ fiber-as-asset-holder custody)
NFT fractionalize     ↦ Fractionalize   (S = 1 → C = 0 shards)
1155 / bundle / Value ↦ Compose / Decompose   (aggregation monoid)
```

The two delegation cases are worth stating: `approve; transferFrom` becomes a `Governed Transfer`
plus a **delegation record** (an allowlist + amount limit), and `permit` (EIP-2612) becomes a
`Governed Transfer` guarded by a **one-time `usedNonce`** — EIP-2612's per-owner nonce *is* Otto's
linear nonce by construction (no approve race, single use).

### 4.3 Why `F` is lax, partial, and forgetful

Each adjective is *forced*, not a hedge.

**Forgetful.** `F` discards exactly the structure Otto's lattice has no coordinate for: ERC-20
`decimals` precision (Otto models `S` as a boolean "splittable," not a fixed-point scale), EIP-2981
royalty *amounts* (Otto keeps royalty as a policy field, not a structural bit — matching EVM's
non-enforcement), CIP-25/68 off-chain metadata bodies, Token-2022 transfer-*fee* basis points, Sui
object `version`/`digest`. The forgotten data is carried, **un-typed**, in policy/provenance metadata
(§6), not in `β`. This is the *right* kind of forgetful: `F` is **faithful on the structural
skeleton** (`T/S/C/E/G`, the supply law, the morphism kind) and forgetful only on
quantitative/presentational decoration.

**Lax (not strict) on composition.** `F` does **not** strictly preserve composite behavior, because
external bundles do not all carry Otto's conservation law. The lax structure-map is the comparison

```
φ :  meet(F(A).β, F(B).β)  ≤  F(A ⊗_ext B).β
```

For families with **ledger-enforced bundles** (Cardano `Value`, Sui dynamic-object-fields, Charms
"string of charms"), `φ` is an **equality** and `F` is strict on those — these are exactly the
families where the external structure already *has* a conservation law. For families where bundling is
an **uncodified custody contract** (EVM WETH / ERC-4626 / baskets), the foreign side has no enforced
`meet`, so `F` can only promise the inequality: Otto's composite is *at least as restrictive* as the
foreign bundle pretended to be.

Crucially, `F` **always commutes with the behavior homomorphism on the Otto side**: once an asset is
inside Otto, `behavior(Compose(F(A), F(B))) = meet(F(A).β, F(B).β)` holds **strictly**, by §2.4 fact
2. The laxity is entirely about the *foreign* operation's fidelity, never about Otto's internal
invariant.

**Partial.** `F` is undefined / object-valued-only on inputs whose operations have no typed-graph
arrow with a satisfiable domain guard:

- ERC-1155/6909 **singleton multi-token contracts** have **no single object** image — they are a
  *namespace of objects*. `F` sends them to a family `{(β_id, π_id)}` grouped by a registry namespace,
  not to one `(β, π)`. This is the one structural gap the survey flags in the model (see §9).
- **Soulbound imports** (`T = 0`) admit `F(Burn)` but `F(Transfer)` lands on an unsatisfiable guard
  (`Disabled`) — a *partial* arrow, mirroring the morphism graph's own partiality.
- Any chain `F(op₁); F(op₂)` whose Otto image fails a domain guard (e.g. `Fractionalize` then
  `Compose`) is rejected, exactly because the target is a partial graph.

### 4.4 Preservation theorems

Both are checkable.

- **Lattice (monotonicity).** For all readable foreign flags,
  `classify(restrict_ext(X)) ≤ classify(X)` — adding a foreign restriction (freeze, non-transferable,
  partition lock) moves the image *down* `≤`. Equivalently `F` is **monotone** into `(𝓑, ≤)`. This is
  what makes a foreign soulbound/NFT, once bridged, *stay* restrictive inside Otto baskets
  automatically — a guarantee no surveyed bridge provides.
- **Supply.** `F` maps each external supply archetype to the policy shape that makes Otto's
  derived-supply law hold:

  | external archetype | `π.maxSupply` | supply ground truth |
  |---|---|---|
  | **lock-mint** | `None` | `totalMinted` derived from voucher records, equal-by-invariant to escrow (the IBC `TotalEscrowForDenom` invariant becomes "supply derived from records") |
  | **burn-mint** | `None` (canonical) | a single global `policyId` whose supply is invariant across the mesh |
  | **capped** (ERC20Capped, ASA `Total`, Runes `premine + cap·amount`) | `Some(cap)` | derived, bounded by the cap |

---

## 5. Per-standard adapter table

For each family: object mapping (`→ β` + policy), morphism mapping, **PRESERVED**, **LOST** (the
forgetful image), and the OttoChain custody/bridging mechanics. Custody is always one of: **lock-mint**
(foreign asset escrowed in a custody fiber, voucher minted on Otto — for assets we don't control), or
**burn-mint** (foreign supply burned, canonical Otto supply minted — when the issuer controls mint
authority). Provenance is always a **denom-trace-style record** carried in `AssetPolicy`/`AssetRecord`
metadata (§6).

### 5.1 ERC-20 (and EIP-2612 / xERC20)

- **Object → β + policy:** `Fungible(28)`. `π.maxSupply = Some(cap)` iff `ERC20Capped`, else `None`.
  `mintPolicy = onlyOwner / MINTER_ROLE` ⇒ a guard checking caller ∈ `owners`.
- **Morphisms:** `transfer ↦ Transfer`; `approve; transferFrom ↦ Governed Transfer + delegation
  record`; `permit ↦ Governed Transfer guarded by usedNonce` (EIP-2612's per-owner nonce *is* Otto's
  linear `usedNonce`); `mint`/`burn ↦ MintAsset`/`Burn`.
- **PRESERVED:** fungibility (`T,S,C`), supply-cap semantics, mint authority, the
  delegation-with-one-time-nonce pattern.
- **LOST:** `decimals` precision (becomes boolean `S`); the approve race-condition footgun (Otto's
  nonce is one-time by construction); fee-on-transfer / rebasing hook *amounts* (kept as a policy
  guard, not a bit).
- **Custody:** **lock-mint** by default (we rarely hold mint authority on a foreign ERC-20) via a
  custody fiber holding the escrow record; **burn-mint** only for issuer-controlled / xERC20-style
  tokens with a granted minter. Provenance: `(originChainId, contractAddress)`.

### 5.2 ERC-721 / 1155 / 6909

- **Object → β + policy:** ERC-721 ⇒ `NFT(16)`, one `AssetRecord(amount = 1)` per `tokenId`.
  ERC-1155/6909 ⇒ **not a single object**: a *registry namespace* of `AssetPolicy`s, one per `id` — a
  fungible `id` ⇒ `Fungible(28)`, a supply-1 `id` ⇒ `NFT(16)`. **(This is the singleton-multi-token
  gap; `F` is `namespace`-valued / object-family-valued here — see §9.)**
- **Morphisms:** `safeTransferFrom ↦ Transfer`; `setApprovalForAll` / `setOperator ↦ Governed
  Transfer` (blanket operator = unlimited delegation — flagged as coarse/dangerous; Otto prefers a
  tight allowlist + one-time nonce); 6909's per-id allowance ↦ per-policy delegation.
- **PRESERVED:** per-`tokenId` identity; the semi-fungible "id with supply N" via `amount` on the
  record.
- **LOST:** `tokenURI` / `uri(id)` metadata bodies; the callbacks (`onERC1155Received`) — Otto custody
  is in-VM, no CPI-style callback needed; the 1155 *singleton* concept (modeled as a namespace, not a
  host object).
- **Custody:** lock-mint per `tokenId` into a custody fiber; provenance `(originChainId,
  contractAddress, tokenId)`. **NFT provenance is the lossiest import** — the EVM has no on-chain
  "this wrapped NFT IS that origin NFT," so the Otto provenance record is the *only* binding (§6, §9).

### 5.3 Cardano native assets

- **Object → β + policy:** `Fungible(28)` if `quantity > 1`, `NFT(16)` if the policy enforces
  supply-1. `π.maxSupply` from the one-shot / time-lock policy; `mintPolicy`/`burnPolicy` synthesized
  from the (immutable) Cardano policy script — Otto **splits** Cardano's fused mint+burn into two
  policies and **adds** a `maxSupply` cap Cardano lacks.
- **Morphisms:** `Value`-redistribution `↦ Transfer` (structural, no code — the cleanest
  `F(Transfer)`); a `Value` bundle `↦ Compose / Decompose` with **strict `φ`** (Cardano's
  ledger-enforced conservation makes `F` strict here).
- **PRESERVED:** first-class-ledger-asset semantics; value-conservation (maps to Otto's combiner
  conservation invariant); the bundle-as-aggregation-monoid.
- **LOST:** the PolicyID↔script *permanence* (Otto policies are versionable — an enrichment, not a
  loss); CIP-25/68 metadata bodies (carried as provenance metadata).
- **Custody:** **lock-mint** — a custody fiber is the analogue of a Plutus script address; the CIP-68
  reference-NFT-with-inline-datum pattern maps to Otto's *fiber-as-asset-holder* with an upgradeable
  governing guard. Provenance `(PolicyID, AssetName)`.

### 5.4 Cosmos ICS-20 voucher

- **Object → β + policy:** `Fungible(28)`; `π.maxSupply = None` (supply tracks escrow).
  `mintPolicy = "mint iff valid inbound IBC proof"`; `burnPolicy = "burn ⇒ emit outbound, unescrow"`.
- **Morphisms:** `MsgSend ↦ Transfer`; `x/authz SendAuthorization ↦ Governed Transfer with spend-limit
  guard`; ICS-20 escrow/mint ↦ `Wrap` + `MintAsset`, burn/unescrow ↦ `Burn`.
- **PRESERVED:** the **source/sink-zone escrow-vs-mint rule** (an O(1) prefix test → fits Otto's L1
  structural layer); the 1:1 escrow-backs-voucher conservation (→ derived supply); and most
  importantly the **denom-trace provenance model** — this is the family Otto borrows its provenance
  design from wholesale (§6).
- **LOST:** nothing structural — ICS-20 is *less* expressive than Otto (no `S/C/E/G` typing); the
  lossy part is on the *foreign* side (non-canonical voucher fragmentation), which Otto's
  canonical-policyId derivation (§6) fixes.
- **Custody:** **lock-mint** with an escrow custody fiber (`HOLDING → RELEASED`); provenance = the full
  ordered path `{port/channel}*` carried verbatim in `AssetPolicy` metadata, hashed for the `policyId`.

### 5.5 Bitcoin: Charms / Runes / RGB / Taproot Assets

- **Object → β + policy:** Charms `t`-tag ⇒ `Fungible(28)`, `n`-tag ⇒ `NFT(16)` (tag-discrimination
  is the crudest 5-bit `β`); Runes ⇒ `Fungible(28)` with `π.maxSupply = premine + cap·amount`; RGB20 ⇒
  Fungible, RGB21 (UDA) ⇒ NFT, RGB25 ⇒ Fractionalize codomain; Taproot Assets `normal` ⇒ Fungible,
  `collectible` ⇒ NFT.
- **Morphisms:** Runes `edict ↦ Transfer`; Charms `string of charms ↦ Compose` with **strict `φ`**
  (Charms' contract sees the whole tx — its `app_contract(app, tx, x, w) → bool` is the *same predicate
  shape* as Otto's combiner guard, `x ↦ public context`, `w ↦ witness`); Taproot
  `split_commitment ↦ Fractionalize`, merge ↦ `Compose`; RGB owned-state assignment ↦ `Transfer`.
- **PRESERVED:** the **L1 structural fast-path** — Charms' "simple `t`/`n` transfer needs no app proof,
  structural conservation/identity only" is *exactly* Otto's `AssetCommit`-on-`OnChain` O(1) check;
  conservation-as-structural-invariant (Taproot MS-SUM tree, Charms sum-check).
- **LOST:** the off-chain client-side-validation / recursive-proof *substrate* (Otto re-implements
  conservation as a deterministic combiner invariant rather than a zk proof — and the lesson is that
  this is a *feature*, given the SPL ZK-ElGamal soundness failure of June 2025); the RGB/Charms
  data-availability model.
- **Custody:** Charms' chain-agnostic "beaming" is the model to study — "the proof is the bridge." For
  lock-mint into Otto, the custody fiber holds the Bitcoin-side claim; provenance =
  `(tag, identity, vk)` (Charms) / `RuneId = BLOCK:TX` / Taproot `asset_id`.

### 5.6 Move: Coin / Fungible Asset (Sui, Aptos)

- **Object → β + policy:** Sui `Coin<T>` / Aptos FA ⇒ `Fungible(28)`; `NonTransferable` / soulbound ⇒
  `NFT`-or-`Soulbound` with `T = 0`; `π` carries the capability authorities.
- **Morphisms:** `transfer ↦ Transfer` (possession-is-authority, no allowance — aligns with Otto's
  holder-signs model); Sui `TreasuryCap` / Aptos `MintRef`/`BurnRef ↦ MintAsset`/`Burn` gated by a
  capability guard; Aptos **dispatchable FA hooks** (`withdraw`/`deposit`) `↦ Governed Transfer guards`
  — *structurally identical* to Otto attaching per-morphism guards; Aptos `PermanentDelegate` /
  clawback ↦ `Governed Transfer` override (sets `G`).
- **PRESERVED:** capability-as-authority (Otto can model a *capability record* — a non-fungible
  `AssetRecord` acting as mint-cap); the Coin↔FA **canonical pairing** (the lesson Otto adopts for
  DAG-currency, §9: one canonical `policyId` + conversion morphisms, not two unrelated reps); Move's
  **linear-resource conservation** — which Otto, lacking linear types, re-implements as the combiner
  conservation invariant (`AssetMorphismLawSuite`).
- **LOST:** Move's compile-time linearity guarantee (becomes a runtime combiner check); the hot-potato
  `store`-less type (becomes "a morphism fully applies or is gracefully `CombineRejected`"); PTB
  atomic-command chaining.
- **Custody:** **burn-mint** when the issuer controls the `TreasuryCap`/`MintRef`; **lock-mint**
  otherwise. Critical warning imported from Aptos: the dispatchable-hook **reentrancy rule** ("must use
  `*_with_ref`; the dispatchable entry aborts inside a hook") becomes Otto's invariant that
  `_transferAsset` effects are *single-pass, non-reentrant per combiner pass*.

### 5.7 SPL Token-2022

- **Object → β + policy:** a Mint with TLV extensions ⇒ `(β, π)` where `β` reads the extensions:
  `NonTransferable ⇒ T = 0`; `DefaultAccountState = frozen` / freeze-authority ⇒ `G = 1` (governed);
  `Group/Member ⇒` a registry namespace (collection). Token-2022's "behavior flags on one mint" is the
  *closest external precedent* for Otto's `(β, π)` — but Otto separates `β` (instance) from `π`
  (supply/policy) more cleanly than SPL splits base-flags vs extensions.
- **Morphisms:** `TransferChecked ↦ Transfer`; `Approve`-delegate (bounded `delegated_amount`) ↦
  `Governed Transfer with amount-limited guard`; the **Transfer Hook** extension (CPI to external
  program, accounts read-only + `transferring` flag) `↦ Otto's Layer-3 JSON-Logic guard` — and Otto's
  runs *in-VM* (no CPI), the noted security advantage; `PermanentDelegate ↦ Governed clawback`;
  `MintTo`/`Burn ↦ MintAsset`/`Burn`.
- **PRESERVED:** composable-behavior-flags-on-one-asset; the transfer-hook-as-policy-guard;
  withhold-fee semantics (as a policy field); permanent-delegate/clawback and default-frozen as
  `Governed` morphisms.
- **LOST:** the **extension-set-frozen-at-mint** limitation (Otto's versionable fibers + `UpgradeFiber`
  *fix* this — a strict improvement, so it's a "loss" only of a misfeature); confidential-transfer ZK
  (Otto's readable-JLVM-first thesis deliberately omits the novel-ZK-primitive liability that broke
  SPL in June 2025); interest-bearing UI-amount trick.
- **Custody:** **burn-mint** for issuer-controlled; **lock-mint** for bridged (Wormhole-style, with the
  wrapped mint's authorities held by a custody fiber). Provenance `(originChainId, mintAddress)`.

---

## 6. Round-trip, adjointness, provenance & the wrapping hazard

### 6.1 `wrap ∘ unwrap` is a retraction, not an inverse

Inside Otto, `wrap ∘ unwrap = id` holds **by construction**: `Wrap` is identity-preserving on `β`
(codomain `= β`), and an `Unwrap` (the inverse custody transition — `Burn` the voucher + release the
held original from the custody fiber) restores the recorded origin. This is *the same retraction
shape* as `Decompose ∘ Compose = id` (§2.4 fact 1): the round-trip is a **left inverse realized by
stored data** — the custody fiber stores the origin claim verbatim and returns it unmodified, exactly
as `Compose` stores `componentFiberIds`.

It is **not a two-sided inverse**: `unwrap ∘ wrap` is *not* the identity on the *foreign* side. You
cannot re-derive the foreign asset's lost decoration (`decimals`, royalty amounts, off-chain metadata)
from the Otto voucher — that is the forgetful image of §4.3.

### 6.2 Import/export is adjunction-shaped but obstructed

Let `F` (import: foreign → Otto voucher) and `G` (export: Otto voucher → foreign release). Honesty
requires stating the relationship as adjunction-*shaped*, **not** a clean Galois connection on the
nose:

- The unit `η : X → G(F(X))` is the "round-trip a foreign asset out and back" map.
- The counit `ε : F(G(Y)) → Y` is "unwrap then re-wrap."
- The **triangle holds on the structural skeleton**: `ε ∘ F(η) = id` on `β` and on the provenance
  record (IBC's bit-identical `A → B → A` round-trip is the canonical witness — the denom trims back
  to the native denom).
- The would-be adjunction **fails on the forgetful coordinates**: `η` is not invertible on the
  decoration `F` forgot.

So `F ⊣ G` holds **only after quotienting out the forgotten data**:

> **`F` is left adjoint to `G` on the structural quotient `Otto/≅_decoration`, and merely a partial
> retraction on the full model.** A *reflective* relationship on structure, not a clean adjunction.

Do **not** implement a literal two-sided inverse, a tensor inverse, or a Galois connection in Scala —
the same overclaim discipline as "not a monoidal category" in §1.

### 6.3 Provenance preservation (the IBC denom-trace analogue)

The forgotten data and the origin binding are carried, **un-typed**, in metadata. The asset model
gains:

```scala
final case class OriginProvenance(
  originChainId:   String,        // e.g. "eip155:1", "cardano-mainnet", "cosmoshub-4"
  originAssetRef:  String,        // contract+tokenId / (PolicyID,AssetName) / mint / denom-base
  fullPath:        List[Hop],     // the ordered denom-trace path; prepend forward, trim backward
  attestationHash: Hash           // commitment to the inbound lock/burn attestation
)
```

carried as `Option[OriginProvenance]` on `AssetRecord` and `AssetPolicy`:

```scala
final case class AssetRecord(
  // … existing fields …
  origin: Option[OriginProvenance] = None     // None for natively-issued assets
)
```

The field **MUST be `Option`** (signing-canonical invariant #1): `null` is dropped by `dropNulls` on
both signing and verification sides, so an omitted origin round-trips; a non-`Option` field with a
default (`false`/`{}`/`[]`) would re-inflate on the chain's decode and diverge the canonical →
`InvalidSignature`. A native asset omits `origin` entirely; a wrapped asset always carries it.

**The source/sink-zone hop rule (IBC verbatim).** On a **forward hop** the receiving side *prepends*
its hop (source-zone ⇒ escrow + mint), growing `fullPath`. On a **backward hop** it *trims* the
leading hop (sink-zone ⇒ burn + unescrow). Deciding source vs sink is an **O(1) prefix test** on
`fullPath` — exactly the shape Otto's L1 structural layer wants, so it can live as a structural check
on `AssetCommit` without reading `CalculatedState` lineage (invariant #3).

**Canonical identity (the cure for fragmentation).** Derive a deterministic `policyId` from origin —
the Axelar `interchainTokenId = keccak256(deployer, salt)` pattern:

```
policyId = derive(originChainId, originAssetRef)
```

enforced as an `AssetCombiner` **uniqueness invariant** so the **same foreign asset always resolves to
exactly one Otto `AssetPolicy`**. This must be a combiner check (it reads existing asset/policy state)
and **never** a `validateSignedUpdate` lineage read (invariant #3 — a TOCTOU read there would
block-poison the snapshot).

### 6.4 The two distinct wrapping hazards

**(1) Fragmentation** — the same origin asset wrapped by N bridges ⇒ N mutually-non-fungible vouchers
(the "10+ wrapped USDCs" pathology). **Cure:** the canonical-`policyId`-from-origin uniqueness
invariant above — a second `Wrap` of an already-wrapped origin resolves to the *same* policy, not a
new one. The `AssetCommit` on `OnChain` gains an **origin discriminator** so L1 can *structurally*
reject a double-wrap of the same origin (an O(1) check, no lineage read).

**(2) The behavior-acquisition hazard** (the subtle one). Because the lattice reverses E/G,
**composing/wrapping can ADD `E`/`G`** that the foreign asset did not have:

```
meet(Fungible-imported = 28, GovernedFungible = 29) = 29    — the composite GAINS governance
```

An integrator must **not** assume "my bridged non-governed token cannot become governed by being put
in an Otto basket" — it can. This is by design (acquiring governance/expiry is "restrictive," i.e.
*down* the lattice), but it must be **surfaced in tooling**. The correct, checkable statement (which
`AssetMorphismLawSuite` asserts) is the homomorphism, *not* the folk-claim:

```
behavior(Compose(xs)) = foldMeet(map(behavior, xs))
```

and the only *forced-off* coordinates from a soulbound component are `T/S/C` — never a guarantee about
`E/G`.

---

## 7. Two worked examples

### 7.1 Importing USDC (ERC-20, lock-mint)

Source: `USDC` at `(chainId = 1, 0xA0b8…eB48)`, `decimals = 6`, mint gated by `MINTER_ROLE`, no
on-chain cap. We do **not** hold its minter, so: **lock-mint**.

```
classify(USDC)           = Fungible = 28   (T=1, S=1, C=1,  E=0, G=0)
F(USDC) = (β = 28, π) where
  π.behavior   = 28
  π.maxSupply  = None                       // supply tracks escrow, derived from voucher records
  π.mintPolicy = "mint iff a valid inbound lock-attestation is presented"   // verifier check (DVN / light-client) as guard
  π.burnPolicy = "burn ⇒ emit outbound unlock message to chainId = 1"
  π.morphisms  = { Transfer:            Public,
                   Transfer(delegated): Governed + one-time usedNonce,       // ← approve / permit
                   Compose/Decompose:   Public }                             // fungible, freely bundlable
```

- **TokenBehavior:** `28 (TSC--)`.
- **AssetPolicy:** `maxSupply = None`; derived `totalMinted = Σ voucher amounts ≡ escrow` (the IBC
  `TotalEscrowForDenom` invariant, now "supply derived from records").
- **Custody fiber:** a `USDC-escrow` state machine, `HOLDING → RELEASED`, holding the origin-side
  escrow claim; `MintAsset(holder = AssetHolder.Fiber(escrowId))` is gated so only that escrow fiber
  is a valid mint target.
- **Provenance:** `(originChainId = "eip155:1", originAssetRef = "0xA0b8…eB48",
  fullPath = [ottochain-bridge-hop], attestationHash)`;
  `policyId = derive("eip155:1", "0xA0b8…eB48")` so any later bridge of the same USDC re-resolves to
  *this* policy (no fragmentation).
- **Forgotten:** `decimals = 6` collapses to boolean `S = 1`; the wallet keeps the precision as display
  metadata only, not in `β`.

### 7.2 Importing a Cardano native token (lock-mint, strict `φ`)

Source: a fungible native token `(PolicyID = b1a2…f7 (28-byte blake2b-224), AssetName = "MILK")`,
`quantity > 1`, minted under a Plutus policy with a one-shot + time-lock (effective fixed cap `K`). We
do not hold the Cardano policy key: **lock-mint**.

```
classify(MILK)           = Fungible = 28          // quantity > 1, divisible bundle
F(MILK) = (β = 28, π) where
  π.behavior   = 28
  π.maxSupply  = Some(K)                           // Cardano's one-shot / time-lock cap becomes an explicit maxSupply
  π.mintPolicy = "mint iff valid inbound lock-attestation"      // Otto SPLITS Cardano's fused mint+burn…
  π.burnPolicy = "burn ⇒ emit outbound unescrow"               // …into two policies
  π.morphisms  = { Transfer:          Public,                   // Value-redistribution, structural, no code
                   Compose/Decompose: Public }                  // Value bundle = aggregation monoid (φ STRICT here)
```

- **TokenBehavior:** `28`.
- **AssetPolicy:** `maxSupply = Some(K)`; derived supply from records.
- **φ is strict** on composition for this import (equality), because Cardano's `Value` already carries
  ledger-enforced conservation — bundling MILK with another Cardano-imported asset and `Decompose`-ing
  it round-trips exactly.
- **Custody fiber:** a Cardano-style custody machine playing the role of a Plutus script address; if
  MILK were CIP-68 (updatable metadata), the *fiber-as-asset-holder* with an upgradeable governing
  guard is the direct analogue of the CIP-68 reference-NFT-with-inline-datum + reference-inputs pattern
  — keep the asset identity stable while the governing fiber evolves metadata.
- **Provenance:** `(originChainId = "cardano-mainnet", originAssetRef = "(b1a2…f7, MILK)",
  fullPath, attestationHash)`; `policyId = derive("cardano-mainnet", "b1a2…f7‖MILK")`.
- **Forgotten:** the PolicyID↔script *permanence* (Otto policies are versionable — an enrichment), and
  any CIP-25 label-721 off-chain metadata body (carried as provenance metadata, not `β`).

---

## 8. Required additions to the asset model

This RFC needs the following concrete changes to `docs/proposals/asset-model.md`. Every one must
respect invariant #1 (signed fields `Option`-or-required) and invariant #3 (no asset/policy/origin
*lineage* reads in `validateSignedUpdate`).

1. **`Option[OriginProvenance]` on `AssetRecord` and `AssetPolicy`.**
   `OriginProvenance = (originChainId, originAssetRef, fullPath: List[Hop], attestationHash)`, carried
   as `Option[...] = None`. Native assets omit it; wrapped assets always carry it. **Invariant #1:**
   `Option`, never a defaulted record/map.

2. **Origin discriminator on `AssetCommit` (`OnChain`).** Add an `originRef: Option[Hash]` (or an
   `Option[OriginKey]`) to `AssetCommit` so L1 can **structurally** reject a double-wrap of the same
   origin — an O(1) check on a safe `OnChain` value, no lineage read. (Following the existing
   `OnChain` pattern, this field defaults to `SortedMap.empty` *only* on the `OnChain` container map,
   not on any signed message — the signed `MintAsset`/`Wrap` carries the origin as a required-or-Option
   field, not a defaulted one.)

3. **Canonical-`policyId` uniqueness invariant in `AssetCombiner`.**
   `policyId = derive(originChainId, originAssetRef)`, enforced so the same foreign asset resolves to
   exactly one policy. **Combiner-only** (`CombineRejected` → `RejectionReceipt`); never in
   `validateSignedUpdate` (invariant #3 — it reads existing asset/policy state, a TOCTOU
   block-poisoning hazard there).

4. **The custody-fiber pattern.** Lock-mint custody is a fiber-as-asset-holder state machine,
   `HOLDING → RELEASED`, upgradeable via `UpgradeFiber`. Inbound bridge = `Wrap` →
   `MintAsset(holder = AssetHolder.Fiber(custodyId))`; the cross-chain attestation / verifier-set check
   lives in `mintPolicy` (or a script fiber that validates the proof) as a **combiner-only** guard
   (invariant #3). `Unwrap(Wrap(x)) = x` (the retraction law, §6.1). Enforce `_transferAsset`
   single-pass / non-reentrant per combiner pass (the Aptos dispatchable-hook lesson).

5. **L1 prefix test for source/sink.** The forward-prepend / backward-trim hop rule (§6.3) is an O(1)
   prefix test on `fullPath`, suitable for the L1 structural layer on `AssetCommit` — structural only,
   no `CalculatedState` read (invariant #2/#3).

6. **Signing-canonical coverage.** Add the new origin-carrying signed fields to
   `PublishVersionSigningCanonicalSuite` (every new signed field is `Option[T]` or required-no-default
   — invariant #1).

---

## 9. Open questions

1. **NFT provenance is the lossiest import.** For ERC-721 (and any UTXO-bound NFT), the source chain
   has **no on-chain "this wrapped NFT IS that origin NFT"** — the Otto `OriginProvenance` record is
   the *only* binding, and it is only as trustworthy as the attestation. Is a stronger binding
   (e.g. a proof-of-burn or a light-client inclusion proof committed in `attestationHash`) worth the
   weight, or is the provenance record sufficient given Otto's combiner is the authoritative gate?

2. **The ERC-1155/6909 singleton ⇒ namespace-valued `F` gap.** These contracts have no single object
   image — `F` is `namespace`-valued (a family `{(β_id, π_id)}`). Does Otto add a first-class
   "per-id-policy namespace" concept (a `collectionId`/`groupId` grouping many `AssetPolicy`s under one
   registry name), or continue to model each `id` as an independent policy linked only by a registry
   name? This is the one structural place the external survey is *more* expressive than the current
   model.

3. **Attestation / verifier design for `mintPolicy`.** The cross-chain mint guard reduces to "is this
   inbound lock/burn real?" — a **light-client** inclusion proof (IBC-grade, strongest) vs a **DVN /
   threshold-attestation** verifier set (LayerZero/Wormhole-grade, weaker but cheaper). Which does
   Otto target first, and does the choice live per-policy (so a high-value asset can demand a
   light-client proof while a low-value one accepts a DVN)? The guard stays combiner-only either way
   (invariant #3).

4. **DAG-currency canonical pairing (the Move Coin↔FA lesson).** Move's `paired_metadata` /
   `coin_to_fungible_asset` shows the right pattern for an asset with two faces: **one canonical
   `policyId` + conversion morphisms**, not two unrelated representations. When OttoChain bridges its
   metagraph asset model to the native DAG currency layer, the same discipline applies — a single
   canonical identity plus a governed conversion, never a fresh unrelated wrapper. (Tracks Open Q1 of
   the asset model.)
