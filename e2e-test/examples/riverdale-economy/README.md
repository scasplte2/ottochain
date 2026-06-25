# Riverdale Economy — full 6-party economy e2e

The richest cross-fiber + asset-custody example: a worked **macro-economy** that exercises cross-fiber
`_triggers`, real `_transferAsset` custody between fibers, two versioned package upgrades, a broadcast tax
sweep, a spawned child auction, and wallet-context asset morphisms — plus a negative-test flow for graceful
rejections. It extends the original 3-fiber "supply-chain + upgrade" de-risk slice (now FLOW 1's P0–P7
backbone) into the whole economy.

## Parties & assets

CI lane: `--wallets alice,bob,carol,dave,erin,frank` (a step's `signers` ARE its owners/authorizers).

| Wallet | Role         | Fiber / package                                  |
| ------ | ------------ | ------------------------------------------------ |
| alice  | Manufacturer | `manufacturer`, mints the assets                 |
| bob    | Retailer     | `retailer` (verified-bound `retailer.machine` v1→v2) |
| carol  | Consumer     | `consumer` (+ spawns the child auction)          |
| dave   | Bank         | `bank`, holds + lends RVD                         |
| erin   | Fed          | `fed` (verified-bound `fed.machine` v1→v2)        |
| frank  | Governance   | `gov`, runs the tax sweep                         |

| Asset         | Behavior      | Meaning                                              |
| ------------- | ------------- | --------------------------------------------------- |
| `goods.asset` | `20` = T\|C   | combinable + transferable inventory                 |
| `rvd.asset`   | `28` = T\|S\|C = Fungible | the fungible currency (Transfer/Fractionalize/Burn/Stake morphisms) |

Fixed, deterministic ids live in [`ids.ts`](./ids.ts) — **never** `crypto.randomUUID()`, so custody/spawn
assertions observe the SAME instance across mint → transfer → assert.

## Asset choreography — the R1 constraint (verified in `AssetCombiner.scala`)

A FIBER-held asset's raw `ApplyMorphism` is **rejected** (`requireWalletHolder`). Fiber-custody value moves
**only** via the `_transferAsset` fiber-effect return channel — a WHOLE instance, in a transition effect,
where the emitting fiber must HOLD it (R1 holder defense in `applyFiberTransfer`). So each payment leg is a
**separate pre-minted RVD instance**, minted DIRECTLY into the fiber that spends it:

| Instance     | Minted into     | Moved by (`_transferAsset`)        |
| ------------ | --------------- | ---------------------------------- |
| `RVD_LOAN`   | bank fiber      | `bank.underwrite` → consumer       |
| `RVD_PAY`    | consumer fiber  | `consumer.buy` → retailer          |
| `RVD_REPAY`  | consumer fiber  | `consumer.make_payment` → bank     |
| `RVD_TAX`    | consumer fiber  | `consumer.pay_taxes` → gov         |
| `GOODS`      | manufacturer    | `fulfill_order` → retailer; then `process_sale` → consumer |

Minting INTO a Fiber holder is allowed (`AssetCombiner.mintAsset`). Wallet-context morphisms (FLOW 1 P12)
are SEPARATE and run where R1 requires `signer == holder`.

## FLOW 1 — the economy (concurrency 1)

P0 genesis (asset policies + `retailer.machine` v1/v2 + `fed.machine` v1/v2) → P1 create the 6 fibers → P2
mint GOODS + the four RVD legs → **P3 supply** (manufacturer→retailer) → **P4 monetary** (fed→bank rate) →
**P5 lending** (bank→consumer, RVD_LOAN custody) → **P6 commerce** (consumer↔retailer: RVD_PAY out, GOODS in,
one tx) → **P7 retailer upgrade** v1→v2 + v2-only `redeem_loyalty` → **P8 servicing** (consumer→bank,
RVD_REPAY) → **P9 fed upgrade** v1→v2 + v2-only `emergency_lending` → **P10 tax sweep** (gov broadcasts
`pay_taxes` to manufacturer/retailer/consumer; consumer remits RVD_TAX to gov) → **P11 auction** (consumer
spawns a child auction at `AUCTION_CHILD_ID`; bob bids + accepts; `sale_completed` loops back to the
consumer) → **P12 wallet morphism** (mint RVD into dave's wallet, then `STAKE` it).

### Wallet-morphism coverage + the runner limitation (read this)

The runner confirms an `applyMorphism` step by polling the **source** asset's `state-proof` for a
**sequence ADVANCE** (`runner.ts` ~L699-713). That only works for **non-consuming** morphisms:

- **STAKE** (live, P12): codomain `E:=1`, bumps the seq, the record survives → confirmable. ✅
- **FRACTIONALIZE / BURN** (deferred): CONSUMING/terminal — they REMOVE the source record, so the
  seq-advance predicate can never be satisfied and the step would time out. Their body files
  (`fractionalize-rvd.ts`, `burn-rvd.ts`) + ids are shipped and the `rvd` policy permits them
  (`morphisms` + `burnPolicy`); the deferred steps are written out (commented) at the end of P12. They
  activate the instant the runner gains consuming-morphism confirmation (e.g. confirm Fractionalize via
  shard existence, Burn via source absence). Absence is also not assertable via `assertAsset`.

`STAKE` is clean to enable: `applyStake` adds no behavior-bit requirement (`structuralOk` has no Stake
gate); only the policy `morphisms` map must declare it (it does).

## FLOW 2 — negative tests (graceful ML0 rejections)

Disjoint fibers/policies/packages so it never collides with FLOW 1. Each is admitted by DL1 (structurally
valid) then DENIED at ML0 combine, leaving state unchanged:

- **wrong-party** — alice owns the fiber; a transition signed by bob → `NotSignedByAuthorizedParty`.
- **replay / seq-regression** — re-submit a one-way transition after the fiber moved on → `NoTransitionForEvent`.
- **mint-over-cap** — a capped policy (`maxSupply 100`); the 2nd mint pushes derived supply over the cap.
- **non-monotonic publish** — publish a HIGHER version then a LOWER one (the lower is not in the lineage, so
  "did not land" is observable — re-publishing the same version is indistinguishable from idempotence).

## On-wire shapes (verified against the chain sources)

- `_transferAsset` recipient is a BARE STRING; UUID-shaped → `AssetHolder.Fiber`, DAG address →
  `AssetHolder.Wallet` (`EffectExtractor.parseRecipient`). We pass fiberIds so legs land in Fiber custody.
- Cross-fiber triggers need NO declared dependency; the gate is `FiberPolicy.acceptedCallers` (UNSET =
  Unconstrained here). The retailer/consumer/etc. guards are `{"==":[1,1]}`.
- Spawned child `owners = event.auctionOwners` (`SpawnProcessor`); a child's transitions are gated by
  `owners ∪ authorizedSigners` (`FiberRules.updateSignedByOwnerOrParticipant`), so the **bidder bob is in
  `auctionOwners`** — otherwise his `place_bid`/`accept_bid` are rejected at ML0.
- `MintAsset` holder wire forms: `{"Fiber":{"fiberId":…}}` / `{"Wallet":{"address":…}}`; `policyRef` is a
  `SchemaRef` with `VersionReq.Exact`. `ApplyMorphism` body: `{assetId, kind, …}` (`FRACTIONALIZE` adds
  `shardIds`; the runner fills `targetSequenceNumber`).
- Verified binding: the retailer/fed are created with `schemaRef …@1.0.0`, so the SAME definition file is
  used for both publish + create (and again for v2 publish + upgrade) — the `pay_taxes` transition added to
  the retailer definitions is therefore mirrored in their published versions, keeping `logicHash` aligned.

## Signed-message discipline (CLAUDE.md #1)

Optional message fields are **omitted**, never `null` — the `.ts` mint/event/morphism files return only
present fields, so the client-signed and chain-re-derived canonicals match.
