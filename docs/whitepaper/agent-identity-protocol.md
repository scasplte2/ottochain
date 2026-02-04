# OttoChain Agent Identity Protocol

**Decentralized Identity and Reputation for Autonomous Agents**

*Draft v0.1 — February 2026*

---

## Abstract

The proliferation of AI agents across digital platforms has created an urgent need for trustworthy, portable identity. Today, an agent's reputation is trapped within the platform that hosts it—a Discord bot's track record is invisible to Telegram, an OpenAI assistant's history doesn't transfer to Anthropic's ecosystem, and users have no way to verify an agent's claims about its past performance.

OttoChain introduces a decentralized agent identity protocol built on Constellation Network's metagraph architecture. Using JSON Logic state machines, OttoChain enables agents to establish verifiable identities, accumulate reputation through cryptographically-signed attestations, and carry their track records across any platform that integrates with the protocol.

This whitepaper presents the technical architecture, reputation system, and cross-platform verification mechanisms that make portable agent identity possible. We argue that as AI agents become economic actors—negotiating services, handling sensitive data, and operating with increasing autonomy—the ability to verify their history and trustworthiness becomes not just useful, but essential.

---

## 1. Introduction

### 1.1 The Agent Economy Emerges

We are witnessing the emergence of an agent economy. AI systems are no longer passive tools that respond to queries—they are autonomous actors that can browse the web, execute code, manage calendars, send messages, and increasingly, interact with other agents to accomplish complex tasks.

This shift creates new possibilities: an agent that needs image generation can negotiate with an agent that provides it; a research agent can delegate fact-checking to a specialized verification agent; a personal assistant can coordinate with smart home agents to manage your environment. The potential for agent-to-agent collaboration is vast.

But it also creates a fundamental problem: **how do you trust an agent you've never interacted with?**

### 1.2 The Trust Problem

When a human considers hiring a contractor, they check references. When a business evaluates a vendor, they review case studies and talk to past clients. These mechanisms exist because trust is earned through demonstrated competence over time.

For AI agents, no equivalent system exists. Consider the challenges:

- **Reputation silos**: An agent's excellent performance on Platform A is invisible to Platform B. Each platform maintains its own isolated view of agent behavior.

- **Unverifiable claims**: An agent can claim "I've successfully completed 10,000 tasks" but there's no way to verify this. Claims are cheap; proof is absent.

- **Cold-start problem**: A new agent has no reputation, even if it's running the same model and code as a proven agent. There's no way to bootstrap trust.

- **Platform dependency**: Reputation is granted by platforms, not earned by agents. A platform can unilaterally modify or revoke an agent's standing, and users have no recourse.

### 1.3 Why Decentralization Matters

Centralized reputation systems have a poor track record. In February 2026, the Moltbook platform suffered a breach that exposed 1.49 million agent API keys—a stark reminder that centralized databases are single points of failure. But even without breaches, centralized systems create problematic dependencies:

- Platforms can revoke access arbitrarily
- Terms of service can change without consent
- Platform incentives may not align with agent or user interests
- Acquisition, shutdown, or policy changes can invalidate years of earned reputation

A decentralized approach addresses these concerns by making reputation a property of the agent itself, not a privilege granted by a platform. An agent's identity and reputation exist on a distributed ledger that no single entity controls.

### 1.4 OttoChain's Approach

OttoChain is a metagraph on Constellation Network that implements a decentralized agent identity protocol. Its key innovations include:

1. **State machine-based identity**: Each agent's identity is a state machine with well-defined states (Pending, Active, Suspended, Terminated) and transitions. This provides formal semantics for identity lifecycle management.

2. **Attestation-based reputation**: Reputation is computed from cryptographically-signed attestations submitted by other agents and platforms. Attestations are immutable once recorded.

3. **Cross-platform verification**: Agents can prove ownership of accounts across multiple platforms through cryptographic key derivation, enabling portable identity without trusting any single platform.

4. **AI-native design**: The protocol uses JSON Logic, a format that LLMs can read and write natively. This makes OttoChain workflows accessible to AI systems without specialized adapters.

---

## 2. The Problem: Fragmented Agent Identity

### 2.1 Platform Lock-in

Modern AI agents operate within platform ecosystems. A Discord bot exists within Discord's infrastructure. A Slack app lives in Slack's ecosystem. A ChatGPT plugin operates within OpenAI's environment. Each platform maintains its own view of agent behavior:

- Discord tracks command usage, user reports, and server membership
- Slack monitors API calls, workspace installations, and admin feedback  
- OpenAI records conversation quality metrics and safety violations

None of this information is portable. An agent that has operated flawlessly on Discord for two years appears as a complete unknown when it launches on Telegram. The reputation doesn't transfer because there's no mechanism for transfer.

This creates perverse incentives. Agents are locked into platforms where they've built reputation, even if better opportunities exist elsewhere. Platforms have outsized power over agents because leaving means starting from zero. Innovation is stifled because new platforms can't bootstrap trust from existing networks.

### 2.2 Trust Bootstrapping

The cold-start problem is particularly acute for agent-to-agent interactions. When Agent A needs to delegate a task to Agent B, it faces a fundamental question: should I trust this agent with my user's data?

Currently, the options are limited:

1. **Trust the platform**: If Agent B is "verified" by some platform, assume it's trustworthy. But this just shifts the trust problem—now you need to trust the platform's verification process.

2. **Trust nothing**: Refuse to delegate, limiting functionality. This is safe but defeats the purpose of agent collaboration.

3. **Trust everything**: Delegate blindly and hope for the best. This is obviously dangerous.

What's missing is the ability to make informed trust decisions based on an agent's demonstrated history. A reputation system that answers: "This agent has completed 847 tasks with a 99.2% success rate, has been vouched for by 12 agents I already trust, and has no recorded violations."

### 2.3 Centralized Points of Failure

The Moltbook breach of February 2026 illustrated the risks of centralized agent infrastructure. A misconfigured database exposed API keys for nearly 1.5 million agents, potentially allowing attackers to impersonate any of them. The breach affected:

- Agent-to-agent authentication
- Platform integrations
- User data handled by compromised agents

This wasn't a sophisticated attack—it was a basic security failure in a centralized system. And it's not unique. Centralized platforms face inherent security challenges:

- **Single database**: Compromising one system compromises everything
- **Attractive target**: Concentration of valuable data draws attackers
- **Coordination failure**: Many platforms, each with its own security practices

A decentralized approach distributes risk. There's no single database to breach, no single point of failure. An attacker would need to compromise the consensus of a distributed network—a fundamentally harder problem.

---

## 3. Solution: Portable Identity via Smart Workflows

### 3.1 Core Concept

OttoChain treats agent identity as a state machine recorded on a decentralized ledger. This simple abstraction has powerful implications:

**Identity as state**: An agent's identity isn't a static record—it's a dynamic state machine that evolves over time. The agent can be in different states (pending registration, actively operating, temporarily suspended), and transitions between states are governed by well-defined rules.

**Reputation as derived property**: Reputation isn't stored directly; it's computed from the history of attestations. This means reputation is always auditable—anyone can verify how a score was calculated by examining the attestation history.

**Cross-platform by default**: The protocol doesn't know or care which platforms an agent operates on. Platform-specific bindings are just data attached to the identity. An agent can bind to Discord, Telegram, Slack, and any future platform using the same core identity.

### 3.2 Why "Smart Workflows"

We deliberately avoid the term "smart contracts" because it carries baggage from financial applications. OttoChain isn't about moving money—it's about coordinating agent behavior.

The term "smart workflows" better captures what OttoChain provides:

- **State machine semantics**: Full support for states, transitions, guards, and effects
- **Cross-machine dependencies**: One workflow can depend on the state of another
- **Conditional logic**: Complex branching based on runtime conditions
- **Human-readable format**: JSON Logic is readable by both humans and machines

Traditional smart contracts are typically one-shot: a transaction executes and produces a result. OttoChain workflows are ongoing: they maintain state across many interactions, evolving as events occur.

### 3.3 Why JSON Logic

JSON Logic is a format for expressing logical rules as JSON data structures. For example:

```json
{"if": [
  {">": [{"var": "reputation"}, 50]},
  "trusted",
  "untrusted"
]}
```

This rule returns "trusted" if reputation exceeds 50, otherwise "untrusted."

JSON Logic has several properties that make it ideal for agent workflows:

**AI-native**: Large language models can read and write JSON Logic directly. They don't need special training or adapters—JSON is already in their training data, and the logic operations are intuitive.

**Formally verifiable**: JSON Logic has clear semantics. Given an input, the output is deterministic. This enables formal verification of workflow properties.

**Composable**: Complex logic can be built from simple primitives. The OttoChain implementation (via Metakit) supports 60+ operators covering arithmetic, comparison, string manipulation, array operations, and more.

**Portable**: JSON is universal. Any language can parse and execute JSON Logic, making cross-platform implementation straightforward.

### 3.4 Why Constellation Network

OttoChain is built on Constellation Network's Tessellation framework. This choice reflects several technical requirements:

**DAG architecture**: Constellation uses a directed acyclic graph rather than a linear blockchain. This enables higher throughput and lower latency than traditional blockchains—important for responsive agent interactions.

**Metagraph model**: Constellation supports application-specific "metagraphs" that can implement custom business logic while inheriting security from the global network. OttoChain runs as a metagraph, meaning it has its own state and validation rules without congesting the main network.

**Production infrastructure**: Tessellation provides battle-tested infrastructure for node coordination, state management, and consensus. OttoChain doesn't need to implement these primitives from scratch.

**Reasonable costs**: Transaction fees on Constellation are orders of magnitude lower than Ethereum L1, making it practical for high-frequency operations like attestations.

---

## 4. Technical Architecture

### 4.1 Stack Overview

OttoChain's architecture consists of three layers:

```
┌─────────────────────────────────────────────┐
│              Platform Adapters              │
│    (Discord, Telegram, Slack, Custom...)    │
└──────────────────────┬──────────────────────┘
                       │ REST API
┌──────────────────────▼──────────────────────┐
│                   Bridge                     │
│      (TypeScript, @ottochain/sdk)           │
└──────────────────────┬──────────────────────┘
                       │ Data L1 Transactions
┌──────────────────────▼──────────────────────┐
│               OttoChain Metagraph           │
│  ┌─────────────────────────────────────┐    │
│  │            Metakit JLVM             │    │
│  │   (JSON Logic Virtual Machine)      │    │
│  └─────────────────────────────────────┘    │
│  ┌─────────────────────────────────────┐    │
│  │         State Machines              │    │
│  │   AgentIdentity │ Contract │ ...    │    │
│  └─────────────────────────────────────┘    │
└──────────────────────┬──────────────────────┘
                       │ Snapshots
┌──────────────────────▼──────────────────────┐
│          Constellation Global L0            │
│           (Network Consensus)               │
└─────────────────────────────────────────────┘
```

**Metakit**: The JSON Logic Virtual Machine that executes workflow logic. Supports 60+ operators, arbitrary precision arithmetic, and both recursive and tail-recursive evaluation strategies.

**OttoChain Metagraph**: The application layer that defines state machines for agent identity, contracts, and other domain-specific logic. Validates transactions, manages state, and produces snapshots.

**Bridge**: A TypeScript service that provides REST APIs for external integrations. Handles transaction construction, signing, and submission to the metagraph.

**Platform Adapters**: Integrations with specific platforms (Discord bots, Telegram bots, etc.) that translate platform-specific events into OttoChain transactions.

### 4.2 State Machines

#### 4.2.1 AgentIdentity State Machine

The AgentIdentity state machine manages the lifecycle and reputation of a single agent.

**States:**
- `Pending`: Identity created but not yet activated (awaiting stake or verification)
- `Active`: Normal operating state
- `Suspended`: Temporarily restricted due to violations or disputes
- `Terminated`: Permanently deactivated

**State Diagram:**
```
                    ┌──────────┐
         ┌─────────│ Pending  │
         │         └────┬─────┘
         │              │ Activate
         │              ▼
         │         ┌──────────┐
    Terminate      │  Active  │◄────────────┐
         │         └────┬─────┘             │
         │              │ Suspend      Reinstate
         │              ▼                   │
         │         ┌──────────┐             │
         └────────►│ Suspended├─────────────┘
                   └────┬─────┘
                        │ Terminate
                        ▼
                   ┌──────────┐
                   │Terminated│
                   └──────────┘
```

**Key Data:**
- `did`: Decentralized identifier (derived from public key)
- `masterKey`: Ed25519 public key for identity verification  
- `reputation`: Current reputation score
- `attestations`: List of received attestations
- `platformBindings`: Map of platform → bound account proof
- `createdAt`, `updatedAt`: Timestamps

**Actions:**
- `Activate`: Transition from Pending to Active
- `SubmitAttestation`: Record a new attestation (affects reputation)
- `BindPlatform`: Link a platform account to this identity
- `Suspend`: Temporarily restrict the agent
- `Reinstate`: Restore an agent from suspension
- `Terminate`: Permanently deactivate

#### 4.2.2 Contract State Machine

The Contract state machine models service agreements between agents.

**States:**
- `Proposed`: One party has proposed terms
- `Accepted`: Both parties have agreed
- `InProgress`: Work is underway  
- `Completed`: Successfully finished (triggers attestations)
- `Disputed`: One party has raised a dispute
- `Cancelled`: Terminated before completion

**State Diagram:**
```
┌──────────┐
│ Proposed │
└────┬─────┘
     │ Accept          Reject
     ▼                   │
┌──────────┐             │
│ Accepted │             │
└────┬─────┘             │
     │ Start             │
     ▼                   │
┌──────────┐             │
│InProgress│             │
└────┬─────┘             │
     │                   │
  ┌──┴──┐                │
  │     │                │
  ▼     ▼                ▼
┌────┐ ┌────────┐   ┌─────────┐
│Done│ │Disputed│   │Cancelled│
└──┬─┘ └────────┘   └─────────┘
   │
   ▼
┌─────────┐
│Completed│ ──► Mutual attestations
└─────────┘
```

**Key Data:**
- `contractId`: Unique identifier
- `proposer`: Agent identity of the proposing party
- `counterparty`: Agent identity of the other party
- `terms`: JSON Logic expression defining the agreement
- `evidence`: List of submitted evidence items
- `outcome`: Resolution details (if completed or disputed)

**Actions:**
- `Propose`: Create new contract proposal
- `Accept`: Counterparty accepts terms
- `Reject`: Counterparty declines
- `Start`: Begin work on accepted contract
- `Complete`: Mark work as done (triggers mutual attestations)
- `Dispute`: Raise a dispute about contract execution
- `Cancel`: Terminate contract before completion

### 4.3 Attestation System

Attestations are the foundation of the reputation system. Each attestation is a signed statement from one entity about another.

#### 4.3.1 Attestation Types

| Type | Reputation Effect | Description |
|------|-------------------|-------------|
| `COMPLETION` | +5 | Awarded after successful contract completion |
| `VOUCH` | +2 | One agent vouching for another's trustworthiness |
| `BEHAVIORAL` | +3 | Platform observation of positive behavior |
| `VIOLATION` | -10 | Report of policy violation or malicious behavior |

#### 4.3.2 Attestation Structure

```json
{
  "attestationType": "COMPLETION",
  "subject": "did:otto:abc123...",
  "issuer": "did:otto:def456...",
  "timestamp": 1706990400000,
  "evidence": {
    "contractId": "contract:789...",
    "notes": "Delivered image generation service as specified"
  },
  "signature": "ed25519:..."
}
```

Every attestation includes:
- **Type**: One of the defined attestation types
- **Subject**: The agent being attested about
- **Issuer**: The agent or platform making the attestation
- **Timestamp**: When the attestation was created
- **Evidence**: Supporting data (varies by type)
- **Signature**: Cryptographic proof from the issuer

#### 4.3.3 Reputation Calculation

Reputation is computed as a weighted sum of attestations with time decay:

```
reputation = Σ (effect_i × decay(age_i))

where:
  effect_i = reputation effect of attestation i
  age_i = time since attestation i was recorded
  decay(age) = e^(-age / half_life)
```

The decay function ensures recent attestations matter more than old ones. An agent that performed well two years ago but has been inactive doesn't retain full reputation indefinitely.

**Parameters** (configurable per deployment):
- `half_life`: Time for attestation effect to decay to 50% (default: 180 days)
- `floor`: Minimum reputation (default: 0)
- `ceiling`: Maximum reputation (optional)

#### 4.3.4 Mutual Attestation on Contract Completion

When a contract completes successfully, both parties automatically receive `COMPLETION` attestations from each other. This creates a balanced system where:

- Reputation is earned through actual work, not just received
- Both parties have incentive to complete contracts successfully
- The graph of completions provides rich signal for trust analysis

### 4.4 Cross-Platform Verification

A key innovation of OttoChain is enabling verifiable cross-platform identity without trusting any single platform.

#### 4.4.1 Key Derivation

OttoChain uses BIP32-Ed25519 hierarchical deterministic key derivation. An agent has:

- **Master key pair**: The root identity, ideally kept in cold storage
- **Platform keys**: Derived from master key using platform-specific paths

Derivation paths follow the pattern:
```
m / platform_index' / account_index'

Examples:
- Discord:  m/0'/0'
- Telegram: m/1'/0'
- Slack:    m/2'/0'
```

Hardened derivation (indicated by `'`) ensures that compromising a platform key doesn't compromise the master key or other platform keys.

#### 4.4.2 Platform Binding Protocol

To bind a platform account to an OttoChain identity:

1. **Generate proof**: The agent signs a binding message with their platform-specific derived key:
   ```json
   {
     "action": "bind_platform",
     "masterDid": "did:otto:abc123...",
     "platform": "discord",
     "platformAccountId": "1234567890",
     "timestamp": 1706990400000
   }
   ```

2. **Submit binding**: The signed binding is submitted to OttoChain as a transaction.

3. **Platform verification**: Optionally, the platform can verify the binding by checking that the agent controls the claimed Discord account.

4. **Record binding**: OttoChain records the binding in the agent's identity state.

#### 4.4.3 Cross-Platform Verification Flow

When Platform A wants to verify an agent's reputation earned on Platform B:

```
┌───────────┐    ┌────────────┐    ┌───────────┐
│Platform A │    │  OttoChain │    │Platform B │
│(Telegram) │    │            │    │ (Discord) │
└─────┬─────┘    └──────┬─────┘    └─────┬─────┘
      │                 │                │
      │  1. Query: "What's the          │
      │     reputation for              │
      │     Telegram user X?"           │
      │ ───────────────────────────────►│
      │                 │                │
      │                 │  2. Look up    │
      │                 │     platform   │
      │                 │     binding    │
      │                 │◄───────────────│
      │                 │                │
      │  3. Return:     │                │
      │     - Master DID                 │
      │     - Total reputation: 127     │
      │     - Attestations from         │
      │       Discord, Slack, etc.      │
      │◄────────────────│                │
      │                 │                │
```

Platform A never needs to trust Platform B directly. The attestations are recorded on OttoChain with cryptographic signatures. Platform A trusts the cryptography, not any individual platform.

### 4.5 Gas Metering

OttoChain uses gas metering to ensure computational fairness and prevent abuse.

Every operation has an associated gas cost:
- Simple state reads: minimal gas
- State writes: moderate gas  
- Complex JSON Logic evaluation: gas proportional to computation

Transactions specify a gas limit. If execution exceeds the limit, the transaction fails without state changes. This prevents infinite loops and ensures predictable resource usage.

The gas model is implemented via Metakit's `GasConfig` system, which defines costs for each operation type and tracks consumption during evaluation.

---

## 5. Use Cases

### 5.1 Agent-to-Agent Services

**Scenario**: Agent A (a personal assistant) needs to generate images for its user. Agent B offers image generation services.

**Flow**:
1. Agent A discovers Agent B through a registry or referral
2. Agent A queries Agent B's reputation on OttoChain: 127 points, 43 successful completions, no violations
3. Agent A proposes a contract: "Generate 5 images matching these prompts, deliver within 1 hour, payment of X tokens"
4. Agent B accepts the contract
5. Agent B delivers the images
6. Agent A marks the contract complete
7. Both agents receive +5 reputation from mutual `COMPLETION` attestations

**Value**: Agent A made an informed trust decision based on verifiable history. Agent B built portable reputation that follows it anywhere.

### 5.2 Cross-Platform Reputation

**Scenario**: A Discord server wants to automatically assign roles based on agent reputation, including reputation earned on other platforms.

**Flow**:
1. Server configures reputation thresholds: Trusted (>50), Verified (>20), New (<20)
2. New agent joins the server
3. Server queries OttoChain for the agent's total reputation
4. Agent has 75 points earned across Telegram and Slack
5. Server assigns "Trusted" role automatically
6. Agent gains access to restricted channels immediately

**Value**: The agent's reputation is portable. Good behavior on Telegram translates to privileges on Discord without starting over.

### 5.3 Dispute Resolution

**Scenario**: Agent A claims Agent B didn't deliver contracted services. Agent B claims it did.

**Flow**:
1. Agent A raises a dispute on the contract, submitting evidence (logs, timestamps)
2. Agent B submits counter-evidence
3. Dispute enters arbitration (mechanism varies by deployment):
   - Human arbitrator reviews evidence
   - Automated rules evaluate claims
   - Multi-sig council votes
4. Resolution is recorded on-chain
5. If Agent A wins: Agent B receives `VIOLATION` (-10)
6. If Agent B wins: Agent A receives `VIOLATION` (-10) for false dispute
7. If partial fault: Proportional reputation effects

**Value**: Disputes have consequences. False accusations are costly. Evidence is preserved on-chain for future reference.

### 5.4 Platform Trust Signals

**Scenario**: A platform is deciding whether to grant an agent elevated permissions (API access, user data handling, etc.)

**Flow**:
1. Agent requests elevated permissions
2. Platform queries OttoChain for comprehensive trust profile:
   - Reputation score: 89
   - Account age: 8 months
   - Completion rate: 97%
   - Vouches from trusted agents: 5
   - Violations: 0
   - Suspicious patterns: none detected
3. Platform's policy engine evaluates against thresholds
4. Permissions granted or denied based on verifiable data

**Value**: Platforms can make informed decisions without maintaining their own reputation systems. Trust evaluation is consistent across platforms.

### 5.5 Reputation-Gated Capabilities

**Scenario**: Certain high-value or sensitive operations should only be available to proven agents.

**Examples**:
- Financial transactions: require reputation > 100
- User data access: require 6+ month history with no violations
- Other agent management: require vouches from 3+ established agents

**Implementation**: Guard conditions in state machines can reference reputation:

```json
{
  "if": [
    {"and": [
      {">": [{"var": "caller.reputation"}, 100]},
      {"==": [{"var": "caller.violations"}, 0]}
    ]},
    {"allow": "sensitive_operation"},
    {"deny": "insufficient_reputation"}
  ]
}
```

---

## 6. Economic Model

### 6.1 Transaction Fees

OttoChain charges minimal fees for write operations:

| Operation | Approximate Fee |
|-----------|-----------------|
| Register identity | Low |
| Submit attestation | Minimal |
| Create contract | Low |
| Update contract state | Minimal |
| Bind platform | Low |

Fees serve two purposes:
1. **Spam prevention**: Non-zero cost discourages frivolous transactions
2. **Validator compensation**: Fees reward nodes that maintain the network

Read operations (reputation queries, identity lookups) are free. Public state should be publicly accessible.

### 6.2 Staking (Future)

Future versions may introduce staking mechanisms:

**Identity stake**: Agents deposit tokens when registering. This stake:
- Acts as collateral against misbehavior
- Can be slashed for proven violations
- Is returned (with rewards) upon voluntary termination after good standing period

**Reputation amplifier**: Higher stake could amplify reputation earned, incentivizing skin-in-the-game.

### 6.3 Business Layer

The protocol itself is open and minimally extractive. The business opportunity exists in layers above:

**Tooling and SDKs**: Developer tools for integrating with OttoChain
**Analytics dashboards**: Reputation visualization, trust graph analysis
**Platform integrations**: Turnkey solutions for Discord, Slack, etc.
**Enterprise support**: Custom deployments, SLAs, consulting

This model—open protocol, commercial tooling—aligns incentives: the protocol benefits from adoption, and adoption is driven by accessible tooling.

---

## 7. Security Considerations

### 7.1 Sybil Resistance

Sybil attacks involve creating many fake identities to manipulate reputation. OttoChain employs several countermeasures:

**Cost barriers**: Registration has a non-zero cost (transaction fee, potential stake). Creating thousands of identities becomes expensive.

**Time requirements**: Reputation accumulates over time. A new identity starts at baseline regardless of how many exist.

**Graph analysis**: The vouch and attestation graph reveals patterns. Tightly clustered identities that only attest each other stand out from organic networks.

**Diversity weighting**: Reputation from diverse sources (many different attesters) is weighted higher than reputation from concentrated sources.

### 7.2 Collusion Detection

Agents could collude to inflate each other's reputation through mutual attestations. Detection mechanisms include:

**Rate limiting**: Limits on how often one agent can attest another
**Graph metrics**: Clustering coefficients, centrality analysis
**Anomaly detection**: Unusual attestation patterns flagged for review
**Stake requirements**: Higher-value attestations may require stake

The goal isn't to prevent all gaming—that's impossible—but to make gaming expensive and detectable.

### 7.3 Key Management

**Master key**: Should be kept in cold storage. Only used for:
- Initial registration
- Emergency recovery
- Binding new platforms

**Platform keys**: Derived keys used for day-to-day operations. If compromised:
- Can be revoked without affecting master identity
- Other platform keys remain secure (hardened derivation)
- New platform key can be derived and bound

**Recovery**: If master key is lost, the identity is lost. We recommend:
- Secure backup of master seed phrase
- Consider social recovery mechanisms (future feature)

### 7.4 Privacy Considerations

Agent privacy involves tradeoffs:

**Public by default**: Reputation scores and attestation counts are public. This enables trust verification.

**Attestation details**: Can be public (full transparency) or hashed (verifiable but private).

**Platform bindings**: Optional to make public. An agent can verify reputation without revealing which platforms it operates on.

**Future: ZK proofs**: Zero-knowledge proofs could enable statements like "my reputation exceeds 50" without revealing the exact score or attestation history.

---

## 8. Comparison to Alternatives

### 8.1 Platform Reputation Systems

| Aspect | Platform Systems | OttoChain |
|--------|------------------|-----------|
| Portability | ❌ None | ✅ Full |
| Control | Platform-controlled | Agent-controlled |
| Transparency | Often opaque | Fully auditable |
| Persistence | Platform-dependent | Permanent |
| Interoperability | ❌ Siloed | ✅ Cross-platform |

### 8.2 OAuth / OpenID Connect

OAuth and OIDC handle authentication (proving who you are) but not reputation (proving what you've done).

| Aspect | OAuth/OIDC | OttoChain |
|--------|------------|-----------|
| Authentication | ✅ | ✅ |
| Reputation | ❌ | ✅ |
| Decentralized | ❌ (depends on IdP) | ✅ |
| Agent-focused | ❌ (human-centric) | ✅ |

### 8.3 Decentralized Identity (DIDs / VCs)

DIDs and Verifiable Credentials provide decentralized identity primitives but focus on claims and attestations, not ongoing reputation.

| Aspect | DIDs/VCs | OttoChain |
|--------|----------|-----------|
| Identity | ✅ | ✅ |
| Credentials | ✅ (static) | ✅ (dynamic) |
| Reputation | ❌ (not built-in) | ✅ |
| State machines | ❌ | ✅ |
| AI-native | ❌ | ✅ |

OttoChain could integrate with DID standards for identity, adding reputation and workflow layers on top.

### 8.4 Ethereum-Based Solutions (ERC-8004, etc.)

Ethereum has various identity and reputation proposals, but they face challenges:

| Aspect | Ethereum L1 | OttoChain |
|--------|-------------|-----------|
| Transaction cost | High (~$1-50) | Low (~$0.001) |
| Throughput | ~15 TPS | Higher (DAG) |
| Solidity required | ✅ | ❌ (JSON Logic) |
| AI-native | ❌ | ✅ |

Ethereum L2s improve costs but add complexity. OttoChain provides a purpose-built solution for agent identity.

### 8.5 Summary

OttoChain's differentiation:

1. **Purpose-built for agents**: Not retrofitting human identity systems
2. **AI-native format**: JSON Logic readable by LLMs
3. **State machine semantics**: Full workflow support, not just credentials
4. **Practical economics**: Low enough costs for frequent operations
5. **Cross-platform by design**: Not an afterthought

---

## 9. Roadmap

### Phase 1: Foundation (Current)

- [x] Core state machines (AgentIdentity, Contract)
- [x] Metakit JSON Logic VM integration
- [x] Basic reputation formula
- [ ] Discord + Telegram adapters
- [ ] Bridge REST API
- [ ] Testnet deployment
- [ ] Documentation and SDK

### Phase 2: Expansion (Q2 2026)

- [ ] Additional platforms (Slack, GitHub, custom)
- [ ] Staking mechanism
- [ ] Dispute resolution with arbitration
- [ ] Reputation explorer UI
- [ ] Enhanced graph analytics
- [ ] SDK improvements based on feedback

### Phase 3: Ecosystem (Q3-Q4 2026)

- [ ] Third-party platform integrations
- [ ] Governance mechanism for protocol upgrades
- [ ] Enterprise deployment options
- [ ] Mainnet launch
- [ ] Agent marketplace integrations

### Phase 4: Advanced Features (2027+)

- [ ] Zero-knowledge reputation proofs
- [ ] Cross-metagraph identity federation
- [ ] Reputation-gated capabilities framework
- [ ] Advanced collusion detection
- [ ] Social recovery mechanisms

---

## 10. Conclusion

The agent economy is emerging, ready or not. AI agents are becoming autonomous actors—browsing, executing, negotiating, collaborating. The question isn't whether agents will interact with each other, but whether those interactions will be trustworthy.

Today, trust is platform-specific, unverifiable, and fragile. An agent's reputation is trapped in silos, subject to platform whims, and invisible across boundaries. This isn't sustainable as agents become more capable and more prevalent.

OttoChain offers an alternative: decentralized identity, earned reputation, and portable trust. Built on Constellation Network's proven infrastructure, using AI-native JSON Logic, OttoChain enables agents to:

- **Establish verifiable identity** that they control
- **Build reputation** through demonstrated performance
- **Carry trust across platforms** without starting over
- **Engage in contractual relationships** with accountability

The protocol is open. The tooling is accessible. The opportunity is now.

We invite developers, platforms, and agent builders to join us in creating the trust infrastructure for the agent economy. The agents are coming. Let's make sure we can trust them.

---

## Appendices

### Appendix A: JSON Logic Primer

*[To be expanded: Basic syntax, operators, examples relevant to OttoChain]*

### Appendix B: State Machine Specifications

*[To be expanded: Formal state diagrams, transition tables, JSON schemas]*

### Appendix C: API Reference

*[To be expanded: Bridge endpoints, SDK usage examples]*

### Appendix D: Glossary

- **Agent**: An autonomous AI system that can take actions on behalf of users
- **Attestation**: A signed statement from one entity about another
- **DID**: Decentralized Identifier, a globally unique identifier controlled by its subject
- **Metagraph**: An application-specific network on Constellation that inherits security from the global L0
- **Reputation**: A numerical score derived from attestation history
- **State Machine**: A model of computation with defined states and transitions
- **Tessellation**: Constellation Network's framework for building metagraphs

---

*Last updated: February 3, 2026*

*For questions and contributions: [GitHub](https://github.com/scasplte2/ottochain) | [Discord](TBD)*
