# Trust & verification hand-off

## The problem

The chain is deliberately agnostic. It verifies only what it can check cheaply and deterministically:

- hash commitments (`schemaHash`, `logicHash`),
- append-only / immutable / strictly-monotonic version lineage,
- ownership (signing proofs),
- **verified binding** — `fiber.definition.computeDigest == version.logicHash` (#37).

A second class of properties is *richer* and **cannot** be verified on-chain cheaply, so the chain
**hands their verification off** to authoring/Bridge time and trusts an expectation:

| Property | Why it can't be a cheap on-chain gate | Where it's verified |
|---|---|---|
| **Conformance** (logic ⊆ schema, see `strong-typing-and-conformance.md` §0.5) | needs protobuf descriptor parsing + static analysis of the JSON-Logic | Bridge / authoring (opt-in) |
| **Migration commute-law** (`migrate ∘ step = step ∘ migrate`, #27) | needs running *both* logic versions over all inputs | commute-law test-kit (offline) |
| **SchemaShape ↔ descriptor** (the on-chain shape faithfully projects the proto) | needs descriptor parsing | Bridge (re-derivable) |

Each of these is recorded **in code** at the hand-off point as "verified off-chain by expectation," and
each `PublishVersion` / `UpgradeFiber` that accepts such a property **without** verifying it must say so.

## Why this needs a trust layer

If the chain accepts a property it can't enforce, a publisher can *claim* it falsely. The chain stays
correct (its cheap invariants still hold — a lying publisher can't forge a `logicHash` or break
append-only), but a **consumer** choosing `name@latest` has no on-chain signal of whether the off-chain
verification actually happened. That signal is the trust layer. It never becomes a chain-enforced gate on
agnostic content — it is **advisory metadata a consumer can filter on**.

## Two complementary mechanisms

### A. Reserved / curated namespace (a TLD like `std`)

A reserved top-level label — `std.*` (cf. Rust `std`, npm scoped/verified packages) — that only a
**designated curator** may publish under. Publishing under `std.` asserts the program passed the full
off-chain battery (conformance + commute-law + audit). The trust is *baked into the name*: `std.escrow` is
legible to a human and an agent as "vouched-for." Cheap and concrete: it's an authorization rule on
`RegistryName` (reserved labels require the curator's signature), plus a documented expectation.

- **Pros:** simple, legible, immediate; human- and agent-readable audit trail.
- **Cons:** centralizes the vouching authority (who is the curator?); doesn't scale to the long tail.

### B. Reputation

Publishers and programs carry **reputation** — and the ecosystem's `identity` app already has a
`Reputation` field (AGENT / ORACLE / SERVICE). A version gains trust from: the publisher's reputation,
on-chain **attestations** that the off-chain verification ran (a signed claim "I checked conformance +
commute-law for `escrow@2.0.0`, here's the report hash"), successful upgrade history, and audits.

- **Pros:** decentralized, earned, scales to the long tail; no single curator.
- **Cons:** needs the identity-app integration + an attestation flow; bootstrapping (cold-start) is harder.

### How they compose

`std`/curated = strong, governance-vouched trust for a small blessed core. Reputation = decentralized,
earned trust for everything else. Both are **signals layered on the agnostic registry**; neither changes
what the chain enforces. A consumer's `VersionReq` could eventually filter on them (e.g. "latest version
that is `std` **or** has an attestation from a reputable verifier").

## Recommendation

1. **Start with the namespace mechanism** — reserve `std` (and a short reserved set) on `RegistryName`,
   gated by a curator authority, as the MVP trust signal. Concrete, cheap, immediately useful for the
   agent audience.
2. **Layer reputation later** — once the `identity`-app `Reputation` + an attestation message exist, let
   versions carry verifier attestations and let consumers weight them.
3. **Never gate agnostic content on it.** The chain keeps enforcing only its cheap invariants; trust stays
   advisory. The hand-off is always documented in code at the point the chain declines to verify.

## Open decisions

- Who is the `std` curator — a governance DAO address, a multisig, the metagraph operator's key?
- What is the reputation source of truth — the `identity` app `Reputation`, or a registry-local score?
- Should `VersionReq` gain a trust filter (`LatestVerified`, `LatestStd`), or is that a Bridge/SDK concern?
