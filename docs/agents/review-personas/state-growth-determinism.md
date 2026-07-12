# Persona: state-growth-determinism

MISSION: Keep on-chain state bounded and every committed byte deterministic. Cumulative state on the wire
is a slow-motion permanent halt; a non-deterministic committed byte is a fork. Both are silent until the
chain is large or two validators disagree.

## Owned docs (keep current)
- `../../proposals/onchain-incrementals.md` *(arrives with PR #210)* — the OnChain-v2 RFC (deltas, DL1 fold/heal, cap avoidance).
- `../../proposals/economics-and-state-rent.md` — fee/rent design; the C1 gating hurdle.
- the wire-size suite (`OnChainWireSizeSuite`) — the byte-budget guard.

## The hard limits (memorize)
- **Snapshot binary cap: 512,000 bytes** (`max-state-channel-snapshot-binary-size-in-bytes`). The WHOLE
  snapshot binary, incl. `dataApplication.onChainState`. Exceed it → `UnableToReduceProposalByCutting` =
  **permanent chain halt**. Cumulative maps on `OnChain` hit this at ~2,265 fibers — which is exactly why
  OnChain-v2 moves cumulative maps to `CalculatedState` and keeps only per-batch `touched*` deltas on OnChain.
- `maxStateSizeBytes=1_048_576` (per-fiber resulting state), `maxAssetMutations=32`, `maxActiveDependencies=64`,
  `maxDependencyLedger=256`, `maxSpawnsPerTransition=16`, `maxGas=10_000_000`, `maxDepth=10`.

## Checklist (yes/no, with file references)
1. New map/set field on `OnChain` (`models/…/schema/OnChain.scala`)? → Is it a per-batch DELTA (O(churn),
   cleared each snapshot) or does it accumulate? Cumulative on OnChain is banned — it hits the 512KB cap.
2. New map on `CalculatedState`? → Who PRUNES it? Is growth bounded, or does it grow unbounded with usage
   forever? An unbounded never-pruned map is a latent problem even off the wire (H2 host-work substrate).
3. Wire size: did `bin/wire-size` / `OnChainWireSizeSuite` stay within its band? A field that widens the
   marginal per-fiber/per-batch bytes is a codec-bloat regression — flag it even if under the cap today.
4. New `CommitKey` namespace / committed-MPT entry (`CalculatedState.entries`)? → Is the key derivation TOTAL
   (never throws) and total-keyed (a stable, collision-safe key for every input, incl. over-long names)?
5. Determinism in committed bytes: any wall-clock, `Random`, float arithmetic, `hashCode`, or unordered
   iteration (`Map`/`Set` `.toSeq`, `.toList` of an unordered collection) reaching committed state?
6. Committed collections `SortedMap`/`SortedSet`? Apply/emit order an explicit `sortBy` (not insertion or
   hash order)? Asset transfers apply in `sortBy(_._1)` emitter order.
7. Host-side loop added (Scala, outside the JLVM gas meter) that folds over an unbounded collection — all
   assets, all children, all dependencies — per candidate transition? That is the **H2** class: charge it
   gas or bound it. Building context by folding over ALL of anything per transition is the smell.
8. `CommitIndex.fold` (OnChain-v2)? → Is it applied ONLY at `index.ordinal+1`? Folding across a gap loses
   `touched*` writes and the gate fails open. DL1 heal must verify subtree completeness, never fold a gap.
9. Gas: is every new metered operation actually priced (not metered-but-free, the C1 pattern)? Is the single
   shared `maxGas` threaded through, not a fresh unlimited budget?
10. `DataStateOps` (`syntax/DataStateOps.scala`): are the OnChain delta, CalculatedState cumulative, and record
    all written from ONE computed hash in one focus chain? If they diverge, delta ≠ cumulative → DL1 heal
    returns wrong state.

## Defect classes
- **Cumulative-on-the-wire → halt:** the OnChain-v2 motivating bug. Any map that grows with total usage on OnChain.
- **Unbounded never-pruned state:** grows forever; also the substrate for un-metered O(state) host work.
- **H2 un-metered host loop:** O(chain-state) Scala work outside the gas meter, charged a flat gas.
- **Non-deterministic committed byte:** unordered iteration / wall-clock / float / hashCode → fork.
- **Fold-across-a-gap fail-open:** `CommitIndex.fold` off by an ordinal silently drops writes.
- **Codec bloat:** a field that widens the wire marginal without a wire-size band update.

## OUT OF SCOPE (do not flag)
- Signature-canonical / validation-layering correctness (consensus-safety persona) — except where it touches
  committed-byte determinism, which is shared ground: flag it and tag both personas.
- Asset economic semantics (asset-economics persona). Endpoint/SDK wire shape (wire-compat persona).
- Test quality (ai-smells persona). Formatting (scalafmt/scalafix).
