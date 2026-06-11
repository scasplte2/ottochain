# Versionable Contracts — RFC

**Status:** draft / design. Date: 2026-06-04. Branch: `feat/versionable-contracts`.
**Goal:** make deployed JSON-Logic programs (state-machine fibers and script oracles) behave like
**versioned software libraries** — interfaces migrate over time, functionality is deprecated and added,
callers pick a *specific version* or *latest*, and inter-contract dependencies resolve like package deps
with a lockfile. All while preserving metagraph consensus determinism.

Companion: `docs/TOPOS-FIBER-CATEGORICAL-ASSESSMENT.md` (the theory this operationalizes) and
`docs/proposals/jlvm-engine-foundations.md` (the engine refactors that make this clean).

## 0. Today (baseline)

A program is a `StateMachineDefinition` (or oracle `scriptProgram`) **embedded inline** in the fiber
record at creation and never changed — *immutable by absence of any update that edits it*. There is no
`version` field, no definition hash, no registry, no migration, and `dependencies: Set[UUID]` points at
**specific running instances**, not at versioned interfaces. To change behavior you create a brand-new
fiber with a new UUID, orphaning callers. (See `models/.../schema/Records.scala`, `Updates.scala`,
`fiber/Transition.scala:19`.)

## 1. Requirements (from the ask)

1. **Interface migration over time** — the accepted events (SM) / methods (oracle) can change across versions.
2. **Deprecate + add** — mark functionality deprecated (still callable, flagged) and introduce new functionality.
3. **Caller-chosen version** — a caller targets an *exact* version, a *range* (latest-compatible), or *latest*.
4. **Deps like software libs** — a contract declares dependencies on *named, versioned* packages, resolved
   deterministically, with a recorded **lockfile** for reproducibility.

## 2. Design principles

- **Determinism is non-negotiable.** Every resolution (version selection, dependency binding, migration)
  must be a pure function of consensus state at a fixed `SnapshotOrdinal`. No wall-clock, no `Set`
  iteration order (use `SortedMap`/ordered traversal — see foundations doc §determinism).
- **Pin-then-resolve.** A running instance always pins the *exact* `DefinitionHash` it executes (so its
  behavior is fixed and replayable). Re-resolution happens only at explicit, audited points (upgrade, or
  an opt-in auto-upgrade policy) — never silently mid-life.
- **Additive & backward-compatible rollout.** Existing inline-definition fibers must keep working
  unchanged; model them as an *anonymous, single-version package* (§9). Schema changes are greenfield-ok
  on a fresh dev cluster but the published metagraph exists — gate behind a version flag / fresh genesis.
- **Semver is the compatibility contract**, enforced structurally, not by convention (§4).

## 3. Content-addressed definitions

Introduce `DefinitionHash = Hash` computed by canonical-hashing a definition (reuse metakit's
canonicalize + the existing `computeDigest`/`JsonBinaryHasher`). A definition becomes an **immutable,
content-addressed artifact** — the "compiled library blob." Two identical definitions share a hash;
auditors and light clients reference programs by hash. (This can later sit in the in-flight authenticated
trie / state-roots work — see `feat/metagraph-phase1-state-roots`.)

## 4. The Package registry (new on-chain state)

A `ContractPackage` is the versioned home of a program lineage:

```scala
final case class PackageName(value: String)        // e.g. "escrow", namespaced by owner to prevent squatting
final case class SemVer(major: Int, minor: Int, patch: Int)

sealed trait VersionStatus
object VersionStatus { case object Active; case object Deprecated; case object Yanked }
//  Active     = selectable, recommended
//  Deprecated = still resolvable + runnable, flagged in receipts/queries (soft-remove)
//  Yanked     = not selectable for NEW instances/calls; existing pinned instances keep running (hard-stop new uptake)

final case class PackageVersion(
  version:      SemVer,
  defHash:      DefinitionHash,
  kind:         FiberKind,                 // StateMachine | Script
  interface:    InterfaceSurface,          // §5
  status:       VersionStatus,
  publishedAt:  SnapshotOrdinal,
  migrationsFrom: SortedMap[SemVer, MigrationSpec]  // §7: how to arrive AT this version from older ones
)

final case class ContractPackage(
  name:     PackageName,
  owners:   Set[Address],                  // who may publish new versions / change status
  versions: SortedMap[SemVer, PackageVersion]
)
```

Stored as a new `SortedMap[PackageName, ContractPackage]` in `CalculatedState`, with a corresponding
hash commit added to `OnChain` (mirrors the existing `FiberCommit` pattern via `DataStateOps`).

**New updates:** `PublishVersion(name, version, definition, interface, migrationsFrom)`,
`SetVersionStatus(name, version, status)` (deprecate/yank), owner-gated like fiber ops.

### Semver discipline (structurally enforced at `PublishVersion`)
- **patch** (`x.y.Z`): guard/effect logic changes only; **same interface, same state shape** → state
  migration is identity; auto-applicable.
- **minor** (`x.Y.0`): **additive** interface (new events/methods/states), no removals, state shape
  backward-compatible → old callers unaffected; instances may upgrade freely.
- **major** (`X.0.0`): breaking interface or state-shape change → requires an explicit `MigrationSpec`;
  callers pinned to old majors are *not* auto-moved.

The validator checks the declared bump against the actual interface/state-shape diff and rejects a
mislabeled version (e.g. removing an event under a minor bump).

## 5. Interface surface (the migratable interface)

```scala
sealed trait Member { def name: String; def status: MemberStatus; def payloadSchema: Option[JsonLogicValue] }
final case class EventMember(name: String, status: MemberStatus, payloadSchema: Option[...]) extends Member   // SM
final case class MethodMember(name: String, status: MemberStatus, argsSchema: Option[...])  extends Member   // oracle
sealed trait MemberStatus { case object Active; case object Deprecated }

final case class InterfaceSurface(members: SortedMap[String, Member])
```

A version *declares* the events/methods it accepts and their status. **Deprecation** = a member with
`MemberStatus.Deprecated` (still dispatched, but the receipt is flagged and queries can surface a
warning). **Adding** = a new member in a minor/major bump. This is the "interface migrated over time."

## 6. Instances reference a package, and pin a resolution

`StateMachineFiberRecord` / `ScriptFiberRecord` change from carrying an inline `definition` to:

```scala
packageRef:   PackageRef,        // PackageRef(name, VersionReq) — what the instance TARGETS
resolvedHash: DefinitionHash,    // the EXACT definition it is currently running (pinned)
resolvedVer:  SemVer,            // the version that hash corresponds to
upgradePolicy: UpgradePolicy     // Manual (default) | AutoWithin(SemVerRange)
```

The instance executes `resolvedHash` deterministically. `packageRef` records intent so an upgrade can
re-resolve. (Inline definitions remain expressible via §9.)

## 7. Migration = a deterministic, behavior-aware state transform

```scala
final case class MigrationSpec(
  from:        SemVer,
  to:          SemVer,
  stateMigrate: JsonLogicExpression,   // oldStateData -> newStateData (gas-metered like any eval)
  eventRemap:   SortedMap[String, String] = SortedMap.empty,  // optional rename of in-flight/queued events
  breaking:     Boolean                // false => publisher asserts behavior-preserving (checkable, §below)
)
```

New update `UpgradeFiber(fiberId, targetVersionReq)`:
1. resolve target version against the registry at the current ordinal (§8),
2. run `stateMigrate` on the instance's `stateData` (charged gas, atomic — abort on failure),
3. validate migrated state against the new definition's initial-state invariants,
4. repin `resolvedHash`/`resolvedVer`, record the migration + resolved versions in the receipt.

**The commute law (the categorical payoff).** For a *non-breaking* (`breaking = false`) migration the
publisher asserts, and the test-kit verifies:

> `migrate(step(s, e)) == step'(migrate(s), remap(e))` — *migrate-then-step equals step-then-migrate*.

This is exactly "the upgrade is a natural transformation / a behavioral simulation." It is enforced as a
**publish-time property test** in the SDK test-kit, and optionally as an **admission-time spot-check**
(run the migration on the instance's current state and confirm the new definition accepts it). For
`breaking = true` (major) versions, behavior change is allowed but the flag forces callers to opt in.

## 8. Version selection (specific vs latest) — deterministic resolution

```scala
sealed trait VersionReq
object VersionReq {
  final case class Exact(v: SemVer)        extends VersionReq   // "1.2.3"
  final case class Caret(v: SemVer)        extends VersionReq   // "^1.2.0" — latest compatible (same major)
  final case class Tilde(v: SemVer)        extends VersionReq   // "~1.2.0" — latest patch within minor
  case object Latest                       extends VersionReq   // highest Active version
  final case class PinnedHash(h: DefinitionHash) extends VersionReq // exact artifact, version-agnostic
}

def resolve(pkg: ContractPackage, req: VersionReq, atOrdinal: SnapshotOrdinal): Either[ResolveError, PackageVersion]
```

`resolve` is a **pure function of registry state** (which is consensus state), so all nodes agree.
"Latest" = the highest `Active` (non-`Yanked`) version satisfying the constraint *as of this snapshot* —
deterministic because the registry only changes via consensus. `Yanked` versions are excluded from new
resolutions; `Deprecated` are resolvable but flagged. Callers supply a `VersionReq` when creating a fiber,
sending an event/trigger, or calling an oracle; the resolved version is **recorded in the receipt** for
audit/replay.

> Footgun guard: `Latest`/range reqs on a *running instance* do not silently re-resolve per snapshot —
> instances only move at an explicit `UpgradeFiber` (or their `AutoWithin` policy, evaluated at a defined
> point). Per-*call* resolution (triggers/oracle calls) resolves at call time. This separates "what an
> instance runs" (pinned, stable) from "what a caller reaches for" (resolved per call).

## 9. Dependencies as versioned libraries (+ lockfile)

Evolve `Transition.dependencies` from `Set[UUID]` to:

```scala
final case class Dependency(
  alias:   String,            // local name used in JSON-Logic context, e.g. "pricefeed"
  ref:     DepRef,            // ByPackage(PackageName, VersionReq) | ByInstance(UUID)
  mode:    DepMode            // ReadState (current behavior) | CallInterface (invoke methods/events)
)
```

- **`ByPackage` + `CallInterface`** = the "library dependency": call a versioned package's methods; a
  breaking change in the dep is caught by the `VersionReq` (you don't get silently upgraded across a major).
- **`ByInstance` + `ReadState`** = today's behavior (read a specific fiber's state), preserved for explicit wiring.

**Lockfile.** On create/upgrade, the fiber records a `DependencyLock: SortedMap[String, (PackageName, SemVer, DefinitionHash)]`
— the resolved versions of all declared deps, pinned. Behavior is then reproducible and replayable
exactly like `Cargo.lock`/`package-lock.json`. Re-resolution (to pick up dep upgrades) is an explicit,
audited action, not implicit.

Resolution still reads all deps from the single `CalculatedState` at one ordinal → the dependency "sheaf"
stays consistent by construction (one global section; see assessment §3.3).

## 10. Determinism & consensus checklist (must hold)

- All resolution/migration reads consensus state at a fixed ordinal; `SortedMap` everywhere; canonical
  hashing for `DefinitionHash`; no `Set`-iteration in consensus-visible folds.
- `resolve`, `stateMigrate` eval, and dependency binding are pure/total (errors → `FailureReason`, abort).
- Receipts capture resolved versions + dep lock + migration applied (audit + replay).
- New `OnChain` commits for the package registry; `hashCalculatedState` extended to include it.

## 11. Categorical spine (why this shape, not arbitrary)

- Package version lineage = the **version poset/DAG**.
- `PackageVersion.defHash` = the presheaf's value at a version.
- `MigrationSpec` = the **functorial action** on a version morphism.
- `breaking = false` upgrade = a **natural transformation** = the §7 commute law.
- Semver = a **grading on morphisms**: iso (patch) / mono-extension (minor) / arbitrary (major).
- Deps-as-libs = the **indexed/fibered** structure; `DependencyLock` = a chosen **section**.

The math is the *spec and the laws*; the implementation is plain records + functions + property tests.
No `Topos`/`Cell`-style abstraction is introduced (see assessment §4 for why that would be ceremony).

## 12. Phased plan (each slice additive + independently testable)

1. **Content-addressing + anonymous packages (§3, §9-compat).** Add `DefinitionHash`; wrap existing inline
   definitions as single-version anonymous packages. No user-visible behavior change. Establishes the
   registry plumbing + hashing.
2. **Registry + publish/status (§4, §5).** `ContractPackage` state, `PublishVersion`/`SetVersionStatus`
   updates, semver-diff validator. Instances can now be created from a `PackageRef`.
3. **Version selection (§8).** `VersionReq` + deterministic `resolve`; create/call by version; record in receipts.
4. **Migration + upgrade (§7).** `MigrationSpec`, `UpgradeFiber`, commute-law test-kit + admission spot-check.
5. **Deps-as-libs + lockfile (§9).** `Dependency`/`DepRef`/`DepMode`, `DependencyLock`, re-resolve action.
6. **SDK + routes.** TS SDK types, publish/upgrade/resolve endpoints, version-aware queries (deprecation warnings).

## 13. Open decisions (need your call)

- **Namespacing of `PackageName`** — global names (first-come) vs owner-namespaced (`addr/name`)? Owner-namespaced avoids squatting; recommend that.
- **Who may publish a new version** — only the package's `owners`; can ownership transfer? (governance hook)
- **Auto-upgrade** — support `UpgradePolicy.AutoWithin(range)` evaluated at a snapshot boundary, or manual-only for v1? (manual-only is simpler/safer to start)
- **Reconcile with in-flight branches** — `feat/schema-refactor`, `feat/version-endpoint`, `feat/jlvm-delegation-operators`, `feat/metagraph-phase1-state-roots` may overlap; do we rebase onto one of those instead of `main`?
- **Live metagraph** — published metagraph exists on integrationnet; ship behind fresh genesis / a feature flag, or treat as a new-chain feature?
