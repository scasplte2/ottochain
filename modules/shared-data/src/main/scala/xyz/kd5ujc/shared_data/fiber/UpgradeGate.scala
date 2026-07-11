package xyz.kd5ujc.shared_data.fiber

import cats.syntax.show._

import io.constellationnetwork.metagraph_sdk.json_logic.MapValue
import io.constellationnetwork.schema.address.Address

import xyz.kd5ujc.schema.fiber._
import xyz.kd5ujc.schema.registry._
import xyz.kd5ujc.schema.{CalculatedState, Records}

/**
 * The version & compatibility-family migration gate (fiber-policy.md `version-compat-family` stream). One
 * entry consulted at the top of [[FiberEngine]]'s `migrateStateMachine`, BEFORE the conformance gate, with the
 * OLD record + NEW definition/binding + full [[CalculatedState]] + the VERIFIED signer addresses of the
 * `UpgradeFiber` update in scope. Every check FAILS CLOSED: unresolved / missing / malformed ⇒ DENY.
 *
 * The order is load-bearing:
 *   1. [[gateByUpgradePolicy]] runs against the OLD policy — you must satisfy today's constitution to migrate
 *      AT ALL (Immutable denies first, so you can never escape it; Governed needs the authority's consent;
 *      AppendOnly requires an additive schema delta).
 *   2. tighten-only (`FiberPolicy.tightens`) is enforced at the migrate site itself — it forbids LOOSENING the
 *      lattice (e.g. Governed→Arbitrary in one hop).
 *   3. [[compatBridge]] enforces the OLD definition's declared bridge window.
 *
 * The authority and the registry id for `Governed` are read from the OLD (hash-pinned) policy ONLY, never from
 * `newDefinition` — this closes the self-authorizing `Role(registryFiberId = attacker-fiber)` hole (I3).
 *
 * Prior art: Aptos `upgrade_policy`; Sui `UpgradeCap`; CosmWasm cw2 migrate-admin; Substrate additive
 * `StorageVersion`; protobuf additive+reserved; ERC-165; OZ proxy admin.
 */
object UpgradeGate {

  /**
   * Run the full gate against the OLD record's policy. Returns `Some(reason)` on the FIRST denial (total
   * discard at the call site), or `None` to admit. `addrs` are the VERIFIED signer addresses of the update.
   */
  def check(
    old:           Records.StateMachineFiberRecord,
    newDefinition: StateMachineDefinition,
    newBinding:    SchemaBinding,
    state:         CalculatedState,
    addrs:         Set[Address]
  ): Option[FailureReason] = {
    val oldP = old.definition.policy
    firstSome(
      gateByUpgradePolicy(oldP, old, newBinding, state, addrs),
      // tighten-only is enforced at the migrate site; mirror it here too so the gate is a complete predicate
      // (the validation-tier mirror can reuse `check` without re-deriving the lattice).
      FiberPolicy
        .tightens(oldP, newDefinition.policy)
        .left
        .toOption
        .map(dial =>
          FailureReason.PolicyViolation("tighten", s"dial '$dial' may only tighten, never loosen, across a migration")
        ),
      compatBridge(oldP, newBinding)
    )
  }

  /**
   * Satisfy the OLD policy's `upgradePolicy` tier in order to migrate at all. An absent dial is
   * [[UpgradePolicy.Arbitrary]] (legacy) ⇒ always admit.
   */
  def gateByUpgradePolicy(
    oldP:       FiberPolicy,
    old:        Records.StateMachineFiberRecord,
    newBinding: SchemaBinding,
    state:      CalculatedState,
    addrs:      Set[Address]
  ): Option[FailureReason] =
    oldP.effectiveUpgradePolicy match {
      case UpgradePolicy.Arbitrary => None

      case UpgradePolicy.Immutable =>
        Some(FailureReason.PolicyViolation("upgradePolicy", "immutable: migrations are forbidden"))

      case UpgradePolicy.Governed(authority) =>
        gateGoverned(authority, state, addrs)

      case UpgradePolicy.AppendOnly =>
        gateAppendOnly(old.schemaBinding, newBinding, state)
    }

  /**
   * SCRIPT upgrade gate (audit 2026-07-07, finding L2). A script is a raw JSON-Logic program with no protobuf
   * machine shape, so only the owner-constitution tiers apply: an absent dial / [[UpgradePolicy.Arbitrary]]
   * admits (today's behaviour — any owner may re-point to any registered same-package version); [[UpgradePolicy.Immutable]]
   * denies ALL upgrades; [[UpgradePolicy.Governed]] requires the migration authority's consent (reusing
   * [[gateGoverned]] — the authority is read from the OLD record's pinned policy, never re-suppliable on
   * `UpgradeScript`, so the self-authorizing `Role(registryFiberId = attacker)` hole is closed by construction).
   * [[UpgradePolicy.AppendOnly]] needs a strict machine schema to verify an additive delta, which a script does
   * NOT have — DENIED as unsupported (rejected at create, fail-closed here too). `addrs` are the VERIFIED signer
   * addresses of the `UpgradeScript`. There is no `newDefinition` policy to tighten-check (the policy is fixed
   * at create), so only `gateByUpgradePolicy`'s equivalent runs.
   */
  def gateScriptUpgrade(
    policy: Option[UpgradePolicy],
    state:  CalculatedState,
    addrs:  Set[Address]
  ): Option[FailureReason] =
    policy.getOrElse(UpgradePolicy.default) match {
      case UpgradePolicy.Arbitrary =>
        None
      case UpgradePolicy.Immutable =>
        Some(FailureReason.PolicyViolation("upgradePolicy", "immutable: script migrations are forbidden"))
      case UpgradePolicy.Governed(authority) =>
        gateGoverned(authority, state, addrs)
      case UpgradePolicy.AppendOnly =>
        Some(
          FailureReason.PolicyViolation(
            "upgradePolicy",
            "appendOnly requires a strict machine schema and is not supported for scripts"
          )
        )
    }

  // ── Governed ────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Migration permitted only with the authority's consent. `Signers` is an ADDITIONAL, narrower gate ON TOP of
   * the L0 owner-signature requirement — an owner not in the authority set is rejected here by design (Governed
   * = "owner-signed AND authority-signed"). `Role` reads the flat per-role map (`{<address>: true}`) at
   * `roleField` from the registry fiber's state; total/fail-closed — a missing fiber / missing or non-map field
   * / non-`true` value ⇒ DENY. The registry id is pinned to the OLD metadata (passed in via `authority`), never
   * `newDefinition` (I3).
   */
  private def gateGoverned(
    authority: MigrationAuthority,
    state:     CalculatedState,
    addrs:     Set[Address]
  ): Option[FailureReason] =
    authority match {
      case MigrationAuthority.Signers(authSet) =>
        if (addrs.intersect(authSet).nonEmpty) None
        else
          Some(
            FailureReason.PolicyViolation(
              "upgradePolicy",
              "governed: no verified signer is in the migration-authority set"
            )
          )

      case MigrationAuthority.Role(registryFiberId, roleField) =>
        val permitted: Boolean =
          state.stateMachines.get(registryFiberId).map(_.stateData) match {
            case Some(MapValue(top)) =>
              top.get(roleField) match {
                case Some(MapValue(roleMap)) =>
                  // Permit iff any verified address is a KEY of the flat per-role map — the exact total/
                  // fail-closed `has`-presence semantics the SDK `signerHasRoleVia` guard relies on (a null
                  // inner map would ERROR rather than return null, so a flat map keeps the read total). The
                  // address is keyed by its Base58 `show`, matching ContextProvider's proofs projection.
                  addrs.exists(a => roleMap.contains(a.show))
                case _ => false // missing or non-map role field ⇒ DENY
              }
            case _ => false // missing registry fiber or non-map state ⇒ DENY
          }
        if (permitted) None
        else
          Some(
            FailureReason.PolicyViolation(
              "upgradePolicy",
              s"governed: no verified signer holds role '$roleField' in registry fiber $registryFiberId"
            )
          )
    }

  // ── AppendOnly ──────────────────────────────────────────────────────────────────────────────────────

  /**
   * The schema delta must be ADDITIVE. Resolve both versions' STRICT `MachineShape`s (old via `old.schemaBinding`,
   * new via `newBinding`); if EITHER is non-strict / unresolved ⇒ DENY (fail-closed — a cap you cannot verify
   * must not silently pass). Then, over the state message AND pairwise over commands (a removed command ⇒ deny),
   * assert every OLD field survives at the SAME protobuf field NUMBER with identical name/typeName/repeated/
   * optional. This is a SHAPE-monotonicity check only; the produced-state VALUE conformance stays the existing
   * gate at the migrate site (which also covers the commute-law obligation note in §6).
   */
  private def gateAppendOnly(
    oldBinding: Option[SchemaBinding],
    newBinding: SchemaBinding,
    state:      CalculatedState
  ): Option[FailureReason] =
    (strictMachineShape(oldBinding, state), strictMachineShape(Some(newBinding), state)) match {
      case (Some(oldM), Some(newM)) =>
        if (!commandsAdditive(oldM, newM))
          Some(FailureReason.PolicyViolation("upgradePolicy", "appendOnly: a command was removed in the new schema"))
        else
          firstNonAdditive(oldM, newM).map(detail =>
            FailureReason.PolicyViolation("upgradePolicy", s"appendOnly: non-additive schema delta — $detail")
          )
      case _ =>
        Some(
          FailureReason.PolicyViolation(
            "upgradePolicy",
            "appendOnly requires BOTH the from- and to-versions to be strict-bound machine schemas"
          )
        )
    }

  /** Resolve a binding to its STRICT registered MachineShape, or `None` (unbound / not found / non-strict / non-machine). */
  private def strictMachineShape(binding: Option[SchemaBinding], state: CalculatedState): Option[MachineShape] =
    binding
      .flatMap { b =>
        state.registry
          .get(b.name)
          .map(_.target)
          .collect { case RegistryTarget.SchemaPackage(lineage) => lineage }
          .flatMap(_.versions.get(b.version))
          .filter(_.strict)
          .map(_.shape)
      }
      .collect { case RegistryShape.Machine(machineShape) => machineShape }

  /** Every OLD command must survive (additively) in NEW; a removed command name ⇒ not additive. */
  private def commandsAdditive(oldM: MachineShape, newM: MachineShape): Boolean =
    oldM.commands.forall { case (name, oldCmd) =>
      newM.commands.get(name).exists(newCmd => additive(oldCmd, newCmd))
    }

  /** Returns a human-readable description of the first non-additive MESSAGE delta (state + commands), or None. */
  private def firstNonAdditive(oldM: MachineShape, newM: MachineShape): Option[String] = {
    val statePair = Option.when(!additive(oldM.stateMessage, newM.stateMessage))(
      s"state message '${oldM.stateMessage.typeName}' dropped or changed a field"
    )
    statePair.orElse {
      oldM.commands.collectFirst {
        case (name, oldCmd) if !newM.commands.get(name).exists(newCmd => additive(oldCmd, newCmd)) =>
          s"command '$name' dropped or changed a field"
      }
    }
  }

  /**
   * A NEW message is an additive successor of OLD iff EVERY old field survives at the SAME field number with an
   * identical name/typeName/repeated/optional. New fields at new numbers are allowed; changing or dropping an
   * old field at its number is NOT.
   */
  private def additive(oldM: MessageShape, newM: MessageShape): Boolean = {
    val byNum: Map[Int, FieldShape] = newM.fields.map(f => f.number -> f).toMap
    oldM.fields.forall { o =>
      byNum
        .get(o.number)
        .exists(n =>
          n.name == o.name && n.typeName == o.typeName && n.repeated == o.repeated && n.optional == o.optional
        )
    }
  }

  // ── compatBridge ────────────────────────────────────────────────────────────────────────────────────

  /**
   * If the OLD policy declares a `compatibleWith` bridge window, the NEW (verified) binding version must fall
   * inside it; otherwise unconstrained. The predecessor declares which successor versions it will bridge TO.
   */
  private def compatBridge(oldP: FiberPolicy, newBinding: SchemaBinding): Option[FailureReason] =
    oldP.dials.flatMap(_.compatibleWith).flatMap { window =>
      if (window.contains(newBinding.version)) None
      else
        Some(
          FailureReason.PolicyViolation(
            "compatibleWith",
            s"target version ${newBinding.version.render} is outside the declared bridge window"
          )
        )
    }

  /** First non-empty of a short, lazily-evaluated list of optional denials. */
  private def firstSome(options: Option[FailureReason]*): Option[FailureReason] =
    options.iterator.flatten.nextOption()
}
