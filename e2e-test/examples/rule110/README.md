# Rule-110 cellular automaton — Turing-complete substrate

The first **Turing-complete bare-computation substrate** on OttoChain: the Rule-110
elementary cellular automaton evolved **in place** by a single fiber, where one
`step` event = one CA generation. The chain of `RUNNING → RUNNING` transitions *is*
the Turing-machine tape-head.

Rule 110: `next[i] = 1` iff `p ∈ {1,2,3,5,6}`, where `p = 4·left + 2·center + right`.

## What it demonstrates

- **The index-array idiom.** JLVM's `map` exposes no element index, and there is no
  `range`/`iota` opcode. So a monotone index array `idx = [0..N-1]` is **stored in
  state** and `map`ped over; each element is the cell index `i`. Neighbour access is
  a *computed `var` path* with a null-default boundary:

  ```jsonc
  nb(off) = { "var": [ { "cat": ["state.tape.", off] }, 0 ] }
  ```

  An out-of-bounds index (`-1` or `N`) resolves to the default `0` — the **fixed-0
  boundary** — so `tape` and `idx` stay exactly length `N` forever.

- **The inlined-cell constraint (no `let` over the map element).** Inside `map` a
  primitive element overlays the eval context, so `{"var":""}` coerces to the cell
  int *only in arithmetic position*. Opening a `let` scope makes `{"var":""}` resolve
  to the whole context object and any `cat` on it throws `JsonLogicRuntimeError`. The
  per-cell predicate `p` is therefore **fully inlined** at each of the five `==`
  sites. No bitwise, no spawn, no zip.

## The `step` transition (in `definition.json`)

```jsonc
{ "from": "RUNNING", "to": "RUNNING", "eventName": "step",
  "guard":  { "or": [ {"missing":["state.maxGen"]},
                      {"<":[{"var":"state.gen"},{"var":"state.maxGen"}]} ] },
  "effect": { "merge": [ {"var":"state"},
               { "tape": { "map": [ {"var":"state.idx"}, <inlined Rule-110 cell> ] },
                 "gen":  { "+": [ {"var":"state.gen"}, 1 ] } } ] } }
```

The effect result *is* the new `stateData`: `merge[state, {…overrides}]` preserves
`idx`/`maxGen` and overwrites `tape`/`gen`. `idx` is byte-identical across every
generation (it is never written).

## Trust model

**Public, permissionless, pure compute** — no assets, no parties, no owner. Anyone
may submit `step`. The DoS / griefing surface is bounded only by **per-event gas/fee**
(~987 gas per cell, linear) and the optional **`maxGen` halt bound**. With `maxGen`
set the fiber is finite-work; **without it the fiber runs until fees stop** —
operators must understand this. An owner-gated variant (`event.agent == state.owner`,
using `event.*`, never `witness.*`) is an opt-in, not the default.

## The flows

The seed is a single `1` at index 7 in a width-15 tape, `maxGen = 8`.

1. **Rule-110 fractal — 8 generations, tape asserted.** Each `step` asserts the
   **exact** `stateData` (`tape`, `idx`, `gen`, `maxGen`) via `expectedStateData`
   deep-equal — not just the state id. A miscomputation fails the test. The canonical
   left-growing Rule-110 fractal:

   ```
   gen0: 000000010000000   (seed)
   gen1: 000000110000000
   gen2: 000001110000000
   gen3: 000011010000000
   gen4: 000111110000000
   gen5: 001100010000000
   gen6: 011100110000000
   gen7: 110101110000000
   gen8: 111111010000000
   ```

   These tapes were computed off-chain by applying Rule 110 directly to the seed and
   cross-checked byte-for-byte against the real JLVM
   (`@constellation-network/metagraph-sdk-jlvm@1.8.0-rc.5`) running the shipped
   `step` effect.

2. **Halt is gated until exhaustion.** `halt` at `gen 0` (`< maxGen 8`) is rejected by
   the `gen >= maxGen` guard (combine-denied at ml0).

3. **Halt after exhaustion.** After 8 steps `gen == maxGen`; `halt` now succeeds →
   `HALTED` (terminal).

## Harness note

This example relies on the `expectedStateData` step field (deep structural equality
of a fiber's `stateData` after a `processEvent` step), added to the runner in
`runner.ts` (`TestStep` + the `processEvent` dispatch) and
`lib/state-machine/processEvent.ts` (`ProcessEventOptions` + a recursive,
key-order-independent `deepEqual` in the validator).
