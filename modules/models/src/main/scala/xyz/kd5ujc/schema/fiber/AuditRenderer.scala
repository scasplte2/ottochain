package xyz.kd5ujc.schema.fiber

import java.util.UUID

import cats.syntax.show._

import io.constellationnetwork.schema.SnapshotOrdinal

import xyz.kd5ujc.schema.registry.{RegistryName, SchemaBinding}

/**
 * Human-readable rendering of the on-chain audit trail (the [[FiberLogEntry]] log in
 * `OnChain.latestLogs`). Each fiber renders as `nickname (fingerprint)` when it has a canonical
 * reverse-record name (#29), or just its deterministic proquint [[FiberFingerprint]] otherwise — so every
 * fiber is legible whether or not it was ever named (docs/proposals/naming-and-fingerprints.md §4).
 *
 * Pure + reproducible: anyone can regenerate these lines from the log, the reverse-name map, and the
 * UUIDs, and the fingerprint round-trips back to the fiber UUID via [[FiberFingerprint.decode]].
 */
object AuditRenderer {

  /** Render an ordered list of log entries (e.g. one fiber's `OnChain.latestLogs` slice). */
  def renderAll(entries: List[FiberLogEntry], reverseNames: Map[UUID, RegistryName] = Map.empty): List[String] =
    entries.map(render(_, reverseNames))

  /** A single audit line for one log entry; each fiber renders as `nickname (fingerprint)` or fingerprint. */
  def render(entry: FiberLogEntry, reverseNames: Map[UUID, RegistryName] = Map.empty): String = entry match {
    case r: FiberLogEntry.CreationReceipt =>
      val bound = r.schemaBinding.fold("")(b => s", bound to ${binding(b)}")
      val parent = r.parentFiberId.fold("")(p => s", child of ${machine(p, reverseNames)}")
      s"[ord ${ord(r.ordinal)}] ${machine(r.fiberId, reverseNames)} created in state '${r.initialState.value}'$bound$parent"

    case r: FiberLogEntry.UpgradeReceipt =>
      val from = r.fromBinding.fold("(unbound)")(binding)
      val migrated = if (r.migrated) ", state migrated" else ""
      s"[ord ${ord(r.ordinal)}] ${machine(r.fiberId, reverseNames)} upgraded $from -> ${binding(r.toBinding)}$migrated (gas ${r.gasUsed})"

    case r: FiberLogEntry.EventReceipt =>
      val outcome =
        if (r.success) s"${r.fromState.value} -> ${r.toState.value}"
        else s"FAILED${r.errorMessage.fold("")(e => s": $e")}"
      val triggers = if (r.triggersFired > 0) s", ${r.triggersFired} trigger(s)" else ""
      s"[ord ${ord(r.ordinal)}] ${machine(r.fiberId, reverseNames)} '${r.eventName}' $outcome (gas ${r.gasUsed}$triggers)"

    case i: FiberLogEntry.OracleInvocation =>
      s"[ord ${ord(i.invokedAt)}] ${script(i.fiberId, reverseNames)} .${i.method}() by ${i.invokedBy.show} (gas ${i.gasUsed})"
  }

  private def machine(id: UUID, names: Map[UUID, RegistryName]): String = labelFor(id, FiberKind.StateMachine, names)
  private def script(id:  UUID, names: Map[UUID, RegistryName]): String = labelFor(id, FiberKind.Script, names)

  /** `nickname (fingerprint)` when the fiber has a canonical reverse name, else the bare fingerprint. */
  private def labelFor(id: UUID, kind: FiberKind, names: Map[UUID, RegistryName]): String = {
    val fingerprint = FiberFingerprint.of(id, kind)
    names.get(id).fold(fingerprint)(name => s"${name.render} ($fingerprint)")
  }

  private def binding(b: SchemaBinding): String = s"${b.name.render}@${b.version.render}"
  private def ord(o:     SnapshotOrdinal): String = o.value.value.toString
}
