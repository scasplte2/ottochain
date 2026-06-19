# zk-eligibility — semi-private guarded transition (live e2e)

A loan fiber whose `pending → eligible` transition is gated by a **semi-private guard**. The borrower
proves OFF-CHAIN (SP1 zk-jlvm) that their PRIVATE data satisfies a PUBLIC, pinned rule and carries only
`{publicValues, proof}` on the `prove` event. The on-chain guard re-verifies the proof and binds it —
the value stays hidden, the predicate stays legible. This is the first **live-metagraph** exercise of
the zk stack (the chain unit suite `SemiPrivateGuardedTransferSuite` covers it at the combiner level).

The transition guard (in `definition.json`) uses only opcodes metakit already gas-meters — no
`jlvm_pv_decode`:

1. `groth16_verify[<vkey>, event.publicValues, event.proof]` — the SP1 proof is valid.
2. `exprHash` word (`cat("0x", substr(event.publicValues, 2, 64))`) `== logicHash` — the proof ran THE pinned rule.
3. `outputHash` word (offset 130) `== keccak256("true")` — that rule evaluated true.

`event-prove.ts` carries a **real** SP1-Groth16 bundle (from `rust/zk-jlvm --mode groth16` on the GPU,
for `{">=":[{"var":"score"},700]}` / `{"score":740}` → true). The `vkey` / `logicHash` / `keccakTrue`
literals in the guard are the off-chain keccak values for that rule — byte-aligned with `@ottochain/sdk/zk`.

## Run

```bash
# via the e2e runner (stands up / targets a metagraph)
npx tsx runner.ts --target local            # picks up this flow with the others

# or manually, step by step
node terminal.js sm create --definition examples/zk-eligibility/definition.json --initialData examples/zk-eligibility/initial-data.json
node terminal.js sm process-event --address <CID> --event examples/zk-eligibility/event-prove.ts --expectedState eligible
```

## The reject path (`event-prove-bad.ts`)

`event-prove-bad.ts` is the same bundle with a flipped proof nibble — `groth16_verify` fails → ML0
`CombineRejected` → a `RejectionReceipt` is logged and the fiber stays `pending`. It is **not** an
automated `testFlow` step: a rejection leaves the fiber UNMUTATED (`Combiner.scala` appends a receipt
to the snapshot, not the fiber), but the `processEvent` validator requires the sequence number to
advance — so it can only be checked manually:

```bash
node terminal.js sm process-event --address <CID> --event examples/zk-eligibility/event-prove-bad.ts
# observe: state still "pending", a RejectionReceipt in the snapshot logs
```

Rejection (garbage proof, wrong `exprHash`, wrong `outputHash`, absent witness, and a valid proof of a
FALSE evaluation) is exhaustively asserted at the combiner level in the chain's
`SemiPrivateGuardedTransferSuite`.

> **Audit:** metakit's `groth16_verify` is unaudited — a semi-private guard must not protect real value until it is.
