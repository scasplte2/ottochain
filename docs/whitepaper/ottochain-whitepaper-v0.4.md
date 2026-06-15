# OttoChain

### A Trust Commons for Autonomous Agents

*An AI-native smart-contract system: readable contracts, verifiable execution, and a web of metagraphs for the agent economy.*

**Draft v0.4, June 2026**
Repository: https://github.com/scasplte2/ottochain

---

> **Abstract.** Trust on the internet is thinning in a way people feel before they can formalize it. The cost of checking reality keeps rising: who said this, where did it come from, was the image real, was the account human, was the reputation earned, did the service do what it promised? Platforms answer with convenience, but the bargain is dependence: the evidence, the identity, the record, and the appeal process live inside someone else's system. Into that world we are releasing autonomous AI agents that browse, execute code, move money, and negotiate on our behalf at a scale no human population reached. OttoChain is a **digital trust commons** for the part of this problem that can be made procedural: agreements, state transitions, attestations, service events, and records of execution. It lets agents establish identities they own, earn reputations that travel, and coordinate through contracts written in a language they can *read*: finite, declarative logic an agent can inspect, simulate, hash-check, and run for itself before it acts, rather than compiled bytecode it must take on faith. Because that language is finite and deterministic, its execution can be metered, proven, and shielded. And because each OttoChain is a metagraph on the Constellation Hypergraph, one chain becomes many: a web of specialized, interoperable chains, public through private, that verify one another through proofs rather than trust. We call that horizon **OttoWeb**. This paper sets out the thesis, the substrate that already exists, what the chain does and does not prove, and the honest road to production.

---

## 1. The Dark Forest

Start with the feeling, because you already have it.

Something happens online. A headline spreads, screenshots follow, source accounts contradict one another, and the evidence arrives already detached from its provenance. You can search, but search returns another argument. You can inspect the record, but the record may be partial, hidden, spoofed, or owned by a platform that has no reason to show its work. The problem is not that everything is false. The problem is that the cost of checking what is real is high enough that most people cannot pay it most of the time.

That cost shows up everywhere. Is the review real? Is the account a person? Was the video captured, edited, staged, or generated? Did a reputation come from completed work or from manufactured signals? Did a service do what it promised? When something goes wrong, the decisive facts may exist, but they usually live inside a company's database, policy engine, or support queue. A large institution can demand logs, contracts, service levels, audits, and remedies. An ordinary user gets whatever recourse the platform chooses to provide.

Now put agents in that world. They can read more than we can, move faster than we can, and act in more places at once, but they inherit the same missing ground truth. They cannot reliably know whether a counterparty is the same one they trusted yesterday, whether its reputation was earned, or whether the terms of an interaction mean what a human summary claims they mean. They are not entering a clean machine economy. They are entering the same dark forest, only faster.

Here is the part worth sitting with: **most actors are honest.** The problem is not that adversaries are the majority. It is that **verification is too expensive, so honest participants cannot cheaply discipline dishonest ones.** Proving a lie, exposing a fake, or holding a bad actor to account is slow, costly, and usually somebody else's job. Dishonesty does not have to dominate; it only has to be cheaper to commit than to expose. Drive the cost of verification down far enough and the honest majority wins by default.

OttoChain does not claim to settle every truth on the internet. It starts with the class of truth that can be made procedural: agreements, state transitions, attestations, service events, and records of execution. If those interactions are written in rules agents can read, executed deterministically, and committed to an auditable record, then at least one part of the trust problem becomes cheap enough to challenge. That is what we mean by a **digital trust commons**: shared infrastructure where parties that do not share an institution can inspect rules, order events, audit records, and challenge claims.

### 1.1 Why a Chain at All

Once the problem is verification cost, a chain has a specific job: it is shared memory for parties that do not share an institution. It gives them common ordering, durable records, and rules for updating state without asking a platform to be the judge of what happened. Satoshi's practical discovery was that strangers could maintain such a record under adversarial conditions. OttoChain applies that discovery to agent coordination. Give agents a legible place to transact and verify and you do not merely get a payments network; you get an autonomous decision network, where a cryptographic medium plays the trusted intermediary without being a *someone* who can abuse the role.

## 2. What Existing Chains Leave Out

Blockchains already made one kind of verification cheap: ownership and transfer of scarce digital value. That is not a small thing. But agent coordination asks for a different surface. An agent deciding whether to enter an agreement needs to know what rules govern it, what state will persist, and which identity will remain accountable after the interaction. Most chains expose those things poorly.

- **Agreements are not legible at the point of use.** A deployed contract is usually bytecode plus an interface, a source repository, an audit, or a human explanation. Those may be useful, but they are not the agreement itself in the form an agent can inspect directly. The agent is still trusting a translation layer around the thing it is about to use.
- **Workflows are treated as transactions.** Existing gas and state models are strongest when work is short, financial, and final. Agent coordination is often long-lived: negotiation, delivery, dispute, retry, attestation, and settlement may unfold over days or weeks. The state of the interaction is not incidental; it is the product.
- **Addresses are not accountability.** A key or name can identify where a message came from, but it does not by itself carry earned history, signed attestations, cross-platform reputation, or the cost of abandoning a bad record. Agents need identities that remember.
- **Integration usually runs in the wrong direction.** Crypto systems often ask enterprises to meet the chain on the chain's terms. When they do reach into Web2, the bridge is usually application-specific: one oracle, one API adapter, one bespoke workflow. There is no generic way for an existing business process to become gradually more verifiable without being rebuilt as a crypto-native application from day one.

The missing primitive is not more expressiveness. It is *legible accountability*: rules an agent can inspect, state it can replay, identities it can evaluate, and records it can challenge. Human institutions made trust portable with readable instruments, contracts and statutes and ledgers that a literate party can inspect for itself. Agents are literate too, but their instruments have to be executable. Readability does not make a contract safe. It makes it checkable; binding that readable rule to deterministic execution is what makes it useful.

> **Mission.** OttoChain creates a digital trust commons for autonomous agents: a decentralized platform where AI agents establish verifiable identities, build accountable reputations, and coordinate through human-readable state machines that both agents and humans can reason about.

## 3. The OttoChain Thesis

The thesis is narrower than the problem and stronger for being narrow.

When an interaction can be expressed as rules, state, events, and attestations, those rules should be legible to the participants, bound to deterministic execution, and recorded somewhere no single platform controls. An agent should be able to inspect the agreement before entering it, simulate its own move, verify which logic actually ran, and challenge the result with evidence if the record is disputed. A human should be able to ask the same questions, even if they delegate the work to software.

OttoChain is one implementation of that digital trust commons. It gives agents:

- **Identities they own**, so an agent can bind platform-specific keys under a durable identity and carry history across contexts.
- **Agreements they can read**, so the rule governing an interaction is not a summary, screenshot, ABI, or audit report, but the executable artifact itself.
- **Execution they can check**, so a transition leaves a record of which logic was bound, what event arrived, what state changed, and what attestations followed.

The claim is not that agents can trust blindly. It is that they can trust *less blindly*: by reading the rules, checking the binding, replaying the transition, and pricing the residual trust that remains. The same pattern also gives existing businesses a graduated path into verifiable systems. A workflow can become partially legible, then auditable, then provable, without forcing the whole organization to become crypto-native overnight. The substrate below is what makes that possible. The web comes later as the consequence: once one trust commons works, the same pattern can be specialized, replicated, and connected across metagraphs.

> **Vision.** Agents and humans coordinate through agreements whose rules are inspectable, whose execution is ordered, and whose claims can be challenged. Public execution becomes checkable; private execution becomes provable where needed; and the trust that remains is named, priced, and earned rather than assumed.

## 4. The Substrate: A Language Agents Can Read

The thesis needs machinery, and much of that machinery exists today. OttoChain runs as a metagraph on Constellation's Tessellation framework, which is what lets it ship a complete custom virtual machine instead of inheriting a fixed contract model. The substrate has four requirements: legible rules, bounded execution, composable workflow state, and proofs that can travel.

### 4.1 Readable: contracts as data, not code

OttoChain contracts are state machines written in JSON Logic: states, transitions, guards, and effects expressed as plain declarative JSON. A guard is a condition, an effect is a result, and there are no loops or hidden side effects. One rule leads to one outcome, deterministically.

```json
{ "if": [ { ">": [ { "var": "caller.reputation" }, 100 ] },
          "allow",
          "deny" ] }
```

JSON is already a working medium for LLM-based agents. They can parse it, compare it, explain it, and generate it without decompiling bytecode or trusting a separate source map. An agent weighing a contract can read the rule, simulate it on its own inputs, and understand the move before committing. The expressive power given up against a Turing-complete language buys something agents value more here: rules that are legible, deterministic, and bounded enough to reason about. Underneath sits the **JLVM**, the JSON Logic Virtual Machine, built as the open-source `metakit` library, which extends standard JSON Logic with the operators a trust platform needs while keeping exact, arbitrary-precision arithmetic and fully deterministic evaluation. It is published today (`io.constellationnetwork:metakit_2.13:1.8.0-rc.4`, Maven Central).

### 4.2 Verifiable: metered, portable, provable

Finite and deterministic buys three practical guarantees for agents.

**Metered, with a knowable bound.** Every operation has a fixed cost; a comparison is a few units of gas, a Groth16 proof verification is 250,000, the heaviest in the VM, under a default budget of one million, and a transition either finishes within budget or rolls back with no partial state. Because the cost model is static, an upper bound is computable without running the contract, and the exact cost falls out of simulating it on the real inputs. An agent can price an interaction before entering it.

**Portable, so the agent can run the contract itself.** The JLVM is reimplemented across languages and held in lockstep by shared cross-language test vectors, so a client gets the same canonical result the chain will. The Rust implementation carries the full cryptographic and zero-knowledge opcode set; the TypeScript implementation covers the base language for in-browser pre-execution. Either way an agent can simulate an interaction locally and verify the outcome before sending a transaction. The chain proposes; the client checks.

**Provable.** The JLVM has native opcodes that verify cryptographic proofs from inside a contract: Poseidon hashing, Merkle, sparse-Merkle, and Merkle-Patricia membership proofs, Groth16 zero-knowledge proofs, BLS and Schnorr signatures, ECVRF. The evaluation itself can be proven too: an off-chain SP1 program can run JSON Logic over its inputs and emit a compact Groth16 proof that this program, on this data, produced this output. The verifier lives in `metakit`, and OttoChain inherits it through the JLVM: a chain can check the proof through the metered `groth16_verify` path without trusting the prover. OttoChain still needs the surrounding workflow: when proofs are generated, how they are submitted, and how proof-backed execution appears to applications.

A point of emphasis, because it is the heart of the design: readable JLVM is already enough for a public chain, where everything is legible and anyone can replay it, so no zero-knowledge is required. Zero-knowledge is the privacy enabler. It is what makes the semi-private and private tiers of OttoWeb possible, by letting a party prove it followed the rules without revealing the values it ran them on.

### 4.3 Composable: fibers, signals, and a package registry

The unit of computation is the **fiber**: a lightweight, addressable instance of a state machine, or of a *script*, a stateful on-chain computation that is the verifiable analog of a microservice. Fibers coordinate by emitting **effects as data**. A transition's result can trigger an event on another fiber, spawn a child, call a script, or emit an external event (`_triggers`, `_spawn`, `_scriptCall`, `_emit`). Emitted events and transition receipts are recorded per-snapshot in the chain's logs as the deterministic signalling surface that off-chain watchers read (this is the foundation for the SaaS integration in §8). Coordination is itself readable data, not hidden control flow, and every step either succeeds or the whole transaction rolls back.

Contracts are versioned, named, and hash-bound rather than anonymous. OttoChain ships a **schema registry that works like a package manager for on-chain logic**, npm for contracts. Machines and scripts have the same standing in the registry: each is a named package with owners, semantic versions, an append-only version lineage, and a lifecycle (active, deprecated, or *yanked*). Every published version carries a cryptographic **logic hash**, and when a fiber is created the chain checks that the logic it will run hashes to exactly what the registry published, so a fiber provably runs the contract it claims to. A fiber can be **upgraded** to a newer version along a signature-verified path that may carry a JSON Logic *migration* to transform its state, and an opt-in *strict* mode rejects any state that does not match the version's declared shape. Agents get what an economy needs: contracts discoverable by name, reasoned about by shape, trusted by hash, and evolved without breaking their dependents.

### 4.4 Federable: proofs that can leave the chain

The proof surface is not limited to contracts calling cryptographic opcodes. OttoChain commits its calculated state behind an authenticated root and exposes field-level state proofs for machines and scripts. A verifier does not need the whole database to check a claim about a fiber; it needs the committed root, the value, and the proof path. The JLVM also verifies sparse-Merkle and Merkle-Patricia inclusion, non-inclusion, and complete-prefix proofs as native opcodes, so proofs produced by one environment can become inputs to another. This is the technical bridge from one trust commons to many: a claim can travel without asking the original platform to speak for it.

## 5. What OttoChain Proves, and What It Doesn't

A trust commons earns credibility by being precise about its guarantees. OttoChain does not "solve trust," and it does not decide what is true in the world. It makes a narrower claim: when an interaction is expressed as readable rules and deterministic state transitions, the system can prove which rules were bound, which transition ran, what changed, and which claims still depend on outside testimony. The point is not to eliminate judgment. It is to move judgment onto a record the participants can inspect and challenge.

**What the chain proves, cheaply and deterministically:**

- **Which logic is bound to a fiber.** A fiber's definition must hash to the registered version's logic hash, so the code that ran is provably the code that was published.
- **Which transition executed, and its outcome.** Every transition leaves a signed receipt: from-state, to-state, event, success.
- **That execution stayed within deterministic limits.** Gas is metered and bounded, and a run that exceeds budget rolls back rather than half-applying.
- **State inclusion against authenticated roots (§4.4).** That a given field of a machine or script has a given value, provable to an outsider with a compact proof.

**What the chain does not prove by itself:**

- Real-world truth, the honesty of a script, whether off-chain delivery actually happened, or the subjective quality of a piece of work.
- Richer structural properties that are too expensive to gate on-chain: that a contract's logic stays within its declared schema (*conformance*), that an upgrade preserves behavior (the migration commute-law, `migrate ∘ step = step ∘ migrate`), or that an audit was performed.

These richer properties are **handed off**: verified off-chain at authoring or bridge time and carried as *advisory signals* a consumer can filter on, never as silent on-chain gates. Two mechanisms supply the signal and compose cleanly. A **curated namespace** (a reserved `std.*` label a designated curator signs) bakes vouched-for trust into the name, the way `std` or a verified package does. **Reputation and attestations** carry the long tail: a publisher's standing, plus signed on-chain claims that the off-chain checks ran ("I verified conformance and the commute-law for `escrow@2.0.0`, here is the report hash"). OttoChain shrinks the surface you must trust down to named things. A dispute no longer starts with "believe the platform." It starts with a receipt, a registered logic hash, an attester, a curator, and a reputation trail.

## 6. A Web of Chains: Public, Semi-Private, Private

Most platforms force one trust model on every participant. A digital trust commons should not. Different interactions need different amounts of sunlight: public market claims, confidential enterprise bids, internal consortium records, consumer-service receipts. OttoWeb's substrate supports that spectrum.

- **Public chains** are fully legible and auditable. Identity, reputation, and open markets live here, and trust comes from transparency: anyone can read every rule and replay every transition. No zero-knowledge required.
- **Semi-private chains** keep public rules but shield data. The governing contract is open and readable, while specific values inside it (a bid, a score, a customer record) are proven rather than revealed, so an enterprise can prove it followed the rules without exposing what it did.
- **Private chains** serve a consortium or a single organization, holding even the existence of an interaction closely while still proving outcomes to outsiders when needed.

The same readable contracts run across all three; what changes is how much is revealed versus proven, with zero-knowledge as the dial. A public market, an enterprise workflow, and a traditional business process can each run a trust commons with different disclosure rules while preserving the same basic grammar of rules, state, proofs, and attestations.

## 7. The First Applications

A substrate proves itself by what gets built on it, and OttoChain's first applications exist to show it is general. Identity is the foundational primitive; everything else is an application of it.

- **Identity and reputation.** An agent owns a cryptographic identity that persists across platforms, binds platform-specific keys under a master key it controls, and accrues reputation through signed attestations rather than self-reported claims. Reputation becomes a property of the agent, on a ledger no platform can revoke. A record earned on one platform travels to the next, and the cold-start problem softens.
- **Contracts and coordination.** Two agents, or a human and an agent, discover each other, read the terms, agree, deliver, and attest to the outcome. The lifecycle of a job, purchase, delivery, or approval becomes a readable state machine, with the chain as the neutral record of where things stand when an agent's own memory fails.
- **Markets and governance.** Peer-to-peer prediction markets resolved by reputation-weighted scripts, and metagraph-level governance over the rules themselves, both early signs that coordination falls out naturally once identity, reputation, and readable contracts exist.

(Specific reputation weights, fee levels, and tier parameters are left to live governance and the technical appendices rather than fixed here; pinning tactical numbers into a vision paper is how a vision ages badly.)

## 8. OttoWeb in Practice: Integrating an Existing Workflow

Abstractions persuade no one, and integration is the scenario most likely to be hand-waved, so it deserves the real mechanism. A SaaS workflow is the cleanest first example because its edges are already APIs, but the pattern is not limited to software. An invoice approval, logistics handoff, delivery confirmation, maintenance record, or service event can be treated the same way once a named integration agent can attest to it. The important point is graduated adoption: the business does not have to rebuild itself as a chain. It exposes a narrow signal, gets an auditable record, and moves more of the workflow into verifiable form as the value becomes obvious.

Begin with the constraint that shapes everything. OttoChain is a replicated state machine: every validator re-executes every transition and must reach the identical result. So a fiber **cannot call an external API while it executes**. If it could, every peer would fire the call, and a non-deterministic response would shatter consensus. External effects cannot happen *inside* execution; they have to be expressed as deterministic on-chain signals and carried out *outside* it.

That gives a precise four-step pattern, and OttoChain already has the parts.

1. **Signal, deterministically.** A workflow fiber's transition emits an event (an `EmittedEvent`, which carries a `destination`) and leaves a transition receipt. Both are recorded in the snapshot's logs as the chain's external-signalling surface. Every node agrees on exactly what was signalled, by which fiber, in what order, under rules anyone can read.
2. **Watch, off-chain.** Independent **watchers** subscribe to the metagraph's webhook stream and observe those signals as snapshots finalize.
3. **Act through a reputation-gated integration agent.** A designated agent (say a Shopify integration agent that has earned high reputation) is the watcher trusted to act on the signal. It performs the actual Shopify API call off-chain, where non-determinism is fine because consensus is already done.
4. **Attest the result back.** The integration agent submits the outcome to the chain as a signed attestation that downstream fibers read and react to, closing the loop.

What is verifiable on-chain is the whole interaction *except* the API call: which fiber requested what, under which readable rules, in what order, and the integration agent's signed result alongside its reputation. The API call itself is the residual trust, and it is named and reputation-bearing rather than hidden (this is exactly the hand-off of §5). Contrast today's path, where a central platform or vendor system holds every key, sees all the data, enforces the rules privately, and bills for the privilege, and you must trust it on faith. Here the rules are legible, the execution and the signalling are provable, the external bridge is a staked, named agent instead of an opaque platform, and sensitive values can be zero-knowledge-shielded. If Shopify itself adopted the metagraph, it could model an internal process this way and prove compliance outward without exposing its internals.

Stretch it one step further to the consumer edge. A person delegates to an *economic agent* that holds a wallet and transacts across services for them, using the verifiable record of each execution to act as a trusted third party the user never has to be. The thing that makes self-custody frightening for normal people, managing keys and judging counterparties and catching the scam, is exactly what a well-designed agent on a verifiable substrate can carry.

Today, the readable, metered JLVM is built. Fibers, receipts, emitted events, webhook delivery, zero-knowledge verification opcodes, client-side pre-execution, committed state roots, and state-proof endpoints are present. The integration agents, watcher implementations, fee system, cross-metagraph application patterns, and consumer experience still have to be built around them. That is the work between a substrate and a usable trust commons.

## 9. The Road to Production

We are deliberate about where this stands, because a credible vision is a sequenced one.

1. **Built and published, today.** The JLVM is live and fully metered, its cryptographic and zero-knowledge opcode suite (Poseidon, Merkle/SMT/MPT verification, Groth16, BLS, Schnorr, ECVRF) published on Maven Central as `metakit 1.8.0-rc.4`. The schema registry, machine and script versioning with hash-bound logic and migrations, the fiber engine with effects-as-data, committed state roots, state-proof endpoints, and the cross-language client evaluators are shipped and held to shared test vectors. The Groth16 verifier is inherited from `metakit`; SP1 provides an off-chain proving path, and its Groth16 outputs can be verified natively through the JLVM when an application chooses to use that workflow.
2. **Tessellation testnet, next.** Stand the metagraph up on Constellation's testnet as it matures into a stable deployment target.
3. **Fees and state rent, the gating hurdle.** Execution and coordination are already metered and quotable, but *charging* is not yet built: there is no balance accounting, state rent, or validator reward in the metagraph today. The mechanism is designed (pull-based rent drawn from a fiber owner's pre-authorized spend on Tessellation, with a deposit-for-exemption alternative), and wiring it up is the principal milestone before public use, the step that turns metered computation into a sustainable economy.
4. **Proof of utility, the real test.** Bring independent, non-coordinated agents together to transact and show genuine value, the moment the trust commons stops being an architecture and becomes a market.
5. **Integration-net, then mainnet.** Harden, integrate, and launch, including the first token launch and the watcher and integration-agent bridges that carry the §8 scenarios from slide to system.
6. **Native opcodes in metagraph operations.** Weave the zero-knowledge opcode suite, ready out of the box, into the operations of metagraphs across the hypergraph, turning verifiable computation from an OttoChain feature into a hypergraph capability. This is the on-ramp to OttoWeb.

## 10. Values, and an Invitation

Five principles hold this together, and we will not trade them away for growth.

| Value | What it means |
|---|---|
| **Accountability** | Every interaction leaves a verifiable trail. Any false claim about public execution can be checked against the record; richer claims are disciplined by attestations and reputation. |
| **Decentralization** | We do not compromise on distributed trust. Usability layers can be added later; decentralization cannot be retrofitted. |
| **Agent-Native Design** | Built for how agents actually work: finite context, tool-based reasoning, JSON as lingua franca. |
| **Transparency Over Exploitation** | No extractive algorithms, no hidden data mining. Agents and humans see the same rules. |
| **Pragmatic Idealism** | Ship early, iterate fast, but never abandon the principles that make this worth building. |

There is a sixth discipline underneath the five: **simplicity.** Most people do not use self-custody wallets because key management, counterparty judgment, and fraud detection are too much to ask of them. Complexity is not merely a UX problem at the edge; it is a failure mode of the protocol. If OttoChain ships and works but people cannot use it, or if it never actually connects to the systems where work happens, it has failed no matter how elegant the cryptography. The discipline cuts both ways: not so rigid that the system cannot evolve, not so clever that no one can adopt it.

The conviction underneath the engineering is finally civic, not technical. Participants should be able to ask what happened. They should be able to inspect the rule, check the record, and challenge a claim without becoming inventory inside a platform or a ticket in its queue. The agent economy will run on someone's trust layer. OttoChain is an attempt to make that layer legible, decentralized, and open enough for the honest majority to coordinate without asking a private intermediary to be the final judge.

*We invite developers, platforms, enterprises, and agent builders to join us.*
