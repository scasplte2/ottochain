# Riverdale Health e2e — ZK medical privacy design

Status: implemented (`e2e-test/examples/riverdale-health/`, CI lane `riverdale-health`,
PR-gated by the `e2e-health` label, always on nightly/push/dispatch).

## Why this exists

A flagship, CI-verifiable demonstration that sensitive medical data can be **owned, managed,
and modified** on ottochain using the ZK machinery that is already live — and that the story
goes materially beyond selective disclosure. Every act's guarantee is enforced by an on-chain
verifier opcode evaluated in the combiner (`groth16_verify`, `sigma_verify`, `pmt_verify`);
the e2e asserts both the accept paths and the reject paths (graceful `CombineRejected`, per
the block-validity invariant — no stateful checks at DL1 block acceptance).

CI has no GPU, so all SP1 proofs are **pre-generated** on a local RTX 5090
(`SP1_PROVER=cuda`) and committed as fixtures; CI only verifies (milliseconds). Σ-protocol
fixtures need no GPU at all (`@noble/curves` BN254).

## The real-world workflow being simulated

The US controlled-substance prescription lifecycle:

- **Step therapy / "fail-first" prior authorization** — insurers require ≥N failed
  first-line treatments before covering the prescribed drug. Today: the patient's chart is
  faxed around. Here: a zk proof over the *hidden* chart.
- **EPCS** (Electronic Prescriptions for Controlled Substances, DEA 21 CFR 1311) — the
  prescriber signs with two of three registered authentication factors. Here: a CDS
  THRESHOLD(2-of-3) Σ-proof.
- **Schedule II fill rules** (21 CFR 1306.12) — no refills; one script, one fill. Here: a
  witness-bound ring nullifier consumed exactly once, across independent pharmacies —
  enforced by the PROTOCOL nullifier set (`_consumeNullifier` → `NullifierCombiner`,
  `docs/proposals/protocol-nullifier-set.md`), not an app-authored spent map.
- **PDMPs** (Prescription Drug Monitoring Programs) — every US state runs a centralized
  plaintext database of controlled-substance histories, with documented criticism
  (warrantless law-enforcement access, breach history, chilling effects on legitimate care).
  Here: the chain is the shared dedup bulletin board; the dispensing log links **no patient
  identity** to a fill — the cohort ring proof hides *which* prescription was filled.

## Acts → primitives → templates

| Act | Guarantee | Primitive | Pattern source |
|---|---|---|---|
| 1. The chart | private state, privately updated; replay denied | `zk-jlvm-shielded` (M5) + `groth16_verify` + protocol nullifier set (`_consumeNullifier`) + `pmt_verify` root advance | `shieldApp` (sdk PR #212), corrected idioms |
| 2. Prior auth | predicate over hidden HISTORY is true | `zk-jlvm` + exprHash/outputHash binding | `zk-eligibility` lane / `SemiPrivateGuard` |
| 3. EPCS co-sign | 2-of-3 factors co-signed THIS proof for THIS rx | `sigma_verify` CDS THRESHOLD + message binding | first live THRESHOLD e2e (spec: `metakit/docs/sigma-verify.md`) |
| 4. One fill | one-time action, actor-cohort hidden | `sigma_verify` CDS OR-of-dhtuple + protocol nullifier set (x-only consumption) | `sigma-mixer` lane |
| 5. Audit | record provable vs consensus-signed root; spend provable by single key | `mpt_verify` client-side (`assertStateProof` + `assertNullifier` runner actions) | committed-state PR #164 + protocol-nullifier-set Phase A |

Three fibers: `record` (patient-owned shielded pool), `rx` (prescription; composite guard =
`and[groth16_verify…, sigma_verify THRESHOLD…]`), `pdmp` (shared dispensing log). One
sequential flow (the sigma-mixer lesson: flows fan out in parallel on fresh fibers, so a
stateful storyline must be a single flow), five wallets
(`dr-adams,patient-blake,pharmacy-corner,pharmacy-mainst,auditor-dea`).

### Corrected shielded-pool idioms

`ottochain-sdk/src/privacy/shield-app.ts` carries two documented latent on-chain bugs
(`ergo-patterns-as-fiber-primitives.md` §hardening): `cat` cannot append arrays on-chain, and
bare `substr` yields un-prefixed hex that never equals a stored `0x` value. The
`record.definition.json` guard/effect use the proven mixer idioms instead: `merge`-append for
arrays and `cat("0x", substr(…))` re-prefixing for every public-values word. Additionally —
an improvement over shield-app — root advancement is not blind: the guard runs
`pmt_verify(event.newRoot, newCommitment, state.leafCount, event.newSiblings)`, so the
client-supplied next anchor must at least contain the new commitment at the next leaf index.

The original lane also used the mixer's third idiom — a `stateData` **map** + `has`/`set` as
the nullifier spent-set. That idiom is RETIRED here: both definitions now emit the
`_consumeNullifier` effect token and the chain's `NullifierCombiner` enforces uniqueness
(graceful `CombineRejected("nullifier already consumed …")` → `RejectionReceipt`), keyed under
the consuming fiber's own id (`nullifier/<domain>/<nf>`). Consequences worth naming:

- **Records are constant-size** — no per-spend `stateData` growth, so state proofs over the
  record stay constant-size too.
- The record definition consumes the M5 public-values nullifier word
  (`cat("0x", substr(publicValues, 66, 64))`); the dispense definition consumes the ring
  nullifier's **x-coordinate** (`substr(event.nullifier, 2, 64)` of the 128-hex G1 point) —
  x-only consumption also kills ±Nf malleability (a negated point shares its x → collides →
  rejected).
- The double-fill reject is now the PROTOCOL reason: the guard (sigma + message binding)
  passes for the replayed bundle, then `NullifierCombiner` rejects the second consumption. The
  visit-2 REPLAY, by contrast, still dies at the guard — `pmt_verify` fails against the
  advanced `leafCount` — with the protocol set as the backstop for any guard-passing reuse.
- The defs set no `allowedEffects` policy dial, and the dial's absence means ALL effect
  families are permitted (`FiberPolicy.Constrained.allowedEffects = None` ⇒ legacy allow-all),
  so `EffectKind.Nullifier` needs no explicit grant.
- **No fixture regeneration**: the M5 `exprHash` binds the IN-GUEST effect, not the on-chain
  definition, and event payloads are unchanged.

## Fixture pipeline

Generator: `ottochain-sdk/scripts/gen-health-fixtures.ts` (`prove` | `assemble` | `all`).

1. **Self-test** — before anything, the TS Poseidon-Merkle tree, note commitment
   (`Poseidon([keccakHi, keccakLo, owner, rho])`), nullifier (`Poseidon([rho, nsk])`) and
   JCS-canonical keccak are byte-checked against the committed metakit-sdk M5 fixture, and the
   `groth16_verify`/`pmt_verify` oracles run against it.
2. **Witnesses** — the private chart sequence `s0 → s1 → s2` is derived by running the pinned
   medical effect through the parity evaluator; wire witnesses (anchor, Merkle path, nsk, rho)
   are computed in TS (depth-8 tree, positions 0..2, roots r0..r2).
3. **GPU proofs** (local only) — 2× `zk-jlvm-shielded --witness` bundles (visit-1/2) and 2×
   `zk-jlvm --expr/--data` bundles (prior-auth over canonical(s2)=true and canonical(s1)=false).
   Canonical-input invariant: `--expr`/`--data` are the JCS strings, so `exprHash`/`dataHash`
   equal the SDK's `exprHash()`/`dataHash()`. Proven public values are asserted against the TS
   predictions word-for-word. (sp1-cuda 6.2.x can panic in a destructor during process teardown
   AFTER saving the proof — the wrapper gates on the artifact verifying, not the exit code.
   Gotcha fixed en route: `zk-jlvm/script` was missing the `cuda` feature on sp1-sdk.)
4. **Σ-proofs** (no GPU) — THRESHOLD(2-of-3 dlog) co-signs (the ports of metakit's
   `SigmaVectorGen` prover: GF(2^8) byte-lane Shamir over 31-byte challenges) bound to
   `(publicValues ‖ rxId)`, and the OR-of-dhtuple fill ring (mixer port; NUMS `H` from a
   health-domain hash-to-curve; `Nf = x_rx·H`).
5. **Oracle gate + dress rehearsal** — every honest artifact must verify through the real
   opcodes; every reject artifact must fail; then the EXACT emitted guards/effects are
   evaluated end-to-end (accept, replay, under-documented, tampered co-sign, double-fill)
   through the parity evaluator with on-chain context shapes. Only then are files written.
6. **Provenance** — `fixture-manifest.json` pins the metakit-sdk commit (VKEY provenance: any
   `jlvm-core` change rotates the M5 guest ELF → vkey → regenerate), vkeys, rule text, roots,
   nullifiers. Determinism: all secrets derive from sha256 domain strings; only the proof
   bytes themselves are non-deterministic (re-verification is the check).

## Honesty ledger (what is NOT cryptographically enforced)

Kept in the example `README.md` guarantees table; summary: (a) proof↔record binding across
acts 1→2 is by convention (`dataHash` of the proven history is pinned on-chain, but no
circuit links it to the pool's commitments — a read-proof variant is the roadmap item);
(b) root advancement pins only new-commitment membership (production: `poseidon_merkle_append`
opcode or bridge attestation); (c) ~~no protocol nullifier set~~ RESOLVED — the lane consumes
the protocol set (privacy-handoff P0.1 flipped); the residual gap is light-client ABSENCE
proofs ("unspent" is a trusted 404 until protocol-nullifier-set.md Phase B / metakit rc.8);
(d) time arithmetic lives inside proofs over private timestamps,
not chain-attested; (e) `groth16_verify`/`sigma_verify` are unaudited — demo-grade.

## Stakeholder name-drop map

- **Midnight (Cardano)** — its private-state model IS Kachina (public σ / private ρ, "prove,
  hide, compute, reveal"); act 1 is the same class of system, with the differentiator that
  ottochain proves the *interpreter itself* in a zkVM, so the same JSON-Logic runs in clear or
  shielded. Midnight has no shipped first-party medical demo.
- **Semaphore / Polygon ID / Worldcoin** — nullifier-based one-time actions and
  proof-of-uniqueness (act 4).
- **Aleo zPass** — signed credential → local proof (the act-2 shape).
- **Galactica** — threshold-gated compliance unlock (the act-3/act-5 shape).
- **Nova / Halo accumulation** — proofs over accumulated history (the act-1→2 composition,
  demo-grade here, folding-scheme grade later).

## Roadmap items this demo motivates

1. ~~Protocol nullifier set (P0.1)~~ **SHIPPED** (`docs/proposals/protocol-nullifier-set.md`;
   this lane consumes it). Remaining slice: Phase B MPT absence proofs (metakit rc.8) so
   "unspent" becomes light-client-verifiable — then `assertNullifier expectSpent:false`
   upgrades from a trusted 404 to a verified absence proof.
2. `poseidon_merkle_append` opcode (trustless root advancement).
3. Read-proofs over committed notes (cryptographic act-1→act-2 binding).
4. External audit of `groth16_verify` + the sigma family.
5. The M4 `zk-shielded` value-transfer pool as an app (confidential payment for the fill).
