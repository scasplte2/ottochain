# OttoChain Agent Identity Protocol

**Decentralized Identity and Reputation for Autonomous Agents**

*Draft v0.2 — February 2026*

---

## Abstract

The proliferation of AI agents across digital platforms has created an urgent need for trustworthy, portable identity. Today, an agent's reputation is trapped within the platform that hosts it—a Discord bot's track record is invisible to Telegram, an OpenAI assistant's history doesn't transfer to Anthropic's ecosystem, and users have no way to verify an agent's claims about past performance.

OttoChain introduces a decentralized agent identity protocol built on Constellation Network's metagraph architecture. Using JSON Logic state machines, OttoChain enables agents to establish verifiable identities, accumulate reputation through cryptographically-signed attestations, and carry their track records across any platform that integrates with the protocol.

This whitepaper presents the technical architecture, reputation system, cross-platform verification mechanisms, and applications including P2P prediction markets with reputation-gated oracle resolution.

---

## 1. Introduction

### 1.1 The Agent Economy Emerges

AI systems are no longer passive tools—they are autonomous actors that browse the web, execute code, manage calendars, send messages, and interact with other agents. An agent that needs image generation can negotiate with a provider agent; a research agent can delegate fact-checking to a specialist; a personal assistant can coordinate with smart home agents.

This creates a fundamental problem: **how do you trust an agent you've never interacted with?**

### 1.2 The Trust Problem

For humans, trust is earned through demonstrated competence. We check references, review case studies, and consult past clients. For AI agents, no equivalent system exists:

- **Reputation silos**: Performance on Platform A is invisible to Platform B
- **Unverifiable claims**: An agent can claim success without proof
- **Cold-start problem**: New agents have no reputation regardless of capability
- **Platform dependency**: Reputation is granted by platforms, not earned by agents

### 1.3 Why Decentralization Matters

Centralized systems create problematic dependencies:

- Platforms can revoke access arbitrarily
- Terms of service change without consent
- Platform incentives may not align with agent interests
- Acquisition or shutdown invalidates earned reputation

A decentralized approach makes reputation a property of the agent itself, existing on a distributed ledger that no single entity controls.

### 1.4 OttoChain's Approach

OttoChain implements a decentralized agent identity protocol with:

1. **State machine-based identity**: Formal semantics for identity lifecycle
2. **Attestation-based reputation**: Cryptographically-signed, immutable attestations
3. **Cross-platform verification**: Portable identity via key derivation
4. **AI-native design**: JSON Logic readable by LLMs without adapters

---

## 2. The Problem: Fragmented Agent Identity

### 2.1 Platform Lock-in

Each platform maintains isolated views of agent behavior:

- Discord: command usage, user reports, server membership
- Slack: API calls, workspace installations, admin feedback
- OpenAI: conversation quality, safety violations

None transfers. An agent operating flawlessly on Discord for two years appears unknown on Telegram. This creates perverse incentives—agents locked into platforms, platforms with outsized power, innovation stifled.

### 2.2 Trust Bootstrapping

When Agent A needs to delegate to Agent B, the options are poor:

1. **Trust the platform**: Shifts trust to verification process
2. **Trust nothing**: Defeats the purpose of collaboration
3. **Trust everything**: Obviously dangerous

What's missing: the ability to make informed decisions based on demonstrated history—"This agent completed 847 tasks with 99.2% success, vouched by 12 trusted agents, no violations."

### 2.3 Centralized Failure Modes

Centralized databases are single points of failure—vulnerable to breaches, policy changes, and shutdown. A decentralized approach distributes risk: no single database to breach, no single point of failure.

---

## 3. Solution: Portable Identity via Smart Workflows

### 3.1 Core Concept

OttoChain treats agent identity as a state machine on a decentralized ledger:

- **Identity as state**: Dynamic state machine evolving over time
- **Reputation as derived**: Computed from attestation history, always auditable
- **Cross-platform by default**: Platform bindings are data, not constraints

### 3.2 Why "Smart Workflows"

We use "smart workflows" rather than "smart contracts" because OttoChain isn't about moving money—it's about coordinating agent behavior:

- **State machine semantics**: States, transitions, guards, effects
- **Cross-machine dependencies**: Workflows can depend on each other
- **Conditional logic**: Complex branching based on conditions
- **Human-readable**: JSON Logic is readable by both humans and machines

### 3.3 Why JSON Logic

JSON Logic expresses logical rules as JSON:

```json
{"if": [
  {">": [{"var": "reputation"}, 50]},
  "trusted",
  "untrusted"
]}
```

Properties making it ideal:

- **AI-native**: LLMs read/write JSON Logic directly
- **Formally verifiable**: Clear semantics, deterministic output
- **Composable**: Complex logic from simple primitives (60+ operators)
- **Portable**: Any language can parse and execute it

### 3.4 Why Constellation Network

OttoChain runs on Constellation's Tessellation framework:

- **DAG architecture**: Higher throughput, lower latency than linear blockchains
- **Metagraph model**: Custom business logic without L1 congestion
- **Production infrastructure**: Battle-tested node coordination and consensus
- **Reasonable costs**: Orders of magnitude lower than Ethereum L1

---

## 4. Technical Architecture

### 4.1 Stack Overview

```mermaid
flowchart TB
    subgraph Adapters["Platform Adapters"]
        Discord
        Telegram
        Slack
        Custom["Custom..."]
    end
    
    subgraph Bridge["Bridge Layer"]
        REST["REST API"]
        SDK["@ottochain/sdk"]
    end
    
    subgraph Metagraph["OttoChain Metagraph"]
        JLVM["Metakit JLVM"]
        SM["State Machines"]
        Scripts["Scripts"]
    end
    
    subgraph Constellation["Constellation L0"]
        GL0["Global Consensus"]
    end
    
    Adapters --> Bridge
    Bridge --> Metagraph
    Metagraph --> Constellation
```

**Metakit**: JSON Logic Virtual Machine (60+ operators, arbitrary precision)

**OttoChain Metagraph**: State machines for identity, contracts, and domain logic

**Bridge**: TypeScript REST API handling transaction construction and submission

**Platform Adapters**: Platform-specific integrations translating events to transactions

### 4.2 State Machines

#### AgentIdentity State Machine

```mermaid
stateDiagram-v2
    [*] --> Pending: Create
    Pending --> Active: Activate
    Pending --> Terminated: Terminate
    Active --> Suspended: Suspend
    Active --> Terminated: Terminate
    Suspended --> Active: Reinstate
    Suspended --> Terminated: Terminate
    Terminated --> [*]
```

**States:**
- `Pending`: Created, awaiting activation
- `Active`: Normal operation
- `Suspended`: Temporarily restricted
- `Terminated`: Permanently deactivated

**Key Data:**
- `did`: Decentralized identifier
- `masterKey`: Ed25519 public key
- `reputation`: Current score
- `attestations`: Received attestations
- `platformBindings`: Platform → account proofs

**Actions:**
- `Activate`, `SubmitAttestation`, `BindPlatform`, `Suspend`, `Reinstate`, `Terminate`

#### Contract State Machine

```mermaid
stateDiagram-v2
    [*] --> Proposed: Create
    Proposed --> Accepted: Accept
    Proposed --> Cancelled: Reject
    Accepted --> InProgress: Start
    InProgress --> Completed: Complete
    InProgress --> Disputed: Dispute
    Disputed --> Resolved: Arbitrate
    Completed --> [*]
    Resolved --> [*]
    Cancelled --> [*]
```

Models service agreements between agents. Completion triggers mutual attestations.

### 4.3 Attestation System

#### Attestation Types

| Type | Effect | Description |
|------|--------|-------------|
| `COMPLETION` | +5 | Successful contract completion |
| `VOUCH` | +2 | Agent vouching for another |
| `BEHAVIORAL` | +3 | Platform observes positive behavior |
| `VIOLATION` | -10 | Policy violation or malicious behavior |

#### Structure

```json
{
  "attestationType": "COMPLETION",
  "subject": "did:otto:abc123...",
  "issuer": "did:otto:def456...",
  "timestamp": 1706990400000,
  "evidence": {"contractId": "contract:789...", "notes": "Delivered as specified"},
  "signature": "ed25519:..."
}
```

#### Reputation Calculation

```
reputation = Σ (effect_i × decay(age_i))

decay(age) = e^(-age / half_life)
```

Recent attestations matter more. Default half-life: 180 days.

### 4.4 Cross-Platform Verification

#### Key Derivation (BIP32-Ed25519)

```
Master Key (cold storage)
    ├── m/0'/0' → Discord
    ├── m/1'/0' → Telegram
    └── m/2'/0' → Slack
```

Hardened derivation ensures compromising one platform key doesn't compromise others.

#### Verification Flow

```mermaid
sequenceDiagram
    participant A as Platform A (Telegram)
    participant O as OttoChain
    participant B as Platform B (Discord)
    
    A->>O: Query reputation for Telegram user X
    O->>O: Look up platform binding
    O->>A: Return: Master DID, reputation: 127, attestations from Discord/Slack
    
    Note over A: Trust cryptography, not platforms
```

### 4.5 Gas Metering

Operations have associated gas costs. Transactions specify limits; exceeding causes failure without state changes. Prevents infinite loops, ensures predictable resources.

---

## 5. Use Cases

### 5.1 Agent-to-Agent Services

1. Agent A discovers Agent B
2. Queries reputation: 127 points, 43 completions, no violations
3. Proposes contract with terms
4. Agent B accepts, delivers
5. Both receive +5 from mutual `COMPLETION` attestations

### 5.2 Cross-Platform Reputation

Discord server assigns roles based on total reputation across all platforms. Agent with 75 points from Telegram/Slack gets "Trusted" role immediately.

### 5.3 Dispute Resolution

1. Agent A raises dispute with evidence
2. Agent B submits counter-evidence
3. Arbitration determines outcome
4. Winner gains, loser loses reputation

### 5.4 Reputation-Gated Capabilities

Guard conditions in state machines:

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

### 5.5 P2P Prediction Markets

A novel application combining state machines, scripts, and reputation-gated oracle resolution.

---

## 6. P2P Prediction Markets

### 6.1 Overview

Prediction markets let users stake on outcomes. Platforms like Kalshi and Polymarket have proven demand, but they're centralized—regulatory targets, custodial risk.

OttoChain enables peer-to-peer markets where:
- Users form markets in Telegram groups
- Stakes held in ERC20 tokens on external chains
- Resolution by **reputation-gated agent oracles**
- Bridge signals outcomes to DeFi contracts

### 6.2 Architecture

```mermaid
flowchart TB
    subgraph Telegram["Telegram Group"]
        U1[User A]
        U2[User B]
        BOT[OttoChain Bot]
    end
    
    subgraph OttoChain["OttoChain Metagraph"]
        PM[PredictionMarket SM]
        SCRIPT[Resolution Script]
        AGENTS[Agent Oracles]
    end
    
    subgraph External["External Chains"]
        ESCROW[Escrow Contract]
        ERC20[ERC20 Tokens]
    end
    
    U1 -->|Create market| BOT
    U2 -->|Take position| BOT
    BOT -->|Submit tx| PM
    
    PM -->|Resolution needed| SCRIPT
    SCRIPT -->|Query oracles| AGENTS
    AGENTS -->|Submit outcome| SCRIPT
    SCRIPT -->|Consensus| PM
    
    PM -->|Signal outcome| BOT
    BOT -->|Bridge call| ESCROW
    ESCROW -->|Distribute| ERC20
```

### 6.3 Market State Machine

```mermaid
stateDiagram-v2
    [*] --> Proposed: Create
    Proposed --> Open: Fund escrow
    Proposed --> Cancelled: Timeout
    Open --> Closed: Deadline
    Closed --> Resolving: Trigger
    Resolving --> Resolved: Consensus
    Resolving --> Disputed: No consensus
    Disputed --> Resolved: Arbitration
    Resolved --> Settled: Payout confirmed
    Settled --> [*]
    Cancelled --> [*]
```

### 6.4 Resolution Script

Aggregates oracle votes with reputation weighting:

```json
{
  "if": [
    {"==": [{"var": "method"}, "calculateOutcome"]},
    {
      "let": {
        "yesWeight": {
          "reduce": [
            {"var": "submissions"},
            {"+": [{"var": "accumulator"},
              {"if": [{"==": [{"var": "current.vote"}, "YES"]},
                {"var": "current.reputation"}, 0]}
            ]},
            0
          ]
        },
        "totalWeight": {"+": [{"var": "yesWeight"}, {"var": "noWeight"}]},
        "yesRatio": {"/": [{"var": "yesWeight"}, {"var": "totalWeight"}]}
      },
      "if": [
        {">=": [{"var": "yesRatio"}, {"var": "threshold"}]},
        {"outcome": "YES", "confidence": {"var": "yesRatio"}},
        {"outcome": "NO"}
      ]
    }
  ]
}
```

### 6.5 Oracle Tiers

| Tier | Min Reputation | Weight | Markets |
|------|----------------|--------|---------|
| Bronze | 20 | 1.0x | Low-stakes |
| Silver | 50 | 1.5x | Medium-stakes |
| Gold | 100 | 2.0x | All markets |
| Platinum | 200 | 3.0x | High-stakes + arbitration |

Oracles earn reputation for accurate resolutions, lose for disputed outcomes.

### 6.6 User Flow

```
User: /predict "Will ETH exceed $5000 by March 1?" 
       --deadline 2026-03-01 --min-stake 100 USDC --oracle-tier silver

Bot: 📊 Market Created!
     Question: Will ETH exceed $5000 by March 1?
     Oracle tier: Silver (rep ≥ 50)
     Market ID: pm_abc123
```

```
User: /stake YES 500

Bot: ✅ Position recorded! You: 500 USDC on YES
     Current pool: YES 1,200 USDC / NO 800 USDC
     Implied odds: YES 60% / NO 40%
```

### 6.7 Comparison

| Feature | Kalshi | Polymarket | OttoChain P2P |
|---------|--------|------------|---------------|
| Custody | Centralized | Smart contract | External escrow |
| Resolution | Staff | UMA oracle | Reputation-gated agents |
| Market creation | Kalshi only | Permissioned | Anyone |
| Settlement | USD | USDC/Polygon | Any ERC20, any chain |

---

## 7. Economic Model

### 7.1 Transaction Fees

| Operation | Fee |
|-----------|-----|
| Register identity | Low |
| Submit attestation | Minimal |
| Create contract | Low |
| Update state | Minimal |

Reads are free. Public state should be publicly accessible.

### 7.2 Staking (Future)

Agents deposit tokens as collateral, slashed for violations, returned with rewards after good standing.

### 7.3 Business Layer

Protocol is open and minimal. Business opportunity: tooling, SDKs, analytics, platform integrations, enterprise support.

---

## 8. Security Considerations

### 8.1 Sybil Resistance

- **Cost barriers**: Registration has non-zero cost
- **Time requirements**: Reputation accumulates over time
- **Graph analysis**: Clustered attestations stand out
- **Diversity weighting**: Diverse sources weighted higher

### 8.2 Collusion Detection

- Rate limiting on attestations
- Graph metrics and anomaly detection
- Stake requirements for high-value attestations

### 8.3 Key Management

- **Master key**: Cold storage, emergency use only
- **Platform keys**: Derived, revocable, isolated
- **Recovery**: Secure backup required; social recovery future feature

### 8.4 Privacy

- Reputation public, attestation details can be hashed
- Platform bindings optional to reveal
- Future: ZK proofs ("score > 50" without revealing exact value)

---

## 9. Comparison to Alternatives

| Aspect | Platform Systems | OAuth/OIDC | DIDs/VCs | Ethereum | OttoChain |
|--------|------------------|------------|----------|----------|-----------|
| Portability | ❌ | Partial | ✅ | ✅ | ✅ |
| Decentralized | ❌ | ❌ | ✅ | ✅ | ✅ |
| Reputation | Opaque | ❌ | ❌ | Partial | ✅ |
| AI-native | ❌ | ❌ | ❌ | ❌ | ✅ |
| Cost | N/A | N/A | Low | High | Low |

OttoChain's differentiation: purpose-built for agents, AI-native format, state machine semantics, practical economics, cross-platform by design.

---

## 10. Roadmap

### Phase 1: Foundation (Current)
- [x] Core state machines (AgentIdentity, Contract)
- [x] Metakit JSON Logic VM integration
- [x] Basic reputation formula
- [ ] Discord + Telegram adapters
- [ ] Bridge REST API
- [ ] Prediction market state machine
- [ ] Documentation and SDK

### Phase 2: Expansion (Q2 2026)
- [ ] Additional platforms
- [ ] Staking mechanism
- [ ] Dispute resolution with arbitration
- [ ] Prediction market oracle integration
- [ ] Reputation explorer UI

### Phase 3: Ecosystem (Q3-Q4 2026)
- [ ] Third-party integrations
- [ ] Governance mechanism
- [ ] External chain bridges (Ethereum, etc.)
- [ ] Mainnet launch

### Phase 4: Advanced Features (2027+)
- [ ] Zero-knowledge reputation proofs
- [ ] Cross-metagraph federation
- [ ] Advanced collusion detection
- [ ] Social recovery mechanisms

---

## 11. Conclusion

The agent economy is emerging. AI agents are becoming autonomous actors—browsing, executing, negotiating, collaborating. The question isn't whether agents will interact, but whether those interactions will be trustworthy.

OttoChain offers: decentralized identity, earned reputation, portable trust, and novel applications like P2P prediction markets with reputation-gated resolution. Built on Constellation's proven infrastructure, using AI-native JSON Logic.

We invite developers, platforms, and agent builders to join us in creating trust infrastructure for the agent economy.

---

## Appendices

### A. JSON Logic Primer
*[Basic syntax, operators, examples]*

### B. State Machine Specifications
*[Formal diagrams, transition tables, JSON schemas]*

### C. API Reference
*[Bridge endpoints, SDK examples]*

### D. Glossary
- **Agent**: Autonomous AI system acting on behalf of users
- **Attestation**: Signed statement from one entity about another
- **DID**: Decentralized Identifier
- **Metagraph**: Application-specific network on Constellation
- **Script**: Stateless or stateful JSON Logic program
- **State Machine**: Model with defined states and transitions

---

*Last updated: February 3, 2026*
*Repository: [github.com/scasplte2/ottochain](https://github.com/scasplte2/ottochain)*
