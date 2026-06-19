# Metakit Sigma Fiat-Shamir Audit Artifact

Date: 2026-06-17

Scope:

- `Constellation-Labs/metakit` PR 51, refreshed head `3fd242c2b2aad221df3e4fc8c3daa2507328f5ba`, merged by `6fb26d7d5f6151c9f460f1e5c9829269b76159d4`.
- `Constellation-Labs/metakit-sdk` PR 49, refreshed head `a1de0d3db74822236d83358cbdc717b5bed24961`, merged by `5153b8cc65a51d31badb78a6885e78e01a28a405`.
- Primary audited surface: `prove_dlog_verify`, `prove_dhtuple_verify`, `sigma_verify`, fixed-width crypto encodings, shared conformance vectors, Rust and TypeScript ports.

Review workflow:

- Main reviewer inspected Scala reference implementation, Rust and TypeScript ports, tests, vector metadata, and RFC documentation.
- Three subagents were launched for independent adversarial review: Scala cryptoanalysis, SDK parity review, and operational/DoS/documentation review.
- Tests were not rerun during this artifact pass because metakit and metakit-sdk are outside the writable workspace; this artifact is a static code and test-harness audit, not a fresh CI report.

## Verdict

I stand by a bounded code-level audit of the Fiat-Shamir and CDS implementation in these refreshed PRs.

No blocking Fiat-Shamir soundness defect was identified in the reviewed implementation. The refreshed code contains the controls this audit requires for relying on the verifier as an implementation of the intended Sigma/CDS protocol:

- Strong Fiat-Shamir transcript binding for DLog, DHTuple, and recursive tree proofs.
- 31-byte Sigma challenges with direct byte-to-scalar use and no mod-R alias.
- Canonical response scalars `z < R`, removing proof-byte malleability from `z` vs `z + R`.
- Commitment reconstruction in `sigma_verify`; commitments are not trusted from proof input.
- Identity-point rejection for DLog public keys and DHTuple statement points.
- Explicit CDS challenge-split checks for AND, OR, and threshold.
- Fixed canonical serialization with tags, child counts, threshold `k`, 64-byte G1 points, and a domain separator.

This is not a formal proof of the construction and not an external cryptographic audit. The conclusion assumes standard Fiat-Shamir random-oracle reasoning for SHA-256, the standard BN254 G1 prime-order/cofactor-1 model used by the implementation, and correct integration-side message binding. External review is still recommended before high-value production deployment, but I do not see a reason to block the updated code on the hand-rolled Fiat-Shamir operations as implemented.

Separate from Fiat-Shamir soundness, the subagent review found production-hardening issues around unbounded proposition traversal, message length, and TypeScript shape handling. Those should block calling the opcode production DoS-hardened for adversarial/high-value execution until remediated.

## Security Claims And Evidence

### 1. DLog leaf binds commitment, public key, and message

Claim: `prove_dlog_verify` is an alias for the existing Schnorr/DLog verifier, with challenge `SHA256(R || pk || msg) mod R` and acceptance equation `s*G == R + c*pk`.

Evidence:

- Scala states the DLog/Schnorr transcript and equation in `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:347-354`.
- Scala parses fixed-width `pk`, `msg`, and 96-byte proof, enforces canonical response scalar, checks curve membership, binds canonical `pk` bytes, rejects identity `pk`, hashes `R || pk || msg`, and checks the equation at `CryptoOps.scala:369-409`.
- `prove_dlog_verify` is an alias over `schnorrVerify` with only error-label replacement at `CryptoOps.scala:519-520`.
- Rust mirrors this behavior at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:113-174` and aliases `prove_dlog_verify` at `crypto.rs:998-1008`.
- TypeScript mirrors the challenge/equation and identity rejection at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:220-253`, with `prove_dlog_verify` aliasing at `crypto-ops.ts:267-284`.
- Tests include valid proof, parity with `schnorr_verify`, tampered proof false, and identity public-key false at `metakit@3fd242c:src/test/scala/json_logic/SigmaOpsSuite.scala:51-105`.

Audit conclusion: This leaf has the required Fiat-Shamir binding for a standalone DLog proof. The identity-key universal-forgery vector is explicitly closed.

### 2. DHTuple leaf uses strong Fiat-Shamir over the full statement

Claim: `prove_dhtuple_verify` binds the full DDH statement `(g,h,u,v)`, both commitments `(a1,a2)`, and `msg` in the challenge, then verifies both equations with one shared response `z`.

Evidence:

- Scala documents the strong transcript `SHA256(g || h || u || v || a1 || a2 || msg) mod R` at `CryptoOps.scala:523-537`.
- Scala parses exact proof width `a1(64B) || a2(64B) || z(32B)`, validates points, enforces canonical `z`, rejects identity statement points, binds canonical statement bytes and proof commitment bytes into the transcript, computes the challenge, and checks both equations at `CryptoOps.scala:542-607`.
- Rust mirrors the DHTuple transcript, identity-point rejection, canonical response rule, and equations at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1011-1100`.
- TypeScript mirrors proof parsing, canonical `z`, identity rejection, transcript construction, and equations at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:286-372`.
- Tests independently construct the same strong-FS challenge at `metakit@3fd242c:src/test/scala/json_logic/SigmaOpsSuite.scala:113-124`.
- Tests cover valid proof, tampered `z`, tampered commitment, swapped `v`, non-DH tuple, changed message, changed base, off-curve point, wrong width, and identity statement points at `SigmaOpsSuite.scala:162-245`.

Audit conclusion: The leaf avoids the weak-Fiat-Shamir class where statement or commitment components are omitted. I found no transcript omission in the DHTuple implementation.

### 3. Recursive `sigma_verify` binds the whole tree with a domain-separated root challenge

Claim: `sigma_verify` implements the CDS verifier by reconstructing commitments, serializing the whole statement tree and reconstructed commitments under a fixed layout, and comparing the proof root challenge to `low31(SHA256("sigma_verify:v1" || serialized_tree || message))`.

Evidence:

- Scala identifies the load-bearing correctness targets: strong Fiat-Shamir, CDS splitting, injective challenge domain, and reconstructed commitments at `CryptoOps.scala:615-649`.
- Scala defines the frozen serialization: node tags, 4-byte arities and threshold `k`, 64-byte G1 points, domain separator, and low31 root challenge at `CryptoOps.scala:652-691`.
- Scala fixes tags and domain separator at `CryptoOps.scala:692-702`, challenge width at `CryptoOps.scala:704-710`, low31 at `CryptoOps.scala:712-718`, and direct challenge-scalar conversion with no mod-R at `CryptoOps.scala:720-725`.
- Scala parses proposition and proof trees, enforces threshold structure, parses 31-byte proof challenges, and enforces canonical response `z` at `CryptoOps.scala:945-1005`.
- Scala recomputes the root challenge over `DomainSep || serialized || msg` and compares bytes at `CryptoOps.scala:1052-1069`.
- Rust mirrors the same domain separator, 31-byte low31 challenge, and no-mod-R challenge scalar at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1108-1177`.
- Rust recomputes root challenge and compares it byte-for-byte at `crypto.rs:1568-1590`.
- TypeScript mirrors the same domain separator, challenge width, low31 rule, and no-mod-R scalar conversion at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:375-433`.
- TypeScript recomputes the root hash over domain, serialized tree, and message at `crypto-ops.ts:956-966`.

Audit conclusion: The recursive verifier binds tree shape, threshold parameters, every statement point, every reconstructed commitment, and message under a dedicated domain separator. This directly addresses the weak-Fiat-Shamir failure class.

### 4. The 31-byte challenge domain removes the raw-challenge/mod-R alias

Claim: Sigma proof challenges are exactly 31 bytes. The verifier uses the same 31-byte object for CDS byte operations and for scalar arithmetic, directly converting `BigInt(1, e)` without reducing modulo `R`. Since `2^248 < R`, the map is injective and the prior `e` vs `e + R` alias cannot occur.

Evidence:

- Scala documents the previous 32-byte raw-vs-mod-R weakness and the 31-byte fix at `CryptoOps.scala:677-690`.
- Scala sets `ChallengeBytes = 31`, derives `low31(digest.takeRight(31))`, and uses `BigInt(1,e)` directly at `CryptoOps.scala:704-725`.
- Scala parses proof challenge `e` as exactly 31 bytes at `CryptoOps.scala:989-998`.
- Rust states and implements the same invariant at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1128-1177`, and parses challenge bytes at `crypto.rs:1484-1492`.
- TypeScript states and implements the same invariant at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:390-433`, and parses challenge bytes at `crypto-ops.ts:512-516`.
- Scala tests explicitly prove `2^248 < R`, low31 range, and rejection of the `e + R` alias at `metakit@3fd242c:src/test/scala/json_logic/SigmaVerifySuite.scala:483-510`.
- Scala tests reject old 32-byte challenges as wrong width at `SigmaVerifySuite.scala:737-750`.

Audit conclusion: This is the key remediation. The refreshed implementation correctly avoids the dangerous split where CDS operates on raw bytes but leaf algebra operates on reduced scalars.

### 5. Responses are canonical and proof-byte malleability is constrained

Claim: Response scalars `s`/`z` must be canonical `< R`; accepting `z + R` would verify the same algebra with different proof bytes.

Evidence:

- Scala `requireCanonicalScalar` rejects response scalars `>= R` at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:359-367`.
- Scala uses this guard for Schnorr/DLog at `CryptoOps.scala:382-383`, DHTuple at `CryptoOps.scala:562-565`, and recursive proof responses at `CryptoOps.scala:1000-1005`.
- Rust implements the same rule at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1494-1507` and applies it at `crypto.rs:1509-1513`.
- TypeScript implements the same rule at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:107-114`, applies it to Schnorr at `crypto-ops.ts:227-228`, DHTuple at `crypto-ops.ts:324-327`, and recursive proof responses at `crypto-ops.ts:518-522`.
- Totality/adversarial generation explicitly includes non-canonical scalars at `metakit@3fd242c:src/test/scala/json_logic/CryptoOpsTotalitySuite.scala:33-37` and malformed 32-byte all-ones cases at `CryptoOpsTotalitySuite.scala:79-99`.

Audit conclusion: Response malleability is addressed at every entry point reviewed.

### 6. CDS challenge splitting is checked, not trusted

Claim: The verifier enforces CDS composition rules: AND copies the parent challenge, OR XORs child challenges to the parent, and threshold verifies byte-wise GF(2^8) interpolation.

Evidence:

- Scala implements GF(2^8) threshold arithmetic at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:770-815`.
- Scala checks AND, OR, and threshold challenge relations and serializes connectives at `CryptoOps.scala:1125-1183`.
- Scala threshold interpolation verifies degree `n-k` byte-lane polynomials using `(0,parent)` plus the first `n-k` child points at `CryptoOps.scala:1190-1226`.
- Rust mirrors AND/OR/threshold checks at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1653-1754` and threshold interpolation at `crypto.rs:1765-1790`.
- TypeScript mirrors GF arithmetic and threshold interpolation at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:679-772`, and verifies AND/OR/threshold relations at `crypto-ops.ts:874-941`.
- Scala soundness tests include OR simulate-all false, OR root-match-but-XOR-break false, threshold with only `k-1` witnesses false, wrong message false, tampered response false, tampered challenge false, and broken AND relation false at `metakit@3fd242c:src/test/scala/json_logic/SigmaVerifySuite.scala:545-673`.

Audit conclusion: The code implements the standard CDS verifier pattern and tests the important adversarial cases. No missing split relation was identified.

### 7. Encoding is fixed-width and unambiguous

Claim: The transcript uses fixed-width encodings and rejects malformed/non-canonical field encodings at opcode boundaries.

Evidence:

- Scala `HexBytes` defines the lowercase `0x`, big-endian, fixed-width convention and no-throw error discipline at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/HexBytes.scala:9-30`.
- `parseBytes` validates lowercase prefix, even body, and exact expected width at `HexBytes.scala:65-92`.
- `parseFr`, `parseFq`, and `parseG1` enforce canonical field-coordinate widths and bounds at `HexBytes.scala:94-141`.
- Fixed-width encoders for bytes, G1, integers, and Fr are at `HexBytes.scala:192-216`.
- Scala serialization helpers use canonical reconstructed G1 bytes, 4-byte big-endian counts, XOR, and constant-time equality at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:1245-1264`.
- Rust re-encodes reconstructed commitments and uses fixed 4-byte counts at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1843-1854`.
- TypeScript uses fixed 4-byte counts, fixed challenge width, and byte equality helpers at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:774-823`.

Audit conclusion: I did not find a variable-width or ambiguous-serialization path in the reviewed transcript construction.

### 8. Proof-shape bounds exist, but DoS hardening is incomplete

Claim: The verifier distinguishes malformed input from invalid proofs and has a raw proof-shape bound before recursive proof parsing. This helps, but it is not sufficient production DoS hardening because proposition traversal and message length are not comparably bounded.

Evidence:

- Scala parses the proposition, computes proposition raw shape, bounds proof node count/depth before parsing proof nodes, then verifies the tree at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:820-858`.
- Scala implements cheap raw-shape and proof-bound walkers at `CryptoOps.scala:860-914`.
- Rust mirrors proof-shape bounds at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1243-1347`.
- TypeScript mirrors proof-shape bounds at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:621-677`.
- Scala DoS tests cover tiny proposition with huge proof and excessive nested proof depth at `metakit@3fd242c:src/test/scala/json_logic/SigmaVerifySuite.scala:512-539`.
- Totality tests define the pure opcode layer no-throw goal at `metakit@3fd242c:src/test/scala/json_logic/CryptoOpsTotalitySuite.scala:15-38`, assert no throw at `CryptoOpsTotalitySuite.scala:52-58`, include all Sigma opcodes at `CryptoOpsTotalitySuite.scala:175-189`, and run random Sigma tree no-throw checks at `CryptoOpsTotalitySuite.scala:191-216`.

Audit conclusion: Proof-shape bounds are present and meaningful, but the broader DoS posture is not complete. See implementation findings IMPL-1 through IMPL-4.

### 9. Cross-language parity is covered by shared vectors

Claim: Rust and TypeScript ports are designed to reproduce the Scala reference behavior for Sigma categories.

Evidence:

- Rust differential harness lists Sigma categories and op tags at `metakit-sdk@a1de0d3:rust/jlvm-core/tests/zk_differential.rs:97-109`.
- Rust harness requires value cases to match both structurally and by canonical bytes at `zk_differential.rs:291-320`, and reports/asserts full pass at `zk_differential.rs:346-390`.
- TypeScript conformance test includes `sigma_dlog`, `sigma_dhtuple`, and `sigma` categories at `metakit-sdk@a1de0d3:packages/typescript/tests/json-logic-zk-vectors.test.ts:40-49`, requires error cases to throw at `json-logic-zk-vectors.test.ts:64-69`, and checks expected values at `json-logic-zk-vectors.test.ts:73-79`.
- Shared vectors include Sigma categories at `metakit@3fd242c:src/test/resources/conformance/zk_opcode_test_vectors.json:633`, `:692`, and `:763`; the same file is shared into `metakit-sdk`.
- Counting the refreshed SDK vector file yields 9 `sigma_dlog`, 11 `sigma_dhtuple`, and 23 `sigma` cases, for 43 Sigma-category cases.

Audit conclusion: The ports are not just similar prose ports; they are covered by shared differential vectors. I did not rerun the test suites in this artifact pass, so this is a static evidence claim plus test-harness review, not a fresh green test report.

## Implementation Findings Separate From Fiat-Shamir Soundness

These findings do not show an accepting Fiat-Shamir/CDS forgery. They do affect production readiness in adversarial execution environments.

### IMPL-1: unbounded proposition recursion occurs before proof bounds and before gas is consumed

Severity: High for production hardening; not a Fiat-Shamir soundness flaw.

`sigma_verify` parses the full proposition before applying proof shape caps. The recursive proposition parser has no explicit node/depth cap. The gas layer also computes the Sigma proposition shape while constructing the pre-charge, before gas is actually consumed. A deeply nested proposition supplied through data can therefore force stack/CPU work before the documented proof cap applies.

Evidence:

- Scala `sigmaVerify` parses `prop` before computing `sigmaRawShape` or calling `boundProofShape` at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:831-849`.
- Scala recursive proposition parsing descends `and`, `or`, and `threshold` children at `CryptoOps.scala:945-979`.
- Scala gas computes `preCost = ... + getInputScaledCost(...)` before `consumeGas(preCost)` at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/semantics/GasAwareSemantics.scala:122-138`.
- Scala Sigma gas shape traversal recursively descends proposition children at `GasAwareSemantics.scala:329-347`.
- Rust mirrors proposition parse before proof bound at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1251-1267`.
- TypeScript mirrors proposition parse before proof bound at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:970-985`.

Recommendation: Add schema-aware proposition node/depth caps before recursive parse and before expensive work. The cap should be charged or enforced using the same semantic shape used for gas, not a separate raw walk.

### IMPL-2: proof bound can be inflated through ignored `children` fields on leaf propositions

Severity: Medium.

The raw proof bound is derived from `sigmaRawShape`, which counts any `children` field on any map. The proposition parser and gas shape logic treat `dlog`/`dhtuple` as leaves and ignore extra fields. A semantically one-leaf proposition with an ignored large `children` field can increase the allowed proof shape without paying corresponding Sigma tree gas.

Evidence:

- Scala `sigmaRawShape` recurses into any raw `children` field at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:860-879`.
- Scala proposition parsing treats `"dlog"` as a leaf and ignores unrelated fields at `CryptoOps.scala:945-950`.
- Scala gas treats `dlog` and `dhtuple` as leaves at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/semantics/GasAwareSemantics.scala:329-347`.
- Rust raw-shape/proof-bound logic mirrors the raw `children` walk at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1276-1347`.
- TypeScript raw-shape/proof-bound logic mirrors the raw `children` walk at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:621-677`.

Recommendation: Either reject unknown fields in Sigma proposition/proof nodes, or derive proof bounds from the parsed semantic proposition shape. The bound and gas model should use the same schema-aware interpretation.

### IMPL-3: Sigma message bytes are arbitrary length and not separately metered

Severity: Medium.

`messageHex` is parsed as arbitrary-width bytes for DHTuple and recursive Sigma verification. The gas model scales by proposition shape and root serialization size, but there is no explicit independent message-length cap or charge at the Sigma opcode boundary. Large messages can force hex decode, allocation, and SHA-256 work outside the intended Sigma tree pricing.

Evidence:

- Scala DHTuple parses message with unbounded `parseBytes(..., None)` at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:556`.
- Scala `sigmaVerify` parses message with unbounded `parseBytes(..., None)` at `CryptoOps.scala:835-836`.
- Scala `HexBytes.parseBytes` allows arbitrary width when `expectedLen` is `None` at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/HexBytes.scala:65-92`.
- Rust mirrors unbounded DHTuple message parsing at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1042`.
- TypeScript mirrors unbounded DHTuple message parsing at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:317` and Sigma message parsing at `crypto-ops.ts:974-975`.
- Scala Sigma gas is based on proposition tree shape at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/semantics/GasAwareSemantics.scala:304-317`.

Recommendation: Define a maximum Sigma message length or add message-byte scaled gas before parsing/hashing. OttoChain should also define a compact canonical message format for policy use.

### IMPL-4: TypeScript loses the early-abort proof bound during plain-object conversion

Severity: Medium for SDK/TS DoS parity.

The TypeScript port converts the entire parsed `JsonLogicValue` tree to a plain JS object before calling `boundProofShape`. That recursive conversion walks and allocates the whole proof tree, so it does not preserve the Scala/Rust early-abort property. It also writes attacker-controlled keys into `{}`, weakening the `MapValue` prototype-safety model for this shape walk.

Evidence:

- TypeScript computes proof bounds through `boundProofShape(valueToPlain(values[1]), ...)` at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:980-984`.
- `valueToPlain` recursively converts maps into plain `{}` objects at `crypto-ops.ts:988-1007`.
- TypeScript raw-shape logic reads `children` from the converted object at `crypto-ops.ts:621-643`.

Recommendation: Traverse `JsonLogicValue`/`MapValue` directly for shape bounds, or use `Object.create(null)` plus own-property checks and a bounded conversion. Prefer direct traversal to preserve early abort and prototype-safety.

### IMPL-5: extra fields make raw proposition/proof encodings non-canonical

Severity: Low/Medium.

Unknown fields are accepted and ignored by the Sigma parsers. This does not appear to create algebraic proof malleability because the verifier hashes only the semantic proposition and reconstructed commitments. It does make raw JSON/object encodings non-canonical for logs, caches, external signing layers, and DoS accounting.

Evidence:

- Scala field lookup consumes only required keys at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:918-920`.
- Scala proposition parser consumes known fields by node type at `CryptoOps.scala:945-985`.
- Rust field lookup is last-wins/selective at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1351-1358`.
- TypeScript field lookup consumes only required keys at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:463-471`.

Recommendation: For consensus/security-facing use, reject unknown fields in Sigma proposition and proof node maps, or define and document a canonical normalization layer before signing/logging/cache keys are computed.

### IMPL-6: TypeScript Sigma vector tests should pin vector counts and edge-case floors

Severity: Low test-hardening issue.

TypeScript includes Sigma vector categories but does not assert expected category counts or edge-case floors. Rust has a stronger differential harness. This can let future vector-file regressions silently reduce coverage.

Evidence:

- TypeScript includes Sigma categories at `metakit-sdk@a1de0d3:packages/typescript/tests/json-logic-zk-vectors.test.ts:40-49` and iterates available cases at `json-logic-zk-vectors.test.ts:54-83`.
- Rust lists Sigma categories/op tags at `metakit-sdk@a1de0d3:rust/jlvm-core/tests/zk_differential.rs:97-109` and has report/assert infrastructure at `zk_differential.rs:346-390`.

Recommendation: Add TS assertions for the expected 43 Sigma cases and specific required edge cases: 31-byte challenge width, 32-byte challenge rejection, non-canonical `z`, OR/XOR negatives, threshold negatives, and proof-shape bound cases.

## Documentation Correctness Findings

These findings do not block the code-level Fiat-Shamir verdict, but they should be fixed before this is handed to another auditor as the authoritative artifact.

### DOC-1: shared vector metadata still says root challenge is `SHA256(... ) mod R`

Severity: Medium documentation correctness issue.

The code now uses `low31(SHA256(domain || tree || msg))` with no mod-R reduction for `sigma_verify` root challenges. However the shared vector metadata still describes the Sigma root as `root = SHA256(domainSep||tree||msg) mod R`.

Evidence:

- Stale metadata appears in `metakit@3fd242c:src/test/resources/conformance/zk_opcode_test_vectors.json:2`.
- The same metadata appears in `metakit-sdk@a1de0d3:shared/zk_opcode_test_vectors.json:2`.
- Correct implementation is in Scala at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:1052-1069`, Rust at `metakit-sdk@a1de0d3:rust/jlvm-core/src/crypto.rs:1568-1590`, and TypeScript at `metakit-sdk@a1de0d3:packages/typescript/src/json-logic/crypto-ops.ts:956-966`.

Recommendation: Update the vector description to say `root = low31(SHA256(domainSep || tree || msg))`, compared byte-for-byte with the 31-byte proof challenge, no mod-R.

### DOC-2: RFC references a `sigma_opcode_test_vectors.json` and serialization-only vector set that do not appear in the PR

Severity: Low documentation correctness issue.

The RFC says conformance vectors are checked into `sigma_opcode_test_vectors.json` plus a serialization-only vector set. The refreshed PRs instead carry `zk_opcode_test_vectors.json`; I did not find a separate serialization-only KAT file in the PR tree.

Evidence:

- RFC text at `metakit@3fd242c:docs/sigma-verify.md:244-248`.
- Actual metakit vector file is `metakit@3fd242c:src/test/resources/conformance/zk_opcode_test_vectors.json`.
- Actual SDK vector file is `metakit-sdk@a1de0d3:shared/zk_opcode_test_vectors.json`.

Recommendation: Either add the promised serialization-only KAT file or rewrite the RFC to say the `sigma` category in `zk_opcode_test_vectors.json` is the frozen serialization byte-contract. Do not leave auditors searching for a non-existent artifact.

### DOC-3: RFC still says the proof tree carries commitments in one algorithm step

Severity: Low documentation precision issue.

The implementation does not carry commitments in the recursive `sigma_verify` proof tree; it carries per-node challenges and per-leaf responses, then reconstructs commitments. One algorithm sentence still says proof tree carries per-leaf `(commitment, response)` pairs, which conflicts with the later and correct statement that commitments are not carried.

Evidence:

- Ambiguous sentence: `metakit@3fd242c:docs/sigma-verify.md:99-101`.
- Correct commitment reconstruction rule: `docs/sigma-verify.md:116-124`.
- Correct proof schema: `docs/sigma-verify.md:168-184`.
- Implementation proof nodes contain `e` and `z`, not commitments, at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:987-1038`.

Recommendation: Replace the sentence with "proof tree carries per-node challenges and per-leaf responses."

### DOC-4: some comments and test prose overclaim or preserve stale wording

Severity: Low documentation/test clarity issue.

The implementation is clearer than some surrounding prose. The RFC status text mentions Scala and Rust but omits the TypeScript implementation. One Scala verifier comment still describes the final hash as "mod R" even though code now uses low31/no mod-R for recursive Sigma. The Sigma test suite summary also claims coverage of duplicate/out-of-range threshold-share indices, but threshold shares are implicit child positions; there are no explicit share indices to duplicate.

Evidence:

- RFC status text says "metakit Scala reference + metakit-sdk Rust" at `metakit@3fd242c:docs/sigma-verify.md:24-26`, omitting the TypeScript implementation in `metakit-sdk`.
- Scala comment says the final hash is "mod R" at `metakit@3fd242c:src/main/scala/io/constellationnetwork/metagraph_sdk/json_logic/ops/CryptoOps.scala:1047-1050`; the actual implementation uses `Sigma.low31(...)` and byte comparison at `CryptoOps.scala:1063-1069`.
- Test-suite summary mentions duplicate/out-of-range share-index coverage at `metakit@3fd242c:src/test/scala/json_logic/SigmaVerifySuite.scala:28-30`; actual threshold indices are implicit child positions and `n <= 255` is enforced at `CryptoOps.scala:974-979`.

Recommendation: Align comments and test prose with the actual low31 challenge rule and implicit-index threshold model. Keep prose precise: standalone JLVM `or` over atomic leaves is not a hiding Sigma OR proof, but it can still be a valid non-hiding authorization policy if at least one standalone proof verifies.

## Residual Caveats

1. Message binding is an integration requirement. The verifier binds exactly the `messageHex` supplied to it. OttoChain policy code must make that message canonical and domain-separated for the intended action, asset, morphism, chain/network, and replay context. Otherwise a valid proof can be replayed wherever the same proposition and message are accepted. This is not a verifier bug; it is an integration contract.

2. This audit is code-level, not proof-level. The implementation matches the intended Fiat-Shamir/CDS pattern under standard assumptions, but the extraction/soundness theorem for this exact encoding and challenge width has not been formally proven here.

3. The code remains "hand rolled" in the sense that the recursive Sigma/CDS verifier is locally implemented rather than imported from a mature cryptographic library. The current implementation is auditable and internally consistent, but that fact increases the value of keeping the RFC, vectors, and comments exact.

4. Operational hardening is not complete. The Fiat-Shamir transcript can be sound while the opcode is still vulnerable to resource-exhaustion patterns. IMPL-1 through IMPL-4 should be handled before high-value adversarial deployment.

## Final Recommendation

Do not block the refreshed PRs on the Fiat-Shamir soundness concern as currently implemented. The refreshed implementation addresses the specific hand-rolled hazards that would have been blocking: weak transcript binding, 32-byte challenge/mod-R aliasing, response malleability, identity-point forgeries, and unchecked CDS split relations.

Do block claims that this opcode is production DoS-hardened until IMPL-1 through IMPL-4 are fixed or explicitly accepted by risk owners. Before treating the docs as final audit material, fix DOC-1 through DOC-4. For OttoChain production use, define a canonical, compact `messageHex` construction for each `sigma_verify` policy path and record it as an ADR or protocol specification.
