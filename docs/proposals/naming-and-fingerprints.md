# Human-Readable Naming & Fingerprints — RFC

**Status:** draft / design. Date: 2026-06-04. Branch: `feat/versionable-contracts`.
**Goal:** make fibers (state machines + script oracles) referenceable by humans and agents with stable,
readable handles, and produce **human-readable audit trails** — without sacrificing the determinism and
global uniqueness the ledger needs. Audience skews toward *agents* deploying contracts, but the artifacts
must be auditable by humans.

Unifies with `versionable-contracts.md` (a package name is a registry name) and complements the UUID
keying used throughout the engine.

## 0. The core lesson: Zooko's Triangle (don't fight it — split the problem)

A name can be at most **two** of {human-meaningful, globally-unique, decentralized} *in a single string*.
Petname-system theory resolves this by using **three coordinated layers**, not one name:

| Layer | Example | Properties | Needs a registry? |
|---|---|---|---|
| **Key** | `607e22ae-…` (UUID) | global, secure, **not memorable** | no — it *is* the identity |
| **Fingerprint** (deterministic mnemonic) | `thirsty-goat-bubble-quacker.machine` | global, deterministic, offline, *somewhat* memorable, **not chosen/semantic** | **no** — a pure function of the UUID |
| **Nickname** (chosen name) | `escrow.acme.machine` | **human-meaningful**, semantic, hierarchical, **owned** | **yes** — consensus registry to dedupe/own |

Your "map the UUID to a set of words" is the **Fingerprint** (Mechanism A). Your
"`battery-crypto-horse.galaxy-brain.machine`" with delegation is the **Nickname registry** (Mechanism B,
the DNS/ENS analog). Build both; they compose. Every fiber gets a fingerprint *for free*; a chosen
nickname is *optional* and *owned*.

## 1. What the real world teaches (copy / avoid)

- **DNS** — hierarchical, *delegated* zones (`label.label.tld`), ownership, TTLs. **Copy:** hierarchy +
  delegation + ownership records. **Avoid:** central registrars, renewal/expiry economics as a core
  primitive, squatting with no recourse.
- **ENS (Ethereum Name Service)** — the closest precedent: names are on-chain, **owned as transferable
  records**, hierarchical subdomains (owner of `acme` controls `*.acme`), *resolvers* (indirection from
  name → target), and **reverse records** (address → canonical name) for display/audit. **Copy:** on-chain
  ownership as the Zooko resolver, subdomain delegation, reverse resolution for audit trails, resolver
  indirection so a name can be re-pointed.
- **BIP39** — entropy → ordered words from a fixed 2048-word list (11 bits/word) **with a checksum**, so a
  typo is detectable. **Copy:** deterministic bytes→words + checksum. **Note:** some BIP39 words are
  obscure; a curated list reads better.
- **Proquint** — maps every 16 bits to a pronounceable 5-char quintet (`lusab`, `babad`) via a *fixed
  consonant/vowel grammar* — **no wordlist asset, fully algorithmic, bijective, reversible**. **Copy:** the
  zero-dependency option when curating a wordlist is undesirable.
- **what3words** — deterministic fixed-size 3-word encoding of a huge space. **Copy:** determinism +
  fixed size. **Avoid:** proprietary, ~46 bits only (too small for a 122-bit UUID without truncation).
- **Docker/Heroku handles** (`nostalgic_lovelace`) — auto-assigned `adjective_noun`. **Copy:** friendly
  auto-handles for things nobody bothered to name. **Note:** theirs are random (collision-by-retry), ours
  should be *deterministic from the UUID* so they're stable and offline-derivable.
- **Petname systems / Zooko** — the theory above. **Copy:** the three-layer split.

## 2. Mechanism A — the Fingerprint (deterministic UUID → mnemonic; no registry)

A pure, total, offline function: `fingerprint(uuid, kind) : String`. Properties:
- **Deterministic + offline** — anyone (human, agent, light client) computes it from the UUID alone; no
  consensus lookup. Stable forever.
- **Checksummed** — include a checksum syllable/word (BIP39-style) so a mistyped handle in an audit log is
  detectable, not silently resolved to the wrong fiber.
- **Kind-suffixed** — append the fiber kind as a "TLD": `…​.machine` (state machine) / `…​.script`
  (oracle). Deterministic from the record; disambiguates at a glance and matches your intuition.
- **Sizing** — a UUID is 122 bits. Two honest options:
  - **Reversible (bijective) full encoding** — encode all 122 bits → ~8 proquints or ~11 wordlist words.
    Collision-free, decodable back to the UUID with no registry. Longer.
  - **Short fingerprint** — encode a truncated hash of the UUID (e.g. 44–64 bits → 4–6 words) for casual
    reference; not collision-free at scale, so it's a *display aid*, and exact resolution still uses the
    full UUID or the registry. (Like a git short-hash.)
  Recommendation: ship the **reversible full encoding** as the canonical fingerprint, and allow a
  **short prefix** for display.
- **Encoding scheme — two implementable choices** (this is the one fixed-asset decision; see §6):
  - **Proquint** (zero asset): `lusab-babad-gutih-tugad.machine`. Fully algorithmic (fixed 16-consonant ×
    4-vowel tables), bijective, reversible, pronounceable. Implementable *today* with no wordlist file.
  - **Curated wordlist** (real words, more memorable): `thirsty-goat-bubble-quacker.machine`. Needs a fixed,
    vetted, deduplicated, homophone-free wordlist embedded as a protocol resource (BIP39-English or
    EFF-large-diceware ≈ 7776 words ≈ 12.9 bits/word). More fun/memorable; requires sourcing the asset.
- **Determinism caveat** — the grammar/wordlist is a **fixed, versioned protocol asset**. Changing it
  re-labels every fiber → treat it as set-once (a `fingerprintScheme` version constant). Do not hand-author
  a wordlist casually; a wrong/edited word silently breaks decoding.

This layer is a self-contained pure utility — **no on-chain state, no schema change** → a safe, revertable
first slice that immediately yields readable audit handles.

## 3. Mechanism B — the Name Registry (chosen nicknames; top-level, on-chain)

The DNS/ENS analog: an on-chain, owned, hierarchical namespace mapping chosen names → targets.

```scala
final case class Label(value: String)                  // one dot-separated segment, e.g. "acme"
final case class Name(labels: List[Label], tld: NameTld)   // escrow.acme + .machine

sealed trait NameTarget
object NameTarget {
  final case class Fiber(uuid: UUID) extends NameTarget          // leaf: resolves to a machine/oracle
  final case class Delegation(zoneOwner: Set[Address]) extends NameTarget  // node: owner controls children
}

final case class NameRecord(
  name:     Name,
  owner:    Set[Address],
  target:   NameTarget,
  resolver: Option[UUID] = None,   // ENS-style indirection: re-point without re-registering
  registeredAt: SnapshotOrdinal
)
```

- **Hierarchical delegation (ENS subdomains).** Owner of `acme.machine` controls `*.acme.machine`, so
  `escrow.acme.machine` and `battery-crypto-horse.galaxy-brain.machine` are created by the parent-zone
  owner. Resolution walks labels right-to-left.
- **TLDs = fiber kind** (`.machine`, `.script`) plus `.package` for versioned packages (unifies with the
  versioning registry — a `PackageName` *is* a `Name` under `.package`). Reserve the TLD set in-protocol.
- **Reverse records (audit trails).** A canonical `UUID → Name` reverse map (owner-set, ENS-style) so logs
  render `escrow.acme.machine` instead of a raw UUID. If unset, the audit trail falls back to the
  Mechanism-A **fingerprint** — so *every* entry is human-readable, registered or not.
- **Anti-squatting.** Default to **owner-namespacing at the root** (you can only register under a label you
  own / derived from your address), the same lesson as the versioning RFC; optionally stake/auction for
  premium global roots later. Avoid DNS-style expiry churn unless there's a clear reason.
- **Resolution is deterministic** — reads consensus state at a fixed `SnapshotOrdinal`; same machinery as
  the package registry; record the resolved `(name → UUID, version)` in receipts for replay/audit.
- **Versioned references unify naming + versioning:** `escrow.acme.machine@^1.2` = name + `VersionReq`.

## 4. How they compose (the agent + audit story)

- **Deploy:** an agent creates a fiber by UUID; it *immediately* has a fingerprint
  (`thirsty-goat-bubble-quacker.machine`) with no registry interaction.
- **Name (optional):** the owner registers `escrow.acme.machine → uuid` for a semantic handle.
- **Reference:** agents/humans use any of UUID, fingerprint, or nickname; all resolve to the UUID. Triggers,
  oracle calls, and dependencies accept a `FiberRef = ByUuid | ByFingerprint | ByName(+VersionReq)`.
- **Audit trail:** every log line renders `nickname (fingerprint)` — e.g.
  `escrow.acme.machine (thirsty-goat-bubble-quacker)` — or just the fingerprint if unnamed. Never a bare
  UUID. The fingerprint is the checksummed, offline-verifiable anchor; the nickname is the convenience.

## 5. Determinism / consensus checklist

- Fingerprint: pure/total/offline; fixed scheme constant; checksummed.
- Registry: consensus state; resolution at a fixed ordinal; `SortedMap`; owner-gated mutations; reverse
  records owner-set; resolved names recorded in receipts. No wall-clock, no `Set`-iteration in
  consensus-visible folds.

## 6. Phasing & the one decision

1. **Fingerprint utility (Mechanism A)** — pure, no schema change, fully testable. Ships readable audit
   handles immediately. **Blocked only on the scheme choice (§ below).**
2. **Audit-trail rendering** — render fingerprints in receipts/logs (cosmetic, no consensus change).
3. **Name registry (Mechanism B)** — on-chain `NameRecord` state + register/transfer/set-reverse updates +
   `FiberRef` resolution; unify with the versioning package registry. (Schema change → same rollout
   decision as versioning.)
4. **Hierarchy / delegation + reverse records + resolvers.**

**Decision needed (fixed-once protocol asset): the fingerprint scheme.**
- **(a) Proquint** — implementable now, zero asset, bijective/pronounceable (`lusab-babad-…`), less "fun."
- **(b) Curated wordlist** — your `thirsty-goat-bubble-quacker` aesthetic; needs a vetted ~2048/7776-word
  asset sourced (BIP39-English or EFF-large) — should not be hand-authored (a wrong word silently breaks
  decoding). Recommendation: **start with proquint** (so the fingerprint layer ships now and the scheme is
  proven end-to-end), and add a wordlist scheme as `fingerprintScheme = v2` once a list is sourced —
  schemes are versioned, so both can coexist for display.

## 7. Open decisions

- Fingerprint scheme: proquint-now vs wordlist (and which list)? (§6)
- Anti-squatting at the root: owner-namespaced (recommended) vs stake/auction for premium names?
- TLD set: just `.machine`/`.script`/`.package`, or extensible?
- Reverse-record authority: fiber owner sets its own canonical name (recommended) vs registry-wide policy.
