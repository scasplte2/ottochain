# Riverdale Health — ZK medical privacy e2e

A controlled-substance prescription lifecycle in the Riverdale universe, built so that **every
claimed guarantee is enforced on-chain by a verifier opcode** — nothing is narrated. Sensitive
medical data is owned, managed, and modified without ever appearing on-chain.

## Cast

| Wallet | Role |
|---|---|
| `patient-blake` | owns the shielded medical record |
| `dr-adams` | prescriber (EPCS-style 2-of-3 factor co-sign) |
| `pharmacy-corner` | fills the prescription |
| `pharmacy-mainst` | attempts the second fill (denied) |
| `auditor-dea` | operates the shared dispensing log; audits via light-client proofs |

## The five acts

1. **The chart (shielded history).** `patient-blake`'s record is a shielded pool
   (`record.definition.json`). Two visits — failed gabapentin, failed duloxetine — update the
   PRIVATE state via `zk-jlvm-shielded` (M5) Groth16 proofs: the JLVM update effect runs
   **inside the SP1 guest**; on-chain travel only `anchor | nullifier | newCommitment | exprHash`.
   Replaying a spent note is denied by the nullifier map. This is Kachina-class private
   contract state (the model Midnight is built on), verified by the live `groth16_verify` opcode.
2. **Prior auth, fail-first.** Authorization requires an SP1 `zk-jlvm` proof that the HIDDEN
   history satisfies the pinned rule `failedFirstLine >= 2 AND activeOpioidCount == 0`
   (step-therapy / "fail-first"). The guard binds `exprHash == logicHash` (the intended rule
   ran) and `outputHash == keccak256(true)` (it returned true). A chart with only one
   documented failure is rejected — `groth16_verify` passes but the output binding fails.
3. **The co-sign (EPCS).** The same guard demands a CDS **THRESHOLD(2-of-3)** Σ-proof
   (`sigma_verify`) over the prescriber's registered factor keys — the DEA EPCS two-factor
   pattern (21 CFR 1311) — message-bound to `(publicValues ‖ rxId)`, so the co-sign attests to
   exactly this prior-auth proof for exactly this prescription. A tampered co-sign is rejected.
4. **One script, one fill.** Schedule II allows NO refills (21 CFR 1306.12). The fill on the
   shared dispensing log is a CDS OR-of-dhtuple **ring proof**: it proves knowledge of ONE
   authorized prescription secret in the cohort — *which one stays hidden* — and binds the
   nullifier `Nf = x_rx·H` to that hidden witness. `pharmacy-mainst` presenting the same fill
   is denied by `has(spentNullifiers, Nf)`. The chain replaces the central PDMP database: fills
   are globally deduplicated with no patient-identity linkage on the log.
5. **Audit without dragnet.** `assertStateProof` fetches `/state-machines/{id}/state-proof`
   and verifies the Merkle-Patricia inclusion proof **client-side with the real `mpt_verify`
   opcode** against `mptRoot`, whose combined hash IS the snapshot's consensus-signed
   `calculatedStateProof` — a specific record is proven, nothing else is exposed.

## Guarantees table — proven vs simulated

| Claim | Enforced by | Status |
|---|---|---|
| Chart updates ran the pinned medical logic on the committed note | `groth16_verify` (M5) + `exprHash` binding | **proven on-chain** |
| Spent chart notes cannot be replayed | nullifier map (`has`/`set`) in the guard | **proven on-chain** |
| The client-advanced tree root contains the new commitment at the next leaf | `pmt_verify` in the guard | **proven on-chain** (see caveat 3) |
| The hidden history satisfies the fail-first rule | `groth16_verify` (zk-jlvm) + `exprHash`/`outputHash` binding | **proven on-chain** |
| 2 of 3 prescriber factors co-signed exactly this proof + rx | `sigma_verify` CDS THRESHOLD + message binding | **proven on-chain** |
| One fill per prescription, across all pharmacies | witness-bound ring nullifier + spent map | **proven on-chain** |
| Which prescription/patient a fill belongs to stays hidden | CDS OR simulation (ring of 4) | **proven on-chain** (anonymity set = 4, public) |
| Audit reads are verifiable without trusting the node | `mpt_verify` vs consensus-signed root | **proven client-side** |
| The proven history IS the history committed in the record pool | same private JSON by convention; `priorAuthDataHash` pinned on-chain | **narrative binding** (cryptographic link = roadmap: read-proofs over committed notes) |
| Proof freshness / who may present a proof | signed-update signers + one-shot FSM transitions | **partially** — a Groth16 bundle is replayable in general; here replay is bounded by the one-shot transition and nullifier maps |
| "30-day window" style time arithmetic | inside the proof over private timestamps | **not chain-attested** (the chain does not verify wall-clock time) |

Known platform caveats: (1) `groth16_verify` / `sigma_verify` are **not externally audited**
(`SemiPrivateGuard.scala` note) — demo-grade, not for protecting real value yet; (2) there is
no protocol-level nullifier SET (privacy-handoff P0.1) — fiber `stateData` maps are the
stand-in, as in `sigma-mixer`; (3) root advancement is client-supplied and only
membership-checked (`pmt_verify` proves the new root contains the new commitment at the next
leaf index, not that other leaves were preserved) — `poseidon_merkle_append` or bridge
attestation is the production path.

## Fixtures

All artifacts are generated by `ottochain-sdk/scripts/gen-health-fixtures.ts`
(`prove` = 4 real SP1-Groth16 proofs on GPU via `SP1_PROVER=cuda`; `assemble` = Σ-proofs +
oracle verification + file emission). **The verifier is the oracle**: every honest artifact
must verify through the real opcodes (and every reject artifact must fail) before anything is
written; the emitted guards themselves are dress-rehearsed against the parity evaluator.
`fixture-manifest.json` records provenance (metakit-sdk commit, vkeys, rule text, roots,
nullifiers). The M5 wire witnesses (the actual private chart) are committed under `fixtures/`
for reproducibility — in production these never leave the client.

CI never proves: it only verifies the committed bundles (Groth16 verification is
milliseconds; proving is GPU-minutes).
