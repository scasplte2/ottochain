# Supplier verification of an agent mandate

Two wallet signatures authorized request 2 for 4,000 test cents to supplier-a. The ledger recorded the authorization at ordinal 198 and owner revocation at 227. The recording and examples use synthetic invoices.

## Replay a real decision

From the root of the checkout at tag `demo/agent-mandate-20261120`, run the following with Node 24.21.0 and npm. The first command installs the published, locked dependencies. Replay then works offline with the files in `evidence/`.

```sh
cd e2e-test
npm ci --no-audit --no-fund
node examples/agent-mandate/decision.ts
node examples/agent-mandate/tamper.ts
```

Expected decision output includes `Committed action at 198 · $40.00 remaining at 199` and `Revoked by 227 · $40.00 remaining`. The tamper regression accepts the genuine checkpoint and requires rejection for each independent modification. To observe the verifier itself return exit 1 after changing one digit, run this in `e2e-test`.

```sh
d=examples/agent-mandate
changed=$(mktemp)
jq '.proof.record.stateData.remainingCents = 4001' "$d/evidence/authorized.bundle.json" > "$changed"
node "$d/verify-mandate.ts" replay "$changed" "$d/evidence/authorized.pin.json"
# Error: leaf dataDigest != sha256(JCS(record))
rm "$changed"
```

The pinned owner is the recorded demo issuer. The decision command independently checks the two application signatures, expected invoice, fiber, target sequence, persisted signers, remaining budget and later revocation. For another mandate, supply independently obtained capture pins and establish the issuer and invoice appropriate to that transaction.

## What happened on the ledger

All eight rows refer to fiber `fb9388d7-0d1d-4a2b-8cda-46e48fe26021`. The machine uses proof-derived addresses and an increasing request number. The runtime also binds a signed update to its target fiber sequence. Amounts are integer cents, the solo limit is 2,500 cents, the suppliers are supplier-a and supplier-b, and authorization expires at ordinal 100000.

| Signed request | Action ordinal | Test balance | Result |
|---|---:|---:|---|
| Owner creates mandate | 101 | $100 | Created |
| Agent, $20 to supplier-a | 152 | $80 | Authorized |
| Agent, $20 to other | 189 | $80 | Guard refused |
| Stranger, $20 to supplier-a | 192 | $80 | Guard refused |
| Agent alone, $40 to supplier-a | 196 | $80 | Guard refused |
| Agent and co-signer, same $40 request | 198 | $40 | Authorized |
| Owner revokes | 227 | $40 | Revoked |
| Agent, fresh $10 to supplier-b | 254 | $40 | Guard refused |

`evidence/ledger.json` records the actual requests, verified signer addresses, receipt ordinals, snapshot identities, and separate record-witness ordinals. The refused requests carry the runtime’s generic “No guard matched” reason. The table labels identify the changed input. The runner requires the signed request in the failure receipt’s ledger snapshot and unchanged business state.

## Capture and replay

```sh
# Run in e2e-test. Use a live fiber ID printed by the runner.
d=examples/agent-mandate
node "$d/verify-mandate.ts" capture new.bundle.json new.pin.json "$FIBER" http://127.0.0.1:9200
node "$d/verify-mandate.ts" replay new.bundle.json new.pin.json
node "$d/card.ts" "$d/evidence/cosigner-required.bundle.json" "$d/evidence/cosigner-required.pin.json" 4000
```

Add a second ML0 URL after the primary to collect its node identity and the corresponding ledger snapshot. To follow a run from creation through post-revocation refusal, start `node "$d/observe.ts" "$FIBER" new-evidence-directory` when the runner prints its fiber ID.

The verifier checks the SDK inclusion witness at the expected `fiber/<id>` key and renders the proved record. It independently computes SHA-256 over the decoded MPT root followed by the decoded catalog root, then compares it with the ledger snapshot’s `value.dataApplication.calculatedStateProof`. A later ledger snapshot authenticates the catalog proof for the earlier ordinal. Metakit puts N’s trie root into the next catalog. The checked epoch size is 65536.

The network pin is the local ledger’s first incremental snapshot hash. The pins also identify each snapshot by served hash, exact JSON-response SHA-256 and node ID. Supply pins through a trusted channel, such as the reviewed revision. This run used one serving ML0 on one operator. The separate ML0 attempted catch-up but failed its calculated-state check. Validator signature threshold verification against an independently trusted validator set remains open. Application signatures are verified directly.

## Run the live example and agent

The application uses OttoChain base `7ada01f1`, Scala 2.13, Java 21.0.9, sbt 1.12.0 and Metakit 1.8.0-rc.7. The Tessellation SDK pin is 4.0.0. The local stack used the CI hypergraph release mapping to v4.0.0-rc.10. Use an isolated local stack with one Ready GL0 at 9000, ML0 at 9200 and three Ready DL1 endpoints at 9400, 9410 and 9420. Configure `DATA_PEERS_COUNT=2` and `DATA_L1_TIME_TRIGGER_INTERVAL="8 seconds"` inside the node containers. The captured topology is in `evidence/topology.json`.

```sh
# From the repo root
bin/test shared '*AgentMandateSuite*'
just e2e-health
cd e2e-test
npm ci --no-audit --no-fund
npm run typecheck
npx --yes -p node@20.20.2 -c 'node node_modules/tsx/dist/cli.mjs runner.ts --target local --only approval-workflow --wallets owner'
npx --yes -p node@20.20.2 -c 'node node_modules/tsx/dist/cli.mjs runner.ts --target local --only agent-mandate --wallets owner,agent,cosigner,stranger --parallel false'
npx --yes -p node@20.20.2 -c 'node node_modules/tsx/dist/cli.mjs examples/agent-mandate/model-demo.ts new-invoice.jsonl'
```

The model harness connects to loopback Ollama. The operator creates a separate fresh mandate and gives the agent process its test wallet. The model sees `read_mandate` and `submit_payment`. The latter accepts only the exact invoice, once, and uses the runner’s signed transition generator and DL1 transport. Run the approved control with a fresh transcript path followed by `supplier-a`. Reusing a transcript path exits before generating another mandate. The card and offline verifier only read data.

Euler’s already-loaded `qwen-turbo:q6` read the mandate and declined the supplier-c invoice. On a separate fresh mandate, it submitted a 2,000-cent supplier-a authorization that the ledger committed at ordinal 271. Both transcripts preserve the actual tool calls and timings. The deterministic refusal at 189 was a separate test submission. The recording replays captured decisions with waits removed.
